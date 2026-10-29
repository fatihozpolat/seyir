#!/usr/bin/env bash
# Kaynak ağacını (salt okunur /src) kopyalayıp Linux paketlerini üretir; çıktılar /out'a yazılır.
# Windows/Linux derleme çıktıları karışmasın diye build/ ve .gradle/ klasörleri kopyalanmaz.
set -euo pipefail
rsync_like() {
  tar -C /src --exclude='./.git' --exclude='*/.git' --warning=no-file-changed --exclude='./build' --exclude='*/build' --exclude='./.gradle' --exclude='./.kotlin' \
      --exclude='./.claude' --exclude='./dist' --exclude='./app/resources/windows' -cf - . \
    | tar -C /work -xf - || [ $? -eq 1 ]
}
rsync_like
cd /work
chmod +x gradlew
./gradlew --no-daemon --console=plain :cli:installDist :app:packageDeb :app:packageRpm :app:createDistributable
mkdir -p /out
cp app/build/compose/binaries/main/deb/*.deb app/build/compose/binaries/main/rpm/*.rpm /out/
tar -C app/build/compose/binaries/main/app -czf /out/seyir-linux-x64.tar.gz Seyir
# Linux'ta çekirdeği doğrula: eklentiler yükleniyor mu, gerçek bir siteden bağlantı geliyor mu
cli/build/install/cli/bin/cli list 2>/dev/null | tail -1
cli/build/install/cli/bin/cli list 2>/dev/null | head -6 || true
ls -la /out
