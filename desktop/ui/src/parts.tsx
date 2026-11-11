import {useEffect, useRef, useState} from 'react';
import {AlertTriangle, ChevronLeft, ChevronRight, Cpu, FileText, Loader2, Trash2, Upload, X} from 'lucide-react';
import {Answer, api, CatalogData, DossierRow, Fascicolo, Summary} from './api';

/** Barra dello score: pieno = score, chiaro = recuperabile con le evidenze, rosato = gap tecnici. */
export function ScoreBar({s}: {s: Pick<Summary, 'score' | 'max'>}) {
  return <div className="range" title={`Score ${Math.round(s.score * 100)}% · massimo ${Math.round(s.max * 100)}%`}>
    <i className="mx" style={{width: s.max * 100 + '%'}}/><i className="sc" style={{width: s.score * 100 + '%'}}/></div>;
}

export function Drop({onFiles, compact}: {onFiles: (f: File[]) => void; compact?: boolean}) {
  const [over, setOver] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  return <div className={'drop ' + (over ? 'over' : '')} role="button" tabIndex={0} onClick={() => input.current?.click()} onKeyDown={e => e.key === 'Enter' && input.current?.click()}
    onDragOver={e => { e.preventDefault(); setOver(true); }} onDragLeave={() => setOver(false)}
    onDrop={e => { e.preventDefault(); setOver(false); onFiles([...e.dataTransfer.files]); }}>
    <Upload size={compact ? 18 : 22}/><div style={{marginTop: 6}}><b>Trascina qui i file</b> o fai clic per sceglierli</div>
    <div className="tiny mute">PDF con testo, DOCX, TXT, MD. Le scansioni senza testo non sono ancora lette.</div>
    <input ref={input} type="file" multiple accept=".pdf,.docx,.txt,.md" hidden onChange={e => { onFiles([...(e.target.files || [])]); e.target.value = ''; }}/>
  </div>;
}

let catalogCache: Promise<CatalogData> | null = null;
export const catalog = () => (catalogCache ??= api.catalog());

export function scopeCount(cat: CatalogData, values: Record<string, boolean>) {
  const tags = ['generale', values.quasi_macchina ? 'quasi_macchina' : 'macchina', ...Object.keys(values).filter(k => k !== 'quasi_macchina' && values[k])];
  const sc = cat.requirements.filter(r => (r.always && (r.law === 'MR' || tags.includes('cra'))) || r.tags.some(t => tags.includes(t)));
  return {total: sc.length, mr: sc.filter(r => r.law === 'MR').length, cra: sc.filter(r => r.law === 'CRA').length};
}

/** Profilo proposto dall'AI: ogni domanda con la frase che lo giustifica; le risposte non determinate sono evidenziate. */
export function ProfileEditor({answers, values, setValues}: {answers: Answer[]; values: Record<string, boolean>; setValues: (v: Record<string, boolean>) => void}) {
  const [cat, setCat] = useState<CatalogData | null>(null);
  useEffect(() => { void catalog().then(setCat); }, []);
  if (!cat) return <p className="mute">Caricamento…</p>;
  return <div>{cat.questions.map(q => {
    const a = answers.find(x => x.tag === q.tag), unsure = !a || a.verdict === 'UNKNOWN', v = !!values[q.tag];
    return <div className={'q ' + (unsure ? 'unsure' : '')} key={q.tag}>
      <div className="grow"><b className="small">{q.label}</b>
        <div className="tiny mute">{a?.quote ? <>«{a.quote}» — {a.doc}{a.page && a.page > 1 ? ', p. ' + a.page : ''}</> : q.hint}</div>
        <div className="tiny" style={{color: unsure ? 'var(--part)' : 'var(--mute)'}}>{unsure ? 'L\'AI non ha trovato elementi: verifica tu' : a!.verdict === 'TRUE' ? 'Proposta AI: sì' : 'Proposta AI: no'}</div></div>
      <div className="yn"><button className={v ? 'on' : ''} onClick={() => setValues({...values, [q.tag]: true})}>Sì</button><button className={!v ? 'on' : ''} onClick={() => setValues({...values, [q.tag]: false})}>No</button></div>
    </div>;
  })}</div>;
}

export const valuesFrom = (f: Fascicolo, answers?: Answer[]) => Object.fromEntries((answers || f.profile).map(a => [a.tag, a.value ?? a.verdict === 'TRUE']));

/** Procedura guidata: macchina e documenti → profilo proposto dall'AI → avvio della pre-valutazione. */
export function Wizard({close, done}: {close: () => void; done: (id: string) => void}) {
  const [step, setStep] = useState(0);
  const [d, setD] = useState({name: '', maker: '', model: '', serial: '', year: '', use: ''});
  const [files, setFiles] = useState<File[]>([]);
  const [id, setId] = useState<string | null>(null);
  const [answers, setAnswers] = useState<Answer[]>([]);
  const [values, setValues] = useState<Record<string, boolean>>({});
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [cat, setCat] = useState<CatalogData | null>(null);
  useEffect(() => { void catalog().then(setCat); }, []);

  async function next() {
    setError('');
    try {
      if (step === 0) {
        if (!d.name.trim()) throw new Error('Inserisci il nome della macchina');
        if (!files.length) throw new Error('Carica almeno un documento');
        setBusy('Salvataggio dei documenti…');
        const f = id ? await api.update(id, d) : await api.create(d);
        setId(f.id);
        await api.upload(f.id, files);
        setBusy('L\'AI legge i documenti e propone il profilo della macchina…');
        const a = await api.propose(f.id);
        setAnswers(a); setValues(Object.fromEntries(a.map(x => [x.tag, x.verdict === 'TRUE'])));
        setStep(1);
      } else if (step === 1) {
        setBusy('Salvataggio del profilo…');
        await api.confirmProfile(id!, values);
        setStep(2);
      } else {
        setBusy('Avvio…');
        await api.run(id!);
        done(id!);
      }
    } catch (e) { setError((e as Error).message); } finally { setBusy(''); }
  }

  const n = cat ? scopeCount(cat, values) : null;
  const field = (k: keyof typeof d, label: string, ph = '') => <label className="f">{label}<input value={d[k]} placeholder={ph} onChange={e => setD({...d, [k]: e.target.value})}/></label>;
  return <div className="ov"><div className="modal" role="dialog" aria-modal="true" aria-label="Nuova macchina">
    <div className="mh"><div className="grow"><span className="eyebrow">Nuova macchina · passo {step + 1} di 3</span><h2>{['Macchina e documenti', 'Profilo proposto dall\'AI', 'Avvio della pre-valutazione'][step]}</h2></div>
      <div className="wsteps">{[0, 1, 2].map(i => <span key={i} className={i <= step ? 'on' : ''}/>)}</div><button className="iconbtn" onClick={close} aria-label="Chiudi"><X size={16}/></button></div>
    <div className="mb">
      {busy ? <div className="empty"><Loader2 size={26} className="spinning"/><p style={{marginTop: 10}}>{busy}</p><p className="tiny mute">Con documenti lunghi può servire qualche minuto.</p></div>
      : step === 0 ? <div className="stack">
        <div className="grid2" style={{gridTemplateColumns: '1fr 1fr'}}>{field('name', 'Nome della macchina *', 'es. Pressa piegatrice PB-80')}{field('maker', 'Fabbricante')}{field('model', 'Modello')}{field('serial', 'Matricola')}{field('year', 'Anno')}</div>
        <label className="f">Uso previsto<textarea value={d.use} onChange={e => setD({...d, use: e.target.value})} placeholder="Cosa fa la macchina, chi la usa, dove"/></label>
        <Drop onFiles={f => setFiles([...files, ...f])} compact/>
        {files.map((f, i) => <div className="row small" key={i}><FileText size={14}/><span className="grow">{f.name}</span><span className="tiny mute">{(f.size / 1e6).toFixed(1)} MB</span>
          <button className="iconbtn" aria-label="Rimuovi" onClick={() => setFiles(files.filter((_, j) => j !== i))}><X size={13}/></button></div>)}
      </div>
      : step === 1 ? <><p className="small mute" style={{marginBottom: 10}}>L'AI ha letto i documenti e propone il profilo. Controlla soprattutto le risposte <b style={{color: 'var(--part)'}}>evidenziate</b>.</p>
        <ProfileEditor answers={answers} values={values} setValues={setValues}/></>
      : <div><div style={{textAlign: 'center', padding: '10px 0'}}><div className="eyebrow">Perimetro</div><div className="count">{n?.total} requisiti</div>
          <div className="small mute">{n?.mr} Regolamento Macchine · {n?.cra} Cyber Resilience Act</div></div>
        <div className="card" style={{marginTop: 12}}><div className="row"><Cpu size={16} color="var(--brand)"/><div className="grow"><b>Pre-valutazione AI</b>
          <div className="tiny mute">circa {Math.max(1, Math.round((n?.total || 0) * 1.6 / 60))}–{Math.max(2, Math.round((n?.total || 0) * 3 / 60))} minuti · prosegue in sottofondo, puoi rivedere i primi esiti subito</div></div></div></div></div>}
      {error && <div className="err" style={{marginTop: 12}}><AlertTriangle size={14}/>{error}</div>}
    </div>
    <div className="mf">{step > 0 && !busy && <button className="btn" onClick={() => setStep(step - 1)}><ChevronLeft size={14}/>Indietro</button>}<span className="grow"/>
      {!busy && <button className="btn pri" onClick={next}>{['Proponi il profilo', 'Conferma il profilo', 'Avvia la pre-valutazione'][step]} <ChevronRight size={14}/></button>}</div>
  </div></div>;
}

export function DeleteModal({row, cancel, confirm}: {row: DossierRow; cancel: () => void; confirm: () => Promise<void>}) {
  const [name, setName] = useState(''); const [error, setError] = useState('');
  const f = row.fascicolo;
  return <div className="ov"><div className="modal" role="alertdialog" aria-modal="true" aria-label="Elimina fascicolo" style={{width: 'min(520px,100%)'}}>
    <div className="mh"><Trash2 size={18} color="var(--nc)"/><h2 className="grow">Eliminare il fascicolo?</h2><button className="iconbtn" onClick={cancel} aria-label="Chiudi"><X size={16}/></button></div>
    <div className="mb stack"><p className="small">Verranno eliminati da questo computer:</p>
      <ul className="small" style={{margin: 0, paddingLeft: 18, lineHeight: 1.8}}><li><b>{f.name}</b> e i dati della macchina</li><li>{row.documents} documenti caricati e il loro indice</li><li>{row.summary.done} esiti della pre-valutazione e le revisioni</li><li>i report archiviati nel fascicolo</li></ul>
      <label className="f">Per confermare scrivi il nome della macchina: <b>{f.name}</b><input value={name} onChange={e => setName(e.target.value)} autoComplete="off"/></label>
      {error && <div className="err">{error}</div>}</div>
    <div className="mf"><span className="grow"/><button className="btn" onClick={cancel}>Annulla</button>
      <button className="btn danger pri" disabled={name.trim() !== f.name} onClick={() => confirm().catch(e => setError(e.message))}><Trash2 size={14}/>Elimina definitivamente</button></div>
  </div></div>;
}
