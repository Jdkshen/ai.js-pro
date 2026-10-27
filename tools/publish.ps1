<#
  发布一版到自建更新源（.artifacts/updates），带前置校验与发布后验证。

  为什么要有这个脚本（而不是直接跑 tools/dev-update.ps1）：
  - dev-update.ps1 的 Get-DeviceVersionCode / adb reverse 依赖它自己解析设备；
    在多设备在线时会静默把手机版本读成 0，把「手机上已装」从基准里丢掉，算小版本号。
    本脚本用 -s 自己读一遍，算出版本号后【显式】传给 dev-update.ps1。
  - 发布后要验证：versionCode 等于预期、SHA-256 与清单一致、手机真的能拉到清单。
    dev-update.ps1 不校验「发出去的版本号是否可以让手机收到更新」。

  默认走 -NoServe（不起 HTTP 服务），适用于「PC 上已有常驻服务在服务同一个目录」的场景：
  PC 的 8080 常由 `python -m http.server 8080 --directory .artifacts\updates` 提供，
  写完文件即时生效。手机更新源配置为 http://<PcHost>:<Port>/update.json（局域网可达），
  因此跳过 adb reverse 也不影响手机检查更新。

  ⚠️ 本脚本会真的发布。只想看会发什么版本、不写任何文件时，加 -DryRun。

  注意：本文件必须保存为「UTF-8 带 BOM」。Windows PowerShell 5.1 读无 BOM 脚本时按 GBK
  解码，本脚本含中文输出，无 BOM 会乱码甚至解析报错。

  用法：
    .\tools\publish.ps1 -ReleaseNotes "本版改了什么"
    .\tools\publish.ps1 -ReleaseNotes "..." -DryRun
    .\tools\publish.ps1 -ReleaseNotes "..." -AllowMultiDevice -DeviceId cccc62c7
#>

param(
    [Parameter(Mandatory = $true)]
    [string]$ReleaseNotes,

    [string]$Abi = 'arm64-v8a',

    # A local test candidate may be newer than the connected test phone/feed.
    # An explicit release code must still be above every observed baseline.
    [ValidateRange(0,2100000000)][int]$VersionCode = 0,

    [string]$DeviceId = 'cccc62c7',

    [string]$PcHost = '192.168.10.5',

    [int]$Port = 8080,

    # 复用现有 APK 直接发布（不重新构建）。「编译后自动发布」链路用它：
    # 构建已由调用方完成，这里只做发布 + 验证。
    [switch]$SkipBuild,

    # 显式发布已验证的 APK（包括 Flutter release），避免误选 debug 目录的旧产物。
    # 仅与 -SkipBuild 同用；沿用当前开发通道签名校验，插件附件保持原样。
    [string]$ApkPath = '',

    # 多设备时也允许发布，但仍需满足下方第 0 节的两个前提。
    [switch]$AllowMultiDevice,

    # 只做前置检查与基准计算并打印将发布的版本号，不构建、不发布、不写任何文件。
    [switch]$DryRun,

    # 完整日志落盘路径；默认 .artifacts\publish-<时间戳>.log
    [string]$LogFile = ''
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$updatesDir = Join-Path $projectRoot '.artifacts\updates'
$manifestPath = Join-Path $updatesDir 'update.json'
$adb = 'C:\Android\platform-tools\adb.exe'
$packageName = 'com.jdkshen.aijspro'

if ([string]::IsNullOrWhiteSpace($LogFile)) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $LogFile = Join-Path $projectRoot ".artifacts\publish-$stamp.log"
}

# 把本次运行的完整输出同时落到日志文件。
# 这样调用方永远不需要用 `| Select-String` 之类的管道去过滤本脚本的输出 ——
# 那种写法会提前关闭管道并把 gradle 进程杀掉（本仓库已因此损失过两次构建）。
try {
    Start-Transcript -LiteralPath $LogFile -Force | Out-Null
    $transcriptActive = $true
} catch {
    Write-Host "警告：无法写日志文件 $LogFile（$($_.Exception.Message)），继续但不留档" -ForegroundColor Yellow
    $transcriptActive = $false
}

function Fail([string]$message) {
    Write-Host "FAILED: $message" -ForegroundColor Red
    if ($transcriptActive) { Stop-Transcript | Out-Null }
    exit 1
}

function Done([string]$message) {
    Write-Host $message -ForegroundColor Green
    Write-Host "日志：$LogFile" -ForegroundColor DarkGray
    if ($transcriptActive) { Stop-Transcript | Out-Null }
    exit 0
}

if (-not [string]::IsNullOrWhiteSpace($ApkPath) -and -not $SkipBuild) {
    Fail '-ApkPath 必须与 -SkipBuild 同用；先构建并验证目标 APK。'
}

# --- 0. 前置：目标设备在线；多设备需显式放行 ---------------------------------
# 多设备时 dev-update.ps1 的 dumpsys 会静默返回 0（把手机已装版本从基准里去掉）。
# 用 -AllowMultiDevice 放行时必须满足：
#   1) 目标机（$DeviceId）确实装着 App，且能用 -s 读到 versionCode；
#   2) 手机上的 versionCode 不高于「仓库版本」与「已发布版本」中的较大者
#      —— 这样即使漏读也不改变 max()，基准仍正确。
$online = @(& $adb devices | Select-String '^\S+\s+device$')
if ($online.Count -eq 0) { Fail '没有在线设备。' }
if ($online.Count -ne 1 -and -not $AllowMultiDevice) {
    Write-Host "当前在线设备：$($online.Count) 个" -ForegroundColor Yellow
    $online | ForEach-Object { Write-Host "  $($_.Line)" }
    Fail '请先拔掉多余的设备，只保留目标机（或用 -AllowMultiDevice，前提见脚本注释）。'
}
$targetOnline = @($online | Where-Object { $_.Line -match $DeviceId })
if ($targetOnline.Count -ne 1) {
    Fail "目标设备 $DeviceId 不在线（在线：$($online.Line -join ', ')）。"
}
Write-Host "[0/5] 设备检查通过：$DeviceId（在线 $($online.Count) 台）" -ForegroundColor Green

# arm64 发布包必须带 Pikafish NNUE；缺失时不能静默退回 JS 算棋，
# 否则面板上的原生线程设置看似生效、实际完全不起作用。
if ($Abi -eq 'arm64-v8a' -and -not $SkipBuild) {
    $nnuePath = Join-Path $projectRoot 'third-party\Pikafish\src\pikafish.nnue'
    if (-not (Test-Path -LiteralPath $nnuePath -PathType Leaf)) {
        Fail "找不到 Pikafish 权重：$nnuePath；arm64 发布已取消。"
    }
    $nnueBytes = (Get-Item -LiteralPath $nnuePath).Length
    if ($nnueBytes -lt 20000000) {
        Fail "Pikafish 权重过小（$nnueBytes 字节）：$nnuePath；arm64 发布已取消。"
    }
    Write-Host "      Pikafish NNUE 已就绪：$nnueBytes 字节" -ForegroundColor Green
}

# --- 1. 当前基准（版本号由本脚本算定，再显式交给 dev-update.ps1）-------------
$versionsFile = Join-Path $projectRoot 'project-versions.json'
$repoVersion = [int](Get-Content -LiteralPath $versionsFile -Raw -Encoding UTF8 | ConvertFrom-Json).appVersionCode
$publishedVersion = 0
if (Test-Path -LiteralPath $manifestPath) {
    $publishedVersion = [int]((Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json).versionCode)
}
$dump = ((& $adb -s $DeviceId shell dumpsys package $packageName) -join "`n")
$deviceVersion = [int][regex]::Match($dump, 'versionCode=(\d+)').Groups[1].Value
if ($deviceVersion -le 0) {
    Fail "读不到 $DeviceId 上 $packageName 的 versionCode（App 没装？）。缺了这项基准会算小版本号。"
}
$expected = [Math]::Max([Math]::Max($repoVersion, $publishedVersion), $deviceVersion) + 1
if ($VersionCode -gt 0) {
    if ($VersionCode -lt $expected) {
        Fail "显式版本 $VersionCode 必须至少为 $expected，不能覆盖已发布或已安装版本。"
    }
    $expected = $VersionCode
}
Write-Host "[1/5] baseline: repo=$repoVersion published=$publishedVersion device=$deviceVersion -> 预期 dev-$expected" -ForegroundColor Green

# --- 2. 读手机更新源（只读，不改手机）----------------------------------------
$prefsPath = "/data/data/$packageName/shared_prefs/${packageName}_preferences.xml"
$previousPrefsAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    # Android release 不允许 run-as；这是只读的可选信息，不能阻止发布。
    $prefs = ((& $adb -s $DeviceId shell run-as $packageName cat $prefsPath) 2>&1) -join "`n"
} finally { $ErrorActionPreference = $previousPrefsAction }
$sourceUrl = [regex]::Match($prefs, 'key_update_source_url">([^<]+)<').Groups[1].Value
# 仅本地 update.json 跟随手机的主机；GitHub/代理源不指向这次发布的本地目录。
$verifyHost = $PcHost
$configuredSource = $null
if ([Uri]::TryCreate($sourceUrl, [UriKind]::Absolute, [ref]$configuredSource) -and
    $configuredSource.AbsolutePath -eq '/update.json' -and $configuredSource.Port -eq $Port) {
    $verifyHost = $configuredSource.Host
}
if ($sourceUrl) {
    Write-Host "[2/5] 手机更新源：$sourceUrl" -ForegroundColor Green
    if ($verifyHost -ne $PcHost) {
        Write-Host "      注意：手机更新源主机是 $verifyHost（脚本默认 $PcHost），发布校验将用 $verifyHost。" -ForegroundColor Yellow
    }
} else {
    Write-Host "[2/5] 未读到更新源设置（不影响发布，但请确认手机能访问 $PcHost`:$Port）" -ForegroundColor Yellow
}

if ($DryRun) {
    Write-Host ""
    Write-Host "[DryRun] 不会构建、不会发布、不会写任何文件。" -ForegroundColor Cyan
    Write-Host "  将要发布的版本：dev-$expected（versionCode $expected）" -ForegroundColor Cyan
    if ($SkipBuild) {
        Write-Host "  已指定 -SkipBuild：将复用现有 APK，不重新构建。" -ForegroundColor Cyan
    }
    Write-Host "  清单 SHA 校验、手机可达性检查均跳过。" -ForegroundColor Cyan
    Done "DryRun 完成。"
}

# --- 3+4. 构建并发布 ---------------------------------------------------------
# Gradle/JVM 会把「注: 某些输入文件使用或覆盖了已过时的 API」写到 stderr；
# 在 $ErrorActionPreference='Stop' 下原生命令的任何 stderr 都会被当成终止错误并让整个脚本
# 以 1 退出 —— 即使构建其实 BUILD SUCCESSFUL。故临时放宽，只认真实退出码。
if ($SkipBuild) {
    Write-Host "[3/5] 发布（复用现有 APK，目标 dev-$expected，不重新构建）..." -ForegroundColor Cyan
} else {
    Write-Host "[3/5] 构建 + 发布（$Abi，目标 dev-$expected，耗时数分钟）..." -ForegroundColor Cyan
}
$previousErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    # 必须把 -DeviceId 一并传下去：dev-update.ps1 自己也有多设备守卫，不传会在
    # 「检测到 2 台设备」处抛错 —— 本脚本的 -AllowMultiDevice 管不到下游。
    # -SkipBuild 同样按需透传（复用调用方已构建好的 APK）。
    $devUpdateArgs = @{
        Abi          = $Abi
        NoServe      = $true
        DeviceId     = $DeviceId
        VersionCode  = $expected
        VersionName  = "dev-$expected"
        ReleaseNotes = $ReleaseNotes
    }
    if ($SkipBuild) { $devUpdateArgs['SkipBuild'] = $true }
    if ([string]::IsNullOrWhiteSpace($ApkPath)) {
        & (Join-Path $PSScriptRoot 'dev-update.ps1') @devUpdateArgs
        $devUpdateExit = $LASTEXITCODE
    } else {
        $inputApk = Get-Item -LiteralPath $ApkPath -ErrorAction Stop
        if ($inputApk.Name -match '-unsigned\.apk$') { throw '不能发布 unsigned APK。' }
        $aaptTool = Get-ChildItem 'C:\Android\build-tools\*\aapt.exe' |
            Sort-Object FullName -Descending | Select-Object -First 1
        $signerTool = Join-Path $aaptTool.DirectoryName 'apksigner.bat'
        $badging = (& $aaptTool.FullName dump badging $inputApk.FullName) -join "`n"
        if ($LASTEXITCODE -ne 0 -or
                $badging -notmatch "package: name='com\.jdkshen\.aijspro' versionCode='$expected' versionName='dev-$expected'") {
            throw "显式 APK 的包名/版本必须是 com.jdkshen.aijspro / dev-$expected。"
        }
        $tree = (& $aaptTool.FullName dump xmltree $inputApk.FullName 'AndroidManifest.xml') -join "`n"
        if ($LASTEXITCODE -ne 0 -or $tree -match 'org\.autojs\.autojspro\.debug\.provider') {
            throw 'APK 清单检查失败或仍包含冲突 provider。'
        }
        $certs = (& $signerTool verify --print-certs $inputApk.FullName 2>&1) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw '显式 APK 签名校验失败。' }
        $newSigner = @([regex]::Matches($certs, 'certificate SHA-256 digest: ([0-9a-fA-F]+)') |
            ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() })
        $previousFeed = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $previousAsset = @($previousFeed.assets)[0]
        $previousApk = Join-Path $updatesDir ([string]$previousAsset.name)
        $oldCerts = (& $signerTool verify --print-certs $previousApk 2>&1) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw '无法核对当前开发通道签名，拒绝覆盖更新源。' }
        $oldSigner = @([regex]::Matches($oldCerts, 'certificate SHA-256 digest: ([0-9a-fA-F]+)') |
            ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() })
        if ($newSigner.Count -ne 1 -or $oldSigner.Count -ne 1 -or $newSigner[0] -ne $oldSigner[0]) {
            throw '新 APK 与当前开发通道签名不一致。'
        }
        & python (Join-Path $PSScriptRoot 'check-android-16kb.py') $inputApk.FullName --abi $Abi
        if ($LASTEXITCODE -ne 0) { throw '16KB ELF/RELRO/ZIP 校验未通过。' }
        . (Join-Path $PSScriptRoot 'jdk17.ps1') | Out-Null
        & python (Join-Path $PSScriptRoot 'check-apk-retrofit.py') $inputApk.FullName
        if ($LASTEXITCODE -ne 0) { throw 'Retrofit APK signature audit failed; refusing publication.' }
        $staging = Join-Path $projectRoot '.artifacts\release-staging'
        New-Item -ItemType Directory -Force -Path $staging | Out-Null
        $stagedApk = Join-Path $staging "AI-js-Pro-Flutter-dev-$expected.apk"
        Copy-Item -LiteralPath $inputApk.FullName -Destination $stagedApk -Force
        # 直接交给现有更新源写入器；不重选/替换其他工作项正在维护的插件附件。
        & (Join-Path $PSScriptRoot 'serve-updates.ps1') -Apk $stagedApk -Dir $updatesDir `
            -VersionCode $expected -VersionName "dev-$expected" -ReleaseNotes $ReleaseNotes -NoServe
        $devUpdateExit = $LASTEXITCODE
    }
} finally {
    $ErrorActionPreference = $previousErrorAction
}
if ($devUpdateExit -ne 0) { Fail "dev-update.ps1 退出码 $devUpdateExit" }

# --- 5. 验证发布结果 ---------------------------------------------------------
Write-Host "[5/5] 验证发布产物" -ForegroundColor Cyan
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$publishedCode = [int]$manifest.versionCode
if ($publishedCode -ne $expected) {
    # 发错版本号的后果是「手机检查更新时提示已是最新」——必须直接失败，不能只提示。
    Fail "发布的 versionCode 是 $publishedCode，预期 $expected。手机将收不到更新提示。"
}
$asset = @($manifest.assets)[0]
$apkPath = Join-Path $updatesDir $asset.name
if (-not (Test-Path -LiteralPath $apkPath)) { Fail "APK 未找到：$apkPath" }
$realHash = 'sha256:' + (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($realHash -ne $asset.sha256) {
    Fail "SHA-256 不匹配：清单 $($asset.sha256) / 实际 $realHash"
}
$sizeMb = [math]::Round((Get-Item -LiteralPath $apkPath).Length / 1MB, 2)

# --- 服务可达性：用手机自己 curl，比在 PC 上测更有说服力 ---------------------
$url = "http://${verifyHost}:$Port/update.json"
$code = ((& $adb -s $DeviceId shell "curl -s -o /dev/null -w '%{http_code}' --max-time 6 $url") 2>&1) -join ''
$code = $code.Trim()

Write-Host "  versionCode : $publishedCode / $($manifest.versionName)" -ForegroundColor Green
Write-Host "  APK         : $($asset.name)  ($sizeMb MB)" -ForegroundColor Green
Write-Host "  SHA-256     : $realHash" -ForegroundColor Green
Write-Host "  手机拉取 $url -> HTTP $code" -ForegroundColor $(if ($code -eq '200') { 'Green' } else { 'Red' })
if ($code -ne '200') {
    Fail "手机拉不到清单。检查 HTTP 服务是否在跑、以及两者是否同一网段。"
}

Done "发布完成：dev-$publishedCode 已就绪，手机可检查更新。"
