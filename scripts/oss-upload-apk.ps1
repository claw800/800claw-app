# Upload a local APK to Aliyun OSS (private bucket). Requires ossutil 2.x (recommended) or 1.x in PATH.
# Usage (same env vars as oss-upload-apk.sh):
#   $env:ALIYUN_OSS_ENDPOINT = "oss-cn-xxx.aliyuncs.com"
#   $env:ALIYUN_OSS_BUCKET = "bucket"
#   $env:ALIYUN_OSS_ACCESS_KEY_ID = "..."
#   $env:ALIYUN_OSS_ACCESS_KEY_SECRET = "..."
#   .\scripts\oss-upload-apk.ps1 <local-apk-path> <oss-object-key>
# Optional: ALIYUN_OSS_REGION / OSS_REGION — ossutil 2.x needs --region for SigV4; otherwise derived
#   from the endpoint host (oss-cn-hangzhou.aliyuncs.com -> cn-hangzhou).
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string] $LocalApk,
    [Parameter(Mandatory = $true, Position = 1)]
    [string] $OssObjectKey
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$required = @(
    "ALIYUN_OSS_ENDPOINT",
    "ALIYUN_OSS_BUCKET",
    "ALIYUN_OSS_ACCESS_KEY_ID",
    "ALIYUN_OSS_ACCESS_KEY_SECRET"
)
foreach ($name in $required) {
    $v = [Environment]::GetEnvironmentVariable($name, "Process")
    if ([string]::IsNullOrWhiteSpace($v)) {
        Write-Error "set $name"
    }
}

$ossutil = Get-Command ossutil -ErrorAction SilentlyContinue
if (-not $ossutil) {
    Write-Error "ossutil not found; install ossutil 2.0 from https://www.alibabacloud.com/help/en/oss/install-ossutil2"
}

$endpoint = $env:ALIYUN_OSS_ENDPOINT.Trim()
$endpoint = $endpoint -replace '^(https?://)', ''
$ep = "https://$endpoint"
$hostOnly = ($endpoint -split '/')[0]

$region = [Environment]::GetEnvironmentVariable("ALIYUN_OSS_REGION", "Process")
if ([string]::IsNullOrWhiteSpace($region)) {
    $region = [Environment]::GetEnvironmentVariable("OSS_REGION", "Process")
}
if ([string]::IsNullOrWhiteSpace($region)) {
    if ($hostOnly -match '^oss-(.+)-internal\.aliyuncs\.com$') {
        $region = $Matches[1]
    }
    elseif ($hostOnly -match '^oss-(.+)\.aliyuncs\.com$') {
        $region = $Matches[1]
    }
    else {
        throw "Set ALIYUN_OSS_REGION (e.g. cn-hangzhou); could not parse region from endpoint host '$hostOnly'"
    }
}

$bucket = $env:ALIYUN_OSS_BUCKET.Trim()
$dest = "oss://$bucket/$OssObjectKey"

& ossutil cp -f $LocalApk $dest `
    -e $ep `
    --region $region `
    -i $env:ALIYUN_OSS_ACCESS_KEY_ID `
    -k $env:ALIYUN_OSS_ACCESS_KEY_SECRET

if ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "Uploaded $dest"
