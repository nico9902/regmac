package it.regmac;
import org.springframework.stereotype.Component;
import java.util.*;
import static it.regmac.Domain.*;
@Component public class ActiveComplianceAgent {
 public List<EvidenceRequest> plan(Assessment a){
  Map<String,List<String>> groups=new LinkedHashMap<>();
  for(var c:a.controls())if(c.status()==Status.UNDETERMINED){String key="UNKNOWN".equals(c.task().applicability())?"applicability":(String)c.task().machine_state().get("fact_key");groups.computeIfAbsent(key,k->new ArrayList<>()).add(c.controlId());}
  return groups.entrySet().stream().map(e->new EvidenceRequest(id(),a.machineId(),a.id(),e.getKey(),e.getValue(),
   "Fornire o verificare evidenze per "+e.getKey()+"; controllare anche eventuali contraddizioni. Allegare protocollo, risultato e configurazione del test.","OPEN",null)).toList();
 }
}
