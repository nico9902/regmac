CREATE TABLE normative_document (id VARCHAR(80) PRIMARY KEY, sha256 VARCHAR(64) NOT NULL UNIQUE, payload TEXT NOT NULL);
CREATE TABLE normative_page (document_id VARCHAR(80) NOT NULL REFERENCES normative_document(id), page_number INTEGER NOT NULL, text_content TEXT NOT NULL, PRIMARY KEY(document_id,page_number));
