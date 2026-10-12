$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'local-simulator-state.ps1')

function Assert-True($Value, [string]$Message) {
    if (-not $Value) { throw $Message }
    Write-Output "PASS $Message"
}
function New-Status {
    return '{"phase":"STOPPED","mqtt_connected":false,"error":"previous failure","realtime":{"state":"STOPPED","error":"receiver failure","countermeasure":{"listening":false}}}' | ConvertFrom-Json
}

foreach ($phase in @('IDLE', 'STOPPED', 'COMPLETED', 'FAILED')) {
    foreach ($state in @('STOPPED', 'FAILED')) {
        $value = New-Status
        $value.phase = $phase
        $value.realtime.state = $state
        Assert-True (Test-LocalSimulatorStopped $value) "$phase/$state confirms inactive legacy transports"
        Assert-True ($value.error -ceq 'previous failure' -and $value.realtime.error -ceq 'receiver failure') 'Confirmation preserves diagnostics'
    }
    $value.realtime = $null
    Assert-True (Test-LocalSimulatorStopped $value) "$phase supports explicitly disabled realtime receivers"
}
foreach ($phase in @('PREPARING', 'RUNNING', 'PAUSED', 'STOPPING', 'UNKNOWN', '', 'stopped')) {
    $value = New-Status
    $value.phase = $phase
    Assert-True (-not (Test-LocalSimulatorStopped $value)) "Reject scene phase '$phase'"
}
foreach ($state in @('STARTING', 'RUNNING', 'STOPPING', 'UNKNOWN', '', 'stopped')) {
    $value = New-Status
    $value.realtime.state = $state
    Assert-True (-not (Test-LocalSimulatorStopped $value)) "Reject receiver state '$state'"
}
foreach ($invalid in @($null, $true, 'false', 0)) {
    $value = New-Status
    $value.mqtt_connected = $invalid
    Assert-True (-not (Test-LocalSimulatorStopped $value)) 'MQTT confirmation requires a real false boolean'
    $value = New-Status
    $value.realtime.countermeasure.listening = $invalid
    Assert-True (-not (Test-LocalSimulatorStopped $value)) 'Listener confirmation requires a real false boolean'
}
foreach ($missing in @('phase', 'mqtt_connected', 'realtime')) {
    $value = New-Status
    $value.PSObject.Properties.Remove($missing)
    Assert-True (-not (Test-LocalSimulatorStopped $value)) "Missing $missing does not confirm stop"
}
foreach ($missing in @('state', 'countermeasure')) {
    $value = New-Status
    $value.realtime.PSObject.Properties.Remove($missing)
    Assert-True (-not (Test-LocalSimulatorStopped $value)) "Missing realtime $missing does not confirm stop"
}
$value = New-Status
$value.realtime.countermeasure.PSObject.Properties.Remove('listening')
Assert-True (-not (Test-LocalSimulatorStopped $value)) 'Missing listener state does not confirm stop'
Assert-True (-not (Test-LocalSimulatorStopped $null)) 'A missing response does not confirm stop'
foreach ($location in @('scene', 'realtime')) {
    foreach ($active in @($true, $null, 'false', $false)) {
        $value = New-Status
        $target = if ($location -eq 'scene') { $value } else { $value.realtime }
        $target | Add-Member -NotePropertyName active -NotePropertyValue $active
        $expected = $active -is [bool] -and -not $active
        Assert-True ((Test-LocalSimulatorStopped $value) -eq $expected) "$location activity metadata cannot contradict shutdown"
    }
}

$script:pollReads = 0
$readStatus = {
    param($Uri, $Timeout)
    if ($Uri -cne 'http://example.invalid/api/status' -or $Timeout -lt 1 -or $Timeout -gt 3) { throw 'Invalid bounded read contract' }
    $script:pollReads++
    if ($script:pollReads -eq 2) { throw 'Temporary read failure' }
    $value = New-Status
    if ($script:pollReads -eq 1) { $value.phase = 'STOPPING' }
    return $value
}
$result = Wait-LocalSimulatorStopped -Origin 'http://example.invalid' -TimeoutSeconds 3 -ReadStatus $readStatus
Assert-True ($script:pollReads -eq 3 -and $result.phase -ceq 'STOPPED') 'Polls through asynchronous stop and a transient read failure'

$timer = [Diagnostics.Stopwatch]::StartNew()
$failed = $false
try {
    Wait-LocalSimulatorStopped -Origin 'http://example.invalid' -TimeoutSeconds 2 -ReadStatus { param($Uri, $Timeout) return [pscustomobject]@{ phase='STOPPED' } } | Out-Null
} catch { $failed = $true }
Assert-True ($failed -and $timer.Elapsed.TotalSeconds -lt 2.5) 'Incomplete status fails closed within the confirmation deadline'

$tokens = $null
$errors = $null
$path = Join-Path (Split-Path -Parent $PSScriptRoot) 'stop-local.ps1'
$null = [Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
Assert-True ($errors.Count -eq 0) 'Stop launcher syntax parses without executing any service action'
