#requires -version 5.1
<#
.SYNOPSIS
  从 :inrt release 构建宿主内置的「瘦身打包模板」并更新 excluded-zip-files.json。

.DESCRIPTION
  产出：
    apps/app/src/main/assets/template.apk          （无 lib/ 的 inrt 模板）
    apps/app/src/main/assets/excluded-zip-files.json
      general/opencv/v8 三个数组列出被剔除、打包时需从宿主 APK 补齐的全部 lib/*
  最后自动跑 :app:verifySlimPackagingTemplate。

  重要：
    * 只用 :inrt:packageRelease，绝不跑 :inrt:assembleRelease / buildApkPlugin，
      不覆盖 plugins/apkbuilder-plugin/src/main/assets/template.apk（插件那份 70MB 旧模板）。
    * lib 清单必须与 inrt APK 的 lib/* 一一对应；漏一个会导致打包时补不全。

.PARAMETER NoBuild
  跳过 :inrt:packageRelease，复用已有 apps/inrt/build/outputs/apk/release/*.apk。

.PARAMETER NoVerify
  跳过 :app:verifySlimPackagingTemplate（仅调试脚本时使用）。

.EXAMPLE
  .\tools\build-slim-template.ps1
  .\tools\build-slim-template.ps1 -NoBuild
#>
param(
    [switch]$NoBuild,
    [switch]$NoVerify
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path (Split-Path $MyInvocation.MyCommand.Path -Parent) -Parent
Push-Location $repoRoot
try {
    $inrtApk = Join-Path $repoRoot 'apps/inrt/build/outputs/apk/release/inrt-arm64-v8a-release-unsigned.apk'
    $templateApk = Join-Path $repoRoot 'apps/app/src/main/assets/template.apk'
    $excludedJson = Join-Path $repoRoot 'apps/app/src/main/assets/excluded-zip-files.json'
    $logDir = Join-Path $repoRoot '.artifacts'
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $buildLog = Join-Path $logDir 'build-slim-template-inrt.log'
    $verifyLog = Join-Path $logDir 'build-slim-template-verify.log'

    if (-not $NoBuild) {
        Write-Host '[C-SLIM] :inrt:packageRelease 构建中（不会触碰插件模板）...'
        . (Join-Path $repoRoot 'tools/jdk17.ps1') | Out-Null
        & (Join-Path $repoRoot 'gradlew.bat') ':inrt:packageRelease' '--console=plain' *> $buildLog
        if ($LASTEXITCODE -ne 0) {
            throw " :inrt:packageRelease 失败（exit=$LASTEXITCODE），日志：$buildLog"
        }
    }
    if (-not (Test-Path $inrtApk)) {
        throw "缺少 :inrt release APK：$inrtApk（先运行 .\tools\build-slim-template.ps1）"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $srcZip = [System.IO.Compression.ZipFile]::OpenRead($inrtApk)
    try {
        $libNames = New-Object System.Collections.Generic.List[string]
        foreach ($entry in $srcZip.Entries) {
            if ($entry.FullName -like 'lib/*') { $libNames.Add($entry.FullName) }
        }
        $libNames.Sort([System.StringComparer]::Ordinal)

        $general = New-Object System.Collections.Generic.List[string]
        $opencv = New-Object System.Collections.Generic.List[string]
        $v8 = New-Object System.Collections.Generic.List[string]
        foreach ($name in $libNames) {
            $leaf = [System.IO.Path]::GetFileName($name)
            if ($leaf -match '^libopencv' -or $leaf -eq 'libtbb.so') {
                $opencv.Add($name)
            } elseif ($leaf -match '^libv8' -or $leaf -match '^libnode') {
                $v8.Add($name)
            } else {
                $general.Add($name)
            }
        }
        $general.Sort([System.StringComparer]::Ordinal)
        $opencv.Sort([System.StringComparer]::Ordinal)
        $v8.Sort([System.StringComparer]::Ordinal)

        # 硬校验：分类必须覆盖每个 lib/*，不能漏、不能多。
        $classified = New-Object System.Collections.Generic.List[string]
        foreach ($name in $general) { $classified.Add($name) }
        foreach ($name in $opencv) { $classified.Add($name) }
        foreach ($name in $v8) { $classified.Add($name) }
        $classified.Sort([System.StringComparer]::Ordinal)
        if ($classified.Count -ne $libNames.Count) {
            throw "lib 分类数量不一致：源 $($libNames.Count) 个，分类后 $($classified.Count) 个"
        }
        for ($i = 0; $i -lt $libNames.Count; $i++) {
            if ($libNames[$i] -ne $classified[$i]) {
                throw "lib 分类与源不符：源=$($libNames[$i])，分类=$($classified[$i])"
            }
        }
        if (($general.Count + $opencv.Count) -eq 0) {
            throw 'general/opencv 同时为空，:app:verifySlimPackagingTemplate 会失败'
        }

        # task-119/task-213：除 lib/ 外，模板还剔除两个不随打包模板分发的巨型运行时资源：
        #   * assets/source-engine-upgrade/node24/arm64-v8a/libnode.so（97MB 未压缩）
        #   * resources/new_api_database.ser（6.5MB）
        # 否则瘦身模板会超过 :app:verifySlimPackagingTemplate 的 20MB 上限。
        $excludedAssetNames = @(
            'assets/source-engine-upgrade/node24/arm64-v8a/libnode.so',
            'resources/new_api_database.ser'
        )
        $tmpTemplate = "$templateApk.tmp"
        if (Test-Path $tmpTemplate) { Remove-Item $tmpTemplate -Force }
        $dstZip = [System.IO.Compression.ZipFile]::Open(
            $tmpTemplate, [System.IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($entry in $srcZip.Entries) {
                if ($entry.FullName -like 'lib/*') { continue }
                if ($excludedAssetNames -contains $entry.FullName) {
                    Write-Host "[C-SLIM] 跳过资源：$($entry.FullName)"
                    continue
                }
                $newEntry = $dstZip.CreateEntry(
                    $entry.FullName, [System.IO.Compression.CompressionLevel]::Optimal)
                $newEntry.LastWriteTime = $entry.LastWriteTime
                if (-not [string]::IsNullOrEmpty($entry.Name)) {
                    $in = $entry.Open()
                    $out = $newEntry.Open()
                    try { $in.CopyTo($out) } finally { $out.Dispose(); $in.Dispose() }
                }
            }
        } finally {
            $dstZip.Dispose()
        }

        $shaBefore = ''
        if (Test-Path $templateApk) {
            $shaBefore = (Get-FileHash $templateApk -Algorithm SHA256).Hash
        }
        Move-Item -Force $tmpTemplate $templateApk
        $shaAfter = (Get-FileHash $templateApk -Algorithm SHA256).Hash

        $json = [ordered]@{
            general = @($general.ToArray())
            opencv = @($opencv.ToArray())
            v8 = @($v8.ToArray())
        }
        $jsonText = ($json | ConvertTo-Json -Depth 3)
        [System.IO.File]::WriteAllText($excludedJson, $jsonText,
            (New-Object System.Text.UTF8Encoding($false)))

        # 产物自检（与 :app:verifySlimPackagingTemplate 的硬条件保持一致）。
        $checkZip = [System.IO.Compression.ZipFile]::OpenRead($templateApk)
        try {
            $checkNames = New-Object System.Collections.Generic.List[string]
            foreach ($entry in $checkZip.Entries) {
                $checkNames.Add($entry.FullName)
                if ($entry.FullName -like 'lib/*') {
                    throw "瘦身模板仍含 lib/ 条目：$($entry.FullName)"
                }
            }
            foreach ($required in @('AndroidManifest.xml', 'classes.dex', 'resources.arsc')) {
                if (-not $checkNames.Contains($required)) {
                    throw "瘦身模板缺少必需条目：$required"
                }
            }
        } finally {
            $checkZip.Dispose()
        }
        $size = (Get-Item $templateApk).Length
        if ($size -lt 6MB -or $size -gt 20MB) {
            throw "瘦身模板体积异常：$size 字节（:app:verifySlimPackagingTemplate 要求 6-20MB）"
        }

        Write-Host "[C-SLIM] template=$templateApk"
        Write-Host ("[C-SLIM] size={0} ({1:N1} MB) sha256={2} shaBefore={3}" -f $size, ($size/1MB), $shaAfter, $shaBefore)
        Write-Host ("[C-SLIM] libs total={0} general={1} opencv={2} v8={3}" -f $libNames.Count, $general.Count, $opencv.Count, $v8.Count)
        Write-Host ("[C-SLIM] plugin template untouched: plugins/apkbuilder-plugin/src/main/assets/template.apk")

        if (-not $NoVerify) {
            Write-Host '[C-SLIM] :app:verifySlimPackagingTemplate 校验中...'
            & (Join-Path $repoRoot 'gradlew.bat') ':app:verifySlimPackagingTemplate' '--console=plain' *> $verifyLog
            if ($LASTEXITCODE -ne 0) {
                throw ":app:verifySlimPackagingTemplate 失败（exit=$LASTEXITCODE），日志：$verifyLog"
            }
            Write-Host "[C-SLIM] verifySlimPackagingTemplate PASS"
        }
    } finally {
        if ($srcZip) { $srcZip.Dispose() }
    }
} finally {
    Pop-Location
}
