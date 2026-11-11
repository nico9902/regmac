#!/bin/sh
# Ripristino da un backup creato con scripts/backup.sh. SOVRASCRIVE database e documenti attuali.
# Uso: scripts/restore.sh backups/regmac-AAAAMMGG-HHMMSS
set -eu
cd "$(dirname "$0")/.."
SRC="$(cd "${1:?Indicare la cartella del backup}" && pwd)"
( cd "$SRC" && shasum -a 256 -c SHA256SUMS ) || { echo "Verifica SHA-256 fallita: backup alterato o incompleto"; exit 1; }
printf "Il ripristino sostituisce TUTTI i dati attuali. Digitare RIPRISTINA per continuare: "
read -r ok; [ "$ok" = "RIPRISTINA" ] || { echo "Annullato"; exit 1; }
PROJECT="$(docker compose ps --format '{{.Project}}' | head -1)"
docker compose stop backend
docker compose exec -T db pg_restore -U regmac -d regmac --clean --if-exists --no-owner < "$SRC/database.dump"
docker run --rm -v "${PROJECT}_documents:/data" -v "$SRC:/backup:ro" alpine sh -c "rm -rf /data/* && tar xzf /backup/documents.tar.gz -C /data && chown -R 999:999 /data 2>/dev/null || true"
docker compose start backend
echo "Ripristino completato da $SRC"
