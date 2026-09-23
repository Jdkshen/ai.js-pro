[CmdletBinding()]
param(
    [string]$NdkPath = 'D:\VisualStudio\Shared\Android\AndroidNDK\android-ndk-r27c',
    [string]$CMakePath = 'D:\VisualStudio\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe',
    [string]$NinjaPath = 'D:\VisualStudio\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja\ninja.exe',
    [ValidateSet('Debug', 'Release')]
    [string]$Configuration = 'Release',
    # Cortex-A76/Snapdragon 855+ only; default off so older devices can also load the .so.
    [switch]$DotProd
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Continue'

$cppDirectory = $PSScriptRoot
# modules/engine/src/main/cpp -> repo root is five levels up; the Gradle module root is three up.
$engineDirectory = (Resolve-Path -LiteralPath (Join-Path $cppDirectory '..\..\..')).Path
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $cppDirectory '..\..\..\..\..')).Path
$toolchainFile = Join-Path $NdkPath 'build\cmake\android.toolchain.cmake'
$pikafishSource = Join-Path $repositoryRoot 'third-party\Pikafish\src'

foreach ($requiredPath in @($CMakePath, $NinjaPath, $toolchainFile, $pikafishSource)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Required native build input was not found: $requiredPath"
    }
}

# Pikafish only ships for arm64-v8a (the only ABI the vendored NNUE path is used on).
$abi = 'arm64-v8a'
$buildDirectory = Join-Path $engineDirectory ".cxx\pikafish-r27\$abi"
$jniDirectory = Join-Path $engineDirectory "src\main\jniLibs\$abi"
New-Item -ItemType Directory -Force -Path $buildDirectory, $jniDirectory | Out-Null

$dotProdValue = 'OFF'
if ($DotProd) { $dotProdValue = 'ON' }

& $CMakePath -S (Join-Path $cppDirectory 'pikafish') -B $buildDirectory -G Ninja `
    "-DCMAKE_MAKE_PROGRAM=$NinjaPath" `
    "-DCMAKE_TOOLCHAIN_FILE=$toolchainFile" `
    "-DANDROID_ABI=$abi" `
    '-DANDROID_PLATFORM=android-21' `
    '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON' `
    '-DANDROID_STL=c++_static' `
    "-DPIKAFISH_DOTPROD=$dotProdValue" `
    "-DCMAKE_BUILD_TYPE=$Configuration" 2>$null
if ($LASTEXITCODE -ne 0) {
    throw 'Pikafish CMake configuration failed'
}

& $CMakePath --build $buildDirectory --config $Configuration 2>$null
if ($LASTEXITCODE -ne 0) {
    throw 'Pikafish native build failed'
}

$libraryPath = Join-Path $buildDirectory 'libpikafish.so'
if (-not (Test-Path -LiteralPath $libraryPath -PathType Leaf)) {
    throw "Pikafish native library was not produced: $libraryPath"
}

$targetPath = Join-Path $jniDirectory 'libpikafish.so'
Copy-Item -LiteralPath $libraryPath -Destination $targetPath -Force
Get-Item -LiteralPath $targetPath | Select-Object FullName, Length, LastWriteTime
