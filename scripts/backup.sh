#!/bin/sh
# Backup completo di RegMac: database PostgreSQL + documenti e rapporti archiviati.
# Uso:   scripts/backup.sh [cartella]        (default: ./backups)
# Ripristino: scripts/restore.sh <cartella-backup>
set -eu
cd "$(dirname "$0")/.."
DEST="${1:-backups}/regmac-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$DEST"
DEST="$(cd "$DEST" && pwd)"
PROJECT="$(docker compose ps --format '{{.Project}}' | head -1)"
[ -n "$PROJECT" ] || { echo "Stack non in esecuzione: avviare con docker compose up -d"; exit 1; }
echo "Database…"
docker compose exec -T db pg_dump -U regmac -d regmac --format=custom --no-owner > "$DEST/database.dump"
echo "Documenti e rapporti…"
docker run --rm -v "${PROJECT}_documents:/data:ro" -v "$DEST:/backup" alpine tar czf /backup/documents.tar.gz -C /data .
( cd "$DEST" && shasum -a 256 database.dump documents.tar.gz > SHA256SUMS )
echo "Backup completato: $DEST"
cat "$DEST/SHA256SUMS"
