import {useEffect, useMemo, useState} from 'react';
import {ExternalLink, Search} from 'lucide-react';
import {api, CatalogData} from './api';
import {catalog} from './parts';

/** Wiki dei due regolamenti: testo ufficiale, scheda di verifica usata dall'AI, norme di riferimento. */
export default function Normativa() {
  const [cat, setCat] = useState<CatalogData | null>(null);
  const [q, setQ] = useState('');
  const [sel, setSel] = useState('MR-III-1.2.4.3');
  useEffect(() => { void catalog().then(setCat); }, []);
  const list = useMemo(() => (cat?.requirements || []).filter(r => !q || (r.ref + ' ' + r.title + ' ' + r.text + ' ' + r.standards.join(' ')).toLowerCase().includes(q.toLowerCase())), [cat, q]);
  if (!cat) return <div className="page mute">Caricamento…</div>;
  const r = cat.requirements.find(x => x.id === sel) || cat.requirements[0];
  const pdf = r.law === 'MR' ? 'CELEX_32023R1230_IT_TXT.pdf' : 'OJ_L_202402847_IT_TXT.pdf';
  let lastLaw = '', lastGroup = '';
  return <div className="eval">
    <div className="side"><div className="top">
      <div className="row" style={{border: '1px solid var(--line)', borderRadius: 7, padding: '0 8px', gap: 6}}><Search size={14}/><input style={{border: 0, padding: '7px 0', background: 'none'}} placeholder="Cerca nei due regolamenti o per norma" value={q} onChange={e => setQ(e.target.value)}/></div>
      <div className="tiny mute">{list.length} requisiti valutabili · schede {cat.schede}</div></div>
      <div className="tree">{list.map(x => {
        const h: React.ReactNode[] = [];
        if (x.law !== lastLaw) { h.push(<div className="law" key={'l' + x.id}>{x.law === 'MR' ? 'Regolamento Macchine' : 'Cyber Resilience Act'}</div>); lastLaw = x.law; }
        if (x.group !== lastGroup) { h.push(<div className="ghead" key={'g' + x.id}>{x.group.replace(/^Allegato III — /, '').slice(0, 60)}</div>); lastGroup = x.group; }
        return [...h, <button key={x.id} className={'item ' + (x.id === r.id ? 'on' : '')} onClick={() => setSel(x.id)}><span className="ref">{x.shortRef}</span><span>{x.title}</span></button>];
      })}</div></div>
    <div className="detail"><div className="dwrap">
      <div className="row tiny mute"><span>{r.law === 'MR' ? 'Regolamento (UE) 2023/1230' : 'Regolamento (UE) 2024/2847'}</span>·<span>{r.group}</span></div>
      <h1 style={{fontSize: 22, margin: '6px 0 10px'}}><span className="mute" style={{fontWeight: 500}}>{r.shortRef}</span> {r.title}</h1>
      <div className="row wrap tiny" style={{marginBottom: 12}}><button className="linkbtn row" style={{gap: 4, color: 'var(--brand)', fontSize: 11}} onClick={() => void api.openLaw(r.law)} title={pdf}><ExternalLink size={12}/>Gazzetta ufficiale: apri il PDF, pagina {r.pages?.[0]}</button>
        {r.always && <span className="chip brand">Si applica in ogni caso</span>}</div>
      <div className="legal">{r.text}</div>
      <div className="section-t">Scheda di verifica <span className="chip" style={{textTransform: 'none', letterSpacing: 0}}>{r.sheet.status === 'rivista' ? 'rivista' : 'bozza generata, da rivedere'}</span></div>
      <div className="card">{r.sheet.condition && <p className="small"><b>Si applica se:</b> {r.sheet.condition}</p>}
        <ol className="small" style={{margin: r.sheet.condition ? '10px 0 0' : 0, paddingLeft: 20, lineHeight: 1.7}}>{r.sheet.points.map(p => <li key={p.q}>{p.q}{p.doc && <span className="tiny mute"> · cerca in: {p.doc.replace('_', ' ')}{p.quasi === 'integratore' ? ' · quasi-macchina: a carico dell\'integratore' : ''}</span>}</li>)}</ol></div>
      <div className="section-t">Norme di riferimento</div>
      {r.standards.length ? <div className="row wrap">{r.standards.map(s => <span className="chip" key={s}>{s}</span>)}</div> : <p className="small mute">Nessuna norma associata.</p>}
    </div></div>
  </div>;
}
