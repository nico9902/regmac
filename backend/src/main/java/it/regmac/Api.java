package it.regmac;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
import java.nio.file.Files;
import static it.regmac.Domain.*;
@RestController @RequestMapping("/api") public class Api {
 final Store s;final KnowledgeService kb;final AssessmentService assessments;final AiClient ai;
 public Api(Store s,KnowledgeService kb,AssessmentService assessments,AiClient ai){this.s=s;this.kb=kb;this.assessments=assessments;this.ai=ai;}
 @GetMapping("/health") public Map<String,String> health(){s.checkHealth();return Map.of("status","UP","version","0.1.0");}
 @GetMapping("/machines") public List<Machine> machines(){return s.all("machine",Machine.class);}
 @PostMapping("/machines") public Machine create(@RequestBody Machine m){return kb.create(m);}
 @GetMapping("/machines/{id}/knowledge") public Map<String,Object> knowledge(@PathVariable String id){return Map.of("machine",s.get("machine",id,Machine.class),"facts",s.forMachine("fact",id,Fact.class),"evidence",s.forMachine("evidence",id,Evidence.class),"entities",s.forMachine("machine_entity",id,Entity.class),"relations",s.forMachine("machine_relation",id,Relation.class));}
 @PostMapping("/machines/{id}/documents") public Evidence upload(@PathVariable String id,@RequestParam MultipartFile file)throws Exception{return kb.upload(id,file.getOriginalFilename(),Objects.toString(file.getContentType(),"application/octet-stream"),file.getBytes());}
 @PostMapping("/documents/{id}/retry") public Evidence retry(@PathVariable String id){return kb.process(id);}
 @GetMapping("/documents/{id}") public ResponseEntity<byte[]> document(@PathVariable String id)throws Exception{s.get("evidence",id,Evidence.class);return ResponseEntity.ok().header("Content-Disposition","attachment; filename="+id+".bin").contentType(MediaType.APPLICATION_OCTET_STREAM).body(Files.readAllBytes(kb.storage().resolve(id)));}
 @PostMapping("/machines/{id}/facts") public Fact fact(@PathVariable String id,@RequestBody Fact f){return kb.addFact(id,f);}
 @PostMapping("/facts/{id}/verify") public Fact review(@PathVariable String id,@RequestBody Review review){return kb.reviewFact(id,review);}
 @PostMapping("/machines/{id}/entities") public Entity entity(@PathVariable String id,@RequestBody Entity e){return kb.entity(id,e);}
 @PostMapping("/machines/{id}/relations") public Relation relation(@PathVariable String id,@RequestBody Relation r){return kb.relation(id,r);}
 @PostMapping("/facts/{id}/retract") public Fact retract(@PathVariable String id,@RequestBody Review r){return kb.retract(id,r);}
 @PutMapping("/machines/{id}/scope") public Machine scope(@PathVariable String id,@RequestBody Machine m){return kb.scope(id,m);}
 @GetMapping("/requirements") public List<Requirement> requirements(){return s.all("requirement_version",Requirement.class);}
 @PostMapping("/requirements") public Requirement draft(@RequestBody Requirement r){return kb.draft(r);}
 @PostMapping("/requirements/{id}/activate") public Requirement activate(@PathVariable String id,@RequestBody Review review){return kb.activate(id,review);}
 public record AssessmentInput(List<String> versions){}
 @PostMapping("/machines/{id}/assessments") public Assessment run(@PathVariable String id,@RequestBody AssessmentInput input){return assessments.assess(id,input.versions());}
 @GetMapping("/machines/{id}/assessments") public List<Assessment> history(@PathVariable String id){return s.forMachine("assessment",id,Assessment.class);}
 @GetMapping("/assessments/{id}/report") public ResponseEntity<Assessment> report(@PathVariable String id){return ResponseEntity.ok().header("Content-Disposition","attachment; filename=assessment-"+id+".json").body(s.get("assessment",id,Assessment.class));}
 @GetMapping("/machines/{id}/requests") public List<EvidenceRequest> requests(@PathVariable String id){return s.forMachine("evidence_request",id,EvidenceRequest.class);}
 public record ResponseInput(String evidenceId){}
 @PostMapping("/requests/{id}/respond") public Assessment respond(@PathVariable String id,@RequestBody ResponseInput input){return assessments.respond(id,input.evidenceId());}
 @GetMapping("/audit") public List<Map<String,Object>> audit(){return s.audit();}
 @ExceptionHandler(NoSuchElementException.class) @ResponseStatus(HttpStatus.NOT_FOUND) public Map<String,String> missing(Exception e){return Map.of("error",e.getMessage());}
 @ExceptionHandler({IllegalArgumentException.class,org.springframework.dao.DataIntegrityViolationException.class}) @ResponseStatus(HttpStatus.BAD_REQUEST) public Map<String,String> invalid(Exception e){return Map.of("error",e instanceof IllegalArgumentException?e.getMessage():"Record duplicato o relazione non valida");}
 @ExceptionHandler(IllegalStateException.class) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE) public Map<String,String> unavailable(Exception e){return Map.of("error",e.getMessage());}
}
