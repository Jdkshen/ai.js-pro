# Offline compile/run checks for the Android-side editor clipboard policy.
# This does not start Gradle, a browser, an emulator, or a device.
$ErrorActionPreference = 'Stop'
$scriptPath = $MyInvocation.MyCommand.Path
if (-not $scriptPath) { $scriptPath = $PSCommandPath }
$root = Split-Path -Parent (Split-Path -Parent $scriptPath)

. (Join-Path $root 'tools\jdk17.ps1') | Out-Null

$sdkLine = Get-Content -LiteralPath (Join-Path $root 'local.properties') -Encoding UTF8 |
    Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
if (-not $sdkLine) { throw 'sdk.dir is missing from local.properties' }
$sdk = ($sdkLine -replace '^sdk\.dir=', '') -replace '/', '\'
$androidJar = Join-Path $sdk 'platforms\android-35\android.jar'
if (-not (Test-Path -LiteralPath $androidJar)) {
    $platform = Get-ChildItem -LiteralPath (Join-Path $sdk 'platforms') -Directory |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $platform) { throw 'No Android platform jars found' }
    $androidJar = Join-Path $platform.FullName 'android.jar'
}

$out = Join-Path $root 'build\ace-clipboard-jvm-test'
$out = [IO.Path]::GetFullPath($out)
$workspacePrefix = [IO.Path]::GetFullPath($root).TrimEnd('\') + '\'
if (-not $out.StartsWith($workspacePrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Test output must stay inside workspace: $out"
}
if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Recurse -Force }
New-Item -ItemType Directory -Path $out | Out-Null

$helper = Join-Path $root 'apps\app\src\main\java\com\jdkshen\aijspro\ui\editor\EditorClipboard.java'
$test = Join-Path $root 'tools\EditorClipboardJvmTest.java'
& javac -Xlint:-options -source 8 -target 8 -cp $androidJar -d $out $helper $test
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp "$out;$androidJar" com.jdkshen.aijspro.ui.editor.EditorClipboardJvmTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
