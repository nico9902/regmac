package it.regmac;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static it.regmac.Domain.*;
import static it.regmac.RequirementCompiler.require;
@Service public class AssessmentService {
 final Store store;final DecisionTaskBuilder builder;final ComplianceEngine engine;final AiClient ai;final ActiveComplianceAgent agent;
 public AssessmentService(Store store,DecisionTaskBuilder builder,ComplianceEngine engine,AiClient ai,ActiveComplianceAgent agent){this.store=store;this.builder=builder;this.engine=engine;this.ai=ai;this.agent=agent;}
 @Transactional public Assessment assess(String mid,List<String> versions){
  Machine m=store.get("machine",mid,Machine.class);require(versions!=null&&!versions.isEmpty(),"Selezionare almeno una versione approvata");
  List<Requirement> rules=versions.stream().distinct().map(id->store.get("requirement_version",id,Requirement.class)).toList();
  require(rules.stream().allMatch(r->"ACTIVE".equals(r.status())),"Controlli non approvati");require(rules.stream().map(Requirement::id).distinct().count()==rules.size(),"Una sola versione per requisito");
  var facts=store.forMachine("fact",mid,Fact.class);List<ControlResult> controls=new ArrayList<>();List<RequirementResult> requirements=new ArrayList<>();
  for(var r:rules){List<Status> states=new ArrayList<>();for(var c:r.controls()){var task=builder.build(m,r,c,facts);var result=engine.evaluate(task,ai.decide(task));controls.add(result);states.add(result.status());}requirements.add(new RequirementResult(r.id(),r.version(),engine.aggregate(states)));}
  var usedIds=controls.stream().flatMap(c->c.task().evidence().stream()).map(Fact::evidenceId).distinct().toList();
  var sources=usedIds.stream().map(eid->store.get("evidence",eid,Evidence.class)).map(e->new Evidence(e.id(),e.machineId(),e.name(),e.sha256(),e.mediaType(),"",e.createdAt(),e.processingStatus(),e.error())).toList();
  var a=new Assessment(id(),mid,now(),m,sources,rules,controls,requirements,engine.aggregate(requirements.stream().map(RequirementResult::status).toList()),"0.1.0");store.insert("assessment",a.id(),a,mid);
  for(var request:agent.plan(a))store.insert("evidence_request",request.id(),request,mid,a.id());
  store.audit("ASSESSMENT_COMPLETED",a.id(),Map.of("status",a.status(),"versions",versions));return a;
 }
 @Transactional public Assessment respond(String requestId,String evidenceId){
  var r=store.get("evidence_request",requestId,EvidenceRequest.class);var e=store.get("evidence",evidenceId,Evidence.class);
  require(r.machineId().equals(e.machineId()),"Documento di un'altra macchina");require("READY".equals(e.processingStatus()),"Documento non ancora elaborato");require("OPEN".equals(r.status()),"Richiesta già risposta");
  store.update("evidence_request",requestId,new EvidenceRequest(r.id(),r.machineId(),r.assessmentId(),r.key(),r.controlIds(),r.question(),"ANSWERED",evidenceId));
  store.audit("REQUEST_ANSWERED",requestId,Map.of("evidenceId",evidenceId));var previous=store.get("assessment",r.assessmentId(),Assessment.class);
  return assess(r.machineId(),previous.rules().stream().map(Requirement::versionId).toList());
 }
}
