"""Versione veloce della pre-valutazione: stesse regole di run.py, molte meno parole generate e richieste in parallelo.

Differenze rispetto a run.py:
1. risposte compatte: il modello indica il NUMERO del brano, non ricopia la citazione (la citazione è il brano stesso);
2. verifica raggruppata: una sola chiamata per requisito per tutte le prove trovate;
3. applicabilità: ogni condizione distinta si valuta una sola volta per macchina, all'inizio;
4. filtro: i punti senza alcun brano abbastanza simile diventano "senza evidenze" senza chiamare il modello;
5. parallelismo: più requisiti insieme (serve OLLAMA_NUM_PARALLEL >= WORKERS).
Uso: python3 experiments/preassessment/run_fast.py [macchina ...]
"""
import json, sys, time, concurrent.futures as cf
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from run import HERE, ROOT, MODEL, SEED, TOP_K, call, embed, cos, chunks, find  # noqa: E402

import os
WORKERS, MIN_SIM, MAX_TEXT = int(os.environ.get('WORKERS', 4)), 0.62, 600
PAIR_SIM = float(os.environ.get('PAIR_SIM', 0.68))
OPTS = {"temperature": 0, "seed": SEED, "num_ctx": 4096}

DECIDE = {"type": "object", "properties": {"r": {"type": "array", "items": {"type": "object", "properties": {
    "n": {"type": "integer"}, "a": {"type": "string", "enum": ["S", "C", "I"]}, "b": {"type": "integer"}, "p": {"type": "number"}},
    "required": ["n", "a", "b", "p"]}}}, "required": ["r"]}
VERIFY = {"type": "object", "properties": {"v": {"type": "array", "items": {"type": "string", "enum": ["S", "C", "N"]}}}, "required": ["v"]}
COND = {"type": "object", "properties": {"v": {"type": "string", "enum": ["T", "F", "U"]}, "b": {"type": "integer"}}, "required": ["v", "b"]}


SALT = os.environ.get("SALT", "")


def chat(content, schema, ctx=4096):
    if SALT:  # solo per le misure: impedisce a Ollama di riusare testo già elaborato
        content = f"[sessione {SALT}]\n" + content
    r = call("/api/chat", {"model": MODEL, "stream": False, "think": False, "format": schema, "options": {**OPTS, "num_ctx": ctx},
                           "messages": [{"role": "user", "content": content}]})
    return json.loads(r["message"]["content"]), r.get("eval_count", 0)


def numbered(passages):
    return "\n".join(f"[{i + 1}] {p['text']}" for i, p in enumerate(passages))


PAIRS_PER_POINT = 3


ONE = {"type": "object", "properties": {"v": {"type": "string", "enum": ["S", "C", "N"]}}, "required": ["v"]}
POOL = cf.ThreadPoolExecutor(WORKERS)


def judge_one(claim, text):
    v, tok = chat(f"""Punto di controllo: {claim}
Frase da un documento della macchina: «{text}»
La frase, da sola, dimostra in modo specifico che il punto è soddisfatto/vero (S), dimostra che non lo è (C), oppure non riguarda specificamente questo punto o è una dichiarazione generica (N)? Rispondi {{"v":"S"|"C"|"N"}}.""", ONE, 4096)
    return v.get("v", "N"), tok


def judge(pairs, req=None):
    """Una chiamata breve per coppia (affermazione, frase), eseguite in parallelo; il modello scrive una lettera."""
    res = list(POOL.map(lambda pr: judge_one(*pr), pairs))
    return [r[0] for r in res], sum(r[1] for r in res)


def resolve(verdicts, cands):
    """Esito di un punto dalle sue coppie: contraddetto prevale, poi supportato, altrimenti senza evidenze."""
    for want in ("C", "S"):
        for v, c in zip(verdicts, cands):
            if v == want:
                return want, c
    return "I", None


def condition(cond, cands):
    verdicts, tok = judge([(cond, c["text"]) for c in cands])
    ans, src = resolve(verdicts, cands)
    return {"S": "TRUE", "C": "FALSE"}.get(ans, "UNKNOWN"), (src or {}).get("text", ""), tok


def assess(req, sheet, cands_per_point, weak):
    pts = [{"point": t, "answer": "I", "p": None, "quote": "", "note": "nessun brano pertinente" if i in weak else ""} for i, t in enumerate(sheet["points"])]
    pairs, index = [], []
    for i, cands in enumerate(cands_per_point):
        if i in weak:
            continue
        for c in cands[:1] + [c for c in cands[1:] if c.get("sim", 1) >= PAIR_SIM]:
            pairs.append((sheet["points"][i], c["text"]))
            index.append((i, c))
    if not pairs:
        return pts, 0
    verdicts, tok = judge(pairs, req)
    for i in range(len(pts)):
        vs = [v for v, (j, _) in zip(verdicts, index) if j == i]
        cs = [c for (j, c) in index if j == i]
        ans, src = resolve(vs, cs)
        if src:
            pts[i].update(answer=ans, quote=src["text"], passage=src["id"], p=round(vs.count(ans) / len(vs), 2))
    return pts, tok


def run(machine):
    cat = {r["id"]: r for r in json.loads((ROOT / "data/normative/requirements.json").read_text())["requirements"]}
    sheets = json.loads((HERE / "schede.json").read_text())["schede"]
    t0 = time.time()
    ch = chunks(machine)
    vec = embed([f"{c['section']}: {c['text']}" for c in ch], "d")
    # tutte le query di ricerca in un'unica chiamata di embedding
    items = list(sheets.items())
    queries = [r["title"] + ". " + " ".join(s["points"]) for r, s in ((cat[i], s) for i, s in items)]
    point_q = [p for _, s in items for p in s["points"]]
    conds = sorted({s["condition"] for _, s in items if s.get("condition")})
    qv = embed(queries + point_q + conds, "q")
    req_v, pt_v, cond_v = qv[:len(queries)], qv[len(queries):len(queries) + len(point_q)], qv[len(queries) + len(point_q):]
    top = lambda q: [c for c, _ in sorted(zip(ch, vec), key=lambda cv: -cos(q, cv[1]))[:TOP_K]]

    with cf.ThreadPoolExecutor(WORKERS) as ex:
        near = lambda q, n=PAIRS_PER_POINT: [{**c, "sim": sc} for sc, c in sorted(((cos(q, v), c) for c, v in zip(ch, vec)), key=lambda x: -x[0])[:n]]
        cond_res = {c: condition(c, near(q)) for c, q in zip(conds, cond_v)}
        jobs, k = [], 0
        for (rid, sheet), rq in zip(items, req_v):
            n = len(sheet["points"])
            weak = {i for i in range(n) if max(cos(pt_v[k + i], v) for v in vec) < MIN_SIM}
            jobs.append((rid, sheet, [near(pt_v[k + i]) for i in range(n)], weak))
            k += n
    outs = [assess(cat[j[0]], j[1], j[2], j[3]) for j in jobs]

    results = []
    for (rid, sheet, passages, weak), (pts, tok) in zip(jobs, outs):
        app, quote, _ = cond_res.get(sheet.get("condition"), ("TRUE", "", 0))
        na = app != "TRUE"
        n, S, I = len(pts), sum(p["answer"] == "S" for p in pts), sum(p["answer"] == "I" for p in pts)
        results.append({"id": rid, "ref": cat[rid]["ref"].replace("All. III, punto ", ""), "title": cat[rid]["title"], "applicability": app,
                        "applies_quote": quote, "na": na, "value": None if na else S / n, "max": None if na else (S + I) / n, "points": pts, "tokens_out": tok})
    a = [r for r in results if not r["na"]]
    return {"machine": machine, "mode": "fast", "workers": WORKERS, "applicable": len(a), "requirements": len(results),
            "score": sum(r["value"] for r in a) / len(a), "max": sum(r["max"] for r in a) / len(a),
            "coverage": sum(p["answer"] != "I" for r in a for p in r["points"]) / sum(len(r["points"]) for r in a),
            "skipped_points": sum(p["note"] == "nessun brano pertinente" for r in results for p in r["points"]),
            "tokens_out": sum(r["tokens_out"] for r in results), "seconds": round(time.time() - t0, 1)}, results


if __name__ == "__main__":
    (HERE / "results").mkdir(exist_ok=True)
    for m in sys.argv[1:] or ["pb80", "tr30"]:
        s, res = run(m)
        print(f"{m}: score {s['score']:.0%}  max {s['max']:.0%}  copertura {s['coverage']:.0%}  applicabili {s['applicable']}/{s['requirements']}  "
              f"punti saltati {s['skipped_points']}  token generati {s['tokens_out']}  tempo {s['seconds']} s")
        (HERE / "results" / f"fast-{m}-{time.strftime('%H%M%S')}.json").write_text(json.dumps({"summary": s, "results": res}, ensure_ascii=False, indent=1))
