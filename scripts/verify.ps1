#Requires -Version 7.0
<#
.SYNOPSIS
    Yerel doğrulama: çekirdek ve uygulama testleri, komut satırı aracının derlenmesi.

.DESCRIPTION
    GitHub Actions bu hesapta kullanılamadığı için push öncesi .githooks/pre-push (verify.sh) ve
    elle çalıştırma için aynı denetimi yapar. Başarısızlıkta sıfırdan farklı kodla çıkar.
#>
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)

$gradle = Join-Path (Get-Location) ($IsWindows ? 'gradlew.bat' : 'gradlew')
& $gradle ':core:test' ':app:test' ':cli:compileKotlin'
exit $LASTEXITCODE
