param(
    [switch]$SkipNative,
    [switch]$IncludeInrt
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS

try {
    if (-not $SkipNative) {
        & (Join-Path $projectRoot 'app\src\main\cpp\build-native.ps1')
        if ($LASTEXITCODE -ne 0) {
            throw "ImGui native build failed with exit code $LASTEXITCODE"
        }
        & (Join-Path $projectRoot 'autojs\src\main\cpp\build-quickjs.ps1')
        if ($LASTEXITCODE -ne 0) {
            throw "QuickJS native build failed with exit code $LASTEXITCODE"
        }
    }

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
