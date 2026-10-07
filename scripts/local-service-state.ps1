# Shared process ownership and state for the local launchers. No credentials are stored.
function Get-LocalRuntimeDirectory {
    param([string]$RepositoryRoot)
    return Join-Path $RepositoryRoot 'artifacts\local-runtime'
}

function Read-LocalServiceState {
    param([string]$Path)
    $result = [ordered]@{}
    if (Test-Path -LiteralPath $Path) {
        $saved = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
        foreach ($property in $saved.PSObject.Properties) { $result[$property.Name] = $property.Value }
    }
    return $result
}

function Save-LocalServiceState {
    param([string]$Path, [System.Collections.IDictionary]$Services)
    $temporary = "$Path.new"
    [IO.File]::WriteAllText($temporary, ($Services | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Get-ProcessIdentity {
    param([int]$ProcessId)
    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if (-not $process) { return $null }
    try { return [pscustomobject]@{ pid = $process.Id; start_ticks = $process.StartTime.ToUniversalTime().Ticks.ToString() } }
    catch { return $null }
}

function Test-ProcessIdentity {
    param($Identity)
    if (-not $Identity -or -not $Identity.pid -or -not $Identity.start_ticks) { return $false }
    $current = Get-ProcessIdentity -ProcessId ([int]$Identity.pid)
    return [bool]($current -and $current.start_ticks -eq $Identity.start_ticks)
}

function Test-OwnedService {
    param($Service)
    if (-not $Service -or $Service.reused -or -not $Service.entry_file) { return $false }
    if (-not (Test-ProcessIdentity $Service)) { return $false }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($Service.pid)"
    return [bool]($process -and $process.CommandLine -and
        $process.CommandLine.IndexOf([string]$Service.entry_file, [StringComparison]::OrdinalIgnoreCase) -ge 0)
}

function Get-OwnedProcessTree {
    param($Service)
    $members = [System.Collections.Generic.List[object]]::new()
    if (-not (Test-OwnedService $Service)) { return @() }
    $allProcesses = @(Get-CimInstance Win32_Process)
    $queue = [System.Collections.Generic.Queue[int]]::new()
    $queue.Enqueue([int]$Service.pid)
    $seen = @{}
    while ($queue.Count -gt 0) {
        $currentId = $queue.Dequeue()
        if ($seen.ContainsKey($currentId)) { continue }
        $seen[$currentId] = $true
        $identity = Get-ProcessIdentity -ProcessId $currentId
        if (-not $identity) { continue }
        $members.Add($identity)
        foreach ($child in $allProcesses) {
            if ($child.ParentProcessId -eq $currentId) { $queue.Enqueue([int]$child.ProcessId) }
        }
    }
    return $members.ToArray()
}

function Stop-OwnedService {
    param($Service)
    $members = @(Get-OwnedProcessTree $Service)
    # Saved identities also cover children whose wrapper exited after launch.
    foreach ($savedMember in @($Service.members)) {
        if ((Test-ProcessIdentity $savedMember) -and $savedMember.pid -notin @($members | ForEach-Object { $_.pid })) {
            $members += $savedMember
        }
    }
    [array]::Reverse($members)
    foreach ($member in $members) {
        if (Test-ProcessIdentity $member) {
            try { Stop-Process -Id ([int]$member.pid) -ErrorAction Stop }
            catch {
                # Wrappers can exit as soon as their child terminates.
                if (Test-ProcessIdentity $member) { throw }
            }
        }
    }
    foreach ($member in $members) {
        if (Test-ProcessIdentity $member) {
            try { Wait-Process -Id ([int]$member.pid) -Timeout 15 -ErrorAction Stop }
            catch { if (Test-ProcessIdentity $member) { throw } }
        }
    }
}
