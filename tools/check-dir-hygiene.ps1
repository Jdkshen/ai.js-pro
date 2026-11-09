<#
.SYNOPSIS
    目录/产出卫生自检（配套 docs/guides/DIRECTORY_CONVENTIONS.md）

.DESCRIPTION
    检查并（可选）清理违反《目录与产出规范》的产物。默认只报告，不改任何东西。

    检查项：
      1. output/ 根目录的散落文件（应为 0 —— 临时文件该进 output/<任务名>-<日期>/）
      2. .artifacts/ 的任务目录：0 文档引用 且 >7 天未动
      3. .artifacts/**/*.apk：14 天前（updates/ 永不删；flutter-release-candidate 只留最新 4）
      4. 仓库根目录的散落日志/截图
      5. Downloads 里的构建产物（AI 不该往人的下载目录写东西）
      6. 各盘余量（C: < 15 GB 需先清）
      7. 保护项是否完好（.artifacts/updates、src/test、官方基线 APK）
      8. 构建是否进行中（进行中禁止清理 build/ 与 Gradle 增量状态）

.PARAMETER Fix
    按规范执行清理。会先打印将删清单；检测到构建进行中则拒绝执行。

.PARAMETER RepoRoot
    仓库根目录，默认取脚本所在目录的上一级。

.EXAMPLE
    pwsh -File tools\check-dir-hygiene.ps1
    pwsh -File tools\check-dir-hygiene.ps1 -Fix
#>
[CmdletBinding()]
param(
    [switch]$Fix,
    [string]$RepoRoot
)

$ErrorActionPreference = 'Continue'
if (-not $RepoRoot) { $RepoRoot = Split-Path -Parent $PSScriptRoot }
Set-Location -LiteralPath $RepoRoot

$script:Violations = 0
$script:FreedBytes = 0

function Write-Section([string]$Title) { Write-Host ""; Write-Host "== $Title ==" -ForegroundColor Cyan }
function Write-OK([string]$Msg)        { Write-Host "  [ok]   $Msg" -ForegroundColor Green }
function Write-Warn2([string]$Msg)     { Write-Host "  [warn] $Msg" -ForegroundColor Yellow; $script:Violations++ }
function Write-Bad([string]$Msg)       { Write-Host "  [BAD]  $Msg" -ForegroundColor Red;    $script:Violations++ }

function Get-DirSize([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return 0 }
    try {
        $s = (Get-ChildItem -LiteralPath $Path -Recurse -File -Force -ErrorAction SilentlyContinue |
              Measure-Object -Property Length -Sum).Sum
        if ($null -eq $s) { return 0 }
        return $s
    } catch { return 0 }
}
function Format-Size([long]$Bytes) {
    if ($Bytes -ge 1GB) { return ("{0:N2} GB" -f ($Bytes / 1GB)) }
    if ($Bytes -ge 1MB) { return ("{0:N1} MB" -f ($Bytes / 1MB)) }
    return ("{0:N0} KB" -f ($Bytes / 1KB))
}
function Get-FreeGB([string]$Drive) {
    $d = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='$Drive'" -ErrorAction SilentlyContinue
    if ($d) { return [math]::Round($d.FreeSpace / 1GB, 2) }
    return -1
}

# ── 构建进行中判定：看 build/ 的写入时间（不是日志！R8 期间日志会静默数分钟）──
function Test-BuildRunning {
    $dirs = @('apps\app\build', 'apps\inrt\build', 'modules\engine\build') |
            Where-Object { Test-Path -LiteralPath $_ }
    if (-not $dirs) { return @{ Running = $false; IdleMin = 9999 } }
    $newest = Get-ChildItem $dirs -Recurse -File -Force -ErrorAction SilentlyContinue |
              Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $newest) { return @{ Running = $false; IdleMin = 9999 } }
    $idle = ((Get-Date) - $newest.LastWriteTime).TotalMinutes
    return @{ Running = ($idle -lt 4); IdleMin = [math]::Round($idle, 1) }
}

# 文档引用集合（文件名/目录名粒度）
function Get-DocText {
    $parts = @()
    $parts += (Get-ChildItem 'docs' -Recurse -File -Filter '*.md' -ErrorAction SilentlyContinue |
               ForEach-Object { Get-Content $_.FullName -Raw -Encoding UTF8 })
    if (Test-Path 'AGENTS.md') { $parts += (Get-Content 'AGENTS.md' -Raw -Encoding UTF8) }
    return ($parts -join "`n")
}
function Test-Referenced([string]$Text, [string]$Name) {
    return ([regex]::Matches($Text, [regex]::Escape($Name))).Count -gt 0
}

Write-Host "目录卫生自检  (repo: $RepoRoot)" -ForegroundColor White
Write-Host "模式: $(if ($Fix) { 'FIX（会实际删除）' } else { 'REPORT（只报告）' })"

# ═══ 0. 构建状态（决定能不能动 build/）═══
Write-Section '0. 构建状态'
$build = Test-BuildRunning
if ($build.Running) {
    Write-Warn2 "构建进行中（build/ 仅静默 $($build.IdleMin) 分钟）→ 本次不会清理 build/ 与 .gradle 增量状态"
} else {
    Write-OK "无构建在进行（build/ 静默 $($build.IdleMin) 分钟）"
}

# ═══ 1. output/ 根目录散落文件 ═══
Write-Section '1. output/ 根目录散落文件（规范要求：临时文件进 output/<任务名>-<日期>/）'
$docText = Get-DocText
if (Test-Path 'output') {
    $loose = Get-ChildItem 'output' -File -Force -ErrorAction SilentlyContinue
    $unref = $loose | Where-Object { -not (Test-Referenced $docText $_.Name) }
    if ($loose.Count -eq 0) {
        Write-OK 'output/ 根目录无散落文件'
    } elseif ($unref.Count -eq 0) {
        Write-OK "output/ 根目录 $($loose.Count) 个文件全部被文档引用"
    } else {
        $bytes = ($unref | Measure-Object Length -Sum).Sum
        Write-Bad "output/ 根目录有 $($unref.Count) 个未被文档引用的散落文件（$(Format-Size $bytes)）"
        $unref | Sort-Object Length -Descending | Select-Object -First 8 |
            ForEach-Object { Write-Host ("         {0,10}  {1}" -f (Format-Size $_.Length), $_.Name) }
        if ($unref.Count -gt 8) { Write-Host "         ... 其余 $($unref.Count - 8) 个" }
        # -Fix 只自动删「明确是临时产物」的扩展名，且 >1 天未动。
        # .md（可能是在写的稿）/.apk/.png（可能是证据）一律只报告，交人决定。
        $scratchExt = @('.log', '.txt', '.b64', '.patch', '.diff', '.json', '.tmp', '.bak')
        $cut1d = (Get-Date).AddDays(-1)
        $autoDel = $unref | Where-Object { $scratchExt -contains $_.Extension.ToLower() -and $_.LastWriteTime -lt $cut1d }
        $manual  = $unref | Where-Object { $autoDel.FullName -notcontains $_.FullName }
        if ($manual.Count -gt 0) {
            Write-Host "     $($manual.Count) 个需人工确认（.md/.apk/.png 等，脚本不自动删）：" -ForegroundColor DarkGray
            $manual | Sort-Object Length -Descending | Select-Object -First 5 |
                ForEach-Object { Write-Host ("         {0,10}  {1}" -f (Format-Size $_.Length), $_.Name) }
        }
        if ($Fix) {
            if ($autoDel.Count -eq 0) {
                Write-Host "     -> 无可自动删除项（其余需人工确认）" -ForegroundColor DarkGray
            } else {
                foreach ($f in $autoDel) { Remove-Item -LiteralPath $f.FullName -Force -ErrorAction SilentlyContinue }
                $gone = $autoDel | Where-Object { -not (Test-Path -LiteralPath $_.FullName) }
                $script:FreedBytes += ($gone | Measure-Object Length -Sum).Sum
                Write-Host "     -> 已删除 $(($gone | Measure-Object).Count) 个临时产物" -ForegroundColor Magenta
            }
        }
    }
} else { Write-OK 'output/ 不存在（无需检查）' }

# ═══ 2. .artifacts/ 任务目录 ═══
Write-Section '2. .artifacts/ 任务目录（0 文档引用 且 >7 天未动 = 可删）'
$staleDirs = @()
if (Test-Path '.artifacts') {
    $cut7 = (Get-Date).AddDays(-7)
    $staleDirs = Get-ChildItem '.artifacts' -Directory -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -ne 'updates' -and $_.LastWriteTime -lt $cut7 } |
        Where-Object { -not (Test-Referenced $docText $_.Name) }
    if ($staleDirs.Count -eq 0) {
        Write-OK '无符合条件的过期任务目录'
    } else {
        $rows = $staleDirs | ForEach-Object { [PSCustomObject]@{ N = $_.Name; B = (Get-DirSize $_.FullName) } } |
                Where-Object { $_.B -gt 1MB } | Sort-Object B -Descending
        Write-Warn2 "有 $($rows.Count) 个过期任务目录（>1MB），合计 $(Format-Size (($rows | Measure-Object B -Sum).Sum))"
        $rows | Select-Object -First 8 | ForEach-Object { Write-Host ("         {0,10}  {1}" -f (Format-Size $_.B), $_.N) }
        if ($Fix) {
            foreach ($d in $staleDirs) {
                $before = Get-DirSize $d.FullName
                Remove-Item -LiteralPath $d.FullName -Recurse -Force -ErrorAction SilentlyContinue
                if (-not (Test-Path -LiteralPath $d.FullName)) { $script:FreedBytes += $before }
            }
            Write-Host "     -> 已按规则清理" -ForegroundColor Magenta
        }
    }
} else { Write-OK '.artifacts/ 不存在' }

# ═══ 3. .artifacts APK 年龄 ═══
Write-Section '3. .artifacts/**/*.apk 年龄（14 天规则；updates/ 永不删）'
if (Test-Path '.artifacts') {
    $cut14 = (Get-Date).AddDays(-14)
    $oldApk = Get-ChildItem '.artifacts' -Recurse -File -Filter '*.apk' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '\\updates\\' -and $_.LastWriteTime -lt $cut14 }
    if ($oldApk.Count -eq 0) {
        Write-OK '无 14 天前的 APK（updates/ 已排除）'
    } else {
        Write-Warn2 "$($oldApk.Count) 个 14 天前的 APK，合计 $(Format-Size (($oldApk | Measure-Object Length -Sum).Sum))"
        if ($Fix) {
            foreach ($a in $oldApk) {
                $len = $a.Length
                Remove-Item -LiteralPath $a.FullName -Force -ErrorAction SilentlyContinue
                if (-not (Test-Path -LiteralPath $a.FullName)) { $script:FreedBytes += $len }
            }
            Write-Host "     -> 已删除" -ForegroundColor Magenta
        }
    }
    # flutter-release-candidate 只留最新 4
    $rc = '.artifacts\flutter-release-candidate'
    if (Test-Path $rc) {
        $apks = Get-ChildItem $rc -File -Filter '*.apk' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending
        if ($apks.Count -gt 4) {
            $extra = $apks | Select-Object -Skip 4
            Write-Warn2 "flutter-release-candidate 有 $($apks.Count) 个候选 APK（规范：只留最新 4 个），多余 $($extra.Count) 个 / $(Format-Size (($extra | Measure-Object Length -Sum).Sum))"
            if ($Fix) {
                foreach ($a in $extra) {
                    $len = $a.Length
                    Remove-Item -LiteralPath $a.FullName -Force -ErrorAction SilentlyContinue
                    if (-not (Test-Path -LiteralPath $a.FullName)) { $script:FreedBytes += $len }
                }
                Write-Host "     -> 已保留最新 4 个" -ForegroundColor Magenta
            }
        } else { Write-OK "flutter-release-candidate 候选数 $($apks.Count) <= 4" }
    }
} else { Write-OK '.artifacts/ 不存在' }

# ═══ 4. 仓库根散落文件 ═══
Write-Section '4. 仓库根目录散落文件（日志/截图不该留在根）'
$strayRoot = Get-ChildItem $RepoRoot -File -Force -ErrorAction SilentlyContinue |
    Where-Object { $_.Extension -match '^\.(log|png|jpg|jpeg|mp4|b64|patch|diff|tmp|bak)$' -or $_.Name -match '^(hs_err_pid|replay_pid).*' }
if ($strayRoot.Count -eq 0) {
    Write-OK '仓库根目录干净'
} else {
    Write-Warn2 "仓库根有 $($strayRoot.Count) 个疑似散落文件，合计 $(Format-Size (($strayRoot | Measure-Object Length -Sum).Sum))"
    $strayRoot | Select-Object -First 8 | ForEach-Object { Write-Host "         $($_.Name)" }
    Write-Host "     （建议人工确认后移到 output/local-cleanup-<日期>/，脚本不自动删根目录文件）" -ForegroundColor DarkGray
}

# ═══ 5. Downloads 里的构建产物 ═══
Write-Section '5. Downloads 里的构建产物（AI 不该往人的下载目录写东西）'
$dl = Join-Path $env:USERPROFILE 'Downloads'
if (Test-Path $dl) {
    $devApk = Get-ChildItem $dl -File -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -ieq '.apk' -and $_.Name -match '^(AIjsPro-dev-|AI-js-Pro-Flutter.*dev-)' }
    if ($devApk.Count -eq 0) {
        Write-OK 'Downloads 无本项目构建产物（.apk；release-note 的 .md/.png 不算）'
    } else {
        Write-Bad "Downloads 有 $($devApk.Count) 个本项目构建产物，合计 $(Format-Size (($devApk | Measure-Object Length -Sum).Sum))"
        Write-Host "     （构建产物应放 .artifacts/<任务名>-<日期>/；官方基线 APK 除外）" -ForegroundColor DarkGray
    }
} else { Write-OK 'Downloads 不存在' }

# ═══ 6. 磁盘余量 ═══
Write-Section '6. 磁盘余量'
foreach ($d in @('C:', 'D:', 'E:')) {
    $free = Get-FreeGB $d
    if ($free -lt 0) { continue }
    $wmi = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='$d'"
    $total = $wmi.Size / 1GB
    $pct = [math]::Round((($total - $free) / $total) * 100, 0)
    $line = "  {0}  {1,7:N2} GB free / {2,7:N2} GB  ({3,3}% used)" -f $d, $free, $total, $pct
    if ($d -eq 'C:' -and $free -lt 15) { Write-Bad ($line + '  <- C: < 15 GB，先清再开工') }
    elseif ($pct -ge 92)                 { Write-Warn2 ($line + '  <- >= 92%') }
    else                                 { Write-Host $line -ForegroundColor Green }
}

# ═══ 7. 保护项完好性 ═══
Write-Section '7. 保护项完好性（这些被删了就是事故）'
$protect = @(
    @{ P = '.artifacts\updates\update.json'; D = 'LAN 更新源清单' },
    @{ P = 'apps\app\src\test';              D = 'App 测试源码' },
    @{ P = 'modules\engine\src\test';        D = '引擎测试源码' },
    @{ P = 'output\flutter-poc';             D = 'Flutter AOT 构建输入' }
)
foreach ($p in $protect) {
    if (Test-Path -LiteralPath $p.P) { Write-OK "$($p.D): $($p.P)" }
    else { Write-Bad "$($p.D) 缺失: $($p.P)" }
}
$testCount = (Get-ChildItem 'apps', 'modules', 'plugins' -Recurse -File -Force -ErrorAction SilentlyContinue |
              Where-Object { $_.FullName -match '\\src\\test\\' } | Measure-Object).Count
if ($testCount -gt 0) { Write-OK "src/test/** 共 $testCount 个文件" } else { Write-Bad 'src/test/** 为空 —— 测试源码被删了？' }

# ═══ 8. 发布源指纹 ═══
Write-Section '8. 发布源指纹（清理前后必须一致）'
$uj = '.artifacts\updates\update.json'
if (Test-Path $uj) {
    try {
        $u = Get-Content $uj -Raw -Encoding UTF8 | ConvertFrom-Json
        $n = (Get-ChildItem '.artifacts\updates' -File -ErrorAction SilentlyContinue | Measure-Object).Count
        Write-OK "versionCode=$($u.versionCode) / $($u.versionName)   updates/ 共 $n 个文件"
    } catch { Write-Bad "update.json 解析失败: $($_.Exception.Message)" }
} else { Write-Bad 'update.json 不存在！' }

# ═══ 汇总 ═══
Write-Host ""
Write-Host ('=' * 64) -ForegroundColor DarkGray
if ($Fix) { Write-Host ("本次释放: {0}" -f (Format-Size $script:FreedBytes)) -ForegroundColor Magenta }
if ($script:Violations -eq 0) {
    Write-Host "结论: 干净（0 项违规）" -ForegroundColor Green
    exit 0
} else {
    Write-Host "结论: $($script:Violations) 项需处理" -ForegroundColor Yellow
    if (-not $Fix) { Write-Host "（加 -Fix 按规范清理；清理前请确认无构建在进行）" -ForegroundColor DarkGray }
    exit 1
}
