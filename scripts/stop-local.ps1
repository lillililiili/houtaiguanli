[CmdletBinding()]
param([switch]$WithInfrastructure)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-service-state.ps1')
$runtimeDir = Get-LocalRuntimeDirectory -RepositoryRoot (Split-Path -Parent $PSScriptRoot)
$stateFile = Join-Path $runtimeDir 'services.json'
if (-not (Test-Path -LiteralPath $stateFile)) {
    Write-Host '没有找到本次启动的进程清单。'
    return
}

$operationLock = [IO.File]::Open((Join-Path $runtimeDir 'operation.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
try {
    $services = Read-LocalServiceState -Path $stateFile
    $unmanaged = @($services.Values | Where-Object { $_.kind -eq 'process' -and $_.reused })
    if ($WithInfrastructure -and $unmanaged.Count -gt 0) {
        throw '有复用的非托管应用，未停止数据库/MQTT。请先在原启动窗口关闭应用，或仅运行 stop-local.ps1。'
    }
    # Stop all transports through the simulator before terminating its server process.
    if ($services.Contains('simulator') -and (Test-OwnedService $services.simulator) -and
        (Get-NetTCPConnection -State Listen -LocalPort $services.simulator.port -ErrorAction SilentlyContinue)) {
        $origin = ([Uri]$services.simulator.probe).GetLeftPart([UriPartial]::Authority)
        try {
            $stopped = Invoke-RestMethod -Method Post -Uri "$origin/api/realtime/control" `
                -ContentType 'application/json' -Body '{"action":"stop_all"}' -TimeoutSec 30
            $simStatus = Invoke-RestMethod -Uri "$origin/api/status" -TimeoutSec 10
            if ($stopped.state -ne 'STOPPED' -or $simStatus.phase -ne 'STOPPED') { throw '模拟器收发尚未停止。' }
        } catch {
            throw '模拟器未确认停止收发；为保留当前运行状态，本次关闭已中止。请查看模拟器状态后重试。'
        }
    }
    foreach ($name in @('simulator', 'admin', 'business', 'api')) {
        if (-not $services.Contains($name)) { continue }
        $service = $services[$name]
        if ($service.reused) {
            Write-Warning "$name 是非托管的已有服务，保持运行。"
            continue
        }
        Write-Host "[停止] $name（校验 PID 与创建时间后停止整个服务进程树）"
        Stop-OwnedService $service
        if ($service.port -and (Get-NetTCPConnection -State Listen -LocalPort $service.port -ErrorAction SilentlyContinue)) {
            throw "$name 端口 $($service.port) 仍有监听，未关闭数据库/MQTT，请检查进程归属。"
        }
        $services.Remove($name)
        Save-LocalServiceState -Path $stateFile -Services $services
    }
    if ($WithInfrastructure) {
        foreach ($name in @('deploy-mosquitto-1', 'deploy-db-1')) {
            if (-not $services.Contains($name)) { continue }
            $service = $services[$name]
            $currentId = & docker inspect -f '{{.Id}}' $name 2>$null
            if ($LASTEXITCODE -ne 0 -or $currentId -ne $service.container_id) {
                throw "容器 $name 的身份已变化，未执行停止。"
            }
            & docker stop --timeout 30 $name
            if ($LASTEXITCODE -ne 0) { throw "停止容器失败：$name" }
            $services.Remove($name)
            Save-LocalServiceState -Path $stateFile -Services $services
        }
    }
    Write-Host '已关闭本脚本管理的服务。数据库文件、容器、历史模拟数据和日志均保留。'
} finally {
    $operationLock.Dispose()
}
