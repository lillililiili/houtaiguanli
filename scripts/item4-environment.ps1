param(
  [ValidateSet('Start','Status','RestartBroker','RestartDatabase','RestartMedia','Stop')][string]$Action='Status',
  [ValidatePattern('^[0-9]{8}[a-z0-9]*$')][string]$RunId='20261007',
  [string]$Docker='C:/Users/19221/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe'
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$output=Join-Path $repo "server/target/item4-$RunId"
$label="item4-$RunId"
$names=@{db="item4-postgis-$RunId"; broker="item4-mosquitto-$RunId"; media="item4-mediamtx-$RunId"}
function Invoke-Docker([string[]]$Arguments) {
  $result=& $Docker @Arguments
  if ($LASTEXITCODE -ne 0) { throw "Docker operation failed: $($Arguments[0])" }
  return $result
}
function Get-Owned([string]$Name) {
  $raw=& $Docker inspect $Name 2>$null
  if ($LASTEXITCODE -ne 0) { return $null }
  $item=($raw | ConvertFrom-Json)[0]
  if ($item.Config.Labels.'uav.acceptance' -ne $label -or $item.Name -ne "/$Name") { throw 'Container ownership mismatch' }
  foreach($property in $item.HostConfig.PortBindings.PSObject.Properties) {
    foreach($binding in $property.Value) {
      if ($binding.HostIp -ne '127.0.0.1') { throw 'Fault containers must be loopback only' }
    }
  }
  return $item
}
if ($Action -eq 'Start') {
  New-Item -ItemType Directory -Force -Path $output | Out-Null
  $conf=Join-Path $output 'mosquitto.conf'
  "listener 1883`nallow_anonymous true`npersistence true`npersistence_location /mosquitto/data/`nautosave_interval 1`n" | Set-Content -LiteralPath $conf -Encoding ascii
  $mediaConf=Join-Path $output 'mediamtx.yml'
  @'
logLevel: warn
rtspAddress: :8554
rtspTransports: [tcp]
rtmp: no
webrtc: no
srt: no
hls: yes
hlsAddress: :8888
hlsAlwaysRemux: yes
hlsVariant: mpegts
api: yes
apiAddress: :9997
authMethod: internal
authInternalUsers:
  - user: any
    permissions:
      - action: publish
      - action: read
      - action: api
paths:
  all_others:
'@ | Set-Content -LiteralPath $mediaConf -Encoding ascii
  foreach($role in @('db','broker','media')) {
    $owned=Get-Owned $names[$role]
    if ($null -eq $owned) {
      $arguments=@('run','-d','--name',$names[$role],'--label',"uav.acceptance=$label")
      if ($role -eq 'db') {
        $arguments+=@('-p','127.0.0.1:25432:5432','-e','POSTGRES_HOST_AUTH_METHOD=trust','-e','POSTGRES_USER=item4','-e',"POSTGRES_DB=stage456_verify_item4_$RunId",'postgis/postgis:16-3.5')
      } elseif($role -eq 'broker') {
        $arguments+=@('-p','127.0.0.1:21883:1883','--mount',"type=bind,source=$conf,target=/mosquitto/config/mosquitto.conf,readonly",'eclipse-mosquitto:2')
      } else {
        $arguments+=@('-p','127.0.0.1:18554:8554','-p','127.0.0.1:18888:8888','-p','127.0.0.1:19997:9997','--mount',"type=bind,source=$mediaConf,target=/mediamtx.yml,readonly",'bluenviron/mediamtx:1.21.1')
      }
      Invoke-Docker $arguments | Out-Null
    } elseif (-not $owned.State.Running) { Invoke-Docker @('start',$owned.Id) | Out-Null }
  }
}
if ($Action -in @('RestartBroker','RestartDatabase','RestartMedia','Stop')) {
  $roles=if($Action -eq 'Stop'){@('broker','media','db')}elseif($Action -eq 'RestartBroker'){@('broker')}elseif($Action -eq 'RestartMedia'){@('media')}else{@('db')}
  foreach($role in $roles) {
    $owned=Get-Owned $names[$role]
    if ($null -eq $owned) { throw 'Owned container not found' }
    $operation=if($Action -eq 'Stop'){'stop'}else{'restart'}
    Invoke-Docker @($operation,$owned.Id) | Out-Null
  }
}
foreach($role in @('db','broker','media')) {
  $owned=Get-Owned $names[$role]
  if ($null -ne $owned) { [pscustomobject]@{role=$role;name=$names[$role];id=$owned.Id;running=$owned.State.Running} }
}
