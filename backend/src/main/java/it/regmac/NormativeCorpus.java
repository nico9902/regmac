package it.regmac;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import static it.regmac.RequirementCompiler.require;

@Service public class NormativeCorpus {
    public record Page(int number,String text) {}
    public record Document(String id,String law,String title,String language,String publicationDate,String edition,String filename,String sha256,String sourceUrl,String provenance,String extractor,int pageCount,List<Page> pages) {
        public Document metadata(){return new Document(id,law,title,language,publicationDate,edition,filename,sha256,sourceUrl,provenance,extractor,pageCount,List.of());}
    }
    public record Hit(String documentId,int page,String text) {}
    private final Store store;
    private final JdbcTemplate jdbc;
    private final Path directory;
    public NormativeCorpus(Store store,JdbcTemplate jdbc,@Value("${regmac.seed}")String seed){this.store=store;this.jdbc=jdbc;directory=Path.of(seed).toAbsolutePath().getParent();}
    @EventListener(ApplicationReadyEvent.class) @Transactional
    public void importFiles() throws Exception {
        Path manifest=directory.resolve("corpus.json");
        if(!Files.exists(manifest))return;
        for(Document d:store.mapper().readValue(manifest.toFile(),Document[].class)){
            require(d.id()!=null&&d.id().matches("(MR|CRA)-[a-f0-9]{16}"),"ID documento non valido");
            require(d.pages()!=null&&d.pages().size()==d.pageCount()&&d.pageCount()>0,"Pagine mancanti");
            require(KnowledgeService.sha(Files.readAllBytes(file(d))).equals(d.sha256()),"Hash PDF non corrispondente");
            for(int i=0;i<d.pageCount();i++)require(d.pages().get(i).number()==i+1&&!d.pages().get(i).text().isBlank(),"Pagina vuota o fuori sequenza");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM normative_document WHERE sha256=?",Integer.class,d.sha256())>0)continue;
            jdbc.update("INSERT INTO normative_document VALUES (?,?,?)",d.id(),d.sha256(),store.json(d.metadata()));
            for(Page p:d.pages())jdbc.update("INSERT INTO normative_page VALUES (?,?,?)",d.id(),p.number(),p.text());
            store.audit("NORMATIVE_DOCUMENT_IMPORTED",d.id(),d.metadata());
        }
    }
    private Path file(Document d){Path p=directory.resolve(d.filename()).normalize();require(p.startsWith(directory)&&d.filename().endsWith(".pdf"),"Percorso PDF non valido");return p;}
    public List<Document> documents(){return jdbc.query("SELECT payload FROM normative_document ORDER BY id",(rs,n)->store.decode(rs.getString(1),Document.class));}
    public Document document(String id){return documents().stream().filter(d->d.id().equals(id)).findFirst().orElseThrow(()->new NoSuchElementException("Documento normativo non trovato"));}
    public byte[] pdf(String id)throws Exception{Document d=document(id);byte[] bytes=Files.readAllBytes(file(d));require(KnowledgeService.sha(bytes).equals(d.sha256()),"Il file originale è stato modificato");return bytes;}
    public Page page(String id,int page){document(id);var rows=jdbc.query("SELECT page_number,text_content FROM normative_page WHERE document_id=? AND page_number=?",(rs,n)->new Page(rs.getInt(1),rs.getString(2)),id,page);if(rows.isEmpty())throw new NoSuchElementException("Pagina non trovata");return rows.getFirst();}
    public List<Hit> search(String query){
        require(query!=null&&query.trim().length()>=2&&query.length()<=200,"Inserire da 2 a 200 caratteri");
        String pattern="%"+query.trim().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        return jdbc.query("SELECT document_id,page_number,text_content FROM normative_page WHERE LOWER(text_content) LIKE ? ESCAPE '!' ORDER BY document_id,page_number LIMIT 50",(rs,n)->new Hit(rs.getString(1),rs.getInt(2),rs.getString(3)),pattern);
    }
}
