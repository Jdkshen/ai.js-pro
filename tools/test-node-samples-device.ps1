<#
.SYNOPSIS
  Run bundled Node24 samples on a connected Android device (Node sample regression harness).
.DESCRIPTION
  Discovers node samples under apps/app/src/main/assets/sample/Node 引擎/**/*.js, skips
  interactive/project-only samples via a whitelist, pushes each case to
  /sdcard/脚本/_node_samples_regress_/ preserving relative paths, launches it with
  RunIntentActivity, watches logcat for completion markers, and writes
  output/node-samples-regress-<timestamp>/summary.json + summary.md + logs/.
  Repo sample files are never modified.
.PARAMETER Filter
  Substring or regex on sample relative path, e.g. -Filter 找色.
.PARAMETER DeviceId
  adb serial (e.g. cccc62c7). Default: the only online device; errors if several.
.PARAMETER TimeoutSeconds
  Per-sample wait budget in seconds (default 20).
.PARAMETER SkipPatterns
  Regex whitelist for interactive samples; default covers 悬浮窗/无障碍/截图授权/音乐/插件/
  网络服务/无限循环等. Pass -NoSkipPatterns to disable.
.PARAMETER SkipListFile
  Optional file with one regex per line to skip (blank lines and # comments ignored).
.PARAMETER IncludeProject
  Also run samples inside project dirs (project.json/package.json) by pushing the nearest
  project root. With -Filter, matching project samples are included implicitly.
.PARAMETER AllowScreenCapture
  Test-only: temporarily set PROJECT_MEDIA appop=allow so requestScreenCapture samples can
  auto-authorize; the previous value is restored afterwards.
.PARAMETER MaxSamples
  Limit executed cases after filtering (0 = all).
.PARAMETER ListOnly
  Only discover/classify and print; never touches the device.
.PARAMETER KeepScripts
  Keep pushed files on device (default: cleanup).
.EXAMPLE
  pwsh -NoProfile -File tools/test-node-samples-device.ps1 -ListOnly
.EXAMPLE
  pwsh -NoProfile -File tools/test-node-samples-device.ps1 -Filter 找色 -DeviceId cccc62c7 -AllowScreenCapture
#>
[CmdletBinding()]
param(
    [string]$DeviceId = '',
    [string]$Filter = '',
    [int]$TimeoutSeconds = 20,
    [string]$SkipPatterns = '悬浮窗|悬浮日志|无障碍|按键监听|选择器|坐标点击|模拟点击|模拟按键|电量优化|设备唤醒|锁屏|播放音乐|插件|MCP|http-server|axios|定时任务|对话框|通知与Toast|root-automator2|shell命令|文字识别|任务\.js|本地存储|设置\.js|floating_window',
    [switch]$NoSkipPatterns,
    [string]$SkipListFile = '',
    [switch]$AllowScreenCapture,
    [switch]$IncludeProject,
    [int]$MaxSamples = 0,
    [switch]$ListOnly,
    [switch]$KeepScripts
)

$ErrorActionPreference = 'Continue'
$projectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$packageName = 'com.jdkshen.aijspro'
$runner = $packageName + '/.external.open.RunIntentActivity'
$samplesRoot = Join-Path $projectRoot 'apps\app\src\main\assets\sample\Node 引擎'
$remoteRoot = '/sdcard/脚本/_node_samples_regress_'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outRoot = Join-Path $projectRoot ('output\node-samples-regress-' + $stamp)
$logDir = Join-Path $outRoot 'logs'
$nl = [Environment]::NewLine
$dq = [char]34

function Resolve-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -ne $cmd) { return $cmd.Source }
    $candidates = @('C:\Android\platform-tools\adb.exe', 'C:\Android\platform-tools-2\adb.exe')
    $resolved = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if ($null -eq $resolved) { throw 'adb.exe not found; install platform-tools or add adb to PATH.' }
    return $resolved
}
$adb = Resolve-Adb
function Invoke-Adb {
    param([string[]]$AdbArgs, [switch]$AllowFailure)
    $output = & $adb -s $script:DeviceId @AdbArgs 2>&1
    $code = $LASTEXITCODE
    if ($code -ne 0 -and -not $AllowFailure) {
        throw ('adb failed (' + $code + '): ' + ($AdbArgs -join ' '))
    }
    return $output
}
function Get-LogcatText {
    $raw = & $adb -s $script:DeviceId logcat -d -v brief 2>&1
    return ($raw | Out-String)
}
function Get-MatchingLines {
    param([string]$Text, [string]$Pattern, [int]$Limit = 3)
    $result = New-Object System.Collections.ArrayList
    foreach ($line in ($Text -split $nl)) {
        if ($line -match $Pattern) { [void]$result.Add($line.Trim()); if ($result.Count -ge $Limit) { break } }
    }
    return $result.ToArray()
}
function Get-RelativePath {
    param([string]$Base, [string]$Full)
    return $Full.Substring($Base.Length).TrimStart('\').Replace('\', '/')
}
function Quote-PathForShell {
    param([string]$Path)
    $safe = $Path.Replace([string][char]39, '')
    return [string][char]39 + $safe + [string][char]39
}
function Get-ProjectRootFor {
    param([System.IO.FileInfo]$File)
    $d = $File.Directory
    while ($null -ne $d -and $d.FullName.Length -ge $samplesRoot.Length) {
        if (Test-Path -LiteralPath (Join-Path $d.FullName 'project.json')) { return $d.FullName }
        if (Test-Path -LiteralPath (Join-Path $d.FullName 'package.json')) { return $d.FullName }
        $d = $d.Parent
    }
    return $null
}

$appVersion = 'list-only'
if (-not $ListOnly) {
    if ([string]::IsNullOrWhiteSpace($DeviceId)) {
        $online = & $adb devices | Select-String '^(\S+)\s+device$'
        if ($online.Count -ne 1) {
            throw ('Expected exactly one online device, found ' + $online.Count + '. Pass -DeviceId explicitly.')
        }
        $DeviceId = ($online[0].Line -split '\s+')[0]
    }
    $script:DeviceId = $DeviceId
    $raw = (Invoke-Adb @('shell', 'dumpsys', 'package', $packageName) -AllowFailure) | Out-String
    $m = [regex]::Match($raw, 'versionName=(\S+)')
    if ($m.Success) { $appVersion = $m.Groups[1].Value }
    if ($appVersion -eq 'unknown') {
        throw ('App ' + $packageName + ' is not installed on ' + $DeviceId + '. Install the dev APK first.')
    }
} else {
    if ([string]::IsNullOrWhiteSpace($DeviceId)) { $DeviceId = '(list-only)' }
    $script:DeviceId = $DeviceId
}
$skipRegexes = New-Object System.Collections.ArrayList
if (-not $NoSkipPatterns -and -not [string]::IsNullOrWhiteSpace($SkipPatterns)) {
    foreach ($p in ($SkipPatterns -split '\|')) {
        if (-not [string]::IsNullOrWhiteSpace($p)) { [void]$skipRegexes.Add($p.Trim()) }
    }
}
if (-not [string]::IsNullOrWhiteSpace($SkipListFile)) {
    if (-not (Test-Path -LiteralPath $SkipListFile -PathType Leaf)) { throw ('SkipListFile not found: ' + $SkipListFile) }
    foreach ($line in Get-Content -LiteralPath $SkipListFile -Encoding UTF8) {
        $trimmed = $line.Trim()
        if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
        [void]$skipRegexes.Add($trimmed)
    }
}

if (-not (Test-Path -LiteralPath $samplesRoot -PathType Container)) { throw ('Samples dir not found: ' + $samplesRoot) }
$files = Get-ChildItem -LiteralPath $samplesRoot -Recurse -File -Filter '*.js' |
    Where-Object { $_.FullName -notmatch '\\node_modules\\' } | Sort-Object FullName
$records = New-Object System.Collections.ArrayList
$ignoredCount = 0
foreach ($file in $files) {
    $rel = Get-RelativePath -Base $samplesRoot -Full $file.FullName
    $fullText = [System.IO.File]::ReadAllText($file.FullName, [System.Text.Encoding]::UTF8)
    $headLines = @($fullText -split $nl | Select-Object -First 14)
    $head = $headLines -join $nl
    $isNode = $head.Contains($dq + 'nodejs' + $dq) -or $head.Contains($dq + 'ui-thread' + $dq)
    if (-not $isNode) { $ignoredCount++; continue }
    if (-not [string]::IsNullOrWhiteSpace($Filter)) {
        if ($rel -notmatch $Filter) { $ignoredCount++; continue }
    }
    $projRoot = Get-ProjectRootFor -File $file
    $isMain = $file.Name -match '^(main|index)\.node\.js$|^main\.js$'
    $skipReason = $null
    if ($head -match 'device-test:\s*skip') { $skipReason = 'header: device-test: skip' }
    elseif ($isMain) { $skipReason = 'project entry (main.js/main.node.js)' }
    elseif ($fullText -match 'keepRunning\s*\(') { $skipReason = 'keeps running (keepRunning)' }
    elseif ($null -ne $projRoot -and -not $IncludeProject -and [string]::IsNullOrWhiteSpace($Filter)) { $skipReason = 'project-context (use -IncludeProject)' }
    if ($null -eq $skipReason) {
        foreach ($re in $skipRegexes) { if ($rel -match $re) { $skipReason = 'whitelist: ' + $re; break } }
    }
    $verdict = 'PENDING'
    if ($null -ne $skipReason) { $verdict = 'SKIP' }
    [void]$records.Add([pscustomobject]@{
        Index = 0
        RelativePath = $rel
        LocalPath = $file.FullName
        ProjectRoot = $projRoot
        SkipReason = $skipReason
        RemotePath = $null
        Verdict = $verdict
        DurationSeconds = 0
        ExitCode = $null
        Reason = $skipReason
        Evidence = @()
        LogFile = $null
    })
}

Write-Host ('Discovered: total=' + ($records.Count + $ignoredCount) + ', matched=' + $records.Count + ', ignored=' + $ignoredCount)

if ($ListOnly) {
    $i = 0
    foreach ($r in $records) {
        $i++
        if ($null -eq $r.SkipReason) { $status = 'RUN' } else { $status = 'SKIP' }
        Write-Host ('{0,2}. {1,-6} {2} {3}' -f $i, $status, $r.RelativePath, $r.SkipReason)
    }
    Write-Host 'ListOnly: device untouched.'
    exit 0
}

New-Item -ItemType Directory -Force -Path $outRoot, $logDir | Out-Null
Invoke-Adb @('shell', 'rm', '-rf', $remoteRoot) -AllowFailure | Out-Null
Invoke-Adb @('shell', 'mkdir', '-p', $remoteRoot) | Out-Null

$previousAppOp = $null
if ($AllowScreenCapture) {
    $opRaw = (Invoke-Adb @('shell', 'cmd', 'appops', 'get', $packageName, 'PROJECT_MEDIA') -AllowFailure) | Out-String
    $opMatch = [regex]::Match($opRaw, 'PROJECT_MEDIA:\s*(\w+)')
    if ($opMatch.Success) { $previousAppOp = $opMatch.Groups[1].Value }
    Invoke-Adb @('shell', 'cmd', 'appops', 'set', $packageName, 'PROJECT_MEDIA', 'allow') -AllowFailure | Out-Null
}

$executed = 0
$passCount = 0
$failCount = 0
$skipCount = 0
$timeoutCount = 0

foreach ($record in $records) {
    if ($null -ne $record.SkipReason) { $skipCount++; continue }
    if ($MaxSamples -gt 0 -and $executed -ge $MaxSamples) {
        $record.Verdict = 'SKIP'; $record.Reason = 'MaxSamples limit'; $skipCount++; continue
    }
    $executed++
    $record.Index = $executed
    try {
        if ($null -ne $record.ProjectRoot) {
            Invoke-Adb @('push', $record.ProjectRoot, ($remoteRoot + '/')) | Out-Null
            $projBase = Split-Path -Leaf $record.ProjectRoot
            $relInProj = $record.LocalPath.Substring($record.ProjectRoot.Length).TrimStart('\').Replace('\', '/')
            $record.RemotePath = $remoteRoot + '/' + $projBase + '/' + $relInProj
        } else {
            $parentRel = Split-Path -Parent $record.RelativePath
            if (-not [string]::IsNullOrWhiteSpace($parentRel)) { Invoke-Adb @('shell', 'mkdir', '-p', (Quote-PathForShell ($remoteRoot + '/' + $parentRel))) | Out-Null }
            Invoke-Adb @('push', $record.LocalPath, ($remoteRoot + '/' + $record.RelativePath)) | Out-Null
            $record.RemotePath = $remoteRoot + '/' + $record.RelativePath
        }
        Invoke-Adb @('shell', 'am', 'force-stop', $packageName) -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 250
        Invoke-Adb @('logcat', '-c') -AllowFailure | Out-Null
        $startedAt = Get-Date
        $launch = (Invoke-Adb @('shell', 'am', 'start', '-W', '-n', $runner, '--es', 'path', (Quote-PathForShell $record.RemotePath)) -AllowFailure) | Out-String
        if ($launch -match 'Error:') { throw ('RunIntentActivity launch failed: ' + $launch.Trim()) }
        $logText = ''
        $finished = $false
        $exitCode = $null
        $consentSeen = $false
        $elapsed = 0
        while ($elapsed -lt $TimeoutSeconds) {
            Start-Sleep -Seconds 1
            $elapsed = [int]((Get-Date) - $startedAt).TotalSeconds
            $logText = Get-LogcatText
            $exitMatch = [regex]::Match($logText, 'execute returned[^\n]*exitCode=(-?\d+)')
            if ($exitMatch.Success) {
                $exitCode = [int]$exitMatch.Groups[1].Value
                $finished = $true
                break
            }
            if ($logText -match 'Node24CaptureConsentActivity|MediaProjection|requestScreenCapture') { $consentSeen = $true }
            if ($logText -match '运行结束') { $finished = $true; break }
            if ($logText -match 'FATAL EXCEPTION') { break }
            if ($consentSeen -and $elapsed -ge 6) { break }
        }
        $record.DurationSeconds = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 1)
        $record.ExitCode = $exitCode
        $safeName = ($record.RelativePath -replace '[\\/:*?<>|]', '_')
        $logFile = Join-Path $logDir (('{0:d3}' -f $executed) + '-' + $safeName + '.log')
        $logText | Set-Content -LiteralPath $logFile -Encoding UTF8
        $record.LogFile = $logFile
        $hasScriptError = $logText -match 'SCRIPT_ERROR|ScriptException: SCRIPT_ERROR'
        if ($consentSeen -and -not $finished -and -not $AllowScreenCapture) {
            $record.Verdict = 'SKIP'
            $record.Reason = '需要用户授权截图（检测到截图授权 Activity；可加 -AllowScreenCapture）'
            $record.Evidence = Get-MatchingLines -Text $logText -Pattern 'CaptureConsent|MediaProjection|requestScreenCapture' -Limit 3
            $skipCount++
        } elseif ($null -ne $exitCode) {
            if ($exitCode -eq 0 -and -not $hasScriptError) {
                $record.Verdict = 'PASS'; $record.Reason = 'exitCode=0'; $passCount++
            } else {
                $record.Verdict = 'FAIL'; $record.Reason = 'exitCode=' + $exitCode + ', scriptError=' + $hasScriptError
                $record.Evidence = Get-MatchingLines -Text $logText -Pattern 'SCRIPT_ERROR|ScriptException|Error:' -Limit 3
                $failCount++
            }
        } elseif ($finished) {
            if ($hasScriptError) {
                $record.Verdict = 'FAIL'; $record.Reason = 'SCRIPT_ERROR'
                $record.Evidence = Get-MatchingLines -Text $logText -Pattern 'SCRIPT_ERROR|ScriptException' -Limit 3
                $failCount++
            } else {
                $record.Verdict = 'PASS'; $record.Reason = '运行结束（无 exitCode 标记）'; $passCount++
            }
        } elseif ($logText -match 'FATAL EXCEPTION') {
            $record.Verdict = 'FAIL'; $record.Reason = 'FATAL EXCEPTION'
            $record.Evidence = Get-MatchingLines -Text $logText -Pattern 'FATAL EXCEPTION' -Limit 2
            $failCount++
        } else {
            $windowDump = (Invoke-Adb @('shell', 'dumpsys', 'window', 'windows') -AllowFailure) | Out-String
            $activityDump = (Invoke-Adb @('shell', 'dumpsys', 'activity', 'activities') -AllowFailure) | Out-String
            $consentWindow = ($windowDump -match 'ProjectionPermission|MediaProjectionPermission|CaptureConsent') -or ($activityDump -match 'MediaProjectionPermissionActivity|CaptureConsent')
            $otherAppTop = ($activityDump -match 'topResumedActivity=') -and ($activityDump -notmatch 'topResumedActivity=[^\n]*com\.jdkshen\.aijspro')
            if ($consentWindow) {
                $record.Verdict = 'SKIP'
                $record.Reason = '等待系统录屏授权弹窗（需用户交互；可加 -AllowScreenCapture 尝试自动授权）'
                $record.Evidence = @('timeout=' + $TimeoutSeconds + 's', 'windowDump matched projection consent')
                $skipCount++
            } elseif ($otherAppTop) {
                $record.Verdict = 'SKIP'
                $record.Reason = '运行期间前台被其它 App 抢占，无法判定（勿在共享测试机上并发跑真机用例）'
                $record.Evidence = Get-MatchingLines -Text $activityDump -Pattern 'topResumedActivity=' -Limit 2
                $skipCount++
            } else {
                $record.Verdict = 'TIMEOUT'; $record.Reason = 'wait >' + $TimeoutSeconds + 's'
                $timeoutCount++
            }
        }
        $line = '{0:d3} {1,-7} {2,5:n1}s {3} {4}' -f $executed, $record.Verdict, $record.DurationSeconds, $record.RelativePath, $record.Reason
        if ($record.Verdict -eq 'FAIL') { Write-Host $line -ForegroundColor Red } else { Write-Host $line }
    } catch {
        $record.Verdict = 'FAIL'
        $errText = [string]$_.Exception.Message
        $errText = ($errText -split $nl)[0]
        if ($errText.Length -gt 200) { $errText = $errText.Substring(0, 200) }
        $record.Reason = 'harness error: ' + $errText
        $failCount++
        Write-Host ('{0:d3} FAIL {1} {2}' -f $executed, $record.RelativePath, $record.Reason) -ForegroundColor Red
    } finally {
        Invoke-Adb @('shell', 'am', 'force-stop', $packageName) -AllowFailure | Out-Null
    }
}

$counts = [pscustomobject]@{ executed = $executed; pass = $passCount; fail = $failCount; timeout = $timeoutCount; skip = $skipCount; ignored = $ignoredCount }
$summary = [pscustomobject]@{
    generatedAt = (Get-Date).ToString('o')
    device = $DeviceId
    appVersion = $appVersion
    filter = $Filter
    timeoutSeconds = $TimeoutSeconds
    samplesRoot = $samplesRoot
    counts = $counts
    cases = @($records)
}
$summaryJson = Join-Path $outRoot 'summary.json'
$summary | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $summaryJson -Encoding UTF8

$md = New-Object System.Collections.ArrayList
[void]$md.Add('# Node 示例设备回归结果')
[void]$md.Add('')
[void]$md.Add('- 生成时间：' + $summary.generatedAt)
[void]$md.Add('- 设备：' + $DeviceId + '，App：' + $appVersion)
[void]$md.Add('- Filter：' + $Filter + '；超时：' + $TimeoutSeconds + 's')
[void]$md.Add('- 统计：executed=' + $executed + ', pass=' + $passCount + ', fail=' + $failCount + ', timeout=' + $timeoutCount + ', skip=' + $skipCount + ', ignored=' + $ignoredCount)
[void]$md.Add('')
[void]$md.Add('| # | 示例 | 判定 | 用时(s) | exitCode | 原因 |')
[void]$md.Add('| --- | --- | --- | --- | --- | --- |')
foreach ($r in $records) {
    $ic = ''
    if ($null -ne $r.ExitCode) { $ic = [string]$r.ExitCode }
    $reasonText = [string]$r.Reason
    $reasonText = $reasonText.Replace('|', '/')
    [void]$md.Add('| ' + $r.Index + ' | ' + $r.RelativePath + ' | ' + $r.Verdict + ' | ' + $r.DurationSeconds + ' | ' + $ic + ' | ' + $reasonText + ' |')
}
$failed = @($records | Where-Object { $_.Verdict -eq 'FAIL' -or $_.Verdict -eq 'TIMEOUT' })
if ($failed.Count -gt 0) {
    [void]$md.Add('')
    [void]$md.Add('## 失败/超时明细')
    foreach ($r in $failed) {
        [void]$md.Add('- ' + $r.RelativePath + '：' + $r.Reason + '（log: ' + $r.LogFile + '）')
        foreach ($e in $r.Evidence) {
            $ev = ([string]$e).Trim()
            if ($ev.Length -gt 200) { $ev = $ev.Substring(0, 200) }
            [void]$md.Add('  - ' + $ev)
        }
    }
}
$mdFile = Join-Path $outRoot 'summary.md'
$md -join $nl | Set-Content -LiteralPath $mdFile -Encoding UTF8

if (-not $KeepScripts) { Invoke-Adb @('shell', 'rm', '-rf', $remoteRoot) -AllowFailure | Out-Null }
if ($AllowScreenCapture -and $null -ne $previousAppOp) {
    Invoke-Adb @('shell', 'cmd', 'appops', 'set', $packageName, 'PROJECT_MEDIA', $previousAppOp) -AllowFailure | Out-Null
}

Write-Host ''
Write-Host ('SUMMARY: pass=' + $passCount + ' fail=' + $failCount + ' timeout=' + $timeoutCount + ' skip=' + $skipCount)
Write-Host ('JSON: ' + $summaryJson)
Write-Host ('MD:   ' + $mdFile)
if (($failCount + $timeoutCount) -gt 0) { exit 1 } else { exit 0 }
