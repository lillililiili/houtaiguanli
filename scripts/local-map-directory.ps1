# Shared map storage for the API and the business Vite server. These helpers do not start or stop services.
function ConvertTo-LocalAbsoluteDirectory {
    param(
        [Parameter(Mandatory)][string]$Path,
        [string]$BaseDirectory = (Get-Location).ProviderPath
    )
    $value = $Path.Trim()
    if (-not $value -or $value -match '^[A-Za-z]:(?:[^\\/]|$)' -or $value -match '["\r\n]') {
        throw "地图目录必须是有效的绝对路径或相对路径：$Path"
    }
    if (-not [IO.Path]::IsPathRooted($value)) { $value = Join-Path $BaseDirectory $value }
    elseif ($value -match '^[\\/](?![\\/])') {
        $value = Join-Path ([IO.Path]::GetPathRoot($BaseDirectory)) $value.TrimStart('\', '/')
    }
    $absolute = [IO.Path]::GetFullPath($value)
    $root = [IO.Path]::GetPathRoot($absolute)
    if ($absolute.Length -gt $root.Length) { $absolute = $absolute.TrimEnd('\', '/') }
    return $absolute
}

function Resolve-LocalMapDataDirectory {
    param(
        [string]$MapDataDir = '',
        [string]$EnvironmentMapDataDir = $env:MAP_DATA_DIR,
        [string]$BusinessRoot = '',
        [Parameter(Mandatory)][string]$ServerRoot,
        [string]$BaseDirectory = (Get-Location).ProviderPath
    )
    if (-not [string]::IsNullOrWhiteSpace($MapDataDir)) {
        return ConvertTo-LocalAbsoluteDirectory -Path $MapDataDir -BaseDirectory $BaseDirectory
    }
    if (-not [string]::IsNullOrWhiteSpace($EnvironmentMapDataDir)) {
        return ConvertTo-LocalAbsoluteDirectory -Path $EnvironmentMapDataDir -BaseDirectory $BaseDirectory
    }
    if ($BusinessRoot) {
        $business = ConvertTo-LocalAbsoluteDirectory -Path $BusinessRoot -BaseDirectory $BaseDirectory
        # Keep the same candidate order as the business Vite map-data plugin.
        $candidates = @(
            (ConvertTo-LocalAbsoluteDirectory -Path (Join-Path $business '..\map-data')),
            (ConvertTo-LocalAbsoluteDirectory -Path (Join-Path $business '..\..\map-data'))
        )
        foreach ($candidate in $candidates) {
            if (Test-Path -LiteralPath (Join-Path $candidate 'dongying-dev\manifest.json') -PathType Leaf) { return $candidate }
        }
        return $candidates[0]
    }
    # A backend-only installation need not have a business checkout or existing map packages.
    return ConvertTo-LocalAbsoluteDirectory -Path (Join-Path $ServerRoot 'data\map-data') -BaseDirectory $BaseDirectory
}

function Get-LocalMapEnvironmentCommand {
    param([Parameter(Mandatory)][string]$MapDataDirectory)
    return "`$env:MAP_DATA_DIR = '" + $MapDataDirectory.Replace("'", "''") + "'`n"
}

function Get-LocalMapServiceProperty {
    param($Service, [string]$Name)
    if ($null -eq $Service) { return $null }
    if ($Service -is [System.Collections.IDictionary]) { return $Service[$Name] }
    $property = $Service.PSObject.Properties[$Name]
    if ($null -ne $property) { return $property.Value }
    return $null
}

function Assert-LocalMapServiceReuse {
    param(
        [Parameter(Mandatory)][string]$Name,
        $Service,
        [Parameter(Mandatory)][string]$MapDataDirectory,
        [Parameter(Mandatory)][string]$WorkingDirectory,
        [int[]]$OwnedProcessIds = @(),
        [int[]]$ListenerProcessIds = @()
    )
    $recordedMap = Get-LocalMapServiceProperty -Service $Service -Name 'map_data_dir'
    $recordedCwd = Get-LocalMapServiceProperty -Service $Service -Name 'cwd'
    $matches = $false
    if ($recordedMap -and $recordedCwd) {
        try {
            $matches = [string]::Equals((ConvertTo-LocalAbsoluteDirectory $recordedMap), (ConvertTo-LocalAbsoluteDirectory $MapDataDirectory), [StringComparison]::OrdinalIgnoreCase) -and
                [string]::Equals((ConvertTo-LocalAbsoluteDirectory $recordedCwd), (ConvertTo-LocalAbsoluteDirectory $WorkingDirectory), [StringComparison]::OrdinalIgnoreCase)
        } catch { $matches = $false }
    }
    # A live wrapper alone does not establish who owns the listening port.
    $owned = $OwnedProcessIds.Count -gt 0 -and $ListenerProcessIds.Count -gt 0
    foreach ($listenerId in $ListenerProcessIds) {
        if ($listenerId -notin $OwnedProcessIds) { $owned = $false }
    }
    if (-not $matches -or -not $owned) {
        throw "$Name 已在运行，但无法确认其地图目录与本次一致（期望：$MapDataDirectory）。请先停止并重启本项目相关服务；未停止或覆盖已有进程。"
    }
}
