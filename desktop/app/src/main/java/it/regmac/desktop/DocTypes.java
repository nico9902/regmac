package it.regmac.desktop;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Proposta automatica del tipo di documento (nome del file e parole chiave del testo), senza AI: è istantanea
 * e l'utente la conferma o la corregge nella scheda Documenti. Un documento può avere più tipi
 * (es. un manuale che riporta anche la dichiarazione di incorporazione).
 */
final class DocTypes {
  private DocTypes() { }

  private static final Map<String, Pattern> BY_NAME = new LinkedHashMap<>();
  static {
    BY_NAME.put("istruzioni", p("manual|manuale|istruzion|handbook|user guide|guida|instruction|manutenzion|maintenance|service|uso e"));
    BY_NAME.put("specifiche", p("scheda|datasheet|data sheet|specific|spec |technical data|dati tecnici|anagrafica|descrizione"));
    BY_NAME.put("dichiarazione", p("dichiaraz|declaration|\\bdoi\\b|\\bdoc\\b|\\bdoc_|conformit"));
    BY_NAME.put("valutazione_rischi", p("rischi|risk"));
    BY_NAME.put("schemi", p("schem|wiring|circuit|pneumati|idraulic|hydraulic"));
    BY_NAME.put("disegni", p("disegn|drawing|layout|assieme"));
    BY_NAME.put("rapporti_prova", p("prova|prove|test|collaudo|\\bfat\\b|misur|measure|validaz|verbale|rapporto"));
    BY_NAME.put("targa", p("targa|nameplate|marcatura|marking"));
    BY_NAME.put("cybersicurezza", p("cyber|sbom|vulnerab|sicurezza informatica"));
    BY_NAME.put("certificati", p("certific|attestat"));
  }
  /** Testo di una dichiarazione vera e propria (non la semplice menzione). */
  private static final Pattern DECLARATION = p("(declaration of (incorporation|conformity)|dichiarazione (ue |ce )?di (incorporazione|conformit))");
  private static final Pattern DECLARES = p("(hereby declare|it is declared|declares that|si dichiara|dichiara che|dichiara sotto la propria)");
  private static final Pattern CYBER = p("(software bill of materials|\\bsbom\\b|coordinated vulnerability|divulgazione coordinata|vulnerability handling|gestione delle vulnerabilit)");

  static Pattern p(String re) { return Pattern.compile(re, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); }

  /** Fascicolo tecnico completo in un solo file: contiene più documenti. */
  private static final Pattern TECH_FILE = p("technical (construction )?file|\\btcf\\b|fascicolo tecnico|dossier technique|technische dokumentation");
  private static final Pattern RISK = p("risk assessment|valutazione dei rischi|risk estimation|stima del rischio");
  private static final Pattern TESTS = p("test report|rapporto di prova|test result|verbale di collaudo|measurement report");
  private static final Pattern SCHEMES = p("circuit diagram|wiring diagram|electrical schematic|hydraulic schematic|schema elettrico|schema idraulico|schema pneumatico");
  private static final Pattern DRAWINGS = p("assembly drawing|general arrangement|disegno d'assieme|disegno di assieme");
  private static final Pattern PLATE = p("\\bnameplate\\b|rating plate|targa (dati|identificativa)");

  static int count(Pattern p, String t) { int n = 0; var m = p.matcher(t); while (m.find() && n < 50) n++; return n; }

  static List<String> propose(String fileName, int pages, String text) {
    String name = fileName.replaceAll("\\.[A-Za-z0-9]+$", "").replace('_', ' ').replace('-', ' ').toLowerCase(Locale.ROOT) + " ";
    LinkedHashSet<String> out = new LinkedHashSet<>();
    String body = text == null ? "" : text;
    boolean techFile = TECH_FILE.matcher(name).find() || TECH_FILE.matcher(body.substring(0, Math.min(body.length(), 20000))).find();
    if (techFile) out.addAll(List.of("specifiche", "dichiarazione"));
    // contenuti riconosciuti dal testo (soglie per non scambiare una menzione per il documento)
    if (count(RISK, body) >= (techFile ? 2 : 6)) out.add("valutazione_rischi");
    if (count(TESTS, body) >= (techFile ? 1 : 3)) out.add("rapporti_prova");
    if (count(SCHEMES, body) >= (techFile ? 1 : 3)) out.add("schemi");
    if (count(DRAWINGS, body) >= 1 && techFile) out.add("disegni");
    if (count(PLATE, body) >= 1 && techFile) out.add("targa");
    if (techFile && pages > 20) out.add("istruzioni");
    BY_NAME.forEach((type, re) -> { if (re.matcher(name).find()) out.add(type); });
    String t = text == null ? "" : text;
    if (DECLARATION.matcher(t).find() && DECLARES.matcher(t).find()) out.add("dichiarazione");
    if (CYBER.matcher(t).find()) out.add("cybersicurezza");
    // un documento lungo senza indicazioni è quasi sempre un manuale
    if (out.isEmpty() || (out.equals(Set.of("dichiarazione")) && pages > 10)) out.add("istruzioni");
    List<String> ordered = new ArrayList<>();
    for (String k : Catalog.DOC_TYPES.keySet()) if (out.contains(k)) ordered.add(k);
    return ordered;
  }
}
