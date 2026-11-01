<#
.SYNOPSIS
    The receipt a whole-app walk leaves.

.DESCRIPTION
    Dot-sourced by walk-app.ps1. release/release.ps1 reads the receipt's result, screens and
    installed package, and won't publish until the walk has come back PASS from an APK patched with
    the bundle being released.
#>

# The whole-app walk profile and the one package it walks.
$WalkProfilePackages = [ordered]@{
    'tiktok-whole-app-v1' = 'com.zhiliaoapp.musically'
}
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'audit/evidence.ps1')
. (Join-Path $PSScriptRoot 'apk-facts.ps1')

function Get-WalkProfileName([string]$Package) {
    foreach ($entry in $WalkProfilePackages.GetEnumerator()) {
        if ($entry.Value -ceq $Package) { return [string]$entry.Key }
    }
    return $null
}

function New-WalkReceipt {
    <#
    .SYNOPSIS
        The receipt for one walk, as an ordered dictionary ready for ConvertTo-Json.
    #>
    param(
        [Parameter(Mandatory = $true)][ValidateSet('PASS', 'PARTIAL', 'FAIL')][string]$Result,
        [Parameter(Mandatory = $true)][bool]$Full,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$Device,
        [AllowEmptyString()][string]$AppVersion,
        [Parameter(Mandatory = $true)][string]$BundleSha256,
        [AllowEmptyString()][string]$InstalledApkSha256,
        [string]$InstalledPackage,
        [string]$InstalledVersionCode,
        $NativeBuild,
        [Parameter(Mandatory = $true)][DateTimeOffset]$Started,
        [Parameter(Mandatory = $true)][DateTimeOffset]$Finished,
        [int]$Screens,
        [int]$Crashes,
        [string[]]$Problems = @(),
        [string]$WalkProfile = 'tiktok-whole-app-v1'
    )
    if (-not $WalkProfilePackages.Contains($WalkProfile)) { throw "There's no whole-app walk profile named $WalkProfile." }
    return [ordered]@{
        schema = 2; profile = $WalkProfile; result = $Result; full = $Full
        serial = $Serial; device = $Device; emulator = $Serial.StartsWith('emulator-', [StringComparison]::Ordinal)
        appVersion = $AppVersion; bundleSha256 = $BundleSha256.ToLowerInvariant(); installedApkSha256 = $InstalledApkSha256
        installed = [ordered]@{
            package = $InstalledPackage; versionName = $AppVersion; versionCode = $InstalledVersionCode; sha256 = $InstalledApkSha256
        }
        build = if ($null -eq $NativeBuild) { $null } else { $NativeBuild | ConvertTo-Json -Depth 20 | ConvertFrom-Json }
        started = $Started.UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ssZ'); finished = $Finished.UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ssZ')
        screens = $Screens; crashes = $Crashes; problems = @($Problems)
    }
}
