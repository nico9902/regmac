// Tipi e chiamate verso il motore locale (stessa origine, cookie di sessione impostato all'avvio).
export type Profile = {name: string; organization: string};
export type Pull = {model: string; status: string; total: number; completed: number; error: string | null; done: boolean};
export type ModelRow = {name: string; role: string; approxBytes: number; installed: boolean; pull: Pull | null};
export type Status = {
  profile: Profile | null; ready: boolean; memoryGb: number; dataDir: string; os: string; schede: string; version: string;
  ollama: {running: boolean; version: string; installed: boolean; startedByApp: boolean}; models: ModelRow[];
  ollamaInstall: {status: string; total: number; completed: number; error: string | null; done: boolean} | null; ollamaAsset: string | null; ollamaOwn: boolean; aiBytes: number; aiDir: string;
};
export type Answer = {tag: string; verdict: 'TRUE' | 'FALSE' | 'UNKNOWN'; quote: string; doc: string | null; page: number | null; value: boolean};
export type Run = {state: 'idle' | 'queued' | 'indexing' | 'running' | 'done' | 'stopped' | 'interrupted' | 'error'; done: number; total: number;
  startedAt: string | null; finishedAt: string | null; message: string | null; model: string | null};
export type Fascicolo = {id: string; name: string; model: string; serial: string; year: string; maker: string; use: string; createdAt: string; updatedAt: string;
  profile: Answer[]; tags: string[]; profileConfirmed: boolean; run: Run; exportedAt: string | null};
export type Doc = {id: string; name: string; file: string; size: number; pages: number; sha256: string; passages: number; warning: string | null; addedAt: string};
export type Point = {text: string; answer: 'S' | 'C' | 'I'; quote: string | null; docId: string | null; doc: string | null; page: number | null; support: number | null; user: 'S' | 'C' | 'I' | null};
export type Result = {rid: string; applicability: 'TRUE' | 'FALSE' | 'UNKNOWN'; appQuote: string | null; appDoc: string | null; appPage: number | null;
  points: Point[]; confirmed: boolean; note: string | null; hash: string; at: string};
export type Sheet = {condition: string; points: string[]; status: string};
export type ReqView = {id: string; ref: string; title: string; law: string; group: string; area: string; always: boolean; outcome: Outcome | null;
  value: number | null; max: number | null; applicability: string | null; result: Result | null; sheet: Sheet};
export type Outcome = 'OK' | 'PART' | 'NC' | 'INS' | 'NA';
export type Summary = {total: number; done: number; confirmed: number; applicable: number; na: number; full: number; S: number; C: number; I: number;
  score: number; max: number; coverage: number; notEvaluable: boolean; gapsTech: number; gapsDoc: number; gapsApp: number};
export type Gap = {rid: string; ref: string; title: string; kind: string; priority: number; value: number; points: Point[]; condition: string | null};
export type Assessment = {summary: Summary; areas: {name: string; n: number; score: number; max: number}[]; gaps: Gap[]; docsNeeded: string[]; requirements: ReqView[]};
export type DossierRow = {fascicolo: Fascicolo; summary: Summary; documents: number};
export type DossierFull = {fascicolo: Fascicolo; documents: Doc[]; assessment: Assessment};
export type CatalogReq = {id: string; law: string; section: string; group: string; ref: string; shortRef: string; title: string; text: string; pages: number[];
  tags: string[]; always: boolean; standards: string[]; sheet: Sheet; area: string};
export type Question = {tag: string; label: string; hint: string; condition: string};
export type CatalogData = {laws: {id: string; short: string; code: string; pdf: string}[]; requirements: CatalogReq[]; questions: Question[]; schede: string};

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const r = await fetch('/api' + path, {credentials: 'same-origin', ...init});
  const text = await r.text();
  let data: unknown = null;
  try { data = text ? JSON.parse(text) : null; } catch { /* risposta non JSON */ }
  if (!r.ok) throw new Error((data as {error?: string; message?: string} | null)?.error || (data as {message?: string} | null)?.message || `Errore ${r.status}`);
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
  propose: (id: string) => req<Answer[]>(`/fascicoli/${id}/profile/propose`, json('POST')),
  confirmProfile: (id: string, values: Record<string, boolean>) => req<Fascicolo>(`/fascicoli/${id}/profile`, json('PUT', {values})),
  run: (id: string) => req<Fascicolo>(`/fascicoli/${id}/run`, json('POST')),
  stop: (id: string) => req<Fascicolo>(`/fascicoli/${id}/stop`, json('POST')),
  review: (id: string, rid: string, body: {points?: (string | null)[]; confirmed?: boolean; note?: string}) => req<DossierFull>(`/fascicoli/${id}/results/${rid}`, json('PUT', body)),
  docUrl: (id: string, docId: string) => `/api/fascicoli/${id}/documents/${docId}`,
  openDoc: (id: string, docId: string) => req<{opened: string}>(`/fascicoli/${id}/documents/${docId}/open`, json('POST')),
  openLaw: (law: string) => req<{opened: string}>(`/normativa/${law}/open`, json('POST')),
  openUrl: (url: string) => req<{opened: string}>('/open-url', json('POST', {url})),
  saveReport: (id: string) => req<{path: string; name: string}>(`/fascicoli/${id}/report/save`, json('POST')),
  reportUrl: (id: string) => `/api/fascicoli/${id}/report.pdf`,
};

export const pct = (v: number) => Math.round(v * 100) + '%';
export const running = (r: Run) => ['queued', 'indexing', 'running'].includes(r.state);
export const OUT: Record<Outcome, [string, string]> = {OK: ['Soddisfatto', 'ok'], PART: ['Parziale', 'part'], NC: ['Contraddetto', 'nc'], INS: ['Senza evidenze', 'und'], NA: ['Non applicabile', 'na']};
export const ANS: Record<'S' | 'C' | 'I', [string, string]> = {S: ['Supportato', 'ok'], C: ['Contraddetto', 'nc'], I: ['Senza evidenze', 'und']};
export const gb = (b: number) => (b / 1e9).toFixed(1).replace('.', ',') + ' GB';
