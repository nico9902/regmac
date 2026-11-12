import {useEffect, useState} from 'react';
import {AlertTriangle, Check, ChevronRight, Cpu, Plus, Trash2} from 'lucide-react';
import {api, DossierRow, pct, running, Summary} from './api';
import type {Toast} from './App';
import {DeleteModal, ScoreBar, Wizard} from './parts';

export default function Dossiers({open, toast}: {open: (id: string, sub?: string) => void; toast: Toast}) {
  const [rows, setRows] = useState<DossierRow[] | null>(null);
  const [q, setQ] = useState('');
  const [error, setError] = useState('');
  const [wizard, setWizard] = useState(false);
  const [del, setDel] = useState<DossierRow | null>(null);

  const load = () => api.list().then(setRows).catch(e => setError(e.message));
  useEffect(() => { void load(); }, []);
  const anyRunning = rows?.some(r => running(r.fascicolo.run));
  useEffect(() => { if (!anyRunning) return; const t = window.setInterval(load, 2500); return () => window.clearInterval(t); }, [anyRunning]);

  const shown = (rows || []).filter(r => !q || (r.fascicolo.name + ' ' + r.fascicolo.serial + ' ' + r.fascicolo.maker).toLowerCase().includes(q.toLowerCase()));

  async function remove(r: DossierRow) {
    await api.remove(r.fascicolo.id); setDel(null); await load();
    toast(`Fascicolo «${r.fascicolo.name}» eliminato`, {label: 'Annulla', run: () => { void api.restore(r.fascicolo.id).then(load); }});
  }

  return <div className="page">
    <div className="row"><div className="grow"><h1>Fascicoli</h1><p className="mute small">Una macchina, un fascicolo: documenti, pre-valutazione AI, gap e report.</p></div>
      <div style={{width: 260}}><input placeholder="Cerca macchina, matricola, fabbricante…" value={q} onChange={e => setQ(e.target.value)}/></div>
      <button className="btn pri" onClick={() => setWizard(true)}><Plus size={15}/>Nuova macchina</button></div>
    {error && <div className="err" style={{marginTop: 12}}>{error}</div>}
    {rows && rows.length === 0 && <div className="card empty" style={{marginTop: 16}}><Cpu size={28}/><h2 style={{margin: '8px 0'}}>Nessun fascicolo</h2>
      <p className="small">Crea il primo: carichi la documentazione della macchina e l'AI locale fa la pre-valutazione.</p>
      <button className="btn pri" style={{marginTop: 12}} onClick={() => setWizard(true)}><Plus size={15}/>Nuova macchina</button></div>}
    <div className="list">{shown.map(r => <Row key={r.fascicolo.id} r={r} open={() => open(r.fascicolo.id)} del={() => setDel(r)}/>)}</div>
    {wizard && <Wizard close={() => setWizard(false)} done={id => { setWizard(false); open(id, 'panoramica'); }}/>}
    {del && <DeleteModal row={del} cancel={() => setDel(null)} confirm={() => remove(del)}/>}
  </div>;
}

function Row({r, open, del}: {r: DossierRow; open: () => void; del: () => void}) {
  const f = r.fascicolo, s: Summary = r.summary, run = running(f.run);
  const state = run ? <span className="chip brand"><span className="spin"><Cpu size={12}/></span>Pre-valutazione {f.run.done}/{f.run.total}</span>
    : !f.profileConfirmed ? <span className="chip">Da completare</span>
    : f.run.state === 'idle' ? <span className="chip">Da avviare</span>
    : ['stopped', 'interrupted', 'error'].includes(f.run.state) ? <span className="chip part"><AlertTriangle size={12}/>{f.run.state === 'error' ? 'Errore' : 'Interrotta'}</span>
    : f.exportedAt ? <span className="chip ok"><Check size={12}/>Report esportato</span>
    : <span className="chip part">Da rivedere {s.confirmed}/{s.done}</span>;
  return <div className="mrow" role="button" tabIndex={0} onClick={open} onKeyDown={e => e.key === 'Enter' && open()}>
    <div className="micon"><Cpu size={20}/></div>
    <div><b style={{fontSize: 14}}>{f.name}</b><div className="tiny mute">{[f.maker, f.serial && 'matr. ' + f.serial, r.documents + ' documenti', new Date(f.updatedAt).toLocaleDateString('it-IT')].filter(Boolean).join(' · ')}</div></div>
    {s.done === 0 ? <div className="tiny mute">{run ? 'in elaborazione' : 'nessun esito'}</div>
      : s.notEvaluable && !run ? <div className="row" style={{gap: 10}}><span className="chip part"><AlertTriangle size={12}/>Non valutabile</span><span className="tiny mute">documentazione insufficiente · copertura {pct(s.coverage)}</span></div>
      : <div className="row" style={{gap: 14}}><div><div className="sc-mini">{pct(s.score)}</div><div className="tiny mute">{run ? 'provvisorio' : 'score'}</div></div>
        <div className="grow"><ScoreBar s={s}/><div className="tiny mute" style={{marginTop: 4}}>fino a {pct(s.max)} con le evidenze mancanti · {s.gapsTech + s.gapsDoc + s.gapsApp} gap{s.gapsTech ? ` · ${s.gapsTech} possibili non conformità` : ''}</div></div></div>}
    <div>{state}</div>
    <span className="rowdel" role="button" tabIndex={0} title="Elimina fascicolo" onClick={e => { e.stopPropagation(); del(); }}><Trash2 size={15}/></span>
    <ChevronRight size={16} style={{display: 'none'}}/>
  </div>;
}
