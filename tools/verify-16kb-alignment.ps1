<#
.SYNOPSIS
    16 KB 页对齐守卫：检查 Android arm64 .so 的 PT_LOAD p_align 与 PT_GNU_RELRO 末尾。

.DESCRIPTION
    判定口径（https://developer.android.com/guide/practices/page-sizes）：
      1. 每个 PT_LOAD 的 p_align 必须 >= 0x4000（硬门：系统兼容性弹窗里唯一给出确切结论的项）。
      2. PT_GNU_RELRO 的 (vaddr + memsz) 必须按 0x4000 圆整（严格口径/推荐项）。
         默认只提示；加 -StrictRelro 后，非白名单库不达标按失败处理。

    -AllowList 命中的库只 WARN，不拦住构建：这些库来自上游预编译产物，我们无法重链
    （Flutter 引擎 / OpenCV / ML Kit / androidx.graphics / libc++_shared 等）。
    -OwnedList（自研库：quickjs / aijscrypto / ppocr / pikafish）不达标直接 FAIL，
    优先级高于白名单，因为它们可以在本仓库内重编。

.PARAMETER Apk
    待检查的 APK；脚本用 System.IO.Compression 解出 lib/<Abi>/*.so 到临时目录后逐个检查。

.PARAMETER LibDir
    直接检查一个目录下的 *.so（例如 modules/engine/src/main/jniLibs/arm64-v8a），不递归。

.PARAMETER Abi
    APK 内要检查的 ABI 目录，默认 arm64-v8a（16 KB 页只约束 64 位 ABI）。

.PARAMETER AllowList
    白名单正则（匹配文件名）。命中且不达标时只 WARN（默认值覆盖 Flutter / OpenCV / ML Kit 等）。

.PARAMETER OwnedList
    自研库正则（匹配文件名）。不达标直接 FAIL，优先级高于 AllowList。

.PARAMETER Readelf
    指定 llvm-readelf 路径；默认自动查找 ANDROID_NDK_HOME / local.properties 的 ndk.dir /
    <sdk>/ndk/* / C:\Android\ndk-* / 本仓库构建脚本使用的 NDK 目录。

.PARAMETER StrictRelro
    严格口径：PT_GNU_RELRO 末尾未按 16 KB 圆整时，非白名单库按 FAIL 处理。

.EXAMPLE
    .\tools\verify-16kb-alignment.ps1 -Apk apps\app\build\outputs\apk\miuixCompat\debug\app-miuix-compat-arm64-v8a-debug.apk

.EXAMPLE
    .\tools\verify-16kb-alignment.ps1 -LibDir modules\engine\src\main\jniLibs\arm64-v8a -StrictRelro

.NOTES
    退出码：0 = 通过（可含白名单告警）；1 = 未通过，或脚本无法完成检查（缺 APK / 缺 readelf 等）。
#>
[CmdletBinding(DefaultParameterSetName = 'Apk')]
param(
    [Parameter(Mandatory = $true, ParameterSetName = 'Apk', Position = 0)]
    [string]$Apk,

    [Parameter(Mandatory = $true, ParameterSetName = 'LibDir', Position = 0)]
    [string]$LibDir,

    [string]$Abi = 'arm64-v8a',
    [string]$AllowList = 'libflutter|libapp|libc\+\+_shared|opencv|mlkit|libtbb|graphics',
    [string]$OwnedList = 'quickjs|aijscrypto|ppocr|pikafish',
    [string]$Readelf,
    [switch]$StrictRelro
)

Set-StrictMode -Version Latest
# 注意：不用 Stop —— 原生命令（llvm-readelf）的任何 stderr 都会被当成终止错误。
$ErrorActionPreference = 'Continue'

$scriptFilePath = $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($scriptFilePath)) {
    Write-Output '[FAIL] 无法确定脚本自身路径（请用 -File 方式运行本脚本）。'
    exit 1
}
# tools/verify-16kb-alignment.ps1 -> 仓库根目录
$repositoryRoot = Split-Path -Parent (Split-Path -Parent $scriptFilePath)

function Convert-HexToUInt64([string]$Value) {
    $clean = $Value.Trim()
    if ($clean.StartsWith('0x') -or $clean.StartsWith('0X')) {
        $clean = $clean.Substring(2)
    }
    return [Convert]::ToUInt64($clean, 16)
}

function Resolve-ReadelfPath {
    if (-not [string]::IsNullOrWhiteSpace($Readelf)) {
        $candidate = $Readelf
        if (Test-Path -LiteralPath $candidate -PathType Container) {
            $candidate = Join-Path $candidate 'llvm-readelf.exe'
        }
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            throw "指定的 llvm-readelf 不存在：$Readelf"
        }
        return (Resolve-Path -LiteralPath $candidate).Path
    }

    # 1) 环境变量（ANDROID_NDK_HOME 优先，其次 NDK_ROOT / NDK）
    $ndkDirectories = @()
    foreach ($envName in @('ANDROID_NDK_HOME', 'ANDROID_NDK_ROOT', 'ANDROID_NDK')) {
        $value = [Environment]::GetEnvironmentVariable($envName)
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            $ndkDirectories += $value
        }
    }

    # 2) local.properties 的 ndk.dir / sdk.dir
    $sdkDirectory = $null
    $localProperties = Join-Path $repositoryRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
        foreach ($line in [IO.File]::ReadAllLines($localProperties)) {
            if ($line -match '^\s*ndk\.dir\s*=\s*(.+?)\s*$') {
                $ndkValue = $matches[1] -replace '\\\\', '\' -replace '\\:', ':'
                $ndkDirectories += $ndkValue.Trim()
            }
            elseif ($line -match '^\s*sdk\.dir\s*=\s*(.+?)\s*$') {
                $sdkValue = $matches[1] -replace '\\\\', '\' -replace '\\:', ':'
                $sdkDirectory = $sdkValue.Trim()
            }
        }
    }
    if ([string]::IsNullOrWhiteSpace($sdkDirectory)) {
        foreach ($envName in @('ANDROID_SDK_ROOT', 'ANDROID_HOME')) {
            $value = [Environment]::GetEnvironmentVariable($envName)
            if (-not [string]::IsNullOrWhiteSpace($value)) {
                $sdkDirectory = $value
                break
            }
        }
    }

    # 3) 常见 SDK / 本仓库构建脚本使用的 NDK 根目录
    $searchParents = @()
    if (-not [string]::IsNullOrWhiteSpace($sdkDirectory)) {
        $searchParents += (Join-Path $sdkDirectory 'ndk')
    }
    $searchParents += 'C:\Android\Sdk\ndk'
    $searchParents += 'C:\Android'
    $searchParents += 'D:\VisualStudio\Shared\Android\AndroidNDK'
    if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $searchParents += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\ndk')
    }
    foreach ($parent in $searchParents) {
        if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
            continue
        }
        foreach ($child in @(Get-ChildItem -LiteralPath $parent -Directory -ErrorAction SilentlyContinue)) {
            if ($child.Name -match '(?i)ndk') {
                $ndkDirectories += $child.FullName
            }
        }
    }

    # 版本号从高到低（android-ndk-r27c 优先于 android-ndk-r26d）
    $ordered = @($ndkDirectories | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Sort-Object -Unique | Sort-Object -Descending)
    foreach ($ndkRoot in $ordered) {
        foreach ($relative in @(
                'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe',
                'toolchains\llvm\prebuilt\linux-x86_64\bin\llvm-readelf',
                'toolchains\llvm\prebuilt\darwin-x86_64\bin\llvm-readelf')) {
            $exe = Join-Path $ndkRoot $relative
            if (Test-Path -LiteralPath $exe -PathType Leaf) {
                return $exe
            }
        }
    }

    # 4) 最后看 PATH
    $onPath = Get-Command 'llvm-readelf' -ErrorAction SilentlyContinue
    if ($null -ne $onPath) {
        return $onPath.Source
    }

    throw ('找不到 llvm-readelf。请设置 ANDROID_NDK_HOME、在 local.properties 写 ndk.dir，' +
        '或显式传入 -Readelf <path>。')
}

function Get-ElfLayout([string]$Path, [string]$ReadelfPath) {
    $lines = @(& $ReadelfPath --program-headers --wide $Path 2>$null)
    if ($LASTEXITCODE -ne 0) {
        return $null
    }
    $layout = [pscustomobject]@{
        LoadAligns   = @()
        RelroPresent = $false
        RelroEnd     = [uint64]0
    }
    foreach ($line in $lines) {
        if ($line -notmatch '^\s+(LOAD|GNU_RELRO)\s+') {
            continue
        }
        $tokens = @($line.Trim() -split '\s+')
        if ($tokens.Count -lt 6) {
            continue
        }
        if ($tokens[0] -eq 'LOAD') {
            $layout.LoadAligns += (Convert-HexToUInt64 $tokens[$tokens.Count - 1])
        }
        elseif ($tokens[0] -eq 'GNU_RELRO') {
            $vaddr = Convert-HexToUInt64 $tokens[2]
            $memsz = Convert-HexToUInt64 $tokens[5]
            $layout.RelroEnd = $vaddr + $memsz
            $layout.RelroPresent = $true
        }
    }
    return $layout
}

function Expand-ApkLibraries([string]$ApkPath, [string]$AbiName) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    try {
        $zip = [IO.Compression.ZipFile]::OpenRead($ApkPath)
    }
    catch {
        throw ('无法把 APK 当作 ZIP 打开（文件损坏或不是 APK）：' + $ApkPath + '；' + $_.Exception.Message)
    }
    $temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ('aijspro-16kb-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $temporaryRoot | Out-Null
    try {
        $prefix = 'lib/' + $AbiName + '/'
        $seen = @{}
        foreach ($entry in $zip.Entries) {
            if (-not $entry.FullName.StartsWith($prefix, [StringComparison]::Ordinal)) {
                continue
            }
            $name = $entry.FullName.Substring($prefix.Length)
            if ($name.Length -eq 0 -or $name.Contains('/')) {
                continue
            }
            if (-not $name.EndsWith('.so', [StringComparison]::OrdinalIgnoreCase)) {
                continue
            }
            if ($seen.ContainsKey($name)) {
                continue
            }
            $seen[$name] = $true
            $target = Join-Path $temporaryRoot $name
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
        }
    }
    finally {
        $zip.Dispose()
    }
    return $temporaryRoot
}

$temporaryRoot = $null
$exitCode = 0

try {
    $readelfPath = Resolve-ReadelfPath

    Write-Output '16 KB 页对齐校验（16 KB 只约束 64 位 ABI）'
    Write-Output ('  ABI         : ' + $Abi)
    Write-Output ('  llvm-readelf: ' + $readelfPath)

    $libraryDirectory = $null
    if ($PSCmdlet.ParameterSetName -eq 'Apk') {
        if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
            throw "APK 不存在：$Apk"
        }
        $apkFullPath = (Resolve-Path -LiteralPath $Apk).Path
        Write-Output ('  APK         : ' + $apkFullPath + ' (lib/' + $Abi + '/*.so)')
        $temporaryRoot = Expand-ApkLibraries -ApkPath $apkFullPath -AbiName $Abi
        $libraryDirectory = $temporaryRoot
        if (@(Get-ChildItem -LiteralPath $libraryDirectory -File -Filter '*.so').Count -eq 0) {
            throw ('APK 内没有 lib/' + $Abi + '/*.so（确认这是该 ABI 的拆分包，或换用 -Abi）')
        }
    }
    else {
        if (-not (Test-Path -LiteralPath $LibDir -PathType Container)) {
            throw "LibDir 目录不存在：$LibDir"
        }
        $libraryDirectory = (Resolve-Path -LiteralPath $LibDir).Path
        Write-Output ('  LibDir      : ' + $libraryDirectory + '\*.so')
    }

    $libraries = @(Get-ChildItem -LiteralPath $libraryDirectory -File -Filter '*.so' | Sort-Object Name)
    if ($libraries.Count -eq 0) {
        throw "在 $libraryDirectory 下没有找到 *.so（ABI=$Abi）"
    }

    Write-Output ('  AllowList   : ' + $AllowList + '（命中且不达标只 WARN）')
    Write-Output ('  OwnedList   : ' + $OwnedList + '（不达标 FAIL，优先于白名单）')
    if ($StrictRelro) {
        Write-Output '  StrictRelro : 开（RELRO 末尾未圆整时非白名单库 FAIL）'
    }
    else {
        Write-Output '  StrictRelro : 关（RELRO 末尾只提示，不作为失败）'
    }
    Write-Output ''

    Write-Output ('{0,-3} {1,-42} {2,-20} {3,-26} {4,-6} {5}' -f '#', 'Library', 'LOAD p_align', 'RELRO end (pad)', 'Status', 'Note')
    Write-Output ('-' * 160)

    $index = 0
    $loadFailureCount = 0
    $relroFailureCount = 0
    $warnCount = 0
    $failCount = 0

    foreach ($library in $libraries) {
        $index++
        $name = $library.Name
        $layout = Get-ElfLayout -Path $library.FullName -ReadelfPath $readelfPath
        if ($null -eq $layout) {
            $failCount++
            Write-Output ('{0,-3} {1,-42} {2,-20} {3,-26} {4,-6} {5}' -f $index, $name, '-', 'n/a', 'FAIL', 'llvm-readelf 无法解析该 ELF（文件损坏或不是 ELF）')
            continue
        }

        $isOwned = $false
        $isAllowed = $false
        try {
            $isOwned = [regex]::IsMatch($name, $OwnedList)
            $isAllowed = [regex]::IsMatch($name, $AllowList)
        }
        catch {
            throw ('-AllowList / -OwnedList 不是有效的正则表达式：' + $_.Exception.Message)
        }

        $loadAligns = @($layout.LoadAligns)
        $loadOk = $loadAligns.Count -gt 0
        foreach ($align in $loadAligns) {
            if ($align -lt 0x4000) {
                $loadOk = $false
            }
        }
        if ($loadAligns.Count -eq 0) {
            $loadText = 'n/a'
        }
        else {
            $uniqueAligns = @($loadAligns | Sort-Object -Unique)
            $loadText = (@($uniqueAligns | ForEach-Object { '0x{0:x}' -f $_ }) -join '/')
        }

        $relroEnd = [uint64]0
        $relroPad = [uint64]0
        $relroOk = $true
        if ($layout.RelroPresent) {
            $relroEnd = [uint64]$layout.RelroEnd
            $relroPad = [uint64]((0x4000 - ($relroEnd % 0x4000)) % 0x4000)
            $relroOk = ($relroPad -eq 0)
            $relroText = ('0x{0:x} (pad=0x{1:x})' -f $relroEnd, $relroPad)
        }
        else {
            $relroText = 'n/a (无 PT_GNU_RELRO)'
        }

        $status = 'OK'
        $notes = @()

        if (-not $loadOk) {
            $loadFailureCount++
            $note = 'LOAD p_align=' + $loadText + ' < 0x4000'
            if ($isOwned) {
                $status = 'FAIL'
                $notes += ($note + '（自研库，必须修复）')
            }
            elseif ($isAllowed) {
                if ($status -ne 'FAIL') { $status = 'WARN' }
                $notes += ($note + '（白名单，仅告警）')
            }
            else {
                $status = 'FAIL'
                $notes += ($note + '（不在白名单，阻断）')
            }
        }

        if (-not $relroOk) {
            $relroFailureCount++
            $note = ('RELRO 末尾 0x{0:x} 未按 0x4000 圆整（pad=0x{1:x}）' -f $relroEnd, $relroPad)
            if ($StrictRelro) {
                if ($isOwned -or -not $isAllowed) {
                    $status = 'FAIL'
                    $notes += ($note + '（严格口径：失败）')
                }
                else {
                    if ($status -ne 'FAIL') { $status = 'WARN' }
                    $notes += ($note + '（白名单，仅告警）')
                }
            }
            else {
                $notes += ($note + '（严格口径提示；-StrictRelro 时拦截）')
            }
        }

        if ($status -eq 'FAIL') {
            $failCount++
        }
        elseif ($status -eq 'WARN') {
            $warnCount++
        }

        Write-Output ('{0,-3} {1,-42} {2,-20} {3,-26} {4,-6} {5}' -f $index, $name, $loadText, $relroText, $status, ($notes -join '；'))
    }

    Write-Output ''
    Write-Output ('汇总：共 {0} 个库；LOAD 未对齐 {1} 个；RELRO 末尾未圆整 {2} 个；FAIL {3} 个；WARN {4} 个' -f $libraries.Count, $loadFailureCount, $relroFailureCount, $failCount, $warnCount)

    if ($failCount -gt 0) {
        Write-Output '判定：未通过（退出码 1）。非白名单库的 LOAD 必须 >= 0x4000；自研库需重编（见 docs/reports/2026-10-18_16KB对齐升级评估.md §5 P2）。'
        $exitCode = 1
    }
    else {
        if ($loadFailureCount -gt 0 -or $relroFailureCount -gt 0) {
            Write-Output '判定：通过（退出码 0），但存在白名单/严格口径告警，详见上表 Note 列。'
        }
        else {
            Write-Output '判定：通过（退出码 0），所有库 LOAD 与 RELRO 均满足 16 KB 口径。'
        }
        $exitCode = 0
    }
}
catch {
    Write-Output ''
    Write-Output ('[FAIL] ' + $_.Exception.Message)
    $exitCode = 1
}
finally {
    if (-not [string]::IsNullOrWhiteSpace($temporaryRoot) -and (Test-Path -LiteralPath $temporaryRoot)) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

exit $exitCode
