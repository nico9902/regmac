package it.regmac;
import org.springframework.stereotype.Component;
import java.util.*;
import static it.regmac.Domain.*;
@Component public class RequirementCompiler {
 public Requirement compile(Requirement r){
  require(r.id()!=null&&r.id().matches("[A-Z0-9-]+"),"ID requisito non valido");
  require(r.version()!=null&&r.version().matches("[0-9]+\\.[0-9]+\\.[0-9]+"),"Versione semantica richiesta");
  require(Set.of("MR","CRA").contains(r.law()),"Normativa non supportata");
  require(r.sourceUrl()!=null&&r.sourceUrl().startsWith("https://eur-lex.europa.eu/"),"Fonte EUR-Lex richiesta");
  require(r.originalText()!=null&&!r.originalText().isBlank()&&r.interpretation()!=null&&!r.interpretation().isBlank(),"Separare testo e interpretazione");
  require(r.effectiveFrom()!=null&&r.scopeKey()!=null&&!r.scopeKey().isBlank(),"Ambito e data richiesti");
  require("ALL".equals(r.aggregation()),"Solo aggregazione ALL supportata");
  require(r.controls()!=null&&!r.controls().isEmpty(),"Controlli mancanti");
  Set<String> ids=new HashSet<>();
  for(Control c:r.controls()){
   require(c.id()!=null&&ids.add(c.id()),"ID controllo duplicato");
   require(c.factKey()!=null&&!c.factKey().isBlank()&&c.question()!=null&&!c.question().isBlank(),"Controllo incompleto");
   require("VERIFIED_TEST".equals(c.evidenceType()),"Tipo evidenza non supportato");
   require(c.questions()!=null&&!c.questions().isEmpty(),"Domande mancanti");Set<String> qids=new HashSet<>();
   for(Question q:c.questions()){
    require(qids.add(q.id())&&q.instructions()!=null&&!q.instructions().isBlank(),"Domanda non valida");
    require(Set.of("CHOICE","NOUL","SCORE").contains(q.type()),"Primitiva sconosciuta");
    if(q.type().equals("CHOICE"))require(q.criteria()!=null&&q.criteria().keySet().equals(Set.of("SUPPORTED","CONTRADICTED","INSUFFICIENT")),"Opzioni CHOICE richieste");
    if(q.type().equals("SCORE"))require(q.levels()!=null&&q.levels().size()>=2,"Criteri ordinali richiesti");
   }
  }
  return r;
 }
 public Requirement activate(Requirement r,Review review){
  compile(r);require(review.reviewer()!=null&&!review.reviewer().isBlank()&&review.note()!=null&&!review.note().isBlank(),"Revisore e motivazione richiesti");
  require(r.sourceHash()!=null&&r.sourceHash().matches("[a-f0-9]{64}")&&r.sourceSnapshot()!=null&&!r.sourceSnapshot().isBlank(),"Snapshot ufficiale con SHA-256 richiesto prima di attivare");
  return new Requirement(r.id(),r.version(),r.law(),r.reference(),r.sourceUrl(),r.sourceSnapshot(),r.sourceHash(),r.originalText(),r.interpretation(),r.effectiveFrom(),r.scopeKey(),r.controls(),r.aggregation(),"ACTIVE",review.reviewer(),review.note(),r.relatedRequirements());
 }
 static void require(boolean b,String message){if(!b)throw new IllegalArgumentException(message);}
}
