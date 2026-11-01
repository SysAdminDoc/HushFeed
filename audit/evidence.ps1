# Shared by execution, read-only result validation and human verdict import.
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'scripts/apk-facts.ps1')

function Test-AuditExecutableInputPath([string]$Path) {
    # Global scope until a reviewed patch-to-input map exists. Unknown paths stay included.
    # Runtime resources, stubs, build tools, pins and verification metadata are all inputs.
    if ($Path -cmatch '^(extensions|patches)/(.+/)?src/main/') { return $true }
    if ($Path -cmatch '(^|/)src/(test|androidTest|testFixtures)/' -or
        $Path -cmatch '^(scripts|audit)/test-[^/]+\.ps1$' -or
        $Path -cmatch '^tools/(.*/)?test_[^/]+\.py$') { return $false }
    # Catalog behavior has its own definition hashes. Evidence and receipt outputs must
    # not invalidate the build whose execution they record.
    if ($Path -cmatch '^audit/catalog\.json$' -or $Path -cmatch '^audit/results/' -or
        $Path -ceq 'patches-bundle.json') { return $false }
    if ($Path -cmatch '\.md$' -or $Path -cmatch '^(docs|concepts)/' -or
        $Path -cin @('LICENSE', 'NOTICE') -or $Path -cmatch '^assets/(readme-|screenshots/)') { return $false }
    return $true
}

function Get-AuditWorkingInputProblem([string]$GitRoot, [string]$Commit = 'HEAD') {
    if (-not $GitRoot) { $GitRoot = Split-Path -Parent $PSScriptRoot }
    $head = [string](Invoke-RepoGit -Root $GitRoot -Arguments @('rev-parse', '--verify', 'HEAD'))
    if ($LASTEXITCODE -ne 0 -or $head -cnotmatch '^[a-f0-9]{40}$') { throw 'Cannot read current audit source commit.' }
    if ($Commit -cne 'HEAD') {
        $target = [string](Invoke-RepoGit -Root $GitRoot -Arguments @('rev-parse', '--verify', "$Commit^{commit}"))
        if ($LASTEXITCODE -ne 0 -or $target -cnotmatch '^[a-f0-9]{40}$') { throw 'Cannot resolve requested audit source commit.' }
        # Historical qualification describes that exact committed tree, not this checkout.
        if ($target -cne $head) { return $null }
    }
    $encodingBefore = [Console]::OutputEncoding
    $paths = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    try {
        [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
        foreach ($arguments in @(
            @('diff', '--name-only', '--no-ext-diff', '--no-renames', '-z', $head, '--'),
            @('ls-files', '--others', '--exclude-standard', '-z'),
            @('ls-files', '--others', '--ignored', '--exclude-standard', '-z', '--',
                ':(glob)extensions/**/src/main/**', ':(glob)patches/**/src/main/**')
        )) {
            $answer = @(Invoke-RepoGit -Root $GitRoot -Arguments $arguments) -join [char]10
            if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect current executable input changes.' }
            foreach ($path in $answer.Split([char]0)) {
                if ($path -and (Test-AuditExecutableInputPath $path)) { [void]$paths.Add($path) }
            }
        }
        $flags = @(Invoke-RepoGit -Root $GitRoot -Arguments @('ls-files', '-v', '-z')) -join [char]10
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect executable input index flags.' }
        foreach ($flag in $flags.Split([char]0)) {
            if ($flag -and $flag[0] -cne 'H' -and (Test-AuditExecutableInputPath $flag.Substring(2))) {
                [void]$paths.Add($flag.Substring(2))
            }
        }
    } finally { [Console]::OutputEncoding = $encodingBefore }
    if ($paths.Count) {
        return 'uncommitted or hidden executable inputs: ' + ((@($paths | Sort-Object) | Select-Object -First 5) -join ', ')
    }
    return $null
}

function Get-AuditExecutableInputs([string]$GitRoot, [string]$Commit = 'HEAD') {
    if (-not $GitRoot) { $GitRoot = Split-Path -Parent $PSScriptRoot }
    $resolved = @((Invoke-RepoGit -Root $GitRoot -Arguments @('rev-parse', '--verify', "$Commit^{commit}")))
    if ($LASTEXITCODE -ne 0 -or $resolved.Count -ne 1 -or $resolved[0] -cnotmatch '^[a-f0-9]{40}$') {
        throw 'Cannot resolve the audited executable-input commit.'
    }
    $commitId = [string]$resolved[0]
    $key = [IO.Path]::GetFullPath($GitRoot) + '|' + $commitId
    if (-not (Get-Variable auditInputCache -Scope Script -ErrorAction SilentlyContinue)) {
        $script:auditInputCache = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    }
    if ($script:auditInputCache.ContainsKey($key)) { return $script:auditInputCache[$key] }
    $encodingBefore = [Console]::OutputEncoding
    try {
        [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
        $tree = @((Invoke-RepoGit -Root $GitRoot -Arguments @('ls-tree', '-rz', '--full-tree', $commitId))) -join [char]10
        $treeExit = $LASTEXITCODE
    } finally { [Console]::OutputEncoding = $encodingBefore }
    if ($treeExit -ne 0 -or -not $tree) { throw 'Cannot read audited executable inputs.' }
    $files = [Collections.Generic.SortedDictionary[string,object]]::new([StringComparer]::Ordinal)
    foreach ($record in $tree.Split([char]0)) {
        if (-not $record) { continue }
        if ($record -cnotmatch '^(?<mode>[0-7]{6}) (?<type>blob|commit) (?<object>[a-f0-9]{40})\t(?<path>[\s\S]+)$') {
            throw 'Unrecognized executable-input tree entry.'
        }
        $mode = $Matches.mode; $type = $Matches.type; $object = $Matches.object; $path = $Matches.path
        if (-not (Test-AuditExecutableInputPath $path)) { continue }
        $files.Add($path, [ordered]@{ mode = $mode; type = $type; object = $object })
    }
    if ($files.Count -eq 0) { throw 'No audited executable inputs were found.' }
    $identity = [pscustomobject]@{
        schemaVersion = 1; scope = 'global-executable-v1'; fileCount = $files.Count
        sha256 = Get-AuditHash ([ordered]@{ scope = 'global-executable-v1'; files = $files })
    }
    $script:auditInputCache[$key] = $identity
    return $identity
}

function Test-AuditExecutableInputBinding($Build, $ExpectedInputs, [string]$GitRoot) {
    try {
        $recorded = $Build.executionInputs
        if ($null -eq $recorded -or $recorded.schemaVersion -ne 1 -or
            [string]$recorded.scope -cne 'global-executable-v1' -or
            [string]$recorded.sha256 -cnotmatch '^[a-f0-9]{64}$' -or [int]$recorded.fileCount -le 0 -or
            [string]$Build.source.commit -cnotmatch '^[a-f0-9]{40}$') { return $false }
        if ($null -eq $ExpectedInputs) { $ExpectedInputs = Get-AuditExecutableInputs $GitRoot }
        # A stored digest must describe its own source as well as the release it qualifies.
        $sourceInputs = Get-AuditExecutableInputs $GitRoot ([string]$Build.source.commit)
        return (Get-AuditHash $recorded) -ceq (Get-AuditHash $sourceInputs) -and
            (Get-AuditHash $recorded) -ceq (Get-AuditHash $ExpectedInputs)
    } catch { return $false }
}

function ConvertTo-AuditJsonString([string]$Value) {
    # Windows PowerShell HTML-escapes characters that PS7 leaves literal. Own the encoding so
    # the same definition and persisted receipt keep their identity across both hosts.
    $escaped = $Value.Replace('\', '\\').Replace('"', '\"')
    $escaped = [regex]::Replace($escaped, '[\x00-\x1f\uD800-\uDFFF]', {
        param($match)
        '\u' + ([int][char]$match.Value).ToString('x4', [Globalization.CultureInfo]::InvariantCulture)
    })
    return '"' + $escaped + '"'
}

function Get-AuditCanonicalJson($Value) {
    if ($null -eq $Value) { return 'null' }
    # Scalars first. [pscustomobject] means PSObject, and any value a pipeline emitted is
    # wrapped in one, so a string tested that way serialized as {"Length":n}. -is looks through
    # the wrapper for every other type, PSCustomObject included.
    if ($Value -is [string] -or $Value -is [char]) { return ConvertTo-AuditJsonString ([string]$Value) }
    if ($Value -is [bool]) { return ([string]$Value).ToLowerInvariant() }
    $culture = [Globalization.CultureInfo]::InvariantCulture
    # ConvertFrom-Json reads 1.5 as decimal on Windows PowerShell and as double on PS7, so a
    # decimal prints as the double it stands for. Fixed digit counts, never 'R': Windows
    # PowerShell's 'R' falls back to 17 digits where PS7 prints the shortest round trip.
    if ($Value -is [decimal]) { $Value = [double]$Value }
    if ($Value -is [double] -or $Value -is [single]) {
        if ([double]::IsNaN($Value) -or [double]::IsInfinity($Value)) { throw 'Non-finite audit number.' }
        return $Value.ToString($(if ($Value -is [single]) { 'G9' } else { 'G17' }), $culture)
    }
    if ($Value -is [byte] -or $Value -is [sbyte] -or $Value -is [int16] -or $Value -is [uint16] -or
        $Value -is [int32] -or $Value -is [uint32] -or $Value -is [int64] -or $Value -is [uint64]) {
        return $Value.ToString($culture)
    }
    if ($Value -is [System.Collections.IDictionary]) {
        $keys = [string[]]@($Value.Keys)
        [Array]::Sort($keys, [StringComparer]::Ordinal)
        $parts = foreach ($key in $keys) {
            (ConvertTo-AuditJsonString ([string]$key)) + ':' + (Get-AuditCanonicalJson $Value[$key])
        }
        return '{' + ($parts -join ',') + '}'
    }
    if ($Value -is [System.Management.Automation.PSCustomObject]) {
        $keys = [string[]]@($Value.PSObject.Properties.Name)
        [Array]::Sort($keys, [StringComparer]::Ordinal)
        $parts = foreach ($key in $keys) {
            (ConvertTo-AuditJsonString $key) + ':' + (Get-AuditCanonicalJson $Value.PSObject.Properties[$key].Value)
        }
        return '{' + ($parts -join ',') + '}'
    }
    if ($Value -is [System.Collections.IEnumerable]) {
        $items = foreach ($item in $Value) { Get-AuditCanonicalJson $item }
        return '[' + (@($items) -join ',') + ']'
    }
    throw "Unsupported audit value type: $($Value.GetType().FullName)"
}

function Get-AuditHash($Value) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($algorithm.ComputeHash(
            [Text.Encoding]::UTF8.GetBytes((Get-AuditCanonicalJson $Value))))).Replace('-', '').ToLowerInvariant()
    } finally { $algorithm.Dispose() }
}

function Get-AuditDefinitionHash($Entry, $Check) {
    # Settings and patch classification can alter setup even if the check's own steps did not.
    return Get-AuditHash ([ordered]@{
        name = $Entry.name; category = $Entry.category; kind = $Entry.kind
        settings = @($Entry.settings); check = $Check
        stepKinds = @($Check.steps | ForEach-Object { @($_.PSObject.Properties.Name)[0] })
    })
}

function Get-AuditProbePreconditionProblem($Step, [string]$Answer) {
    $required = $Step.PSObject.Properties['preconditionExpect']
    if ($null -eq $required) { return $null }
    if ($required.Value -isnot [string] -or [string]::IsNullOrWhiteSpace($required.Value)) { throw 'Invalid probe precondition pattern.' }
    if (-not [regex]::IsMatch($Answer, $required.Value, [Text.RegularExpressions.RegexOptions]::Multiline)) {
        return "probe $($Step.probe) did not establish its required observation"
    }
    return $null
}

function Get-AuditCatalogProblems($Entries) {
    $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($entry in $Entries) {
        if ([string]::IsNullOrWhiteSpace([string]$entry.name) -or ([string]$entry.name).Contains('/')) {
            "invalid patch name '$($entry.name)'"
        } elseif (-not $names.Add([string]$entry.name)) { "duplicate patch '$($entry.name)'" }
        $ids = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($check in @($entry.checks)) {
            foreach ($step in @($check.steps)) {
                $required = $step.PSObject.Properties['preconditionExpect']
                if ($null -eq $required) { continue }
                if (@($step.PSObject.Properties.Name)[0] -cne 'probe' -or $required.Value -isnot [string] -or [string]::IsNullOrWhiteSpace($required.Value)) {
                    "invalid probe precondition '$($entry.name)/$($check.id)'"
                } else {
                    try { [void][regex]::new($required.Value) } catch { "invalid probe precondition regex '$($entry.name)/$($check.id)'" }
                }
            }
            $selection = $check.PSObject.Properties['patchSelection']
            if ($null -ne $selection -and ($selection.Value -isnot [string] -or $selection.Value -cnotin @('selected','absent'))) {
                "invalid patch selection policy '$($entry.name)/$($check.id)'"
            }
            if ([string]::IsNullOrWhiteSpace([string]$check.id) -or ([string]$check.id).Contains('/')) {
                "invalid check ID '$($entry.name)/$($check.id)'"
            } elseif (-not $ids.Add([string]$check.id)) { "duplicate check '$($entry.name)/$($check.id)'" }
        }
    }
}

function Get-AuditEvidenceHashes($Evidence, [string]$Directory) {
    foreach ($file in @($Evidence)) {
        if (-not $Directory -or [string]::IsNullOrWhiteSpace([string]$file) -or
            [string]$file -cne [IO.Path]::GetFileName([string]$file) -or $file -in '.', '..') {
            throw 'Evidence must name a file inside -OutDir.'
        }
        [ordered]@{ file = [string]$file; sha256 = (Get-FileHash -LiteralPath (Join-Path $Directory $file) -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant() }
    }
}

function Set-AuditCheckReceipt($Result, [string]$Directory) {
    $Result.receipt = [ordered]@{
        execution = [Guid]::NewGuid().ToString('N'); definition = $Result.definitionHash
        apk = $Result.apkSha256; installed = $Result.installedApkSha256; build = $Result.build
        evidence = @($Result.evidence); files = @(Get-AuditEvidenceHashes $Result.evidence $Directory)
        review = @($Result.review); auto = $Result.auto; status = $Result.status; failures = @($Result.failures)
    }
    $Result.receiptId = Get-AuditHash $Result.receipt
}

function Get-DeviceNativeBuildProblem($Build, [string]$ApkHash, $App) {
    try {
        if ($Build.status -cne 'verified') { return 'The native build identity is not verified.' }
        if (($Build.nativeBuildSchema -isnot [int] -and $Build.nativeBuildSchema -isnot [long]) -or
            $Build.nativeBuildSchema -ne 2) { return 'The build has no complete native APK identity.' }
        $sourceCheck = Test-BuildSourceFacts -Source $Build.source -ExpectedCommit ([string]$Build.source.commit)
        if (-not $sourceCheck.Valid) { return $sourceCheck.Reason }
        if ([string]$Build.receiptSha256 -cnotmatch '^[a-f0-9]{64}$') { return 'The build has no device receipt identity.' }
        if ($null -eq $App -or [string]::IsNullOrWhiteSpace([string]$App.package)) {
            return 'The device build has no app package identity.'
        }
        foreach ($facts in @($Build.sourceApk, $Build.apk)) {
            if ($null -eq $facts -or [string]$facts.package -cne [string]$App.package -or
                [string]::IsNullOrWhiteSpace([string]$facts.versionName) -or
                [string]$facts.versionCode -cnotmatch '^[0-9]+$' -or
                [string]$facts.sha256 -cnotmatch '^[a-f0-9]{64}$') { return 'The build has malformed APK manifest or hash facts.' }
            $code = 0L
            if (-not [long]::TryParse([string]$facts.versionCode, [ref]$code) -or $code -le 0) {
                return 'The build has an invalid APK version code.'
            }
        }
        if ([string]$Build.apk.sha256 -cne $ApkHash -or
            [string]$Build.apk.versionName -cne [string]$App.version -or
            [string]$Build.apk.versionCode -cne [string]$App.versionCode -or
            [string]$Build.sourceApk.versionName -cne [string]$Build.apk.versionName -or
            [string]$Build.sourceApkSha256 -cne [string]$Build.sourceApk.sha256) {
            return 'The build APK facts do not match the audited app and artifact.'
        }
        if ([string]$Build.bundle.sha256 -cnotmatch '^[a-f0-9]{64}$' -or
            [string]$Build.patchReportSha256 -cnotmatch '^[a-f0-9]{64}$') {
            return 'The build has no bundle or patch report identity.'
        }
        if ($Build.patches -isnot [Array] -or $Build.patches.Count -eq 0 -or
            $Build.options -isnot [Array]) { return 'The build has no exact patch and option selection.' }
        $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($name in $Build.patches) {
            if ($name -isnot [string] -or [string]::IsNullOrWhiteSpace($name) -or -not $names.Add($name)) {
                return 'The build has a missing or duplicate selected patch.'
            }
        }
        foreach ($option in $Build.options) {
            if ($option -isnot [string] -or $option -cnotmatch '^([^|]+)\|([^=]+)=(.*)$' -or
                -not $names.Contains($Matches[1])) { return 'A build option does not belong to a selected patch.' }
        }
        return $null
    } catch { return 'The build has malformed native identity fields.' }
}

# The audit profile: the catalog and results name, the probe build (tools/verification-probe/profile.ps1)
# that loads inside TikTok and the routes the runner drives. Any other package can't be audited.
function Get-AuditPackageProfile([string]$Package) {
    if ($Package -cne 'com.zhiliaoapp.musically') { return $null }
    return [pscustomobject]@{
        Package = $Package; Label = 'TikTok'; Catalog = 'audit/catalog.json'; ResultsPrefix = 'tiktok'
        Scheme = 'snssdk1233'; Tabs = @{ feed = '^Home'; friends = '^Friends'; inbox = '^Inbox'; profile = '^Profile' }
    }
}

function Get-AuditNativeBuildProblem($Build, [string]$ApkHash, $App) {
    if ($null -eq $App -or $null -eq (Get-AuditPackageProfile ([string]$App.package))) {
        return 'This package has no audit profile.'
    }
    return Get-DeviceNativeBuildProblem $Build $ApkHash $App
}

function Get-AuditPatchSelectionProblem($Entry, $Check, $Build) {
    # Ordinary checks need the patch selected. Negative checks declare absence explicitly.
    $policyProperty = $Check.PSObject.Properties['patchSelection']
    $policy = if ($null -eq $policyProperty) { 'selected' } else { [string]$policyProperty.Value }
    if (($null -ne $policyProperty -and $policyProperty.Value -isnot [string]) -or
        $policy -cnotin @('selected', 'absent')) { return 'Unknown per-check patch selection policy.' }
    if ($null -eq $Build -or $Build.patches -isnot [Array]) { return 'The check has no recorded patch selection.' }
    $selected = [string]$Entry.name -cin $Build.patches
    if ($selected -ne ($policy -ceq 'selected')) {
        return "$($Entry.name)/$($Check.id) requires its patch $policy in the audited build."
    }
    return $null
}

function Test-AuditCheckBinding($Entry, $Check, $Result, [string]$ApkHash, [string]$Directory, $ExecutableInputs, [string]$GitRoot, $App) {
    if ((Get-AuditNativeBuildProblem $Result.build $ApkHash $App) -or
        (Get-AuditPatchSelectionProblem $Entry $Check $Result.build)) { return $false }
    if (-not (Test-AuditExecutableInputBinding $Result.build $ExecutableInputs $GitRoot)) { return $false }
    if ($Result.definitionHash -cne (Get-AuditDefinitionHash $Entry $Check)) { return $false }
    if ($ApkHash -notmatch '^[a-fA-F0-9]{64}$' -or $Result.apkSha256 -cne $ApkHash) { return $false }
    if ($Result.receiptId -notmatch '^[a-f0-9]{64}$' -or $Result.build.status -cne 'verified' -or
        $Result.build.receiptSha256 -notmatch '^[a-f0-9]{64}$') { return $false }
    $source = $Result.build.source
    if ($source.commit -notmatch '^[a-f0-9]{40}$' -or $source.clean -isnot [bool] -or -not $source.clean -or
        $source.startFingerprint -notmatch '^[a-fA-F0-9]{64}$' -or
        $source.startFingerprint -cne $source.endFingerprint) { return $false }
    $needsDevice = @($Check.steps | Where-Object { @($_.PSObject.Properties.Name)[0] -ne 'apk' }).Count -gt 0
    if ($needsDevice -and $Result.installedApkSha256 -cne $ApkHash) { return $false }
    $receipt = $Result.receipt
    if ($null -eq $receipt -or $receipt.execution -notmatch '^[a-f0-9]{32}$' -or
        (Get-AuditHash $receipt) -cne $Result.receiptId -or
        $receipt.definition -cne $Result.definitionHash -or $receipt.apk -cne $Result.apkSha256 -or
        $receipt.installed -cne $Result.installedApkSha256 -or $receipt.auto -ne $Result.auto -or
        (Get-AuditHash $receipt.build) -cne (Get-AuditHash $Result.build) -or
        (Get-AuditHash @($receipt.review)) -cne (Get-AuditHash @($Result.review)) -or
        (Get-AuditHash @($receipt.failures)) -cne (Get-AuditHash @($Result.failures)) -or
        (Get-AuditHash @($receipt.evidence)) -cne (Get-AuditHash @($Result.evidence))) { return $false }
    if ($Result.status -cne $receipt.status) {
        if ($receipt.status -cne 'review' -or $Result.auto -or $Check.auto -or
            $Result.status -cnotin @('pass', 'fail') -or $Result.verdict.status -cne $Result.status -or
            $Result.verdict.receiptId -cne $Result.receiptId -or
            [string]::IsNullOrWhiteSpace([string]$Result.verdict.note)) { return $false }
    }
    try { $files = @(Get-AuditEvidenceHashes $Result.evidence $Directory) } catch { return $false }
    if ((Get-AuditHash $files) -cne (Get-AuditHash @($receipt.files))) { return $false }
    foreach ($review in @($Result.review)) {
        if ([string]$review.shot -cnotin @($files | ForEach-Object { $_.file })) { return $false }
    }
    return $true
}

function Get-AuditResultValidation($Entries, $Run, [string]$Directory, [string]$GitRoot, [string]$Head = 'HEAD') {
    $expected = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    $problems = [Collections.Generic.List[string]]::new()
    $executableInputs = $null
    try {
        $executableInputs = Get-AuditExecutableInputs $GitRoot $Head
        $workingProblem = Get-AuditWorkingInputProblem $GitRoot $Head
        if ($workingProblem) { $problems.Add($workingProblem); $executableInputs = $null }
    }
    catch { $executableInputs = $null; $problems.Add("unavailable-executable-inputs: $($_.Exception.Message)") }
    foreach ($problem in @(Get-AuditCatalogProblems $Entries)) { $problems.Add($problem) }
    foreach ($entry in $Entries) {
        foreach ($check in $entry.checks) { $expected["$($entry.name)/$($check.id)"] = @($entry, $check) }
    }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $missing = [Collections.Generic.List[string]]::new()
    $duplicates = [Collections.Generic.List[string]]::new()
    $removed = [Collections.Generic.List[string]]::new()
    $changed = [Collections.Generic.List[string]]::new()
    $unknown = [Collections.Generic.List[string]]::new()
    $historical = [Collections.Generic.List[string]]::new()
    $digests = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $current = 0
    $currentPass = 0
    foreach ($patch in @($Run.patches)) {
        foreach ($result in @($patch.checks)) {
            $key = "$($patch.name)/$($result.id)"
            $digest = if ($result.apkSha256) { [string]$result.apkSha256 } else { [string]$patch.apkSha256 }
            if ($digest) { [void]$digests.Add($digest) }
            if (-not $seen.Add($key)) { $duplicates.Add($key); continue }
            if (-not $expected.ContainsKey($key)) { $removed.Add($key); continue }
            if ($result.status -cnotin @('pass','fail','review','inconclusive','error','skipped')) { $unknown.Add($key); continue }
            $entry, $check = $expected[$key]
            if ($result.definitionHash -cne (Get-AuditDefinitionHash $entry $check)) { $changed.Add($key) }
            if ($null -ne $executableInputs -and
                (Test-AuditCheckBinding $entry $check $result ([string]$Run.apk.sha256) $Directory $executableInputs $GitRoot $Run.app)) {
                $current++
                if ($result.status -ceq 'pass') { $currentPass++ }
            } else { $historical.Add($key) }
        }
    }
    foreach ($key in $expected.Keys) { if (-not $seen.Contains($key)) { $missing.Add($key) } }
    foreach ($category in @(@('missing',$missing), @('duplicate',$duplicates), @('removed',$removed), @('changed-or-unversioned',$changed), @('unknown-status',$unknown))) {
        foreach ($key in $category[1]) { $problems.Add("$($category[0]): $key") }
    }
    foreach ($key in $historical) { $problems.Add("historical-or-unbound: $key") }
    if ($digests.Count -gt 1) { $problems.Add("mixed-build: results come from $($digests.Count) APKs") }
    return [pscustomobject]@{
        state = if ($problems.Count -eq 0) { 'current-bound' } else { 'historical-or-incomplete' }
        executionInputs = $executableInputs
        catalogChecks = $expected.Count; resultChecks = $seen.Count; currentChecks = $current; currentPass = $currentPass
        apkDigests = @($digests | Sort-Object); mixedBuild = $digests.Count -gt 1
        missing = @($missing | Sort-Object); duplicates = @($duplicates | Sort-Object); removed = @($removed | Sort-Object)
        changed = @($changed | Sort-Object); unknown = @($unknown | Sort-Object); historical = @($historical | Sort-Object)
        problems = @($problems)
    }
}

function ConvertFrom-AuditVerdictJson([string]$Json) {
    $value = ConvertFrom-Json -InputObject $Json -ErrorAction Stop
    if ($value -isnot [pscustomobject] -or -not $Json.TrimStart().StartsWith('{')) {
        throw 'Verdicts must be a JSON object keyed by patch/check ID.'
    }
    # ConvertFrom-Json discards earlier duplicate properties. Scan the original tokens before
    # accepting that object, including escaped names and duplicates within each verdict.
    $contexts = [Collections.Generic.Stack[object]]::new()
    $strings = [regex]'(?s)\G"(?:\\.|[^"\\])*"'
    for ($i = 0; $i -lt $Json.Length; $i++) {
        $character = $Json[$i]
        if ($character -eq "'" -or ($contexts.Count -gt 0 -and $contexts.Peek().keyExpected -and
                $character -ne '"' -and $character -ne '}' -and -not [char]::IsWhiteSpace($character))) {
            throw 'Verdict property names and strings must use JSON double quotes.'
        }
        if ($character -eq '"') {
            $token = $strings.Match($Json, $i)
            if (-not $token.Success) { throw 'Invalid JSON string in verdicts.' }
            if ($contexts.Count -gt 0 -and $contexts.Peek().keyExpected) {
                $context = $contexts.Peek()
                $decoded = ConvertFrom-Json -InputObject ('{' + $token.Value + ':null}')
                $key = @($decoded.PSObject.Properties.Name)[0]
                if (-not $context.keys.Add($key)) { throw "Duplicate verdict property '$key'." }
                $context.keyExpected = $false
            }
            $i += $token.Length - 1
        } elseif ($character -eq '{' -or $character -eq '[') {
            $contexts.Push([pscustomobject]@{
                isObject = $character -eq '{'; keyExpected = $character -eq '{'
                keys = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
            })
        } elseif ($character -eq '}' -or $character -eq ']') {
            [void]$contexts.Pop()
        } elseif ($character -eq ',' -and $contexts.Count -gt 0) {
            $contexts.Peek().keyExpected = $contexts.Peek().isObject
        } elseif ($character -eq '/') {
            throw 'JSON comments are not allowed in verdict imports.'
        }
    }
    return $value
}

function Set-AuditVerdicts($Entries, $Run, $Verdicts, [string]$Directory, [string]$GitRoot) {
    $executableInputs = Get-AuditExecutableInputs $GitRoot
    $workingProblem = Get-AuditWorkingInputProblem $GitRoot
    if ($workingProblem) { throw $workingProblem }
    $catalogProblems = @(Get-AuditCatalogProblems $Entries)
    if ($catalogProblems.Count) { throw ($catalogProblems -join '; ') }
    $updates = @()
    foreach ($property in $Verdicts.PSObject.Properties) {
        $name, $id = $property.Name -split '/', 2
        $entriesFound = @($Entries | Where-Object { $_.name -ceq $name })
        $patchesFound = @($Run.patches | Where-Object { $_.name -ceq $name })
        if ($entriesFound.Count -ne 1 -or $patchesFound.Count -ne 1) { throw "No unique patch for $($property.Name)." }
        $checksFound = @($entriesFound[0].checks | Where-Object { $_.id -ceq $id })
        $resultsFound = @($patchesFound[0].checks | Where-Object { $_.id -ceq $id })
        if ($checksFound.Count -ne 1 -or $resultsFound.Count -ne 1) { throw "No unique check for $($property.Name)." }
        $check = $checksFound[0]
        $result = $resultsFound[0]
        $verdict = $property.Value
        if ($result.status -cne 'review' -or $result.auto -or $check.auto -or @($result.review).Count -eq 0 -or
            @($check.steps | Where-Object { $_.shot }).Count -eq 0) { throw "$($property.Name) is not an undecided review check." }
        if ($verdict.status -cnotin @('pass','fail') -or [string]::IsNullOrWhiteSpace([string]$verdict.note)) {
            throw "$($property.Name) needs pass/fail and a review note."
        }
        if (-not (Test-AuditCheckBinding $entriesFound[0] $check $result ([string]$Run.apk.sha256) $Directory $executableInputs $GitRoot $Run.app) -or
            $verdict.receiptId -cne $result.receiptId) { throw "$($property.Name) has stale or unbound evidence." }
        $updates += , @($result, $verdict)
    }
    # Validate the entire import before changing any result.
    foreach ($update in $updates) {
        $update[0].status = $update[1].status
        $update[0] | Add-Member -NotePropertyName verdictNote -NotePropertyValue $update[1].note -Force
        $update[0] | Add-Member -NotePropertyName verdict -NotePropertyValue ([pscustomobject]@{
            receiptId = $update[1].receiptId; status = $update[1].status; note = $update[1].note
        }) -Force
    }
}

function Read-DeviceBuildIdentity([string]$ApkPath, [string]$ReceiptPath, [string]$BundlePath, [string]$GitRoot, $App) {
    if (-not $ReceiptPath) { return [pscustomobject]@{ status = 'unbound'; reason = 'No device-build receipt supplied.' } }
    if (-not $BundlePath) { throw 'A build receipt requires -Bundle to verify its source manifest.' }
    . (Join-Path (Split-Path -Parent $PSScriptRoot) 'scripts/apk-facts.ps1')
    $receipt = Get-Content -LiteralPath $ReceiptPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if (($receipt.schemaVersion -isnot [int] -and $receipt.schemaVersion -isnot [long]) -or
        $receipt.schemaVersion -cnotin @(1,2) -or $receipt.kind -cne 'hushfeed-device-build') { throw 'Unsupported device-build receipt.' }
    if ($receipt.apk.sha256 -ine (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash -or
        $receipt.bundle.sha256 -ine (Get-FileHash -LiteralPath $BundlePath -Algorithm SHA256).Hash) {
        throw 'The build receipt does not match the APK and bundle.'
    }
    $manifest = Get-BundleManifestFacts -BundlePath $BundlePath
    $sourceCheck = Test-BuildSourceFacts -Source $manifest.source -ExpectedCommit ([string]$receipt.source.commit)
    if (-not $sourceCheck.Valid -or (Get-AuditHash $manifest.source) -cne (Get-AuditHash $receipt.source)) {
        throw 'The build receipt source does not match a clean, unchanged bundle build.'
    }
    $identity = [ordered]@{
        status = 'historical'; receiptSha256 = (Get-FileHash -LiteralPath $ReceiptPath -Algorithm SHA256).Hash.ToLowerInvariant()
        source = $manifest.source; executionInputs = Get-AuditExecutableInputs $GitRoot ([string]$manifest.source.commit)
    }
    if ($receipt.schemaVersion -eq 1) {
        $identity.reason = 'The device-build receipt predates complete APK and selection identity.'
        return [pscustomobject]$identity
    }
    foreach ($name in @('apk','sourceApk','sourceApkSha256','bundle','patches','options','patchReportSha256')) {
        $identity[$name] = $receipt.$name
    }
    $identity.nativeBuildSchema = 2
    $identity.status = 'verified'
    $problem = Get-DeviceNativeBuildProblem ([pscustomobject]$identity) ([string]$receipt.apk.sha256) $App
    if ($problem) { throw $problem }
    return [pscustomobject]$identity
}

function Read-AuditBuildIdentity([string]$ApkPath, [string]$ReceiptPath, [string]$BundlePath, [string]$GitRoot, $App) {
    $identity = Read-DeviceBuildIdentity $ApkPath $ReceiptPath $BundlePath $GitRoot $App
    if ($identity.status -ceq 'verified') {
        $problem = Get-AuditNativeBuildProblem $identity ([string]$identity.apk.sha256) $App
        if ($problem) { throw $problem }
    }
    return $identity
}
