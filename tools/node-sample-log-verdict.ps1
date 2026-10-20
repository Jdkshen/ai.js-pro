function Get-NodeSampleLogIssues {
    param([string]$Text)
    # Only script-owned output; Android service warnings are not script errors.
    $pattern = 'SCRIPT_ERROR|AJSLOG1:E:|GlobalConsole\([^)]*\):[^\r\n]*/E:|FATAL EXCEPTION'
    $semanticFailure = 'AJSLOG1:[VDIWE]:[^\r\n]*"(?:ok|supported)"\s*:\s*false'
    @($Text -split '\r?\n' | Where-Object { $_ -match $pattern -or $_ -match $semanticFailure })
}
