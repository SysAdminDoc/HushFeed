[CmdletBinding()]
param([string]$Root)
$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $Root 'audit/evidence.ps1')
. (Join-Path $Root 'scripts/patch-target.ps1')
. (Join-Path $Root 'scripts/fixtures/walk-native-fixture.ps1')
. (Join-Path $Root 'tools/verification-probe/probe-log.ps1')
$checks = 0
function Assert-Profile([bool]$Condition, [string]$Message) { $script:checks++; if (-not $Condition) { throw $Message } }
function Copy-ProfileValue($Value) { return $Value | ConvertTo-Json -Depth 30 | ConvertFrom-Json }
function Assert-ProfileThrows([scriptblock]$Action, [string]$Message) {
    $rejected = $false
    try { & $Action } catch { $rejected = $true }
    Assert-Profile $rejected $Message
}
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$directory = Join-Path $tempRoot ('hushfeed-verification-profile-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($directory)
try {
    $fixture = New-WalkNativeFixture $directory
    $target = @(Get-DeclaredPatchTargets -PatchList (Get-Content -LiteralPath (Join-Path $Root 'patches-list.json') -Raw | ConvertFrom-Json) |
        Where-Object PackageName -CEQ 'com.ss.android.ttmd.video')[0]
    $build = $fixture.Build
    $build.sourceApk.package = 'com.ss.android.ttmd.video'; $build.apk.package = $build.sourceApk.package
    $build.patches = @($target.PatchNames)
    $build.source.commit = [string](Invoke-RepoGit -Root $Root -Arguments @('rev-parse', 'HEAD'))
    $plan = [pscustomobject]@{ kind = 'hushfeed-capability-inspection'; schema = 1; profile = 'pinedrama-47.5.3-capabilities-v1'
        full = $false; eligibleForPublication = $false; build = $build; expectedPatches = @($target.PatchNames); expectedOptions = @()
        catalogCommit = $build.source.commit; missingCoverage = @((Get-VerificationProbeProfile 'pinedrama-47.5.3-capabilities-v1').MissingCoverage) }
    Assert-Profile (-not (Get-PineDramaInspectionProblem $plan)) 'Exact PineDrama subset was rejected.'
    $app = [pscustomobject]@{ package = $build.apk.package; version = $build.apk.versionName; versionCode = $build.apk.versionCode }
    Assert-Profile (-not (Get-DeviceNativeBuildProblem $build $build.apk.sha256 $app)) 'Package-neutral artifact identity rejected PineDrama.'
    Assert-Profile ([bool](Get-AuditNativeBuildProblem $build $build.apk.sha256 $app)) 'PineDrama slipped into the global native audit.'
    foreach ($mutation in @(
        { param($p) $p.profile = 'tiktok-whole-app-v1' },
        { param($p) $p.full = $true },
        { param($p) $p.schema = '1' },
        { param($p) $p.missingCoverage = @() },
        { param($p) $p.eligibleForPublication = $true },
        { param($p) $p.build.apk.package = 'com.zhiliaoapp.musically'; $p.build.sourceApk.package = $p.build.apk.package },
        { param($p) $p.build.apk.versionName = '47.4.3'; $p.build.sourceApk.versionName = $p.build.apk.versionName },
        { param($p) $p.build.sourceApk.versionCode = '2024704030' },
        { param($p) $p.build.patches = @($p.build.patches | Where-Object { $_ -cne 'Allow photo comments' }) },
        { param($p) $p.build.patches = @($p.build.patches | Where-Object { $_ -cne 'Settings' }) },
        { param($p) $p.build.patches += 'Mute feed videos' },
        { param($p) $p.build.patches = @('Settings','Allow photo comments'); $p.expectedPatches = @($p.build.patches) },
        { param($p) $p.build.options = @('Allow photo comments|example=true') },
        { param($p) $p.build.options = @('Mute feed videos|x=true') },
        { param($p) $p.build.sourceApkSha256 = '1' * 64 },
        { param($p) $p.build.source.commit = 'b' * 40 },
        { param($p) $p.catalogCommit = 'b' * 40 },
        { param($p) $p.build.nativeBuildSchema = 1 }
    )) {
        $bad = Copy-ProfileValue $plan; & $mutation $bad
        Assert-Profile ([bool](Get-PineDramaInspectionProblem $bad)) 'Wrong profile/package/version/selection/source was accepted.'
    }
    # End-to-end producer fixture: real files/hashes/manifest parser, synthetic native facts only.
    $stockFile = Join-Path $directory 'stock.apk'; $patchedFile = Join-Path $directory 'patched.apk'
    [IO.File]::WriteAllText($stockFile, 'synthetic stock bytes')
    [IO.File]::WriteAllText($patchedFile, 'synthetic patched bytes')
    $producerBuild = Copy-ProfileValue $build
    $producerBuild.sourceApk.sha256 = (Get-FileHash $stockFile -Algorithm SHA256).Hash.ToLowerInvariant()
    $producerBuild.sourceApkSha256 = $producerBuild.sourceApk.sha256
    $producerBuild.apk.sha256 = (Get-FileHash $patchedFile -Algorithm SHA256).Hash.ToLowerInvariant()
    $zip = [IO.Compression.ZipFile]::Open($fixture.Bundle, [IO.Compression.ZipArchiveMode]::Update)
    try {
        $entry = $zip.GetEntry('META-INF/MANIFEST.MF'); $reader = [IO.StreamReader]::new($entry.Open())
        try { $manifestText = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $entry.Delete(); $entry = $zip.CreateEntry('META-INF/MANIFEST.MF'); $writer = [IO.StreamWriter]::new($entry.Open())
        try { $writer.Write($manifestText.Replace('a' * 40, $producerBuild.source.commit)) } finally { $writer.Dispose() }
    } finally { $zip.Dispose() }
    $producerBuild.bundle.sha256 = (Get-FileHash $fixture.Bundle -Algorithm SHA256).Hash.ToLowerInvariant()
    $receipt = [pscustomobject]@{ kind = 'hushfeed-device-build'; schemaVersion = 2; source = $producerBuild.source
        apk = $producerBuild.apk; sourceApk = $producerBuild.sourceApk; sourceApkSha256 = $producerBuild.sourceApkSha256
        bundle = $producerBuild.bundle; patches = @($producerBuild.patches); options = @(); patchReportSha256 = $producerBuild.patchReportSha256 }
    $receiptPath = Join-Path $directory 'build.json'
    $receipt | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $receiptPath -Encoding UTF8
    function Invoke-FixtureManifest {
        $file = [string]$args[-1]
        $code = if ($file.EndsWith('stock.apk')) { '2024705030' } else { '2147483647' }
        'E: manifest (line=2)'
        if ($script:fixtureSplitRequired) { '  A: http://schemas.android.com/apk/res/android:requiredSplitTypes(0x0101064e)="base__abi,base__density"' }
        '  A: package="com.ss.android.ttmd.video" (Raw: "com.ss.android.ttmd.video")'
        '  A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)="47.5.3" (Raw: "47.5.3")'
        '  A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=' + $code
        $global:LASTEXITCODE = 0
    }
    $producerArgs = @{ StockApk = $stockFile; PatchedApk = $patchedFile; AuditBuild = $receiptPath
        Bundle = $fixture.Bundle; GitRoot = $Root; Aapt2 = 'Invoke-FixtureManifest' }
    $script:fixtureSplitRequired = $false
    $produced = New-PineDramaInspectionPlan @producerArgs
    Assert-Profile ($produced.build.status -ceq 'verified' -and -not $produced.full -and -not $produced.eligibleForPublication) 'Bound producer invented runtime qualification.'
    Assert-Profile ($produced.build.sourceApk.sha256 -ceq $producerBuild.sourceApk.sha256 -and
        $produced.build.apk.sha256 -ceq $producerBuild.apk.sha256 -and $produced.build.receiptSha256 -ceq (Get-FileHash $receiptPath -Algorithm SHA256).Hash.ToLowerInvariant()) 'Producer lost actual APK or receipt hashes.'
    $script:fixtureSplitRequired = $true
    Assert-ProfileThrows { New-PineDramaInspectionPlan @producerArgs | Out-Null } 'A split-required base APK was accepted as a standalone source.'
    $script:fixtureSplitRequired = $false
    foreach ($mutation in @(
        { param($r) $r.apk.package = 'com.zhiliaoapp.musically'; $r.sourceApk.package = $r.apk.package },
        { param($r) $r.sourceApk.sha256 = 'e' * 64; $r.sourceApkSha256 = $r.sourceApk.sha256 },
        { param($r) $r.patches = @($r.patches | Where-Object { $_ -cne 'Allow photo comments' }) },
        { param($r) $r.source.commit = 'b' * 40 },
        { param($r) $r.options = @('Allow photo comments|switch=true') },
        { param($r) $r.apk.sha256 = 'a' * 64 }
    )) {
        $bad = Copy-ProfileValue $receipt; & $mutation $bad
        $bad | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $receiptPath -Encoding UTF8
        Assert-ProfileThrows { New-PineDramaInspectionPlan @producerArgs | Out-Null } 'Producer accepted changed package/hash/subset/source/options.'
    }

    $response = 'pine-capabilities-v1 requestAccepted=true targetPackageMatches=true targetVersionMatches=true profileMatches=true actionMatches=true installedApkMatches=true singleBaseApk=true photoPatchRegistered=true photoSwitchOn=false nativePhotoGateShape=true nativeAlbumEntryShape=true photoPermissionsDeclared=true settingsActivityDeclared=true settingsActivityForeground=false wholeAppCoverageComplete=false'
    $result = ConvertFrom-PineDramaInspection -Plan $plan -Lines @('pine-ready=true',$response)
    Assert-Profile ($result.status -ceq 'INCOMPLETE' -and -not $result.full -and -not $result.eligibleForPublication) 'A capability prerequisite became release qualification.'
    Assert-Profile (-not $result.capabilities.photoSwitchOn) 'Inspection invented an enabled preference.'
    foreach ($lines in @(
        @($response,$response), @($response.Replace('wholeAppCoverageComplete=false','wholeAppCoverageComplete=true')),
        @($response.Replace('installedApkMatches=true','installedApkMatches=false')),
        @($response.Replace('photoPatchRegistered=true','photoPatchRegistered=false')),
        @($response.Replace('singleBaseApk=true','singleBaseApk=false')),
        @($response + ' privateText=anything'), @($response + ' photoSwitchOn=true'),
        @($response.Replace('photoSwitchOn=false','photoSwitchOn=unknown')),
        @($response.Replace('settingsActivityForeground=false','')),
        @('pine-inspection-unavailable=true')
    )) { Assert-ProfileThrows { ConvertFrom-PineDramaInspection -Plan $plan -Lines $lines | Out-Null } 'Malformed or incomplete PineDrama response accepted.' }

    $global = Get-VerificationProbeProfile
    $pine = Get-VerificationProbeProfile -Profile $plan.profile
    Assert-Profile ($global.Package -ceq 'com.zhiliaoapp.musically' -and $global.ProbePackage -ceq 'app.hushfeed.verification' -and -not $global.Restricted) 'Global default changed.'
    Assert-Profile ($pine.Package -ceq 'com.ss.android.ttmd.video' -and $pine.ProbePackage -cne $global.ProbePackage -and $pine.LogTag -cne $global.LogTag) 'PineDrama shares global APK or log identity.'
    $template = Join-Path $Root 'tools/verification-probe/AndroidManifest.xml'
    $manifest = Join-Path $directory 'AndroidManifest.xml'
    Write-VerificationProbeManifest -Template $template -Destination $manifest
    Assert-Profile ([IO.File]::ReadAllText($template) -ceq [IO.File]::ReadAllText($manifest)) 'Default manifest was changed.'
    Write-VerificationProbeManifest -Template $template -Destination $manifest -Profile $plan.profile
    [xml]$xml = Get-Content -LiteralPath $manifest -Raw
    $android = 'http://schemas.android.com/apk/res/android'
    Assert-Profile ($xml.manifest.GetAttribute('package') -ceq $pine.ProbePackage) 'PineDrama package was not retargeted.'
    $instruments = @($xml.manifest.SelectNodes('instrumentation'))
    Assert-Profile ($instruments.Count -eq 1 -and $instruments[0].GetAttribute('name',$android) -ceq $pine.Instrumentation -and
        $instruments[0].GetAttribute('targetPackage',$android) -ceq $pine.Package) 'PineDrama has another instrumentation route.'
    Assert-Profile (@($xml.manifest.SelectNodes('uses-permission | application/service')).Count -eq 0) 'PineDrama exposed an unreviewed service.'
    Assert-ProfileThrows { Get-VerificationProbeProfile -Profile 'TIKTOK-WHOLE-APP-V1' | Out-Null } 'A case-folded profile silently selected another package.'
    Assert-ProfileThrows { Get-VerificationProbeProfile -Profile 'pinedrama' | Out-Null } 'An unnamed/incomplete profile was inferred.'

    $script:fakeCalls = [Collections.Generic.List[object]]::new()
    $script:fakeMarker = ''
    function Assert-DeviceLease { param($Lease, [string]$Serial) }
    function Invoke-FakeProfileDevice {
        $Arguments = @($args)
        $script:fakeCalls.Add(@($Arguments))
        $global:LASTEXITCODE = 0
        if ($Arguments[2] -ceq 'shell' -and $Arguments[3] -ceq 'log') { $script:fakeMarker = $Arguments[-1] }
        if ($Arguments[2] -ceq 'logcat') { $script:fakeMarker; $response }
    }
    foreach ($action in @('dump','settings','set','tap','comments','open','follow','send','publish')) {
        Assert-ProfileThrows { Invoke-ProbeAction -Serial 'fixture-serial' -Action $action -Profile $plan.profile -InspectionPlan $plan -Adb Invoke-FakeProfileDevice -Lease $null } 'PineDrama forwarded a UI or social action.'
    }
    Assert-ProfileThrows { Invoke-ProbeAction -Serial fixture-serial -Action capabilities -Profile $plan.profile -InspectionPlan $plan -Extras @('-e','action','set') -Adb Invoke-FakeProfileDevice -Lease $null } 'Caller extras escaped PineDrama restrictions.'
    Assert-ProfileThrows { Invoke-ProbeAction -Serial fixture-serial -Action capabilities -Profile $plan.profile -Adb Invoke-FakeProfileDevice -Lease $null } 'An unbound PineDrama request reached a device.'
    Assert-Profile ($script:fakeCalls.Count -eq 0) 'Rejected PineDrama work ran device commands.'
    $answer = @(Invoke-ProbeAction -Serial fixture-serial -Action capabilities -Profile $plan.profile -InspectionPlan $plan -Adb Invoke-FakeProfileDevice -Lease $null -WaitMs 1)
    Assert-Profile ($script:fakeCalls.Count -eq 3) 'The read-only capability request did not run its marked read.'
    $broadcast = $script:fakeCalls[1] -join ' '
    Assert-Profile ($broadcast -cmatch '-p com\.ss\.android\.ttmd\.video ' -and $broadcast.Contains('-e profile ' + $plan.profile) -and
        $broadcast.Contains('-e apkSha256 ' + $plan.build.apk.sha256) -and $broadcast.Contains('-e versionCode ' + $plan.build.apk.versionCode)) 'The request lost its exact installed identity.'
    Assert-Profile (($script:fakeCalls[0] -join ' ').Contains('HushfeedPineProbe') -and ($script:fakeCalls[2] -join ' ').Contains('HushfeedPineProbe:V')) 'PineDrama read the global log stream.'
    Assert-Profile ((ConvertFrom-PineDramaInspection -Plan $plan -Lines $answer).status -ceq 'INCOMPLETE') 'An actual marked answer was not retained as incomplete.'

    foreach ($name in @('PineDramaProbe.java','PineDramaCapabilities.java')) {
        $source = Get-Content -LiteralPath (Join-Path $Root "tools/verification-probe/src/app/hushfeed/verification/$name") -Raw
        Assert-Profile ($source -cnotmatch 'performClick|dispatchTouchEvent|startActivity|sendKey|\.edit\(|getSharedPreferences|\.save\(|getPlayerManager\(') 'The PineDrama observer gained UI or preference writes.'
    }
    Write-Output "Verification profile contracts passed ($checks assertions). No device actions."
} finally {
    $absolute = [IO.Path]::GetFullPath($directory)
    if (-not $absolute.StartsWith($tempRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($absolute) -notmatch '^hushfeed-verification-profile-[a-f0-9]{32}$') { throw 'Owned profile test cleanup guard failed.' }
    Remove-Item -LiteralPath $absolute -Recurse -Force
}
