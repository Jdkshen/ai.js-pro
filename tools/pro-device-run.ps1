<#
.SYNOPSIS
Runs a JS file inside an installed Auto.js-family app (e.g. Auto.js Pro) on the attached
device and collects a result file written by the script.

.RECIPE (learned 2026-10-01 on the K40, see docs/reports/2026-10-01_AutoJsPro逆向与图色API对照.md)
  1. adb push <script> -> <DeviceDir>/<name>
  2. force-stop + relaunch the target app  (the editor caches tab content; always start clean)
  3. locate the file row in the list via uiautomator dump (row positions vary -> dynamic search)
  4. open the editor (tap row) and tap the run button (resource-id <pkg>:id/run)
  5. poll the result file until it stops changing, then print the final content
  6. force-stop the app again unless -KeepRunning (this also kills hung scripts)

.EXAMPLE
  powershell -File tools/pro-device-run.ps1 -Script .artifacts/pro-hang-probe.js
  powershell -File tools/pro-device-run.ps1 -Script .artifacts/matrix-probe.js -Package com.boyai.auma
#>
param(
    [Parameter(Mandatory = $true)][string]$Script,
    [string]$Package = 'org.autojs.autojspro',
    [string]$DeviceDir = '',
    [string]$ResultFile = '',
    [int]$TimeoutSeconds = 25,
    [int]$PollSeconds = 2,
    [int]$OpenWaitSeconds = 3,
    [switch]$KeepRunning,
    [string]$SaveResult
)

$ErrorActionPreference = 'Continue'
$adb = 'C:\Android\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = 'adb' }

# default device dir: /sdcard/ + <U+811A><U+672C>  (kept ASCII in this file on purpose)
if (-not $DeviceDir) { $DeviceDir = '/sdcard/' + [string][char]0x811A + [string][char]0x672C }
if (-not $ResultFile) { $ResultFile = "$DeviceDir/steps.txt" }

$scriptPath = (Resolve-Path -LiteralPath $Script).Path
$scriptName = Split-Path -Leaf $scriptPath
$deviceScript = "$DeviceDir/$scriptName"

function Adb([string[]]$AdbArgs) {
    # adb writes info to stderr; keep it out of the error stream (PS 5.1 would print a red block)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    $r = & $adb @AdbArgs 2>&1 | Out-String
    $ErrorActionPreference = $prev
    return $r
}

$state = (Adb @('get-state')).Trim()
if ($state -ne 'device') { Write-Host "ERROR: no device (adb get-state = $state)"; exit 3 }

# make sure the screen is on and unlocked, otherwise uiautomator/screencap act on a black screen
Adb @('shell', 'input', 'keyevent', '224') | Out-Null   # KEYCODE_WAKEUP
Adb @('shell', 'wm', 'dismiss-keyguard') | Out-Null

Write-Host "[1/6] push -> $deviceScript"
Write-Host (Adb @('push', $scriptPath, $deviceScript))

Write-Host "[2/6] clear result + restart $Package"
Adb @('shell', 'rm', '-f', $ResultFile) | Out-Null
Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
Start-Sleep -Seconds 2
Adb @('shell', 'monkey', '-p', $Package, '-c', 'android.intent.category.LAUNCHER', '1') | Out-Null
Start-Sleep -Seconds 6
# the notification shade may be covering the app (left over from previous automation)
Adb @('shell', 'cmd', 'statusbar', 'collapse') | Out-Null
Start-Sleep -Seconds 1

function Get-UiXml {
    Adb @('shell', 'uiautomator', 'dump', '/sdcard/.__pro_ui.xml') | Out-Null
    $tmp = Join-Path $env:TEMP '.__pro_ui.xml'
    Adb @('pull', '/sdcard/.__pro_ui.xml', $tmp) | Out-Null
    if (Test-Path $tmp) { return [IO.File]::ReadAllText($tmp, [Text.Encoding]::UTF8) }
    return ''
}

Write-Host "[3/6] locate '$scriptName' in the file list"
$opened = $false
# Pro may relaunch straight into the editor it last had open; handle that first.
$xml0 = Get-UiXml
if ($xml0 -match ':id/run' -or $xml0 -match 'editorView') {
    if ($xml0.Contains($scriptName)) {
        Write-Host '  already in editor with target file open; skipping file-list step'
        $opened = $true
    } else {
        Write-Host '  editor open with another file; pressing BACK to the file list'
        Adb @('shell', 'input', 'keyevent', '4') | Out-Null
        Start-Sleep -Seconds 2
    }
}
for ($round = 1; $round -le 10 -and -not $opened; $round++) {
    $xml = Get-UiXml
    foreach ($n in [regex]::Matches($xml, '<node[^>]*>')) {
        $tag = $n.Value
        $cd = ([regex]::Match($tag, 'content-desc="([^"]*)"')).Groups[1].Value
        $m = [regex]::Match($tag, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if ($m.Success -and $cd.Contains($scriptName)) {
            $cy = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
            Write-Host ("  round {0}: row found, y={1}; tapping" -f $round, $cy)
            Adb @('shell', 'input', 'tap', '300', "$cy") | Out-Null
            $opened = $true
            break
        }
    }
    if (-not $opened) {
        Adb @('shell', 'input', 'swipe', '540', '1900', '540', '700', '300') | Out-Null
        Start-Sleep -Milliseconds 600
    }
}
if (-not $opened) { Write-Host 'ERROR: script row not found (is the app showing its file list?)'; exit 3 }

# auma (高级版) pops its own integrity guard ("ResetDataActivity" / 需要重置应用数据):
# choose 暂不清除 (button2) -- the app then continues opening the editor.
Start-Sleep -Seconds 2
$focus = (Adb @('shell', 'dumpsys', 'window')) -join ''
if ($focus -match 'ResetDataActivity') {
    Write-Host '  guard dialog detected (auma integrity); tapping button2 (keep data)'
    $gx = 526; $gy = 1453
    $gxml = Get-UiXml
    $gm = [regex]::Match($gxml, 'resource-id="android:id/button2"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($gm.Success) {
        $gx = [int](([int]$gm.Groups[1].Value + [int]$gm.Groups[3].Value) / 2)
        $gy = [int](([int]$gm.Groups[2].Value + [int]$gm.Groups[4].Value) / 2)
    }
    Adb @('shell', 'input', 'tap', "$gx", "$gy") | Out-Null
    Start-Sleep -Seconds 2
}

Write-Host '[4/6] tap run'
Start-Sleep -Seconds $OpenWaitSeconds
$xml = Get-UiXml
$rm = [regex]::Match($xml, 'resource-id="[^"]*:id/run"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if ($rm.Success) {
    $rx = [int](([int]$rm.Groups[1].Value + [int]$rm.Groups[3].Value) / 2)
    $ry = [int](([int]$rm.Groups[2].Value + [int]$rm.Groups[4].Value) / 2)
} else {
    $rx = 772; $ry = 165
}
Write-Host ("  run button at ({0},{1})" -f $rx, $ry)
Adb @('shell', 'input', 'tap', "$rx", "$ry") | Out-Null

Write-Host "[5/6] wait for $ResultFile (timeout ${TimeoutSeconds}s; stops early when stable)"
$content = ''
$stable = 0
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds $PollSeconds
    $cur = (Adb @('shell', "cat $ResultFile 2>/dev/null || echo __MISSING__")).Trim()
    if ($cur -eq '__MISSING__') { $cur = '' }
    if ($cur -and $cur -eq $content) {
        $stable++
        if ($stable -ge 2) { break }
    } else {
        $stable = 0
        $content = $cur
    }
}

Write-Host '[6/6] ---- result ----'
Write-Host $content
if ($SaveResult -and $content) {
    [IO.File]::WriteAllText($SaveResult, $content, (New-Object Text.UTF8Encoding($false)))
    Write-Host "result saved -> $SaveResult"
}
if (-not $KeepRunning) {
    Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
    Write-Host 'app stopped (cleanup; use -KeepRunning to keep it alive)'
}
if ($content) { Write-Host 'STATUS: collected'; exit 0 }
Write-Host 'STATUS: NO RESULT (timeout)'; exit 2
