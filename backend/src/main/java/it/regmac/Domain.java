package it.regmac;
import java.util.*;
import java.time.*;
public final class Domain {
 private Domain() {}
 public enum Status {SATISFIED, NOT_SATISFIED, PARTIALLY_SATISFIED, UNDETERMINED, NOT_APPLICABLE}
 public record Machine(String id,String name,boolean synthetic,LocalDate targetDate,Map<String,Boolean> scope,String scopeReviewer) {}
 public record Evidence(String id,String machineId,String name,String sha256,String mediaType,String text,String createdAt,String processingStatus,String error) {}
 public record Fact(String id,String machineId,String evidenceId,String subject,String key,Object value,String quote,String locator,String nature,String reviewer,String reviewNote) {}
 public record Entity(String id,String machineId,String type,String name,Map<String,Object> attributes) {}
 public record Relation(String id,String machineId,String sourceId,String targetId,String type) {}
 public record Question(String id,String type,String instructions,Map<String,String> criteria,List<String> levels) {}
 public record Control(String id,String factKey,String question,String evidenceType,String applicabilityKey,List<Question> questions) {}
 public record Requirement(String id,String version,String law,String reference,String sourceUrl,String sourceSnapshot,String sourceHash,String originalText,String interpretation,LocalDate effectiveFrom,String scopeKey,List<Control> controls,String aggregation,String status,String reviewer,String reviewNote,List<String> relatedRequirements) {
  public String versionId(){return id+"@"+version;}
 }
 public record Task(String requirement_id,String requirement_version,String control_id,String applicability,Map<String,Object> machine_state,List<Fact> evidence,List<Question> questions) {}
 public record Decision(String provider,String model,String status,boolean calibrated,boolean abstain,Map<String,Object> answers,String error) {}
 public record ControlResult(String requirementId,String version,String controlId,Status status,String reason,Task task,Decision decision) {}
 public record RequirementResult(String requirementId,String version,Status status) {}
 public record Assessment(String id,String machineId,String createdAt,Machine machineSnapshot,List<Evidence> sourceEvidence,List<Requirement> rules,List<ControlResult> controls,List<RequirementResult> requirements,Status status,String engineVersion) {}
 public record EvidenceRequest(String id,String machineId,String assessmentId,String key,List<String> controlIds,String question,String status,String responseEvidenceId) {}
 public record Review(String reviewer,String note) {}
 public static String id(){return UUID.randomUUID().toString();}
 public static String now(){return Instant.now().toString();}
}
