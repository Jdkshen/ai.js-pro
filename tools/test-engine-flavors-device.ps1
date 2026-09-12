<#
.SYNOPSIS
  Run the QuickJS/Rhino flavor matrix on one connected Android device.
.PARAMETER Serial
  Optional adb serial. When omitted, exactly one online device is required.
.PARAMETER SkipBuild
  Reuse the current compat/lite debug APKs.
#>
param(
    [string]$Serial = '',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$packageName = 'com.jdkshen.aijspro'
$runner = "$packageName/.external.open.RunIntentActivity"
$remoteDirectory = '/sdcard/Scripts/engine-matrix'

function Resolve-Adb {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }
    $candidates = @(
        'C:\Android\platform-tools-2\adb.exe',
        'C:\Android\platform-tools\adb.exe',
        'C:\Users\18101\Documents\Codex\2026-09-06\xz\work\android-platform-tools\platform-tools\adb.exe'
    )
    $resolved = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
    if ($null -eq $resolved) { throw 'adb.exe was not found.' }
    return $resolved
}

$adb = Resolve-Adb
if ([string]::IsNullOrWhiteSpace($Serial)) {
    $online = & $adb devices | Select-String '^\S+\s+device$'
    if ($online.Count -ne 1) {
        throw "Expected exactly one online device, found $($online.Count). Pass -Serial explicitly."
    }
    $Serial = ($online[0].Line -split '\s+')[0]
}

function Invoke-Adb {
    param([string[]]$AdbArgs)
    & $adb -s $Serial @AdbArgs
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed ($LASTEXITCODE): $($AdbArgs -join ' ')"
    }
}

function Install-Apk {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "APK not found: $Path"
    }
    Invoke-Adb @('install', '-r', $Path) | Out-Host
}

function Invoke-ScriptCheck {
    param(
        [string]$LocalScript,
        [string]$RemoteName,
        [string]$ExpectedLog
    )
    $remotePath = "$remoteDirectory/$RemoteName"
    Invoke-Adb @('push', $LocalScript, $remotePath) | Out-Host
    Invoke-Adb @('logcat', '-c') | Out-Null
    Invoke-Adb @('shell', 'am', 'force-stop', $packageName) | Out-Null
    Invoke-Adb @('shell', 'am', 'start', '-a', 'android.intent.action.VIEW',
        '-d', "file://$remotePath", '-t', 'text/plain', '-n', $runner) | Out-Host
    Start-Sleep -Seconds 6

    $crash = (Invoke-Adb @('logcat', '-d', '-b', 'crash')) -join "`n"
    $log = (Invoke-Adb @('logcat', '-d', '-v', 'brief')) -join "`n"
    if ($crash -match 'FATAL EXCEPTION' -or $log -match 'FATAL EXCEPTION') {
        throw "Device crash while running $RemoteName`n$crash"
    }
    if (-not $log.Contains($ExpectedLog)) {
        throw "Expected log marker was not found for ${RemoteName}: $ExpectedLog"
    }
    Write-Host "PASS $RemoteName -> $ExpectedLog" -ForegroundColor Green
}

$compatApk = Join-Path $projectRoot `
    'apps\app\build\outputs\apk\miuixCompat\debug\app-miuix-compat-arm64-v8a-debug.apk'
$liteApk = Join-Path $projectRoot `
    'apps\app\build\outputs\apk\miuixLite\debug\app-miuix-lite-arm64-v8a-debug.apk'
$quickJsScript = Join-Path $projectRoot `
    'apps\app\src\main\assets\sample\QuickJS 新引擎\QuickJS 全模块回归测试.js'
$rhinoScript = Join-Path $projectRoot `
    'apps\app\src\main\assets\sample\Rhino 引擎\全模块回归测试.js'
$uiFloatyScript = Join-Path $projectRoot `
    'apps\app\src\main\assets\sample\QuickJS 新引擎\QuickJS UI Floaty 回归测试.js'

try {
    if (-not $SkipBuild) {
        & (Join-Path $projectRoot 'tools\test-miuix.ps1') -GradleTasks @(
            ':app:testMiuixCompatDebugUnitTest',
            ':app:testMiuixLiteDebugUnitTest',
            ':app:assembleMiuixCompatDebug',
            ':app:assembleMiuixLiteDebug'
        )
        if ($LASTEXITCODE -ne 0) { throw "Gradle verification failed with exit code $LASTEXITCODE" }
    }

    Invoke-Adb @('shell', 'mkdir', '-p', $remoteDirectory) | Out-Null

    Write-Host '=== compat: QuickJS + legacy Rhino ===' -ForegroundColor Cyan
    Install-Apk $compatApk
    Invoke-ScriptCheck $quickJsScript 'quickjs-full.js' 'QUICKJS_REGRESSION_OK'
    Invoke-ScriptCheck $uiFloatyScript 'quickjs-ui-floaty.js' 'QUICKJS_UI_FLOATY_OK'
    Invoke-ScriptCheck $rhinoScript 'zz_regress_rhino.js' 'Rhino 回归完成: 23 pass, 0 fail'

    Write-Host '=== lite: QuickJS + controlled Rhino rejection ===' -ForegroundColor Cyan
    Install-Apk $liteApk
    Invoke-ScriptCheck $quickJsScript 'quickjs-full.js' 'QUICKJS_REGRESSION_OK'
    Invoke-ScriptCheck $uiFloatyScript 'quickjs-ui-floaty.js' 'QUICKJS_UI_FLOATY_OK'
    Invoke-ScriptCheck $rhinoScript 'zz_regress_rhino.js' '此脚本需要 Rhino 兼容引擎'

    Write-Host '=== restore compat ===' -ForegroundColor Cyan
    Install-Apk $compatApk
    Invoke-Adb @('shell', 'am', 'force-stop', $packageName) | Out-Null
    Invoke-Adb @('shell', 'monkey', '-p', $packageName,
        '-c', 'android.intent.category.LAUNCHER', '1') | Out-Null
    Start-Sleep -Seconds 3
    $processId = (Invoke-Adb @('shell', 'pidof', $packageName)) -join ''
    if ([string]::IsNullOrWhiteSpace($processId)) { throw 'App did not remain running after compat restore.' }

    Write-Host "ENGINE FLAVOR MATRIX PASSED on $Serial" -ForegroundColor Green
} finally {
    & $adb -s $Serial shell rm -f "$remoteDirectory/quickjs-full.js" `
        "$remoteDirectory/quickjs-ui-floaty.js" "$remoteDirectory/zz_regress_rhino.js" | Out-Null
    & $adb -s $Serial shell rmdir $remoteDirectory 2>$null | Out-Null
}
