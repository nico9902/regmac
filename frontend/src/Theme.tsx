import {useState} from 'react';
import {Monitor,Moon,Sun} from 'lucide-react';

export type ThemeMode='light'|'dark'|'system';
const KEY='regmac-theme';

export function loadTheme():ThemeMode{
 try{const v=localStorage.getItem(KEY);return v==='light'||v==='dark'?v:'system'}catch{return 'system'}
}
/** Applica il tema: 'system' lascia decidere a prefers-color-scheme. */
export function applyTheme(mode:ThemeMode){
 const root=document.documentElement;
 if(mode==='system')delete root.dataset.theme;else root.dataset.theme=mode;
 try{mode==='system'?localStorage.removeItem(KEY):localStorage.setItem(KEY,mode)}catch{/* storage non disponibile */}
}

export function ThemeSwitch(){
 const [mode,setMode]=useState<ThemeMode>(loadTheme);
 const opts:[ThemeMode,string,typeof Sun][]=[['light','Chiaro',Sun],['dark','Scuro',Moon],['system','Sistema',Monitor]];
 return <div className="theme-switch" role="radiogroup" aria-label="Tema">
  {opts.map(([m,label,Icon])=><button key={m} role="radio" aria-checked={mode===m} className={mode===m?'on':''} title={label} onClick={()=>{applyTheme(m);setMode(m)}}><Icon size={13}/>{label}</button>)}
 </div>;
}
