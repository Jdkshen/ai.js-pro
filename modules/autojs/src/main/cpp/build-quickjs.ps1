[CmdletBinding()]
param(
    [string]$NdkPath = 'D:\VisualStudio\Shared\Android\AndroidNDK\android-ndk-r27c',
    [string]$CMakePath = 'D:\VisualStudio\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe',
    [string]$NinjaPath = 'D:\VisualStudio\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja\ninja.exe',
    [ValidateSet('Debug', 'Release')]
    [string]$Configuration = 'Release'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Continue'

$cppDirectory = $PSScriptRoot
$autoJsDirectory = (Resolve-Path -LiteralPath (Join-Path $cppDirectory '..\..\..')).Path
$toolchainFile = Join-Path $NdkPath 'build\cmake\android.toolchain.cmake'
$openCvAar = Join-Path $autoJsDirectory 'libs\opencv-5.0.0-16kb-page-fix.aar'

foreach ($requiredPath in @($CMakePath, $NinjaPath, $toolchainFile, $openCvAar)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "Required native build tool was not found: $requiredPath"
    }
}

foreach ($abi in @('armeabi-v7a', 'arm64-v8a', 'x86')) {
    $buildDirectory = Join-Path $autoJsDirectory ".cxx\quickjs-r27\$abi"
    $jniDirectory = Join-Path $autoJsDirectory "src\main\jniLibs\$abi"
    $openCvLinkDirectory = Join-Path $buildDirectory 'opencv-link'
    New-Item -ItemType Directory -Force -Path $buildDirectory, $jniDirectory, $openCvLinkDirectory | Out-Null
    # arm64 packages OpenCV as several shared libraries, while older ABIs use
    # the monolithic libopencv_java5.so. Extract the complete ABI directory so
    # CMake can link whichever layout the AAR actually provides.
    tar -xf $openCvAar -C $openCvLinkDirectory "jni/$abi"
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to extract the OpenCV link library for $abi"
    }
    $openCvLibrary = Join-Path $openCvLinkDirectory "jni\$abi\libopencv_java5.so"

    & $CMakePath -S $cppDirectory -B $buildDirectory -G Ninja `
        "-DCMAKE_MAKE_PROGRAM=$NinjaPath" `
        "-DCMAKE_TOOLCHAIN_FILE=$toolchainFile" `
        "-DANDROID_ABI=$abi" `
        '-DANDROID_PLATFORM=android-21' `
        '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON' `
        '-DANDROID_STL=c++_static' `
        "-DOPENCV_JAVA_LIBRARY=$openCvLibrary" `
        "-DCMAKE_BUILD_TYPE=$Configuration" 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "QuickJS CMake configuration failed for $abi"
    }

    & $CMakePath --build $buildDirectory --config $Configuration 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "QuickJS native build failed for $abi"
    }

    foreach ($library in @('libquickjs.so', 'libquickjs_jni.so')) {
        $libraryPath = Join-Path $buildDirectory $library
        if (-not (Test-Path -LiteralPath $libraryPath -PathType Leaf)) {
            throw "QuickJS native library was not produced for $abi`: $library"
        }
        Copy-Item -LiteralPath $libraryPath -Destination (Join-Path $jniDirectory $library) -Force
    }
}

Get-ChildItem -LiteralPath (Join-Path $autoJsDirectory 'src\main\jniLibs') `
    -Recurse -File -Include 'libquickjs.so', 'libquickjs_jni.so' |
    Select-Object FullName, Length, LastWriteTime
