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
$saved = $env:JAVA_TOOL_OPTIONS
$t0 = Get-Date

Write-Host "`n=== Release Builder ===" -ForegroundColor Yellow

try {
    if (-not $SkipNative) {
        Write-Host "`n[1/4] Native libs" -ForegroundColor Cyan
        & (Join-Path $root 'app\src\main\cpp\build-native.ps1') 2>$null
        if ($LASTEXITCODE) { throw "ImGui failed" }
        & (Join-Path $root 'autojs\src\main\cpp\build-quickjs.ps1') 2>$null
        if ($LASTEXITCODE) { throw "QuickJS failed" }
        Write-Host "  Native OK" -ForegroundColor Green
    }

    Write-Host "`n[2/4] Gradle" -ForegroundColor Cyan
    $cpkgs = @('api','code','comp','file','main','model','parser','processing','tree','util','jvm')
    $jx = ($cpkgs | ForEach-Object { "--add-exports=jdk.compiler/com.sun.tools.javac.$_=ALL-UNNAMED" })
    $env:JAVA_TOOL_OPTIONS = @(
        '--add-opens=java.base/java.util=ALL-UNNAMED',
        '--add-opens=java.base/java.lang=ALL-UNNAMED',
        '--add-opens=java.base/java.io=ALL-UNNAMED'
    ) + $jx -join ' '

    Push-Location $root
    try {
        $gradle = "set JAVA_TOOL_OPTIONS=--add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED && gradlew.bat :app:assembleCommonDebug --no-daemon --max-workers=1"
        if ($ForceClean) {
            $cleanCmd = "set JAVA_TOOL_OPTIONS=--add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED && gradlew.bat :app:clean --no-daemon --max-workers=1"
            cmd /c $cleanCmd 2>$null | Out-Null
        }
        cmd /c $gradle 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed (exit $LASTEXITCODE)" }
    } finally { Pop-Location }
    Write-Host "  Gradle OK" -ForegroundColor Green

    Write-Host "`n[3/4] APK" -ForegroundColor Cyan
    $apkDir = Join-Path $root 'app\build\outputs\apk\common'
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
} finally {
    $env:JAVA_TOOL_OPTIONS = $saved
}
