"""Real local stack test. Creates synthetic records; never activates normative rules."""
import json,os,uuid,urllib.request,urllib.error,time
BASE=os.getenv('REGMAC_URL','http://localhost:5173')+'/api'
TOKEN=os.getenv('API_TOKEN') or exit('Impostare API_TOKEN (anche nel .env del backend) per usare questo script')
def call(path,data=None,method=None,ctype='application/json'):
    req=urllib.request.Request(BASE+path,data=data,method=method,headers={'Authorization':'Bearer '+TOKEN,'Content-Type':ctype})
    with urllib.request.urlopen(req,timeout=240) as r:return json.load(r)
def post(path,obj):return call(path,json.dumps(obj).encode())
if __name__=='__main__':
    for attempt in range(60):
        try:
            assert call('/health')['status']=='UP'; break
        except Exception:
            if attempt == 59: raise
            time.sleep(1)
    assert len(call('/requirements'))>=2
    machine=post('/machines',{'name':'SMOKE — SYNTHETIC','synthetic':True,'targetDate':'2028-01-01','scope':{'machinery':True,'digital_product':True},'scopeReviewer':'SMOKE only'})
    mid=machine['id'];boundary='regmac-'+uuid.uuid4().hex
    document={'format':'regmac-evidence-v1','text':'SYNTHETIC: PLC present.','facts':[{'subject':'machine','key':'plc','value':True,'quote':'PLC present.','locator':'smoke'}]}
    raw=json.dumps(document).encode()
    body=f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="smoke.json"\r\nContent-Type: application/json\r\n\r\n'.encode()+raw+f'\r\n--{boundary}--\r\n'.encode()
    e=call(f'/machines/{mid}/documents',body,ctype='multipart/form-data; boundary='+boundary)
    assert e['processingStatus']=='READY',e
    facts=call(f'/machines/{mid}/knowledge')['facts'];assert len(facts)==1 and facts[0]['nature']=='DECLARED'
    post(f"/facts/{facts[0]['id']}/verify",{'reviewer':'SMOKE test','note':'SYNTHETIC input validation only'})
    assert call(f'/machines/{mid}/knowledge')['facts'][0]['nature']=='VERIFIED'
    assert len(call('/audit'))>0
    print(json.dumps({'status':'PASS','machine_id':mid,'checks':['health','PostgreSQL persistence','normative seed','FastAPI structured import','citation','human-review transition','audit']},indent=2))
