$ErrorActionPreference='Stop'
Set-Location -LiteralPath $PSScriptRoot
$staging=Join-Path $PSScriptRoot ('.runtime/package-'+[guid]::NewGuid().ToString('N'))
$target=Join-Path $staging 'enterprise'
New-Item -ItemType Directory -Path $target -Force | Out-Null
$directories=@('backend/src','frontend/src','checks','database','examples')
$files=@('README.md','ARCHITECTURE.md','DEPLOYMENT.md','USER_GUIDE.md','RAG_GUIDE.md','RAG_VERIFICATION.md','.env.example','.gitignore','start.ps1','stop.ps1','start.cmd','package.ps1','compose.yml','compose.production.yml','backend/pom.xml','backend/Dockerfile','backend/.dockerignore','frontend/package.json','frontend/package-lock.json','frontend/tsconfig.json','frontend/vite.config.ts','frontend/index.html','frontend/Dockerfile','frontend/.dockerignore','frontend/nginx.conf')
$files+=@('API_TESTING.md','PROJECT_AUDIT_20261019.md')
foreach ($relative in $directories+$files) {
    $source=Join-Path $PSScriptRoot $relative
    if (-not (Test-Path -LiteralPath $source)) { throw "Missing source: $relative" }
    $destination=Join-Path $target $relative
    New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination -Recurse -Force
}
$archive=Join-Path (Split-Path $PSScriptRoot -Parent) '序时企业版-Vue-SpringBoot-PostgreSQL.zip'
Compress-Archive -LiteralPath $target -DestinationPath $archive -Force
Write-Host $archive
