#!/usr/bin/env powershell
# Isolated VS Code instance E2E: install only our VSIX into a clean profile,
# launch with AIJSPRO_E2E_CONNECT=<phone>, then check the extension wrote
# aijspro.devices (only written after a completed hello handshake).

$ErrorActionPreference = 'Continue'
$repo = 'c:\Users\18101\Documents\ai-js-pro'
$vsix = Join-Path $repo 'vscode-extension\aijspro-vscode-0.2.0.vsix'
$code = 'd:\Microsoft VS Code\bin\code.cmd'
$tmp = Join-Path $env:TEMP 'aijspro-vsix-e2e'
$ud = Join-Path $tmp 'user-data'
$ext = Join-Path $tmp 'extensions'
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $ud, $ext -Force | Out-Null

& $code --user-data-dir $ud --extensions-dir $ext --install-extension $vsix --force 2>&1 | Select-Object -Last 2

$env:AIJSPRO_E2E_CONNECT = '192.168.31.59:9317'
& $code --user-data-dir $ud --extensions-dir $ext --new-window (Join-Path $repo 'vscode-extension') 2>&1 | Select-Object -Last 1

Start-Sleep -Seconds 12

$settings = Join-Path $ud 'User\settings.json'
if (Test-Path $settings) {
    $s = Get-Content $settings -Raw -Encoding UTF8
    if ($s -match '"aijspro\.devices"\s*:\s*\[[^\]]*\]') {
        'E2E OK: ' + $Matches[0]
    } else {
        'E2E FAIL: aijspro.devices not written'
    }
} else {
    'E2E FAIL: settings.json missing'
}

Get-CimInstance Win32_Process -Filter "Name='Code.exe'" |
    Where-Object { $_.CommandLine -like '*aijspro-vsix-e2e*' } |
    ForEach-Object { 'kill pid ' + $_.ProcessId; Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
