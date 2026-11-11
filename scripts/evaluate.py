"""Offline evaluation and temperature fitting on EXPERT-labeled independent datasets.
JSONL rows: {"probabilities":{"SUPPORTED":0.8,...},"gold":"SUPPORTED","abstain":false}.
Never evaluate calibration on the same data used to fit it.
"""
import argparse,json,math
from pathlib import Path
def temperature(probabilities,t):
    if t<=0:raise ValueError('temperature must be positive')
    logits={k:math.log(max(v,1e-12))/t for k,v in probabilities.items()}
    top=max(logits.values());exps={k:math.exp(v-top) for k,v in logits.items()};z=sum(exps.values())
    return {k:v/z for k,v in exps.items()}
def validate(rows):
    if not rows:raise ValueError('empty dataset')
    for r in rows:
        p=r['probabilities']
        if r['gold'] not in p or any(not math.isfinite(v) or not 0<=v<=1 for v in p.values()) or abs(sum(p.values())-1)>.01:raise ValueError('invalid labeled probabilities')
def metrics(rows,t=1.):
    validate(rows);n=len(rows);correct=accepted=accepted_correct=fp=neg=0;brier=nll=ece=0.;bins=[[] for _ in range(10)]
    for r in rows:
        p=temperature(r['probabilities'],t);pred=max(p,key=p.get);gold=r['gold'];confidence=p[pred];right=pred==gold
        correct+=right;neg+=gold!='SUPPORTED';fp+=pred=='SUPPORTED' and gold!='SUPPORTED' and not r.get('abstain',False)
        accepted+=not r.get('abstain',False);accepted_correct+=right and not r.get('abstain',False)
        brier+=sum((v-(k==gold))**2 for k,v in p.items());nll-=math.log(max(p[gold],1e-12));bins[min(int(confidence*10),9)].append((confidence,right))
    for bucket in bins:
        if bucket:ece+=len(bucket)/n*abs(sum(x[0] for x in bucket)/len(bucket)-sum(x[1] for x in bucket)/len(bucket))
    return {'n':n,'accuracy':correct/n,'selective_accuracy':accepted_correct/accepted if accepted else None,'abstention_rate':1-accepted/n,'false_positive_rate_accepted_supported':fp/neg if neg else None,'brier':brier/n,'nll':nll/n,'ece_10_bins':ece}
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('dataset',type=Path);p.add_argument('--fit',type=Path,help='Separate calibration JSONL file');p.add_argument('--output',type=Path,default=Path('evaluation.json'));a=p.parse_args()
    read=lambda path:[json.loads(s) for s in path.read_text().splitlines() if s.strip()]
    rows=read(a.dataset);t=1.
    if a.fit:
        if a.fit.resolve()==a.dataset.resolve():raise ValueError('Calibration and evaluation files must differ')
        calibration=read(a.fit);validate(calibration)
        t=min([.1+i*.05 for i in range(199)],key=lambda x:metrics(calibration,x)['nll'])
    result={'temperature':t,'evaluation':metrics(rows,t),'raw_evaluation':metrics(rows),'serving_approved':False,'note':'Bind to model digest, question schema and expert approval before serving. This file does not enable automatic compliance.'}
    a.output.write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
