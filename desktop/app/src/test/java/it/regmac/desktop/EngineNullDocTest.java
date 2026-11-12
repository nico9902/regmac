package it.regmac.desktop;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

/** Regressione: un'affermazione libera (profilo, condizioni) non ha documento atteso e non deve far fallire la ricerca. */
class EngineNullDocTest {
  @TempDir Path temp;
  @Test void claimWithoutExpectedDocumentDoesNotFail() throws Exception {
    String previous = System.getProperty("regmac.data");
    System.setProperty("regmac.data", temp.toString());
    try {
      Engine engine = new Engine(new Store(), null, null);
      var v = engine.decide(Catalog.claim("Il prodotto è una quasi-macchina"), new Engine.Ctx(List.of(), Map.of(), new ConcurrentHashMap<>()));
      assertEquals("I", v.answer());
      var spec = new Catalog.Spec("Le istruzioni indicano la massa", "istruzioni", "massa in kg", "frasi generiche", null, null, null);
      assertEquals("DOC_MISSING", engine.decide(spec, new Engine.Ctx(List.of(), Map.of(), new ConcurrentHashMap<>())).reason());
    } finally { if (previous == null) System.clearProperty("regmac.data"); else System.setProperty("regmac.data", previous); }
  }
}
