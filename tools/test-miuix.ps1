param(
    [string]$Drive = '',
    [string[]]$GradleTasks = @(':app:testMiuixCompatDebugUnitTest', ':app:testMiuixLiteDebugUnitTest')
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

# 统一选择 JDK 17（避免吃到系统 Java 8；含 java -version stderr 兼容处理）。
. (Join-Path $PSScriptRoot 'jdk17.ps1')
if (-not $GradleTasks -or $GradleTasks.Count -eq 0) { throw 'At least one Gradle task is required.' }

# 仓库路径已改为纯 ASCII（ai-js-pro）；仅当路径含非 ASCII 字符时才用 subst
# 建立临时盘符——Gradle/JDK 参数文件在中文路径下可能解码错误。
$useSubst = $projectRoot -match '[^\x00-\x7F]'
$runRoot = $projectRoot
if ($useSubst) {
    if (-not $Drive) {
        $Drive = @('S:', 'R:', 'Q:', 'P:') | Where-Object { -not (Test-Path -LiteralPath "$_\") } | Select-Object -First 1
        if (-not $Drive) { throw 'No free temporary drive letter is available (tried S:, R:, Q:, P:).' }
    }
    if ($Drive -notmatch '^[A-Za-z]:$') { throw 'Drive must look like S:.' }
    $Drive = $Drive.ToUpperInvariant()
    if (Test-Path -LiteralPath "$Drive\") { throw "Drive is already in use: $Drive" }
    & subst.exe $Drive $projectRoot
    if ($LASTEXITCODE -ne 0) { throw 'Unable to create temporary ASCII drive mapping.' }
    $runRoot = "$Drive\"
}

try {
    Push-Location $runRoot
    try {
        & '.\gradlew.bat' @GradleTasks '--no-daemon'
        if ($LASTEXITCODE -ne 0) { throw 'Miuix unit tests failed.' }
    } finally {
        Pop-Location
    }
} finally {
    if ($useSubst -and (Test-Path -LiteralPath "$Drive\")) { & subst.exe $Drive /D }
}
