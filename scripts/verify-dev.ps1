[CmdletBinding()]
param(
    [string]$SeedAccount = 'admin1',
    [string]$SeedPassword = $(if ($env:APP_DEV_SEED_PASSWORD) { $env:APP_DEV_SEED_PASSWORD } else { 'changeme' }),
    [switch]$CheckBusinessFrontend
)

$ErrorActionPreference = 'Stop'

function Test-Endpoint {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Uri
    )

    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 5
        if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400) {
            Write-Host "[OK] $Name - $Uri"
            return $true
        }
    }
    catch {
        Write-Host "[FAIL] $Name - $Uri - $($_.Exception.Message)"
        return $false
    }

    Write-Host "[FAIL] $Name - $Uri"
    return $false
}

$adminOk = Test-Endpoint -Name 'admin frontend' -Uri 'http://127.0.0.1:5175/'
$businessOk = $true
if ($CheckBusinessFrontend) { $businessOk = Test-Endpoint -Name 'business frontend' -Uri 'http://127.0.0.1:5173/' }
$backendOk = Test-Endpoint -Name 'backend' -Uri 'http://127.0.0.1:8081/actuator/health'

if (-not ($adminOk -and $businessOk -and $backendOk)) {
    exit 1
}

try {
    $loginBody = @{ account = $SeedAccount; password = $SeedPassword } | ConvertTo-Json -Compress
    $login = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8081/api/v1/auth/login' -ContentType 'application/json' -Body $loginBody -TimeoutSec 8
    if (-not $login.ok -or -not $login.data.session_id) { throw 'login response did not contain a session_id' }
    $headers = @{ Authorization = "Bearer $($login.data.session_id)" }
    $me = Invoke-RestMethod -Uri 'http://127.0.0.1:8081/api/v1/auth/me' -Headers $headers -TimeoutSec 8
    if (-not $me.ok -or $me.data.account -ne $SeedAccount) { throw 'backend /auth/me did not return the expected account' }
    $adminMe = Invoke-RestMethod -Uri 'http://127.0.0.1:5175/dev-api/v1/auth/me' -Headers $headers -TimeoutSec 8
    if (-not $adminMe.ok -or $adminMe.data.account -ne $SeedAccount) { throw 'Admin Vite /dev-api proxy did not return the expected account' }
    if ($CheckBusinessFrontend) {
        $businessMe = Invoke-RestMethod -Uri 'http://127.0.0.1:5173/api/v1/auth/me' -Headers $headers -TimeoutSec 8
        if (-not $businessMe.ok -or $businessMe.data.account -ne $SeedAccount) { throw 'Business Vite /api proxy did not return the expected account' }
    }
    Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8081/api/v1/auth/logout' -Headers $headers | Out-Null
    Write-Host "[OK] auth session and requested API proxies - $SeedAccount"
}
catch {
    Write-Host "[FAIL] auth session or Vite API proxy - $($_.Exception.Message)"
    exit 1
}
