param(
    [string]$SdkRoot = 'C:\Android',
    [string]$JavaHome = 'C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot',
    [Parameter(Mandatory = $true)][string]$ZxingCoreJar
)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent (Resolve-Path $MyInvocation.MyCommand.Path).Path
$androidJar = Join-Path $SdkRoot 'platforms\android-35\android.jar'
$d8Path = Join-Path $SdkRoot 'build-tools\34.0.0\d8.bat'
$zxingPath = (Resolve-Path -LiteralPath $ZxingCoreJar).Path
$buildPath = Join-Path ([IO.Path]::GetTempPath()) ('dsh-quickjs-' + [Guid]::NewGuid().ToString('N'))
$classesPath = Join-Path $buildPath 'classes'
New-Item -ItemType Directory -Path $classesPath -Force | Out-Null
& (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -source 8 -target 8 -cp "$androidJar;$zxingPath" -d $classesPath (Join-Path $projectPath 'ClientRuntime.java')
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
$helperJar = Join-Path $buildPath 'helper.jar'
& (Join-Path $JavaHome 'bin\jar.exe') cf $helperJar -C $classesPath .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
# d8.bat uses JAVA_HOME to locate Java.
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JavaHome
    & $d8Path --min-api 26 --lib $androidJar --output (Join-Path $projectPath 'client-runtime.jar') $helperJar $zxingPath
    if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
} finally { $env:JAVA_HOME = $previousJavaHome }
Get-Item -LiteralPath (Join-Path $projectPath 'client-runtime.jar')
