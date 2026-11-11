"""Create an explicitly synthetic machine and import a questionnaire. Never approve rules automatically."""
import json
import os
from pathlib import Path
import urllib.request
import uuid
import time
BASE=os.getenv("REGMAC_URL","http://localhost:5173")+"/api"
TOKEN=os.getenv("API_TOKEN") or exit("Impostare API_TOKEN (anche nel .env del backend) per usare questo script")
def request(path, data=None, content_type="application/json"):
    req=urllib.request.Request(BASE+path,data=data,headers={"Authorization":"Bearer "+TOKEN,"Content-Type":content_type})
    with urllib.request.urlopen(req,timeout=240) as r:return json.load(r)
def post(path,data):return request(path,json.dumps(data).encode())
if __name__=='__main__':
    for attempt in range(60):
        try:
            request('/health'); break
        except Exception:
            if attempt == 59: raise
            time.sleep(1)
    machine=post('/machines',{"name":"RM-01 · Linea dimostrativa","synthetic":True,"targetDate":"2028-01-01","scope":{"machinery":True,"digital_product":True},"scopeReviewer":"DEMO — ambito sintetico"})
    mid=machine['id']
    boundary='regmac-'+uuid.uuid4().hex
    raw=(Path(__file__).resolve().parents[1]/'data/demo/questionario-sintetico.json').read_bytes()
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="questionario-sintetico.json"\r\nContent-Type: application/json\r\n\r\n'.encode()+raw+f'\r\n--{boundary}--\r\n'.encode())
    document=request(f'/machines/{mid}/documents',body,'multipart/form-data; boundary='+boundary)
    entities=[]
    for name,kind in [('PLC dimostrativo','CONTROL_SYSTEM'),('HMI dimostrativa','INTERFACE'),('Ethernet','NETWORK'),('Gateway remoto','COMPONENT')]:entities.append(post(f'/machines/{mid}/entities',{"type":kind,"name":name,"attributes":{"synthetic":True}}))
    for target in entities[1:]:post(f'/machines/{mid}/relations',{"sourceId":entities[0]['id'],"targetId":target['id'],"type":"CONNECTED_TO"})
    print(json.dumps({"machine_id":mid,"document_status":document['processingStatus'],"next":"Apri la UI; revisiona ambito, controlli e test sintetici. Poi esegui assessment. Nessuna approvazione normativa automatica."},indent=2,ensure_ascii=False))
