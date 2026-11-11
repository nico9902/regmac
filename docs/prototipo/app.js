/* RegMac — prototipo del flusso di pre-assessment con AI locale. Dati di esempio, nessuna AI reale. */
const OUT={OK:['Conforme','ok'],PART:['Parziale','part'],NC:['Non conforme','nc'],INS:['Evidenze insufficienti','und'],NA:['Non applicabile','na']};
const ANS={S:['Supportato','ok'],C:['Contraddetto','nc'],I:['Insufficiente','und']};

/* ---------- catalogo ---------- */
const REQ=CAT.requirements, BYID=Object.fromEntries(REQ.map(r=>[r.id,r]));
const inScope=(r,tags)=>r.always&&(r.law==='MR'||tags.includes('cra'))||r.tags.some(t=>tags.includes(t));
const shortRef=r=>r.ref.replace(/, elenco \d+, lettera /,' ').replace(/, lettera /,' ').replace(/, comma (\d+)/,' c.$1').replace(/^All\. III, parte B, punto /,'III-B.').replace(/^All\. III, punto /,'').replace(/^All\. I, parte (I+), punto /,'$1.').replace(/^All\. IV, parte ([AB])/,'IV-$1').replace(/^All\. V, parte ([AB])/,'V-$1').replace(/^All\. (\w+), punto /,'$1.').replace(/^Allegato V/,'V').replace(/^Art\. (\d+), par\. (\d+)/,'art. $1($2)');
function hash(s){let x=2166136261;for(const c of s){x^=c.charCodeAt(0);x=Math.imul(x,16777619)}return (x>>>0)/4294967296}
const rnd=(...k)=>hash(k.join('|'));
const cut=(s,n)=>s.length>n?s.slice(0,n-1).replace(/\s+\S*$/,'')+'…':s;
/** Punti di controllo della scheda: le lettere a), b)… del testo, altrimenti le frasi principali. */
function points(r){
 if(SHEETS[r.id])return SHEETS[r.id].pts;
 const letters=[...r.text.matchAll(/(?:^|\s)([a-z])\)\s+([^;]{12,}?)(?=;|\.\s|$)/g)].map(m=>m[1]+') '+m[2]);
 const src=letters.length>=2?letters:r.text.split(/(?<=[.;:])\s+/).filter(s=>s.length>30);
 return (src.length?src:[r.text]).slice(0,4).map(s=>cut(s.trim(),120));
}
function applicability(r){
 if(SHEETS[r.id]?.app)return SHEETS[r.id].app;
 if(r.always)return 'Si applica in ogni caso.';
 const g=r.group.replace(/^Allegato III — /,'');
 return r.law==='CRA'?'Si applica ai prodotti con elementi digitali.':/^1\./.test(g)?`Si applica se la macchina presenta il pericolo o la funzione descritti (${cut(r.title,60)}).`:`Si applica alle macchine della categoria: ${cut(g,70)}.`;
}
/* schede curate per alcuni requisiti di esempio */
const SHEETS={
 'MR-III-1.2.4.3':{app:'Si applica salvo le eccezioni a) e b) del testo (arresto di emergenza inutile o macchine portatili).',pts:['Uno o più dispositivi di arresto di emergenza raggiungibili da ogni postazione di lavoro','Comandi chiaramente individuabili, ben visibili e rapidamente accessibili','Il comando resta bloccato finché non viene sbloccato con una manovra apposita','Lo sblocco non riavvia la macchina: consente solo la rimessa in funzione']},
 'MR-III-1.3.8.1':{app:'Si applica se la macchina ha elementi mobili di trasmissione (cinghie, catene, alberi, giunti).',pts:['Gli elementi mobili di trasmissione sono protetti da ripari fissi o mobili interbloccati','I ripari fissi richiedono un utensile per essere rimossi','Se sono previsti interventi frequenti, i ripari sono mobili e interbloccati']},
 'CRA-I-1-2d':{app:'Si applica se il prodotto ha interfacce di accesso (HMI, rete, porte di servizio).',pts:['Esiste un meccanismo di autenticazione per ogni interfaccia di accesso','Le credenziali non sono predefinite e comuni a tutti gli esemplari','Gli accessi non autorizzati vengono registrati e segnalati']},
};
const DOCS=[['Manuale istruzioni PX-200 rev C.pdf','Istruzioni per l\'uso',84,'9f2c…a71e',512],['Valutazione dei rischi PX-200.pdf','Valutazione dei rischi',36,'41be…0c93',204],['Schema elettrico E-200-01.pdf','Schemi',22,'c07d…5512',96],['Calcolo PL funzioni di sicurezza (SISTEMA).pdf','Rapporti di prova / calcoli',14,'77aa…e2f0',61],['Dichiarazione CE barriere fotoelettriche.pdf','Dichiarazioni componenti',2,'b5e1…09d4',8]];
const QS={S:['I ripari fissi sono fissati con viti che richiedono un utensile per la rimozione.','Il quadro elettrico è realizzato secondo EN 60204-1, grado di protezione IP54.','Le istruzioni sono fornite in lingua italiana e inglese.','La macchina è dotata di selettore di modalità con chiave estraibile.','Il comando a due mani è conforme alla EN ISO 13851, tipo IIIC.','Il livello di pressione acustica al posto operatore è pari a 78 dB(A).','Le funzioni di sicurezza raggiungono PL d secondo EN ISO 13849-1.','Il riavvio richiede un comando volontario sul pannello frontale.','La targa riporta fabbricante, modello, matricola, anno di costruzione e marcatura CE.'],
 C:['Il carter della trasmissione è fissato con viti a farfalla.','Zona di scarico posteriore: accesso dell\'operatore durante il ciclo di scarico manuale.','Non è previsto un dispositivo di blocco del riparo in posizione aperta.','La documentazione non riporta i valori di vibrazione trasmessi al sistema mano-braccio.']};
const CURATED={
 'MR-III-1.2.4.3':[['C',.86,'Il pulsante di arresto di emergenza è situato sul pannello di comando frontale.',0,'p. 34'],['S',.93,'Pulsante a fungo rosso su fondo giallo, conforme a EN ISO 13850.',0,'p. 34'],['S',.88,'Il pulsante di emergenza resta azionato fino allo sblocco per rotazione.',0,'p. 35'],['I',.71,'',null,'']],
 'MR-III-1.3.8.1':[['C',.9,'Il carter della trasmissione a cinghia è fissato con viti a farfalla.',1,'§ 4.3'],['C',.82,'Le viti a farfalla consentono la rimozione rapida per la pulizia.',0,'p. 52'],['I',.64,'',null,'']],
 'CRA-I-1-2d':[['S',.91,'L\'accesso all\'HMI richiede utente e password.',0,'p. 61'],['C',.88,'Password di accesso predefinita: admin (uguale per tutte le macchine).',0,'p. 61'],['I',.77,'',null,'']],
};

/* ---------- fascicoli di esempio ---------- */
const machines=[
 {id:'px200',name:'Pressa idraulica PX-200',model:'PX-200',serial:'0042',year:2026,maker:'Officine Rossi S.r.l.',use:'Stampaggio a freddo di lamiere fino a 3 mm, carico e scarico manuale da un operatore.',tags:['macchina','generale','cra'],edited:'ieri, 17:42',docs:DOCS,run:{state:'done',minutes:11},reviewed:96},
 {id:'l1',name:'Linea confezionamento L1',model:'PKL-1',serial:'0117',year:2025,maker:'Pack Systems S.p.A.',use:'Confezionamento in film termoretraibile di prodotti alimentari.',tags:['macchina','generale','alimentari','cra'],edited:'03/11/2026',docs:DOCS.slice(0,3),run:{state:'done',minutes:9},reviewed:'all',exported:'03/11/2026'},
 {id:'tr30',name:'Troncatrice TR-30',model:'TR-30',serial:'T-0310',year:2025,maker:'Taglio Rapido S.r.l.',use:'Taglio di profilati in alluminio.',tags:['macchina','generale'],edited:'oggi, 9:12',docs:[['Scheda prodotto TR-30.pdf','Scheda commerciale',1,'3d1f…77ab',6]],run:{state:'done',minutes:4},reviewed:0,sparse:true},
 {id:'ce15',name:'Carrello elevatore CE-15',model:'CE-15',serial:'A-7781',year:2026,maker:'Lift Italia S.r.l.',use:'Movimentazione di carichi pallettizzati fino a 1,5 t in magazzino.',tags:['macchina','generale','mobilita','sollevamento'],edited:'in corso',docs:DOCS.slice(0,4),run:{state:'running',done:142},reviewed:0},
];
const M=()=>machines.find(m=>m.id===S.mid);
const scope=m=>REQ.filter(r=>inScope(r,m.tags));
const AI={};
function evaluate(m,r){
 const u=rnd(r.id,m.model);
 if(!r.always&&u<.12)return {na:true,app:+(.04+.3*rnd(r.id,'a')).toFixed(2),pts:[]};
 const pts=points(r),kind=m.sparse?(u<.16?'PART':u<.2?'NC':'INS'):u<.42?'OK':u<.60?'PART':u<.86?'NC':'INS';
 if(CURATED[r.id]&&m.id==='px200')return {na:false,app:.97,pts:pts.map((t,i)=>{const c=CURATED[r.id][i]||['I',.7,'',null,''];return {t,a:c[0],p:c[1],q:c[2],d:c[3],loc:c[4]}})};
 const ci=Math.floor(rnd(r.id,'c')*pts.length);
 return {na:false,app:+(.86+.13*rnd(r.id,'ap')).toFixed(2),pts:pts.map((t,i)=>{
  const a=kind==='OK'?'S':kind==='INS'?'I':kind==='NC'?(i===ci||rnd(r.id,'nc',i)<.5?'C':'S'):(i===0||pts.length===1?'S':rnd(r.id,'pp',i)<.6?'I':'S');
  const aa=kind==='PART'&&pts.length>1&&i===pts.length-1&&a==='S'?'I':a;
  const pool=aa==='C'?QS.C:QS.S,doc=Math.floor(rnd(r.id,'d',i)*m.docs.length);
  return {t,a:aa,p:+(.68+.31*rnd(r.id,'p',i)).toFixed(2),q:aa==='I'?'':pool[Math.floor(rnd(r.id,'q',i)*pool.length)],d:aa==='I'?null:doc,loc:aa==='I'?'':'p. '+(5+Math.floor(rnd(r.id,'l',i)*70))};
 })};
}
machines.forEach(m=>{AI[m.id]={};const sc=scope(m),n=m.run.state==='running'?m.run.done:sc.length;sc.slice(0,n).forEach((r,i)=>{AI[m.id][r.id]={...evaluate(m,r),ok:m.reviewed==='all'||i<m.reviewed}})});
function outcome(x){if(!x)return null;if(x.na)return 'NA';const a=x.pts.map(p=>p.a);if(a.includes('C'))return 'NC';if(a.every(v=>v==='S'))return 'OK';if(a.includes('S'))return 'PART';return 'INS'}
const conf=x=>x.na?1-x.app:Math.min(...x.pts.map(p=>p.p),x.app);
const doubtful=x=>x&&!x.ok&&conf(x)<.8;
/** Score per requisito: ogni requisito applicabile vale la quota dei suoi punti supportati (contraddetti e senza evidenze = 0).
 *  Massimo raggiungibile: come se tutti i punti senza evidenze risultassero supportati. */
function reqScore(x){const n=x.pts.length||1,S=x.pts.filter(p=>p.a==='S').length,I=x.pts.filter(p=>p.a==='I').length;return {v:S/n,max:(S+I)/n}}
function score(m){
 let S=0,C=0,I=0,reqs=0,na=0,done=0,ok=0,sum=0,sumMax=0,full=0;const sc=scope(m);
 sc.forEach(r=>{const x=AI[m.id][r.id];if(!x)return;done++;if(x.ok)ok++;if(x.na){na++;return}reqs++;x.pts.forEach(p=>p.a==='S'?S++:p.a==='C'?C++:I++);const q=reqScore(x);sum+=q.v;sumMax+=q.max;if(q.v===1)full++});
 const T=S+C+I||1,R=reqs||1;return {S,C,I,T,score:sum/R,max:sumMax/R,cov:(S+C)/T,full,reqs,na,done,ok,total:sc.length};
}
const pct=v=>Math.round(v*100)+'%';
/** Sotto questa copertura delle evidenze lo score non è significativo: il report dice "non valutabile". */
const MIN_COV=.30;
const notEval=s=>s.done>0&&s.cov<MIN_COV;
/** Un gap per requisito: tecnico se almeno un punto è contraddetto, documentale se mancano solo prove. */
function gaps(m){
 const out=[];scope(m).forEach(r=>{const x=AI[m.id][r.id];if(!x)return;
  if(x.na){if(x.app>.2)out.push({r,kind:'Applicabilità',pr:3,t:applicability(r),p:1-x.app,pts:[]});return}
  const bad=x.pts.filter(p=>p.a!=='S');if(!bad.length)return;const tech=bad.some(p=>p.a==='C');
  out.push({r,kind:tech?'Tecnico':'Documentale',pr:tech?1:2,t:(bad.find(p=>p.a==='C')||bad[0]).t,p:Math.max(...bad.map(p=>p.p)),pts:bad,lost:1-reqScore(x).v})});
 return out.sort((a,b)=>a.pr-b.pr||b.p-a.p);
}
function areaOf(r){
 if(r.section==='MR-III'){const g=r.group.replace(/^Allegato III — /,'');const m=g.match(/^(\d)(\.\d)?\.?\s/);if(!m)return 'Reg. Macchine — 1.1 Considerazioni generali';return 'Reg. Macchine — '+(m[1]==='1'?g.replace(/^(\d\.\d)\. /,'$1 '):({2:'2 Categorie particolari',3:'3 Mobilità',4:'4 Sollevamento',5:'5 Lavori sotterranei',6:'6 Sollevamento di persone'})[m[1]])}
 if(r.law==='MR')return 'Reg. Macchine — Obblighi, documentazione e dichiarazioni';
 if(/parte I —/.test(r.group))return 'CRA — Proprietà di sicurezza (All. I, parte I)';
 if(/parte II/.test(r.group))return 'CRA — Gestione delle vulnerabilità (All. I, parte II)';
 return 'CRA — Obblighi, informazioni e documentazione';
}
function areas(m){const a={};scope(m).forEach(r=>{const x=AI[m.id][r.id];if(!x||x.na)return;const k=areaOf(r);a[k]=a[k]||{n:0,v:0,max:0};const q=reqScore(x);a[k].n++;a[k].v+=q.v;a[k].max+=q.max});return Object.entries(a).map(([k,v])=>({k,n:v.n,score:v.v/v.n,max:v.max/v.n}))}

/* ---------- stato UI ---------- */
const S={screen:'welcome',wstep:0,tab:'fascicoli',mid:'px200',ftab:'requisiti',sel:'MR-III-1.2.4.3',filter:'all',q:'',wiz:null,norm:'MR-III-1.2.4.3',nq:'',note:true,showLegal:false,gk:'all',allGaps:false};
const NOTES={
 welcome:['Primo avvio','Due passi: chi firma il report e il controllo dell\'AI locale. Senza Ollama e i modelli l\'app non procede: è un\'app di pre-assessment basata sull\'AI.'],
 fascicoli:['Fascicoli','Una riga per macchina con lo score di compliance e lo stato della pre-valutazione. Il carrello CE-15 è in elaborazione: guarda la barra avanzare.'],
 panoramica:['Panoramica','Score unico: ogni requisito applicabile pesa uguale e vale la quota dei suoi punti supportati. Il massimo dice fin dove può arrivare se le evidenze mancanti risultano favorevoli; il resto sono gap tecnici.'],
 documenti:['Documenti','I file vengono letti e indicizzati una volta sola. Con un documento nuovo si rivalutano solo i requisiti interessati.'],
 requisiti:['Requisiti','Qui si rivede ciò che ha deciso l\'AI: per ogni punto di controllo risposta, probabilità e citazione. Puoi cambiare un punto: l\'esito si ricalcola. Invio conferma e passa al successivo, J/K scorrono.'],
 gap:['Gap','Un gap per requisito: tecnico se almeno un punto è contraddetto, documentale se mancano solo le prove. Dentro ci sono i punti non soddisfatti con le citazioni.'],
 report:['Report','Report di pre-assessment: score e massimo raggiungibile, aree, gap per requisito, documenti da richiedere. Niente verdetto conforme/non conforme e niente firma di chiusura.'],
 normativa:['Normativa','La wiki dei due regolamenti. Per ogni requisito c\'è anche la scheda di verifica che usa l\'AI: condizione di applicabilità e punti di controllo.'],
 impostazioni:['Impostazioni','Profilo, AI locale (obbligatoria), versione delle schede, backup. Nient\'altro.'],
 wizard:['Nuova macchina','Carichi i documenti, l\'AI propone il profilo con le citazioni, tu confermi i punti incerti e avvii la pre-valutazione.'],
};
function noteKey(){if(S.wiz)return 'wizard';if(S.screen==='welcome')return 'welcome';if(S.tab==='fascicolo')return S.ftab;return S.tab}

/* ---------- componenti ---------- */
const chip=(o,extra='')=>o?`<span class="chip ${OUT[o][1]}">${extra}${OUT[o][0]}</span>`:'<span class="chip">In coda</span>';
const rangeBar=s=>`<div class="range" title="Score ${pct(s.score)} · massimo raggiungibile ${pct(s.max)}"><i class="mx" style="width:${s.max*100}%"></i><i class="sc" style="width:${s.score*100}%"></i></div>`;
const fill=(v,cls='')=>`<div class="bar"><i class="fill ${cls}" style="width:${(v*100).toFixed(1)}%"></i></div>`;
function runBox(m){const sc=scope(m);if(m.run.state!=='running')return '';const d=Object.keys(AI[m.id]).length;return `<div class="card run"><div class="row"><span class="spin">${I('spark',16)}</span><b class="grow">Pre-valutazione in corso — ${d} di ${sc.length} requisiti</b><span class="tiny mute">circa ${Math.max(1,Math.round((sc.length-d)*2.6/60))} min · qwen3:8b</span></div>${fill(d/sc.length,'brand')}<p class="tiny mute" style="margin-top:6px">Puoi già rivedere i requisiti valutati. Il lavoro continua anche se chiudi il fascicolo.</p></div>`}

/* ---------- render ---------- */
function render(){
 const w=document.getElementById('win');
 const title=S.screen==='welcome'?'RegMac':S.tab==='fascicolo'?`RegMac — ${M().name}`:'RegMac';
 const body=S.screen==='welcome'?welcome():appbar()+`<div class="content">${S.tab==='fascicoli'?fascicoli():S.tab==='normativa'?normativa():S.tab==='impostazioni'?impostazioni():fascicolo()}</div>`;
 w.innerHTML=`<div class="titlebar"><div class="dots"><span></span><span></span><span></span></div><div class="t">${esc(title)}</div></div>`+body;
 document.getElementById('ov').innerHTML=S.wiz?wizard():S.del?delModal():'';
 const n=NOTES[noteKey()];
 document.getElementById('note').innerHTML=S.note&&n?`<div class="note" role="note"><button data-a="hidenote" aria-label="Nascondi nota">×</button><b>${n[0]} — nota di design</b>${n[1]}</div>`:'';
 const cur=S.screen==='welcome'?'welcome':S.tab==='fascicolo'?'f:'+S.ftab:S.tab;
 document.getElementById('proto').innerHTML='<span>Prototipo</span>'+[['welcome','Primo avvio'],['fascicoli','Fascicoli'],['wizard','Nuova macchina'],['f:panoramica','Panoramica'],['f:documenti','Documenti'],['f:requisiti','Requisiti'],['f:gap','Gap'],['f:report','Report'],['normativa','Normativa'],['impostazioni','Impostazioni']].map(([k,l])=>`<button data-a="jump" data-k="${k}" class="${(k===cur&&!S.wiz)||(k==='wizard'&&S.wiz)?'on':''}">${l}</button>`).join('')+`<button data-a="theme" title="Tema chiaro/scuro">◐</button><button data-a="togglenote">${S.note?'Nascondi note':'Mostra note'}</button>`;
 document.querySelector('.item.on')?.scrollIntoView({block:'nearest'});
}
function appbar(){
 const t=(id,ic,l)=>`<button class="tab ${S.tab===id||(id==='fascicoli'&&S.tab==='fascicolo')?'on':''}" data-a="tab" data-t="${id}">${I(ic)}${l}</button>`;
 return `<div class="appbar"><div class="logo"><img src="logo.png" alt="">RegMac</div><div class="tabs">${t('fascicoli','folder','Fascicoli')}${t('normativa','book','Normativa')}</div>
 <div class="who"><span class="tiny"><span class="live"></span>AI locale attiva</span><div class="avatar">MR</div><span>Mario Rossi</span><button class="iconbtn" data-a="tab" data-t="impostazioni" aria-label="Impostazioni">${I('gear',17)}</button></div></div>`;
}

/* primo avvio */
function welcome(){
 const left=`<div class="l"><div class="logo" style="color:#fff"><img src="logo.png" alt="">RegMac</div>
 <h1 style="font-size:30px;line-height:1.2;color:#fff;margin-top:30px">Pre-assessment di macchine con l'AI, sul tuo computer.</h1>
 <p>Carichi la documentazione tecnica: l'AI locale valuta ogni requisito del Regolamento Macchine (UE) 2023/1230 e del Cyber Resilience Act (UE) 2024/2847 e produce un report con score di compliance e gap.</p>
 <ul style="padding-left:18px;font-size:13px"><li>Nessun dato esce dal computer</li><li>Ogni esito cita il documento da cui deriva</li><li>Tu rivedi e correggi prima del report</li></ul>
 <p class="tiny" style="margin-top:auto;color:#ffffff99">Versione 1.0 · schede di verifica v0.1</p></div>`;
 const right=S.wstep===0?`<div class="r"><span class="eyebrow">Primo avvio · 1 di 2</span><h1>Chi firma i report?</h1><p class="mute">Nome e organizzazione compaiono nell'intestazione del report. Puoi cambiarli in Impostazioni.</p>
 <label class="f">Nome e cognome<input value="Mario Rossi"></label><label class="f"><span>Organizzazione <span class="tiny mute">(facoltativa)</span></span><input value="Studio Tecnico Rossi"></label>
 <div class="row" style="margin-top:8px"><button class="btn pri" data-a="wstep">Avanti ${I('chev')}</button></div></div>`
 :`<div class="r"><span class="eyebrow">Primo avvio · 2 di 2</span><h1>Controllo dell'AI locale</h1><p class="mute">RegMac usa Ollama e tre modelli. Il controllo si ripete a ogni avvio.</p>
 <div class="card" style="padding:4px 14px">${[['Ollama 0.40.16','in esecuzione su questo computer'],['qwen3:8b · 5,2 GB','decisioni sui requisiti'],['qwen3-vl:8b · 6,2 GB','lettura di scansioni e immagini'],['nomic-embed-text · 274 MB','indice dei documenti'],['Memoria 36 GB','sufficiente (minimo 16 GB)']].map(([a,b])=>`<div class="block"><span style="color:var(--ok)">${I('check')}</span><div class="grow"><b>${a}</b><div class="tiny mute">${b}</div></div></div>`).join('')}</div>
 <p class="tiny mute">Se manca qualcosa, qui compaiono le istruzioni e il download dei modelli con l'avanzamento.</p>
 <div class="row"><button class="btn" data-a="wback0">${I('left')}Indietro</button><button class="btn pri" data-a="start">Inizia ${I('chev')}</button></div></div>`;
 return `<div class="welcome">${left}${right}</div>`;
}

/* fascicoli */
function fascicoli(){
 const rows=machines.map(m=>{const s=score(m),running=m.run.state==='running';
  const state=running?`<span class="chip brand"><span class="spin">${I('spark',12)}</span>Pre-valutazione ${s.done}/${s.total}</span>`:m.exported?`<span class="chip ok">${I('check',12)}Report esportato ${m.exported}</span>`:`<span class="chip part">Da rivedere ${s.ok}/${s.total}</span>`;
  return `<button class="mrow" data-a="open" data-m="${m.id}"><div class="micon">${I('machine',20)}</div>
  <div><b style="font-size:14px">${esc(m.name)}</b><div class="tiny mute">${esc(m.maker)} · matr. ${esc(m.serial)} · ${m.edited}</div></div>
  ${notEval(s)&&!running?`<div class="row" style="gap:10px"><span class="chip part">${I('warn',12)}Non valutabile</span><span class="tiny mute">documentazione insufficiente · copertura ${pct(s.cov)}</span></div>`:`<div class="row" style="gap:14px"><div><div class="sc-mini">${pct(s.score)}</div><div class="tiny mute">${running?'provvisorio':'score'}</div></div><div class="grow">${running?fill(s.done/s.total,'brand'):rangeBar(s)}<div class="tiny mute" style="margin-top:4px">${running?'in elaborazione':`fino a ${pct(s.max)} con le evidenze mancanti · ${gaps(m).length} gap`}</div></div></div>`}
  <div>${state}</div><span class="rowdel" role="button" tabindex="0" title="Elimina fascicolo" data-a="askdel" data-m="${m.id}">${I('trash',15)}</span></button>`}).join('');
 return `<div class="page"><div class="row"><div class="grow"><h1>Fascicoli</h1><p class="mute small">Una macchina, un fascicolo: documenti, pre-valutazione AI, gap e report.</p></div>
 <div style="width:260px"><input placeholder="Cerca macchina, matricola, fabbricante…"></div><button class="btn pri" data-a="wiz">${I('plus')}Nuova macchina</button></div>
 <div class="list">${rows}</div></div>`;
}

/* fascicolo */
function fascicolo(){
 const m=M(),s=score(m),g=gaps(m);
 const t=(id,l,n,cls)=>`<button class="ftab ${S.ftab===id?'on':''}" data-a="ftab" data-f="${id}">${l}${n!=null?`<span class="n ${cls||''}">${n}</span>`:''}</button>`;
 const head=`<div class="fhead"><div class="crumb"><button data-a="tab" data-t="fascicoli">Fascicoli</button>›<span>${esc(m.name)}</span></div>
 <div class="row" style="margin-top:6px"><h1>${esc(m.name)}</h1>${m.run.state==='running'?`<span class="chip brand"><span class="spin">${I('spark',12)}</span>Pre-valutazione in corso</span>`:(notEval(s)?`<span class="chip part">Non valutabile · documentazione insufficiente</span>`:`<span class="chip">Score ${pct(s.score)}</span>`)}<div class="grow"></div><button class="btn" data-a="toast" data-msg="Esporta il fascicolo in un file .regmac (dati, documenti, esiti, report)">${I('dl')}Esporta</button><button class="btn danger" data-a="askdel" data-m="${m.id}">${I('trash')}Elimina</button></div>
 <div class="ftabs">${t('panoramica','Panoramica')}${t('documenti','Documenti',m.docs.length)}${t('requisiti','Requisiti',`${s.ok}/${s.total}`)}${t('gap','Gap',g.length,'nc')}${t('report','Report')}</div></div>`;
 const body={panoramica,documenti,requisiti,gap,report}[S.ftab](m,s,g);
 return `<div style="display:flex;flex-direction:column;height:100%">${head}<div style="flex:1;min-height:0;overflow:${S.ftab==='requisiti'?'hidden':'auto'}">${body}</div></div>`;
}
function panoramica(m,s,g){
 const top=g.slice(0,5),running=m.run.state==='running';
 return `<div class="page">${runBox(m)}<div class="grid2" style="grid-template-columns:1.1fr 1fr">
 ${notEval(s)&&!running?`<div class="card"><span class="eyebrow">Compliance score</span><div class="row" style="gap:12px;margin:8px 0 10px"><span class="chip part" style="font-size:14px;padding:6px 10px">${I('warn',15)}Non valutabile</span></div>
 <p class="small">I documenti rispondono solo al <b>${pct(s.cov)}</b> dei punti di controllo (soglia ${pct(MIN_COV)}). Uno score calcolato su così poche evidenze sarebbe fuorviante.</p>
 <p class="small mute" style="margin-top:8px">Carica almeno istruzioni per l'uso, valutazione dei rischi e schemi: la pre-valutazione si aggiorna sui requisiti interessati.</p>
 <div class="row" style="margin-top:12px"><button class="btn pri sm" data-a="ftab" data-f="documenti">${I('upload',13)}Carica documenti</button><button class="btn sm" data-a="ftab" data-f="report">Elenco documenti da richiedere</button></div></div>`:`<div class="card"><span class="eyebrow">Compliance score</span><div class="row" style="align-items:flex-end;gap:16px;margin:4px 0 10px"><div class="score">${pct(s.score)}</div><div class="small mute" style="padding-bottom:9px">può arrivare fino a <b>${pct(s.max)}</b> con le evidenze mancanti<br>copertura evidenze <b>${pct(s.cov)}</b></div></div>${rangeBar(s)}<div class="row tiny mute" style="margin-top:6px;gap:14px"><span><i class="lg sc"></i>score</span><span><i class="lg mx"></i>recuperabile con le evidenze</span><span><i class="lg rest"></i>gap tecnici</span></div>
 <div class="grid3" style="margin-top:14px;gap:8px">${[['Requisiti pienamente soddisfatti',s.full,'ok'],['Con gap tecnici',g.filter(x=>x.kind==='Tecnico').length,'nc'],['Con gap documentali',g.filter(x=>x.kind==='Documentale').length,'und']].map(([l,v,c])=>`<div class="mini"><span class="tiny mute">${l}</span><b style="color:var(--${c==='und'?'mute':c})">${v}</b></div>`).join('')}</div>
 <p class="tiny mute" style="margin-top:10px">${s.reqs} requisiti applicabili · ${s.na} non applicabili · score = media sui requisiti applicabili della quota di punti di controllo supportati</p></div>`}
 <div class="card"><h2>Percorso</h2><div class="steps" style="margin-top:6px">${[['Documenti caricati e indicizzati',`${m.docs.length} documenti · ${m.docs.reduce((a,d)=>a+d[4],0)} brani`,true],['Profilo proposto dall\'AI e confermato',`${s.total} requisiti nel perimetro`,true],['Pre-valutazione AI',running?`${s.done} di ${s.total}…`:`completata in ${m.run.minutes} min · qwen3:8b`,!running],['Revisione delle proposte',`${s.ok} di ${s.total} confermate`,s.ok===s.total],['Report di pre-assessment',m.exported?'esportato il '+m.exported:'non ancora esportato',!!m.exported]].map((x,i,arr)=>{const cur=arr.findIndex(y=>!y[2]);return `<div class="step"><div class="sn ${x[2]?'done':i===cur?'cur':''}">${x[2]?I('check',13):i+1}</div><div class="grow"><b>${x[0]}</b><div class="tiny mute">${x[1]}</div></div>${i===cur&&i===3?'<button class="btn pri sm" data-a="ftab" data-f="requisiti">Rivedi</button>':''}</div>`}).join('')}</div></div></div>
 <div class="grid2" style="grid-template-columns:1.1fr 1fr;margin-top:16px"><div class="card"><div class="row"><h2 class="grow">Gap principali</h2><button class="btn ghost sm" data-a="ftab" data-f="gap">Tutti i ${g.length}</button></div>
 ${top.map(x=>`<div class="block"><span class="chip ${x.kind==='Tecnico'?'nc':x.kind==='Documentale'?'und':'part'}">${x.kind}</span><div class="grow"><b>${esc(shortRef(x.r))}</b> ${esc(x.r.title)}<div class="tiny mute">${esc(cut(x.t,110))}</div></div></div>`).join('')||'<p class="mute small">Nessun gap.</p>'}</div>
 <div class="card"><div class="row"><h2 class="grow">Macchina</h2><button class="btn ghost sm">Modifica</button></div><dl class="kv" style="margin-top:10px"><dt>Modello</dt><dd>${m.model} · matr. ${m.serial} · ${m.year}</dd><dt>Fabbricante</dt><dd>${esc(m.maker)}</dd><dt>Uso previsto</dt><dd>${esc(m.use)}</dd>
 <dt>Profilo</dt><dd>Macchina completa · RESS generali${m.tags.includes('cra')?' · CRA':''}${m.tags.includes('alimentari')?' · Alimentari':''}${m.tags.includes('mobilita')?' · Mobilità':''}${m.tags.includes('sollevamento')?' · Sollevamento':''}<div class="tiny mute">proposto dall'AI, confermato da Mario Rossi</div></dd></dl></div></div></div>`;
}
function documenti(m){
 const rows=m.docs.map(d=>`<tr><td><div class="row">${I('file')}<b>${esc(d[0])}</b></div></td><td>${d[1]}</td><td>${d[2]}</td><td><span class="chip ok">${I('check',11)}${d[4]} brani</span></td><td><code class="tiny mute">${d[3]}</code></td></tr>`).join('');
 return `<div class="page"><div class="card" style="border-style:dashed;text-align:center;padding:22px;background:var(--brand3)">${I('upload',22)}<div style="margin-top:6px"><b>Trascina qui i file</b> o <button class="btn ghost sm" style="padding:0">sfoglia</button></div><div class="tiny mute">PDF, Word, immagini e scansioni. Vengono letti, indicizzati e conservati con l'impronta SHA-256.</div></div>
 <div class="card" style="margin-top:14px;padding:6px 8px"><table><thead><tr><th>Documento</th><th>Tipo (riconosciuto dall'AI)</th><th>Pagine</th><th>Indicizzazione</th><th>Impronta</th></tr></thead><tbody>${rows}</tbody></table></div>
 <div class="card row" style="margin-top:14px;background:var(--brand3)"><div class="grow"><b>Hai aggiunto un documento?</b><div class="tiny mute">Vengono rivalutati solo i requisiti a cui il nuovo documento è pertinente.</div></div><button class="btn" data-a="toast" data-msg="Rivaluta 37 requisiti interessati dal nuovo documento (circa 2 minuti)">Aggiorna la pre-valutazione</button></div></div>`;
}
function requisiti(m,s){
 const ai=AI[m.id],q=S.q.toLowerCase();
 const pass=r=>{const x=ai[r.id],o=outcome(x);if(S.filter==='todo'&&(!x||x.ok))return false;if(S.filter==='doubt'&&!doubtful(x))return false;if(S.filter==='gap'&&!['NC','PART','INS'].includes(o))return false;if(S.filter==='na'&&o!=='NA')return false;if(q&&!(r.ref+' '+r.title+' '+r.text).toLowerCase().includes(q))return false;return true};
 const sc=scope(m);let last='',lastLaw='';
 const tree=sc.filter(pass).map(r=>{const x=ai[r.id],o=outcome(x);let h='';
  if(r.law!==lastLaw){h+=`<div class="law">${r.law==='MR'?'Regolamento Macchine':'Cyber Resilience Act'}</div>`;lastLaw=r.law}
  if(r.group!==last){h+=`<div class="ghead">${esc(cut(r.group.replace(/^Allegato III — /,''),48))}</div>`;last=r.group}
  return h+`<button class="item ${S.sel===r.id?'on':''}" data-a="sel" data-r="${r.id}"><span class="dot ${o||'Q'}"></span><span class="ref">${esc(shortRef(r))}</span><span class="grow">${esc(r.title)}</span>${x?.ok?`<span class="ok-mark" title="Confermato">${I('check',12)}</span>`:doubtful(x)?`<span class="doubt" title="Da verificare: confidenza bassa">!</span>`:''}</button>`}).join('');
 const r=BYID[S.sel],x=ai[r.id],o=outcome(x),idx=sc.findIndex(z=>z.id===r.id);
 let card;
 if(!x)card=`<div class="card" style="text-align:center;padding:30px"><span class="spin">${I('spark',22)}</span><p class="mute" style="margin-top:8px">Requisito in coda: l'AI lo valuterà a breve.</p></div>`;
 else if(x.na)card=`<div class="card"><div class="row"><span class="eyebrow grow">Esito dell'AI</span>${chip('NA')}<span class="tiny mute">probabilità che si applichi ${x.app}</span></div><p class="small" style="margin-top:10px"><b>Condizione:</b> ${esc(applicability(r))}</p><p class="small mute" style="margin-top:6px">Nei documenti non c'è traccia della condizione. ${x.app>.2?'<b style="color:var(--part)">Confidenza bassa: verifica.</b>':''}</p>
  <div class="row" style="margin-top:10px"><button class="btn sm" data-a="toast" data-msg="Il requisito torna applicabile e viene valutato dall'AI">Invece si applica</button></div></div>`;
 else card=`<div class="card"><div class="row wrap"><span class="eyebrow grow">Esito dell'AI</span>${chip(o)}<span class="chip brand" title="Quota di punti supportati: è il contributo del requisito allo score">vale ${pct(reqScore(x).v)}${reqScore(x).max>reqScore(x).v?` · max ${pct(reqScore(x).max)}`:''}</span><span class="tiny mute">confidenza minima ${conf(x).toFixed(2)}</span></div>
  <p class="tiny mute" style="margin:6px 0 12px">Applicabile (probabilità ${x.app}) · ${esc(applicability(r))}</p>
  ${x.pts.map((p,i)=>`<div class="pt"><div class="row" style="align-items:flex-start"><span class="pn">${i+1}</span><div class="grow"><div class="small"><b>${esc(p.t)}</b></div>
   ${p.q?`<q>${esc(p.q)}</q><div class="tiny mute">${I('file',11)} ${esc(m.docs[p.d]?.[0]||'')} · ${esc(p.loc)} · citazione verificata</div>`:'<div class="tiny mute" style="margin-top:4px">Nessun brano dei documenti risponde a questo punto.</div>'}</div>
   <div style="text-align:right"><div class="yn">${Object.entries(ANS).map(([k,v])=>`<button class="${p.a===k?'on '+v[1]:''}" data-a="pt" data-i="${i}" data-v="${k}">${v[0]}</button>`).join('')}</div><div class="row tiny mute" style="justify-content:flex-end;margin-top:5px">p ${p.p.toFixed(2)}<div class="pbar"><i style="width:${p.p*100}%;background:${p.p<.8?'var(--part)':'var(--brand)'}"></i></div></div></div></div></div>`).join('')}
  <label class="f" style="margin-top:12px">Nota del revisore <span class="tiny mute">(facoltativa, compare nel report)</span><textarea style="min-height:44px"></textarea></label></div>`;
 const detail=`<div class="dwrap"><div class="row tiny mute"><span>${r.law==='MR'?'Reg. (UE) 2023/1230':'Reg. (UE) 2024/2847'}</span>·<span>${esc(r.group)}</span></div>
 <div class="row wrap" style="margin:6px 0 12px"><h1 style="font-size:21px"><span class="mute" style="font-weight:500">${esc(shortRef(r))}</span> ${esc(r.title)}</h1>${r.always?'<span class="chip brand">Si applica in ogni caso</span>':''}</div>
 ${card}
 <details class="legalbox" ${S.showLegal?'open':''}><summary data-a="legal">Testo ufficiale · Gazzetta, p. ${r.pages?.[0]}${(r.standards||[]).length?' · Norme: '+r.standards.slice(0,3).map(esc).join(', '):''}</summary><div class="legal" style="margin-top:10px">${esc(r.text)}</div></details></div>`;
 const nav=`<div class="navbar"><button class="btn" data-a="nav" data-d="-1">${I('left')}</button><span class="tiny mute grow">${idx+1} di ${sc.length} · <kbd>Invio</kbd> conferma · <kbd>J</kbd>/<kbd>K</kbd> scorri</span>${x?.ok?`<span class="chip ok">${I('check',12)}Confermato</span><button class="btn pri" data-a="nav" data-d="1">Successivo ${I('chev')}</button>`:`<button class="btn" data-a="nav" data-d="1">Salta</button><button class="btn pri" data-a="confirm" ${x?'':'disabled'}>${I('check',14)}Conferma e avanti</button>`}</div>`;
 return `<div class="eval"><div class="side"><div class="top">${runBox(m).replace('class="card run"','class="card run sm"')}<div class="row" style="border:1px solid var(--line);border-radius:7px;padding:0 8px;gap:6px">${I('search',14)}<input id="q" style="border:0;padding:7px 0;background:none" placeholder="Cerca requisito" value="${esc(S.q)}"></div>
 <div class="filters">${[['all','Tutti'],['todo',`Da confermare · ${s.done-s.ok}`],['doubt',`Dubbi · ${sc.filter(r=>doubtful(ai[r.id])).length}`],['gap','Con gap'],['na','N/A']].map(([k,l])=>`<button class="${S.filter===k?'on':''}" data-a="filter" data-k="${k}">${l}</button>`).join('')}</div></div>
 <div class="tree">${tree||'<p class="mute small" style="padding:12px">Nessun requisito con questi filtri.</p>'}</div>
 <div class="prog"><div class="row"><span class="grow">${s.ok} di ${s.total} confermati</span><span>${Math.round(s.ok/s.total*100)}%</span></div>${fill(s.ok/s.total,'brand')}</div></div>
 <div class="detail" style="display:flex;flex-direction:column"><div style="flex:1">${detail}</div>${nav}</div></div>`;
}
function gap(m,s,g){
 const list=g.filter(x=>S.gk==='all'||x.kind===S.gk),shown=S.allGaps?list:list.slice(0,40);
 const cnt=k=>g.filter(x=>x.kind===k).length;
 return `<div class="page"><div class="row"><div class="grow"><h2>Registro dei gap</h2><p class="tiny mute">Tecnico: i documenti contraddicono il requisito. Documentale: manca la prova. Applicabilità: non è chiaro se il requisito si applica.</p></div><button class="btn" data-a="toast" data-msg="Esporta il registro in Excel o PDF da inviare al fabbricante">${I('dl')}Esporta</button></div>
 <div class="filters" style="margin:12px 0">${[['all',`Tutti · ${g.length}`],['Tecnico',`Tecnici · ${cnt('Tecnico')}`],['Documentale',`Documentali · ${cnt('Documentale')}`],['Applicabilità',`Applicabilità · ${cnt('Applicabilità')}`]].map(([k,l])=>`<button class="${S.gk===k?'on':''}" data-a="gk" data-k="${k}">${l}</button>`).join('')}</div>
 <div class="card" style="padding:6px 8px"><table><thead><tr><th style="width:110px">Priorità</th><th style="width:220px">Requisito</th><th>Punti non soddisfatti ed evidenze</th><th style="width:150px">Azione</th></tr></thead><tbody>
 ${shown.map(x=>`<tr><td><span class="chip ${x.pr===1?'nc':x.pr===2?'und':'part'}">${['','Alta','Media','Bassa'][x.pr]}</span><div class="tiny mute" style="margin-top:4px">${x.kind}${x.lost!=null?` · requisito al ${pct(1-x.lost)}`:''}</div></td><td><button class="linkbtn" data-a="goto" data-r="${x.r.id}"><b>${esc(shortRef(x.r))}</b> ${esc(x.r.title)}</button></td>
  <td>${x.pts.length?x.pts.map(p=>`<div class="gp"><span class="chip ${ANS[p.a][1]}">${ANS[p.a][0]}</span> ${esc(p.t)}${p.q?`<q>${esc(p.q)}</q><div class="tiny mute">${esc(m.docs[p.d]?.[0]||'')} · ${esc(p.loc)}</div>`:''}</div>`).join(''):esc(x.t)}</td><td class="small">${x.kind==='Tecnico'?'Adeguare la macchina'+((x.r.standards||[])[0]?` (rif. ${esc(x.r.standards[0])})`:''):x.kind==='Documentale'?'Chiedere al fabbricante prova, calcolo o istruzioni':'Confermare se la condizione è presente'}</td></tr>`).join('')}</tbody></table>
 ${list.length>shown.length?`<div style="text-align:center;padding:10px"><button class="btn sm" data-a="allgaps">Mostra tutti i ${list.length}</button></div>`:''}</div></div>`;
}
function report(m,s,g){
 const ar=areas(m),docsNeeded=[...new Set(g.filter(x=>x.kind==='Documentale').map(x=>/istruz|informaz|manuale/i.test(x.r.title+x.t)?'Istruzioni per l\'uso (sezioni mancanti)':/rumor|vibraz|emission/i.test(x.r.title+x.t)?'Misure di rumore e vibrazioni':/cyber|vulnerab|accesso|dati/i.test(x.r.title+x.t)?'Documentazione di cibersicurezza (All. VII CRA)':/arresto|comando|sicurezza/i.test(x.r.title+x.t)?'Calcoli e validazione delle funzioni di sicurezza':'Rapporti di prova e verifiche di collaudo'))];
 const paper=`<div class="paper"><div class="lh"><div style="display:flex;gap:10px;align-items:center"><img src="logo.png" alt="" style="width:30px;height:30px;border-radius:6px"><div><b style="font-size:13px">Studio Tecnico Rossi</b><div>Mario Rossi</div></div></div><div style="text-align:right">Pre-assessment PX200-01<br>11/11/2026</div></div>
 <h1>Report di pre-assessment</h1><div style="margin:4px 0 14px">${esc(m.name)} · matr. ${m.serial} · ${esc(m.maker)}</div>
 ${notEval(s)?`<div style="border:1.5px solid #B45309;color:#B45309;border-radius:4px;padding:10px 12px;margin-bottom:6px"><b>NON VALUTABILE — documentazione insufficiente.</b><br>I documenti esaminati rispondono al ${pct(s.cov)} dei punti di controllo (soglia ${pct(MIN_COV)}): lo score non viene calcolato. Vedi i documenti da richiedere.</div>`:`<div style="display:flex;gap:26px;align-items:flex-end;margin-bottom:6px"><div><div style="font-size:9px;letter-spacing:.1em;color:#64748B">COMPLIANCE SCORE</div><div style="font-size:40px;font-weight:700;color:#0B1F3A;line-height:1">${pct(s.score)}</div></div><div style="padding-bottom:4px">Massimo raggiungibile con le evidenze mancanti <b>${pct(s.max)}</b> · Copertura evidenze <b>${pct(s.cov)}</b><br>${s.reqs} requisiti applicabili · ${s.full} pienamente soddisfatti · ${g.filter(x=>x.kind==='Tecnico').length} con gap tecnici · ${g.filter(x=>x.kind==='Documentale').length} con gap documentali</div></div>`}
 <table style="margin-top:12px"><tr><th>Area</th><th style="width:52px">Score</th><th style="width:52px">Max</th><th style="width:150px"></th></tr>${ar.map(a=>`<tr><td>${esc(a.k)}</td><td><b>${pct(a.score)}</b></td><td style="color:#64748B">${pct(a.max)}</td><td><div style="position:relative;height:6px;background:#E2E8F0;border-radius:3px"><div style="position:absolute;height:6px;width:${a.max*100}%;background:#BFD3FB;border-radius:3px"></div><div style="position:absolute;height:6px;width:${a.score*100}%;background:#155EEF;border-radius:3px"></div></div></td></tr>`).join('')}</table>
 <h3 style="margin:14px 0 6px;color:#0B1F3A">Gap principali</h3><table>${g.slice(0,6).map(x=>`<tr><td style="width:80px"><b>${esc(shortRef(x.r))}</b></td><td style="width:80px">${x.kind}</td><td>${esc(x.r.title)}: ${esc(cut(x.t,90))}</td></tr>`).join('')}</table>
 <div style="position:absolute;bottom:26px;left:50px;right:50px;display:flex;justify-content:space-between;font-size:9px;color:#777;border-top:1px solid #ddd;padding-top:6px"><span>Generato con AI locale (qwen3:8b) · schede v0.1 · ${s.ok}/${s.total} esiti rivisti</span><span>Pag. 1 di 18</span></div></div>`;
 return `<div class="page"><div class="rep"><div>${paper}</div><div class="stack">
 <div class="card"><h2>Esporta il report</h2><div style="margin-top:8px">
 <div class="block"><span style="color:var(--${s.ok===s.total?'ok':'part'})">${I(s.ok===s.total?'check':'warn')}</span><span class="grow">${s.ok===s.total?'Tutti gli esiti rivisti':`${s.total-s.ok} esiti non ancora rivisti`}</span>${s.ok<s.total?'<button class="btn sm" data-a="todo">Rivedi</button>':''}</div>
 <div class="block"><span style="color:var(--ok)">${I('check')}</span><span class="grow">Ogni esito ha citazioni verificate</span></div>
 <div class="block"><span style="color:var(--ok)">${I('check')}</span><span class="grow">Modello, schede e soglie registrati</span></div></div>
 <p class="tiny mute" style="margin:8px 0 12px">Puoi esportare anche con esiti non rivisti: il report lo dichiara.</p>
 <div class="row wrap"><button class="btn pri" data-a="toast" data-msg="PDF del report salvato e archiviato con la sua impronta">${I('dl')}PDF</button><button class="btn" data-a="toast" data-msg="Excel con requisiti, punti di controllo e gap">Excel</button></div></div>
 <div class="card"><h3>Documenti da richiedere</h3><ul class="small" style="padding-left:18px;margin:8px 0 0;line-height:1.8">${docsNeeded.slice(0,6).map(d=>`<li>${esc(d)}</li>`).join('')}</ul></div>
 <div class="card"><h3>Contenuto</h3><ol class="small mute" style="padding-left:18px;margin:8px 0 0;line-height:1.8"><li>Macchina, profilo e documenti esaminati</li><li>Compliance score, massimo raggiungibile e copertura</li><li>Score per area</li><li>Registro dei gap per priorità</li><li>Documenti da richiedere</li><li>Dettaglio per requisito con citazioni</li><li>Metodo e limiti del pre-assessment</li></ol></div></div></div></div>`;
}

/* normativa */
function normativa(){
 const q=S.nq.toLowerCase();let lastG='',lastLaw='';
 const tree=REQ.filter(r=>!q||(r.ref+' '+r.title+' '+r.text+' '+(r.standards||[]).join(' ')).toLowerCase().includes(q)).map(r=>{let h='';
  if(r.law!==lastLaw){h+=`<div class="law">${r.law==='MR'?'Regolamento Macchine':'Cyber Resilience Act'}</div>`;lastLaw=r.law}
  if(r.group!==lastG){h+=`<div class="ghead">${esc(cut(r.group.replace(/^Allegato III — /,''),48))}</div>`;lastG=r.group}
  return h+`<button class="item ${S.norm===r.id?'on':''}" data-a="nsel" data-r="${r.id}"><span class="ref">${esc(shortRef(r))}</span><span>${esc(r.title)}</span></button>`}).join('');
 const r=BYID[S.norm];
 return `<div class="eval"><div class="side"><div class="top"><div class="row" style="border:1px solid var(--line);border-radius:7px;padding:0 8px;gap:6px">${I('search',14)}<input id="nq" style="border:0;padding:7px 0;background:none" placeholder="Cerca nei due regolamenti o per norma" value="${esc(S.nq)}"></div><div class="tiny mute">${REQ.length} requisiti valutabili</div></div><div class="tree">${tree}</div></div>
 <div class="detail"><div class="dwrap"><div class="row tiny mute"><span>${r.law==='MR'?'Regolamento (UE) 2023/1230':'Regolamento (UE) 2024/2847'}</span>·<span>${esc(r.group)}</span></div>
 <h1 style="font-size:22px;margin:6px 0 10px"><span class="mute" style="font-weight:500">${esc(shortRef(r))}</span> ${esc(r.title)}</h1>
 <div class="legal">${esc(r.text)}</div>
 <div class="section-t">Scheda di verifica <span class="chip" style="text-transform:none;letter-spacing:0">${SHEETS[r.id]?'v0.1 · rivista':'v0.1 · bozza generata'}</span></div>
 <div class="card"><p class="small"><b>Si applica se:</b> ${esc(applicability(r))}</p><ol class="small" style="margin:10px 0 0;padding-left:20px;line-height:1.7">${points(r).map(p=>`<li>${esc(p)}</li>`).join('')}</ol></div>
 <div class="section-t">Norme di riferimento</div>${(r.standards||[]).length?`<div class="row wrap">${r.standards.map(s=>`<span class="chip">${esc(s)}</span>`).join('')}</div>`:'<p class="small mute">Nessuna norma associata.</p>'}</div></div></div>`;
}

/* impostazioni */
function impostazioni(){return `<div class="page" style="max-width:860px"><h1>Impostazioni</h1><div class="stack" style="margin-top:16px">
 <div class="card"><h2>Autore dei report</h2><div class="grid2" style="grid-template-columns:1fr 1fr;margin-top:12px"><label class="f">Nome e cognome<input value="Mario Rossi"></label><label class="f">Organizzazione<input value="Studio Tecnico Rossi"></label></div></div>
 <div class="card"><div class="row"><h2 class="grow">AI locale</h2><span class="chip ok"><span class="live"></span>Ollama 0.40.16 attivo</span></div>
 <table style="margin-top:10px"><tbody>${[['qwen3:8b','Decisioni sui requisiti e profilo','5,2 GB'],['qwen3-vl:8b','Lettura di scansioni e immagini','6,2 GB'],['nomic-embed-text','Indice semantico dei documenti','274 MB']].map(x=>`<tr><td><b>${x[0]}</b></td><td class="mute">${x[1]}</td><td>${x[2]}</td><td><span class="chip ok">${I('check',11)}installato</span></td></tr>`).join('')}</tbody></table>
 <div class="row" style="margin-top:10px"><span class="tiny mute grow">Soglia di confidenza: sotto 0,80 un esito è segnalato come dubbio.</span><button class="btn" data-a="toast" data-msg="Prova: risposta in 1,2 s">Prova i modelli</button></div></div>
 <div class="card"><h2>Schede di verifica</h2><p class="small mute" style="margin-top:6px">Versione v0.1 · ${REQ.length} schede · ${Object.keys(SHEETS).length} riviste da un esperto. Si aggiornano con le nuove versioni dell'app; i fascicoli indicano la versione usata.</p></div>
 <div class="card"><h2>Dati e backup</h2><dl class="kv" style="margin-top:10px"><dt>Cartella dati</dt><dd><code class="tiny">~/Library/Application Support/RegMac</code></dd><dt>Ultimo backup</dt><dd>08/11/2026 18:00</dd></dl>
 <div class="row" style="margin-top:12px"><button class="btn pri" data-a="toast" data-msg="Backup salvato in un unico file verificato">${I('db',14)}Esegui backup</button><button class="btn" data-a="toast" data-msg="Sceglie un backup, lo verifica e chiede conferma">Ripristina…</button></div></div></div></div>`}

/* nuova macchina */
const QUEST=[['mobilita','Si sposta durante il lavoro?',.04,'«La pressa è installata su basamento fisso ancorato al pavimento.»'],['sollevamento','Solleva o movimenta carichi?',.18,'«Carico e scarico manuale dei pezzi da parte dell\'operatore.»'],['sollevamento_persone','Solleva persone?',.02,''],['portatili','È portatile o condotta a mano?',.01,''],['alimentari','Lavora alimenti, cosmetici o farmaci?',.03,''],['legno','Lavora legno o materiali simili?',.02,''],['fitosanitari','Applica prodotti fitosanitari?',.01,''],['sotterraneo','È usata in lavori sotterranei?',.01,''],['cra','Ha software, rete o accesso remoto?',.94,'«HMI con porta Ethernet per teleassistenza del fabbricante.»'],['allegato1','È tra le categorie dell\'Allegato I (organismo notificato)?',.58,'«Pressa per la lavorazione a freddo dei metalli con carico manuale.»']];
function wizTags(w){const ans=k=>w.ans[k]??(QUEST.find(q=>q[0]===k)[2]>=.5);return ['generale','macchina',...QUEST.filter(q=>q[0]!=='allegato1'&&ans(q[0])).map(q=>q[0])]}
function wizard(){const w=S.wiz;
 const ans=k=>w.ans[k]??(QUEST.find(q=>q[0]===k)[2]>=.5);
 const tags=wizTags(w),sc=REQ.filter(r=>inScope(r,tags));
 let b='';
 if(w.step===0)b=`<div class="stack"><div class="grid2" style="grid-template-columns:1fr 1fr"><label class="f">Nome della macchina<input value="Pressa idraulica PX-300"></label><label class="f">Fabbricante<input value="Officine Rossi S.r.l."></label><label class="f">Modello<input value="PX-300"></label><label class="f">Matricola<input value="0051"></label></div>
 <div class="card" style="border-style:dashed;text-align:center;background:var(--brand3)">${I('upload',20)}<div><b>Trascina qui la documentazione tecnica</b></div><div class="tiny mute">Manuale, valutazione dei rischi, schemi, prove, dichiarazioni</div></div>
 ${DOCS.slice(0,3).map(d=>`<div class="row small"><span style="color:var(--ok)">${I('check',14)}</span><span class="grow">${esc(d[0])}</span><span class="tiny mute">letto · ${d[4]} brani</span></div>`).join('')}</div>`;
 if(w.step===1)b=`<p class="small mute" style="margin-bottom:10px">L'AI ha letto i documenti e propone il profilo. Controlla soprattutto le risposte <b style="color:var(--part)">incerte</b>.</p>
 ${QUEST.map(([k,qq,p,quote])=>{const unsure=p>.25&&p<.75;return `<div class="q ${unsure?'unsure':''}"><div class="grow"><b class="small">${qq}</b><div class="tiny mute">${quote?esc(quote)+' · ':''}probabilità sì ${p.toFixed(2)}${unsure?' · <b style="color:var(--part)">incerta</b>':''}</div></div><div class="yn"><button class="${ans(k)?'on':''}" data-a="wans" data-k="${k}" data-v="1">Sì</button><button class="${ans(k)?'':'on'}" data-a="wans" data-k="${k}" data-v="0">No</button></div></div>`}).join('')}`;
 if(w.step===2)b=`<div style="text-align:center;padding:10px 0"><div class="eyebrow">Perimetro</div><div class="count">${sc.length} requisiti</div><div class="small mute">${sc.filter(r=>r.law==='MR').length} Reg. Macchine · ${sc.filter(r=>r.law==='CRA').length} CRA</div></div>
 ${ans('allegato1')?`<div class="card" style="background:var(--partbg);border-color:transparent;color:var(--part);margin-top:10px"><div class="row small">${I('warn')}<span>Macchina dell'Allegato I: il report lo segnala, la valutazione finale richiede un organismo notificato.</span></div></div>`:''}
 <div class="card" style="margin-top:12px"><div class="row"><span style="color:var(--brand);display:flex">${I('spark',16)}</span><div class="grow"><b>Pre-valutazione AI</b><div class="tiny mute">circa ${Math.round(sc.length*2.6/60)} minuti con qwen3:8b · prosegue in sottofondo</div></div></div></div>`;
 return `<div class="ov"><div class="modal" role="dialog" aria-modal="true" aria-label="Nuova macchina"><div class="mh"><div class="grow"><span class="eyebrow">Nuova macchina · passo ${w.step+1} di 3</span><h2>${['Macchina e documenti','Profilo proposto dall\'AI','Avvio della pre-valutazione'][w.step]}</h2></div><div class="wsteps">${[0,1,2].map(i=>`<span class="${i<=w.step?'on':''}"></span>`).join('')}</div><button class="iconbtn" data-a="close">${I('x')}</button></div>
 <div class="mb">${b}</div><div class="mf">${w.step>0?`<button class="btn" data-a="wback">${I('left')}Indietro</button>`:''}<span class="grow"></span>${w.step<2?`<button class="btn pri" data-a="wnext">${w.step===0?'Proponi il profilo':'Conferma il profilo'} ${I('chev')}</button>`:`<button class="btn pri" data-a="wcreate">Avvia la pre-valutazione ${I('chev')}</button>`}</div></div></div>`}

function delModal(){const m=machines.find(x=>x.id===S.del),s=score(m);
 return `<div class="ov"><div class="modal" role="alertdialog" aria-modal="true" aria-label="Elimina fascicolo" style="width:min(520px,100%)"><div class="mh"><span style="color:var(--nc);display:flex">${I('trash',18)}</span><h2 class="grow">Eliminare il fascicolo?</h2><button class="iconbtn" data-a="close">${I('x')}</button></div>
 <div class="mb stack"><p class="small">Verranno eliminati definitivamente da questo computer:</p>
 <ul class="small" style="margin:0;padding-left:18px;line-height:1.8"><li><b>${esc(m.name)}</b> e i dati della macchina</li><li>${m.docs.length} documenti caricati e il loro indice</li><li>${s.done} esiti della pre-valutazione e le revisioni</li><li>i report esportati archiviati nel fascicolo</li></ul>
 <div class="card row" style="background:var(--brand3);padding:10px 12px"><span class="grow tiny">Vuoi tenerne una copia? Esporta prima il fascicolo in un file .regmac.</span><button class="btn sm" data-a="toast" data-msg="Fascicolo esportato in un file .regmac">${I('dl',13)}Esporta</button></div>
 <label class="f">Per confermare scrivi il nome della macchina: <b>${esc(m.name)}</b><input id="delname" autocomplete="off"></label></div>
 <div class="mf"><span class="grow"></span><button class="btn" data-a="close">Annulla</button><button class="btn danger pri" data-a="dodel">${I('trash',14)}Elimina definitivamente</button></div></div></div>`}
function undoToast(name){clearTimeout(toastT);document.querySelector('.toast')?.remove();const t=document.createElement('div');t.className='toast';t.innerHTML=`Fascicolo «${esc(name)}» eliminato <button data-a="undo" class="undo">Annulla</button>`;document.body.appendChild(t);toastT=setTimeout(()=>{t.remove();S.trash=null},8000)}

/* ---------- eventi ---------- */
let toastT;function toast(msg){clearTimeout(toastT);let t=document.querySelector('.toast');if(!t){t=document.createElement('div');t.className='toast';document.body.appendChild(t)}t.textContent='Prototipo — '+msg;toastT=setTimeout(()=>t.remove(),2600)}
function move(d){const f=scope(M()),i=f.findIndex(r=>r.id===S.sel);S.sel=f[Math.max(0,Math.min(f.length-1,i+d))].id;render()}
function confirmSel(){const x=AI[S.mid][S.sel];if(!x)return;x.ok=true;const f=scope(M()),i=f.findIndex(r=>r.id===S.sel),n=f.slice(i+1).find(r=>AI[S.mid][r.id]&&!AI[S.mid][r.id].ok);if(n)S.sel=n.id;render()}
document.addEventListener('click',e=>{const el=e.target.closest('[data-a]');if(!el)return;const a=el.dataset.a;if(el.tagName==='A'||a==='legal')e.preventDefault();
 if(a==='jump'){const k=el.dataset.k;S.wiz=null;if(k==='welcome'){S.screen='welcome';S.wstep=0}else{S.screen='app';if(k==='wizard'){S.tab='fascicoli';S.wiz={step:0,ans:{}}}else if(k.startsWith('f:')){S.tab='fascicolo';S.mid='px200';S.ftab=k.slice(2)}else S.tab=k}}
 if(a==='wstep')S.wstep=1;if(a==='wback0')S.wstep=0;
 if(a==='start'){S.screen='app';S.tab='fascicoli'}
 if(a==='tab')S.tab=el.dataset.t;
 if(a==='open'){S.mid=el.dataset.m;S.tab='fascicolo';S.ftab='panoramica';const f=scope(M());S.sel=(f.find(r=>AI[S.mid][r.id]&&!AI[S.mid][r.id].ok)||f[0]).id;S.filter='all'}
 if(a==='ftab')S.ftab=el.dataset.f;
 if(a==='sel')S.sel=el.dataset.r;
 if(a==='pt'){const x=AI[S.mid][S.sel];x.pts[+el.dataset.i].a=el.dataset.v;x.pts[+el.dataset.i].p=1;x.ok=false}
 if(a==='confirm'){confirmSel();return}
 if(a==='nav'){move(+el.dataset.d);return}
 if(a==='filter')S.filter=el.dataset.k;
 if(a==='legal')S.showLegal=!S.showLegal;
 if(a==='gk'){S.gk=el.dataset.k;S.allGaps=false}
 if(a==='allgaps')S.allGaps=true;
 if(a==='goto'){S.ftab='requisiti';S.sel=el.dataset.r;S.filter='all'}
 if(a==='todo'){S.ftab='requisiti';S.filter='todo';const f=scope(M()),n=f.find(r=>AI[S.mid][r.id]&&!AI[S.mid][r.id].ok);if(n)S.sel=n.id}
 if(a==='close'){S.wiz=null;S.del=null}
 if(a==='askdel'){e.stopPropagation();S.del=el.dataset.m}
 if(a==='dodel'){const i=machines.findIndex(x=>x.id===S.del),m=machines[i];if(document.getElementById('delname').value.trim()!==m.name){toast('scrivi il nome esatto della macchina per confermare');return}
  machines.splice(i,1);S.trash={m,i,ai:AI[m.id]};delete AI[m.id];S.del=null;S.tab='fascicoli';undoToast(m.name)}
 if(a==='undo'&&S.trash){machines.splice(S.trash.i,0,S.trash.m);AI[S.trash.m.id]=S.trash.ai;S.trash=null;document.querySelector('.toast')?.remove()}
 if(a==='wiz')S.wiz={step:0,ans:{}};
 if(a==='wnext')S.wiz.step++;if(a==='wback')S.wiz.step--;
 if(a==='wans')S.wiz.ans[el.dataset.k]=el.dataset.v==='1';
 if(a==='wcreate'){const id='px300-'+Date.now();machines.unshift({id,name:'Pressa idraulica PX-300',model:'PX-300',serial:'0051',year:2026,maker:'Officine Rossi S.r.l.',use:'Stampaggio a freddo di lamiere fino a 5 mm, carico manuale.',tags:wizTags(S.wiz),edited:'adesso',docs:DOCS.slice(0,3),run:{state:'running',done:0,fast:true},reviewed:0});AI[id]={};S.wiz=null;S.mid=id;S.tab='fascicolo';S.ftab='panoramica';const f=scope(M());S.sel=f[0].id}
 if(a==='nsel')S.norm=el.dataset.r;
 if(a==='toast'){toast(el.dataset.msg);return}
 if(a==='hidenote'||a==='togglenote')S.note=!S.note;
 if(a==='theme'){const cur=document.documentElement.dataset.theme||(matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light');document.documentElement.dataset.theme=cur==='dark'?'light':'dark'}
 render();
});
document.addEventListener('input',e=>{for(const id of ['q','nq'])if(e.target.id===id){S[id]=e.target.value;const pos=e.target.selectionStart;render();const n=document.getElementById(id);n.focus();n.setSelectionRange(pos,pos)}});
document.addEventListener('keydown',e=>{if(S.tab!=='fascicolo'||S.ftab!=='requisiti'||S.wiz||S.screen==='welcome')return;if(/INPUT|TEXTAREA|SELECT/.test(document.activeElement?.tagName))return;
 if(e.key==='Enter'){e.preventDefault();confirmSel()}if(e.key==='j'||e.key==='ArrowDown'){e.preventDefault();move(1)}if(e.key==='k'||e.key==='ArrowUp'){e.preventDefault();move(-1)}});
/* simulazione: le pre-valutazioni in corso avanzano (in un'app reale: circa 2–3 s per requisito) */
setInterval(()=>{let changed=null;
 machines.forEach(m=>{if(m.run.state!=='running')return;const sc=scope(m);let d=Object.keys(AI[m.id]).length;
  for(let k=0;k<(m.run.fast?8:1)&&d<sc.length;k++,d++){const r=sc[d];AI[m.id][r.id]={...evaluate(m,r),ok:false}}
  changed=changed||m;
  if(d>=sc.length){m.run={state:'done',minutes:m.run.fast?Math.round(sc.length*2.6/60):9};m.edited='adesso';toast(`pre-valutazione di ${m.name} completata: ora puoi rivedere gli esiti`)}});
 if(!changed||S.screen!=='app'||S.wiz)return;
 if(S.tab==='fascicoli'||(S.tab==='fascicolo'&&S.ftab!=='requisiti'))render()},500);
render();
