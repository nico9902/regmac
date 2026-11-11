package it.regmac;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
import static it.regmac.Domain.*;
@Repository public class Store {
 final JdbcTemplate jdbc; final ObjectMapper mapper;
 private static final Set<String> TABLES=Set.of("machine","evidence","fact","machine_entity","machine_relation","requirement_version","assessment","evidence_request");
 public Store(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
 public ObjectMapper mapper(){return mapper;}
 public void checkHealth(){jdbc.queryForObject("SELECT 1",Integer.class);}
 public String json(Object o){try{return mapper.writeValueAsString(o);}catch(Exception e){throw new IllegalArgumentException("JSON non valido",e);}}
 public <T>T decode(String s,Class<T> c){try{return mapper.readValue(s,c);}catch(Exception e){throw new IllegalStateException("Record non valido",e);}}
 private String table(String t){if(!TABLES.contains(t))throw new IllegalArgumentException();return t;}
 public <T>List<T> all(String t,Class<T> c){return jdbc.query("SELECT payload FROM "+table(t)+" ORDER BY id",(rs,n)->decode(rs.getString(1),c));}
 public <T>List<T> forMachine(String t,String id,Class<T> c){return jdbc.query("SELECT payload FROM "+table(t)+" WHERE machine_id=? ORDER BY id",(rs,n)->decode(rs.getString(1),c),id);}
 public <T>T get(String t,String id,Class<T> c){var rows=jdbc.query("SELECT payload FROM "+table(t)+" WHERE id=?",(rs,n)->decode(rs.getString(1),c),id);if(rows.isEmpty())throw new NoSuchElementException(t+": "+id);return rows.getFirst();}
 public void insert(String t,String id,Object o,String... relations){
  String columns="id,payload";List<Object> args=new ArrayList<>(List.of(id,json(o)));
  if(relations.length>0){columns+=",machine_id";args.add(relations[0]);}
  if(t.equals("fact")){columns+=",evidence_id";args.add(relations[1]);}
  if(t.equals("machine_relation")){columns+=",source_id,target_id";args.add(relations[1]);args.add(relations[2]);}
  if(t.equals("evidence_request")){columns+=",assessment_id";args.add(relations[1]);}
  jdbc.update("INSERT INTO "+table(t)+" ("+columns+") VALUES ("+String.join(",",Collections.nCopies(args.size(),"?"))+")",args.toArray());
 }
 public void update(String t,String id,Object o){if(jdbc.update("UPDATE "+table(t)+" SET payload=? WHERE id=?",json(o),id)!=1)throw new NoSuchElementException(id);}
 public void lockEvidence(String id){jdbc.queryForObject("SELECT id FROM evidence WHERE id=? FOR UPDATE",String.class,id);}
 public void audit(String action,String subject,Object payload){jdbc.update("INSERT INTO audit_event (id,occurred_at,action,subject,payload,actor) VALUES (?,?,?,?,?,?)",id(),now(),action,subject,json(payload),UserService.actor());}
 public List<Map<String,Object>> audit(){return jdbc.query("SELECT id,occurred_at,action,subject,payload,actor FROM audit_event ORDER BY occurred_at,id",(rs,n)->{Map<String,Object> m=new LinkedHashMap<>();m.put("id",rs.getString(1));m.put("occurred_at",rs.getString(2));m.put("action",rs.getString(3));m.put("subject",rs.getString(4));m.put("payload",rs.getString(5));m.put("actor",rs.getString(6));return m;});}
}
