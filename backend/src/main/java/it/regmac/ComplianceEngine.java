package it.regmac;
import org.springframework.stereotype.Component;
import java.util.*;
import static it.regmac.Domain.*;
@Component public class ComplianceEngine {
 public ControlResult evaluate(Task t,Decision d){
  Status status;String reason;
  var facts=t.evidence().stream().filter(f->f.key().equals(t.machine_state().get("fact_key"))).toList();
  Set<Object> all=new HashSet<>();facts.forEach(f->all.add(f.value()));
  var verified=facts.stream().filter(f->"VERIFIED".equals(f.nature())&&f.reviewer()!=null&&!f.reviewer().isBlank()).toList();
  if(Set.of("NOT_APPLICABLE","OUTSIDE_DATE").contains(t.applicability())){status=Status.NOT_APPLICABLE;reason=t.applicability().equals("OUTSIDE_DATE")?"Fuori dalla data di applicazione del controllo selezionato; non valuta gli altri obblighi transitori":"Ambito escluso mediante revisione umana";}
  else if(!"APPLICABLE".equals(t.applicability())){status=Status.UNDETERMINED;reason="Applicabilità da verificare";}
  else if(all.size()>1){status=Status.UNDETERMINED;reason="Evidenze contraddittorie: revisione necessaria";}
  else if(verified.stream().anyMatch(f->Boolean.FALSE.equals(f.value()))){status=Status.NOT_SATISFIED;reason="Test negativo verificato da un revisore";}
  else if(verified.stream().anyMatch(f->Boolean.TRUE.equals(f.value()))){status=Status.SATISFIED;reason="Test positivo verificato da un revisore; esito deterministico, non dedotto dalla probabilità AI";}
  else {status=Status.UNDETERMINED;reason=facts.isEmpty()?"Evidenza mancante":"Evidenza dichiarativa o inferita: occorre un test verificato";}
  return new ControlResult(t.requirement_id(),t.requirement_version(),t.control_id(),status,reason,t,d);
 }
 public Status aggregate(List<Status> statuses){
  if(statuses.isEmpty())return Status.UNDETERMINED;
  var relevant=statuses.stream().filter(s->s!=Status.NOT_APPLICABLE).toList();
  if(relevant.isEmpty())return Status.NOT_APPLICABLE;
  if(relevant.stream().allMatch(s->s==Status.SATISFIED))return Status.SATISFIED;
  if(relevant.contains(Status.NOT_SATISFIED))return Status.NOT_SATISFIED;
  if(relevant.contains(Status.SATISFIED)||relevant.contains(Status.PARTIALLY_SATISFIED))return Status.PARTIALLY_SATISFIED;
  return Status.UNDETERMINED;
 }
}
