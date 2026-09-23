param(
    [switch]$SkipNative,
    [switch]$IncludeInrt,
    [switch]$Lite,

    # 只编译、不自动发布。默认：编译成功后自动发一版到更新源（版本号自动 +1，
    # 复用刚构建的 APK，不再重复编译）。
    [switch]$NoPublish
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

# 统一选择 JDK 17（避免直接构建吃到系统 Java 8 而在配置阶段失败）。
. (Join-Path $projectRoot 'tools\jdk17.ps1')

# 默认变体 = miuix + compat（MIUIX 皮肤 + Rhino/QuickJS 双引擎），与 CI、release.ps1
# 和 AGENTS.md 保持一致。历史上这里是 :app:assembleCommonDebug，但 common / coolapk
# 两个 channel flavor 已在「UI 统一到 Compose」时退役（见
# docs/plans/UI_统一到 Compose(Miuix) 迁移方案.md 第 2 节），该任务名已不存在。
# 需要精简执行版（仅 QuickJS）时加 -Lite。

if (-not $SkipNative) {
    & (Join-Path $projectRoot 'modules\engine\src\main\cpp\build-quickjs.ps1')
    if ($LASTEXITCODE -ne 0) {
        throw "QuickJS native build failed with exit code $LASTEXITCODE"
    }
}

Push-Location $projectRoot
try {
    $variant = if ($Lite) { 'MiuixLiteDebug' } else { 'MiuixCompatDebug' }
    $gradleTasks = @(":app:assemble$variant")
    if ($IncludeInrt) {
        $gradleTasks += ':inrt:assembleDebug'
    }
    & '.\gradlew.bat' @gradleTasks '--no-daemon'
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

# ---- 编译完成 → 自动发布（发布脚本以正确版本号打包；-NoPublish 可跳过）-----------
# 日常流程：改代码 → 跑本脚本 → 编译成功自动发一版（版本号自动 +1），手机上
# 点“检查更新”就能收到。自动发布需要至少一台装着 App 的设备在线（读版本号做
# 基准 + 发布后在手机上验证可达性）；没有设备时跳过发布并提示，不影响编译结果。
if (-not $NoPublish) {
    if ($Lite) {
        Write-Host '提示：-Lite 变体不自动发布（更新源只发 MiuixCompatDebug）。' -ForegroundColor Yellow
    } else {
        Write-Host ''
        Write-Host '=== 编译成功：自动发布 ===' -ForegroundColor Cyan

        # 目标设备：优先 K40（cccc62c7），否则取任意一台在线设备
        $adbPath = 'C:\Android\platform-tools\adb.exe'
        $online = @()
        try {
            $online = @(& $adbPath devices 2>$null | Select-String '^\S+\s+device$' |
                ForEach-Object { ($_.Line -split '\s+')[0] })
        } catch { }
        if ($online.Count -eq 0) {
            Write-Host '警告：没有在线设备，已跳过自动发布（编译产物已就绪，之后可双击桌面「发布更新」补发）。' -ForegroundColor Yellow
        } else {
            $target = if ($online -contains 'cccc62c7') { 'cccc62c7' } else { $online[0] }
            Write-Host "目标设备：$target（在线 $($online.Count) 台）" -ForegroundColor Gray

            # 发布说明：取最近一次 git 提交标题（取不到就用时间戳）
            $notes = ''
            try {
                $oldEnc = [Console]::OutputEncoding
                [Console]::OutputEncoding = [Text.Encoding]::UTF8
                $notes = ((& git -C $projectRoot log -1 --format=%s 2>$null) -join ' ').Trim()
                [Console]::OutputEncoding = $oldEnc
            } catch { }
            if ([string]::IsNullOrWhiteSpace($notes)) {
                $notes = '自动发布（编译触发 ' + (Get-Date -Format 'yyyy-MM-dd HH:mm') + '）'
            }
            Write-Host "发布说明：$notes" -ForegroundColor Gray

            try {
                # ⚠ 不能加 -SkipBuild：普通编译产物的 versionCode 是仓库号（465），
                # 与要发布的版本号不一致；清单版本与 APK 实际版本必须一致，否则手机
                # 安装时会被当成降级拒绝。发布脚本会先把版本号写进构建再打包（增量、
                # 很快）——本脚本前面那次编译只用于“验证能编译过”。
                & (Join-Path $projectRoot 'tools\publish.ps1') -ReleaseNotes $notes -AllowMultiDevice -DeviceId $target
                if ($LASTEXITCODE -ne 0) { throw "发布脚本退出码 $LASTEXITCODE" }
                Write-Host '=== 已发布：手机可以检查更新了 ===' -ForegroundColor Green
            } catch {
                Write-Host "警告：自动发布未完成（$($_.Exception.Message)）" -ForegroundColor Yellow
                Write-Host '编译产物已就绪，可稍后双击桌面「发布更新」手动补发。' -ForegroundColor Yellow
            }
        }
    }
}
