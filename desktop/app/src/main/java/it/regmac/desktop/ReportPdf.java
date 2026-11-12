package it.regmac.desktop;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.*;
import it.regmac.desktop.Store.*;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Report di pre-assessment in PDF: score e massimo, aree, gap, documenti da richiedere, dettaglio, metodo. */
@Component
public class ReportPdf {
  static final Color NAVY = new Color(0x0B, 0x1F, 0x3A), BLUE = new Color(0x15, 0x5E, 0xEF), LIGHT = new Color(0xBF, 0xD3, 0xFB),
      GREY = new Color(0x64, 0x74, 0x8B), LINE = new Color(0xE2, 0xE8, 0xF0), RED = new Color(0xDC, 0x26, 0x26), AMBER = new Color(0xB4, 0x53, 0x09), GREEN = new Color(0x16, 0xA3, 0x4A);
  static final Font H1 = new Font(Font.HELVETICA, 18, Font.BOLD, NAVY), H2 = new Font(Font.HELVETICA, 12, Font.BOLD, NAVY), B = new Font(Font.HELVETICA, 9),
      BB = new Font(Font.HELVETICA, 9, Font.BOLD), S = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, GREY), Q = new Font(Font.TIMES_ROMAN, 9, Font.ITALIC, new Color(0x33, 0x41, 0x55)),
      BIG = new Font(Font.HELVETICA, 34, Font.BOLD, NAVY);
  static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

  public byte[] render(Fascicolo f, List<Doc> docs, Views.Assessment a, Profile author, String schede) throws Exception {
    var out = new ByteArrayOutputStream();
    Document doc = new Document(PageSize.A4, 46, 46, 70, 54);
    PdfWriter w = PdfWriter.getInstance(doc, out);
    w.setPageEvent(new Footer(f, author));
    doc.addTitle("Report di pre-assessment — " + f.name());
    doc.open();
    var s = a.summary();

    doc.add(p("REPORT DI PRE-ASSESSMENT", new Font(Font.HELVETICA, 8, Font.BOLD, GREY), 2));
    doc.add(p(t(f.name()), H1, 4));
    doc.add(p(t(String.join(" · ", java.util.stream.Stream.of(nz("Modello " + f.model(), f.model()), nz("matr. " + f.serial(), f.serial()), nz(f.year(), f.year()), nz(f.maker(), f.maker())).filter(x -> !x.isBlank()).toList())), B, 12));

    // score
    PdfPTable box = new PdfPTable(new float[]{1.1f, 2.4f}); box.setWidthPercentage(100);
    if (s.notEvaluable()) {
      PdfPCell c = cell(t("NON VALUTABILE — evidenze riconosciute insufficienti.\nIl sistema ha riconosciuto evidenze per il " + pct(s.coverage()) + " dei punti di controllo (soglia "
          + pct(Views.MIN_COVERAGE) + "): lo score non viene mostrato. Verificare profilo, lettura e ricerca prima di attribuire lacune ai documenti."), new Font(Font.HELVETICA, 10, Font.BOLD, AMBER));
      c.setColspan(2); c.setBorderColor(AMBER); c.setBorderWidth(1.2f); c.setPadding(10); box.addCell(c);
    } else {
      PdfPCell c1 = new PdfPCell(); c1.setBorder(Rectangle.NO_BORDER);
      c1.addElement(p("COMPLIANCE SCORE", new Font(Font.HELVETICA, 7, Font.BOLD, GREY), 0)); c1.addElement(p(pct(s.score()), BIG, 0));
      box.addCell(c1);
      PdfPCell c2 = new PdfPCell(); c2.setBorder(Rectangle.NO_BORDER); c2.setPaddingTop(10);
      c2.addElement(p(t("Può arrivare fino a " + pct(s.max()) + " con le evidenze mancanti · copertura delle evidenze " + pct(s.coverage())), BB, 4));
      c2.addElement(p(t(s.applicable() + " requisiti applicabili · " + s.full() + " pienamente soddisfatti · " + s.gapsTech() + " con possibili non conformità · "
          + s.gapsMissing() + " con documento assente · " + s.gapsEvidence() + " con evidenza mancante · " + s.na() + " non applicabili o da confermare"
          + (s.integrator() > 0 ? " · " + s.integrator() + " punti a carico dell'integratore (esclusi dallo score)" : "")), B, 6));
      c2.addElement(bar(s.score(), s.max()));
      box.addCell(c2);
    }
    doc.add(box);
    for (Doc source : docs) if (source.error() != null) doc.add(p(t("DOCUMENTO NON ANALIZZATO: " + source.name() + " — " + source.error()), S, 6));
    if (s.confirmed() < s.done()) doc.add(p(t("Esiti rivisti da una persona: " + s.confirmed() + " su " + s.done() + ". Gli altri sono proposte dell'AI non ancora riviste."), S, 6));

    doc.add(p("1. Score per area", H2, 6));
    PdfPTable ar = new PdfPTable(new float[]{5f, 1f, 1f, 2.2f}); ar.setWidthPercentage(100);
    head(ar, "Area", "Score", "Max", "");
    for (var x : a.areas()) {
      ar.addCell(cell(t(x.name()) + " (" + x.n() + ")", B)); ar.addCell(cell(pct(x.score()), BB)); ar.addCell(cell(pct(x.max()), S));
      PdfPCell c = new PdfPCell(); c.setBorderColor(LINE); c.setPadding(6); c.addElement(bar(x.score(), x.max())); ar.addCell(c);
    }
    doc.add(ar);

    // possibili non conformità in evidenza: da confermare da parte di un revisore
    var ncs = a.gaps().stream().filter(x -> Views.NC.equals(x.kind())).toList();
    doc.add(p("2. Possibili non conformità (da confermare)", H2, 6));
    if (ncs.isEmpty()) doc.add(p("Nessuna: nessun documento smentisce esplicitamente un punto di controllo.", B, 4));
    for (var x : ncs) for (var pt : x.points()) if (Views.NC.equals(pt.motive())) {
      doc.add(p(t(x.ref() + " " + x.title() + " — " + pt.text()), BB, 1));
      if (pt.quote() != null) doc.add(p(t("«" + pt.quote() + "» — " + pt.doc() + (pt.page() != null && pt.page() > 1 ? ", p. " + pt.page() : "")), Q, 1));
      doc.add(p(pt.reviewed() ? "Confermata dal revisore." : "Proposta dell'AI, non ancora confermata da un revisore.", S, 5));
    }

    doc.add(p("3. Gap per requisito", H2, 6));
    var others = a.gaps().stream().filter(x -> !Views.INTEGRATOR.equals(x.kind())).toList();
    if (others.isEmpty()) doc.add(p("Nessun gap.", B, 4));
    PdfPTable g = new PdfPTable(new float[]{1.1f, 1.4f, 6f}); g.setWidthPercentage(100); g.setHeaderRows(1);
    head(g, "Requisito", "Motivo", "Punti non soddisfatti e documento atteso");
    for (var x : others) {
      g.addCell(cell(t(x.ref()), BB));
      g.addCell(cell(t(x.kind()) + (x.priority() < 3 ? "\nal " + pct(x.value()) : ""), new Font(Font.HELVETICA, 8.5f, Font.BOLD, x.priority() == 1 ? RED : x.priority() == 2 ? GREY : AMBER)));
      PdfPCell c = new PdfPCell(); c.setBorderColor(LINE); c.setPadding(5);
      c.addElement(p(t(x.title()), BB, 2));
      for (var pt : x.points()) {
        if (Views.INTEGRATOR.equals(pt.motive())) continue;
        c.addElement(p(t(pt.motive() + ": " + pt.text()), B, 0));
        c.addElement(p(t("Documento atteso: " + pt.expectedLabel()), S, 2));
      }
      g.addCell(c);
    }
    doc.add(g);

    var integ = a.gaps().stream().flatMap(x -> x.points().stream().filter(pt -> Views.INTEGRATOR.equals(pt.motive())).map(pt -> x.ref() + " " + pt.text() + " (" + pt.expectedLabel() + ")")).toList();
    if (!integ.isEmpty()) {
      doc.add(p("Punti a carico dell'integratore (quasi-macchina, esclusi dallo score)", BB, 3));
      for (String x : integ) doc.add(p("• " + t(x), S, 1));
    }

    doc.add(p("4. Documenti da richiedere", H2, 6));
    if (a.docsNeeded().isEmpty()) doc.add(p("Nessuno.", B, 4));
    for (var d : a.docsNeeded()) doc.add(p("• " + t(d.label() + (d.integrator() ? " — a carico dell'integratore" : d.missing() ? " — assente dal fascicolo" : " — presente ma senza le evidenze richieste")
        + " (" + d.points() + " punti, " + d.requirements() + " requisiti)"), B, 2));

    doc.add(p("5. Documenti esaminati", H2, 6));
    PdfPTable dt = new PdfPTable(new float[]{5f, 1f, 3.2f}); dt.setWidthPercentage(100);
    head(dt, "Documento e tipo", "Pagine", "Impronta SHA-256");
    for (Doc d : docs) { dt.addCell(cell(t(d.name()) + "\n" + t(String.join(", ", d.typesOrEmpty().stream().map(Views::label).toList())) + (d.warning() != null ? "\n" + t(d.warning()) : ""), B));
      dt.addCell(cell(String.valueOf(d.pages()), B)); dt.addCell(cell(d.sha256(), S)); }
    doc.add(dt);

    doc.newPage();
    doc.add(p("6. Dettaglio per requisito", H2, 6));
    PdfPTable rt = new PdfPTable(new float[]{1.1f, 4.6f, 1.4f, 0.9f}); rt.setWidthPercentage(100); rt.setHeaderRows(1);
    head(rt, "Requisito", "Titolo e punti di controllo", "Esito", "Valore");
    for (var r : a.requirements()) {
      if (r.result() == null) continue;
      rt.addCell(cell(t(r.ref()), BB));
      PdfPCell c = new PdfPCell(); c.setBorderColor(LINE); c.setPadding(4);
      c.addElement(p(t(r.title()), BB, 1));
      if ("TRUE".equals(r.applicability())) for (Point pt : r.result().points())
        c.addElement(p(t(("S".equals(pt.effective()) ? "✓ " : "C".equals(pt.effective()) ? "✗ " : "? ") + pt.text() + (pt.user() != null ? " [corretto dal revisore]" : "")), S, 0));
      rt.addCell(c);
      rt.addCell(cell(outcome(r.outcome(), r.applicability()), B));
      rt.addCell(cell(r.value() == null ? "—" : pct(r.value()), B));
    }
    doc.add(rt);

    doc.add(p("7. Metodo e limiti", H2, 6));
    doc.add(p(t("Pre-assessment documentale eseguito con AI locale (Ollama, modello " + Ollama.CHAT + ", indice " + Ollama.EMBED + "), schede di verifica " + schede
        + ". Ogni punto di controllo indica il documento atteso, cosa conta come prova e, dove previsto, cosa conta come smentita; l'AI esamina solo i documenti del tipo atteso "
        + "(se mancano il punto è un gap \"documento assente\") e i valori numerici sono confrontati dal programma. Una smentita è sempre una possibile non conformità da confermare; "
        + "ogni esito positivo o negativo cita la frase da cui deriva. Score = media sui requisiti applicabili della quota di punti supportati; massimo = come se le "
        + "evidenze mancanti risultassero favorevoli. Sotto il " + pct(Views.MIN_COVERAGE) + " di copertura delle evidenze il fascicolo è dichiarato non valutabile."), B, 4));
    doc.add(p(t("Il pre-assessment non è una valutazione della conformità ai sensi del Reg. (UE) 2023/1230 (art. 25) o del Reg. (UE) 2024/2847 (art. 32), "
        + "non sostituisce la valutazione dei rischi del fabbricante né l'intervento di un organismo notificato quando previsto. Le schede di verifica marcate come bozza "
        + "sono generate automaticamente e da rivedere."), S, 4));
    doc.close();
    return out.toByteArray();
  }

  static String outcome(String o, String app) {
    if (o == null) return "—";
    if ("NA".equals(o)) return "UNKNOWN".equals(app) ? "Da confermare" : "Non applicabile";
    return switch (o) { case "OK" -> "Soddisfatto"; case "PART" -> "Parziale"; case "NC" -> "Possibile non conformità"; case "INT" -> "A carico dell'integratore"; default -> "Senza evidenze"; };
  }

  static PdfPTable bar(double v, double max) {
    PdfPTable b = new PdfPTable(new float[]{(float) Math.max(v, 0.001), (float) Math.max(max - v, 0.001), (float) Math.max(1 - max, 0.001)});
    b.setWidthPercentage(100);
    for (Color c : new Color[]{BLUE, LIGHT, new Color(0xF5, 0xD0, 0xD0)}) { PdfPCell x = new PdfPCell(); x.setFixedHeight(5); x.setBorder(Rectangle.NO_BORDER); x.setBackgroundColor(c); b.addCell(x); }
    return b;
  }

  static void head(PdfPTable tb, String... h) {
    for (String x : h) { PdfPCell c = cell(x, new Font(Font.HELVETICA, 8, Font.BOLD, GREY)); c.setBackgroundColor(new Color(0xF8, 0xFA, 0xFC)); tb.addCell(c); }
  }

  static PdfPCell cell(String s, Font f) { PdfPCell c = new PdfPCell(new Phrase(s, f)); c.setBorderColor(LINE); c.setPadding(5); return c; }
  static Paragraph p(String s, Font f, float after) { Paragraph x = new Paragraph(s, f); x.setSpacingAfter(after); if (f == H2) x.setSpacingBefore(12); return x; }
  static String pct(double v) { return Math.round(v * 100) + "%"; }
  static String nz(String v, String test) { return test == null || test.isBlank() ? "" : v; }

  /** I font base dei PDF usano cp1252: i caratteri non rappresentabili vengono sostituiti. */
  static String t(String s) {
    if (s == null) return "";
    s = s.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"').replace("…", "...").replace('–', '-').replace('—', '-').replace("≥", ">=").replace("≤", "<=");
    var enc = java.nio.charset.Charset.forName("windows-1252").newEncoder();
    StringBuilder b = new StringBuilder();
    for (char c : s.toCharArray()) b.append(enc.canEncode(c) ? c : '?');
    return b.toString().replace(" · ", " - ").replace("·", "-");
  }

  static final class Footer extends PdfPageEventHelper {
    final Fascicolo f; final Profile author; PdfTemplate total; Image logo;
    Footer(Fascicolo f, Profile author) { this.f = f; this.author = author; }
    @Override public void onOpenDocument(PdfWriter w, Document d) { total = w.getDirectContent().createTemplate(30, 12); }
    @Override public void onEndPage(PdfWriter w, Document d) {
      PdfContentByte cb = w.getDirectContent();
      try {
        if (logo == null) { var u = ReportPdf.class.getResource("/static/favicon.png"); if (u != null) { logo = Image.getInstance(u); logo.scaleToFit(22, 22); } }
        if (logo != null) { logo.setAbsolutePosition(46, PageSize.A4.getHeight() - 44); cb.addImage(logo); }
      } catch (Exception ignored) { }
      ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, new Phrase(t((author.organization() == null || author.organization().isBlank() ? "" : author.organization() + " - ") + author.name()), BB), 74, PageSize.A4.getHeight() - 36, 0);
      ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT, new Phrase(t("Pre-assessment - " + f.name()) + " - " + D.format(java.time.Instant.now()), S), PageSize.A4.getWidth() - 46, PageSize.A4.getHeight() - 36, 0);
      cb.setColorStroke(BLUE); cb.setLineWidth(1.2f); cb.moveTo(46, PageSize.A4.getHeight() - 50); cb.lineTo(PageSize.A4.getWidth() - 46, PageSize.A4.getHeight() - 50); cb.stroke();
      String txt = "Generato con RegMac (AI locale) - Pag. " + w.getPageNumber() + " di";
      float x = PageSize.A4.getWidth() - 46 - 16;
      ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT, new Phrase(txt, S), x, 30, 0);
      cb.addTemplate(total, x + 3, 30);
    }
    @Override public void onCloseDocument(PdfWriter w, Document d) {
      ColumnText.showTextAligned(total, Element.ALIGN_LEFT, new Phrase(String.valueOf(w.getPageNumber() - 1), S), 0, 0, 0);
    }
  }
}
