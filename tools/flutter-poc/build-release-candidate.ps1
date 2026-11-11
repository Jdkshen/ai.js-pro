<#
.SYNOPSIS
  只构建 Flutter Android release 候选，不发布、不安装、不改仓库版本文件。

.DESCRIPTION
  这是给主助手/本地验收用的“候选构建”入口。它固定：
    -PflutterPoc=true
    -PflutterPocAbi=arm64-v8a   （payload 只有 arm64；避免打出缺 libflutter 的 v7a/x86 包）

  默认保留 VS Code USB 自动发现 provider；同机安装真 Auto.js Pro 会冲突时再显式加
  -NoUsbDiscovery（只去掉 USB 自动发现，LAN/MCP 不受影响）。

  正式签名仍只读 AIJSPRO_*。没有正式签名时必须显式 -TestSigning 才会使用本机 debug
  keystore；默认既不自动退回 debug，也不会把候选发布到 .artifacts/updates。

  默认版本号来自 project-versions.json；-VersionCode/-VersionName 通过 Gradle -P 注入，
  不会修改仓库版本文件。

  产物体积/签名/清单检查：
    - 拒绝 *-unsigned.apk
    - apksigner 校验签名；测试签名会明确标注
    - aapt2 检查 release 清单不含 DebugCommandReceiver / DEBUG_RUN / WRITE_SECURE_SETTINGS
    - 可选跑 tools/check-android-16kb.py 与 tools/audit-apk-size.py
    - 复制候选到 .artifacts/flutter-release-candidate（或 -OutDir），写 SHA-256 sidecar

.EXAMPLE
  # 有正式 AIJSPRO_* 环境变量
  .\tools\flutter-poc\build-release-candidate.ps1 -VersionCode 1241 -VersionName 1.0.2

.EXAMPLE
  # 本地验收：显式测试签名 + 保守 R8
  .\tools\flutter-poc\build-release-candidate.ps1 -VersionCode 1241 -VersionName 1.0.2 -R8 -TestSigning

.EXAMPLE
  # 显式资源裁剪（必须先有 R8；默认不推荐，动态 getIdentifier 资源需要额外 keep 审计）
  .\tools\flutter-poc\build-release-candidate.ps1 -R8 -ShrinkResources -TestSigning
#>

[CmdletBinding()]
param(
    [int]$VersionCode = 0,
    [string]$VersionName = '',
    [switch]$R8,
    [switch]$ShrinkResources,
    [switch]$OptimizeR8,
    # 仅本地验收：用本机 debug keystore 给 release 签名。绝不会自动启用。
    [switch]$TestSigning,
    # 与真 Auto.js Pro 同机安装时去掉 USB 自动发现 provider（LAN/MCP 不受影响）。
    [switch]$NoUsbDiscovery,
    # 显式重建 AOT；默认复用已锁定的 output/flutter-poc/payload。
    [switch]$RebuildAot,
    # Lead 2026-11-10：出包前跑构件新鲜度自检（模板/AOT 是否跟得上源码）。STALE 直接失败；急用可 -SkipFreshnessCheck。
    [switch]$SkipFreshnessCheck,
# Lead 2026-11-12：UI 颜色对比度门禁（ui-contrast --audit --strict）。急用可跳过。
[switch]$SkipUiContrast,
# Lead 2026-11-12：Flutter↔原生本地桥契约门禁（tools/flutter-bridge-contract.mjs --strict）。急用可跳过。
[switch]$SkipBridgeContract,
# Lead 2026-11-12：PowerShell 编码门禁（.ps1 必须带 UTF-8 BOM）。急用可跳过。
[switch]$SkipPs1Encoding,
    [switch]$AotOffline,
    [string]$BaselineApk = '',
    [double]$MaxTotalMiB = -1,
    [double]$MaxGrowthMiB = -1,
    [double]$MaxGrowthPercent = -1,
    [string]$OutDir = '',
    [switch]$Skip16kbCheck,
    [ValidateRange(0,8192)][int]$MaxHeapMb = 0,
    [ValidateRange(1,8)][int]$MaxWorkers = 1
)

$ErrorActionPreference = 'Continue'
if ($MaxHeapMb -eq 0) { $MaxHeapMb = if ($R8) { 2048 } else { 1024 } }
$scriptPath = $MyInvocation.MyCommand.Path
$pocDir = Split-Path -Parent $scriptPath
$repo = Split-Path -Parent (Split-Path -Parent $pocDir)
$versionsFile = Join-Path $repo 'project-versions.json'
$payloadDir = Join-Path $repo 'output\flutter-poc\payload'

if (-not (Test-Path -LiteralPath $versionsFile -PathType Leaf)) {
    throw "仓库根目录识别失败，缺少 project-versions.json：$repo"
}
if (-not (Test-Path -LiteralPath $payloadDir -PathType Container)) {
    throw "Flutter payload 缺失：$payloadDir；先运行 tools/flutter-poc/build-aot.ps1"
}
if ($ShrinkResources -and -not $R8) {
    throw '资源裁剪必须同时开启 -R8；资源 shrink 依赖 R8 的代码引用分析。'
}
if ($OptimizeR8 -and -not $R8) {
    throw '-OptimizeR8 必须同时开启 -R8。'
}
if ($MaxGrowthMiB -ge 0 -or $MaxGrowthPercent -ge 0) {
    if ([string]::IsNullOrWhiteSpace($BaselineApk)) {
        throw '体积增长预算需要 -BaselineApk 指向旧 APK。'
    }
}
if (-not [string]::IsNullOrWhiteSpace($VersionName) -and $VersionName -match '\s') {
    throw 'VersionName 不能包含空白字符。'
}

$signingNames = @('AIJSPRO_KEYSTORE', 'AIJSPRO_STORE_PASSWORD', 'AIJSPRO_KEY_ALIAS', 'AIJSPRO_KEY_PASSWORD')
$signingSet = @($signingNames | ForEach-Object {
    -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_))
})
$signingConfigured = @($signingSet | Where-Object { $_ }).Count -eq $signingNames.Count
$signingPartial = @($signingSet | Where-Object { $_ }).Count -gt 0 -and -not $signingConfigured
if ($signingPartial) {
    throw 'AIJSPRO_* 只配置了一部分；请补全四项正式签名变量，或清空后显式 -TestSigning。'
}
if ($signingConfigured -and $TestSigning) {
    throw '检测到正式 AIJSPRO_* 且传了 -TestSigning，签名意图不明确；请二选一。'
}
if (-not $signingConfigured -and -not $TestSigning) {
    throw '没有正式 AIJSPRO_*。正式 release 不会悄悄退回 debug 签名；本地验收请显式加 -TestSigning。'
}
$signingLabel = if ($signingConfigured) { 'official' } else { 'test-debug' }

$repoVersions = Get-Content -LiteralPath $versionsFile -Raw -Encoding UTF8 | ConvertFrom-Json
$effectiveVersionCode = if ($VersionCode -gt 0) { $VersionCode } else { [int]$repoVersions.appVersionCode }
$effectiveVersionName = if ([string]::IsNullOrWhiteSpace($VersionName)) {
    [string]$repoVersions.appVersionName
} else {
    $VersionName
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$defaultOutDir = Join-Path $repo '.artifacts\flutter-release-candidate'
$outRoot = if ([string]::IsNullOrWhiteSpace($OutDir)) { $defaultOutDir } else { $OutDir }
if (-not [IO.Path]::IsPathRooted($outRoot)) {
    $outRoot = Join-Path $repo $outRoot
}
$outRoot = [IO.Path]::GetFullPath($outRoot)
New-Item -ItemType Directory -Force -Path $outRoot | Out-Null
$logPath = Join-Path $outRoot "build-$stamp.log"

if ($RebuildAot) {
    $aotScript = Join-Path $pocDir 'build-aot.ps1'
    if (-not (Test-Path -LiteralPath $aotScript -PathType Leaf)) {
        throw "缺少 AOT 构建脚本：$aotScript"
    }
    $aotArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $aotScript)
    if ($AotOffline) { $aotArgs += '-Offline' }
    Write-Host '[1/5] 重建 Flutter AOT payload' -ForegroundColor Cyan
    & powershell @aotArgs
    if ($LASTEXITCODE -ne 0) { throw "build-aot.ps1 失败：$LASTEXITCODE" }
} else {
    Write-Host '[1/5] 复用已锁定 payload（不重建 AOT）' -ForegroundColor DarkGray
}

. (Join-Path $repo 'tools\jdk17.ps1') | Out-Null

$gradleArgs = @(
    ':app:assembleMiuixCompatRelease',
    '-PflutterPoc=true',
    '-PflutterPocAbi=arm64-v8a',
    '--console=plain',
    '--no-daemon'
)
$gradleArgs += "--max-workers=$MaxWorkers"
$gradleArgs += "-Dorg.gradle.jvmargs=-Xms64m -Xmx${MaxHeapMb}m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8"
$gradleArgs += '-Pkotlin.compiler.execution.strategy=in-process'
if ($NoUsbDiscovery) { $gradleArgs += '-PdevPluginUsbDiscovery=false' }
if ($VersionCode -gt 0) { $gradleArgs += "-PappVersionCode=$VersionCode" }
if (-not [string]::IsNullOrWhiteSpace($VersionName)) { $gradleArgs += "-PappVersionName=$VersionName" }
if ($R8) { $gradleArgs += '-PreleaseR8=true' }
if ($OptimizeR8) { $gradleArgs += '-PreleaseR8Optimize=true' }
if ($ShrinkResources) { $gradleArgs += '-PreleaseShrinkResources=true' }
if ($TestSigning) { $gradleArgs += '-PflutterReleaseTestSigning=true' }

Write-Host "[2/5] Gradle release（signing=$signingLabel, R8=$([bool]$R8), shrink=$([bool]$ShrinkResources)）" -ForegroundColor Cyan
Write-Host ("版本: {0} / {1}" -f $effectiveVersionCode, $effectiveVersionName) -ForegroundColor DarkGray
Write-Host ("日志: {0}" -f $logPath) -ForegroundColor DarkGray
Push-Location $repo
try {
    # Lead 2026-11-10：出包前的构件新鲜度门禁（今天两次真机踩坑都是「构件静默过期」：
#   模板没重建 -> 打包版跑旧引擎；AOT 没 -RebuildAot -> UI 文案看不见）。
if (-not $SkipFreshnessCheck) {
    $freshness = Join-Path $repo 'tools/check-artifact-freshness.ps1'
    if (Test-Path -LiteralPath $freshness -PathType Leaf) {
        # Lead 2026-11-11：带 -RebuildAot 时**跳过 AOT 那项检查** —— 门禁跑在 AOT 重建**之前**，
        # 此刻 payload 必然比 flutter-ui 源码旧（STALE(kind=aot)），是**假阳性**：加了 -RebuildAot 就是
        # 明确表示「马上要从源码重建 AOT」⇒ 再要求先满足 AOT 新鲜度是自相矛盾（此前每次都要手动补
        # -SkipFreshnessCheck 才能出包，且会把模板那项真检查一起关掉，风险更大）。
        # 模板那项**照跑**（模板不会因 -RebuildAot 而重建，STALE 仍必须拦住）。
        $freshnessArgs = @('-Root', $repo, '-Deep')
        if ($RebuildAot) { $freshnessArgs += '-SkipAot' }
        Write-Host ('[0/5] 构件新鲜度自检（tools/check-artifact-freshness.ps1 -Deep{0}）' -f $(if ($RebuildAot) { ' -SkipAot（本版将由 -RebuildAot 重建 AOT）' } else { '' })) -ForegroundColor Cyan
        & powershell -NoProfile -ExecutionPolicy Bypass -File $freshness @freshnessArgs
        if ($LASTEXITCODE -ne 0) {
            throw ('构件已过期：先跑 tools/build-slim-template.ps1（模板）或加 -RebuildAot（Flutter AOT）；' +
                   '确要强过请显式加 -SkipFreshnessCheck。')
        }
    } else {
        Write-Host '[0/5] 跳过构件新鲜度自检（脚本不存在）' -ForegroundColor DarkGray
    }
}

# Lead 2026-11-12：UI 颜色对比度门禁（WCAG）。
#  · 只查**硬编码字面量**（scheme.* 派生色算不了）⇒ 判据用非文本门槛 3:1；
#  · 已知误报类走白名单 tools/ui-contrast-allow.json（每条带理由，人工确认过）；
#  · 精确门禁仍是 flutter-ui/test/contrast_test.dart 的数值断言（随 flutter test 跑）。
#  急用可 -SkipUiContrast。
if (-not $SkipUiContrast) {
    $contrast = Join-Path $repo 'tools/ui-contrast.mjs'
    $contrastTest = Join-Path $repo 'tools/tests/ui-contrast.test.mjs'
    if ((Test-Path -LiteralPath $contrast -PathType Leaf) -and (Test-Path -LiteralPath $contrastTest -PathType Leaf)) {
        Write-Host '[0.5/5] UI 颜色对比度检查（ui-contrast --audit --strict）' -ForegroundColor Cyan
        & node $contrastTest 2>&1 | Select-Object -Last 2
        if ($LASTEXITCODE -ne 0) { throw 'UI 对比度工具自测失败：node tools/tests/ui-contrast.test.mjs' }
        Push-Location $repo
        try { & node $contrast --audit --strict 2>&1 | Select-Object -Last 6; $contrastExit = $LASTEXITCODE }
        finally { Pop-Location }
        if ($contrastExit -ne 0) {
            throw ('UI 颜色对比度不达标：node tools/ui-contrast.mjs --audit --strict（确认是误报就加进 ' +
                   'tools/ui-contrast-allow.json 并写理由；急用 -SkipUiContrast）')
        }
    }
}

# Lead 2026-11-12：Flutter↔原生本地桥契约门禁。
#  背景：本会话新增的 7 个桥方法（pluginCatalog/pluginInstall/pluginInstallStatus/pluginUninstall/
#  openInstallPermissionSettings/createLauncherShortcut/apkBreakdown）**都没进成文契约** ✗；
#  而「桥名三处同步」（PocBridge 路由 / PocIntegration 分发 / docs/guides/Flutter本地桥API.md）
#  漂移一次就是静默失效 —— 本会话已栽过两次（updateSource、apkBreakdown）。急用 -SkipBridgeContract。
if (-not $SkipBridgeContract) {
    $bridgeContract = Join-Path $repo 'tools/flutter-bridge-contract.mjs'
    if (Test-Path -LiteralPath $bridgeContract -PathType Leaf) {
        Write-Host '[0.6/5] Flutter 桥契约检查（flutter-bridge-contract --strict）' -ForegroundColor Cyan
        Push-Location $repo
        try { & node $bridgeContract --strict 2>&1 | Select-Object -Last 4; $bridgeExit = $LASTEXITCODE }
        finally { Pop-Location }
        if ($bridgeExit -ne 0) {
            throw ('Flutter 桥契约缺口：node tools/flutter-bridge-contract.mjs --strict ' +
                   '（补 docs/guides/Flutter本地桥API.md 的方法总表；急用 -SkipBridgeContract）')
        }
    }
}

# Lead 2026-11-12：PowerShell 编码门禁（每个 .ps1 必须带 UTF-8 BOM）。
#  血泪教训（三次）：本仓库 .ps1 里全是中文，缺 BOM 时 PS 5.1 按 GBK 解析 ⇒ 中文变乱码 ⇒
#  Parser::ParseFile 报 26–32 个「意外的标记」错，而 git diff 里只看到几行改动、根本看不出来。
#  触发方式极隐蔽：**用编辑工具改写 .ps1 会静默丢 BOM**。本轮实测 37 个 .ps1 里 16 个缺 BOM
#  （5 个含中文 = 真危险），已全部补齐并确认 34 个 .ps1 全部 0 语法错。
if (-not $SkipPs1Encoding) {
    $ps1Gate = Join-Path $repo 'tools/check-ps1-encoding.mjs'
    if (Test-Path -LiteralPath $ps1Gate -PathType Leaf) {
        Write-Host '[0.7/5] PowerShell 编码门禁（check-ps1-encoding）' -ForegroundColor Cyan
        Push-Location $repo
        try { & node $ps1Gate --quiet 2>&1 | Select-Object -Last 6; $ps1Exit = $LASTEXITCODE }
        finally { Pop-Location }
        if ($ps1Exit -ne 0) {
            throw ('有 .ps1 缺 UTF-8 BOM：node tools/check-ps1-encoding.mjs ' +
                   '（缺 BOM 会被 PS 5.1 按 GBK 解析中文 ⇒ 语法错；急用 -SkipPs1Encoding）')
        }
    }
}
& .\gradlew.bat @gradleArgs *> $logPath
    $gradleExit = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($gradleExit -ne 0) {
    Write-Host '--- Gradle 日志末尾 ---' -ForegroundColor Yellow
    if (Test-Path -LiteralPath $logPath) { Get-Content -LiteralPath $logPath -Tail 100 -Encoding UTF8 }
    throw "Gradle release 构建失败：$gradleExit（完整日志 $logPath）"
}

$apkDir = Join-Path $repo 'apps\app\build\outputs\apk\miuixCompat\release'
$apk = Get-ChildItem -LiteralPath $apkDir -Filter '*arm64-v8a-release*.apk' -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Where-Object { $_.Name -notmatch '-unsigned\.apk$' } |
    Select-Object -First 1
if ($null -eq $apk) {
    throw "没有找到已签名的 arm64 release APK：$apkDir（拒绝 *-unsigned.apk）"
}

# Gradle 源集正确不代表打包结果一定带 Flutter；对最终 APK 再做一次 payload 硬检查。
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
try {
    $entryNames = @($zip.Entries | ForEach-Object { $_.FullName })
    foreach ($requiredEntry in @(
        'lib/arm64-v8a/libflutter.so',
        'lib/arm64-v8a/libapp.so',
        'assets/flutter_assets/AssetManifest.bin'
    )) {
        if (-not ($entryNames -contains $requiredEntry)) {
            throw "release APK 缺少 Flutter payload 条目：$requiredEntry（检查 release sourceSet/merge 缓存）"
        }
    }
} finally {
    $zip.Dispose()
}

# ---- SDK 工具定位 -----------------------------------------------------------
$sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, 'C:\Android\sdk', 'C:\Android') |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path -LiteralPath $_) }
$buildToolDirs = @()
foreach ($sdk in $sdkCandidates) {
    $candidateDir = Join-Path $sdk 'build-tools'
    if (Test-Path -LiteralPath $candidateDir) {
        $buildToolDirs += Get-ChildItem -LiteralPath $candidateDir -Directory -ErrorAction SilentlyContinue
    }
}
$buildTool = $buildToolDirs |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'aapt2.exe') } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($null -eq $buildTool) {
    throw '找不到 aapt2.exe；请设置 ANDROID_HOME 或安装 build-tools。'
}
$aapt2 = Join-Path $buildTool.FullName 'aapt2.exe'
$apksigner = Join-Path $buildTool.FullName 'apksigner.bat'
if (-not (Test-Path -LiteralPath $apksigner -PathType Leaf)) {
    throw "找不到 apksigner：$apksigner"
}

# ---- 签名与清单验证 ---------------------------------------------------------
Write-Host '[3/5] 校验签名与 release 清单' -ForegroundColor Cyan
$signatureReport = & $apksigner verify --print-certs $apk.FullName 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    throw "apksigner 校验失败：$signatureReport"
}
$isDebugCert = $signatureReport -match 'CN=Android Debug'
if ($isDebugCert -and -not $TestSigning) {
    throw 'APK 实际是 debug 签名，但本次没有显式 -TestSigning；拒绝交付。'
}
if (-not $isDebugCert -and $signingLabel -eq 'test-debug') {
    throw '本次声明测试签名，但 apksigner 未看到 Android Debug 证书；请人工核对签名配置。'
}
if ($isDebugCert) {
    Write-Host '签名: Android Debug（仅本地验收，禁止发布）' -ForegroundColor Yellow
} else {
    Write-Host '签名: 非 Android Debug 证书' -ForegroundColor Green
}

$manifestTree = & $aapt2 dump xmltree --file AndroidManifest.xml $apk.FullName 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    throw "aapt2 dump xmltree 失败：$manifestTree"
}
if ($manifestTree -match 'DebugCommandReceiver|DEBUG_RUN') {
    throw 'release 清单仍包含 DebugCommandReceiver/DEBUG_RUN；拒绝交付。'
}
if ($manifestTree -match 'WRITE_SECURE_SETTINGS') {
    throw 'release 清单仍包含 WRITE_SECURE_SETTINGS；拒绝交付。'
}
$badging = & $aapt2 dump badging $apk.FullName 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    throw "aapt2 dump badging 失败：$badging"
}
if ($badging -match 'application-debuggable') {
    throw 'release APK 仍标记为 debuggable；拒绝交付。'
}
Write-Host '清单: 无调试广播、无 WRITE_SECURE_SETTINGS、非 debuggable' -ForegroundColor Green

# ---- 16 KB / 体积审计 --------------------------------------------------------
$python = Get-Command python -ErrorAction SilentlyContinue
if ($null -eq $python) {
    if (-not $Skip16kbCheck) {
        throw '找不到 python，无法执行 16 KB 对齐检查；确认 PATH，或显式 -Skip16kbCheck 后再自行检查。'
    }
    Write-Warning '找不到 python，跳过体积审计（16 KB 检查已显式跳过）。'
} else {
    if (-not $Skip16kbCheck) {
        $check16kb = Join-Path $repo 'tools\check-android-16kb.py'
        if (-not (Test-Path -LiteralPath $check16kb -PathType Leaf)) {
            throw "缺少 16 KB 对齐检查脚本：$check16kb"
        }
        Write-Host '[4/5] 16 KB 对齐检查' -ForegroundColor Cyan
        & $python.Source $check16kb $apk.FullName
        if ($LASTEXITCODE -ne 0) { throw "16 KB 对齐检查失败：$LASTEXITCODE" }
    } else {
        Write-Host '[4/5] 16 KB 检查已跳过（-Skip16kbCheck）' -ForegroundColor DarkGray
    }

    $auditScript = Join-Path $repo 'tools\audit-apk-size.py'
    Write-Host 'Checking for bundled Android/XML Pull platform definitions' -ForegroundColor Cyan
    & $python.Source (Join-Path $repo 'tools\check-android-platform-shadowing.py') $apk.FullName --report (Join-Path $outRoot "platform-api-$stamp.json")
    if ($LASTEXITCODE -ne 0) { throw 'Android/XML Pull API shadowing detected; refusing candidate.' }
    Write-Host 'Checking Retrofit signatures in final DEX' -ForegroundColor Cyan
    & $python.Source (Join-Path $repo 'tools\check-apk-retrofit.py') $apk.FullName
    if ($LASTEXITCODE -ne 0) { throw 'Retrofit generic signatures/annotations were stripped; refusing candidate.' }
    if (Test-Path -LiteralPath $auditScript -PathType Leaf) {
        Write-Host '[5/5] APK 体积审计' -ForegroundColor Cyan
        $auditArgs = @($auditScript, $apk.FullName, '--top', '12', '--json', (Join-Path $outRoot "size-$stamp.json"))
        if (-not [string]::IsNullOrWhiteSpace($BaselineApk)) {
            $baselineFull = if ([IO.Path]::IsPathRooted($BaselineApk)) { $BaselineApk } else { Join-Path $repo $BaselineApk }
            if (-not (Test-Path -LiteralPath $baselineFull -PathType Leaf)) { throw "Baseline APK 不存在：$baselineFull" }
            $auditArgs += @('--baseline', $baselineFull)
        }
        if ($MaxTotalMiB -ge 0) { $auditArgs += @('--max-total-mib', $MaxTotalMiB) }
        if ($MaxGrowthMiB -ge 0) { $auditArgs += @('--max-growth-mib', $MaxGrowthMiB) }
        if ($MaxGrowthPercent -ge 0) { $auditArgs += @('--max-growth-percent', $MaxGrowthPercent) }
        & $python.Source @auditArgs
        if ($LASTEXITCODE -ne 0) { throw "APK 体积预算/审计失败：$LASTEXITCODE" }
    } else {
        Write-Warning "缺少体积审计脚本：$auditScript"
    }
}

# ---- 候选复制与 sidecar -----------------------------------------------------
$candidateName = "AI-js-Pro-Flutter-release-candidate-$effectiveVersionCode.apk"
$candidatePath = Join-Path $outRoot $candidateName
Copy-Item -LiteralPath $apk.FullName -Destination $candidatePath -Force
$candidateHash = (Get-FileHash -LiteralPath $candidatePath -Algorithm SHA256).Hash.ToLowerInvariant()
$candidateSize = (Get-Item -LiteralPath $candidatePath).Length
$sidecar = [ordered]@{
    generatedAt = (Get-Date).ToString('o')
    sourceApk = $apk.FullName
    candidateApk = $candidatePath
    versionCode = $effectiveVersionCode
    versionName = $effectiveVersionName
    signing = $signingLabel
    r8 = [bool]$R8
    r8Optimize = [bool]$OptimizeR8
    shrinkResources = [bool]$ShrinkResources
    flutterPocAbi = 'arm64-v8a'
    sizeBytes = $candidateSize
    sha256 = $candidateHash
}
$sidecarPath = Join-Path $outRoot "candidate-$effectiveVersionCode-$stamp.json"
[IO.File]::WriteAllText($sidecarPath, ($sidecar | ConvertTo-Json -Depth 4), (New-Object Text.UTF8Encoding($false)))

Write-Host ''
Write-Host '=== release 候选构建完成（未发布、未安装）===' -ForegroundColor Green
Write-Host "APK: $candidatePath" -ForegroundColor White
Write-Host ("Size: {0:N0} bytes ({1:N2} MiB)" -f $candidateSize, ($candidateSize / 1MB)) -ForegroundColor White
Write-Host "SHA-256: $candidateHash" -ForegroundColor White
Write-Host "Signing: $signingLabel" -ForegroundColor $(if ($signingLabel -eq 'official') { 'Green' } else { 'Yellow' })
Write-Host "Sidecar: $sidecarPath" -ForegroundColor DarkGray
Write-Host "Build log: $logPath" -ForegroundColor DarkGray
Write-Host '未修改 update.json / project-versions.json，未执行 adb 安装。' -ForegroundColor DarkGray
