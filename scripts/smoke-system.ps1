[CmdletBinding()]
param(
    [string]$SeedAccount = 'admin1',
    # 不再猜测本地 admin1 密码。账号改密后，使用旧默认值做冒烟会累计真实失败次数并触发锁定。
    [string]$SeedPassword = $(if ($env:APP_BOOTSTRAP_ADMIN_PASSWORD) { $env:APP_BOOTSTRAP_ADMIN_PASSWORD } else { '' }),
    [switch]$RequireBusinessFrontend,
    [switch]$RequireSimulator,
    [int]$ApiPort = 8081,
    [int]$AdminPort = 5175,
    [int]$BusinessPort = 5173,
    [int]$SimulatorPort = 8766,
    [string]$ReportPath
)

$ErrorActionPreference = 'Stop'
$results = [System.Collections.Generic.List[object]]::new()

function Check-Http {
    param([string]$Name, [string]$Uri, [switch]$Required)
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 8
        $ok = $response.StatusCode -ge 200 -and $response.StatusCode -lt 400
        $results.Add([pscustomobject]@{ name = $Name; uri = $Uri; status = if ($ok) { 'PASS' } else { 'FAIL' }; detail = "HTTP $($response.StatusCode)" })
        if ($ok) { Write-Host "[PASS] $Name - $Uri" } else { Write-Host "[FAIL] $Name - $Uri" }
        return $ok
    }
    catch {
        $results.Add([pscustomobject]@{ name = $Name; uri = $Uri; status = if ($Required) { 'FAIL' } else { 'WARN' }; detail = $_.Exception.Message })
        if ($Required) { Write-Host "[FAIL] $Name - $Uri - $($_.Exception.Message)" } else { Write-Host "[WARN] $Name - $Uri - $($_.Exception.Message)" }
        return $false
    }
}

function Check-MapConfig {
    param([string]$Name, [string]$Uri, [switch]$Required)
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 8
        if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 400) { throw "HTTP $($response.StatusCode)" }
        if (-not $response.Headers['Content-Type'] -or $response.Headers['Content-Type'] -notlike '*application/json*') {
            throw "Content-Type 不是 JSON：$($response.Headers['Content-Type'])"
        }
        $config = $response.Content | ConvertFrom-Json
        if (-not ($config.manifest -is [string]) -or -not $config.manifest.Trim()) { throw '地图配置缺少 manifest' }
        $results.Add([pscustomobject]@{ name = $Name; uri = $Uri; status = 'PASS'; detail = "manifest=$($config.manifest)" })
        Write-Host "[PASS] $Name - $Uri - manifest=$($config.manifest)"
        return $config
    }
    catch {
        $status = if ($Required) { 'FAIL' } else { 'WARN' }
        $results.Add([pscustomobject]@{ name = $Name; uri = $Uri; status = $status; detail = $_.Exception.Message })
        if ($Required) { Write-Host "[FAIL] $Name - $Uri - $($_.Exception.Message)" } else { Write-Host "[WARN] $Name - $Uri - $($_.Exception.Message)" }
        return $null
    }
}

function Find-MapProviderBase {
    param([Parameter(Mandatory)][int]$Port)
    $candidates = [System.Collections.Generic.List[string]]::new()
    $candidates.Add('http://127.0.0.1')
    try {
        foreach ($address in @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop | Select-Object -ExpandProperty IPAddress)) {
            if ($address -and $address -notlike '127.*' -and $address -notlike '169.254.*') {
                $candidates.Add("http://$address")
            }
        }
    }
    catch { }
    foreach ($origin in ($candidates | Select-Object -Unique)) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "${origin}:$Port/map-config.json" -TimeoutSec 3
            if ($response.Headers['Content-Type'] -like '*application/json*') {
                $config = $response.Content | ConvertFrom-Json
                if ($config.manifest -is [string] -and $config.manifest.Trim()) { return "${origin}:$Port" }
            }
        }
        catch { }
    }
    return "http://127.0.0.1:$Port"
}

$apiBase = "http://127.0.0.1:$ApiPort"
$adminBase = "http://127.0.0.1:$AdminPort"
$businessBase = Find-MapProviderBase -Port $BusinessPort
$simulatorBase = "http://127.0.0.1:$SimulatorPort"
$apiHealth = Check-Http -Name 'API readiness' -Uri "$apiBase/actuator/health/readiness" -Required
$admin = Check-Http -Name '管理前端' -Uri "$adminBase/" -Required
$business = Check-Http -Name '业务前台' -Uri "$businessBase/" -Required:$RequireBusinessFrontend
$simulator = Check-Http -Name '设备模拟器' -Uri "$simulatorBase/" -Required:$RequireSimulator
$businessMapConfig = Check-MapConfig -Name '业务前台地图配置' -Uri "$businessBase/map-config.json" -Required:$RequireBusinessFrontend
$adminMapConfig = Check-MapConfig -Name '管理端地图代理' -Uri "$adminBase/map-config.json" -Required:$RequireBusinessFrontend
$mapBusiness = $null -ne $businessMapConfig
$mapAdmin = $null -ne $adminMapConfig
if ($mapBusiness -and $mapAdmin -and $businessMapConfig.manifest -ne $adminMapConfig.manifest) {
    $mapBusiness = $false
    $mapAdmin = $false
    $message = "业务前台与管理端 manifest 不一致：$($businessMapConfig.manifest) != $($adminMapConfig.manifest)"
    $results.Add([pscustomobject]@{ name = '管理端/业务前台地图一致性'; uri = "$adminBase/map-config.json"; status = 'FAIL'; detail = $message })
    Write-Host "[FAIL] 管理端/业务前台地图一致性 - $message"
}

try {
    Invoke-WebRequest -UseBasicParsing -Uri "$apiBase/api/v1/devices?page=1&size=1" -TimeoutSec 8 | Out-Null
    $results.Add([pscustomobject]@{ name = 'API 未登录拦截'; uri = "$apiBase/api/v1/devices"; status = 'FAIL'; detail = '未登录请求未返回 401' })
    Write-Host '[FAIL] API 未登录拦截 - 未登录请求未返回 401'
}
catch {
    $statusCode = $_.Exception.Response.StatusCode.value__
    if ($statusCode -eq 401) {
        $results.Add([pscustomobject]@{ name = 'API 未登录拦截'; uri = "$apiBase/api/v1/devices"; status = 'PASS'; detail = 'HTTP 401' })
        Write-Host '[PASS] API 未登录拦截 - HTTP 401'
    }
    else {
        $results.Add([pscustomobject]@{ name = 'API 未登录拦截'; uri = "$apiBase/api/v1/devices"; status = 'FAIL'; detail = "HTTP $statusCode" })
        Write-Host "[FAIL] API 未登录拦截 - HTTP $statusCode"
    }
}

if ($simulator) {
    try {
        $simulatorStatus = Invoke-RestMethod -Uri "$simulatorBase/api/status" -TimeoutSec 8
        if ($null -eq $simulatorStatus.phase -or $null -eq $simulatorStatus.scene_mode -or $null -eq $simulatorStatus.mqtt_reconnect_count) { throw '模拟器状态缺少 phase/scene_mode/mqtt_reconnect_count' }
        $results.Add([pscustomobject]@{ name = '模拟器运行状态契约'; uri = "$simulatorBase/api/status"; status = 'PASS'; detail = "phase=$($simulatorStatus.phase); scene_mode=$($simulatorStatus.scene_mode); mqtt_reconnect_count=$($simulatorStatus.mqtt_reconnect_count)" })
        Write-Host "[PASS] 模拟器运行状态契约 - phase=$($simulatorStatus.phase); scene_mode=$($simulatorStatus.scene_mode); mqtt_reconnect_count=$($simulatorStatus.mqtt_reconnect_count)"
    }
    catch {
        $results.Add([pscustomobject]@{ name = '模拟器运行状态契约'; uri = "$simulatorBase/api/status"; status = if ($RequireSimulator) { 'FAIL' } else { 'WARN' }; detail = $_.Exception.Message })
        Write-Host "[FAIL] 模拟器运行状态契约 - $($_.Exception.Message)"
    }
}

$authOk = $false
$sessionId = $null
if ([string]::IsNullOrWhiteSpace($SeedPassword)) {
    $results.Add([pscustomobject]@{ name = '登录与设备契约'; uri = "$apiBase/api/v1/auth/login"; status = 'WARN'; detail = '未提供 -SeedPassword，跳过登录请求以避免错误密码触发账号锁定' })
    Write-Host '[WARN] 登录、前端代理或设备列表契约 - 未提供 -SeedPassword，已跳过登录请求'
}
else {
    try {
        $body = @{ account = $SeedAccount; password = $SeedPassword } | ConvertTo-Json -Compress
        $login = Invoke-RestMethod -Method Post -Uri "$apiBase/api/v1/auth/login" -ContentType 'application/json' -Body $body -TimeoutSec 8
        if (-not $login.ok -or -not $login.data.session_id) { throw '登录响应缺少 session_id' }
        $sessionId = $login.data.session_id
        $headers = @{ Authorization = "Bearer $sessionId" }
        $me = Invoke-RestMethod -Uri "$apiBase/api/v1/auth/me" -Headers $headers -TimeoutSec 8
        $adminMe = Invoke-RestMethod -Uri "$adminBase/dev-api/v1/auth/me" -Headers $headers -TimeoutSec 8
        if (-not $me.ok -or $me.data.account -ne $SeedAccount) { throw '后端 /auth/me 账号不匹配' }
        if (-not $adminMe.ok -or $adminMe.data.account -ne $SeedAccount) { throw '管理端 /dev-api 代理账号不匹配' }
        if ($RequireBusinessFrontend) {
            $businessMe = Invoke-RestMethod -Uri "$businessBase/api/v1/auth/me" -Headers $headers -TimeoutSec 8
            if (-not $businessMe.ok -or $businessMe.data.account -ne $SeedAccount) { throw '业务前台 /api 代理账号不匹配' }
        }
        $devices = Invoke-RestMethod -Uri "$apiBase/api/v1/devices?page=1&size=1" -Headers $headers -TimeoutSec 8
        if (-not $devices.ok -or $null -eq $devices.data.items -or $null -eq $devices.data.total) { throw '设备列表不是约定的 items/total 契约' }
        foreach ($item in @($devices.data.items)) {
            foreach ($field in @('device_id', 'device_no', 'connectivity')) {
                if (-not ($item.PSObject.Properties.Name -contains $field)) { throw "设备列表缺少字段 $field" }
            }
        }
        $authOk = $true
        Write-Host '[PASS] 登录、两个前端代理和设备列表契约'
        Invoke-RestMethod -Method Post -Uri "$apiBase/api/v1/auth/logout" -Headers $headers -TimeoutSec 8 | Out-Null
    }
    catch {
        $results.Add([pscustomobject]@{ name = '登录与设备契约'; uri = "$apiBase/api/v1/auth/login"; status = 'FAIL'; detail = $_.Exception.Message })
        Write-Host "[FAIL] 登录、前端代理或设备列表契约 - $($_.Exception.Message)"
    }
}

$report = [ordered]@{
    checked_at = [DateTimeOffset]::Now.ToString('o')
    endpoints = $results
    summary = [ordered]@{ api = $apiHealth; admin = $admin; business = $business; simulator = $simulator; auth = $authOk; map = ($mapBusiness -and $mapAdmin) }
}
if ($ReportPath) {
    $parent = Split-Path -Parent $ReportPath
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
    Write-Host "冒烟报告：$ReportPath"
}

$requiredFailures = @($results | Where-Object { $_.status -eq 'FAIL' })
if ($requiredFailures.Count -gt 0) { exit 1 }
