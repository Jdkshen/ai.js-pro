# 用 PC 上的 aapt2 把工程（project.json 的 androidResources）编译成一份独立的资源包。
#
# 为什么要 PC 编译：Google 只发布 linux/windows/osx(x86_64) 的 aapt2，**没有 arm64/Android 版本**，
# 设备上跑不了 aapt2（Android 10+ 也禁止从 app data 目录 exec）。所以工程 res 在电脑上先编好，
# 产物 apk 推到设备后由引擎用 AssetManager.addAssetPath 加载 —— 这样 ui.R 拿到的是**真 resource id**，
# AnimationUtils.loadAnimation(ctx, id) / theme.applyStyle(id) / resources.getColor(id) 全部原生可用。
#
# 用法：
#   .\tools\build-project-res.ps1 -ProjectDir <工程目录> [-OutDir <输出目录>]
#                                 [-PackageName <包名>] [-Push] [-DeviceId <序列号>]
#
# 产物默认落在 <工程目录>\build\project-res.apk（与 project.json 的 ignore:["build"] 约定一致）。
# 引擎按这个路径查找；找不到就回落到「自研 R 表 + 合成 id」那条老路。
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ProjectDir,
    [string]$OutDir,
    [string]$PackageName = 'com.jdkshen.aijspro',
    [string]$ResDir = 'res',
    [string]$Manifest = 'AndroidManifest.xml',
    [string]$AppApk,
    [switch]$Push,
    [string]$DeviceId,
    [string]$Adb = 'C:\Android\platform-tools-2\adb.exe'
)

$ErrorActionPreference = 'Stop'

function Write-Step($text) { Write-Host "==> $text" }
function Fail($text) { Write-Host "失败：$text" -ForegroundColor Red; exit 1 }

# 只算宿主 APK 里 resources.arsc 条目的 SHA-256（解压后的资源表字节），不哈希整个 APK。
# 引擎侧按同一个摘要判断资源包是否对应当前宿主；APK 大小因签名/压缩/原生库变化时不再误判。
function Get-ApkResourcesSha256([string]$ApkPath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
    $zip = [IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        $entry = $zip.GetEntry('resources.arsc')
        if (-not $entry) { Fail "宿主 APK 里没有 resources.arsc：$ApkPath" }
        $entryStream = $entry.Open()
        try {
            $sha = [Security.Cryptography.SHA256]::Create()
            try {
                $hashBytes = $sha.ComputeHash($entryStream)
            } finally {
                $sha.Dispose()
            }
        } finally {
            $entryStream.Dispose()
        }
        return ([BitConverter]::ToString($hashBytes) -replace '-', '').ToLowerInvariant()
    } finally {
        $zip.Dispose()
    }
}

$projectRoot = (Resolve-Path $ProjectDir).Path
$resRoot = Join-Path $projectRoot $ResDir
if (-not (Test-Path $resRoot)) { Fail "找不到资源目录：$resRoot" }

$manifestFile = Join-Path $projectRoot $Manifest
if (-not (Test-Path $manifestFile)) { Fail "找不到清单文件：$manifestFile" }

# aapt2：优先用最新的 build-tools
$aapt2 = Get-ChildItem 'C:\Android\build-tools\*\aapt2.exe' -ErrorAction SilentlyContinue |
    Sort-Object { [version]($_.Directory.Name) } | Select-Object -Last 1
if (-not $aapt2) { Fail '找不到 aapt2.exe（在 C:\Android\build-tools\*\ 下）' }
$aapt2 = $aapt2.FullName

# framework 资源：用最新的 platform android.jar 作为 -I
$androidJar = Get-ChildItem 'C:\Android\platforms\android-*\android.jar' -ErrorAction SilentlyContinue |
    Sort-Object { [int]($_.Directory.Name -replace 'android-', '') } | Select-Object -Last 1
if (-not $androidJar) { Fail '找不到 android.jar（在 C:\Android\platforms\ 下）' }
$androidJar = $androidJar.FullName

# 宿主 app 的 APK 也要当 -I：工程的 styles.xml 会写 parent="Theme.AppCompat.Light.NoActionBar"、
# 引用 attr/colorPrimary 这些，它们来自 appcompat/material，不在 android.jar 里。
if (-not $AppApk) {
    $preferred = 'apps\app\build\outputs\apk\miuixCompat\debug\app-miuix-compat-arm64-v8a-debug.apk'
    if (Test-Path $preferred) {
        $AppApk = (Resolve-Path $preferred).Path
    } else {
        $found = Get-ChildItem 'apps\app\build\outputs\apk' -Recurse -Filter '*.apk' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime | Select-Object -Last 1
        if (-not $found) { Fail '找不到宿主 APK（先编译一次 app），或用 -AppApk 指定' }
        $AppApk = $found.FullName
    }
}

if (-not $OutDir) { $OutDir = Join-Path $projectRoot 'build' }
$outApk = Join-Path $OutDir 'project-res.apk'

# aapt2 是原生 exe，PowerShell 5.1 把中文参数传给它时会变成乱码
# （报 error: failed to open directory）—— 所以一律在 ASCII 的临时目录里编译，完事再拷回目标路径。
$work = Join-Path ([IO.Path]::GetTempPath()) ("aijspro-res-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path $work | Out-Null
$workRes = Join-Path $work 'res'
$workManifest = Join-Path $work 'AndroidManifest.xml'
$workApk = Join-Path $work 'project-res.apk'
$compiledZip = Join-Path $work 'compiled.zip'

Write-Step "aapt2      : $aapt2"
Write-Step "android.jar: $androidJar"
Write-Step "宿主 APK   : $AppApk"
Write-Step "res        : $resRoot"
Write-Step "输出       : $outApk"
Write-Step "包名       : $PackageName"

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Copy-Item -Recurse -Force $resRoot $workRes

# --- 工程 res 预处理：aapt2 比 JS 引擎严格，下面两类写法在 JS 兼容路径能跑，但 aapt2 直接失败 ---
# 1) 裸数字尺寸（android:layout_marginTop="12"）—— Auto.js 语义是 dp，补上单位；
# 2) ?attr/xxx 引用了宿主资源表里没有的 attr（R8 资源收缩会删掉未用 attr）——
#    能映射到同族 attr 就替换，否则删掉该属性，并逐条打印。
Write-Step '预处理工程 res（裸数字尺寸 / 缺失 attr）'
$dimensionAttrs = @(
    'layout_margin','layout_marginTop','layout_marginBottom','layout_marginLeft','layout_marginRight',
    'layout_marginStart','layout_marginEnd','layout_width','layout_height',
    'padding','paddingTop','paddingBottom','paddingLeft','paddingRight','paddingStart','paddingEnd',
    'textSize','minWidth','minHeight','maxWidth','maxHeight','elevation',
    'cardCornerRadius','cardElevation','itemIconSize','iconSize','lineSpacingExtra','letterSpacing'
)
$attrAliases = @{
    'materialButtonTonalStyle' = 'materialButtonOutlinedStyle'
}
$hostAttrs = New-Object 'System.Collections.Generic.HashSet[string]'
try {
    $dump = & $aapt2 dump resources $AppApk 2>$null
    foreach ($line in $dump) {
        foreach ($m in [regex]::Matches($line, 'attr/([A-Za-z0-9_]+)')) { [void]$hostAttrs.Add($m.Groups[1].Value) }
    }
} catch { }
$script:bareFixed = 0
$script:attrFixed = 0
$script:attrDropped = 0
foreach ($f in (Get-ChildItem -Path $workRes -Recurse -Include *.xml -File)) {
    $text = [IO.File]::ReadAllText($f.FullName)
    $original = $text
    $dimPattern = '(android:[A-Za-z_]+)="(-?\d+(?:\.\d+)?)"'
    $text = [regex]::Replace($text, $dimPattern, {
        param($m)
        $name = $m.Groups[1].Value.Substring('android:'.Length)
        if ($dimensionAttrs -contains $name) {
            $script:bareFixed++
            return $m.Groups[1].Value + '="' + $m.Groups[2].Value + 'dp"'
        }
        return $m.Value
    })
    if ($hostAttrs.Count -gt 0 -and $text -match '\?attr/') {
        $text = [regex]::Replace($text, '\?attr/([A-Za-z0-9_]+)', {
            param($m)
            $name = $m.Groups[1].Value
            if ($hostAttrs.Contains($name)) { return $m.Value }
            if ($attrAliases.ContainsKey($name) -and $hostAttrs.Contains($attrAliases[$name])) {
                $script:attrFixed++
                Write-Host ("  替换 ?attr/" + $name + " -> ?attr/" + $attrAliases[$name] + "  (" + $f.Name + ")")
                return '?attr/' + $attrAliases[$name]
            }
            $script:attrDropped++
            Write-Host ("  删除宿主不支持的 ?attr/" + $name + "  (" + $f.Name + ")")
            return '__AJSPRO_DROP_ATTR__'
        })
        $text = [regex]::Replace($text, '\s+[A-Za-z0-9_:]+="__AJSPRO_DROP_ATTR__"', '')
    }
    if ($text -ne $original) {
        [IO.File]::WriteAllText($f.FullName, $text, (New-Object Text.UTF8Encoding($false)))
    }
}
if ($bareFixed -gt 0) { Write-Host ("  裸数字尺寸补 dp：" + $bareFixed + " 处") }
if ($attrFixed -gt 0) { Write-Host ("  attr 映射替换：" + $attrFixed + " 处") }
if ($attrDropped -gt 0) { Write-Host ("  attr 删除：" + $attrDropped + " 处（如外观不符请改用宿主存在的 style/属性）") }

# 清单里的 package 决定资源编到哪个包里。Pro 的示例写死了 org.autojs.autojspro，对我们没意义：
# 统一改成宿主 app 的包名，这样引擎侧所有 getIdentifier(name, type, getPackageName()) 直接命中，
# 且与其同包 id(0x7f) 时行为等价于「叠加」—— 只有同名资源才会被覆盖。
# （注意：--rename-manifest-package 只改产物清单，不改资源表里的包名，改不出这个效果。）
$manifestText = [IO.File]::ReadAllText($manifestFile)
if ($manifestText -match 'package\s*=') {
    $manifestText = $manifestText -replace 'package\s*=\s*"[^"]*"', "package=`"$PackageName`""
} else {
    $manifestText = $manifestText -replace '<manifest', "<manifest package=`"$PackageName`""
}
[IO.File]::WriteAllText($workManifest, $manifestText, (New-Object Text.UTF8Encoding($false)))

try {
    Write-Step 'aapt2 compile'
    & $aapt2 compile --dir $workRes -o $compiledZip
    if ($LASTEXITCODE -ne 0) { Fail "aapt2 compile 退出码 $LASTEXITCODE" }

    Write-Step 'aapt2 link'
    # --package-id 0x7e：与宿主 0x7f 分开，且保持正 int，避免 View.findViewById 拒绝负数 id。
    #   资源包与 app 同包 id 时，按名字查得到、按 id 查不到（framework 按包 id 解析时只会落到 app 那个包，
    #   实测 getDrawable(0x7f030001) 直接 NotFoundException）—— 而 framework 内部全是按 id 查的。
    #   引擎里的按名字查找已改成跨包搜索（getIdentifier(name, type, "")），所以包名是啥都无所谓。
    & $aapt2 link -o $workApk -I $androidJar -I $AppApk --manifest $workManifest `
        --package-id 0x7e --allow-reserved-package-id `
        --min-sdk-version 21 --target-sdk-version 28 `
        $compiledZip
    if ($LASTEXITCODE -ne 0) { Fail "aapt2 link 退出码 $LASTEXITCODE" }

    Copy-Item -Force $workApk $outApk
} finally {
    $cleanupTarget = [IO.Path]::GetFullPath($work)
    $cleanupParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $cleanupTarget.StartsWith($cleanupParent, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($cleanupTarget) -notmatch '^aijspro-res-[0-9a-f]{8}$') {
        throw "拒绝清理非预期临时目录：$cleanupTarget"
    }
    Remove-Item -LiteralPath $cleanupTarget -Recurse -Force -ErrorAction SilentlyContinue
}

$size = (Get-Item $outApk).Length
Write-Step "完成：$outApk（$size 字节）"

$sha = (Get-FileHash $outApk -Algorithm SHA256).Hash
Write-Host "SHA256: $sha"

# 旁边写一份 sidecar：引擎靠它判断资源包是不是给当前这版宿主编的
# （aapt2 的 -I 会把 app 侧的资源 id 一起编进引用里，宿主换版本后 id 可能漂移），
# 以及资源包在资源表里的**包名**（独立包 id 时 aapt2 会命名成 <app 包名>.feature，
# 按名字查资源要连它一起试 —— 见引擎里的 ResIds）。
# appResourcesSha256 只覆盖 resources.arsc：它才是资源 id 的来源；APK 其它字节变了不影响。
$dump = & $aapt2 dump resources $outApk 2>$null | Select-String -Pattern 'Package name=(\S+)\s+id='
if ($dump -and $dump.ToString() -match 'Package name=(\S+)\s+id=') {
    $resourcePackageName = $matches[1]
} else {
    $resourcePackageName = "$PackageName.feature"
}
$appResourcesSha256 = Get-ApkResourcesSha256 $AppApk
Write-Step "宿主 resources.arsc SHA256：$appResourcesSha256"
$sidecar = Join-Path $OutDir 'project-res.json'
$sidecarJson = [ordered]@{
    packageName         = $PackageName
    resourcePackageName = $resourcePackageName
    appApk              = $AppApk
    appApkSize          = (Get-Item $AppApk).Length
    appResourcesSha256  = $appResourcesSha256
    generatedAt         = (Get-Date).ToString('s')
} | ConvertTo-Json
[IO.File]::WriteAllText($sidecar, $sidecarJson, (New-Object Text.UTF8Encoding($false)))
Write-Step "已写 sidecar：$sidecar（资源包名 $resourcePackageName）"

if ($Push) {
    if (-not $DeviceId) {
        $devices = (& $Adb devices) | Select-String -Pattern '^\S+\s+device$'
        if ($devices.Count -ne 1) { Fail "Push 需要 -DeviceId（当前识别到 $($devices.Count) 台设备）" }
        $DeviceId = ($devices[0] -split '\s+')[0]
    }
    Write-Step "推送到 $DeviceId"
    # 推到工程在设备上的同名位置：脚本目录/<工程目录名>/build/
    $remoteDir = "/storage/emulated/0/脚本/$([IO.Path]::GetFileName($projectRoot))/build"
    & $Adb -s $DeviceId shell "mkdir -p '$remoteDir'"
    & $Adb -s $DeviceId push $outApk "$remoteDir/project-res.apk"
    & $Adb -s $DeviceId push $sidecar "$remoteDir/project-res.json"
    Write-Step "已推送：$remoteDir/"
}
