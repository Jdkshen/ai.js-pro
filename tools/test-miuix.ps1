param(
    [string]$Drive = '',
    [string[]]$GradleTasks = @(':app:testMiuixCompatDebugUnitTest', ':app:testMiuixLiteDebugUnitTest')
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$preferredJavaHome = 'C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'
$javaHome = $preferredJavaHome
if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe'))) {
    $javaHome = $env:JAVA_HOME
}
if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe'))) {
    throw "JDK 17 not found: $javaHome"
}
$javaVersionText = & (Join-Path $javaHome 'bin\java.exe') -version 2>&1 | Select-Object -First 1
if ($javaVersionText -notmatch 'version "(?<major>\d+)') {
    throw "Unable to determine Java version from: $javaVersionText"
}
$javaMajor = [int]$Matches.major
if ($javaMajor -eq 1 -and $javaVersionText -match 'version "1\.(?<legacyMajor>\d+)') {
    $javaMajor = [int]$Matches.legacyMajor
}
if ($javaMajor -lt 17) {
    throw "JDK 17 or newer is required; found: $javaVersionText"
}
if (-not $Drive) {
    $Drive = @('S:', 'R:', 'Q:', 'P:') | Where-Object { -not (Test-Path -LiteralPath "$_\") } | Select-Object -First 1
    if (-not $Drive) { throw 'No free temporary drive letter is available (tried S:, R:, Q:, P:).' }
}
if ($Drive -notmatch '^[A-Za-z]:$') { throw 'Drive must look like S:.' }
$Drive = $Drive.ToUpperInvariant()
if (Test-Path -LiteralPath "$Drive\") { throw "Drive is already in use: $Drive" }
if (-not $GradleTasks -or $GradleTasks.Count -eq 0) { throw 'At least one Gradle task is required.' }

$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"
try {
    & subst.exe $Drive $projectRoot
    if ($LASTEXITCODE -ne 0) { throw 'Unable to create temporary ASCII drive mapping.' }
    Push-Location "$Drive\"
    try {
        # Gradle/JDK argument files can misdecode a Chinese checkout path on Windows.
        # Running through a temporary ASCII drive keeps the test runtime classpath valid.
        & '.\gradlew.bat' @GradleTasks '--no-daemon'
        if ($LASTEXITCODE -ne 0) { throw 'Miuix unit tests failed.' }
    } finally {
        Pop-Location
    }
} finally {
    if (Test-Path -LiteralPath "$Drive\") { & subst.exe $Drive /D }
}
