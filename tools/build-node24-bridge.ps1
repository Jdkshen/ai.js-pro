#requires -version 5.1
<#
.SYNOPSIS
Build the Node 24 JNI callback bridge against the pinned bundled libnode.so.

.DESCRIPTION
Configures the Android arm64/API 24 C++ shared Release build with CMake+Ninja,
links NODE24_LIBRARY to the bundled source-engine-upgrade libnode.so, verifies
that library against runtime.json, builds and strips libajsnode24bridge.so.

With -UpdateAssets, only libajsnode24bridge.so is copied into the bundled asset
directory and only its SHA-256 value in runtime.json is replaced. The JSON stays
UTF-8 without BOM. The script never deletes or moves files.

.PARAMETER NodeSourceDir
Node v24.22.0 source tree with src/node.h and deps/v8/include.

.PARAMETER NodeConfigFile
Generated Android arm64 shared config.gypi for the same Node build. Its
node_base.target.mk must be beside it or in its out/ directory.

.PARAMETER NdkRoot
Android NDK root. Defaults to C:\Android\ndk-r26d.

.PARAMETER CMake
CMake executable. Defaults to C:\msys64\mingw64\bin\cmake.exe.

.PARAMETER Ninja
Ninja executable. Defaults to C:\msys64\mingw64\bin\ninja.exe.

.PARAMETER Python
Python interpreter. Defaults to C:\Python314\python.exe.

.PARAMETER BuildDir
Build directory. Defaults to output\node24-bridge\native-build under the repo.

.PARAMETER UpdateAssets
Copy the stripped bridge to the bundled node24 arm64-v8a assets and update only
its hash in runtime.json.

.EXAMPLE
.\tools\build-node24-bridge.ps1 `
  -NodeSourceDir 'C:\...\third_party\node-v24.22.0' `
  -NodeConfigFile 'C:\...\artifacts\node24-candidate\arm64-v8a\config.gypi' `
  -UpdateAssets
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$NodeSourceDir,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$NodeConfigFile,

    [string]$NdkRoot = 'C:\Android\ndk-r26d',
    [string]$CMake = 'C:\msys64\mingw64\bin\cmake.exe',
    [string]$Ninja = 'C:\msys64\mingw64\bin\ninja.exe',
    [string]$Python = 'C:\Python314\python.exe',
    [string]$BuildDir,
    [switch]$UpdateAssets
)

$ErrorActionPreference = 'Stop'
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

function Get-RequiredFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Label
    )
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label file not found: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).ProviderPath
}

function Get-RequiredDirectory {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Label
    )
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "$Label directory not found: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).ProviderPath
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$NativeArguments,
        [Parameter(Mandatory = $true)][string]$Step
    )
    & $FilePath @NativeArguments
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "$Step failed with exit code ${exitCode}: $FilePath"
    }
}

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Assert-Arm64Elf {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Label
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label is missing: $Path"
    }
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -lt 20 -or
        $bytes[0] -ne 0x7F -or $bytes[1] -ne 0x45 -or
        $bytes[2] -ne 0x4C -or $bytes[3] -ne 0x46) {
        throw "$Label is not an ELF file: $Path"
    }
    if ($bytes[4] -ne 2) {
        throw "$Label is not a 64-bit ELF file: $Path"
    }
    $machine = [System.BitConverter]::ToUInt16($bytes, 18)
    if ($machine -ne 0xB7) {
        throw "$Label is not an AArch64 ELF file: $Path"
    }
}

function Get-CacheValue {
    param(
        [Parameter(Mandatory = $true)][string]$CacheText,
        [Parameter(Mandatory = $true)][string]$Key
    )
    $pattern = '(?m)^' + [regex]::Escape($Key) + ':[^=\r\n]+=(.+?)\r?$'
    $match = [regex]::Match($CacheText, $pattern)
    if (-not $match.Success) {
        throw "CMake cache is missing $Key"
    }
    return $match.Groups[1].Value.Trim().Trim('"')
}

$scriptPath = $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($scriptPath)) {
    throw 'Cannot determine the build script path.'
}
$repoRoot = Split-Path -Parent (Split-Path -Parent $scriptPath)
$node24Dir = Join-Path $repoRoot 'modules\engine\src\main\node24'
$assetDir = Join-Path $repoRoot 'modules\engine\src\main\assets\source-engine-upgrade\node24\arm64-v8a'
$cmakeLists = Get-RequiredFile (Join-Path $node24Dir 'CMakeLists.txt') 'Bridge CMakeLists.txt'
$runtimeFile = Get-RequiredFile (Join-Path $assetDir 'runtime.json') 'Bundled runtime.json'
$bundledLibNode = Get-RequiredFile (Join-Path $assetDir 'libnode.so') 'Pinned bundled libnode.so'

$nodeSource = Get-RequiredDirectory $NodeSourceDir 'NodeSourceDir'
$nodeConfig = Get-RequiredFile $NodeConfigFile 'NodeConfigFile'
$ndkDirectory = Get-RequiredDirectory $NdkRoot 'NdkRoot'
$cmakeExe = Get-RequiredFile $CMake 'CMake'
$ninjaExe = Get-RequiredFile $Ninja 'Ninja'
$pythonExe = Get-RequiredFile $Python 'Python'
$toolchainFile = Get-RequiredFile (Join-Path $ndkDirectory 'build\cmake\android.toolchain.cmake') 'NDK CMake toolchain'
$stripExe = Get-RequiredFile (Join-Path $ndkDirectory 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe') 'NDK llvm-strip'

$nodeHeader = Get-RequiredFile (Join-Path $nodeSource 'src\node.h') 'Node node.h'
$v8Header = Get-RequiredFile (Join-Path $nodeSource 'deps\v8\include\v8-version.h') 'V8 version header'
$uvHeader = Get-RequiredFile (Join-Path $nodeSource 'deps\uv\include\uv.h') 'libuv header'
$versionHeader = Get-RequiredFile (Join-Path $nodeSource 'src\node_version.h') 'Node version header'

$versionText = [System.IO.File]::ReadAllText($versionHeader, [System.Text.Encoding]::UTF8)
$expectedVersion = @(24, 22, 0)
$versionNames = @('NODE_MAJOR_VERSION', 'NODE_MINOR_VERSION', 'NODE_PATCH_VERSION')
for ($i = 0; $i -lt $expectedVersion.Count; $i++) {
    $pattern = '(?m)^#define\s+' + $versionNames[$i] + '\s+(\d+)\s*$'
    $match = [regex]::Match($versionText, $pattern)
    if (-not $match.Success -or [int]$match.Groups[1].Value -ne $expectedVersion[$i]) {
        throw "NodeSourceDir must be exactly Node 24.22.0 (expected $($expectedVersion -join '.'))."
    }
}

$nodeConfigDir = Split-Path -Parent $nodeConfig
$targetMakefile = Join-Path $nodeConfigDir 'node_base.target.mk'
if (-not (Test-Path -LiteralPath $targetMakefile -PathType Leaf)) {
    $targetMakefile = Join-Path $nodeConfigDir 'out\node_base.target.mk'
}
$targetMakefile = Get-RequiredFile $targetMakefile 'Generated node_base.target.mk'

$runtimeText = [System.IO.File]::ReadAllText($runtimeFile, [System.Text.Encoding]::UTF8)
try {
    $runtime = $runtimeText | ConvertFrom-Json
} catch {
    throw "Bundled runtime.json is not valid JSON: $($_.Exception.Message)"
}
if ($null -eq $runtime -or $runtime -is [System.Array]) {
    throw 'Bundled runtime.json must be a JSON object.'
}
$runtimeNames = @($runtime.PSObject.Properties.Name)
if ($runtimeNames -notcontains 'libnode.so') {
    throw 'Bundled runtime.json has no libnode.so hash.'
}
if ($runtimeNames -notcontains 'libajsnode24bridge.so' -and $UpdateAssets) {
    throw 'Bundled runtime.json has no libajsnode24bridge.so hash to update.'
}
$runtimeLibNodeHash = [string]$runtime.'libnode.so'
if ($runtimeLibNodeHash -notmatch '^[0-9A-Fa-f]{64}$') {
    throw 'Bundled runtime.json has an invalid libnode.so SHA-256 value.'
}
$bundledLibNodeHash = Get-Sha256 $bundledLibNode
if (-not [string]::Equals($bundledLibNodeHash, $runtimeLibNodeHash, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Bundled libnode.so does not match runtime.json (file=$bundledLibNodeHash, json=$runtimeLibNodeHash)."
}

if ([string]::IsNullOrWhiteSpace($BuildDir)) {
    $buildDirFull = Join-Path $repoRoot 'output\node24-bridge\native-build'
} else {
    $buildDirFull = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($BuildDir)
}
$buildDirFull = [System.IO.Path]::GetFullPath($buildDirFull)
if ([string]::Equals($buildDirFull.TrimEnd('\'), $node24Dir.TrimEnd('\'), [System.StringComparison]::OrdinalIgnoreCase) -or
    [string]::Equals($buildDirFull.TrimEnd('\'), $assetDir.TrimEnd('\'), [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'BuildDir must not be the bridge source directory or the bundled asset directory.'
}
if (Test-Path -LiteralPath $buildDirFull) {
    if (-not (Test-Path -LiteralPath $buildDirFull -PathType Container)) {
        throw "BuildDir exists but is not a directory: $buildDirFull"
    }
} else {
    New-Item -ItemType Directory -Force -Path $buildDirFull | Out-Null
}

Write-Host "Pinned bundled libnode.so SHA-256 verified: $bundledLibNodeHash"
Write-Host "Node source: $nodeSource"
Write-Host "Node config: $nodeConfig"
Write-Host "Build dir: $buildDirFull"

$configureArguments = @(
    '-S', $node24Dir,
    '-B', $buildDirFull,
    '-G', 'Ninja',
    "-DCMAKE_TOOLCHAIN_FILE=$toolchainFile",
    '-DANDROID_ABI=arm64-v8a',
    '-DANDROID_PLATFORM=android-24',
    '-DANDROID_STL=c++_shared',
    '-DCMAKE_BUILD_TYPE=Release',
    "-DCMAKE_MAKE_PROGRAM=$ninjaExe",
    "-DPython3_EXECUTABLE=$pythonExe",
    "-DNODE24_SOURCE_DIR=$nodeSource",
    "-DNODE24_CONFIG_GYPI=$nodeConfig",
    "-DNODE24_LIBRARY=$bundledLibNode"
)
Invoke-Checked -FilePath $cmakeExe -NativeArguments $configureArguments -Step 'CMake configure'

$cacheFile = Join-Path $buildDirFull 'CMakeCache.txt'
$cacheText = [System.IO.File]::ReadAllText($cacheFile, [System.Text.Encoding]::UTF8)
$cachedBuildType = Get-CacheValue -CacheText $cacheText -Key 'CMAKE_BUILD_TYPE'
$cachedAbi = Get-CacheValue -CacheText $cacheText -Key 'ANDROID_ABI'
$cachedPlatform = Get-CacheValue -CacheText $cacheText -Key 'ANDROID_PLATFORM'
$cachedLibrary = Get-CacheValue -CacheText $cacheText -Key 'NODE24_LIBRARY'
$cachedConfig = Get-CacheValue -CacheText $cacheText -Key 'NODE24_CONFIG_GYPI'
if (-not [string]::Equals($cachedBuildType, 'Release', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "CMake cache is not Release: $cachedBuildType"
}
if ($cachedAbi -ne 'arm64-v8a' -or $cachedPlatform -ne 'android-24') {
    throw "CMake cache is not Android arm64-v8a/API 24: $cachedAbi/$cachedPlatform"
}
$cachedLibraryFull = [System.IO.Path]::GetFullPath($cachedLibrary)
if (-not [string]::Equals($cachedLibraryFull.TrimEnd('\'), $bundledLibNode.TrimEnd('\'), [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "CMake cache links an unexpected libnode.so: $cachedLibrary"
}
$cachedConfigFull = [System.IO.Path]::GetFullPath($cachedConfig)
if (-not [string]::Equals($cachedConfigFull.TrimEnd('\'), $nodeConfig.TrimEnd('\'), [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "CMake cache uses an unexpected config.gypi: $cachedConfig"
}

$buildArguments = @(
    '--build', $buildDirFull,
    '--target', 'ajsnode24bridge',
    '--config', 'Release'
)
Invoke-Checked -FilePath $cmakeExe -NativeArguments $buildArguments -Step 'CMake build'

$builtBridge = Join-Path $buildDirFull 'libajsnode24bridge.so'
Assert-Arm64Elf -Path $builtBridge -Label 'Built bridge'

$stripArguments = @('--strip-unneeded', $builtBridge)
Invoke-Checked -FilePath $stripExe -NativeArguments $stripArguments -Step 'llvm-strip'
Assert-Arm64Elf -Path $builtBridge -Label 'Stripped bridge'

$bridgeHash = Get-Sha256 $builtBridge
Write-Host "Bridge built: $builtBridge"
Write-Host "Bridge SHA-256: $bridgeHash"

if ($UpdateAssets) {
    $destination = Join-Path $assetDir 'libajsnode24bridge.so'
    Copy-Item -LiteralPath $builtBridge -Destination $destination -Force
    $copiedHash = Get-Sha256 $destination
    if (-not [string]::Equals($copiedHash, $bridgeHash, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Copied bridge asset does not match the built bridge hash.'
    }

    $bridgePattern = '("libajsnode24bridge\.so"\s*:\s*")(?<hash>[0-9A-Fa-f]{64})(")'
    $bridgeMatches = [regex]::Matches($runtimeText, $bridgePattern)
    if ($bridgeMatches.Count -ne 1) {
        throw 'Bundled runtime.json must contain exactly one libajsnode24bridge.so hash.'
    }
    $hashGroup = $bridgeMatches[0].Groups['hash']
    $updatedRuntimeText = $runtimeText.Substring(0, $hashGroup.Index) + $bridgeHash +
        $runtimeText.Substring($hashGroup.Index + $hashGroup.Length)

    try {
        $updatedRuntime = $updatedRuntimeText | ConvertFrom-Json
    } catch {
        throw "Refusing to write invalid runtime.json: $($_.Exception.Message)"
    }
    if (-not [string]::Equals([string]$updatedRuntime.'libajsnode24bridge.so', $bridgeHash, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'updated runtime.json does not contain the new bridge hash.'
    }
    if (-not [string]::Equals([string]$updatedRuntime.'libnode.so', $runtimeLibNodeHash, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Runtime libnode.so hash changed unexpectedly; refusing to write runtime.json.'
    }

    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($runtimeFile, $updatedRuntimeText, $utf8NoBom)
    $writtenBytes = [System.IO.File]::ReadAllBytes($runtimeFile)
    if ($writtenBytes.Length -ge 3 -and $writtenBytes[0] -eq 0xEF -and $writtenBytes[1] -eq 0xBB -and $writtenBytes[2] -eq 0xBF) {
        throw 'runtime.json was written with a UTF-8 BOM.'
    }
    $finalRuntime = [System.IO.File]::ReadAllText($runtimeFile, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    if (-not [string]::Equals([string]$finalRuntime.'libajsnode24bridge.so', $bridgeHash, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'runtime.json verification failed after writing.'
    }
    Write-Host "Updated bridge asset: $destination"
    Write-Host "Updated runtime.json bridge hash only: $runtimeFile"
}

Write-Host 'Node 24 bridge build completed.'
