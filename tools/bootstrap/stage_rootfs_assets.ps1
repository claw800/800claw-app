param(
    [ValidateSet("arm64-v8a", "x86_64", "all")]
    [string]$Arch = "all",

    [string]$Source = "",
    [string]$Dest = ""
)

$ErrorActionPreference = "Stop"

function Resolve-RepoRoot {
    # Prefer automatic script-scoped vars first; they are reliable across direct invocation
    # and most dot-sourcing scenarios.
    $scriptDir = $PSScriptRoot
    if ([string]::IsNullOrWhiteSpace($scriptDir) -and -not [string]::IsNullOrWhiteSpace($PSCommandPath)) {
        $scriptDir = Split-Path -Parent $PSCommandPath
    }
    if ([string]::IsNullOrWhiteSpace($scriptDir) -and -not [string]::IsNullOrWhiteSpace($MyInvocation.MyCommand.Path)) {
        $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    }
    if ([string]::IsNullOrWhiteSpace($scriptDir)) {
        # Final fallback to current location if script metadata is unavailable in caller context.
        $scriptDir = (Get-Location).Path
    }
    return (Resolve-Path (Join-Path $scriptDir "..\..")).Path
}

function Ensure-Dir([string]$PathValue) {
    if (-not (Test-Path -LiteralPath $PathValue)) {
        New-Item -ItemType Directory -Path $PathValue -Force | Out-Null
    }
}

function Find-Tarball([string]$basePath, [string]$arch) {
    $expectedName = "ubuntu-noble-$arch.tar.xz"

    # Preferred layout: <source>/<arch>/ubuntu-noble-<arch>.tar.xz
    $nested = Join-Path (Join-Path $basePath $arch) $expectedName
    if (Test-Path -LiteralPath $nested) {
        return @{
            Tarball = (Resolve-Path $nested).Path
            Mode = "nested"
            MetadataRoot = (Resolve-Path (Join-Path $basePath $arch)).Path
        }
    }

    # Flat layout (common after downloading and unzipping single GitHub artifact)
    $flat = Join-Path $basePath $expectedName
    if (Test-Path -LiteralPath $flat) {
        return @{
            Tarball = (Resolve-Path $flat).Path
            Mode = "flat"
            MetadataRoot = (Resolve-Path $basePath).Path
        }
    }

    throw "Missing tarball for arch '$arch'. Checked: '$nested' and '$flat'."
}

function Copy-OptionalMetadata([string]$metadataRoot, [string]$destMetadataRoot) {
    Ensure-Dir $destMetadataRoot
    foreach ($name in @("dpkg-manifest.txt", "python-version.txt", "node-version.txt")) {
        $src = Join-Path $metadataRoot $name
        if (Test-Path -LiteralPath $src) {
            Copy-Item -LiteralPath $src -Destination (Join-Path $destMetadataRoot $name) -Force
        }
    }
}

$repoRoot = Resolve-RepoRoot
if ([string]::IsNullOrWhiteSpace($Source)) {
    $Source = Join-Path $repoRoot "tools\bootstrap\output\rootfs"
}
if ([string]::IsNullOrWhiteSpace($Dest)) {
    $Dest = Join-Path $repoRoot "app\src\main\assets\rootfs"
}

if (-not (Test-Path -LiteralPath $Source)) {
    throw "Source path not found: $Source"
}

Ensure-Dir $Dest
Ensure-Dir (Join-Path $Dest "metadata")

$archList = if ($Arch -eq "all") { @("arm64-v8a", "x86_64") } else { @($Arch) }

foreach ($currentArch in $archList) {
    $result = Find-Tarball -basePath $Source -arch $currentArch
    $targetTarball = Join-Path $Dest ("ubuntu-noble-{0}.tar.xz" -f $currentArch)

    Write-Host ("Staging {0} (mode: {1})" -f (Split-Path -Leaf $result.Tarball), $result.Mode)
    Copy-Item -LiteralPath $result.Tarball -Destination $targetTarball -Force

    $destMeta = Join-Path (Join-Path $Dest "metadata") $currentArch
    Copy-OptionalMetadata -metadataRoot $result.MetadataRoot -destMetadataRoot $destMeta
}

Write-Host "Staged rootfs assets to: $Dest"
Get-ChildItem -LiteralPath $Dest | Select-Object Name, Length, LastWriteTime
