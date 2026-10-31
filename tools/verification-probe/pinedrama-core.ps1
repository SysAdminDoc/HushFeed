# Package-specific core evidence. Never accepted as a whole-app or release receipt.
. (Join-Path $PSScriptRoot 'profile.ps1')
. (Join-Path $PSScriptRoot 'probe-log.ps1')
. (Join-Path $PSScriptRoot '../../audit/evidence.ps1')

function New-PineDramaWalkLease {
    param([Parameter(Mandatory = $true)][string]$Serial, [AllowNull()]$Lease,
        [string]$LeaseDir, [string]$ChatIdentity)
    if ($null -ne $Lease) {
        Assert-DeviceLease -Lease $Lease -Serial $Serial
        Update-DeviceLease -Lease $Lease -LeaseMinutes 30
        return [pscustomobject]@{ Lease = $Lease; OwnedByWalk = $false }
    }
    $owned = New-DeviceLease -LeaseDir $LeaseDir -Serial $Serial -Project hushfeed -ChatIdentity $ChatIdentity `
        -Purpose 'PineDrama core native walk, incomplete qualification' -LeaseMinutes 30
    return [pscustomobject]@{ Lease = $owned; OwnedByWalk = $true }
}

function Remove-PineDramaWalkLease {
    param([AllowNull()]$Context)
    if ($null -ne $Context -and $Context.OwnedByWalk -is [bool] -and $Context.OwnedByWalk) {
        Remove-DeviceLease -Lease $Context.Lease -WarnOnFailure
    }
}

function Get-PineDramaCoreDefinition {
    param([string]$GitRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)), [string]$Commit = 'HEAD',
        [ValidateSet('pinedrama-47.5.3-core-walk-v1', 'pinedrama-47.5.3-core-walk-v2', IgnoreCase = $false)]
        [string]$Profile = 'pinedrama-47.5.3-core-walk-v2')
    $file = $Profile.Replace('-walk-', '-') + '.json'
    $text = @(Invoke-RepoGit -Root $GitRoot -Arguments @('show', ($Commit + ':audit/profiles/' + $file))) -join [char]10
    if ($LASTEXITCODE -ne 0) { throw 'The source tree has no reviewed PineDrama core profile.' }
    return $text | ConvertFrom-Json
}

function Get-PineDramaCoreCoverageProblem {
    param($Definition, [string[]]$ExpectedPatches)
    try {
        $mapped = @($Definition.coverage | ForEach-Object { $_.patches })
        $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($name in $mapped) { if (-not $names.Add($name)) { return 'A PineDrama patch occurs in more than one coverage group.' } }
        if ($mapped.Count -ne 98 -or (Get-AuditHash @($mapped | Sort-Object -CaseSensitive)) -cne
                (Get-AuditHash @($ExpectedPatches | Sort-Object -CaseSensitive))) { return 'The PineDrama core coverage map is not the complete declared 98-patch subset.' }
        foreach ($group in @($Definition.coverage)) {
            if ($group.status -cne 'pending-native-qualification' -or [string]::IsNullOrWhiteSpace($group.route) -or
                    [string]::IsNullOrWhiteSpace($group.prerequisites)) { return 'PineDrama coverage omitted a route or prerequisite, or claimed unsupported qualification.' }
        }
        return $null
    } catch { return 'Malformed PineDrama core coverage map.' }
}

function Get-PineDramaCorePlanProblem {
    param($Plan, [string]$GitRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
    try {
        if ($Plan.kind -cne 'hushfeed-pinedrama-core-plan' -or ($Plan.schema -isnot [int] -and $Plan.schema -isnot [long]) -or $Plan.schema -ne 1 -or
                $Plan.profile -cnotin @('pinedrama-47.5.3-core-walk-v1', 'pinedrama-47.5.3-core-walk-v2') -or $Plan.full -isnot [bool] -or $Plan.full -or
                $Plan.eligibleForPublication -isnot [bool] -or $Plan.eligibleForPublication) { return 'PineDrama core plans must remain incomplete and ineligible for publication.' }
        $problem = Get-PineDramaInspectionProblem $Plan.inspection -GitRoot $GitRoot
        if ($problem) { return $problem }
        $definition = Get-PineDramaCoreDefinition -GitRoot $GitRoot -Commit $Plan.inspection.build.source.commit -Profile $Plan.profile
        if ($definition.profile -cne $Plan.profile) { return 'The PineDrama core profile does not match its definition.' }
        if ((Get-AuditHash $definition) -cne (Get-AuditHash $Plan.definition)) { return 'The PineDrama core definition differs from its exact source tree.' }
        $problem = Get-PineDramaCoreCoverageProblem $definition $Plan.inspection.expectedPatches
        if ($problem) { return $problem }
        $source = $Plan.inspection.build.sourceApk
        foreach ($field in @('package','versionName','versionCode','sha256')) {
            if ([string]$source.$field -cne [string]$definition.source.$field) { return 'The PineDrama core profile requires its reviewed merged source APK.' }
        }
        if ($Plan.sourceBytes -isnot [long] -and $Plan.sourceBytes -isnot [int]) { return 'The PineDrama core plan has no exact source byte length.' }
        if ($Plan.sourceBytes -ne $definition.source.bytes) { return 'The PineDrama merged source byte length changed.' }
        if (-not (Test-AuditExecutableInputBinding $Plan.inspection.build $null $GitRoot)) { return 'The PineDrama core plan has stale executable inputs.' }
        return $null
    } catch { return 'Malformed PineDrama core plan or unavailable source definition.' }
}

function New-PineDramaCorePlan {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$StockApk, [Parameter(Mandatory = $true)][string]$PatchedApk,
        [Parameter(Mandatory = $true)][string]$AuditBuild, [Parameter(Mandatory = $true)][string]$Bundle,
        [Parameter(Mandatory = $true)][string]$GitRoot, [Parameter(Mandatory = $true)][string]$Aapt2,
        [string[]]$ExpectedOptions = @())
    $workingProblem = Get-AuditWorkingInputProblem $GitRoot
    if ($workingProblem) { throw $workingProblem }
    $inspection = New-PineDramaInspectionPlan -StockApk $StockApk -PatchedApk $PatchedApk -AuditBuild $AuditBuild `
        -Bundle $Bundle -GitRoot $GitRoot -Aapt2 $Aapt2 -ExpectedOptions $ExpectedOptions
    $plan = [pscustomobject]@{ kind = 'hushfeed-pinedrama-core-plan'; schema = 1; profile = 'pinedrama-47.5.3-core-walk-v2'
        full = $false; eligibleForPublication = $false; inspection = $inspection
        definition = Get-PineDramaCoreDefinition -GitRoot $GitRoot -Commit $inspection.build.source.commit
        sourceBytes = (Get-Item -LiteralPath $StockApk).Length }
    $problem = Get-PineDramaCorePlanProblem $plan -GitRoot $GitRoot
    if ($problem) { throw $problem }
    return $plan
}

function ConvertFrom-PineDramaCoreResponse {
    param([Parameter(Mandatory = $true)][string[]]$Lines,
        [ValidateSet('pinedrama-47.5.3-core-walk-v1', 'pinedrama-47.5.3-core-walk-v2', IgnoreCase = $false)]
        [string]$Profile = 'pinedrama-47.5.3-core-walk-v2')
    $inline = $Profile -ceq 'pinedrama-47.5.3-core-walk-v2'
    $prefix = if ($inline) { 'pine-core-v2 ' } else { 'pine-core-v1 ' }
    $responses = @($Lines | Where-Object { $_ -cmatch '^pine-core-v[0-9]+ ' })
    if ($responses.Count -ne 1) { throw 'Expected one complete PineDrama core response.' }
    if (-not $responses[0].StartsWith($prefix, [StringComparison]::Ordinal)) { throw 'The PineDrama response belongs to another route profile.' }
    $keys = @('requestAccepted','targetMatches','installedIdentityMatches','foregroundOwned','nativeMainForeground',
        'settingsHomeForeground','commentsRowPresent','nativeOwnerRestored','actionAccepted')
    $keys += if ($inline) { @('commentsExpanded','commentsCollapsed','settingsOwnerRetained') } else { @('commentsForeground') }
    $values = [ordered]@{}
    foreach ($token in $responses[0].Substring($prefix.Length).Split(' ')) {
        if ($token -cnotmatch '^([A-Za-z]+)=(true|false)$' -or $Matches[1] -cnotin $keys -or $values.Contains($Matches[1])) {
            throw 'PineDrama core responses permit only the exact boolean contract.'
        }
        $values[$Matches[1]] = $Matches[2] -ceq 'true'
    }
    if ($values.Count -ne $keys.Count -or -not $values.requestAccepted -or -not $values.targetMatches -or
            -not $values.installedIdentityMatches -or -not $values.actionAccepted) { throw 'The PineDrama core request or native action was rejected.' }
    $routeKeys = @('nativeMainForeground','settingsHomeForeground')
    if (-not $inline) { $routeKeys += 'commentsForeground' }
    $routes = @($routeKeys | Where-Object { $values[$_] })
    if ($routes.Count -gt 1 -or ($routes.Count -gt 0 -and -not $values.foregroundOwned) -or
            ($values.commentsRowPresent -and -not $values.settingsHomeForeground) -or
            ($values.nativeOwnerRestored -and -not $values.nativeMainForeground)) { throw 'The PineDrama core response contains conflicting route evidence.' }
    if ($inline -and (($values.commentsExpanded -and $values.commentsCollapsed) -or
            (($values.commentsExpanded -or $values.commentsCollapsed) -and -not $values.commentsRowPresent) -or
            ($values.settingsOwnerRetained -and -not $values.settingsHomeForeground))) {
        throw 'The PineDrama core response contains conflicting inline settings evidence.'
    }
    return [pscustomobject]$values
}

function Invoke-PineDramaCoreAction {
    param([Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][ValidateSet('snapshot','open-settings','open-comments','back-home','expand-comments','collapse-comments','back-native', IgnoreCase = $false)][string]$Action,
        [Parameter(Mandatory = $true)]$Plan, [Parameter(Mandatory = $true)][AllowNull()]$Lease,
        [string]$GitRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)), [string]$Adb = 'adb', [int]$WaitMs = 2000,
        [ref]$RequestId)
    if ($null -ne $RequestId) { $RequestId.Value = $null }
    $problem = Get-PineDramaCorePlanProblem $Plan -GitRoot $GitRoot
    if ($problem) { throw $problem }
    $inline = $Plan.profile -ceq 'pinedrama-47.5.3-core-walk-v2'
    if (($inline -and $Action -cin @('open-comments','back-home')) -or
            (-not $inline -and $Action -cin @('expand-comments','collapse-comments'))) { throw 'This action belongs to another PineDrama route profile.' }
    $workingProblem = Get-AuditWorkingInputProblem $GitRoot
    if ($workingProblem) { throw $workingProblem }
    $profile = Get-VerificationProbeProfile $Plan.profile
    $request = [guid]::NewGuid().ToString('N')
    $marker = 'hushfeed-pine-core-' + $request
    Assert-DeviceLease -Lease $Lease -Serial $Serial
    & $Adb -s $Serial shell log -t $profile.LogTag $marker
    if ($LASTEXITCODE) { throw 'Could not mark the PineDrama core response.' }
    Assert-DeviceLease -Lease $Lease -Serial $Serial
    & $Adb -s $Serial shell am broadcast -a app.hushfeed.verification.PINE_CORE -p $profile.Package `
        -e request $request -e profile $Plan.profile -e action $Action -e apkSha256 $Plan.inspection.build.apk.sha256 `
        -e versionCode $Plan.inspection.build.apk.versionCode | Out-Null
    if ($LASTEXITCODE) { throw 'Could not send the PineDrama core request.' }
    Start-Sleep -Milliseconds $WaitMs
    Assert-DeviceLease -Lease $Lease -Serial $Serial
    $lines = @(& $Adb -s $Serial logcat -d -s "$($profile.LogTag):V" -v raw)
    if ($LASTEXITCODE) { throw 'Could not read the PineDrama core response.' }
    $answer = @(Select-ProbeRun -Lines $lines -Marker $marker -Serial $Serial)
    $prefix = 'pine-core-response-' + $request + ' '
    $matching = @($answer | Where-Object { $_.StartsWith($prefix, [StringComparison]::Ordinal) })
    if ($matching.Count -ne 1) { throw 'No unique PineDrama core answer matches this request.' }
    $proof = ConvertFrom-PineDramaCoreResponse @($matching[0].Substring($prefix.Length)) -Profile $Plan.profile
    if ($null -ne $RequestId) { $RequestId.Value = $request }
    return $proof
}

function New-PineDramaCoreResult {
    param([Parameter(Mandatory = $true)]$Plan, [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Checks,
        [Parameter(Mandatory = $true)]$InstalledBefore, [Parameter(Mandatory = $true)]$InstalledAfter,
        [bool]$SameProcess, [ValidateRange(0, 2147483647)][int]$Crashes, [string[]]$Problems = @(), [object[]]$Evidence = @(),
        [string]$GitRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
    $problem = Get-PineDramaCorePlanProblem $Plan -GitRoot $GitRoot
    if ($problem) { throw $problem }
    foreach ($identity in @($InstalledBefore,$InstalledAfter)) {
        foreach ($field in @('package','versionName','versionCode','sha256')) {
            if ([string]$identity.$field -cne [string]$Plan.inspection.build.apk.$field) { throw 'PineDrama installed identity changed or differs from the planned APK.' }
        }
    }
    $steps = @($Plan.definition.coreSteps)
    if ($Checks.Count -gt $steps.Count) { throw 'Unexpected PineDrama core checks.' }
    $files = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $Evidence) {
        if ($entry.file -isnot [string] -or $entry.file -cnotmatch '^[A-Za-z0-9_-]+\.(json|png|txt)$' -or
                -not $files.Add($entry.file)) { throw 'PineDrama core evidence paths are invalid or repeated.' }
    }
    $observations = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $requests = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    for ($index = 0; $index -lt $Checks.Count; $index++) {
        $check = $Checks[$index]
        if ($check.id -cne $steps[$index] -or $check.status -cnotin @('pass','inconclusive','fail') -or
                $check.observed -isnot [bool] -or ($check.status -ceq 'pass' -and -not $check.observed)) { throw 'PineDrama core checks are incomplete, repeated or out of order.' }
        if ($check.status -ceq 'pass') {
            if ($check.requestId -isnot [string] -or $check.requestId -cnotmatch '^[a-f0-9]{32}$' -or
                    -not $requests.Add($check.requestId)) { throw 'A PineDrama core pass has no unique native request.' }
            $tokens = @($check.proof.PSObject.Properties | ForEach-Object {
                if ($_.Value -isnot [bool]) { throw 'PineDrama route proof must contain only booleans.' }
                $_.Name + '=' + $_.Value.ToString().ToLowerInvariant()
            })
            $inline = $Plan.profile -ceq 'pinedrama-47.5.3-core-walk-v2'
            $prefix = if ($inline) { 'pine-core-v2 ' } else { 'pine-core-v1 ' }
            $proof = ConvertFrom-PineDramaCoreResponse @($prefix + ($tokens -join ' ')) -Profile $Plan.profile
            $field = if ($inline) { @('nativeMainForeground','commentsCollapsed','commentsExpanded','commentsCollapsed','nativeMainForeground')[$index] }
                else { @('nativeMainForeground','settingsHomeForeground','commentsForeground','settingsHomeForeground','nativeMainForeground')[$index] }
            if (-not $proof.$field -or ($inline -and $index -cin @(2,3) -and -not $proof.settingsOwnerRetained) -or
                    ($index -eq 4 -and -not $proof.nativeOwnerRestored) -or
                    [string]::IsNullOrWhiteSpace([string]$check.evidence)) { throw 'PineDrama core pass has no observed destination or original native owner.' }
            if ($check.evidence -isnot [string] -or $check.evidence -cnotmatch
                    ('^[A-Za-z0-9_-]+-' + [regex]::Escape([string]$check.id) + '-' + $check.requestId + '\.json$')) {
                throw 'A PineDrama core observation is not bound to its route and request.'
            }
            $image = [IO.Path]::ChangeExtension($check.evidence, 'png')
            if (-not $observations.Add($check.evidence) -or -not $observations.Add($image) -or
                    $check.evidence -cnotin @($Evidence | ForEach-Object { $_.file }) -or
                    $image -cnotin @($Evidence | ForEach-Object { $_.file })) {
                throw 'A PineDrama core destination has no distinct observation and screenshot.'
            }
        }
    }
    $complete = $Checks.Count -eq $steps.Count -and @($Checks | Where-Object { $_.status -cne 'pass' }).Count -eq 0
    $failed = -not $SameProcess -or $Crashes -gt 0 -or @($Checks | Where-Object status -CEQ 'fail').Count -gt 0
    $result = if ($failed) { 'FAIL' } elseif ($complete -and $Problems.Count -eq 0) { 'PASS' } else { 'INCONCLUSIVE' }
    return [pscustomobject]@{ kind = 'hushfeed-package-core-walk'; schema = 1; profile = $Plan.profile
        app = $Plan.inspection.build.apk; build = $Plan.inspection.build; definitionSha256 = Get-AuditHash $Plan.definition
        status = 'INCOMPLETE'; coreResult = $result; full = $false; eligibleForPublication = $false
        checks = @($Checks); installedBefore = $InstalledBefore; installedAfter = $InstalledAfter
        sameProcess = $SameProcess; crashes = $Crashes; problems = @($Problems); evidence = @($Evidence)
        missingCoverage = @($Plan.definition.missingCoverage); coverage = @($Plan.definition.coverage) }
}

function Get-PineDramaCoreResultProblem {
    param($Plan, $Result, [Parameter(Mandatory = $true)][string]$Directory,
        [string]$GitRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
    try {
        $Directory = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Directory)
        $expected = New-PineDramaCoreResult -Plan $Plan -Checks @($Result.checks) -InstalledBefore $Result.installedBefore `
            -InstalledAfter $Result.installedAfter -SameProcess $Result.sameProcess -Crashes $Result.crashes `
            -Problems @($Result.problems) -Evidence @($Result.evidence) -GitRoot $GitRoot
        foreach ($property in $expected.PSObject.Properties) {
            if ((Get-AuditHash $property.Value) -cne (Get-AuditHash $Result.($property.Name))) { return 'PineDrama core result claims differ from its bound observations.' }
        }
        $files = @($Result.evidence | ForEach-Object { $_.file })
        if (@($files | Sort-Object -Unique).Count -ne $files.Count -or
                (Get-AuditHash @(Get-AuditEvidenceHashes $files $Directory)) -cne (Get-AuditHash @($Result.evidence))) { return 'PineDrama core evidence is missing, repeated or changed.' }
        foreach ($check in @($Result.checks | Where-Object status -CEQ 'pass')) {
            if ($check.evidence -cnotin $files) { return 'A PineDrama core pass has no retained native observation.' }
            $observation = Get-Content -LiteralPath (Join-Path $Directory $check.evidence) -Raw | ConvertFrom-Json
            if ($observation.step -isnot [string] -or $observation.requestId -isnot [string] -or
                    $observation.step -cne $check.id -or $observation.requestId -cne $check.requestId -or
                    (Get-AuditHash $observation.proof) -cne (Get-AuditHash $check.proof)) { return 'A PineDrama core observation changed or belongs to another request.' }
            $shot = [IO.File]::ReadAllBytes((Join-Path $Directory ([IO.Path]::ChangeExtension([string]$check.evidence, 'png'))))
            if ($shot.Length -lt 24 -or (($shot[0..7] -join ',') -cne '137,80,78,71,13,10,26,10')) { return 'A PineDrama core screenshot is not a PNG.' }
        }
        return $null
    } catch { return 'Malformed or incomplete PineDrama core result.' }
}
