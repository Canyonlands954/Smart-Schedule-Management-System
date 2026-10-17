$ErrorActionPreference='Stop'
Set-Location -LiteralPath $PSScriptRoot
if (Test-Path -LiteralPath '.runtime/pids.json') {
    $records=Get-Content -LiteralPath '.runtime/pids.json' -Raw | ConvertFrom-Json
    foreach ($record in @($records)) {
        $process=Get-Process -Id $record.id -ErrorAction SilentlyContinue
        # PowerShell 7.5+ may deserialize ISO strings to DateTime automatically.
        $recordedStart=[datetime]$record.started
        if ($process -and $process.StartTime.ToUniversalTime().Ticks -eq $recordedStart.ToUniversalTime().Ticks) { Stop-Process -Id $record.id -ErrorAction Stop }
    }
    Remove-Item -LiteralPath '.runtime/pids.json'
}
Write-Host 'Application processes stopped. PostgreSQL data has been retained.'
