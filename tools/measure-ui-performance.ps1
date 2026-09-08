param(
    [string]$Device = 'cccc62c7',
    [string]$Adb = 'C:\Android\platform-tools-2\adb.exe',
    [int]$Swipes = 12,
    [string]$Package = 'com.jdkshen.aijspro'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) { throw "adb not found: $Adb" }
if ($Swipes -lt 1 -or $Swipes -gt 100) { throw 'Swipes must be between 1 and 100.' }
$savedErrorPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$deviceStateOutput = @(& $Adb -s $Device get-state 2>&1)
$deviceStateExitCode = $LASTEXITCODE
$ErrorActionPreference = $savedErrorPreference
$deviceState = if ($deviceStateOutput.Count -gt 0) { $deviceStateOutput[0].ToString().Trim() } else { '' }
if ($deviceStateExitCode -ne 0 -or $deviceState -ne 'device') {
    throw "Device is not connected or authorized: $Device"
}

& $Adb -s $Device shell dumpsys gfxinfo $Package reset | Out-Null
for ($index = 0; $index -lt $Swipes; $index++) {
    $up = ($index % 2) -eq 0
    $startY = if ($up) { 1850 } else { 650 }
    $endY = if ($up) { 650 } else { 1850 }
    & $Adb -s $Device shell input swipe 540 $startY 540 $endY 280 | Out-Null
}

$report = (& $Adb -s $Device shell dumpsys gfxinfo $Package) -join "`n"
$outputRoot = Join-Path $projectRoot '.artifacts\performance'
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$outputPath = Join-Path $outputRoot ("gfxinfo-{0}-{1}.txt" -f $Device, (Get-Date -Format 'yyyyMMdd-HHmmss'))
$report | Set-Content -LiteralPath $outputPath -Encoding UTF8

foreach ($pattern in @('Total frames rendered:', 'Janky frames:', '50th percentile:', '95th percentile:', '99th percentile:')) {
    $line = $report -split "`n" | Where-Object { $_ -match [regex]::Escape($pattern) } | Select-Object -First 1
    if ($null -ne $line) { Write-Output $line.Trim() }
}
Write-Output "Report: $outputPath"
