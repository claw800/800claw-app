# Upload a local APK to Cloudflare R2 for the v2 account-gated download Worker.
# Requires Wrangler auth. Defaults to the remote R2 bucket currently bound by
# v2.download.800claw.com.
#
# Usage:
#   .\scripts\r2-upload-apk.ps1 <local-apk-path> <r2-object-key>
#
# Example keys:
#   prod/artifacts/apks/800claw-termux-app/800claw-termux-app-latest-universal.apk
#   prod/artifacts/apks/800claw-termux-app/800claw-termux-app-v1.0.0+abc1234-universal.apk
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string] $LocalApk,
    [Parameter(Mandatory = $true, Position = 1)]
    [string] $R2ObjectKey,
    [Parameter(Mandatory = $false)]
    [string] $Bucket = "800claw-public",
    [switch] $Local
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $LocalApk -PathType Leaf)) {
    throw "APK not found: $LocalApk"
}

$wrangler = Get-Command wrangler.cmd -ErrorAction SilentlyContinue
if (-not $wrangler) {
    $wrangler = Get-Command wrangler -ErrorAction SilentlyContinue
}

$args = @("r2", "object", "put", "$Bucket/$R2ObjectKey", "--file", $LocalApk)
if (-not $Local) {
    $args += "--remote"
}

if ($wrangler) {
    & $wrangler.Source @args
} else {
    $npx = Get-Command npx.cmd -ErrorAction SilentlyContinue
    if (-not $npx) {
        $npx = Get-Command npx -ErrorAction SilentlyContinue
    }
    if (-not $npx) {
        throw "Neither wrangler nor npx was found on PATH."
    }
    & $npx.Source wrangler @args
}

if ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "Uploaded r2://$Bucket/$R2ObjectKey"
