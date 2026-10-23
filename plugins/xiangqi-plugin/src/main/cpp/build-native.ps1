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

# 编译象棋插件 APK 自带的原生引擎 libxiangqi_engine.so（Pikafish 源码 + 宿主同一份 JNI 胶水）。
# 产物直接落到本模块的 jniLibs，Gradle 打包时带走。
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Continue'

$cppDirectory = $PSScriptRoot
# plugins/xiangqi-plugin/src/main/cpp -> 模块根是三层上，仓库根是五层上。
$moduleDirectory = (Resolve-Path -LiteralPath (Join-Path $cppDirectory '..\..\..')).Path
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $cppDirectory '..\..\..\..\..')).Path
$toolchainFile = Join-Path $NdkPath 'build\cmake\android.toolchain.cmake'
$pikafishSource = Join-Path $repositoryRoot 'third-party\Pikafish\src'
$sharedJni = Join-Path $repositoryRoot 'modules\engine\src\main\cpp\pikafish\pikafish_jni.cpp'

foreach ($requiredPath in @($CMakePath, $NinjaPath, $toolchainFile, $pikafishSource, $sharedJni)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Required native build input was not found: $requiredPath"
    }
}

# 引擎只有 arm64-v8a 一份构建。
$abi = 'arm64-v8a'
$buildDirectory = Join-Path $moduleDirectory ".cxx\engine-r27\$abi"
$jniDirectory = Join-Path $moduleDirectory "src\main\jniLibs\$abi"
New-Item -ItemType Directory -Force -Path $buildDirectory, $jniDirectory | Out-Null

$dotProdValue = 'OFF'
if ($DotProd) { $dotProdValue = 'ON' }

& $CMakePath -S $cppDirectory -B $buildDirectory -G Ninja `
    "-DCMAKE_MAKE_PROGRAM=$NinjaPath" `
    "-DCMAKE_TOOLCHAIN_FILE=$toolchainFile" `
    "-DANDROID_ABI=$abi" `
    '-DANDROID_PLATFORM=android-21' `
    '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON' `
    '-DANDROID_STL=c++_static' `
    "-DPIKAFISH_DOTPROD=$dotProdValue" `
    "-DCMAKE_BUILD_TYPE=$Configuration" 2>$null
if ($LASTEXITCODE -ne 0) {
    throw 'Plugin engine CMake configuration failed'
}

& $CMakePath --build $buildDirectory --config $Configuration --parallel 8
if ($LASTEXITCODE -ne 0) {
    throw 'Plugin engine native build failed'
}

$libraryPath = Join-Path $buildDirectory 'libxiangqi_engine.so'
if (-not (Test-Path -LiteralPath $libraryPath -PathType Leaf)) {
    throw "Plugin engine library was not produced: $libraryPath"
}

$targetPath = Join-Path $jniDirectory 'libxiangqi_engine.so'
Copy-Item -LiteralPath $libraryPath -Destination $targetPath -Force
$uciPath = Join-Path $buildDirectory 'libxiangqi_uci.so'
if (-not (Test-Path -LiteralPath $uciPath -PathType Leaf)) {
    throw "Plugin UCI executable was not produced: $uciPath"
}
Copy-Item -LiteralPath $uciPath -Destination (Join-Path $jniDirectory 'libxiangqi_uci.so') -Force
$optimizedPath = Join-Path $buildDirectory 'libxiangqi_uci_dotprod.so'
if (-not (Test-Path -LiteralPath $optimizedPath -PathType Leaf)) {
    throw "Plugin optimized UCI executable was not produced: $optimizedPath"
}
Copy-Item -LiteralPath $optimizedPath -Destination (Join-Path $jniDirectory 'libxiangqi_uci_dotprod.so') -Force
Get-Item -LiteralPath $targetPath | Select-Object FullName, Length, LastWriteTime
