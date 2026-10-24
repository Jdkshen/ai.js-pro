# Build the Flutter AOT libapp.so for the glass UI.
# Sources: <repo>/flutter-ui (vendored Dart project)
# Base payload (libflutter/embedding jar/flutter_assets): <repo>/flutter-payload-base
# Output: <repo>/output/flutter-poc/payload
# Usage: powershell -ExecutionPolicy Bypass -File tools/flutter-poc/build-aot.ps1 [-Offline]
param(
    [switch]$Offline,
    [string]$FlutterSdk = 'C:\Android\flutter-3.47.7'
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
$runtime = Get-Content -LiteralPath (Join-Path $base 'runtime.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$sdkEngine = [IO.File]::ReadAllText((Join-Path $FlutterSdk 'bin\internal\engine.version')).Trim()
if ($sdkEngine -ne $runtime.engineRevision) { throw "Flutter SDK 与基础引擎不匹配：需要 $($runtime.frameworkVersion) / $($runtime.engineRevision)" }
foreach ($artifact in $runtime.artifacts.PSObject.Properties) {
    $actual = (Get-FileHash -LiteralPath (Join-Path $base $artifact.Name) -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $artifact.Value.sha256) { throw "Flutter 基础产物 SHA-256 不匹配：$($artifact.Name)" }
}
New-Item -ItemType Directory -Force -Path $out, $payload | Out-Null

function Sync-GeneratedTree([string]$Source, [string]$Target) {
    $resolved = [IO.Path]::GetFullPath($Target)
    $boundary = [IO.Path]::GetFullPath($out).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($boundary, [StringComparison]::OrdinalIgnoreCase)) { throw "拒绝镜像仓库 output 之外的路径：$resolved" }
    if (-not (Test-Path -LiteralPath $Source -PathType Container)) { throw "缺少同步源目录：$Source" }
    New-Item -ItemType Directory -Force -Path $resolved | Out-Null
    & robocopy $Source $resolved /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed: $LASTEXITCODE ($Source)" }
}

# 1) sync vendored Dart source to ASCII working dir
New-Item -ItemType Directory -Force -Path $src | Out-Null
Sync-GeneratedTree (Join-Path $ui 'lib') (Join-Path $src 'lib')
Sync-GeneratedTree (Join-Path $ui 'third_party') (Join-Path $src 'third_party')
Sync-GeneratedTree (Join-Path $ui 'assets') (Join-Path $src 'assets')
Copy-Item (Join-Path $ui 'pubspec.yaml') $src -Force
if (Test-Path (Join-Path $ui 'pubspec.lock')) { Copy-Item (Join-Path $ui 'pubspec.lock') $src -Force }

# 2) seed/refresh payload from the vendored base (libapp.so is regenerated below)
New-Item -ItemType Directory -Force -Path (Join-Path $payload 'jniLibs\arm64-v8a') | Out-Null
Copy-Item (Join-Path $base 'jniLibs\arm64-v8a\libflutter.so') (Join-Path $payload 'jniLibs\arm64-v8a') -Force
Copy-Item (Join-Path $base 'flutter_embedding_release.jar') $payload -Force

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

# Assets must come from this SDK/build too (manifest, fonts, shaders, project assets).
Sync-GeneratedTree (Join-Path $src 'build\flutter_assets') (Join-Path $payload 'assets\flutter_assets')
Copy-Item (Join-Path $src 'pubspec.lock') (Join-Path $ui 'pubspec.lock') -Force

$appSo = Join-Path $src 'build\arm64-v8a\app.so'
if (-not (Test-Path $appSo)) { throw "AOT 产物缺失：$appSo" }
Copy-Item $appSo (Join-Path $payload 'jniLibs\arm64-v8a\libapp.so') -Force
$runtime | Add-Member -NotePropertyName aotSha256 -NotePropertyValue ((Get-FileHash $appSo -Algorithm SHA256).Hash.ToLowerInvariant()) -Force
[IO.File]::WriteAllText((Join-Path $payload 'runtime.json'), ($runtime | ConvertTo-Json -Depth 8), (New-Object Text.UTF8Encoding($false)))
Write-Output ("Flutter " + $runtime.frameworkVersion + " engine=" + $runtime.engineRevision)
$size = (Get-Item (Join-Path $payload 'jniLibs\arm64-v8a\libapp.so')).Length
$hash = (Get-FileHash (Join-Path $payload 'jniLibs\arm64-v8a\libapp.so') -Algorithm SHA256).Hash
Write-Output ("AOT libapp.so: " + $size + " bytes sha256=" + $hash)
Write-Output ("payload: " + $payload)
# robocopy uses exit code 1 for successful copies. Do not leak that status to
# callers after Flutter assemble and payload verification have succeeded.
exit 0
