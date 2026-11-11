#!/usr/bin/env bash
# Costruisce gli installatori Linux x64 di RegMac (.deb e .AppImage) in un contenitore Ubuntu 22.04,
# anche da macOS o Windows. Uso (dalla radice del repository):
#   docker run --rm --platform linux/amd64 --dns 1.1.1.1 -v "$PWD:/src:ro" -v "$PWD/desktop/dist-linux:/out" \
#     ubuntu:22.04 bash /src/desktop/packaging/linux/build-in-container.sh
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive APPIMAGE_EXTRACT_AND_RUN=1
apt-get update -qq
apt-get install -y -qq --no-install-recommends \
  curl ca-certificates git build-essential pkg-config file xz-utils maven openjdk-21-jdk-headless \
  libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev libssl-dev patchelf >/dev/null
curl -fsSL https://nodejs.org/dist/v22.11.0/node-v22.11.0-linux-x64.tar.xz | tar -xJ -C /opt
export PATH=/opt/node-v22.11.0-linux-x64/bin:/root/.cargo/bin:$PATH
curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal -q
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
# il sorgente arriva in sola lettura: si copia in un'area di lavoro (solo i file del repository)
rm -rf /work && mkdir /work
cd /src && git -c safe.directory=/src ls-files -z | xargs -0 -I{} cp --parents {} /work/
cd /work && bash desktop/build.sh deb,appimage
cp desktop/dist/* /out/
ls -lh /out
