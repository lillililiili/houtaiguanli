param([string]$Python='D:/Software/python311/python.exe')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$sim=Join-Path (Split-Path $repo -Parent) 'demo-ronghe/tools/device-simulator'
$output=Join-Path $repo 'server/target/item4-20261007'
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$status=Invoke-RestMethod 'http://127.0.0.1:8766/api/status'
if($status.phase -notin @('STOPPED','COMPLETED','FAILED')){throw 'Stop the current scene through its normal controls before reloading'}
$config=Invoke-RestMethod 'http://127.0.0.1:8766/api/realtime/config'
$config | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $output "$stamp-realtime-before.json") -Encoding utf8
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8766/api/realtime/control' -ContentType 'application/json' -Body '{"action":"stop_all"}' | Out-Null
$listeners=@(Get-NetTCPConnection -LocalPort 8766 -State Listen | Select-Object -ExpandProperty OwningProcess -Unique)
if($listeners.Count -ne 1){throw 'Expected exactly one simulator owner'}
$owner=Get-CimInstance Win32_Process -Filter "ProcessId=$($listeners[0])"
if($owner.Name -ne 'python.exe' -or $owner.CommandLine -notmatch 'server.py' -or $owner.CommandLine -notmatch '8766'){throw 'Unexpected simulator process'}
$media=Join-Path $repo 'server/target/item2-media'
$credentials=Get-Content -Raw -LiteralPath (Join-Path $media 'credentials-private.json') | ConvertFrom-Json
$env:PYTHONPATH='C:/Temp/houtaiguanlii-local-runtime/python-deps'
$env:QA_VIDEO_ENABLED='true';$env:QA_VIDEO_RTSP_BASE='rtsp://127.0.0.1:8554'
$env:QA_VIDEO_PUBLISH_USER='qa-publisher';$env:QA_VIDEO_PUBLISH_PASSWORD=$credentials.publish
$env:PATH=(Join-Path $media 'ffmpeg-9.0.2-essentials_build/bin')+';'+$env:PATH
Stop-Process -Id $owner.ProcessId
Wait-Process -Id $owner.ProcessId -Timeout 15 -ErrorAction SilentlyContinue
$started=Start-Process -FilePath $Python -ArgumentList @('server.py','--port','8766','--map-origin','http://127.0.0.1:5173') -WorkingDirectory $sim -WindowStyle Hidden -RedirectStandardOutput (Join-Path $output "$stamp-simulator.out.log") -RedirectStandardError (Join-Path $output "$stamp-simulator.err.log") -PassThru
$deadline=(Get-Date).AddSeconds(20)
do {
  try {$check=Invoke-RestMethod 'http://127.0.0.1:8766/api/status';break} catch {Start-Sleep -Milliseconds 500}
}while((Get-Date) -lt $deadline)
if($null -eq $check){throw 'New simulator did not become ready'}
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8766/api/realtime/config' -ContentType 'application/json' -Body ($config | ConvertTo-Json -Depth 30) | Out-Null
[pscustomobject]@{pid=$started.Id;phase=$check.phase;login_required=(-not $check.connected);configuration_restored=$true}
