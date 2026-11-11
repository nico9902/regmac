#!/usr/bin/env bash
# Crea l'installatore macOS di RegMac (.dmg): finestra nativa Tauri + motore Java con Java 21 incluso.
# Requisiti: JDK 21, Node.js/npm, Rust (cargo). Uso: desktop/build-macos.sh
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$(cd .. && pwd)
VERSION=1.0.0
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
export JAVA_HOME
MVN="$ROOT/.tools/apache-maven-3.9.9/bin/mvn"
[ -x "$MVN" ] || MVN=mvn

echo "== 1/5 Dati normativi e schede di verifica"
cp "$ROOT/data/normative/requirements.json" "$ROOT/data/normative/schede.json" app/src/main/resources/normative/
mkdir -p ui/public/normative && cp "$ROOT"/data/normative/*.pdf ui/public/normative/

echo "== 2/5 Interfaccia"
(cd ui && npm ci --prefer-offline --no-audit --no-fund >/dev/null && npm run build >/dev/null)

echo "== 3/5 Motore (jar)"
(cd app && "$MVN" -q -Dmaven.repo.local="$ROOT/.tools/m2" -DskipTests package)

echo "== 4/5 Java incluso (runtime ridotto con jlink)"
rm -rf build/backend && mkdir -p build/backend
cp app/target/regmac-desktop.jar build/backend/
"$JAVA_HOME/bin/jlink" --output build/backend/runtime \
  --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.sql,java.xml,java.instrument,java.security.jgss,java.scripting,jdk.crypto.ec,jdk.unsupported,jdk.management,jdk.zipfs,jdk.charsets,jdk.localedata \
  --strip-debug --no-man-pages --no-header-files --compress zip-6

echo "== 5/5 Applicazione nativa (Tauri) e installatore"
(cd tauri && npm ci --no-audit --no-fund >/dev/null && npx tauri build --bundles app,dmg)
rm -rf dist && mkdir -p dist
cp tauri/src-tauri/target/release/bundle/dmg/*.dmg dist/
ls -lh dist
echo "Installatore pronto: desktop/dist/$(ls dist)"
