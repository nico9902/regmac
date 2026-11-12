package it.regmac.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Test opt-in sui documenti pubblici, non redistribuiti nel repository. */
class RealPdfTest {
  @Test void allPublicPdfsIncludingTheItalianManualAreReadable() throws Exception {
    String dir = System.getProperty("regmac.test.pdfDir");
    Assumptions.assumeTrue(dir != null, "Specificare -Dregmac.test.pdfDir per i sei PDF UR5e");
    var chunks = new ArrayList<Map<String,Object>>();
    int pages = 0, files = 0;
    try(var walk = Files.walk(Path.of(dir))) {
      for (Path path : walk.filter(p -> p.toString().endsWith(".pdf")).sorted().toList()) {
        var result = TextExtractor.extract(path);
        assertFalse(result.passages().isEmpty(), path.toString());
        assertTrue(result.passages().stream().allMatch(p -> p.text().length() <= 1200));
        if (path.getFileName().toString().startsWith("01_")) {
          assertEquals(257, result.pages());
          assertTrue(result.passages().stream().anyMatch(p -> p.page() > 38 && p.text().length() > 100));
        }
        for(var p : result.passages()) chunks.add(Map.of("doc", path.getFileName().toString(), "page", p.page(), "text", p.text()));
        pages += result.pages(); files++;
        System.out.println("PDF OK " + path.getFileName() + " | pagine=" + result.pages() + " | brani=" + result.passages().size());
      }
    }
    assertEquals(6, files); assertEquals(644, pages);
    Path output = Path.of(System.getProperty("regmac.test.output", "target/ur5e-chunks.json"));
    Files.createDirectories(output.toAbsolutePath().getParent());
    new ObjectMapper().writeValue(output.toFile(), chunks);
  }
}
