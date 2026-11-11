package it.regmac;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static it.regmac.Domain.now;
import static it.regmac.RequirementCompiler.require;

/**
 * Catalogo dei requisiti della Wiki normativa (Regolamento Macchine + CRA).
 * Importato all'avvio da {@code requirements.json}, generato da scripts/build_requirements.py
 * a partire dal corpus integrale. L'import è idempotente (per SHA-256 del file) e verifica
 * che il catalogo derivi dai PDF originali presenti nella stessa directory.
 */
@Service
public class RequirementCatalog {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogRequirement(String id, String law, String section, String group, String ref, String title,
                                     String text, List<Integer> pages, List<String> tags, boolean assessable,
                                     boolean always, String kind, String chapter, List<Map<String, String>> standards) {
        /** Impronta del testo: serve a segnalare valutazioni fatte su un testo poi modificato. */
        public String textSha256() { return KnowledgeService.sha(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String law, String sha256, String filename) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Catalog(String schema, List<Source> generatedFrom, String disclaimer, List<Map<String, Object>> laws,
                          List<Map<String, Object>> profileTags, List<CatalogRequirement> requirements,
                          Map<String, Object> standardsNotice, Map<String, Object> changes) {
        Catalog metadata(Map<String, Object> changes) { return new Catalog(schema, generatedFrom, disclaimer, laws, profileTags, List.of(), standardsNotice, changes); }
    }

    private final Store store;
    private final JdbcTemplate jdbc;
    private final Path directory;
    private volatile Snapshot cache;

    private record Snapshot(String sha256, Catalog catalog, Map<String, CatalogRequirement> byId) {}

    public RequirementCatalog(Store store, JdbcTemplate jdbc, @Value("${regmac.seed}") String seed) {
        this.store = store;
        this.jdbc = jdbc;
        this.directory = Path.of(seed).toAbsolutePath().getParent();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void importCatalog() throws Exception {
        Path file = directory.resolve("requirements.json");
        if (!Files.exists(file)) return;
        byte[] bytes = Files.readAllBytes(file);
        String sha = KnowledgeService.sha(bytes);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM normative_catalog WHERE sha256=?", Integer.class, sha) > 0) return;
        Catalog c = store.mapper().readValue(bytes, Catalog.class);
        validate(c);
        Map<String, Object> changes = diff(c);
        Set<String> ids = new HashSet<>();
        int position = 0;
        for (CatalogRequirement r : c.requirements()) {
            ids.add(r.id());
            String payload = store.json(r);
            int updated = jdbc.update("UPDATE normative_requirement SET law=?,section_id=?,position=?,assessable=?,catalog_sha256=?,payload=? WHERE id=?",
                    r.law(), r.section(), position, r.assessable(), sha, payload, r.id());
            if (updated == 0)
                jdbc.update("INSERT INTO normative_requirement (id,law,section_id,position,assessable,catalog_sha256,payload) VALUES (?,?,?,?,?,?,?)",
                        r.id(), r.law(), r.section(), position, r.assessable(), sha, payload);
            position++;
        }
        // Voci scomparse dal nuovo catalogo: eliminate se mai valutate, altrimenti conservate per lo storico.
        for (String old : jdbc.queryForList("SELECT id FROM normative_requirement WHERE catalog_sha256<>?", String.class, sha))
            if (!ids.contains(old) && jdbc.queryForObject("SELECT COUNT(*) FROM checklist_item WHERE requirement_id=?", Integer.class, old) == 0)
                jdbc.update("DELETE FROM normative_requirement WHERE id=?", old);
        jdbc.update("INSERT INTO normative_catalog (sha256,imported_at,payload) VALUES (?,?,?)", sha, now(), store.json(c.metadata(changes)));
        store.audit("NORMATIVE_CATALOG_IMPORTED", sha, Map.of("requirements", c.requirements().size(),
                "assessable", c.requirements().stream().filter(CatalogRequirement::assessable).count(), "sources", c.generatedFrom(), "changes", changes));
        cache = null;
    }

    /** Differenze rispetto al catalogo precedente (per ID e impronta del testo). */
    private Map<String, Object> diff(Catalog next) {
        var prev = jdbc.query("SELECT sha256 FROM normative_catalog ORDER BY imported_at DESC", (rs, n) -> rs.getString(1));
        Map<String, Object> out = new LinkedHashMap<>();
        if (prev.isEmpty()) { out.put("previous", null); return out; }
        Map<String, String> old = new HashMap<>();
        jdbc.query("SELECT id,payload FROM normative_requirement WHERE catalog_sha256=?",
                rs -> { old.put(rs.getString(1), store.decode(rs.getString(2), CatalogRequirement.class).textSha256()); }, prev.getFirst());
        List<String> added = new ArrayList<>(), changed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (CatalogRequirement r : next.requirements()) {
            seen.add(r.id());
            if (!old.containsKey(r.id())) added.add(r.id());
            else if (!old.get(r.id()).equals(r.textSha256())) changed.add(r.id());
        }
        List<String> removed = old.keySet().stream().filter(id -> !seen.contains(id)).sorted().toList();
        out.put("previous", prev.getFirst());
        out.put("added", added);
        out.put("removed", removed);
        out.put("changed", changed);
        return out;
    }

    private void validate(Catalog c) throws Exception {
        require("regmac-wiki-catalog-v1".equals(c.schema()), "Schema del catalogo non supportato");
        require(c.requirements() != null && !c.requirements().isEmpty(), "Catalogo vuoto");
        require(c.generatedFrom() != null && !c.generatedFrom().isEmpty(), "Origine del catalogo mancante");
        for (Source s : c.generatedFrom()) {
            Path pdf = directory.resolve(s.filename()).normalize();
            require(pdf.startsWith(directory) && s.filename().endsWith(".pdf") && Files.exists(pdf), "PDF di origine mancante: " + s.filename());
            require(KnowledgeService.sha(Files.readAllBytes(pdf)).equals(s.sha256()), "Il catalogo non corrisponde al PDF " + s.filename());
        }
        Set<String> tags = new HashSet<>();
        c.profileTags().forEach(t -> tags.add(String.valueOf(t.get("id"))));
        Set<String> seen = new HashSet<>();
        for (CatalogRequirement r : c.requirements()) {
            require(r.id() != null && r.id().matches("(MR|CRA)-[A-Za-z0-9.\\-]{1,70}"), "ID requisito non valido: " + r.id());
            require(seen.add(r.id()), "ID requisito duplicato: " + r.id());
            require(Set.of("MR", "CRA").contains(r.law()) && r.text() != null && !r.text().isBlank(), "Requisito incompleto: " + r.id());
            require(!r.assessable() || (r.tags() != null && !r.tags().isEmpty() && tags.containsAll(r.tags())), "Profilo non valido per " + r.id());
        }
    }

    /** Catalogo corrente (ultimo importato) con i soli requisiti di quella versione, in ordine. */
    public synchronized Map<String, Object> current() {
        Snapshot s = load();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sha256", s.sha256());
        out.put("schema", s.catalog().schema());
        out.put("generatedFrom", s.catalog().generatedFrom());
        out.put("disclaimer", s.catalog().disclaimer());
        out.put("laws", s.catalog().laws());
        out.put("profileTags", s.catalog().profileTags());
        out.put("requirements", s.catalog().requirements());
        out.put("standardsNotice", s.catalog().standardsNotice());
        out.put("changes", s.catalog().changes());
        return out;
    }

    private synchronized Snapshot load() {
        if (cache != null) return cache;
        var rows = jdbc.query("SELECT sha256,payload FROM normative_catalog ORDER BY imported_at DESC",
                (rs, n) -> Map.entry(rs.getString(1), rs.getString(2)));
        if (rows.isEmpty()) throw new IllegalStateException("Catalogo dei requisiti non importato: eseguire scripts/build_requirements.py");
        String sha = rows.getFirst().getKey();
        Catalog meta = store.decode(rows.getFirst().getValue(), Catalog.class);
        List<CatalogRequirement> reqs = jdbc.query("SELECT payload FROM normative_requirement WHERE catalog_sha256=? ORDER BY position",
                (rs, n) -> store.decode(rs.getString(1), CatalogRequirement.class), sha);
        Map<String, CatalogRequirement> byId = new LinkedHashMap<>();
        reqs.forEach(r -> byId.put(r.id(), r));
        cache = new Snapshot(sha, new Catalog(meta.schema(), meta.generatedFrom(), meta.disclaimer(), meta.laws(), meta.profileTags(), reqs,
                meta.standardsNotice(), meta.changes()), byId);
        return cache;
    }

    /** Svuota la cache (dopo modifiche dirette al database, es. manutenzione o test). */
    public synchronized void invalidate() { cache = null; }

    public String sha256() { return load().sha256(); }
    public List<CatalogRequirement> requirements() { return load().catalog().requirements(); }
    public Catalog catalog() { return load().catalog(); }
    public Optional<CatalogRequirement> find(String id) { return Optional.ofNullable(load().byId().get(id)); }
    public Set<String> profileTags() {
        Set<String> t = new LinkedHashSet<>();
        load().catalog().profileTags().forEach(x -> t.add(String.valueOf(x.get("id"))));
        return t;
    }
}
