#!/usr/bin/env powershell
# Isolated VS Code color-assistant E2E:
#   1. install our VSIX into a clean profile
#   2. env hook auto-connects to the phone (127.0.0.1:19317 via adb forward) and
#      opens the "Pick Color" webview panel (AIJSPRO_E2E_COLOR=1)
#   3. assert the extension captured a screenshot from the phone and saved the
#      PNG into the extension storage folder
#
# Prereqs:
#   - phone app running with the Pro extension server enabled and accessibility on
#   - adb forward tcp:19317 tcp:9317

$ErrorActionPreference = 'Continue'
$repo = 'c:\Users\18101\Documents\ai-js-pro'
$vsix = Join-Path $repo 'vscode-extension\aijspro-vscode-0.3.0.vsix'
$code = 'd:\Microsoft VS Code\bin\code.cmd'
$tmp = Join-Path $env:TEMP 'aijspro-color-e2e'
$ud = Join-Path $tmp 'user-data'
$ext = Join-Path $tmp 'extensions'
$ws = Join-Path $tmp 'ws'
$log = Join-Path $tmp 'color-result.log'
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $ud, $ext, $ws -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $ws 'main.js'), "console.log('color e2e');")

& $code --user-data-dir $ud --extensions-dir $ext --install-extension $vsix --force 2>&1 | Select-Object -Last 2

$deadline = (Get-Date).AddSeconds(20)
while ((Get-Date) -lt $deadline) {
    $alive = Get-CimInstance Win32_Process -Filter "Name='Code.exe'" |
        Where-Object { $_.CommandLine -like '*aijspro-color-e2e*' }
    if (-not $alive) { break }
    Start-Sleep -Seconds 1
}
Remove-Item (Join-Path $ud 'CachedProfilesData') -Recurse -Force -ErrorAction SilentlyContinue

$env:AIJSPRO_E2E_CONNECT = '127.0.0.1:19317'
$env:AIJSPRO_E2E_COLOR = '1'
$env:AIJSPRO_E2E_COLOR_TEST = '#fff7f7f7'
$env:AIJSPRO_E2E_COLOR_TEST_MULTI = '#fff7f7f7'
$env:AIJSPRO_E2E_DEBUG_LOG = $log
& $code --user-data-dir $ud --extensions-dir $ext --disable-workspace-trust --skip-welcome --skip-release-notes --new-window $ws 2>&1 | Select-Object -Last 1

Start-Sleep -Seconds 30

$result = ''
if (Test-Path $log) {
    $result = Get-Content $log -Raw -Encoding UTF8
    '--- color-result.log ---'
    $result
    '--- end of log ---'
} else {
    'E2E: color-result.log was not created at all'
}

$shots = Get-ChildItem $ud -Recurse -Filter 'shot-*.png' -ErrorAction SilentlyContinue
'screenshots found: ' + ($shots | Measure-Object).Count

$ok = $true
foreach ($needle in @('color assistant opened', 'screenshot 1080x', 'test {"kind":"findColor"', 'test {"kind":"findMulti"', 'device test done', 'multi test done')) {
    if ($result -notlike ('*' + $needle + '*')) {
        $ok = $false
        "E2E MISSING: $needle"
    }
}
if (($shots | Measure-Object).Count -lt 1) {
    $ok = $false
    'E2E MISSING: saved screenshot file'
}

if ($ok) {
    'E2E OK: color assistant captured phone screenshot into VS Code'
} else {
    'E2E FAIL: see color-result.log'
}

Get-CimInstance Win32_Process -Filter "Name='Code.exe'" |
    Where-Object { $_.CommandLine -like '*aijspro-color-e2e*' } |
    ForEach-Object { 'kill pid ' + $_.ProcessId; Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
