param(
    [Parameter(Mandatory=$true)][int]$VersionCode,
    [Parameter(Mandatory=$true)][string]$VersionName,
    [switch]$SkipAot
)
$ErrorActionPreference = 'Continue'
$poc = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent (Split-Path -Parent $poc)
$versionsFile = Join-Path $repo 'project-versions.json'
$original = [IO.File]::ReadAllText($versionsFile, [Text.Encoding]::UTF8)

if (-not $SkipAot) {
    & (Join-Path $poc 'build-aot.ps1')
    if ($LASTEXITCODE -ne 0) { throw "build-aot.ps1 failed: $LASTEXITCODE" }
}

foreach ($rel in @('merged_native_libs','merged_jni_libs','stripped_native_libs','merged_manifests','merged_assets','compressed_assets')) {
    $dir = Join-Path $repo ('apps\app\build\intermediates\' + $rel)
    if (Test-Path $dir) { Remove-Item $dir -Recurse -Force -ErrorAction SilentlyContinue }
}

. (Join-Path $repo 'tools\jdk17.ps1') | Out-Null
Push-Location $repo
$log = Join-Path $poc ('poc-build-' + $VersionCode + '.log')
try {
    $patched = $original -replace '"appVersionCode"\s*:\s*\d+', ('"appVersionCode": ' + $VersionCode)
    $patched = $patched -replace '"appVersionName"\s*:\s*"[^"]*"', ('"appVersionName": "' + $VersionName + '"')
    [IO.File]::WriteAllText($versionsFile, $patched, (New-Object System.Text.UTF8Encoding($false)))
    & .\gradlew.bat :app:assembleMiuixCompatDebug -PflutterPoc=true -PdevPluginUsbDiscovery=false --console=plain *> $log
    $exit = $LASTEXITCODE
} finally {
    [IO.File]::WriteAllText($versionsFile, $original, (New-Object System.Text.UTF8Encoding($false)))
    Pop-Location
}
if ($exit -ne 0) { throw "gradle build failed: $exit (see $log)" }

$apk = Join-Path $repo 'apps\app\build\outputs\apk\miuixCompat\debug\app-miuix-compat-arm64-v8a-debug.apk'
if (-not (Test-Path $apk)) { throw "APK missing: $apk" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($apk)
try {
    $names = $zip.Entries | ForEach-Object { $_.FullName }
    foreach ($need in @('lib/arm64-v8a/libflutter.so', 'lib/arm64-v8a/libapp.so')) {
        if (-not ($names -contains $need)) { throw "APK missing $need (stale merge cache?)" }
    }
} finally { $zip.Dispose() }
$sizeMb = [math]::Round((Get-Item $apk).Length / 1MB, 1)
Write-Output ("APK: " + $apk + " (" + $sizeMb + " MB) versionCode=" + $VersionCode + " versionName=" + $VersionName)
