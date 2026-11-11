package it.regmac;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;


/** API della Wiki normativa per requisiti e delle checklist di valutazione manuale. */
@RestController
@RequestMapping("/api")
public class ChecklistApi {
    private final RequirementCatalog catalog;
    private final ChecklistService checklists;

    public ChecklistApi(RequirementCatalog catalog, ChecklistService checklists) {
        this.catalog = catalog;
        this.checklists = checklists;
    }

    @GetMapping("/requirement-catalog")
    public Map<String, Object> catalog() { return catalog.current(); }

    @GetMapping("/machines/{id}/checklists")
    public List<ChecklistService.Summary> list(@PathVariable String id) { return checklists.list(id); }

    @PostMapping("/machines/{id}/checklists")
    public ChecklistService.Checklist create(@PathVariable String id, @RequestBody(required = false) ChecklistService.Input in) { return checklists.create(id, in); }

    @GetMapping("/checklists/{id}")
    public Map<String, Object> get(@PathVariable String id) { return checklists.get(id); }

    @PutMapping("/checklists/{id}")
    public ChecklistService.Checklist update(@PathVariable String id, @RequestBody ChecklistService.Input in) { return checklists.update(id, in); }

    @PutMapping("/checklists/{id}/items/{requirementId}")
    public ChecklistService.Item item(@PathVariable String id, @PathVariable String requirementId, @RequestBody ChecklistService.Item in) {
        return checklists.saveItem(id, requirementId, in);
    }

    @PostMapping("/checklists/{id}/close")
    public ChecklistService.Checklist close(@PathVariable String id, @RequestBody ChecklistService.CloseInput in) { return checklists.close(id, in); }

    @PostMapping("/checklists/{id}/items/{requirementId}/suggest")
    public ChecklistService.Suggestion suggest(@PathVariable String id, @PathVariable String requirementId) { return checklists.suggest(id, requirementId); }

    @GetMapping("/checklists/{id}/report.pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String id) {
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=rapporto-" + id + ".pdf")
                .contentType(MediaType.APPLICATION_PDF).body(checklists.pdf(id));
    }

    @GetMapping("/checklists/{id}/report")
    public Map<String, Object> report(@PathVariable String id) { return checklists.report(id); }

    @GetMapping("/checklists/{id}/report.csv")
    public ResponseEntity<byte[]> csv(@PathVariable String id) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=checklist-" + id + ".csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(checklists.csv(id).getBytes(StandardCharsets.UTF_8));
    }

    @ExceptionHandler(SecurityException.class) @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, String> unauthorized(Exception e) { return Map.of("error", e.getMessage()); }

    @ExceptionHandler(NoSuchElementException.class) @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> missing(Exception e) { return Map.of("error", e.getMessage()); }

    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(Exception e) { return Map.of("error", e.getMessage()); }

    @ExceptionHandler(IllegalStateException.class) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> unavailable(Exception e) { return Map.of("error", e.getMessage()); }
}
