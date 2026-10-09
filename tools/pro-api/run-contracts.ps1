# 一键跑完 tools/pro-api 下的所有真机契约脚本，并汇总 pass/fail。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\pro-api\run-contracts.ps1
#   powershell ... -File .\tools\pro-api\run-contracts.ps1 -Only ocr,features
#   powershell ... -File .\tools\pro-api\run-contracts.ps1 -DeviceId ce4d2bdb
#
# 说明：
# - 每个脚本跑之前都会 force-stop 应用（否则 am start 只投递 intent，脚本不重跑），
#   并重新写一遍无障碍服务设置（force-stop 之后不会自动重绑）。
# - 日志落在 .artifacts\contract-<脚本名>.log；汇总结果写 .artifacts\contracts-summary.txt。
# - 任一脚本失败时脚本以退出码 1 结束。
param(
    [string]$DeviceId = 'ce4d2bdb',
    [string[]]$Only = @(),
    [int]$DefaultSleepSeconds = 35
)

$ErrorActionPreference = 'Continue'
# ⚠️ 顶层作用域里没有 $MyInvocation.MyCommand.Path（那是函数内才可靠），
#    powershell -File 与 & .\script.ps1 两种跑法都用 $PSScriptRoot 最稳。
$scriptDir = $PSScriptRoot
if (-not $scriptDir) {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
}
$repo = Split-Path -Parent (Split-Path -Parent $scriptDir)
if (-not (Test-Path (Join-Path $repo 'project-versions.json'))) {
    throw "无法定位仓库根目录（从 $scriptDir 推断出 $repo）"
}
$adb = 'C:\Android\platform-tools-2\adb.exe'
$artifactDir = Join-Path $repo '.artifacts'
if (-not (Test-Path $artifactDir)) { New-Item -ItemType Directory -Path $artifactDir | Out-Null }

# 脚本名 -> 每个脚本需要等多久（默认 DefaultSleepSeconds）；debug 会 dump hprof，需要更久
$contracts = [ordered]@{
    'crypto-contract.js'                = 30
    'zip-contract.js'                   = 40
    'debug-contract.js'                 = 90
    'power-manager-contract.js'         = 30
    'floaty-permission-contract.js'     = 30
    'settings-contract.js'              = 35
    'dialog-contract.js'                = 30
    'colormapping-contract.js'          = 30
    'click-center-contract.js'          = 35
    'images-contract.js'                = 40
    'automator-extra-contract.js'       = 60
    'engines-controller-contract.js'    = 60
    'rootautomator2-contract.js'        = 60
    'ocr-contract.js'                   = 75
    'features-contract.js'              = 45
}

$accessibility = 'com.jdkshen.aijspro/com.google.android.accessibility.selecttospeak.SelectToSpeakService'

# -File 调用时 `-Only a,b` 会当成一个字符串传进来，这里统一拆开
$filter = @()
foreach ($item in $Only) {
    $filter += ($item -split ',')
}
$filter = @($filter | Where-Object { $_ } | ForEach-Object { $_.Trim() })

$results = @()

foreach ($script in $contracts.Keys) {
    $name = $script.Replace('-contract.js', '')
    if ($filter.Count -gt 0 -and ($filter -notcontains $name)) {
        continue
    }
    $path = Join-Path $repo "tools\pro-api\$script"
    if (-not (Test-Path $path)) {
        $results += [pscustomobject]@{ Script = $script; Summary = '(missing)'; Passed = $false }
        continue
    }
    Write-Host "=== $script ===" -ForegroundColor Cyan
    & $adb -s $DeviceId push $path '/sdcard/probe/current.js' | Out-Null
    & $adb -s $DeviceId shell am force-stop com.jdkshen.aijspro | Out-Null
    & $adb -s $DeviceId shell settings put secure enabled_accessibility_services $accessibility | Out-Null
    & $adb -s $DeviceId shell settings put secure accessibility_enabled 1 | Out-Null
    Start-Sleep -Seconds 3
    & $adb -s $DeviceId shell logcat -c | Out-Null
    & $adb -s $DeviceId shell am start -a android.intent.action.VIEW -d 'file:///sdcard/probe/current.js' `
        -t text/plain -n com.jdkshen.aijspro/.external.open.RunIntentActivity | Out-Null
    Start-Sleep -Seconds $contracts[$script]
    $log = Join-Path $artifactDir "contract-$script.log"
    cmd /c "`"$adb`" -s $DeviceId logcat -d -s GlobalConsole:V > `"$log`""
    $lines = Get-Content -Path $log -Encoding UTF8 | ForEach-Object { $_ -replace '^.*GlobalConsole: ', '' }
    $summary = $lines | Where-Object { $_ -match '_SUMMARY\|' } | Select-Object -Last 1
    $failed = $lines | Where-Object { $_ -match '^FAIL ' }
    if (-not $summary) { $summary = '(no summary)' }
    $passed = ($summary -match 'failed=0')
    $results += [pscustomobject]@{ Script = $script; Summary = $summary; Passed = $passed; FailedLines = @($failed) }
    Write-Host ("  " + $summary)
    foreach ($line in $failed) { Write-Host ("  " + $line) -ForegroundColor Yellow }
}

# 资源释放检查：两个脚本连跑（第 1 个故意「用完不释放」，第 2 个看有没有被自动清掉）
function Invoke-ContractScript {
    param([string]$ScriptFile, [int]$WaitSeconds)
    $path = Join-Path $repo "tools\pro-api\$ScriptFile"
    & $adb -s $DeviceId push $path '/sdcard/probe/current.js' | Out-Null
    & $adb -s $DeviceId shell am force-stop com.jdkshen.aijspro | Out-Null
    & $adb -s $DeviceId shell settings put secure enabled_accessibility_services $accessibility | Out-Null
    & $adb -s $DeviceId shell settings put secure accessibility_enabled 1 | Out-Null
    Start-Sleep -Seconds 3
    & $adb -s $DeviceId shell logcat -c | Out-Null
    & $adb -s $DeviceId shell am start -a android.intent.action.VIEW -d 'file:///sdcard/probe/current.js' `
        -t text/plain -n com.jdkshen.aijspro/.external.open.RunIntentActivity | Out-Null
    Start-Sleep -Seconds $WaitSeconds
    $log = Join-Path $artifactDir "contract-$ScriptFile.log"
    cmd /c "`"$adb`" -s $DeviceId logcat -d -s GlobalConsole:V > `"$log`""
    return (Get-Content -Path $log -Encoding UTF8 | ForEach-Object { $_ -replace '^.*GlobalConsole: ', '' })
}

if ($filter.Count -eq 0 -or $filter -contains 'cleanup') {
    Write-Host '=== cleanup（资源释放：脚本退出后自动回收）===' -ForegroundColor Cyan
    $first = Invoke-ContractScript -ScriptFile 'cleanup-1.js' -WaitSeconds 25
    $second = Invoke-ContractScript -ScriptFile 'cleanup-2.js' -WaitSeconds 25
    $checks = @(
        @{ Name = '第 1 个脚本创建了 3 个 OCR 会话 + 1 个 RootAutomator2（且不释放）';
           Ok = [bool]($first -match 'CLEANUP1\|创建后 ocr=3 RootAutomator2=1') },
        @{ Name = '第 2 个脚本里 OCR / RootAutomator2 计数归零（引擎自动释放）';
           Ok = [bool]($second -match 'CLEANUP2\|ocr=0 RootAutomator2=0') },
        @{ Name = '第 2 个脚本里 ColorMapping 单例已被回收';
           Ok = [bool]($second -match 'CLEANUP2\|ColorMapping 单例已回收=true') },
        @{ Name = '第 2 个脚本自己的会话计数正常（创建=1、release 后=0）';
           Ok = [bool](($second -match 'CLEANUP2\|本脚本创建后 ocr=1') -and ($second -match 'CLEANUP2\|release\(\) 之后 ocr=0')) }
    )
    $failedChecks = @($checks | Where-Object { -not $_.Ok })
    foreach ($check in $checks) {
        Write-Host ('  ' + $(if ($check.Ok) { 'OK   ' } else { 'FAIL ' }) + '| ' + $check.Name)
    }
    $results += [pscustomobject]@{
        Script = 'cleanup-1/2.js'; Summary = "$($checks.Count - $failedChecks.Count)/$($checks.Count) 项通过"
        Passed = ($failedChecks.Count -eq 0); FailedLines = @($failedChecks | ForEach-Object { $_.Name })
    }
}

$summaryPath = Join-Path $artifactDir 'contracts-summary.txt'
$report = @()
$report += "真机契约汇总（$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')，设备 $DeviceId）"
foreach ($r in $results) {
    $report += ('{0,-34} {1,-46} {2}' -f $r.Script, $r.Summary, $(if ($r.Passed) { 'PASS' } else { 'FAIL' }))
}
$passedCount = @($results | Where-Object { $_.Passed }).Count
$report += "合计：$passedCount / $($results.Count) 通过"
$report | Set-Content -Path $summaryPath -Encoding UTF8

Write-Host ''
$report | ForEach-Object { Write-Host $_ }
if ($passedCount -ne $results.Count) { exit 1 }
exit 0
