param(
    [string]$Package = 'com.jdkshen.aijspro',
    [int]$WaitMs = 60000
)
# Release gate: run the QuickJS image-API regression sample (assets 01-*.js)
# on the connected device through the dev-plugin channel.
# Device-optional: SKIPs (exit 0) when no device or no reachable app.
# Exit 0 = pass or skip, 1 = sample failed (assertion / marker missing).
#
# The sample asserts: capture -> pixel/colors -> findColor/findMultiColors ->
# clip/resize/scale/grayscale/cvtColor -> compress -> save -> findImage ->
# matchTemplate -> NaN threshold rejected -> oversized template rejected
# ("larger than") and finally prints QUICKJS_IMAGE_API_OK.

$repo = Split-Path -Parent $PSScriptRoot
$adb = 'C:\Android\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = 'adb' }

function Adb([string[]]$AdbArgs) {
    # adb writes info to stderr; keep it out of the error stream
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    $r = & $adb @AdbArgs 2>&1 | Out-String
    $ErrorActionPreference = $prev
    return $r
}

$state = (Adb @('get-state')).Trim()
if ($state -ne 'device') { Write-Host '[image-gate] SKIP: no adb device'; exit 0 }

# assets sample dir contains CJK - build the path from char codes (this file
# itself stays ASCII so PS 5.1 reads it correctly without a BOM)
$q = 'QuickJS ' + [string][char]0x5F15 + [string][char]0x64CE
$imgDirName = [string][char]0x56FE + [string][char]0x8272 + [string][char]0x5904 + [string][char]0x7406
$sampleDir = Join-Path $repo ("apps\app\src\main\assets\sample\$q\$imgDirName")
$sample = Get-ChildItem -LiteralPath $sampleDir -Filter '01-*.js' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $sample) { Write-Host "[image-gate] FAIL: sample not found under $sampleDir"; exit 1 }

$runner = Join-Path $repo 'vscode-extension\dist\probe-run-sample.js'
if (-not (Test-Path $runner)) { Write-Host '[image-gate] SKIP: vscode-extension\dist\probe-run-sample.js not built'; exit 0 }

Adb @('forward', 'tcp:19317', 'tcp:9317') | Out-Null

if (-not (Test-Path env:AIJSPRO_IMAGE_GATE_NO_LAUNCH)) {
    # wake the screen and try to bring the app up (harmless when already running)
    Adb @('shell', 'input', 'keyevent', '224') | Out-Null
    Adb @('shell', 'monkey', '-p', $Package, '-c', 'android.intent.category.LAUNCHER', '1') | Out-Null
    Start-Sleep -Seconds 3
}

Write-Host ("[image-gate] running {0} (wait {1}s)" -f $sample.Name, [int]($WaitMs / 1000))
$env:PROBE_WAIT_MS = "$WaitMs"
$env:PROBE_EXPECT = 'QUICKJS_IMAGE_API_OK'
try {
    $output = & node $runner $sample.FullName 2>&1 | Out-String
    $code = $LASTEXITCODE
} finally {
    Remove-Item Env:\PROBE_WAIT_MS -ErrorAction SilentlyContinue
    Remove-Item Env:\PROBE_EXPECT -ErrorAction SilentlyContinue
}
$output -split "`r?`n" | Where-Object { $_ } | ForEach-Object { Write-Host ('  ' + $_) }

if ($code -eq 0) { Write-Host '[image-gate] PASS'; exit 0 }
if ($output -match 'no device connection') {
    Write-Host '[image-gate] SKIP: app dev-plugin channel did not answer'
    exit 0
}
Write-Host ("[image-gate] FAIL: image API sample did not pass (runner exit {0})" -f $code) -ForegroundColor Red
exit 1
