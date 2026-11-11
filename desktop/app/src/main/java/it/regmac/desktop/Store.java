package it.regmac.desktop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Archivio su file: una cartella per fascicolo (fascicolo.json, documenti, indice, esiti, report).
 * Scritture atomiche (file temporaneo + rename); l'eliminazione sposta nel cestino e si può annullare.
 */
@Component
public class Store {
  public record Profile(String name, String organization) { }
  public record Answer(String tag, String verdict, String quote, String doc, Integer page, Boolean value) { }
  public record Run(String state, int done, int total, Instant startedAt, Instant finishedAt, String message, String model) {
    static Run idle() { return new Run("idle", 0, 0, null, null, null, null); }
  }
  public record Fascicolo(String id, String name, String model, String serial, String year, String maker, String use,
                          Instant createdAt, Instant updatedAt, List<Answer> profile, List<String> tags, boolean profileConfirmed,
                          Run run, Instant exportedAt) { }
  public record Doc(String id, String name, String file, long size, int pages, String sha256, int passages, String warning, Instant addedAt) { }
  public record Chunk(String id, String docId, int page, String section, String text, float[] vec) { }
  public record Point(String text, String answer, String quote, String docId, String doc, Integer page, Double support, String user) {
    public String effective() { return user != null ? user : answer; }
  }
  public record Result(String rid, String applicability, String appQuote, String appDoc, Integer appPage, List<Point> points,
                       boolean confirmed, String note, String hash, Instant at) { }

  final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  private final Path root = Paths.dataDir(), dossiers = root.resolve("fascicoli"), trash = root.resolve("cestino");

  public Store() throws IOException {
    Files.createDirectories(dossiers);
    Files.createDirectories(trash);
    try (Stream<Path> s = Files.list(trash)) { s.forEach(Store::deleteTree); } // il cestino si svuota a ogni avvio
  }

  public Path dir(String id) {
    if (!id.matches("[a-z0-9-]{8,64}")) throw new IllegalArgumentException("Identificativo non valido");
    return dossiers.resolve(id);
  }

  // ---------- profilo del valutatore ----------
  public Optional<Profile> profile() { return read(root.resolve("profilo.json"), Profile.class); }
  public void saveProfile(Profile p) { write(root.resolve("profilo.json"), p); }

  // ---------- fascicoli ----------
  public List<Fascicolo> list() {
    List<Fascicolo> out = new ArrayList<>();
    try (Stream<Path> s = Files.list(dossiers)) {
      s.filter(Files::isDirectory).forEach(d -> read(d.resolve("fascicolo.json"), Fascicolo.class).ifPresent(out::add));
    } catch (IOException ignored) { }
    out.sort(Comparator.comparing(Fascicolo::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
    return out;
  }

  public Fascicolo get(String id) { return read(dir(id).resolve("fascicolo.json"), Fascicolo.class).orElseThrow(() -> new NoSuchElementException("Fascicolo non trovato")); }

  public synchronized Fascicolo save(Fascicolo f) {
    Fascicolo n = new Fascicolo(f.id(), f.name(), f.model(), f.serial(), f.year(), f.maker(), f.use(), f.createdAt(), Instant.now(),
        f.profile(), f.tags(), f.profileConfirmed(), f.run(), f.exportedAt());
    try { Files.createDirectories(dir(f.id()).resolve("documenti")); } catch (IOException e) { throw new IllegalStateException(e); }
    write(dir(f.id()).resolve("fascicolo.json"), n);
    return n;
  }

  public synchronized void setRun(String id, Run run) {
    Fascicolo f = get(id);
    write(dir(id).resolve("fascicolo.json"), new Fascicolo(f.id(), f.name(), f.model(), f.serial(), f.year(), f.maker(), f.use(), f.createdAt(), f.updatedAt(),
        f.profile(), f.tags(), f.profileConfirmed(), run, f.exportedAt()));
  }

  public void delete(String id) throws IOException { Files.move(dir(id), trash.resolve(id), StandardCopyOption.ATOMIC_MOVE); }
  public void restore(String id) throws IOException {
    if (!id.matches("[a-z0-9-]{8,64}")) throw new IllegalArgumentException("Identificativo non valido");
    Files.move(trash.resolve(id), dir(id), StandardCopyOption.ATOMIC_MOVE);
  }

  // ---------- documenti, indice, esiti ----------
  public List<Doc> docs(String id) { return read(dir(id).resolve("documenti.json"), new TypeReference<List<Doc>>() { }).orElse(new ArrayList<>()); }
  public void saveDocs(String id, List<Doc> d) { write(dir(id).resolve("documenti.json"), d); }
  public List<Chunk> index(String id) { return read(dir(id).resolve("indice.json"), new TypeReference<List<Chunk>>() { }).orElse(new ArrayList<>()); }
  public void saveIndex(String id, List<Chunk> c) { write(dir(id).resolve("indice.json"), c); }
  public Map<String, Result> results(String id) { return read(dir(id).resolve("esiti.json"), new TypeReference<Map<String, Result>>() { }).orElse(new LinkedHashMap<>()); }
  public void saveResults(String id, Map<String, Result> r) { write(dir(id).resolve("esiti.json"), r); }
  public Map<String, String> judgements(String id) { return read(dir(id).resolve("giudizi.json"), new TypeReference<Map<String, String>>() { }).orElse(new HashMap<>()); }
  public void saveJudgements(String id, Map<String, String> j) { write(dir(id).resolve("giudizi.json"), j); }
  public Map<String, float[]> queryCache() { return read(root.resolve("cache-query.json"), new TypeReference<Map<String, float[]>>() { }).orElse(new HashMap<>()); }
  public void saveQueryCache(Map<String, float[]> c) { write(root.resolve("cache-query.json"), c); }

  // ---------- utilità ----------
  <T> Optional<T> read(Path p, Class<T> c) {
    try { return Files.exists(p) ? Optional.of(json.readValue(p.toFile(), c)) : Optional.empty(); } catch (IOException e) { throw new IllegalStateException("File danneggiato: " + p, e); }
  }
  <T> Optional<T> read(Path p, TypeReference<T> t) {
    try { return Files.exists(p) ? Optional.of(json.readValue(p.toFile(), t)) : Optional.empty(); } catch (IOException e) { throw new IllegalStateException("File danneggiato: " + p, e); }
  }
  synchronized void write(Path p, Object v) {
    try {
      Files.createDirectories(p.getParent());
      Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
      json.writeValue(tmp.toFile(), v);
      Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) { throw new IllegalStateException("Scrittura non riuscita: " + p, e); }
  }

  static void deleteTree(Path p) {
    try (Stream<Path> w = Files.walk(p)) { w.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete()); } catch (IOException ignored) { }
  }
}
