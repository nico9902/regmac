package it.regmac.desktop;

import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;

/** Prova opt-in su un fascicolo indicizzato: tipi dei documenti proposti, profilo proposto dall'AI e un elenco di requisiti. */
class DossierCheckTest {
  @Test void run() throws Exception {
    String id = System.getProperty("regmac.debug.dossier"); Assumptions.assumeTrue(id != null);
    Store store = new Store(); Catalog catalog = new Catalog(); Ollama ai = new Ollama(); Engine engine = new Engine(store, catalog, ai);
    String prep = System.getProperty("regmac.prepare.dir");
    if (prep != null) { // crea il fascicolo dai file della cartella e lo indicizza
      var now = java.time.Instant.now();
      store.save(new Store.Fascicolo(id, "Prova " + id, "", "", "", "", "", now, now, List.of(), List.of("generale", "macchina"), true, new Store.Run("idle", 0, 0, null, null, null, null), null));
      java.nio.file.Files.createDirectories(store.dir(id).resolve("documenti"));
      List<Store.Doc> ds = new ArrayList<>();
      try (var files = java.nio.file.Files.list(java.nio.file.Path.of(prep))) {
        for (var src : files.sorted().toList()) {
          byte[] b = java.nio.file.Files.readAllBytes(src); String sha = Engine.sha(b), name = src.getFileName().toString();
          String file = sha.substring(0, 12) + name.substring(name.lastIndexOf('.'));
          java.nio.file.Files.write(store.dir(id).resolve("documenti").resolve(file), b);
          var res = TextExtractor.extract(store.dir(id).resolve("documenti").resolve(file));
          ds.add(new Store.Doc(sha.substring(0, 12), name, file, b.length, res.pages(), sha, res.passages().size(), null, now));
        }
      }
      store.saveDocs(id, ds);
      engine.ensureIndex(id);
    }
    var index = store.index(id);
    List<Store.Doc> docs = new ArrayList<>();
    for (var d : store.docs(id)) {
      var t = DocTypes.propose(d.name(), d.pages(), String.join(" ", index.stream().filter(c -> c.docId().equals(d.id())).map(Store.Chunk::text).toList()));
      docs.add(d.withTypes(t)); System.out.println("TIPI " + d.name() + " " + t);
    }
    store.saveDocs(id, docs);
    long t0 = System.nanoTime();
    for (var a : engine.proposeProfile(id)) System.out.println("PROFILO " + a.tag() + " " + a.verdict() + " | " + (a.quote() == null ? "" : a.quote().substring(0, Math.min(120, a.quote().length()))));
    System.out.printf("TEMPO profilo %.0f s%n", (System.nanoTime() - t0) / 1e9);
    var f = store.get(id);
    var ctx = new Engine.Ctx(index, engine.docsById(id), new ConcurrentHashMap<>());
    List<Catalog.Req> reqs = Arrays.stream(System.getProperty("regmac.sample.rids").split(",")).map(catalog::get).toList();
    List<String> texts = new ArrayList<>(); reqs.forEach(r -> r.sheet().points().forEach(p -> texts.add(Engine.queryText(p)))); engine.prefetch(texts);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    t0 = System.nanoTime(); int calls0 = engine.calls.get();
    List<Future<Store.Result>> fs = new ArrayList<>();
    for (var r : reqs) fs.add(pool.submit(() -> engine.evaluate(r, ctx, false, f.tags(), "x", null)));
    Map<String, Integer> count = new TreeMap<>();
    for (int i = 0; i < reqs.size(); i++) { int k = 0; for (var p : fs.get(i).get().points()) {
      System.out.println("PT " + reqs.get(i).id() + " " + (k++) + " " + p.answer() + " " + p.reason());
      count.merge(p.answer() + "/" + p.reason(), 1, Integer::sum);
      if (!"I".equals(p.answer())) System.out.println("ESITO " + p.answer() + " " + reqs.get(i).id() + " | " + p.text().substring(0, Math.min(80, p.text().length())) + "\n      >> " + p.doc() + " " + p.quote().substring(0, Math.min(170, p.quote().length())));
    } }
    pool.shutdown();
    System.out.printf("TEMPO requisiti %.0f s, chiamate %d, esiti %s%n", (System.nanoTime() - t0) / 1e9, engine.calls.get() - calls0, count);
  }
}
