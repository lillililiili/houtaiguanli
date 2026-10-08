[CmdletBinding()]
param([string]$Account = 'admin1')
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:8081/api/v1'
$headers = @{}
function Call-Platform([string]$Method, [string]$Path, $Body = $null, [string]$Key = '') {
    $request = @{Uri=$base+$Path; Method=$Method; Headers=$headers; TimeoutSec=12}
    if ($null -ne $Body) {
        $request.ContentType='application/json; charset=utf-8'
        $request.Body=[Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10 -Compress))
    }
    if ($Key) { $request.Headers=@{}+$headers; $request.Headers['Idempotency-Key']=$Key }
    $value=Invoke-RestMethod @request
    if (-not $value.ok) { throw '平台未受理请求' }
    return $value.data
}
try {
    $secret=Read-Host '请输入现有平台账号密码（仅用于本机 API 登录，不保存）' -AsSecureString
    $credential=New-Object System.Management.Automation.PSCredential($Account,$secret)
    $login=Call-Platform 'POST' '/auth/login' @{account=$Account;password=$credential.GetNetworkCredential().Password}
    $headers.Authorization='Bearer '+$login.session_id
    $credential=$null; $secret=$null; $login=$null
    $matches=@(Call-Platform 'GET' '/mqtt-brokers' | Where-Object { $_.name -eq 'local-lingyun-replay' })
    if ($matches.Count -gt 1) { throw '同名连接不唯一，请核对现有配置' }
    if ($matches.Count -eq 1) { $broker=$matches[0] }
    else {
        $scopes=@(Call-Platform 'GET' '/mqtt-brokers/scopes')
        if ($scopes.Count -eq 0) { throw '账号没有可用的单位和区域范围' }
        for ($i=0;$i -lt $scopes.Count;$i++) {
            Write-Host "[$($i+1)] $($scopes[$i].org_name) / $($scopes[$i].district_name) ($($scopes[$i].org_id) / $($scopes[$i].district_id))"
        }
        $choice=if($scopes.Count -eq 1){1}else{[int](Read-Host '选择本次验收已有的单位与区域序号')}
        if ($choice -lt 1 -or $choice -gt $scopes.Count) { throw '选择范围无效' }
        $scope=$scopes[$choice-1]
        $body=[ordered]@{name='local-lingyun-replay';host='127.0.0.1';port=1883;tls=$false;username=$null;credential_ref=$null;allowed_cidrs='127.0.0.1/32';source_mode='replay';owner_org_id=$scope.org_id;district_id=$scope.district_id}
        $hash=[Security.Cryptography.SHA256]::Create()
        try { $digest=[BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes(($body|ConvertTo-Json -Compress)))).Replace('-','').Substring(0,32) }
        finally { $hash.Dispose() }
        try { $broker=Call-Platform 'POST' '/mqtt-brokers' $body ('item1-create-'+$digest) }
        catch {
            $matches=@(Call-Platform 'GET' '/mqtt-brokers' | Where-Object { $_.name -eq 'local-lingyun-replay' })
            if ($matches.Count -ne 1) { throw '提交结果未知，回读未确认；已停止，不能盲目重复创建' }
            $broker=$matches[0]
            if ($broker.owner_org_id -ne $scope.org_id -or $broker.district_id -ne $scope.district_id) { throw '回读范围与本次提交不一致' }
        }
    }
    if ($broker.source_mode -ne 'replay' -or $broker.host -ne '127.0.0.1' -or $broker.port -ne 1883 -or $broker.tls -or $broker.allowed_cidrs -ne '127.0.0.1/32') {
        throw '同名连接与本机验收配置不一致；不会覆盖或修改它'
    }
    if (-not $broker.enabled) {
        try { $broker=Call-Platform 'PATCH' ('/mqtt-brokers/'+$broker.broker_id+'/enabled') @{version=$broker.version;enabled=$true} ('item1-enable-'+$broker.broker_id+'-'+$broker.version) }
        catch {
            $broker=Call-Platform 'GET' ('/mqtt-brokers/'+$broker.broker_id)
            if (-not $broker.enabled) { throw '启用结果未确认；停止操作，请核对后再运行' }
        }
    }
    Write-Host '回放连接已通过授权 API 配置并回读确认；请返回模拟器点击开始模拟。' -ForegroundColor Green
    $broker | Select-Object name,host,port,source_mode,enabled,owner_org_id,district_id | Format-List
}
finally {
    if ($headers.Authorization) { try { $null=Call-Platform 'POST' '/auth/logout' @{} } catch { Write-Warning '临时配置会话未能注销，将按原有效期失效。' } }
    $headers=@{}; $credential=$null; $secret=$null
}
