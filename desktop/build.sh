#!/usr/bin/env bash
# Crea gli installatori di RegMac: finestra nativa Tauri + motore Java con Java 21 incluso.
# Funziona su macOS, Windows (Git Bash) e Linux. Requisiti: JDK 21, Node.js/npm, Rust (cargo).
# Da macOS si possono creare anche gli installatori Windows (.exe NSIS):
#   REGMAC_CROSS=windows desktop/build.sh        (Intel/AMD x64)
#   REGMAC_CROSS=windows-arm64 desktop/build.sh  (ARM64, es. Surface, Snapdragon, macchine virtuali su Mac)
#   (servono rustup con il target x86_64-pc-windows-msvc, cargo-xwin, llvm e makensis)
# Uso: desktop/build.sh [pacchetti]   es. "app,dmg" (macOS, predefinito), "msi,nsis" (Windows), "deb,appimage" (Linux)
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$(cd .. && pwd)
VERSION=1.1.2
case "$(uname -s)" in
  Darwin) DEFAULT=app,dmg; JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)} ;;
  MINGW*|MSYS*|CYGWIN*) DEFAULT=msi,nsis ;;
  *) DEFAULT=deb,appimage ;;
esac
BUNDLES=${1:-$DEFAULT}
CROSS=${REGMAC_CROSS:-}
case "$CROSS" in
  windows) WARCH=x64; JARCH=x64; RTARGET=x86_64-pc-windows-msvc ;;
  windows-arm64) WARCH=arm64; JARCH=aarch64; RTARGET=aarch64-pc-windows-msvc ;;
esac
if [ -n "$CROSS" ]; then
  BUNDLES=nsis
  export PATH="/opt/homebrew/opt/rustup/bin:$HOME/.cargo/bin:/opt/homebrew/opt/llvm/bin:$PATH"
fi
export JAVA_HOME
MVN="$ROOT/.tools/apache-maven-3.9.9/bin/mvn"
MVN_REPO=()
if [ -x "$MVN" ]; then MVN_REPO=(-Dmaven.repo.local="$ROOT/.tools/m2"); else MVN=mvn; fi

echo "== 1/5 Dati normativi e schede di verifica"
mkdir -p app/src/main/resources/normative app/src/main/resources/static
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
if [ -n "$CROSS" ]; then
  # Java per Windows: JRE 21 ufficiale (Eclipse Temurin) dell'architettura giusta, non si può creare con jlink da macOS
  curl -fsSL -o build/jre-windows.zip "https://api.adoptium.net/v3/binary/latest/21/ga/windows/$JARCH/jre/hotspot/normal/eclipse"
  rm -rf build/jre-tmp && mkdir -p build/jre-tmp && unzip -q build/jre-windows.zip -d build/jre-tmp
  mv build/jre-tmp/*/ build/backend/runtime && rm -rf build/jre-tmp build/jre-windows.zip
else
"$JAVA_HOME/bin/jlink" --output build/backend/runtime \
  --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.sql,java.xml,java.instrument,java.security.jgss,java.scripting,jdk.crypto.ec,jdk.unsupported,jdk.management,jdk.zipfs,jdk.charsets,jdk.localedata \
  --strip-debug --no-man-pages --no-header-files --compress zip-6
fi
chmod -R u+w build/backend   # jlink crea file in sola lettura: Tauri deve poterli copiare e sovrascrivere
[ -d tauri/src-tauri/target/release/backend ] && chmod -R u+w tauri/src-tauri/target/release/backend && rm -rf tauri/src-tauri/target/release/backend

echo "== 5/5 Applicazione nativa (Tauri) e installatore"
if [ -n "$CROSS" ]; then
  rustup target add "$RTARGET" >/dev/null
  (cd tauri && npm ci --no-audit --no-fund >/dev/null && npx tauri build --runner cargo-xwin --target "$RTARGET" --bundles nsis)
  rm -rf dist && mkdir -p dist
  cp tauri/src-tauri/target/$RTARGET/release/bundle/nsis/RegMac_${VERSION}_*-setup.exe "dist/RegMac-Windows-$WARCH-setup.exe"
  ls -lh dist; exit 0
fi
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
