-- Utenti individuali con ruolo; password con PBKDF2-HMAC-SHA256 (sale per utente)
CREATE TABLE app_user (
  id VARCHAR(36) PRIMARY KEY,
  username VARCHAR(80) NOT NULL UNIQUE,
  payload TEXT NOT NULL
);
-- Sessioni: si conserva solo lo SHA-256 del token, mai il token
CREATE TABLE user_session (
  token_sha256 VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
  created_at VARCHAR(50) NOT NULL,
  expires_at VARCHAR(50) NOT NULL
);
CREATE INDEX user_session_user ON user_session(user_id);
-- Autore di ogni evento dell'audit trail
ALTER TABLE audit_event ADD COLUMN actor VARCHAR(160);
