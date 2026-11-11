package it.regmac;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static it.regmac.Domain.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest @AutoConfigureMockMvc class PipelineIntegrationTest {
 static Path root=Files.isDirectory(Path.of("data"))?Path.of("").toAbsolutePath():Path.of("..").toAbsolutePath().normalize();
 static Process python;
 @DynamicPropertySource static void properties(DynamicPropertyRegistry registry)throws Exception{
  int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
  var pb=new ProcessBuilder(root.resolve(".venv/bin/python").toString(),"-m","uvicorn","app.main:app","--host","127.0.0.1","--port",String.valueOf(port));pb.directory(root.resolve("ai-service").toFile());pb.environment().put("OLLAMA_URL","http://127.0.0.1:9");pb.redirectErrorStream(true);pb.redirectOutput(root.resolve(".tools/ai-integration.log").toFile());python=pb.start();
  var client=HttpClient.newHttpClient();boolean ready=false;for(int i=0;i<100;i++){try{if(client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/health")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200){ready=true;break;}}catch(Exception ignored){}Thread.sleep(100);}if(!ready)throw new IllegalStateException("Python service did not start");
  String url="http://127.0.0.1:"+port;
  registry.add("regmac.api-token",()->"local-development-only");registry.add("regmac.ai-url",()->url);registry.add("regmac.seed",()->root.resolve("data/normative/controls.json").toString());registry.add("regmac.storage",()->root.resolve(".tools/test-documents").toString());registry.add("spring.datasource.url",()->"jdbc:h2:mem:regmac;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");registry.add("spring.datasource.driver-class-name",()->"org.h2.Driver");
 }
 @AfterAll static void stop(){if(python!=null)python.destroy();}
 @Autowired NormativeCorpus corpus;@Autowired KnowledgeService kb;@Autowired AssessmentService assessments;@Autowired Store store;@Autowired MockMvc mvc;
 Machine machine(boolean scope){return kb.create(new Machine(null,"SYNTHETIC integration test",true,LocalDate.of(2028,1,1),Map.of("machinery",scope,"digital_product",scope),"TEST reviewer"));}
 List<String> activate(){var rules=store.all("requirement_version",Requirement.class);for(var r:rules)if(r.status().equals("DRAFT"))kb.activate(r.versionId(),new Review("TEST reviewer","Synthetic test only, not a legal approval"));return rules.stream().map(Requirement::versionId).toList();}
 @Test void fullPipelineRealPythonStructuredImportAndOutage()throws Exception{
  var versions=activate();var m=machine(true);
  var document=kb.upload(m.id(),"questionario.json","application/json",Files.readAllBytes(root.resolve("data/demo/questionario-sintetico.json")));
  assertEquals("READY",document.processingStatus());var facts=store.forMachine("fact",m.id(),Fact.class);assertEquals(6,facts.size());assertTrue(facts.stream().allMatch(f->f.nature().equals("DECLARED")));
  for(var f:facts)kb.reviewFact(f.id(),new Review("TEST reviewer","Confirmed synthetic protocol DEMO-1"));
  var first=assessments.assess(m.id(),versions);assertEquals(Status.PARTIALLY_SATISFIED,first.status());assertEquals(4,first.controls().size());assertTrue(first.controls().stream().allMatch(c->c.decision().status().equals("UNAVAILABLE")));assertEquals(2,first.controls().stream().filter(c->c.status()==Status.SATISFIED).count());
  var requests=store.forMachine("evidence_request",m.id(),EvidenceRequest.class);assertEquals(2,requests.size());
  var response=kb.upload(m.id(),"response.json","application/json",Files.readAllBytes(root.resolve("data/demo/risposta.json")));for(var f:store.forMachine("fact",m.id(),Fact.class))if(f.evidenceId().equals(response.id()))kb.reviewFact(f.id(),new Review("TEST reviewer","Confirmed additional synthetic tests"));
  var second=assessments.respond(requests.getFirst().id(),response.id());assertEquals(Status.SATISFIED,second.status());assertEquals(Status.PARTIALLY_SATISFIED,store.get("assessment",first.id(),Assessment.class).status());
  kb.upload(m.id(),"conflict.json","application/json",Files.readAllBytes(root.resolve("data/demo/contraddizione.json")));var conflict=assessments.assess(m.id(),versions);assertEquals(Status.UNDETERMINED,conflict.controls().stream().filter(c->c.controlId().equals("CRA-AUTH-01")).findFirst().orElseThrow().status());
  var bad=store.forMachine("fact",m.id(),Fact.class).stream().filter(f->Boolean.FALSE.equals(f.value())).findFirst().orElseThrow();kb.retract(bad.id(),new Review("TEST reviewer","Synthetic conflict resolved after inspection"));assertEquals(Status.SATISFIED,assessments.assess(m.id(),versions).status());
  assertTrue(store.audit().size()>10);
  mvc.perform(get("/api/assessments/"+first.id()+"/report").header("Authorization","Bearer local-development-only")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PARTIALLY_SATISFIED"));
 }
 @Test void explicitScopeExclusion(){var m=machine(false);assertEquals(Status.NOT_APPLICABLE,assessments.assess(m.id(),activate()).status());}
 @Test void quoteAndCrossMachineValidation()throws Exception{
  var m=machine(true);var other=machine(true);var d=kb.upload(m.id(),"q.json","application/json",Files.readAllBytes(root.resolve("data/demo/questionario-sintetico.json")));
  assertThrows(IllegalArgumentException.class,()->kb.addFact(other.id(),new Fact(null,null,d.id(),"machine","x",true,"invented","p1","DECLARED",null,null)));
  assertThrows(IllegalArgumentException.class,()->kb.addFact(m.id(),new Fact(null,null,d.id(),"machine","x",true,"invented","p1","DECLARED",null,null)));
 }
 @Test void noModelsDoesNotInventFacts(){var m=machine(true);var d=kb.upload(m.id(),"manual.txt","text/plain","PLC with network".getBytes());assertEquals("TEXT_ONLY",d.processingStatus());assertEquals("PLC with network",d.text());assertTrue(store.forMachine("fact",m.id(),Fact.class).isEmpty());
  var bin=kb.upload(m.id(),"foto.png","image/png",new byte[]{1,2,3});assertEquals("FAILED",bin.processingStatus());}
 @Test void compilerBlocksUnreviewedRules()throws Exception{
  var original=store.all("requirement_version",Requirement.class).getFirst();var draft=new Requirement("TEST-DRAFT","0.1.0",original.law(),original.reference(),original.sourceUrl(),original.sourceSnapshot(),original.sourceHash(),original.originalText(),original.interpretation(),original.effectiveFrom(),original.scopeKey(),original.controls(),original.aggregation(),"ACTIVE","forged","forged",List.of());
  assertEquals("DRAFT",kb.draft(draft).status());assertThrows(IllegalArgumentException.class,()->assessments.assess(machine(true).id(),List.of(draft.versionId())));
  assertThrows(IllegalArgumentException.class,()->kb.activate(draft.versionId(),new Review("","")));
 }
 @Test void integralNormativeCorpusIsCompleteSearchableAndIdempotent()throws Exception{
  var docs=corpus.documents();assertEquals(2,docs.size());assertEquals(183,docs.stream().mapToInt(NormativeCorpus.Document::pageCount).sum());
  for(var d:docs){assertFalse(corpus.page(d.id(),1).text().isBlank());assertFalse(corpus.page(d.id(),d.pageCount()).text().isBlank());assertEquals(d.sha256(),KnowledgeService.sha(corpus.pdf(d.id())));}
  assertFalse(corpus.search("cibersicurezza").isEmpty());assertFalse(corpus.search("macchine").isEmpty());assertTrue(corpus.search("%' OR 1=1 --").isEmpty());
  corpus.importFiles();assertEquals(2,corpus.documents().size());
  mvc.perform(get("/api/normative-documents").header("Authorization","Bearer local-development-only")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
 }
 @Test void apiRequiresToken()throws Exception{mvc.perform(get("/api/machines")).andExpect(status().isUnauthorized());mvc.perform(get("/api/health")).andExpect(status().isOk());}
}
