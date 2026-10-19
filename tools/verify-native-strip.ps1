# Native library strip guard (OpenCV AAR / APK).
# Checks that packaged native libs no longer contain ELF .symtab (unstripped debug
# symbols) and that the R8 API database resource stays excluded from APKs.
# Exit code: 0 = pass, 1 = fail or cannot check.
#
# Usage:
#   .\tools\verify-native-strip.ps1
#   .\tools\verify-native-strip.ps1 -Aar modules\engine\libs\opencv-5.0.0-16kb-page-fix.aar
#   .\tools\verify-native-strip.ps1 -Apk apps\app\build\outputs\apk\miuixCompat\debug\app-miuix-compat-arm64-v8a-debug.apk
[CmdletBinding(DefaultParameterSetName = 'Aar')]
param(
    [Parameter(ParameterSetName = 'Aar', Position = 0)]
    [string]$Aar = 'modules/engine/libs/opencv-5.0.0-16kb-page-fix.aar',

    [Parameter(ParameterSetName = 'Apk', Mandatory = $true)]
    [string]$Apk
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

function Test-HasSymtab([byte[]]$bytes) {
    $needle = [System.Text.Encoding]::ASCII.GetBytes('.symtab')
    $limit = $bytes.Length - $needle.Length
    for ($i = 0; $i -le $limit; $i++) {
        if ($bytes[$i] -ne $needle[0]) { continue }
        $ok = $true
        for ($j = 1; $j -lt $needle.Length; $j++) {
            if ($bytes[$i + $j] -ne $needle[$j]) { $ok = $false; break }
        }
        if ($ok) { return $true }
    }
    return $false
}

function Check-Zip([string]$path, [string]$pattern) {
    if (-not (Test-Path -LiteralPath $path)) {
        Write-Host ("MISSING: " + $path) -ForegroundColor Red
        exit 1
    }
    Write-Host ("Checking " + $path)
    $zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $path).Path)
    $offenders = @()
    $count = 0
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName -notlike $pattern) { continue }
            $count++
            $stream = $entry.Open()
            $ms = New-Object System.IO.MemoryStream
            try { $stream.CopyTo($ms) } finally { $stream.Dispose() }
            $bytes = $ms.ToArray()
            $ms.Dispose()
            $bad = Test-HasSymtab $bytes
            Write-Host ("  " + $entry.FullName + " stored=" + $bytes.Length + " symtab=" + $bad)
            if ($bad) { $offenders += $entry.FullName }
        }
    } finally {
        $zip.Dispose()
    }
    if ($count -eq 0) {
        Write-Host ("FAIL: no native libs matched " + $pattern) -ForegroundColor Red
        exit 1
    }
    if ($offenders.Count -gt 0) {
        Write-Host ("FAIL: " + $offenders.Count + " native lib(s) still contain .symtab:") -ForegroundColor Red
        $offenders | ForEach-Object { Write-Host ("  - " + $_) }
        exit 1
    }
    Write-Host ("PASS: " + $count + " native lib(s) stripped (no .symtab).") -ForegroundColor Green
}

if ($PSCmdlet.ParameterSetName -eq 'Apk') {
    $apkPath = (Resolve-Path -LiteralPath $Apk).Path
    Check-Zip $apkPath 'lib/*/*.so'
    $zip = [System.IO.Compression.ZipFile]::OpenRead($apkPath)
    $ser = $false
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName -eq 'resources/new_api_database.ser') { $ser = $true; break }
        }
    } finally {
        $zip.Dispose()
    }
    if ($ser) {
        Write-Host "FAIL: resources/new_api_database.ser is still packaged (expected via packagingOptions.excludes)." -ForegroundColor Red
        exit 1
    }
    Write-Host "PASS: resources/new_api_database.ser excluded from APK." -ForegroundColor Green
} else {
    Check-Zip $Aar 'jni/*/*.so'
}
exit 0
