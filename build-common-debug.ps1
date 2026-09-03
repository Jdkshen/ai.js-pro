param(
    [switch]$SkipNative,
    [switch]$IncludeInrt
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS

function Get-JavaMajorVersion {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'java'
    $psi.Arguments = '-version'
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $process = [System.Diagnostics.Process]::Start($psi)
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    $text = $stdout + $stderr
    if ($text -match '"(\d+)\.(\d+)') {
        $major = [int]$Matches[1]
        $minor = [int]$Matches[2]
        if ($major -eq 1) {
            return $minor
        }
        return $major
    }
    return 0
}

try {
    if (-not $SkipNative) {
        & (Join-Path $projectRoot 'apps\app\src\main\cpp\build-native.ps1')
        if ($LASTEXITCODE -ne 0) {
            throw "ImGui native build failed with exit code $LASTEXITCODE"
        }
        & (Join-Path $projectRoot 'modules\autojs\src\main\cpp\build-quickjs.ps1')
        if ($LASTEXITCODE -ne 0) {
            throw "QuickJS native build failed with exit code $LASTEXITCODE"
        }
    }

    # Gradle 4.10.2 requires module access flags when running on JDK 9+;
    # JDK 8 does not recognise them and fails to start.
    $javaMajor = Get-JavaMajorVersion
    if ($javaMajor -ge 9) {
        $compilerPackages = @(
            'api', 'code', 'comp', 'file', 'main', 'model',
            'parser', 'processing', 'tree', 'util', 'jvm'
        )
        $compilerExports = $compilerPackages | ForEach-Object {
            "--add-exports=jdk.compiler/com.sun.tools.javac.$_=ALL-UNNAMED"
        }
        $env:JAVA_TOOL_OPTIONS = @(
            '--add-opens=java.base/java.util=ALL-UNNAMED',
            '--add-opens=java.base/java.lang=ALL-UNNAMED',
            '--add-opens=java.base/java.io=ALL-UNNAMED'
            $compilerExports
        ) -join ' '
    }

    Push-Location $projectRoot
    try {
        $gradleTasks = @(':app:assembleCommonDebug')
        if ($IncludeInrt) {
            $gradleTasks += ':inrt:assembleDebug'
        }
        # Gradle 4.10.2 can race while Jetifier transforms the large OpenCV AAR.
        & '.\gradlew.bat' @gradleTasks '--no-daemon' '--max-workers=1'
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
}
