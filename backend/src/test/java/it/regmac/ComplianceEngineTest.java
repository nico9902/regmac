package it.regmac;
import org.junit.jupiter.api.Test;
import java.util.*;
import static it.regmac.Domain.*;
import static org.junit.jupiter.api.Assertions.*;
class ComplianceEngineTest {
 final ComplianceEngine engine=new ComplianceEngine();
 final Decision unavailable=new Decision("unavailable","none","UNAVAILABLE",false,true,Map.of(),"offline");
 Fact fact(Object value,String nature){return new Fact(id(),"m","e","machine","test",value,"source","line 1",nature,nature.equals("VERIFIED")?"TEST reviewer":null,"test protocol");}
 Task task(String applicable,Fact... facts){return new Task("REQ","1.0.0","CHECK",applicable,Map.of("fact_key","test"),List.of(facts),List.of());}
 @Test void missingEvidenceIsNotFailure(){assertEquals(Status.UNDETERMINED,engine.evaluate(task("APPLICABLE"),unavailable).status());}
 @Test void declarationsNeverProveCompliance(){assertEquals(Status.UNDETERMINED,engine.evaluate(task("APPLICABLE",fact(true,"DECLARED")),unavailable).status());}
 @Test void probabilityAloneNeverProvesCompliance(){var decision=new Decision("test-fixture","none","OK",true,false,Map.of("support",Map.of("SUPPORTED",.999)),null);assertEquals(Status.UNDETERMINED,engine.evaluate(task("APPLICABLE"),decision).status());}
 @Test void verifiedPositiveCanSurviveModelOutage(){assertEquals(Status.SATISFIED,engine.evaluate(task("APPLICABLE",fact(true,"VERIFIED")),unavailable).status());}
 @Test void verifiedNegativeIsFailure(){assertEquals(Status.NOT_SATISFIED,engine.evaluate(task("APPLICABLE",fact(false,"VERIFIED")),unavailable).status());}
 @Test void contradictionsRequireReview(){assertEquals(Status.UNDETERMINED,engine.evaluate(task("APPLICABLE",fact(true,"VERIFIED"),fact(false,"DECLARED")),unavailable).status());}
 @Test void explicitNonApplicability(){assertEquals(Status.NOT_APPLICABLE,engine.evaluate(task("NOT_APPLICABLE"),unavailable).status());}
 @Test void unknownApplicabilityIsNotExclusion(){assertEquals(Status.UNDETERMINED,engine.evaluate(task("UNKNOWN",fact(true,"VERIFIED")),unavailable).status());}
 @Test void aggregationIsConservative(){assertEquals(Status.PARTIALLY_SATISFIED,engine.aggregate(List.of(Status.SATISFIED,Status.UNDETERMINED)));assertEquals(Status.NOT_SATISFIED,engine.aggregate(List.of(Status.SATISFIED,Status.NOT_SATISFIED)));assertEquals(Status.UNDETERMINED,engine.aggregate(List.of()));assertEquals(Status.NOT_APPLICABLE,engine.aggregate(List.of(Status.NOT_APPLICABLE)));}
}
