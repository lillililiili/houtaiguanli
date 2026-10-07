[CmdletBinding()]
param(
    [switch]$OpenBrowser,
    [switch]$SkipBusinessFrontend,
    [switch]$SkipFrontendInstall,
    [switch]$WithQaVideo,
    [string]$BusinessRoot = '',
    [string]$SimulatorRoot = '',
    [string]$JavaHome = '',
    [ValidateRange(20, 600)]
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$startScript = Join-Path $PSScriptRoot 'start-local.ps1'
if (-not (Test-Path -LiteralPath $startScript)) {
    throw "启动脚本不存在：$startScript"
}

$startParams = @{
    WithMqtt = $true
    WithSimulator = $true
    TimeoutSeconds = $TimeoutSeconds
}
if ($SkipBusinessFrontend) { $startParams.SkipBusinessFrontend = $true }
if ($SkipFrontendInstall) { $startParams.SkipFrontendInstall = $true }
if ($WithQaVideo) { $startParams.WithQaVideo = $true }
if ($BusinessRoot) { $startParams.BusinessRoot = $BusinessRoot }
if ($SimulatorRoot) { $startParams.SimulatorRoot = $SimulatorRoot }
if ($JavaHome) { $startParams.JavaHome = $JavaHome }

Write-Host '=== 一键启动：Docker + 数据库 + 后端 + 管理端 + 业务前台 + 模拟器 ==='
& $startScript @startParams
if ($LASTEXITCODE -ne 0) {
    throw "项目启动失败，退出码：$LASTEXITCODE"
}

if ($OpenBrowser) {
    Start-Process 'http://127.0.0.1:5175/login?redirect=/'
    Start-Process 'http://127.0.0.1:5173/'
    Start-Process 'http://127.0.0.1:8766/'
}

Write-Host ''
Write-Host '一键启动完成：'
Write-Host '  管理端： http://127.0.0.1:5175/login?redirect=/'
Write-Host '  业务端： http://127.0.0.1:5173/'
Write-Host '  模拟器： http://127.0.0.1:8766/'
Write-Host '  一键关闭服务：双击 一键关闭.cmd（保留数据库和模拟历史）'
