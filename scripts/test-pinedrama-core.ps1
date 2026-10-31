[CmdletBinding()]
param([string]$Root, [string]$FixtureApk, [string]$Aapt2)
$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $Root 'tools/verification-probe/pinedrama-core.ps1')
. (Join-Path $Root 'scripts/patch-target.ps1')
. (Join-Path $Root 'scripts/fixtures/walk-native-fixture.ps1')
$script:assertions = 0
function Assert-PineCore([bool]$Condition, [string]$Message) { $script:assertions++; if (-not $Condition) { throw $Message } }
function Copy-PineValue($Value) { return $Value | ConvertTo-Json -Depth 40 | ConvertFrom-Json }
function Assert-PineThrows([scriptblock]$Action, [string]$Message) {
    $rejected = $false
    try { & $Action } catch { $rejected = $true }
    Assert-PineCore $rejected $Message
}
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
$directory = Join-Path $tempRoot ('hushfeed-pine-core-host-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($directory)
try {
    # Test the current working source without changing its Git-bound production guards.
    $sourceRoot = [IO.Path]::GetFullPath($Root)
    $snapshot = Join-Path $directory 'source'
    $paths = @(Invoke-RepoGit -Root $sourceRoot -Arguments @('ls-files', '--cached', '--others', '--exclude-standard', '-z')) -join [char]10
    if ($LASTEXITCODE) { throw 'Cannot list current PineDrama contract source.' }
    Invoke-RepoGit -Root $directory -Arguments @('clone', '--quiet', '--shared', '--no-checkout', '--no-hardlinks', '--', $sourceRoot, $snapshot) | Out-Null
    if ($LASTEXITCODE) { throw 'Cannot create the isolated PineDrama source snapshot.' }
    Invoke-RepoGit -Root $snapshot -Arguments @('read-tree', 'HEAD') | Out-Null
    if ($LASTEXITCODE) { throw 'Cannot preserve source file modes in the PineDrama snapshot.' }
    $exactCopy = $true
    foreach ($relative in @($paths.Split([char]0) | Where-Object { $_ } | Sort-Object -Unique)) {
        $from = Join-Path $sourceRoot $relative
        if (-not (Test-Path -LiteralPath $from -PathType Leaf)) { continue }
        $to = Join-Path $snapshot $relative
        [void][IO.Directory]::CreateDirectory((Split-Path -Parent $to))
        [IO.File]::Copy($from, $to)
        if ((Get-FileHash -LiteralPath $from -Algorithm SHA256).Hash -cne
                (Get-FileHash -LiteralPath $to -Algorithm SHA256).Hash) { $exactCopy = $false }
    }
    Assert-PineCore $exactCopy 'The isolated source snapshot changed current file bytes.'
    Invoke-RepoGit -Root $snapshot -Arguments @('-c', 'core.autocrlf=false', 'add', '--all', '--', '.') | Out-Null
    if ($LASTEXITCODE) { throw 'Cannot record current PineDrama source files in the isolated snapshot.' }
    $snapshotHead = [string](Invoke-RepoGit -Root $snapshot -Arguments @('rev-parse', 'HEAD'))
    if ($LASTEXITCODE) { throw 'Cannot identify the original PineDrama snapshot commit.' }
    Invoke-RepoGit -Root $snapshot -Arguments @('diff', '--cached', '--quiet', '--exit-code') | Out-Null
    $changes = $LASTEXITCODE
    if ($changes -eq 1) {
        Invoke-RepoGit -Root $snapshot -Arguments @('-c', 'user.name=SysAdminDoc', '-c', 'user.email=matt_parker@outlook.com',
            'commit', '--quiet', '-m', 'test: snapshot current PineDrama contract source') | Out-Null
        if ($LASTEXITCODE) { throw 'Cannot commit the isolated PineDrama source snapshot.' }
    } elseif ($changes -ne 0) { throw 'Cannot inspect the isolated PineDrama snapshot changes.' }
    Assert-PineCore ($changes -eq 1 -or [string](Invoke-RepoGit -Root $snapshot -Arguments @('rev-parse', 'HEAD')) -ceq $snapshotHead) 'An unchanged snapshot replaced its original commit.'
    $Root = $snapshot
    Assert-PineCore (-not (Get-AuditWorkingInputProblem $Root)) 'The isolated PineDrama source snapshot is dirty.'
    # Synthetic native/build facts only. These do not constitute APK production or device proof.
    $fixture = New-WalkNativeFixture $directory
    $definition = Get-PineDramaCoreDefinition -GitRoot $Root
    $target = @(Get-DeclaredPatchTargets -PatchList (Get-Content -LiteralPath (Join-Path $Root 'patches-list.json') -Raw | ConvertFrom-Json) |
        Where-Object PackageName -CEQ 'com.ss.android.ttmd.video')[0]
    $build = $fixture.Build
    $build.sourceApk = Copy-PineValue $definition.source
    $build.sourceApk.PSObject.Properties.Remove('bytes')
    $build.sourceApkSha256 = $build.sourceApk.sha256
    $build.apk.package = $build.sourceApk.package
    $build.patches = @($target.PatchNames)
    $build.source.commit = [string](Invoke-RepoGit -Root $Root -Arguments @('rev-parse','HEAD'))
    $build | Add-Member -NotePropertyName executionInputs -NotePropertyValue (Get-AuditExecutableInputs $Root)
    $inspection = [pscustomobject]@{ kind = 'hushfeed-capability-inspection'; schema = 1; profile = 'pinedrama-47.5.3-capabilities-v1'
        full = $false; eligibleForPublication = $false; build = $build; expectedPatches = @($target.PatchNames); expectedOptions = @()
        catalogCommit = $build.source.commit; missingCoverage = @((Get-VerificationProbeProfile 'pinedrama-47.5.3-capabilities-v1').MissingCoverage) }
    $plan = [pscustomobject]@{ kind = 'hushfeed-pinedrama-core-plan'; schema = 1; profile = $definition.profile
        full = $false; eligibleForPublication = $false; inspection = $inspection; definition = $definition; sourceBytes = $definition.source.bytes }
    Assert-PineCore (-not (Get-PineDramaCorePlanProblem $plan -GitRoot $Root)) 'Exact source/subset core plan rejected.'
    Assert-PineCore (-not (Get-PineDramaCoreCoverageProblem $definition $target.PatchNames)) 'Coverage map does not cover the exact 98.'
    Assert-PineCore ($build.patches.Count -eq 98) 'PineDrama declared subset count changed.'
    $legacyPlan = Copy-PineValue $plan
    $legacyPlan.profile = 'pinedrama-47.5.3-core-walk-v1'
    $legacyPlan.definition = Get-PineDramaCoreDefinition -GitRoot $Root -Profile $legacyPlan.profile
    Assert-PineCore (-not (Get-PineDramaCorePlanProblem $legacyPlan -GitRoot $Root)) 'Historical v1 core plan rejected.'
    Assert-PineCore ($legacyPlan.definition.coreSteps[2] -ceq 'comments-page' -and
        $legacyPlan.definition.coreSteps[3] -ceq 'settings-return' -and $definition.coreSteps[2] -ceq 'comments-expanded' -and
        $definition.coreSteps[3] -ceq 'comments-collapsed') 'The inline profile rewrote historical route semantics.'
    foreach ($mutation in @(
        { param($p) $p.profile = 'tiktok-whole-app-v1' },
        { param($p) $p.profile = 'pinedrama-47.5.3-core-walk-v1' },
        { param($p) $p.full = $true },
        { param($p) $p.schema = '1' },
        { param($p) $p.eligibleForPublication = $true },
        { param($p) $p.inspection.build.apk.package = 'com.zhiliaoapp.musically'; $p.inspection.build.sourceApk.package = $p.inspection.build.apk.package },
        { param($p) $p.inspection.build.apk.versionName = '47.4.3'; $p.inspection.build.sourceApk.versionName = '47.4.3' },
        { param($p) $p.inspection.build.sourceApk.versionCode = '2024704030' },
        { param($p) $p.inspection.build.sourceApk.sha256 = 'e' * 64; $p.inspection.build.sourceApkSha256 = 'e' * 64 },
        { param($p) $p.sourceBytes = 1 },
        { param($p) $p.inspection.build.patches = @($p.inspection.build.patches | Where-Object { $_ -cne 'Settings' }) },
        { param($p) $p.inspection.build.patches = @($p.inspection.build.patches | Where-Object { $_ -cne 'Allow photo comments' }) },
        { param($p) $p.inspection.build.patches += 'Mute feed videos' },
        { param($p) $p.inspection.build.options = @('Settings|x=true') },
        { param($p) $p.inspection.build.executionInputs.sha256 = 'e' * 64 },
        { param($p) $p.inspection.build.source.commit = 'e' * 40 },
        { param($p) $p.definition.missingCoverage = @() },
        { param($p) $p.definition.anchors.main = 'global-guessed-main' },
        { param($p) $p.definition.coverage[0].status = 'qualified' }
    )) {
        $bad = Copy-PineValue $plan; & $mutation $bad
        Assert-PineCore ([bool](Get-PineDramaCorePlanProblem $bad -GitRoot $Root)) 'Wrong identity, source, coverage or subset accepted.'
    }
    $badCoverage = Copy-PineValue $definition; $badCoverage.coverage[0].patches += 'Settings'
    Assert-PineCore ([bool](Get-PineDramaCoreCoverageProblem $badCoverage $target.PatchNames)) 'Duplicate coverage accepted.'
    $badCoverage = Copy-PineValue $definition; $badCoverage.coverage[0].patches = @('Settings')
    Assert-PineCore ([bool](Get-PineDramaCoreCoverageProblem $badCoverage $target.PatchNames)) 'Omitted selected runtime patches accepted.'

    $legacyResponse = 'pine-core-v1 requestAccepted=true targetMatches=true installedIdentityMatches=true foregroundOwned=true nativeMainForeground=true settingsHomeForeground=false commentsForeground=false commentsRowPresent=false nativeOwnerRestored=false actionAccepted=true'
    $legacyProof = ConvertFrom-PineDramaCoreResponse @($legacyResponse) -Profile $legacyPlan.profile
    Assert-PineCore ($legacyProof.nativeMainForeground -and @($legacyProof.PSObject.Properties).Count -eq 10) 'Historical v1 ten-boolean response changed.'
    $response = 'pine-core-v2 requestAccepted=true targetMatches=true installedIdentityMatches=true foregroundOwned=true nativeMainForeground=true settingsHomeForeground=false commentsExpanded=false commentsCollapsed=false commentsRowPresent=false settingsOwnerRetained=false nativeOwnerRestored=false actionAccepted=true'
    Assert-PineCore ((ConvertFrom-PineDramaCoreResponse @($response)).nativeMainForeground) 'Exact native main observation rejected.'
    Assert-PineThrows { ConvertFrom-PineDramaCoreResponse @($response) -Profile $legacyPlan.profile } 'Inline proof qualified a legacy route.'
    foreach ($lines in @(
        @($legacyResponse), @($response,$legacyResponse), @($response,$response), @($response + ' privateText=anything'), @($response + ' actionAccepted=true'),
        @($response.Replace('installedIdentityMatches=true','installedIdentityMatches=false')),
        @($response.Replace('actionAccepted=true','actionAccepted=false')),
        @($response.Replace('settingsHomeForeground=false','settingsHomeForeground=true')),
        @($response.Replace('foregroundOwned=true','foregroundOwned=false')),
        @($response.Replace('nativeOwnerRestored=false','nativeOwnerRestored=unknown')),
        @($response.Replace('commentsRowPresent=false','commentsRowPresent=true')),
        @($response.Replace('commentsExpanded=false','commentsExpanded=true')),
        @($response.Replace('commentsCollapsed=false','commentsCollapsed=true')),
        @($response.Replace('settingsOwnerRetained=false','settingsOwnerRetained=true')),
        @($response.Replace('nativeOwnerRestored=false',''))
    )) { Assert-PineThrows { ConvertFrom-PineDramaCoreResponse $lines | Out-Null } 'Malformed/conflicting native response accepted.' }

    $script:deviceCalls = [Collections.Generic.List[object]]::new()
    $script:marker = ''
    $script:request = ''
    $script:wrongRequest = $false
    function Assert-DeviceLease { param($Lease,[string]$Serial) }
    function Invoke-PineFixtureDevice {
        $arguments = @($args)
        $script:deviceCalls.Add($arguments)
        $global:LASTEXITCODE = 0
        if ($arguments[2] -ceq 'shell' -and $arguments[3] -ceq 'log') { $script:marker = $arguments[-1] }
        if ($arguments[2] -ceq 'shell' -and $arguments[3] -ceq 'am') { $script:request = $arguments[[Array]::IndexOf($arguments, 'request') + 1] }
        if ($arguments[2] -ceq 'logcat') {
            $script:marker
            $token = if ($script:wrongRequest) { 'e' * 32 } else { $script:request }
            'pine-core-response-' + $token + ' ' + $response
        }
    }
    foreach ($action in @('dump','set','tap','follow','send','publish','OPEN-SETTINGS','open-page','open-comments','back-home')) {
        Assert-PineThrows { Invoke-PineDramaCoreAction -Serial fixture-serial -Action $action -Plan $plan -Lease $null -GitRoot $Root -Adb Invoke-PineFixtureDevice } 'Unsupported UI/global action accepted.'
    }
    Assert-PineThrows { Invoke-PineDramaCoreAction -Serial fixture-serial -Action expand-comments -Plan $legacyPlan -Lease $null -GitRoot $Root -Adb Invoke-PineFixtureDevice } 'Inline action entered a historical v1 route.'
    $bad = Copy-PineValue $plan; $bad.inspection.build.sourceApk.sha256 = 'e' * 64
    Assert-PineThrows { Invoke-PineDramaCoreAction -Serial fixture-serial -Action open-settings -Plan $bad -Lease $null -GitRoot $Root -Adb Invoke-PineFixtureDevice } 'Wrong source reached a device action.'
    Assert-PineCore ($script:deviceCalls.Count -eq 0) 'Rejected requests ran device commands.'
    $dirtyPath = Join-Path $Root 'tools/verification-probe/pinedrama-core.ps1'
    $cleanBytes = [IO.File]::ReadAllBytes($dirtyPath)
    try {
        [IO.File]::AppendAllText($dirtyPath, ([char]10 + '# Synthetic dirty-input control.'))
        Assert-PineCore ([bool](Get-AuditWorkingInputProblem $Root)) 'The snapshot concealed an uncommitted executable input.'
        Assert-PineThrows { Invoke-PineDramaCoreAction -Serial fixture-serial -Action snapshot -Plan $plan -Lease $null -GitRoot $Root -Adb Invoke-PineFixtureDevice -WaitMs 1 } 'An uncommitted executable input reached a core action.'
        Assert-PineCore ($script:deviceCalls.Count -eq 0) 'Dirty-source rejection ran device commands.'
    } finally { [IO.File]::WriteAllBytes($dirtyPath, $cleanBytes) }
    $requestId = $null
    $answer = Invoke-PineDramaCoreAction -Serial fixture-serial -Action snapshot -Plan $plan -Lease $null -GitRoot $Root -Adb Invoke-PineFixtureDevice -WaitMs 1 -RequestId ([ref]$requestId)
    Assert-PineCore ($answer.nativeMainForeground -and $script:deviceCalls.Count -eq 3) 'Marked PineDrama observation failed.'
    Assert-PineCore ($requestId -ceq $script:request -and $requestId -cmatch '^[a-f0-9]{32}$' -and
        @($answer.PSObject.Properties).Count -eq 12) 'The request token is unbound or changed the v2 twelve-boolean proof.'
    $broadcast = $script:deviceCalls[1] -join ' '
    Assert-PineCore ($broadcast.Contains('-a app.hushfeed.verification.PINE_CORE -p com.ss.android.ttmd.video') -and
        $broadcast.Contains('-e profile ' + $plan.profile) -and $broadcast.Contains('-e apkSha256 ' + $build.apk.sha256)) 'Core request lost package or APK identity.'
    $script:wrongRequest = $true
    Assert-PineThrows { Invoke-PineDramaCoreAction -Serial fixture-serial -Action snapshot -Plan $plan -Lease $null -GitRoot $Root -Adb Invoke-PineFixtureDevice -WaitMs 1 } 'A late answer from another request was accepted.'
    Assert-PineThrows { Invoke-PineDramaCoreAction -Serial fixture-serial -Action snapshot -Plan $plan -Lease $null -GitRoot $Root -Adb Invoke-PineFixtureDevice -WaitMs 1 -RequestId ([ref]$requestId) } 'A late answer was bound to a saved observation.'
    Assert-PineCore ($null -eq $requestId) 'A rejected request retained its preceding successful token.'
    $script:wrongRequest = $false

    & {
        $walk = [Management.Automation.Language.Parser]::ParseFile((Join-Path $Root 'scripts/walk-pinedrama.ps1'), [ref]$null, [ref]$null)
        foreach ($name in @('Save-PineEvidence', 'Wait-PineRoute')) {
            $function = @($walk.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name }, $true))[0]
            . ([scriptblock]::Create($function.Extent.Text))
        }
        function Update-DeviceLease { param($Lease, $LeaseMinutes) }
        function Test-PineProcess { }
        function Invoke-PineDramaCoreAction {
            param($Serial, $Action, $Plan, $Lease, $GitRoot, $Adb, [ref]$RequestId)
            $RequestId.Value = 'b' * 32
            return ConvertFrom-PineDramaCoreResponse @($response)
        }
        function Invoke-PineAdb {
            if ($args[0] -ceq 'pull') { [IO.File]::WriteAllBytes($args[2], [Convert]::FromBase64String('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=')) }
        }
        $run = 'a' * 32; $work = $directory
        $checks = [Collections.Generic.List[object]]::new(); $evidence = [Collections.Generic.List[object]]::new()
        Wait-PineRoute launch nativeMainForeground
        $saved = Get-Content -LiteralPath (Join-Path $work $checks[0].evidence) -Raw | ConvertFrom-Json
        Assert-PineCore ($checks[0].requestId -ceq ('b' * 32) -and $saved.requestId -ceq $checks[0].requestId -and
            $saved.step -ceq 'launch' -and @($saved.proof.PSObject.Properties).Count -eq 12 -and
            $evidence[1].file -ceq [IO.Path]::ChangeExtension($checks[0].evidence, 'png')) 'The driver did not retain the actual request with its observation and screenshot.'
    }

    $checks = @(); $evidence = @()
    for ($index = 0; $index -lt $definition.coreSteps.Count; $index++) {
        $proof = ConvertFrom-PineDramaCoreResponse @($response)
        $proof.nativeMainForeground = $index -eq 0 -or $index -eq 4
        $proof.settingsHomeForeground = $index -cin @(1,2,3)
        $proof.commentsExpanded = $index -eq 2
        $proof.commentsCollapsed = $index -cin @(1,3)
        $proof.settingsOwnerRetained = $index -cin @(2,3)
        $proof.commentsRowPresent = $proof.settingsHomeForeground
        $proof.nativeOwnerRestored = $index -eq 4
        $requestId = [guid]::NewGuid().ToString('N')
        $file = 'fixture-' + $definition.coreSteps[$index] + '-' + $requestId + '.json'
        $path = Join-Path $directory $file
        [pscustomobject]@{ step = $definition.coreSteps[$index]; requestId = $requestId; proof = $proof } |
            ConvertTo-Json | Set-Content -LiteralPath $path -Encoding UTF8
        $checks += [pscustomobject]@{ id = $definition.coreSteps[$index]; status = 'pass'; observed = $true; requestId = $requestId; proof = $proof; evidence = $file }
        $evidence += [pscustomobject]@{ file = $file; sha256 = (Get-FileHash $path -Algorithm SHA256).Hash.ToLowerInvariant() }
        $image = [IO.Path]::ChangeExtension($file, 'png')
        $imagePath = Join-Path $directory $image
        [IO.File]::WriteAllBytes($imagePath, [Convert]::FromBase64String('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII='))
        $evidence += [pscustomobject]@{ file = $image; sha256 = (Get-FileHash $imagePath -Algorithm SHA256).Hash.ToLowerInvariant() }
    }
    $resultArgs = @{ Plan = $plan; Checks = $checks; InstalledBefore = $build.apk; InstalledAfter = $build.apk
        SameProcess = $true; Crashes = 0; Evidence = $evidence; GitRoot = $Root }
    $result = New-PineDramaCoreResult @resultArgs
    Assert-PineCore ($result.coreResult -ceq 'PASS' -and $result.status -ceq 'INCOMPLETE' -and -not $result.full -and -not $result.eligibleForPublication) 'Core PASS became whole-app qualification.'
    Assert-PineCore (-not (Get-PineDramaCoreResultProblem $plan $result -Directory $directory -GitRoot $Root)) 'Bound package-specific core result rejected.'
    $legacyChecks = @(); $legacyEvidence = @()
    for ($index = 0; $index -lt $legacyPlan.definition.coreSteps.Count; $index++) {
        $proof = ConvertFrom-PineDramaCoreResponse @($legacyResponse) -Profile $legacyPlan.profile
        $proof.nativeMainForeground = $index -cin @(0,4)
        $proof.settingsHomeForeground = $index -cin @(1,3)
        $proof.commentsForeground = $index -eq 2
        $proof.commentsRowPresent = $proof.settingsHomeForeground
        $proof.nativeOwnerRestored = $index -eq 4
        $request = [guid]::NewGuid().ToString('N'); $step = $legacyPlan.definition.coreSteps[$index]
        $file = 'legacy-' + $step + '-' + $request + '.json'; $path = Join-Path $directory $file
        [pscustomobject]@{ step = $step; requestId = $request; proof = $proof } | ConvertTo-Json | Set-Content -LiteralPath $path -Encoding UTF8
        $image = [IO.Path]::ChangeExtension($file, 'png')
        [IO.File]::Copy((Join-Path $directory ([IO.Path]::ChangeExtension($checks[$index].evidence, 'png'))), (Join-Path $directory $image))
        $legacyChecks += [pscustomobject]@{ id = $step; status = 'pass'; observed = $true; requestId = $request; proof = $proof; evidence = $file }
        foreach ($name in @($file,$image)) {
            $legacyEvidence += [pscustomobject]@{ file = $name; sha256 = (Get-FileHash (Join-Path $directory $name) -Algorithm SHA256).Hash.ToLowerInvariant() }
        }
    }
    $legacyArgs = @{} + $resultArgs
    $legacyArgs.Plan = $legacyPlan; $legacyArgs.Checks = $legacyChecks; $legacyArgs.Evidence = $legacyEvidence
    $legacyResult = New-PineDramaCoreResult @legacyArgs
    Assert-PineCore (-not (Get-PineDramaCoreResultProblem $legacyPlan $legacyResult -Directory $directory -GitRoot $Root)) 'Saved historical v1 evidence no longer validates.'
    Assert-PineCore ([bool](Get-PineDramaCoreResultProblem $plan $legacyResult -Directory $directory -GitRoot $Root)) 'Historical screenshots qualified the inline route.'
    $returnPath = Join-Path $directory $checks[3].evidence
    $returnImage = [IO.Path]::ChangeExtension($returnPath, 'png')
    $returnBytes = [IO.File]::ReadAllBytes($returnPath); $imageBytes = [IO.File]::ReadAllBytes($returnImage)
    $returnText = Get-Content -LiteralPath $returnPath -Raw
    try {
        Remove-Item -LiteralPath $returnPath, $returnImage
        $bad = Copy-PineValue $result
        $bad.checks[3].evidence = $bad.checks[1].evidence
        $bad.evidence = @($bad.evidence | Where-Object { $_.file -cnotin @($checks[3].evidence, [IO.Path]::GetFileName($returnImage)) })
        Assert-PineCore ([bool](Get-PineDramaCoreResultProblem $plan $bad -Directory $directory -GitRoot $Root)) 'A return passed using home evidence after its own observation and screenshot were removed.'
    } finally { [IO.File]::WriteAllBytes($returnPath, $returnBytes); [IO.File]::WriteAllBytes($returnImage, $imageBytes) }
    foreach ($mutation in @(
        { param($r) $old = $r.checks[3].evidence; $r.checks[3].evidence = $r.checks[1].evidence
            $r.evidence = @($r.evidence | Where-Object { $_.file -cnotin @($old, [IO.Path]::ChangeExtension($old, 'png')) }) },
        { param($r) $r.checks[3].evidence = $r.checks[1].evidence.ToUpperInvariant() },
        { param($r) $r.checks[3].requestId = $r.checks[1].requestId },
        { param($r) $r.checks[3].requestId = 'A' * 32 },
        { param($r) $r.checks[3].PSObject.Properties.Remove('requestId') },
        { param($r) $r.evidence += [pscustomobject]@{ file = $r.evidence[1].file.ToUpperInvariant().Replace('.PNG', '.png'); sha256 = $r.evidence[1].sha256 } }
    )) {
        $bad = Copy-PineValue $result; & $mutation $bad
        Assert-PineCore ([bool](Get-PineDramaCoreResultProblem $plan $bad -Directory $directory -GitRoot $Root)) 'Reused route evidence, path alias or copied request was accepted.'
    }
    try {
        foreach ($sameStep in @($false, $true)) {
            $copied = Get-Content -LiteralPath (Join-Path $directory $checks[1].evidence) -Raw | ConvertFrom-Json
            if ($sameStep) { $copied.step = $checks[3].id }
            $copied | ConvertTo-Json | Set-Content -LiteralPath $returnPath -Encoding UTF8
            $bad = Copy-PineValue $result
            @($bad.evidence | Where-Object file -CEQ $checks[3].evidence)[0].sha256 = (Get-FileHash -LiteralPath $returnPath -Algorithm SHA256).Hash.ToLowerInvariant()
            Assert-PineCore ([bool](Get-PineDramaCoreResultProblem $plan $bad -Directory $directory -GitRoot $Root)) 'A copied request observation with an updated file hash was accepted.'
        }
        foreach ($field in @('step', 'requestId')) {
            foreach ($singleValue in @($false, $true)) {
                $malformed = $returnText | ConvertFrom-Json
                $savedValue = $malformed.$field
                $malformed.$field = [object[]]@()
                if ($singleValue) { $malformed.$field = [object[]]@($savedValue) }
                $malformed | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $returnPath -Encoding UTF8
                $bad = Copy-PineValue $result
                @($bad.evidence | Where-Object file -CEQ $checks[3].evidence)[0].sha256 = (Get-FileHash -LiteralPath $returnPath -Algorithm SHA256).Hash.ToLowerInvariant()
                Assert-PineCore ([bool](Get-PineDramaCoreResultProblem $plan $bad -Directory $directory -GitRoot $Root)) 'An array-shaped saved route or request field was accepted.'
            }
        }
    } finally { [IO.File]::WriteAllBytes($returnPath, $returnBytes) }
    foreach ($mutation in @(
        { param($r) $r.status = 'PASS' }, { param($r) $r.full = $true }, { param($r) $r.eligibleForPublication = $true },
        { param($r) $r.app.package = 'com.zhiliaoapp.musically' }, { param($r) $r.build.sourceApk.sha256 = 'e' * 64 },
        { param($r) $r.missingCoverage = @() }, { param($r) $r.coverage[0].status = 'qualified' },
        { param($r) $r.checks[4].proof.nativeOwnerRestored = $false }, { param($r) $r.checks[2].proof.commentsExpanded = $false },
        { param($r) $r.checks[2].proof.commentsCollapsed = $true }, { param($r) $r.checks[3].proof.commentsCollapsed = $false },
        { param($r) $r.checks[2].proof.settingsOwnerRetained = $false }, { param($r) $r.checks[3].proof.settingsOwnerRetained = $false },
        { param($r) $r.checks[2].id = 'comments-page' }, { param($r) $r.checks[3].id = 'settings-return' },
        { param($r) $r.checks[1].id = 'launch' }, { param($r) $r.evidence[0].sha256 = 'e' * 64 },
        { param($r) $r.installedAfter.sha256 = 'e' * 64 }, { param($r) $r.coreResult = 'QUALIFIED' },
        { param($r) $r.evidence = @($r.evidence | Where-Object { $_.file -cnotmatch '\.png$' }) }
    )) {
        $bad = Copy-PineValue $result; & $mutation $bad
        Assert-PineCore ([bool](Get-PineDramaCoreResultProblem $plan $bad -Directory $directory -GitRoot $Root)) 'Mutated receipt, missing route/owner or changed evidence accepted.'
    }
    $partial = @{} + $resultArgs; $partial.Checks = @($checks[0])
    Assert-PineCore ((New-PineDramaCoreResult @partial).coreResult -ceq 'INCONCLUSIVE') 'Partial core walk passed.'
    $partial = @{} + $resultArgs; $partial.Crashes = 1
    Assert-PineCore ((New-PineDramaCoreResult @partial).coreResult -ceq 'FAIL') 'A native crash passed.'
    $partial = @{} + $resultArgs; $partial.SameProcess = $false
    Assert-PineCore ((New-PineDramaCoreResult @partial).coreResult -ceq 'FAIL') 'A replaced process passed.'
    $partial = @{} + $resultArgs; $partial.Crashes = -1
    Assert-PineThrows { New-PineDramaCoreResult @partial | Out-Null } 'A negative crash count was accepted.'
    [IO.File]::AppendAllText((Join-Path $directory $evidence[0].file),'changed')
    Assert-PineCore ([bool](Get-PineDramaCoreResultProblem $plan $result -Directory $directory -GitRoot $Root)) 'Changed observation bytes passed.'

    $manifest = Join-Path $directory 'AndroidManifest.xml'
    Write-VerificationProbeManifest -Template (Join-Path $Root 'tools/verification-probe/AndroidManifest.xml') -Destination $manifest -Profile $plan.profile
    [xml]$xml = Get-Content -LiteralPath $manifest -Raw
    $android = 'http://schemas.android.com/apk/res/android'
    $instruments = @($xml.manifest.SelectNodes('instrumentation'))
    Assert-PineCore ($instruments.Count -eq 1 -and $instruments[0].GetAttribute('name',$android) -ceq 'app.hushfeed.verification.PineDramaCoreProbe' -and
        $instruments[0].GetAttribute('targetPackage',$android) -ceq 'com.ss.android.ttmd.video') 'Core manifest exposed the global instrumentation.'
    Assert-PineCore (@($xml.manifest.SelectNodes('uses-permission | application/service')).Count -eq 0) 'Core manifest exposed unreviewed services.'

    if ($FixtureApk) {
        Assert-PineCore ((Get-FileHash $FixtureApk -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $definition.source.sha256 -and
            (Get-Item $FixtureApk).Length -eq $definition.source.bytes) 'Reviewed merged fixture bytes changed.'
        if (-not $Aapt2) { throw '-FixtureApk requires -Aapt2 for actual manifest anchor verification.' }
        Assert-PineCore (-not (Get-PineDramaStandaloneInputProblem -Apk $FixtureApk -Aapt2 $Aapt2)) 'Actual merged fixture still requires splits.'
        $facts = Get-ApkManifestFacts -Apk $FixtureApk -Aapt2 $Aapt2
        foreach ($field in @('package','versionName','versionCode')) { Assert-PineCore ([string]$facts.$field -ceq [string]$definition.source.$field) 'Actual fixture identity changed.' }
        $manifestText = @(& $Aapt2 dump xmltree --file AndroidManifest.xml $FixtureApk) -join [char]10
        if ($LASTEXITCODE) { throw 'Actual fixture manifest dump failed.' }
        Assert-PineCore ($manifestText.Contains($definition.anchors.settings) -and $manifestText -match
            ('(?s)E: activity-alias[^\n]*\n(?:(?!E: activity).)*name[^\n]*"' + [regex]::Escape($definition.anchors.launcher) +
            '"(?:(?!E: activity).)*targetActivity[^\n]*"' + [regex]::Escape($definition.anchors.main) + '"')) 'Actual PineDrama launcher/target/settings anchors changed.'
        Write-Output 'Actual merged fixture hash, standalone manifest and native entry anchors verified.'
    }
    Write-Output "PineDrama core host contracts passed ($script:assertions assertions). No device actions."
} finally {
    $absolute = [IO.Path]::GetFullPath($directory)
    if (-not $absolute.StartsWith($tempRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($absolute) -cnotmatch '^hushfeed-pine-core-host-[a-f0-9]{32}$') { throw 'Owned PineDrama host cleanup guard failed.' }
    Remove-Item -LiteralPath $absolute -Recurse -Force
}
