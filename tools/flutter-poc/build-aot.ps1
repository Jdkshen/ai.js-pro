# Build the Flutter AOT libapp.so for the glass UI.
# Sources: <repo>/flutter-ui (vendored Dart project)
# Base payload (libflutter/embedding jar/flutter_assets): <repo>/flutter-payload-base
# Output: <repo>/output/flutter-poc/payload
# Usage: powershell -ExecutionPolicy Bypass -File tools/flutter-poc/build-aot.ps1 [-Offline]
param(
    [switch]$Offline,
    [string]$FlutterSdk = 'C:\Android\flutter-3.3.4'
)
$ErrorActionPreference = 'Continue'
$poc = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent (Split-Path -Parent $poc)
$ui = Join-Path $repo 'flutter-ui'
$out = Join-Path $repo 'output\flutter-poc'
$src = Join-Path $out 'src'
$payload = Join-Path $out 'payload'
$base = Join-Path $repo 'flutter-payload-base'

if (-not (Test-Path (Join-Path $ui 'pubspec.yaml'))) { throw "flutter-ui 源码缺失：$ui" }
if (-not (Test-Path (Join-Path $base 'jniLibs\arm64-v8a\libflutter.so'))) { throw "flutter-payload-base 缺失：$base" }
New-Item -ItemType Directory -Force -Path $out, $payload | Out-Null

# 1) sync vendored Dart source to ASCII working dir
New-Item -ItemType Directory -Force -Path $src | Out-Null
robocopy (Join-Path $ui 'lib') (Join-Path $src 'lib') /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
robocopy (Join-Path $ui 'third_party') (Join-Path $src 'third_party') /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
robocopy (Join-Path $ui 'assets') (Join-Path $src 'assets') /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
Copy-Item (Join-Path $ui 'pubspec.yaml') $src -Force
if (Test-Path (Join-Path $ui 'pubspec.lock')) { Copy-Item (Join-Path $ui 'pubspec.lock') $src -Force }

# 2) seed/refresh payload from the vendored base (libapp.so is regenerated below)
robocopy (Join-Path $base 'assets') (Join-Path $payload 'assets') /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $payload 'jniLibs\arm64-v8a') | Out-Null
Copy-Item (Join-Path $base 'jniLibs\arm64-v8a\libflutter.so') (Join-Path $payload 'jniLibs\arm64-v8a') -Force
Copy-Item (Join-Path $base 'flutter_embedding_3.3.4.jar') $payload -Force

# 3) pub get + AOT assemble (online by default; clean machines need network for pub cache)
Push-Location $src
if ($Offline) {
    & (Join-Path $FlutterSdk 'bin\flutter.bat') pub get --offline *> (Join-Path $out 'aot-pubget.log')
} else {
    & (Join-Path $FlutterSdk 'bin\flutter.bat') pub get *> (Join-Path $out 'aot-pubget.log')
}
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "flutter pub get failed: $LASTEXITCODE (see $out\aot-pubget.log)" }
& (Join-Path $FlutterSdk 'bin\flutter.bat') assemble --output build --define=BuildMode=release --define=TargetPlatform=android-arm64 android_aot_bundle_release_android-arm64 *> (Join-Path $out 'aot-assemble.log')
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "flutter assemble failed: $LASTEXITCODE (see $out\aot-assemble.log)" }
Pop-Location

$appSo = Join-Path $src 'build\arm64-v8a\app.so'
if (-not (Test-Path $appSo)) { throw "AOT 产物缺失：$appSo" }
Copy-Item $appSo (Join-Path $payload 'jniLibs\arm64-v8a\libapp.so') -Force
$size = (Get-Item (Join-Path $payload 'jniLibs\arm64-v8a\libapp.so')).Length
$hash = (Get-FileHash (Join-Path $payload 'jniLibs\arm64-v8a\libapp.so') -Algorithm SHA256).Hash
Write-Output ("AOT libapp.so: " + $size + " bytes sha256=" + $hash)
Write-Output ("payload: " + $payload)
