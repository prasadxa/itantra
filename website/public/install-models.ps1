# iTantra model installer (Windows PowerShell).
# Downloads the ~1.2 GB on-device model pack, checks every file's SHA-256, and copies it to the
# phone over USB with adb. Safe to re-run: files already downloaded and verified are skipped.
#
#   curl.exe -fsSL https://itantra-106.pages.dev/install-models.ps1 -o install-models.ps1
#   powershell -ExecutionPolicy Bypass -File install-models.ps1
#
# Set $env:ITANTRA_NO_PUSH = "1" to download and verify only, without a phone.
$ErrorActionPreference = "Stop"

$Base      = if ($env:ITANTRA_MODELS_URL) { $env:ITANTRA_MODELS_URL } else { "https://pub-d139da76dbb3434bbcb332e210a95fcf.r2.dev/models/v1" }
$Cache     = if ($env:ITANTRA_CACHE) { $env:ITANTRA_CACHE } else { Join-Path $env:LOCALAPPDATA "itantra-models" }
$Pkg       = "org.itantra.app"
$DeviceDir = "/sdcard/Android/data/$Pkg/files/models"
$NoPush    = $env:ITANTRA_NO_PUSH -eq "1"

function Fail($msg) { Write-Host "Error: $msg" -ForegroundColor Red; exit 1 }
function Sha256($file) { (Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLower() }
function Download($url, $out) {
  & curl.exe -fL --retry 3 --progress-bar $url -o $out
  if ($LASTEXITCODE -ne 0) { Fail "Download failed: $url" }
}

if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) { Fail "curl.exe not found (it ships with Windows 10 1803 and later)." }
if (-not $NoPush) {
  if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { Fail "adb is not installed or not on PATH. Get Android platform-tools: https://developer.android.com/tools/releases/platform-tools" }
  $devices = (adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" }
  if (@($devices).Count -ne 1) { Fail "Connect exactly one phone with USB debugging on. Run 'adb devices' to check." }
  adb shell pm path $Pkg *> $null
  if ($LASTEXITCODE -ne 0) { Fail "iTantra is not installed on the phone. Install the APK first, open it once, then re-run." }
}

New-Item -ItemType Directory -Force -Path $Cache | Out-Null
Write-Host "==> Fetching file list" -ForegroundColor Yellow
$manifest = Join-Path $Cache "manifest.tsv"
Download "$Base/manifest.tsv" $manifest

foreach ($line in Get-Content -LiteralPath $manifest) {
  if (-not $line.Trim()) { continue }
  $path, $size, $sha, $parts = $line -split "`t"
  $dest = Join-Path $Cache ($path -replace "/", "\")
  New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
  if ((Test-Path -LiteralPath $dest) -and ((Sha256 $dest) -eq $sha)) { Write-Host "  ok       $path"; continue }
  Write-Host ("  download {0} ({1} MB)" -f $path, [math]::Floor([int64]$size / 1000000))
  $tmp = "$dest.tmp"
  if (-not $parts) {
    Download "$Base/$path" $tmp
  } else {
    $dir = ($path -split "/" | Select-Object -SkipLast 1) -join "/"
    $outStream = [System.IO.File]::Create($tmp)
    try {
      foreach ($p in ($parts -split ",")) {
        $partFile = Join-Path (Split-Path $dest) $p
        Download "$Base/$dir/$p" $partFile
        $in = [System.IO.File]::OpenRead($partFile)
        try { $in.CopyTo($outStream) } finally { $in.Close() }
        Remove-Item -LiteralPath $partFile
      }
    } finally { $outStream.Close() }
  }
  if ((Sha256 $tmp) -ne $sha) { Remove-Item -LiteralPath $tmp; Fail "Checksum mismatch for $path. Re-run to download it again." }
  Move-Item -Force -LiteralPath $tmp -Destination $dest
}

Write-Host "==> All model files downloaded and verified in $Cache" -ForegroundColor Yellow
if ($NoPush) { exit 0 }

Write-Host "==> Copying to the phone (this takes a few minutes over USB)" -ForegroundColor Yellow
adb shell mkdir -p $DeviceDir
foreach ($top in "vad", "stt", "tts") { adb push (Join-Path $Cache $top) "$DeviceDir/" }
adb shell am force-stop $Pkg *> $null

Write-Host "==> Done. Open iTantra, then Settings > Models: every row should show as present." -ForegroundColor Yellow
