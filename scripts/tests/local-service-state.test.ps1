$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'local-service-state.ps1')
function Assert-True($Value, [string]$Message) {
    if (-not $Value) { throw $Message }
    Write-Output "PASS $Message"
}

$identity = Get-ProcessIdentity -ProcessId $PID
Assert-True (Test-ProcessIdentity $identity) 'Current process identity is recognized'
$mismatched = [pscustomobject]@{pid=$PID; start_ticks='0'}
Assert-True (-not (Test-ProcessIdentity $mismatched)) 'A reused PID with a different creation time is rejected'
$unowned = [pscustomobject]@{
    name='test'; pid=$PID; start_ticks=$identity.start_ticks
    reused=$false; entry_file=(Join-Path $PSScriptRoot ([Guid]::NewGuid().ToString('N') + '.ps1')); members=@()
}
Assert-True (-not (Test-OwnedService $unowned)) 'A process with an unrelated command line is rejected'
Stop-OwnedService $unowned
Assert-True (Test-ProcessIdentity $identity) 'Unowned process survives a stop request'
$unowned.reused = $true
Assert-True (-not (Test-OwnedService $unowned)) 'Reused external services are not owned'

$testDirectory = Join-Path ([IO.Path]::GetTempPath()) ('local-service-state-test-' + [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $testDirectory
$statePath = Join-Path $testDirectory 'services.json'
$state = [ordered]@{ api = [ordered]@{name='api';kind='process';pid=123;start_ticks='456';members=@()} }
Save-LocalServiceState -Path $statePath -Services $state
$loaded = Read-LocalServiceState -Path $statePath
$loaded['simulator'] = [ordered]@{name='simulator';kind='process';pid=789;start_ticks='987';members=@()}
Save-LocalServiceState -Path $statePath -Services $loaded
$readback = Read-LocalServiceState -Path $statePath
Assert-True ($readback.api.pid -eq 123 -and $readback.simulator.pid -eq 789) 'Adding a service preserves previous ownership records'
Assert-True ($readback.api.start_ticks -ceq '456') 'Process creation time remains a string through JSON'
Assert-True ((Get-LocalRuntimeDirectory 'E:\sample project') -eq 'E:\sample project\artifacts\local-runtime') 'Runtime location is independent of TEMP changes and accepts spaces'
Write-Output "State roundtrip evidence: $statePath"
