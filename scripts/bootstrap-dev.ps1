[CmdletBinding()]
param(
    [switch]$SkipFrontendInstall
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$adminDir = Join-Path $repoRoot 'ruoyi-ui'
$businessDir = Join-Path (Split-Path -Parent $repoRoot) 'demo-ronghe\dongying-vue'
$composeFile = Join-Path $repoRoot 'deploy\compose.yml'
$mapDataCandidates = @(
    (Join-Path $repoRoot 'map-data\dongying-dev\manifest.json'),
    (Join-Path (Split-Path -Parent $repoRoot) 'map-data\dongying-dev\manifest.json'),
    (Join-Path (Split-Path -Parent $repoRoot) 'demo-ronghe\map-data\dongying-dev\manifest.json')
)

function Assert-Command {
    param([Parameter(Mandatory)][string]$Name)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found. See the repository setup guide."
    }
}

Assert-Command docker
Assert-Command java
Assert-Command node
Assert-Command npm

docker info *> $null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Engine is not running. Start Docker Desktop and try again.'
}

Write-Host 'Starting the personal PostgreSQL/PostGIS database...'
docker compose -f $composeFile up -d --wait db
if ($LASTEXITCODE -ne 0) {
    throw 'The local database failed to start.'
}

if (-not $SkipFrontendInstall -and -not (Test-Path (Join-Path $adminDir 'node_modules'))) {
    Write-Host 'Installing admin frontend dependencies...'
    Push-Location $adminDir
    try {
        npm install
        if ($LASTEXITCODE -ne 0) {
            throw 'Frontend dependency installation failed.'
        }
    }
    finally {
        Pop-Location
    }
}

$mapManifest = $mapDataCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if ($mapManifest) {
    Write-Host "Offline map package detected: $(Split-Path -Parent (Split-Path -Parent $mapManifest))"
}
else {
    Write-Warning 'Offline map package was not found. Set MAP_DATA_DIR before starting Vite; see docs/offline-map-deployment.'
}

Write-Host ''
Write-Host 'Local development dependencies are ready. Run these commands in separate PowerShell windows:'
Write-Host '  cd ruoyi-ui; npm run dev'
if (Test-Path $businessDir) {
    Write-Host '  cd ..\demo-ronghe\dongying-vue; npm run dev'
}
Write-Host "  cd server; .\mvnw.cmd spring-boot:run `"-Dspring-boot.run.profiles=local`""
Write-Host ''
Write-Host 'Admin frontend: http://127.0.0.1:5175/'
Write-Host 'Business frontend: http://127.0.0.1:5173/'
Write-Host 'Backend health: http://127.0.0.1:8080/actuator/health'
