from pathlib import Path
import json
import sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'ai-service'))
from app.contracts import Task,Decision,Extraction
if __name__=='__main__':
    (ROOT/'contracts').mkdir(exist_ok=True)
    for model in (Task,Decision,Extraction):
        (ROOT/'contracts'/f'{model.__name__}.schema.json').write_text(json.dumps(model.model_json_schema(),indent=2)+'\n')
