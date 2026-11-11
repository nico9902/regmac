import {useState} from 'react';
import {Check, Monitor, Moon, Sun} from 'lucide-react';
import {api, gb, Status} from './api';
import type {Toast} from './App';

type Mode = 'light' | 'dark' | 'system';
const loadMode = (): Mode => { try { const v = localStorage.getItem('regmac-theme'); return v === 'light' || v === 'dark' ? v : 'system'; } catch { return 'system'; } };
export function applyTheme(m: Mode) {
  if (m === 'system') delete document.documentElement.dataset.theme; else document.documentElement.dataset.theme = m;
  try { if (m === 'system') localStorage.removeItem('regmac-theme'); else localStorage.setItem('regmac-theme', m); } catch { /* storage non disponibile */ }
}
applyTheme(loadMode());

export default function Settings({status, reload, toast}: {status: Status; reload: () => Promise<void>; toast: Toast}) {
  const [name, setName] = useState(status.profile?.name || '');
  const [org, setOrg] = useState(status.profile?.organization || '');
  const [mode, setMode] = useState<Mode>(loadMode());
  async function save() { try { await api.saveProfile({name, organization: org}); await reload(); toast('Profilo salvato'); } catch (e) { toast((e as Error).message); } }
  return <div className="page" style={{maxWidth: 860}}><h1>Impostazioni</h1><div className="stack" style={{marginTop: 16}}>
    <div className="card"><h2>Autore dei report</h2>
      <div className="grid2" style={{gridTemplateColumns: '1fr 1fr', marginTop: 12}}><label className="f">Nome e cognome<input value={name} onChange={e => setName(e.target.value)}/></label>
        <label className="f">Organizzazione<input value={org} onChange={e => setOrg(e.target.value)}/></label></div>
      <div className="row" style={{marginTop: 10}}><span className="grow"/><button className="btn pri" onClick={save}>Salva</button></div></div>
    <div className="card"><div className="row"><h2 className="grow">AI locale</h2><span className="chip ok"><span className="live"/>Ollama {status.ollama.version} attivo</span></div>
      <table style={{marginTop: 10}}><tbody>{status.models.map(m => <tr key={m.name}><td><b>{m.name}</b></td><td className="mute">{m.role}</td><td>{gb(m.approxBytes)}</td>
        <td><span className="chip ok"><Check size={11}/>installato</span></td></tr>)}</tbody></table>
      <p className="tiny mute" style={{marginTop: 8}}>{status.ollama.startedByApp ? 'Ollama è stato avviato da RegMac con 4 richieste in parallelo.' : 'Ollama era già in esecuzione: per la massima velocità impostare OLLAMA_NUM_PARALLEL=4.'} Memoria del computer: {status.memoryGb} GB.</p></div>
    <div className="card"><h2>Aspetto</h2><div className="yn" style={{marginTop: 10}}>{([['light', 'Chiaro', Sun], ['dark', 'Scuro', Moon], ['system', 'Sistema', Monitor]] as const).map(([m, l, I]) =>
      <button key={m} className={mode === m ? 'on' : ''} onClick={() => { applyTheme(m); setMode(m); }}><I size={13} style={{verticalAlign: -2, marginRight: 4}}/>{l}</button>)}</div></div>
    <div className="card"><h2>Dati</h2><dl className="kv" style={{marginTop: 10}}><dt>Cartella dati</dt><dd><code className="path">{status.dataDir}</code></dd><dt>Schede di verifica</dt><dd>{status.schede}</dd><dt>Versione</dt><dd>RegMac {status.version} · {status.os}</dd></dl>
      <p className="tiny mute" style={{marginTop: 8}}>Ogni fascicolo è una cartella dentro «fascicoli»: per un backup basta copiare la cartella dati con RegMac chiuso.</p></div>
  </div></div>;
}
