param(
    [switch]$SkipNative,
    [switch]$IncludeInrt
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not $SkipNative) {
    & (Join-Path $projectRoot 'modules\autojs\src\main\cpp\build-quickjs.ps1')
    if ($LASTEXITCODE -ne 0) {
        throw "QuickJS native build failed with exit code $LASTEXITCODE"
    }
}

    Push-Location $projectRoot
    try {
        $gradleTasks = @(':app:assembleCommonDebug')
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
