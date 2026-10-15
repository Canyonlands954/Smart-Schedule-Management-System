param([string]$ImportSource = '')
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
foreach ($commandName in 'java','mvn','node','npm.cmd','docker') {
    if (-not (Get-Command $commandName -ErrorAction SilentlyContinue)) { throw "Missing required command: $commandName. See DEPLOYMENT.md." }
}
if (-not (Test-Path -LiteralPath '.env')) {
    $random = New-Object byte[] 24
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($random) } finally { $generator.Dispose() }
    $password = -join ($random | ForEach-Object { $_.ToString('x2') })
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot '.env'), "DB_PASSWORD=$password`n")
}
Get-Content -LiteralPath '.env' | ForEach-Object {
    if ($_ -match '^([A-Z][A-Z0-9_]*)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process') }
}
if (-not $env:DB_PASSWORD -or $env:DB_PASSWORD -eq 'change-this-before-use') { throw 'Configure a private DB_PASSWORD in .env first.' }
& docker compose up -d postgres
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL could not start. Ensure Docker Desktop is running.' }
New-Item -ItemType Directory -Path '.runtime' -Force | Out-Null
function Test-Api {
    try { $health=Invoke-RestMethod 'http://127.0.0.1:18865/api/health' -TimeoutSec 2; return $health.architecture -eq 'spring-boot-postgresql' } catch { return $false }
}
$ownedProcesses=@()
if (Test-Path -LiteralPath '.runtime/pids.json') { $ownedProcesses=@(Get-Content -LiteralPath '.runtime/pids.json' -Raw | ConvertFrom-Json) }
if (-not (Test-Api)) {
    if (-not (Test-Path -LiteralPath 'backend/target/schedule-api-2.0.0.jar')) {
        & mvn -B -q -f backend/pom.xml package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    }
    Copy-Item -LiteralPath 'backend/target/schedule-api-2.0.0.jar' -Destination '.runtime/api.jar' -Force
    if ($ImportSource) { $env:IMPORT_SOURCE=(Resolve-Path -LiteralPath $ImportSource).Path }
    $apiProcess=Start-Process -FilePath (Get-Command java).Source -ArgumentList @('-jar','.runtime/api.jar') -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput '.runtime/api.out.log' -RedirectStandardError '.runtime/api.err.log' -PassThru
    $ownedProcesses+=@{id=$apiProcess.Id;started=$apiProcess.StartTime.ToUniversalTime().ToString('o');kind='api'}
    $ownedProcesses | ConvertTo-Json -AsArray | Set-Content -LiteralPath '.runtime/pids.json' -Encoding utf8
    Remove-Item Env:IMPORT_SOURCE -ErrorAction SilentlyContinue
    $ready=$false
    for ($attempt=0;$attempt -lt 40;$attempt++) {
        if ($apiProcess.HasExited) { throw 'API startup failed. See .runtime/api.err.log and api.out.log.' }
        if (Test-Api) { $ready=$true;break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'API startup timed out. Check the logs.' }
}
try { $page=Invoke-WebRequest 'http://127.0.0.1:8765/' -TimeoutSec 2; $webReady=$page.Content.Contains('/src/main.ts') -or $page.Content.Contains('/assets/index-') } catch { $webReady=$false }
if (-not $webReady) {
    if (Get-NetTCPConnection -LocalPort 8765 -State Listen -ErrorAction SilentlyContinue) { throw 'Port 8765 is occupied. Stop the old application before starting this version.' }
    if (-not (Test-Path -LiteralPath 'frontend/node_modules/vite/bin/vite.js')) {
        & npm.cmd --prefix frontend ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    }
    $webProcess=Start-Process -FilePath (Get-Command node).Source -ArgumentList @('node_modules/vite/bin/vite.js','--host','127.0.0.1','--port','8765','--strictPort') -WorkingDirectory (Join-Path $PSScriptRoot 'frontend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $PSScriptRoot '.runtime/web.out.log') -RedirectStandardError (Join-Path $PSScriptRoot '.runtime/web.err.log') -PassThru
    $ownedProcesses+=@{id=$webProcess.Id;started=$webProcess.StartTime.ToUniversalTime().ToString('o');kind='web'}
    $ownedProcesses | ConvertTo-Json -AsArray | Set-Content -LiteralPath '.runtime/pids.json' -Encoding utf8
}
Write-Host 'Xushi enterprise is running: http://127.0.0.1:8765/'
Write-Host 'Data is stored in PostgreSQL. Use stop.ps1 to stop the app; database data is retained.'
