package it.regmac.desktop;

import io.airlift.compress.zstd.ZstdInputStream;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Scarica Ollama (licenza MIT) dal sito ufficiale delle release, ne verifica l'impronta SHA-256 pubblicata
 * e lo estrae nella cartella dati di RegMac: nessun permesso di amministratore, nessuna installazione di sistema.
 */
@Component
public class OllamaInstaller {
  static final String BASE = "https://github.com/ollama/ollama/releases/latest/download/";
  public record Progress(String status, long total, long completed, String error, boolean done) { }

  private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(20)).build();
  private volatile Progress progress;
  private volatile Thread worker;

  public static Path home() { return Paths.dataDir().resolve("ollama"); }

  /** Pacchetto ufficiale per questo sistema (senza le varianti ROCm/MLX/Jetson). */
  public static Optional<String> asset() {
    String os = System.getProperty("os.name").toLowerCase(), arch = System.getProperty("os.arch").toLowerCase();
    boolean arm = arch.contains("aarch64") || arch.contains("arm64");
    if (os.contains("mac")) return Optional.of("ollama-darwin.tgz");
    if (os.contains("win")) return Optional.of(arm ? "ollama-windows-arm64.zip" : "ollama-windows-amd64.zip");
    if (os.contains("linux")) return Optional.of(arm ? "ollama-linux-arm64.tar.zst" : "ollama-linux-amd64.tar.zst");
    return Optional.empty();
  }

  /** Eseguibile scaricato da RegMac, se presente. */
  public static Optional<Path> installedBinary() {
    Path cur = home().resolve("corrente");
    if (!Files.isDirectory(cur)) return Optional.empty();
    try (Stream<Path> w = Files.walk(cur, 4)) {
      return w.filter(p -> p.getFileName().toString().equals(isWindows() ? "ollama.exe" : "ollama") && Files.isRegularFile(p)).findFirst();
    } catch (IOException e) { return Optional.empty(); }
  }

  public Progress progress() { return progress; }
  public boolean running() { return worker != null && worker.isAlive(); }

  /** Avvia il download in sottofondo; al termine chiama onDone (es. avvio di Ollama e download dei modelli). */
  public synchronized void install(Runnable onDone) {
    if (running()) return;
    String asset = asset().orElseThrow(() -> new IllegalStateException("Sistema operativo non supportato per l'installazione automatica di Ollama"));
    progress = new Progress("Preparazione", 0, 0, null, false);
    worker = Thread.ofPlatform().daemon().start(() -> {
      try {
        Files.createDirectories(home());
        Path archive = home().resolve(asset);
        String expected = expectedSha(asset);
        download(BASE + asset, archive);
        progress = new Progress("Verifica dell'impronta", 1, 1, null, false);
        String actual = sha256(archive);
        if (!actual.equalsIgnoreCase(expected)) throw new IOException("Impronta SHA-256 non corrispondente: download danneggiato");
        progress = new Progress("Estrazione", 1, 1, null, false);
        Path tmp = home().resolve("estrazione");
        deleteTree(tmp); Files.createDirectories(tmp);
        extract(archive, tmp);
        Path cur = home().resolve("corrente");
        deleteTree(cur);
        Files.move(tmp, cur);
        Files.deleteIfExists(archive);
        Path bin = installedBinary().orElseThrow(() -> new IOException("Eseguibile di Ollama non trovato nel pacchetto"));
        bin.toFile().setExecutable(true);
        progress = new Progress("Installato", 1, 1, null, true);
        if (onDone != null) onDone.run();
      } catch (Exception e) {
        progress = new Progress("Errore", 0, 0, "Installazione di Ollama non riuscita: " + e.getMessage(), true);
      }
    });
  }

  String expectedSha(String asset) throws Exception {
    var res = http.send(HttpRequest.newBuilder(URI.create(BASE + "sha256sum.txt")).timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    if (res.statusCode() != 200) throw new IOException("Elenco delle impronte non disponibile (HTTP " + res.statusCode() + ")");
    for (String line : res.body().split("\n")) {
      String[] p = line.trim().split("\\s+");
      if (p.length == 2 && p[1].replaceFirst("^\\./", "").equals(asset)) return p[0];
    }
    throw new IOException("Impronta di " + asset + " non trovata");
  }

  void download(String url, Path target) throws Exception {
    var res = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofInputStream());
    if (res.statusCode() != 200) throw new IOException("Download non riuscito (HTTP " + res.statusCode() + ")");
    long total = res.headers().firstValueAsLong("Content-Length").orElse(0);
    Path part = target.resolveSibling(target.getFileName() + ".part");
    try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(part)) {
      byte[] buf = new byte[1 << 16]; long done = 0; int n; long last = 0;
      while ((n = in.read(buf)) > 0) {
        out.write(buf, 0, n); done += n;
        if (done - last > (1 << 20)) { progress = new Progress("Download di Ollama", total, done, null, false); last = done; }
      }
    }
    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
  }

  static void extract(Path archive, Path dir) throws Exception {
    String name = archive.getFileName().toString();
    if (name.endsWith(".zip")) { unzip(archive, dir); return; }
    // .tgz e .tar.zst: decompressione in Java e lettura dell'archivio tar con il comando di sistema (presente su macOS e Linux)
    Process tar = new ProcessBuilder("tar", "-xf", "-", "-C", dir.toString()).redirectErrorStream(true).start();
    try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive));
         InputStream in = name.endsWith(".zst") ? new ZstdInputStream(raw) : new java.util.zip.GZIPInputStream(raw);
         OutputStream out = tar.getOutputStream()) {
      in.transferTo(out);
    }
    String log = new String(tar.getInputStream().readAllBytes());
    if (tar.waitFor() != 0) throw new IOException("Estrazione non riuscita: " + log.strip());
  }

  static void unzip(Path archive, Path dir) throws IOException {
    try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
      for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
        Path out = dir.resolve(e.getName()).normalize();
        if (!out.startsWith(dir)) throw new IOException("Percorso non valido nell'archivio: " + e.getName());
        if (e.isDirectory()) { Files.createDirectories(out); continue; }
        Files.createDirectories(out.getParent());
        Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  static String sha256(Path p) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    try (InputStream in = Files.newInputStream(p)) { byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n); }
    return HexFormat.of().formatHex(md.digest());
  }

  static void deleteTree(Path p) throws IOException {
    if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
    try (Stream<Path> w = Files.walk(p)) { w.sorted(java.util.Comparator.reverseOrder()).forEach(x -> x.toFile().delete()); }
  }

  static boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("win"); }
}
