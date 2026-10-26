# The PineDrama profile is an inspection prerequisite, never a whole-app qualification.
function Get-VerificationProbeProfile {
    param([ValidateSet('tiktok-whole-app-v1', 'pinedrama-47.5.3-capabilities-v1', IgnoreCase = $false)][string]$Profile = 'tiktok-whole-app-v1')
    if ($Profile -ceq 'tiktok-whole-app-v1') {
        return [pscustomobject]@{ Name = $Profile; Package = 'com.zhiliaoapp.musically'; ProbePackage = 'app.hushfeed.verification'
            Instrumentation = 'app.hushfeed.verification.Probe'; LogTag = 'HushfeedProbe'; Restricted = $false }
    }
    return [pscustomobject]@{ Name = $Profile; Package = 'com.ss.android.ttmd.video'; Version = '47.5.3'
        ProbePackage = 'app.hushfeed.verification.pinedrama'; Instrumentation = 'app.hushfeed.verification.PineDramaProbe'
        LogTag = 'HushfeedPineProbe'; Restricted = $true
        MissingCoverage = @('native-navigation', 'all-settings-pages', 'all-selected-runtime-patches', 'photo-gallery-visible',
            'photo-album-opens', 'account-server-entitlement', 'pause-and-switch-restoration', 'crash-free-whole-app-walk') }
}

function Get-PineDramaInspectionProblem {
    param($Plan, [string]$GitRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
    try {
        if (($Plan.schema -isnot [int] -and $Plan.schema -isnot [long]) -or $Plan.schema -ne 1 -or $Plan.kind -cne 'hushfeed-capability-inspection' -or $Plan.profile -cne 'pinedrama-47.5.3-capabilities-v1' -or
                $Plan.full -isnot [bool] -or $Plan.full -or $Plan.eligibleForPublication -isnot [bool] -or $Plan.eligibleForPublication) {
            return 'PineDrama requires its own incomplete capability-inspection plan.'
        }
        . (Join-Path $PSScriptRoot '../../audit/evidence.ps1')
        $app = [pscustomobject]@{ package = $Plan.build.apk.package; version = $Plan.build.apk.versionName; versionCode = $Plan.build.apk.versionCode }
        $problem = Get-DeviceNativeBuildProblem $Plan.build ([string]$Plan.build.apk.sha256) $app
        if ($problem) { return $problem }
        if ($app.package -cne 'com.ss.android.ttmd.video' -or $app.version -cne '47.5.3' -or
                [string]$Plan.build.sourceApk.versionCode -cne '2024705030') { return 'The PineDrama inspection profile requires its exact 47.5.3 source build.' }
        . (Join-Path $PSScriptRoot '../../scripts/patch-target.ps1')
        $catalogText = @(Invoke-RepoGit -Root $GitRoot -Arguments @('show', ([string]$Plan.build.source.commit + ':patches-list.json'))) -join [char]10
        if ($LASTEXITCODE -ne 0) { return 'Cannot verify the inspection subset in its source tree.' }
        $target = @(Get-DeclaredPatchTargets -PatchList ($catalogText | ConvertFrom-Json) |
            Where-Object { $_.PackageName -ceq 'com.ss.android.ttmd.video' -and '47.5.3' -cin $_.PackageVersions })
        if ($target.Count -ne 1 -or (Get-AuditHash @($target[0].PatchNames)) -cne (Get-AuditHash $Plan.expectedPatches)) {
            return 'The inspection subset differs from its exact source catalog.'
        }
        if ($Plan.expectedPatches -isnot [Array] -or $Plan.expectedPatches.Count -eq 0 -or
                (Get-AuditHash $Plan.build.patches) -cne (Get-AuditHash $Plan.expectedPatches) -or
                'Settings' -cnotin $Plan.expectedPatches -or 'Allow photo comments' -cnotin $Plan.expectedPatches) {
            return 'The PineDrama inspection needs the complete declared subset, including its positive photo-comment patch.'
        }
        if ($Plan.expectedOptions -isnot [Array] -or (Get-AuditHash $Plan.expectedOptions) -cne (Get-AuditHash $Plan.build.options)) {
            return 'The inspection option selection differs from the requested options.'
        }
        if ($Plan.missingCoverage -isnot [Array] -or
                (Get-AuditHash $Plan.missingCoverage) -cne (Get-AuditHash (Get-VerificationProbeProfile $Plan.profile).MissingCoverage)) {
            return 'The inspection omitted its unverified runtime prerequisites.'
        }
        if ([string]$Plan.catalogCommit -cne [string]$Plan.build.source.commit) { return 'The inspection catalog does not describe the build source.' }
        return $null
    } catch { return 'Malformed PineDrama inspection identity.' }
}

function Get-PineDramaStandaloneInputProblem {
    param([Parameter(Mandatory = $true)][string]$Apk, [Parameter(Mandatory = $true)][string]$Aapt2)
    $before = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& $Aapt2 dump xmltree --file AndroidManifest.xml $Apk 2>&1)
        $status = $LASTEXITCODE
    } finally { $ErrorActionPreference = $before }
    if ($status) { return 'Cannot inspect PineDrama standalone manifest requirements.' }
    foreach ($line in $lines) {
        if ([string]$line -cmatch '^\s*A:\s*(?:http://schemas\.android\.com/apk/res/android:)?(requiredSplitTypes|splitTypes|isSplitRequired|split|configForSplit|isFeatureSplit)(?:\(0x[0-9a-fA-F]+\))?=(.*)$') {
            $value = ConvertFrom-XmlTreeValue $Matches[2]
            if ($value -and $value -cne 'false') { return 'PineDrama inspection refuses a split-required source or output. Merge the complete original APKM first.' }
        }
    }
    return $null
}

function New-PineDramaInspectionPlan {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$StockApk, [Parameter(Mandatory = $true)][string]$PatchedApk,
        [Parameter(Mandatory = $true)][string]$AuditBuild, [Parameter(Mandatory = $true)][string]$Bundle,
        [Parameter(Mandatory = $true)][string]$GitRoot, [Parameter(Mandatory = $true)][string]$Aapt2,
        [string]$ExpectedCommit = 'HEAD', [string[]]$ExpectedOptions = @())
    . (Join-Path $PSScriptRoot '../../audit/evidence.ps1')
    . (Join-Path $PSScriptRoot '../../scripts/patch-target.ps1')
    $commit = [string](Invoke-RepoGit -Root $GitRoot -Arguments @('rev-parse', '--verify', "$ExpectedCommit^{commit}"))
    if ($LASTEXITCODE -ne 0 -or $commit -cnotmatch '^[a-f0-9]{40}$') { throw 'Cannot resolve the expected inspection source.' }
    $catalogText = @(Invoke-RepoGit -Root $GitRoot -Arguments @('show', ($commit + ':patches-list.json'))) -join [char]10
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read the inspection catalog from its exact source commit.' }
    $declared = @(Get-DeclaredPatchTargets -PatchList ($catalogText | ConvertFrom-Json) |
        Where-Object { $_.PackageName -ceq 'com.ss.android.ttmd.video' -and '47.5.3' -cin $_.PackageVersions })
    if ($declared.Count -ne 1) { throw 'The build source does not declare the PineDrama inspection target.' }
    $stockHash = (Get-FileHash -LiteralPath $StockApk -Algorithm SHA256).Hash.ToLowerInvariant()
    $patchedHash = (Get-FileHash -LiteralPath $PatchedApk -Algorithm SHA256).Hash.ToLowerInvariant()
    foreach ($apk in @($StockApk, $PatchedApk)) {
        $standaloneProblem = Get-PineDramaStandaloneInputProblem -Apk $apk -Aapt2 $Aapt2
        if ($standaloneProblem) { throw $standaloneProblem }
    }
    $stock = Get-ApkManifestFacts -Apk $StockApk -Aapt2 $Aapt2
    $patched = Get-ApkManifestFacts -Apk $PatchedApk -Aapt2 $Aapt2
    $app = [pscustomobject]@{ package = $patched.package; version = $patched.versionName; versionCode = $patched.versionCode }
    $build = Read-DeviceBuildIdentity $PatchedApk $AuditBuild $Bundle $GitRoot $app
    if ($build.source.commit -cne $commit) { throw 'The inspection build does not match the requested source commit.' }
    foreach ($pair in @(@($stock, $build.sourceApk, $stockHash), @($patched, $build.apk, $patchedHash))) {
        foreach ($field in @('package','versionName','versionCode')) {
            if ([string]$pair[0].$field -cne [string]$pair[1].$field) { throw 'An inspection APK manifest differs from its build receipt.' }
        }
        if ($pair[2] -cne [string]$pair[1].sha256) { throw 'An inspection APK hash differs from its build receipt.' }
    }
    if ($stockHash -ine (Get-FileHash -LiteralPath $StockApk -Algorithm SHA256).Hash -or
            $patchedHash -ine (Get-FileHash -LiteralPath $PatchedApk -Algorithm SHA256).Hash) { throw 'An inspection APK changed during preflight.' }
    $plan = [pscustomobject]@{ kind = 'hushfeed-capability-inspection'; schema = 1
        profile = 'pinedrama-47.5.3-capabilities-v1'; full = $false; eligibleForPublication = $false; build = $build
        expectedPatches = @($declared[0].PatchNames); expectedOptions = @($ExpectedOptions); catalogCommit = $commit
        missingCoverage = @((Get-VerificationProbeProfile 'pinedrama-47.5.3-capabilities-v1').MissingCoverage) }
    $problem = Get-PineDramaInspectionProblem $plan -GitRoot $GitRoot
    if ($problem) { throw $problem }
    return $plan
}

function ConvertFrom-PineDramaInspection {
    param([Parameter(Mandatory = $true)]$Plan, [Parameter(Mandatory = $true)][string[]]$Lines)
    $problem = Get-PineDramaInspectionProblem $Plan
    if ($problem) { throw $problem }
    $responses = @($Lines | Where-Object { $_ -cmatch '^pine-capabilities-v1 ' })
    if ($responses.Count -ne 1) { throw 'Expected exactly one PineDrama capability response.' }
    $keys = @('requestAccepted','targetPackageMatches','targetVersionMatches','profileMatches','actionMatches','installedApkMatches','singleBaseApk',
        'photoPatchRegistered','photoSwitchOn','nativePhotoGateShape','nativeAlbumEntryShape',
        'photoPermissionsDeclared','settingsActivityDeclared','settingsActivityForeground','wholeAppCoverageComplete')
    $values = [ordered]@{}
    foreach ($token in $responses[0].Substring('pine-capabilities-v1 '.Length).Split(' ')) {
        if ($token -cnotmatch '^([A-Za-z]+)=(true|false)$' -or $Matches[1] -cnotin $keys -or $values.Contains($Matches[1])) {
            throw 'The PineDrama response contains unknown, repeated or non-boolean data.'
        }
        $values[$Matches[1]] = $Matches[2] -ceq 'true'
    }
    if ($values.Count -ne $keys.Count -or $values.wholeAppCoverageComplete) { throw 'The PineDrama response is incomplete or claims unsupported coverage.' }
    foreach ($required in @('requestAccepted','targetPackageMatches','targetVersionMatches','profileMatches','actionMatches','installedApkMatches','singleBaseApk',
            'photoPatchRegistered','nativePhotoGateShape','nativeAlbumEntryShape','photoPermissionsDeclared','settingsActivityDeclared')) {
        if (-not $values[$required]) { throw 'The PineDrama capability prerequisite was not met.' }
    }
    return [pscustomobject]@{ kind = $Plan.kind; schema = 1; profile = $Plan.profile; status = 'INCOMPLETE'
        full = $false; eligibleForPublication = $false; build = $Plan.build; capabilities = [pscustomobject]$values
        missingCoverage = @($Plan.missingCoverage) }
}

function Write-VerificationProbeManifest {
    param([Parameter(Mandatory = $true)][string]$Template, [Parameter(Mandatory = $true)][string]$Destination,
        [ValidateSet('tiktok-whole-app-v1', 'pinedrama-47.5.3-capabilities-v1', IgnoreCase = $false)][string]$Profile = 'tiktok-whole-app-v1')
    $target = Get-VerificationProbeProfile $Profile
    $text = [IO.File]::ReadAllText($Template)
    if (-not $target.Restricted) { [IO.File]::WriteAllText($Destination, $text, [Text.UTF8Encoding]::new($false)); return }
    [xml]$xml = $text
    $android = 'http://schemas.android.com/apk/res/android'
    $xml.DocumentElement.SetAttribute('package', $target.ProbePackage)
    foreach ($node in @($xml.DocumentElement.SelectNodes('instrumentation'))) { [void]$xml.DocumentElement.RemoveChild($node) }
    $instrumentation = $xml.CreateElement('instrumentation')
    [void]$instrumentation.SetAttribute('name', $android, $target.Instrumentation)
    [void]$instrumentation.SetAttribute('targetPackage', $android, $target.Package)
    [void]$instrumentation.SetAttribute('label', $android, 'Hushfeed PineDrama capability inspection')
    [void]$xml.DocumentElement.InsertBefore($instrumentation, $xml.DocumentElement.SelectSingleNode('application'))
    # No audio fixture, storage instrumentation or foreground-service permission is reviewed here.
    foreach ($node in @($xml.DocumentElement.SelectNodes('uses-permission')) + @($xml.DocumentElement.SelectNodes('application/service'))) {
        [void]$node.ParentNode.RemoveChild($node)
    }
    $xml.Save($Destination)
}
