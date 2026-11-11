"""Genera con l'AI locale (Ollama) le bozze delle schede di verifica per tutti i requisiti valutabili.

Ogni scheda: condizione di applicabilità facoltativa + 2–4 punti di controllo verificabili sui documenti.
Le schede scritte a mano (experiments/preassessment/schede.json) hanno la precedenza e sono marcate "rivista".
Uso: python3 scripts/generate_schede.py   → data/normative/schede.json (riprende da dove si era fermato)
"""
import json, re, sys, time, urllib.request, concurrent.futures as cf
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "data/normative/schede.json"
MODEL, SEED, WORKERS = "qwen3:8b", 42, 4
SCHEMA = {"type": "object", "properties": {"condition": {"type": "string"}, "points": {"type": "array", "items": {"type": "string"}}},
          "required": ["condition", "points"]}


def ask(req):
    prompt = f"""Prepari una scheda di verifica per un pre-assessment documentale di una macchina.
Requisito {req['ref']} — {req['title']} ({req['group']}):
{req['text'][:2500]}

Scrivi da 2 a 4 PUNTI DI CONTROLLO: affermazioni concrete e verificabili leggendo la documentazione tecnica della macchina
(manuale, valutazione dei rischi, schemi, dichiarazioni). Ogni punto: una frase in italiano, massimo 20 parole, formulata
in positivo come fatto da verificare (es. "Le istruzioni indicano il livello di pressione acustica al posto operatore").
Non copiare il testo di legge, non usare "deve", non inventare requisiti non presenti.
CONDIZIONE: se il requisito si applica solo quando la macchina ha una certa caratteristica (es. dispositivi laser, ripari
fissi, alimentazione elettrica, più modi di funzionamento), scrivila come frase sulla macchina (es. "La macchina impiega
dispositivi laser"); altrimenti lascia stringa vuota. Rispondi in JSON."""
    body = {"model": MODEL, "stream": False, "think": False, "format": SCHEMA, "options": {"temperature": 0, "seed": SEED, "num_ctx": 4096},
            "messages": [{"role": "user", "content": prompt}]}
    r = urllib.request.Request("http://localhost:11434/api/chat", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(r, timeout=300) as resp:
        out = json.loads(json.load(resp)["message"]["content"])
    pts = [re.sub(r"\s+", " ", p).strip().rstrip(".") for p in out.get("points", []) if isinstance(p, str) and 8 < len(p.strip()) < 220][:4]
    cond = (out.get("condition") or "").strip()
    return {"condition": cond if 8 < len(cond) < 200 else "", "points": pts}


def main():
    reqs = [r for r in json.loads((ROOT / "data/normative/requirements.json").read_text())["requirements"] if r["assessable"]]
    curated = json.loads((ROOT / "experiments/preassessment/schede.json").read_text())["schede"]
    done = json.loads(OUT.read_text())["schede"] if OUT.exists() else {}
    for rid, s in curated.items():
        done[rid] = {"condition": s.get("condition", ""), "points": s["points"], "status": "rivista"}
    todo = [r for r in reqs if r["id"] not in done]
    print(f"{len(reqs)} requisiti, {len(done)} già pronti, {len(todo)} da generare", flush=True)
    t0 = time.time()

    def save():
        OUT.write_text(json.dumps({"schema": "regmac-schede-v0.1", "model": MODEL,
                                   "note": "Bozze generate con AI locale da rivedere; 'rivista' = scritta o controllata da una persona.",
                                   "schede": dict(sorted(done.items()))}, ensure_ascii=False, indent=1))

    with cf.ThreadPoolExecutor(WORKERS) as ex:
        futs = {ex.submit(ask, r): r for r in todo}
        for i, f in enumerate(cf.as_completed(futs), 1):
            r = futs[f]
            try:
                s = f.result()
                if len(s["points"]) >= 1:
                    done[r["id"]] = {**s, "status": "bozza"}
            except Exception as e:  # noqa: BLE001
                print("errore", r["id"], e, file=sys.stderr)
            if i % 20 == 0:
                save()
                print(f"  {i}/{len(todo)}  {time.time() - t0:.0f}s", flush=True)
    save()
    print(f"fatto: {len(done)} schede in {time.time() - t0:.0f}s")


if __name__ == "__main__":
    main()
