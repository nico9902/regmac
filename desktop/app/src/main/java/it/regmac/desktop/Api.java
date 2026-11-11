package it.regmac.desktop;

import it.regmac.desktop.Store.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api")
public class Api {
  private final Store store;
  private final Catalog catalog;
  private final Ollama ai;
  private final Engine engine;
  private final Views views;
  private final ReportPdf pdf;
  private final OllamaInstaller installer;

  public Api(Store store, Catalog catalog, Ollama ai, Engine engine, Views views, ReportPdf pdf, OllamaInstaller installer) {
    this.store = store; this.catalog = catalog; this.ai = ai; this.engine = engine; this.views = views; this.pdf = pdf; this.installer = installer;
  }

  // ---------- avvio: profilo e AI locale ----------
  @GetMapping("/status")
  public Map<String, Object> status() {
    var version = ai.version();
    Set<String> inst = version.isPresent() ? ai.installed() : Set.of();
    List<Map<String, Object>> models = new ArrayList<>();
    for (var m : Ollama.REQUIRED) {
      var p = ai.pulls().get(m.name());
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", m.name()); row.put("role", m.role()); row.put("approxBytes", m.approxBytes()); row.put("installed", inst.contains(m.name()));
      row.put("pull", p);
      models.add(row);
    }
    boolean ready = version.isPresent() && models.stream().allMatch(m -> (Boolean) m.get("installed"));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("profile", store.profile().orElse(null));
    out.put("ollama", Map.of("running", version.isPresent(), "version", version.orElse(""), "installed", version.isPresent() || ai.binary().isPresent(), "startedByApp", ai.startedByUs()));
    out.put("models", models);
    out.put("ollamaInstall", installer.progress());
    out.put("ollamaAsset", OllamaInstaller.asset().orElse(null));
    out.put("ollamaOwn", OllamaInstaller.installedBinary().isPresent());
    out.put("aiBytes", Ollama.sizeOf(OllamaInstaller.home()));
    out.put("aiDir", OllamaInstaller.home().toString());
    out.put("ready", ready);
    out.put("memoryGb", Math.round(((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize() / 1073741824.0));
    out.put("dataDir", Paths.dataDir().toString());
    out.put("os", System.getProperty("os.name"));
    out.put("schede", catalog.schedeVersion());
    out.put("version", "1.2.0");
    return out;
  }

  @PostMapping("/setup/profile")
  public Profile saveProfile(@RequestBody Profile p) {
    if (p.name() == null || p.name().isBlank()) throw new IllegalArgumentException("Il nome è obbligatorio");
    Profile n = new Profile(p.name().strip(), p.organization() == null ? "" : p.organization().strip());
    store.saveProfile(n);
    return n;
  }

  @PostMapping("/setup/start-ollama")
  public Map<String, Object> startOllama() { return Map.of("running", ai.start()); }

  /**
   * Preparazione automatica dell'AI: avvia Ollama se c'è; altrimenti lo scarica (verificato) e lo avvia;
   * poi scarica i modelli che mancano. Si può chiamare più volte: fa solo ciò che manca.
   */
  @PostMapping("/setup/auto")
  public Map<String, Object> auto() {
    Runnable models = () -> {
      if (!ai.start()) return;
      Set<String> inst = ai.installed();
      for (var m : Ollama.REQUIRED) if (!inst.contains(m.name())) ai.pull(m.name());
    };
    if (ai.version().isPresent() || ai.binary().isPresent()) Thread.ofVirtual().start(models);
    else if (!installer.running()) installer.install(models);
    return status();
  }

  /** Elimina modelli AI e Ollama scaricati da RegMac (es. prima di cestinare l'app su macOS). */
  @PostMapping("/setup/delete-ai")
  public Map<String, Object> deleteAi() throws Exception {
    if (engine.busy()) throw new IllegalStateException("C'è una pre-valutazione in corso: interrompila prima di eliminare i modelli");
    return Map.of("freedBytes", ai.deleteAll());
  }

    @PostMapping("/setup/pull")
  public Map<String, Object> pull() {
    if (ai.version().isEmpty() && !ai.start()) throw new IllegalStateException("Ollama non è in esecuzione");
    Set<String> inst = ai.installed();
    for (var m : Ollama.REQUIRED) if (!inst.contains(m.name())) ai.pull(m.name());
    return status();
  }

  // ---------- catalogo ----------
  @GetMapping("/catalog")
  public Map<String, Object> catalog() {
    return Map.of("laws", catalog.laws(), "requirements", catalog.all(), "questions", Catalog.QUESTIONS, "schede", catalog.schedeVersion());
  }

  // ---------- fascicoli ----------
  public record NewDossier(String name, String model, String serial, String year, String maker, String use) { }

  @GetMapping("/fascicoli")
  public List<Map<String, Object>> list() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Fascicolo f : store.list()) {
      var a = views.assess(f, store.results(f.id()), false);
      out.add(Map.of("fascicolo", f, "summary", a.summary(), "documents", store.docs(f.id()).size()));
    }
    return out;
  }

  @PostMapping("/fascicoli")
  public Fascicolo create(@RequestBody NewDossier d) {
    if (d.name() == null || d.name().isBlank()) throw new IllegalArgumentException("Il nome della macchina è obbligatorio");
    String id = UUID.randomUUID().toString();
    return store.save(new Fascicolo(id, d.name().strip(), s(d.model()), s(d.serial()), s(d.year()), s(d.maker()), s(d.use()), Instant.now(), Instant.now(),
        List.of(), List.of("macchina", "generale"), false, Run.idle(), null));
  }

  @GetMapping("/fascicoli/{id}")
  public Map<String, Object> get(@PathVariable String id) {
    Fascicolo f = store.get(id);
    return Map.of("fascicolo", f, "documents", store.docs(id), "assessment", views.assess(f, store.results(id), true));
  }

  @PutMapping("/fascicoli/{id}")
  public Fascicolo update(@PathVariable String id, @RequestBody NewDossier d) {
    Fascicolo f = store.get(id);
    return store.save(new Fascicolo(id, d.name() == null || d.name().isBlank() ? f.name() : d.name().strip(), s(d.model()), s(d.serial()), s(d.year()), s(d.maker()), s(d.use()),
        f.createdAt(), f.updatedAt(), f.profile(), f.tags(), f.profileConfirmed(), f.run(), f.exportedAt()));
  }

  @DeleteMapping("/fascicoli/{id}")
  public Map<String, Object> delete(@PathVariable String id) throws Exception {
    engine.stop(id);
    if (Set.of("running", "indexing").contains(store.get(id).run().state())) Thread.sleep(1500);
    store.delete(id);
    return Map.of("deleted", id);
  }

  @PostMapping("/fascicoli/{id}/restore")
  public Map<String, Object> restore(@PathVariable String id) throws Exception { store.restore(id); return Map.of("restored", id); }

  // ---------- documenti ----------
  @PostMapping("/fascicoli/{id}/documents")
  public List<Doc> upload(@PathVariable String id, @RequestParam("file") List<MultipartFile> files) throws Exception {
    store.get(id);
    List<Doc> docs = new ArrayList<>(store.docs(id));
    for (MultipartFile f : files) {
      String name = Optional.ofNullable(f.getOriginalFilename()).orElse("documento").replaceAll("[\\\\/:*?\"<>|]", "_");
      String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase() : "";
      if (!Set.of(".pdf", ".docx", ".txt", ".md").contains(ext)) throw new IllegalArgumentException(name + ": formato non supportato (PDF, DOCX, TXT, MD)");
      byte[] bytes = f.getBytes();
      String sha = Engine.sha(bytes);
      if (docs.stream().anyMatch(x -> x.sha256().equals(sha))) continue;
      String docId = sha.substring(0, 12);
      Path target = store.dir(id).resolve("documenti").resolve(docId + ext);
      Files.write(target, bytes);
      var res = TextExtractor.extract(target); // verifica subito che il file sia leggibile
      docs.add(new Doc(docId, name, docId + ext, bytes.length, res.pages(), sha, res.passages().size(), res.warning(), Instant.now()));
    }
    store.saveDocs(id, docs);
    touch(id);
    return docs;
  }

  @DeleteMapping("/fascicoli/{id}/documents/{docId}")
  public List<Doc> deleteDoc(@PathVariable String id, @PathVariable String docId) throws Exception {
    List<Doc> docs = new ArrayList<>(store.docs(id));
    for (Doc d : docs) if (d.id().equals(docId)) Files.deleteIfExists(store.dir(id).resolve("documenti").resolve(d.file()));
    docs.removeIf(d -> d.id().equals(docId));
    store.saveDocs(id, docs);
    touch(id);
    return docs;
  }

  @GetMapping("/fascicoli/{id}/documents/{docId}")
  public ResponseEntity<byte[]> openDoc(@PathVariable String id, @PathVariable String docId) throws Exception {
    Doc d = store.docs(id).stream().filter(x -> x.id().equals(docId)).findFirst().orElseThrow(() -> new NoSuchElementException("Documento non trovato"));
    byte[] b = Files.readAllBytes(store.dir(id).resolve("documenti").resolve(d.file()));
    MediaType mt = d.file().endsWith(".pdf") ? MediaType.APPLICATION_PDF : d.file().endsWith(".docx") ? MediaType.APPLICATION_OCTET_STREAM : new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8);
    return ResponseEntity.ok().contentType(mt).header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(d.name(), java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(b);
  }

  // ---------- profilo della macchina ----------
  @PostMapping("/fascicoli/{id}/profile/propose")
  public List<Answer> propose(@PathVariable String id) throws Exception {
    if (store.docs(id).isEmpty()) throw new IllegalArgumentException("Carica almeno un documento");
    List<Answer> a = engine.proposeProfile(id);
    Fascicolo f = store.get(id);
    store.save(new Fascicolo(id, f.name(), f.model(), f.serial(), f.year(), f.maker(), f.use(), f.createdAt(), f.updatedAt(), a, f.tags(), false, f.run(), f.exportedAt()));
    return a;
  }

  public record ProfileChoice(Map<String, Boolean> values) { }

  @PutMapping("/fascicoli/{id}/profile")
  public Fascicolo confirmProfile(@PathVariable String id, @RequestBody ProfileChoice c) {
    Fascicolo f = store.get(id);
    List<String> tags = new ArrayList<>(List.of("generale"));
    tags.add(Boolean.TRUE.equals(c.values().get("quasi_macchina")) ? "quasi_macchina" : "macchina");
    for (var q : Catalog.QUESTIONS) if (!q.tag().equals("quasi_macchina") && Boolean.TRUE.equals(c.values().get(q.tag()))) tags.add(q.tag());
    List<Answer> answers = new ArrayList<>();
    for (var q : Catalog.QUESTIONS) {
      Answer a = f.profile().stream().filter(x -> x.tag().equals(q.tag())).findFirst().orElse(new Answer(q.tag(), "UNKNOWN", "", null, null, false));
      answers.add(new Answer(a.tag(), a.verdict(), a.quote(), a.doc(), a.page(), Boolean.TRUE.equals(c.values().get(q.tag()))));
    }
    return store.save(new Fascicolo(id, f.name(), f.model(), f.serial(), f.year(), f.maker(), f.use(), f.createdAt(), f.updatedAt(), answers, tags, true, f.run(), f.exportedAt()));
  }

  // ---------- pre-valutazione ----------
  @PostMapping("/fascicoli/{id}/run")
  public Fascicolo run(@PathVariable String id) {
    if (!store.get(id).profileConfirmed()) throw new IllegalArgumentException("Conferma prima il profilo della macchina");
    if (store.docs(id).isEmpty()) throw new IllegalArgumentException("Carica almeno un documento");
    engine.start(id);
    return store.get(id);
  }

  @PostMapping("/fascicoli/{id}/stop")
  public Fascicolo stop(@PathVariable String id) { engine.stop(id); return store.get(id); }

  public record Review(List<String> points, Boolean confirmed, String note) { }

  @PutMapping("/fascicoli/{id}/results/{rid}")
  public Map<String, Object> review(@PathVariable String id, @PathVariable String rid, @RequestBody Review rv) {
    synchronized (store) {
      Map<String, Result> all = store.results(id);
      Result r = Optional.ofNullable(all.get(rid)).orElseThrow(() -> new NoSuchElementException("Requisito non ancora valutato"));
      List<Point> pts = new ArrayList<>(r.points());
      if (rv.points() != null) for (int i = 0; i < pts.size() && i < rv.points().size(); i++) {
        String u = rv.points().get(i); Point p = pts.get(i);
        String user = u == null || u.equals(p.answer()) ? null : (Set.of("S", "C", "I").contains(u) ? u : null);
        pts.set(i, new Point(p.text(), p.answer(), p.quote(), p.docId(), p.doc(), p.page(), p.support(), user));
      }
      all.put(rid, new Result(r.rid(), r.applicability(), r.appQuote(), r.appDoc(), r.appPage(), pts,
          rv.confirmed() != null ? rv.confirmed() : r.confirmed(), rv.note() != null ? rv.note() : r.note(), r.hash(), r.at()));
      store.saveResults(id, all);
    }
    touch(id);
    return get(id);
  }

  // ---------- report ----------
  @GetMapping("/fascicoli/{id}/report.pdf")
  public ResponseEntity<byte[]> report(@PathVariable String id) throws Exception {
    Fascicolo f = store.get(id);
    byte[] b = pdf.render(f, store.docs(id), views.assess(f, store.results(id), true), store.profile().orElse(new Profile("", "")), catalog.schedeVersion());
    Path dir = store.dir(id).resolve("report");
    Files.createDirectories(dir);
    String name = "pre-assessment-" + java.time.LocalDate.now() + ".pdf";
    Files.write(dir.resolve(name), b);
    store.save(new Fascicolo(id, f.name(), f.model(), f.serial(), f.year(), f.maker(), f.use(), f.createdAt(), f.updatedAt(), f.profile(), f.tags(), f.profileConfirmed(), f.run(), Instant.now()));
    String fname = (f.name() + " - " + name).replaceAll("[\\\\/:*?\"<>|]", "_");
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fname, java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(b);
  }

  /** Salva il report nella cartella Download e lo mostra nel Finder / Esplora risorse. */
  @PostMapping("/fascicoli/{id}/report/save")
  public Map<String, Object> saveReport(@PathVariable String id) throws Exception {
    var r = report(id);
    String name = Objects.requireNonNull(r.getHeaders().getContentDisposition().getFilename());
    Path out = OsOpen.downloads().resolve(name);
    for (int i = 2; Files.exists(out); i++) out = OsOpen.downloads().resolve(name.replace(".pdf", " (" + i + ").pdf"));
    Files.write(out, Objects.requireNonNull(r.getBody()));
    OsOpen.reveal(out);
    return Map.of("path", out.toString(), "name", out.getFileName().toString());
  }

  /** Apre un documento del fascicolo con l'applicazione predefinita (copia temporanea con il nome originale). */
  @PostMapping("/fascicoli/{id}/documents/{docId}/open")
  public Map<String, Object> openDocument(@PathVariable String id, @PathVariable String docId) throws Exception {
    Doc d = store.docs(id).stream().filter(x -> x.id().equals(docId)).findFirst().orElseThrow(() -> new NoSuchElementException("Documento non trovato"));
    Path tmp = Paths.dataDir().resolve("tmp").resolve(docId);
    Files.createDirectories(tmp);
    Path copy = tmp.resolve(d.name());
    Files.copy(store.dir(id).resolve("documenti").resolve(d.file()), copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    OsOpen.open(copy.toString());
    return Map.of("opened", d.name());
  }

  /** Apre il PDF ufficiale del regolamento con l'applicazione predefinita. */
  @PostMapping("/normativa/{law}/open")
  public Map<String, Object> openLaw(@PathVariable String law) throws Exception {
    String file = switch (law) { case "MR" -> "CELEX_32023R1230_IT_TXT.pdf"; case "CRA" -> "OJ_L_202402847_IT_TXT.pdf"; default -> throw new IllegalArgumentException("Regolamento sconosciuto"); };
    Path out = Paths.dataDir().resolve("normativa").resolve(file);
    if (!Files.exists(out)) {
      Files.createDirectories(out.getParent());
      try (var in = Api.class.getResourceAsStream("/static/normative/" + file)) { Files.copy(Objects.requireNonNull(in), out); }
    }
    OsOpen.open(out.toString());
    return Map.of("opened", file);
  }

  /** Apre nel browser di sistema solo indirizzi noti (es. download di Ollama). */
  @PostMapping("/open-url")
  public Map<String, Object> openUrl(@RequestBody Map<String, String> body) throws Exception {
    String url = body.getOrDefault("url", "");
    if (!url.equals("https://ollama.com/download")) throw new IllegalArgumentException("Indirizzo non consentito");
    OsOpen.open(url);
    return Map.of("opened", url);
  }

  /** Chiusura ordinata richiesta dalla finestra nativa: gli hook chiudono anche Ollama se l'ha avviato RegMac. */
  @PostMapping("/shutdown")
  public Map<String, Object> shutdown() {
    Thread.ofPlatform().start(() -> { try { Thread.sleep(300); } catch (InterruptedException ignored) { } System.exit(0); });
    return Map.of("bye", true);
  }

  // ---------- errori ----------
  @ExceptionHandler({IllegalArgumentException.class, NoSuchElementException.class, IllegalStateException.class})
  public ResponseEntity<Map<String, String>> error(RuntimeException e) {
    HttpStatus st = e instanceof NoSuchElementException ? HttpStatus.NOT_FOUND : e instanceof IllegalStateException ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
    return ResponseEntity.status(st).body(Map.of("error", e.getMessage() == null ? "Errore" : e.getMessage()));
  }

  private void touch(String id) { store.save(store.get(id)); }
  private static String s(String v) { return v == null ? "" : v.strip(); }
}
