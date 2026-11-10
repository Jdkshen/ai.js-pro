<#
.SYNOPSIS
  构件新鲜度自检：签入的二进制构件（打包模板 / Flutter AOT payload）是否已经跟上源码。

.DESCRIPTION
  今天两次真机事故都是「构件静默过期」：
   1) apps/app/src/main/assets/template.apk 是签入的二进制，引擎/inrt 改动后不重建
      ⇒ 打包版一直跑旧引擎（dex 里 SjbcApi/ScriptHostRouting/MultiProcessPrefs 全 0 命中）。
   2) output/flutter-poc/payload/jniLibs/*/libapp.so 默认复用锁定 payload，flutter-ui/lib 改了
      不加 -RebuildAot ⇒ UI 文案永远看不见。

  判据：
   · 模板：template.apk 的 mtime vs「影响模板的源集合」最新 mtime
           （modules/*/src/main/**、apps/inrt/src/main/**、apps/inrt/build.gradle*）
   · AOT ：每个 libapp.so 的 mtime vs「flutter-ui/lib/**、flutter-ui/pubspec.*」最新 mtime
   · -Deep：mtime 会被 checkout/复制打乱 ⇒ 再做**内容级**判据
           （模板 dex 里找 SjbcApi/ScriptHostRouting/MultiProcessPrefs；
             libapp.so 里找「最新 Dart 文件里的中文字面量」，正是改文案不重编的场景）

.EXAMPLE
  pwsh -File tools/check-artifact-freshness.ps1
  pwsh -File tools/check-artifact-freshness.ps1 -Deep -Json
#>
param(
    # 仓库根（测试用假目录时显式传）
    [string]$Root = '',
    # 覆盖默认构件路径（测试用）
    [string]$TemplatePath = '',
    [string]$PayloadDir = '',
    # 内容级判据（推荐；mtime 被 checkout 打乱时仍能判出来）。
    # 默认：内容级不一致只报 WARN（启发式，可能误报，不挡构建）；-DeepStrict 才把它算 STALE。
    [switch]$Deep,
    [switch]$DeepStrict,
    # 跳过某一侧
    [switch]$SkipTemplate,
    [switch]$SkipAot,
    # 机器可读输出
    [switch]$Json,
    # 即使 STALE 也返回 0（只报告不挡构建）
    [switch]$NoFail
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Root)) {
    $Root = Split-Path (Split-Path $MyInvocation.MyCommand.Path -Parent) -Parent
}
$Root = (Resolve-Path -LiteralPath $Root).Path
if ([string]::IsNullOrWhiteSpace($TemplatePath)) { $TemplatePath = Join-Path $Root 'apps/app/src/main/assets/template.apk' }
if ([string]::IsNullOrWhiteSpace($PayloadDir)) { $PayloadDir = Join-Path $Root 'output/flutter-poc/payload' }

function Get-NewestFile {
    param([string[]]$Paths, [string[]]$Include = @('*'))
    $newest = $null
    foreach ($p in $Paths) {
        if (-not (Test-Path -LiteralPath $p)) { continue }
        $items = @(Get-ChildItem -LiteralPath $p -Recurse -File -Include $Include -ErrorAction SilentlyContinue |
                   Where-Object { $_.FullName -notmatch '[\\/](build|\.gradle|\.git|node_modules|\.dart_tool|test|integration_test)[\\/]' })
        foreach ($item in $items) {
            if ($null -eq $newest -or $item.LastWriteTimeUtc -gt $newest.LastWriteTimeUtc) { $newest = $item }
        }
    }
    return $newest
}

function Get-RelativePath {
    param([string]$Full)
    if ([string]::IsNullOrEmpty($Full)) { return '' }
    if ($Full.StartsWith($Root, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $Full.Substring($Root.Length).TrimStart('\', '/').Replace('\', '/')
    }
    return $Full
}

function Format-Stamp { param($Time) if ($null -eq $Time) { return '(none)' } return ([datetime]$Time).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ') }

# 二进制里搜 UTF-8 标记：把 byte[] 当 Latin-1（1 字节 = 1 字符，无损）后用原生 IndexOf，
# 这样 10MB 的 dex 也是毫秒级（不要用 PowerShell 双重循环，那会慢到不可用）。
$script:Latin1 = [System.Text.Encoding]::GetEncoding(28591)

function ConvertTo-Latin1String {
    param([byte[]]$Bytes)
    if ($null -eq $Bytes -or $Bytes.Length -eq 0) { return '' }
    return $script:Latin1.GetString($Bytes)
}

function Get-MarkerNeedle {
    param([string]$Marker)
    return ConvertTo-Latin1String ([System.Text.Encoding]::UTF8.GetBytes($Marker))
}

# 内容级：在 zip 里按名字读条目并搜 UTF-8 标记（不整包解压）
function Test-ZipEntryContains {
    param([string]$ZipPath, [string]$EntryPattern, [string[]]$Markers)
    $found = @{}
    foreach ($m in $Markers) { $found[$m] = $false }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName -notlike $EntryPattern) { continue }
            $stream = $entry.Open()
            try {
                $memory = New-Object System.IO.MemoryStream
                $stream.CopyTo($memory)
                $haystack = ConvertTo-Latin1String $memory.ToArray()
            } finally { $stream.Dispose() }
            foreach ($m in $Markers) {
                if ($found[$m]) { continue }
                if ($haystack.IndexOf((Get-MarkerNeedle $m), [System.StringComparison]::Ordinal) -ge 0) { $found[$m] = $true }
            }
        }
    } finally { $zip.Dispose() }
    return $found
}

function Test-FileContainsUtf8 {
    param([string]$Path, [string[]]$Markers)
    $found = @{}
    foreach ($m in $Markers) { $found[$m] = $false }
    $haystack = ConvertTo-Latin1String ([System.IO.File]::ReadAllBytes($Path))
    foreach ($m in $Markers) {
        if ($haystack.IndexOf((Get-MarkerNeedle $m), [System.StringComparison]::Ordinal) -ge 0) { $found[$m] = $true }
    }
    return $found
}

# 只在源码里确实存在该标记时才要求构件里有它（避免重命名/删除后误报）
function Test-SourceContains {
    param([string[]]$Paths, [string]$Marker)
    foreach ($p in $Paths) {
        if (-not (Test-Path -LiteralPath $p)) { continue }
        $hit = Get-ChildItem -LiteralPath $p -Recurse -File -Include '*.java', '*.kt' -ErrorAction SilentlyContinue |
               Where-Object { $_.FullName -notmatch '[\\/]build[\\/]' } |
               Select-String -Pattern ([regex]::Escape($Marker)) -SimpleMatch -List -ErrorAction SilentlyContinue
        if ($hit) { return $true }
    }
    return $false
}

$results = New-Object System.Collections.Generic.List[object]
$deepNotes = New-Object System.Collections.Generic.List[string]

if (-not $SkipTemplate) {
    $sourceDirs = @()
    $modulesDir = Join-Path $Root 'modules'
    if (Test-Path -LiteralPath $modulesDir) {
        foreach ($module in (Get-ChildItem -LiteralPath $modulesDir -Directory -ErrorAction SilentlyContinue)) {
            $sourceDirs += (Join-Path $module.FullName 'src/main')
        }
    }
    $sourceDirs += (Join-Path $Root 'apps/inrt/src/main')
    $sourceFiles = @((Join-Path $Root 'apps/inrt/build.gradle'), (Join-Path $Root 'apps/inrt/build.gradle.kts'))
    $newestSource = Get-NewestFile -Paths $sourceDirs
    $newestBuildFile = Get-NewestFile -Paths @($Root) -Include @('build.gradle', 'build.gradle.kts') | Where-Object { $_.FullName -like '*inrt*' }
    if ($null -ne $newestBuildFile -and ($null -eq $newestSource -or $newestBuildFile.LastWriteTimeUtc -gt $newestSource.LastWriteTimeUtc)) {
        $newestSource = $newestBuildFile
    }

    if (-not (Test-Path -LiteralPath $TemplatePath)) {
        $results.Add([pscustomobject]@{ kind = 'template'; state = 'STALE'; reason = 'template.apk 不存在'; newest = ''; newestPath = ''; artifact = $TemplatePath })
    } else {
        $templateTime = (Get-Item -LiteralPath $TemplatePath).LastWriteTimeUtc
        if ($null -eq $newestSource) {
            $results.Add([pscustomobject]@{ kind = 'template'; state = 'FRESH'; reason = '无源码可比（源集合为空）'; newest = ''; newestPath = ''; artifact = $TemplatePath })
        } elseif ($templateTime -lt $newestSource.LastWriteTimeUtc) {
            $results.Add([pscustomobject]@{ kind = 'template'; state = 'STALE'; reason = '模板比源码旧'; newest = (Format-Stamp $newestSource.LastWriteTimeUtc); newestPath = (Get-RelativePath $newestSource.FullName); artifact = $TemplatePath })
        } else {
            $results.Add([pscustomobject]@{ kind = 'template'; state = 'FRESH'; reason = '模板不旧于引擎/inrt 源集合'; newest = (Format-Stamp $newestSource.LastWriteTimeUtc); newestPath = (Get-RelativePath $newestSource.FullName); artifact = $TemplatePath })
        }

        if ($Deep -and (Test-Path -LiteralPath $TemplatePath)) {
            # 模板里的引擎类：这些是「打包版跑旧引擎」最直观的指纹（Lead dev-1456 实测用的三个）
            $candidates = @('SjbcApi', 'ScriptHostRouting', 'MultiProcessPrefs')
            $required = @()
            $sourceSearchRoots = @((Join-Path $Root 'modules'), (Join-Path $Root 'apps/inrt/src'))
            foreach ($marker in $candidates) {
                if (Test-SourceContains -Paths $sourceSearchRoots -Marker $marker) { $required += $marker }
            }
            if ($required.Count -gt 0) {
                $found = Test-ZipEntryContains -ZipPath $TemplatePath -EntryPattern 'classes*.dex' -Markers $required
                $missing = @($required | Where-Object { -not $found[$_] })
                $deepNotes.Add('template dex markers: required=[' + ($required -join ',') + '] missing=[' + ($missing -join ',') + ']')
                if ($missing.Count -gt 0) {
                    $results.Add([pscustomobject]@{ kind = 'template-deep'; state = $(if ($DeepStrict) { 'STALE' } else { 'WARN' }); reason = '模板 dex 里找不到源码已有的类：' + ($missing -join ','); newest = ''; newestPath = ''; artifact = $TemplatePath })
                } else {
                    # 内容级判据通过 ⇒ mtime 的 STALE 是 checkout/复制造成的假阳性（深度优先）
                    foreach ($r in $results) {
                        if ($r.kind -eq 'template' -and $r.state -eq 'STALE') {
                            $r.state = 'FRESH'
                            $r.reason = 'mtime 说旧，但 dex 内容级判据通过（' + ($required -join ',') + ' 齐全）'
                        }
                    }
                }
            } else {
                $deepNotes.Add('template dex markers: 源码里没找到候选类，跳过内容级判据')
            }
        }
    }
}

if (-not $SkipAot) {
    $payloadLibs = @()
    if (Test-Path -LiteralPath $PayloadDir) {
        $payloadLibs = @(Get-ChildItem -LiteralPath $PayloadDir -Recurse -File -Filter 'libapp.so' -ErrorAction SilentlyContinue)
    }
    $flutterLib = Join-Path $Root 'flutter-ui/lib'
    $flutterSources = @($flutterLib)
    $newestDart = Get-NewestFile -Paths $flutterSources -Include @('*.dart')
    # 只认 flutter-ui/lib/** 与两个 pubspec 文件（不要递归整个 flutter-ui，否则会把 test/ 的 .dart 当源码）
    $pubspecCandidates = @((Join-Path $Root 'flutter-ui/pubspec.yaml'), (Join-Path $Root 'flutter-ui/pubspec.lock')) |
        Where-Object { Test-Path -LiteralPath $_ }
    $pubspec = Get-NewestFile -Paths $pubspecCandidates -Include @('pubspec.yaml', 'pubspec.lock')
    if ($null -ne $pubspec -and ($null -eq $newestDart -or $pubspec.LastWriteTimeUtc -gt $newestDart.LastWriteTimeUtc)) { $newestDart = $pubspec }

    if ($payloadLibs.Count -eq 0) {
        $results.Add([pscustomobject]@{ kind = 'aot'; state = 'STALE'; reason = 'payload 里没有 libapp.so（先跑 tools/flutter-poc/build-aot.ps1）'; newest = ''; newestPath = ''; artifact = $PayloadDir })
    } else {
        foreach ($lib in $payloadLibs) {
            if ($null -eq $newestDart) {
                $results.Add([pscustomobject]@{ kind = 'aot'; state = 'FRESH'; reason = '无 Dart 源码可比'; newest = ''; newestPath = ''; artifact = $lib.FullName })
                continue
            }
            if ($lib.LastWriteTimeUtc -lt $newestDart.LastWriteTimeUtc) {
                $results.Add([pscustomobject]@{ kind = 'aot'; state = 'STALE'; reason = 'payload 比 flutter-ui 源码旧（多半是没加 -RebuildAot）'; newest = (Format-Stamp $newestDart.LastWriteTimeUtc); newestPath = (Get-RelativePath $newestDart.FullName); artifact = (Get-RelativePath $lib.FullName) })
            } else {
                $results.Add([pscustomobject]@{ kind = 'aot'; state = 'FRESH'; reason = 'payload 不旧于 flutter-ui 源码'; newest = (Format-Stamp $newestDart.LastWriteTimeUtc); newestPath = (Get-RelativePath $newestDart.FullName); artifact = (Get-RelativePath $lib.FullName) })
            }
        }

        if ($Deep -and $null -ne $newestDart) {
            # 内容级：从「最新的 Dart 文件」抽中文字面量（UI 文案），看 payload 里有没有 —— 正是「改文案不重编」的场景
            $text = Get-Content -LiteralPath $newestDart.FullName -Raw -ErrorAction SilentlyContinue
            if (-not [string]::IsNullOrEmpty($text)) {
                $literals = [regex]::Matches($text, "'([^'\r\n]{4,40})'") | ForEach-Object { $_.Groups[1].Value }
                $cjk = @($literals | Where-Object { $_ -match '[\u4e00-\u9fff]' } | Select-Object -Unique -First 3)
                if ($cjk.Count -gt 0) {
                    foreach ($lib in $payloadLibs) {
                        $found = Test-FileContainsUtf8 -Path $lib.FullName -Markers $cjk
                        $missing = @($cjk | Where-Object { -not $found[$_] })
                        $deepNotes.Add('aot markers (' + (Get-RelativePath $lib.FullName) + '): required=[' + ($cjk -join ' | ') + '] missing=[' + ($missing -join ' | ') + ']')
                        if ($missing.Count -eq $cjk.Count) {
                            # 注意：**AOT 侧永远只报 WARN、不做门禁**。实测证据（2026-11-10）：连 K40 截图里
                            # 已确认存在的文案（加密等级/脚本和加密/运行库与资源/SJBC 资源目录）在 libapp.so 里
                            # 用明文 UTF-8 都搜不到 ⇒ Flutter AOT 的字面量不是明文 UTF-8 存储，明文搜索不可靠。
                            # 所以 AOT 的新鲜度以 **mtime** 为准（payload 09:35 > project_page.dart 09:32 实测 FRESH）。
                            $results.Add([pscustomobject]@{ kind = 'aot-deep'; state = 'WARN'; reason = 'payload 里明文搜不到最新 Dart 文案（快照编码，搜索不可靠 → 仅提示）'; newest = ''; newestPath = (Get-RelativePath $newestDart.FullName); artifact = (Get-RelativePath $lib.FullName) })
                        } else {
                            # 内容级通过 ⇒ mtime 说旧也判 FRESH（文案真的在 payload 里）
                            foreach ($r in $results) {
                                if ($r.kind -eq 'aot' -and $r.state -eq 'STALE' -and $r.artifact -eq (Get-RelativePath $lib.FullName)) {
                                    $r.state = 'FRESH'
                                    $r.reason = 'mtime 说旧，但 payload 里能找到最新 Dart 文案（内容级通过）'
                                }
                            }
                        }
                    }
                } else {
                    $deepNotes.Add('aot markers: 最新 Dart 文件里没有中文字面量，跳过内容级判据')
                }
            }
        }
    }
}

$stale = @($results | Where-Object { $_.state -eq 'STALE' })
if ($Json) {
    $payload = [pscustomobject]@{ stale = ($stale.Count -gt 0); checks = $results; deepNotes = $deepNotes }
    $payload | ConvertTo-Json -Depth 5
} else {
    foreach ($note in $deepNotes) { Write-Host ("[FRESH-CHECK] " + $note) }
    foreach ($r in $results) {
        if ($r.state -eq 'WARN') {
            Write-Host ("WARN(kind=" + $r.kind + ", " + $r.reason + ") → 内容级判据不一致（不挡构建；要当门禁用 -DeepStrict）")
        } elseif ($r.state -eq 'STALE') {
            $extra = if ($r.kind -eq 'template' -or $r.kind -eq 'template-deep') { ' → 先跑 .\tools\build-slim-template.ps1' } else { ' → 加 -RebuildAot（或先跑 tools/flutter-poc/build-aot.ps1）' }
            $where = if ($r.newestPath) { ' newest=' + $r.newestPath + '@' + $r.newest } else { '' }
            Write-Host ("STALE(kind=" + $r.kind + ", " + $r.reason + ")" + $where + $extra)
        } else {
            Write-Host ("FRESH(kind=" + $r.kind + ", " + $r.reason + ") newest=" + $r.newestPath + "@" + $r.newest)
        }
    }
    if ($stale.Count -gt 0) { Write-Host ("[FRESH-CHECK] 结论：STALE（" + $stale.Count + " 项）—— 别用过期构件出包") }
    else { Write-Host "[FRESH-CHECK] 结论：FRESH（模板与 AOT 都跟得上源码）" }
}

if ($stale.Count -gt 0 -and -not $NoFail) { exit 1 }
exit 0
