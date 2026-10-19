# Strip debug symbols from native libraries inside an Android AAR (OpenCV).
#
# Why: AGP only strips native libs for release variants; debug builds ship whatever
# the AAR contains. The OpenCV 5.0.0 16kb-page-fix AAR carries full DWARF + .symtab
# in its .so files (~40 MB uncompressed, ~8.5 MB compressed in the APK). This script
# applies NDK llvm-strip --strip-unneeded to every jni/<abi>/*.so, preserves zip
# entry names/order, and re-validates 16 KB page alignment for arm64.
#
# Usage:
#   .\tools\strip-opencv-aar.ps1
#   .\tools\strip-opencv-aar.ps1 -Aar <in.aar> -OutputAar <out.aar>
#   .\tools\strip-opencv-aar.ps1 -Ndk C:\Android\ndk-r26d
#
# After regenerating the AAR (see docs/guides/SIFT特征匹配示例.md's packaging step),
# run this script again and update the AAR SHA-256 in docs/architecture/项目说明.md.
[CmdletBinding()]
param(
    [string]$Aar = 'modules/engine/libs/opencv-5.0.0-16kb-page-fix.aar',
    [string]$OutputAar = '',
    [string]$Ndk = '',
    [switch]$KeepUnstrippedBackup
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

function Resolve-Tool([string]$preferredNdk, [string]$name) {
    $roots = @()
    if ($preferredNdk) { $roots += $preferredNdk }
    if ($env:ANDROID_NDK_HOME) { $roots += $env:ANDROID_NDK_HOME }
    foreach ($base in @('C:\Android', 'C:\Android\ndk')) {
        if (Test-Path $base) {
            Get-ChildItem $base -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -like 'ndk*' } |
                ForEach-Object { $roots += $_.FullName }
        }
    }
    foreach ($root in $roots) {
        $tool = Join-Path $root ('toolchains\llvm\prebuilt\windows-x86_64\bin\' + $name + '.exe')
        if (Test-Path -LiteralPath $tool) { return $tool }
        $guess = Get-ChildItem (Join-Path $root 'toolchains\llvm\prebuilt') -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path $_.FullName ('bin\' + $name + '.exe') } |
            Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
        if ($guess) { return $guess }
    }
    return $null
}

$strip = Resolve-Tool $Ndk 'llvm-strip'
$readelf = Resolve-Tool $Ndk 'llvm-readelf'
if (-not $strip) { throw 'llvm-strip not found; pass -Ndk <ndk dir>' }

$aarPath = (Resolve-Path -LiteralPath $Aar).Path
if (-not $OutputAar) { $OutputAar = $aarPath }
if ([System.IO.Path]::IsPathRooted($OutputAar)) {
    $outPath = [System.IO.Path]::GetFullPath($OutputAar)
} else {
    $outPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputAar))
}
Write-Host ('AAR      : ' + $aarPath)
Write-Host ('Output   : ' + $outPath)
Write-Host ('llvm-strip: ' + $strip)

$beforeSize = (Get-Item -LiteralPath $aarPath).Length
$beforeSha = (Get-FileHash -LiteralPath $aarPath -Algorithm SHA256).Hash
$work = Join-Path ([System.IO.Path]::GetTempPath()) ('opencv-aar-strip-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
try {
    [System.IO.Compression.ZipFile]::ExtractToDirectory($aarPath, $work)
    $libs = @(Get-ChildItem (Join-Path $work 'jni') -Recurse -Filter '*.so' -ErrorAction SilentlyContinue)
    if ($libs.Count -eq 0) { throw 'no jni/*/*.so entries found in AAR' }
    $rawBefore = 0L
    $rawAfter = 0L
    foreach ($lib in $libs) {
        $rawBefore += $lib.Length
        & $strip --strip-unneeded $lib.FullName 2>$null
        $rawAfter += (Get-Item -LiteralPath $lib.FullName).Length
    }
    Write-Host ('jni/*.so  : ' + $rawBefore + ' -> ' + $rawAfter + ' bytes (saved ' + ($rawBefore - $rawAfter) + ')')

    # Rebuild the AAR with the original entry names/order (backslash-free, forward slashes).
    $tmpAar = Join-Path $work 'rewritten.aar'
    $source = [System.IO.Compression.ZipFile]::OpenRead($aarPath)
    $target = [System.IO.Compression.ZipFile]::Open($tmpAar, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entry in $source.Entries) {
            $newEntry = $target.CreateEntry($entry.FullName, [System.IO.Compression.CompressionLevel]::Optimal)
            if ($entry.FullName.EndsWith('/')) { continue }
            $srcFile = Join-Path $work ($entry.FullName.Replace('/', [System.IO.Path]::DirectorySeparatorChar))
            $in = [System.IO.File]::OpenRead($srcFile)
            $outStream = $newEntry.Open()
            try { $in.CopyTo($outStream) } finally { $in.Dispose(); $outStream.Dispose() }
        }
    } finally {
        $source.Dispose()
        $target.Dispose()
    }

    if ($KeepUnstrippedBackup -and ($outPath -eq $aarPath)) {
        $backup = $aarPath + '.unstripped.aar'
        Copy-Item -LiteralPath $aarPath -Destination $backup -Force
        Write-Host ('backup   : ' + $backup)
    }
    if ($outPath -ne $aarPath) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outPath) | Out-Null
    }
    Copy-Item -LiteralPath $tmpAar -Destination $outPath -Force

    # Validate: no .symtab left, arm64 LOAD p_align stays 0x4000.
    $check = [System.IO.Compression.ZipFile]::OpenRead($outPath)
    $symtabBad = @()
    $alignBad = @()
    try {
        foreach ($entry in $check.Entries) {
            if (-not ($entry.FullName.StartsWith('jni/') -and $entry.FullName.EndsWith('.so'))) { continue }
            $tmpSo = Join-Path $work ((Split-Path $entry.FullName -Leaf))
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $tmpSo, $true)
            $bytes = [System.IO.File]::ReadAllBytes($tmpSo)
            $needle = [System.Text.Encoding]::ASCII.GetBytes('.symtab')
            for ($i = 0; $i -le $bytes.Length - $needle.Length; $i++) {
                if ($bytes[$i] -ne $needle[0]) { continue }
                $ok = $true
                for ($j = 1; $j -lt $needle.Length; $j++) { if ($bytes[$i + $j] -ne $needle[$j]) { $ok = $false; break } }
                if ($ok) { $symtabBad += $entry.FullName; break }
            }
            if ($readelf -and $entry.FullName.StartsWith('jni/arm64-v8a/')) {
                $loads = & $readelf -l $tmpSo 2>$null | Select-String -Pattern '^  LOAD'
                foreach ($line in $loads) {
                    if ($line.Line -notmatch '0x4000$') { $alignBad += ($entry.FullName + ' :: ' + $line.Line.Trim()) }
                }
            }
        }
    } finally {
        $check.Dispose()
    }
    if ($symtabBad.Count -gt 0) { throw ('strip incomplete, .symtab remains: ' + ($symtabBad -join ', ')) }
    if ($alignBad.Count -gt 0) { throw ('arm64 16KB alignment broken: ' + ($alignBad -join '; ')) }

    $afterSize = (Get-Item -LiteralPath $outPath).Length
    $afterSha = (Get-FileHash -LiteralPath $outPath -Algorithm SHA256).Hash
    Write-Host ''
    Write-Host ('AAR size : ' + $beforeSize + ' -> ' + $afterSize + ' (saved ' + ($beforeSize - $afterSize) + ')')
    Write-Host ('SHA-256  : ' + $beforeSha + ' -> ' + $afterSha)
    Write-Host 'OK: all native libs stripped; arm64 16KB alignment preserved.'
    Write-Host 'Remember to update the AAR hash in docs/architecture/项目说明.md after committing.'
} finally {
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
}
