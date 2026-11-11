# Verifiche eseguite — 10 novembre 2026

| Verifica | Risultato effettivo |
| --- | --- |
| Python: `pytest ai-service/tests -q` | **15 passati** |
| Java: `mvn test` | **15 passati**: 9 unitari + 6 integrazione |
| Integrazione Java–Python | FastAPI reale su porta temporanea, H2 isolato; import strutturato, task, modello indisponibile, engine, report e nuove evidenze |
| TypeScript + build Vite | **Riusciti** |
| Build Docker Compose | **Riuscita**, backend Java 21.0.12 |
| Avvio quattro servizi | PostgreSQL, FastAPI, Spring Boot e Nginx avviati |
| Smoke su PostgreSQL reale | **PASS**, eseguito anche dopo aggiornamento delle immagini |
| pgvector | Estensione **0.8.7**, tabella/indice inizializzati; distanza cosine di vettori identici = 0 |
| HTTP frontend e health API | Risposta valida su `http://localhost:5173` |
| Verifica visiva/browser | **Non eseguita**: host di browser automation indisponibile |
| Qwen3 e Qwen3-VL, pesi reali | **Non eseguita**: Ollama/pesi non installati |
| Laya, pesi reali | **Non eseguita**: checkpoint e dipendenze opzionali non installati |
| Docling PDF/OCR con pesi reali | **Non eseguita**: extra/artifact non installati |
| Ricerca semantica con embedding reale | **Non eseguita**: modello embedding assente; infrastruttura pgvector verificata separatamente |
| Validazione esperti / calibrazione industriale | **Non eseguita**, nessun dataset esperto disponibile |

## Scenari coperti

- Mancanza di evidenza → UNDETERMINED, non non-conformità automatica.
- Dichiarazioni o probabilità elevate senza test verificato → nessuna soddisfazione automatica.
- Test positivo/negativo verificato e aggregazione parziale.
- Contraddizioni → astensione del processo e richiesta di revisione; ritiro motivato con audit.
- Ambito escluso → NOT_APPLICABLE; ambito sconosciuto → UNDETERMINED.
- Modello irraggiungibile → UNAVAILABLE, risposte vuote e astensione; nessuna simulazione in produzione.
- Documento con citazione inesistente e collegamento a un'altra macchina rifiutati.
- Bozze non attivabili senza reviewer e non usabili per assessment.
- Risposta a una richiesta → nuovo assessment, precedente snapshot invariato.
- Token REST obbligatorio; endpoint cloud rifiutati dagli adapter.
- CHOICE, NOUL e SCORE: schema e validazione delle distribuzioni.
- Metriche di accuratezza, falsi positivi, astensione e temperatura su dati sintetici di unit test.

Un solo test dell'adapter Ollama usa un test double esplicito per verificare il contratto; non viene contato come inferenza reale. La pipeline d'integrazione importa il formato di questionario strutturato e ne dichiara `extractor = structured-import (no AI)`. L'assenza dei pesi è testata realmente tramite endpoint locale irraggiungibile.

Lo smoke test crea soltanto record contrassegnati SYNTHETIC. Le due versioni normative iniziali restano DRAFT nel database di lavoro: le attivazioni nei test Java avvengono soltanto nel database H2 isolato con reviewer `TEST`.

## Avvisi osservati

- Test locali Java eseguiti con JDK host 26 e target 21; avvisi della toolchain Maven relativi ad API JDK deprecate. Il container è compilato ed eseguito con Java 21.
- Deprecation warning Starlette/AnyIO nei test Python, senza fallimenti.
- Nessun benchmark di latenza, accuratezza industriale o capacità GPU è stato misurato.

## Ripetizione

```sh
.venv/bin/pytest ai-service/tests -q
mvn -f backend/pom.xml test
npm --prefix frontend run build
docker compose up -d --build
python3 scripts/smoke.py
```

I log di questa sessione sono in `.tools/` (ignorati dal versionamento); i report JUnit sono in `backend/target/surefire-reports/`. Lo stato riproducibile è descritto qui; i dati temporanei di test non fanno parte del corpus normativo.

## Importazione integrale delle normative — aggiornamento

- Test Java aggiornati: **16 passati** (9 unitari + 7 integrazione).
- Nuovo test: import delle due edizioni, 183 pagine, hash degli originali, ricerca e idempotenza.
- Build TypeScript/Vite e immagini Docker aggiornate: riuscite.
- `python3 scripts/verify_normative.py`: **PASS sullo stack PostgreSQL reale**. Confrontate tramite API tutte le 102 pagine MR e 81 pagine CRA con il corpus estratto; verificati entrambi i download PDF tramite SHA-256 e la ricerca testuale.
- Le due regole operative esistenti rimangono DRAFT: nessuna approvazione automatica derivata dall'import.
- Controllo visivo delle prime pagine dei PDF effettuato con rendering Poppler. Non è stata ripetuta una verifica visuale del browser.
