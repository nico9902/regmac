package it.regmac;
import org.springframework.stereotype.Component;
import java.util.*;
import static it.regmac.Domain.*;
@Component public class DecisionTaskBuilder {
 public Task build(Machine m,Requirement r,Control c,List<Fact> facts){
  facts=facts.stream().filter(f->!"RETRACTED".equals(f.nature())&&"machine".equals(f.subject())).toList();
  String applicability="UNKNOWN";
  if(m.targetDate().isBefore(r.effectiveFrom()))applicability="OUTSIDE_DATE";
  else if(m.scopeReviewer()!=null&&!m.scopeReviewer().isBlank()&&m.scope().containsKey(r.scopeKey()))applicability=m.scope().get(r.scopeKey())?"APPLICABLE":"NOT_APPLICABLE";
  if("APPLICABLE".equals(applicability)&&c.applicabilityKey()!=null&&!c.applicabilityKey().isBlank()){
   Set<Object> values=new HashSet<>();facts.stream().filter(f->f.key().equals(c.applicabilityKey())&&"VERIFIED".equals(f.nature())).forEach(f->values.add(f.value()));
   applicability=values.size()!=1?"UNKNOWN":values.contains(Boolean.TRUE)?"APPLICABLE":values.contains(Boolean.FALSE)?"NOT_APPLICABLE":"UNKNOWN";
  }
  var selected=facts.stream().filter(f->f.key().equals(c.factKey())||f.key().equals(c.applicabilityKey())).toList();
  Map<String,Object> state=new LinkedHashMap<>();state.put("machine_id",m.id());state.put("target_date",m.targetDate());state.put("fact_key",c.factKey());state.put("required_evidence",c.evidenceType());
  return new Task(r.id(),r.version(),c.id(),applicability,state,selected,c.questions());
 }
}
