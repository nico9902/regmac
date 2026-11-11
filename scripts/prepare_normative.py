"""Extract supplied legal PDFs locally with Poppler, preserving physical page boundaries.
Run before restarting the backend. Repeated imports are idempotent by PDF SHA-256.
"""
import hashlib,json,re,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]/'data/normative'
SOURCES=[('CELEX_32023R1230_IT_TXT.pdf','MR','Regolamento Macchine (UE) 2023/1230','2023-06-29','https://eur-lex.europa.eu/eli/reg/2023/1230/oj'),('OJ_L_202402847_IT_TXT.pdf','CRA','Cyber Resilience Act (UE) 2024/2847','2024-11-20','https://eur-lex.europa.eu/eli/reg/2024/2847/oj')]
def prepare():
    docs=[]
    for filename,law,title,published,url in SOURCES:
        path=ROOT/filename
        info=subprocess.check_output(['pdfinfo',str(path)],text=True)
        count=int(re.search(r'^Pages:\s+(\d+)',info,re.M)[1])
        text=subprocess.check_output(['pdftotext','-layout','-enc','UTF-8',str(path),'-']).decode('utf-8')
        pages=text.split('\f')
        if not pages[-1].strip():pages.pop()
        if len(pages)!=count or any(not page.strip() for page in pages):raise ValueError(f'{filename}: incomplete extraction, OCR review required')
        marker='2023/1230' if law=='MR' else '2024/2847'
        if marker not in pages[0] or 'REGOLAMENTO' not in pages[0]:raise ValueError('Unexpected document title')
        digest=hashlib.sha256(path.read_bytes()).hexdigest()
        docs.append({'id':law+'-'+digest[:16],'law':law,'title':title,'language':'IT','publicationDate':published,'edition':'Pubblicazione originale in Gazzetta ufficiale; non testo consolidato','filename':filename,'sha256':digest,'sourceUrl':url,'provenance':'PDF fornito dall’utente; identità e completezza di estrazione controllate localmente','extractor':'Poppler pdftotext -layout','pageCount':count,'pages':[{'number':i+1,'text':p.strip()} for i,p in enumerate(pages)]})
    output=ROOT/'corpus.json'
    output.write_text(json.dumps(docs,ensure_ascii=False,indent=2)+'\n')
    for d in docs:print(f"{d['law']}: {d['pageCount']} pages, {sum(len(p['text']) for p in d['pages'])} characters, SHA-256 {d['sha256']}")
if __name__=='__main__':prepare()
