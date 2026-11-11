package it.regmac;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static it.regmac.Domain.*;
import static it.regmac.RequirementCompiler.require;
@Service public class KnowledgeService {
 final Store store;final RequirementCompiler compiler;final AiClient ai;final Path storage;final Path seed;
 public KnowledgeService(Store store,RequirementCompiler compiler,AiClient ai,@Value("${regmac.storage}")String storage,@Value("${regmac.seed}")String seed){this.store=store;this.compiler=compiler;this.ai=ai;this.storage=Path.of(storage);this.seed=Path.of(seed);}
 public Path storage(){return storage;}
 @EventListener(ApplicationReadyEvent.class) public void seed() throws Exception {
  Files.createDirectories(storage);
  if(Files.exists(seed)&&store.all("requirement_version",Requirement.class).isEmpty())for(Requirement r:store.mapper().readValue(seed.toFile(),Requirement[].class))draft(r);
 }
 @Transactional public Machine create(Machine input){
  require(input.name()!=null&&!input.name().isBlank()&&input.targetDate()!=null,"Nome e data target richiesti");
  require(input.scope()!=null&&input.scope().keySet().stream().allMatch(Set.of("machinery","digital_product")::contains),"Ambito non valido");
  require(input.scope().isEmpty()||input.scopeReviewer()!=null&&!input.scopeReviewer().isBlank(),"Revisore dell'ambito richiesto");
  var m=new Machine(id(),input.name(),input.synthetic(),input.targetDate(),input.scope(),input.scopeReviewer());store.insert("machine",m.id(),m);store.audit("MACHINE_CREATED",m.id(),m);return m;
 }
 @Transactional public Requirement draft(Requirement input){
  compiler.compile(input);
  var r=new Requirement(input.id(),input.version(),input.law(),input.reference(),input.sourceUrl(),input.sourceSnapshot(),input.sourceHash(),input.originalText(),input.interpretation(),input.effectiveFrom(),input.scopeKey(),input.controls(),input.aggregation(),"DRAFT",null,null,input.relatedRequirements());
  store.insert("requirement_version",r.versionId(),r);store.audit("REQUIREMENT_DRAFT",r.versionId(),r);return r;
 }
 @Transactional public Requirement activate(String id,Review review){
  var r=store.get("requirement_version",id,Requirement.class);require("DRAFT".equals(r.status()),"Versione già attivata: creare una nuova versione");
  // The snapshot is a deployment-owned local file; never fetch an arbitrary user URL.
  try {Path p=seed.toAbsolutePath().getParent().resolve(r.sourceSnapshot()).normalize();require(p.startsWith(seed.toAbsolutePath().getParent()),"Snapshot fuori directory");
   require(sha(Files.readAllBytes(p)).equals(r.sourceHash()),"Hash snapshot non corrispondente");
   require(Files.readString(p).contains(r.originalText()),"Estratto non presente nello snapshot");
  }catch(java.io.IOException e){throw new IllegalArgumentException("Snapshot ufficiale mancante");}
  r=compiler.activate(r,review);store.update("requirement_version",id,r);store.audit("REQUIREMENT_ACTIVATED",id,Map.of("review",review,"rule",r));return r;
 }
 public static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
 @Transactional public Evidence upload(String machineId,String name,String media,byte[] bytes){
  store.get("machine",machineId,Machine.class);require(bytes.length>0&&bytes.length<=20*1024*1024,"Documento vuoto o troppo grande");
  String eid=id();try{Files.write(storage.resolve(eid),bytes,StandardOpenOption.CREATE_NEW);}catch(Exception e){throw new IllegalStateException("Storage non disponibile",e);}
  var e=new Evidence(eid,machineId,name,sha(bytes),media,"",now(),"UPLOADED",null);store.insert("evidence",eid,e,machineId);store.audit("DOCUMENT_UPLOADED",eid,e);return process(eid);
 }
 @SuppressWarnings("unchecked") @Transactional public Evidence process(String eid){
  store.lockEvidence(eid);
  var e=store.get("evidence",eid,Evidence.class);
  require(!"READY".equals(e.processingStatus()),"Documento già elaborato");
  try {
   byte[] bytes=Files.readAllBytes(storage.resolve(eid));
   var result=ai.post("/extract",Map.of("name",e.name(),"content_base64",Base64.getEncoder().encodeToString(bytes)));
   String text=(String)result.get("text");require(text!=null&&!text.isBlank(),"Testo non estratto");
   // Validate every proposed quote before committing any fact. AI never produces VERIFIED facts.
   List<Fact> staged=new ArrayList<>();
   for(var item:(List<Map<String,Object>>)result.getOrDefault("facts",List.of())){
    String quote=(String)item.get("quote");require(quote!=null&&!quote.isBlank()&&text.contains(quote),"Citazione AI non riscontrata");
    staged.add(new Fact(id(),e.machineId(),eid,(String)item.getOrDefault("subject","machine"),(String)item.get("key"),item.get("value"),quote,(String)item.getOrDefault("locator","document"),"DECLARED",null,null));
   }
   e=new Evidence(e.id(),e.machineId(),e.name(),e.sha256(),e.mediaType(),text,e.createdAt(),"READY",null);
   store.update("evidence",eid,e);
   for(Fact f:staged)store.insert("fact",f.id(),f,f.machineId(),eid);
   store.audit("DOCUMENT_EXTRACTED",eid,Map.of("facts",staged,"extractor",result.getOrDefault("extractor","unknown")));
  }catch(Exception ex){
   // Ripiego senza AI: testo estratto localmente (nessun fatto), così il documento resta ricercabile e citabile.
   String local=localText(eid,e.name());
   if(local!=null&&!local.isBlank()){e=new Evidence(e.id(),e.machineId(),e.name(),e.sha256(),e.mediaType(),local,e.createdAt(),"TEXT_ONLY","Testo estratto localmente; estrazione dei fatti AI non eseguita: "+ex.getMessage());store.update("evidence",eid,e);store.audit("DOCUMENT_TEXT_EXTRACTED",eid,Map.of("extractor","locale","aiError",String.valueOf(ex.getMessage())));}
   else{e=new Evidence(e.id(),e.machineId(),e.name(),e.sha256(),e.mediaType(),e.text(),e.createdAt(),"FAILED",ex.getMessage());store.update("evidence",eid,e);store.audit("EXTRACTION_FAILED",eid,Map.of("error",String.valueOf(ex.getMessage())));}
  }
  return e;
 }
 /** Estrazione testuale locale per testo semplice e PDF con livello di testo (senza OCR). */
 private String localText(String eid,String name){
  try{
   byte[] bytes=Files.readAllBytes(storage.resolve(eid));String n=name==null?"":name.toLowerCase(Locale.ROOT);
   if(n.matches(".*\\.(txt|md|json|csv)$"))return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
   if(n.endsWith(".pdf")){
    var reader=new com.lowagie.text.pdf.PdfReader(bytes);var ex=new com.lowagie.text.pdf.parser.PdfTextExtractor(reader);StringBuilder sb=new StringBuilder();
    for(int i=1;i<=reader.getNumberOfPages();i++)sb.append("[pagina ").append(i).append("]\n").append(ex.getTextFromPage(i)).append("\n\n");
    reader.close();String t=sb.toString();return t.replaceAll("\\[pagina \\d+\\]\\s*","").isBlank()?null:t;
   }
  }catch(Exception ignored){}
  return null;
 }
 @Transactional public Fact addFact(String mid,Fact f){
  Evidence e=store.get("evidence",f.evidenceId(),Evidence.class);require(e.machineId().equals(mid),"Evidenza di un'altra macchina");
  require(f.key()!=null&&f.key().matches("[a-z0-9_.-]+")&&f.value()!=null,"Fatto incompleto");
  require(f.quote()!=null&&!f.quote().isBlank()&&e.text().contains(f.quote()),"Citazione non trovata nel documento");
  require(Set.of("DECLARED","INFERRED").contains(f.nature()),"I fatti nuovi richiedono una successiva revisione");
  var fact=new Fact(id(),mid,f.evidenceId(),f.subject(),f.key(),f.value(),f.quote(),f.locator(),f.nature(),null,null);store.insert("fact",fact.id(),fact,mid,f.evidenceId());store.audit("FACT_ADDED",fact.id(),fact);return fact;
 }
 @Transactional public Fact reviewFact(String fid,Review review){
  var f=store.get("fact",fid,Fact.class);require(review.reviewer()!=null&&!review.reviewer().isBlank()&&review.note()!=null&&!review.note().isBlank(),"Indicare revisore e protocollo/risultato del test");
  require(!"RETRACTED".equals(f.nature()),"Fatto ritirato: creare una nuova evidenza");
  require("machine".equals(f.subject()),"Il test deve coprire il controllo a livello macchina");
  require(f.value() instanceof Boolean,"Solo fatti booleani possono attestare questi controlli");
  var out=new Fact(f.id(),f.machineId(),f.evidenceId(),f.subject(),f.key(),f.value(),f.quote(),f.locator(),"VERIFIED",review.reviewer(),review.note());store.update("fact",fid,out);store.audit("FACT_VERIFIED",fid,Map.of("before",f,"after",out));return out;
 }
 @Transactional public Fact retract(String fid,Review review){
  var f=store.get("fact",fid,Fact.class);require(review.reviewer()!=null&&!review.reviewer().isBlank()&&review.note()!=null&&!review.note().isBlank(),"Revisore e motivo del ritiro richiesti");
  var out=new Fact(f.id(),f.machineId(),f.evidenceId(),f.subject(),f.key(),f.value(),f.quote(),f.locator(),"RETRACTED",review.reviewer(),review.note());store.update("fact",fid,out);store.audit("FACT_RETRACTED",fid,Map.of("before",f,"after",out));return out;
 }
 @Transactional public Machine scope(String mid,Machine input){
  Machine old=store.get("machine",mid,Machine.class);require(input.scopeReviewer()!=null&&!input.scopeReviewer().isBlank()&&input.scope()!=null&&input.targetDate()!=null,"Revisore, ambito e data richiesti");
  var out=new Machine(mid,old.name(),old.synthetic(),input.targetDate(),input.scope(),input.scopeReviewer());store.update("machine",mid,out);store.audit("SCOPE_REVIEWED",mid,Map.of("before",old,"after",out));return out;
 }
 @Transactional public Entity entity(String mid,Entity e){
  store.get("machine",mid,Machine.class);require(Set.of("COMPONENT","SUBSYSTEM","FUNCTION","CONTROL_SYSTEM","INTERFACE","NETWORK","PROTECTION","SOFTWARE","FIRMWARE","TEST").contains(e.type()),"Tipo non supportato");
  var out=new Entity(id(),mid,e.type(),e.name(),e.attributes());store.insert("machine_entity",out.id(),out,mid);store.audit("ENTITY_ADDED",out.id(),out);return out;
 }
 @Transactional public Relation relation(String mid,Relation r){
  require(store.get("machine_entity",r.sourceId(),Entity.class).machineId().equals(mid)&&store.get("machine_entity",r.targetId(),Entity.class).machineId().equals(mid),"Relazione tra macchine diverse");
  var out=new Relation(id(),mid,r.sourceId(),r.targetId(),r.type());store.insert("machine_relation",out.id(),out,mid,r.sourceId(),r.targetId());store.audit("RELATION_ADDED",out.id(),out);return out;
 }
}
