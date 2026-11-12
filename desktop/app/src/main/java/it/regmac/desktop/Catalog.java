package it.regmac.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Catalogo dei requisiti (Reg. Macchine e CRA) con le schede di verifica e le domande del profilo. */
@Component
public class Catalog {
  /**
   * Punto di controllo strutturato: affermazione, documento in cui cercare l'evidenza, cosa conta come prova e cosa no,
   * cosa conta come smentita (senza questo campo il punto non può risultare contraddetto), responsabilità per le quasi-macchine,
   * eventuale limite numerico confrontato dal programma e non dall'AI.
   */
  public record Spec(String q, String doc, String ok, String no, String smentita, String quasi, Num num) {
    public boolean integratorIfQuasi() { return "integratore".equals(quasi); }
    public boolean canContradict() { return (smentita != null && !smentita.isBlank()) || num != null; }
  }
  public record Num(String what, String unit, Double min, Double max) { }
  public record Sheet(String condition, List<Spec> points, String status) { }

  /** Tipi di documento: chiave usata nelle schede e nei documenti, etichetta per l'utente. */
  public static final Map<String, String> DOC_TYPES = new LinkedHashMap<>();
  static {
    DOC_TYPES.put("istruzioni", "Istruzioni (uso, installazione, manutenzione)");
    DOC_TYPES.put("specifiche", "Scheda tecnica / specifiche");
    DOC_TYPES.put("dichiarazione", "Dichiarazione UE di conformità o di incorporazione");
    DOC_TYPES.put("valutazione_rischi", "Valutazione dei rischi");
    DOC_TYPES.put("schemi", "Schemi elettrici, pneumatici, idraulici");
    DOC_TYPES.put("disegni", "Disegni e layout");
    DOC_TYPES.put("rapporti_prova", "Rapporti di prova, misure e validazioni");
    DOC_TYPES.put("targa", "Targa e marcatura");
    DOC_TYPES.put("cybersicurezza", "Documentazione di cibersicurezza (CRA)");
    DOC_TYPES.put("certificati", "Certificati e attestati");
  }

  /** Affermazione libera (profilo, condizioni, vecchie schede): cerca in tutti i documenti e ammette la smentita esplicita. */
  public static Spec claim(String text) {
    return new Spec(text, null, "una frase del fabbricante che afferma esplicitamente quanto indicato per questa macchina",
        "frasi generiche, argomenti vicini ma diversi, istruzioni rivolte ad altri, testo di regolamenti o norme riportato (elenchi di categorie di macchine, requisiti)",
        "una frase che afferma esplicitamente il contrario", null, null);
  }
  public record Req(String id, String law, String section, String group, String ref, String shortRef, String title, String text,
                    List<Integer> pages, List<String> tags, boolean always, List<String> standards, Sheet sheet, String area) { }
  /** Domanda del profilo: tag del perimetro, testo per l'utente, condizione che l'AI verifica sui documenti. */
  public record Question(String tag, String label, String hint, String condition) { }

  public static final List<Question> QUESTIONS = List.of(
      new Question("quasi_macchina", "È una quasi-macchina, da incorporare in un'altra macchina?", "Altrimenti è una macchina completa", "Il prodotto è una quasi-macchina (partly completed machinery) da incorporare in un'altra macchina, con dichiarazione di incorporazione (Declaration of Incorporation)"),
      new Question("mobilita", "Si sposta durante il lavoro?", "Semovente, trainata o su ruote", "La macchina si sposta durante il lavoro, è semovente o trainata"),
      new Question("sollevamento", "Solleva o movimenta carichi?", "Gru, paranchi, carrelli elevatori", "La macchina solleva carichi o li movimenta in quota"),
      new Question("sollevamento_persone", "Solleva o trasporta persone?", "Piattaforme di lavoro, ascensori di cantiere", "La macchina solleva o trasporta persone"),
      new Question("portatili", "È portatile o tenuta/condotta a mano?", "Utensili a motore", "La macchina è portatile, tenuta o condotta a mano"),
      new Question("alimentari", "Lavora alimenti, cosmetici o farmaci?", "", "La macchina è destinata a lavorare prodotti alimentari, cosmetici o farmaceutici"),
      new Question("legno", "Lavora legno o materiali simili?", "", "La macchina è destinata alla lavorazione del legno o di materiali con caratteristiche fisiche simili"),
      new Question("fitosanitari", "Applica prodotti fitosanitari?", "Irroratrici, atomizzatori", "La macchina è destinata all'applicazione di prodotti fitosanitari"),
      new Question("sotterraneo", "È usata in lavori sotterranei?", "Gallerie, miniere", "La macchina è destinata a lavori sotterranei"),
      new Question("cra", "Ha software, rete o accesso remoto?", "PLC/HMI collegabili, Ethernet, Wi-Fi, USB, teleassistenza: si applica il Cyber Resilience Act",
          "Il prodotto ha software o elettronica programmabile con interfacce di rete o di comunicazione (Ethernet, Wi-Fi, USB, accesso remoto, network)"));

  private final List<Req> reqs = new ArrayList<>();
  private final Map<String, Req> byId = new HashMap<>();
  private final JsonNode laws;
  private final String schedeVersion;

  public Catalog() throws Exception {
    ObjectMapper m = new ObjectMapper();
    JsonNode cat, sch;
    try (InputStream a = Catalog.class.getResourceAsStream("/normative/requirements.json"); InputStream b = Catalog.class.getResourceAsStream("/normative/schede.json")) {
      cat = m.readTree(a); sch = b != null ? m.readTree(b) : m.createObjectNode();
    }
    laws = cat.path("laws");
    schedeVersion = sch.path("schema").asText("nessuna");
    for (JsonNode r : cat.path("requirements")) {
      if (!r.path("assessable").asBoolean()) continue;
      String id = r.path("id").asText();
      JsonNode s = sch.path("schede").path(id);
      Sheet sheet = s.isMissingNode() ? fallback(r.path("text").asText()) :
          new Sheet(s.path("condition").asText(""), specs(s.path("points")), s.path("status").asText("bozza"));
      List<String> std = new ArrayList<>();
      for (JsonNode x : r.path("standards")) if (std.size() < 4) std.add(x.path("code").asText());
      Req q = new Req(id, r.path("law").asText(), r.path("section").asText(), r.path("group").asText(), r.path("ref").asText(), shortRef(r.path("ref").asText()),
          r.path("title").asText(), r.path("text").asText(), m.convertValue(r.path("pages"), List.class), m.convertValue(r.path("tags"), List.class),
          r.path("always").asBoolean(), std, sheet, null);
      q = new Req(q.id(), q.law(), q.section(), q.group(), q.ref(), q.shortRef(), q.title(), q.text(), q.pages(), q.tags(), q.always(), q.standards(), q.sheet(), areaOf(q));
      reqs.add(q); byId.put(id, q);
    }
  }

  static List<Spec> specs(JsonNode points) {
    List<Spec> out = new ArrayList<>();
    for (JsonNode p : points) {
      if (p.isTextual()) { out.add(claim(p.asText())); continue; } // formato precedente: frase libera
      JsonNode n = p.path("num");
      Num num = n.isObject() ? new Num(n.path("what").asText(""), n.path("unit").asText(""), n.hasNonNull("min") ? n.get("min").asDouble() : null,
          n.hasNonNull("max") ? n.get("max").asDouble() : null) : null;
      String doc = p.path("doc").asText(null);
      out.add(new Spec(p.path("q").asText(), DOC_TYPES.containsKey(doc) ? doc : null, p.path("ok").asText(""), p.path("no").asText(""),
          p.path("smentita").asText(null), p.path("quasi").asText(null), num));
    }
    return out;
  }

  /** Scheda di riserva se manca quella generata: lettere a), b)… del testo o frasi principali. */
  static Sheet fallback(String text) {
    List<String> pts = new ArrayList<>();
    Matcher mt = Pattern.compile("(?:^|\\s)([a-z])\\)\\s+([^;]{12,}?)(?=;|\\.\\s|$)").matcher(text);
    while (mt.find() && pts.size() < 4) pts.add(cut(mt.group(1) + ") " + mt.group(2), 160));
    if (pts.size() < 2) { pts.clear(); for (String s : text.split("(?<=[.;:])\\s+")) if (s.length() > 30 && pts.size() < 3) pts.add(cut(s.trim(), 160)); }
    if (pts.isEmpty()) pts.add(cut(text, 160));
    return new Sheet("", pts.stream().map(Catalog::claim).toList(), "automatica");
  }

  static String cut(String s, int n) { return s.length() > n ? s.substring(0, n - 1).replaceAll("\\s+\\S*$", "") + "…" : s; }

  static String shortRef(String ref) {
    return ref.replaceAll(", elenco \\d+, lettera ", " ").replaceAll(", lettera ", " ").replaceAll(", comma (\\d+)", " c.$1")
        .replaceAll("^All\\. III, parte B, punto ", "III-B.").replaceAll("^All\\. III, punto ", "")
        .replaceAll("^All\\. I, parte (I+), punto ", "$1.").replaceAll("^All\\. IV, parte ([AB])", "IV-$1").replaceAll("^All\\. V, parte ([AB])", "V-$1")
        .replaceAll("^All\\. (\\w+), punto ", "$1.").replaceAll("^Allegato V", "V").replaceAll("^Art\\. (\\d+), par\\. (\\d+)", "art. $1($2)");
  }

  static String areaOf(Req r) {
    if ("MR-III".equals(r.section())) {
      String g = r.group().replaceFirst("^Allegato III — ", "");
      Matcher mt = Pattern.compile("^(\\d)(\\.\\d)?\\.?\\s").matcher(g);
      if (!mt.find()) return "Reg. Macchine — 1.1 Considerazioni generali";
      return "Reg. Macchine — " + switch (mt.group(1)) {
        case "1" -> g.replaceFirst("^(\\d\\.\\d)\\. ", "$1 ");
        case "2" -> "2 Categorie particolari"; case "3" -> "3 Mobilità"; case "4" -> "4 Sollevamento";
        case "5" -> "5 Lavori sotterranei"; default -> "6 Sollevamento di persone"; };
    }
    if ("MR".equals(r.law())) return "Reg. Macchine — Obblighi, documentazione e dichiarazioni";
    if (r.group().contains("parte I —")) return "CRA — Proprietà di sicurezza (All. I, parte I)";
    if (r.group().contains("parte II")) return "CRA — Gestione delle vulnerabilità (All. I, parte II)";
    return "CRA — Obblighi, informazioni e documentazione";
  }

  /** Requisiti nel perimetro: tag del profilo; i requisiti "sempre applicabili" del Reg. Macchine entrano comunque. */
  public List<Req> scope(Collection<String> tags) {
    return reqs.stream().filter(r -> (r.always() && ("MR".equals(r.law()) || tags.contains("cra"))) || r.tags().stream().anyMatch(tags::contains)).toList();
  }

  public List<Req> all() { return reqs; }
  public Req get(String id) { return byId.get(id); }
  public JsonNode laws() { return laws; }
  public String schedeVersion() { return schedeVersion; }
}
