import {useEffect, useRef, useState} from 'react';
import {AlertTriangle, Check, ChevronRight, Download, ExternalLink, Loader2} from 'lucide-react';
import {api, gb, Status} from './api';

/**
 * Primo avvio: profilo del valutatore, poi preparazione automatica dell'AI.
 * Se Ollama manca RegMac lo scarica (pacchetto ufficiale verificato), lo avvia e scarica i modelli: nessun clic.
 */
export default function Setup({status, reload}: {status: Status; reload: () => Promise<void>}) {
  const [step, setStep] = useState(status.profile ? 1 : 0);
  const [name, setName] = useState(status.profile?.name || '');
  const [org, setOrg] = useState(status.profile?.organization || '');
  const [error, setError] = useState('');
  const started = useRef(false);
  const inst = status.ollamaInstall;
  const installing = !!inst && !inst.done;
  const pulling = status.models.some(m => m.pull && !m.pull.done);
  const failed = (inst?.error ? inst.error : null) || status.models.find(m => m.pull?.error)?.pull?.error || null;

  // avvio automatico della preparazione, una volta per sessione (e di nuovo con «Riprova»)
  const prepare = () => { setError(''); api.auto().then(() => reload()).catch(e => setError((e as Error).message)); };
  useEffect(() => { if (step === 1 && !status.ready && !started.current) { started.current = true; prepare(); } }, [step, status.ready]); // eslint-disable-line react-hooks/exhaustive-deps
  // quando Ollama è pronto ma qualche modello manca e nessun download è in corso, riprova da solo
  useEffect(() => {
    if (step === 1 && status.ollama.running && !pulling && !installing && !failed && status.models.some(m => !m.installed)) {
      const t = window.setTimeout(prepare, 1500); return () => window.clearTimeout(t);
    }
  }, [step, status.ollama.running, pulling, installing, failed]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (step !== 1 || status.ready) return;
    const t = window.setInterval(() => { void reload(); }, 1000);
    return () => window.clearInterval(t);
  }, [step, status.ready, reload]);

  async function saveProfile(e: React.FormEvent) {
    e.preventDefault(); setError('');
    try { await api.saveProfile({name, organization: org}); await reload(); setStep(1); } catch (err) { setError((err as Error).message); }
  }

  const ollamaFrac = inst && inst.total ? inst.completed / inst.total : 0;
  const totalLeft = status.models.filter(m => !m.installed).reduce((a, m) => a + m.approxBytes, 0);
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
      <span className="eyebrow">Primo avvio · 2 di 2</span><h1>Preparazione dell'AI locale</h1>
      <p className="mute">RegMac scarica e avvia da solo quello che serve: il motore Ollama e due modelli. Succede una volta sola; poi tutto funziona anche senza internet.</p>
      <div className="card" style={{padding: '4px 14px'}}>
        <div className="block">
          <Icon ok={status.ollama.running} busy={installing || (!status.ollama.running && !failed)} err={!!inst?.error}/>
          <div className="grow"><b>Ollama{status.ollama.version ? ' ' + status.ollama.version : ''}</b> <span className="tiny mute">· motore dell'AI{status.ollamaAsset?.startsWith('ollama-darwin') ? ' · 177 MB' : status.ollamaAsset ? ' · circa 1,5 GB' : ''}</span>
            <div className="tiny mute">{status.ollama.running ? (status.ollamaOwn ? 'scaricato da RegMac, in esecuzione' : 'in esecuzione su questo computer') : installing ? inst!.status : inst?.error ? 'non installato' : 'avvio…'}</div>
            {installing && inst!.total > 0 && <><div className="dl"><i style={{width: (ollamaFrac * 100).toFixed(1) + '%'}}/></div>
              <div className="tiny mute">{gb(inst!.completed)} di {gb(inst!.total)} · {Math.round(ollamaFrac * 100)}%</div></>}
          </div>
        </div>
        {status.models.map(m => {
          const p = m.pull, frac = p && p.total ? p.completed / p.total : 0;
          return <div className="block" key={m.name}>
            <Icon ok={m.installed} busy={!!p && !p.done} err={!!p?.error} wait={!m.installed && !p}/>
            <div className="grow"><b>{m.name}</b> <span className="tiny mute">· {gb(m.approxBytes)}</span><div className="tiny mute">{m.role}</div>
              {!m.installed && p && !p.done && <><div className="dl"><i style={{width: (frac * 100).toFixed(1) + '%'}}/></div><div className="tiny mute">{p.total ? `${gb(p.completed)} di ${gb(p.total)} · ${Math.round(frac * 100)}%` : p.status}</div></>}
            </div>
            <span className="tiny mute">{m.installed ? 'pronto' : p && !p.done ? 'download…' : 'in attesa'}</span>
          </div>;
        })}
        <div className="block"><Icon ok={status.memoryGb >= 16} warnOnly/><div className="grow"><b>Memoria {status.memoryGb} GB</b>
          <div className="tiny mute">{status.memoryGb >= 16 ? 'sufficiente (minimo consigliato 16 GB)' : 'sotto i 16 GB consigliati: l\'analisi sarà lenta'}</div></div></div>
      </div>
      {!status.ready && !failed && <p className="tiny mute">{totalLeft > 0 ? `Da scaricare in tutto circa ${gb(totalLeft + (status.ollama.running ? 0 : status.ollamaAsset?.startsWith('ollama-darwin') ? 177e6 : 1.5e9))}. ` : ''}Puoi lasciare RegMac aperto e usare il computer: al termine si entra da soli.</p>}
      {(failed || error) && <div className="card" style={{background: 'var(--ncbg)', borderColor: 'transparent'}}>
        <b style={{color: 'var(--nc)'}}>Qualcosa non è andato</b><p className="small" style={{marginTop: 6}}>{failed || error}</p>
        <p className="tiny mute" style={{marginTop: 6}}>Controlla la connessione a internet e riprova. In alternativa installa Ollama a mano: RegMac lo rileva da solo.</p>
        <div className="row" style={{marginTop: 10}}><button className="btn pri" onClick={() => { prepare(); }}>Riprova</button>
          <button className="btn" onClick={() => void api.openUrl('https://ollama.com/download')}><ExternalLink size={14}/>ollama.com/download</button></div></div>}
      <div className="row">{!status.profile && <button className="btn" onClick={() => setStep(0)}>Indietro</button>}<span className="grow"/>
        <button className="btn pri" disabled={!status.ready} onClick={() => void reload()}>{status.ready ? 'Inizia' : 'Preparazione…'} <ChevronRight size={15}/></button></div>
    </div>}
  </div>;
}

function Icon({ok, busy, err, wait, warnOnly}: {ok: boolean; busy?: boolean; err?: boolean; wait?: boolean; warnOnly?: boolean}) {
  const color = ok ? 'var(--ok)' : err ? 'var(--nc)' : warnOnly ? 'var(--part)' : 'var(--mute)';
  return <span style={{color, display: 'flex'}}>{ok ? <Check size={16}/> : err || warnOnly ? <AlertTriangle size={16}/> : busy ? <Loader2 size={16} className="spinning"/> : wait ? <Download size={16}/> : <Loader2 size={16} className="spinning"/>}</span>;
}
