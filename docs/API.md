# API REST

Base locale: `http://localhost:5173/api`. Header obbligatorio `Authorization: Bearer <token di sessione>` (da `/auth/login`) oppure `Bearer <API_TOKEN>` se configurato, eccetto `/health` e `/auth/status|login|setup`. Ruoli: `READER` solo GET; `ASSESSOR` lettura e scrittura; `ADMIN` anche `/users`. 401 = sessione mancante o scaduta (12 ore); 403 = ruolo insufficiente. La UI usa il proxy Nginx o Vite. Nessuna CORS pubblica. Il servizio Python è interno; schema OpenAPI disponibile su `/docs` solo se esposto esplicitamente in sviluppo.

| Metodo e percorso | Funzione |
| --- | --- |
| GET /health | Liveness applicativa e database (non disponibilità modelli) |
| GET, POST /machines | Elenco e creazione macchina |
| PUT /machines/{id}/scope | Revisione ambito e data target con reviewer |
| GET /machines/{id}/knowledge | Macchina, fatti, documenti, entità, relazioni |
| POST /machines/{id}/documents | Multipart `file`; acquisizione e processing |
| GET /documents/{id} | Originale in download |
| POST /documents/{id}/retry | Riprova processing fallito |
| POST /machines/{id}/facts | Inserimento ancorato a citazione; solo DECLARED/INFERRED |
| POST /facts/{id}/verify | Review `{reviewer,note}`; fatto booleano macchina |
| POST /facts/{id}/retract | Ritiro motivato, conservato nell'audit |
| POST /machines/{id}/entities | Componente/sottosistema/funzione/test ecc. |
| POST /machines/{id}/relations | Relazione tra entità della stessa macchina |
| GET, POST /requirements | Versioni e nuova bozza compilata/validata |
| POST /requirements/{id}@{version}/activate | Attivazione umana con `{reviewer,note}` |
| POST /machines/{id}/assessments | `{versions:["MR-SOFTWARE-ID@0.1.0", ...]}` |
| GET /machines/{id}/assessments | Storico snapshot |
| GET /assessments/{id}/report | Report JSON scaricabile |
| GET /machines/{id}/requests | Richieste anche storiche |
| POST /requests/{id}/respond | `{evidenceId}` e nuova valutazione |
| POST /machines/{id}/index | Indicizzazione semantica esplicita dei fatti |
| POST /machines/{id}/search | `{query}` nel namespace/owner macchina |
| POST /requirements/index | Indicizzazione semantica wiki |
| POST /requirements/search | `{query}` nella wiki |
| GET /audit | Registro operazioni |

Il client deve controllare `processingStatus` dopo un upload: la conservazione del file può riuscire anche quando l'estrazione AI fallisce. In tale caso si conserva `FAILED` ed errore, senza inventare fatti. Il retry su READY è rifiutato. Uno schema o una citazione non validi vengono rifiutati. La UI non copre ancora l'editing completo del grafo, l'import di nuove regole o tutte le operazioni editoriali: sono disponibili attraverso queste API.

Esempio di creazione:

```json
{"name":"Macchina A","synthetic":false,"targetDate":"2028-01-01","scope":{"machinery":true,"digital_product":true},"scopeReviewer":"Nome revisore"}
```

Fatto manuale:

```json
{"evidenceId":"UUID","subject":"machine","key":"remote_access","value":true,"quote":"Citazione letterale dal testo estratto","locator":"pagina/sezione","nature":"DECLARED"}
```

I contratti DecisionTask e Decision sono in `contracts/` e nei record Java/Pydantic. Il servizio AI non scrive regole normative, non attiva fatti e non determina lo stato legale finale.

## Corpus normativo integrale

- `GET /normative-documents`: metadati delle edizioni importate, numero pagine e SHA-256.
- `GET /normative-documents/{id}/pages/{page}`: testo della pagina fisica del PDF.
- `GET /normative-documents/search?q=...`: ricerca testuale locale parametrizzata (massimo 50 pagine).
- `GET /normative-documents/{id}/pdf`: download del PDF, con controllo del digest prima della risposta.

Preparazione: `python3 scripts/prepare_normative.py`, quindi `docker compose restart backend`. Import idempotente per SHA-256; nessuna attivazione di requisiti derivati. Per la prima installazione della funzionalità ricostruire le immagini con `docker compose up -d --build`.

## Wiki per requisiti e checklist di valutazione

- `GET /requirement-catalog`: catalogo corrente (SHA-256, regolamenti, sezioni, profili, 502 voci di cui 351 valutabili). Generato da `scripts/build_requirements.py` in `data/normative/requirements.json`; importato all'avvio solo se i PDF di origine corrispondono agli SHA-256 dichiarati. Un nuovo catalogo aggiorna le voci per ID e conserva quelle già valutate.
- `GET /machines/{id}/checklists`: checklist della macchina con conteggi per esito.
- `POST /machines/{id}/checklists`: crea una bozza. Corpo facoltativo `{"title","profile":[...],"details":{...},"basedOn":"<id>"}`; con `basedOn` copia dati ed esiti (nuova revisione). Il profilo predefinito deriva dall'ambito della macchina.
- `GET /checklists/{id}`: testata ed esiti (`items` per ID requisito).
- `PUT /checklists/{id}`: aggiorna titolo, profilo, dati macchina/valutatore, conclusioni (solo `DRAFT`).
- `PUT /checklists/{id}/items/{requirementId}`: `{"status":"COMPLIANT|PARTIAL|NON_COMPLIANT|NOT_APPLICABLE|TO_ASSESS","evidence","standard","note","action","owner","due":"AAAA-MM-GG"}`. Solo voci valutabili del catalogo; i cambi di esito vanno nell'audit trail.
- `POST /checklists/{id}/close`: `{"password","note"}` (firma: password dell'utente corrente; non consentita con API_TOKEN). Rifiutata se restano requisiti del profilo da valutare, N/A/parziali/non conformi senza rilievo o parziali/non conformi senza azione correttiva. Dopo la chiusura la checklist è immutabile.
- `GET /checklists/{id}/report`: rapporto (esito complessivo, conteggi, indice di conformità, righe con requisito ed esito). `GET /checklists/{id}/report.csv`: checklist in CSV (separatore `;`, UTF-8 con BOM per Excel).

Esito complessivo: `NON_COMPLIANT` se esiste una non conformità; altrimenti `INCOMPLETE` se restano requisiti da valutare; `PARTIAL` se esistono conformità parziali; altrimenti `COMPLIANT`.


## Accesso e utenti

- `GET /auth/status` → `{"setupRequired":bool}`; `POST /auth/setup` `{"username","displayName","password"}` crea il primo amministratore (solo se non esistono utenti) e restituisce una sessione.
- `POST /auth/login` `{"username","password"}` → `{"token","expiresAt","user"}`; `POST /auth/logout`; `GET /auth/me`; `POST /auth/password` `{"current","next"}`.
- `GET /users`, `POST /users` `{"username","displayName","password","role"}`, `PUT /users/{id}` `{"displayName","role","active","password"}` (solo ADMIN; resta sempre almeno un amministratore attivo; disattivazione e reset password chiudono le sessioni).
- Password: PBKDF2-HMAC-SHA256 (210.000 iterazioni, sale per utente); sessioni conservate solo come SHA-256 del token. Ogni evento dell'audit (`GET /audit`) riporta `actor`.

## Checklist: evidenze, firma, PDF, suggerimenti

- Esito di un requisito (`PUT /checklists/{id}/items/{requirementId}`): oltre ai campi testuali, `evidenceRefs: [{"evidenceId","locator"}]` con documenti della stessa macchina; nome e SHA-256 sono presi dal server. Il server registra `assessedBy` e l'impronta del testo normativo valutato (`textSha256`).
- `GET /checklists/{id}` include `textChanged`: requisiti valutati su un testo poi modificato dal catalogo. Vanno risalvati prima della firma.
- `GET /checklists/{id}/report.pdf`: checklist firmata → PDF archiviato, verificato contro l'impronta registrata in `signature.reportSha256`; bozza → PDF generato al momento con filigrana.
- `POST /checklists/{id}/items/{requirementId}/suggest`: proposta del modello locale (`OK`, `UNAVAILABLE`, `NO_EVIDENCE`) con citazioni verificate letteralmente negli estratti; salvata come `suggestion` dell'esito e nell'audit, mai applicata automaticamente.
- `GET /requirement-catalog` include `standards` per requisito, `standardsNotice` e `changes` (aggiunti, rimossi, testi cambiati rispetto al catalogo precedente).
