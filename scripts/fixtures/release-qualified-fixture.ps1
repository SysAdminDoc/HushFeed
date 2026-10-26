# Synthetic qualification records for offline receipt contracts. They assert no native execution.
function Set-ReleaseTestQualification($Receipt, [object[]]$DeclaredTargets) {
    $Receipt | Add-Member -NotePropertyName declaredTargets -NotePropertyValue @($DeclaredTargets) -Force
    $inputs = New-Object System.Collections.Generic.List[object]
    $audits = New-Object System.Collections.Generic.List[object]
    $index = 0
    foreach ($target in $Receipt.targets) {
        $declared = @($DeclaredTargets | Where-Object { $_.PackageName -ceq $target.source.package })[0]
        $selection = [ordered]@{ patches = @($declared.PatchNames); options = @(); dependencies = @() }
        $patched = [pscustomobject]@{ package = $target.source.package; versionName = $target.source.versionName
            versionCode = '2147483647'; sha256 = ([Convert]::ToString(10 + $index, 16) * 64) }
        $proof = New-ReleaseFixtureBinding -Source $target.source -Patched $patched -Patches $selection.patches -Options @() `
            -BundleSha256 $Receipt.bundle.sha256 -BuildSource $Receipt.source -PatchReportSha256 ('f' * 64) -AppliedPatchNames @($declared.PatchNames)
        foreach ($field in @('selection','actualAppliedPatches','patched','binding')) { $target | Add-Member -NotePropertyName $field -NotePropertyValue $proof[$field] -Force }
        $target | Add-Member -NotePropertyName qualificationSha256 -NotePropertyValue (Get-ReleaseFixtureQualificationHash $target) -Force
        $inputs.Add([ordered]@{ source = ($target.source | ConvertTo-Json -Depth 8 | ConvertFrom-Json); selection = $selection })
        if (-not $target.source.forced) {
            $nativeApk = $patched | ConvertTo-Json | ConvertFrom-Json
            $nativeApk.sha256 = Get-ReleaseIdentityHash @('synthetic-native-apk', [string]$index, [string]$target.source.package, [string]$target.source.versionName, [string]$target.source.sha256)
            $nativeBuild = [ordered]@{
                status = 'verified'; nativeBuildSchema = 2; receiptSha256 = 'd' * 64; patchReportSha256 = 'f' * 64
                source = $Receipt.source; sourceApk = ($target.source | ConvertTo-Json | ConvertFrom-Json)
                sourceApkSha256 = $target.source.sha256; apk = $nativeApk
                bundle = [ordered]@{ sha256 = $Receipt.bundle.sha256 }
                patches = @($declared.PatchNames); options = @()
            }
            $audits.Add([ordered]@{
                package = $target.source.package; appVersion = $target.source.versionName; results = "audit/results/$($target.source.package)-$($target.source.versionName).json"
                sha256 = 'd' * 64; sourceCommit = $Receipt.source.commit; checks = 3; nativeChecks = 1
                summary = [ordered]@{ pass = 3; fail = 0; review = 0; inconclusive = 0; error = 0; skipped = 0 }
                apkSha256 = $nativeApk.sha256; installedApkSha256 = $nativeApk.sha256; nativeBuild = $nativeBuild
            })
        }
        $index++
    }
    $Receipt | Add-Member -NotePropertyName fixtureInputs -NotePropertyValue $inputs.ToArray() -Force
    $Receipt | Add-Member -NotePropertyName audit -NotePropertyValue $audits.ToArray() -Force
}
