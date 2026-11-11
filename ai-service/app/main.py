from typing import Literal
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from .contracts import Task, Decision, ExtractInput
from .models import decide
from .understanding import extract
from . import search as semantic
from .suggest import SuggestInput, Suggestion, suggest as make_suggestion
app = FastAPI(title="RegMac Local AI", version="0.1.0")
@app.get("/health")
def health():
    return {"status":"UP", "inference_verified":False}
@app.post("/decide", response_model=Decision)
def decision(task: Task):
    return decide(task)
@app.post("/suggest", response_model=Suggestion)
def suggestion(data: SuggestInput):
    return make_suggestion(data)
@app.post("/extract")
def extraction(data: ExtractInput):
    try:
        return extract(data)
    except (ValueError, KeyError) as exc:
        raise HTTPException(422, str(exc)) from exc
    except Exception as exc:
        raise HTTPException(503, f"Local extraction unavailable: {type(exc).__name__}: {exc}") from exc
class Chunk(BaseModel):
    id: str
    text: str = Field(max_length=12000)
class IndexInput(BaseModel):
    namespace: Literal["machine","normative"]
    owner_id: str
    chunks: list[Chunk] = Field(max_length=500)
class SearchInput(BaseModel):
    namespace: Literal["machine","normative"]
    owner_id: str
    query: str = Field(min_length=1,max_length=2000)
@app.post("/index")
def index(data: IndexInput):
    try:
        return semantic.index(data.namespace,data.owner_id,[c.model_dump() for c in data.chunks])
    except Exception as exc:
        raise HTTPException(503, f"Index unavailable: {type(exc).__name__}") from exc
@app.post("/search")
def search(data: SearchInput):
    try:
        return semantic.search(data.namespace,data.owner_id,data.query)
    except Exception as exc:
        raise HTTPException(503, f"Semantic search unavailable: {type(exc).__name__}") from exc
