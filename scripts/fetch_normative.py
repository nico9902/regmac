"""Explicit online acquisition of public laws. Never run while processing industrial documents."""
import hashlib
import json
import urllib.request
from pathlib import Path
from datetime import datetime,timezone
TARGET=Path(__file__).resolve().parents[1]/'data/normative/full'
SOURCES={"machinery":"https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:02023R1230-20260727","cra":"https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32024R2847"}
if __name__=='__main__':
    TARGET.mkdir(exist_ok=True)
    for name,url in SOURCES.items():
        try:
            with urllib.request.urlopen(url,timeout=45) as response:data=response.read()
            if len(data)<10000 or b'ANNEX' not in data or b'JavaScript is disabled' in data:raise ValueError('No verified legal text returned; obtain the official file manually')
            (TARGET/(name+'.html')).write_bytes(data)
            (TARGET/(name+'.metadata.json')).write_text(json.dumps({"url":url,"retrieved_at":datetime.now(timezone.utc).isoformat(),"sha256":hashlib.sha256(data).hexdigest(),"review_status":"PENDING"},indent=2))
            print(name, 'downloaded, pending human review')
        except Exception as exc:print(name,'NOT ACQUIRED:',str(exc))
