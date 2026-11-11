import {useCallback, useEffect, useState} from 'react';
import {BookOpen, Folder, Settings as Gear} from 'lucide-react';
import {api, Status} from './api';
import Setup from './Setup';
import Dossiers from './Dossiers';
import Dossier from './Dossier';
import Normativa from './Normativa';
import Settings from './Settings';
// il tema salvato si applica all'avvio (vedi Settings)

export type Toast = (msg: string, action?: {label: string; run: () => void}) => void;
type View = {tab: 'fascicoli' | 'normativa' | 'impostazioni'; id?: string; sub?: string; rid?: string};

export default function App() {
  const [status, setStatus] = useState<Status | null>(null);
  const [error, setError] = useState('');
  const [view, setView] = useState<View>({tab: 'fascicoli'});
  const [toast, setToast] = useState<{msg: string; action?: {label: string; run: () => void}} | null>(null);
  const [deletedAi, setDeletedAi] = useState<number | null>(null);

  const load = useCallback(() => api.status().then(s => { setStatus(s); setError(''); }).catch(e => setError(String(e.message || e))), []);
  useEffect(() => { void load(); }, [load]);

  const show: Toast = (msg, action) => { setToast({msg, action}); window.clearTimeout((window as unknown as {_t: number})._t); (window as unknown as {_t: number})._t = window.setTimeout(() => setToast(null), action ? 8000 : 3500); };

  if (error && !status) return <div className="setup"><h1>RegMac</h1><div className="err" style={{marginTop: 16}}>{error}</div><button className="btn" style={{marginTop: 12}} onClick={load}>Riprova</button></div>;
  if (!status) return <div className="empty">Avvio…</div>;
  if (deletedAi !== null) return <div className="setup" style={{textAlign: 'center', paddingTop: 80}}><img src="/logo.png" alt="" style={{width: 64, borderRadius: 14}}/>
    <h1 style={{marginTop: 14}}>Modelli AI eliminati</h1><p className="mute" style={{marginTop: 8}}>Liberati {(deletedAi / 1e9).toFixed(1).replace('.', ',')} GB. Ora puoi chiudere RegMac e, se vuoi disinstallarlo, trascinarlo nel Cestino.</p>
    <p className="tiny mute" style={{marginTop: 6}}>I fascicoli sono ancora nella cartella dati: <code className="path">{status.dataDir}</code></p>
    <button className="btn" style={{marginTop: 18}} onClick={() => { setDeletedAi(null); void load(); }}>Ho cambiato idea: riscarica i modelli</button></div>;
  if (!status.profile || !status.ready) return <Setup status={status} reload={load}/>;

  const initials = status.profile.name.split(/\s+/).map(x => x[0]).join('').slice(0, 2).toUpperCase();
  const tab = (id: View['tab'], Icon: typeof Folder, label: string) =>
    <button className={'tab ' + (view.tab === id ? 'on' : '')} onClick={() => setView({tab: id})}><Icon size={15}/>{label}</button>;

  return <div className="app">
    <div className="appbar">
      <div className="logo"><img src="/logo.png" alt=""/>RegMac</div>
      <div className="tabs">{tab('fascicoli', Folder, 'Fascicoli')}{tab('normativa', BookOpen, 'Normativa')}</div>
      <div className="who"><span className="tiny"><span className="live"/>AI locale attiva</span><div className="avatar">{initials}</div><span>{status.profile.name}</span>
        <button className={'iconbtn ' + (view.tab === 'impostazioni' ? 'on' : '')} aria-label="Impostazioni" onClick={() => setView({tab: 'impostazioni'})}><Gear size={17}/></button></div>
    </div>
    <div className="content">
      {view.tab === 'fascicoli' && !view.id && <Dossiers open={(id, sub) => setView({tab: 'fascicoli', id, sub})} toast={show}/>}
      {view.tab === 'fascicoli' && view.id && <Dossier id={view.id} initialTab={view.sub} back={() => setView({tab: 'fascicoli'})} toast={show}/>}
      {view.tab === 'normativa' && <Normativa/>}
      {view.tab === 'impostazioni' && <Settings status={status} reload={load} toast={show} onDeletedAi={b => setDeletedAi(b)}/>}
    </div>
    {toast && <div className="toast" role="status">{toast.msg}{toast.action && <button className="undo" onClick={() => { toast.action!.run(); setToast(null); }}>{toast.action.label}</button>}</div>}
  </div>;
}
