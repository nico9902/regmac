package it.regmac;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;
@RestController @RequestMapping("/api/normative-documents") public class NormativeApi {
    private final NormativeCorpus corpus;
    public NormativeApi(NormativeCorpus corpus){this.corpus=corpus;}
    @GetMapping public List<NormativeCorpus.Document> list(){return corpus.documents();}
    @GetMapping("/{id}/pages/{page}") public NormativeCorpus.Page page(@PathVariable String id,@PathVariable int page){return corpus.page(id,page);}
    @GetMapping("/search") public List<NormativeCorpus.Hit> search(@RequestParam String q){return corpus.search(q);}
    @GetMapping("/{id}/pdf") public ResponseEntity<byte[]> pdf(@PathVariable String id)throws Exception{return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header("Content-Disposition","attachment; filename="+corpus.document(id).filename()).body(corpus.pdf(id));}
    @ExceptionHandler(NoSuchElementException.class) @ResponseStatus(HttpStatus.NOT_FOUND) public Map<String,String> missing(Exception e){return Map.of("error",e.getMessage());}
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST) public Map<String,String> invalid(Exception e){return Map.of("error",e.getMessage());}
}
