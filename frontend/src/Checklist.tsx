import {useCallback,useEffect,useMemo,useRef,useState} from 'react';
import {AlertTriangle,BookOpen,ChevronDown,ChevronRight,Copy,Download,ExternalLink,FileText,Link2,Lock,Paperclip,Plus,Printer,Search,Sparkles,Trash2,Upload} from 'lucide-react';

// ------------------------------------------------------------------ tipi
export type Api=<T>(path:string,method?:string,body?:unknown)=>Promise<T>;
export type Standard={code:string;title:string;type:string;status:string};
export type CatalogRequirement={id:string;law:'MR'|'CRA';section:string;group:string;ref:string;title:string;text:string;pages:number[];tags:string[];assessable:boolean;always:boolean;kind:string;standards?:Standard[]};
export type Law={id:string;code:string;short:string;title:string;application:string;publicationDate:string;sections:{id:string;title:string}[]};
export type ProfileTag={id:string;label:string;group:'tipo'|'mr'|'cra'};
export type Catalog={sha256:string;disclaimer:string;generatedFrom:{law:string;sha256:string;filename:string}[];laws:Law[];profileTags:ProfileTag[];requirements:CatalogRequirement[];standardsNotice?:{text:string;updated:string}|null;changes?:{previous:string|null;added?:string[];removed?:string[];changed?:string[]}|null};
export type Details={model:string;serial:string;year:string;manufacturer:string;client:string;description:string;intendedUse:string;assessor:string;organization:string;assessmentDate:string;location:string};
export type Signature={userId:string;username:string;displayName:string;signedAt:string;note:string;reportSha256:string;reportPages:number};
export type ChecklistHead={id:string;machineId:string;title:string;status:'DRAFT'|'CLOSED';createdAt:string;updatedAt:string;catalogSha256:string;profile:string[];details:Details;conclusions:string;closedBy:string|null;closedAt:string|null;closingNote:string|null;basedOn:string|null;revision?:number;createdBy?:string;signature?:Signature|null};
export type EvidenceRef={evidenceId:string;name?:string;sha256?:string;locator?:string};
export type Suggestion={status:'OK'|'UNAVAILABLE'|'NO_EVIDENCE';proposal:string|null;rationale:string|null;citations:{evidenceId:string;document?:string;quote:string}[];missing:string[];model:string|null;createdAt:string;error:string|null};
export type Item={requirementId?:string;status?:Status;evidence?:string;standard?:string;note?:string;action?:string;owner?:string;due?:string|null;updatedAt?:string;evidenceRefs?:EvidenceRef[];textSha256?:string|null;assessedBy?:string|null;suggestion?:Suggestion|null};
export type Summary={checklist:ChecklistHead;counts:Record<Status,number>;total:number};
export type Doc={id:string;name:string;sha256:string;processingStatus:string};
type Status='COMPLIANT'|'PARTIAL'|'NON_COMPLIANT'|'NOT_APPLICABLE'|'TO_ASSESS';
type Row={requirement:CatalogRequirement;inScope:boolean;status:Status;item:Item|null;textChanged:boolean};
export type Report={checklist:ChecklistHead;machine:{id:string;name:string;targetDate:string;synthetic:boolean};catalog:{sha256:string;disclaimer:string;generatedFrom:{law:string;sha256:string;filename:string}[];laws:Law[];profileTags:ProfileTag[]};counts:Record<Status,number>;total:number;applicable:number;complianceIndex:number;verdict:'COMPLIANT'|'NON_COMPLIANT'|'INCOMPLETE'|'PARTIAL';rows:Row[];generatedAt:string};

export const STATUSES:Status[]=['COMPLIANT','PARTIAL','NON_COMPLIANT','NOT_APPLICABLE','TO_ASSESS'];
export const STATUS_LABEL:Record<Status,string>={COMPLIANT:'Conforme',PARTIAL:'Parzialmente conforme',NON_COMPLIANT:'Non conforme',NOT_APPLICABLE:'Non applicabile',TO_ASSESS:'Da valutare'};
const STATUS_SHORT:Record<Status,string>={COMPLIANT:'Conforme',PARTIAL:'Parziale',NON_COMPLIANT:'Non conforme',NOT_APPLICABLE:'N/A',TO_ASSESS:'Da valutare'};
const PROPOSAL:Record<string,string>={COMPLIANT:'Conforme',PARTIAL:'Parzialmente conforme',NON_COMPLIANT:'Non conforme',NOT_APPLICABLE:'Non applicabile',INSUFFICIENT:'Evidenze insufficienti'};
const FIELDS:[keyof Item,string,'area'|'text'|'date'][]=[['note','Rilievo / motivazione','area'],['action','Azione correttiva','area'],['owner','Responsabile','text'],['due','Scadenza','date']];
const fmtDate=(d?:string|null)=>d?d.slice(0,10).split('-').reverse().join('/'):'—';
const fmtTime=(d?:string|null)=>d?new Date(d).toLocaleString('it-IT',{dateStyle:'short',timeStyle:'short'}):'—';
const norm=(s:string)=>s.normalize('NFD').replace(/[̀-ͯ]/g,'').toLowerCase();
const statusOf=(items:Record<string,Item>,id:string):Status=>items[id]?.status||'TO_ASSESS';
export const inScope=(r:CatalogRequirement,profile:string[])=>r.assessable&&r.tags.some(t=>profile.includes(t));
const plural=(n:number,one:string,many:string)=>`${n} ${n===1?one:many}`;

export function warnings(r:CatalogRequirement,it:Item|undefined,changed=false):string[]{
 const s=it?.status||'TO_ASSESS',w:string[]=[];
 if(changed)w.push('Il testo normativo è stato aggiornato dopo questa valutazione: rileggilo e risalva l\'esito per confermarlo.');
 if(s==='NOT_APPLICABLE'&&r.always)w.push('Requisito applicabile in ogni caso (All. III, parte B, punto 2): motivare con cura.');
 if(['NOT_APPLICABLE','NON_COMPLIANT','PARTIAL'].includes(s)&&!it?.note?.trim())w.push('Rilievo/motivazione obbligatorio per chiudere.');
 if(['NON_COMPLIANT','PARTIAL'].includes(s)&&!it?.action?.trim())w.push('Azione correttiva obbligatoria per chiudere.');
 if(s==='COMPLIANT'&&!it?.evidence?.trim()&&!it?.evidenceRefs?.length)w.push('Indica le evidenze o collega un documento.');
 return w;
}

// ------------------------------------------------------------------ stato condiviso
export type ChecklistState=ReturnType<typeof useChecklists>;
export function useChecklists(api:Api,mid:string,token:string,canEdit:boolean,onError:(m:string)=>void,download:(path:string,name:string)=>Promise<void>){
 const [catalog,setCatalog]=useState<Catalog|null>(null);
 const [summaries,setSummaries]=useState<Summary[]>([]);
 const [selected,setSelected]=useState('');
 const [head,setHead]=useState<ChecklistHead|null>(null);
 const [items,setItems]=useState<Record<string,Item>>({});
 const [changed,setChanged]=useState<string[]>([]);
 const [docs,setDocs]=useState<Doc[]>([]);
 const [saving,setSaving]=useState(0);
 const [normDocs,setNormDocs]=useState<{id:string;law:string}[]>([]);
 const timers=useRef<Record<string,number>>({});
 const pending=useRef<Record<string,Item>>({});
 const fail=(e:unknown)=>onError(e instanceof Error?e.message:String(e));
 const editable=canEdit&&head?.status==='DRAFT';

 useEffect(()=>{if(!token)return;api<Catalog>('/requirement-catalog').then(setCatalog).catch(fail);api<{id:string;law:string}[]>('/normative-documents').then(setNormDocs).catch(()=>{})},[token]);
 const loadList=useCallback(async(keep?:string)=>{if(!mid||!token){setSummaries([]);return}const l=await api<Summary[]>(`/machines/${mid}/checklists`);setSummaries(l);setSelected(s=>keep||(l.some(x=>x.checklist.id===s)?s:l[0]?.checklist.id||''))},[mid,token]);
 const loadDocs=useCallback(async()=>{if(!mid||!token){setDocs([]);return}const k=await api<{evidence:Doc[]}>(`/machines/${mid}/knowledge`);setDocs(k.evidence)},[mid,token]);
 useEffect(()=>{setSelected('');setHead(null);setItems({});setChanged([]);loadList().catch(fail);loadDocs().catch(fail)},[mid,token]);
 useEffect(()=>{if(selected)loadDocs().catch(()=>{})},[selected]);
 useEffect(()=>{if(!selected){setHead(null);setItems({});setChanged([]);return}api<{checklist:ChecklistHead;items:Record<string,Item>;textChanged:string[]}>(`/checklists/${selected}`).then(d=>{setHead(d.checklist);setItems(d.items);setChanged(d.textChanged||[])}).catch(fail)},[selected,token]);

 async function flush(id:string){
  const body=pending.current[id];delete pending.current[id];if(!body||!head)return;
  setSaving(n=>n+1);
  try{const saved=await api<Item>(`/checklists/${head.id}/items/${encodeURIComponent(id)}`,'PUT',body);
   setItems(prev=>({...prev,[id]:{...prev[id],evidenceRefs:saved.evidenceRefs,assessedBy:saved.assessedBy,textSha256:saved.textSha256}}));
   setChanged(c=>c.filter(x=>x!==id))}
  catch(e){fail(e)}finally{setSaving(n=>n-1)}
 }
 function setItem(id:string,patch:Partial<Item>,immediate=false){
  if(!editable)return;
  setItems(prev=>{const next={...prev[id],...patch};pending.current[id]=next;return {...prev,[id]:next}});
  clearTimeout(timers.current[id]);
  timers.current[id]=window.setTimeout(()=>void flush(id),immediate?0:700);
 }
 const headTimer=useRef<number>(0);
 function updateHead(patch:Partial<ChecklistHead>,immediate=false){
  if(!editable||!head)return;
  const next={...head,...patch};setHead(next);clearTimeout(headTimer.current);
  headTimer.current=window.setTimeout(async()=>{setSaving(n=>n+1);try{await api(`/checklists/${next.id}`,'PUT',{title:next.title,profile:next.profile,details:next.details,conclusions:next.conclusions});await loadList(next.id)}catch(e){fail(e)}finally{setSaving(n=>n-1)}},immediate?0:800);
 }
 async function flushAll(){Object.keys(pending.current).forEach(k=>clearTimeout(timers.current[k]));await Promise.all(Object.keys(pending.current).map(flush))}
 async function create(basedOn?:string){const c=await api<ChecklistHead>(`/machines/${mid}/checklists`,'POST',basedOn?{basedOn}:{});await loadList(c.id);setSelected(c.id);return c}
 async function close(password:string,note:string){if(!head)return;await flushAll();const c=await api<ChecklistHead>(`/checklists/${head.id}/close`,'POST',{password,note});setHead(c);await loadList(c.id)}
 async function suggest(id:string){if(!head)return;await flushAll();const s=await api<Suggestion>(`/checklists/${head.id}/items/${encodeURIComponent(id)}/suggest`,'POST');setItems(prev=>({...prev,[id]:{...prev[id],suggestion:s}}));return s}
 async function uploadAndLink(id:string,file:File,locator:string){
  const data=new FormData();data.append('file',file);
  const e=await api<Doc>(`/machines/${mid}/documents`,'POST',data);
  await loadDocs();
  setItem(id,{evidenceRefs:[...(items[id]?.evidenceRefs||[]),{evidenceId:e.id,name:e.name,sha256:e.sha256,locator}]},true);
 }
 async function openPdf(law:string,page:number){
  const doc=normDocs.find(d=>d.law===law);if(!doc)return;
  const w=window.open('','_blank');
  try{const r=await fetch(`/api/normative-documents/${doc.id}/pdf`,{headers:{Authorization:'Bearer '+token}});if(!r.ok)throw new Error('PDF non disponibile');const url=URL.createObjectURL(new Blob([await r.blob()],{type:'application/pdf'}));if(w)w.location.href=url+'#page='+page;else window.location.href=url+'#page='+page}catch(e){w?.close();fail(e)}
 }
 const openDoc=(ref:EvidenceRef)=>download(`/documents/${ref.evidenceId}`,ref.name||'documento');
 return {catalog,summaries,selected,setSelected,head,items,changed,docs,setItem,updateHead,create,close,suggest,uploadAndLink,saving,openPdf,openDoc,editable,canEdit,download,reloadDocs:loadDocs,reload:()=>loadList(selected)};
}

// ------------------------------------------------------------------ componenti comuni
function Legal({text,q}:{text:string;q?:string}){
 let prev='',prevColon=false;
 const words=(q||'').trim().split(/\s+/).filter(w=>w.length>2);
 const mark=(p:string)=>{if(!words.length)return p;const rx=new RegExp('('+words.map(w=>w.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')).join('|')+')','gi');return p.split(rx).map((x,i)=>i%2?<mark key={i}>{x}</mark>:x)};
 return <div className="rq-legal">{text.split('\n').map((p,i)=>{
  const m=p.match(/^([a-z]{1,4})\)\s/);let cls='';
  if(m){const roman=/^(ii|iii|iv|vi|vii|viii|ix|x)$/.test(m[1])||((m[1]==='i'||m[1]==='v')&&(prev==='li2'||(prev==='li'&&prevColon)));cls=roman?'li2':'li'}
  else if(/^(\d+\)|—|-\s)/.test(p))cls='li';
  prev=cls;prevColon=p.trim().endsWith(':');
  return <p key={i} className={cls}>{mark(p)}</p>})}</div>;
}
export function StatusPill({s}:{s:Status}){return <span className={'rq-pill '+s}>{STATUS_SHORT[s]}</span>}
function StatusButtons({value,disabled,onChange}:{value:Status;disabled:boolean;onChange:(s:Status)=>void}){
 return <div className="rq-status" role="group" aria-label="Esito">{STATUSES.map(s=><button key={s} type="button" disabled={disabled} className={s+(value===s?' on':'')} aria-pressed={value===s} onClick={()=>onChange(s)}>{STATUS_SHORT[s]}</button>)}</div>;
}
function Standards({r,onUse}:{r:CatalogRequirement;onUse?:(code:string)=>void}){
 if(!r.standards?.length)return null;
 return <div className="rq-standards"><span className="rq-label">Norme di riferimento suggerite</span>
  <ul>{r.standards.map(s=><li key={s.code}><code>{s.code}</code><span>{s.title}</span><small>Tipo {s.type} · {s.status}</small>{onUse&&<button type="button" className="text-button" onClick={()=>onUse(s.code)}>Inserisci</button>}</li>)}</ul></div>;
}

function EvidenceLinks({r,st}:{r:CatalogRequirement;st:ChecklistState}){
 const it=st.items[r.id],refs=it?.evidenceRefs||[];
 const [doc,setDoc]=useState(''),[loc,setLoc]=useState(''),[busy,setBusy]=useState(false);
 const fileRef=useRef<HTMLInputElement>(null);
 const linked=(id:string)=>refs.some(x=>x.evidenceId===id);
 return <div className="rq-evidence"><span className="rq-label"><Paperclip size={12}/> Documenti collegati</span>
  {refs.length?<ul>{refs.map((x,i)=><li key={x.evidenceId+i}><button type="button" className="text-button" onClick={()=>st.openDoc(x)}><FileText size={13}/>{x.name||'documento'}</button>{x.locator&&<span>· {x.locator}</span>}<small>SHA-256 {x.sha256?.slice(0,12)}…</small>
   {st.editable&&<button type="button" className="icon-only" aria-label="Scollega documento" onClick={()=>st.setItem(r.id,{evidenceRefs:refs.filter((_,j)=>j!==i)},true)}><Trash2 size={13}/></button>}</li>)}</ul>:<p className="rq-sub">Nessun documento collegato.</p>}
  {st.editable&&<div className="rq-link-form">
   <select aria-label="Documento della macchina" value={doc} onChange={e=>setDoc(e.target.value)}><option value="">Documento già caricato…</option>{st.docs.filter(d=>!linked(d.id)).map(d=><option key={d.id} value={d.id}>{d.name}</option>)}</select>
   <input aria-label="Pagina o sezione" placeholder="Pagina / sezione" value={loc} onChange={e=>setLoc(e.target.value)}/>
   <button type="button" className="secondary small" disabled={!doc} onClick={()=>{const d=st.docs.find(x=>x.id===doc)!;st.setItem(r.id,{evidenceRefs:[...refs,{evidenceId:d.id,name:d.name,sha256:d.sha256,locator:loc}]},true);setDoc('');setLoc('')}}><Link2 size={13}/> Collega</button>
   <button type="button" className="secondary small" disabled={busy} onClick={()=>fileRef.current?.click()}><Upload size={13}/> {busy?'Caricamento…':'Carica e collega'}</button>
   <input ref={fileRef} type="file" hidden accept=".pdf,.docx,.txt,.md,.json,.png,.jpg,.jpeg" onChange={async e=>{const f=e.target.files?.[0];e.target.value='';if(!f)return;setBusy(true);try{await st.uploadAndLink(r.id,f,loc);setLoc('')}finally{setBusy(false)}}}/>
  </div>}
 </div>;
}

function AiSuggestion({r,st}:{r:CatalogRequirement;st:ChecklistState}){
 const s=st.items[r.id]?.suggestion;const [busy,setBusy]=useState(false);
 const proposal=s?.proposal&&s.proposal!=='INSUFFICIENT'?s.proposal as Status:null;
 return <div className="rq-ai"><div className="rq-ai-head"><span className="rq-label"><Sparkles size={12}/> Suggerimento AI (modello locale, solo indicativo)</span>
  {st.editable&&<button type="button" className="secondary small" disabled={busy} onClick={async()=>{setBusy(true);try{await st.suggest(r.id)}finally{setBusy(false)}}}>{busy?'Analisi dei documenti…':s?'Rigenera':'Chiedi suggerimento'}</button>}</div>
  {s&&<div className="rq-ai-body">
   {s.status==='UNAVAILABLE'&&<p>Modello AI non disponibile in questo momento{s.error?` (${s.error.slice(0,120)})`:''}. Nessun esito proposto.</p>}
   {s.status==='NO_EVIDENCE'&&<p>Nessun documento elaborato o fatto disponibile per questa macchina: carica i documenti tecnici nella Knowledge base.</p>}
   {s.status==='OK'&&<><p><strong>Proposta: {PROPOSAL[s.proposal||'INSUFFICIENT']}</strong> <small>{s.model} · {fmtTime(s.createdAt)}</small></p>{s.rationale&&<p>{s.rationale}</p>}
    {!!s.citations.length&&<ul>{s.citations.map((c,i)=><li key={i}><blockquote>«{c.quote}»</blockquote><small>{c.document}</small></li>)}</ul>}
    {!!s.missing.length&&<p className="rq-sub">Servirebbero: {s.missing.join('; ')}</p>}
    {proposal&&st.editable&&<button type="button" className="text-button" onClick={()=>st.setItem(r.id,{status:proposal},true)}>Applica l'esito proposto (decisione tua, registrata a tuo nome)</button>}</>}
  </div>}
 </div>;
}

function ItemEditor({r,st,showStatus=true}:{r:CatalogRequirement;st:ChecklistState;showStatus?:boolean}){
 const it=st.items[r.id],locked=!st.editable,changed=st.changed.includes(r.id),w=warnings(r,it,changed);
 const useStandard=(code:string)=>{const cur=(it?.standard||'').trim();if(cur.includes(code))return;st.setItem(r.id,{standard:cur?cur+'; '+code:code})};
 return <div className="rq-editor">
  {showStatus&&<StatusButtons value={statusOf(st.items,r.id)} disabled={locked} onChange={s=>st.setItem(r.id,{status:s},true)}/>}
  {w.length>0&&(!locked||changed)&&<div className="rq-warn">{w.join(' ')}</div>}
  <label className="rq-f"><span>Evidenze (prove, verifiche, documenti esaminati)</span><textarea disabled={locked} value={it?.evidence||''} onChange={e=>st.setItem(r.id,{evidence:e.target.value})}/></label>
  <EvidenceLinks r={r} st={st}/>
  <label className="rq-f"><span>Norme / metodi applicati</span><input disabled={locked} value={it?.standard||''} onChange={e=>st.setItem(r.id,{standard:e.target.value})} placeholder="es. EN ISO 13849-1:2023"/></label>
  <Standards r={r} onUse={locked?undefined:useStandard}/>
  <div className="rq-fields">{FIELDS.map(([k,label,type])=><label key={k} className={type==='area'?'wide':''}><span>{label}</span>
   {type==='area'?<textarea disabled={locked} value={(it?.[k] as string)||''} onChange={e=>st.setItem(r.id,{[k]:e.target.value})}/>
   :<input type={type} disabled={locked} value={(it?.[k] as string)||''} onChange={e=>st.setItem(r.id,{[k]:e.target.value},type==='date')}/>}</label>)}</div>
  <AiSuggestion r={r} st={st}/>
  {it?.assessedBy&&<small className="rq-sub">Esito registrato da {it.assessedBy}{it.updatedAt?' · ultimo salvataggio '+fmtTime(it.updatedAt):''}</small>}
 </div>;
}
function shortRef(r:CatalogRequirement){return r.ref.replace(/^All\. III, punto /,'').replace(/^All\. III, /,'').replace(/, lettera /,' ').replace(/, comma /,' c.').replace(/, elenco (\d+), lettera /,' $1')}
function pagesLabel(r:CatalogRequirement){return r.pages?.length?(r.pages[0]===r.pages[1]?`p. ${r.pages[0]}`:`pp. ${r.pages[0]}–${r.pages[1]}`):''}

function ChecklistPicker({st,mid}:{st:ChecklistState;mid:string}){
 const [busy,setBusy]=useState(false);
 if(!mid)return <div className="notice">Seleziona o crea una macchina per avviare una checklist di valutazione.</div>;
 return <div className="rq-picker">
  <FileText size={17}/>
  <select aria-label="Checklist" value={st.selected} onChange={e=>st.setSelected(e.target.value)}>
   {!st.summaries.length&&<option value="">Nessuna checklist per questa macchina</option>}
   {st.summaries.map(s=><option key={s.checklist.id} value={s.checklist.id}>{s.checklist.title} · {s.checklist.status==='CLOSED'?'firmata':'bozza'} · {s.total-s.counts.TO_ASSESS}/{s.total} valutati</option>)}
  </select>
  {st.head&&<span className={'badge '+(st.head.status==='CLOSED'?'ready':'')}>{st.head.status==='CLOSED'?<><Lock size={11}/> Firmata il {fmtDate(st.head.closedAt)}</>:st.canEdit?'Bozza modificabile':'Sola lettura'}</span>}
  <span className="rq-saving">{st.saving?'Salvataggio…':st.editable?'Salvato':''}</span>
  {st.canEdit&&<button className="secondary small" disabled={busy} onClick={async()=>{setBusy(true);try{await st.create()}finally{setBusy(false)}}}><Plus size={14}/> Nuova checklist</button>}
  {st.canEdit&&st.head&&<button className="secondary small" disabled={busy} onClick={async()=>{setBusy(true);try{await st.create(st.head!.id)}finally{setBusy(false)}}}><Copy size={14}/> Nuova revisione</button>}
 </div>;
}

// ------------------------------------------------------------------ wiki dei requisiti
export function RequirementWiki({st,mid,machineName,onChangeMachine}:{st:ChecklistState;mid:string;machineName:string;onChangeMachine:()=>void}){
 const cat=st.catalog;
 const [sel,setSel]=useState(''),[q,setQ]=useState(''),[query,setQuery]=useState('');
 useEffect(()=>{const t=setTimeout(()=>setQuery(q),220);return()=>clearTimeout(t)},[q]);
 const ordered=useMemo(()=>cat?.requirements||[],[cat]);
 const hits=useMemo(()=>{
  if(!cat||query.trim().length<2)return null;
  const words=norm(query).split(/\s+/).filter(Boolean);
  return ordered.filter(r=>{const h=norm(r.ref+' '+r.title+' '+r.text+' '+(r.standards||[]).map(s=>s.code+' '+s.title).join(' '));return words.every(w=>h.includes(w))})
   .sort((a,b)=>(+norm(b.title).includes(words[0])-+norm(a.title).includes(words[0]))||(+b.assessable-+a.assessable)).slice(0,150);
 },[query,ordered]);
 if(!cat)return <section className="card"><p>Caricamento del catalogo dei requisiti…</p></section>;
 const r=ordered.find(x=>x.id===sel);
 const law=r&&cat.laws.find(l=>l.id===r.law);
 const siblings=r?ordered.filter(x=>x.group===r.group&&x.section===r.section):[];
 const idx=r?siblings.findIndex(x=>x.id===r.id):-1;
 const tagLabel=(t:string)=>cat.profileTags.find(x=>x.id===t)?.label||t;
 const go=(id:string)=>{setSel(id);setQ('');setQuery('');window.scrollTo({top:0})};
 const ch=cat.changes;
 return <div className="rq-wiki">
  <div className="card rq-tree" role="navigation" aria-label="Indice dei requisiti">
   <div className="search"><Search size={15}/><input aria-label="Cerca nei requisiti" placeholder="Cerca (es. arresto di emergenza, SBOM, 13849)…" value={q} onChange={e=>{setQ(e.target.value);setSel('')}}/></div>
   <div className="rq-tree-body">{cat.laws.map(l=><details key={l.id} open><summary className="rq-law">{l.short} {l.code}</summary>
    {l.sections.map(s=>{const rs=ordered.filter(x=>x.law===l.id&&x.section===s.id);const groups=[...new Set(rs.map(x=>x.group))];
     return <details key={s.id} className="rq-sec" open={!!r&&r.section===s.id}><summary>{s.title}</summary>
      {groups.map(g=><details key={g} className="rq-grp" open={!!r&&r.group===g&&r.section===s.id}><summary>{g.replace(/^Allegato III — /,'')}</summary>
       {rs.filter(x=>x.group===g).map(x=><button key={x.id} className={'rq-item'+(x.id===sel?' active':'')} onClick={()=>go(x.id)}>
        {st.head&&x.assessable&&<span className={'rq-dot '+statusOf(st.items,x.id)}/>}<span><small>{shortRef(x)}</small> {x.title}</span></button>)}
      </details>)}</details>})}
   </details>)}</div>
  </div>
  <section className="card rq-doc">
   {hits?<><h2>Risultati per «{query}» <small>{hits.length}{hits.length===150?'+':''} voci</small></h2>
     {hits.map(h=><button key={h.id} className="rq-hit" onClick={()=>go(h.id)}><span className="eyebrow">{cat.laws.find(l=>l.id===h.law)?.short} · {h.ref}</span><strong>{h.title}</strong><small>{h.text.slice(0,220).replace(/\n/g,' ')}…</small></button>)}
     {!hits.length&&<p>Nessun risultato.</p>}</>
   :r&&law?<>
     <span className="eyebrow">{law.short.toUpperCase()} {law.code} · {r.group.toUpperCase()}</span>
     <h2 className="rq-title">{r.title}</h2>
     <div className="rq-meta"><code>{r.ref}</code><span className="badge">{r.assessable?'Requisito valutabile':'Testo di riferimento'}</span>{r.always&&<span className="badge rq-always">Si applica in ogni caso</span>}{r.tags.map(t=><span key={t} className="badge">{tagLabel(t)}</span>)}
      {ch?.changed?.includes(r.id)&&<span className="badge rq-changed">Testo aggiornato nell'ultima versione del catalogo</span>}
      {!!r.pages?.length&&<button className="text-button" onClick={()=>st.openPdf(r.law,r.pages[0])}>PDF ufficiale, {pagesLabel(r)} <ExternalLink size={12}/></button>}</div>
     <Legal text={r.text}/>
     {!!r.standards?.length&&<div className="rq-std-box"><Standards r={r}/>{cat.standardsNotice?.text&&<p className="rq-disclaimer">{cat.standardsNotice.text}</p>}</div>}
     {r.assessable&&<div className="rq-assess">
      <div className="rq-assess-head"><span className="eyebrow">VALUTAZIONE{machineName?' — '+machineName.toUpperCase():''}</span><button className="text-button" onClick={onChangeMachine}>Cambia macchina in Assessment</button></div>
      {!mid?<p>Nessuna macchina selezionata: scegline una in Assessment per registrare l'esito.</p>:!st.head?<><p>Nessuna checklist attiva per questa macchina.</p>{st.canEdit&&<button className="primary" onClick={()=>void st.create()}><Plus size={15}/> Crea checklist</button>}</>
      :<>{!inScope(r,st.head.profile)&&<div className="rq-warn">Requisito fuori dal profilo della checklist: un esito lo include comunque nel rapporto.</div>}<p className="rq-sub">{st.head.title}{st.head.status==='CLOSED'?' · checklist firmata (sola lettura)':!st.canEdit?' · sola lettura':''}</p><ItemEditor r={r} st={st}/></>}
     </div>}
     <div className="rq-nav">{idx>0&&<button className="text-button" onClick={()=>go(siblings[idx-1].id)}>← {shortRef(siblings[idx-1])} {siblings[idx-1].title}</button>}{idx>=0&&idx<siblings.length-1&&<button className="text-button" onClick={()=>go(siblings[idx+1].id)}>{shortRef(siblings[idx+1])} {siblings[idx+1].title} →</button>}</div>
    </>
   :<div className="rq-home"><span className="eyebrow">WIKI NORMATIVA PER REQUISITI</span><h2 className="rq-title">Regolamento Macchine e Cyber Resilience Act</h2>
     <p>I testi integrali sono scomposti in voci consultabili. I <strong>requisiti valutabili</strong> si valutano per la macchina selezionata, qui o nella checklist dell'Assessment. Per molti requisiti sono indicate le norme tecniche di riferimento.</p>
     {ch?.previous&&(!!ch.changed?.length||!!ch.added?.length||!!ch.removed?.length)&&<div className="notice"><AlertTriangle size={16}/>Ultimo aggiornamento del catalogo: {plural(ch.changed?.length||0,'requisito con testo modificato','requisiti con testo modificato')}, {plural(ch.added?.length||0,'aggiunto','aggiunti')}, {plural(ch.removed?.length||0,'rimosso','rimossi')}. Le valutazioni sui testi modificati vanno riconfermate.</div>}
     <div className="document-grid">{cat.laws.map(l=>{const rs=ordered.filter(x=>x.law===l.id);return <article key={l.id} className="document"><BookOpen size={20}/><h3>{l.short}</h3><small>Regolamento {l.code} · GU {fmtDate(l.publicationDate)}</small><small>{rs.filter(x=>x.assessable).length} requisiti valutabili · {rs.length} voci · {rs.filter(x=>x.standards?.length).length} con norme di riferimento</small><small>{l.application}</small></article>})}</div>
     <p className="rq-disclaimer">{cat.disclaimer}</p></div>}
  </section>
 </div>;
}

// ------------------------------------------------------------------ checklist (assessment)
export function ChecklistPanel({st,mid,userName,onReport}:{st:ChecklistState;mid:string;userName:string;onReport:()=>void}){
 const cat=st.catalog,head=st.head;
 const [law,setLaw]=useState('ALL'),[status,setStatus]=useState('ALL'),[text,setText]=useState(''),[excluded,setExcluded]=useState(false);
 const [open,setOpen]=useState<Set<string>>(new Set()),[groupsOpen,setGroupsOpen]=useState<Set<string>>(new Set());
 const [closing,setClosing]=useState(false),[closeErr,setCloseErr]=useState(''),[busy,setBusy]=useState(false);
 const all=useMemo(()=>cat&&head?cat.requirements.filter(r=>r.assessable&&(inScope(r,head.profile)||!!st.items[r.id]?.status)):[],[cat,head?.profile,st.items]);
 if(!cat)return <section className="card"><p>Caricamento del catalogo…</p></section>;
 const counts=Object.fromEntries(STATUSES.map(s=>[s,all.filter(r=>statusOf(st.items,r.id)===s).length])) as Record<Status,number>;
 const words=norm(text).split(/\s+/).filter(Boolean);
 const visible=(head?cat.requirements:[]).filter(r=>{
  if(!r.assessable||!head)return false;
  const sc=inScope(r,head.profile)||!!st.items[r.id]?.status;
  if(!sc&&!excluded)return false;
  if(law!=='ALL'&&r.law!==law)return false;
  if(status==='WARN'){if(!warnings(r,st.items[r.id],st.changed.includes(r.id)).length||statusOf(st.items,r.id)==='TO_ASSESS')return false}
  else if(status==='CHANGED'){if(!st.changed.includes(r.id))return false}
  else if(status!=='ALL'&&statusOf(st.items,r.id)!==status)return false;
  if(words.length){const it=st.items[r.id];const h=norm(r.ref+' '+r.title+' '+r.text+' '+(it?.evidence||'')+' '+(it?.note||'')+' '+(it?.evidenceRefs||[]).map(x=>x.name).join(' '));if(!words.every(w=>h.includes(w)))return false}
  return true;
 });
 const groups=[...new Set(visible.map(r=>r.law+'|'+r.group))];
 const filtering=!!text||status!=='ALL';
 const lawName=(id:string)=>cat.laws.find(l=>l.id===id)?.short||id;
 const toggle=(set:Set<string>,k:string)=>{const n=new Set(set);n.has(k)?n.delete(k):n.add(k);return n};
 const d=head?.details,locked=!st.editable;
 const field=(k:keyof Details,label:string,type='text')=><label><span>{label}</span><input type={type} disabled={locked} value={d?.[k]||''} onChange={e=>st.updateHead({details:{...d!,[k]:e.target.value}})}/></label>;
 return <>
  <ChecklistPicker st={st} mid={mid}/>
  {!head?(mid&&<section className="card empty"><h3>Nessuna checklist per questa macchina</h3><p>Crea una checklist: il profilo iniziale deriva dall'ambito della macchina (Regolamento Macchine / CRA).</p>{st.canEdit&&<button className="primary" onClick={()=>void st.create()}><Plus size={15}/> Crea checklist</button>}</section>)
  :<>
   {!!st.changed.length&&<div className="notice"><AlertTriangle size={16}/>{plural(st.changed.length,'requisito è stato valutato','requisiti sono stati valutati')} su un testo normativo poi aggiornato: rivedili (filtro «Testo aggiornato») e risalva l'esito.</div>}
   <details className="card rq-profile" open={!Object.keys(st.items).length}>
    <summary><strong>Dati della macchina e profilo di valutazione</strong> <span>{head.title} · Rev. {head.revision||1}{d?.model?' · '+d.model:''}{d?.serial?' · matr. '+d.serial:''}</span></summary>
    <div className="rq-grid">
     <label><span>Titolo della checklist</span><input disabled={locked} value={head.title} onChange={e=>st.updateHead({title:e.target.value})}/></label>
     {field('model','Modello / tipo')}{field('serial','N. di serie / matricola')}{field('year','Anno di costruzione')}{field('manufacturer','Fabbricante')}{field('client','Committente / utilizzatore')}
     {field('assessor','Valutatore')}{field('organization','Organizzazione del valutatore')}{field('assessmentDate','Data della valutazione','date')}
    </div>
    <div className="rq-grid">{([['description','Descrizione della macchina'],['intendedUse','Uso previsto e limiti'],['location','Luogo / note della valutazione']] as [keyof Details,string][]).map(([k,l])=><label key={k}><span>{l}</span><textarea disabled={locked} value={d?.[k]||''} onChange={e=>st.updateHead({details:{...d!,[k]:e.target.value}})}/></label>)}</div>
    <div className="rq-checks">{(['tipo','mr','cra'] as const).map(g=><div key={g}><h4>{{tipo:'Tipo di prodotto (Regolamento Macchine)',mr:"Capi dell'Allegato III applicabili",cra:'Cyber Resilience Act'}[g]}</h4>
     {cat.profileTags.filter(t=>t.group===g).map(t=><label key={t.id} className="checkbox"><input type="checkbox" disabled={locked} checked={head.profile.includes(t.id)} onChange={e=>{const p=new Set(head.profile);e.target.checked?p.add(t.id):p.delete(t.id);if(p.size)st.updateHead({profile:cat.profileTags.map(x=>x.id).filter(x=>p.has(x))},true)}}/>{t.label}</label>)}</div>)}</div>
    {head.createdBy&&<small className="rq-sub">Creata da {head.createdBy} il {fmtTime(head.createdAt)}</small>}
   </details>
   <div className="card rq-toolbar">
    <div className="search"><Search size={15}/><input aria-label="Filtra requisiti" placeholder="Filtra requisiti…" value={text} onChange={e=>setText(e.target.value)}/></div>
    <select aria-label="Regolamento" value={law} onChange={e=>setLaw(e.target.value)}><option value="ALL">Tutti i regolamenti</option>{cat.laws.map(l=><option key={l.id} value={l.id}>{l.short}</option>)}</select>
    <select aria-label="Esito" value={status} onChange={e=>setStatus(e.target.value)}><option value="ALL">Tutti gli esiti</option>{STATUSES.map(s=><option key={s} value={s}>{STATUS_LABEL[s]}</option>)}<option value="WARN">Con avvisi</option><option value="CHANGED">Testo aggiornato</option></select>
    <label className="checkbox"><input type="checkbox" checked={excluded} onChange={e=>setExcluded(e.target.checked)}/> esclusi dal profilo</label>
    <div className="rq-progress"><span>{all.length-counts.TO_ASSESS}/{all.length} valutati · <b className="c">{counts.COMPLIANT} C</b> · <b className="p">{counts.PARTIAL} P</b> · <b className="nc">{counts.NON_COMPLIANT} NC</b> · {counts.NOT_APPLICABLE} N/A</span>
     <div className="rq-bar">{(['COMPLIANT','PARTIAL','NON_COMPLIANT','NOT_APPLICABLE'] as Status[]).map(s=><i key={s} className={s} style={{width:(all.length?counts[s]/all.length*100:0)+'%'}}/>)}</div></div>
    <button className="secondary small" onClick={()=>setGroupsOpen(new Set(groups))}>Espandi</button>
    <button className="secondary small" onClick={()=>{setGroupsOpen(new Set());setOpen(new Set())}}>Comprimi</button>
   </div>
   {groups.map(g=>{const [l,name]=g.split('|');const rs=visible.filter(r=>r.law+'|'+r.group===g);const isOpen=filtering||groupsOpen.has(g);
    const c=Object.fromEntries(STATUSES.map(s=>[s,rs.filter(r=>statusOf(st.items,r.id)===s).length])) as Record<Status,number>;
    return <section key={g} className="card rq-group">
     <button className="rq-group-head" onClick={()=>setGroupsOpen(toggle(groupsOpen,g))}>{isOpen?<ChevronDown size={16}/>:<ChevronRight size={16}/>}<strong>{lawName(l)} — {name}</strong>
      <small>{rs.length-c.TO_ASSESS}/{rs.length} valutati{c.NON_COMPLIANT?<b className="nc"> · {c.NON_COMPLIANT} NC</b>:null}{c.PARTIAL?<b className="p"> · {c.PARTIAL} P</b>:null}</small></button>
     {isOpen&&rs.map(r=>{const o=open.has(r.id);const it=st.items[r.id];const changed=st.changed.includes(r.id);return <div key={r.id} className={'rq-row'+(inScope(r,head.profile)?'':' out')}>
      <div className="rq-row-head"><code>{r.ref}</code>
       <button className="rq-row-title" onClick={()=>setOpen(toggle(open,r.id))}>{o?<ChevronDown size={14}/>:<ChevronRight size={14}/>} {r.title}{r.always&&<span className="badge rq-always">sempre</span>}{changed&&<span className="badge rq-changed">testo aggiornato</span>}{!!it?.evidenceRefs?.length&&<span className="badge"><Paperclip size={10}/> {it.evidenceRefs.length}</span>}{!o&&it?.note&&<small>{it.note.slice(0,150)}</small>}</button>
       <StatusButtons value={statusOf(st.items,r.id)} disabled={locked} onChange={s=>{st.setItem(r.id,{status:s},true);if(['PARTIAL','NON_COMPLIANT','NOT_APPLICABLE'].includes(s))setOpen(n=>new Set(n).add(r.id))}}/></div>
      {o&&<div className="rq-row-body"><div><Legal text={r.text} q={text}/>{!!r.pages?.length&&<button className="text-button" onClick={()=>st.openPdf(r.law,r.pages[0])}>PDF ufficiale, {pagesLabel(r)} <ExternalLink size={12}/></button>}</div><ItemEditor r={r} st={st} showStatus={false}/></div>}
     </div>})}
    </section>})}
   {!groups.length&&<section className="card empty"><p>Nessun requisito corrisponde ai filtri o al profilo selezionato.</p></section>}
   <section className="card rq-closing">
    {head.status==='CLOSED'&&head.signature?<p><Lock size={14}/> Firmata da <strong>{head.signature.displayName}</strong> ({head.signature.username}) il {fmtTime(head.signature.signedAt)}{head.signature.note?' — '+head.signature.note:''}. Rapporto archiviato: {head.signature.reportPages} pagine, SHA-256 <code>{head.signature.reportSha256}</code>. Per modifiche crea una nuova revisione.</p>
    :!st.canEdit?<p>Accesso in sola lettura: la chiusura è riservata a valutatori e amministratori.</p>
    :<><div><h3>Firma e rapporto finale</h3><p>La firma richiede che tutti i requisiti del profilo siano valutati, con motivazione per N/A, parziali e non conformità e azione correttiva per parziali e non conformità. Firmi come <strong>{userName}</strong> confermando la tua password: il rapporto PDF viene generato, archiviato con impronta SHA-256 e la checklist diventa immutabile.</p></div>
     {closing?<form className="review-form" onSubmit={async e=>{e.preventDefault();const f=new FormData(e.currentTarget);setBusy(true);setCloseErr('');try{await st.close(String(f.get('password')),String(f.get('note')));setClosing(false)}catch(err){setCloseErr(err instanceof Error?err.message:String(err))}finally{setBusy(false)}}}>
      <input name="password" type="password" required autoComplete="current-password" placeholder="La tua password per firmare"/><input name="note" placeholder="Nota di chiusura (facoltativa)"/><button className="primary" disabled={busy}>{busy?'Firma in corso…':'Firma e chiudi'}</button><button type="button" className="secondary" onClick={()=>setClosing(false)}>Annulla</button></form>
     :<div className="actions"><button className="secondary" onClick={onReport}><FileText size={15}/> Anteprima rapporto</button><button className="primary" onClick={()=>setClosing(true)}><Lock size={15}/> Firma e chiudi</button></div>}
     {closeErr&&<div className="alert" role="alert">{closeErr}</div>}</>}
   </section>
  </>}
 </>;
}

// ------------------------------------------------------------------ rapporto finale
export function ReportView({st,api,mid}:{st:ChecklistState;api:Api;mid:string}){
 const [rep,setRep]=useState<Report|null>(null),[err,setErr]=useState('');
 const head=st.head;
 useEffect(()=>{setRep(null);if(!head)return;const t=setTimeout(()=>api<Report>(`/checklists/${head.id}/report`).then(setRep).catch(e=>setErr(String(e))),st.saving?900:0);return()=>clearTimeout(t)},[head?.id,head?.status,head?.updatedAt,st.saving===0]);
 if(!mid||!head)return <><ChecklistPicker st={st} mid={mid}/><section className="card empty"><h3>Nessuna checklist selezionata</h3><p>Il rapporto finale si genera da una checklist di valutazione (tab Assessment).</p></section></>;
 if(!rep)return <><ChecklistPicker st={st} mid={mid}/><section className="card"><p>{err||'Generazione del rapporto…'}</p></section></>;
 const c=rep.counts,d=rep.checklist.details,laws=rep.catalog.laws,lawName=(id:string)=>laws.find(l=>l.id===id)?.short||id;
 const draft=rep.checklist.status!=='CLOSED',sig=rep.checklist.signature;
 const verdictText={NON_COMPLIANT:`NON CONFORME — ${plural(c.NON_COMPLIANT,'requisito non soddisfatto','requisiti non soddisfatti')}${c.PARTIAL?`, ${plural(c.PARTIAL,'parzialmente conforme','parzialmente conformi')}`:''}${c.TO_ASSESS?`, ${c.TO_ASSESS} da valutare`:''}.`,
  INCOMPLETE:`VALUTAZIONE NON COMPLETATA — ${plural(c.TO_ASSESS,'requisito da valutare','requisiti da valutare')}${c.PARTIAL?`, ${plural(c.PARTIAL,'parzialmente conforme','parzialmente conformi')}`:''}; nessuna non conformità rilevata.`,
  PARTIAL:`CONFORMITÀ PARZIALE — ${plural(c.PARTIAL,'requisito parzialmente conforme','requisiti parzialmente conformi')}, nessuna non conformità.`,
  COMPLIANT:`CONFORME — tutti i ${rep.applicable} requisiti applicabili valutati risultano conformi.`}[rep.verdict];
 const byLaw=laws.map(l=>({l,rows:rep.rows.filter(r=>r.requirement.law===l.id)})).filter(x=>x.rows.length);
 const groups=[...new Set(rep.rows.map(r=>r.requirement.law+'|'+r.requirement.group))];
 const nc=rep.rows.filter(r=>r.status==='NON_COMPLIANT'||r.status==='PARTIAL').sort((a,b)=>(a.status==='NON_COMPLIANT'?0:1)-(b.status==='NON_COMPLIANT'?0:1));
 const cnt=(rows:Row[])=>Object.fromEntries(STATUSES.map(s=>[s,rows.filter(r=>r.status===s).length])) as Record<Status,number>;
 const tags=(ids:string[])=>ids.map(t=>rep.catalog.profileTags.find(x=>x.id===t)?.label||t).join('; ');
 const docs=(it:Item|null)=>it?.evidenceRefs?.length?<small>{it.evidenceRefs.map((x,i)=><span key={i} className="rq-docref">{x.name}{x.locator?', '+x.locator:''} (SHA-256 {x.sha256?.slice(0,12)}…)</span>)}</small>:null;
 const file=`rapporto-${rep.machine.name.replace(/[^a-z0-9]+/gi,'-').toLowerCase()}-rev${rep.checklist.revision||1}${draft?'-bozza':''}.pdf`;
 return <>
  <div className="rq-report-actions"><ChecklistPicker st={st} mid={mid}/>
   <div className="actions"><button className="primary" onClick={()=>st.download(`/checklists/${rep.checklist.id}/report.pdf`,file)}><Download size={15}/> {draft?'Scarica PDF (bozza)':'Scarica PDF firmato'}</button>
    <button className="secondary" onClick={()=>window.print()}><Printer size={15}/> Stampa</button>
    <button className="secondary" onClick={()=>st.download(`/checklists/${rep.checklist.id}/report.csv`,`checklist-${rep.checklist.id.slice(0,8)}.csv`)}><Download size={15}/> CSV (Excel)</button>
    <button className="secondary" onClick={()=>st.download(`/checklists/${rep.checklist.id}/report`,`rapporto-${rep.checklist.id.slice(0,8)}.json`)}><Download size={15}/> JSON</button></div></div>
  <article className="card rq-report">
   {draft&&<div className="rq-draft">BOZZA — non firmata</div>}
   <span className="eyebrow">RAPPORTO DI VALUTAZIONE DELLA CONFORMITÀ</span>
   <h1>{rep.machine.name}{d.model?' — '+d.model:''}</h1>
   <p className="rq-sub">{rep.checklist.title} · Revisione {rep.checklist.revision||1} · Regolamento (UE) 2023/1230{rep.checklist.profile.includes('cra')?' · Regolamento (UE) 2024/2847 (Cyber Resilience Act)':''}{rep.machine.synthetic?' · MACCHINA DI PROVA':''}</p>
   <h2>1. Dati identificativi</h2>
   <table className="rq-kv"><tbody>{([['Macchina',rep.machine.name],['Modello / tipo',d.model],['N. di serie / matricola',d.serial],['Anno di costruzione',d.year],['Fabbricante',d.manufacturer],['Committente / utilizzatore',d.client],['Descrizione',d.description],['Uso previsto e limiti',d.intendedUse],['Valutatore',[d.assessor,d.organization].filter(Boolean).join(' — ')],['Data della valutazione',fmtDate(d.assessmentDate)],['Luogo / note',d.location]] as [string,string][]).filter(([,v])=>v).map(([k,v])=><tr key={k}><td>{k}</td><td>{v}</td></tr>)}</tbody></table>
   <h2>2. Ambito della valutazione</h2>
   <p><strong>Profilo applicato:</strong> {tags(rep.checklist.profile)}.</p>
   <p><strong>Esclusi dal profilo:</strong> {tags(rep.catalog.profileTags.map(t=>t.id).filter(t=>!rep.checklist.profile.includes(t)))||'nessuno'}.</p>
   <p>Requisiti considerati: {rep.total}. Fonti: testi originali in Gazzetta ufficiale (non consolidati) — {rep.catalog.generatedFrom.map(g=>`${lawName(g.law)}: ${g.filename} (SHA-256 ${g.sha256.slice(0,12)}…)`).join('; ')}.</p>
   <h2>3. Sintesi dei risultati</h2>
   <div className={'rq-verdict '+rep.verdict}>Esito: {verdictText}</div>
   <div className="rq-stats">{STATUSES.map(s=><div key={s} className={s}><b>{c[s]}</b><span>{STATUS_LABEL[s]}</span></div>)}</div>
   <p>Indice di conformità (conformi / requisiti applicabili): <strong>{rep.complianceIndex}%</strong> ({c.COMPLIANT} su {rep.applicable}).</p>
   <table><thead><tr><th>Regolamento</th>{STATUSES.map(s=><th key={s}>{STATUS_SHORT[s]}</th>)}<th>Totale</th></tr></thead><tbody>{byLaw.map(({l,rows})=>{const k=cnt(rows);return <tr key={l.id}><td>{l.short} {l.code}</td>{STATUSES.map(s=><td key={s} className="num">{k[s]}</td>)}<td className="num"><b>{rows.length}</b></td></tr>})}</tbody></table>
   <h3>Dettaglio per sezione</h3>
   <table><thead><tr><th>Sezione</th>{STATUSES.map(s=><th key={s}>{STATUS_SHORT[s]}</th>)}<th>Tot.</th></tr></thead><tbody>{groups.map(g=>{const rows=rep.rows.filter(r=>r.requirement.law+'|'+r.requirement.group===g);const k=cnt(rows);const [l,n]=g.split('|');return <tr key={g}><td>{lawName(l)} — {n}</td>{STATUSES.map(s=><td key={s} className="num">{k[s]||''}</td>)}<td className="num">{rows.length}</td></tr>})}</tbody></table>
   <h2>4. Non conformità e azioni correttive</h2>
   {nc.length?<table><thead><tr><th>#</th><th>Riferimento</th><th>Requisito</th><th>Esito</th><th>Rilievo / evidenze</th><th>Azione correttiva</th><th>Resp. / scadenza</th></tr></thead><tbody>{nc.map((r,i)=><tr key={r.requirement.id}><td className="num">{i+1}</td><td><b>{lawName(r.requirement.law)}</b><br/>{r.requirement.ref}</td><td>{r.requirement.title}</td><td><StatusPill s={r.status}/></td><td>{r.item?.note||'—'}{r.item?.evidence&&<small>Evidenze: {r.item.evidence}</small>}{docs(r.item)}</td><td>{r.item?.action||'—'}</td><td>{r.item?.owner}{r.item?.due&&<small>{fmtDate(r.item.due)}</small>}</td></tr>)}</tbody></table>:<p>Nessuna non conformità o conformità parziale registrata.</p>}
   <h2>5. Requisiti non ancora valutati</h2>
   {c.TO_ASSESS?<table><thead><tr><th>Regolamento</th><th>Riferimento</th><th>Requisito</th></tr></thead><tbody>{rep.rows.filter(r=>r.status==='TO_ASSESS').map(r=><tr key={r.requirement.id}><td>{lawName(r.requirement.law)}</td><td>{r.requirement.ref}</td><td>{r.requirement.title}</td></tr>)}</tbody></table>:<p>Tutti i requisiti considerati sono stati valutati.</p>}
   <h2>6. Requisiti non applicabili</h2>
   {c.NOT_APPLICABLE?<table><thead><tr><th>Riferimento</th><th>Requisito</th><th>Motivazione</th></tr></thead><tbody>{rep.rows.filter(r=>r.status==='NOT_APPLICABLE').map(r=><tr key={r.requirement.id}><td>{lawName(r.requirement.law)} · {r.requirement.ref}</td><td>{r.requirement.title}{r.requirement.always&&<small>Si applica in ogni caso</small>}</td><td>{r.item?.note||'⚠ motivazione mancante'}</td></tr>)}</tbody></table>:<p>Nessun requisito dichiarato non applicabile oltre alle esclusioni di profilo.</p>}
   <h2>7. Conclusioni</h2>
   {draft&&st.editable?<textarea className="rq-concl no-print" placeholder="Conclusioni del valutatore, raccomandazioni, limiti della valutazione…" value={head.conclusions||''} onChange={e=>st.updateHead({conclusions:e.target.value})}/>:null}
   <p className={draft&&st.editable?'print-only rq-pre':'rq-pre'}>{(draft?head.conclusions:rep.checklist.conclusions)||'—'}</p>
   <h2>8. Firma</h2>
   {sig?<p>Rapporto firmato elettronicamente da <strong>{sig.displayName}</strong> (utente {sig.username}) il {fmtTime(sig.signedAt)}, previa conferma della password personale.{sig.note?' Nota: '+sig.note:''}<br/><small>PDF archiviato: {sig.reportPages} pagine · SHA-256 {sig.reportSha256}</small></p>:<p>Bozza non firmata: il rapporto definitivo si ottiene con «Firma e chiudi» nella checklist.</p>}
   <h2>Allegato — Checklist completa</h2>
   <table className="rq-full"><thead><tr><th>Reg.</th><th>Riferimento</th><th>Requisito</th><th>Esito</th><th>Evidenze / documenti / norme</th><th>Rilievo</th></tr></thead><tbody>{rep.rows.map(r=><tr key={r.requirement.id}><td>{r.requirement.law}</td><td>{r.requirement.ref}</td><td>{r.requirement.title}{r.textChanged&&<small className="rq-warn-inline">testo aggiornato dopo la valutazione</small>}</td><td><StatusPill s={r.status}/></td><td>{r.item?.evidence}{docs(r.item)}{r.item?.standard&&<small>{r.item.standard}</small>}</td><td>{r.item?.note}{r.item?.assessedBy&&<small>Valutato da {r.item.assessedBy}</small>}</td></tr>)}</tbody></table>
   <p className="rq-disclaimer">{rep.catalog.disclaimer} Il rapporto documenta l'esito della verifica dei requisiti secondo il profilo indicato; non sostituisce la procedura di valutazione della conformità (art. 25 Reg. (UE) 2023/1230, art. 32 Reg. (UE) 2024/2847) né l'eventuale intervento di un organismo notificato. Checklist {rep.checklist.id} · catalogo {rep.catalog.sha256.slice(0,12)} · generato il {fmtTime(rep.generatedAt)}.</p>
  </article>
 </>;
}
