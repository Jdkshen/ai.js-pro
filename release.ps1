<#
.SYNOPSIS
  AI.js Pro one-click build + install.
.PARAMETER SkipNative
  Skip native .so compilation (use when only Java/JS changed).
.PARAMETER Install
  Auto-install APK to connected arm64 device after build.
.PARAMETER ForceClean
  Run Gradle clean before build.
.EXAMPLE
  .\release.ps1 -SkipNative -Install
#>
param([switch]$SkipNative, [switch]$Install, [switch]$ForceClean)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$t0 = Get-Date

Write-Host "`n=== Release Builder ===" -ForegroundColor Yellow

try {
    if (-not $SkipNative) {
        Write-Host "`n[1/4] Native libs" -ForegroundColor Cyan
        & (Join-Path $root 'apps\app\src\main\cpp\build-native.ps1') 2>$null
        if ($LASTEXITCODE) { throw "ImGui failed" }
        & (Join-Path $root 'modules\autojs\src\main\cpp\build-quickjs.ps1') 2>$null
        if ($LASTEXITCODE) { throw "QuickJS failed" }
        Write-Host "  Native OK" -ForegroundColor Green
    }

    Write-Host "`n[2/4] Gradle" -ForegroundColor Cyan

    Push-Location $root
    try {
        $gradle = "gradlew.bat :app:assembleCommonDebug --no-daemon"
        if ($ForceClean) {
            $cleanCmd = "gradlew.bat :app:clean --no-daemon"
            cmd /c $cleanCmd 2>$null | Out-Null
        }
        cmd /c $gradle 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed (exit $LASTEXITCODE)" }
    } finally { Pop-Location }
    Write-Host "  Gradle OK" -ForegroundColor Green

    Write-Host "`n[3/4] APK" -ForegroundColor Cyan
    $apkDir = Join-Path $root 'apps\app\build\outputs\apk\common'
    $apk = Get-ChildItem $apkDir -Recurse -Filter '*arm64*.apk' |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $mb = [math]::Round($apk.Length / 1MB, 1)
    $sha = (Get-FileHash $apk.FullName -Algorithm SHA256).Hash.Substring(0, 16)
    Write-Host "  $($apk.Name)  $mb MB  SHA256:$sha" -ForegroundColor White

    if ($Install) {
        Write-Host "`n[4/4] Install" -ForegroundColor Cyan
        $adb = $null
        foreach ($p in @('C:\Android\platform-tools-2\adb.exe',
                         'C:\Android\platform-tools\adb.exe')) {
            try { & $p version 2>$null | Out-Null; $adb = $p; break } catch {}
        }
        if ($adb) {
            $d = & $adb devices 2>$null | Select-String '^\S+\s+device$'
            if ($d) {
                $s = ($d[0] -split '\s+')[0]
                & $adb -s $s install -r $apk.FullName 2>&1 | Out-Null
                Write-Host "  Installed to $s" -ForegroundColor Green
            }
        }
    }

    $s = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)
    Write-Host "`n=== Done in $s s ===" -ForegroundColor Green
    if (-not $Install) {
        Write-Host "Install: adb install -r `"$($apk.FullName)`"" -ForegroundColor DarkYellow
    }

} catch {
    Write-Host "`nFAILED: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
