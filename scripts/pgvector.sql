CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS semantic_chunk (
 id varchar(200) PRIMARY KEY, namespace varchar(20) NOT NULL CHECK (namespace IN ('machine','normative')),
 owner_id varchar(160) NOT NULL, model varchar(100) NOT NULL, text_content text NOT NULL, embedding vector(768) NOT NULL
);
CREATE INDEX IF NOT EXISTS semantic_owner ON semantic_chunk(namespace, owner_id, model);
CREATE INDEX IF NOT EXISTS semantic_cosine ON semantic_chunk USING hnsw (embedding vector_cosine_ops);
