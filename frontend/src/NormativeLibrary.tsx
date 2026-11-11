import {useEffect,useState} from 'react';
import {BookOpen,Download,Search} from 'lucide-react';
type Document={id:string;title:string;law:string;pageCount:number;filename:string;sha256:string;publicationDate:string;edition:string};
type Page={number:number;text:string};
type Hit={documentId:string;page:number;text:string};
type Props={api:<T>(path:string,method?:string,body?:unknown)=>Promise<T>;download:(path:string,name:string)=>Promise<void>};
export default function NormativeLibrary({api,download}:Props){
 const [docs,setDocs]=useState<Document[]>([]),[selected,setSelected]=useState(''),[page,setPage]=useState<Page|null>(null),[query,setQuery]=useState(''),[hits,setHits]=useState<Hit[]|null>(null),[error,setError]=useState(''),[busy,setBusy]=useState(false);
 useEffect(()=>{let current=true;api<Document[]>('/normative-documents').then(d=>{if(current)setDocs(d)}).catch(e=>{if(current)setError(e.message)});return()=>{current=false}},[]);
 async function open(id:string,n:number){setBusy(true);setError('');try{const p=await api<Page>(`/normative-documents/${id}/pages/${n}`);setSelected(id);setPage(p)}catch(e){setError(String(e))}finally{setBusy(false)}}
 const doc=docs.find(d=>d.id===selected);
 return <section className="card"><div className="card-heading"><div><span className="eyebrow">CORPUS NORMATIVO INTEGRALE</span><h2>Documenti originali in italiano</h2><p>Testo estratto localmente, consultabile per pagina. Le pubblicazioni originali non includono automaticamente rettifiche o modifiche successive.</p></div><BookOpen size={24}/></div>
 {error&&<div className="alert" role="alert">{error}</div>}
 <div className="document-grid">{docs.map(d=><article key={d.id} className="document"><h3>{d.title}</h3><small>{d.pageCount} pagine · Pubblicazione {d.publicationDate}</small><small>{d.edition}</small><small className="hash">SHA-256 {d.sha256}</small><div className="actions"><button className="secondary small" disabled={busy} onClick={()=>open(d.id,1)}>Leggi testo</button><button className="text-button" onClick={()=>download(`/normative-documents/${d.id}/pdf`,d.filename)}><Download size={14}/> PDF originale</button></div></article>)}</div>
 {!docs.length&&<p>Nessun documento integrale importato.</p>}
 <form className="review-form" onSubmit={async e=>{e.preventDefault();setBusy(true);setError('');try{setHits(await api<Hit[]>('/normative-documents/search?q='+encodeURIComponent(query)))}catch(e){setError(String(e))}finally{setBusy(false)}}}><input aria-label="Ricerca nei testi normativi integrali" placeholder="Cerca nel testo integrale, es. cibersicurezza…" minLength={2} maxLength={200} value={query} onChange={e=>setQuery(e.target.value)} required/><button className="secondary" disabled={busy}><Search size={15}/> Cerca nel testo</button></form>
 {hits!==null&&<div><p>{hits.length} pagine trovate (massimo 50). Ricerca testuale locale.</p>{hits.map(h=><button className="text-button corpus-hit" key={h.documentId+h.page} disabled={busy} onClick={()=>open(h.documentId,h.page)}>{docs.find(d=>d.id===h.documentId)?.title} · pagina {h.page}</button>)}</div>}
 {doc&&page&&<div><div className="section-toolbar"><h3>{doc.title} · pagina {page.number}/{doc.pageCount}</h3><div className="actions"><button className="secondary small" disabled={busy||page.number===1} onClick={()=>open(doc.id,page.number-1)}>Precedente</button><button className="secondary small" disabled={busy||page.number===doc.pageCount} onClick={()=>open(doc.id,page.number+1)}>Successiva</button></div></div><pre className="legal-page">{page.text}</pre></div>}
 </section>
}
