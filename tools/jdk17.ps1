# 选择 JDK 17+ 并导出 JAVA_HOME / Path 供调用脚本使用（dot-source）。
#
# 用法：. (Join-Path $projectRoot 'tools\jdk17.ps1')
#
# 背景：系统默认 JAVA_HOME 可能是 Java 8（本机为 Zulu 8），而 AGP 8.6.1 要求
# JVM 11+、本项目按 JDK 17 构建。所有构建/测试入口统一在此选择 JDK，避免
# “直接跑 gradlew 吃到 Java 8 在配置阶段失败”。
#
# 注意：java.exe -version 把版本信息写到 stderr；在 $ErrorActionPreference='Stop'
# 下该输出会被 PowerShell 5.1 误判为终止性错误。这里优先读取 JDK 自带的
# release 文件，读取不到时才在临时放宽 Stop 的作用域内回退到 java -version。

$jdk17Preferred = 'C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'

function Get-JavaMajorVersion {
    param([string]$JavaHome)

    $versionText = $null
    $releaseFile = Join-Path $JavaHome 'release'
    if (Test-Path -LiteralPath $releaseFile) {
        $m = Select-String -LiteralPath $releaseFile -Pattern 'JAVA_VERSION="(?<v>[^"]+)"' | Select-Object -First 1
        if ($m) { $versionText = $m.Matches[0].Groups['v'].Value }
    }

    if (-not $versionText) {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $versionText = (& (Join-Path $JavaHome 'bin\java.exe') -version 2>&1 | Out-String)
        } finally {
            $ErrorActionPreference = $previous
        }
        if ($versionText -match '"(?<ver>[^"]+)"') { $versionText = $Matches.ver }
    }

    if ($versionText -match '^1\.(?<legacy>\d+)') { return [int]$Matches.legacy }
    if ($versionText -match '^(?<major>\d+)') { return [int]$Matches.major }
    return $null
}

$jdk17Home = $null
foreach ($candidate in @($jdk17Preferred, $env:JAVA_HOME)) {
    if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
    if (-not (Test-Path -LiteralPath (Join-Path $candidate 'bin\java.exe'))) { continue }
    $major = Get-JavaMajorVersion -JavaHome $candidate
    if ($null -ne $major -and $major -ge 17) { $jdk17Home = $candidate; break }
}

if (-not $jdk17Home) {
    throw "未找到 JDK 17+。请安装 JDK 17，或设置 JAVA_HOME 指向它（首选路径: $jdk17Preferred）。"
}

$env:JAVA_HOME = $jdk17Home
$env:Path = "$jdk17Home\bin;$env:Path"
Write-Host "JDK: $jdk17Home" -ForegroundColor DarkGray
