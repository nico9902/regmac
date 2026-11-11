import {useEffect, useState} from 'react';
import {AlertTriangle, Check, ChevronRight, Download, ExternalLink, Loader2} from 'lucide-react';
import {api, gb, Status} from './api';

/** Primo avvio: profilo del valutatore, poi controllo di Ollama e dei modelli con download e avanzamento. */
export default function Setup({status, reload}: {status: Status; reload: () => Promise<void>}) {
  const [step, setStep] = useState(status.profile ? 1 : 0);
  const [name, setName] = useState(status.profile?.name || '');
  const [org, setOrg] = useState(status.profile?.organization || '');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const pulling = status.models.some(m => m.pull && !m.pull.done);

  // durante il download aggiorna lo stato ogni secondo
  useEffect(() => {
    if (step !== 1) return;
    const t = window.setInterval(() => { void reload(); }, pulling || !status.ollama.running ? 1000 : 4000);
    return () => window.clearInterval(t);
  }, [step, pulling, status.ollama.running, reload]);

  // se Ollama è installato ma spento, prova ad avviarlo
  useEffect(() => {
    if (step === 1 && !status.ollama.running && status.ollama.installed && !busy) {
      setBusy(true); api.startOllama().finally(() => { setBusy(false); void reload(); });
    }
  }, [step, status.ollama.running, status.ollama.installed]); // eslint-disable-line react-hooks/exhaustive-deps

  async function saveProfile(e: React.FormEvent) {
    e.preventDefault(); setError('');
    try { await api.saveProfile({name, organization: org}); await reload(); setStep(1); } catch (err) { setError((err as Error).message); }
  }
  async function download() { setError(''); try { await api.pull(); await reload(); } catch (err) { setError((err as Error).message); } }

  const missing = status.models.filter(m => !m.installed);
  const left = <div className="l">
    <div className="logo" style={{color: '#fff'}}><img src="/logo.png" alt=""/>RegMac</div>
    <h1 style={{fontSize: 30, lineHeight: 1.2, color: '#fff', marginTop: 30}}>Pre-assessment di macchine con l'AI, sul tuo computer.</h1>
    <p>Carichi la documentazione tecnica: l'AI locale valuta i requisiti del Regolamento Macchine (UE) 2023/1230 e del Cyber Resilience Act (UE) 2024/2847 e produce un report con score di compliance e gap.</p>
    <ul style={{paddingLeft: 18, fontSize: 13}}><li>Nessun dato esce dal computer</li><li>Ogni esito cita la frase del documento da cui deriva</li><li>Rivedi e correggi prima del report</li></ul>
    <p className="tiny" style={{marginTop: 'auto', color: '#ffffff99'}}>Versione {status.version} · schede {status.schede}</p>
  </div>;

  return <div className="welcome" style={{height: '100vh'}}>{left}
    {step === 0 ? <form className="r" onSubmit={saveProfile}>
      <span className="eyebrow">Primo avvio · 1 di 2</span><h1>Chi firma i report?</h1>
      <p className="mute">Nome e organizzazione compaiono nell'intestazione dei report. Puoi cambiarli in Impostazioni.</p>
      <label className="f">Nome e cognome<input value={name} onChange={e => setName(e.target.value)} required autoFocus/></label>
      <label className="f"><span>Organizzazione <span className="tiny mute">(facoltativa)</span></span><input value={org} onChange={e => setOrg(e.target.value)}/></label>
      {error && <div className="err">{error}</div>}
      <div className="row" style={{marginTop: 8}}><button className="btn pri">Avanti <ChevronRight size={15}/></button></div>
    </form> : <div className="r">
      <span className="eyebrow">Primo avvio · 2 di 2</span><h1>Controllo dell'AI locale</h1>
      <p className="mute">RegMac funziona solo con l'AI locale: Ollama e due modelli. Il controllo si ripete a ogni avvio.</p>
      <div className="card" style={{padding: '4px 14px'}}>
        <Row ok={status.ollama.running} title={status.ollama.running ? `Ollama ${status.ollama.version}` : 'Ollama'}
             sub={status.ollama.running ? 'in esecuzione su questo computer' : status.ollama.installed ? 'avvio in corso…' : 'non installato'}/>
        {status.models.map(m => {
          const p = m.pull, frac = p && p.total ? p.completed / p.total : 0;
          return <div className="block" key={m.name}>
            <span style={{color: m.installed ? 'var(--ok)' : p?.error ? 'var(--nc)' : 'var(--mute)', display: 'flex'}}>{m.installed ? <Check size={16}/> : p && !p.done ? <Loader2 size={16} className="spinning"/> : p?.error ? <AlertTriangle size={16}/> : <Download size={16}/>}</span>
            <div className="grow"><b>{m.name}</b> <span className="tiny mute">· {gb(m.approxBytes)}</span><div className="tiny mute">{m.role}</div>
              {!m.installed && p && !p.done && <><div className="dl"><i style={{width: (frac * 100).toFixed(1) + '%'}}/></div><div className="tiny mute">{p.total ? `${gb(p.completed)} di ${gb(p.total)} · ${Math.round(frac * 100)}%` : p.status}</div></>}
              {p?.error && <div className="tiny" style={{color: 'var(--nc)'}}>{p.error}</div>}
            </div>
            <span className="tiny mute">{m.installed ? 'installato' : p && !p.done ? 'download…' : 'mancante'}</span>
          </div>;
        })}
        <Row ok={status.memoryGb >= 16} warn title={`Memoria ${status.memoryGb} GB`} sub={status.memoryGb >= 16 ? 'sufficiente (minimo 16 GB)' : 'sotto i 16 GB consigliati: l\'analisi sarà lenta'}/>
      </div>
      {!status.ollama.installed && <div className="card" style={{background: 'var(--partbg)', borderColor: 'transparent'}}>
        <b>Installa Ollama per continuare</b>
        <p className="small" style={{marginTop: 6}}>Scaricalo da <b>ollama.com/download</b> e installalo come una normale applicazione. Poi torna qui: RegMac lo rileva da solo.</p>
        <div className="row" style={{marginTop: 10}}><button className="btn" onClick={() => void api.openUrl('https://ollama.com/download')}><ExternalLink size={14}/>Apri ollama.com/download</button><button className="btn" onClick={() => void reload()}>Riprova</button></div>
      </div>}
      {status.ollama.running && missing.length > 0 && !pulling && <div className="card row" style={{background: 'var(--brand3)'}}>
        <div className="grow"><b>Mancano {missing.length === 1 ? 'un modello' : missing.length + ' modelli'}</b><div className="tiny mute">Download di circa {gb(missing.reduce((a, m) => a + m.approxBytes, 0))}, una volta sola. Puoi continuare a usare il computer.</div></div>
        <button className="btn pri" onClick={download}><Download size={14}/>Scarica</button>
      </div>}
      {error && <div className="err">{error}</div>}
      <div className="row">{!status.profile && <button className="btn" onClick={() => setStep(0)}>Indietro</button>}
        <span className="grow tiny mute">{status.ready ? 'Tutto pronto.' : pulling ? 'Download in corso: non chiudere RegMac.' : ''}</span>
        <button className="btn pri" disabled={!status.ready} onClick={() => void reload()}>Inizia <ChevronRight size={15}/></button></div>
    </div>}
  </div>;
}

function Row({ok, title, sub, warn}: {ok: boolean; title: string; sub: string; warn?: boolean}) {
  return <div className="block"><span style={{color: ok ? 'var(--ok)' : warn ? 'var(--part)' : 'var(--nc)', display: 'flex'}}>{ok ? <Check size={16}/> : <AlertTriangle size={16}/>}</span>
    <div className="grow"><b>{title}</b><div className="tiny mute">{sub}</div></div></div>;
}
