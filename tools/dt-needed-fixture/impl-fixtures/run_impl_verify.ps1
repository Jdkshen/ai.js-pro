#requires -version 5.1
<#
  用 Gradle 已经编出来的 class 跑**真实** ToolkitElfNeeded（不跑 gradle、不改源码），
  对 .artifacts/fixtures-dt-needed 下的三份夹具打印 Outcome 并把补丁结果写到 kotlin-verify/out/。
  用法： powershell -NoProfile -File .artifacts/fixtures-dt-needed/run_impl_verify.ps1 [-ClassesDir <path>]
#>
param(
    [string]$ClassesDir = ''
)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $here '..\..')).Path
if (-not $ClassesDir) {
    $ClassesDir = Join-Path $repo 'apps\app\build\tmp\kotlin-classes\miuixCompatRelease'
}
if (-not (Test-Path $ClassesDir)) { throw "missing compiled classes dir: $ClassesDir (run an :app build first)" }
. (Join-Path $repo 'tools\jdk17.ps1') | Out-Null
$cache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
$gson = (Get-ChildItem $cache -Recurse -File -Filter 'gson-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } | Sort-Object Name | Select-Object -Last 1).FullName
$stdlib = (Get-ChildItem $cache -Recurse -File -Filter 'kotlin-stdlib-2.1.0.jar' -ErrorAction SilentlyContinue |
    Select-Object -First 1).FullName
if (-not $gson -or -not $stdlib) { throw 'gson / kotlin-stdlib jar not found in the Gradle cache' }
$work = Join-Path $here 'kotlin-verify'
$outClasses = Join-Path $work 'out-classes'
if (Test-Path $outClasses) { Remove-Item -Recurse -Force $outClasses }
New-Item -ItemType Directory -Force -Path $outClasses | Out-Null
& javac -encoding UTF-8 -nowarn -cp "$ClassesDir;$gson;$stdlib" -d $outClasses (Join-Path $work 'VerifyMain.java')
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
Push-Location $work
try {
    $jargs = @('-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8', '-cp',
        "$outClasses;$ClassesDir;$gson;$stdlib", 'VerifyMain',
        '..\libdtprobe.so', '..\libdtprobe-preexisting.so', '..\libdtprobe-raw-no-gap.so')
    & java @jargs 2>&1 | Tee-Object -FilePath (Join-Path $work 'verify-output.txt')
} finally {
    Pop-Location
}
