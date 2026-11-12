# Schede di verifica v2 — formato e regole di scrittura

Le schede servono a RegMac, uno strumento di **pre-assessment** documentale: un modello AI locale piccolo (8 miliardi di parametri) legge brani dei documenti di una macchina e decide, punto per punto, se un brano **prova** il punto (S), lo **smentisce** (C) o no. Il modello è affidabile solo se il punto è concreto e i criteri sono espliciti. Le vecchie schede (`old_points`) erano bozze generate da quel modello: vaghe e piene di riferimenti a documenti che spesso non esistono. Riscrivile.

## Formato di uscita

Un oggetto JSON con una chiave per requisito (`id` del requisito):

```json
{
  "MR-III-1.2.4.3": {
    "condition": "",
    "points": [
      {
        "q": "Le istruzioni descrivono l'arresto di emergenza: categoria di arresto e modalità di ripristino",
        "doc": "istruzioni",
        "ok": "categoria di arresto (0, 1 o 2) o descrizione del ripristino manuale del comando",
        "no": "solo il rimando a una norma; avvertenze generiche sull'emergenza",
        "smentita": "il manuale dichiara che il comando di emergenza non si blocca o che il ripristino riavvia la macchina"
      }
    ]
  }
}
```

### Campi di ogni punto
- `q` (obbligatorio): l'affermazione da verificare, **concreta e verificabile su un documento**, in italiano, max ~170 caratteri. Inizia dicendo **quale documento** e **cosa contiene**. Niente formule vaghe ("la documentazione include misure adeguate", "è conforme"). Una cosa per punto.
- `doc` (obbligatorio): il tipo di documento in cui l'evidenza va cercata. Uno solo tra:
  - `istruzioni` — manuale d'uso, installazione, manutenzione (le istruzioni del fabbricante)
  - `specifiche` — scheda tecnica, dati tecnici, caratteristiche dichiarate
  - `dichiarazione` — dichiarazione UE di conformità o di incorporazione
  - `valutazione_rischi` — valutazione dei rischi del prodotto (Reg. Macchine)
  - `schemi` — schemi elettrici, pneumatici, idraulici, di comando
  - `disegni` — disegni d'assieme, layout, disegni di dettaglio
  - `rapporti_prova` — rapporti di prova, misure (rumore, vibrazioni…), calcoli, validazione delle funzioni di sicurezza, collaudi
  - `targa` — targa/marcatura sul prodotto (foto o descrizione della marcatura)
  - `cybersicurezza` — documentazione CRA: valutazione del rischio di cibersicurezza, SBOM, politica di gestione delle vulnerabilità, divulgazione coordinata, periodo di supporto, aggiornamenti di sicurezza
  - `certificati` — certificati/attestati di organismi notificati, sistemi qualità, procedure di valutazione della conformità
- `ok` (obbligatorio): cosa, nel brano, **conta come prova** (breve, concreto: un dato, una procedura, una funzione, un elenco).
- `no` (obbligatorio): cosa **non** conta come prova pur sembrando pertinente (tipicamente: frasi generiche, il solo rimando a una norma, un'istruzione che chiede a qualcun altro di farlo, un argomento vicino ma diverso).
- `smentita` (facoltativo): **solo se esiste una formulazione documentale plausibile che contraddice esplicitamente il punto** (es. "il manuale vieta…", "la protezione si può disattivare senza password", "l'arresto è di categoria 2 non monitorato"). Se non c'è un caso concreto, ometti il campo: senza `smentita` il punto non potrà mai risultare contraddetto. L'assenza di informazioni NON è una smentita.
- `quasi` (facoltativo): metti `"integratore"` se, quando il prodotto è una **quasi-macchina** (da incorporare), questo punto è responsabilità di chi integra/realizza la macchina finale e non del fabbricante della quasi-macchina (es. dichiarazione CE di conformità della macchina completa, marcatura CE della macchina finale, valutazione dei rischi della cella/installazione, ripari e dispositivi della cella, istruzioni della macchina finale). Ricorda che il fabbricante di una quasi-macchina deve comunque: redigere la documentazione tecnica pertinente (All. IV parte B), le istruzioni di assemblaggio, la dichiarazione di incorporazione, e rispettare i requisiti essenziali che dichiara applicati.
- `num` (facoltativo, raro): solo se il requisito fissa un **valore numerico** verificabile, `{"what": "grandezza", "unit": "unità", "min": numero, "max": numero}` (uno dei due limiti può mancare). Non inventare limiti che il testo normativo non dà.

### Campo `condition`
Condizione di applicabilità del requisito, verificata sui documenti (es. "Il prodotto impiega dispositivi laser"). Lascia `""` se il requisito si applica sempre nel suo perimetro. Mantieni `old_condition` se è sensata; non aggiungere condizioni ovvie.

## Regole
1. 2–4 punti per requisito (1 se il requisito è semplice). Meglio pochi punti verificabili che molti vaghi.
2. Basati sul **testo normativo** (`text`), non sulle vecchie bozze: usale solo come spunto.
3. Scegli `doc` realistico: per i requisiti di progettazione (All. III) l'evidenza documentale tipica è nelle `istruzioni` (descrizione di funzioni, dispositivi, limiti), nelle `specifiche`, nei `rapporti_prova` o nella `valutazione_rischi`. Non mettere tutto in `valutazione_rischi`: usala quando il punto chiede proprio l'analisi del rischio.
4. Non scrivere punti che chiedono di dimostrare un'attività eseguita in un'installazione specifica se il requisito riguarda il prodotto.
5. Requisiti CRA: i punti riguardano il prodotto con elementi digitali (software, interfacce, aggiornamenti, vulnerabilità); `doc` di solito `cybersicurezza`, `istruzioni` (informazioni per l'utente, All. II CRA) o `dichiarazione`.
6. Requisiti di obbligo/procedura (MR-OBB, CRA-OBB) e di documentazione (MR-DOC, CRA-DOC): i punti chiedono la presenza e il contenuto del documento previsto (es. dichiarazione con i dati obbligatori, istruzioni nella lingua, marcatura).
7. Italiano chiaro, senza abbreviazioni oscure. Nessun commento fuori dal JSON.

Scrivi il file di uscita come JSON valido (UTF-8) e verificalo con `python3 -c "import json;json.load(open('<file>'))"`.
