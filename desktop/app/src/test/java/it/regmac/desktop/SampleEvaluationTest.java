package it.regmac.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Prova opt-in: valuta un campione di requisiti del catalogo su un fascicolo già indicizzato, senza cache, e misura tempo ed esiti. */
class SampleEvaluationTest {
  @Test void run() throws Exception {
    String id = System.getProperty("regmac.debug.dossier"); Assumptions.assumeTrue(id != null);
    var rids = new ObjectMapper().readTree(Path.of("../../experiments/preassessment/ur5e/schede_strutturate.json").toFile()).path("schede").fieldNames();
    Store store = new Store(); Catalog catalog = new Catalog(); Ollama ai = new Ollama(); Engine engine = new Engine(store, catalog, ai);
    var f = store.get(id);
    boolean quasi = f.tags().contains("quasi_macchina");
    var ctx = new Engine.Ctx(store.index(id), engine.docsById(id), new ConcurrentHashMap<>());
    List<Catalog.Req> reqs = new ArrayList<>(); rids.forEachRemaining(r -> reqs.add(catalog.get(r)));
    List<String> texts = new ArrayList<>();
    for (var r : reqs) { r.sheet().points().forEach(p -> texts.add(Engine.queryText(p))); if (!r.sheet().condition().isBlank()) texts.add(r.sheet().condition()); }
    engine.prefetch(texts);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    long t0 = System.nanoTime();
    List<Future<Store.Result>> fs = new ArrayList<>();
    for (var r : reqs) fs.add(pool.submit(() -> engine.evaluate(r, ctx, quasi, f.tags(), "x", null)));
    Map<String, Integer> count = new TreeMap<>();
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < reqs.size(); i++) {
      var res = fs.get(i).get();
      out.append(reqs.get(i).id()).append(" app=").append(res.applicability()).append('\n');
      for (var p : res.points()) {
        count.merge(p.answer() + "/" + p.reason(), 1, Integer::sum);
        out.append("  ").append(p.answer()).append(' ').append(p.reason()).append(" | ").append(p.text(), 0, Math.min(90, p.text().length()))
           .append(p.quote() != null ? "\n      >> " + p.doc() + " p." + p.page() + " " + p.quote().substring(0, Math.min(150, p.quote().length())) : "").append('\n');
      }
    }
    double s = (System.nanoTime() - t0) / 1e9;
    pool.shutdown();
    Files.writeString(Path.of("target/sample-" + System.getProperty("regmac.sample.tag", "x") + ".txt"), out + "\nTEMPO " + s + " CHIAMATE " + engine.calls.get() + " " + count + "\n");
    System.out.println("TEMPO " + String.format("%.0f", s) + " s, chiamate " + engine.calls.get() + ", esiti " + count);
  }
}
