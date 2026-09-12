<#
.SYNOPSIS
    One command dev update: bump version -> build the Miuix compat debug APK ->
    publish it as a self-hosted update source (update.json + HTTP server).

.DESCRIPTION
    When you change code and want the phone to pick it up through its own
    "check for updates" flow:

        .\tools\dev-update.ps1 -ReleaseNotes "fix: something"

    What it does:
      1. Computes the next versionCode as
             max(repo project-versions.json, last published update.json,
                 versionCode installed on the ADB device) + 1
         so the served manifest is always strictly newer than anything out
         there (equal version codes never trigger an update prompt).
         Override with -VersionCode / -VersionName (default: dev-<code>).
      2. Patches project-versions.json just for the build and restores it right
         after, so the workspace stays clean.
      3. Builds :app:assembleMiuixCompatDebug (skip with -SkipBuild to republish
         the APK from the last build instead).
      4. adb reverse tcp:<Port> tcp:<Port> when a device is connected, so a
         phone whose update source points at http://127.0.0.1:<Port>/update.json
         can reach this machine over USB.
      5. Publishes via tools\serve-updates.ps1 (writes update.json with SHA-256
         and serves the folder over HTTP). This step blocks; press Ctrl+C to
         stop the server when done.

    On the phone: open AI.js Pro -> drawer -> "check for updates"
    (or Settings -> About -> check for updates) -> download -> install.

    Note: the APK only installs over the existing app when both are signed with
    the same key. Debug builds use this machine's debug keystore, so keep
    testing with debug builds.

.PARAMETER VersionCode
    Force a concrete versionCode (default: auto increment as described above).

.PARAMETER VersionName
    Force a concrete versionName (default: dev-<versionCode>).

.PARAMETER ReleaseNotes
    Text stored in update.json and shown in the in-app update dialog.

.PARAMETER Abi
    Which APK split to publish: arm64-v8a (default), armeabi-v7a, x86...

.PARAMETER Port
    HTTP port for the update source (default 8080).

.PARAMETER SkipBuild
    Do not run Gradle; pick up the APK from the previous build.

.PARAMETER NoServe
    Only write update.json; do not start the HTTP server.

    ⚠️ 注意：本开关**只**跳过起服务。**发布动作（写 update.json + 拷贝 APK）照常执行**，
    所以任何一次调用都会改写真实更新源。用假版本号试参数会真的把假版本发出去
    （曾用 -VersionCode 9999 试 -DeviceId，导致更新源变成 9999、手机永远提示有新版本）。
    只验证参数解析/报错路径时：加 -SkipBuild 并给一个临时 -Dir，或干脆别调用本脚本。

.PARAMETER NoReverse
    Do not touch adb reverse even when a device is connected.

.PARAMETER DeviceId
    Target device serial. Leave empty when exactly one device is online (auto-selected);
    with several devices connected you MUST pass it, otherwise the device version is read
    as 0 (dumpsys without -s fails silently) and the next versionCode is computed too low.

.EXAMPLE
    .\tools\dev-update.ps1

.EXAMPLE
    .\tools\dev-update.ps1 -SkipBuild -ReleaseNotes "republish last APK"

.EXAMPLE
    .\tools\dev-update.ps1 -VersionCode 500 -VersionName "1.0.4-test"

.EXAMPLE
    .\tools\dev-update.ps1 -DryRun -ReleaseNotes "x"
    # 只算版本号并打印将要做什么；不构建、不发布、不写任何文件。
    # 验证参数解析或报错路径时用这个，不要用假版本号真的发一版。
#>
param(
    [int]$VersionCode = 0,
    [string]$VersionName = "",
    [string]$ReleaseNotes = "",
    [string]$Abi = "arm64-v8a",
    [int]$Port = 8080,
    [switch]$SkipBuild,
    [switch]$NoServe,
    [switch]$NoReverse,
    # 只算版本号并打印将要做什么，不构建、不发布、不写任何文件。
    # 本脚本的发布动作**不受 -NoServe 保护**：用假版本号试探会真的写进真实更新源。
    [switch]$DryRun,
    # 目标设备序列号。留空时：恰好一台在线设备则自动使用；多台则报错要求显式指定
    # （多设备下不带 -s 会让 dumpsys/reverse 静默失效，进而算错版本号）。
    [string]$DeviceId = ""
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$versionsFile = Join-Path $projectRoot "project-versions.json"
$publishedManifest = Join-Path $projectRoot ".artifacts\updates\update.json"
$apkDir = Join-Path $projectRoot "apps\app\build\outputs\apk\miuixCompat\debug"
$gradleTask = ":app:assembleMiuixCompatDebug"
$packageName = "com.jdkshen.aijspro"

function Get-AdbCommand {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    # adb is not always on PATH (this machine keeps it under the SDK configured in local.properties)
    $localProps = Join-Path $projectRoot 'local.properties'
    if (Test-Path -LiteralPath $localProps) {
        $m = Select-String -LiteralPath $localProps -Pattern '^\s*sdk\.dir\s*=\s*(.+?)\s*$' | Select-Object -First 1
        if ($m) {
            $sdk = $m.Matches[0].Groups[1].Value -replace '\\\\', '\' -replace '\\:', ':'
            $fromSdk = Join-Path $sdk 'platform-tools\adb.exe'
            if (Test-Path -LiteralPath $fromSdk) { return $fromSdk }
        }
    }
    foreach ($candidate in @(
            (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
            'C:\Android\platform-tools\adb.exe',
            'C:\Android\sdk\platform-tools\adb.exe')) {
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    return $null
}

function Invoke-Quiet {
    # Native commands (adb) may print to stderr on harmless paths; keep that
    # from turning into a terminating error under $ErrorActionPreference = 'Stop'.
    param([string]$FilePath, [string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        return (& $FilePath @Arguments 2>$null)
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Resolve-TargetDevice {
    # 多设备时 adb 的 shell/dumpsys/reverse 都必须带 -s，否则：
    #   - dumpsys 报 "more than one device/emulator"，被 Get-DeviceVersionCode 吞掉后
    #     返回 0，于是「手机上已装版本」从基准里消失，把新版本号算小（曾因此发成与
    #     手机已装相同的版本，检查更新直接提示「已是最新」）；
    #   - adb reverse 直接失败（已被 Invoke-Quiet 静默）。
    param([string]$Adb, [string]$Requested)
    if (-not $Adb) { return "" }
    if (-not [string]::IsNullOrWhiteSpace($Requested)) {
        $known = @((Invoke-Quiet $Adb @('devices')) | Where-Object { $_ -match '^\S+\s+device$' })
        if (-not ($known | Where-Object { $_ -match [regex]::Escape($Requested) })) {
            throw "-DeviceId '$Requested' 不在线。在线设备：$($known -join ', ')"
        }
        return $Requested
    }
    $online = @((Invoke-Quiet $Adb @('devices')) | Where-Object { $_ -match '^\S+\s+device$' })
    if ($online.Count -eq 1) { return ($online[0] -split '\s+')[0] }
    if ($online.Count -gt 1) {
        throw ("检测到 $($online.Count) 台设备，无法自动判定目标机（不指定会让版本号算错）。" +
               "请用 -DeviceId 指定，例如 -DeviceId $((($online[0] -split '\s+')[0]))")
    }
    return ""
}

function Get-DeviceVersionCode {
    param([string]$Adb, [string]$Package, [string]$DeviceId)
    if (-not $Adb) { return 0 }
    $arguments = @()
    if (-not [string]::IsNullOrWhiteSpace($DeviceId)) { $arguments += @('-s', $DeviceId) }
    $arguments += @('shell', 'dumpsys', 'package', $Package)
    $dump = ((Invoke-Quiet $Adb $arguments) -join "`n")
    $m = [regex]::Match($dump, 'versionCode=(\d+)')
    if ($m.Success) { return [int]$m.Groups[1].Value }
    return 0
}

Write-Host "== dev-update ==" -ForegroundColor Cyan

# --- 1. decide the next version ---------------------------------------------
$repoVersion = 0
$repoName = ""
if (Test-Path -LiteralPath $versionsFile) {
    $repoJson = Get-Content -LiteralPath $versionsFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $repoVersion = [int]$repoJson.appVersionCode
    $repoName = [string]$repoJson.appVersionName
} else {
    throw "project-versions.json not found at $versionsFile"
}

$publishedVersion = 0
if (Test-Path -LiteralPath $publishedManifest) {
    try {
        $publishedVersion = [int]((Get-Content -LiteralPath $publishedManifest -Raw -Encoding UTF8 | ConvertFrom-Json).versionCode)
    } catch {
        Write-Warning "update.json at $publishedManifest is not readable, ignoring it"
    }
}

$adb = Get-AdbCommand
$resolvedDevice = Resolve-TargetDevice -Adb $adb -Requested $DeviceId
if ($resolvedDevice) { Write-Host "device       : $resolvedDevice" -ForegroundColor DarkGray }
$deviceVersion = Get-DeviceVersionCode -Adb $adb -Package $packageName -DeviceId $resolvedDevice

$baseline = [Math]::Max([Math]::Max($repoVersion, $publishedVersion), $deviceVersion)
$newCode = $VersionCode
if ($newCode -le 0) { $newCode = $baseline + 1 }
$newName = $VersionName
if ([string]::IsNullOrWhiteSpace($newName)) { $newName = "dev-$newCode" }
if ([string]::IsNullOrWhiteSpace($ReleaseNotes)) { $ReleaseNotes = "dev build $newName" }

Write-Host ("version      : {0} (versionCode {1})" -f $newName, $newCode)
Write-Host ("baseline     : repo={0} published={1} device={2}" -f $repoVersion, $publishedVersion, $deviceVersion) -ForegroundColor DarkGray
if ($SkipBuild) {
    Write-Host "skipping the build; the published version is read from the existing APK" -ForegroundColor Yellow
}

if ($DryRun) {
    Write-Host ""
    Write-Host "[DryRun] 到此为止：不构建、不发布、不改 project-versions.json、不写 update.json。" -ForegroundColor Cyan
    Write-Host ("  将要发布 : {0} (versionCode {1})" -f $newName, $newCode) -ForegroundColor Cyan
    Write-Host ("  ABI      : {0}" -f $Abi) -ForegroundColor Cyan
    Write-Host ("  目标目录 : {0}" -f (Join-Path (Split-Path -Parent $PSScriptRoot) '.artifacts\updates')) -ForegroundColor Cyan
    Write-Host ("  设备     : {0}" -f $(if ($resolvedDevice) { $resolvedDevice } else { '(无)' })) -ForegroundColor Cyan
    Write-Host ("  发布说明 : {0}" -f $ReleaseNotes) -ForegroundColor Cyan
    exit 0
}

# --- 2. build ----------------------------------------------------------------
if (-not $SkipBuild) {
    $original = [IO.File]::ReadAllText($versionsFile)
    $patched = $original -replace '"appVersionCode"\s*:\s*\d+', ('"appVersionCode": {0}' -f $newCode)
    $patched = $patched -replace '"appVersionName"\s*:\s*"[^"]*"', ('"appVersionName": "{0}"' -f $newName)
    if ($patched -eq $original) {
        throw "could not patch project-versions.json (unexpected layout)"
    }
    [IO.File]::WriteAllText($versionsFile, $patched)
    Write-Host "project-versions.json patched for this build (restored afterwards)" -ForegroundColor DarkGray
    try {
        Push-Location $projectRoot
        try {
            . (Join-Path $projectRoot 'tools\jdk17.ps1')
            # Gradle writes javac notes ("注: 某些输入文件...") to stderr; under
            # $ErrorActionPreference='Stop' PowerShell turns that into a NativeCommandError.
            $previousEap = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try {
                & '.\gradlew.bat' $gradleTask '--console=plain'
            } finally {
                $ErrorActionPreference = $previousEap
            }
            if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit code $LASTEXITCODE)" }
        } finally {
            Pop-Location
        }
    } finally {
        [IO.File]::WriteAllText($versionsFile, $original)
        Write-Host "project-versions.json restored" -ForegroundColor DarkGray
    }
}

# --- 3. locate the APK -------------------------------------------------------
$apk = $null
if (Test-Path -LiteralPath $apkDir) {
    $all = @(Get-ChildItem -LiteralPath $apkDir -Filter *.apk -File | Sort-Object LastWriteTime -Descending)
    $hit = @($all | Where-Object { $_.Name -like "*$Abi*" } | Select-Object -First 1)
    if ($hit.Count -gt 0) {
        $apk = $hit[0]
    } elseif ($all.Count -gt 0) {
        $apk = $all[0]
        Write-Warning ("no APK matched ABI '{0}', publishing {1} instead" -f $Abi, $apk.Name)
    }
}
if (-not $apk) {
    throw "no APK found under $apkDir; run once without -SkipBuild"
}
Write-Host ("apk          : {0}" -f $apk.Name)

# --- 3.5 port availability ---------------------------------------------------
if (-not $NoServe) {
    $existing = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($existing) {
        $proc = Get-Process -Id $existing.OwningProcess -ErrorAction SilentlyContinue
        $procName = 'unknown'
        if ($proc) { $procName = $proc.ProcessName }
        throw ("port {0} is already in use by PID {1} ({2}); stop it first (likely an older update server) or rerun with -Port 8091" -f $Port, $existing.OwningProcess, $procName)
    }
}

# --- 4. adb reverse (USB loopback) ------------------------------------------
if (-not $NoServe -and -not $NoReverse -and $adb) {
    if ($resolvedDevice) {
        # 必须带 -s：多设备时 adb reverse 会失败（且这里是静默调用）。
        Invoke-Quiet $adb @('-s', $resolvedDevice, 'reverse', "tcp:$Port", "tcp:$Port") | Out-Null
        Write-Host ("adb reverse  : {0} tcp:{1} -> tcp:{1} (phone can use http://127.0.0.1:{1}/update.json)" -f $resolvedDevice, $Port)
    } else {
        Write-Host "adb reverse  : no device connected; use the LAN URL printed below" -ForegroundColor Yellow
    }
}

# --- 5. publish + serve ------------------------------------------------------
Write-Host ""
Write-Host "=== on the phone ===" -ForegroundColor Green
Write-Host "1. open AI.js Pro"
Write-Host "2. drawer -> 'check for updates' (or Settings -> About -> check for updates)"
Write-Host "3. download the update and install it"
Write-Host ""

$serveArgs = @{
    Apk          = $apk.FullName
    ReleaseNotes = $ReleaseNotes
    Port         = $Port
}
if ($NoServe) { $serveArgs.NoServe = $true }
& (Join-Path $PSScriptRoot 'serve-updates.ps1') @serveArgs
