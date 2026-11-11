#!/bin/sh
# Rimozione di RegMac su Linux (pacchetto .deb).
# remove: elimina Ollama e i modelli AI scaricati da RegMac; purge: elimina anche i fascicoli.
set -e
for home in /root /home/*; do
  d="$home/.local/share/regmac"
  [ -d "$d" ] || continue
  case "$1" in
    remove|purge) rm -rf "$d/ollama" ;;
  esac
  if [ "$1" = purge ]; then rm -rf "$d"; fi
done
exit 0
