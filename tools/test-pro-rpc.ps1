<#
.SYNOPSIS
    javac self-test for the Auto.js Pro ws-rpc protocol core (no Gradle, no device).

.DESCRIPTION
    Compiles the Android-free protocol classes
        ProRpcFrames / ProRpcWebSocket / ProRpcDispatcher
    together with src/test/.../ProRpcProtocolTest.java and runs the JUnit suite
    on the desktop JVM. Output goes to .artifacts/pro-rpc-selftest (git ignored).

    -WithAndroidGlue additionally type-checks the Android side of the channel
    (ProRpcServer / ProRpcSession / ProRpcAuthorizer / ProRpcAuthActivity plus the
    files it integrates with) against android.jar and the outputs of the last
    :app:compileMiuixCompatDebugJavaWithJavac run. It does NOT invoke Gradle; the
    Gradle build itself is the Lead's job.

    JDK 17 is selected through tools/jdk17.ps1 (AGENTS.md: the default JDK is 8).

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File tools\test-pro-rpc.ps1

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File tools\test-pro-rpc.ps1 -WithAndroidGlue
#>
param(
    [switch]$WithAndroidGlue
)

$ErrorActionPreference = 'Stop'

$scriptPath = $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Split-Path -Parent (Split-Path -Parent $scriptPath))).Path
Push-Location $repoRoot
try {
    . (Join-Path $repoRoot 'tools\jdk17.ps1') | Out-Null
    $javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    $workDir = Join-Path $repoRoot '.artifacts\pro-rpc-selftest'
    $classDir = Join-Path $workDir 'classes'
    $libDir = Join-Path $workDir 'libs'
    New-Item -ItemType Directory -Force -Path $classDir, $libDir | Out-Null
    Remove-Item -LiteralPath (Join-Path $classDir 'com') -Recurse -Force -ErrorAction SilentlyContinue

    function Find-GradleJar([string]$group, [string]$pattern) {
        $base = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\$group"
        if (-not (Test-Path -LiteralPath $base)) {
            throw "Gradle cache entry not found: $base (has this project ever been built?)"
        }
        $jar = Get-ChildItem -LiteralPath $base -Recurse -Filter $pattern -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if (-not $jar) {
            throw "No $pattern under $base"
        }
        return $jar.FullName
    }

    function Expand-AarClasses([string]$aarPath, [string]$targetJar) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        if (Test-Path -LiteralPath $targetJar) { Remove-Item -LiteralPath $targetJar -Force }
        $zip = [System.IO.Compression.ZipFile]::OpenRead($aarPath)
        try {
            $entry = $zip.Entries | Where-Object { $_.FullName -eq 'classes.jar' } | Select-Object -First 1
            if (-not $entry) { throw "classes.jar missing in $aarPath" }
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $targetJar, $true)
        } finally {
            $zip.Dispose()
        }
    }

    $pluginDir = Join-Path $repoRoot 'apps\app\src\main\java\com\jdkshen\aijspro\pluginclient'
    $testDir = Join-Path $repoRoot 'apps\app\src\test\java\com\jdkshen\aijspro\pluginclient'

    # ---- 1) protocol core + JUnit suite (always) --------------------------------
    $gson = Find-GradleJar 'com.google.code.gson\gson' 'gson-*.jar'
    $junit = Find-GradleJar 'junit\junit\4.13.2' '*.jar'
    $hamcrest = Find-GradleJar 'org.hamcrest\hamcrest-core\1.3' '*.jar'
    $coreClasspath = @($gson, $junit, $hamcrest) -join ';'
    $coreSources = @(
        (Join-Path $pluginDir 'ProRpcFrames.java'),
        (Join-Path $pluginDir 'ProRpcWebSocket.java'),
        (Join-Path $pluginDir 'ProRpcDispatcher.java'),
        (Join-Path $testDir 'ProRpcProtocolTest.java')
    )

    Write-Host '[1/2] javac: ws-rpc protocol core + ProRpcProtocolTest' -ForegroundColor Cyan
    & $javac --release 8 -encoding UTF-8 -Xlint:all -cp $coreClasspath -d $classDir $coreSources
    if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

    Write-Host '[2/2] JUnit: ProRpcProtocolTest' -ForegroundColor Cyan
    & $java -cp "$classDir;$coreClasspath" org.junit.runner.JUnitCore com.jdkshen.aijspro.pluginclient.ProRpcProtocolTest
    if ($LASTEXITCODE -ne 0) { throw "ProRpcProtocolTest failed with exit code $LASTEXITCODE" }

    if (-not $WithAndroidGlue) {
        Write-Host 'PASS (protocol core). Re-run with -WithAndroidGlue to type-check the Android glue.' -ForegroundColor Green
        return
    }

    # ---- 2) Android glue type check (optional, needs a previous Gradle compile) --
    $androidJar = 'C:\Android\platforms\android-35\android.jar'
    if (-not (Test-Path -LiteralPath $androidJar)) {
        $candidate = Get-ChildItem -LiteralPath 'C:\Android\platforms' -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName 'android.jar' } |
            Where-Object { Test-Path -LiteralPath $_ } |
            Select-Object -First 1
        if (-not $candidate) { throw 'android.jar not found under C:\Android\platforms' }
        $androidJar = $candidate
    }

    $requiredDirs = [ordered]@{
        'app javac classes (miuixCompatDebug)' = 'apps\app\build\intermediates\javac\miuixCompatDebug\compileMiuixCompatDebugJavaWithJavac\classes'
        'app kotlin classes (miuixCompatDebug)' = 'apps\app\build\tmp\kotlin-classes\miuixCompatDebug'
        'engine classes' = 'modules\engine\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes'
        'engine kotlin classes' = 'modules\engine\build\tmp\kotlin-classes\debug'
        'common classes' = 'modules\common\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes'
        'common kotlin classes' = 'modules\common\build\tmp\kotlin-classes\debug'
        'automator classes' = 'modules\automator\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes'
        'automator kotlin classes' = 'modules\automator\build\tmp\kotlin-classes\debug'
    }
    $missing = @()
    foreach ($entry in $requiredDirs.GetEnumerator()) {
        if (-not (Test-Path -LiteralPath (Join-Path $repoRoot $entry.Value))) { $missing += $entry.Key }
    }
    $rJar = 'apps\app\build\intermediates\compile_and_runtime_not_namespaced_r_class_jar\miuixCompatDebug\processMiuixCompatDebugResources\R.jar'
    if (-not (Test-Path -LiteralPath (Join-Path $repoRoot $rJar))) { $missing += 'R.jar' }
    if ($missing.Count -gt 0) {
        throw ("Android glue check needs the outputs of a previous debug build; missing: " +
            ($missing -join ', ') +
            ". Run ':app:compileMiuixCompatDebugJavaWithJavac' once (Lead does the Gradle builds), then retry.")
    }

    $annotation = Find-GradleJar 'androidx.annotation\annotation-jvm\1.7.1' '*.jar'
    $runtimeKotlin = Find-GradleJar 'org.jetbrains.kotlin\kotlin-stdlib' 'kotlin-stdlib-*.jar'
    $rxjava = Find-GradleJar 'io.reactivex.rxjava2\rxjava\2.1.2' '*.jar'
    $okhttp = Find-GradleJar 'com.squareup.okhttp3\okhttp\3.10.0' '*.jar'
    $coreAar = Find-GradleJar 'androidx.core\core\1.13.1' '*.aar'
    $rxAndroidAar = Find-GradleJar 'io.reactivex.rxjava2\rxandroid\2.0.1' '*.aar'
    $androidxCoreJar = Join-Path $libDir 'androidx-core-classes.jar'
    $rxAndroidJar = Join-Path $libDir 'rxandroid-classes.jar'
    Expand-AarClasses $coreAar $androidxCoreJar
    Expand-AarClasses $rxAndroidAar $rxAndroidJar

    $glueParts = @($androidJar, (Join-Path $repoRoot $rJar))
    foreach ($entry in $requiredDirs.GetEnumerator()) {
        $glueParts += (Join-Path $repoRoot $entry.Value)
    }
    $glueParts += @(
        $gson, $runtimeKotlin, $rxjava, $okhttp, $annotation, $androidxCoreJar, $rxAndroidJar
    )
    $glueClasspath = $glueParts -join ';'
    $glueSources = @(
        'ProRpcFrames.java', 'ProRpcWebSocket.java', 'ProRpcDispatcher.java', 'ProRpcSession.java',
        'ProRpcServer.java', 'ProRpcAuthorizer.java', 'ProRpcAuthActivity.java',
        'DebugServerProvider.java', 'DevPluginServer.java', 'DevPluginServerService.java'
    ) | ForEach-Object { Join-Path $pluginDir $_ }

    Write-Host '[3/3] javac: Android glue (type check against android.jar)' -ForegroundColor Cyan
    & $javac --release 8 -encoding UTF-8 -nowarn -proc:none -cp $glueClasspath -d $classDir $glueSources
    if ($LASTEXITCODE -ne 0) { throw "javac (Android glue) failed with exit code $LASTEXITCODE" }

    Write-Host 'PASS (protocol core + Android glue type check).' -ForegroundColor Green
} finally {
    Pop-Location
}
