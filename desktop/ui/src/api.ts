// Tipi e chiamate verso il motore locale (stessa origine, cookie di sessione impostato all'avvio).
export type Profile = {name: string; organization: string};
export type Pull = {model: string; status: string; total: number; completed: number; error: string | null; done: boolean};
export type ModelRow = {name: string; role: string; approxBytes: number; installed: boolean; pull: Pull | null};
export type Status = {
  profile: Profile | null; ready: boolean; memoryGb: number; dataDir: string; os: string; schede: string; version: string;
  ollama: {running: boolean; version: string; installed: boolean; startedByApp: boolean}; models: ModelRow[];
  ollamaInstall: {status: string; total: number; completed: number; error: string | null; done: boolean} | null; ollamaAsset: string | null; ollamaOwn: boolean; aiBytes: number; aiDir: string;
};
export type Answer = {tag: string; verdict: 'TRUE' | 'FALSE' | 'UNKNOWN'; quote: string; doc: string | null; page: number | null; value: boolean | null};
export type Run = {state: 'idle' | 'queued' | 'indexing' | 'running' | 'done' | 'stopped' | 'interrupted' | 'error'; done: number; total: number;
  startedAt: string | null; finishedAt: string | null; message: string | null; model: string | null};
export type Fascicolo = {id: string; name: string; model: string; serial: string; year: string; maker: string; use: string; createdAt: string; updatedAt: string;
  profile: Answer[]; tags: string[]; profileConfirmed: boolean; run: Run; exportedAt: string | null};
export type Doc = {id: string; name: string; file: string; size: number; pages: number; sha256: string; passages: number; warning: string | null; error?: string | null; addedAt: string; types?: string[] | null};
export type Point = {text: string; answer: 'S' | 'C' | 'I'; quote: string | null; docId: string | null; doc: string | null; page: number | null; support: number | null; user: 'S' | 'C' | 'I' | null; reason?: string; candidates?: number; expected?: string | null};
export type Result = {rid: string; applicability: 'TRUE' | 'FALSE' | 'UNKNOWN'; appQuote: string | null; appDoc: string | null; appPage: number | null;
  points: Point[]; confirmed: boolean; note: string | null; hash: string; at: string; appUser?: 'TRUE' | 'FALSE' | null};
export type Spec = {q: string; doc: string | null; ok: string; no: string; smentita: string | null; quasi: string | null; num: {what: string; unit: string; min: number | null; max: number | null} | null};
export type Sheet = {condition: string; points: Spec[]; status: string};
export type ReqView = {id: string; ref: string; title: string; law: string; group: string; area: string; always: boolean; outcome: Outcome | null;
  value: number | null; max: number | null; applicability: string | null; result: Result | null; sheet: Sheet};
export type Outcome = 'OK' | 'PART' | 'NC' | 'INS' | 'NA' | 'INT';
export type Summary = {total: number; done: number; confirmed: number; applicable: number; na: number; full: number; S: number; C: number; I: number;
  score: number; max: number; coverage: number; notEvaluable: boolean; gapsTech: number; gapsDoc: number; gapsApp: number;
  gapsMissing: number; gapsEvidence: number; integrator: number};
export type GapPoint = {text: string; motive: string; expected: string | null; expectedLabel: string; answer: 'S' | 'C' | 'I'; quote: string | null; doc: string | null; page: number | null; reviewed: boolean};
export type Gap = {rid: string; ref: string; title: string; kind: string; priority: number; value: number; points: GapPoint[]; condition: string | null};
export type DocNeed = {type: string | null; label: string; missing: boolean; points: number; requirements: number; integrator: boolean};
export type Assessment = {summary: Summary; areas: {name: string; n: number; score: number; max: number}[]; gaps: Gap[]; docsNeeded: DocNeed[]; requirements: ReqView[]};
export type DossierRow = {fascicolo: Fascicolo; summary: Summary; documents: number};
export type DossierFull = {fascicolo: Fascicolo; documents: Doc[]; assessment: Assessment};
export type CatalogReq = {id: string; law: string; section: string; group: string; ref: string; shortRef: string; title: string; text: string; pages: number[];
  tags: string[]; always: boolean; standards: string[]; sheet: Sheet; area: string};
export type Question = {tag: string; label: string; hint: string; condition: string};
export type CatalogData = {laws: {id: string; short: string; code: string; pdf: string}[]; requirements: CatalogReq[]; questions: Question[]; schede: string; docTypes: Record<string, string>};

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const r = await fetch('/api' + path, {credentials: 'same-origin', ...init});
  const text = await r.text();
  let data: unknown = null;
  try { data = text ? JSON.parse(text) : null; } catch { /* risposta non JSON */ }
  if (!r.ok) throw new Error((data as {error?: string; message?: string} | null)?.message || (data as {error?: string} | null)?.error || `Errore ${r.status}`);
  return data as T;
}
const json = (method: string, body?: unknown): RequestInit => ({method, headers: {'Content-Type': 'application/json'}, body: body === undefined ? undefined : JSON.stringify(body)});

export const api = {
  status: () => req<Status>('/status'),
  saveProfile: (p: Profile) => req<Profile>('/setup/profile', json('POST', p)),
  startOllama: () => req<{running: boolean}>('/setup/start-ollama', json('POST')),
  pull: () => req<Status>('/setup/pull', json('POST')),
  auto: () => req<Status>('/setup/auto', json('POST')),
  deleteAi: () => req<{freedBytes: number}>('/setup/delete-ai', json('POST')),
  catalog: () => req<CatalogData>('/catalog'),
  list: () => req<DossierRow[]>('/fascicoli'),
  create: (d: Partial<Fascicolo>) => req<Fascicolo>('/fascicoli', json('POST', d)),
  get: (id: string) => req<DossierFull>(`/fascicoli/${id}`),
  update: (id: string, d: Partial<Fascicolo>) => req<Fascicolo>(`/fascicoli/${id}`, json('PUT', d)),
  remove: (id: string) => req(`/fascicoli/${id}`, json('DELETE')),
  restore: (id: string) => req(`/fascicoli/${id}/restore`, json('POST')),
  upload: (id: string, files: File[]) => { const f = new FormData(); files.forEach(x => f.append('file', x)); return req<Doc[]>(`/fascicoli/${id}/documents`, {method: 'POST', body: f}); },
  removeDoc: (id: string, docId: string) => req<Doc[]>(`/fascicoli/${id}/documents/${docId}`, json('DELETE')),
  setDocTypes: (id: string, docId: string, types: string[]) => req<Doc[]>(`/fascicoli/${id}/documents/${docId}/types`, json('PUT', {types})),
  propose: (id: string) => req<Answer[]>(`/fascicoli/${id}/profile/propose`, json('POST')),
  confirmProfile: (id: string, values: Record<string, boolean | null | undefined>) => req<Fascicolo>(`/fascicoli/${id}/profile`, json('PUT', {values})),
  run: (id: string, fresh = false) => req<Fascicolo>(`/fascicoli/${id}/run${fresh ? '?fresh=true' : ''}`, json('POST')),
  stop: (id: string) => req<Fascicolo>(`/fascicoli/${id}/stop`, json('POST')),
  review: (id: string, rid: string, body: {points?: (string | null)[]; confirmed?: boolean; note?: string; applicability?: 'TRUE' | 'FALSE' | 'AUTO'}) => req<DossierFull>(`/fascicoli/${id}/results/${rid}`, json('PUT', body)),
  docUrl: (id: string, docId: string) => `/api/fascicoli/${id}/documents/${docId}`,
  openDoc: (id: string, docId: string) => req<{opened: string}>(`/fascicoli/${id}/documents/${docId}/open`, json('POST')),
  openLaw: (law: string) => req<{opened: string}>(`/normativa/${law}/open`, json('POST')),
  openUrl: (url: string) => req<{opened: string}>('/open-url', json('POST', {url})),
  saveReport: (id: string) => req<{path: string; name: string}>(`/fascicoli/${id}/report/save`, json('POST')),
  reportUrl: (id: string) => `/api/fascicoli/${id}/report.pdf`,
};

export const pct = (v: number) => Math.round(v * 100) + '%';
export const running = (r: Run) => ['queued', 'indexing', 'running'].includes(r.state);
export const OUT: Record<Outcome, [string, string]> = {OK: ['Soddisfatto', 'ok'], PART: ['Parziale', 'part'], NC: ['Possibile non conformità', 'nc'], INS: ['Senza evidenze', 'und'], NA: ['Non applicabile', 'na'], INT: ['A carico dell\'integratore', 'na']};
export const ANS: Record<'S' | 'C' | 'I', [string, string]> = {S: ['Supportato', 'ok'], C: ['Smentito', 'nc'], I: ['Senza evidenze', 'und']};
/** Colore del motivo di un gap. */
export const MOTIVE: Record<string, string> = {'Possibile non conformità': 'nc', 'Documento assente': 'und', 'Evidenza mancante': 'und', 'Applicabilità da verificare': 'part', 'A carico dell\'integratore': 'na'};
export const gb = (b: number) => (b / 1e9).toFixed(1).replace('.', ',') + ' GB';

export const reasonLabel = (reason?: string) => ({
  DOC_MISSING: 'Documento assente: nel fascicolo non c\'è un documento del tipo atteso',
  NO_EVIDENCE: 'Evidenza mancante: i documenti del tipo atteso non contengono la prova richiesta',
  INTEGRATOR: 'A carico dell\'integratore: per una quasi-macchina questo punto spetta a chi realizza la macchina finale',
  POSSIBLE_NC: 'Possibile non conformità: da confermare',
  APPLICABILITY_UNRESOLVED: 'Punto non valutato: applicabilità da verificare',
  NO_RELEVANT_PASSAGE: 'Nessun brano pertinente riconosciuto dalla ricerca',
  INSUFFICIENT_EVIDENCE: 'Brani recuperati, ma insufficienti a dimostrare il punto',
}[reason || ''] || 'Nessuna evidenza riconosciuta');
