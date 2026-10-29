<#
.SYNOPSIS
    Windows paketine gömülecek mpv'yi indirir ve app/resources/windows/mpv/ altına açar.

.DESCRIPTION
    Kaynak: GitHub shinchiro/mpv-winbuild-cmake son sürümü (düz x86_64 derlemesi, "v3" değil).
    Arşiv 7z biçiminde olduğu için sırayla şunlar denenir: 7z, Bandizip (bz.exe), Python py7zr.
    İkili dosyalar git'e girmez (.gitignore: app/resources/windows/mpv/).
    Linux için betik yoktur: orada sistem mpv'si kullanılır (apt/dnf/pacman install mpv ya da
    flatpak io.mpv.Mpv); bulma sırası core/.../player/MpvLocator.kt içinde.

.PARAMETER OutDir
    Hedef klasör. Varsayılan: <depo>/app/resources/windows/mpv

.PARAMETER Force
    Hedefte mpv.exe zaten varsa da yeniden indirir.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/fetch-mpv.ps1
#>
param(
    [string]$OutDir = "",
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# mpv.exe GUI alt sistemiyle derlendiği için "& mpv.exe" çıktısını yakalamaz ve beklemez;
# bu yüzden süreç başlatılıp çıktısı dosyaya yönlendirilir. Sürüm satırını döndürür (başarısızsa $null).
function Get-MpvVersion([string]$exe) {
    $out = [IO.Path]::GetTempFileName()
    try {
        $proc = Start-Process -FilePath $exe -ArgumentList '--version' -NoNewWindow -Wait -PassThru `
            -RedirectStandardOutput $out
        if ($proc.ExitCode -ne 0) { return $null }
        return (Get-Content -Path $out -TotalCount 1)
    } finally {
        Remove-Item $out -ErrorAction SilentlyContinue
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) { $OutDir = Join-Path $repoRoot 'app/resources/windows/mpv' }
$mpvExe = Join-Path $OutDir 'mpv.exe'

if ((Test-Path $mpvExe) -and -not $Force) {
    Write-Host "mpv zaten var: $mpvExe (yeniden indirmek için -Force)"
    Write-Host (Get-MpvVersion $mpvExe)
    exit 0
}

# --- 1) Son sürümü bul ---------------------------------------------------------------------
Write-Host 'Son sürüm GitHub API ile aranıyor...'
$release = $null
try {
    $release = Invoke-RestMethod -UseBasicParsing -Headers @{ 'User-Agent' = 'seyir-fetch-mpv' } `
        -Uri 'https://api.github.com/repos/shinchiro/mpv-winbuild-cmake/releases/latest'
} catch {
    Write-Host "API doğrudan erişilemedi ($($_.Exception.Message)); gh CLI deneniyor..."
    if (Get-Command gh -ErrorAction SilentlyContinue) {
        $release = (& gh api repos/shinchiro/mpv-winbuild-cmake/releases/latest) -join "`n" | ConvertFrom-Json
    } else {
        throw 'GitHub API kullanılamadı ve gh CLI kurulu değil.'
    }
}

# Düz x86_64: mpv-x86_64-20261021-git-abcdef.7z (v3, dev, aarch64, i686 ve ffmpeg paketleri hariç)
$asset = $release.assets | Where-Object { $_.name -match '^mpv-x86_64-\d{8}-git-[0-9a-f]+\.7z$' } | Select-Object -First 1
if (-not $asset) { throw "Uygun asset bulunamadı (sürüm: $($release.tag_name))." }
Write-Host "Sürüm: $($release.tag_name)  Asset: $($asset.name)  Boyut: $([math]::Round($asset.size / 1MB, 1)) MB"

# --- 2) İndir ---------------------------------------------------------------------------------
$work = Join-Path ([IO.Path]::GetTempPath()) ("seyir-mpv-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
$archive = Join-Path $work $asset.name
try {
    Write-Host 'İndiriliyor...'
    $ProgressPreference = 'SilentlyContinue'   # Windows PowerShell 5.1'de indirmeyi çok yavaşlatır
    Invoke-WebRequest -UseBasicParsing -Uri $asset.browser_download_url -OutFile $archive
    if ((Get-Item $archive).Length -ne $asset.size) { throw 'İndirilen dosya boyutu beklenenle uyuşmuyor.' }

    # --- 3) Aç ----------------------------------------------------------------------------------
    $extract = Join-Path $work 'out'
    New-Item -ItemType Directory -Path $extract | Out-Null
    $mpvExtracted = Join-Path $extract 'mpv.exe'

    $done = $false
    $sevenZip = $null
    $cmd = Get-Command 7z -ErrorAction SilentlyContinue
    if ($cmd) { $sevenZip = $cmd.Source }
    if (-not $sevenZip) {
        foreach ($p in @("$env:ProgramFiles\7-Zip\7z.exe", "${env:ProgramFiles(x86)}\7-Zip\7z.exe")) {
            if (Test-Path $p) { $sevenZip = $p; break }
        }
    }
    if ($sevenZip) {
        Write-Host "7z ile açılıyor: $sevenZip"
        & $sevenZip x -y "-o$extract" $archive | Out-Null
        $done = ($LASTEXITCODE -eq 0) -and (Test-Path $mpvExtracted)
    }
    if (-not $done) {
        $bz = "$env:ProgramFiles\Bandizip\bz.exe"
        if (Test-Path $bz) {
            Write-Host 'Bandizip ile açılıyor...'
            & $bz x -y "-o:$extract" $archive | Out-Null
            $done = Test-Path $mpvExtracted
        }
    }
    if (-not $done) {
        $py = Get-Command py -ErrorAction SilentlyContinue
        if (-not $py) { $py = Get-Command python -ErrorAction SilentlyContinue }
        if ($py) {
            Write-Host 'Python py7zr ile açılıyor...'
            & $py.Source -m pip install --quiet --disable-pip-version-check py7zr
            if ($LASTEXITCODE -eq 0) {
                $code = 'import sys, py7zr; z = py7zr.SevenZipFile(sys.argv[1]); z.extractall(sys.argv[2]); z.close()'
                & $py.Source -I -c $code $archive $extract
                $done = ($LASTEXITCODE -eq 0) -and (Test-Path $mpvExtracted)
            }
        }
    }
    if (-not $done) { throw '7z arşivi açılamadı (7z, Bandizip ve py7zr denendi).' }

    # --- 4) Yerleştir -----------------------------------------------------------------------------
    if (Test-Path $OutDir) { Remove-Item -Recurse -Force $OutDir }
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    Copy-Item -Recurse -Force -Path (Join-Path $extract '*') -Destination $OutDir
    # Paketle gelen gereksiz parçalar: yükleyici betikleri, belgeler, konsol sarmalayıcısı
    foreach ($junk in @('doc', 'installer', 'updater.bat', 'mpv-register.bat', 'mpv-unregister.bat', 'mpv.com')) {
        Remove-Item -Recurse -Force (Join-Path $OutDir $junk) -ErrorAction SilentlyContinue
    }
} finally {
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}

# --- 5) Doğrula -------------------------------------------------------------------------------------
if (-not (Test-Path $mpvExe)) { throw "mpv.exe hedefte yok: $mpvExe" }
$version = Get-MpvVersion $mpvExe
if (-not $version) { throw 'mpv.exe --version çalışmadı.' }
$size = (Get-ChildItem -Recurse -File $OutDir | Measure-Object Length -Sum).Sum
Write-Host "Tamam: $version"
Write-Host "Konum: $OutDir  ($([math]::Round($size / 1MB, 1)) MB)"
