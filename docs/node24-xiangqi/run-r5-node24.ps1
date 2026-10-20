# run-r5-node24.ps1
# R5.4: run Node24 recognition probe on device (test package com.jdkshen.aijspro.nodebench),
# pull its result JSON, compare against QuickJS baseline, write r5-recognition-parity.md/.json.
# Only writes output/three-engine-bench/; pushes probe+chess bundle to device; no source edits/installs.
param(
    [string]$Serial = 'cccc62c7',
    [string]$Package = 'com.jdkshen.aijspro.nodebench',
    [string]$Adb = 'C:/Android/platform-tools/adb.exe',
    [string]$DeviceDir = '/sdcard/Download/three-engine-bench',
    [string]$Node24Dir = '',                     # local dir with probe+shim files (default: <here>\r5-node24)
    [string]$ProbeName = 'r5-node24-recognize.node24.js',
    [string]$ProbePath = '',                     # explicit local probe file (overrides Node24Dir\ProbeName)
    [string]$RemoteSubdir = 'r5-node24',         # remote dir under DeviceDir
    [string]$ResultRel = 'r5-node24-recognition.json',  # result path relative to DeviceDir
    [string]$LocalResult = 'r5-node24-recognition.json',
    [string]$Until = 'R5_NODE24_DONE',
    [int]$WaitSeconds = 180,
    [int]$Repeats = 1,                           # >1: run probe N times (timing range + parity on last run)
    [string]$Baseline = 'quickjs-baseline-recognition.json',
    [string]$Report = 'r5-recognition-parity.md',
    [string]$ReportJson = 'r5-recognition-parity.json',
    [switch]$SkipPushBundle,
    [switch]$DryRun
)

$ErrorActionPreference = 'Continue'
$Here = $PSScriptRoot
$Bundle = Join-Path $Here 'r5-chess'
if (-not $Node24Dir) { $Node24Dir = Join-Path $Here 'r5-node24' }
if (-not $ProbePath) { $ProbePath = Join-Path $Node24Dir $ProbeName }
$BaselinePath = Join-Path $Here $Baseline
$ReportPath = Join-Path $Here $Report
$ReportJsonPath = Join-Path $Here $ReportJson
$LocalResultPath = Join-Path $Here $LocalResult
$RemoteDir = "$DeviceDir/$RemoteSubdir"
$RemoteProbe = "$RemoteDir/$ProbeName"
$RemoteResult = "$DeviceDir/$ResultRel"

function Step([string]$Text) { Write-Host ""; Write-Host ("==== " + $Text + " ====") -ForegroundColor Cyan }
function Fail([string]$Text) { Write-Host ("FAIL: " + $Text) -ForegroundColor Red; exit 2 }

Step "0. inputs"
if (-not (Test-Path -LiteralPath $BaselinePath)) { Fail "missing QuickJS baseline: $BaselinePath" }
Write-Host "baseline: $BaselinePath"

if ($DryRun) {
    Step "1. DryRun: compare mock Node24 JSON (no device)"
    $mock = Join-Path $Here 'r5-node24-recognition.mock.json'
    $mockMd = Join-Path $Here 'r5-recognition-parity.mock.md'
    $mockJson = Join-Path $Here 'r5-recognition-parity.mock.json'
    if (-not (Test-Path -LiteralPath $mock)) { Fail "missing mock: $mock (run: python compare-recognition.py --emit-mock $mock)" }
    python (Join-Path $Here 'compare-recognition.py') --baseline $BaselinePath --node24 $mock --out-json $mockJson --out-md $mockMd
    $code = $LASTEXITCODE
    Write-Host ("compare exit=" + $code + " mock report=" + $mockMd)
    exit $code
}

Step "1. environment"
if (-not (Test-Path -LiteralPath $Adb)) { Fail "adb not found: $Adb" }
$devices = & $Adb devices 2>&1
if (-not ($devices -match ([regex]::Escape($Serial) + '\s+device'))) { Fail "device not connected: $Serial" }
$pkgs = & $Adb -s $Serial shell "pm list packages" 2>&1
if (-not ($pkgs -match [regex]::Escape($Package))) { Fail "test package not installed: $Package (wait for R5 build)" }
if (-not (Test-Path -LiteralPath $ProbePath)) {
    Fail ("missing Node24 probe: " + $ProbePath + " (wait for node-smem probe or pass -ProbePath; expected format: {input,bbox,grid,pieces,placement,counts,ms})")
}
Write-Host ("env ok: " + $Serial + " / " + $Package)
Write-Host ("probe: " + $ProbePath)

Step "2. push chess bundle + node24 probe/shim"
if (-not $SkipPushBundle) {
    if (-not (Test-Path -LiteralPath $Bundle)) { Fail "missing chess bundle: $Bundle (rebuild via run-r5-baseline.ps1 -OldRepo ...)" }
    & $Adb -s $Serial shell "rm -rf $DeviceDir/r5-chess" | Out-Null
    & $Adb -s $Serial push $Bundle "$DeviceDir/" 2>&1 | Select-Object -Last 1
}
& $Adb -s $Serial shell "mkdir -p $RemoteDir" | Out-Null
if (Test-Path -LiteralPath $Node24Dir) {
    & $Adb -s $Serial push "$Node24Dir/." "$RemoteDir/" 2>&1 | Select-Object -Last 1
}
$probeParent = Split-Path -Parent $ProbePath
if ($probeParent -and ((Resolve-Path -LiteralPath $probeParent).Path -ne (Resolve-Path -LiteralPath $Node24Dir -ErrorAction SilentlyContinue).Path)) {
    & $Adb -s $Serial push $ProbePath "$RemoteDir/" 2>&1 | Select-Object -Last 1
}
Write-Host "remote probe: $RemoteProbe"

Step "3. run Node24 probe"
$logPath = Join-Path $Here 'r5-node24-run.log'
$repFiles = @()
if ($Repeats -lt 1) { $Repeats = 1 }
for ($rep = 1; $rep -le $Repeats; $rep++) {
    Write-Host ("run #" + $rep + "/" + $Repeats)
    & $Adb -s $Serial shell "rm -f $RemoteResult" | Out-Null
    & $Adb -s $Serial logcat -c | Out-Null
    & $Adb -s $Serial shell "am broadcast -n $Package/com.jdkshen.aijspro.flutterpoc.DebugCommandReceiver -a com.jdkshen.aijspro.flutterpoc.DEBUG_RUN --es path $RemoteProbe" 2>&1 | Out-Null
    $waited = 0
    $done = $false
    while ($waited -lt $WaitSeconds) {
        Start-Sleep -Seconds 2
        $waited += 2
        $lines = & $Adb -s $Serial logcat -d -v threadtime 2>&1
        $lines | Set-Content -LiteralPath $logPath -Encoding UTF8
        if ($lines | Select-String -Pattern $Until | Select-Object -Last 1) { $done = $true; break }
    }
    if (-not $done) { Fail ("Node24 probe #" + $rep + " did not print " + $Until + " within " + $WaitSeconds + "s; log: " + $logPath) }
    $pull = & $Adb -s $Serial shell "cat $RemoteResult" 2>&1
    if ($LASTEXITCODE -ne 0) { Fail ("pull failed: " + $RemoteResult) }
    $text = ($pull -join "`n")
    if ($Repeats -gt 1) {
        $repPath = Join-Path $Here ("r5-node24-recognition.rep" + $rep + ".json")
    } else {
        $repPath = $LocalResultPath
    }
    [System.IO.File]::WriteAllText($repPath, $text, [System.Text.UTF8Encoding]::new($false))
    $repFiles += $repPath
    Write-Host ("  rep#" + $rep + " ok (" + $waited + "s wall, " + $text.Length + " chars) -> " + (Split-Path -Leaf $repPath))
}
$comparePath = $repFiles[$repFiles.Count - 1]
if ($Repeats -gt 1) {
    Copy-Item -LiteralPath $comparePath -Destination $LocalResultPath -Force
}
$RepMs = $null
try {
    $RepMs = python -c "import json,sys; d=json.load(open(sys.argv[1],encoding='utf-8')); print(' '.join('%s=%.0fms' % (x.get('input'), float(x.get('ms') or 0)) for x in (d.get('inputs') or [])))" $comparePath 2>&1
} catch { }
Write-Host ("result: " + $LocalResultPath + " (" + $comparePath + ")")
if ($RepMs) { Write-Host ("rep ms: " + ($RepMs -join ' ')) }

Step "5. compare with QuickJS baseline"
python (Join-Path $Here 'compare-recognition.py') --baseline $BaselinePath --node24 $comparePath --out-json $ReportJsonPath --out-md $ReportPath
$code = $LASTEXITCODE

Step "6. summary"
if (Test-Path -LiteralPath $ReportPath) { Get-Content -LiteralPath $ReportPath -TotalCount 30 }
Write-Host ("R5_NODE24_PARITY_" + $(if ($code -eq 0) { 'PASS' } else { 'FAIL' }))
exit $code
