$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'local-service-state.ps1')
function Assert-True($Value, [string]$Message) {
    if (-not $Value) { throw $Message }
    Write-Output "PASS $Message"
}
function Stop-Process { throw 'Port polling must not terminate any process' }
$script:portReads = 0
$released = Wait-LocalPortReleased -Port 12345 -HasListener {
    param($Port)
    if ($Port -ne 12345) { throw 'Unexpected port' }
    $script:portReads++
    return $false
}
Assert-True ($released -and $script:portReads -eq 1) 'A released port succeeds without a wait'

$script:portReads = 0
$released = Wait-LocalPortReleased -Port 23456 -HasListener {
    param($Port)
    if ($Port -ne 23456) { throw 'Unexpected port' }
    $script:portReads++
    return $script:portReads -lt 3
}
Assert-True ($released -and $script:portReads -eq 3) 'A short listener-release delay succeeds on a later read'

$timer = [Diagnostics.Stopwatch]::StartNew()
$released = Wait-LocalPortReleased -Port 34567 -TimeoutMilliseconds 150 -HasListener { param($Port) return $true }
Assert-True (-not $released -and $timer.ElapsedMilliseconds -lt 1500) 'Persistent occupancy fails within the bounded polling interval'

$script:portReads = 0
$released = Wait-LocalPortReleased -Port 45678 -HasListener {
    param($Port)
    $script:portReads++
    throw 'Cannot read TCP listeners'
}
Assert-True (-not $released -and $script:portReads -eq 1) 'A failed port query cannot confirm release'
foreach ($invalid in @($null, 'false', 0)) {
    $reader = { param($Port) return $invalid }.GetNewClosure()
    Assert-True (-not (Wait-LocalPortReleased -Port 56789 -HasListener $reader)) 'Unknown or untyped port state fails closed'
}
