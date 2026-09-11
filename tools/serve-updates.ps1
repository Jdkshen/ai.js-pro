<#
.SYNOPSIS
    Publish one or more APKs as a self-hosted update source (update.json + HTTP server).

.DESCRIPTION
    Copies (or hard-links) the APKs into a folder, writes update.json next to them
    with SHA-256 and size, then serves that folder over HTTP so any phone on the
    same network can either read the manifest (AI.js Pro -> Settings -> Update
    source) or just download an APK directly.

    Passing several APKs (e.g. one per ABI) publishes them as "assets"; the app
    then picks the one matching its own variant (compat/lite) and the device ABI,
    exactly like it does for GitHub release assets.

    Manifest layout (com.jdkshen.aijspro.network.entity.UpdateManifest):

        {
          "versionCode": 466,
          "versionName": "1.0.3",
          "releaseNotes": "...",
          "apkUrl": "./app-miuix-compat-arm64-v8a-debug.apk",
          "apkSha256": "sha256:...",
          "assets": [
            { "name": "...arm64-v8a....apk", "url": "./...", "abi": "arm64-v8a", "sha256": "..." }
          ]
        }

    Notes:
      * The phone must use the same signing key as the served APK, otherwise the
        in-app updater refuses to install it (that check is intentional).
      * Keep this file ASCII-only: Windows PowerShell 5.1 reads -File scripts as
        ANSI, so non-ASCII text in the script itself can break parsing.

.EXAMPLE
    .\tools\serve-updates.ps1 -Apk apps\app\build\outputs\apk\miuixCompat\debug\*.apk -HardLink

.EXAMPLE
    # Only generate the manifest (no server), e.g. to upload with scp/rsync:
    .\tools\serve-updates.ps1 -Apk .\a.apk -NoServe
#>
param(
    [Parameter(Mandatory = $true)][string[]]$Apk,
    [string]$Dir = "$PSScriptRoot\..\.artifacts\updates",
    [int]$Port = 8080,
    [string]$ReleaseNotes = "",
    [int]$VersionCode = 0,
    [string]$VersionName = "",
    [string]$SourceName = "aijspro",
    [switch]$HardLink,
    [switch]$NoServe
)

$ErrorActionPreference = "Stop"

$Apk = $Apk | ForEach-Object {
    $item = Get-Item -LiteralPath $_ -ErrorAction SilentlyContinue
    if ($item) { $item } else { Get-ChildItem -Path $_ -File -ErrorAction SilentlyContinue }
} | ForEach-Object { $_.FullName } | Select-Object -Unique
if (-not $Apk) {
    throw "No APK matched; pass -Apk <path or wildcard>"
}

# --- version metadata -------------------------------------------------------
if ($VersionCode -le 0 -or [string]::IsNullOrWhiteSpace($VersionName)) {
    $aapt = Get-ChildItem "C:\Android\build-tools\*\aapt.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($aapt) {
        $badging = & $aapt.FullName dump badging $Apk[0] 2>$null
        $line = ($badging | Select-String -Pattern "^package:" | Select-Object -First 1).Line
        if ($line) {
            if ($VersionCode -le 0 -and $line -match "versionCode='(\d+)'") { $VersionCode = [int]$Matches[1] }
            if ([string]::IsNullOrWhiteSpace($VersionName) -and $line -match "versionName='([^']+)'") { $VersionName = $Matches[1] }
        }
    }
}
if ($VersionCode -le 0 -or [string]::IsNullOrWhiteSpace($VersionName)) {
    $versionsFile = Join-Path $PSScriptRoot "..\project-versions.json"
    if (Test-Path -LiteralPath $versionsFile) {
        $versions = (Get-Content -LiteralPath $versionsFile -Raw | ConvertFrom-Json)
        if ($VersionCode -le 0) { $VersionCode = [int]$versions.appVersionCode }
        if ([string]::IsNullOrWhiteSpace($VersionName)) { $VersionName = [string]$versions.appVersionName }
    }
}
if ($VersionCode -le 0 -or [string]::IsNullOrWhiteSpace($VersionName)) {
    throw "Cannot detect versionCode/versionName; pass -VersionCode and -VersionName explicitly."
}

# --- publish ---------------------------------------------------------------
New-Item -ItemType Directory -Force -Path $Dir | Out-Null
$Dir = (Resolve-Path -LiteralPath $Dir).Path
$assets = @()
foreach ($source in $Apk) {
    $fileName = Split-Path -Leaf $source
    $target = Join-Path $Dir $fileName
    if ($target -eq $source) {
        # Already the published file (re-publishing a folder in place): never delete the source.
    } else {
        if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Force }
        if ($HardLink) {
            New-Item -ItemType HardLink -Path $target -Target $source | Out-Null
        } else {
            Copy-Item -LiteralPath $source -Destination $target -Force
        }
    }
    $hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
    $size = (Get-Item -LiteralPath $target).Length
    $abi = ""
    if ($fileName -match "(arm64-v8a|armeabi-v7a|x86_64|x86)") { $abi = $Matches[1] }
    $assets += [ordered]@{
        name   = $fileName
        url    = "./$fileName"
        abi    = $abi
        sha256 = "sha256:$hash"
        size   = $size
    }
    $abiLabel = if ($abi) { $abi } else { "any" }
    Write-Host ("Published: {0}  ({1} bytes)  abi={2}" -f $fileName, $size, $abiLabel)
}

# Prefer arm64 as "apkUrl" (the app falls back to it when assets are absent).
$preferred = $assets | Where-Object { $_.name -match "arm64-v8a" } | Select-Object -First 1
if (-not $preferred) { $preferred = $assets[0] }
if ([string]::IsNullOrWhiteSpace($ReleaseNotes)) {
    $ReleaseNotes = "AI.js Pro $VersionName (versionCode $VersionCode)"
}

$manifest = [ordered]@{
    versionCode  = $VersionCode
    versionName  = $VersionName
    releaseNotes = $ReleaseNotes
    apkUrl       = $preferred.url
    apkSha256    = $preferred.sha256
    apkSize      = $preferred.size
    deprecated   = 0
    assets       = $assets
}
$manifestPath = Join-Path $Dir "update.json"
# Write without a BOM: Windows PowerShell 5.1's -Encoding UTF8 adds one, and a
# leading U+FEFF makes strict JSON parsers (and older clients) unhappy.
$json = $manifest | ConvertTo-Json -Depth 5
[System.IO.File]::WriteAllText($manifestPath, $json, (New-Object System.Text.UTF8Encoding($false)))

# --- addresses -------------------------------------------------------------
$addresses = @()
try {
    $addresses = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
        Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } |
        Select-Object -ExpandProperty IPAddress
} catch {
    $addresses = @((ipconfig | Select-String -Pattern "IPv4.*: ([\d\.]+)" |
        ForEach-Object { $_.Matches[0].Groups[1].Value }) | Where-Object { $_ -notlike "127.*" })
}
if (-not $addresses) { $addresses = @("127.0.0.1") }

Write-Host ""
Write-Host "Update folder : $Dir"
Write-Host "Manifest      : $manifestPath"
Write-Host "Preferred APK : $($preferred.name)  ($($preferred.size) bytes)"
Write-Host "Preferred SHA : $($preferred.sha256)"
Write-Host ""
foreach ($ip in $addresses) {
    Write-Host "Update source : http://${ip}:$Port/update.json"
    foreach ($asset in $assets) {
        Write-Host ("Direct APK    : http://{0}:{1}/{2}" -f $ip, $Port, $asset.name)
    }
}
Write-Host ""

if ($NoServe) {
    Write-Host "Serve skipped (-NoServe). Upload the folder somewhere and use that URL."
    return
}

$python = Get-Command python -ErrorAction SilentlyContinue
Write-Host "Serving $Dir on port $Port (Ctrl+C to stop)..."
if ($python) {
    # python's http.server binds 0.0.0.0 without needing admin rights, unlike
    # HttpListener which requires a URL ACL for non-loopback prefixes.
    & $python.Source -m http.server $Port --bind 0.0.0.0 --directory $Dir
} else {
    Write-Host "python not found; falling back to HttpListener (may need an elevated shell)."
    $listener = New-Object System.Net.HttpListener
    $listener.Prefixes.Add("http://+:$Port/")
    $listener.Start()
    try {
        while ($listener.IsListening) {
            $context = $listener.GetContext()
            $relative = [Uri]::UnescapeDataString($context.Request.Url.AbsolutePath).TrimStart("/")
            if ([string]::IsNullOrWhiteSpace($relative)) { $relative = "update.json" }
            $file = Join-Path $Dir $relative
            if (Test-Path -LiteralPath $file -PathType Leaf) {
                $bytes = [System.IO.File]::ReadAllBytes($file)
                $context.Response.ContentType = if ($file -like "*.json") { "application/json" } else { "application/octet-stream" }
                $context.Response.ContentLength64 = $bytes.Length
                $context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
            } else {
                $context.Response.StatusCode = 404
            }
            $context.Response.Close()
        }
    } finally {
        $listener.Stop()
    }
}
