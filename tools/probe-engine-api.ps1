param(
    [string[]]$Name = @('images', 'opencv'),
    [ValidateSet('rhino', 'quickjs', 'both')]
    [string]$Engine = 'both',
    [string]$Device = 'ce4d2bdb',
    [string]$Adb = 'C:\Android\platform-tools-2\adb.exe',
    [string]$Url = 'http://127.0.0.1:18790/mcp',
    [string]$Token = '',
    [int]$HostPort = 18790,
    [int]$DevicePort = 8788,
    [string]$OutputDirectory = '.artifacts\engine-api'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

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

# 与 compare-engine-api.ps1 相同：PowerShell 5.1 会带 Expect: 100-continue，服务按协议回 417，
# 因此统一用 curl.exe 并显式去掉该头。
function Invoke-McpRequest([string]$Body) {
    $systemRoot = [Environment]::GetEnvironmentVariable('SystemRoot')
    $curl = if ([string]::IsNullOrWhiteSpace($systemRoot)) { 'curl.exe' }
        else { Join-Path $systemRoot 'System32\curl.exe' }
    if (-not (Test-Path -LiteralPath $curl -PathType Leaf)) { $curl = 'curl.exe' }
    $bodyFile = Join-Path ([System.IO.Path]::GetTempPath()) ('probe-api-' + [guid]::NewGuid().ToString('N') + '.json')
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

function Invoke-McpTool([int]$Id, [string]$ToolName, [hashtable]$Arguments) {
    $body = @{
        jsonrpc = '2.0'
        id = $Id
        method = 'tools/call'
        params = @{ name = $ToolName; arguments = $Arguments }
    } | ConvertTo-Json -Depth 8 -Compress
    $response = Invoke-McpRequest -Body $body
    if ($null -ne $response.error) { throw $response.error.message }
    $hasContent = $null -ne $response.result -and
        $response.result.PSObject.Properties.Name -contains 'content'
    if ($hasContent) {
        $content = $response.result.content | Select-Object -First 1
        if ($response.result.isError) { throw $content.text }
        if ($null -eq $content -or [string]::IsNullOrWhiteSpace([string]$content.text)) {
            throw "MCP tool returned an empty content payload: $ToolName"
        }
        return $content.text | ConvertFrom-Json
    }
    if ($null -eq $response.result) { throw "MCP tool returned no result: $ToolName" }
    return $response.result
}

function Get-MemberNames([string]$ProbeEngine, [string]$ProbeName, [int]$Id) {
    $probe = Invoke-McpTool $Id 'probe_engine_api' @{ engine = $ProbeEngine; name = $ProbeName }
    $members = @($probe.members | ForEach-Object { [string]$_ } | Sort-Object -Unique -CaseSensitive)
    return [ordered]@{
        engine = $ProbeEngine
        name = $ProbeName
        type = [string]$probe.type
        count = $members.Count
        members = $members
    }
}

$engines = if ($Engine -eq 'both') { @('rhino', 'quickjs') } else { @($Engine) }
$report = [ordered]@{
    generatedAt = (Get-Date).ToString('o')
    device = $Device
    modules = @()
    diffs = @()
}
$id = 1
foreach ($moduleName in $Name) {
    $probes = @()
    foreach ($probeEngine in $engines) {
        try {
            $probes += Get-MemberNames $probeEngine $moduleName $id
        } catch {
            Write-Warning ("probe 失败: {0}/{1} - {2}" -f $probeEngine, $moduleName, $_.Exception.Message)
        }
        $id++
    }
    $report.modules += [ordered]@{ name = $moduleName; probes = $probes }
    if ($probes.Count -eq 2) {
        $rhinoSet = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
        $quickJsSet = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
        $probes[0].members | ForEach-Object { [void]$rhinoSet.Add($_) }
        $probes[1].members | ForEach-Object { [void]$quickJsSet.Add($_) }
        $onlyRhino = @($probes[0].members | Where-Object { -not $quickJsSet.Contains($_) })
        $onlyQuickJs = @($probes[1].members | Where-Object { -not $rhinoSet.Contains($_) })
        $report.diffs += [ordered]@{
            name = $moduleName
            onlyRhino = $onlyRhino
            onlyQuickJs = $onlyQuickJs
        }
        Write-Output ("[{0}] Rhino={1} QuickJS={2} 只有Rhino={3} 只有QuickJS={4}" -f `
                $moduleName, $probes[0].count, $probes[1].count, $onlyRhino.Count, $onlyQuickJs.Count)
        if ($onlyRhino.Count -gt 0) { Write-Output ("  只有 Rhino: " + ($onlyRhino -join ', ')) }
        if ($onlyQuickJs.Count -gt 0) { Write-Output ("  只有 QuickJS: " + ($onlyQuickJs -join ', ')) }
    }
}

$outputRoot = Join-Path $projectRoot $OutputDirectory
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$outputPath = Join-Path $outputRoot 'module-probe.json'
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $outputPath -Encoding UTF8
Write-Output "Report: $outputPath"
