<#
.SYNOPSIS
  Build, hash and optionally install an AI.js Pro Miuix APK.
.PARAMETER Variant
  MiuixRelease requires the four AIJSPRO signing environment variables.
.PARAMETER SkipNative
  Reuse checked-in QuickJS native libraries when only Java/Kotlin/resources changed.
.PARAMETER Install
  Install the arm64 APK on one connected device after a successful build.
.PARAMETER ForceClean
  Clean the app module before building.
.EXAMPLE
  .\release.ps1 -Variant MiuixDebug -SkipNative -Install
.EXAMPLE
  $env:AIJSPRO_KEYSTORE='D:\private\aijspro.jks'
  .\release.ps1 -Variant MiuixRelease -SkipNative
#>
param(
    [ValidateSet('MiuixRelease', 'MiuixDebug')]
    [string]$Variant = 'MiuixRelease',
    [switch]$SkipNative,
    [switch]$Install,
    [switch]$ForceClean
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$startedAt = Get-Date
$gradleWrapper = Join-Path $projectRoot 'gradlew.bat'
$releaseBuild = $Variant.EndsWith('Release')

Write-Host '=== AI.js Pro Build ===' -ForegroundColor Yellow
Write-Host "Variant: $Variant" -ForegroundColor White

try {
    if ($releaseBuild) {
        $requiredSigning = @(
            'AIJSPRO_KEYSTORE',
            'AIJSPRO_STORE_PASSWORD',
            'AIJSPRO_KEY_ALIAS',
            'AIJSPRO_KEY_PASSWORD'
        )
        $missingSigning = $requiredSigning | Where-Object {
            [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_))
        }
        if ($missingSigning.Count -gt 0) {
            throw "Missing release signing environment variables: $($missingSigning -join ', ')"
        }
        $keystorePath = [Environment]::GetEnvironmentVariable('AIJSPRO_KEYSTORE')
        if (-not (Test-Path -LiteralPath $keystorePath -PathType Leaf)) {
            throw 'AIJSPRO_KEYSTORE does not point to a readable file.'
        }
    }

    if (-not $SkipNative) {
        Write-Host '[1/4] QuickJS native libraries' -ForegroundColor Cyan
        & (Join-Path $projectRoot 'modules\autojs\src\main\cpp\build-quickjs.ps1')
        if ($LASTEXITCODE -ne 0) { throw "QuickJS build failed with exit code $LASTEXITCODE" }
    } else {
        Write-Host '[1/4] QuickJS native libraries skipped' -ForegroundColor DarkGray
    }

    Write-Host '[2/4] Gradle verification and assembly' -ForegroundColor Cyan
    Push-Location $projectRoot
    try {
        if ($ForceClean) {
            & $gradleWrapper ':app:clean' '--no-daemon'
            if ($LASTEXITCODE -ne 0) { throw "Gradle clean failed with exit code $LASTEXITCODE" }
        }
        & $gradleWrapper ":app:assemble$Variant" '--no-daemon'
        if ($LASTEXITCODE -ne 0) { throw "Gradle assembly failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }

    Write-Host '[3/4] APK identity and SHA-256' -ForegroundColor Cyan
    $buildType = if ($releaseBuild) { 'release' } else { 'debug' }
    $apkDirectory = Join-Path $projectRoot "apps\app\build\outputs\apk\miuix\$buildType"
    $apk = Get-ChildItem -LiteralPath $apkDirectory -Filter "*arm64-v8a-$buildType.apk" -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $apk) { throw "No arm64 APK found under $apkDirectory" }
    $hash = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash
    $sizeMb = [math]::Round($apk.Length / 1MB, 2)
    Write-Host "APK: $($apk.FullName)" -ForegroundColor White
    Write-Host "Size: $sizeMb MB" -ForegroundColor White
    Write-Host "SHA-256: $hash" -ForegroundColor White

    if ($Install) {
        Write-Host '[4/4] Device installation' -ForegroundColor Cyan
        $adbCandidates = @(
            'C:\Android\platform-tools-2\adb.exe',
            'C:\Android\platform-tools\adb.exe'
        )
        $adb = $adbCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
        if ($null -eq $adb) { throw 'adb.exe was not found in the supported local SDK paths.' }
        $devices = & $adb devices | Select-String '^\S+\s+device$'
        if ($devices.Count -ne 1) { throw "Expected exactly one connected device, found $($devices.Count)." }
        $deviceId = ($devices[0].Line -split '\s+')[0]
        & $adb -s $deviceId install -r $apk.FullName
        if ($LASTEXITCODE -ne 0) { throw "APK installation failed with exit code $LASTEXITCODE" }
        Write-Host "Installed to $deviceId" -ForegroundColor Green
    } else {
        Write-Host '[4/4] Installation skipped' -ForegroundColor DarkGray
    }

    $elapsed = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 1)
    Write-Host "=== Completed in $elapsed seconds ===" -ForegroundColor Green
} catch {
    Write-Host "FAILED: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
