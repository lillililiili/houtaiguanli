[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$runtimeDir = Join-Path ([System.IO.Path]::GetTempPath()) 'houtaiguanlii-local-runtime'
$stateFile = Join-Path $runtimeDir 'services.json'
if (-not (Test-Path -LiteralPath $stateFile)) {
    Write-Host '没有找到本次启动的进程清单。'
    exit 0
}

$services = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
foreach ($service in $services.PSObject.Properties.Value) {
    if ($service.reused -or $null -eq $service.pid) { continue }
    $process = Get-Process -Id ([int]$service.pid) -ErrorAction SilentlyContinue
    if ($process) {
        Write-Host "停止 $($service.name) PID $($service.pid)"
        Stop-Process -Id $process.Id -ErrorAction SilentlyContinue
    }
}
Write-Host '已请求停止本次脚本启动的应用进程；数据库/MQTT 容器保持不变。'
