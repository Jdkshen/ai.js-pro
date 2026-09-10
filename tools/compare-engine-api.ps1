param(
    [string]$Url = 'http://127.0.0.1:18790/mcp',
    [string]$Token = '',
    [string]$OutputDirectory = '.artifacts\engine-api',
    [string]$Device = 'cccc62c7',
    [string]$Adb = 'C:\Android\platform-tools-2\adb.exe',
    [int]$HostPort = 18790,
    [int]$DevicePort = 8788,
    [switch]$SkipAdbForward
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

if (-not $SkipAdbForward) {
    if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) { throw "adb not found: $Adb" }
    $savedErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $deviceStateOutput = @(& $Adb -s $Device get-state 2>&1)
    $deviceStateExitCode = $LASTEXITCODE
    $ErrorActionPreference = $savedErrorPreference
    $deviceState = if ($deviceStateOutput.Count -gt 0) { $deviceStateOutput[0].ToString().Trim() } else { '' }
    if ($deviceStateExitCode -ne 0 -or $deviceState -ne 'device') {
        throw "Device is not connected or authorized: $Device"
    }
    & $Adb -s $Device forward "tcp:$HostPort" "tcp:$DevicePort" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to forward host port $HostPort to device port $DevicePort" }
}

function Invoke-McpRequest([string]$Body) {
    # 不用 Invoke-RestMethod / HttpWebRequest：PowerShell 5.1 的 .NET 栈会给请求带上
    # "Expect: 100-continue"，而应用的 MCP 服务收到该头按协议直接回 417 Expectation Failed
    # （见 McpHttpServer.serve）。curl 的 -H "Expect:" 能把这个头精确去掉。
    # SystemRoot 在部分终端/CI 环境里可能为空，为空时直接回退到 PATH 上的 curl.exe。
    $systemRoot = [Environment]::GetEnvironmentVariable('SystemRoot')
    $curl = if ([string]::IsNullOrWhiteSpace($systemRoot)) { 'curl.exe' }
        else { Join-Path $systemRoot 'System32\curl.exe' }
    if (-not (Test-Path -LiteralPath $curl -PathType Leaf)) { $curl = 'curl.exe' }
    $bodyFile = Join-Path ([System.IO.Path]::GetTempPath()) ('engine-api-' + [guid]::NewGuid().ToString('N') + '.json')
    [System.IO.File]::WriteAllText($bodyFile, $Body, (New-Object System.Text.UTF8Encoding($false)))
    try {
        $curlArguments = @('--fail-with-body', '-sS', '-X', 'POST', $Url,
            '-H', 'Content-Type: application/json',
            '-H', 'Accept: application/json',
            '-H', 'Expect:',
            '--data-binary', "@$bodyFile")
        if (-not [string]::IsNullOrWhiteSpace($Token)) {
            $curlArguments += @('-H', "Authorization: Bearer $Token")
        }
        $text = & $curl @curlArguments
        $exitCode = $LASTEXITCODE
    } finally {
        Remove-Item -LiteralPath $bodyFile -Force -ErrorAction SilentlyContinue
    }
    if ($exitCode -ne 0) { throw "MCP request failed (curl $exitCode): $text" }
    return ($text | ConvertFrom-Json)
}

function Invoke-McpTool([int]$Id, [string]$Name, [hashtable]$Arguments) {
    $body = @{
        jsonrpc = '2.0'
        id = $Id
        method = 'tools/call'
        params = @{ name = $Name; arguments = $Arguments }
    } | ConvertTo-Json -Depth 8 -Compress
    $response = Invoke-McpRequest -Body $body
    if ($null -ne $response.error) { throw $response.error.message }
    $hasContent = $null -ne $response.result -and
        $response.result.PSObject.Properties.Name -contains 'content'
    if ($hasContent) {
        $content = $response.result.content | Select-Object -First 1
        if ($response.result.isError) { throw $content.text }
        if ($null -eq $content -or [string]::IsNullOrWhiteSpace([string]$content.text)) {
            throw "MCP tool returned an empty content payload: $Name"
        }
        return $content.text | ConvertFrom-Json
    }
    if ($null -eq $response.result) { throw "MCP tool returned no result: $Name" }
    return $response.result
}

$rhino = Invoke-McpTool 1 'list_engine_api' @{ engine = 'rhino' }
$quickJs = Invoke-McpTool 2 'list_engine_api' @{ engine = 'quickjs' }
$rhinoNames = @($rhino.items | ForEach-Object { [string]$_ } | Sort-Object -Unique -CaseSensitive)
$quickJsNames = @($quickJs.items | ForEach-Object { [string]$_ } | Sort-Object -Unique -CaseSensitive)
$rhinoSet = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
$quickJsSet = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
$rhinoNames | ForEach-Object { [void]$rhinoSet.Add($_) }
$quickJsNames | ForEach-Object { [void]$quickJsSet.Add($_) }
$onlyRhino = @($rhinoNames | Where-Object { -not $quickJsSet.Contains($_) })
$onlyQuickJs = @($quickJsNames | Where-Object { -not $rhinoSet.Contains($_) })
$common = @($rhinoNames | Where-Object { $quickJsSet.Contains($_) })
$result = [ordered]@{
    generatedAt = (Get-Date).ToString('o')
    rhinoCount = $rhinoNames.Count
    quickJsCount = $quickJsNames.Count
    commonCount = $common.Count
    onlyRhino = $onlyRhino
    onlyQuickJs = $onlyQuickJs
    common = $common
}

$outputRoot = Join-Path $projectRoot $OutputDirectory
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$outputPath = Join-Path $outputRoot 'engine-api-diff.json'
$result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $outputPath -Encoding UTF8
Write-Output "Rhino=$($rhinoNames.Count), QuickJS=$($quickJsNames.Count), common=$($common.Count)"
Write-Output "Only Rhino=$($onlyRhino.Count), only QuickJS=$($onlyQuickJs.Count)"
Write-Output "Report: $outputPath"
