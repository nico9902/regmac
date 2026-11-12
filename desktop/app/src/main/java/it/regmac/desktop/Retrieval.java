package it.regmac.desktop;

import it.regmac.desktop.Store.Chunk;
import java.text.Normalizer;
import java.util.*;

/** Ricerca ibrida: riserva posti sia alla semantica multilingue sia alle parole chiave.
 * La similarità sceglie candidati, non decide se un requisito è dimostrato. */
final class Retrieval {
  static final String VERSION = "hybrid-context-v2";
  record Hit(Chunk chunk, double similarity) { }
  private static final Set<String> STOP = Set.of("della", "delle", "dello", "degli", "dalla", "nella", "nelle", "sono", "essere", "alla", "alle", "dell", "dall", "nell", "with", "that", "this", "from", "have", "must", "shall", "the", "and", "for", "una", "del", "dei", "gli", "che", "per", "con", "non", "nel", "sul");
  static Set<String> tokens(String text) {
    Set<String> out = new HashSet<>();
    for (String word : Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "").split("[^\\p{L}\\p{N}]+"))
      if (word.length() >= 3 && !STOP.contains(word)) out.add(word);
    return out;
  }
  static List<Hit> find(String claim, float[] query, List<Chunk> index, int limit) {
    if (index.isEmpty()) return List.of();
    List<Hit> semantic = index.stream().map(c -> new Hit(c, Ollama.cos(query, c.vec())))
        .sorted(Comparator.comparingDouble(Hit::similarity).reversed()).toList();
    Set<String> words = tokens(claim);
    Map<String, Integer> frequency = new HashMap<>();
    Map<String, Set<String>> terms = new HashMap<>();
    for (Chunk c : index) {
      Set<String> ts = tokens(c.text()); ts.retainAll(words); terms.put(c.id(), ts);
      ts.forEach(t -> frequency.merge(t, 1, Integer::sum));
    }
    Map<String, Double> lexical = new HashMap<>();
    for (Chunk c : index) {
      double score = terms.get(c.id()).stream().mapToDouble(t -> Math.log(1 + (double)index.size() / frequency.get(t))).sum();
      lexical.put(c.id(), score / Math.sqrt(Math.max(1, c.text().length() / 500.0)));
    }
    List<Hit> lex = semantic.stream().filter(h -> lexical.get(h.chunk().id()) > 0)
        .sorted(Comparator.<Hit>comparingDouble(h -> lexical.get(h.chunk().id())).reversed()).toList();
    LinkedHashMap<String, Hit> chosen = new LinkedHashMap<>();
    // Con 4 candidati: tre semantici e uno lessicale (misurato sulla UR5e: 21/23 contro 17/23 con due e due); riempie eventuali duplicati con i successivi semantici.
    semantic.stream().limit(Math.max(1, limit - (limit >= 6 ? 2 : 1))).forEach(h -> chosen.put(h.chunk().id(), h));
    for (Hit h : lex) { if (chosen.size() >= limit) break; chosen.putIfAbsent(h.chunk().id(), h); }
    for (Hit h : semantic) { if (chosen.size() >= limit) break; chosen.putIfAbsent(h.chunk().id(), h); }
    return new ArrayList<>(chosen.values());
  }
}
