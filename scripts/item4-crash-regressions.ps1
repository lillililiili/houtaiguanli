param([string]$MavenRepository='C:/Users/19221/.m2/repository', [string]$JavaHome='C:/Users/19221/.jdks/temurin-17')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
if((Get-Location).Path -ne (Join-Path $repo 'server')){throw 'Run from server/'}
$env:JAVA_HOME=$JavaHome
$env:POSTGRES_TEST_URL='jdbc:postgresql://127.0.0.1:25432/stage456_verify_item4_20261007'
$env:POSTGRES_TEST_USER='item4';$env:POSTGRES_TEST_PASSWORD='isolated-unused';$env:ITEM4_RESTART_TESTS='true'
$output=Join-Path $repo 'server/target/item4-20261007'
$selector='AutomationMqttAcceptancePostgresTest#independentJvmFailureKillRecoveryAndRestartDoNotRepeatWireAction+crashAfterRealPublishNeverBlindlyRepeatsCommand'
$jobs=@(@{name='fusion-crashes';tests='Item4FusionProcessRecoveryPostgresTest'})
foreach($round in 1..3){$jobs+=@{name="control-crashes-$round";tests=$selector}}
foreach($job in $jobs){
  $log=Join-Path $output ($job.name+'.log')
  if(Test-Path -LiteralPath $log){throw "Previous evidence exists: $log"}
  & .\mvnw.cmd -o "-Dmaven.repo.local=$MavenRepository" "-Dtest=$($job.tests)" test *> $log
  $code=$LASTEXITCODE
  & 'D:/Software/python311/python.exe' (Join-Path $repo 'scripts/item4-results.py') $log
  if($code -ne 0){throw "Crash regression failed; inspect $log before repeating"}
}
