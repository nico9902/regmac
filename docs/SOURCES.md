# Fonti e verifica di fattibilità — 10 novembre 2026

## Norme

- [Regolamento Macchine, EUR-Lex](https://eur-lex.europa.eu/eli/reg/2023/1230/en): consultato l'estratto del punto 1.1.9 dell'allegato III; il catalogo mostrava una versione consolidata del 27 luglio 2026. Il seed cita soltanto una breve porzione e non incorpora l'intero testo consolidato.
- [Rettifica delle date, GU del 4 luglio 2023](https://eur-lex.europa.eu/eli/reg/2023/1230/corrigendum/2023-07-04/oj/eng/pdf) e [sintesi ufficiale EUR-Lex](https://eur-lex.europa.eu/EN/legal-content/summary/machinery-safety-requirements.html?fromSummary=24): applicazione generale dal 20 gennaio 2027, con eccezioni per specifiche disposizioni.
- [CRA, testo ufficiale EUR-Lex](https://eur-lex.europa.eu/legal-content/EN/TXT/PDF/?uri=CELEX%3A32024R2847): allegato I, parte I, punto 2(d), protezione degli accessi. Il controllo remoto è una derivazione operativa parziale, da validare rispetto alla valutazione dei rischi dell'art. 13(2).
- [CRA, Commissione europea](https://digital-strategy.ec.europa.eu/en/policies/cyber-resilience-act): calendario applicativo distinto dagli obblighi di segnalazione. Il seed non implementa l'intero calendario.

Gli endpoint di download integrale EUR-Lex hanno restituito contenuti vuoti o challenge JavaScript nell'ambiente corrente. Nessun file vuoto è stato accettato come normativa. Gli snapshot `*-excerpt.txt` sono esplicitamente estratti, con URL, lingua, data e hash. Prima dell'uso reale acquisire gli atti integrali e le rettifiche dalla fonte ufficiale, verificare le versioni applicabili e approvare nuove versioni dei controlli.

## Modelli e parsing

- [Qwen3:8b nel catalogo Ollama](https://ollama.com/library/qwen3:8b): variante locale 8B disponibile. API chat locale.
- [Qwen3-VL:8b nel catalogo Ollama](https://registry.ollama.com/library/qwen3-vl:8b) — vedere il [catalogo Qwen3-VL](https://ollama.com/library/qwen3-vl:8b): input testuale/immagine e requisito Ollama 0.12.7.
- [Docling: opzioni avanzate e uso offline](https://github.com/docling-project/docling/blob/main/docs/usage/advanced_options.md): prefetch degli artifact; disabilitazione servizi remoti. Configurazione predisposta, parsing con pesi non eseguito.
- [Laya, repository richiesto](https://github.com/NandhaKishorM/laya): documenta inferenza locale, percorsi checkpoint, Python >=3.10, CHOICE/NOUL/SCORE e output probabilistici. Interfaccia `Agent(...).predict(...)`. L'adapter usa il percorso locale, non Router con download automatici. Nessuna equivalenza scientifica con Jev è presunta.

Laya è fattibile a livello di interfaccia e deployment locale secondo la documentazione. Rimangono da qualificare dipendenze/pesi effettivi, distribuzioni SCORE specifiche del checkpoint, tokenizer, prestazioni e calibrazione sul dataset industriale. Non riportiamo i benchmark dichiarati dal progetto come risultati ottenuti su RegMac.

## Riproducibilità

Versionare in una futura qualifica: commit Laya, lock degli extra Python, digest dei pesi e delle immagini Docker, config di inferenza, schema domande, prompt, dataset di calibrazione e held-out. Il codice di questa prima versione registra nome modello/provider e risposte; il digest esatto del runtime non è ancora archiviato nel report.

## Aggiornamento: PDF integrali forniti dall’utente

Il 10 novembre 2026 sono stati acquisiti localmente i due PDF in italiano, senza download esterni: `CELEX_32023R1230_IT_TXT.pdf` (102 pagine) e `OJ_L_202402847_IT_TXT.pdf` (81 pagine). Le intestazioni e i metadati identificano le pubblicazioni originali in GU del 29 giugno 2023 e del 20 novembre 2024. Non si attesta che incorporino rettifiche o aggiornamenti successivi.

L’estrazione Poppler conserva tutte le 183 pagine, senza pagine vuote, con 882.372 caratteri di testo. PDF originali e digest sono collegati ai record persistiti; il testo estratto è un ausilio alla consultazione, mentre impaginazione e tabelle vanno verificate sull’originale. I precedenti paragrafi sui download bloccati descrivono il tentativo iniziale, ora superato dall’acquisizione dei file dell’utente.
