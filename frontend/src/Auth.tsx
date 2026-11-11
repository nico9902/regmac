import {useEffect,useState} from 'react';
import {KeyRound,UserPlus} from 'lucide-react';

export type Role='ADMIN'|'ASSESSOR'|'READER';
export type User={id:string;username:string;displayName:string;role:Role;active:boolean;createdAt:string|null;lastLoginAt:string|null};
export type Session={token:string;expiresAt:string;user:User};
export const ROLE_LABEL:Record<Role,string>={ADMIN:'Amministratore',ASSESSOR:'Valutatore',READER:'Lettore'};
const KEY='regmac-session';

export function loadSession():Session|null{
 try{const s=JSON.parse(localStorage.getItem(KEY)||'null') as Session|null;return s&&new Date(s.expiresAt)>new Date()?s:null}catch{return null}
}
export function saveSession(s:Session|null){try{s?localStorage.setItem(KEY,JSON.stringify(s)):localStorage.removeItem(KEY)}catch{/* storage non disponibile */}}

async function post<T>(path:string,body:unknown):Promise<T>{
 const r=await fetch('/api'+path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
 const data=await r.json().catch(()=>({}));
 if(!r.ok)throw new Error(data.error||'Errore '+r.status);
 return data as T;
}

/** Schermata di accesso; al primo avvio crea l'amministratore. */
export function Login({onLogin}:{onLogin:(s:Session)=>void}){
 const [setup,setSetup]=useState<boolean|null>(null),[error,setError]=useState(''),[busy,setBusy]=useState(false);
 useEffect(()=>{fetch('/api/auth/status').then(r=>r.json()).then(d=>setSetup(!!d.setupRequired)).catch(()=>setError('Server non raggiungibile'))},[]);
 async function submit(e:React.FormEvent<HTMLFormElement>){
  e.preventDefault();const f=new FormData(e.currentTarget);setBusy(true);setError('');
  try{
   if(setup){if(f.get('password')!==f.get('confirm'))throw new Error('Le due password non coincidono');
    onLogin(await post<Session>('/auth/setup',{username:f.get('username'),displayName:f.get('displayName'),password:f.get('password')}))}
   else onLogin(await post<Session>('/auth/login',{username:f.get('username'),password:f.get('password')}));
  }catch(err){setError(err instanceof Error?err.message:String(err))}finally{setBusy(false)}
 }
 return <div className="auth-page"><section className="modal auth-card">
  <div className="auth-brand"><img className="brand-logo" src="/logo.png" alt=""/>RegMac</div>
  {setup===null&&!error&&<p>Connessione al server…</p>}
  {setup!==null&&<><span className="eyebrow">{setup?'CONFIGURAZIONE INIZIALE':'ACCESSO'}</span><h2>{setup?'Crea l\'amministratore':'Accedi al workspace'}</h2>
   {setup&&<p>Primo avvio: crea l'utente amministratore. Potrai poi aggiungere valutatori e lettori dalla sezione Utenti.</p>}
   <form onSubmit={submit}>
    <label>Nome utente<input name="username" required autoComplete="username" pattern="[a-zA-Z0-9._\-]{3,60}" title="3-60 caratteri: lettere, numeri, punto, trattino"/></label>
    {setup&&<label>Nome e cognome<input name="displayName" required autoComplete="name"/></label>}
    <label>Password<input name="password" type="password" required minLength={setup?10:1} autoComplete={setup?'new-password':'current-password'}/></label>
    {setup&&<label>Conferma password<input name="confirm" type="password" required minLength={10} autoComplete="new-password"/></label>}
    {setup&&<small>Almeno 10 caratteri. La password serve anche a firmare i rapporti.</small>}
    <button className="primary" disabled={busy}>{busy?'Attendere…':setup?'Crea e accedi':'Accedi'}</button>
   </form></>}
  {error&&<div className="alert" role="alert">{error}</div>}
 </section></div>;
}

/** Gestione utenti (solo amministratori). */
export function UsersPage({api,me}:{api:<T>(path:string,method?:string,body?:unknown)=>Promise<T>;me:User}){
 const [users,setUsers]=useState<User[]>([]),[error,setError]=useState(''),[notice,setNotice]=useState(''),[reset,setReset]=useState<User|null>(null);
 const load=()=>api<User[]>('/users').then(setUsers).catch(e=>setError(String(e.message||e)));
 useEffect(()=>{void load()},[]);
 async function run(fn:()=>Promise<unknown>,ok:string){setError('');setNotice('');try{await fn();setNotice(ok);await load()}catch(e){setError(e instanceof Error?e.message:String(e))}}
 return <>
  {error&&<div className="alert" role="alert">{error}</div>}{notice&&<div className="notice" role="status">{notice}</div>}
  <section className="card table-card"><table><thead><tr><th>Utente</th><th>Ruolo</th><th>Stato</th><th>Ultimo accesso</th><th/></tr></thead><tbody>
   {users.map(u=><tr key={u.id}><td><strong>{u.displayName}</strong><small>{u.username}{u.id===me.id?' · tu':''}</small></td>
    <td><select aria-label={'Ruolo di '+u.username} value={u.role} disabled={u.id===me.id} onChange={e=>run(()=>api(`/users/${u.id}`,'PUT',{role:e.target.value}),'Ruolo aggiornato.')}>{(Object.keys(ROLE_LABEL) as Role[]).map(r=><option key={r} value={r}>{ROLE_LABEL[r]}</option>)}</select></td>
    <td>{u.active?<span className="badge ready">Attivo</span>:<span className="badge failed">Disattivato</span>}</td>
    <td>{u.lastLoginAt?new Date(u.lastLoginAt).toLocaleString('it-IT'):'—'}</td>
    <td><div className="actions"><button className="text-button" onClick={()=>setReset(u)}>Reimposta password</button>{u.id!==me.id&&<button className="text-button" onClick={()=>run(()=>api(`/users/${u.id}`,'PUT',{active:!u.active}),u.active?'Utente disattivato: le sue sessioni sono state chiuse.':'Utente riattivato.')}>{u.active?'Disattiva':'Riattiva'}</button>}</div></td></tr>)}
  </tbody></table></section>
  <section className="card"><h2><UserPlus size={18}/> Nuovo utente</h2>
   <p>Valutatore: crea e compila checklist e firma i rapporti. Lettore: consulta wiki, checklist e rapporti senza modificarli. Amministratore: anche gestione utenti.</p>
   <form className="review-form" onSubmit={e=>{e.preventDefault();const f=e.currentTarget,d=new FormData(f);void run(async()=>{await api('/users','POST',{username:d.get('username'),displayName:d.get('displayName'),password:d.get('password'),role:d.get('role')});f.reset()},'Utente creato: comunica la password iniziale in modo sicuro.')}}>
    <input name="username" required placeholder="Nome utente" pattern="[a-zA-Z0-9._\-]{3,60}"/><input name="displayName" required placeholder="Nome e cognome"/><input name="password" type="password" required minLength={10} placeholder="Password iniziale (min. 10)" autoComplete="new-password"/>
    <select name="role" defaultValue="ASSESSOR">{(Object.keys(ROLE_LABEL) as Role[]).map(r=><option key={r} value={r}>{ROLE_LABEL[r]}</option>)}</select><button className="primary">Crea utente</button></form>
  </section>
  {reset&&<div className="overlay"><section className="modal" role="dialog" aria-modal="true" aria-label="Reimposta password"><button className="close" onClick={()=>setReset(null)} aria-label="Chiudi">×</button><h2>Reimposta la password di {reset.displayName}</h2><p>Le sessioni aperte dell'utente vengono chiuse.</p>
   <form onSubmit={e=>{e.preventDefault();const p=String(new FormData(e.currentTarget).get('password'));void run(()=>api(`/users/${reset.id}`,'PUT',{password:p}),'Password reimpostata.').then(()=>setReset(null))}}><label>Nuova password<input name="password" type="password" required minLength={10} autoComplete="new-password"/></label><button className="primary">Reimposta</button></form></section></div>}
 </>;
}

export function PasswordModal({api,onClose}:{api:<T>(path:string,method?:string,body?:unknown)=>Promise<T>;onClose:()=>void}){
 const [error,setError]=useState(''),[done,setDone]=useState(false);
 return <div className="overlay"><section className="modal" role="dialog" aria-modal="true" aria-label="Cambia password"><button className="close" onClick={onClose} aria-label="Chiudi">×</button><h2><KeyRound size={18}/> Cambia password</h2>
  {done?<><p>Password aggiornata.</p><button className="primary" onClick={onClose}>Chiudi</button></>:
  <form onSubmit={async e=>{e.preventDefault();const f=new FormData(e.currentTarget);setError('');if(f.get('next')!==f.get('confirm')){setError('Le due password non coincidono');return}try{await api('/auth/password','POST',{current:f.get('current'),next:f.get('next')});setDone(true)}catch(err){setError(err instanceof Error?err.message:String(err))}}}>
   <label>Password attuale<input name="current" type="password" required autoComplete="current-password"/></label><label>Nuova password<input name="next" type="password" required minLength={10} autoComplete="new-password"/></label><label>Conferma nuova password<input name="confirm" type="password" required minLength={10} autoComplete="new-password"/></label>
   {error&&<div className="alert" role="alert">{error}</div>}<button className="primary">Aggiorna password</button></form>}
 </section></div>;
}
