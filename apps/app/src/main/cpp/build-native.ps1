[CmdletBinding()]
param(
    [string]$NdkPath = 'D:\VisualStudio\Shared\Android\AndroidNDK\android-ndk-r27c',
    [string]$CMakePath = 'D:\VisualStudio\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe',
    [string]$NinjaPath = 'D:\VisualStudio\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja\ninja.exe',
    [ValidateSet('Debug', 'Release')]
    [string]$Configuration = 'Release'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$cppDirectory = $PSScriptRoot
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $cppDirectory '..\..\..\..\..')).Path
$appDirectory = Join-Path $projectRoot 'apps\app'
$autoJsDirectory = Join-Path $projectRoot 'modules\autojs'
$toolchainFile = Join-Path $NdkPath 'build\cmake\android.toolchain.cmake'

foreach ($requiredPath in @($CMakePath, $NinjaPath, $toolchainFile)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "Required native build tool was not found: $requiredPath"
    }
}

foreach ($abi in @('armeabi-v7a', 'arm64-v8a', 'x86')) {
    $buildDirectory = Join-Path $appDirectory ".cxx\imgui-r27\$abi"
    $jniDirectory = Join-Path $appDirectory "src\main\jniLibs\$abi"
    New-Item -ItemType Directory -Force -Path $buildDirectory, $jniDirectory | Out-Null

    & $CMakePath -S $cppDirectory -B $buildDirectory -G Ninja `
        "-DCMAKE_MAKE_PROGRAM=$NinjaPath" `
        "-DCMAKE_TOOLCHAIN_FILE=$toolchainFile" `
        "-DANDROID_ABI=$abi" `
        '-DANDROID_PLATFORM=android-21' `
        '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON' `
        '-DANDROID_STL=c++_static' `
        "-DCMAKE_BUILD_TYPE=$Configuration"
    if ($LASTEXITCODE -ne 0) {
        throw "CMake configuration failed for $abi"
    }

    & $CMakePath --build $buildDirectory --config $Configuration
    if ($LASTEXITCODE -ne 0) {
        throw "Native build failed for $abi"
    }

    $libraryPath = Join-Path $buildDirectory 'libautojs_imgui.so'
    if (-not (Test-Path -LiteralPath $libraryPath -PathType Leaf)) {
        throw "Native library was not produced for $abi"
    }
    Copy-Item -LiteralPath $libraryPath `
        -Destination (Join-Path $jniDirectory 'libautojs_imgui.so') -Force
}

Get-ChildItem -LiteralPath (Join-Path $appDirectory 'src\main\jniLibs') `
    -Recurse -File -Filter 'libautojs_imgui.so' |
    Select-Object FullName, Length, LastWriteTime
