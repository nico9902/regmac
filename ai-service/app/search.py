"""Two isolated semantic namespaces, local Ollama embeddings, PostgreSQL pgvector."""
import os
import math
import httpx
import psycopg
from .models import local_url
MODEL = os.getenv("EMBEDDING_MODEL", "nomic-embed-text")
def embed(text: str):
    if "cloud" in MODEL.lower():
        raise ValueError("Cloud embedding model forbidden")
    with httpx.Client(timeout=60, trust_env=False) as client:
        r = client.post(local_url(os.getenv("OLLAMA_URL", "http://localhost:11434")) + "/api/embed", json={"model": MODEL, "input": text})
        r.raise_for_status()
        vector = r.json()["embeddings"][0]
    if len(vector) != 768 or any(not math.isfinite(v) for v in vector):
        raise ValueError("Expected 768-dimensional finite embeddings")
    return "[" + ",".join(str(v) for v in vector) + "]"
def connect():
    return psycopg.connect(os.environ["SEARCH_DATABASE_URL"])
def index(namespace: str, owner_id: str, chunks: list[dict]):
    vectors = [(c["id"], namespace, owner_id, MODEL, c["text"], embed(c["text"])) for c in chunks]
    with connect() as conn:
        conn.execute("DELETE FROM semantic_chunk WHERE namespace=%s AND owner_id=%s AND model=%s", (namespace, owner_id, MODEL))
        for row in vectors:
            conn.execute("INSERT INTO semantic_chunk VALUES (%s,%s,%s,%s,%s,%s::vector)", row)
    return {"indexed": len(vectors), "model": MODEL}
def search(namespace: str, owner_id: str, query: str):
    vector = embed(query)
    with connect() as conn:
        rows = conn.execute("SELECT id,text_content,1-(embedding <=> %s::vector) AS similarity FROM semantic_chunk WHERE namespace=%s AND owner_id=%s AND model=%s ORDER BY embedding <=> %s::vector LIMIT 8", (vector, namespace, owner_id, MODEL, vector)).fetchall()
    return {"hits": [{"id":r[0],"text":r[1],"similarity":r[2]} for r in rows], "model":MODEL}
