"""Genera docs/prototipo/index.html inserendo nel template i requisiti valutabili del catalogo."""
import json, pathlib
root = pathlib.Path(__file__).resolve().parents[2]
d = json.loads((root / 'data/normative/requirements.json').read_text())
reqs = [{k: r.get(k) for k in ('id','law','section','group','ref','title','text','pages','tags','always')} | {'standards': [s.get('code') for s in r.get('standards', [])][:4]}
        for r in d['requirements'] if r['assessable']]
data = {'requirements': reqs, 'standardsCount': len({s for r in reqs for s in r['standards']})}
tpl = (root / 'docs/prototipo/template.html').read_text()
out = tpl.replace('/*DATA*/null', json.dumps(data, ensure_ascii=False).replace('</', '<\\/')).replace('/*APP*/', (root / 'docs/prototipo/app.js').read_text())
(root / 'docs/prototipo/index.html').write_text(out)
print(len(reqs), 'requisiti,', len(out)//1024, 'KB')
