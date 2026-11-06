<#
.SYNOPSIS
    Render the complete local update history as a standalone HTML page.
#>
param(
    [Parameter(Mandatory = $true)][string]$HistoryPath,
    [string]$OutputPath = ''
)
$ErrorActionPreference = 'Stop'
$HistoryPath = (Resolve-Path -LiteralPath $HistoryPath).Path
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path (Split-Path -Parent $HistoryPath) 'release-history.html'
}
$parsed = Get-Content -LiteralPath $HistoryPath -Raw -Encoding UTF8 | ConvertFrom-Json
$entries = @(@($parsed) | Where-Object { $_ } | Sort-Object { [int]$_.versionCode } -Descending)
$templatePath = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'templates\release-history.html'
$template = [IO.File]::ReadAllText($templatePath, [Text.Encoding]::UTF8)
$cards = New-Object Text.StringBuilder
for ($index = 0; $index -lt $entries.Count; $index++) {
    $entry = $entries[$index]
    $version = [Net.WebUtility]::HtmlEncode([string]$entry.versionName)
    $date = [Net.WebUtility]::HtmlEncode([string]$entry.date)
    $notes = [Net.WebUtility]::HtmlEncode([string]$entry.notes)
    $current = if ($index -eq 0) { ' cur' } else { '' }
    $badge = if ($index -eq 0) { '<span class="badge">&#24403;&#21069;</span>' } else { '' }
    [void]$cards.AppendLine(('<div class="card{0}"><div class="head"><span class="ver">{1}</span>{2}<span class="date">{3}</span></div><div class="notes">{4}</div></div>' -f $current, $version, $badge, $date, $notes))
}
$latestVersion = if ($entries.Count) { [Net.WebUtility]::HtmlEncode([string]$entries[0].versionName) } else { '-' }
$latestDate = if ($entries.Count) { [Net.WebUtility]::HtmlEncode([string]$entries[0].date) } else { '-' }
# Replace cards last, so release notes containing placeholder text stay verbatim.
$html = $template.Replace('{{COUNT}}', [string]$entries.Count).
    Replace('{{LATEST_VERSION}}', $latestVersion).
    Replace('{{LATEST_DATE}}', $latestDate).
    Replace('{{GENERATED_AT}}', (Get-Date -Format 'yyyy-MM-dd HH:mm')).
    Replace('{{CARDS}}', $cards.ToString())
[IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputPath), $html, (New-Object Text.UTF8Encoding($false)))
Write-Host ('History page  : {0} ({1} entries)' -f $OutputPath, $entries.Count)
