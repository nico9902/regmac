package it.regmac;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Flusso completo con utenti reali: configurazione iniziale → ruoli → catalogo → checklist → evidenze
 * collegate → firma con password → PDF archiviato e verificato → nuova revisione.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChecklistIntegrationTest {
    static Path root = Files.isDirectory(Path.of("data")) ? Path.of("").toAbsolutePath() : Path.of("..").toAbsolutePath().normalize();
    static final String ADMIN_PWD = "Amministratore-2026!";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("regmac.api-token", () -> "");   // nessun token condiviso: solo utenti
        r.add("regmac.ai-url", () -> "http://127.0.0.1:9");
        r.add("regmac.seed", () -> root.resolve("data/normative/controls.json").toString());
        r.add("regmac.storage", () -> root.resolve(".tools/test-documents").toString());
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:checklist;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        r.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired RequirementCatalog catalog;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    static String adminToken;

    JsonNode call(String token, MockHttpServletRequestBuilder b, Object body, int status) throws Exception {
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.contentType(MediaType.APPLICATION_JSON);
        if (body != null) b.content(mapper.writeValueAsString(body));
        var res = mvc.perform(b).andExpect(status().is(status)).andReturn().getResponse();
        res.setCharacterEncoding("UTF-8");
        String s = res.getContentAsString();
        return s.isBlank() ? null : mapper.readTree(s);
    }

    String admin() throws Exception {
        if (adminToken == null) {
            JsonNode st = call(null, get("/api/auth/status"), null, 200);
            if (st.get("setupRequired").asBoolean())
                adminToken = call(null, post("/api/auth/setup"), Map.of("username", "admin", "displayName", "Anna Amministratrice", "password", ADMIN_PWD), 200).get("token").asText();
            else
                adminToken = call(null, post("/api/auth/login"), Map.of("username", "admin", "password", ADMIN_PWD), 200).get("token").asText();
        }
        return adminToken;
    }

    @Test @Order(1)
    void authenticationAndRoles() throws Exception {
        call(null, get("/api/machines"), null, 401);
        call("token-inventato", get("/api/machines"), null, 401);
        String a = admin();
        call(null, post("/api/auth/setup"), Map.of("username", "altro", "displayName", "X", "password", "abcdefghijkl"), 400);
        call(null, post("/api/auth/login"), Map.of("username", "admin", "password", "sbagliata-123"), 401);
        assertEquals("ADMIN", call(a, get("/api/auth/me"), null, 200).get("role").asText());

        call(a, post("/api/users"), Map.of("username", "lettore", "displayName", "Luca Lettore", "password", "Lettore-2026!!", "role", "READER"), 200);
        call(a, post("/api/users"), Map.of("username", "valutatore", "displayName", "Vera Valutatrice", "password", "Valutatore-2026!", "role", "ASSESSOR"), 200);
        call(a, post("/api/users"), Map.of("username", "corta", "displayName", "X", "password", "breve", "role", "READER"), 400);
        String reader = call(null, post("/api/auth/login"), Map.of("username", "lettore", "password", "Lettore-2026!!"), 200).get("token").asText();
        call(reader, get("/api/machines"), null, 200);
        call(reader, post("/api/machines"), Map.of("name", "X", "targetDate", "2027-01-01", "scope", Map.of()), 403);
        call(reader, get("/api/users"), null, 403);
        String assessor = call(null, post("/api/auth/login"), Map.of("username", "valutatore", "password", "Valutatore-2026!"), 200).get("token").asText();
        call(assessor, get("/api/users"), null, 403);
        call(assessor, post("/api/auth/logout"), null, 200);
        call(assessor, get("/api/machines"), null, 401);

        var audit = call(a, get("/api/audit"), null, 200);
        boolean withActor = false;
        for (JsonNode e : audit) if ("USER_CREATED".equals(e.get("action").asText()) && e.get("actor").asText().contains("admin")) withActor = true;
        assertTrue(withActor, "l'audit trail registra l'autore");
    }

    @Test @Order(2)
    void catalogImportedAndIdempotent() throws Exception {
        JsonNode c = call(admin(), get("/api/requirement-catalog"), null, 200);
        assertEquals("regmac-wiki-catalog-v1", c.get("schema").asText());
        assertTrue(c.get("requirements").size() > 400);
        String sha = catalog.sha256();
        catalog.importCatalog();
        assertEquals(sha, catalog.sha256());
        assertTrue(catalog.find("MR-III-1.1.2").orElseThrow().always());
    }

    @Test @Order(3)
    void checklistLifecycleWithSignatureAndArchivedPdf() throws Exception {
        String a = admin();
        String mid = call(a, post("/api/machines"), Map.of("name", "Pressa di prova", "synthetic", true, "targetDate", "2027-02-01",
                "scope", Map.of("machinery", true, "digital_product", true), "scopeReviewer", "Revisore test"), 200).get("id").asText();
        var file = new MockMultipartFile("file", "fascicolo.txt", "text/plain", "Fascicolo tecnico: prove funzionali dell'arresto di emergenza eseguite.".getBytes());
        var up = mvc.perform(multipart("/api/machines/" + mid + "/documents").file(file).header("Authorization", "Bearer " + a)).andReturn().getResponse();
        String evId = mapper.readTree(up.getContentAsString()).get("id").asText();

        JsonNode cl = call(a, post("/api/machines/" + mid + "/checklists"), Map.of(), 200);
        String id = cl.get("id").asText();
        assertEquals(1, cl.get("revision").asInt());
        call(a, put("/api/checklists/" + id), Map.of("profile", List.of("generale", "cra")), 200);

        // evidenze: nome e hash dal server, documento della stessa macchina
        JsonNode it = call(a, put("/api/checklists/" + id + "/items/MR-III-1.2.4.3"), Map.of("status", "COMPLIANT",
                "evidenceRefs", List.of(Map.of("evidenceId", evId, "locator", "par. 4", "name", "nome falso", "sha256", "x"))), 200);
        assertEquals("fascicolo.txt", it.get("evidenceRefs").get(0).get("name").asText());
        assertEquals(64, it.get("evidenceRefs").get(0).get("sha256").asText().length());
        assertTrue(it.get("assessedBy").asText().contains("admin"));
        call(a, put("/api/checklists/" + id + "/items/MR-III-1.2.4.2"), Map.of("status", "COMPLIANT", "evidenceRefs", List.of(Map.of("evidenceId", "non-esiste"))), 404);

        // suggerimento AI: servizio irraggiungibile → stato esplicito, nessun esito inventato
        JsonNode sug = call(a, post("/api/checklists/" + id + "/items/MR-III-1.2.4.3/suggest"), null, 200);
        assertTrue(List.of("UNAVAILABLE", "NO_EVIDENCE").contains(sug.get("status").asText()), sug.toString());
        assertTrue(sug.get("proposal").isNull());

        // PDF bozza generato al volo
        var draftPdf = mvc.perform(get("/api/checklists/" + id + "/report.pdf").header("Authorization", "Bearer " + a)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals("%PDF", new String(draftPdf, 0, 4));

        call(a, put("/api/checklists/" + id + "/items/MR-III-1.1.2"), Map.of("status", "NON_COMPLIANT", "note", "Prove funzionali assenti"), 200);
        JsonNode err = call(a, post("/api/checklists/" + id + "/close"), Map.of("password", ADMIN_PWD), 400);
        assertTrue(err.get("error").asText().contains("da valutare"));
        assertTrue(err.get("error").asText().contains("senza azione correttiva"));

        JsonNode rep = call(a, get("/api/checklists/" + id + "/report"), null, 200);
        for (JsonNode row : rep.get("rows")) {
            String rid = row.get("requirement").get("id").asText();
            if (rid.equals("MR-III-1.2.4.3")) continue;
            Object body = rid.equals("MR-III-1.1.2")
                    ? Map.of("status", "NON_COMPLIANT", "note", "Prove funzionali assenti", "action", "Redigere procedura di prova", "due", "2027-01-15")
                    : Map.of("status", "COMPLIANT", "evidence", "Fascicolo tecnico rev. 3");
            call(a, put("/api/checklists/" + id + "/items/" + rid), body, 200);
        }
        call(a, post("/api/checklists/" + id + "/close"), Map.of("password", "password-errata"), 400);
        JsonNode closed = call(a, post("/api/checklists/" + id + "/close"), Map.of("password", ADMIN_PWD, "note", "Rapporto finale"), 200);
        assertEquals("CLOSED", closed.get("status").asText());
        assertEquals("admin", closed.get("signature").get("username").asText());
        String sha = closed.get("signature").get("reportSha256").asText();
        assertTrue(closed.get("signature").get("reportPages").asInt() >= 2);

        byte[] pdf = mvc.perform(get("/api/checklists/" + id + "/report.pdf").header("Authorization", "Bearer " + a)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals(sha, KnowledgeService.sha(pdf), "il PDF scaricato è quello archiviato e firmato");
        call(a, put("/api/checklists/" + id + "/items/MR-III-1.1.3"), Map.of("status", "PARTIAL"), 400);

        JsonNode rev = call(a, post("/api/machines/" + mid + "/checklists"), Map.of("basedOn", id), 200);
        assertEquals(2, rev.get("revision").asInt());
        assertTrue(rev.get("title").asText().startsWith("Rev. 2"));
        JsonNode full = call(a, get("/api/checklists/" + rev.get("id").asText()), null, 200);
        assertEquals("NON_COMPLIANT", full.get("items").get("MR-III-1.1.2").get("status").asText());
        assertEquals(0, full.get("textChanged").size());

        var csv = mvc.perform(get("/api/checklists/" + id + "/report.csv").header("Authorization", "Bearer " + a)).andExpect(status().isOk()).andReturn().getResponse();
        csv.setCharacterEncoding("UTF-8");
        assertTrue(csv.getContentAsString().contains("fascicolo.txt (par. 4)"));
    }

    @Test @Order(4)
    void textChangedAfterAssessmentMustBeReconfirmed() throws Exception {
        String a = admin();
        String mid = call(a, post("/api/machines"), Map.of("name", "Macchina testo aggiornato", "targetDate", "2027-02-01",
                "scope", Map.of("machinery", true), "scopeReviewer", "x"), 200).get("id").asText();
        String id = call(a, post("/api/machines/" + mid + "/checklists"), Map.of("profile", List.of("generale")), 200).get("id").asText();
        JsonNode rep = call(a, get("/api/checklists/" + id + "/report"), null, 200);
        for (JsonNode row : rep.get("rows"))
            call(a, put("/api/checklists/" + id + "/items/" + row.get("requirement").get("id").asText()), Map.of("status", "COMPLIANT", "evidence", "ok"), 200);
        assertEquals(0, call(a, get("/api/checklists/" + id), null, 200).get("textChanged").size());

        // simula un nuovo catalogo con il testo del punto 1.1.3 modificato
        String payload = jdbc.queryForObject("SELECT payload FROM normative_requirement WHERE id='MR-III-1.1.3'", String.class);
        String original = payload;
        jdbc.update("UPDATE normative_requirement SET payload=? WHERE id='MR-III-1.1.3'", payload.replace("\"text\":\"", "\"text\":\"[Rettifica] "));
        catalog.invalidate();
        try {
            JsonNode full = call(a, get("/api/checklists/" + id), null, 200);
            assertEquals("MR-III-1.1.3", full.get("textChanged").get(0).asText());
            JsonNode err = call(a, post("/api/checklists/" + id + "/close"), Map.of("password", ADMIN_PWD), 400);
            assertTrue(err.get("error").asText().contains("testo normativo poi aggiornato"), err.toString());
            // risalvare l'esito conferma la valutazione sul testo corrente
            call(a, put("/api/checklists/" + id + "/items/MR-III-1.1.3"), Map.of("status", "COMPLIANT", "evidence", "ok, testo rettificato verificato"), 200);
            assertEquals(0, call(a, get("/api/checklists/" + id), null, 200).get("textChanged").size());
            assertEquals("CLOSED", call(a, post("/api/checklists/" + id + "/close"), Map.of("password", ADMIN_PWD), 200).get("status").asText());
        } finally {
            jdbc.update("UPDATE normative_requirement SET payload=? WHERE id='MR-III-1.1.3'", original);
            catalog.invalidate();
        }
    }
}
