<# Offline contracts for walk-receipt.ps1: publish takes only a full, passing walk of the bundle it ships. #>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'walk-receipt.ps1')
. (Join-Path $PSScriptRoot 'fixtures/walk-native-fixture.ps1')

function Assert-WalkContract([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

$work = Join-Path ([IO.Path]::GetTempPath()) ('hushfeed-walk-contract-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($work)
try {
    $native = New-WalkNativeFixture $work
    $bundle = $native.Bundle
    $expected = $native.Expected
    $walkPath = Join-Path $work 'walk-receipt.json'
    # The receipt a walk of that bundle writes. -Change bends one field.
    function Write-Walk([hashtable]$Change = @{}) {
        $walk = New-WalkReceipt -Result PASS -Full $true -Serial 'TESTPHONE01' -Device 'SM-S908U1' -AppVersion $native.Installed.versionName `
            -BundleSha256 (Get-FileHash -LiteralPath $bundle -Algorithm SHA256).Hash -InstalledApkSha256 ('4' * 64) `
            -InstalledPackage $native.Installed.package -InstalledVersionCode $native.Installed.versionCode -NativeBuild $native.Build `
            -Started '2026-10-21T16:00:00Z' -Finished '2026-10-21T16:30:00Z' -Screens 40 -Crashes 0
        foreach ($key in $Change.Keys) { $walk[$key] = $Change[$key] }
        [IO.File]::WriteAllText($walkPath, ($walk | ConvertTo-Json -Depth 20))
    }

    # The release phone or an emulator walks for a release.
    foreach ($walker in @(@{}, @{ serial = 'emulator-5554'; device = 'sdk_gphone64_x86_64'; emulator = $true })) {
        Write-Walk $walker
        $good = Test-WalkReceipt -Path $walkPath -BundlePath $bundle @expected
        Assert-WalkContract ($good.Valid -and $good.Receipt.screens -eq 40) "A full PASS walk of the bundle was refused: $($good.Reason)"
    }

    # The native identity is retained in full, not only reduced to the bundle hash.
    Write-Walk
    $retained = (Read-WalkReceipt -Path $walkPath).Receipt
    Assert-WalkContract ((Get-AuditHash $retained.build) -ceq (Get-AuditHash $native.Build)) 'The walk discarded native APK, source or selection facts.'
    $identityCases = [ordered]@{
        malformedProfile = { param($w) $w.profile = @('tiktok-whole-app-v1') }
        malformedInstalledVersionCode = { param($w) $w.installed.versionCode = @('2147483647') }
        wrongPackageSameVersion = { param($w) $w.installed.package = 'com.ss.android.ttmd.video'; $w.build.apk.package = $w.installed.package; $w.build.sourceApk.package = $w.installed.package }
        wrongInstalledHash = { param($w) $w.installed.sha256 = $w.installedApkSha256 = '5' * 64 }
        wrongPatchedHash = { param($w) $w.build.apk.sha256 = '5' * 64 }
        wrongInstalledVersionCode = { param($w) $w.installed.versionCode = '2024705030' }
        wrongSourceVersionCode = { param($w) $w.build.sourceApk.versionCode = '42' }
        sameVersionWrongSourceApk = { param($w) $w.build.sourceApk.sha256 = $w.build.sourceApkSha256 = '6' * 64 }
        changedSourceCommit = { param($w) $w.build.source.commit = 'b' * 40 }
        changedSourceFingerprint = { param($w) $w.build.source.startFingerprint = $w.build.source.endFingerprint = 'D' * 64 }
        dirtySource = { param($w) $w.build.source.clean = $false }
        differentNativeBundle = { param($w) $w.build.bundle.sha256 = '7' * 64 }
        excludedPatch = { param($w) $w.build.patches = @('Example') }
        reorderedSelection = { param($w) $w.build.patches = @('Version code', 'Example') }
        changedOptions = { param($w) $w.build.options = @('Example|mode=other') }
        missingPatchReport = { param($w) $w.build.patchReportSha256 = '' }
        oldNativeSchema = { param($w) $w.build.nativeBuildSchema = 1 }
        historicalBuild = { param($w) $w.build.status = 'historical' }
        missingNativeIdentity = { param($w) $w.build = $null }
        unimplementedProfile = { param($w) $w.profile = 'pinedrama-whole-app-v1' }
        unresolvedProblem = { param($w) $w.problems = @('A screen failed') }
    }
    foreach ($case in $identityCases.GetEnumerator()) {
        Write-Walk
        $changed = Get-Content -LiteralPath $walkPath -Raw | ConvertFrom-Json
        & $case.Value $changed
        [IO.File]::WriteAllText($walkPath, ($changed | ConvertTo-Json -Depth 20))
        $bad = Test-WalkReceipt -Path $walkPath -BundlePath $bundle @expected
        Assert-WalkContract (-not $bad.Valid) "A walk accepted mismatched native identity: $($case.Key)."
    }
    Write-Walk
    $noExpectation = Test-WalkReceipt -Path $walkPath -BundlePath $bundle
    Assert-WalkContract (-not $noExpectation.Valid) 'A walk authorized publication without independent target/source/selection expectations.'
    $pine = @{} + $expected
    $pine.ExpectedSourceApk = $native.SourceApk | ConvertTo-Json | ConvertFrom-Json
    $pine.ExpectedSourceApk.package = 'com.ss.android.ttmd.video'
    Assert-WalkContract (-not (Test-WalkReceipt -Path $walkPath -BundlePath $bundle @pine).Valid) 'A TikTok walk qualified another package with the same version.'
    Write-Walk @{ schema = 1 }
    Assert-WalkContract ((Read-WalkReceipt -Path $walkPath).Historical) 'A historical walk stopped being readable.'
    Assert-WalkContract (-not (Test-WalkReceipt -Path $walkPath -BundlePath $bundle @expected).Valid) 'A historical walk authorized a new publication.'
    Write-Walk
    $custom = Get-Content -LiteralPath $walkPath -Raw | ConvertFrom-Json
    $custom.build.options = @('Example|mode=one')
    [IO.File]::WriteAllText($walkPath, ($custom | ConvertTo-Json -Depth 20))
    $explicit = @{} + $expected; $explicit.ExpectedOptions = @('Example|mode=one')
    Assert-WalkContract ((Test-WalkReceipt -Path $walkPath -BundlePath $bundle @explicit).Valid) 'An explicitly matching native option selection was lost.'

    foreach ($badWalk in @(
            @(@{ result = 'FAIL' }, '*the walk ended FAIL, not PASS*'),
            @(@{ result = 'PARTIAL'; full = $false }, '*the walk ended PARTIAL, not PASS*'),
            @(@{ result = $null }, '*ended with no result*'),
            @(@{ full = $false }, '*was not the whole app*'),
            @(@{ screens = 0 }, "*doesn't say it opened any screen*"),
            @(@{ crashes = 1 }, '*recorded a TikTok or PineDrama crash*'),
            @(@{ device = $null }, "*doesn't say which device walked it*"),
            @(@{ device = 'Pixel 9' }, "*Pixel 9 isn't one of the release walk phones*"),
            @(@{ device = 'SM-S938B' }, "*SM-S938B isn't one of the release walk phones (SM-S908U1)*"),
            @(@{ device = 'Pixel 9'; emulator = $true }, "*Pixel 9 isn't one of the release walk phones*"),
            @(@{ bundleSha256 = ('5' * 64) }, '*patched with bundle*and this one is*'),
            @(@{ schema = 1 }, '*schema 1 walk is historical*'),
            @(@{ schema = 3 }, '*Unsupported walk receipt schema*'))) {
        Write-Walk $badWalk[0]
        $bad = Test-WalkReceipt -Path $walkPath -BundlePath $bundle @expected
        Assert-WalkContract (-not $bad.Valid -and $bad.Reason -like $badWalk[1]) "Expected the walk refusal [$($badWalk[1])], got [$($bad.Reason)]."
    }
    [IO.File]::WriteAllText($walkPath, 'not json')
    $bad = Test-WalkReceipt -Path $walkPath -BundlePath $bundle @expected
    Assert-WalkContract (-not $bad.Valid -and $bad.Reason -like "*doesn't read as JSON*") "A receipt that isn't JSON read as: $($bad.Reason)"
    $bad = Test-WalkReceipt -Path (Join-Path $work 'none.json') -BundlePath $bundle @expected
    Assert-WalkContract (-not $bad.Valid -and $bad.Reason -like "*there's no walk receipt at*") "A missing receipt read as: $($bad.Reason)"

    # A walk of one bundle never backs another, even a rebuild of the same commit.
    Write-Walk
    [IO.File]::WriteAllText($bundle, 'the signed bundle, rebuilt')
    $bad = Test-WalkReceipt -Path $walkPath -BundlePath $bundle @expected
    Assert-WalkContract (-not $bad.Valid -and $bad.Reason -like '*patched with bundle*and this one is*') "A walk backed another bundle: $($bad.Reason)"
    $bad = Test-WalkReceipt -Path $walkPath -BundlePath (Join-Path $work 'gone.mpp') @expected
    Assert-WalkContract (-not $bad.Valid -and $bad.Reason -like "*there's no bundle at*") "A walk backed a bundle that isn't there: $($bad.Reason)"
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}

$single = [pscustomobject]@{ PackageName = 'com.zhiliaoapp.musically'; PackageVersions = @('47.5.3'); OtherPackages = @() }
Assert-WalkContract (-not (Get-WalkPublicationProblem $single)) 'The implemented single global target was refused before receipt validation.'
foreach ($coverage in @(
        [pscustomobject]@{ PackageName = $single.PackageName; PackageVersions = $single.PackageVersions; OtherPackages = @('com.ss.android.ttmd.video') },
        [pscustomobject]@{ PackageName = $single.PackageName; PackageVersions = @('47.5.3','47.6.0'); OtherPackages = @() },
        [pscustomobject]@{ PackageName = 'com.ss.android.ttmd.video'; PackageVersions = @('47.5.3'); OtherPackages = @() })) {
    Assert-WalkContract ([bool](Get-WalkPublicationProblem $coverage)) 'One global walk authorized another package or target version.'
}

# The existing published release reader stays pinned to its exact historical document.
$frozenPath = Join-Path $PSScriptRoot 'fixtures/legacy-receipt-0.61.0.json'
$frozen = Get-Content -LiteralPath $frozenPath -Raw | ConvertFrom-Json
Assert-WalkContract (Test-FrozenLegacyReceipt -Receipt $frozen -ReceiptPath $frozenPath) 'The frozen historical release stopped being readable.'
$frozen.bundle.sha256 = '5' * 64
Assert-WalkContract (-not (Test-FrozenLegacyReceipt -Receipt $frozen -ReceiptPath $frozenPath)) 'A changed object borrowed the frozen release identity.'

# Publish asks for the walk once the built assets check out, before the notes, the tag or GitHub.
$releaseText = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'release/release.ps1'))
$publishAt = $releaseText.IndexOf("'publish' {")
$assetsAt = $releaseText.IndexOf('$receipt.source.commit -ne $head', $publishAt)
$walkAt = $releaseText.IndexOf('$walked = Test-WalkReceipt -Path $Walk', $publishAt)
$refuseAt = $releaseText.IndexOf('if (-not $walked.Valid) {', $publishAt)
foreach ($later in @('github-notes', 'Invoke-ReleasePublication -Root')) {
    $laterAt = $releaseText.IndexOf($later, $publishAt)
    Assert-WalkContract ($publishAt -gt 0 -and $assetsAt -gt $publishAt -and $walkAt -gt $assetsAt -and $refuseAt -gt $walkAt -and $laterAt -gt $refuseAt) `
        "release.ps1 -Stage publish reaches $later before it holds the bundle to its walk."
}
Assert-WalkContract ($releaseText -match '(?m)^\s*\[string\]\$Walk,') 'release.ps1 has no -Walk.'
$coverageAt = $releaseText.IndexOf('Get-WalkPublicationProblem $target', $publishAt)
Assert-WalkContract ($coverageAt -gt $assetsAt -and $coverageAt -lt $walkAt) 'Publication skipped the per-package/version coverage guard.'
Write-Host '[scripts] walk receipt contracts passed'
