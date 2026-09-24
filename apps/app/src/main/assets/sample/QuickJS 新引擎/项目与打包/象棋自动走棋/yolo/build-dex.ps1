# Rebuild yolo/dex/tflite_classes_fen_v2.dex from yolo/dex-src.
#
# ASCII-only on purpose: Windows PowerShell 5.1 reads BOM-less .ps1 files as ANSI and
# chokes on Chinese comments (see repo AGENTS.md "踩过的坑"). Keep this file ASCII.
#
# Usage (from anywhere):
#   powershell -File yolo\build-dex.ps1
#   powershell -File yolo\build-dex.ps1 -OutDex C:\tmp\classes.dex
#
# Defaults expect:
#   javac/jar  = JDK 17 on PATH (repo habit: `. .\tools\jdk17.ps1` first)
#   d8         = C:\Android\build-tools\34.0.0\d8.bat
#   android.jar= C:\Android\platforms\android-28\android.jar
#   opencv aar = <repo>\modules\engine\libs\opencv-5.0.0-16kb-page-fix.aar
#                (only needed because TflRunner compiles against org.opencv.core.Mat)
param(
    [string]$OutDex,
    [string]$D8 = "C:\Android\build-tools\34.0.0\d8.bat",
    [string]$AndroidJar = "C:\Android\platforms\android-28\android.jar",
    [string]$OpenCvAar,
    [string]$Javac = "javac",
    [string]$Jar = "jar",
    [int]$MinApi = 21
)

$ErrorActionPreference = "Stop"
$scriptPath = $MyInvocation.MyCommand.Path
$yoloDir = Split-Path -Parent $scriptPath
$projDir = Split-Path -Parent $yoloDir
$srcDir = Join-Path $yoloDir "dex-src"

if (-not (Test-Path (Join-Path $srcDir "com\xq\TflRunner.java"))) {
    throw "dex-src is incomplete: missing com/xq/TflRunner.java (looked in $srcDir)"
}
if (-not $OutDex) {
    $OutDex = Join-Path $yoloDir "dex\tflite_classes_fen_v2.dex"
}
if (-not $OpenCvAar) {
    # repo root = closest ancestor that has gradlew.bat (do not hardcode the walk-up depth)
    $repo = $projDir
    while ($repo -and -not (Test-Path (Join-Path $repo "gradlew.bat"))) {
        $repo = Split-Path -Parent $repo
    }
    if (-not $repo) { throw "repo root (gradlew.bat) not found above $projDir; pass -OpenCvAar" }
    $OpenCvAar = Join-Path $repo "modules\engine\libs\opencv-5.0.0-16kb-page-fix.aar"
}
if (-not (Test-Path $AndroidJar)) { throw "android.jar not found: $AndroidJar" }
if (-not (Test-Path $OpenCvAar)) { throw "OpenCV aar not found: $OpenCvAar (pass -OpenCvAar)" }
if (-not (Test-Path $D8)) { throw "d8 not found: $D8" }

$work = Join-Path ([IO.Path]::GetTempPath()) ("yolo-dex-" + (Get-Random))
$classesDir = Join-Path $work "classes"
$depsDir = Join-Path $work "deps"
New-Item -ItemType Directory -Force -Path $classesDir, $depsDir | Out-Null

Write-Host "[1/4] extract OpenCV classes.jar from the aar"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($OpenCvAar)
try {
    $entry = $zip.Entries | Where-Object { $_.FullName -eq "classes.jar" }
    if (-not $entry) { throw "classes.jar not found inside $OpenCvAar" }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $depsDir "opencv-classes.jar"), $true)
} finally {
    $zip.Dispose()
}
$opencvJar = Join-Path $depsDir "opencv-classes.jar"

Write-Host "[2/4] javac dex-src -> $classesDir"
$sources = Get-ChildItem -Recurse -File -Filter *.java $srcDir | ForEach-Object { $_.FullName }
$javacArgs = @("-source", "8", "-target", "8", "-nowarn", "-encoding", "UTF-8",
    "-cp", ($AndroidJar + ";" + $opencvJar), "-d", $classesDir) + $sources
& $Javac @javacArgs
if ($LASTEXITCODE -ne 0) { throw "javac failed (exit $LASTEXITCODE)" }

Write-Host "[3/4] jar the classes"
$classesJar = Join-Path $work "classes.jar"
& $Jar cf $classesJar -C $classesDir .
if ($LASTEXITCODE -ne 0) { throw "jar failed (exit $LASTEXITCODE)" }

Write-Host "[4/4] d8 -> $OutDex"
& $D8 --min-api $MinApi --lib $AndroidJar --output $work $classesJar
if ($LASTEXITCODE -ne 0) { throw "d8 failed (exit $LASTEXITCODE)" }
$produced = Join-Path $work "classes.dex"
if (-not (Test-Path $produced)) { throw "d8 produced no classes.dex in $work" }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutDex) | Out-Null
Copy-Item $produced $OutDex -Force

$hash = (Get-FileHash $OutDex -Algorithm SHA256).Hash
Write-Host ("OK  " + $OutDex)
Write-Host ("    {0} bytes  sha256={1}" -f (Get-Item $OutDex).Length, $hash)
Remove-Item -Recurse -Force $work
