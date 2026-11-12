"""Valida i lotti riscritti e li unisce in schede.json (formato v0.2). La versione precedente resta in schede-v1.json."""
import json, glob, os, sys
ALLOWED = {"istruzioni","specifiche","dichiarazione","valutazione_rischi","schemi","disegni","rapporti_prova","targa","cybersicurezza","certificati"}
here = os.path.dirname(os.path.abspath(__file__)); base = os.path.dirname(here)
req = [r for r in json.load(open(os.path.join(base, "requirements.json")))["requirements"] if r.get("assessable")]
ids = {r["id"] for r in req}
out, problems = {}, []
for f in sorted(glob.glob(os.path.join(here, "output", "lotto-*.json"))):
    d = json.load(open(f))
    for rid, sc in d.items():
        if rid not in ids: problems.append(f"{os.path.basename(f)}: id sconosciuto {rid}"); continue
        pts = sc.get("points") or []
        if not pts: problems.append(f"{rid}: nessun punto")
        for i, p in enumerate(pts):
            for k in ("q", "doc", "ok", "no"):
                if not isinstance(p.get(k), str) or not p[k].strip(): problems.append(f"{rid}[{i}]: manca {k}")
            if p.get("doc") not in ALLOWED: problems.append(f"{rid}[{i}]: doc non ammesso {p.get('doc')}")
            if p.get("quasi") not in (None, "integratore"): problems.append(f"{rid}[{i}]: quasi non ammesso")
            n = p.get("num")
            if n is not None and not (isinstance(n, dict) and n.get("unit") and (isinstance(n.get("min"), (int, float)) or isinstance(n.get("max"), (int, float)))):
                problems.append(f"{rid}[{i}]: num non valido {n}")
        out[rid] = {"condition": sc.get("condition", "") or "", "points": pts, "status": "bozza"}
missing = sorted(ids - set(out))
print(f"requisiti {len(out)}/{len(ids)}, punti {sum(len(v['points']) for v in out.values())}, mancanti {len(missing)}, problemi {len(problems)}")
for p in problems[:30]: print("  ", p)
if missing: print("  mancanti:", missing[:20])
if missing or problems: sys.exit(1)
old = os.path.join(base, "schede.json"); backup = os.path.join(base, "schede-v1.json")
if not os.path.exists(backup): os.rename(old, backup)
json.dump({"schema": "regmac-schede-v0.2", "note": "Schede strutturate (documento atteso, prova, smentita, responsabilità per quasi-macchine) scritte con AI e da rivedere da un esperto; la versione precedente è in schede-v1.json.",
           "schede": out}, open(old, "w"), ensure_ascii=False, indent=1)
print("scritto", old)
