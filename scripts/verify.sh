#!/usr/bin/env sh
# Yerel doğrulama: çekirdek ve uygulama testleri, komut satırı aracının derlenmesi.
# GitHub Actions bu hesapta kullanılamadığı için push öncesi .githooks/pre-push bunu çalıştırır.
set -eu

cd "$(dirname "$0")/.."
./gradlew :core:test :app:test :cli:compileKotlin
