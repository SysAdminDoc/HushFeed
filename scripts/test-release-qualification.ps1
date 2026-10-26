<# Offline release matrix contracts. Fixture and native records below are synthetic. #>
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'release-receipt.ps1')
. (Join-Path $PSScriptRoot 'patch-target.ps1')
. (Join-Path $PSScriptRoot 'fixtures/walk-native-fixture.ps1')
. (Join-Path $PSScriptRoot 'fixtures/release-qualified-fixture.ps1')
$script:matrixChecks=0
function Assert-Matrix([bool]$Condition,[string]$Message){if(-not $Condition){throw $Message};$script:matrixChecks++}
$work=Join-Path ([IO.Path]::GetTempPath()) ('hushfeed-release-matrix-'+[guid]::NewGuid().ToString('N'))
try {
    $fixture=New-WalkNativeFixture $work
    $declared=@(
        [pscustomobject]@{PackageName='com.example.host';PackageVersions=@('47.5.3');PatchNames=@('Alpha','Beta')},
        [pscustomobject]@{PackageName='com.example.drama';PackageVersions=@('47.5.3');PatchNames=@('Alpha','Gamma')})
    $targets=@()
    for($i=0;$i -lt $declared.Count;$i++){
        $targets+= [pscustomobject]@{
            source=[pscustomobject]@{file="stock-$i.apk";package=$declared[$i].PackageName;versionName='47.5.3';versionCode='2024705030';sha256=([string][char](98+$i))*64;forced=$false}
            patches=@($declared[$i].PatchNames|ForEach-Object{[pscustomobject]@{name=$_;applied=$true;reason=$null}})
            manifestDelta=[pscustomobject]@{permissionsAdded=@();permissionsRemoved=@();exportedComponentsAdded=@();exportedComponentsRemoved=@()}
        }
    }
    $base=[pscustomobject]@{
        schemaVersion=3;release=[pscustomobject]@{version='0.79.0';tag='v0.79.0';commit=$fixture.Commit;commitTimestamp=1792886400L;patchCount=3}
        bundle=[pscustomobject]@{file='patches-0.79.0.mpp';sizeBytes=(Get-Item -LiteralPath $fixture.Bundle).Length;sha256=(Get-Sha256Hex $fixture.Bundle);timestamp=1792886400000L}
        toolchain=[pscustomobject]@{patcherVersion='2.0.0';managerFloor='1.30.0'};source=$fixture.Build.source
        extension=[pscustomobject]@{dexPayloads=@([pscustomobject]@{name='extensions/tiktok.rve';sizeBytes=10;sha256='a'*64})};targets=$targets
    }
    Set-ReleaseTestQualification $base $declared
    $json=$base|ConvertTo-Json -Depth 24
    $arguments=@{ExpectedVersion='0.79.0';ExpectedPatchNames=@('Alpha','Beta','Gamma');ExpectedPatcherVersion='2.0.0';ExpectedManagerFloor='1.30.0';
        ExpectedPackageName='com.example.host';ExpectedPackageVersions=@('47.5.3');ExpectedTargets=$declared;BundlePath=$fixture.Bundle;ExpectedCommit=$fixture.Commit;RequireCurrentContract=$true}
    $valid=Test-ReleaseReceipt -Receipt ($json|ConvertFrom-Json) @arguments
    Assert-Matrix ($valid.Valid -and $valid.EligibleForPublication -and -not $valid.Historical) "A complete independent two-package matrix failed: $($valid.Reason)"
    $mutations=[ordered]@{
        missingPackage={param($r)$r.targets=@($r.targets[0])}
        missingDeclaration={param($r)$r.declaredTargets=@($r.declaredTargets[0])}
        wrongPackageSameVersion={param($r)$r.targets[1].source.package='com.example.host'}
        wrongStockApk={param($r)$r.targets[1].source.sha256='5'*64}
        wrongStockVersionCode={param($r)$r.targets[1].source.versionCode='42'}
        wrongPatchedPackage={param($r)$r.targets[1].patched.package='com.example.host'}
        wrongSelectedSubset={param($r)$r.targets[1].selection.patches=@('Alpha','Beta')}
        wrongOption={param($r)$r.targets[1].selection.options=@('Alpha|flag=true')}
        unknownActualApplication={param($r)$r.targets[1].actualAppliedPatches+=@('Unknown')}
        missingActualApplication={param($r)$r.targets[1].actualAppliedPatches=@('Alpha')}
        wrongBundle={param($r)$r.targets[1].binding.bundleSha256='6'*64}
        wrongSource={param($r)$r.targets[1].binding.sourceCommit='b'*40}
        wrongReport={param($r)$r.targets[1].binding.patchReportSha256='7'*64}
        missingNativePackage={param($r)$r.audit=@($r.audit[0])}
        wrongNativeSource={param($r)$r.audit[1].nativeBuild.sourceApk.sha256='8'*64}
        wrongNativeBundle={param($r)$r.audit[1].nativeBuild.bundle.sha256='9'*64}
        wrongNativeSelection={param($r)$r.audit[1].nativeBuild.patches=@('Alpha','Beta')}
        wrongNativeOptions={param($r)$r.audit[1].nativeBuild.options=@('Alpha|flag=true')}
        wrongInstalledApk={param($r)$r.audit[1].installedApkSha256='a'*64}
        noNativeChecks={param($r)$r.audit[1].nativeChecks=0}
        historicalNativeBuild={param($r)$r.audit[1].nativeBuild.status='historical'}
        repeatedNative={param($r)$r.audit=@($r.audit[0],$r.audit[1],$r.audit[1])}
        failedNativeCheck={param($r)$r.audit[1].summary.fail=1;$r.audit[1].summary.pass=2}
        unreviewedNativeCheck={param($r)$r.audit[1].summary.review=1;$r.audit[1].summary.pass=2}
        malformedSchema={param($r)$r.schemaVersion='3'}
    }
    foreach($case in $mutations.GetEnumerator()){
        $changed=$json|ConvertFrom-Json;& $case.Value $changed
        $bad=Test-ReleaseReceipt -Receipt $changed @arguments
        Assert-Matrix (-not $bad.Valid -and [bool]$bad.Reason) "A mismatched release matrix passed: $($case.Key)."
    }
    $noExpectation=@{}+$arguments;$noExpectation.Remove('ExpectedTargets')
    Assert-Matrix (-not (Test-ReleaseReceipt -Receipt ($json|ConvertFrom-Json) @noExpectation).Valid) 'A current receipt passed without a complete catalog matrix expectation.'
    $actualCatalog=Get-Content -LiteralPath (Join-Path (Split-Path -Parent $PSScriptRoot) 'patches-list.json') -Raw|ConvertFrom-Json
    $actual=@(Get-DeclaredPatchTargets $actualCatalog)
    $pine=@($actual|Where-Object PackageName -CEQ 'com.ss.android.ttmd.video')
    Assert-Matrix ($actual.Count -eq 2 -and $pine.Count -eq 1 -and $pine[0].PatchNames.Count -eq 95 -and $pine[0].PackageVersions -ccontains '47.5.3') 'The actual catalog did not retain PineDrama as its own exact 95-patch target.'
    $frozenPath=Join-Path $PSScriptRoot 'fixtures/legacy-receipt-0.79.0.json'
    $frozen=Get-Content -LiteralPath $frozenPath -Raw|ConvertFrom-Json
    $historicalArgs=@{ExpectedVersion='0.79.0';ExpectedPatchNames=@($frozen.targets[0].patches|ForEach-Object name);ExpectedPatcherVersion=$frozen.toolchain.patcherVersion;
        ExpectedManagerFloor=$frozen.toolchain.managerFloor;ExpectedPackageName='com.zhiliaoapp.musically';ExpectedPackageVersions=@('47.5.3');ReceiptPath=$frozenPath;
        ApprovedManifestDelta=@($frozen.targets|ForEach-Object{ConvertTo-ManifestDeltaEntries $_.manifestDelta}|Sort-Object -Unique)}
    $read=Test-ReleaseReceipt -Receipt $frozen @historicalArgs
    Assert-Matrix ($read.Valid -and $read.Historical -and -not $read.EligibleForPublication) "Exact published schema 2 stopped being readable: $($read.Reason)"
    Assert-Matrix (-not (Test-ReleaseReceipt -Receipt $frozen @historicalArgs -RequireCurrentContract).Valid) 'A frozen historical receipt authorized a current artifact.'
    $frozen.targets[0].source.sha256='f'*64
    Assert-Matrix (-not (Test-ReleaseReceipt -Receipt $frozen @historicalArgs).Valid) 'A different object borrowed the frozen schema-2 file identity.'
    # Execute the actual publish call and lookup AST without running any publishing stages.
    $releaseSource=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'release/release.ps1') -Raw
    $releaseAst=[Management.Automation.Language.Parser]::ParseInput($releaseSource,[ref]$null,[ref]$null)
    $walkCall=$releaseAst.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst] -and $n.GetCommandName() -ceq 'Test-WalkReceipt'},$true)
    Assert-Matrix ($walkCall.Count -eq 1) 'Publish no longer has one explicit walk qualification call.'
    function Test-WalkReceipt { param($Path,$BundlePath,$ExpectedSourceApk,$ExpectedCommit,[string[]]$ExpectedPatches,[string[]]$ExpectedOptions)
        [pscustomobject]@{Source=$ExpectedSourceApk;Commit=$ExpectedCommit;Patches=@($ExpectedPatches);Options=@($ExpectedOptions)} }
    $walkTargets=@($base.targets[0]);$walkTargets[0].selection.options=@('Alpha|mode=exact')
    $Walk='synthetic-walk.json';$assets=$work;$bundleName='patches-0.79.0.mpp';$head=$fixture.Commit
    $expectation=& ([scriptblock]::Create($walkCall[0].Extent.Text))
    Assert-Matrix ((Get-ReleaseIdentityHash $expectation.Patches) -ceq (Get-ReleaseIdentityHash @($walkTargets[0].selection.patches)) -and
        (Get-ReleaseIdentityHash $expectation.Options) -ceq (Get-ReleaseIdentityHash @('Alpha|mode=exact'))) 'Publish discarded the qualified nondefault patch/options selection.'
    Assert-Matrix ($releaseSource.IndexOf('$coverageProblem = Get-WalkPublicationProblem') -ge 0 -and
        $releaseSource.IndexOf('$coverageProblem = Get-WalkPublicationProblem') -lt $walkCall[0].Extent.StartOffset) 'Publish skipped profile-coverage refusal before checking the walk.'
    $lookup=$releaseAst.FindAll({param($n) $n -is [Management.Automation.Language.AssignmentStatementAst] -and $n.Left.Extent.Text -ceq '$recorded'},$true)
    Assert-Matrix ($lookup.Count -eq 1) 'Publish audit lookup is no longer explicit.'
    $receipt=$base;$fact=$base.audit[0]
    $found=@(& ([scriptblock]::Create($lookup[0].Extent.Text+[char]10+'$recorded')))
    Assert-Matrix ($found.Count -eq 1 -and $found[0].package -ceq $fact.package -and $found[0].appVersion -ceq $fact.appVersion) 'Publish confused same-version audits from different packages.'
    $fact=[pscustomobject]@{package='com.example.unknown';appVersion=$base.audit[0].appVersion}
    $found=@(& ([scriptblock]::Create($lookup[0].Extent.Text+[char]10+'$recorded')))
    Assert-Matrix ($found.Count -eq 0) 'Another package audit qualified a missing package with the same version.'
    Write-Output ('Release matrix contracts passed: '+$script:matrixChecks)
} finally {
    $resolved=[IO.Path]::GetFullPath($work);$parent=[IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if(-not $resolved.StartsWith($parent,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $resolved) -notlike 'hushfeed-release-matrix-*'){throw 'Unsafe matrix fixture cleanup path.'}
    if(Test-Path -LiteralPath $resolved){Remove-Item -LiteralPath $resolved -Recurse -Force}
}
