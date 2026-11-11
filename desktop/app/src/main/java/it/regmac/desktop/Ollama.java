package it.regmac.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Client dell'AI locale: verifica, avvio, download dei modelli, chat con output JSON vincolato ed embedding. */
@Component
public class Ollama {
  /**
   * RegMac usa sempre un proprio Ollama, su una porta dedicata e con i modelli nella propria cartella dati:
   * così alla disinstallazione si possono eliminare senza toccare un Ollama già presente sul computer.
   */
  public static final String BASE = System.getProperty("regmac.ollama", "http://127.0.0.1:11435");
  /** Modelli necessari: decisioni (chat) e indice semantico (embedding). */
  public static final String CHAT = "qwen3:8b", EMBED = "nomic-embed-text";
  public static final List<Model> REQUIRED = List.of(
      new Model(CHAT, "Decisioni sui requisiti e profilo della macchina", 5_200_000_000L),
      new Model(EMBED, "Indice semantico dei documenti", 274_000_000L));
  public record Model(String name, String role, long approxBytes) { }
  public record Pull(String model, String status, long total, long completed, String error, boolean done) { }

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final ObjectMapper json = new ObjectMapper();
  private final Map<String, Pull> pulls = new ConcurrentHashMap<>();
  private volatile Process started;

  public Optional<String> version() {
    try {
      var r = http.send(HttpRequest.newBuilder(URI.create(BASE + "/api/version")).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
      return r.statusCode() == 200 ? Optional.of(json.readTree(r.body()).path("version").asText()) : Optional.empty();
    } catch (Exception e) { return Optional.empty(); }
  }

  public Set<String> installed() {
    Set<String> out = new HashSet<>();
    try {
      var r = http.send(HttpRequest.newBuilder(URI.create(BASE + "/api/tags")).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
      for (JsonNode m : json.readTree(r.body()).path("models")) {
        String n = m.path("name").asText();
        out.add(n);
        if (n.endsWith(":latest")) out.add(n.substring(0, n.length() - 7));
      }
    } catch (Exception ignored) { }
    return out;
  }

  /** Percorsi tipici dell'eseguibile di Ollama sui tre sistemi. */
  public Optional<String> binary() {
    var own = OllamaInstaller.installedBinary(); // scaricato da RegMac: ha la precedenza
    if (own.isPresent()) return Optional.of(own.get().toString());
    if (Boolean.getBoolean("regmac.ignoreSystemOllama")) return Optional.empty(); // solo per i test
    String home = System.getProperty("user.home"), local = System.getenv("LOCALAPPDATA");
    List<String> c = new ArrayList<>(List.of("/opt/homebrew/bin/ollama", "/usr/local/bin/ollama", "/usr/bin/ollama",
        "/Applications/Ollama.app/Contents/Resources/ollama", home + "/.local/bin/ollama"));
    if (local != null) c.add(local + "\\Programs\\Ollama\\ollama.exe");
    String path = System.getenv("PATH");
    if (path != null) for (String d : path.split(File.pathSeparator)) { c.add(d + File.separator + "ollama"); c.add(d + File.separator + "ollama.exe"); }
    return c.stream().filter(p -> new File(p).canExecute()).findFirst();
  }

  /** Archivio dei modelli di RegMac (dentro la cartella dati). */
  public static Path modelsDir() { return OllamaInstaller.home().resolve("modelli"); }

  /** Archivio comune di un Ollama già installato (variabile OLLAMA_MODELS oppure ~/.ollama/models). */
  static Path sharedModelsDir() {
    String env = System.getenv("OLLAMA_MODELS");
    return env != null && !env.isBlank() ? Path.of(env) : Path.of(System.getProperty("user.home"), ".ollama", "models");
  }

  /**
   * Se i modelli necessari sono già nell'archivio comune, li collega in quello di RegMac (collegamenti fisici:
   * nessuno spazio in più e, eliminando la copia di RegMac, l'originale resta). Se il collegamento non è possibile
   * (dischi diversi) li copia.
   */
  void importShared() {
    Path from = sharedModelsDir(), to = modelsDir();
    if (!Files.isDirectory(from) || from.toAbsolutePath().normalize().equals(to.toAbsolutePath().normalize())) return;
    for (var m : REQUIRED) {
      String[] nt = m.name().split(":");
      String name = nt[0], tag = nt.length > 1 ? nt[1] : "latest";
      Path rel = Path.of("manifests", "registry.ollama.ai", "library", name, tag), src = from.resolve(rel), dst = to.resolve(rel);
      if (!Files.isRegularFile(src) || Files.exists(dst)) continue;
      try {
        JsonNode man = json.readTree(src.toFile());
        List<String> digests = new ArrayList<>();
        digests.add(man.path("config").path("digest").asText());
        for (JsonNode l : man.path("layers")) digests.add(l.path("digest").asText());
        for (String d : digests) {
          if (d.isBlank()) continue;
          Path b = from.resolve("blobs").resolve(d.replace(':', '-')), t = to.resolve("blobs").resolve(d.replace(':', '-'));
          if (!Files.isRegularFile(b)) throw new IOException("blob mancante " + d);
          if (Files.exists(t)) continue;
          Files.createDirectories(t.getParent());
          try { Files.createLink(t, b); } catch (Exception e) { Files.copy(b, t); }
        }
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst);
      } catch (Exception ignored) { /* se qualcosa manca il modello verrà scaricato */ }
    }
  }

  /** Avvio automatico all'apertura di RegMac, se Ollama è disponibile. */
  @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
  public void autoStart() { if (binary().isPresent()) Thread.ofVirtual().start(this::start); }

  /** Avvia il proprio "ollama serve" (porta dedicata, modelli nella cartella di RegMac, 4 richieste in parallelo). */
  public synchronized boolean start() {
    if (version().isPresent()) return true;
    var bin = binary();
    if (bin.isEmpty()) return false;
    try {
      Files.createDirectories(modelsDir());
      importShared();
      ProcessBuilder pb = new ProcessBuilder(bin.get(), "serve").redirectErrorStream(true)
          .redirectOutput(Paths.dataDir().resolve("logs/ollama.log").toFile());
      Paths.dataDir().resolve("logs").toFile().mkdirs();
      pb.environment().put("OLLAMA_NUM_PARALLEL", "4");
      pb.environment().put("OLLAMA_HOST", URI.create(BASE).getHost() + ":" + URI.create(BASE).getPort());
      pb.environment().put("OLLAMA_MODELS", modelsDir().toString());
      started = pb.start();
      Runtime.getRuntime().addShutdownHook(new Thread(() -> { if (started != null) started.destroy(); }));
      for (int i = 0; i < 40; i++) { Thread.sleep(250); if (version().isPresent()) return true; }
    } catch (Exception ignored) { }
    return version().isPresent();
  }

  public boolean startedByUs() { return started != null && started.isAlive(); }

  /** Ferma il proprio Ollama ed elimina modelli e Ollama scaricati da RegMac; restituisce i byte liberati. */
  public synchronized long deleteAll() throws Exception {
    if (started != null) { started.destroy(); started.waitFor(10, java.util.concurrent.TimeUnit.SECONDS); if (started.isAlive()) started.destroyForcibly(); started = null; }
    pulls.clear();
    long size = sizeOf(OllamaInstaller.home());
    OllamaInstaller.deleteTree(OllamaInstaller.home());
    return size;
  }

  /**
   * Spazio occupato davvero da Ollama e modelli di RegMac: i file collegati a un archivio comune
   * (collegamenti fisici, più di un nome) non contano, perché eliminandoli lo spazio non si libera.
   */
  public static long sizeOf(Path dir) {
    if (!Files.exists(dir)) return 0;
    try (var w = Files.walk(dir)) { return w.filter(Files::isRegularFile).filter(p -> links(p) <= 1).mapToLong(p -> p.toFile().length()).sum(); } catch (IOException e) { return 0; }
  }

  static int links(Path p) {
    try { return ((Number) Files.getAttribute(p, "unix:nlink")).intValue(); } catch (Exception e) { return 1; } // Windows: non disponibile
  }

  public Map<String, Pull> pulls() { return pulls; }

  /** Scarica un modello in background aggiornando l'avanzamento. */
  public void pull(String model) {
    Pull cur = pulls.get(model);
    if (cur != null && !cur.done()) return;
    pulls.put(model, new Pull(model, "avvio", 0, 0, null, false));
    Thread.ofVirtual().start(() -> {
      Map<String, long[]> layers = new LinkedHashMap<>();
      try {
        var req = HttpRequest.newBuilder(URI.create(BASE + "/api/pull")).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("model", model, "stream", true)))).build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (var br = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
          String line;
          while ((line = br.readLine()) != null) {
            JsonNode n = json.readTree(line);
            if (n.has("error")) { pulls.put(model, new Pull(model, "errore", 0, 0, n.get("error").asText(), true)); return; }
            if (n.has("digest") && n.has("total")) layers.put(n.get("digest").asText(), new long[]{n.path("total").asLong(), n.path("completed").asLong()});
            long t = layers.values().stream().mapToLong(a -> a[0]).sum(), c = layers.values().stream().mapToLong(a -> a[1]).sum();
            String st = n.path("status").asText();
            pulls.put(model, new Pull(model, st, t, c, null, "success".equals(st)));
          }
        }
        if (!pulls.get(model).done()) pulls.put(model, new Pull(model, installed().contains(model) ? "success" : "interrotto", 0, 0, null, true));
      } catch (Exception e) {
        pulls.put(model, new Pull(model, "errore", 0, 0, "Download non riuscito: " + e.getMessage(), true));
      }
    });
  }

  /** Chat con output vincolato a uno schema JSON; temperatura 0 e seme fisso per risultati ripetibili. */
  public JsonNode chat(String prompt, Map<String, Object> schema) throws Exception {
    Map<String, Object> body = Map.of("model", CHAT, "stream", false, "think", false, "format", schema,
        "options", Map.of("temperature", 0, "seed", 42, "num_ctx", 4096),
        "messages", List.of(Map.of("role", "user", "content", prompt)));
    var req = HttpRequest.newBuilder(URI.create(BASE + "/api/chat")).timeout(Duration.ofMinutes(5))
        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    var res = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (res.statusCode() != 200) throw new IllegalStateException("Ollama: " + res.body());
    return json.readTree(json.readTree(res.body()).path("message").path("content").asText());
  }

  /** Embedding normalizzati (nomic-embed-text richiede i prefissi di ricerca). */
  public List<float[]> embed(List<String> texts, boolean query) throws Exception {
    List<float[]> out = new ArrayList<>();
    for (int i = 0; i < texts.size(); i += 64) {
      List<String> batch = texts.subList(i, Math.min(texts.size(), i + 64)).stream().map(t -> (query ? "search_query: " : "search_document: ") + t).toList();
      var req = HttpRequest.newBuilder(URI.create(BASE + "/api/embed")).timeout(Duration.ofMinutes(5))
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("model", EMBED, "input", batch)))).build();
      var res = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() != 200) throw new IllegalStateException("Ollama embed: " + res.body());
      for (JsonNode v : json.readTree(res.body()).path("embeddings")) {
        float[] f = new float[v.size()]; double norm = 0;
        for (int k = 0; k < f.length; k++) { f[k] = (float) v.get(k).asDouble(); norm += f[k] * f[k]; }
        norm = Math.sqrt(norm);
        for (int k = 0; k < f.length; k++) f[k] /= (float) norm;
        out.add(f);
      }
    }
    return out;
  }

  public static double cos(float[] a, float[] b) { double s = 0; for (int i = 0; i < a.length; i++) s += a[i] * b[i]; return s; }
}
