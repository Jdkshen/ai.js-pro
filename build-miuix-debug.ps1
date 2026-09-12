param(
    [switch]$SkipNative,
    [switch]$IncludeInrt,
    [switch]$Lite
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
