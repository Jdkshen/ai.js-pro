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
    # -DeepStrict：历史开关（task-91 起注册表/推导标记本身就按 STALE 处理，此项保留仅为兼容）
    [switch]$DeepStrict,
    # 标记注册表路径（默认 <Root>/tools/artifact-markers.json，退回脚本目录同名文件）
    [string]$MarkersPath = '',
    # 跳过「从最近改动源文件推导标记」（应急开关）
    [switch]$NoDerived,
    # 覆盖推导标记个数上限（0 = 用注册表 derived.maxMarkers，默认 5）
    [int]$MaxDerivedMarkers = 0,
    # 插件兜底模板（plugins/apkbuilder-plugin/src/main/assets/template.apk）—— task-131：它同样是
    # 「签入的二进制模板」，引擎/inrt 改动后不重建就会静默留旧内容（真机实证：旧 OCR 文案）。
    [string]$PluginTemplatePath = '',
    # 跳过某一侧
    [switch]$SkipTemplate,
    [switch]$SkipPluginTemplate,
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
if ([string]::IsNullOrWhiteSpace($PluginTemplatePath)) { $PluginTemplatePath = Join-Path $Root 'plugins/apkbuilder-plugin/src/main/assets/template.apk' }

function Get-NewestFile {
    param([string[]]$Paths, [string[]]$Include = @('*'))
    $newest = $null
    foreach ($p in $Paths) {
        if (-not (Test-Path -LiteralPath $p)) { continue }
        # Lead 2026-11-12：mtime 判据要**排除不进模板的文件类型**，否则「改了构建脚本/文档」会假 STALE。
        # 实测两次：给 modules/engine/src/main/cpp/*.ps1 补 BOM ⇒ 模板被判 STALE，而模板内容其实没问题
        # （:inrt 产物没变 ⇒ Gradle UP-TO-DATE ⇒ 模板 mtime 不刷新 ⇒ mtime 判据永远追不上）。
        # 只排 .ps1 / .md（都不进模板）；.json/.xml/.gradle 等**保留**（它们可能真进包）。
        $notPackaged = @('.ps1', '.md')
        $skipDir = '[\\/](build|\.gradle|\.git|node_modules|\.dart_tool|test|integration_test)[\\/]'
        $items = @(Get-ChildItem -LiteralPath $p -Recurse -File -Include $Include -ErrorAction SilentlyContinue |
                   Where-Object { ($_.FullName -notmatch $skipDir) -and ($notPackaged -notcontains $_.Extension.ToLowerInvariant()) })
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

# 一次性把「哪些标记出现在源码里」算出来（单趟 Select-String + 正则或）。
# 逐个标记全树扫会慢到不可用（15 个标记 × 上万文件），这里必须单趟。
function Get-SourceMarkersPresent {
    param([string[]]$Paths, [string[]]$Markers)
    $result = @{}
    foreach ($m in $Markers) { $result[$m] = $false }
    if ($null -eq $Markers -or $Markers.Count -eq 0) { return $result }
    $pattern = ($Markers | ForEach-Object { [regex]::Escape($_) }) -join '|'
    foreach ($p in $Paths) {
        if (-not (Test-Path -LiteralPath $p)) { continue }
        # 只认「会进 APK 的源」：排除 build/、以及 src/test/** 与 src/androidTest/**（task-98）
        $hits = Get-ChildItem -LiteralPath $p -Recurse -File -Include '*.java', '*.kt' -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -notmatch '[\\/](build|test|androidTest)[\\/]' } |
            Select-String -Pattern $pattern -ErrorAction SilentlyContinue
        foreach ($hit in $hits) {
            foreach ($m in $Markers) {
                if (-not $result[$m] -and $hit.Line -like ('*' + $m + '*')) { $result[$m] = $true }
            }
        }
    }
    return $result
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

if (-not $SkipPluginTemplate) {
    # 与 template 同源判据：引擎 modules/*/src/main + apps/inrt/src/main + inrt build.gradle
    $pluginSourceDirs = @()
    $pluginModulesDir = Join-Path $Root 'modules'
    if (Test-Path -LiteralPath $pluginModulesDir) {
        foreach ($module in (Get-ChildItem -LiteralPath $pluginModulesDir -Directory -ErrorAction SilentlyContinue)) {
            $pluginSourceDirs += (Join-Path $module.FullName 'src/main')
        }
    }
    $pluginSourceDirs += (Join-Path $Root 'apps/inrt/src/main')
    $pluginNewest = Get-NewestFile -Paths $pluginSourceDirs
    $pluginBuildFile = Get-NewestFile -Paths @($Root) -Include @('build.gradle', 'build.gradle.kts') | Where-Object { $_.FullName -like '*inrt*' }
    if ($null -ne $pluginBuildFile -and ($null -eq $pluginNewest -or $pluginBuildFile.LastWriteTimeUtc -gt $pluginNewest.LastWriteTimeUtc)) {
        $pluginNewest = $pluginBuildFile
    }
    if (-not (Test-Path -LiteralPath $PluginTemplatePath)) {
        $results.Add([pscustomobject]@{ kind = 'plugin-template'; state = 'STALE'; reason = '插件兜底模板不存在（plugins/apkbuilder-plugin/src/main/assets/template.apk）'; newest = ''; newestPath = ''; artifact = $PluginTemplatePath })
    } else {
        $pluginTemplateTime = (Get-Item -LiteralPath $PluginTemplatePath).LastWriteTimeUtc
        if ($null -eq $pluginNewest) {
            $results.Add([pscustomobject]@{ kind = 'plugin-template'; state = 'FRESH'; reason = '无源码可比（源集合为空）'; newest = ''; newestPath = ''; artifact = $PluginTemplatePath })
        } elseif ($pluginTemplateTime -lt $pluginNewest.LastWriteTimeUtc) {
            $results.Add([pscustomobject]@{ kind = 'plugin-template'; state = 'STALE'; reason = '插件兜底模板比引擎/inrt 源集合旧（兜底路径会带旧文案/旧引擎，需重建或改为引用 apps/app 那份）'; newest = (Format-Stamp $pluginNewest.LastWriteTimeUtc); newestPath = (Get-RelativePath $pluginNewest.FullName); artifact = $PluginTemplatePath })
        } else {
            $results.Add([pscustomobject]@{ kind = 'plugin-template'; state = 'FRESH'; reason = '插件兜底模板不旧于引擎/inrt 源集合'; newest = (Format-Stamp $pluginNewest.LastWriteTimeUtc); newestPath = (Get-RelativePath $pluginNewest.FullName); artifact = $PluginTemplatePath })
        }
    }
}

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
            $sourceSearchRoots = @((Join-Path $Root 'modules'), (Join-Path $Root 'apps/inrt/src'))
            $registryMarkers = @()
            $registrySince = @{}
            $derivedCfg = $null

            # ① 标记注册表（task-91）：可维护 + 只要求「源码里确实存在」的标记 + 防漂移 WARN
            $registryFile = if (-not [string]::IsNullOrWhiteSpace($MarkersPath)) { $MarkersPath }
                elseif (Test-Path -LiteralPath (Join-Path $Root 'tools/artifact-markers.json')) { Join-Path $Root 'tools/artifact-markers.json' }
                else { Join-Path $PSScriptRoot 'artifact-markers.json' }
            if (Test-Path -LiteralPath $registryFile) {
                try {
                    # PS 5.1 默认按 ANSI 读无 BOM 文件 ⇒ 必须显式 UTF8（否则中文/JSON 结构都会读坏）
                    $registry = Get-Content -LiteralPath $registryFile -Raw -Encoding UTF8 | ConvertFrom-Json
                    $groupNotes = @()
                    $allNames = @($registry.groups | ForEach-Object { $_.markers | ForEach-Object { [string]$_.name } } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
                    $presentMap = Get-SourceMarkersPresent -Paths $sourceSearchRoots -Markers $allNames
                    foreach ($group in $registry.groups) {
                        $present = @()
                        $absent = @()
                        foreach ($marker in $group.markers) {
                            $name = [string]$marker.name
                            if ([string]::IsNullOrWhiteSpace($name)) { continue }
                            if ($presentMap[$name]) {
                                $present += $name
                                $registryMarkers += $name
                                $registrySince[$name] = [string]$marker.since
                            } else {
                                $absent += $name
                            }
                        }
                        if ($present.Count -gt 0 -and $absent.Count -gt 0) {
                            # Lead 2026-11-12：由 WARN 改 **STALE**（W3 漂移清单第 10 条）。
                            # 理由：注册表是门禁自己的「要求清单」—— 某个标记**已不在源码**，说明它被改名/删了，
                            # 而门禁仍在拿一个**不存在的名字**去要求模板 ⇒ 这条要求**永远满足不了**、也永远抓不到真回归
                            #（dev-1459「假 FRESH」的同类）。WARN 不挡构建 ⇒ 没人会修 ⇒ 门禁会慢慢被掏空 ✗。
                            # 实测当前注册表**无此漂移**（干净）⇒ 加严不会误伤现状 ✓。
                            # 修法：同步 tools/artifact-markers.json（改名的同步改名、确实删掉的删条目）。
                            $results.Add([pscustomobject]@{ kind = 'markers-registry'; state = 'STALE'; reason = '组 ' + $group.id + ' 里这些标记已不在源码：' + ($absent -join ',') + '（请同步 tools/artifact-markers.json：改名就改名、删了就删条目）'; newest = ''; newestPath = ''; artifact = $registryFile })
                        }
                        $groupNotes += ($group.id + '=>' + ($present -join '|'))
                    }
                    $derivedCfg = $registry.derived
                    $deepNotes.Add('registry=' + (Get-RelativePath $registryFile) + ' groups=[' + ($groupNotes -join '; ') + ']')
                } catch {
                    $results.Add([pscustomobject]@{ kind = 'markers-registry'; state = 'WARN'; reason = '注册表读取/解析失败：' + $_.Exception.Message; newest = ''; newestPath = ''; artifact = $registryFile })
                }
            } else {
                $deepNotes.Add('registry 不存在（' + (Get-RelativePath $registryFile) + '）：只做推导标记')
            }

            # ② 推导标记（更硬的证据）：模板 mtime 之后改动过的引擎/inrt 源文件里抽公共成员名
            $derived = @()
            $derivedSource = '(skipped)'
            if (-not $NoDerived) {
                $maxDerived = if ($MaxDerivedMarkers -gt 0) { $MaxDerivedMarkers } elseif ($null -ne $derivedCfg) { [int]$derivedCfg.maxMarkers } else { 5 }
                $templateTime = (Get-Item -LiteralPath $TemplatePath).LastWriteTimeUtc
                $changed = @()
                # 2a) 优先用 git（只读查询，不改仓库）；失败/不可用则退回 mtime 扫描
                try {
                    Push-Location $Root
                    $since = $templateTime.ToString('yyyy-MM-ddTHH:mm:ssZ')
                    $gitOut = & git log --name-only --pretty=format: --since=$since -- modules apps/inrt 2>$null
                    if ($LASTEXITCODE -eq 0 -and $gitOut) {
                        $gitFiles = @($gitOut | Where-Object { $_ -and $_.Trim() -ne '' } | ForEach-Object { $_.Trim().Replace('/', '\') } |
                            Where-Object { $_ -like 'modules\*\src\main\*' -or $_ -like 'apps\inrt\src\main\*' } |
                            ForEach-Object { Join-Path $Root $_ } | Where-Object { Test-Path -LiteralPath $_ })
                        if ($gitFiles.Count -gt 0) { $changed = $gitFiles; $derivedSource = 'git(--since=' + $since + ')' }
                    }
                } catch {
                    $derivedSource = 'mtime(git 不可用)'
                } finally {
                    Pop-Location
                }
                if ($changed.Count -eq 0) {
                    # 只看「会进模板的源」：modules/*/src/main/** 与 apps/inrt/src/main/**。
                    # 绝不能把 src/test/** 与 src/androidTest/** 算进来（task-98 修：测试方法名永远
                    # 不可能进模板 ⇒ 否则只要有人写单测就必然假 STALE）。
                    $mainRoots = @()
                    $modulesDir = Join-Path $Root 'modules'
                    if (Test-Path -LiteralPath $modulesDir) {
                        foreach ($module in (Get-ChildItem -LiteralPath $modulesDir -Directory -ErrorAction SilentlyContinue)) {
                            $mainRoots += (Join-Path $module.FullName 'src/main')
                        }
                    }
                    $mainRoots += (Join-Path $Root 'apps/inrt/src/main')
                    $changed = @($mainRoots | Where-Object { Test-Path -LiteralPath $_ } | ForEach-Object {
                            Get-ChildItem -LiteralPath $_ -Recurse -File -Include '*.java', '*.kt' -ErrorAction SilentlyContinue
                        } | Where-Object {
                            $_.FullName -notmatch '[\\/](build|test|androidTest)[\\/]' -and $_.LastWriteTimeUtc -gt $templateTime
                        })
                    if ($derivedSource -eq '(skipped)') { $derivedSource = 'mtime(模板 mtime 之后改动，只看 src/main)' }
                }
                # 归一成 FileInfo（git 分支给的是字符串路径）
                $changed = @($changed | ForEach-Object {
                    if ($_ -is [System.IO.FileInfo]) { $_ } elseif (Test-Path -LiteralPath $_) { Get-Item -LiteralPath $_ }
                } | Where-Object { $_ -ne $null })
                $stop = @('toString', 'equals', 'hashCode', 'getClass', 'main', 'invoke', 'create', 'build', 'run', 'start', 'stop', 'onCreate', 'onDestroy', 'apply', 'values', 'size', 'length', 'close', 'init', 'write', 'read', 'call', 'handle')
                foreach ($file in @($changed | Sort-Object LastWriteTimeUtc -Descending)) {
                    if ($derived.Count -ge $maxDerived) { break }
                    $text = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
                    if ([string]::IsNullOrWhiteSpace($text)) { continue }
                    foreach ($match in [regex]::Matches($text, '(?:public|protected)\s+(?:static\s+|final\s+|synchronized\s+|abstract\s+|open\s+|override\s+|suspend\s+)*[\w<>\[\],\.\?\s]{0,80}?\b(\w+)\s*\(')) {
                        $name = $match.Groups[1].Value
                        if ($name.Length -lt 6) { continue }
                        if ($stop -contains $name) { continue }
                        if ($name -match '^(get|set|is|on|to)[A-Z]') { continue }
                        if ($derived -notcontains $name) { $derived += $name }
                        if ($derived.Count -ge $maxDerived) { break }
                    }
                }
                $deepNotes.Add('derived markers from ' + $derivedSource + ' files=' + @($changed).Count + ' required=[' + ($derived -join ',') + ']')
            }

            # ③ 一起在模板 dex 里找：注册表标记 + 推导标记，缺任何一个都 STALE（task-91 的核心）
            $required = @(@($registryMarkers) + @($derived) | Select-Object -Unique)
            if ($required.Count -gt 0) {
                $found = Test-ZipEntryContains -ZipPath $TemplatePath -EntryPattern 'classes*.dex' -Markers $required
                $missing = @($required | Where-Object { -not $found[$_] })
                $deepNotes.Add('template dex: required=[' + ($required -join ',') + '] missing=[' + ($missing -join ',') + ']')
                foreach ($name in $missing) {
                    $since = if ($registrySince.ContainsKey($name)) { ' (since ' + $registrySince[$name] + ')' } else { ' (derived)' }
                    $results.Add([pscustomobject]@{ kind = 'template'; state = 'STALE'; reason = 'marker-missing:' + $name + $since + ' → 模板没跟上这批源码（先跑 build-slim-template.ps1）；确属误报可加 -NoDerived 或同步注册表'; newest = ''; newestPath = ''; artifact = $TemplatePath })
                }
                if ($missing.Count -eq 0) {
                    # 内容级判据通过 ⇒ mtime 的 STALE 是 checkout/复制造成的假阳性（深度优先）
                    foreach ($r in $results) {
                        if ($r.kind -eq 'template' -and $r.state -eq 'STALE') {
                            $r.state = 'FRESH'
                            $r.reason = 'mtime 说旧，但 dex 内容级判据通过（' + $required.Count + ' 个标记齐全）'
                        }
                    }
                }
            } else {
                $deepNotes.Add('template dex: 没有可判定的标记（注册表+推导都为空）')
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
            $extra = if ($r.kind -eq 'plugin-template') { ' → 先跑 .\gradlew.bat :inrt:assembleRelease（buildApkPlugin 会顺带重建插件兜底模板 + manifest）' } elseif ($r.kind -eq 'template' -or $r.kind -eq 'template-deep') { ' → 先跑 .\tools\build-slim-template.ps1' } elseif ($r.kind -eq 'aot' -or $r.kind -eq 'aot-deep') { ' → 加 -RebuildAot（或先跑 tools/flutter-poc/build-aot.ps1）' }
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
