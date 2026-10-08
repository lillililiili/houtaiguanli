param([string]$MavenRepository='C:/Users/19221/.m2/repository', [string]$JavaHome='C:/Users/19221/.jdks/temurin-17',
  [ValidatePattern('^[a-z0-9-]+$')][string]$EvidenceLabel='final-regressions')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
if((Get-Location).Path -ne (Join-Path $repo 'server')){throw 'Run from server/'}
$env:JAVA_HOME=$JavaHome
$env:POSTGRES_TEST_USER='item4';$env:POSTGRES_TEST_PASSWORD='isolated-unused'
$env:ITEM4_RESTART_TESTS='true'
$output=Join-Path $repo "server/target/item4-20261007/$EvidenceLabel"
New-Item -ItemType Directory -Force -Path $output | Out-Null
$jobs=@(
  @{name='maintenance-crashes';db='maintenance_flow_verify';tests='Item4MaintenanceRecoveryPostgresTest#committedPassCannotCompleteAfterRestartAndNewFault'},
  @{name='authorization-crashes';db='stage456_verify';tests='Item4AuthorizationRecoveryPostgresTest#savedApplicationAndApprovalSurviveProcessCrashesWithoutAnExecutionRequest'},
  @{name='receipt-crashes';db='stage456_verify';tests='Item4ReceiptRecoveryPostgresTest#committedReceiptSettlesOnceAfterCrashAndCreatesOneOrdinaryJammingChild'},
  @{name='eo-crashes';db='stage456_verify';tests='Item4EoRecoveryPostgresTest#pendingBeginAndEndKeepOccupancyAndPauseAcrossCrashes'},
  @{name='postgres-video-corrected';db='advisory_verify';tests='QaVideoStreamPostgresTest,TargetVideoPostgresTest'},
  @{name='report-acceptance-final';db='report_test';tests='AcceptanceReportingApiTest,ReportScopePolicyTest,BusinessReportExportTest'}
)
foreach($job in $jobs){
  $log=Join-Path $output ($job.name+'.log')
  if(Test-Path -LiteralPath $log){throw "Previous evidence exists: $log"}
  $url="jdbc:postgresql://127.0.0.1:25432/$($job.db)_item4_20261007"
  $env:POSTGRES_TEST_URL=$url
  $env:REPORT_TEST_PG_URL=if($job.db -eq 'report_test'){$url}else{''}
  & .\mvnw.cmd -o "-Dmaven.repo.local=$MavenRepository" "-Dtest=$($job.tests)" test *> $log
  $code=$LASTEXITCODE
  & 'D:/Software/python311/python.exe' (Join-Path $repo 'scripts/item4-results.py') $log
  if($code -ne 0){throw "Regression failed; inspect $log before repeating"}
  $result=Get-Content -Raw -LiteralPath ([IO.Path]::ChangeExtension($log,$null)+'/results.json') | ConvertFrom-Json
  if($result.skipped -ne 0 -or $result.tests -eq 0){throw "Required cases did not execute: $log"}
}
$package=Join-Path $output 'backend-package-final.log'
if(Test-Path -LiteralPath $package){throw 'Previous package evidence exists'}
& .\mvnw.cmd -o "-Dmaven.repo.local=$MavenRepository" -DskipTests package *> $package
if($LASTEXITCODE -ne 0){throw 'Package failed'}
