"""Confronto di formulazioni della domanda di giudizio (coppia punto–frase) su casi noti."""
import json, sys, time, concurrent.futures as cf
sys.path.insert(0, '.')
from run import call
ONE = {"type": "object", "properties": {"v": {"type": "string", "enum": ["S", "C", "N"]}}, "required": ["v"]}
OLD = lambda c, t: f"""Punto di controllo: {c}
Frase da un documento della macchina: «{t}»
La frase, da sola, dimostra in modo specifico che il punto è soddisfatto/vero (S), dimostra che non lo è (C), oppure non riguarda specificamente questo punto o è una dichiarazione generica (N)? Rispondi {{"v":"S"|"C"|"N"}}."""
NEW = lambda c, t: f"""Devi dire se una frase tratta dai documenti di una macchina conferma o smentisce un'affermazione.
S = la frase afferma esplicitamente che l'affermazione è vera.
C = la frase afferma esplicitamente il contrario dell'affermazione.
N = la frase parla d'altro, oppure è generica ("conforme alle norme"), oppure non basta per decidere.
Esempi:
Affermazione: La macchina si sposta durante il lavoro. Frase: «La macchina va fissata al pavimento con quattro tasselli.» → C
Affermazione: La macchina è una quasi-macchina. Frase: «L'avviamento avviene premendo il pulsante di marcia.» → N
Affermazione: I ripari fissi richiedono un utensile per essere rimossi. Frase: «I carter sono fissati con viti che richiedono una chiave.» → S
Affermazione: Il comando di emergenza è chiaramente visibile. Frase: «La macchina è dotata di pulsante di emergenza ed è conforme alle norme.» → N
Ora:
Affermazione: {c}
Frase: «{t}»
Rispondi {{"v":"S"|"C"|"N"}}."""
CASES = [
 ("Il prodotto è una quasi-macchina destinata a essere incorporata in un'altra macchina e non funziona da sola", "L'avviamento della macchina è possibile solo mediante azione volontaria sul pulsante di marcia.", "N"),
 ("La macchina si sposta durante il lavoro, è semovente o trainata", "La macchina deve essere posata su pavimento in calcestruzzo e fissata con i quattro tasselli chimici M16 forniti, secondo lo schema di ancoraggio riportato in figura 2.1.", "C"),
 ("La macchina è destinata a lavori sotterranei", "L'avviamento della macchina è possibile solo mediante azione volontaria sul pulsante di marcia.", "N"),
 ("La macchina contiene software o elettronica programmabile collegabile a reti o ad altri dispositivi, oppure consente l'accesso remoto", "HMI con porta Ethernet per teleassistenza del fabbricante.", "S"),
 ("La macchina è destinata alla lavorazione del legno o di materiali con caratteristiche fisiche simili", "Non è destinata alla lavorazione di materiali diversi dai metalli in lamiera né all'uso da parte di più operatori contemporaneamente.", "C"),
 ("Uno o più dispositivi di arresto di emergenza sono raggiungibili da ogni postazione di lavoro", "La macchina è dotata di tre pulsanti di arresto di emergenza a fungo rosso su fondo giallo: uno sul pannello frontale, uno sul pedale mobile e uno sul lato posteriore, in prossimità della zona di scarico.", "S"),
 ("I comandi di arresto di emergenza sono chiaramente individuabili, ben visibili e rapidamente accessibili", "La macchina è dotata di pulsante di emergenza e conforme alle norme vigenti.", "N"),
 ("I dispositivi di comando sono chiaramente visibili e identificati, se del caso con pittogrammi", "Il pulsante di arresto generale porta la macchina in condizione di sicurezza; il comando di arresto ha sempre priorità sui comandi di avvio.", "N"),
 ("I ripari fissi si possono aprire o smontare solo con un utensile", "Per la sostituzione rapida della lama, il carter di protezione del disco si rimuove a mano svitando il pomello, senza bisogno di attrezzi.", "C"),
 ("Gli elementi mobili di trasmissione sono protetti da ripari fissi o da ripari mobili interbloccati", "La trasmissione a cinghia della pompa idraulica è racchiusa in un carter fisso.", "S"),
 ("Il dispositivo di isolamento è bloccabile (es. con lucchetto) in posizione di isolamento", "L'interruttore generale sezionatore, identificato da targa gialla, isola la macchina dalla rete elettrica ed è lucchettabile in posizione 0.", "S"),
 ("I dispositivi laser sono progettati per evitare radiazioni involontarie", "La macchina non impiega dispositivi laser.", "N"),
 ("La macchina impiega dispositivi laser", "La macchina non impiega dispositivi laser.", "C"),
 ("Il livello di emissione sonora è misurato e dichiarato", "Il livello di pressione acustica di emissione al posto operatore, misurato secondo EN ISO 11204, è 72 dB(A); il livello di potenza acustica è 84 dB(A).", "S"),
 ("La valutazione considera anche l'uso scorretto ragionevolmente prevedibile", "La valutazione dei rischi è stata eseguita secondo EN ISO 12100:2010 ed è documentata nel presente fascicolo tecnico.", "N"),
 ("Le istruzioni per l'uso sono fornite nella lingua del paese di utilizzo", "Sono stati considerati l'uso previsto e l'uso scorretto ragionevolmente prevedibile, tra cui il tentativo di inserire le mani oltre le barriere.", "N"),
]
def ask(p):
    r = call("/api/chat", {"model": "qwen3:8b", "stream": False, "think": False, "format": ONE, "options": {"temperature": 0, "seed": 42, "num_ctx": 4096}, "messages": [{"role": "user", "content": p}]})
    return json.loads(r["message"]["content"]).get("v")
for name, f in (("attuale", OLD), ("con esempi", NEW)):
    t = time.time()
    with cf.ThreadPoolExecutor(4) as ex: out = list(ex.map(lambda c: ask(f(c[0], c[1])), CASES))
    ok = sum(o == c[2] for o, c in zip(out, CASES))
    print(f"{name}: {ok}/{len(CASES)} corretti in {time.time() - t:.1f}s  errori: {[ (i+1, c[2], o) for i, (o, c) in enumerate(zip(out, CASES)) if o != c[2]]}")

# accordo tra le due formulazioni: S/C valgono solo se entrambe concordano
with cf.ThreadPoolExecutor(4) as ex:
    a = list(ex.map(lambda c: ask(NEW(c[0], c[1])), CASES))
    b = list(ex.map(lambda c: ask(OLD(c[0], c[1])), CASES))
agree = [x if x == y else "N" for x, y in zip(a, b)]
ok = sum(o == c[2] for o, c in zip(agree, CASES))
fp = [(i + 1, c[2], o) for i, (o, c) in enumerate(zip(agree, CASES)) if o != c[2] and o != "N"]
print(f"accordo: {ok}/{len(CASES)} corretti; errori gravi (S o C sbagliati): {fp}; errori: {[(i+1, c[2], o) for i, (o, c) in enumerate(zip(agree, CASES)) if o != c[2]]}")
