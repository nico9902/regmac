package it.regmac;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.net.http.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import static it.regmac.Domain.*;
@Component public class AiClient {
 private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
 private final String base;private final Store store;
 public AiClient(@Value("${regmac.ai-url}")String base,Store store){this.base=base;this.store=store;}
 @SuppressWarnings("unchecked") public Map<String,Object> post(String path,Object payload){
  try {var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(180)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(store.json(payload))).build();
   var response=client.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw new IllegalStateException("Servizio AI HTTP "+response.statusCode());return store.decode(response.body(),Map.class);
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("AI interrotta",e);}catch(Exception e){throw new IllegalStateException("AI non disponibile: "+e.getMessage(),e);}
 }
 public Decision decide(Task task){
  if(!"APPLICABLE".equals(task.applicability()))return new Decision("none","none","SKIPPED",false,true,Map.of(),"Applicabilità non confermata");
  try{return store.decode(store.json(post("/decide",task)),Decision.class);}catch(Exception e){return new Decision("unavailable","unknown","UNAVAILABLE",false,true,Map.of(),e.getMessage());}
 }
}
