param(
  [string]$Docker='C:/Users/19221/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe',
  [string]$Python='D:/Software/python311/python.exe',
  [string]$JavaHome='C:/Users/19221/.jdks/temurin-17',
  [string]$MavenRepository='C:/Users/19221/.m2/repository',
  [ValidatePattern('^[0-9]{8}[a-z0-9]*$')][string]$RunId='20261007'
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$server=Join-Path $repo 'server'
if((Get-Location).Path -ne $server){throw 'Run this script from server/'}
$output=Join-Path $server "target/item4-$RunId"
$container="item4-postgis-$RunId"
$description=(& $Docker inspect $container | ConvertFrom-Json)[0]
if($LASTEXITCODE -ne 0 -or $description.Name -ne "/$container" -or $description.Config.Labels.'uav.acceptance' -ne "item4-$RunId"){throw 'Independent database ownership mismatch'}
$binding=$description.HostConfig.PortBindings.'5432/tcp'
if($binding.Count -ne 1 -or $binding[0].HostIp -ne '127.0.0.1' -or $binding[0].HostPort -ne '25432'){throw 'Independent database port mismatch'}
$ownedId=$description.Id
$groups=@(
  @{name='advisory';db='advisory_verify';tests='UavAdvisoryPostgresTest,AutoSmsPostgresTest,AutoVoicePostgresTest'},
  @{name='maintenance';db='maintenance_flow_verify';tests='DeviceMaintenanceWorkflowPostgresTest'},
  @{name='maintenance-notice';db='maintenance_notice_verify';tests='DeviceMaintenanceNoticePostgresTest'},
  @{name='handoff-notice';db='handoff_notice_verify';tests='HandoffNotificationPostgresTest'},
  @{name='business-state';db='stage456_verify';tests='NotificationPresenceTimingPostgresTest,DualChannelReceiptPostgresTest,HandoffAutomaticMatrixPostgresTest,HandoffLocationPostgresTest,NoCounterPostgresTest,RiskClearancePostgresTest,ReportingFormalFactsPostgresTest'},
  @{name='eo';db='stage456_verify';tests='EoEdgeMonitoringPostgresTest,EoTrackingPolicyTest,EoTrackingCommandSafetyTest'},
  @{name='video';db='advisory_verify';tests='QaVideoStreamPostgresTest,TargetVideoPostgresTest'},
  @{name='reports';db='report_test';tests='BusinessReportingApiTest'},
  @{name='flight';db='stage_flight_verify';tests='FlightVerificationPostgresTest'}
)
foreach($dbPrefix in ($groups.db | Select-Object -Unique)){
  $database="${dbPrefix}_item4_$RunId"
  $exists=& $Docker exec $ownedId psql -U item4 -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$database'"
  if($LASTEXITCODE -ne 0){throw 'Cannot check test database'}
  if($exists -ne '1'){
    & $Docker exec $ownedId createdb -U item4 $database | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Cannot create isolated test database'}
  }
  & $Docker exec $ownedId psql -U item4 -d $database -v ON_ERROR_STOP=1 -c 'CREATE EXTENSION IF NOT EXISTS postgis' | Out-Null
  if($LASTEXITCODE -ne 0){throw 'Cannot initialize test PostGIS'}
}
$env:JAVA_HOME=$JavaHome
$env:POSTGRES_TEST_USER='item4';$env:POSTGRES_TEST_PASSWORD='isolated-unused'
$env:FLIGHT_TEST_PG_USER='item4';$env:FLIGHT_TEST_PG_PASSWORD='isolated-unused'
$env:PYTHONPATH='C:/Temp/houtaiguanlii-local-runtime/python-deps'
Remove-Item Env:ITEM4_DATABASE_CONTAINER -ErrorAction SilentlyContinue
foreach($group in $groups){
  $log=Join-Path $output ("postgres-"+$group.name+'.log')
  if(Test-Path -LiteralPath $log){throw "Preserve previous evidence: $log"}
  $url="jdbc:postgresql://127.0.0.1:25432/$($group.db)_item4_$RunId"
  $env:POSTGRES_TEST_URL=$url
  $env:REPORT_TEST_PG_URL=if($group.db -eq 'report_test'){$url}else{''}
  $env:FLIGHT_TEST_PG_URL=if($group.db -eq 'stage_flight_verify'){$url}else{''}
  & .\mvnw.cmd -o "-Dmaven.repo.local=$MavenRepository" "-Dtest=$($group.tests)" test *> $log
  $testExit=$LASTEXITCODE
  & $Python (Join-Path $repo 'scripts/item4-results.py') $log
  $result=Get-Content -Raw -LiteralPath ([System.IO.Path]::ChangeExtension($log,$null)+'/results.json') | ConvertFrom-Json
  if($result.skipped -gt 0){Write-Output "INCOMPLETE: $($group.name) has skipped cases; inspect conditions before claiming completion"}
  [pscustomobject]@{suite=$group.name;exit=$testExit;log=$log} | ConvertTo-Json -Compress | Add-Content -LiteralPath (Join-Path $output 'regression-runs.jsonl') -Encoding utf8
  if($testExit -ne 0){Write-Output "FAILED: $($group.name); preserved evidence; continuing independent suites"}
}
