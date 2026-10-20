param(
    [switch]$SkipSync,
    [string]$FlutterSdk = 'C:\Android\flutter-3.3.4',
    [string]$RebuildSrc = 'C:\Users\18101\Documents\AutoJsPro-本地工程-20261008\工程\output\autojs-pro-flutter-rebuild'
)
$ErrorActionPreference = 'Continue'
$poc = Split-Path -Parent $MyInvocation.MyCommand.Path
$src = Join-Path $poc 'src'
$payload = Join-Path $poc 'payload'
$jni = Join-Path $payload 'jniLibs\arm64-v8a'

if (-not $SkipSync) {
    New-Item -ItemType Directory -Force -Path $src | Out-Null
    robocopy (Join-Path $RebuildSrc 'lib') (Join-Path $src 'lib') /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
    robocopy (Join-Path $RebuildSrc 'assets') (Join-Path $src 'assets') /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
    if (Test-Path (Join-Path $RebuildSrc 'third_party')) {
        robocopy (Join-Path $RebuildSrc 'third_party') (Join-Path $src 'third_party') /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
    }
    Copy-Item (Join-Path $RebuildSrc 'pubspec.yaml') $src -Force
    if (Test-Path (Join-Path $RebuildSrc 'pubspec.lock')) { Copy-Item (Join-Path $RebuildSrc 'pubspec.lock') $src -Force }
}

Push-Location $src
& (Join-Path $FlutterSdk 'bin\flutter.bat') pub get --offline *> (Join-Path $poc 'aot-pubget.log')
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "flutter pub get failed: $LASTEXITCODE" }
& (Join-Path $FlutterSdk 'bin\flutter.bat') assemble --output build --define=BuildMode=release --define=TargetPlatform=android-arm64 android_aot_bundle_release_android-arm64 *> (Join-Path $poc 'aot-assemble.log')
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "flutter assemble failed: $LASTEXITCODE" }
Pop-Location

$appSo = Join-Path $src 'build\arm64-v8a\app.so'
if (-not (Test-Path $appSo)) { throw "AOT output missing: $appSo" }
New-Item -ItemType Directory -Force -Path $jni | Out-Null
Copy-Item $appSo (Join-Path $jni 'libapp.so') -Force
$hash = (Get-FileHash (Join-Path $jni 'libapp.so') -Algorithm SHA256).Hash
$size = (Get-Item (Join-Path $jni 'libapp.so')).Length
Write-Output ("AOT libapp.so: " + $size + " bytes sha256=" + $hash)
