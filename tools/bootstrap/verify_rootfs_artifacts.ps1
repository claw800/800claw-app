param(
    [string]$Root = "tools/bootstrap/output",
    [switch]$ExtractArtifacts
)

$ErrorActionPreference = "Stop"

function Must-Exist([string]$PathValue) {
    if (-not (Test-Path -LiteralPath $PathValue)) {
        throw "Required path not found: $PathValue"
    }
}

function Resolve-TopLevelEntries([string]$TarPath) {
    $entries = tar -tf $TarPath
    return $entries |
        ForEach-Object { ($_ -split '/')[0] } |
        Where-Object { $_ -and $_.Trim().Length -gt 0 } |
        Sort-Object -Unique
}

function Verify-OneArch([string]$BaseDir, [string]$Arch) {
    $tarName = "ubuntu-noble-$Arch.tar.xz"
    $archDir = Join-Path $BaseDir "rootfs\$Arch"
    Must-Exist $archDir

    $tarPath = Join-Path $archDir $tarName
    Must-Exist $tarPath

    foreach ($meta in @("dpkg-manifest.txt", "python-version.txt", "node-version.txt")) {
        Must-Exist (Join-Path $archDir $meta)
    }

    Write-Host "Checking tar integrity: $tarPath" -ForegroundColor Cyan
    # Full listing to null is a practical integrity smoke check.
    tar -tf $tarPath > $null
    Write-Host "Integrity OK: $tarName" -ForegroundColor Green

    $top = Resolve-TopLevelEntries $tarPath
    Write-Host ""
    Write-Host "Top-level entries ($Arch):" -ForegroundColor Yellow
    $top | Select-Object -First 40 | ForEach-Object { Write-Host "  $_" }

    $hash = Get-FileHash -Algorithm SHA256 -LiteralPath $tarPath
    $item = Get-Item -LiteralPath $tarPath
    [PSCustomObject]@{
        Arch = $Arch
        Tarball = $item.Name
        SizeMB = [Math]::Round($item.Length / 1MB, 2)
        SHA256 = $hash.Hash
    }
}

Must-Exist $Root

$armZip = Join-Path $Root "ubuntu-rootfs-arm64-v8a.zip"
$x64Zip = Join-Path $Root "ubuntu-rootfs-x86_64.zip"

if ($ExtractArtifacts) {
    Must-Exist $armZip
    Must-Exist $x64Zip

    $armOut = Join-Path $Root "rootfs\arm64-v8a"
    $x64Out = Join-Path $Root "rootfs\x86_64"
    New-Item -ItemType Directory -Force -Path $armOut | Out-Null
    New-Item -ItemType Directory -Force -Path $x64Out | Out-Null

    Write-Host "Extracting artifact zips..." -ForegroundColor Cyan
    Expand-Archive -LiteralPath $armZip -DestinationPath $armOut -Force
    Expand-Archive -LiteralPath $x64Zip -DestinationPath $x64Out -Force
}

$results = @()
$results += Verify-OneArch -BaseDir $Root -Arch "arm64-v8a"
$results += Verify-OneArch -BaseDir $Root -Arch "x86_64"

Write-Host ""
Write-Host "Summary:" -ForegroundColor Green
$results | Format-Table -AutoSize

