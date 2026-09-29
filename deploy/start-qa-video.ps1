param([Parameter(Mandatory=$true)][string]$MediaMtxPath)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $MediaMtxPath -PathType Leaf)) { throw 'MediaMTX executable not found' }
foreach ($secret in @($env:QA_VIDEO_PUBLISH_PASSWORD, $env:QA_VIDEO_READ_PASSWORD)) {
    if ($secret -notmatch '^[A-Za-z0-9_-]{24,128}$') { throw 'Set separate QA_VIDEO_PUBLISH_PASSWORD and QA_VIDEO_READ_PASSWORD base64url secrets (24-128 characters).' }
}
if ($env:QA_VIDEO_PUBLISH_PASSWORD -eq $env:QA_VIDEO_READ_PASSWORD) { throw 'Publish and read secrets must differ.' }
$values = @{
    MTX_AUTHINTERNALUSERS_0_USER='qa-publisher'; MTX_AUTHINTERNALUSERS_0_PASS=$env:QA_VIDEO_PUBLISH_PASSWORD
    MTX_AUTHINTERNALUSERS_0_PERMISSIONS_0_ACTION='publish'; MTX_AUTHINTERNALUSERS_0_PERMISSIONS_0_PATH='~^qa/[a-f0-9-]{36}$'
    MTX_AUTHINTERNALUSERS_1_USER='qa-platform'; MTX_AUTHINTERNALUSERS_1_PASS=$env:QA_VIDEO_READ_PASSWORD
    MTX_AUTHINTERNALUSERS_1_PERMISSIONS_0_ACTION='read'; MTX_AUTHINTERNALUSERS_1_PERMISSIONS_0_PATH='~^qa/[a-f0-9-]{36}$'
    MTX_AUTHINTERNALUSERS_1_PERMISSIONS_1_ACTION='api'
}
$previous = @{}
Get-ChildItem Env:MTX_AUTHINTERNALUSERS* | ForEach-Object { $previous[$_.Name]=$_.Value; Remove-Item -LiteralPath ('Env:'+$_.Name) }
try {
    foreach($name in $values.Keys) { Set-Item -LiteralPath ('Env:'+$name) -Value $values[$name] }
    # Native config listens exclusively on loopback. Run in this terminal; Ctrl+C stops only MediaMTX.
    & $MediaMtxPath (Join-Path $PSScriptRoot 'mediamtx-qa.yml')
} finally {
    foreach($name in $values.Keys) { Remove-Item -LiteralPath ('Env:'+$name) -ErrorAction SilentlyContinue }
    foreach($name in $previous.Keys) { Set-Item -LiteralPath ('Env:'+$name) -Value $previous[$name] }
}
