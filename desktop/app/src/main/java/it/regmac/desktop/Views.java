package it.regmac.desktop;

import it.regmac.desktop.Store.*;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Calcolo deterministico di esiti, score e gap a partire dalle risposte dei punti di controllo.
 * Score: media sui requisiti applicabili della quota di punti supportati (possibili non conformità e punti senza evidenze valgono 0).
 * I punti a carico dell'integratore (quasi-macchine) sono esclusi dallo score e elencati a parte.
 * Massimo raggiungibile: come se i punti senza evidenze risultassero supportati.
 * Sotto il 30% di copertura delle evidenze il fascicolo è "non valutabile".
 * Ogni gap indica il motivo e il documento atteso.
 */
@Component
public class Views {
  public static final double MIN_COVERAGE = 0.30;

  /** Motivi dei gap, in ordine di priorità. */
  public static final String NC = "Possibile non conformità", MISSING = "Documento assente", NO_EVIDENCE = "Evidenza mancante",
      APPLICABILITY = "Applicabilità da verificare", INTEGRATOR = "A carico dell'integratore";

  public record ReqView(String id, String ref, String title, String law, String group, String area, boolean always, String outcome,
                        Double value, Double max, String applicability, Result result, Catalog.Sheet sheet) { }
  public record Summary(int total, int done, int confirmed, int applicable, int na, int full, int S, int C, int I,
                        double score, double max, double coverage, boolean notEvaluable, int gapsTech, int gapsDoc, int gapsApp,
                        int gapsMissing, int gapsEvidence, int integrator) { }
  public record Area(String name, int n, double score, double max) { }
  /** Punto non soddisfatto: motivo esplicito e documento atteso (tipo ed etichetta). */
  public record GapPoint(String text, String motive, String expected, String expectedLabel, String answer, String quote, String doc, Integer page, boolean reviewed) { }
  public record Gap(String rid, String ref, String title, String kind, int priority, double value, List<GapPoint> points, String condition) { }
  /** Documento da richiedere: tipo, se manca del tutto o è incompleto, quanti punti e requisiti ne dipendono. */
  public record DocNeed(String type, String label, boolean missing, int points, int requirements, boolean integrator) { }
  public record Assessment(Summary summary, List<Area> areas, List<Gap> gaps, List<DocNeed> docsNeeded, List<ReqView> requirements) { }

  private final Catalog catalog;
  public Views(Catalog catalog) { this.catalog = catalog; }

  static String label(String type) { return type == null ? "Documento non indicato dalla scheda" : Catalog.DOC_TYPES.getOrDefault(type, type); }

  /** Motivo di un punto non supportato. */
  static String motive(Point p) {
    String e = p.effective();
    if ("C".equals(e)) return NC;
    if (p.user() == null && "INTEGRATOR".equals(p.reason())) return INTEGRATOR;
    if (p.user() == null && "DOC_MISSING".equals(p.reason())) return MISSING;
    return NO_EVIDENCE;
  }

  public Assessment assess(Fascicolo f, Map<String, Result> results, boolean withRequirements) {
    List<Catalog.Req> scope = catalog.scope(f.tags());
    List<ReqView> views = new ArrayList<>();
    int done = 0, confirmed = 0, applicable = 0, na = 0, full = 0, S = 0, C = 0, I = 0, integrator = 0;
    double sum = 0, sumMax = 0;
    Map<String, double[]> areas = new LinkedHashMap<>();
    List<Gap> gaps = new ArrayList<>();
    Map<String, int[]> need = new LinkedHashMap<>(); // chiave tipo|mancante|integratore → punti, requisiti
    for (Catalog.Req r : scope) {
      Result x = results.get(r.id());
      String outcome = null; Double value = null, max = null;
      if (x != null) {
        done++;
        if (x.confirmed()) confirmed++;
        if (!"TRUE".equals(x.effectiveApplicability())) {
          na++; outcome = "NA";
          if ("UNKNOWN".equals(x.effectiveApplicability())) gaps.add(new Gap(r.id(), r.shortRef(), r.title(), APPLICABILITY, 3, 0,
              List.of(new GapPoint(r.sheet().condition(), APPLICABILITY, "istruzioni", "Istruzioni o scheda tecnica che descrivano la caratteristica", "I", null, null, null, false)),
              r.sheet().condition()));
        } else {
          int s = 0, c = 0, i = 0, n = 0;
          List<GapPoint> bad = new ArrayList<>();
          Set<String> seen = new HashSet<>();
          for (Point p : x.points()) {
            String m = "S".equals(p.effective()) ? null : motive(p);
            if (INTEGRATOR.equals(m)) integrator++;
            else { n++; switch (p.effective()) { case "S" -> s++; case "C" -> c++; default -> i++; } }
            if (m == null) continue;
            bad.add(new GapPoint(p.text(), m, p.expected(), label(p.expected()), p.effective(), "C".equals(p.effective()) ? p.quote() : null,
                "C".equals(p.effective()) ? p.doc() : null, "C".equals(p.effective()) ? p.page() : null, p.user() != null));
            if (!NC.equals(m) && seen.add(m + p.expected())) {
              int[] k = need.computeIfAbsent((p.expected() == null ? "" : p.expected()) + "|" + MISSING.equals(m) + "|" + INTEGRATOR.equals(m), z -> new int[2]);
              k[1]++;
            }
            if (!NC.equals(m)) need.get((p.expected() == null ? "" : p.expected()) + "|" + MISSING.equals(m) + "|" + INTEGRATOR.equals(m))[0]++;
          }
          if (n == 0) { outcome = "INT"; } // tutti i punti spettano all'integratore: fuori dallo score
          else {
            applicable++;
            S += s; C += c; I += i;
            value = (double) s / n; max = (double) (s + i) / n;
            sum += value; sumMax += max;
            if (s == n) full++;
            outcome = c > 0 ? "NC" : s == n ? "OK" : s > 0 ? "PART" : "INS";
            double[] a = areas.computeIfAbsent(r.area(), k -> new double[3]);
            a[0]++; a[1] += value; a[2] += max;
          }
          if (!bad.isEmpty()) {
            String kind = bad.stream().map(GapPoint::motive).min(Comparator.comparingInt(Views::rank)).orElse(NO_EVIDENCE);
            gaps.add(new Gap(r.id(), r.shortRef(), r.title(), kind, rank(kind), value == null ? 0 : value, bad, null));
          }
        }
      }
      if (withRequirements) views.add(new ReqView(r.id(), r.shortRef(), r.title(), r.law(), r.group(), r.area(), r.always(), outcome, value, max,
          x != null ? x.effectiveApplicability() : null, x, r.sheet()));
    }
    int T = S + C + I;
    double cov = T == 0 ? 0 : (double) (S + C) / T;
    gaps.sort(Comparator.comparingInt(Gap::priority).thenComparingDouble(Gap::value));
    int tech = (int) gaps.stream().filter(g -> NC.equals(g.kind())).count(), missing = (int) gaps.stream().filter(g -> MISSING.equals(g.kind())).count(),
        evidence = (int) gaps.stream().filter(g -> NO_EVIDENCE.equals(g.kind())).count(), app = (int) gaps.stream().filter(g -> APPLICABILITY.equals(g.kind())).count();
    Summary sm = new Summary(scope.size(), done, confirmed, applicable, na, full, S, C, I,
        applicable == 0 ? 0 : sum / applicable, applicable == 0 ? 0 : sumMax / applicable, cov, done > 0 && cov < MIN_COVERAGE,
        tech, missing + evidence, app, missing, evidence, integrator);
    List<Area> al = areas.entrySet().stream().map(e -> new Area(e.getKey(), (int) e.getValue()[0], e.getValue()[1] / e.getValue()[0], e.getValue()[2] / e.getValue()[0])).toList();
    List<DocNeed> docs = new ArrayList<>();
    need.forEach((k, v) -> { String[] p = k.split("\\|", -1); String t = p[0].isEmpty() ? null : p[0];
      docs.add(new DocNeed(t, label(t), Boolean.parseBoolean(p[1]), v[0], v[1], Boolean.parseBoolean(p[2]))); });
    docs.sort(Comparator.comparing(DocNeed::integrator).thenComparing(d -> !d.missing()).thenComparing(d -> -d.points()));
    return new Assessment(sm, al, gaps, docs, views);
  }

  static int rank(String motive) {
    return switch (motive) { case NC -> 1; case MISSING -> 2; case NO_EVIDENCE -> 2; case APPLICABILITY -> 3; default -> 4; };
  }
}
