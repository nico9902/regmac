package it.regmac;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static it.regmac.Domain.*;
import static it.regmac.RequirementCatalog.CatalogRequirement;
import static it.regmac.RequirementCompiler.require;

/**
 * Checklist di valutazione manuale dei requisiti del catalogo per una macchina.
 * Bozza (DRAFT) modificabile; chiusura (CLOSED) firmata con la password dell'utente,
 * che genera e archivia il rapporto PDF con impronta SHA-256. Le revisioni successive
 * si creano a partire dalla checklist precedente, che resta immutata.
 */
@Service
public class ChecklistService {

    public static final List<String> STATUSES = List.of("COMPLIANT", "PARTIAL", "NON_COMPLIANT", "NOT_APPLICABLE", "TO_ASSESS");

    public record Details(String model, String serial, String year, String manufacturer, String client, String description,
                          String intendedUse, String assessor, String organization, String assessmentDate, String location) {}

    public record Signature(String userId, String username, String displayName, String signedAt, String note,
                            String reportSha256, int reportPages) {}

    public record Checklist(String id, String machineId, String title, String status, String createdAt, String updatedAt,
                            String catalogSha256, List<String> profile, Details details, String conclusions,
                            String closedBy, String closedAt, String closingNote, String basedOn,
                            Integer revision, String createdBy, Signature signature) {
        int rev() { return revision == null || revision < 1 ? 1 : revision; }
    }

    /** Riferimento a un documento della Knowledge base della stessa macchina. */
    public record EvidenceRef(String evidenceId, String name, String sha256, String locator) {}

    /** Ultimo suggerimento del modello locale: informativo, mai applicato automaticamente. */
    public record Suggestion(String status, String proposal, String rationale, List<Map<String, String>> citations,
                             List<String> missing, String model, String createdAt, String error) {}

    public record Item(String requirementId, String status, String evidence, String standard, String note, String action,
                       String owner, String due, String updatedAt, List<EvidenceRef> evidenceRefs, String textSha256,
                       String assessedBy, Suggestion suggestion) {}

    public record Input(String title, List<String> profile, Details details, String conclusions, String basedOn) {}

    public record CloseInput(String password, String note) {}

    public record Summary(Checklist checklist, Map<String, Integer> counts, int total) {}

    public record Row(CatalogRequirement requirement, boolean inScope, String status, Item item, boolean textChanged) {}

    private final Store store;
    private final JdbcTemplate jdbc;
    private final RequirementCatalog catalog;
    private final UserService users;
    private final AiClient ai;
    private final Path reports;

    public ChecklistService(Store store, JdbcTemplate jdbc, RequirementCatalog catalog, UserService users, AiClient ai,
                            @Value("${regmac.storage}") String storage) {
        this.store = store;
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.users = users;
        this.ai = ai;
        this.reports = Path.of(storage).resolve("reports");
    }

    // ------------------------------------------------------------------ lettura
    public Checklist checklist(String id) {
        var rows = jdbc.query("SELECT payload FROM checklist WHERE id=?", (rs, n) -> store.decode(rs.getString(1), Checklist.class), id);
        if (rows.isEmpty()) throw new NoSuchElementException("Checklist non trovata");
        return rows.getFirst();
    }

    public Map<String, Item> items(String id) {
        Map<String, Item> out = new LinkedHashMap<>();
        jdbc.query("SELECT requirement_id,payload FROM checklist_item WHERE checklist_id=?",
                rs -> { out.put(rs.getString(1), store.decode(rs.getString(2), Item.class)); }, id);
        return out;
    }

    public List<Summary> list(String machineId) {
        store.get("machine", machineId, Machine.class);
        return jdbc.query("SELECT payload FROM checklist WHERE machine_id=?", (rs, n) -> store.decode(rs.getString(1), Checklist.class), machineId)
                .stream().sorted(Comparator.comparing(Checklist::createdAt).reversed())
                .map(c -> { var rows = rows(c, items(c.id())); return new Summary(c, counts(rows), rows.size()); })
                .toList();
    }

    public Map<String, Object> get(String id) {
        Checklist c = checklist(id);
        Map<String, Item> items = items(id);
        List<String> stale = items.entrySet().stream().filter(e -> stale(e.getValue())).map(Map.Entry::getKey).toList();
        return Map.of("checklist", c, "items", items, "textChanged", stale);
    }

    // ------------------------------------------------------------------ scrittura
    @Transactional
    public Checklist create(String machineId, Input in) {
        Machine m = store.get("machine", machineId, Machine.class);
        Checklist base = null;
        if (in != null && in.basedOn() != null && !in.basedOn().isBlank()) {
            base = checklist(in.basedOn());
            require(base.machineId().equals(machineId), "La checklist di partenza appartiene a un'altra macchina");
        }
        List<String> profile = in != null && in.profile() != null ? in.profile() : base != null ? base.profile() : defaultProfile(m);
        validateProfile(profile);
        Details details = in != null && in.details() != null ? clean(in.details()) : base != null ? base.details() : emptyDetails();
        int revision = base == null ? 1 : maxRevision(machineId, base) + 1;
        String root = base == null ? null : base.title().replaceFirst("^Rev\\. \\d+ — ", "");
        String title = in != null && in.title() != null && !in.title().isBlank() ? trim(in.title(), 200)
                : base != null ? "Rev. " + revision + " — " + root : "Valutazione del " + LocalDate.now();
        String ts = now();
        Checklist c = new Checklist(id(), machineId, title, "DRAFT", ts, ts, catalog.sha256(), List.copyOf(profile), details,
                base != null ? base.conclusions() : "", null, null, null, base != null ? base.id() : null,
                revision, UserService.actor(), null);
        jdbc.update("INSERT INTO checklist (id,machine_id,payload) VALUES (?,?,?)", c.id(), machineId, store.json(c));
        if (base != null)
            for (var e : items(base.id()).entrySet())
                if (catalog.find(e.getKey()).isPresent())
                    jdbc.update("INSERT INTO checklist_item (checklist_id,requirement_id,updated_at,payload) VALUES (?,?,?,?)",
                            c.id(), e.getKey(), ts, store.json(e.getValue()));
        store.audit("CHECKLIST_CREATED", c.id(), Map.of("machineId", machineId, "profile", profile, "revision", revision,
                "basedOn", Objects.toString(c.basedOn(), "")));
        return c;
    }

    /** Numero di revisione più alto tra le checklist della macchina con lo stesso titolo radice. */
    private int maxRevision(String machineId, Checklist base) {
        String root = base.title().replaceFirst("^Rev\\. \\d+ — ", "");
        return list(machineId).stream().map(Summary::checklist)
                .filter(x -> x.title().replaceFirst("^Rev\\. \\d+ — ", "").equals(root))
                .mapToInt(Checklist::rev).max().orElse(base.rev());
    }

    @Transactional
    public Checklist update(String id, Input in) {
        Checklist c = editable(id);
        require(in != null, "Dati mancanti");
        List<String> profile = in.profile() != null ? in.profile() : c.profile();
        validateProfile(profile);
        Checklist u = with(c, in.title() != null && !in.title().isBlank() ? trim(in.title(), 200) : c.title(), c.status(), List.copyOf(profile),
                in.details() != null ? clean(in.details()) : c.details(), in.conclusions() != null ? trim(in.conclusions(), 20000) : c.conclusions(),
                c.signature());
        jdbc.update("UPDATE checklist SET payload=? WHERE id=?", store.json(u), id);
        if (!new HashSet<>(profile).equals(new HashSet<>(c.profile())))
            store.audit("CHECKLIST_PROFILE_CHANGED", id, Map.of("from", c.profile(), "to", profile));
        return u;
    }

    @Transactional
    public Item saveItem(String id, String requirementId, Item in) {
        Checklist c = editable(id);
        CatalogRequirement r = catalog.find(requirementId).orElseThrow(() -> new NoSuchElementException("Requisito non presente nel catalogo"));
        require(r.assessable(), "La voce " + r.ref() + " è solo di consultazione");
        require(in != null, "Dati mancanti");
        String status = in.status() == null || in.status().isBlank() ? "TO_ASSESS" : in.status();
        require(STATUSES.contains(status), "Esito non valido");
        String due = in.due() == null || in.due().isBlank() ? null : in.due();
        if (due != null) try { LocalDate.parse(due); } catch (Exception e) { throw new IllegalArgumentException("Scadenza non valida (AAAA-MM-GG)"); }
        List<EvidenceRef> refs = evidenceRefs(c.machineId(), in.evidenceRefs());
        Map<String, Item> current = items(id);
        Item previous = current.get(requirementId);
        String before = previous == null || previous.status() == null ? "TO_ASSESS" : previous.status();
        String ts = now();
        boolean statusChanged = !before.equals(status);
        // l'impronta del testo si aggiorna a ogni salvataggio: risalvare conferma la valutazione sul testo corrente
        Item item = new Item(requirementId, status, trim(in.evidence(), 10000), trim(in.standard(), 1000), trim(in.note(), 10000),
                trim(in.action(), 10000), trim(in.owner(), 200), due, ts, refs, "TO_ASSESS".equals(status) ? null : r.textSha256(),
                statusChanged ? UserService.actor() : previous == null ? UserService.actor() : previous.assessedBy(),
                previous == null ? null : previous.suggestion());
        if (previous == null)
            jdbc.update("INSERT INTO checklist_item (checklist_id,requirement_id,updated_at,payload) VALUES (?,?,?,?)", id, requirementId, ts, store.json(item));
        else
            jdbc.update("UPDATE checklist_item SET updated_at=?,payload=? WHERE checklist_id=? AND requirement_id=?", ts, store.json(item), id, requirementId);
        if (statusChanged)
            store.audit("CHECKLIST_ITEM_STATUS", id, Map.of("requirementId", requirementId, "reference", r.ref(), "from", before, "to", status));
        touch(c);
        return item;
    }

    private List<EvidenceRef> evidenceRefs(String machineId, List<EvidenceRef> in) {
        if (in == null || in.isEmpty()) return List.of();
        require(in.size() <= 30, "Massimo 30 documenti collegati per requisito");
        List<EvidenceRef> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (EvidenceRef ref : in) {
            require(ref != null && ref.evidenceId() != null, "Riferimento documento non valido");
            Evidence e = store.get("evidence", ref.evidenceId(), Evidence.class);
            require(e.machineId().equals(machineId), "Il documento appartiene a un'altra macchina");
            String locator = trim(ref.locator(), 200);
            if (seen.add(e.id() + "|" + locator)) out.add(new EvidenceRef(e.id(), e.name(), e.sha256(), locator)); // nome e hash dal server
        }
        return out;
    }

    @Transactional
    public Checklist close(String id, CloseInput in) {
        Checklist c = editable(id);
        require(in != null, "Dati mancanti");
        UserService.User signer = users.confirm(in.password());
        var rows = rows(c, items(id));
        List<String> problems = new ArrayList<>();
        problems.addAll(refs(rows, r -> "TO_ASSESS".equals(r.status()), "da valutare"));
        problems.addAll(refs(rows, r -> List.of("NOT_APPLICABLE", "NON_COMPLIANT", "PARTIAL").contains(r.status()) && blank(r.item() == null ? null : r.item().note()), "senza rilievo/motivazione"));
        problems.addAll(refs(rows, r -> List.of("NON_COMPLIANT", "PARTIAL").contains(r.status()) && blank(r.item() == null ? null : r.item().action()), "senza azione correttiva"));
        problems.addAll(refs(rows, Row::textChanged, "valutati su un testo normativo poi aggiornato (rivedere e risalvare)"));
        require(problems.isEmpty(), "Impossibile chiudere: " + String.join("; ", problems));
        String ts = now();
        Signature pending = new Signature(signer.id(), signer.username(), signer.displayName(), ts, trim(in.note(), 2000), null, 0);
        Checklist closing = with(c, c.title(), "CLOSED", c.profile(), c.details(), c.conclusions(), pending);
        closing = new Checklist(closing.id(), closing.machineId(), closing.title(), "CLOSED", closing.createdAt(), ts, closing.catalogSha256(),
                closing.profile(), closing.details(), closing.conclusions(), signer.displayName(), ts, pending.note(), closing.basedOn(),
                closing.rev(), closing.createdBy(), pending);
        ReportPdf.Result pdf = ReportPdf.render(reportData(closing, rows));
        String sha = KnowledgeService.sha(pdf.bytes());
        try {
            Files.createDirectories(reports);
            Files.write(reports.resolve(id + ".pdf"), pdf.bytes());
        } catch (Exception e) {
            throw new IllegalStateException("Archivio rapporti non disponibile", e);
        }
        Signature sig = new Signature(signer.id(), signer.username(), signer.displayName(), ts, pending.note(), sha, pdf.pages());
        Checklist closed = new Checklist(closing.id(), closing.machineId(), closing.title(), "CLOSED", closing.createdAt(), ts, closing.catalogSha256(),
                closing.profile(), closing.details(), closing.conclusions(), signer.displayName(), ts, sig.note(), closing.basedOn(),
                closing.rev(), closing.createdBy(), sig);
        jdbc.update("UPDATE checklist SET payload=? WHERE id=?", store.json(closed), id);
        store.audit("CHECKLIST_CLOSED", id, Map.of("signedBy", signer.username(), "counts", counts(rows), "verdict", verdict(counts(rows)),
                "reportSha256", sha, "revision", closed.rev()));
        return closed;
    }

    // ------------------------------------------------------------------ suggerimento AI
    @Transactional
    public Suggestion suggest(String id, String requirementId) {
        Checklist c = editable(id);
        CatalogRequirement r = catalog.find(requirementId).orElseThrow(() -> new NoSuchElementException("Requisito non presente nel catalogo"));
        require(r.assessable(), "Voce non valutabile");
        Item current = items(id).get(requirementId);
        Set<String> linked = current == null || current.evidenceRefs() == null ? Set.of()
                : current.evidenceRefs().stream().map(EvidenceRef::evidenceId).collect(Collectors.toSet());
        List<Map<String, Object>> excerpts = excerpts(c.machineId(), r, linked);
        List<Map<String, Object>> facts = store.forMachine("fact", c.machineId(), Fact.class).stream()
                .filter(f -> !"RETRACTED".equals(f.nature())).limit(40)
                .map(f -> Map.<String, Object>of("key", f.key(), "value", String.valueOf(f.value()), "nature", f.nature(), "quote", Objects.toString(f.quote(), "")))
                .toList();
        Map<String, Object> payload = Map.of(
                "requirement", Map.of("id", r.id(), "law", r.law(), "reference", r.ref(), "title", r.title(), "text", r.text()),
                "excerpts", excerpts, "facts", facts);
        Suggestion s;
        if (excerpts.isEmpty() && facts.isEmpty()) {
            s = new Suggestion("NO_EVIDENCE", null, null, List.of(), List.of("Nessun documento elaborato o fatto disponibile per questa macchina"), null, now(), null);
        } else {
            try {
                Map<String, Object> res = ai.post("/suggest", payload);
                s = store.decode(store.json(res), Suggestion.class);
                s = new Suggestion(s.status(), s.proposal(), s.rationale(), s.citations() == null ? List.of() : s.citations(),
                        s.missing() == null ? List.of() : s.missing(), s.model(), now(), s.error());
            } catch (Exception e) {
                s = new Suggestion("UNAVAILABLE", null, null, List.of(), List.of(), null, now(), e.getMessage());
            }
        }
        Item base = current != null ? current : new Item(requirementId, null, "", "", "", "", "", null, now(), List.of(), null, null, null);
        Item updated = new Item(base.requirementId(), base.status(), base.evidence(), base.standard(), base.note(), base.action(), base.owner(),
                base.due(), base.updatedAt(), base.evidenceRefs(), base.textSha256(), base.assessedBy(), s);
        if (current == null)
            jdbc.update("INSERT INTO checklist_item (checklist_id,requirement_id,updated_at,payload) VALUES (?,?,?,?)", id, requirementId, now(), store.json(updated));
        else
            jdbc.update("UPDATE checklist_item SET payload=? WHERE checklist_id=? AND requirement_id=?", store.json(updated), id, requirementId);
        store.audit("CHECKLIST_AI_SUGGESTION", id, Map.of("requirementId", requirementId, "status", s.status(), "proposal", Objects.toString(s.proposal(), ""),
                "model", Objects.toString(s.model(), "")));
        return s;
    }

    private static final Set<String> STOP = Set.of("della", "delle", "degli", "dello", "nella", "nelle", "negli", "sulla", "sulle", "devono", "deve",
            "essere", "macchina", "macchine", "prodotto", "prodotti", "correlato", "correlati", "quale", "quali", "loro", "questo", "questa",
            "presente", "allegato", "regolamento", "articolo", "paragrafo", "fabbricante", "fabbricanti", "elementi", "digitali", "anche", "modo");

    /** Estratti dei documenti della macchina più pertinenti al requisito (ricerca lessicale, budget limitato). */
    private List<Map<String, Object>> excerpts(String machineId, CatalogRequirement r, Set<String> linked) {
        Set<String> keys = Arrays.stream((r.title() + " " + r.text()).toLowerCase(Locale.ROOT).split("[^\\p{L}0-9]+"))
                .filter(w -> w.length() >= 5 && !STOP.contains(w)).collect(Collectors.toSet());
        record Scored(Evidence e, String text, double score) {}
        List<Scored> all = new ArrayList<>();
        for (Evidence e : store.forMachine("evidence", machineId, Evidence.class)) {
            if (e.text() == null || e.text().isBlank()) continue;
            for (String para : e.text().split("\\n\\s*\\n|(?<=\\.)\\n")) {
                String p = para.strip();
                if (p.length() < 30) continue;
                if (p.length() > 1500) p = p.substring(0, 1500);
                String low = p.toLowerCase(Locale.ROOT);
                long hits = keys.stream().filter(low::contains).count();
                double score = hits + (linked.contains(e.id()) ? 3 : 0);
                if (hits > 0 || linked.contains(e.id())) all.add(new Scored(e, p, score));
            }
        }
        all.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        int budget = 9000;
        for (Scored s : all) {
            if (out.size() >= 8 || budget - s.text().length() < 0) break;
            budget -= s.text().length();
            out.add(Map.of("evidenceId", s.e().id(), "document", s.e().name(), "text", s.text()));
        }
        return out;
    }

    // ------------------------------------------------------------------ rapporto
    public Map<String, Object> report(String id) {
        Checklist c = checklist(id);
        return reportData(c, rows(c, items(id)));
    }

    Map<String, Object> reportData(Checklist c, List<Row> rows) {
        Machine m = store.get("machine", c.machineId(), Machine.class);
        var counts = counts(rows);
        int applicable = rows.size() - counts.get("NOT_APPLICABLE");
        var cat = catalog.catalog();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checklist", c);
        out.put("machine", m);
        out.put("catalog", Map.of("sha256", catalog.sha256(), "generatedFrom", cat.generatedFrom(), "disclaimer", cat.disclaimer(),
                "laws", cat.laws(), "profileTags", cat.profileTags()));
        out.put("counts", counts);
        out.put("total", rows.size());
        out.put("applicable", applicable);
        out.put("complianceIndex", applicable == 0 ? 0 : Math.round(counts.get("COMPLIANT") * 100.0 / applicable));
        out.put("verdict", verdict(counts));
        out.put("rows", rows);
        out.put("generatedAt", now());
        return out;
    }

    /** PDF del rapporto: archiviato e verificato se la checklist è chiusa, altrimenti bozza generata al volo. */
    public byte[] pdf(String id) {
        Checklist c = checklist(id);
        if ("CLOSED".equals(c.status()) && c.signature() != null && c.signature().reportSha256() != null) {
            try {
                byte[] bytes = Files.readAllBytes(reports.resolve(id + ".pdf"));
                require(KnowledgeService.sha(bytes).equals(c.signature().reportSha256()), "Il rapporto archiviato è stato alterato");
                return bytes;
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Rapporto archiviato non trovato", e);
            }
        }
        return ReportPdf.render(report(id)).bytes();
    }

    public String csv(String id) {
        Checklist c = checklist(id);
        var rows = rows(c, items(id));
        StringBuilder sb = new StringBuilder("﻿");
        line(sb, List.of("ID", "Regolamento", "Riferimento", "Sezione", "Requisito", "Nel profilo", "Esito", "Evidenze", "Documenti collegati",
                "Norme/metodi", "Rilievo", "Azione correttiva", "Responsabile", "Scadenza", "Valutato da", "Testo aggiornato dopo la valutazione", "Pagine PDF"));
        for (Row r : rows) {
            Item it = r.item() == null ? new Item(null, null, null, null, null, null, null, null, null, null, null, null, null) : r.item();
            String docs = it.evidenceRefs() == null ? "" : it.evidenceRefs().stream()
                    .map(e -> e.name() + (blank(e.locator()) ? "" : " (" + e.locator() + ")") + " [SHA-256 " + e.sha256().substring(0, 12) + "]")
                    .collect(Collectors.joining(" | "));
            line(sb, Arrays.asList(r.requirement().id(), r.requirement().law(), r.requirement().ref(), r.requirement().group(), r.requirement().title(),
                    r.inScope() ? "sì" : "no", r.status(), it.evidence(), docs, it.standard(), it.note(), it.action(), it.owner(), it.due(),
                    it.assessedBy(), r.textChanged() ? "sì" : "",
                    r.requirement().pages() == null ? "" : r.requirement().pages().stream().map(String::valueOf).collect(Collectors.joining("-"))));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ supporto
    /** Requisiti considerati: valutabili nel profilo, più quelli fuori profilo con un esito esplicito. */
    List<Row> rows(Checklist c, Map<String, Item> items) {
        List<Row> out = new ArrayList<>();
        for (CatalogRequirement r : catalog.requirements()) {
            if (!r.assessable()) continue;
            Item it = items.get(r.id());
            boolean in = r.tags() != null && r.tags().stream().anyMatch(c.profile()::contains);
            String status = it == null || it.status() == null ? "TO_ASSESS" : it.status();
            if (in || !"TO_ASSESS".equals(status)) out.add(new Row(r, in, status, it, it != null && stale(it)));
        }
        return out;
    }

    private boolean stale(Item it) {
        if (it.textSha256() == null || it.status() == null || "TO_ASSESS".equals(it.status())) return false;
        return catalog.find(it.requirementId()).map(r -> !r.textSha256().equals(it.textSha256())).orElse(false);
    }

    static Map<String, Integer> counts(List<Row> rows) {
        Map<String, Integer> m = new LinkedHashMap<>();
        STATUSES.forEach(s -> m.put(s, 0));
        rows.forEach(r -> m.merge(r.status(), 1, Integer::sum));
        return m;
    }

    static String verdict(Map<String, Integer> c) {
        if (c.get("NON_COMPLIANT") > 0) return "NON_COMPLIANT";
        if (c.get("TO_ASSESS") > 0) return "INCOMPLETE";
        if (c.get("PARTIAL") > 0) return "PARTIAL";
        return "COMPLIANT";
    }

    private List<String> refs(List<Row> rows, java.util.function.Predicate<Row> p, String label) {
        var hits = rows.stream().filter(p).toList();
        if (hits.isEmpty()) return List.of();
        String sample = hits.stream().limit(4).map(r -> r.requirement().law() + " " + r.requirement().ref()).collect(Collectors.joining(", "));
        return List.of(hits.size() + " requisiti " + label + " (" + sample + (hits.size() > 4 ? ", …" : "") + ")");
    }

    private Checklist editable(String id) {
        Checklist c = checklist(id);
        require("DRAFT".equals(c.status()), "Checklist chiusa: creare una nuova revisione per modificarla");
        return c;
    }

    private static Checklist with(Checklist c, String title, String status, List<String> profile, Details details, String conclusions, Signature sig) {
        return new Checklist(c.id(), c.machineId(), title, status, c.createdAt(), now(), c.catalogSha256(), profile, details, conclusions,
                c.closedBy(), c.closedAt(), c.closingNote(), c.basedOn(), c.rev(), c.createdBy(), sig);
    }

    private void touch(Checklist c) {
        jdbc.update("UPDATE checklist SET payload=? WHERE id=?", store.json(with(c, c.title(), c.status(), c.profile(), c.details(), c.conclusions(), c.signature())), c.id());
    }

    private void validateProfile(List<String> profile) {
        require(profile != null && !profile.isEmpty(), "Selezionare almeno un elemento del profilo");
        require(catalog.profileTags().containsAll(profile), "Profilo non valido");
    }

    private static List<String> defaultProfile(Machine m) {
        List<String> p = new ArrayList<>();
        if (m.scope() == null || m.scope().isEmpty() || Boolean.TRUE.equals(m.scope().get("machinery"))) { p.add("macchina"); p.add("generale"); }
        if (m.scope() != null && Boolean.TRUE.equals(m.scope().get("digital_product"))) p.add("cra");
        return p;
    }

    private static Details emptyDetails() {
        return new Details("", "", "", "", "", "", "", "", "", LocalDate.now().toString(), "");
    }

    private static Details clean(Details d) {
        return new Details(trim(d.model(), 200), trim(d.serial(), 200), trim(d.year(), 20), trim(d.manufacturer(), 300), trim(d.client(), 300),
                trim(d.description(), 5000), trim(d.intendedUse(), 5000), trim(d.assessor(), 200), trim(d.organization(), 300),
                trim(d.assessmentDate(), 20), trim(d.location(), 2000));
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        require(s.length() <= max, "Testo troppo lungo (massimo " + max + " caratteri)");
        return s;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static void line(StringBuilder sb, List<String> values) {
        sb.append(values.stream().map(v -> "\"" + Objects.toString(v, "").replace("\"", "\"\"") + "\"").collect(Collectors.joining(";"))).append("\r\n");
    }
}
