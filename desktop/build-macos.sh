#!/usr/bin/env bash
# Crea l'installatore macOS di RegMac (RegMac-<versione>.dmg) con Java 21 incluso.
# Requisiti: JDK 21 (con jpackage), Node.js/npm. Uso: desktop/build-macos.sh
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$(cd .. && pwd)
VERSION=1.0.0
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
export JAVA_HOME
MVN="$ROOT/.tools/apache-maven-3.9.9/bin/mvn"
[ -x "$MVN" ] || MVN=mvn

echo "== 1/4 Dati normativi e schede di verifica"
cp "$ROOT/data/normative/requirements.json" "$ROOT/data/normative/schede.json" app/src/main/resources/normative/
mkdir -p ui/public/normative && cp "$ROOT"/data/normative/*.pdf ui/public/normative/

echo "== 2/4 Interfaccia"
(cd ui && npm ci --prefer-offline --no-audit --no-fund >/dev/null && npm run build >/dev/null)

echo "== 3/4 Programma (jar)"
(cd app && "$MVN" -q -Dmaven.repo.local="$ROOT/.tools/m2" -DskipTests package)
rm -rf build/input && mkdir -p build/input && cp app/target/regmac-desktop.jar build/input/

echo "== 4/4 Installatore"
rm -rf dist && mkdir -p dist
"$JAVA_HOME/bin/jpackage" --type dmg --dest dist \
  --name RegMac --app-version "$VERSION" --vendor "RegMac" \
  --description "Pre-assessment di macchine con AI locale (Reg. UE 2023/1230 e 2024/2847)" \
  --input build/input --main-jar regmac-desktop.jar \
  --icon packaging/RegMac.icns --mac-package-identifier it.regmac.desktop --mac-package-name RegMac \
  --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.sql,java.xml,java.instrument,java.security.jgss,java.scripting,jdk.crypto.ec,jdk.unsupported,jdk.management,jdk.zipfs,jdk.charsets,jdk.localedata \
  --jlink-options "--strip-debug --no-man-pages --no-header-files" \
  --java-options "-Xmx2g -Dfile.encoding=UTF-8 -Dapple.awt.application.name=RegMac"
ls -lh dist
echo "Installatore pronto: desktop/dist/RegMac-$VERSION.dmg"
