$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'node-sample-log-verdict.ps1')
$caught = "I/Node24Console(12): AJSLOG1:E:Error [AutoJsHostError]: defineClass failed`nI/Node24Service(12): execute returned exitCode=0"
if (@(Get-NodeSampleLogIssues $caught).Count -ne 1) { throw 'Caught errors must fail even with exitCode=0' }
$unsupported = 'I/Node24Console(12): AJSLOG1:I:{"ok":true,"array":{"supported":false}}'
if (@(Get-NodeSampleLogIssues $unsupported).Count -ne 1) { throw 'Degraded API result must fail' }
$good = "W/System.err(12): log4j:WARN No appenders`nI/Node24Console(12): AJSLOG1:I:{`"ok`":true}`nI/Node24Service(12): execute returned exitCode=0"
if (@(Get-NodeSampleLogIssues $good).Count -ne 0) { throw 'System warnings must not fail samples' }
Write-Host 'PASS sample log verdict: caught errors and degraded results fail, unrelated warnings ignored'
