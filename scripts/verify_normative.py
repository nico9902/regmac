"""Read-only verification of every imported page and the original PDF digests."""
import hashlib,json,os,time,urllib.request,urllib.parse
from pathlib import Path
BASE=os.getenv('REGMAC_URL','http://localhost:5173')+'/api'
TOKEN=os.getenv('API_TOKEN','local-development-only')
def fetch(path):
    with urllib.request.urlopen(urllib.request.Request(BASE+path,headers={'Authorization':'Bearer '+TOKEN}),timeout=15) as r:return r.read()
def read(path):return json.loads(fetch(path))
if __name__=='__main__':
    expected=json.loads((Path(__file__).resolve().parents[1]/'data/normative/corpus.json').read_text())
    for attempt in range(40):
        try:
            docs=read('/normative-documents')
            if len(docs)>=len(expected):break
        except Exception:
            if attempt==39:raise
        time.sleep(1)
    results=[]
    for d in expected:
        actual=next(x for x in docs if x['id']==d['id'])
        assert actual['pageCount']==d['pageCount']
        assert hashlib.sha256(fetch('/normative-documents/'+d['id']+'/pdf')).hexdigest()==d['sha256']
        for page in d['pages']:
            p=read(f"/normative-documents/{d['id']}/pages/{page['number']}")
            assert p==page,(d['id'],page['number'])
        results.append({'law':d['law'],'pages_verified':len(d['pages']),'pdf_sha256':d['sha256']})
    for word in ['macchine','cibersicurezza']:
        assert read('/normative-documents/search?q='+urllib.parse.quote(word))
    print(json.dumps({'status':'PASS','documents':results,'requirement_statuses':[r['status'] for r in read('/requirements')]},indent=2))
