# RegMac Desktop

Applicazione desktop per il **pre-assessment** di macchine rispetto al Regolamento Macchine (UE) 2023/1230 e al Cyber Resilience Act (UE) 2024/2847, con AI locale (Ollama). Nessun dato esce dal computer.

## Installazione (macOS)

1. Apri il file `.dmg` e trascina **RegMac** in Applicazioni.
2. Primo avvio: l'app non è firmata, quindi macOS la blocca. Clic destro su RegMac → **Apri** → **Apri**. In alternativa: Impostazioni di Sistema → Privacy e sicurezza → «Apri comunque».
3. RegMac si apre nella sua finestra, come ogni altra applicazione. Chiudendo la finestra il programma termina, e con lui il motore interno.

Al primo avvio:
- **profilo**: nome e organizzazione per l'intestazione dei report;
- **preparazione automatica dell'AI**, senza clic:
  - se Ollama è installato, RegMac lo usa (e lo avvia se è spento);
  - se manca, lo scarica dalle release ufficiali (`OllamaInstaller`), verifica l'impronta SHA-256, lo estrae nella cartella dati e lo avvia con 4 richieste in parallelo; lo spegne alla chiusura;
  - scarica i modelli mancanti `qwen3:8b` (5,2 GB) e `nomic-embed-text` (274 MB), mostrando l'avanzamento.

Requisiti consigliati: 16 GB di RAM, circa 7 GB liberi su disco per i modelli.

## Uso

1. **Nuova macchina**: dati della macchina e documenti (PDF con testo, DOCX, TXT, MD).
2. **Profilo proposto dall'AI**: ogni risposta ha la frase che la giustifica. Quelle non determinate sono evidenziate e vanno verificate.
3. **Pre-valutazione**: prosegue in sottofondo, circa 2 s per requisito. I primi esiti si possono già rivedere.
4. **Requisiti**: per ogni punto di controllo vedi la risposta dell'AI e la citazione. Puoi correggere; Invio conferma, J/K scorrono.
5. **Gap** e **Report PDF**: lo score è la media sui requisiti applicabili della quota di punti supportati, con il massimo raggiungibile. Sotto il 30% di copertura delle evidenze il fascicolo è «non valutabile».

## Dati

| Sistema | Cartella dati |
|---|---|
| macOS | `~/Library/Application Support/RegMac` |
| Windows | `%APPDATA%\RegMac` |
| Linux | `~/.local/share/regmac` |

Ogni fascicolo è una cartella dentro `fascicoli/`. Per un backup basta copiare la cartella dati con RegMac chiuso.

## Compilare l'installatore

```bash
desktop/build.sh            # sul sistema in uso: .dmg (macOS), .msi/.exe (Windows), .deb/.AppImage (Linux)
```

Requisiti per compilare: JDK 21, Node.js, Rust. Windows (.msi) e Linux (.deb/.AppImage) si creano con gli stessi passi sul rispettivo sistema; vanno ancora preparati.

## Struttura

- `tauri/` — finestra nativa (Tauri, Rust):
  - avvia il motore Java incluso, con porta casuale e token;
  - mostra la schermata di caricamento;
  - alla chiusura spegne il motore.
- `app/` — motore Java (Spring Boot), con:
  - `Engine`: motore di pre-valutazione;
  - `Ollama`: client dell'AI, avvio di Ollama e download dei modelli;
  - `Views`: calcolo di score e gap;
  - `ReportPdf`: report;
  - `AccessFilter`: accesso solo dal browser aperto dall'app.
- `ui/` — interfaccia React; la build finisce dentro il jar.
- `../data/normative/schede.json` — schede di verifica: 16 riviste, le altre generate con `scripts/generate_schede.py` e da rivedere.

## Limiti di questa versione

- PDF scansionati (senza testo) non ancora letti.
- Schede di verifica in gran parte in bozza.
- Il modello da 8 miliardi di parametri sbaglia: per questo ogni esito mostra la citazione e si può correggere.
- Installatore non firmato (firma ad hoc): avvisi di Gatekeeper / SmartScreen.
- Senza finestra nativa il motore si può avviare anche da solo (`java -jar regmac-desktop.jar`): in questo caso l'interfaccia si apre nel browser.
