param([string]$Drive = 'S:')

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$javaHome = 'C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'
if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe'))) {
    throw "JDK 17 not found: $javaHome"
}
if ($Drive -notmatch '^[A-Z]:$') { throw 'Drive must look like S:.' }
if (Test-Path -LiteralPath "$Drive\") { throw "Drive is already in use: $Drive" }

$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"
try {
    & subst.exe $Drive $projectRoot
    if ($LASTEXITCODE -ne 0) { throw 'Unable to create temporary ASCII drive mapping.' }
    Push-Location "$Drive\"
    try {
        & '.\gradlew.bat' ':app:testMiuixDebugUnitTest'
        if ($LASTEXITCODE -ne 0) { throw 'Miuix unit tests failed.' }
    } finally {
        Pop-Location
    }
} finally {
    if (Test-Path -LiteralPath "$Drive\") { & subst.exe $Drive /D }
}
