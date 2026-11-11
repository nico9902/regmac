import base64
import json
from pathlib import Path
import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.models import validate_answers, local_url, answer_schema
from app.contracts import Task
from app.understanding import extract
from app.contracts import ExtractInput
client = TestClient(app)
ROOT = Path(__file__).resolve().parents[2]
def task():
    return Task(requirement_id="R",requirement_version="1.0.0",control_id="C",applicability="APPLICABLE",machine_state={},evidence=[],questions=[{"id":"support","type":"CHOICE","instructions":"test","criteria":{"SUPPORTED":"positive","CONTRADICTED":"negative","INSUFFICIENT":"missing"}}, {"id":"binary","type":"NOUL","instructions":"test"}, {"id":"quality","type":"SCORE","instructions":"test","levels":["low","high"]}])
def valid():
    return {"support":{"probabilities":{"SUPPORTED":.2,"CONTRADICTED":.1,"INSUFFICIENT":.7}},"binary":{"noul":.2},"quality":{"probabilities":{"0":.7,"1":.3}}}
def test_structured_import_is_explicit_not_ai():
    raw=(ROOT/'data/demo/questionario-sintetico.json').read_bytes()
    out=client.post('/extract',json={"name":"demo.json","content_base64":base64.b64encode(raw).decode()})
    assert out.status_code==200
    assert out.json()['extractor']=='structured-import (no AI)'
    assert len(out.json()['facts'])==6
    assert all(f['quote'] in out.json()['text'] for f in out.json()['facts'])
def test_fake_quote_rejected():
    data={"format":"regmac-evidence-v1","text":"PLC","facts":[{"key":"remote_access","value":True,"quote":"unseen"}]}
    out=client.post('/extract',json={"name":"test.json","content_base64":base64.b64encode(json.dumps(data).encode()).decode()})
    assert out.status_code==422
def test_model_outage_abstains(monkeypatch):
    monkeypatch.setenv('OLLAMA_URL','http://127.0.0.1:9')
    out=client.post('/decide',json=task().model_dump()).json()
    assert out['status']=='UNAVAILABLE' and out['abstain'] and not out['answers']
def test_primitive_validation():
    out=validate_answers(task(),valid())
    assert out['support']['choice']=='INSUFFICIENT'
    assert out['binary']['noul']==.2
    assert out['quality']['score']=='0'
@pytest.mark.parametrize('bad',[float('nan'),-1,1.1,True])
def test_bad_probability_rejected(bad):
    data=valid();data['binary']['noul']=bad
    with pytest.raises(ValueError):validate_answers(task(),data)
def test_wrong_distribution_rejected():
    data=valid();data['support']['probabilities']['SUPPORTED']=.9
    with pytest.raises(ValueError):validate_answers(task(),data)
def test_no_external_model_endpoint():
    with pytest.raises(ValueError):local_url('https://api.example.org')
def test_missing_laya_fails_closed(monkeypatch):
    monkeypatch.setenv('DECISION_PROVIDER','laya');monkeypatch.setenv('LAYA_MODEL_PATH','/nonexistent-regmac-model')
    out=client.post('/decide',json=task().model_dump()).json()
    assert out['status']=='UNAVAILABLE' and out['abstain']
def test_ollama_adapter_contract_with_explicit_test_double(monkeypatch):
    from app.models import OllamaDecision, Ollama
    monkeypatch.setattr(Ollama,'structured',lambda *args,**kwargs:valid())
    out=OllamaDecision().predict(task())
    assert out.status=='OK' and not out.calibrated and out.abstain
    assert set(answer_schema(task())['properties'])=={'support','binary','quality'}
def test_cloud_model_rejected(monkeypatch):
    monkeypatch.setenv('OLLAMA_MODEL','qwen-cloud')
    out=client.post('/decide',json=task().model_dump()).json()
    assert out['status']=='INVALID_OUTPUT'
