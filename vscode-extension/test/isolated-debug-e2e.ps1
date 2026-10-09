#!/usr/bin/env powershell
# Isolated VS Code debug E2E:
#   1. install our VSIX into a clean profile
#   2. open a temp workspace containing one JS file
#   3. env hook auto-connects to the phone (127.0.0.1:19317 via adb forward),
#      sets a breakpoint on line 4 and starts a debug session (F5 equivalent)
#   4. assert the DAP session reached "stopped line=4" and terminated
#
# Prereqs:
#   - phone app running with the Pro extension server enabled
#   - adb forward tcp:19317 tcp:9317

$ErrorActionPreference = 'Continue'
$repo = 'c:\Users\18101\Documents\ai-js-pro'
$vsix = Join-Path $repo 'vscode-extension\aijspro-vscode-0.2.0.vsix'
$code = 'd:\Microsoft VS Code\bin\code.cmd'
$tmp = Join-Path $env:TEMP 'aijspro-debug-e2e'
$ud = Join-Path $tmp 'user-data'
$ext = Join-Path $tmp 'extensions'
$ws = Join-Path $tmp 'ws'
$log = Join-Path $tmp 'debug-result.log'
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $ud, $ext, $ws -Force | Out-Null

# --- test script: breakpoint is expected on line 4 ---
$script = @(
    'var a = 1;'
    'function work() {'
    '    var b = 2;'
    '    var sum = a + b;'
    "    console.log('E2E_DEBUG_SUM ' + sum);"
    '    return sum;'
    '}'
    'work();'
    "console.log('E2E_DEBUG_END');"
) -join "`n"
[IO.File]::WriteAllText((Join-Path $ws 'e2e-debug.js'), $script)

& $code --user-data-dir $ud --extensions-dir $ext --install-extension $vsix --force 2>&1 | Select-Object -Last 2

# Wait until the transient instance spawned by the install finishes, then drop any
# cached profile/extension scan so the new window rescans $ext from scratch.
$deadline = (Get-Date).AddSeconds(20)
while ((Get-Date) -lt $deadline) {
    $alive = Get-CimInstance Win32_Process -Filter "Name='Code.exe'" |
        Where-Object { $_.CommandLine -like '*aijspro-debug-e2e*' }
    if (-not $alive) { break }
    Start-Sleep -Seconds 1
}
Remove-Item (Join-Path $ud 'CachedProfilesData') -Recurse -Force -ErrorAction SilentlyContinue

$env:AIJSPRO_E2E_CONNECT = '127.0.0.1:19317'
$env:AIJSPRO_E2E_DEBUG = '4'
$env:AIJSPRO_E2E_DEBUG_LOG = $log
& $code --user-data-dir $ud --extensions-dir $ext --disable-workspace-trust --skip-welcome --skip-release-notes --new-window $ws 2>&1 | Select-Object -Last 1

Start-Sleep -Seconds 30

$exthost = Get-ChildItem (Join-Path $ud 'logs') -Recurse -Filter 'exthost.log' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($exthost) {
    '--- exthost activation lines ---'
    Get-Content $exthost.FullName -Encoding UTF8 | Select-String -Pattern 'Activate|aijspro' |
        Select-Object -Last 10 | ForEach-Object { $_.Line }
}

$result = ''
if (Test-Path $log) {
    $result = Get-Content $log -Raw -Encoding UTF8
    '--- debug-result.log ---'
    $result
    '--- end of log ---'
} else {
    'E2E: debug-result.log was not created at all'
}

$ok = $true
foreach ($needle in @('ready:', 'breakpoints: 4', 'stopped line=4')) {
    if ($result -notlike ('*' + $needle + '*')) {
        $ok = $false
        "E2E MISSING: $needle"
    }
}

if ($ok) {
    'E2E OK: debug session stopped at line 4 via DAP'
} else {
    'E2E FAIL: see debug-result.log'
}

Get-CimInstance Win32_Process -Filter "Name='Code.exe'" |
    Where-Object { $_.CommandLine -like '*aijspro-debug-e2e*' } |
    ForEach-Object { 'kill pid ' + $_.ProcessId; Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
