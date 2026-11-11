package it.regmac;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static it.regmac.Domain.*;
@RestController @RequestMapping("/api") public class SearchApi {
 final Store store;final AiClient ai;
 public SearchApi(Store store,AiClient ai){this.store=store;this.ai=ai;}
 public record Query(String query){}
 @PostMapping("/machines/{id}/index") public Map<String,Object> index(@PathVariable String id){
  store.get("machine",id,Machine.class);
  var chunks=store.forMachine("fact",id,Fact.class).stream().filter(f->!"RETRACTED".equals(f.nature())).map(f->Map.of("id","machine:"+f.id(),"text",f.key()+"="+f.value()+"\n"+f.quote())).toList();
  return ai.post("/index",Map.of("namespace","machine","owner_id",id,"chunks",chunks));
 }
 @PostMapping("/machines/{id}/search") public Map<String,Object> search(@PathVariable String id,@RequestBody Query q){store.get("machine",id,Machine.class);return ai.post("/search",Map.of("namespace","machine","owner_id",id,"query",q.query()));}
 @PostMapping("/requirements/index") public Map<String,Object> indexRules(){
  var chunks=store.all("requirement_version",Requirement.class).stream().map(r->Map.of("id","normative:"+r.versionId(),"text",r.reference()+"\n"+r.originalText()+"\nINTERPRETATION: "+r.interpretation())).toList();
  return ai.post("/index",Map.of("namespace","normative","owner_id","wiki","chunks",chunks));
 }
 @PostMapping("/requirements/search") public Map<String,Object> searchRules(@RequestBody Query q){return ai.post("/search",Map.of("namespace","normative","owner_id","wiki","query",q.query()));}
}
