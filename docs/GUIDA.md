# Guida all'uso di RegMac

RegMac serve a valutare una macchina rispetto al Regolamento Macchine (UE) 2023/1230 e, se ha elementi digitali, al Cyber Resilience Act (UE) 2024/2847. Il risultato è un rapporto firmato con l'esito di ogni requisito, le evidenze usate e le azioni correttive.

Indirizzo: **http://localhost:5173** (o l'indirizzo comunicato dall'amministratore).

## 1. Accesso

- **Primo avvio**: chi installa l'app crea l'amministratore (nome utente, nome e cognome, password di almeno 10 caratteri).
- **Accessi successivi**: nome utente e password. La sessione dura 12 ore; poi l'app chiede di accedere di nuovo.
- **Cambio password**: in basso a sinistra, "Password". La password serve anche per firmare i rapporti: non condividerla.

| Ruolo | Cosa può fare |
|---|---|
| Amministratore | tutto, più la gestione degli utenti (sezione **Utenti**) |
| Valutatore | crea macchine e checklist, valuta, carica documenti, firma i rapporti |
| Lettore | consulta tutto, senza modificare |

## 2. Creare la macchina

1. **Nuova macchina** (in alto a destra): nome, data di riferimento della valutazione, regolamenti applicabili (Regolamento Macchine, CRA).
2. Spunta "Macchina di prova" solo per esercitazioni: le macchine di prova sono nascoste dall'elenco (si mostrano con "mostra macchine di prova").
3. La barra in alto in Assessment, Rapporto e Knowledge base indica la macchina su cui stai lavorando.

## 3. Caricare la documentazione

Knowledge base → **Carica documenti** (o direttamente dalla riga di un requisito, "Carica e collega"): manuale d'uso, fascicolo tecnico, schemi, valutazione del rischio, rapporti di prova, dichiarazioni dei componenti.

- Ogni file viene conservato con la sua impronta SHA-256: nel rapporto si può dimostrare quale versione è stata esaminata.
- Stato "Elaborato": testo e fatti estratti dall'AI locale. "Testo estratto (senza AI)": il testo è leggibile e citabile, ma l'AI non era disponibile (si può usare "Riprova" più tardi). I PDF scansionati senza testo richiedono l'AI.

## 4. Consultare i requisiti

**Wiki normativa → Requisiti e valutazione**: a sinistra l'indice (Allegato III, obblighi del fabbricante, documentazione tecnica, CRA), in alto la ricerca (anche per norma, es. "13849").

Per ogni requisito trovi:
- il testo ufficiale e il link **PDF ufficiale** alla pagina esatta;
- le **norme di riferimento suggerite**, con tipo (A, B, C, tecnica) e stato. "Armonizzata (dir. 2006/42/CE)" indica una norma citata in Gazzetta per la vecchia direttiva: fino al 19/01/2027 dà presunzione di conformità per la direttiva; per il nuovo Regolamento va verificato l'elenco che la Commissione pubblicherà;
- l'etichetta "Si applica in ogni caso" per i requisiti che non possono essere esclusi (es. 1.1.2, 1.7.3, 1.7.4);
- il riquadro di valutazione per la macchina selezionata.

## 5. Valutare: la checklist

**Assessment → Checklist requisiti** → **Crea checklist**.

1. **Dati e profilo**: compila modello, matricola, fabbricante, descrizione, uso previsto. Nel profilo scegli macchina o quasi-macchina, i capi dell'Allegato III pertinenti (alimentari, portatili, legno, mobilità, sollevamento…) e il CRA. Solo i requisiti del profilo entrano nella valutazione.
2. **Per ogni requisito** scegli l'esito:
   - **Conforme**: indica le evidenze e collega i documenti (con pagina o sezione).
   - **Parziale / Non conforme**: scrivi il rilievo e l'azione correttiva, con responsabile e scadenza.
   - **N/A**: motiva perché il pericolo o la funzione non esistono.
   - **Norme / metodi applicati**: "Inserisci" accanto alla norma suggerita la aggiunge al campo.
3. **Suggerimento AI** (facoltativo): legge i documenti caricati e propone un esito con le frasi citate. Le citazioni sono controllate parola per parola; senza prove la proposta è "evidenze insufficienti". L'esito lo decidi tu: "Applica l'esito proposto" lo registra a tuo nome.
4. Filtri utili: "Con avvisi" (manca qualcosa per poter firmare), "Testo aggiornato", ricerca libera anche nelle evidenze.

Tutto si salva automaticamente ("Salvato" accanto al nome della checklist).

## 6. Firmare e ottenere il rapporto

1. In fondo alla checklist, **Firma e chiudi**: inserisci la tua password.
2. La firma è rifiutata se restano requisiti da valutare, N/A o non conformità senza motivazione, non conformità senza azione correttiva, o requisiti il cui testo normativo è stato aggiornato dopo la valutazione.
3. Alla firma il sistema genera il **rapporto PDF**, lo archivia e ne registra l'impronta SHA-256. La checklist non è più modificabile.
4. **Rapporto finale** → "Scarica PDF firmato". Prima della firma puoi scaricare una bozza (con filigrana "BOZZA"), il CSV per Excel o stampare l'anteprima.

Il rapporto contiene: dati identificativi, ambito, esito complessivo e indice di conformità, tabelle per regolamento e sezione, non conformità con azioni correttive, requisiti non applicabili motivati, conclusioni, firma e la checklist completa con documenti e autori delle valutazioni.

**Verificare un PDF**: confronta la sua impronta SHA-256 (es. `shasum -a 256 rapporto.pdf`) con quella mostrata in fondo alla checklist firmata o nell'Audit trail.

## 7. Revisioni

Dopo le azioni correttive, o per una nuova verifica: **Nuova revisione**. Viene creata "Rev. 2 — …" con dati ed esiti della precedente; aggiorni solo ciò che è cambiato e firmi di nuovo. Le revisioni precedenti restano consultabili e immutate.

## 8. Quando cambia la normativa

Se l'amministratore aggiorna i testi (rettifiche, testo consolidato), nella Wiki compare un avviso con il numero di requisiti modificati. Nelle checklist in bozza i requisiti valutati sul testo precedente sono marcati "testo aggiornato": rileggili e risalva l'esito.

## 9. Amministrazione

- **Utenti**: crea utenti, cambia ruolo, disattiva (chiude subito le sessioni), reimposta password. Deve restare almeno un amministratore attivo.
- **Audit trail**: registro di tutte le operazioni con data e autore.
- **Backup**: `scripts/backup.sh` (database, documenti, rapporti, con impronte); ripristino con `scripts/restore.sh`. Pianificalo e conserva una copia altrove.

## Limiti da conoscere

- I testi sono le pubblicazioni originali in Gazzetta ufficiale, non consolidate.
- La suddivisione in requisiti e le norme suggerite sono un ausilio: non sostituiscono la valutazione del rischio del fabbricante né la procedura di valutazione della conformità (art. 25 Reg. 2023/1230, art. 32 Reg. 2024/2847) o l'intervento di un organismo notificato, quando richiesto.
- La firma è una firma elettronica semplice (utente + password, con impronta registrata), non una firma elettronica qualificata.
