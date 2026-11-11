#!/usr/bin/env bash
# Crea gli installatori di RegMac: finestra nativa Tauri + motore Java con Java 21 incluso.
# Funziona su macOS, Windows (Git Bash) e Linux. Requisiti: JDK 21, Node.js/npm, Rust (cargo).
# Uso: desktop/build.sh [pacchetti]   es. "app,dmg" (macOS, predefinito), "msi,nsis" (Windows), "deb,appimage" (Linux)
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$(cd .. && pwd)
VERSION=1.1.0
case "$(uname -s)" in
  Darwin) DEFAULT=app,dmg; JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)} ;;
  MINGW*|MSYS*|CYGWIN*) DEFAULT=msi,nsis ;;
  *) DEFAULT=deb,appimage ;;
esac
BUNDLES=${1:-$DEFAULT}
export JAVA_HOME
MVN="$ROOT/.tools/apache-maven-3.9.9/bin/mvn"
MVN_REPO=()
if [ -x "$MVN" ]; then MVN_REPO=(-Dmaven.repo.local="$ROOT/.tools/m2"); else MVN=mvn; fi

echo "== 1/5 Dati normativi e schede di verifica"
cp "$ROOT/data/normative/requirements.json" "$ROOT/data/normative/schede.json" app/src/main/resources/normative/
mkdir -p ui/public/normative && cp "$ROOT"/data/normative/*.pdf ui/public/normative/

echo "== 2/5 Interfaccia"
(cd ui && npm ci --prefer-offline --no-audit --no-fund >/dev/null && npm run build >/dev/null)

echo "== 3/5 Motore (jar)"
(cd app && "$MVN" -q ${MVN_REPO[@]+"${MVN_REPO[@]}"} -DskipTests package)

echo "== 4/5 Java incluso (runtime ridotto con jlink)"
[ -d build/backend ] && chmod -R u+w build/backend
rm -rf build/backend && mkdir -p build/backend
cp app/target/regmac-desktop.jar build/backend/
"$JAVA_HOME/bin/jlink" --output build/backend/runtime \
  --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.sql,java.xml,java.instrument,java.security.jgss,java.scripting,jdk.crypto.ec,jdk.unsupported,jdk.management,jdk.zipfs,jdk.charsets,jdk.localedata \
  --strip-debug --no-man-pages --no-header-files --compress zip-6
chmod -R u+w build/backend   # jlink crea file in sola lettura: Tauri deve poterli copiare e sovrascrivere
[ -d tauri/src-tauri/target/release/backend ] && chmod -R u+w tauri/src-tauri/target/release/backend && rm -rf tauri/src-tauri/target/release/backend

echo "== 5/5 Applicazione nativa (Tauri) e installatore"
(cd tauri && npm ci --no-audit --no-fund >/dev/null && npx tauri build --bundles "$BUNDLES")
rm -rf dist && mkdir -p dist
# nomi fissi: i pulsanti di download del README puntano a releases/latest/download/<nome>
B=tauri/src-tauri/target/release/bundle
case "$(uname -m)" in arm64|aarch64) ARCH=arm64 ;; *) ARCH=x64 ;; esac
for f in "$B"/dmg/*.dmg; do if [ -f "$f" ]; then cp "$f" "dist/RegMac-macOS-$ARCH.dmg"; fi; done
for f in "$B"/msi/*.msi; do if [ -f "$f" ]; then cp "$f" "dist/RegMac-Windows-$ARCH.msi"; fi; done
for f in "$B"/nsis/*-setup.exe; do if [ -f "$f" ]; then cp "$f" "dist/RegMac-Windows-$ARCH-setup.exe"; fi; done
for f in "$B"/deb/*.deb; do if [ -f "$f" ]; then cp "$f" "dist/RegMac-Linux-$ARCH.deb"; fi; done
for f in "$B"/appimage/*.AppImage; do if [ -f "$f" ]; then cp "$f" "dist/RegMac-Linux-$ARCH.AppImage"; fi; done
ls -lh dist
echo "Installatori pronti in desktop/dist:"; ls dist
