[CmdletBinding()]
param(
    [switch]$SkipBusinessFrontend,
    [switch]$WithMqtt,
    [switch]$WithSimulator,
    [switch]$WithQaVideo,
    [switch]$SkipFrontendInstall,
    [switch]$SkipWait,
    [int]$DbPort = 0,
    [int]$ApiPort = 8081,
    [int]$AdminPort = 5175,
    [int]$BusinessPort = 5173,
    [int]$SimulatorPort = 8766,
    [ValidateRange(1024, 65535)]
    [int]$QaVideoRtspPort = 8554,
    [ValidateRange(1024, 65535)]
    [int]$QaVideoHlsPort = 8888,
    [ValidateRange(1024, 65535)]
    [int]$QaVideoApiPort = 9997,
    [string]$BusinessRoot = '',
    [string]$SimulatorRoot = '',
    [string]$JavaHome = '',
    [string]$JavaTempDir = 'C:\Temp',
    [ValidateRange(20, 600)]
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$adminDir = Join-Path $repoRoot 'ruoyi-ui'
$serverDir = Join-Path $repoRoot 'server'
$composeFile = Join-Path $repoRoot 'deploy\compose.yml'
$qaVideoComposeFile = Join-Path $repoRoot 'deploy\compose.qa-video.yml'
$businessCandidates = @(
    (Join-Path (Split-Path -Parent $repoRoot) 'rwurenji\_rong\dongying-vue'),
    (Join-Path (Split-Path -Parent $repoRoot) 'demo-ronghe\dongying-vue')
)
$simulatorCandidates = @(
    (Join-Path (Split-Path -Parent $repoRoot) 'rwurenji\_rong\tools\device-simulator'),
    (Join-Path (Split-Path -Parent $repoRoot) 'demo-ronghe\tools\device-simulator')
)
$businessDir = if ($BusinessRoot) { (Resolve-Path -LiteralPath $BusinessRoot).Path } else { $businessCandidates | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'package.json') } | Select-Object -First 1 }
$simulatorDir = if ($SimulatorRoot) { (Resolve-Path -LiteralPath $SimulatorRoot).Path } else { $simulatorCandidates | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'server.py') } | Select-Object -First 1 }
if (-not $businessDir) { $businessDir = '' }
if (-not $simulatorDir) { $simulatorDir = '' }
. (Join-Path $PSScriptRoot 'local-service-state.ps1')
$runtimeDir = Get-LocalRuntimeDirectory -RepositoryRoot $repoRoot
$stateFile = Join-Path $runtimeDir 'services.json'
$powershell = (Get-Process -Id $PID).Path
if (-not $powershell) { $powershell = Join-Path $PSHOME 'powershell.exe' }

function Assert-Command {
    param([Parameter(Mandatory)][string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "缺少必需命令：$Name"
    }
}

function Quote-PowerShell {
    param([Parameter(Mandatory)][string]$Value)
    return "'" + $Value.Replace("'", "''") + "'"
}

function Resolve-Java17Home {
    param([string]$RequestedHome)

    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($RequestedHome) { [void]$candidates.Add($RequestedHome) }
    if ($env:JAVA_HOME) { [void]$candidates.Add($env:JAVA_HOME) }
    foreach ($root in @('D:\DevTools', 'C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium')) {
        if (Test-Path -LiteralPath $root) {
            foreach ($candidate in @(Get-ChildItem -LiteralPath $root -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)) {
                [void]$candidates.Add($candidate)
            }
        }
    }

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        try { $resolvedHome = (Resolve-Path -LiteralPath $candidate -ErrorAction Stop).Path } catch { continue }
        $javaExe = Join-Path $resolvedHome 'bin\java.exe'
        if (-not (Test-Path -LiteralPath $javaExe)) { continue }
        $version = (& $javaExe --version 2>$null) -join ' '
        if ($version -match '\b17(?:\.\d+)?') { return $resolvedHome }
    }
    return $null
}

function Start-LoggedService {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$WorkingDirectory,
        [Parameter(Mandatory)][string]$Command
    )

    $launchId = [Guid]::NewGuid().ToString('N')
    $stdout = Join-Path $runtimeDir "$Name-$launchId.out.log"
    $stderr = Join-Path $runtimeDir "$Name-$launchId.err.log"
    $entryFile = Join-Path $runtimeDir "$Name-$launchId.ps1"
    [IO.File]::WriteAllText($entryFile, ("`$ErrorActionPreference = 'Stop'`r`n" + $Command + "`r`nexit `$LASTEXITCODE"), [Text.UTF8Encoding]::new($true))
    $process = Start-Process -FilePath $powershell -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $entryFile + '"')
    ) -WorkingDirectory $WorkingDirectory -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    return [ordered]@{
        name = $Name
        kind = 'process'
        reused = $false
        pid = $process.Id
        start_ticks = $process.StartTime.ToUniversalTime().Ticks.ToString()
        entry_file = $entryFile
        members = @()
        cwd = $WorkingDirectory
        stdout = $stdout
        stderr = $stderr
        started_at = [DateTimeOffset]::Now.ToString('o')
    }
}

function Register-LocalService {
    param([string]$Name, $Service)
    $services[$Name] = $Service
    Save-LocalServiceState -Path $stateFile -Services $services
}

function Register-LocalContainer {
    param([string]$Name)
    $containerId = & docker inspect -f '{{.Id}}' $Name 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $containerId) { throw "无法记录容器：$Name" }
    Register-LocalService -Name $Name -Service ([ordered]@{name=$Name; kind='container'; container_id=$containerId})
}

function Wait-Http {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Uri
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400) {
                Write-Host "[OK] $Name $Uri"
                return
            }
        }
        catch {
            Start-Sleep -Seconds 2
        }
    } while ((Get-Date) -lt $deadline)
    throw "$Name 未在 ${TimeoutSeconds}s 内就绪：$Uri"
}

function Test-Endpoint {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [switch]$RequireMapConfig
    )
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 3
        if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 400) { return $false }
        if (-not $RequireMapConfig) { return $true }
        $config = $response.Content | ConvertFrom-Json
        return [bool]($config.manifest -is [string] -and $config.manifest.Trim())
    }
    catch { return $false }
}

function Test-SimulatorVideoEnabled {
    param([Parameter(Mandatory)][int]$Port)
    try {
        $status = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/status" -TimeoutSec 3
        return [bool]$status.video_config.enabled
    }
    catch { return $false }
}

function Find-MapProviderOrigin {
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
        if (Test-Endpoint -Uri "${origin}:$Port/map-config.json" -RequireMapConfig) {
            return "${origin}:$Port"
        }
    }
    return $null
}

function Get-ContainerHealth {
    param([Parameter(Mandatory)][string]$Name)
    $ErrorActionPreference = 'Continue'
    $value = & docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $Name 2>$null
    if (-not $value) { return $null }
    return [string]$value
}

function Get-ContainerHostPort {
    param([Parameter(Mandatory)][string]$Name)
    # HostConfig persists for stopped containers; `docker port` only sees live bindings.
    $bindings = & docker inspect -f '{{json .HostConfig.PortBindings}}' $Name
    if ($LASTEXITCODE -ne 0) { return 0 }
    $configured = $bindings | ConvertFrom-Json
    if (-not $configured.'5432/tcp') { return 0 }
    return [int]$configured.'5432/tcp'[0].HostPort
}

function Wait-ContainerHealthy {
    param(
        [Parameter(Mandatory)][string]$Name,
        [int]$Seconds = 180
    )
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        $health = Get-ContainerHealth -Name $Name
        if ($health -eq 'healthy') {
            Write-Host "[OK] 容器 $Name 已健康。"
            return
        }
        if ($health -in @('exited', 'dead')) {
            throw "容器 $Name 已退出，无法继续启动。"
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "容器 $Name 未在 ${Seconds}s 内进入 healthy；当前状态：$health。"
}

function Get-DockerDesktopExecutable {
    $candidates = [System.Collections.Generic.List[string]]::new()
    foreach ($candidate in @(
        (Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Docker\Docker\Docker Desktop.exe'),
        (Join-Path $env:LOCALAPPDATA 'Docker\Docker Desktop.exe'),
        'D:\DevTools\DockerDesktop\Docker Desktop.exe'
    )) {
        if ($candidate) { [void]$candidates.Add($candidate) }
    }
    $dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
    if ($dockerCommand -and $dockerCommand.Source) {
        $dockerRoot = Split-Path -Parent (Split-Path -Parent $dockerCommand.Source)
        [void]$candidates.Add((Join-Path $dockerRoot 'Docker Desktop.exe'))
        [void]$candidates.Add((Join-Path (Split-Path -Parent $dockerRoot) 'Docker Desktop.exe'))
    }
    return $candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -Unique -First 1
}

function Test-DockerEngine {
    $probe = [Diagnostics.Process]::new()
    $probe.StartInfo.FileName = (Get-Command docker).Source
    $probe.StartInfo.Arguments = 'info --format "{{.ServerVersion}}"'
    $probe.StartInfo.UseShellExecute = $false
    $probe.StartInfo.CreateNoWindow = $true
    $probe.StartInfo.RedirectStandardOutput = $true
    $probe.StartInfo.RedirectStandardError = $true
    try {
        [void]$probe.Start()
        $stdoutRead = $probe.StandardOutput.ReadToEndAsync()
        $stderrRead = $probe.StandardError.ReadToEndAsync()
        if (-not $probe.WaitForExit(5000)) {
            $probe.Kill() # Only this function's own disposable health probe.
            return $false
        }
        return $probe.ExitCode -eq 0
    } finally { $probe.Dispose() }
}

function Repair-DockerRuntimeSockets {
    if (Get-Process -Name 'Docker Desktop','com.docker.backend' -ErrorAction SilentlyContinue) { return }
    $locations = @(
        @{ relative='Docker\run'; allowed=@('dockerInference','dockerEthernetVfkit','userAnalyticsOtlpHttp.sock') },
        @{ relative='docker-secrets-engine'; allowed=@('engine.sock') }
    )
    foreach ($location in $locations) {
        $expected = [IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA $location.relative))
        if (-not (Test-Path -LiteralPath $expected)) { continue }
        $directory = (Resolve-Path -LiteralPath $expected).Path
        if ($directory -ne $expected) { throw 'Docker 临时目录不在预期位置，未执行修复。' }
        $entries = @(Get-ChildItem -LiteralPath $directory -Force)
        if ($entries.Count -eq 0) { continue }
        $otherEntries = @($entries | Where-Object {
            $_.Name -notin $location.allowed -or -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint)
        })
        if ($otherEntries.Count -gt 0) { throw "Docker 临时目录含其他内容，未自动处理：$directory" }
        $backup = "$directory-socket-backup-$([Guid]::NewGuid().ToString('N'))"
        Move-Item -LiteralPath $directory -Destination $backup
        New-Item -ItemType Directory -Path $directory | Out-Null
        Write-Host "[修复] 已保留 Docker 遗留套接字目录：$backup"
    }
}

function Ensure-DockerEngine {
    if (Test-DockerEngine) { return }

    $desktopProcess = Get-Process -Name 'Docker Desktop' -ErrorAction SilentlyContinue
    $backendProcess = Get-Process -Name 'com.docker.backend' -ErrorAction SilentlyContinue
    if (-not $desktopProcess -and -not $backendProcess) { Repair-DockerRuntimeSockets }
    $desktopPath = Get-DockerDesktopExecutable
    if (-not $desktopProcess -and $desktopPath) {
        Write-Host "[准备] Docker Engine 未就绪，启动 Docker Desktop：$desktopPath"
        Start-Process -FilePath $desktopPath -WorkingDirectory (Split-Path -Parent $desktopPath) -WindowStyle Hidden | Out-Null
    }
    elseif ($desktopProcess) {
        Write-Host '[等待] Docker Desktop 已启动，等待 Linux 引擎就绪...'
    }
    else {
        $context = (& docker context show 2>$null | Select-Object -First 1)
        throw "Docker Engine 未就绪，且未找到 Docker Desktop。当前 Docker context：$context。请先安装或启动 Docker Desktop。"
    }

    $deadline = (Get-Date).AddSeconds(180)
    do {
        Start-Sleep -Seconds 2
        if (Test-DockerEngine) {
            Write-Host '[OK] Docker Linux 引擎已就绪。'
            return
        }
    } while ((Get-Date) -lt $deadline)

    $context = (& docker context show 2>$null | Select-Object -First 1)
    throw "Docker Desktop 已启动，但 180 秒内 Linux 引擎仍未就绪。当前 Docker context：$context。请检查 Docker Desktop 状态后重试。"
}

function Test-LocalPort {
    param([Parameter(Mandatory)][int]$Port)
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

function Get-ListenerCommandLine {
    param([Parameter(Mandatory)][int]$Port)
    $owners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique)
    foreach ($owner in $owners) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$owner" -ErrorAction SilentlyContinue
        if ($process -and $process.CommandLine) { return [string]$process.CommandLine }
    }
    return ''
}

function Assert-SimulatorMapOrigin {
    param(
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][int]$BusinessPort
    )
    if (-not (Test-Endpoint -Uri "http://127.0.0.1:$Port/")) { return }
    $commandLine = Get-ListenerCommandLine -Port $Port
    $expected = "--map-origin http://127.0.0.1:$BusinessPort"
    if ($commandLine -and $commandLine -notmatch [regex]::Escape($expected)) {
        throw "设备模拟器端口 $Port 已有实例，但地图来源不是 http://127.0.0.1:$BusinessPort；请先停止该实例，再使用当前启动脚本启动。"
    }
}

function Test-ApiRoute {
    param(
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][string]$Path
    )
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port$Path" -TimeoutSec 3
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 400
    }
    catch {
        # 该受保护路由未登录时应返回 401；404 说明 QA Controller 没有加载。
        $statusCode = $_.Exception.Response.StatusCode.value__
        return $statusCode -eq 401
    }
}

function Reuse-Or-Start {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$ProbeUri,
        [Parameter(Mandatory)][string]$WorkingDirectory,
        [Parameter(Mandatory)][AllowEmptyString()][string]$Command,
        [switch]$RequireMapConfig
    )
    if (Test-Endpoint -Uri $ProbeUri -RequireMapConfig:$RequireMapConfig) {
        Write-Host "[复用] $Name 已在运行：$ProbeUri"
        if ($services.Contains($Name) -and (Test-OwnedService $services[$Name])) { return $services[$Name] }
        return [ordered]@{ name = $Name; kind = 'process'; reused = $true; probe = $ProbeUri; started_at = $null }
    }
    $port = ([Uri]$ProbeUri).Port
    if (Test-LocalPort -Port $port) { throw "$Name 端口 $port 已被占用但健康检查未通过；未停止或覆盖已有进程。" }
    $started = Start-LoggedService -Name $Name -WorkingDirectory $WorkingDirectory -Command $Command
    $started.probe = $ProbeUri
    $started.port = $port
    return $started
}

New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
$operationLock = [IO.File]::Open((Join-Path $runtimeDir 'operation.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
try {
$services = Read-LocalServiceState -Path $stateFile
Assert-Command docker
Assert-Command node
Assert-Command npm
if ($WithSimulator) { Assert-Command python }
if ($WithQaVideo) {
    if (-not $WithSimulator) { throw '-WithQaVideo 必须与 -WithSimulator 一起使用。' }
    if ($QaVideoRtspPort -eq $QaVideoHlsPort -or $QaVideoRtspPort -eq $QaVideoApiPort -or $QaVideoHlsPort -eq $QaVideoApiPort) {
        throw 'QA 视频的 RTSP、HLS、API 端口必须互不相同。'
    }
    foreach ($secret in @($env:QA_VIDEO_PUBLISH_PASSWORD, $env:QA_VIDEO_READ_PASSWORD)) {
        if ($secret -notmatch '^[A-Za-z0-9_-]{24,128}$') {
            throw '启用 QA 视频前请在当前 PowerShell 会话设置 QA_VIDEO_PUBLISH_PASSWORD 和 QA_VIDEO_READ_PASSWORD（24-128 位 base64url 随机值）。'
        }
    }
    if ($env:QA_VIDEO_PUBLISH_PASSWORD -eq $env:QA_VIDEO_READ_PASSWORD) {
        throw 'QA 视频发布密码和读取密码必须不同。'
    }
}

if (-not (Test-Path -LiteralPath $composeFile)) { throw "Compose 文件不存在：$composeFile" }
if ($WithQaVideo -and -not (Test-Path -LiteralPath $qaVideoComposeFile)) { throw "QA 视频 Compose 文件不存在：$qaVideoComposeFile" }
if (-not (Test-Path -LiteralPath $adminDir)) { throw "管理前端目录不存在：$adminDir" }
if (-not (Test-Path -LiteralPath $serverDir)) { throw "后端目录不存在：$serverDir" }
if ($WithSimulator -and -not (Test-Path -LiteralPath (Join-Path $simulatorDir 'server.py'))) {
    throw "设备模拟器入口不存在：$simulatorDir\server.py"
}
if ($WithQaVideo -and (Test-Endpoint -Uri "http://127.0.0.1:$SimulatorPort/") -and -not (Test-SimulatorVideoEnabled -Port $SimulatorPort)) {
    throw "设备模拟器端口 $SimulatorPort 已有运行实例，但未启用光电测试推流；请先停止该实例，再使用 -WithQaVideo 重新启动。"
}

Ensure-DockerEngine

$resolvedJavaHome = Resolve-Java17Home -RequestedHome $JavaHome
if (-not $resolvedJavaHome) {
    throw '未找到 Java 17，请使用 -JavaHome 指定 JDK 17 目录。'
}
$env:JAVA_HOME = $resolvedJavaHome
$javaBin = Join-Path $resolvedJavaHome 'bin'
$env:Path = "$javaBin;" + (($env:Path -split ';' | Where-Object { $_ -and $_ -notmatch 'android-studio\\jbr\\bin' }) -join ';')
Assert-Command java
$javaTempDir = (New-Item -ItemType Directory -Force -Path $JavaTempDir).FullName
$javaTempDir = (Resolve-Path -LiteralPath $javaTempDir).Path
$javaTempDirJvm = $javaTempDir.Replace('\', '/')
$env:TEMP = $javaTempDir
$env:TMP = $javaTempDir
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$javaTempDirJvm"

New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null

Write-Host '[1/5] 启动隔离 PostgreSQL/PostGIS...'
$dbHealth = Get-ContainerHealth -Name 'deploy-db-1'
if ($dbHealth) {
    $DbPort = Get-ContainerHostPort -Name 'deploy-db-1'
    if ($dbHealth -ne 'healthy') {
        & docker start deploy-db-1
        if ($LASTEXITCODE -ne 0) { throw '现有数据库容器启动失败。' }
        Register-LocalContainer -Name 'deploy-db-1'
        Wait-ContainerHealthy -Name 'deploy-db-1' -Seconds $TimeoutSeconds
    }
    Write-Host "[复用] 数据库容器 deploy-db-1，保留现有数据卷与宿主机端口 $DbPort"
}
else {
    if ($DbPort -le 0) {
        $DbPort = if (Test-LocalPort -Port 5432) { 25432 } else { 5432 }
    }
    $env:DB_PORT = [string]$DbPort
    try { & docker compose -f $composeFile up -d db } finally { Remove-Item Env:DB_PORT -ErrorAction SilentlyContinue }
    if ($LASTEXITCODE -ne 0) { throw '数据库容器启动失败。' }
    Register-LocalContainer -Name 'deploy-db-1'
    Wait-ContainerHealthy -Name 'deploy-db-1' -Seconds $TimeoutSeconds
}
if ($DbPort -le 0) { throw '无法解析数据库宿主机端口，请使用 -DbPort 显式指定。' }
Register-LocalContainer -Name 'deploy-db-1'
# Read credentials into inherited process environment only, never the launcher or logs.
$env:DB_USER = & docker exec deploy-db-1 printenv POSTGRES_USER
$env:DB_PASSWORD = & docker exec deploy-db-1 printenv POSTGRES_PASSWORD
if (-not $env:DB_USER -or -not $env:DB_PASSWORD) { throw '无法读取本机数据库连接配置。' }

if ($WithMqtt -or $WithSimulator) {
    Write-Host '[2/5] 启动本机 MQTT（qa profile）...'
    $mqttHealth = Get-ContainerHealth -Name 'deploy-mosquitto-1'
    if ($mqttHealth -eq 'running') {
        Write-Host '[复用] MQTT 容器 deploy-mosquitto-1 已在运行。'
    }
    else {
        if ($mqttHealth) { & docker start deploy-mosquitto-1 }
        else { & docker compose -f $composeFile --profile qa up -d mosquitto }
        if ($LASTEXITCODE -ne 0) { throw 'MQTT 容器启动失败。' }
        Register-LocalContainer -Name 'deploy-mosquitto-1'
        $mqttDeadline = (Get-Date).AddSeconds($TimeoutSeconds)
        while (-not (Test-LocalPort -Port 1883) -and (Get-Date) -lt $mqttDeadline) { Start-Sleep -Seconds 2 }
        if (-not (Test-LocalPort -Port 1883)) { throw "MQTT 未在 ${TimeoutSeconds}s 内监听 1883。" }
    }
    Register-LocalContainer -Name 'deploy-mosquitto-1'
}
else {
    Write-Host '[2/5] 未请求 MQTT；跳过 Mosquitto。'
}

if ($WithQaVideo) {
    foreach ($port in @($QaVideoRtspPort, $QaVideoHlsPort, $QaVideoApiPort)) {
        if (Test-LocalPort -Port $port) {
            throw "QA 视频端口 $port 已被占用；请换端口后重试（Android 模拟器常占用 8554，可使用 -QaVideoRtspPort 18554）。"
        }
    }
    Write-Host "[2/5] 启动本机 QA 视频媒体服务（RTSP $QaVideoRtspPort / HLS $QaVideoHlsPort / API $QaVideoApiPort）..."
    $previousVideoPorts = @{}
    foreach ($entry in @{
        QA_VIDEO_RTSP_PORT = [string]$QaVideoRtspPort
        QA_VIDEO_HLS_PORT = [string]$QaVideoHlsPort
        QA_VIDEO_API_PORT = [string]$QaVideoApiPort
    }.GetEnumerator()) {
        $name = $entry.Key
        $previousVideoPorts[$name] = [Environment]::GetEnvironmentVariable($name)
        Set-Item -LiteralPath ('Env:' + $name) -Value $entry.Value
    }
    try {
        & docker compose -f $qaVideoComposeFile --profile qa-video up -d --wait qa-video
        if ($LASTEXITCODE -ne 0) { throw 'QA 视频媒体服务启动失败。' }
    }
    finally {
        foreach ($name in $previousVideoPorts.Keys) {
            if ($null -eq $previousVideoPorts[$name]) { Remove-Item -LiteralPath ('Env:' + $name) -ErrorAction SilentlyContinue }
            else { Set-Item -LiteralPath ('Env:' + $name) -Value $previousVideoPorts[$name] }
        }
    }
}

$springProfiles = if ($WithSimulator) { 'local,qa' } else { 'local' }
$backendVideoEnvironment = if ($WithQaVideo) {
    "`$env:APP_VIDEO_QA_ENABLED = 'true'`n" +
    "`$env:APP_VIDEO_MEDIA_USERNAME = 'qa-platform'`n" +
    "`$env:APP_VIDEO_MEDIA_PASSWORD = [Environment]::GetEnvironmentVariable('QA_VIDEO_READ_PASSWORD')`n" +
    "`$env:APP_VIDEO_MEDIA_API_ORIGIN = 'http://127.0.0.1:$QaVideoApiPort'`n" +
    "`$env:APP_VIDEO_MEDIA_HLS_ORIGIN = 'http://127.0.0.1:$QaVideoHlsPort'`n"
} else { '' }
$backendCommand = @"
${backendVideoEnvironment}
`$env:DB_URL = 'jdbc:postgresql://127.0.0.1:5432/houtaiguanli'
`$env:APP_DEV_SEED_ENABLED = 'false'
`$env:APP_WEATHER_RISK_QA_ENABLED = 'false'
`$env:SPRING_PROFILES_ACTIVE = '$springProfiles'
`$env:SERVER_PORT = '$ApiPort'
& '.\mvnw.cmd' 'spring-boot:run' '-Dspring-boot.run.profiles=$springProfiles' '-Dspring-boot.run.jvmArguments=-Djava.io.tmpdir=$javaTempDirJvm -Djdk.net.unixdomain.tmpdir=$javaTempDirJvm' '-Dspring-boot.run.arguments=--app.dev-seed.enabled=false --app.weather-risk.qa.enabled=false'
"@
$apiReady = Test-Endpoint -Uri "http://127.0.0.1:$ApiPort/actuator/health/readiness"
$apiNeedsSimulatorRoute = $false
$apiNeedsQaVideoRoute = $false
if ($apiReady) {
    $apiNeedsSimulatorRoute = $WithSimulator -and -not (Test-ApiRoute -Port $ApiPort -Path '/api/v1/local-interface-simulator/context')
    $apiNeedsQaVideoRoute = $WithQaVideo -and -not (Test-ApiRoute -Port $ApiPort -Path '/api/v1/local-interface-simulator/video-streams/not-a-task')
}
if ($apiNeedsSimulatorRoute -or $apiNeedsQaVideoRoute) {
    throw '现有后端没有所需模拟接口，请先使用关闭脚本停止本项目服务，再启动。'
}
Write-Host "[3/5] 启动后端（$springProfiles，演示种子保持关闭）..."
$backendCommand = $backendCommand.Replace('127.0.0.1:5432', "127.0.0.1:$DbPort").Replace('127.0.0.1:8081', "127.0.0.1:$ApiPort")
Register-LocalService -Name 'api' -Service (Reuse-Or-Start -Name 'api' -ProbeUri "http://127.0.0.1:$ApiPort/actuator/health/readiness" -WorkingDirectory $serverDir -Command $backendCommand)

if (-not $SkipFrontendInstall -and -not (Test-Path -LiteralPath (Join-Path $adminDir 'node_modules'))) {
    Write-Host '安装管理前端依赖...'
    Push-Location $adminDir
    try { npm ci } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw '管理前端依赖安装失败。' }
}

$runBusiness = -not $SkipBusinessFrontend -and (Test-Path -LiteralPath (Join-Path $businessDir 'package.json'))
$existingBusinessOrigin = Find-MapProviderOrigin -Port $BusinessPort
$businessOrigin = if ($existingBusinessOrigin) { $existingBusinessOrigin } else { "http://127.0.0.1:$BusinessPort" }
if ($runBusiness) {
    if ($existingBusinessOrigin) {
        Write-Host "[复用] 业务前台地图服务已就绪：$existingBusinessOrigin"
        Register-LocalService -Name 'business' -Service (Reuse-Or-Start -Name 'business' -ProbeUri "$businessOrigin/map-config.json" -WorkingDirectory $businessDir -Command '' -RequireMapConfig)
    }
    else {
        if (Test-LocalPort -Port $BusinessPort) {
            throw "业务前台端口 $BusinessPort 已被占用，但该服务没有提供有效的 /map-config.json；请释放端口后重试。"
        }
        if (-not $SkipFrontendInstall -and -not (Test-Path -LiteralPath (Join-Path $businessDir 'node_modules'))) {
            Write-Host '安装业务前台依赖...'
            Push-Location $businessDir
            try { npm ci } finally { Pop-Location }
            if ($LASTEXITCODE -ne 0) { throw '业务前台依赖安装失败。' }
        }
        $businessCommand = "`$env:APP_API_PROXY_TARGET = 'http://127.0.0.1:$ApiPort'`n& 'npm.cmd' 'run' 'dev' '--' '--host' '127.0.0.1' '--port' '$BusinessPort' '--strictPort'"
        Write-Host '[4/5] 启动业务前台（地图资源提供方）...'
        Register-LocalService -Name 'business' -Service (Reuse-Or-Start -Name 'business' -ProbeUri "$businessOrigin/map-config.json" -WorkingDirectory $businessDir -Command $businessCommand -RequireMapConfig)
    }
}
else {
    Write-Warning '未启动业务前台；管理端地图代理将使用已发现的业务地图服务，否则地图检查可能失败。'
}

$adminCommand = @"
`$env:ADMIN_MAP_PROXY_TARGET = '$businessOrigin'
`$env:ADMIN_API_PROXY_TARGET = 'http://127.0.0.1:$ApiPort'
& 'npm.cmd' 'run' 'dev' '--' '--host' '127.0.0.1' '--port' '$AdminPort' '--strictPort'
"@
Write-Host '[4/5] 启动管理前端（地图代理指向业务前台）...'
Register-LocalService -Name 'admin' -Service (Reuse-Or-Start -Name 'admin' -ProbeUri "http://127.0.0.1:$AdminPort/map-config.json" -WorkingDirectory $adminDir -Command $adminCommand -RequireMapConfig)

if ($WithSimulator) {
    $simulatorVideoEnvironment = if ($WithQaVideo) {
        "`$env:QA_VIDEO_ENABLED = 'true'`n" +
        "`$env:QA_VIDEO_RTSP_BASE = 'rtsp://127.0.0.1:$QaVideoRtspPort'`n" +
        "`$env:QA_VIDEO_PUBLISH_PASSWORD = [Environment]::GetEnvironmentVariable('QA_VIDEO_PUBLISH_PASSWORD')`n"
    } else { '' }
    $simulatorCommand = @"
${simulatorVideoEnvironment}
& 'python.exe' $(Quote-PowerShell (Join-Path $simulatorDir 'server.py')) '--port' '$SimulatorPort' '--map-origin' 'http://127.0.0.1:$BusinessPort'
"@
    Write-Host "[5/5] 启动设备模拟器（仅本机 $SimulatorPort）..."
    Assert-SimulatorMapOrigin -Port $SimulatorPort -BusinessPort $BusinessPort
    Register-LocalService -Name 'simulator' -Service (Reuse-Or-Start -Name 'simulator' -ProbeUri "http://127.0.0.1:$SimulatorPort/" -WorkingDirectory $simulatorDir -Command $simulatorCommand)
}
else {
    Write-Host '[5/5] 未请求设备模拟器。'
}

Save-LocalServiceState -Path $stateFile -Services $services
Write-Host "运行状态与日志目录：$runtimeDir"
Write-Host "进程清单：$stateFile"

if (-not $SkipWait) {
    Wait-Http -Name 'API readiness' -Uri "http://127.0.0.1:$ApiPort/actuator/health/readiness"
    Wait-Http -Name '管理前端' -Uri "http://127.0.0.1:$AdminPort/"
    if ($services.Contains('business')) {
        Wait-Http -Name '业务前台' -Uri "$businessOrigin/"
        Wait-Http -Name '业务前台地图配置' -Uri "$businessOrigin/map-config.json"
    }
    if ($existingBusinessOrigin -or $services.Contains('business')) {
        Wait-Http -Name '管理端地图代理' -Uri "http://127.0.0.1:$AdminPort/map-config.json"
    }
    if ($services.Contains('simulator')) { Wait-Http -Name '设备模拟器' -Uri "http://127.0.0.1:$SimulatorPort/" }
}
foreach ($name in @($services.Keys)) {
    $service = $services[$name]
    if ($service.kind -eq 'process' -and -not $service.reused -and (Test-OwnedService $service)) {
        $service.members = @(Get-OwnedProcessTree $service)
    }
}
Save-LocalServiceState -Path $stateFile -Services $services

Write-Host ''
Write-Host '启动完成。建议随后执行：'
Write-Host "  & '$PSScriptRoot\smoke-system.ps1' -RequireBusinessFrontend:$($services.Contains('business')) -RequireSimulator:$($services.Contains('simulator'))"
} finally {
    $operationLock.Dispose()
}
