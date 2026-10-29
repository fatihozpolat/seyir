#Requires -Version 7.0
<#
.SYNOPSIS
    Seyir sürümünü yerelde derler, imzalar ve GitHub Releases'ta yayımlar.

.DESCRIPTION
    GitHub Actions bu hesapta kullanılamadığı için sürüm yerelde çıkarılır. Adımlar sırayla:
      1. Git ağacı temiz mi, -Version gradle.properties içindeki seyirVersion ile aynı mı
      2. :core:test :app:test
      3. :app:packageMsi :app:packageExe
      4. Kurulum dosyaları dist/v<sürüm>/ altına toplanır
      5. SHA256SUMS yazılır (küçük harf hex, iki boşluk, dosya adı, LF satır sonu)
      6. SHA256SUMS, Ed25519 özel anahtarıyla imzalanır -> SHA256SUMS.sig (Base64); anahtar
         $env:SEYIR_SIGNING_KEY ya da ~/.seyir-release/ed25519-private.pem
      7. gh release create v<sürüm> [--prerelease] --notes-file <dosya> + dosyalar

    Uygulama yalnız SHA256SUMS imzası gömülü açık anahtarla doğrulanırsa ve kurulum dosyasının özeti
    listedeki satırla uyuşursa güncelleme kurar; bu yüzden üç dosya (kurulumlar, SHA256SUMS,
    SHA256SUMS.sig) sürümde bulunmalıdır. Özel anahtarı depoya koymayın.

    -DryRun: paketlemeyi ve gh adımını atlar; geri kalan her şeyi (denetimler, testler, özet, imza,
    imza doğrulaması) varsa mevcut dosyalar üzerinde çalıştırır. Hiçbir şeyi yayımlamaz.

.PARAMETER Version
    Yayımlanacak sürüm (1.2.3, v1.2.3 ya da 1.2.3-beta.1). Sayısal kısım gradle.properties içindeki
    seyirVersion ile aynı olmalı; ek varsa sürüm kendiliğinden ön sürüm olarak işaretlenir.

.PARAMETER Prerelease
    Sürümü GitHub'da ön sürüm olarak işaretler (güncelleme denetimi ön sürümleri görmez).

.PARAMETER DryRun
    Paketlemeyi ve GitHub adımını atlar (bkz. yukarısı).

.PARAMETER NotesFile
    İki dilli (Türkçe + İngilizce) sürüm notları dosyası. -DryRun dışında zorunludur.

.EXAMPLE
    pwsh scripts/release.ps1 -Version 1.1.0 -NotesFile notes-1.1.0.md
    pwsh scripts/release.ps1 -Version 1.1.0 -DryRun
#>
param(
    [Parameter(Mandatory)][string]$Version,
    [switch]$Prerelease,
    [switch]$DryRun,
    [string]$NotesFile = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$gradle = Join-Path $root ($IsWindows ? 'gradlew.bat' : 'gradlew')
$repository = 'fatihozpolat/seyir'

function Write-Step([string]$text) { Write-Host "`n==> $text" -ForegroundColor Cyan }

# Çıkış koduna bakarak dış komut çalıştırır; hata varsa betiği durdurur
function Invoke-Checked([string]$what, [scriptblock]$command) {
    & $command
    if ($LASTEXITCODE -ne 0) { throw "$what başarısız (çıkış kodu $LASTEXITCODE)" }
}

# ---- 1. Ön denetimler ----
Write-Step '1/7 Ön denetimler'
$Version = $Version.TrimStart('v', 'V')
# Ön sürüm etiketi (1.2.3-beta.1) kabul edilir; paket sürümü yalnız sayısal kısımdır (MSI başka biçim almıyor)
if ($Version -notmatch '^(\d+\.\d+\.\d+)(-[0-9A-Za-z.]+)?$') { throw "Sürüm x.y.z ya da x.y.z-ek biçiminde olmalı: $Version" }
$packageVersion = $Matches[1]
if ($Matches[2]) { $Prerelease = $true }

$propsLine = Get-Content (Join-Path $root 'gradle.properties') | Where-Object { $_ -match '^\s*seyirVersion\s*=' } | Select-Object -First 1
if (-not $propsLine) { throw 'gradle.properties içinde seyirVersion yok' }
$buildVersion = ($propsLine -split '=', 2)[1].Trim()
if ($buildVersion -ne $packageVersion) {
    throw "Sürüm uyuşmuyor: -Version $Version, gradle.properties seyirVersion=$buildVersion. Önce gradle.properties'i güncelleyip commit'leyin."
}

$dirty = git status --porcelain
if ($LASTEXITCODE -ne 0) { throw 'git status çalışmadı' }
if ($dirty) {
    # Deneme çalıştırmasında yalnız uyarı: betiğin kendisini geliştirirken ağaç doğal olarak kirlidir
    if ($DryRun) { Write-Warning "Git ağacı temiz değil (-DryRun olduğu için devam ediliyor):`n$($dirty -join "`n")" }
    else { throw "Git ağacı temiz değil; önce commit'leyin:`n$($dirty -join "`n")" }
}

$tag = "v$Version"
if (-not $DryRun) {
    if (-not $NotesFile) { throw '-NotesFile zorunlu (Türkçe + İngilizce sürüm notları); yalnız -DryRun onsuz çalışır.' }
    if (-not (Test-Path $NotesFile) -or -not (Get-Content $NotesFile -Raw).Trim()) { throw "Sürüm notları dosyası yok ya da boş: $NotesFile" }
    if (git tag --list $tag) { throw "$tag etiketi zaten var" }
    Invoke-Checked 'gh oturumu' { gh auth status 2>&1 | Out-Null }
}
elseif ($NotesFile -and -not (Test-Path $NotesFile)) {
    Write-Warning "Sürüm notları dosyası bulunamadı: $NotesFile"
}

# Yerelde olmayan ama uzakta duran etiket ve itilmemiş HEAD: gh release create etiketi uzakta HEAD'e
# oluşturur; HEAD itilmemişse sürüm, depoda bulunmayan bir işlemeye bağlanır. Bu denetimler paketlemeden
# önce yapılır. -DryRun'da (ağ yoksa ya da uzak tanımsızsa) yalnız uyarı verir.
function Assert-Release([string]$message) {
    if ($DryRun) { Write-Warning "$message (-DryRun olduğu için devam ediliyor)" } else { throw $message }
}

$remoteTag = git ls-remote --tags origin "refs/tags/$tag"
if ($LASTEXITCODE -ne 0) { Assert-Release 'Uzaktaki etiketler okunamadı (git ls-remote origin)' }
elseif ($remoteTag) { Assert-Release "$tag etiketi uzakta (origin) zaten var" }

git fetch origin --quiet
if ($LASTEXITCODE -ne 0) { Assert-Release 'git fetch origin çalışmadı; HEAD itilmiş mi doğrulanamadı' }
else {
    $head = git rev-parse HEAD
    $originMain = git rev-parse origin/main 2>$null
    $pushed = ($head -eq $originMain)
    if (-not $pushed) {
        # main dışındaki bir uzak dalda bulunan HEAD de yayımlanabilir
        $containing = git branch -r --contains $head 2>$null
        $pushed = [bool]$containing
    }
    if (-not $pushed) { Assert-Release "HEAD ($($head.Substring(0, 8))) uzağa itilmemiş; önce 'git push' yapın" }
}

# Anahtar yolu: önce ortam değişkeni, yoksa varsayılan konum (depo dışında)
$keyPath = $env:SEYIR_SIGNING_KEY
if (-not $keyPath) { $keyPath = Join-Path $HOME '.seyir-release/ed25519-private.pem' }
if (-not (Test-Path $keyPath)) { throw "İmza anahtarı bulunamadı: $keyPath (SEYIR_SIGNING_KEY ile başka yol verilebilir)" }
Write-Host "Sürüm $Version, imza anahtarı: $keyPath"

# ---- 2. Testler ----
Write-Step '2/7 Testler'
Invoke-Checked 'Testler' { & $gradle ':core:test' ':app:test' }

# ---- 3. Paketleme ----
Write-Step '3/7 Paketleme'
if ($DryRun) { Write-Host '(-DryRun: paketleme atlandı; mevcut dosyalar kullanılır)' }
# Uygulamanın kendine bildirdiği sürüm ön sürüm ekiyle birlikte (güncelleme denetimi ön sürümleri ancak böyle ayırır)
else { Invoke-Checked 'Paketleme' { & $gradle ':app:packageMsi' ':app:packageExe' "-PseyirReleaseVersion=$Version" } }

# ---- 4. Kurulum dosyalarını topla ----
Write-Step "4/7 Kurulum dosyaları -> dist/$tag"
$dist = Join-Path $root "dist/$tag"
New-Item -ItemType Directory -Force $dist | Out-Null
# Eski deneme çıktıları yeni özet listesine karışmasın
Get-ChildItem $dist -File | Where-Object { $_.Name -in 'SHA256SUMS', 'SHA256SUMS.sig' } | Remove-Item -Force
$binaries = Join-Path $root 'app/build/compose/binaries/main'
$found = @(
    Get-ChildItem (Join-Path $binaries 'msi') -Filter "*$packageVersion*.msi" -ErrorAction SilentlyContinue
    Get-ChildItem (Join-Path $binaries 'exe') -Filter "*$packageVersion*.exe" -ErrorAction SilentlyContinue
)
if ($found.Count -eq 0 -and -not $DryRun) { throw "Derleme çıktısı bulunamadı: $binaries" }
foreach ($file in $found) { Copy-Item $file.FullName $dist -Force }
$installers = @(Get-ChildItem $dist -File | Where-Object { $_.Extension -in '.msi', '.exe' } | Sort-Object Name)
if ($installers.Count -eq 0) { Write-Warning 'Kurulum dosyası yok (-DryRun); özet listesi boş olacak.' }
$installers | ForEach-Object { Write-Host ('  {0}  {1:N1} MB' -f $_.Name, ($_.Length / 1MB)) }

# ---- 5. SHA256SUMS ----
Write-Step '5/7 SHA256SUMS'
# sha256sum biçimi: küçük harf hex, iki boşluk, dosya adı; LF satır sonu, BOM yok
$lines = foreach ($file in ($installers | Sort-Object Name)) {
    '{0}  {1}' -f (Get-FileHash $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $file.Name
}
$sumsPath = Join-Path $dist 'SHA256SUMS'
$sumsText = if ($lines) { ($lines -join "`n") + "`n" } else { '' }
[System.IO.File]::WriteAllText($sumsPath, $sumsText, [System.Text.UTF8Encoding]::new($false))
Get-Content $sumsPath | ForEach-Object { Write-Host "  $_" }

# ---- 6. İmza ----
Write-Step '6/7 İmza (Ed25519)'
# Gradle --args değerinde tek tırnak boşluklu yolları korur
Invoke-Checked 'İmzalama' { & $gradle -q ':cli:run' "--args=sign '$sumsPath' --key '$keyPath'" }
Invoke-Checked 'İmza doğrulama' { & $gradle -q ':cli:run' "--args=verify '$sumsPath'" }
if (-not (Test-Path "$sumsPath.sig")) { throw 'SHA256SUMS.sig üretilemedi' }

# ---- 7. Yayın ----
Write-Step '7/7 GitHub sürümü'
$assets = @($installers.FullName) + @($sumsPath, "$sumsPath.sig")
$ghArgs = @('release', 'create', $tag, '--repo', $repository, '--title', "Seyir $Version", '--target', (git rev-parse HEAD))
if ($Prerelease) { $ghArgs += '--prerelease' }
if ($NotesFile -and (Test-Path $NotesFile)) { $ghArgs += @('--notes-file', $NotesFile) }
$ghArgs += $assets
if ($DryRun) {
    Write-Host '(-DryRun: yayımlanmadı) Çalıştırılacak komut:'
    Write-Host ('  gh ' + (($ghArgs | ForEach-Object { if ($_ -match '\s') { "'$_'" } else { $_ } }) -join ' '))
    Write-Host "`nDeneme tamamlandı: $dist"
    return
}
Invoke-Checked 'gh release create' { gh @ghArgs }
Write-Host "`nYayımlandı: https://github.com/$repository/releases/tag/$tag" -ForegroundColor Green
