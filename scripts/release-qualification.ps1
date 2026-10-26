# Static fixture qualification and its linkage to independently collected native evidence.
function Get-ReleaseIdentityHash([AllowEmptyCollection()][string[]]$Values = @()) {
    $stream = [IO.MemoryStream]::new()
    $writer = [IO.BinaryWriter]::new($stream, [Text.UTF8Encoding]::new($false, $true), $true)
    try {
        $writer.Write([int]$Values.Count)
        foreach ($value in $Values) { $writer.Write([string]$value) }
        $writer.Flush(); $sha = [Security.Cryptography.SHA256]::Create()
        try { return ([BitConverter]::ToString($sha.ComputeHash($stream.ToArray()))).Replace('-', '').ToLowerInvariant() }
        finally { $sha.Dispose() }
    } finally { $writer.Dispose(); $stream.Dispose() }
}
function Get-ReleaseFixtureQualificationHash($Target) {
    $values = @('release-fixture-v1')
    foreach ($facts in @($Target.source, $Target.patched)) {
        foreach ($field in @('package','versionName','versionCode','sha256')) { $values += [string]$facts.$field }
    }
    $values += [string]$Target.source.forced
    foreach ($field in @('bundleSha256','sourceCommit','sourceStartFingerprint','sourceEndFingerprint','patchReportSha256')) { $values += [string]$Target.binding.$field }
    $values += [string]@($Target.selection.patches).Count; $values += @($Target.selection.patches)
    $values += [string]@($Target.selection.options).Count; $values += @($Target.selection.options)
    $values += [string]@($Target.actualAppliedPatches).Count; $values += @($Target.actualAppliedPatches)
    return Get-ReleaseIdentityHash $values
}
function New-ReleaseFixtureBinding($Source, $Patched, [string[]]$Patches, [string[]]$Options,
        [string]$BundleSha256, $BuildSource, [string]$PatchReportSha256, [string[]]$AppliedPatchNames = @(), [string[]]$DependencyNames = @()) {
    if ($Source.package -cne $Patched.package -or $Source.versionName -cne $Patched.versionName) { throw 'The fixture output manifest differs from its source package/version.' }
    return [ordered]@{
        selection = [ordered]@{ patches = @($Patches); options = @($Options); dependencies = @($DependencyNames) }
        actualAppliedPatches = @($AppliedPatchNames)
        patched = [ordered]@{ package = $Patched.package; versionName = $Patched.versionName; versionCode = $Patched.versionCode; sha256 = $Patched.sha256 }
        binding = [ordered]@{ bundleSha256 = $BundleSha256; sourceCommit = $BuildSource.commit
            sourceStartFingerprint = $BuildSource.startFingerprint; sourceEndFingerprint = $BuildSource.endFingerprint
            patchReportSha256 = $PatchReportSha256 }
    }
}
function Get-ReleaseQualificationProblem($Receipt, [object[]]$ExpectedTargets) {
    try {
        if (-not $ExpectedTargets -or $ExpectedTargets.Count -eq 0) { return 'Schema 3 requires the complete declared package/version matrix.' }
        $declared = @($Receipt.declaredTargets)
        if ($declared.Count -ne $ExpectedTargets.Count) { return 'The receipt declared-target matrix is incomplete.' }
        $known = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($expected in $ExpectedTargets) {
            if (-not $known.Add([string]$expected.PackageName)) { return 'The expected target matrix repeats a package.' }
            $row = @($declared | Where-Object { $_.PackageName -ceq $expected.PackageName })
            if ($row.Count -ne 1 -or
                    (Get-ReleaseIdentityHash @($row[0].PackageVersions)) -cne (Get-ReleaseIdentityHash @($expected.PackageVersions)) -or
                    (Get-ReleaseIdentityHash @($row[0].PatchNames)) -cne (Get-ReleaseIdentityHash @($expected.PatchNames))) {
                return 'The receipt package/version/patch declaration differs from the catalog.'
            }
        }
        foreach ($fact in @($Receipt.audit)) {
            $declaredAudit = @($ExpectedTargets | Where-Object { $_.PackageName -ceq $fact.package -and $fact.appVersion -cin @($_.PackageVersions) })
            if ($declaredAudit.Count -ne 1) { return "Native audit is of undeclared $($fact.package) $($fact.appVersion)." }
        }
        $inputs = @($Receipt.fixtureInputs); $targets = @($Receipt.targets)
        if ($inputs.Count -ne $targets.Count) { return 'The receipt input ledger does not match its fixture runs.' }
        $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $nativeCovered = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($target in $targets) {
            $expected = @($ExpectedTargets | Where-Object { $_.PackageName -ceq $target.source.package })
            if ($expected.Count -ne 1) { return 'A fixture uses an undeclared package.' }
            $key = Get-ReleaseIdentityHash @($target.source.package, $target.source.versionName, $target.source.versionCode, $target.source.sha256)
            if (-not $seen.Add($key)) { return 'The receipt repeats a source APK fixture.' }
            $matchedInput = @($inputs | Where-Object {
                $_.source.package -ceq $target.source.package -and $_.source.versionName -ceq $target.source.versionName -and
                [string]$_.source.versionCode -ceq [string]$target.source.versionCode -and $_.source.sha256 -ceq $target.source.sha256
            })
            if ($matchedInput.Count -ne 1 -or $matchedInput[0].source.forced -isnot [bool] -or $matchedInput[0].source.forced -ne $target.source.forced) { return 'A fixture source APK does not match the pre-patching input ledger.' }
            if ((Get-ReleaseIdentityHash @($target.selection.patches)) -cne (Get-ReleaseIdentityHash @($expected[0].PatchNames)) -or
                    (Get-ReleaseIdentityHash @($matchedInput[0].selection.patches)) -cne (Get-ReleaseIdentityHash @($target.selection.patches)) -or
                    (Get-ReleaseIdentityHash @($matchedInput[0].selection.options)) -cne (Get-ReleaseIdentityHash @($target.selection.options))) {
                return 'A fixture applied a different patch subset or option selection.'
            }
            $dependencyProperty = $expected[0].PSObject.Properties['DependencyNames']
            $expectedDependencies = if ($null -eq $dependencyProperty) { @() } else { @($dependencyProperty.Value) }
            if ($target.actualAppliedPatches -isnot [Array] -or
                    (Get-ReleaseIdentityHash @($target.selection.dependencies)) -cne (Get-ReleaseIdentityHash $expectedDependencies) -or
                    (Get-ReleaseIdentityHash @($matchedInput[0].selection.dependencies)) -cne (Get-ReleaseIdentityHash $expectedDependencies)) {
                return 'A fixture has no exact applied/dependency selection.'
            }
            $allowed = [Collections.Generic.HashSet[string]]::new([string[]](@($target.selection.patches) + $expectedDependencies), [StringComparer]::Ordinal)
            $applied = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
            foreach ($name in @($target.actualAppliedPatches)) {
                if ($name -isnot [string] -or -not $allowed.Contains($name) -or -not $applied.Add($name)) { return 'A fixture reports an unexpected or repeated applied patch.' }
            }
            if (@($target.selection.patches | Where-Object { -not $applied.Contains($_) }).Count -gt 0) { return 'A fixture did not actually apply its requested subset.' }
            if ($target.selection.patches -isnot [Array] -or $target.selection.options -isnot [Array]) { return 'A fixture has no exact selection arrays.' }
            foreach ($option in $target.selection.options) {
                if ($option -isnot [string] -or $option -cnotmatch '^([^|]+)\|([^=]+)=(.*)$' -or $Matches[1] -cnotin $target.selection.patches) {
                    return 'A fixture option does not belong to its selected package subset.'
                }
            }
            foreach ($facts in @($target.source, $target.patched)) {
                [long]$code = 0
                if ([string]$facts.sha256 -notmatch '\A[0-9a-f]{64}\z' -or
                        -not [long]::TryParse([string]$facts.versionCode, [ref]$code) -or $code -le 0) { return 'A fixture has malformed APK hash or version-code facts.' }
            }
            if ($target.patched.package -cne $target.source.package -or $target.patched.versionName -cne $target.source.versionName) {
                return 'The patched APK manifest does not match its source package/version.'
            }
            if ($target.binding.bundleSha256 -ine $Receipt.bundle.sha256 -or $target.binding.sourceCommit -cne $Receipt.source.commit -or
                    $target.binding.sourceStartFingerprint -cne $Receipt.source.startFingerprint -or
                    $target.binding.sourceEndFingerprint -cne $Receipt.source.endFingerprint -or
                    [string]$target.binding.patchReportSha256 -notmatch '\A[0-9a-f]{64}\z' -or
                    $target.qualificationSha256 -cne (Get-ReleaseFixtureQualificationHash $target)) {
                return 'A fixture is not bound to this bundle, source and exact patch result.'
            }
        }
        foreach ($expected in $ExpectedTargets) {
            foreach ($version in @($expected.PackageVersions)) {
                $runs = @($targets | Where-Object { $_.source.package -ceq $expected.PackageName -and $_.source.versionName -ceq $version -and -not $_.source.forced })
                if ($runs.Count -eq 0) { return "No target in the receipt is the declared $($expected.PackageName) $version patched without -f." }
                foreach ($run in $runs) {
                    $native = @($Receipt.audit | Where-Object {
                        $_.package -ceq $expected.PackageName -and $_.appVersion -ceq $version -and
                        $_.nativeBuild.sourceApk.sha256 -ceq $run.source.sha256
                    })
                    if ($native.Count -ne 1) { return "The receipt has no unique native audit of $($expected.PackageName) $version and its exact source APK." }
                    $fact = $native[0]; $build = $fact.nativeBuild
                    [void]$nativeCovered.Add("$($fact.package)|$($fact.appVersion)|$($build.sourceApk.sha256)")
                    if ($fact.results -cnotmatch '^audit/results/[A-Za-z0-9_.-]+\.json$' -or
                            ($fact.nativeChecks -isnot [int] -and $fact.nativeChecks -isnot [long])) { return 'A native audit has malformed evidence location or check counts.' }
                    foreach ($state in @('fail','review','inconclusive','error','skipped')) {
                        if ($null -eq $fact.summary -or $fact.summary.$state -ne 0) { return 'A native audit has unresolved or non-passing checks.' }
                    }
                    if ($fact.summary.pass -ne $fact.checks -or $fact.checks -lt $fact.nativeChecks) { return 'A native audit does not account for every check.' }
                    if ($build.status -cne 'verified' -or $build.nativeBuildSchema -ne 2 -or $fact.nativeChecks -le 0 -or
                            $fact.installedApkSha256 -cne $fact.apkSha256 -or $build.apk.sha256 -cne $fact.apkSha256 -or
                            [string]$fact.apkSha256 -notmatch '\A[0-9a-f]{64}\z' -or [string]$fact.sha256 -notmatch '\A[0-9a-f]{64}\z' -or
                            [string]$build.receiptSha256 -notmatch '\A[0-9a-f]{64}\z' -or [string]$build.patchReportSha256 -notmatch '\A[0-9a-f]{64}\z') {
                        return 'A native audit has no complete installed/build/result identity.'
                    }
                    if ($fact.sourceCommit -cne $Receipt.source.commit -or $build.source.commit -cne $Receipt.source.commit -or
                            $build.source.clean -isnot [bool] -or -not $build.source.clean -or
                            $build.source.startFingerprint -cne $Receipt.source.startFingerprint -or $build.source.endFingerprint -cne $Receipt.source.endFingerprint -or
                            $build.bundle.sha256 -ine $Receipt.bundle.sha256) { return 'A native audit used a different bundle or build source.' }
                    if ([string]$build.sourceApkSha256 -cne [string]$run.source.sha256) { return 'A native audit source hash alias disagrees with the fixture.' }
                    foreach ($field in @('package','versionName','versionCode','sha256')) {
                        if ([string]$build.sourceApk.$field -cne [string]$run.source.$field) { return 'A native audit source APK does not match the qualified fixture.' }
                    }
                    foreach ($field in @('package','versionName','versionCode')) {
                        if ([string]$build.apk.$field -cne [string]$run.patched.$field) { return 'A native audit installed a different patched manifest.' }
                    }
                    if ((Get-ReleaseIdentityHash @($build.patches)) -cne (Get-ReleaseIdentityHash @($run.selection.patches)) -or
                            (Get-ReleaseIdentityHash @($build.options)) -cne (Get-ReleaseIdentityHash @($run.selection.options))) {
                        return 'A native audit used a different selected patch subset or options.'
                    }
                }
            }
        }
        if (@($Receipt.audit).Count -ne $nativeCovered.Count) { return 'The receipt has unrelated or repeated native audits.' }
        return $null
    } catch { return 'The receipt has malformed per-target qualification facts.' }
}
