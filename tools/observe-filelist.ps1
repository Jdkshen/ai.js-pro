<#
  文件列表交互的观测量：读取 SortConfig 与开关，用于判断你在手机上点到了哪条路径。

  原理（列表本身不打日志，所以靠 preferences 的副作用反推）：
  - 长按文件项    -> 只弹菜单，**不写任何 preference**（应无变化）
  - 排序方式菜单  -> persistSortSpec() 会改写 file/dir_sort_type
  - 升降序按钮    -> toggleSortOrder() 会翻转 file/dir_ascending
  - 进目录/返回   -> 不写 preference（应无变化）
  - 误判成点击进目录 -> 注意：Compose 版点文件行是 open(item)（编辑器/查看器），
                       点文件夹行是 enterPage()；都不会写 preference

  用法：
    .\tools\observe-filelist.ps1 -Save      # 存当前状态为基线
    .\tools\observe-filelist.ps1            # 显示当前状态并与基线对比
#>

param(
    [string]$DeviceId = 'cccc62c7',
    [switch]$Save,
    [string]$BaselineFile = (Join-Path (Split-Path -Parent $PSScriptRoot) '.artifacts\filelist-baseline.json')
)

$ErrorActionPreference = 'Stop'
$adb = 'C:\Android\platform-tools\adb.exe'
$pkg = 'com.jdkshen.aijspro'
$pf = "/data/data/$pkg/shared_prefs/${pkg}_preferences.xml"

function Read-State {
    $xml = ((& $adb -s $DeviceId shell run-as $pkg cat $pf) 2>&1) -join "`n"
    $s = [ordered]@{}
    foreach ($k in @('file_sort_type', 'dir_sort_type')) {
        $m = [regex]::Match($xml, '<int name="[^"]*ScriptList\.SortConfig\.' + $k + '" value="([^"]*)"')
        $s[$k] = if ($m.Success) { $m.Groups[1].Value } else { $null }
    }
    foreach ($k in @('file_ascending', 'dir_ascending')) {
        $m = [regex]::Match($xml, '<boolean name="[^"]*ScriptList\.SortConfig\.' + $k + '" value="([^"]*)"')
        $s[$k] = if ($m.Success) { $m.Groups[1].Value } else { $null }
    }
    $m = [regex]::Match($xml, '<boolean name="aijspro\.experimental\.miuix_file_list" value="([^"]*)"')
    $s['miuix_file_list'] = if ($m.Success) { $m.Groups[1].Value } else { '(unset)' }

    # 应用是否在前台 + preferences 的修改时间
    $focus = (((& $adb -s $DeviceId shell dumpsys window) 2>&1 | Select-String 'mCurrentFocus' | Select-Object -First 1) -join '')
    $s['foreground'] = if ($focus -match "$pkg") { 'AijsPro' } else { 'other' }
    $ls = ((& $adb -s $DeviceId shell run-as $pkg ls -la $pf) 2>&1) -join ''
    $tm = [regex]::Match($ls, '\d{4}-\d{2}-\d{2} \d{2}:\d{2}')
    $s['prefs_mtime'] = if ($tm.Success) { $tm.Value } else { '?' }
    return $s
}

$now = Read-State

if ($Save) {
    $now | ConvertTo-Json | Set-Content -LiteralPath $BaselineFile -Encoding UTF8
    Write-Host "已保存基线到 $BaselineFile" -ForegroundColor Green
    $now.GetEnumerator() | ForEach-Object { Write-Host ("  {0,-18} = {1}" -f $_.Key, $_.Value) }
    return
}

Write-Host "=== 当前状态 ===" -ForegroundColor Cyan
$now.GetEnumerator() | ForEach-Object { Write-Host ("  {0,-18} = {1}" -f $_.Key, $_.Value) }

if (-not (Test-Path -LiteralPath $BaselineFile)) {
    Write-Host ""
    Write-Host "没有基线文件；先跑一次 -Save" -ForegroundColor Yellow
    return
}

$base = Get-Content -LiteralPath $BaselineFile -Raw -Encoding UTF8 | ConvertFrom-Json
Write-Host ""
Write-Host "=== 与基线的差异 ===" -ForegroundColor Cyan
$changed = @()
foreach ($k in $now.Keys) {
    if ($k -eq 'foreground' -or $k -eq 'prefs_mtime') { continue }
    if ("$($now[$k])" -ne "$($base.$k)") {
        Write-Host ("  {0}: {1} -> {2}" -f $k, $base.$k, $now[$k]) -ForegroundColor Yellow
        $changed += $k
    }
}
if ($changed.Count -eq 0) {
    Write-Host "  无变化（SortConfig 未被改写）" -ForegroundColor DarkGray
} else {
    Write-Host ""
    if ($changed -contains 'file_sort_type' -or $changed -contains 'dir_sort_type') {
        Write-Host "  => 触发过【排序方式菜单】" -ForegroundColor Green
    }
    if ($changed -contains 'file_ascending' -or $changed -contains 'dir_ascending') {
        Write-Host "  => 触发过【升降序按钮】" -ForegroundColor Green
    }
}

# 关键判据：长按不该写任何 preference
if ($now.prefs_mtime -eq $base.prefs_mtime -and $changed.Count -eq 0) {
    Write-Host ""
    Write-Host "preferences 未被触碰 —— 与「长按只弹菜单、不写配置」的预期一致" -ForegroundColor Green
}
