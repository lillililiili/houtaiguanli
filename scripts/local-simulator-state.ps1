# Read-only stop confirmation. An error terminal state is not the same as active transport.
function Get-SimulatorField {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    if ($Object -is [System.Collections.IDictionary]) { return $Object[$Name] }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -ne $property) { return $property.Value }
    return $null
}

function Test-SimulatorField {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $false }
    if ($Object -is [System.Collections.IDictionary]) { return $Object.Contains($Name) }
    return $null -ne $Object.PSObject.Properties[$Name]
}

function Test-LocalSimulatorStopped {
    param($Status)
    $phase = Get-SimulatorField $Status 'phase'
    $mqtt = Get-SimulatorField $Status 'mqtt_connected'
    if ($phase -isnot [string] -or $phase -cnotin @('IDLE', 'STOPPED', 'COMPLETED', 'FAILED')) { return $false }
    if ($mqtt -isnot [bool] -or $mqtt) { return $false }
    # Optional future activity metadata must never contradict the terminal-state checks.
    if (Test-SimulatorField $Status 'active') {
        $active = Get-SimulatorField $Status 'active'
        if ($active -isnot [bool] -or $active) { return $false }
    }
    # Existing versions explicitly return null when realtime receivers are disabled.
    if (-not (Test-SimulatorField $Status 'realtime')) { return $false }
    $realtime = Get-SimulatorField $Status 'realtime'
    if ($null -eq $realtime) { return $true }
    $state = Get-SimulatorField $realtime 'state'
    if ($state -isnot [string] -or $state -cnotin @('STOPPED', 'FAILED')) { return $false }
    if (Test-SimulatorField $realtime 'active') {
        $active = Get-SimulatorField $realtime 'active'
        if ($active -isnot [bool] -or $active) { return $false }
    }
    $listening = Get-SimulatorField (Get-SimulatorField $realtime 'countermeasure') 'listening'
    return $listening -is [bool] -and -not $listening
}

function Wait-LocalSimulatorStopped {
    param(
        [Parameter(Mandatory=$true)][string]$Origin,
        [ValidateRange(1,30)][int]$TimeoutSeconds = 30,
        [scriptblock]$ReadStatus = { param($Uri, $Timeout) Invoke-RestMethod -Uri $Uri -TimeoutSec $Timeout }
    )
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $lastStatus = $null
    $lastError = $null
    while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        # Integer request timeouts must fit inside the remaining confirmation budget.
        $remaining = [int][Math]::Floor($TimeoutSeconds - $timer.Elapsed.TotalSeconds)
        if ($remaining -lt 1) { break }
        try {
            $lastStatus = & $ReadStatus "$Origin/api/status" ([Math]::Min(3, $remaining))
            $lastError = $null
            if (Test-LocalSimulatorStopped $lastStatus) { return $lastStatus }
        } catch {
            $lastError = $_.Exception.Message
        }
        $wait = [int][Math]::Min(250, [Math]::Max(0, ($TimeoutSeconds - $timer.Elapsed.TotalSeconds) * 1000))
        if ($wait -gt 0) { Start-Sleep -Milliseconds $wait }
    }
    $phase = Get-SimulatorField $lastStatus 'phase'
    $realtimeState = Get-SimulatorField (Get-SimulatorField $lastStatus 'realtime') 'state'
    $detail = if ($lastError) { "状态读取失败：$lastError" } else { "场景状态=$phase；收发状态=$realtimeState" }
    throw "模拟器未在 $TimeoutSeconds 秒内确认停止收发（$detail）。"
}
