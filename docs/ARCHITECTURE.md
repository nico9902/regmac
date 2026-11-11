# Contratti e invarianti

## Persistenza

Le due KB condividono PostgreSQL ma hanno tabelle e namespace distinti. Le tabelle relazionali impongono chiavi, riferimenti a macchina e documento; il payload è JSON serializzato in TEXT per mantenere identici i contratti Java e gli snapshot. È una scelta iniziale: query analitiche avanzate richiederanno colonne tipizzate/JSONB e indici mirati. `machine_entity` e `machine_relation` rappresentano componenti, sottosistemi, funzioni, controllo, interfacce, rete, protezioni, software, firmware e test.

Il documento originale è conservato con UUID come nome, mai con un percorso fornito dall'utente; limite 20 MB. Il digest SHA-256 consente verifica d'integrità. Le citazioni devono comparire nel testo estratto; questo controlla l'ancoraggio, non la verità del contenuto. I fatti nuovi non possono essere VERIFIED; tale promozione richiede un'operazione umana separata, un reviewer e una motivazione. Il ritiro è registrato nell'audit e non altera snapshot precedenti.

## Requirement Compiler

Accetta JSON strutturato e valida ID, versione, origine EUR-Lex, separazione testo/interpretazione, applicabilità, controlli, primitive e aggregazione. Non converte autonomamente prosa giuridica in regole attive. La compilazione validata viene memorizzata una volta. L'attivazione verifica hash e presenza dell'estratto nello snapshot locale, quindi registra il reviewer. L'hash dimostra integrità locale, non autenticità del sito: la verifica editoriale della fonte resta umana. Una versione attiva non viene modificata; una modifica richiede nuovo ID versione.

Le due regole iniziali coprono solo identificazione del software di sicurezza e alcuni controlli sugli accessi remoti. Non chiamare il loro esito «conformità al regolamento».

## Applicabilità

La data target è scelta dall'utente. L'ambito `machinery`/`digital_product` è attestato da un revisore. Le condizioni tecniche, es. `remote_access`, richiedono un fatto VERIFIED univoco; sconosciuto o conflittuale produce UNKNOWN. L'assenza di informazioni non vale FALSE. In questo primo modello un fatto booleano deve riguardare il soggetto `machine`; i fatti su singoli componenti non bastano per il controllo globale.

`OUTSIDE_DATE` e `NOT_APPLICABLE` restano distinguibili nel task e nella motivazione, anche se entrambi si aggregano in NOT_APPLICABLE. Non costituiscono un'esenzione da obblighi esterni ai controlli selezionati. La procedura di aggiornamento scope è auditata.

## Decision Task e motore

Il builder seleziona per chiave del controllo e condizione di applicabilità, dalla sola macchina corrente. Non passa l'intero manuale o l'intera legge. Il record include versione del requisito, controllo, stato, citazioni e domande. Gli adapter restituiscono distribuzioni validate o errore esplicito. Gli output Ollama sono giudizi probabilistici auto-dichiarati; Laya è un diverso modello locale con teste decisionali. Non trasferiamo promesse di calibrazione da benchmark generici alla sicurezza delle macchine.

Ordine delle regole deterministiche:

1. Esclusione di ambito/data confermata → NOT_APPLICABLE.
2. Applicabilità sconosciuta → UNDETERMINED.
3. Valori contraddittori per il controllo → UNDETERMINED, anche se una fonte è verificata.
4. Test macchina negativo verificato → NOT_SATISFIED.
5. Test macchina positivo verificato → SATISFIED.
6. Informazioni solo dichiarative/inferite o assenti → UNDETERMINED.

La probabilità AI viene conservata e mostrata per aiutare la revisione, ma non può sostituire le condizioni probatorie. Un'interruzione del modello non cancella un test umano già verificato. Questi sono controlli booleani; SCORE e NOUL non diventano soglie di conformità.

Aggregazione ALL: escludere i controlli non applicabili; qualsiasi fallimento esplicito prevale; tutti positivi → SATISFIED; alcuni positivi e altri ignoti → PARTIALLY_SATISFIED; tutti ignoti → UNDETERMINED; tutti esclusi → NOT_APPLICABLE. Elenco vuoto → UNDETERMINED.

## Agenti

Machine Understanding esegue parsing locale, estrazione vincolata allo schema, verifica delle citazioni e inserimento dichiarativo. L'import di un questionario `regmac-evidence-v1` è deterministico ed esplicitamente etichettato «no AI»; consente l'esercizio end-to-end senza pesi.

Active Compliance raggruppa i controlli irrisolti per chiave, recupera tutte le evidenze pertinenti già nel task e propone test/richieste. La ricerca semantica è una capacità esplicita dell'utente/API, non altera automaticamente le evidenze decisionali. Una risposta collega un documento pronto e avvia una nuova valutazione con le stesse versioni. Non modifica regole né promuove da solo una macchina a conforme. Le richieste storiche restano consultabili; la UI mostra quelle dell'ultimo assessment.

## Ricerca

Namespace pgvector `machine` e `normative`, isolamento per owner e modello embedding, indice cosine HNSW a 768 dimensioni. Indicizzazione esplicita; in assenza del modello embedding si restituisce indisponibilità, non una ricerca lessicale spacciata per semantica. La UI offre anche filtro strutturato locale dei fatti. I risultati semantici sono suggerimenti: gli ID delle fonti vanno risolti nella KB prima di usarli in una decisione.

## Confini operativi

Chiamate sincrone e transazioni per assessment; adatto a pochi controlli, non a pipeline di grandi dimensioni. I task troppo grandi vengono rifiutati senza troncamento silenzioso. Il budget Laya è conservativo a caratteri, da sostituire con conteggio sul tokenizer qualificato. File caricati possono rimanere orfani in caso di rollback DB: serve un processo di riconciliazione per produzione. Audit append-only tramite API, non WORM rispetto all'amministratore DB.
