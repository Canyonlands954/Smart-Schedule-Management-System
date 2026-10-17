# Resets schedule_test only, starts isolated services and stops its own processes on exit.
$ErrorActionPreference='Stop'
$projectDirectory=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectDirectory
if(-not(Get-Command docker -ErrorAction SilentlyContinue)) {
    $dockerFolder=Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin'
    if(Test-Path -LiteralPath (Join-Path $dockerFolder 'docker.exe')) {$env:PATH="$dockerFolder;$env:PATH"}
}
foreach($commandName in 'java','mvn','node','npm.cmd','docker'){if(-not(Get-Command $commandName -ErrorAction SilentlyContinue)){throw "Missing command: $commandName"}}
if(-not(Test-Path -LiteralPath '.env')){throw 'Configure .env and start the database first.'}
Get-Content -LiteralPath '.env' | ForEach-Object {if($_ -match '^([A-Z][A-Z0-9_]*)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}}
foreach($portNumber in 18866,8877){if(Get-NetTCPConnection -LocalPort $portNumber -State Listen -ErrorAction SilentlyContinue){throw "Test port $portNumber is occupied."}}
New-Item -ItemType Directory -Path '.runtime' -Force | Out-Null
$jar='backend/target/schedule-api-2.0.0.jar'
$needsBuild=-not(Test-Path -LiteralPath $jar)
if(-not $needsBuild){$jarTime=(Get-Item -LiteralPath $jar).LastWriteTimeUtc;$needsBuild=(Get-Item -LiteralPath 'backend/pom.xml').LastWriteTimeUtc -gt $jarTime -or @((Get-ChildItem -LiteralPath 'backend/src/main' -Recurse -File)|Where-Object {$_.LastWriteTimeUtc -gt $jarTime}).Count -gt 0}
if($needsBuild){& mvn -B -q -f backend/pom.xml package -DskipTests;if($LASTEXITCODE -ne 0){throw 'Backend build failed'}}
if(-not(Test-Path -LiteralPath 'frontend/node_modules/playwright')){& npm.cmd --prefix frontend ci --no-audit --no-fund;if($LASTEXITCODE -ne 0){throw 'Dependency install failed'}}
# V4 must already be applied to schedule_test by the backend integration tests.
$testSchema=& docker exec xushi-postgres psql -U schedule -d schedule_test -tAc "SELECT to_regclass('public.knowledge_settings')"
if($LASTEXITCODE -ne 0 -or $testSchema.Trim() -ne 'knowledge_settings'){throw 'Run backend tests against schedule_test first to apply its schema.'}
Get-Content -LiteralPath 'checks/reset-browser.sql' -Raw | & docker exec -i xushi-postgres psql -U schedule -d schedule_test -v ON_ERROR_STOP=1 *> .runtime/browser-reset.log
if($LASTEXITCODE -ne 0){throw 'Isolated test database reset failed'}
$databasePort=if($env:DB_PORT){$env:DB_PORT}else{'55432'}
$env:DB_URL="jdbc:postgresql://127.0.0.1:$databasePort/schedule_test"
$env:API_PORT='18866';$env:API_HOST='127.0.0.1';$env:IMPORT_SOURCE=''
$env:AI_API_KEY='';$env:AI_CONFIG_MASTER_KEY='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='
$env:KNOWLEDGE_DIRECTORY='.runtime/rag-browser-files';$env:KNOWLEDGE_WORKER_ENABLED='true'
$env:ALLOWED_ORIGINS='http://127.0.0.1:8877,http://localhost:8877'
$env:API_PROXY_TARGET='http://127.0.0.1:18866'
$owned=@()
try {
    Copy-Item -LiteralPath $jar -Destination '.runtime/rag-browser-api.jar' -Force
    $apiProcess=Start-Process -FilePath (Get-Command java).Source -ArgumentList @('-jar','.runtime/rag-browser-api.jar') -WorkingDirectory $projectDirectory -WindowStyle Hidden -RedirectStandardOutput '.runtime/rag-browser-api.log' -RedirectStandardError '.runtime/rag-browser-api.err.log' -PassThru
    $owned+=@{id=$apiProcess.Id;started=$apiProcess.StartTime.ToUniversalTime().ToString('o')}
    $ready=$false
    for($attempt=0;$attempt -lt 50;$attempt++){try{$health=Invoke-RestMethod 'http://127.0.0.1:18866/api/health' -TimeoutSec 2;if($health.architecture -eq 'spring-boot-postgresql'){$ready=$true;break}}catch{};if($apiProcess.HasExited){throw 'Test API failed to start'};Start-Sleep -Milliseconds 500}
    if(-not $ready){throw 'Test API startup timed out'}
    $webProcess=Start-Process -FilePath (Get-Command node).Source -ArgumentList @('node_modules/vite/bin/vite.js','--host','127.0.0.1','--port','8877','--strictPort') -WorkingDirectory (Join-Path $projectDirectory 'frontend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $projectDirectory '.runtime/rag-browser-web.log') -RedirectStandardError (Join-Path $projectDirectory '.runtime/rag-browser-web.err.log') -PassThru
    $owned+=@{id=$webProcess.Id;started=$webProcess.StartTime.ToUniversalTime().ToString('o')}
    $ready=$false
    for($attempt=0;$attempt -lt 30;$attempt++){try{$page=Invoke-WebRequest 'http://127.0.0.1:8877' -TimeoutSec 2;if($page.StatusCode -eq 200){$ready=$true;break}}catch{};Start-Sleep -Milliseconds 500}
    if(-not $ready){throw 'Test frontend startup timed out'}
    & node checks/browser.cjs
    if($LASTEXITCODE -ne 0){throw 'Existing browser workflow regression failed'}
    & node checks/rag-browser.cjs
    if($LASTEXITCODE -ne 0){throw 'Knowledge browser workflow check failed'}
} finally {
    foreach($record in $owned){$process=Get-Process -Id $record.id -ErrorAction SilentlyContinue;$recorded=[datetime]$record.started;if($process -and $process.StartTime.ToUniversalTime().Ticks -eq $recorded.ToUniversalTime().Ticks){Stop-Process -Id $record.id -ErrorAction SilentlyContinue}}
}
