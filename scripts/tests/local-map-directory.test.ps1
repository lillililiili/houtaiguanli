$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$scriptsRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $scriptsRoot 'local-map-directory.ps1')
$script:assertions = 0
function Assert-True($Value, [string]$Message) {
    if (-not $Value) { throw $Message }
    $script:assertions++
    Write-Output "PASS $Message"
}
function Assert-Throws([scriptblock]$Action, [string]$Message) {
    $failed = $false
    try { & $Action | Out-Null } catch { $failed = $true }
    Assert-True $failed $Message
}
function New-TestManifest([string]$Directory) {
    $package = Join-Path $Directory 'dongying-dev'
    [void][IO.Directory]::CreateDirectory($package)
    [IO.File]::WriteAllText((Join-Path $package 'manifest.json'), '{}')
}

$scratchRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('local-map-directory-' + [Guid]::NewGuid().ToString('N'))))
$originalEnvironment = $env:MAP_DATA_DIR
try {
    [void][IO.Directory]::CreateDirectory($scratchRoot)
    $serverRoot = Join-Path $scratchRoot '后台 服务\server'
    $businessRoot = Join-Path $scratchRoot '业务 一\dongying-vue'
    $otherBusinessRoot = Join-Path $scratchRoot '业务 二\工作目录\dongying-vue'
    [void][IO.Directory]::CreateDirectory($serverRoot)
    [void][IO.Directory]::CreateDirectory($businessRoot)
    [void][IO.Directory]::CreateDirectory($otherBusinessRoot)
    $firstMap = Join-Path $scratchRoot '业务 一\map-data'
    $secondMap = Join-Path $scratchRoot '业务 二\map-data'
    New-TestManifest $firstMap
    New-TestManifest $secondMap
    $explicitMap = Join-Path $scratchRoot "显式 地图's [资料]; `$(`$false)"
    $environmentMap = Join-Path $scratchRoot '环境 地图'
    $env:MAP_DATA_DIR = $environmentMap

    Assert-True ((Resolve-LocalMapDataDirectory -MapDataDir $explicitMap -BusinessRoot $businessRoot -ServerRoot $serverRoot) -eq $explicitMap) 'Explicit parameter wins over inherited environment and business candidates'
    Assert-True ((Resolve-LocalMapDataDirectory -BusinessRoot $businessRoot -ServerRoot $serverRoot) -eq $environmentMap) 'Inherited MAP_DATA_DIR is respected'
    Assert-True ((Resolve-LocalMapDataDirectory -MapDataDir ' 相对 地图 ' -ServerRoot $serverRoot -BaseDirectory $scratchRoot) -eq (Join-Path $scratchRoot '相对 地图')) 'Relative parameter resolves from the launcher caller directory'
    Assert-True ((Resolve-LocalMapDataDirectory -EnvironmentMapDataDir '.\环境 相对' -ServerRoot $serverRoot -BaseDirectory $scratchRoot) -eq (Join-Path $scratchRoot '环境 相对')) 'Relative environment resolves once before child working directories diverge'
    Assert-True ((Resolve-LocalMapDataDirectory -EnvironmentMapDataDir '' -BusinessRoot $businessRoot -ServerRoot $serverRoot) -eq $firstMap) 'Chinese and spaced business path selects its adjacent map directory'
    Assert-True ((Resolve-LocalMapDataDirectory -EnvironmentMapDataDir '' -BusinessRoot $otherBusinessRoot -ServerRoot $serverRoot) -eq $secondMap) 'A different layout selects the second Vite candidate with a built-in manifest'
    $nearerMap = Join-Path $scratchRoot '业务 二\工作目录\map-data'
    New-TestManifest $nearerMap
    Assert-True ((Resolve-LocalMapDataDirectory -EnvironmentMapDataDir '' -BusinessRoot $otherBusinessRoot -ServerRoot $serverRoot) -eq $nearerMap) 'When both candidates exist Vite candidate order is preserved'
    $emptyBusinessRoot = Join-Path $scratchRoot '尚无地图\业务'
    Assert-True ((Resolve-LocalMapDataDirectory -EnvironmentMapDataDir '' -BusinessRoot $emptyBusinessRoot -ServerRoot $serverRoot) -eq (Join-Path $scratchRoot '尚无地图\map-data')) 'An empty installation has one deterministic shared default'
    Assert-True ((Resolve-LocalMapDataDirectory -EnvironmentMapDataDir '' -ServerRoot $serverRoot) -eq (Join-Path $serverRoot 'data\map-data')) 'Backend-only startup needs no business checkout'
    Assert-True ((Resolve-LocalMapDataDirectory -MapDataDir $explicitMap -ServerRoot $serverRoot) -eq $explicitMap) 'Backend-only startup accepts explicit map storage'
    Assert-True ($env:MAP_DATA_DIR -eq $environmentMap) 'Resolution does not modify inherited caller configuration'
    Assert-True ((ConvertTo-LocalAbsoluteDirectory -Path ($firstMap + '\..\map-data\')) -eq $firstMap) 'Canonical directory removes relative segments and trailing separators'
    foreach ($invalidPath in @('C:', 'C:relative', '"C:\quoted"', "C:\map`nother")) {
        Assert-Throws { ConvertTo-LocalAbsoluteDirectory -Path $invalidPath } 'Ambiguous drive-relative, literal quote or multiline path is rejected'
    }

    $assignment = Get-LocalMapEnvironmentCommand -MapDataDirectory $explicitMap
    Invoke-Expression $assignment
    Assert-True ($env:MAP_DATA_DIR -ceq $explicitMap) 'Child environment assignment preserves Chinese, spaces, apostrophe, brackets and shell metacharacters literally'
    $env:MAP_DATA_DIR = $environmentMap
    $record = [ordered]@{ cwd = $businessRoot; map_data_dir = $firstMap }
    $reuse = @{ Name = 'business'; Service = $record; MapDataDirectory = $firstMap; WorkingDirectory = $businessRoot; OwnedProcessIds = @(1101, 1102); ListenerProcessIds = @(1102) }
    Assert-LocalMapServiceReuse @reuse
    Assert-True $true 'Matching map, code directory and owned listener permit reuse'
    $jsonRecord = $record | ConvertTo-Json | ConvertFrom-Json
    $reuse.Service = $jsonRecord
    $reuse.MapDataDirectory = $firstMap.ToUpperInvariant() + '\'
    Assert-LocalMapServiceReuse @reuse
    Assert-True $true 'Persisted JSON state and Windows path case/trailing separators are accepted'
    $reuse.MapDataDirectory = $secondMap
    Assert-Throws { Assert-LocalMapServiceReuse @reuse } 'Changed map directory refuses reuse'
    $reuse.MapDataDirectory = $firstMap
    $reuse.WorkingDirectory = $otherBusinessRoot
    Assert-Throws { Assert-LocalMapServiceReuse @reuse } 'Different business checkout refuses reuse'
    $reuse.WorkingDirectory = $businessRoot
    $reuse.Service = [pscustomobject]@{ cwd = $businessRoot }
    Assert-Throws { Assert-LocalMapServiceReuse @reuse } 'Legacy state without a recorded map directory refuses reuse'
    $reuse.Service = $null
    Assert-Throws { Assert-LocalMapServiceReuse @reuse } 'Unmanaged service without recorded state refuses reuse'
    $reuse.Service = $record
    $reuse.OwnedProcessIds = @()
    Assert-Throws { Assert-LocalMapServiceReuse @reuse } 'Dead or unverified process ownership refuses reuse'
    $reuse.OwnedProcessIds = @(1101, 1102)
    $reuse.ListenerProcessIds = @()
    Assert-Throws { Assert-LocalMapServiceReuse @reuse } 'Unknown listener ownership refuses reuse'
    $reuse.ListenerProcessIds = @(1102, 2202)
    Assert-Throws { Assert-LocalMapServiceReuse @reuse } 'A live wrapper cannot authorize an unrelated process on the same port'

    # Exercise the actual launcher function with fake processes; never start services or query a live port.
    $launcherPath = Join-Path $scriptsRoot 'start-local.ps1'
    $tokens = $null
    $parseErrors = $null
    $launcher = [Management.Automation.Language.Parser]::ParseFile($launcherPath, [ref]$tokens, [ref]$parseErrors)
    Assert-True ($parseErrors.Count -eq 0) 'Launcher parses successfully'
    foreach ($functionName in @('Assert-RunningMapService', 'Reuse-Or-Start')) {
        $definition = $launcher.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName }.GetNewClosure(), $true)
        Invoke-Expression $definition.Extent.Text
    }
    $script:endpointReady = $false
    $script:startCount = 0
    function Test-Endpoint { param($Uri, [switch]$RequireMapConfig) return $script:endpointReady }
    function Test-LocalPort { param($Port) return $false }
    function Start-LoggedService { param($Name, $WorkingDirectory, $Command) $script:startCount++; return [ordered]@{ name = $Name; cwd = $WorkingDirectory; reused = $false } }
    function Get-OwnedProcessTree { param($Service) return @([pscustomobject]@{ pid = 1101 }, [pscustomobject]@{ pid = 1102 }) }
    function Get-NetTCPConnection { param($State, $ErrorAction) return @([pscustomobject]@{ LocalPort = 18081; OwningProcess = 1102 }) }
    function Test-OwnedService { param($Service) return $true }
    function Stop-Process { throw 'Map directory startup checks must never terminate a process' }
    $services = [ordered]@{}
    $mapDataDirectory = $firstMap
    $started = Reuse-Or-Start -Name api -ProbeUri 'http://127.0.0.1:18081/health' -WorkingDirectory $serverRoot -Command 'unused' -RequireSharedMapDirectory
    Assert-True ($started.map_data_dir -eq $firstMap -and $script:startCount -eq 1) 'Fresh managed launch records the exact shared directory'
    $services.api = $started
    $script:endpointReady = $true
    $reused = Reuse-Or-Start -Name api -ProbeUri 'http://127.0.0.1:18081/health' -WorkingDirectory $serverRoot -Command 'unused' -RequireSharedMapDirectory
    Assert-True ($reused.map_data_dir -eq $firstMap -and $script:startCount -eq 1) 'Verified live listener reuses the service without starting another'
    $mapDataDirectory = $secondMap
    Assert-Throws { Reuse-Or-Start -Name api -ProbeUri 'http://127.0.0.1:18081/health' -WorkingDirectory $serverRoot -Command 'unused' -RequireSharedMapDirectory } 'Actual reuse path rejects changed directory before launching or terminating anything'
    Assert-True ($script:startCount -eq 1) 'Refused reuse leaves the existing process untouched'
    $sharedCalls = @($launcher.FindAll({ param($node) $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName() -eq 'Reuse-Or-Start' -and $node.Extent.Text -match "-Name '(api|business)'" }, $true))
    Assert-True ($sharedCalls.Count -eq 3 -and @($sharedCalls | Where-Object { $_.Extent.Text -notmatch '-RequireSharedMapDirectory' }).Count -eq 0) 'API and both business startup branches require shared-directory validation'
    # Evaluate the launcher's actual scope/probe expressions, including stale saved business state.
    $scopeAssignment = $launcher.Find({ param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -eq '$useBusiness' }, $true)
    $probeAssignment = $launcher.Find({ param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -eq '$adminProbeUri' }, $true)
    $services.business = $record
    $AdminPort = 15175
    foreach ($scopeCase in @(
        @{ Run = $false; Origin = ''; Expected = $false },
        @{ Run = $false; Origin = 'http://127.0.0.1:15173'; Expected = $true },
        @{ Run = $true; Origin = ''; Expected = $true }
    )) {
        $runBusiness = $scopeCase.Run
        $existingBusinessOrigin = $scopeCase.Origin
        Invoke-Expression $scopeAssignment.Extent.Text
        Invoke-Expression $probeAssignment.Extent.Text
        $expectedProbe = if ($scopeCase.Expected) { 'http://127.0.0.1:15175/map-config.json' } else { 'http://127.0.0.1:15175/' }
        Assert-True ($useBusiness -eq $scopeCase.Expected -and $adminProbeUri -eq $expectedProbe) 'Map availability follows selected/current service scope, not stale saved state'
    }
    $oneClick = [Management.Automation.Language.Parser]::ParseFile((Join-Path $scriptsRoot 'start-one-click.ps1'), [ref]$tokens, [ref]$parseErrors)
    Assert-True ($parseErrors.Count -eq 0 -and $oneClick.Extent.Text.Contains('$startParams.MapDataDir = $MapDataDir')) 'One-click entry forwards the optional directory parameter'
    Write-Output "TOTAL $script:assertions assertions passed"
} finally {
    $env:MAP_DATA_DIR = $originalEnvironment
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $scratchRoot.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $scratchRoot) -notmatch '^local-map-directory-[0-9a-f]{32}$') { throw 'Refusing unsafe test-directory cleanup' }
    if (Test-Path -LiteralPath $scratchRoot) { Remove-Item -LiteralPath $scratchRoot -Recurse -Force }
}
