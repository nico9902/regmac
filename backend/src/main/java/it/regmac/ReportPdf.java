package it.regmac;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.Map;
import it.regmac.ChecklistService.Checklist;
import it.regmac.ChecklistService.Details;
import it.regmac.ChecklistService.EvidenceRef;
import it.regmac.ChecklistService.Item;
import it.regmac.ChecklistService.Row;
import it.regmac.ChecklistService.Signature;

import static it.regmac.ChecklistService.STATUSES;

/** Rapporto finale in PDF (A4), generato lato server con OpenPDF. */
final class ReportPdf {
    record Result(byte[] bytes, int pages) {}

    private static final Color INK = new Color(0x26, 0x3b, 0x3c), MUTED = new Color(0x6b, 0x77, 0x73), LINE = new Color(0xd5, 0xdd, 0xd7),
            HEAD = new Color(0xf1, 0xf4, 0xf1), OK = new Color(0x2c, 0x7a, 0x4f), KO = new Color(0xa8, 0x40, 0x2d),
            PART = new Color(0x9a, 0x6a, 0x12), TODO = new Color(0x6a, 0x4f, 0xa0);
    private static final Font H1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 17, INK), H2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11.5f, INK),
            H3 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, INK), BODY = FontFactory.getFont(FontFactory.HELVETICA, 9, INK),
            SMALL = FontFactory.getFont(FontFactory.HELVETICA, 7.5f, MUTED), CELL = FontFactory.getFont(FontFactory.HELVETICA, 7.5f, INK),
            CELLB = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f, INK), EYEBROW = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f, MUTED);
    private static final Map<String, String> LABEL = Map.of("COMPLIANT", "Conforme", "PARTIAL", "Parzialmente conforme", "NON_COMPLIANT", "Non conforme",
            "NOT_APPLICABLE", "Non applicabile", "TO_ASSESS", "Da valutare");
    private static final Map<String, Color> COLOR = Map.of("COMPLIANT", OK, "PARTIAL", PART, "NON_COMPLIANT", KO, "NOT_APPLICABLE", MUTED, "TO_ASSESS", TODO);
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("Europe/Rome"));

    private ReportPdf() {}

    @SuppressWarnings("unchecked")
    static Result render(Map<String, Object> rep) {
        Checklist c = (Checklist) rep.get("checklist");
        Domain.Machine m = (Domain.Machine) rep.get("machine");
        List<Row> rows = (List<Row>) rep.get("rows");
        Map<String, Integer> counts = (Map<String, Integer>) rep.get("counts");
        Map<String, Object> cat = (Map<String, Object>) rep.get("catalog");
        boolean draft = !"CLOSED".equals(c.status());
        Details d = c.details();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 42, 42, 54, 48);
        try {
            PdfWriter w = PdfWriter.getInstance(doc, out);
            Footer footer = new Footer(t(m.name()) + " — " + t(c.title()) + " — Rev. " + c.rev(), draft, c.id(), String.valueOf(cat.get("sha256")));
            w.setPageEvent(footer);
            doc.addTitle("Rapporto di valutazione della conformità — " + t(m.name()));
            doc.addCreator("RegMac");
            doc.open();

            doc.add(p("RAPPORTO DI VALUTAZIONE DELLA CONFORMITÀ", EYEBROW, 2));
            doc.add(p(m.name() + (blank(d.model()) ? "" : " — " + d.model()), H1, 3));
            doc.add(p(c.title() + " · Revisione " + c.rev() + " · Regolamento (UE) 2023/1230"
                    + (c.profile().contains("cra") ? " · Regolamento (UE) 2024/2847 (Cyber Resilience Act)" : "")
                    + (m.synthetic() ? " · CASO SINTETICO" : "") + (draft ? " · BOZZA NON FIRMATA" : ""), SMALL, 10));

            h2(doc, "1. Dati identificativi");
            PdfPTable kv = table(new float[]{1.3f, 3.7f});
            String[][] fields = {{"Macchina", m.name()}, {"Modello / tipo", d.model()}, {"N. di serie / matricola", d.serial()}, {"Anno di costruzione", d.year()},
                    {"Fabbricante", d.manufacturer()}, {"Committente / utilizzatore", d.client()}, {"Descrizione", d.description()},
                    {"Uso previsto e limiti", d.intendedUse()}, {"Valutatore", join(d.assessor(), d.organization())},
                    {"Data della valutazione", date(d.assessmentDate())}, {"Luogo / note", d.location()}};
            for (String[] f : fields) if (!blank(f[1])) { kv.addCell(cell(f[0], CELLB, HEAD)); kv.addCell(cell(f[1], CELL, null)); }
            doc.add(kv);

            h2(doc, "2. Ambito della valutazione");
            List<Map<String, Object>> tags = (List<Map<String, Object>>) cat.get("profileTags");
            doc.add(p("Profilo applicato: " + label(tags, c.profile(), true) + ".", BODY, 3));
            doc.add(p("Esclusi dal profilo: " + label(tags, c.profile(), false) + ".", BODY, 3));
            List<?> sources = (List<?>) cat.get("generatedFrom");
            StringBuilder src = new StringBuilder();
            for (Object o : sources) {
                var s = (RequirementCatalog.Source) o;
                src.append(src.isEmpty() ? "" : "; ").append(s.filename()).append(" (SHA-256 ").append(s.sha256(), 0, 12).append("…)");
            }
            doc.add(p("Requisiti considerati: " + rows.size() + ". Fonti: testi originali in Gazzetta ufficiale (non consolidati) — " + src + ". Catalogo "
                    + String.valueOf(cat.get("sha256")).substring(0, 12) + ".", SMALL, 6));

            h2(doc, "3. Sintesi dei risultati");
            String verdict = String.valueOf(rep.get("verdict"));
            Color vc = switch (verdict) { case "NON_COMPLIANT" -> KO; case "COMPLIANT" -> OK; case "PARTIAL" -> PART; default -> TODO; };
            int nc0 = counts.get("NON_COMPLIANT"), p0 = counts.get("PARTIAL"), td0 = counts.get("TO_ASSESS");
            String vt = switch (verdict) {
                case "NON_COMPLIANT" -> "NON CONFORME — " + n(nc0, "requisito non soddisfatto", "requisiti non soddisfatti")
                        + (p0 > 0 ? ", " + n(p0, "parzialmente conforme", "parzialmente conformi") : "") + (td0 > 0 ? ", " + n(td0, "da valutare", "da valutare") : "");
                case "INCOMPLETE" -> "VALUTAZIONE NON COMPLETATA — " + n(td0, "requisito da valutare", "requisiti da valutare");
                case "PARTIAL" -> "CONFORMITÀ PARZIALE — " + n(p0, "requisito parzialmente conforme", "requisiti parzialmente conformi") + ", nessuna non conformità";
                default -> "CONFORME — tutti i " + rep.get("applicable") + " requisiti applicabili valutati risultano conformi";
            };
            PdfPTable vtab = table(new float[]{1});
            PdfPCell vcell = cell("Esito: " + vt + ".", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, vc), new Color(0xf6, 0xf8, 0xf6));
            vcell.setPadding(8);
            vcell.setBorderColor(vc);
            vtab.addCell(vcell);
            doc.add(vtab);
            doc.add(p("Indice di conformità (conformi / requisiti applicabili): " + rep.get("complianceIndex") + "% (" + counts.get("COMPLIANT") + " su " + rep.get("applicable") + ").", BODY, 6));
            List<Map<String, Object>> laws = (List<Map<String, Object>>) cat.get("laws");
            PdfPTable lt = table(new float[]{3, 1, 1, 1, 1, 1, 1});
            header(lt, "Regolamento", "Conformi", "Parziali", "Non conf.", "N/A", "Da valutare", "Totale");
            for (Map<String, Object> l : laws) {
                List<Row> rs = rows.stream().filter(r -> r.requirement().law().equals(l.get("id"))).toList();
                if (rs.isEmpty()) continue;
                lt.addCell(cell(l.get("short") + " " + l.get("code"), CELL, null));
                for (String s : STATUSES) lt.addCell(num(rs.stream().filter(r -> r.status().equals(s)).count()));
                lt.addCell(num(rs.size()));
            }
            doc.add(lt);
            doc.add(p("Dettaglio per sezione", H3, 4));
            PdfPTable gt = table(new float[]{4, 1, 1, 1, 1, 1, 1});
            header(gt, "Sezione", "C", "P", "NC", "N/A", "Da val.", "Tot.");
            LinkedHashMap<String, List<Row>> groups = new LinkedHashMap<>();
            for (Row r : rows) groups.computeIfAbsent(r.requirement().law() + " — " + r.requirement().group(), k -> new ArrayList<>()).add(r);
            groups.forEach((g, rs) -> {
                gt.addCell(cell(g, CELL, null));
                for (String s : STATUSES) { long n = rs.stream().filter(r -> r.status().equals(s)).count(); gt.addCell(n == 0 ? cell("", CELL, null) : num(n)); }
                gt.addCell(num(rs.size()));
            });
            doc.add(gt);

            h2(doc, "4. Non conformità e azioni correttive");
            List<Row> nc = rows.stream().filter(r -> r.status().equals("NON_COMPLIANT") || r.status().equals("PARTIAL"))
                    .sorted(Comparator.comparing(r -> r.status().equals("NON_COMPLIANT") ? 0 : 1)).toList();
            if (nc.isEmpty()) doc.add(p("Nessuna non conformità o conformità parziale registrata.", BODY, 4));
            else {
                PdfPTable t = table(new float[]{0.4f, 1.6f, 2f, 1f, 2.4f, 2.4f, 1.2f});
                header(t, "#", "Riferimento", "Requisito", "Esito", "Rilievo / evidenze", "Azione correttiva", "Resp. / scadenza");
                int i = 1;
                for (Row r : nc) {
                    Item it = r.item();
                    t.addCell(num(i++));
                    t.addCell(cell(r.requirement().law() + " · " + r.requirement().ref(), CELL, null));
                    t.addCell(cell(r.requirement().title(), CELL, null));
                    t.addCell(status(r.status()));
                    t.addCell(cell(join(it.note(), evidence(it)), CELL, null));
                    t.addCell(cell(it.action(), CELL, null));
                    t.addCell(cell(join(it.owner(), date(it.due())), CELL, null));
                }
                doc.add(t);
            }

            h2(doc, "5. Requisiti non ancora valutati");
            List<Row> todo = rows.stream().filter(r -> r.status().equals("TO_ASSESS")).toList();
            if (todo.isEmpty()) doc.add(p("Tutti i requisiti considerati sono stati valutati.", BODY, 4));
            else {
                doc.add(p(todo.size() + " requisiti risultano ancora da valutare.", BODY, 4));
                PdfPTable t = table(new float[]{1, 2, 4});
                header(t, "Regolamento", "Riferimento", "Requisito");
                for (Row r : todo) { t.addCell(cell(r.requirement().law(), CELL, null)); t.addCell(cell(r.requirement().ref(), CELL, null)); t.addCell(cell(r.requirement().title(), CELL, null)); }
                doc.add(t);
            }

            h2(doc, "6. Requisiti non applicabili");
            List<Row> na = rows.stream().filter(r -> r.status().equals("NOT_APPLICABLE")).toList();
            if (na.isEmpty()) doc.add(p("Nessun requisito dichiarato non applicabile oltre alle esclusioni di profilo.", BODY, 4));
            else {
                PdfPTable t = table(new float[]{2, 3, 4});
                header(t, "Riferimento", "Requisito", "Motivazione");
                for (Row r : na) {
                    t.addCell(cell(r.requirement().law() + " · " + r.requirement().ref(), CELL, null));
                    t.addCell(cell(r.requirement().title() + (r.requirement().always() ? " (si applica in ogni caso)" : ""), CELL, null));
                    t.addCell(cell(blank(r.item().note()) ? "motivazione mancante" : r.item().note(), CELL, null));
                }
                doc.add(t);
            }

            h2(doc, "7. Conclusioni");
            doc.add(p(blank(c.conclusions()) ? "—" : c.conclusions(), BODY, 6));

            h2(doc, "8. Firma");
            if (draft) doc.add(p("Bozza non firmata: il rapporto definitivo si ottiene chiudendo la checklist con la firma del valutatore.", BODY, 6));
            else {
                Signature s = c.signature();
                doc.add(p("Rapporto firmato elettronicamente da " + s.displayName() + " (utente " + s.username() + ") il " + instant(s.signedAt())
                        + ", previa conferma della password personale." + (blank(s.note()) ? "" : " Nota: " + s.note()), BODY, 3));
                doc.add(p("L'impronta SHA-256 di questo file è registrata nel sistema (checklist " + c.id() + ") e nell'audit trail: ogni copia può essere verificata confrontando l'impronta.", SMALL, 6));
            }
            PdfPTable sign = table(new float[]{1, 1});
            PdfPCell s1 = cell("Il valutatore — " + (draft ? Objects.toString(d.assessor(), "") : c.signature().displayName()), SMALL, null);
            PdfPCell s2 = cell("Data e firma" + (draft ? "" : " — " + instant(c.signature().signedAt())), SMALL, null);
            for (PdfPCell sc : List.of(s1, s2)) { sc.setBorder(Rectangle.TOP); sc.setPaddingTop(4); sc.setFixedHeight(42); sign.addCell(sc); }
            sign.setSpacingBefore(26);
            doc.add(sign);

            doc.newPage();
            h2(doc, "Allegato — Checklist completa");
            PdfPTable full = table(new float[]{0.6f, 1.6f, 2.4f, 1f, 2.8f, 2.4f});
            full.setHeaderRows(1);
            header(full, "Reg.", "Riferimento", "Requisito", "Esito", "Evidenze / documenti / norme", "Rilievo");
            for (Row r : rows) {
                Item it = r.item();
                full.addCell(cell(r.requirement().law(), CELL, null));
                full.addCell(cell(r.requirement().ref(), CELL, null));
                full.addCell(cell(r.requirement().title() + (r.textChanged() ? " [testo normativo aggiornato dopo la valutazione]" : ""), CELL, null));
                full.addCell(status(r.status()));
                full.addCell(cell(it == null ? "" : join(evidence(it), it.standard()), CELL, null));
                full.addCell(cell(it == null ? "" : join(it.note(), blank(it.assessedBy()) ? "" : "Valutato da " + it.assessedBy()), CELL, null));
            }
            doc.add(full);
            doc.add(p(String.valueOf(cat.get("disclaimer")) + " Il rapporto documenta l'esito della verifica dei requisiti secondo il profilo indicato; non sostituisce la "
                    + "procedura di valutazione della conformità (art. 25 Reg. (UE) 2023/1230, art. 32 Reg. (UE) 2024/2847) né l'eventuale intervento di un organismo notificato. "
                    + "Generato il " + instant(String.valueOf(rep.get("generatedAt"))) + ".", SMALL, 0));
            doc.close();
            return new Result(out.toByteArray(), footer.pages);
        } catch (DocumentException e) {
            throw new IllegalStateException("Generazione PDF non riuscita", e);
        }
    }

    // ------------------------------------------------------------------ intestazione e piè di pagina
    private static final class Footer extends PdfPageEventHelper {
        final String title, id, catalog;
        final boolean draft;
        PdfTemplate total;
        int pages;
        Footer(String title, boolean draft, String id, String catalog) { this.title = title; this.draft = draft; this.id = id; this.catalog = catalog; }
        @Override public void onOpenDocument(PdfWriter w, Document d) { total = w.getDirectContent().createTemplate(40, 12); }
        @Override public void onEndPage(PdfWriter w, Document d) {
            PdfContentByte cb = w.getDirectContent();
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, new Phrase(t(title), SMALL), d.left(), d.top() + 18, 0);
            String left = "Checklist " + id.substring(0, 8) + " · catalogo " + catalog.substring(0, 12) + (draft ? " · BOZZA" : "");
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, new Phrase(left, SMALL), d.left(), d.bottom() - 22, 0);
            String pg = "Pag. " + w.getPageNumber() + " di ";
            float x = d.right() - 40;
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT, new Phrase(pg, SMALL), x, d.bottom() - 22, 0);
            cb.addTemplate(total, x, d.bottom() - 22);
            cb.setColorStroke(LINE);
            cb.moveTo(d.left(), d.top() + 12); cb.lineTo(d.right(), d.top() + 12); cb.stroke();
            if (draft) {
                PdfContentByte under = w.getDirectContentUnder();
                PdfGState gs = new PdfGState();
                gs.setFillOpacity(0.07f);
                under.saveState();
                under.setGState(gs);
                ColumnText.showTextAligned(under, Element.ALIGN_CENTER, new Phrase("BOZZA", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 110, KO)),
                        (d.left() + d.right()) / 2, (d.top() + d.bottom()) / 2, 45);
                under.restoreState();
            }
        }
        @Override public void onCloseDocument(PdfWriter w, Document d) {
            pages = w.getPageNumber() - 1;
            ColumnText.showTextAligned(total, Element.ALIGN_LEFT, new Phrase(String.valueOf(pages), SMALL), 0, 2, 0);
        }
    }

    // ------------------------------------------------------------------ supporto
    private static final CharsetEncoder CP1252 = Charset.forName("windows-1252").newEncoder();

    /** Il font standard Helvetica copre Windows-1252: gli altri caratteri vengono sostituiti. */
    static String t(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if (ch == '­') continue;
            synchronized (CP1252) { b.append(CP1252.canEncode(ch) || ch == '\n' ? ch : switch (ch) { case '≤' -> "<="; case '≥' -> ">="; case 'μ' -> "u"; case '‐', '‑' -> "-"; default -> "?"; }); }
        }
        return b.toString();
    }

    private static Paragraph p(String s, Font f, float after) {
        Paragraph p = new Paragraph(t(s), f);
        p.setSpacingAfter(after);
        p.setLeading(f.getSize() * 1.35f);
        return p;
    }

    private static void h2(Document doc, String s) throws DocumentException {
        Paragraph p = p(s, H2, 6);
        p.setSpacingBefore(12);
        doc.add(p);
    }

    private static PdfPTable table(float[] widths) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setSpacingAfter(6);
        return t;
    }

    private static PdfPCell cell(String s, Font f, Color bg) {
        PdfPCell c = new PdfPCell(new Phrase(t(s), f));
        c.setBorderColor(LINE);
        c.setPadding(4);
        if (bg != null) c.setBackgroundColor(bg);
        return c;
    }

    private static void header(PdfPTable t, String... cols) { for (String col : cols) t.addCell(cell(col, CELLB, HEAD)); }

    private static PdfPCell num(long n) {
        PdfPCell c = cell(String.valueOf(n), CELL, null);
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return c;
    }

    private static PdfPCell status(String s) { return cell(LABEL.get(s), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f, COLOR.get(s)), null); }

    private static String evidence(Item it) {
        List<String> parts = new ArrayList<>();
        if (!blank(it.evidence())) parts.add(it.evidence());
        if (it.evidenceRefs() != null)
            for (EvidenceRef e : it.evidenceRefs())
                parts.add("Doc.: " + e.name() + (blank(e.locator()) ? "" : ", " + e.locator()) + " (SHA-256 " + e.sha256().substring(0, 12) + "…)");
        return String.join("\n", parts);
    }

    private static String label(List<Map<String, Object>> tags, List<String> profile, boolean included) {
        String s = tags.stream().filter(x -> profile.contains(String.valueOf(x.get("id"))) == included).map(x -> String.valueOf(x.get("label")))
                .reduce((a, b) -> a + "; " + b).orElse("");
        return s.isEmpty() ? "nessuno" : s;
    }

    private static String join(String a, String b) {
        if (blank(a)) return blank(b) ? "" : b;
        return blank(b) ? a : a + "\n" + b;
    }

    private static String date(String iso) { return blank(iso) ? "" : String.join("/", new ArrayDeque<>(List.of(iso.substring(0, 10).split("-"))).reversed()); }

    private static String instant(String iso) {
        try { return DT.format(Instant.parse(iso)); } catch (Exception e) { return Objects.toString(iso, ""); }
    }

    private static String n(int count, String one, String many) { return count + " " + (count == 1 ? one : many); }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
