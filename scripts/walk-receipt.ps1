<#
.SYNOPSIS
    The receipt a whole-app walk leaves, and what makes one good enough to publish a bundle on.

.DESCRIPTION
    Dot-sourced by release/release.ps1, whose publish stage refuses to publish a bundle without a
    receipt that backs it. Nothing goes public until an APK patched from the exact bundle being
    released has been walked end to end on a release phone or an emulator and came back PASS:
    every Hush settings page and every patched screen, with no TikTok or PineDrama crash.

    Schema 2 retains the verified native build and observed installed package/version/APK hash.
    Publish supplies the expected source APK, commit and exact patch/options selection, and checks
    them against that identity and the actual bundle manifest. Schema 1 remains historical only.
    The implemented whole-app profile covers global TikTok; other apps need their own profile.
#>

# The phones release walks run on, by ro.product.model. An emulator passes by its serial.
# Only the S22: TikTok on the S25 (SM-S938B) is a personal account, and Hushfeed is never
# walked there.
$WalkReceiptPhones = @('SM-S908U1')
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'audit/evidence.ps1')
. (Join-Path $PSScriptRoot 'release-receipt.ps1')

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
        [string[]]$Problems = @()
    )
    return [ordered]@{
        schema = 2; profile = 'tiktok-whole-app-v1'; result = $Result; full = $Full
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


function Read-WalkReceipt {
    param([Parameter(Mandatory = $true)][string]$Path)
    $receipt = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($null -eq $receipt -or ($receipt.schema -isnot [int] -and $receipt.schema -isnot [long]) -or $receipt.schema -notin @(1,2)) {
        throw 'Unsupported walk receipt schema.'
    }
    return [pscustomobject]@{ Receipt = $receipt; Historical = $receipt.schema -eq 1 }
}

function Test-WalkReceipt {
    <#
    .SYNOPSIS
        Whether the receipt at -Path is a full, passing walk of the bundle at -BundlePath, on a
        release phone or an emulator, with the expected source APK, source commit and ordered
        patch/options selection. Missing expectations cannot authorize publication.
        Answers @{ Valid; Reason; Receipt }.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$BundlePath,
        $ExpectedSourceApk,
        [string]$ExpectedCommit,
        [string[]]$ExpectedPatches = @(),
        [string[]]$ExpectedOptions = @()
    )
    function Fail([string]$Reason) { return [pscustomobject]@{ Valid = $false; Reason = $Reason; Receipt = $null } }
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return Fail "there's no walk receipt at $Path" }
    try { $read = Read-WalkReceipt -Path $Path; $receipt = $read.Receipt }
    catch { return Fail "the walk receipt doesn't read as JSON: $($_.Exception.Message)" }
    if ($read.Historical) { return Fail 'the schema 1 walk is historical and cannot authorize a new publication' }
    if ($receipt.profile -isnot [string] -or $receipt.profile -cne 'tiktok-whole-app-v1') { return Fail 'the walk has no implemented whole-app profile' }
    if ($receipt.result -isnot [string] -or $receipt.result -cne 'PASS') {
        return Fail "the walk ended $(if ($receipt.result -is [string] -and $receipt.result) { $receipt.result } else { 'with no result' }), not PASS"
    }
    if ($receipt.full -isnot [bool] -or -not $receipt.full) { return Fail 'the walk skipped part of the app, so it was not the whole app' }
    if (($receipt.screens -isnot [int] -and $receipt.screens -isnot [long]) -or $receipt.screens -lt 1) {
        return Fail "the walk receipt doesn't say it opened any screen"
    }
    if (($receipt.crashes -isnot [int] -and $receipt.crashes -isnot [long]) -or $receipt.crashes -ne 0) {
        return Fail 'the walk recorded a TikTok or PineDrama crash'
    }
    if ($receipt.device -isnot [string] -or $receipt.serial -isnot [string] -or $receipt.emulator -isnot [bool]) {
        return Fail "the walk receipt doesn't say which device walked it"
    }
    $isEmulator = $receipt.serial.StartsWith('emulator-', [StringComparison]::Ordinal)
    if ($receipt.emulator -ne $isEmulator -or (-not $isEmulator -and $WalkReceiptPhones -cnotcontains $receipt.device)) {
        return Fail "$($receipt.device) isn't one of the release walk phones ($($WalkReceiptPhones -join ', ')) or an emulator"
    }
    if (-not (Test-Path -LiteralPath $BundlePath -PathType Leaf)) { return Fail "there's no bundle at $BundlePath to hold the walk to" }
    $bundleSha256 = (Get-FileHash -LiteralPath $BundlePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($receipt.bundleSha256 -isnot [string] -or $receipt.bundleSha256 -cne $bundleSha256) {
        return Fail "the walked APK was patched with bundle '$($receipt.bundleSha256)', and this one is '$bundleSha256'"
    }
    try {
        if ($null -eq $ExpectedSourceApk -or $ExpectedCommit -cnotmatch '^[a-f0-9]{40}$' -or $ExpectedPatches.Count -eq 0) {
            return Fail 'the walk needs an expected source APK, source commit and exact selection'
        }
        if ($receipt.problems -isnot [Array] -or $receipt.problems.Count -ne 0) { return Fail 'the walk has unresolved problems' }
        $installed = $receipt.installed
        if ($null -eq $installed -or $receipt.appVersion -isnot [string] -or $receipt.installedApkSha256 -isnot [string]) {
            return Fail 'the walk has no typed installed APK identity'
        }
        foreach ($field in @('package','versionName','versionCode','sha256')) {
            if ($installed.$field -isnot [string]) { return Fail 'the walk has malformed installed APK facts' }
        }
        if ($null -eq $installed -or [string]$installed.package -cne 'com.zhiliaoapp.musically' -or
                [string]$installed.package -cne [string]$ExpectedSourceApk.package -or
                [string]$installed.versionName -cne [string]$ExpectedSourceApk.versionName -or
                [string]$installed.versionName -cne [string]$receipt.appVersion -or
                [string]$installed.sha256 -cne [string]$receipt.installedApkSha256) {
            return Fail 'the installed package, version or APK hash does not match the walk target'
        }
        $app = [pscustomobject]@{ package = $installed.package; version = $installed.versionName; versionCode = $installed.versionCode }
        $problem = Get-AuditNativeBuildProblem $receipt.build ([string]$installed.sha256) $app
        if ($problem) { return Fail $problem }
        if ([string]$receipt.build.bundle.sha256 -cne $bundleSha256) { return Fail 'the native build used a different bundle' }
        $manifest = Get-BundleManifestFacts -BundlePath $BundlePath
        $sourceCheck = Test-BuildSourceFacts -Source $manifest.source -ExpectedCommit $ExpectedCommit
        if (-not $sourceCheck.Valid -or (Get-AuditHash $manifest.source) -cne (Get-AuditHash $receipt.build.source)) {
            return Fail 'the walk source does not match this clean bundle build and expected commit'
        }
        foreach ($field in @('package','versionName','versionCode','sha256')) {
            if ([string]$receipt.build.sourceApk.$field -cne [string]$ExpectedSourceApk.$field) {
                return Fail "the walk source APK $field does not match the expected fixture"
            }
        }
        if ((Get-AuditHash @($receipt.build.patches)) -cne (Get-AuditHash @($ExpectedPatches)) -or
                (Get-AuditHash @($receipt.build.options)) -cne (Get-AuditHash @($ExpectedOptions))) {
            return Fail 'the walk patch and option selection does not match the expected build'
        }
    } catch { return Fail 'the walk has malformed or unreadable native build identity' }
    return [pscustomobject]@{ Valid = $true; Reason = $null; Receipt = $receipt }
}

function Get-WalkPublicationProblem($Target) {
    # This single-walk stage cannot stand in for missing package/version profiles.
    try {
        if ($null -eq $Target -or [string]$Target.PackageName -cne 'com.zhiliaoapp.musically') {
            return 'The current whole-app walk stage only has a global TikTok profile.'
        }
        if (@($Target.OtherPackages).Count -gt 0) {
            return 'Publication needs separate native walks for every declared package; another package has no implemented walk profile.'
        }
        if (@($Target.PackageVersions).Count -ne 1) {
            return 'Publication needs separate native walks for every declared target version.'
        }
        return $null
    } catch { return 'The publication target has no complete native walk coverage declaration.' }
}
