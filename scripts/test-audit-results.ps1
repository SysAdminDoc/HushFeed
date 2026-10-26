<# Offline contracts for audit-results.ps1: a release needs a device audit that still matches the catalog. #>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'audit-results.ps1')
. (Join-Path $PSScriptRoot 'release-receipt.ps1')

function Assert-AuditContract([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

$repo = Split-Path -Parent $PSScriptRoot
$head = $null
$parent = $null
$package = 'com.zhiliaoapp.musically'
$work = Join-Path ([IO.Path]::GetTempPath()) ('hushfeed-audit-contract-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory((Join-Path $work 'audit/results'))
$catalogPath = Join-Path $work 'audit/catalog.json'
$resultsPath = Join-Path $work 'audit/results/tiktok-47.5.3.json'
try {
    function Commit-Inputs([string[]]$Paths, [string]$Message = 'Test fixture input') {
        Invoke-RepoGit -Root $work -Arguments (@('add', '--') + $Paths) | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Cannot stage audit fixture inputs.' }
        Invoke-RepoGit -Root $work -Arguments @('-c', 'user.name=SysAdminDoc', '-c', 'user.email=matt_parker@outlook.com', 'commit', '-qm', $Message) | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Cannot commit audit fixture inputs.' }
        return [string](Invoke-RepoGit -Root $work -Arguments @('rev-parse', 'HEAD'))
    }
    Invoke-RepoGit -Root $work -Arguments @('init', '-q') | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot initialize audit fixture repository.' }
    $inputPaths = @('extensions/tiktok/src/main/java/Runtime.java', 'patches/src/main/kotlin/Patch.kt',
        'extensions/tiktok/src/main/l10n/en.txt', 'gradle/libs.versions.toml',
        ('patches/src/main/kotlin/Spaced ' + [char]0xe9 + '.kt'))
    foreach ($inputPath in $inputPaths + 'README.md') {
        $file = Join-Path $work $inputPath
        [void][IO.Directory]::CreateDirectory((Split-Path -Parent $file))
        [IO.File]::WriteAllText($file, 'original')
    }
    $parent = Commit-Inputs ($inputPaths + 'README.md')
    [IO.File]::WriteAllText((Join-Path $work 'README.md'), 'documentation descendant')
    $head = Commit-Inputs @('README.md')
    $recordedInputs = Get-AuditExecutableInputs $work $parent
    function New-Build([string]$Commit = $parent) {
        return [ordered]@{ status = 'verified'; nativeBuildSchema = 2; receiptSha256 = 'd' * 64
            source = [ordered]@{ commit = $Commit; clean = $true; startFingerprint = 'c' * 64; endFingerprint = 'c' * 64 }
            executionInputs = Get-AuditExecutableInputs $work $Commit
            apk = [ordered]@{ package = $package; versionName = '47.5.3'; versionCode = '1'; sha256 = 'a' * 64 }
            sourceApk = [ordered]@{ package = $package; versionName = '47.5.3'; versionCode = '1'; sha256 = 'f' * 64 }
            sourceApkSha256 = 'f' * 64; bundle = [ordered]@{ sha256 = 'b' * 64 }
            patches = @('Alpha','Beta'); options = @(); patchReportSha256 = 'e' * 64 }
    }
    function New-Catalog {
        return @(
            [ordered]@{
                name = 'Alpha'; category = 'Feed'; kind = 'runtime'; settings = @(); notes = 'Alpha notes.'
                checks = @([ordered]@{ id = 'bound'; what = 'Alpha binds.'; steps = @([ordered]@{ probe = 'report'; expect = 'alpha' }); auto = $true })
            },
            [ordered]@{
                name = 'Beta'; category = 'Comments'; kind = 'runtime'
                settings = @([ordered]@{ key = 'beta'; default = $false; type = 'bool'; row = 'Beta' })
                checks = @(
                    [ordered]@{ id = 'bound'; what = 'Beta binds.'; steps = @([ordered]@{ probe = 'report'; expect = 'beta' }); auto = $true },
                    [ordered]@{ id = 'shot'; what = 'Review: Beta shows.'; steps = @([ordered]@{ shot = 'beta'; review = 'Beta is there.' }); auto = $false }
                )
            }
        )
    }
    function Write-Catalog($Entries) {
        [IO.File]::WriteAllText($catalogPath, (ConvertTo-Json -InputObject @($Entries) -Depth 10))
    }
    # The results a run against the catalog on disk leaves. -Change bends a top-level field and
    # -Skip leaves a check out, as a run made before that check existed would.
    function Write-Results([hashtable]$Change = @{}, [string[]]$Skip = @()) {
        $parsed = Get-Content -LiteralPath $catalogPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $patches = foreach ($entry in $parsed) {
            $checks = foreach ($check in @($entry.checks)) {
                if ("$($entry.name)/$($check.id)" -cin $Skip) { continue }
                [ordered]@{ id = $check.id; status = 'pass'; definitionHash = Get-AuditDefinitionHash $entry $check; build = New-Build; installedApkSha256 = 'a' * 64 }
            }
            [ordered]@{ name = $entry.name; checks = @($checks) }
        }
        $run = [ordered]@{
            app = [ordered]@{ package = $package; version = '47.5.3'; versionCode = '1' }
            apk = [ordered]@{ sha256 = ('a' * 64) }
            build = New-Build
            finishedUtc = '2026-10-23T10:01:58Z'
            summary = [ordered]@{ pass = 2; fail = 0; review = 1; inconclusive = 0; error = 0; skipped = 0 }
            patches = @($patches)
            qualification = [ordered]@{ state = 'current-bound' }
        }
        foreach ($key in $Change.Keys) { $run[$key] = $Change[$key] }
        [IO.File]::WriteAllText($resultsPath, ($run | ConvertTo-Json -Depth 10))
    }
    function Test-Here([string[]]$Versions = @('47.5.3')) {
        return Test-AuditResults -Root $work -PackageName $package -PackageVersions $Versions -Head $head -GitRoot $work
    }

    Write-Catalog (New-Catalog)
    Write-Results
    $good = Test-Here
    Assert-AuditContract $good.Valid "A run matching every catalog check was refused: $($good.Reason)"
    $fact = $good.Facts[0]
    Assert-AuditContract ($good.Facts.Count -eq 1 -and $fact.checks -eq 3 -and $fact.commitsSince -eq 1 -and
        $fact.sourceCommit -ceq $parent -and $fact.finished -ceq '2026-10-23T10:01:58Z' -and $fact.summary.review -eq 1 -and
        $fact.sha256 -ceq (Get-FileHash -Algorithm SHA256 -LiteralPath $resultsPath).Hash.ToLowerInvariant()) `
        "The audit facts don't describe the results file: $($fact | ConvertTo-Json -Depth 4 -Compress)"

    Assert-AuditContract ($fact.package -ceq $package -and $fact.nativeChecks -eq 3 -and $fact.installedApkSha256 -ceq ('a' * 64) -and
        (Get-AuditHash $fact.nativeBuild) -ceq (Get-AuditHash (New-Build))) 'Release facts discarded package, native build or installed APK identity.'

    # A recorded current-bound label cannot cover a different or unknown native selection.
    foreach ($nativeCase in @('missingIdentity','historicalBuild','wrongStock','missingReport','excludedPatch','wrongOption','missingInstalled','differentInstalled')) {
        Write-Results
        $nativeRun = Get-Content -LiteralPath $resultsPath -Raw | ConvertFrom-Json
        $nativeCheck = $nativeRun.patches[0].checks[0]
        switch ($nativeCase) {
            missingIdentity { $nativeRun.build.PSObject.Properties.Remove('nativeBuildSchema') }
            historicalBuild { $nativeCheck.build.status = 'historical' }
            wrongStock { $nativeCheck.build.sourceApk.sha256 = '0' * 64 }
            missingReport { $nativeCheck.build.patchReportSha256 = '' }
            excludedPatch { $nativeCheck.build.patches = @('Beta') }
            wrongOption { $nativeCheck.build.options = @('Unselected|value=true') }
            missingInstalled { $nativeCheck.PSObject.Properties.Remove('installedApkSha256') }
            differentInstalled { $nativeCheck.installedApkSha256 = '0' * 64 }
        }
        [IO.File]::WriteAllText($resultsPath, ($nativeRun | ConvertTo-Json -Depth 20))
        $beforeNative = [IO.File]::ReadAllText($resultsPath)
        Assert-AuditContract (-not (Test-Here).Valid) "Read-only qualification accepted invalid native build facts: $nativeCase"
        Assert-AuditContract ([IO.File]::ReadAllText($resultsPath) -ceq $beforeNative) 'Native identity validation rewrote saved verdicts.'
    }
    Write-Results

    # Patch notes aren't part of any check, so rewording them leaves the run current.
    $renoted = New-Catalog
    $renoted[0].notes = 'Alpha notes, reworded after the run.'
    Write-Catalog $renoted
    Assert-AuditContract (Test-Here).Valid 'Rewording a patch note made its run stale.'

    # A check edited after the run: its result is for a definition that's gone.
    $edited = New-Catalog
    $edited[1].checks[0].steps[0].expect = 'beta (?-i:[A-Z]{2})'
    Write-Catalog $edited
    $stale = Test-Here
    Assert-AuditContract (-not $stale.Valid -and $stale.Reason -like '*changed since the run: Beta/bound*-Only*') `
        "A check edited after the run passed: $($stale.Reason)"
    # So is a changed setting on the patch, which can change what every one of its checks sets up.
    $resettled = New-Catalog
    $resettled[1].settings[0].default = $true
    Write-Catalog $resettled
    $settled = Test-Here
    Assert-AuditContract (-not $settled.Valid -and $settled.Reason -like '*Beta/bound, Beta/shot*') `
        "A patch setting changed after the run left its checks current: $($settled.Reason)"

    # A check added after the run, and one the catalog dropped.
    Write-Catalog (New-Catalog)
    Write-Results -Skip @('Beta/shot')
    $added = Test-Here
    Assert-AuditContract (-not $added.Valid -and $added.Reason -like '*never run: Beta/shot*') "A check the run never had passed: $($added.Reason)"
    Write-Results
    $dropped = New-Catalog
    $dropped[1].checks = @($dropped[1].checks[0])
    Write-Catalog $dropped
    $removed = Test-Here
    Assert-AuditContract (-not $removed.Valid -and $removed.Reason -like '*no longer in the catalog: Beta/shot*') "A result for a dropped check passed: $($removed.Reason)"

    # A declared version nobody audited, a run on another build, one not bound to its evidence,
    # and one built from a commit outside the release's history.
    Write-Catalog (New-Catalog)
    Write-Results
    $unaudited = Test-Here @('47.5.3', '47.6.0')
    Assert-AuditContract (-not $unaudited.Valid -and $unaudited.Reason -like '*no audit/results/tiktok-47.6.0.json*never audited*') `
        "A declared version with no audit passed: $($unaudited.Reason)"
    Write-Results @{ app = [ordered]@{ package = $package; version = '47.4.3' } }
    $other = Test-Here
    Assert-AuditContract (-not $other.Valid -and $other.Reason -like '*47.4.3, not com.zhiliaoapp.musically 47.5.3*') "A run on another TikTok build passed: $($other.Reason)"
    Write-Results @{ qualification = [ordered]@{ state = 'historical-or-incomplete' } }
    $unbound = Test-Here
    Assert-AuditContract (-not $unbound.Valid -and $unbound.Reason -like '*historical-or-incomplete, not current-bound*') "A run not bound to its evidence passed: $($unbound.Reason)"
    Write-Results @{ build = [ordered]@{ source = [ordered]@{ commit = ('f' * 40) } } }
    $stray = Test-Here
    Assert-AuditContract (-not $stray.Valid -and $stray.Reason -like "*isn't in the history of $head*") "A build from outside the release's history passed: $($stray.Reason)"

    # What the receipt carries.
    Write-Results
    $facts = (Test-Here).Facts | ConvertTo-Json -Depth 4 | ConvertFrom-Json
    Assert-AuditContract ($null -eq (Test-ReceiptAuditFacts -Audit $facts -PackageVersions @('47.5.3'))) 'A receipt audit covering the declared version was refused.'
    Assert-AuditContract ($null -eq (Test-ReceiptAuditFacts -Audit $null -PackageVersions @('47.5.3'))) 'A receipt from before the audit block was refused.'
    Assert-AuditContract ((Test-ReceiptAuditFacts -Audit $facts -PackageVersions @('47.5.3', '47.6.0')) -like '*leaves out TikTok 47.6.0*') 'A receipt audit missing a declared version passed.'
    Assert-AuditContract ((Test-ReceiptAuditFacts -Audit $facts -PackageVersions @('47.6.0')) -like '*audit is of TikTok 47.5.3*') 'A receipt audit of an undeclared version passed.'
    Assert-AuditContract ((Test-ReceiptAuditFacts -Audit @($facts, $facts) -PackageVersions @('47.5.3')) -like '*two audits of TikTok 47.5.3*') 'A receipt with the same audit twice passed.'
    $unhashed = $facts | ConvertTo-Json -Depth 4 | ConvertFrom-Json
    $unhashed.sha256 = 'not a hash'
    Assert-AuditContract ((Test-ReceiptAuditFacts -Audit $unhashed -PackageVersions @('47.5.3')) -like '*no results hash*') 'A receipt audit with no results hash passed.'


    Write-Results
    $beforeDirty = [IO.File]::ReadAllText($resultsPath)
    $runtimePath = Join-Path $work $inputPaths[0]
    [IO.File]::WriteAllText($runtimePath, 'uncommitted runtime change')
    $dirty = Test-Here
    Assert-AuditContract (-not $dirty.Valid -and $dirty.Reason -like '*uncommitted or hidden executable inputs:*Runtime.java*') 'A tracked dirty runtime qualified current HEAD.'
    Assert-AuditContract ([IO.File]::ReadAllText($resultsPath) -ceq $beforeDirty) 'Dirty runtime invalidation rewrote historical statuses.'
    $historical = Test-AuditResults -Root $work -PackageName $package -PackageVersions @('47.5.3') -Head $parent -GitRoot $work
    Assert-AuditContract $historical.Valid "An explicit historical commit depended on current working dirt: $($historical.Reason)"
    [IO.File]::WriteAllText($runtimePath, 'original')
    $unknownPath = Join-Path $work 'untracked-generator.ps1'
    [IO.File]::WriteAllText($unknownPath, 'untracked executable input')
    $untracked = Test-Here
    Assert-AuditContract (-not $untracked.Valid -and $untracked.Reason -like '*untracked-generator.ps1*') 'An untracked unknown executable input qualified current HEAD.'
    Assert-AuditContract ([IO.File]::ReadAllText($resultsPath) -ceq $beforeDirty) 'Untracked input invalidation rewrote historical statuses.'
    Remove-Item -LiteralPath $unknownPath -Force
    [IO.File]::WriteAllText((Join-Path $work 'README.md'), 'uncommitted documentation change')
    Assert-AuditContract (Test-Here).Valid 'Documentation-only working dirt invalidated executable qualification.'
    [IO.File]::WriteAllText((Join-Path $work 'README.md'), 'documentation descendant')
    foreach ($badCount in @('not-a-count', '99999999999999999999999')) {
        Write-Results
        $malformed = Get-Content -LiteralPath $resultsPath -Raw | ConvertFrom-Json
        $malformed.build.executionInputs.fileCount = $badCount
        [IO.File]::WriteAllText($resultsPath, ($malformed | ConvertTo-Json -Depth 12))
        $beforeMalformed = [IO.File]::ReadAllText($resultsPath)
        Assert-AuditContract (-not (Test-Here).Valid) "Malformed input count $badCount escaped fail-closed qualification."
        Assert-AuditContract ([IO.File]::ReadAllText($resultsPath) -ceq $beforeMalformed) 'Malformed metadata invalidation rewrote historical statuses.'
    }

    # A new commit alone is insufficient: only documentation can retain qualification.
    Assert-AuditContract ((Get-AuditHash (Get-AuditExecutableInputs $work $head)) -ceq
        (Get-AuditHash $recordedInputs)) 'Documentation changed executable input identity.'
    foreach ($inputPath in $inputPaths + 'unknown-generator.bin') {
        [IO.File]::WriteAllText((Join-Path $work $inputPath), 'changed executable input')
        $head = Commit-Inputs @($inputPath)
        Write-Results
        $before = [IO.File]::ReadAllText($resultsPath)
        $stale = Test-Here
        Assert-AuditContract (-not $stale.Valid -and $stale.Reason -like '*changed or unversioned executable inputs*') "Changed $inputPath remained qualified: $($stale.Reason)"
        Assert-AuditContract ([IO.File]::ReadAllText($resultsPath) -ceq $before) "Invalidating $inputPath rewrote historical results."
        # A partial rerun's new top-level build must not promote old per-check builds.
        Write-Results @{ build = New-Build $head }
        $partial = Test-Here
        Assert-AuditContract (-not $partial.Valid -and $partial.Reason -like '*historical executable inputs for Alpha/bound*') 'A new top-level build promoted an old check.'
        if ($inputPath -ceq 'unknown-generator.bin') {
            Invoke-RepoGit -Root $work -Arguments @('rm', '-q', '--', $inputPath) | Out-Null
            Invoke-RepoGit -Root $work -Arguments @('-c', 'user.name=SysAdminDoc', '-c', 'user.email=matt_parker@outlook.com', 'commit', '-qm', 'Remove fixture input') | Out-Null
            $head = [string](Invoke-RepoGit -Root $work -Arguments @('rev-parse', 'HEAD'))
        } else {
            [IO.File]::WriteAllText((Join-Path $work $inputPath), 'original')
            $head = Commit-Inputs @($inputPath)
        }
        Write-Results
        Assert-AuditContract (Test-Here).Valid 'Restoring all executable inputs left qualification stale.'
    }
    Write-Results
    $legacy = Get-Content -LiteralPath $resultsPath -Raw | ConvertFrom-Json
    $legacy.build.PSObject.Properties.Remove('executionInputs')
    foreach ($patch in $legacy.patches) { foreach ($check in $patch.checks) { $check.build.PSObject.Properties.Remove('executionInputs') } }
    $legacy.patches[0].checks[0].status = 'inconclusive'
    $legacy.patches[1].checks[0].status = 'fail'
    $legacy.patches[1].checks[1].status = 'review'
    [IO.File]::WriteAllText($resultsPath, ($legacy | ConvertTo-Json -Depth 12))
    $before = [IO.File]::ReadAllText($resultsPath)
    Assert-AuditContract (-not (Test-Here).Valid) 'Unversioned legacy inputs remained qualified.'
    Assert-AuditContract ([IO.File]::ReadAllText($resultsPath) -ceq $before) 'Legacy invalidation overwrote fail/review/inconclusive statuses.'

    $beforeCulture = [Threading.Thread]::CurrentThread.CurrentCulture
    try {
        [Threading.Thread]::CurrentThread.CurrentCulture = [Globalization.CultureInfo]::GetCultureInfo('tr-TR')
        $script:auditInputCache.Clear()
        Assert-AuditContract ((Get-AuditHash (Get-AuditExecutableInputs $work $head)) -ceq
            (Get-AuditHash $recordedInputs)) 'Culture changed executable input identity.'
    } finally { [Threading.Thread]::CurrentThread.CurrentCulture = $beforeCulture }
    $otherShell = if ($PSVersionTable.PSVersion.Major -ge 6 -and $env:WINDIR) {
        Join-Path $env:WINDIR 'System32/WindowsPowerShell/v1.0/powershell.exe'
    } elseif (Get-Command pwsh -ErrorAction SilentlyContinue) { (Get-Command pwsh).Source }
    if ($otherShell -and (Test-Path -LiteralPath $otherShell)) {
        $crossScript = Join-Path $work 'compare-inputs.ps1'
        $helperPath = (Join-Path $repo 'audit/evidence.ps1').Replace("'", "''")
        [IO.File]::WriteAllText($crossScript, ('. ''' + $helperPath + "'" + [char]10 +
            'Get-AuditExecutableInputs -GitRoot $args[0] -Commit $args[1] | ConvertTo-Json -Compress'))
        $crossIdentity = & $otherShell -NoProfile -File $crossScript $work $parent
        Assert-AuditContract ($LASTEXITCODE -eq 0 -and
            (Get-AuditHash ($crossIdentity | ConvertFrom-Json)) -ceq (Get-AuditHash $recordedInputs)) 'PowerShell hosts disagreed on Unicode/space input paths.'
        Remove-Item -LiteralPath $crossScript -Force
    }

    $savedGit = @{}
    foreach ($name in @('GIT_DIR', 'GIT_WORK_TREE')) {
        $savedGit[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    try {
        $env:GIT_DIR = Join-Path $repo '.git'
        $env:GIT_WORK_TREE = $repo
        $script:auditInputCache.Clear()
        Write-Results
        Assert-AuditContract (Test-Here).Valid 'Inherited Git variables redirected audit input or ancestry inspection.'
    } finally {
        foreach ($name in $savedGit.Keys) {
            if ($null -eq $savedGit[$name]) { Remove-Item -LiteralPath ('Env:' + $name) -ErrorAction SilentlyContinue }
            else { Set-Item -LiteralPath ('Env:' + $name) -Value $savedGit[$name] }
        }
    }
    # Committing evidence and receipt outputs must not make their own APK stale.
    Write-Results
    [IO.File]::WriteAllText((Join-Path $work 'release-receipt-1.0.0.json'), '{}')
    $head = Commit-Inputs @('audit/results/tiktok-47.5.3.json', 'release-receipt-1.0.0.json')
    Assert-AuditContract (Test-Here).Valid 'Recording audit/receipt outputs invalidated their own build.'
    Write-Results
    $forgedInputs = Get-Content -LiteralPath $resultsPath -Raw | ConvertFrom-Json
    $forgedInputs.build.executionInputs.sha256 = '0' * 64
    [IO.File]::WriteAllText($resultsPath, ($forgedInputs | ConvertTo-Json -Depth 12))
    Assert-AuditContract (-not (Test-Here).Valid) 'A digest unrelated to the recorded source remained qualified.'

    Write-Results
    $unknownScope = Get-Content -LiteralPath $resultsPath -Raw | ConvertFrom-Json
    $unknownScope.build.executionInputs.schemaVersion = 2
    [IO.File]::WriteAllText($resultsPath, ($unknownScope | ConvertTo-Json -Depth 12))
    Assert-AuditContract (-not (Test-Here).Valid) 'An unknown executable-input schema remained qualified.'

} finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}

# The builder holds the audit before it patches a fixture, and publish holds it before anything
# goes public.
$builder = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'build-release-receipt.ps1') -Raw
$auditAt = $builder.IndexOf('Test-AuditResults ')
$patchAt = $builder.IndexOf("$" + "arguments = @('patch'")
Assert-AuditContract ($auditAt -gt 0 -and $patchAt -gt $auditAt -and $builder.Contains('audit         = $auditFacts.ToArray()')) `
    'build-release-receipt.ps1 no longer checks the device audit first or leaves it out of the receipt.'
$release = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'release/release.ps1') -Raw
$publishAt = $release.IndexOf("'publish' {")
$heldAt = $release.IndexOf('Test-AuditResults ', [Math]::Max($publishAt, 0))
$goesOutAt = $release.IndexOf('Invoke-ReleasePublication ', [Math]::Max($publishAt, 0))
Assert-AuditContract ($publishAt -gt 0 -and $heldAt -gt $publishAt -and $goesOutAt -gt $heldAt) `
    'release.ps1 -Stage publish reaches the publication before it holds the release to the device audit.'
Write-Host '[scripts] audit results contracts passed'
