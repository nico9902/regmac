import {reasonLabel} from './api';
import {useCallback, useEffect, useMemo, useState} from 'react';
import {AlertTriangle, Check, ChevronLeft, ChevronRight, Cpu, Download, ExternalLink, FileText, Pause, Play, Search, Trash2, Upload} from 'lucide-react';
import {ANS, api, CatalogData, DossierFull, MOTIVE, OUT, pct, ReqView, running} from './api';
import type {Toast} from './App';
import {catalog, DeleteModal, Drop, profileComplete, ProfileEditor, ScoreBar, valuesFrom} from './parts';

type Tab = 'panoramica' | 'documenti' | 'requisiti' | 'gap' | 'report';

export default function Dossier({id, initialTab, back, toast}: {id: string; initialTab?: string; back: () => void; toast: Toast}) {
  const [d, setD] = useState<DossierFull | null>(null);
  const [tab, setTab] = useState<Tab>((initialTab as Tab) || 'panoramica');
  const [sel, setSel] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [del, setDel] = useState(false);

  const load = useCallback(() => api.get(id).then(x => { setD(x); setError(''); }).catch(e => setError(e.message)), [id]);
  useEffect(() => { void load(); }, [load]);
  const isRunning = d ? running(d.fascicolo.run) : false;
  useEffect(() => { if (!isRunning) return; const t = window.setInterval(load, 2000); return () => window.clearInterval(t); }, [isRunning, load]);

  if (!d) return <div className="page">{error ? <div className="err">{error}</div> : <p className="mute">Caricamento…</p>}</div>;
  const f = d.fascicolo, s = d.assessment.summary, gaps = d.assessment.gaps;
  const t = (k: Tab, label: string, n?: string | number, cls?: string) =>
    <button className={'ftab ' + (tab === k ? 'on' : '')} onClick={() => setTab(k)}>{label}{n !== undefined && <span className={'n ' + (cls || '')}>{n}</span>}</button>;
  const goto = (rid: string) => { setSel(rid); setTab('requisiti'); };

  return <div style={{display: 'flex', flexDirection: 'column', height: '100%'}}>
    <div className="fhead">
      <div className="crumb"><button onClick={back}>Fascicoli</button>›<span>{f.name}</span></div>
      <div className="row" style={{marginTop: 6}}><h1>{f.name}</h1>
        {isRunning ? <span className="chip brand"><span className="spin"><Cpu size={12}/></span>Pre-valutazione {f.run.done}/{f.run.total}</span>
          : s.done === 0 ? null : s.notEvaluable ? <span className="chip part">Non valutabile · documentazione insufficiente</span> : <span className="chip">Score {pct(s.score)}</span>}
        <span className="grow"/>
        <button className="btn danger" onClick={() => setDel(true)}><Trash2 size={14}/>Elimina</button></div>
      <div className="ftabs">{t('panoramica', 'Panoramica')}{t('documenti', 'Documenti', d.documents.length)}{t('requisiti', 'Requisiti', s.total ? `${s.confirmed}/${s.done}` : undefined)}
        {t('gap', 'Gap', gaps.length, 'nc')}{t('report', 'Report')}</div>
    </div>
    {error && <div className="err" style={{margin: '10px 28px'}}>{error}</div>}
    <div style={{flex: 1, minHeight: 0, overflow: tab === 'requisiti' ? 'hidden' : 'auto'}}>
      {tab === 'panoramica' && <Overview d={d} reload={load} setTab={setTab} goto={goto} toast={toast}/>}
      {tab === 'documenti' && <Documents d={d} reload={load} toast={toast}/>}
      {tab === 'requisiti' && <Requirements d={d} setD={setD} sel={sel} setSel={setSel}/>}
      {tab === 'gap' && <Gaps d={d} goto={goto}/>}
      {tab === 'report' && <Report d={d} reload={load} goto={() => setTab('requisiti')} toast={toast}/>}
    </div>
    {del && <DeleteModal row={{fascicolo: f, summary: s, documents: d.documents.length}} cancel={() => setDel(false)} confirm={async () => {
      await api.remove(f.id); back(); toast(`Fascicolo «${f.name}» eliminato`, {label: 'Annulla', run: () => { void api.restore(f.id); }});
    }}/>}
  </div>;
}

function RunBox({d, reload, toast}: {d: DossierFull; reload: () => void; toast: Toast}) {
  const r = d.fascicolo.run, run = running(r), frac = r.total ? r.done / r.total : 0;
  const elapsed = r.startedAt ? (Date.now() - new Date(r.startedAt).getTime()) / 1000 : 0;
  const eta = run && r.done > 5 ? Math.max(1, Math.round(elapsed / r.done * (r.total - r.done) / 60)) : null;
  const act = (p: Promise<unknown>) => p.then(reload).catch(e => toast(e.message));
  if (run) return <div className="card run"><div className="row"><span className="spin"><Cpu size={16}/></span>
    <b className="grow">{r.state === 'indexing' ? 'Lettura e indicizzazione dei documenti…' : r.state === 'queued' ? 'In coda…' : `Pre-valutazione in corso — ${r.done} di ${r.total} requisiti`}</b>
    <span className="tiny mute">{eta ? `circa ${eta} min · ` : ''}{r.model}</span><button className="btn sm" onClick={() => act(api.stop(d.fascicolo.id))}><Pause size={13}/>Interrompi</button></div>
    <div className="bar"><i className="fill brand" style={{width: frac * 100 + '%'}}/></div>
    <p className="tiny mute" style={{marginTop: 6}}>Puoi già rivedere i requisiti valutati. Se chiudi RegMac, la potrai riprendere.</p></div>;
  if (['stopped', 'interrupted', 'error'].includes(r.state)) return <div className="card run row" style={{borderColor: 'var(--part)'}}><AlertTriangle size={16} color="var(--part)"/>
    <div className="grow"><b>{r.state === 'error' ? 'La pre-valutazione si è fermata per un errore' : 'Pre-valutazione interrotta'}</b><div className="tiny mute">{r.message} · {r.done} di {r.total} requisiti</div></div>
    <button className="btn sm" onClick={() => window.confirm('Rianalizzare da zero, senza riusare i giudizi in memoria?') && act(api.run(d.fascicolo.id, true))}>Da zero</button>
    <button className="btn pri sm" onClick={() => act(api.run(d.fascicolo.id))}><Play size={13}/>Riprendi</button></div>;
  return null;
}

function Overview({d, reload, setTab, goto, toast}: {d: DossierFull; reload: () => void; setTab: (t: Tab) => void; goto: (rid: string) => void; toast: Toast}) {
  const f = d.fascicolo, s = d.assessment.summary, g = d.assessment.gaps, run = running(f.run);
  const [values, setValues] = useState(valuesFrom(f));
  const [cat, setCat] = useState<CatalogData | null>(null);
  useEffect(() => { void catalog().then(setCat); }, []);
  // profili confermati con la vecchia risposta "Non so": vanno completati con Sì o No
  const incomplete = f.profileConfirmed && f.profile.some(a => a.value !== true && a.value !== false);
  const [editing, setEditing] = useState(!f.profileConfirmed || incomplete);
  const [busy, setBusy] = useState(false);
  async function propose() { setBusy(true); try { const a = await api.propose(f.id); setValues(Object.fromEntries(a.map(x => [x.tag, x.verdict === 'UNKNOWN' ? null : x.verdict === 'TRUE']))); await reload(); } catch (e) { toast((e as Error).message); } finally { setBusy(false); } }
  async function confirm() { try { await api.confirmProfile(f.id, values); setEditing(false); await reload(); } catch (e) { toast((e as Error).message); } }
  async function start() { try { await api.run(f.id); await reload(); } catch (e) { toast((e as Error).message); } }
  async function fresh() {
    if (!window.confirm('Rianalizzare da zero? L\'AI rivaluta tutti i requisiti senza riusare i giudizi in memoria: richiede il tempo di una prima analisi. Gli esiti attuali restano archiviati.')) return;
    try { await api.run(f.id, true); await reload(); } catch (e) { toast((e as Error).message); }
  }

  const steps: [string, string, boolean][] = [
    ['Documenti caricati e indicizzati', `${d.documents.length} documenti · ${d.documents.reduce((a, x) => a + x.passages, 0)} brani`, d.documents.length > 0],
    ['Profilo proposto dall\'AI e confermato', f.profileConfirmed ? `${s.total} requisiti nel perimetro` : 'da confermare', f.profileConfirmed],
    ['Pre-valutazione AI', run ? `${f.run.done} di ${f.run.total}…` : f.run.state === 'done' ? `completata${f.run.startedAt && f.run.finishedAt ? ' in ' + Math.max(1, Math.round((new Date(f.run.finishedAt).getTime() - new Date(f.run.startedAt).getTime()) / 60000)) + ' min' : ''} · ${f.run.model}` : 'da avviare', f.run.state === 'done'],
    ['Revisione delle proposte', `${s.confirmed} di ${s.done} confermate`, s.done > 0 && s.confirmed === s.done],
    ['Report di pre-assessment', f.exportedAt ? 'esportato il ' + new Date(f.exportedAt).toLocaleDateString('it-IT') : 'non ancora esportato', !!f.exportedAt]];
  const cur = steps.findIndex(x => !x[2]);
  const action = [() => setTab('documenti'), () => setEditing(true), start, () => setTab('requisiti'), () => setTab('report')];

  return <div className="page"><RunBox d={d} reload={reload} toast={toast}/>
    {editing ? <div className="card"><div className="row"><h2 className="grow">Profilo della macchina</h2>
        <button className="btn sm" disabled={busy || !d.documents.length} onClick={propose}>{busy ? 'L\'AI sta leggendo…' : f.profile.length ? 'Riproponi con l\'AI' : 'Proponi con l\'AI'}</button></div>
      <p className="tiny mute" style={{margin: '4px 0 8px'}}>Il profilo decide quali requisiti entrano nella pre-valutazione: rispondi Sì o No a ogni domanda.{incomplete && <b style={{color: 'var(--part)'}}> Il profilo salvato contiene risposte «Non so», non più ammesse: completalo.</b>}</p>
      <ProfileEditor answers={f.profile} values={values} setValues={setValues}/>
      <div className="row" style={{marginTop: 10}}><span className="grow"/>{f.profileConfirmed && <button className="btn" onClick={() => setEditing(false)}>Annulla</button>}
        <button className="btn pri" disabled={!profileComplete(cat, values)} onClick={confirm}>Conferma il profilo</button></div></div>
    : <div className="grid2" style={{gridTemplateColumns: '1.1fr 1fr'}}>
      <div className="card"><span className="eyebrow">Compliance score</span>
        {s.done === 0 ? <p className="mute" style={{marginTop: 10}}>Ancora nessun esito.</p>
        : s.notEvaluable && !run ? <><div className="row" style={{margin: '8px 0 10px'}}><span className="chip part" style={{fontSize: 14, padding: '6px 10px'}}><AlertTriangle size={15}/>Non valutabile</span></div>
          <p className="small">Il sistema ha riconosciuto evidenze per il <b>{pct(s.coverage)}</b> dei punti di controllo (soglia 30%). Uno score calcolato su così poche evidenze sarebbe fuorviante.</p>
          <p className="small mute" style={{marginTop: 8}}>Verifica il profilo, i documenti non letti e le evidenze recuperate prima di concludere che la documentazione sia insufficiente.</p>
          <div className="row" style={{marginTop: 12}}><button className="btn pri sm" onClick={() => setTab('documenti')}><Upload size={13}/>Carica documenti</button></div></>
        : <><div className="row" style={{alignItems: 'flex-end', gap: 16, margin: '4px 0 10px'}}><div className="score">{pct(s.score)}</div>
            <div className="small mute" style={{paddingBottom: 9}}>{run && 'provvisorio · '}può arrivare fino a <b>{pct(s.max)}</b> con le evidenze mancanti<br/>copertura evidenze <b>{pct(s.coverage)}</b></div></div>
          <ScoreBar s={s}/><div className="row tiny mute" style={{marginTop: 6, gap: 14}}><span><i className="lg sc"/>score</span><span><i className="lg mx"/>recuperabile con le evidenze</span><span><i className="lg rest"/>possibili non conformità</span></div>
          <div className="grid2" style={{marginTop: 14, gap: 8, gridTemplateColumns: '1fr 1fr'}}>{([['Pienamente soddisfatti', s.full, 'ok'], ['Possibili non conformità', s.gapsTech, 'nc'], ['Con documento assente', s.gapsMissing, 'mute'], ['Con evidenza mancante', s.gapsEvidence, 'mute']] as const).map(([l, v, c]) =>
            <div className="mini" key={l}><span className="tiny mute">{l}</span><b style={{color: `var(--${c})`}}>{v}</b></div>)}</div>
          <p className="tiny mute" style={{marginTop: 10}}>{s.applicable} requisiti applicabili · {s.na} non applicabili o da confermare{s.integrator > 0 && ` · ${s.integrator} punti a carico dell'integratore, esclusi dallo score`} · score = media sui requisiti applicabili della quota di punti di controllo supportati</p></>}
      </div>
      <div className="card"><h2>Percorso</h2><div className="steps" style={{marginTop: 6}}>{steps.map((x, i) =>
        <div className="step" key={x[0]}><div className={'sn ' + (x[2] ? 'done' : i === cur ? 'cur' : '')}>{x[2] ? <Check size={13}/> : i + 1}</div>
          <div className="grow"><b>{x[0]}</b><div className="tiny mute">{x[1]}</div></div>
          {i === cur && !run && <button className="btn pri sm" onClick={action[i]}>{['Carica', 'Conferma', 'Avvia', 'Rivedi', 'Apri'][i]}</button>}
          {i === 2 && x[2] && !run && <><button className="btn sm" onClick={start} title="Rivaluta: i requisiti non cambiati restano come sono">Aggiorna</button>
            <button className="btn sm" onClick={fresh} title="Rivaluta tutto senza memoria dei giudizi">Da zero</button></>}</div>)}</div></div>
    </div>}
    {d.documents.some(x => x.error) && <div className="card small" style={{marginTop: 14, color: 'var(--nc)'}}>Analisi parziale: {d.documents.filter(x => x.error).map(x => x.name).join(', ')} non analizzati. Consulta la scheda Documenti.</div>}
    {!editing && <div className="grid2" style={{gridTemplateColumns: '1.1fr 1fr', marginTop: 16}}>
      <div className="card"><div className="row"><h2 className="grow">Gap principali</h2><button className="btn ghost sm" onClick={() => setTab('gap')}>Tutti i {g.length}</button></div>
        {g.filter(x => x.priority < 4).slice(0, 5).map(x => <div className="block" key={x.rid}><span className={'chip ' + (MOTIVE[x.kind] || 'und')}>{x.kind}</span>
          <div className="grow"><button className="linkbtn" onClick={() => goto(x.rid)}><b>{x.ref}</b> {x.title}</button><div className="tiny mute">{x.points[0]?.text}{x.points[0] && <> · <b>atteso:</b> {x.points[0].expectedLabel}</>}</div></div></div>)}
        {!g.length && <p className="mute small">Nessun gap.</p>}</div>
      <div className="card"><div className="row"><h2 className="grow">Macchina</h2><button className="btn ghost sm" onClick={() => setEditing(true)}>Modifica profilo</button></div>
        <dl className="kv" style={{marginTop: 10}}><dt>Modello</dt><dd>{[f.model, f.serial && 'matr. ' + f.serial, f.year].filter(Boolean).join(' · ') || '—'}</dd><dt>Fabbricante</dt><dd>{f.maker || '—'}</dd>
          <dt>Uso previsto</dt><dd>{f.use || '—'}</dd><dt>Profilo</dt><dd>{f.tags.filter(x => x !== 'generale').map(x => ({macchina: 'Macchina completa', quasi_macchina: 'Quasi-macchina', cra: 'CRA', mobilita: 'Mobilità', sollevamento: 'Sollevamento', sollevamento_persone: 'Sollevamento persone', portatili: 'Portatile', alimentari: 'Alimentari', legno: 'Legno', fitosanitari: 'Fitosanitari', sotterraneo: 'Sotterraneo'} as Record<string, string>)[x] || x).join(' · ')}</dd></dl></div>
    </div>}
  </div>;
}

function Documents({d, reload, toast}: {d: DossierFull; reload: () => void; toast: Toast}) {
  const [busy, setBusy] = useState(false);
  const [cat, setCat] = useState<CatalogData | null>(null);
  useEffect(() => { void catalog().then(setCat); }, []);
  const toggle = (docId: string, cur: string[], t: string) => api.setDocTypes(d.fascicolo.id, docId, cur.includes(t) ? cur.filter(x => x !== t) : [...cur, t]).then(reload).catch(e => toast(e.message));
  const [pending, setPending] = useState<string | null>(null);
  async function add(files: File[]) {
    if (!files.length) return; setBusy(true);
    try { const docs = await api.upload(d.fascicolo.id, files); await reload(); const failed = docs.filter(x => x.error); toast(failed.length ? 'Non analizzati: ' + failed.map(x => x.name).join(', ') + '. Gli altri documenti sono disponibili.' : 'Caricamento completato'); } catch (e) { toast((e as Error).message); } finally { setBusy(false); }
  }
  const run = running(d.fascicolo.run);
  return <div className="page">{busy ? <div className="card empty">Caricamento e lettura dei documenti…</div> : <Drop onFiles={add}/>}
    <div className="card" style={{marginTop: 14, padding: '6px 8px'}}><table><thead><tr><th>Documento e tipo</th><th>Pagine</th><th>Brani letti</th><th>Impronta SHA-256</th><th/></tr></thead><tbody>
      {d.documents.map(x => <tr key={x.id}><td><button className="linkbtn row" title="Apri con l'applicazione predefinita" onClick={() => api.openDoc(d.fascicolo.id, x.id).catch(e => toast(e.message))}><FileText size={15}/><b>{x.name}</b></button>
        {x.error && <div className="tiny" style={{color: 'var(--nc)'}}>{x.error}</div>}
        {x.warning && <div className="tiny" style={{color: 'var(--part)'}}><AlertTriangle size={11}/> {x.warning}</div>}
        {!x.error && cat && <div className="types">{Object.entries(cat.docTypes).map(([k, label]) => { const cur = x.types || []; return <button key={k} title={label} disabled={run}
          className={'tchip ' + (cur.includes(k) ? 'on' : '')} onClick={() => void toggle(x.id, cur, k)}>{label.split(' (')[0].split(' /')[0]}</button>; })}</div>}
        {!x.error && x.types == null && <div className="tiny mute">Tipo proposto alla prossima analisi</div>}</td><td>{x.pages}</td>
        <td>{x.passages ? <span className="chip ok">{x.passages}</span> : <span className="chip part">0</span>}</td><td><code className="tiny mute" title={x.sha256}>{x.sha256.slice(0, 8)}…{x.sha256.slice(-6)}</code></td>
        <td>{pending === x.id ? <span className="row" style={{gap: 6}}><button className="btn danger pri sm" onClick={() => { setPending(null); void api.removeDoc(d.fascicolo.id, x.id).then(reload); }}>Rimuovi</button><button className="btn sm" onClick={() => setPending(null)}>Annulla</button></span>
          : <button className="iconbtn" aria-label="Rimuovi documento" disabled={run} onClick={() => setPending(x.id)}><Trash2 size={14}/></button>}</td></tr>)}
      {!d.documents.length && <tr><td colSpan={5} className="mute">Nessun documento.</td></tr>}</tbody></table></div>
    <p className="tiny mute" style={{marginTop: 8}}>Il tipo di documento è proposto automaticamente: correggilo se serve. Ogni punto di controllo cerca le evidenze solo nei documenti del tipo atteso; se nessun documento ha quel tipo, il punto diventa un gap «Documento assente».</p>
    {d.fascicolo.run.state !== 'idle' && !run && <div className="card row" style={{marginTop: 14, background: 'var(--brand3)'}}><div className="grow"><b>Hai aggiunto o rimosso documenti?</b>
      <div className="tiny mute">Aggiorna la pre-valutazione: i giudizi già fatti restano in memoria, si ricalcola solo ciò che cambia.</div></div>
      <button className="btn pri" onClick={() => api.run(d.fascicolo.id).then(reload).catch(e => toast(e.message))}><Play size={13}/>Aggiorna la pre-valutazione</button></div>}
  </div>;
}

function Requirements({d, setD, sel, setSel}: {d: DossierFull; setD: (x: DossierFull) => void; sel: string | null; setSel: (s: string) => void}) {
  const [filter, setFilter] = useState<'all' | 'todo' | 'gap' | 'na'>('all');
  const [q, setQ] = useState('');
  const [showLegal, setShowLegal] = useState(false);
  const [note, setNote] = useState('');
  const reqs = d.assessment.requirements;
  const shown = useMemo(() => reqs.filter(r => {
    if (filter === 'todo' && (!r.result || r.result.confirmed)) return false;
    if (filter === 'gap' && !['NC', 'PART', 'INS'].includes(r.outcome || '')) return false;
    if (filter === 'na' && r.outcome !== 'NA' && r.outcome !== 'INT') return false;
    return !q || (r.ref + ' ' + r.title).toLowerCase().includes(q.toLowerCase());
  }), [reqs, filter, q]);
  const cur = reqs.find(r => r.id === sel) || shown.find(r => r.result && !r.result.confirmed) || shown[0] || reqs[0];
  useEffect(() => { setNote(cur?.result?.note || ''); }, [cur?.id, cur?.result?.note]);
  const idx = reqs.findIndex(r => r.id === cur?.id);
  const move = (k: number) => { const n = reqs[Math.max(0, Math.min(reqs.length - 1, idx + k))]; if (n) setSel(n.id); };
  async function save(body: {points?: (string | null)[]; confirmed?: boolean; note?: string; applicability?: 'TRUE' | 'FALSE' | 'AUTO'}, advance = false) {
    const x = await api.review(d.fascicolo.id, cur.id, body); setD(x);
    if (advance) { const n = x.assessment.requirements.slice(idx + 1).find(r => r.result && !r.result.confirmed); if (n) setSel(n.id); }
  }
  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      if (/INPUT|TEXTAREA|SELECT/.test((document.activeElement as HTMLElement)?.tagName)) return;
      if (e.key === 'Enter' && cur?.result) { e.preventDefault(); void save({confirmed: true, note}, true); }
      if (e.key === 'j' || e.key === 'ArrowDown') { e.preventDefault(); move(1); }
      if (e.key === 'k' || e.key === 'ArrowUp') { e.preventDefault(); move(-1); }
    };
    window.addEventListener('keydown', h); return () => window.removeEventListener('keydown', h);
  });
  useEffect(() => { document.querySelector('.item.on')?.scrollIntoView({block: 'nearest'}); }, [cur?.id]);

  let lastLaw = '', lastGroup = '';
  const s = d.assessment.summary;
  return <div className="eval">
    <div className="side"><div className="top">
      <div className="row" style={{border: '1px solid var(--line)', borderRadius: 7, padding: '0 8px', gap: 6}}><Search size={14}/><input style={{border: 0, padding: '7px 0', background: 'none'}} placeholder="Cerca requisito" value={q} onChange={e => setQ(e.target.value)}/></div>
      <div className="filters">{([['all', 'Tutti'], ['todo', `Da confermare · ${s.done - s.confirmed}`], ['gap', 'Con gap'], ['na', 'N/A']] as const).map(([k, l]) =>
        <button key={k} className={filter === k ? 'on' : ''} onClick={() => setFilter(k)}>{l}</button>)}</div></div>
      <div className="tree">{shown.map(r => {
        const head: React.ReactNode[] = [];
        if (r.law !== lastLaw) { head.push(<div className="law" key={'l' + r.id}>{r.law === 'MR' ? 'Regolamento Macchine' : 'Cyber Resilience Act'}</div>); lastLaw = r.law; }
        if (r.group !== lastGroup) { head.push(<div className="ghead" key={'g' + r.id}>{r.group.replace(/^Allegato III — /, '').slice(0, 60)}</div>); lastGroup = r.group; }
        return [...head, <button key={r.id} className={'item ' + (cur?.id === r.id ? 'on' : '')} onClick={() => setSel(r.id)}>
          <span className={'dot ' + (r.outcome || 'Q')}/><span className="ref">{r.ref}</span><span className="grow">{r.title}</span>
          {r.result?.confirmed && <span className="ok-mark" title="Confermato"><Check size={12}/></span>}</button>];
      })}{!shown.length && <p className="mute small" style={{padding: 12}}>Nessun requisito con questi filtri.</p>}</div>
      <div className="prog"><div className="row"><span className="grow">{s.confirmed} di {s.done} confermati</span><span>{s.done ? Math.round(s.confirmed / s.done * 100) : 0}%</span></div>
        <div className="bar"><i className="fill brand" style={{width: (s.done ? s.confirmed / s.done * 100 : 0) + '%'}}/></div></div>
    </div>
    {cur && <div className="detail" style={{display: 'flex', flexDirection: 'column'}}><div style={{flex: 1}}><div className="dwrap">
      <div className="row tiny mute"><span>{cur.law === 'MR' ? 'Reg. (UE) 2023/1230' : 'Reg. (UE) 2024/2847'}</span>·<span>{cur.group}</span></div>
      <div className="row wrap" style={{margin: '6px 0 12px'}}><h1 style={{fontSize: 21}}><span className="mute" style={{fontWeight: 500}}>{cur.ref}</span> {cur.title}</h1>
        {cur.always && <span className="chip brand">Si applica in ogni caso</span>}{cur.sheet.status !== 'rivista' && <span className="chip" title="Scheda di verifica generata automaticamente, da rivedere">scheda in bozza</span>}</div>
      <Card r={cur} d={d} save={save} note={note} setNote={setNote}/>
      <details className="legalbox" open={showLegal}><summary onClick={e => { e.preventDefault(); setShowLegal(!showLegal); }}>Testo ufficiale</summary>
        <LegalText id={cur.id}/></details>
    </div></div>
    <div className="navbar"><button className="btn" onClick={() => move(-1)} aria-label="Precedente"><ChevronLeft size={15}/></button>
      <span className="tiny mute grow">{idx + 1} di {reqs.length} · <kbd>Invio</kbd> conferma · <kbd>J</kbd>/<kbd>K</kbd> scorri</span>
      {cur.result?.confirmed ? <><span className="chip ok"><Check size={12}/>Confermato</span><button className="btn pri" onClick={() => move(1)}>Successivo <ChevronRight size={14}/></button></>
        : <><button className="btn" onClick={() => move(1)}>Salta</button><button className="btn pri" disabled={!cur.result} onClick={() => void save({confirmed: true, note}, true)}><Check size={14}/>Conferma e avanti</button></>}</div>
    </div>}
  </div>;
}

function Card({r, d, save, note, setNote}: {r: ReqView; d: DossierFull; save: (b: {points?: (string | null)[]; confirmed?: boolean; note?: string; applicability?: 'TRUE' | 'FALSE' | 'AUTO'}) => Promise<void>; note: string; setNote: (s: string) => void}) {
  const x = r.result;
  const [cat, setCat] = useState<CatalogData | null>(null);
  useEffect(() => { void catalog().then(setCat); }, []);
  if (!x) return <div className="card empty"><span className="spin"><Cpu size={22}/></span><p style={{marginTop: 8}}>{running(d.fascicolo.run) ? 'Requisito in coda: l\'AI lo valuterà a breve.' : 'Requisito non ancora valutato.'}</p></div>;
  const app = x.appUser ?? x.applicability;
  // applicabilità: proposta dal programma (profilo o evidenze), decisione finale del revisore
  const appBar = (r.sheet.condition || x.appUser) ? <div className="row wrap small" style={{gap: 6, margin: '8px 0 4px'}}>
    <span className="grow"><b>Si applica se:</b> {r.sheet.condition || '—'}
      <span className="tiny mute"> · {x.appUser ? 'deciso dal revisore' : x.applicability === 'TRUE' ? (x.appQuote || 'confermato dalle evidenze trovate') : x.applicability === 'FALSE' ? 'escluso dal profilo' : 'da verificare: nessuna evidenza la conferma'}</span></span>
    <span className="yn"><button className={app === 'TRUE' ? 'on ok' : ''} onClick={() => void save({applicability: 'TRUE'})}>Si applica</button>
      <button className={app === 'FALSE' ? 'on na' : ''} onClick={() => void save({applicability: 'FALSE'})}>Non si applica</button>
      {x.appUser && <button onClick={() => void save({applicability: 'AUTO'})} title="Torna alla proposta del programma">Automatica</button>}</span></div> : null;
  if (app === 'FALSE') return <div className="card"><div className="row"><span className="eyebrow grow">Esito</span><span className="chip na">Non applicabile</span></div>{appBar}
    <p className="small mute" style={{marginTop: 6}}>Il requisito è escluso dallo score.</p></div>;
  const eff = x.points.map(p => p.user ?? p.answer);
  return <div className="card"><div className="row wrap"><span className="eyebrow grow">Esito dell'AI</span>
      {r.outcome && <span className={'chip ' + OUT[r.outcome][1]}>{OUT[r.outcome][0]}</span>}
      {r.value != null && <span className="chip brand" title="Contributo del requisito allo score">vale {pct(r.value)}{r.max! > r.value ? ' · max ' + pct(r.max!) : ''}</span>}</div>
    {appBar}
    {app === 'UNKNOWN' && <p className="tiny" style={{color: 'var(--part)', margin: '0 0 4px'}}>Applicabilità da verificare: i punti sono stati valutati, ma il requisito non entra nello score finché non indichi se si applica.</p>}
    {x.points.map((p, i) => <div className="pt" key={i}><div className="row" style={{alignItems: 'flex-start'}}><span className="pn">{i + 1}</span>
      <div className="grow"><div className="small"><b>{p.text}</b></div>
        {p.expected && <div className="tiny mute">Documento atteso: {cat?.docTypes[p.expected] || p.expected}</div>}
        {p.quote && eff[i] !== 'I' ? <><q>{p.quote}</q><div className="tiny mute"><FileText size={11}/> {p.docId ? <button className="linkbtn" style={{color: 'var(--brand)', fontSize: 11}} onClick={() => void api.openDoc(d.fascicolo.id, p.docId!)}>{p.doc}</button> : p.doc}{p.page && p.page > 1 ? ' · p. ' + p.page : ''}</div></>
          : p.user === 'I' ? <div className="tiny mute" style={{marginTop: 4}}>Segnato dal revisore come senza evidenze.</div> : null}
        {eff[i] === 'C' && <div className="tiny" style={{color: 'var(--nc)', marginTop: 4}}><AlertTriangle size={11}/> Possibile non conformità{p.user ? ' confermata dal revisore' : ': da confermare'}</div>}
        {p.answer === 'I' && !p.user && <div className="tiny mute" style={{marginTop: 4}}>{reasonLabel(p.reason)}{p.candidates ? ` · ${p.candidates} brani esaminati` : ''}</div>}
        {p.user && <div className="tiny" style={{color: 'var(--brand)', marginTop: 3}}>Corretto dal revisore (proposta AI: {ANS[p.answer][0].toLowerCase()})</div>}</div>
      <div className="yn">{(['S', 'C', 'I'] as const).map(k => <button key={k} className={eff[i] === k ? 'on ' + ANS[k][1] : ''}
        onClick={() => void save({points: eff.map((v, j) => j === i ? k : v)})}>{ANS[k][0]}</button>)}</div></div></div>)}
    <label className="f" style={{marginTop: 12}}><span>Nota del revisore <span className="tiny mute">(facoltativa, compare nel report)</span></span>
      <textarea style={{minHeight: 44}} value={note} onChange={e => setNote(e.target.value)} onBlur={() => note !== (x.note || '') && void save({note})}/></label></div>;
}

function LegalText({id}: {id: string}) {
  const [t, setT] = useState<{text: string; pages: number[]; law: string; standards: string[]} | null>(null);
  useEffect(() => { void import('./parts').then(m => m.catalog()).then(c => { const r = c.requirements.find(x => x.id === id); if (r) setT(r); }); }, [id]);
  if (!t) return null;
  const pdf = t.law === 'MR' ? 'Reg. (UE) 2023/1230' : 'Reg. (UE) 2024/2847';
  return <><div className="legal" style={{marginTop: 10}}>{t.text}</div>
    <div className="row wrap tiny" style={{marginTop: 8}}><button className="linkbtn row" style={{gap: 4, color: 'var(--brand)', fontSize: 11}} onClick={() => void api.openLaw(t.law)} title={pdf}><ExternalLink size={12}/>Gazzetta ufficiale: apri il PDF, pagina {t.pages?.[0]}</button>
      {t.standards.length > 0 && <span className="mute">· Norme: {t.standards.join(', ')}</span>}</div></>;
}

function Gaps({d, goto}: {d: DossierFull; goto: (rid: string) => void}) {
  const g = d.assessment.gaps;
  const [k, setK] = useState('all');
  const kinds = ['Possibile non conformità', 'Documento assente', 'Evidenza mancante', 'Applicabilità da verificare', 'A carico dell\'integratore'];
  // un requisito compare sotto ogni motivo dei suoi punti
  const list = g.filter(x => k === 'all' ? x.priority < 4 : x.points.some(p => p.motive === k));
  const cnt = (m: string) => g.filter(y => y.points.some(p => p.motive === m)).length;
  return <div className="page"><div className="row"><div className="grow"><h2>Registro dei gap</h2>
    <p className="tiny mute">Ogni punto indica il motivo e il documento atteso. <b>Possibile non conformità</b>: un documento smentisce il punto (da confermare). <b>Documento assente</b>: nel fascicolo manca il tipo di documento atteso. <b>Evidenza mancante</b>: il documento c'è ma non contiene la prova. <b>A carico dell'integratore</b>: per una quasi-macchina spetta a chi realizza la macchina finale (escluso dallo score).</p></div></div>
    <div className="filters" style={{margin: '12px 0'}}>{[['all', `Tutti · ${g.filter(x => x.priority < 4).length}`], ...kinds.map(m => [m, `${m} · ${cnt(m)}`])].map(([v, l]) =>
      <button key={v} className={k === v ? 'on' : ''} onClick={() => setK(v)}>{l}</button>)}</div>
    <div className="card" style={{padding: '6px 8px'}}><table><thead><tr><th style={{width: 230}}>Requisito</th><th>Punti, motivo e documento atteso</th></tr></thead><tbody>
      {list.map(x => <tr key={x.rid}><td><button className="linkbtn" onClick={() => goto(x.rid)}><b>{x.ref}</b> {x.title}</button>
        {x.priority < 3 && <div className="tiny mute" style={{marginTop: 4}}>requisito al {pct(x.value)}</div>}</td>
        <td>{x.points.filter(p => k === 'all' ? p.motive !== 'A carico dell\'integratore' : p.motive === k).map((p, i) => <div className="gp" key={i}>
          <span className={'chip ' + (MOTIVE[p.motive] || 'und')}>{p.motive}</span> {p.text}
          <div className="tiny mute">Documento atteso: <b>{p.expectedLabel}</b>{p.reviewed ? ' · rivisto' : ''}</div>
          {p.answer === 'C' && p.quote && <><q>{p.quote}</q><div className="tiny mute">{p.doc}{p.page && p.page > 1 ? ' · p. ' + p.page : ''}</div></>}</div>)}</td></tr>)}
      {!list.length && <tr><td colSpan={2} className="mute">Nessun gap.</td></tr>}</tbody></table></div></div>;
}

function Report({d, reload, goto, toast}: {d: DossierFull; reload: () => void; goto: () => void; toast: Toast}) {
  const s = d.assessment.summary, run = running(d.fascicolo.run);
  const [saving, setSaving] = useState(false);
  async function save() { setSaving(true); try { const r = await api.saveReport(d.fascicolo.id); toast(`Report salvato in Download: ${r.name}`); reload(); } catch (e) { toast((e as Error).message); } finally { setSaving(false); } }
  return <div className="page" style={{maxWidth: 820}}><div className="stack">
    <div className="card"><h2>Report di pre-assessment</h2>
      <div style={{marginTop: 8}}>
        <div className="block"><span style={{color: `var(--${s.done && s.confirmed === s.done ? 'ok' : 'part'})`}}>{s.done && s.confirmed === s.done ? <Check size={16}/> : <AlertTriangle size={16}/>}</span>
          <span className="grow">{s.done && s.confirmed === s.done ? 'Tutti gli esiti rivisti' : `${s.done - s.confirmed} esiti non ancora rivisti`}</span>{s.confirmed < s.done && <button className="btn sm" onClick={goto}>Rivedi</button>}</div>
        <div className="block"><span style={{color: 'var(--ok)'}}><Check size={16}/></span><span className="grow">Ogni esito positivo cita la frase del documento; le possibili non conformità sono in evidenza e da confermare</span></div>
        {s.notEvaluable && <div className="block"><span style={{color: 'var(--part)'}}><AlertTriangle size={16}/></span><span className="grow">Documentazione insufficiente: il report dirà «non valutabile» invece dello score</span></div>}
        {run && <div className="block"><span style={{color: 'var(--part)'}}><AlertTriangle size={16}/></span><span className="grow">Pre-valutazione ancora in corso: il report sarebbe parziale</span></div>}
      </div>
      <p className="tiny mute" style={{margin: '8px 0 12px'}}>Puoi esportare anche con esiti non rivisti: il report lo dichiara. Una copia resta archiviata nel fascicolo.</p>
      <button className="btn pri" disabled={saving} onClick={save}><Download size={14}/>{saving ? 'Creazione del report…' : 'Salva il report PDF'}</button></div>
    <div className="card"><h3>Documenti da richiedere</h3>{d.assessment.docsNeeded.length ? <ul className="small" style={{paddingLeft: 18, margin: '8px 0 0', lineHeight: 1.8}}>{d.assessment.docsNeeded.map(x =>
      <li key={x.label + x.missing + x.integrator}><b>{x.label}</b> — {x.integrator ? 'a carico dell\'integratore' : x.missing ? 'assente dal fascicolo' : 'presente, ma senza le evidenze richieste'} <span className="tiny mute">({x.points} punti, {x.requirements} requisiti)</span></li>)}</ul> : <p className="small mute">Nessuno.</p>}</div>
    <div className="card"><h3>Score per area</h3><table style={{marginTop: 8}}><tbody>{d.assessment.areas.map(a => <tr key={a.name}><td>{a.name} <span className="tiny mute">({a.n})</span></td><td style={{width: 60}}><b>{pct(a.score)}</b></td><td style={{width: 60}} className="mute">{pct(a.max)}</td><td style={{width: 180}}><ScoreBar s={a}/></td></tr>)}</tbody></table></div>
  </div></div>;
}
