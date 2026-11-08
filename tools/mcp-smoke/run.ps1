# AI.js Pro MCP 冒烟/回归：一条命令跑完不变量检查。
#
#   .\tools\mcp-smoke\run.ps1                       # K40（USB）：自动 adb forward 后跑
#   .\tools\mcp-smoke\run.ps1 -DeviceId ce4d2bdb     # 换设备
#   .\tools\mcp-smoke\run.ps1 -Base http://192.168.10.4:8765/mcp   # 直连局域网设备（不碰 adb）
#   .\tools\mcp-smoke\run.ps1 -Extra @("--only","shape")
param(
  [string]$DeviceId = "cccc62c7",
  [int]$Port = 18768,
  [int]$RemotePort = 8765,
  [string]$Base = "",
  [string[]]$Extra = @()
)
$ErrorActionPreference = "Continue"
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$smoke = Join-Path $PSScriptRoot "smoke.mjs"

$node = (Get-Command node -ErrorAction SilentlyContinue).Source
if (-not $node) { Write-Error "找不到 node（需要 Node >= 18 的 fetch）"; exit 2 }

if (-not $Base) {
  $adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
  if (-not $adb) { $adb = "C:\Android\platform-tools\adb.exe" }
  if (-not (Test-Path $adb)) { Write-Error "找不到 adb：$adb"; exit 2 }
  Write-Output "准备转发 $DeviceId tcp:$Port -> tcp:$RemotePort"
  & $adb -s $DeviceId forward --remove "tcp:$Port" 2>$null | Out-Null
  & $adb -s $DeviceId forward "tcp:$Port" "tcp:$RemotePort" | Out-Null
  $Base = "http://127.0.0.1:$Port/mcp"
}

Write-Output "MCP smoke -> $Base"
& $node $smoke --base $Base @Extra
$code = $LASTEXITCODE
Write-Output ("退出码：" + $code)
exit $code
