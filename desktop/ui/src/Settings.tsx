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

export default function Settings({status, reload, toast, onDeletedAi}: {status: Status; reload: () => Promise<void>; toast: Toast; onDeletedAi: (bytes: number) => void}) {
  const [confirmDel, setConfirmDel] = useState(false);
  const [deleting, setDeleting] = useState(false);
  async function deleteAi() { setDeleting(true); try { const r = await api.deleteAi(); onDeletedAi(r.freedBytes); } catch (e) { toast((e as Error).message); setDeleting(false); } }
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
      <p className="tiny mute" style={{marginTop: 8}}>RegMac usa un proprio Ollama con 4 richieste in parallelo; modelli e Ollama stanno nella sua cartella ({gb(status.aiBytes)}): <code className="path">{status.aiDir}</code>. Memoria del computer: {status.memoryGb} GB.</p>
      <div className="row" style={{marginTop: 12, paddingTop: 12, borderTop: '1px solid var(--line2)'}}>
        <div className="grow"><b className="small">Elimina i modelli AI</b><div className="tiny mute">{status.aiBytes > 100e6 ? `Libera ${gb(status.aiBytes)}.` : 'I modelli sono condivisi con l\'Ollama già installato sul computer: eliminandoli da RegMac non si libera spazio e quelli originali restano.'} Da fare prima di disinstallare RegMac su macOS; su Windows e Linux lo fa la disinstallazione. I fascicoli restano.</div></div>
        {confirmDel ? <span className="row" style={{gap: 6}}><button className="btn danger pri sm" disabled={deleting} onClick={deleteAi}>{deleting ? 'Eliminazione…' : 'Elimina'}</button><button className="btn sm" onClick={() => setConfirmDel(false)}>Annulla</button></span>
          : <button className="btn danger sm" onClick={() => setConfirmDel(true)}>Elimina…</button>}</div></div>
    <div className="card"><h2>Aspetto</h2><div className="yn" style={{marginTop: 10}}>{([['light', 'Chiaro', Sun], ['dark', 'Scuro', Moon], ['system', 'Sistema', Monitor]] as const).map(([m, l, I]) =>
      <button key={m} className={mode === m ? 'on' : ''} onClick={() => { applyTheme(m); setMode(m); }}><I size={13} style={{verticalAlign: -2, marginRight: 4}}/>{l}</button>)}</div></div>
    <div className="card"><h2>Dati</h2><dl className="kv" style={{marginTop: 10}}><dt>Cartella dati</dt><dd><code className="path">{status.dataDir}</code></dd><dt>Schede di verifica</dt><dd>{status.schede}</dd><dt>Versione</dt><dd>RegMac {status.version} · {status.os}</dd></dl>
      <p className="tiny mute" style={{marginTop: 8}}>Ogni fascicolo è una cartella dentro «fascicoli»: per un backup basta copiare la cartella dati con RegMac chiuso.</p></div>
    <div className="card"><h2>Disinstallare RegMac</h2><ul className="small" style={{margin: '8px 0 0', paddingLeft: 18, lineHeight: 1.8}}>
      <li><b>Windows</b>: Impostazioni → App → RegMac → Disinstalla. Ollama e i modelli vengono eliminati; spunta «Elimina i dati dell'applicazione» per eliminare anche i fascicoli.</li>
      <li><b>macOS</b>: usa prima «Elimina i modelli AI» qui sopra, poi trascina RegMac dalle Applicazioni al Cestino. I fascicoli restano nella cartella dati, che puoi cancellare a mano.</li>
      <li><b>Linux</b>: <code className="path">sudo apt remove reg-mac</code> elimina anche Ollama e i modelli; <code className="path">sudo apt purge reg-mac</code> anche i fascicoli.</li></ul></div>
  </div></div>;
}
