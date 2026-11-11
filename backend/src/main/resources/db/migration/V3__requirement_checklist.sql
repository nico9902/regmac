-- Catalogo dei requisiti estratto dai testi integrali (scripts/build_requirements.py)
CREATE TABLE normative_catalog (sha256 VARCHAR(64) PRIMARY KEY, imported_at VARCHAR(50) NOT NULL, payload TEXT NOT NULL);
CREATE TABLE normative_requirement (
  id VARCHAR(80) PRIMARY KEY,
  law VARCHAR(10) NOT NULL,
  section_id VARCHAR(20) NOT NULL,
  position INTEGER NOT NULL,
  assessable BOOLEAN NOT NULL,
  catalog_sha256 VARCHAR(64) NOT NULL,
  payload TEXT NOT NULL
);
CREATE INDEX normative_requirement_order ON normative_requirement(position);

-- Checklist di valutazione manuale per macchina (una o più revisioni)
CREATE TABLE checklist (id VARCHAR(36) PRIMARY KEY, machine_id VARCHAR(36) NOT NULL REFERENCES machine(id), payload TEXT NOT NULL);
CREATE INDEX checklist_machine ON checklist(machine_id);
CREATE TABLE checklist_item (
  checklist_id VARCHAR(36) NOT NULL REFERENCES checklist(id),
  requirement_id VARCHAR(80) NOT NULL REFERENCES normative_requirement(id),
  updated_at VARCHAR(50) NOT NULL,
  payload TEXT NOT NULL,
  PRIMARY KEY (checklist_id, requirement_id)
);
