package it.regmac.desktop;

import org.junit.jupiter.api.*;
import java.util.*;

/** Diagnosi opt-in: mostra brani candidati e giudizi per un'affermazione su un fascicolo esistente. */
class ClaimDebugTest {
  @Test void explain() throws Exception {
    String id = System.getProperty("regmac.debug.dossier"), claim = System.getProperty("regmac.debug.claim");
    Assumptions.assumeTrue(id != null && claim != null);
    Store store = new Store(); Ollama ai = new Ollama(); Engine engine = new Engine(store, null, ai);
    var index = store.index(id); Map<String, String> cache = new HashMap<>();
    for (var h : Retrieval.find(claim, engine.query(claim), index, Engine.TOP)) {
      String t = h.chunk().text();
      var a = Engine.validate(ai.chat(Engine.prompt(claim, t), Engine.ONE), t);
      var raw = ai.chat(Engine.prompt(claim, t), Engine.ONE);
      var chk = ai.chat(Engine.check(claim, t), Engine.ONE);
      System.out.println("DBG p." + h.chunk().page() + " sim=" + String.format("%.3f", h.similarity()) + " raw=" + raw + " check=" + chk + " valid=" + a.v()
          + "\nDBG   " + t.substring(0, Math.min(300, t.length())).replace('\n', ' '));
    }
  }
}
