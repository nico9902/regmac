package it.regmac.desktop;

import it.regmac.desktop.Store.*;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Calcolo deterministico di esiti, score e gap a partire dalle risposte dei punti di controllo.
 * Score: media sui requisiti applicabili della quota di punti supportati (contraddetti e senza evidenze valgono 0).
 * Massimo raggiungibile: come se i punti senza evidenze risultassero supportati.
 * Sotto il 30% di copertura delle evidenze il fascicolo è "non valutabile".
 */
@Component
public class Views {
  public static final double MIN_COVERAGE = 0.30;

  public record ReqView(String id, String ref, String title, String law, String group, String area, boolean always, String outcome,
                        Double value, Double max, String applicability, Result result, Catalog.Sheet sheet) { }
  public record Summary(int total, int done, int confirmed, int applicable, int na, int full, int S, int C, int I,
                        double score, double max, double coverage, boolean notEvaluable, int gapsTech, int gapsDoc, int gapsApp) { }
  public record Area(String name, int n, double score, double max) { }
  public record Gap(String rid, String ref, String title, String kind, int priority, double value, List<Point> points, String condition) { }
  public record Assessment(Summary summary, List<Area> areas, List<Gap> gaps, List<String> docsNeeded, List<ReqView> requirements) { }

  private final Catalog catalog;
  public Views(Catalog catalog) { this.catalog = catalog; }

  public Assessment assess(Fascicolo f, Map<String, Result> results, boolean withRequirements) {
    List<Catalog.Req> scope = catalog.scope(f.tags());
    List<ReqView> views = new ArrayList<>();
    int done = 0, confirmed = 0, applicable = 0, na = 0, full = 0, S = 0, C = 0, I = 0;
    double sum = 0, sumMax = 0;
    Map<String, double[]> areas = new LinkedHashMap<>();
    List<Gap> gaps = new ArrayList<>();
    for (Catalog.Req r : scope) {
      Result x = results.get(r.id());
      String outcome = null; Double value = null, max = null;
      if (x != null) {
        done++;
        if (x.confirmed()) confirmed++;
        if (!"TRUE".equals(x.applicability())) {
          na++; outcome = "NA";
          if ("UNKNOWN".equals(x.applicability())) gaps.add(new Gap(r.id(), r.shortRef(), r.title(), "Applicabilità", 3, 0, List.of(), r.sheet().condition()));
        } else {
          applicable++;
          int s = 0, c = 0, i = 0;
          for (Point p : x.points()) switch (p.effective()) { case "S" -> s++; case "C" -> c++; default -> i++; }
          S += s; C += c; I += i;
          int n = Math.max(1, x.points().size());
          value = (double) s / n; max = (double) (s + i) / n;
          sum += value; sumMax += max;
          if (s == n) full++;
          outcome = c > 0 ? "NC" : s == n ? "OK" : s > 0 ? "PART" : "INS";
          double[] a = areas.computeIfAbsent(r.area(), k -> new double[3]);
          a[0]++; a[1] += value; a[2] += max;
          if (s < n) {
            List<Point> bad = x.points().stream().filter(p -> !"S".equals(p.effective())).toList();
            gaps.add(new Gap(r.id(), r.shortRef(), r.title(), c > 0 ? "Tecnico" : "Documentale", c > 0 ? 1 : 2, value, bad, null));
          }
        }
      }
      if (withRequirements) views.add(new ReqView(r.id(), r.shortRef(), r.title(), r.law(), r.group(), r.area(), r.always(), outcome, value, max,
          x != null ? x.applicability() : null, x, r.sheet()));
    }
    int T = S + C + I;
    double cov = T == 0 ? 0 : (double) (S + C) / T;
    gaps.sort(Comparator.comparingInt(Gap::priority).thenComparingDouble(Gap::value));
    long tech = gaps.stream().filter(g -> g.priority() == 1).count(), doc = gaps.stream().filter(g -> g.priority() == 2).count();
    Summary s = new Summary(scope.size(), done, confirmed, applicable, na, full, S, C, I,
        applicable == 0 ? 0 : sum / applicable, applicable == 0 ? 0 : sumMax / applicable, cov, done > 0 && cov < MIN_COVERAGE,
        (int) tech, (int) doc, gaps.size() - (int) tech - (int) doc);
    List<Area> al = areas.entrySet().stream().map(e -> new Area(e.getKey(), (int) e.getValue()[0], e.getValue()[1] / e.getValue()[0], e.getValue()[2] / e.getValue()[0])).toList();
    return new Assessment(s, al, gaps, docsNeeded(gaps), views);
  }

  /** Tipi di documento da chiedere al fabbricante, ricavati dai gap documentali. */
  static List<String> docsNeeded(List<Gap> gaps) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (Gap g : gaps) {
      if (g.priority() != 2) continue;
      String t = (g.title() + " " + String.join(" ", g.points().stream().map(Point::text).toList())).toLowerCase();
      if (t.matches(".*(istruzion|informazion|manuale|avvertenz).*")) out.add("Istruzioni per l'uso complete (sezioni mancanti)");
      else if (t.matches(".*(rumor|vibrazion|emission|radiazion).*")) out.add("Misure di rumore, vibrazioni ed emissioni");
      else if (t.matches(".*(cibersic|vulnerab|software|aggiornament|dati|accesso).*")) out.add("Documentazione di cibersicurezza (All. VII CRA)");
      else if (t.matches(".*(arresto|comando|funzion.* di sicurezza|avviamento).*")) out.add("Schemi e validazione delle funzioni di sicurezza");
      else if (t.matches(".*(rischi|valutazione).*")) out.add("Valutazione dei rischi");
      else if (t.matches(".*(dichiarazion|marcatura|targa|document).*")) out.add("Dichiarazioni, marcatura e fascicolo tecnico");
      else out.add("Rapporti di prova e verifiche di collaudo");
    }
    return new ArrayList<>(out);
  }
}
