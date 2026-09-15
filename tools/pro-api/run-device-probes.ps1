# One-shot Pro API device probe runner: 4 member-enumeration variants + 3 path variants,
# then parse everything and recompute the compatibility rollup.
#
# ASCII only: PowerShell 5.1 mis-decodes BOM-less UTF-8 .ps1 files.
# Probe file <-> engine mapping (see gen-device-probe.py):
#   pro-api-probe.js          = quickjs        pro-api-probe-ui.js       = quickjs + "ui";
#   pro-api-probe-rhino.js    = rhino          pro-api-probe-rhino-ui.js = rhino + "ui";
#   pro-api-paths.js / -rhino.js / -rhino-ui.js = same idea for the path probe.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File tools\pro-api\run-device-probes.ps1
param(
    [string]$Adb = 'C:\Android\platform-tools-2\adb.exe',
    [string]$DeviceId = 'ce4d2bdb',
    [string]$Package = 'com.jdkshen.aijspro'
)
$ErrorActionPreference = 'Continue'
# Walk up until project-versions.json shows up (do not trust $PSScriptRoot / cwd).
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
while ($root -and -not (Test-Path (Join-Path $root 'project-versions.json'))) {
    $parent = Split-Path -Parent $root
    if ($parent -eq $root) { throw 'repo root not found' }
    $root = $parent
}
$art = Join-Path $root '.artifacts'
$py = Join-Path $root '.venv\Scripts\python.exe'
Set-Location $root
Write-Host "repo root: $root"

$members = @(
    @{ file = 'pro-api-probe-rhino.js';    log = 'probe-rhino.log';    engine = 'rhino' },
    @{ file = 'pro-api-probe-rhino-ui.js'; log = 'probe-rhino-ui.log'; engine = 'rhino-ui' },
    @{ file = 'pro-api-probe.js';          log = 'probe-qjs.log';      engine = 'quickjs' },
    @{ file = 'pro-api-probe-ui.js';       log = 'probe-ui.log';       engine = 'quickjs-ui' }
)
$paths = @(
    @{ file = 'pro-api-paths-rhino.js';    log = 'probe-paths-rhino.log';    wait = 22 },
    @{ file = 'pro-api-paths-rhino-ui.js'; log = 'probe-paths-rhino-ui.log'; wait = 22 },
    @{ file = 'pro-api-paths.js';          log = 'probe-paths-qjs.log';       wait = 22 }
)

function Invoke-ProbeScript {
    param([string]$Source, [string]$LogName, [int]$WaitSeconds)
    # Without a force-stop `am start` only delivers onNewIntent and the script does NOT
    # re-run -- the log would stay empty (or show the previous script's output).
    & $Adb -s $DeviceId shell am force-stop $Package
    & $Adb -s $DeviceId push $Source '/sdcard/probe/current.js' | Out-Null
    & $Adb -s $DeviceId logcat -c
    & $Adb -s $DeviceId shell am start -a android.intent.action.VIEW -d 'file:///sdcard/probe/current.js' -t text/plain -n "$Package/.external.open.RunIntentActivity" | Out-Null
    & $Adb -s $DeviceId shell sleep $WaitSeconds
    cmd /c "`"$Adb`" -s $DeviceId logcat -d -s GlobalConsole:V > `"$(Join-Path $art $LogName)`""
}

foreach ($v in $members) {
    Invoke-ProbeScript -Source (Join-Path $art $v.file) -LogName $v.log -WaitSeconds 25
    Write-Host "member probe done: $($v.engine)"
}
foreach ($v in $paths) {
    Invoke-ProbeScript -Source (Join-Path $art $v.file) -LogName $v.log -WaitSeconds $v.wait
    Write-Host "path probe done: $($v.file)"
}

foreach ($v in $members) {
    & $py 'tools\pro-api\parse-device-probe.py' (Join-Path $art $v.log) $v.engine
}
# rhino + rhino-ui path logs merge into one engine result.
& $py 'tools\pro-api\parse-device-paths.py' (Join-Path $art 'probe-paths-rhino.log') (Join-Path $art 'probe-paths-rhino-ui.log') --engine rhino
& $py 'tools\pro-api\parse-device-paths.py' (Join-Path $art 'probe-paths-qjs.log') --engine quickjs
& $py 'tools\pro-api\rebuild.py' --stage check --stage rollup
Write-Host 'probes + check + rollup finished'
