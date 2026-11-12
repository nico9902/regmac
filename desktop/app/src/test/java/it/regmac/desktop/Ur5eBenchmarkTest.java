package it.regmac.desktop;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

/** Confronto locale opt-in: legge gli originali, scrive solo in target e nella directory temporanea. */
class Ur5eBenchmarkTest {
  @TempDir Path temp;
  final ObjectMapper json = new ObjectMapper();

  @Test void compareRetrievalAndJudgments() throws Exception {
    Assumptions.assumeTrue(Boolean.getBoolean("regmac.benchmark"));
    String previous = System.getProperty("regmac.data"); System.setProperty("regmac.data", temp.toString());
    try {
      Path goldPath = Path.of(System.getProperty("regmac.gold", "../../experiments/preassessment/ur5e/gold.json"));
      JsonNode gold = json.readTree(goldPath.toFile()).path("cases");
      JsonNode passages = json.readTree(Path.of("target/ur5e-chunks.json").toFile());
      Ollama ai = new Ollama();
      List<Store.Chunk> chunks = new ArrayList<>();
      for (JsonNode c : passages) {
        // Stessi cinque documenti inglesi del fascicolo iniziale: nessun vantaggio dal nuovo manuale italiano.
        if (c.path("doc").asText().startsWith("01_")) continue;
        chunks.add(new Store.Chunk("c" + chunks.size(), c.path("doc").asText(), c.path("page").asInt(), "", c.path("text").asText(), null));
      }
      String signature = Engine.sha(chunks.toString() + Ollama.EMBED_VERSION);
      Path vectors = Path.of("target/ur5e-vectors-" + signature.substring(0, 12) + ".json");
      List<float[]> embeddings;
      if (Files.exists(vectors)) embeddings = Arrays.asList(json.readValue(vectors.toFile(), float[][].class));
      else { embeddings = ai.embed(chunks.stream().map(Store.Chunk::text).toList(), false); json.writeValue(vectors.toFile(), embeddings); }
      for (int i=0; i<chunks.size(); i++) { var c=chunks.get(i); chunks.set(i,new Store.Chunk(c.id(),c.docId(),c.page(),c.section(),c.text(),embeddings.get(i))); }
      Store store = new Store(); Engine engine = new Engine(store, null, ai);
      List<String> claims = new ArrayList<>(); gold.forEach(c -> claims.add(c.path("claim").asText()));
      engine.prefetch(claims);
      List<Map<String,Object>> report = new ArrayList<>();
      Map<String,String> cache = new ConcurrentHashMap<>();
      Path judgeCache=Path.of("target/ur5e-benchmark-judgments.json");
      if (Files.exists(judgeCache)) cache.putAll(json.readValue(judgeCache.toFile(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){}));
      int found=0, eligible=0, correct=0, falseSupport=0;
      // modalità: "early" = arresto alla prima evidenza confermata; "all" = tutti i brani giudicati (schema precedente)
      String mode=System.getProperty("regmac.benchmark.mode","early");
      int threads=Integer.getInteger("regmac.benchmark.threads",4);
      boolean judgeOn=Boolean.getBoolean("regmac.benchmark.judge");
      var pool=java.util.concurrent.Executors.newFixedThreadPool(threads);
      List<java.util.concurrent.Future<Engine.Verdict>> futures=new ArrayList<>();
      long begin=System.nanoTime();
      for (JsonNode c : gold) {
        String claim=c.path("claim").asText();
        futures.add(pool.submit(() -> !judgeOn ? null : "all".equals(mode) ? decideAllPassages(engine,claim,chunks,cache) : engine.decide(claim,chunks,cache)));
      }
      List<Engine.Verdict> verdicts=new ArrayList<>(); for (var f:futures) verdicts.add(f.get()); pool.shutdown();
      double seconds=(System.nanoTime()-begin)/1e9;
      int k=0;
      for (JsonNode c : gold) {
        String claim=c.path("claim").asText(), expected=c.path("expected").asText();
        var hits=Retrieval.find(claim,engine.query(claim),chunks,Engine.TOP);
        boolean recalled=hits.stream().anyMatch(h -> h.chunk().docId().equals(c.path("document").asText()) && contains(c.path("pdfPages"),h.chunk().page()));
        if (!"I".equals(expected)) { eligible++; if(recalled) found++; }
        String answer="NOT_RUN",reason="";
        var verdict=verdicts.get(k++);
        if (verdict!=null) {
          answer=verdict.answer();reason=verdict.reason();
          if(answer.equals(expected)) correct++;
          if(answer.equals("S") && !expected.equals("S"))falseSupport++;
        }
        var row=new LinkedHashMap<String,Object>();row.put("id",c.path("id").asText());row.put("expected",expected);row.put("answer",answer);row.put("goldPageRetrieved",recalled);row.put("reason",reason);
        row.put("candidates",hits.stream().map(h->Map.of("doc",h.chunk().docId(),"page",h.chunk().page(),"similarity",h.similarity())).toList());
        report.add(row);System.out.println(c.path("id").asText()+" atteso="+expected+" ottenuto="+answer+" pagina="+recalled);
      }
      if (judgeOn) json.writeValue(judgeCache.toFile(),cache);
      json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/ur5e-benchmark.json").toFile(),Map.of("embeddingModel",Ollama.EMBED,"mode",mode,"threads",threads,"cases",report,"retrievalHits",found,"retrievalEligible",eligible,"correct",correct,"falseSupport",falseSupport,"seconds",seconds,"modelCalls",engine.calls.get()));
      System.out.println("TEMPO "+mode+" thread="+threads+" secondi="+String.format("%.1f",seconds)+" chiamate="+engine.calls.get());
      System.out.println("RECALL "+found+"/"+eligible+" CORRETTI "+correct+"/"+gold.size()+" FALSI S "+falseSupport);
      assertTrue(found >= 16, "Recupero delle pagine di riferimento inferiore all'obiettivo iniziale");
      if (Boolean.getBoolean("regmac.benchmark.judge")) {
        assertEquals(0,falseSupport,"Un falso positivo impedisce la promozione del giudice");
        assertTrue(correct >= 24,"Almeno 24/30 giudizi corretti nel campione");
      }
    } finally { if(previous==null)System.clearProperty("regmac.data");else System.setProperty("regmac.data",previous); }
  }
  /** Schema precedente: tutti i brani giudicati, contraddizione e supporto insieme = conflitto. */
  static Engine.Verdict decideAllPassages(Engine engine,String claim,List<Store.Chunk> chunks,Map<String,String> cache) throws Exception {
    var cand=Retrieval.find(claim,engine.query(claim),chunks,Engine.TOP);
    List<Engine.Judgment> v=new ArrayList<>(); for(var h:cand) v.add(engine.judgeOne(claim,h.chunk().text(),cache));
    boolean s=v.stream().anyMatch(j->"S".equals(j.v())), c=v.stream().anyMatch(j->"C".equals(j.v()));
    if(s&&c) return new Engine.Verdict("I",null,null,0,"CONFLICTING_EVIDENCE",cand.size());
    for(int i=0;i<v.size();i++) if(!"N".equals(v.get(i).v())) return new Engine.Verdict(v.get(i).v(),cand.get(i).chunk(),v.get(i).quote(),1,"EVIDENCE_FOUND",cand.size());
    return new Engine.Verdict("I",null,null,0,"INSUFFICIENT_EVIDENCE",cand.size());
  }
  static boolean contains(JsonNode a,int v){for(JsonNode n:a)if(n.asInt()==v)return true;return false;}
}
