package it.regmac.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import it.regmac.desktop.Store.*;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

/**
 * Motore della pre-valutazione (stesso schema di experiments/preassessment/run_fast.py):
 * per ogni punto di controllo le 3 frasi più simili dei documenti; per ogni coppia (punto, frase) una domanda brevissima
 * "la frase dimostra il punto (S), lo contraddice (C) o non c'entra (N)?"; contraddetto prevale su supportato.
 * Giudizi ed embedding sono messi in cache: rilanciare ricalcola solo ciò che è cambiato.
 */
@Component
public class Engine {
  /** Cambia quando cambiano le domande al modello: gli esiti salvati vanno ricalcolati. */
  static final String JUDGE_VERSION = "giudizio-v2-accordo";
  static final int TOP = 3;
  static final double MIN_SIM = 0.62, PAIR_SIM = 0.68;
  static final Map<String, Object> ONE = Map.of("type", "object", "properties", Map.of("v", Map.of("type", "string", "enum", List.of("S", "C", "N"))), "required", List.of("v"));

  private final Store store;
  private final Catalog catalog;
  private final Ollama ai;
  private final ExecutorService judges = Executors.newFixedThreadPool(4);
  private final ExecutorService runs = Executors.newSingleThreadExecutor();
  private final Set<String> cancel = ConcurrentHashMap.newKeySet();
  private final Map<String, float[]> queryCache;

  public Engine(Store store, Catalog catalog, Ollama ai) {
    this.store = store; this.catalog = catalog; this.ai = ai;
    this.queryCache = new ConcurrentHashMap<>(store.queryCache());
    for (Fascicolo f : store.list()) // un'elaborazione interrotta dalla chiusura dell'app si può riprendere
      if (Set.of("running", "indexing", "queued").contains(f.run().state()))
        store.setRun(f.id(), new Run("interrupted", f.run().done(), f.run().total(), f.run().startedAt(), null, "Interrotta alla chiusura di RegMac", f.run().model()));
  }

  // ---------- indicizzazione dei documenti ----------
  public synchronized List<Chunk> ensureIndex(String id) throws Exception {
    List<Doc> docs = store.docs(id);
    List<Chunk> index = new ArrayList<>(store.index(id));
    Set<String> indexed = new HashSet<>(), present = new HashSet<>();
    index.forEach(c -> indexed.add(c.docId()));
    docs.forEach(d -> present.add(d.id()));
    index.removeIf(c -> !present.contains(c.docId()));
    List<Chunk> fresh = new ArrayList<>();
    List<Doc> updated = new ArrayList<>();
    for (Doc d : docs) {
      if (indexed.contains(d.id())) { updated.add(d); continue; }
      var res = TextExtractor.extract(store.dir(id).resolve("documenti").resolve(d.file()));
      int k = 0;
      for (var p : res.passages()) fresh.add(new Chunk(d.id() + "#" + (++k), d.id(), p.page(), p.section(), p.text(), null));
      updated.add(new Doc(d.id(), d.name(), d.file(), d.size(), res.pages(), d.sha256(), res.passages().size(), res.warning(), d.addedAt()));
    }
    if (!fresh.isEmpty()) {
      List<float[]> v = ai.embed(fresh.stream().map(c -> (c.section().isBlank() ? "" : c.section() + ": ") + c.text()).toList(), false);
      for (int i = 0; i < fresh.size(); i++) { Chunk c = fresh.get(i); index.add(new Chunk(c.id(), c.docId(), c.page(), c.section(), c.text(), v.get(i))); }
    }
    store.saveDocs(id, updated);
    store.saveIndex(id, index);
    return index;
  }

  // ---------- ricerca e giudizio ----------
  record Hit(Chunk c, double sim) { }

  List<Hit> near(float[] q, List<Chunk> index, int n) {
    return index.stream().map(c -> new Hit(c, Ollama.cos(q, c.vec()))).sorted((a, b) -> Double.compare(b.sim(), a.sim())).limit(n).toList();
  }

  float[] query(String text) throws Exception {
    float[] v = queryCache.get(text);
    if (v == null) { v = ai.embed(List.of(text), true).get(0); queryCache.put(text, v); }
    return v;
  }

  void prefetch(Collection<String> texts) throws Exception {
    List<String> miss = texts.stream().distinct().filter(t -> !queryCache.containsKey(t)).toList();
    if (miss.isEmpty()) return;
    List<float[]> v = ai.embed(miss, true);
    for (int i = 0; i < miss.size(); i++) queryCache.put(miss.get(i), v.get(i));
    store.saveQueryCache(queryCache);
  }

  /** Domanda di giudizio con esempi risolti: riduce molto le conferme sbagliate del modello piccolo (misurato: 10→12 su 16 casi, nessun falso "S"). */
  static String prompt(String claim, String sentence) {
    return """
        Devi dire se una frase tratta dai documenti di una macchina conferma o smentisce un'affermazione.
        S = la frase afferma esplicitamente che l'affermazione è vera.
        C = la frase afferma esplicitamente il contrario dell'affermazione.
        N = la frase parla d'altro, oppure è generica ("conforme alle norme"), oppure non basta per decidere.
        Esempi:
        Affermazione: La macchina si sposta durante il lavoro. Frase: «La macchina va fissata al pavimento con quattro tasselli.» → C
        Affermazione: La macchina è una quasi-macchina. Frase: «L'avviamento avviene premendo il pulsante di marcia.» → N
        Affermazione: I ripari fissi richiedono un utensile per essere rimossi. Frase: «I carter sono fissati con viti che richiedono una chiave.» → S
        Affermazione: Il comando di emergenza è chiaramente visibile. Frase: «La macchina è dotata di pulsante di emergenza ed è conforme alle norme.» → N
        Ora:
        Affermazione: %s
        Frase: «%s»
        Rispondi {"v":"S"|"C"|"N"}.""".formatted(claim, sentence);
  }

  /** Seconda formulazione, indipendente: serve a confermare S e C (le due domande sbagliano in modi diversi). */
  static String check(String claim, String sentence) {
    return "Punto di controllo: " + claim + "\nFrase da un documento della macchina: «" + sentence + "»\n"
        + "La frase, da sola, dimostra in modo specifico che il punto è soddisfatto/vero (S), dimostra che non lo è (C), "
        + "oppure non riguarda specificamente questo punto o è una dichiarazione generica (N)? Rispondi {\"v\":\"S\"|\"C\"|\"N\"}.";
  }

  String ask(String prompt, Map<String, String> cache) throws Exception {
    String key = sha(Ollama.CHAT + "\n" + prompt), hit = cache.get(key);
    if (hit != null) return hit;
    String v = ai.chat(prompt, ONE).path("v").asText("N");
    cache.put(key, v);
    return v;
  }

  /** Giudica le coppie in parallelo usando e aggiornando la cache dei giudizi. */
  List<String> judge(List<String[]> pairs, Map<String, String> cache) throws Exception {
    List<Future<String>> fs = new ArrayList<>();
    for (String[] p : pairs) {
      // S o C valgono solo se anche la seconda formulazione concorda (misurato: nessun S/C sbagliato sui casi di prova)
      fs.add(judges.submit(() -> {
        String a = ask(prompt(p[0], p[1]), cache);
        if ("N".equals(a)) return a;
        return a.equals(ask(check(p[0], p[1]), cache)) ? a : "N";
      }));
    }
    List<String> out = new ArrayList<>();
    for (Future<String> f : fs) out.add(f.get());
    return out;
  }

  /** Esito di un'affermazione dalle sue frasi candidate: contraddetto prevale, poi supportato, altrimenti senza evidenze. */
  record Verdict(String answer, Hit source, double support) { }

  Verdict decide(String claim, List<Chunk> index, Map<String, String> cache) throws Exception {
    List<Hit> hits = near(query(claim), index, TOP);
    if (hits.isEmpty() || hits.get(0).sim() < MIN_SIM) return new Verdict("I", null, 0);
    List<Hit> cand = new ArrayList<>();
    cand.add(hits.get(0));
    hits.stream().skip(1).filter(h -> h.sim() >= PAIR_SIM).forEach(cand::add);
    List<String> v = judge(cand.stream().map(h -> new String[]{claim, h.c().text()}).toList(), cache);
    for (String want : List.of("C", "S"))
      for (int i = 0; i < v.size(); i++)
        if (want.equals(v.get(i))) return new Verdict(want, cand.get(i), (double) Collections.frequency(v, want) / v.size());
    return new Verdict("I", null, 0);
  }

  // ---------- profilo proposto dall'AI ----------
  public List<Answer> proposeProfile(String id) throws Exception {
    List<Chunk> index = ensureIndex(id);
    Map<String, String> cache = new ConcurrentHashMap<>(store.judgements(id));
    Map<String, Doc> docs = docsById(id);
    prefetch(Catalog.QUESTIONS.stream().map(Catalog.Question::condition).toList());
    List<Answer> out = new ArrayList<>();
    for (var q : Catalog.QUESTIONS) {
      Verdict v = decide(q.condition(), index, cache);
      String verdict = switch (v.answer()) { case "S" -> "TRUE"; case "C" -> "FALSE"; default -> "UNKNOWN"; };
      Chunk c = v.source() != null ? v.source().c() : null;
      out.add(new Answer(q.tag(), verdict, c != null ? c.text() : "", c != null ? docs.get(c.docId()).name() : null, c != null ? c.page() : null, "TRUE".equals(verdict)));
    }
    store.saveJudgements(id, cache);
    return out;
  }

  // ---------- pre-valutazione ----------
  public void start(String id) {
    Fascicolo f = store.get(id);
    if (Set.of("running", "queued", "indexing").contains(f.run().state())) return;
    cancel.remove(id);
    store.setRun(id, new Run("queued", 0, catalog.scope(f.tags()).size(), Instant.now(), null, "In coda", Ollama.CHAT));
    runs.submit(() -> run(id));
  }

  public void stop(String id) { cancel.add(id); }

  void run(String id) {
    Fascicolo f = store.get(id);
    List<Catalog.Req> scope = catalog.scope(f.tags());
    Instant started = Instant.now();
    try {
      store.setRun(id, new Run("indexing", 0, scope.size(), started, null, "Lettura e indicizzazione dei documenti", Ollama.CHAT));
      List<Chunk> index = ensureIndex(id);
      Map<String, Doc> docs = docsById(id);
      Map<String, String> cache = new ConcurrentHashMap<>(store.judgements(id));
      List<String> texts = new ArrayList<>();
      scope.forEach(r -> { texts.addAll(r.sheet().points()); if (!r.sheet().condition().isBlank()) texts.add(r.sheet().condition()); });
      prefetch(texts);
      Map<String, Result> results = new LinkedHashMap<>(store.results(id));
      results.keySet().retainAll(scope.stream().map(Catalog.Req::id).toList());
      String indexHash = sha(String.join("|", index.stream().map(Chunk::id).toList()) + Ollama.CHAT + catalog.schedeVersion() + JUDGE_VERSION);
      int done = 0;
      for (Catalog.Req r : scope) {
        if (cancel.remove(id)) { persist(id, results); store.saveJudgements(id, cache);
          store.setRun(id, new Run("stopped", done, scope.size(), started, Instant.now(), "Interrotta", Ollama.CHAT)); return; }
        String hash = sha(indexHash + r.sheet());
        Result old = results.get(r.id());
        if (old == null || !hash.equals(old.hash())) results.put(r.id(), evaluate(r, index, docs, cache, hash, old));
        done++;
        if (done % 5 == 0 || done == scope.size()) {
          persist(id, results);
          store.setRun(id, new Run("running", done, scope.size(), started, null, null, Ollama.CHAT));
        }
      }
      store.saveJudgements(id, cache);
      store.setRun(id, new Run("done", scope.size(), scope.size(), started, Instant.now(), null, Ollama.CHAT));
    } catch (Exception e) {
      store.setRun(id, new Run("error", 0, scope.size(), started, Instant.now(), "Errore: " + e.getMessage(), Ollama.CHAT));
    }
  }

  Result evaluate(Catalog.Req r, List<Chunk> index, Map<String, Doc> docs, Map<String, String> cache, String hash, Result old) throws Exception {
    String app = "TRUE", appQuote = null, appDoc = null; Integer appPage = null;
    if (!r.sheet().condition().isBlank()) {
      Verdict v = decide(r.sheet().condition(), index, cache);
      app = switch (v.answer()) { case "S" -> "TRUE"; case "C" -> "FALSE"; default -> "UNKNOWN"; };
      if (v.source() != null) { appQuote = v.source().c().text(); appDoc = docs.get(v.source().c().docId()).name(); appPage = v.source().c().page(); }
    }
    List<Point> pts = new ArrayList<>();
    for (String claim : r.sheet().points()) {
      Verdict v = "TRUE".equals(app) ? decide(claim, index, cache) : new Verdict("I", null, 0);
      Chunk c = v.source() != null ? v.source().c() : null;
      pts.add(new Point(claim, v.answer(), c != null ? c.text() : null, c != null ? c.docId() : null, c != null ? docs.get(c.docId()).name() : null,
          c != null ? c.page() : null, v.support(), null));
    }
    // le correzioni della persona restano valide se i punti non sono cambiati
    if (old != null && old.points().size() == pts.size()) {
      for (int i = 0; i < pts.size(); i++) {
        Point p = pts.get(i), o = old.points().get(i);
        if (o.user() != null && o.text().equals(p.text())) pts.set(i, new Point(p.text(), p.answer(), p.quote(), p.docId(), p.doc(), p.page(), p.support(), o.user()));
      }
    }
    return new Result(r.id(), app, appQuote, appDoc, appPage, pts, false, old != null ? old.note() : null, hash, Instant.now());
  }

  /** Salva gli esiti senza perdere conferme, note e correzioni fatte dalla persona mentre l'elaborazione era in corso. */
  void persist(String id, Map<String, Result> results) {
    synchronized (store) {
      Map<String, Result> cur = store.results(id);
      for (var e : results.entrySet()) {
        Result now = cur.get(e.getKey()), mine = e.getValue();
        if (now != null && now.hash() != null && now.hash().equals(mine.hash()) && now != mine) e.setValue(now);
      }
      store.saveResults(id, results);
    }
  }

  Map<String, Doc> docsById(String id) { Map<String, Doc> m = new HashMap<>(); store.docs(id).forEach(d -> m.put(d.id(), d)); return m; }

  static String sha(String s) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }

  public static String sha(byte[] b) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); } catch (Exception e) { throw new IllegalStateException(e); }
  }

  public boolean busy() { return store.list().stream().anyMatch(f -> Set.of("running", "queued", "indexing").contains(f.run().state())); }
  static List<Integer> range(int n) { return IntStream.range(0, n).boxed().toList(); }
  static boolean exists(java.nio.file.Path p) { return Files.exists(p); }
}
