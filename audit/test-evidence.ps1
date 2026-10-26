[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'evidence.ps1')
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'scripts/device-lease.ps1')
$script:passed = 0
function Assert-Audit([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:passed++
}
function Copy-Json($Value) { return ($Value | ConvertTo-Json -Depth 30 | ConvertFrom-Json) }
$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$fixture = Join-Path $temporaryRoot ('hushfeed-audit-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
try {
    # Keep qualification fixtures independent of uncommitted changes to the scripts under test.
    $inputRepo = Join-Path $fixture 'input-repo'
    foreach ($relative in @('audit/evidence.ps1', 'audit/run-audit.ps1', 'scripts/release-receipt.ps1', 'scripts/release-qualification.ps1', 'scripts/device-lease.ps1')) {
        $destination = Join-Path $inputRepo $relative
        [void][IO.Directory]::CreateDirectory((Split-Path -Parent $destination))
        [IO.File]::Copy((Join-Path (Split-Path -Parent $PSScriptRoot) $relative), $destination)
    }
    [IO.File]::WriteAllText((Join-Path $inputRepo 'Runtime.java'), 'fixture executable input')
    Invoke-RepoGit -Root $inputRepo -Arguments @('init', '-q') | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot initialize the audit source fixture.' }
    Invoke-RepoGit -Root $inputRepo -Arguments @('add', '--', '.') | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot stage the audit source fixture.' }
    Invoke-RepoGit -Root $inputRepo -Arguments @('-c', 'user.name=SysAdminDoc', '-c', 'user.email=matt_parker@outlook.com', 'commit', '-qm', 'Audit source fixture') | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot commit the audit source fixture.' }
    $inputCommit = [string](Invoke-RepoGit -Root $inputRepo -Arguments @('rev-parse', 'HEAD'))

$entry = [pscustomobject]@{ name = 'Example'; kind = 'runtime'; category = 'Test'; settings = @(); checks = @(
    [pscustomobject]@{ id = 'screen'; what = 'A screen'; auto = $false; steps = @([pscustomobject]@{ shot = 'screen'; review = 'Readable' }) }
) }
$catalog = @($entry)
$check = $entry.checks[0]
$hash = Get-AuditDefinitionHash $entry $check
Assert-Audit ($hash -match '^[a-f0-9]{64}$') 'Definition hash must be SHA-256.'
$escaping = "quotes `" \ apostrophe ' <&> newline`n" + [char]0 + [char]::ConvertFromUtf32(0x1F642)
Assert-Audit ((Get-AuditCanonicalJson $escaping | ConvertFrom-Json) -ceq $escaping) 'Canonical string encoding changed content.'
$cultureBefore = [Threading.Thread]::CurrentThread.CurrentCulture
try {
    $keys = [ordered]@{ z = 1; a = 2; I = 3; J = 4 }
    $keys[[string][char]0x131] = 5
    $englishHash = Get-AuditHash $keys
    [Threading.Thread]::CurrentThread.CurrentCulture = [Globalization.CultureInfo]::GetCultureInfo('tr-TR')
    Assert-Audit ((Get-AuditHash $keys) -ceq $englishHash) 'Culture changed canonical key order.'
} finally { [Threading.Thread]::CurrentThread.CurrentCulture = $cultureBefore }
# Pipeline output arrives wrapped in PSObject; it must hash as the plain value, by content.
$wrapped = @('one.png', 'two.png' | Write-Output)
Assert-Audit ((Get-AuditCanonicalJson $wrapped) -ceq '["one.png","two.png"]') 'A wrapped string serialized as an object.'
Assert-Audit ((Get-AuditHash @('aaa.png' | Write-Output)) -cne (Get-AuditHash @('bbb.png' | Write-Output))) 'Evidence names of equal length shared a hash.'
# Windows PowerShell reads 1.5 from JSON as decimal, PS7 as double. 0.1 + 0.7 needs 16 digits,
# where Windows PowerShell's 'R' and PS7's shortest round trip print different text.
foreach ($number in @('0.1', '0.7999999999999999', '1E+21')) {
    Assert-Audit ((Get-AuditCanonicalJson ([decimal]::Parse($number, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture))) -ceq
        (Get-AuditCanonicalJson ([double]::Parse($number, [Globalization.CultureInfo]::InvariantCulture)))) "Decimal and double printed $number differently."
}
Assert-Audit ((Get-AuditCanonicalJson (0.1 + 0.7)) -ceq '0.79999999999999993') 'A double lost its fixed 17-digit canonical form.'
$reordered = [pscustomobject]@{ checks = $entry.checks; settings = @(); category = 'Test'; kind = 'runtime'; name = 'Example' }
Assert-Audit ($hash -eq (Get-AuditDefinitionHash $reordered $check)) 'Object key order changed definition identity.'
$changed = Copy-Json $check
$changed.steps[0].review = 'Different criterion'
Assert-Audit ($hash -ne (Get-AuditDefinitionHash $entry $changed)) 'Changed review criterion retained identity.'
Assert-Audit (@(Get-AuditCatalogProblems $catalog).Count -eq 0) 'Valid catalog rejected.'
$duplicate = Copy-Json $entry
$duplicate.checks += Copy-Json $check
Assert-Audit (@(Get-AuditCatalogProblems @($duplicate)).Count -gt 0) 'Duplicate check ID accepted.'
$blank = Copy-Json $entry
$blank.checks[0].id = ''
Assert-Audit (@(Get-AuditCatalogProblems @($blank)).Count -gt 0) 'Blank check ID accepted.'
$apkHash = 'a' * 64
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'scripts/release-receipt.ps1')
$source = [pscustomobject]@{ commit = $inputCommit; clean = $true; startFingerprint = 'c' * 64; endFingerprint = 'c' * 64 }
$build = [pscustomobject]@{ status = 'verified'; receiptSha256 = 'd' * 64; source = $source
    executionInputs = Get-AuditExecutableInputs $inputRepo $source.commit
    nativeBuildSchema = 2
    apk = @{ package = 'com.zhiliaoapp.musically'; versionName = '47.1.4'; versionCode = '1'; sha256 = $apkHash }
    sourceApk = @{ package = 'com.zhiliaoapp.musically'; versionName = '47.1.4'; versionCode = '1'; sha256 = 'f' * 64 }
    sourceApkSha256 = 'f' * 64; bundle = @{ sha256 = 'b' * 64 }
    patches = @('Example'); options = @(); patchReportSha256 = 'e' * 64 }
$app = [pscustomobject]@{ package = 'com.zhiliaoapp.musically'; version = '47.1.4'; versionCode = '1' }
[IO.File]::WriteAllText((Join-Path $fixture 'screen.png'), 'original evidence')
$result = [ordered]@{ id = 'screen'; auto = $false; status = 'review'; definitionHash = $hash; evidence = @('screen.png'); review = @(@{ shot = 'screen.png'; criteria = 'Readable' }); build = $build; apkSha256 = $apkHash; installedApkSha256 = $apkHash }
Set-AuditCheckReceipt $result $fixture
$result = Copy-Json $result
$run = [pscustomobject]@{ app = $app; apk = @{ sha256 = $apkHash }; patches = @([pscustomobject]@{ name = 'Example'; checks = @($result) }) }
$report = Get-AuditResultValidation $catalog $run $fixture $inputRepo
Assert-Audit ($report.problems.Count -eq 0 -and $report.currentChecks -eq 1) 'Current bound result rejected.'


# Re-seal each variant so selection and manifest validation, not a stale receipt hash, decides it.
$missingPatch = Copy-Json $run
$missingPatch.patches[0].checks[0].build.patches = @('Other patch')
Set-AuditCheckReceipt $missingPatch.patches[0].checks[0] $fixture
Assert-Audit ((Get-AuditResultValidation $catalog $missingPatch $fixture $inputRepo).currentChecks -eq 0) 'An excluded patch qualified its positive check.'
$negativeEntry = Copy-Json $entry
$negativeCheck = $negativeEntry.checks[0]
$negativeCheck | Add-Member -NotePropertyName patchSelection -NotePropertyValue 'absent'
$negative = Copy-Json $missingPatch
$negative.patches[0].checks[0].definitionHash = Get-AuditDefinitionHash $negativeEntry $negativeCheck
Set-AuditCheckReceipt $negative.patches[0].checks[0] $fixture
Assert-Audit ((Get-AuditResultValidation @($negativeEntry) $negative $fixture $inputRepo).currentChecks -eq 1) 'An explicit negative selection check was refused.'
$negative.patches[0].checks[0].build.patches = @('Example')
Set-AuditCheckReceipt $negative.patches[0].checks[0] $fixture
Assert-Audit ((Get-AuditResultValidation @($negativeEntry) $negative $fixture $inputRepo).currentChecks -eq 0) 'A selected patch qualified an absence check.'
$badPolicy = Copy-Json $negativeEntry
$badPolicy.checks[0].patchSelection = 'unknown'
Assert-Audit (@(Get-AuditCatalogProblems @($badPolicy)).Count -gt 0) 'Unknown selection policy accepted.'
$nativeVariants = [ordered]@{
    missingSchema = { param($r) $r.patches[0].checks[0].build.PSObject.Properties.Remove('nativeBuildSchema') }
    malformedSchema = { param($r) $r.patches[0].checks[0].build.nativeBuildSchema = $true }
    historicalBuild = { param($r) $r.patches[0].checks[0].build.status = 'historical' }
    wrongPackage = { param($r) $r.patches[0].checks[0].build.apk.package = 'com.ss.android.ttmd.video' }
    wrongVersion = { param($r) $r.patches[0].checks[0].build.apk.versionName = '47.5.3' }
    wrongCode = { param($r) $r.patches[0].checks[0].build.apk.versionCode = '2' }
    wrongApkHash = { param($r) $r.patches[0].checks[0].build.apk.sha256 = '0' * 64 }
    wrongSourceHash = { param($r) $r.patches[0].checks[0].build.sourceApk.sha256 = '0' * 64 }
    missingBundle = { param($r) $r.patches[0].checks[0].build.bundle.sha256 = '' }
    missingReport = { param($r) $r.patches[0].checks[0].build.patchReportSha256 = '' }
    missingOptions = { param($r) $r.patches[0].checks[0].build.PSObject.Properties.Remove('options') }
    duplicatePatch = { param($r) $r.patches[0].checks[0].build.patches = @('Example','Example') }
    unrelatedOption = { param($r) $r.patches[0].checks[0].build.options = @('Other patch|enabled=true') }
}
foreach ($variant in $nativeVariants.Keys) {
    $mutated = Copy-Json $run
    & $nativeVariants[$variant] $mutated
    Set-AuditCheckReceipt $mutated.patches[0].checks[0] $fixture
    Assert-Audit ((Get-AuditResultValidation $catalog $mutated $fixture $inputRepo).currentChecks -eq 0) "Invalid native build facts remained current: $variant"
}
$maxCode = Copy-Json $run
$maxCode.app.versionCode = '2147483647'
$maxCode.patches[0].checks[0].build.apk.versionCode = '2147483647'
Set-AuditCheckReceipt $maxCode.patches[0].checks[0] $fixture
Assert-Audit ((Get-AuditResultValidation $catalog $maxCode $fixture $inputRepo).currentChecks -eq 1) 'A deliberately changed patched version code was equated with its stock code.'
$unreviewedPackage = Copy-Json $run
$unreviewedPackage.app.package = 'com.ss.android.ttmd.video'
$unreviewedPackage.patches[0].checks[0].build.sourceApk.package = $unreviewedPackage.app.package
$unreviewedPackage.patches[0].checks[0].build.apk.package = $unreviewedPackage.app.package
Set-AuditCheckReceipt $unreviewedPackage.patches[0].checks[0] $fixture
Assert-Audit ((Get-AuditResultValidation $catalog $unreviewedPackage $fixture $inputRepo).currentChecks -eq 0) 'The global profile qualified an unreviewed PineDrama build.'
foreach ($oldStatus in @('fail','review','inconclusive')) {
    $historicalNative = Copy-Json $run
    $historicalNative.patches[0].checks[0].status = $oldStatus
    $historicalNative.patches[0].checks[0].build.PSObject.Properties.Remove('nativeBuildSchema')
    $beforeNative = Get-AuditHash $historicalNative
    Assert-Audit ((Get-AuditResultValidation $catalog $historicalNative $fixture $inputRepo).currentChecks -eq 0) "Legacy native facts qualified $oldStatus."
    Assert-Audit ((Get-AuditHash $historicalNative) -ceq $beforeNative) 'Invalidating native identity rewrote the recorded verdict.'
}

$runBeforeDirt = Get-AuditHash $run
[IO.File]::WriteAllText((Join-Path $inputRepo 'Runtime.java'), 'dirty runtime')
$dirtyReport = Get-AuditResultValidation $catalog $run $fixture $inputRepo
Assert-Audit ($dirtyReport.state -eq 'historical-or-incomplete' -and $dirtyReport.currentChecks -eq 0) 'Dirty runtime qualified read-only current results.'
Assert-Audit ((Get-AuditHash $run) -ceq $runBeforeDirt) 'Dirty input validation rewrote the stored verdict.'
[IO.File]::WriteAllText((Join-Path $inputRepo 'Runtime.java'), 'fixture executable input')
$untrackedInput = Join-Path $inputRepo 'untracked-generator.ps1'
[IO.File]::WriteAllText($untrackedInput, 'untracked input')
Assert-Audit ((Get-AuditResultValidation $catalog $run $fixture $inputRepo).currentChecks -eq 0) 'An untracked unknown input qualified read-only current results.'
Remove-Item -LiteralPath $untrackedInput -Force
$dirtyDoc = Join-Path $inputRepo 'README.md'
[IO.File]::WriteAllText($dirtyDoc, 'uncommitted documentation')
Assert-Audit ((Get-AuditResultValidation $catalog $run $fixture $inputRepo).currentChecks -eq 1) 'Documentation-only dirt made read-only results stale.'
Remove-Item -LiteralPath $dirtyDoc -Force

foreach ($historicalStatus in @('fail', 'review', 'inconclusive')) {
    $legacy = Copy-Json $run
    $legacyCheck = $legacy.patches[0].checks[0]
    $legacyCheck.status = $historicalStatus
    $legacyCheck.build.PSObject.Properties.Remove('executionInputs')
    $beforeLegacy = Get-AuditHash $legacy
    $legacyReport = Get-AuditResultValidation $catalog $legacy $fixture $inputRepo
    Assert-Audit ($legacyReport.state -eq 'historical-or-incomplete' -and $legacyReport.currentChecks -eq 0) "Legacy $historicalStatus evidence was qualified without executable inputs."
    Assert-Audit ((Get-AuditHash $legacy) -ceq $beforeLegacy) "Invalidation rewrote historical $historicalStatus evidence."
}

foreach ($badCount in @('not-a-count', '99999999999999999999999')) {
    $malformedInputs = Copy-Json $run
    $malformedInputs.patches[0].checks[0].build.executionInputs.fileCount = $badCount
    Assert-Audit ((Get-AuditResultValidation $catalog $malformedInputs $fixture $inputRepo).currentChecks -eq 0) "Malformed input count $badCount escaped fail-closed handling."
}

$changedInputs = Copy-Json $run
$changedInputs.patches[0].checks[0].build.executionInputs.sha256 = '0' * 64
Assert-Audit ((Get-AuditResultValidation $catalog $changedInputs $fixture $inputRepo).currentChecks -eq 0) 'A changed per-check executable input digest remained current.'

$missing = Copy-Json $run
$missing.patches[0].checks = @()
Assert-Audit ((Get-AuditResultValidation $catalog $missing $fixture $inputRepo).missing.Count -eq 1) 'Missing check not detected.'
$twice = Copy-Json $run
$twice.patches[0].checks += Copy-Json $result
Assert-Audit ((Get-AuditResultValidation $catalog $twice $fixture $inputRepo).duplicates.Count -eq 1) 'Duplicate result not detected.'
$mixed = Copy-Json $run
$mixed.patches += [pscustomobject]@{ name = 'Other'; checks = @([pscustomobject]@{ id = 'other'; status = 'pass'; apkSha256 = 'e' * 64 }) }
$mixedReport = Get-AuditResultValidation $catalog $mixed $fixture $inputRepo
Assert-Audit ($mixedReport.mixedBuild -and @($mixedReport.problems | Where-Object { $_ -like 'mixed-build:*' }).Count -eq 1) 'Results from two APKs were not reported as a problem.'
$stale = Copy-Json $run
$stale.patches[0].checks[0].definitionHash = 'f' * 64
Assert-Audit ((Get-AuditResultValidation $catalog $stale $fixture $inputRepo).changed.Count -eq 1) 'Changed definition counted current.'
$old = Copy-Json $run
$old.patches[0].checks[0].apkSha256 = '0' * 64
Assert-Audit ((Get-AuditResultValidation $catalog $old $fixture $inputRepo).currentChecks -eq 0) 'Mixed APK counted current.'
$unbound = Copy-Json $run
$unbound.patches[0].checks[0].installedApkSha256 = ''
Assert-Audit ((Get-AuditResultValidation $catalog $unbound $fixture $inputRepo).currentChecks -eq 0) 'Unbound installed APK counted current.'
$removed = Copy-Json $run
$removed.patches[0].checks[0].id = 'removed'
Assert-Audit ((Get-AuditResultValidation $catalog $removed $fixture $inputRepo).removed.Count -eq 1) 'Removed check not detected.'
$unknown = Copy-Json $run
$unknown.patches[0].checks[0].status = 'made-up'
Assert-Audit ((Get-AuditResultValidation $catalog $unknown $fixture $inputRepo).unknown.Count -eq 1) 'Unknown status accepted.'
$verdict = [pscustomobject]@{ 'Example/screen' = [pscustomobject]@{ status = 'pass'; note = 'Readable'; receiptId = $result.receiptId } }
$verdict = ConvertFrom-AuditVerdictJson ($verdict | ConvertTo-Json -Depth 8)
Assert-Audit ($verdict.'Example/screen'.status -ceq 'pass') 'Unique verdict JSON was rejected.'
foreach ($invalidJson in @(
    '{"Example/screen":{"status":"inconclusive"},"Example/screen":{"status":"pass"}}',
    '{"Example/screen":{},"Example\u002fscreen":{}}',
    '{"Example/screen":{"status":"inconclusive","status":"pass"}}',
    '{"Example/screen":{"status":"fail","\u0073tatus":"pass"}}',
    '{"Example/screen":{"status":"fail","Status":"pass"}}',
    '{"Example/screen":{status:"fail",status:"pass"}}',
    '{"Example/screen":{status:"pass"}}',
    "{`"Example/screen`":{'status':'fail','status':'pass'}}",
    '{"Example/screen":{"note":"ok"},/* hidden duplicate */"Example/screen":{}}',
    '[{"Example/screen":{}}]'
)) {
    $rejected = $false
    try { [void](ConvertFrom-AuditVerdictJson $invalidJson) } catch { $rejected = $true }
    Assert-Audit $rejected 'Ambiguous verdict JSON was accepted.'
}
$tokenCases = ConvertFrom-AuditVerdictJson '{"one":{"note":"{\"status\":1}, /* text */","extra":[{"key":1},{"key":2}]},"two":{"note":"two"}}'
Assert-Audit ($tokenCases.one.extra.Count -eq 2 -and $tokenCases.two.note -ceq 'two') 'Independent objects or quoted punctuation were treated as duplicates.'
$dateKeys = ConvertFrom-AuditVerdictJson '{"Example/screen":{"extra":{"2026-10-16T00:00:00Z":1,"2026-10-16T00:00:00.000Z":2}}}'
Assert-Audit (@($dateKeys.'Example/screen'.extra.PSObject.Properties).Count -eq 2) 'Timestamp-shaped property names were converted to DateTime values.'
Set-AuditVerdicts $catalog $run $verdict $fixture $inputRepo
Assert-Audit ($result.status -eq 'pass') 'Matching review verdict did not apply.'
Assert-Audit ((Get-AuditResultValidation $catalog $run $fixture $inputRepo).currentPass -eq 1) 'Imported verdict lost its execution binding.'
$forged = Copy-Json $run
$forged.patches[0].checks[0].verdict.receiptId = '0' * 64
Assert-Audit ((Get-AuditResultValidation $catalog $forged $fixture $inputRepo).currentPass -eq 0) 'A verdict for another execution remained current.'
$failed = [ordered]@{ status = 'fail'; failures = @('Failed expectation'); auto = $true; definitionHash = $hash; evidence = @(); review = @(); build = $build; apkSha256 = $apkHash; installedApkSha256 = $apkHash }
Set-AuditCheckReceipt $failed $fixture
$failed.status = 'pass'
Assert-Audit (-not (Test-AuditCheckBinding $entry $check $failed $apkHash $fixture $null $inputRepo $app)) 'Changing a failed status reused its receipt.'
$failed.status = 'fail'; $failed.failures = @()
Assert-Audit (-not (Test-AuditCheckBinding $entry $check $failed $apkHash $fixture $null $inputRepo $app)) 'Removing execution failures reused its receipt.'
foreach ($case in 'wrongReceipt', 'automatic', 'staleDefinition', 'invalidStatus', 'missing', 'alreadyDecided', 'changedPayload', 'changedReview', 'changedFile', 'missingFile', 'wrongDirectory') {
    $attempt = Copy-Json $run
    $attempt.patches[0].checks[0].status = 'review'
    $candidate = Copy-Json $verdict
    switch ($case) {
        wrongReceipt { $candidate.'Example/screen'.receiptId = '0' * 64 }
        automatic { $attempt.patches[0].checks[0].auto = $true }
        staleDefinition { $attempt.patches[0].checks[0].definitionHash = '0' * 64 }
        invalidStatus { $candidate.'Example/screen'.status = 'inconclusive' }
        missing { $attempt.patches[0].checks = @() }
        alreadyDecided { $attempt.patches[0].checks[0].status = 'pass' }
        changedPayload { $attempt.patches[0].checks[0].receipt.execution = '0' * 32 }
        changedReview { $attempt.patches[0].checks[0].review[0].criteria = 'Different evidence' }
        changedFile { [IO.File]::WriteAllText((Join-Path $fixture 'screen.png'), 'overwritten by a later run') }
        missingFile { Remove-Item -LiteralPath (Join-Path $fixture 'screen.png') }
    }
    $before = $attempt | ConvertTo-Json -Depth 30 -Compress
    $refused = $false
    $evidenceDirectory = if ($case -eq 'wrongDirectory') { Join-Path $fixture 'absent' } else { $fixture }
    try { Set-AuditVerdicts $catalog $attempt $candidate $evidenceDirectory $inputRepo } catch { $refused = $true }
    Assert-Audit $refused "Unsafe verdict accepted: $case"
    Assert-Audit (($attempt | ConvertTo-Json -Depth 30 -Compress) -eq $before) "Rejected verdict mutated results: $case"
    [IO.File]::WriteAllText((Join-Path $fixture 'screen.png'), 'original evidence')
}
Assert-Audit ((Get-AuditResultValidation $catalog $run $null $inputRepo).currentChecks -eq 0) 'Missing evidence directory counted current.'
$reorderedStep = Copy-Json $check
$reorderedStep.steps = @([pscustomobject]@{ review = 'Readable'; shot = 'screen' })
Assert-Audit ($hash -ne (Get-AuditDefinitionHash $entry $reorderedStep)) 'Changing the step dispatcher retained definition identity.'
# Exercise the actual tap dispatcher without a device. Descriptive tap text must be saved before hashing.
$runner = Join-Path $PSScriptRoot 'run-audit.ps1'
$parseErrors = $null
$syntax = [Management.Automation.Language.Parser]::ParseFile($runner, [ref]$null, [ref]$parseErrors)
Assert-Audit (@($parseErrors).Count -eq 0) 'Runner failed to parse.'
foreach ($name in 'Invoke-Check', 'Test-Pattern', 'Get-LogAfter', 'Invoke-AuditProbe', 'ConvertTo-DeviceQuoted', 'Save-Evidence', 'Save-Shot', 'Start-ScreenRecord', 'Stop-ScreenRecord', 'Clear-ScreenRecord', 'Grant-AuditLease', 'Revoke-AuditLease', 'Test-AuditLeaseOwnership', 'Invoke-AuditAdb') {
    $definition = $syntax.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true)
    . ([scriptblock]::Create($definition.Extent.Text))
}
$selectionTouched = $false
function Invoke-AuditProbe { $script:selectionTouched = $true; throw 'A rejected check touched the app.' }
$guardCheck = [pscustomobject]@{ id = 'selection'; auto = $true; steps = @([pscustomobject]@{ probe = 'report'; expect = 'report' }) }
$guardBuild = Copy-Json $build
$guardBuild.patches = @('Other patch')
$guardRestarted = $false
$guardResult = Invoke-Check $entry $guardCheck @{} ([ref]$guardRestarted) $guardBuild
Assert-Audit ($guardResult.status -eq 'error' -and -not $selectionTouched -and $guardResult.evidence.Count -eq 0) 'A selection-mismatched check ran its steps.'
function Write-Marker { [pscustomobject]@{ id = 'fixture'; since = 'fixture' } }
function Invoke-DumpTap { 'tapped fixture at 10,20' }
function Start-Sleep { param($Milliseconds) }
$deviceUp = $true
$OutDir = $fixture
foreach ($kind in 'tap', 'longpress') {
    $step = [ordered]@{}; $step[$kind] = @{ text = 'Test control' }
    $tapCheck = [pscustomobject]@{ id = $kind; auto = $true; steps = @([pscustomobject]$step) }
    $restarted = $false
    $tapResult = Invoke-Check $entry $tapCheck @{} ([ref]$restarted)
    Assert-Audit ($tapResult.status -eq 'pass') "$kind step failed."
    Assert-Audit ((Get-Content -LiteralPath (Join-Path $fixture $tapResult.evidence[0]) -Raw).Trim() -eq 'tapped fixture at 10,20') "$kind did not save its textual evidence."
    Set-AuditCheckReceipt $tapResult $fixture
    Assert-Audit ($tapResult.receipt.files.Count -eq 1) "$kind could not bind file evidence."
}
# Probe prerequisites and report transport are availability checks, not feature verdicts.
function Restart-TikTok { }
function Save-Shot([string]$Name) { 'fixture-probe-shot.png' }
function Invoke-AuditProbe { param($Action, $Extras, $WaitMs, $Until); return $script:fixtureProbeReply }
$probeFixtures = @(
    @{ id = 'missing-video'; action = 'videoinfo'; required = $true; status = 'inconclusive'; text = "failed videoinfo`njava.lang.IllegalStateException: no current video" },
    @{ id = 'missing-video-crlf'; action = 'videoinfo'; required = $true; status = 'inconclusive'; text = "failed videoinfo`r`njava.lang.IllegalStateException: no current video`r`nat fixture" },
    @{ id = 'missing-video-unguarded'; action = 'videoinfo'; required = $false; status = 'fail'; text = "failed videoinfo`njava.lang.IllegalStateException: no current video" },
    @{ id = 'broken-reader'; action = 'videoinfo'; required = $true; status = 'fail'; text = "failed videoinfo`njava.lang.NoSuchMethodException: getDuration" },
    @{ id = 'other-failure'; action = 'comments'; required = $true; status = 'fail'; text = "failed comments`njava.lang.IllegalStateException: no current video" },
    @{ id = 'different-state'; action = 'videoinfo'; required = $true; status = 'fail'; text = "failed videoinfo`njava.lang.IllegalStateException: no current video decoder" },
    @{ id = 'observed-mismatch'; action = 'videoinfo'; required = $false; status = 'fail'; text = 'ok videoinfo wrong' },
    @{ id = 'observed-match'; action = 'videoinfo'; required = $false; status = 'pass'; text = 'ok videoinfo ready' }
)
foreach ($case in $probeFixtures) {
    $script:fixtureProbeReply = $case.text
    $probeStep = [ordered]@{ probe = $case.action; expect = '^ok videoinfo ready$'; precondition = $case.required }
    $probeCheck = [pscustomobject]@{ id = $case.id; auto = $true; steps = @([pscustomobject]$probeStep) }
    $restarted = $false
    $probeResult = Invoke-Check $entry $probeCheck @{} ([ref]$restarted)
    Assert-Audit ($probeResult.status -eq $case.status) "Probe prerequisite outcome changed for $($case.id): $($probeResult.status)."
    $savedAnswer = Get-Content -LiteralPath (Join-Path $OutDir $probeResult.evidence[0]) -Raw
    Assert-Audit ($savedAnswer.Trim() -ceq $case.text.Trim()) "Probe failure evidence was lost for $($case.id)."
}
$collectorDefinition = $syntax.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-AuditProbe' }, $true)
. ([scriptblock]::Create($collectorDefinition.Extent.Text))
$script:fixtureCollector = ${function:Invoke-AuditProbe}
# End this fixture read immediately. The canonical completion record is still mandatory.
function Invoke-AuditProbe { param($Action, $Extras, $WaitMs, $Until); & $script:fixtureCollector $Action $Extras $WaitMs '.' }
function Invoke-DeviceShell { param($Command); return $script:fixtureLogLines }
$package = 'fixture.package'
$prefix = "MORPHE DIAGNOSTIC REPORT`n`n[BUILD DETAILS]`nlabel: caf$([char]0x00e9)$([char]::ConvertFromUtf32(0x1f642)) "
$switches = "`nregistered_switches_count: 2`nregistered_switches: other,copyCommentsWithoutUsername`n"
$without = "`nregistered_switches_count: 1`nregistered_switches: other`n"
$characters = ($prefix + $switches).Length
$reportFixtures = @(
    @{ id = 'complete'; pieces = @($prefix, $switches); indexes = @(0, 1); chars = $characters; count = 2; done = $true; status = 'pass' },
    @{ id = 'complete-missing-switch'; pieces = @($prefix, $without); indexes = @(0, 1); chars = ($prefix + $without).Length; count = 2; done = $true; status = 'fail' },
    @{ id = 'missing-completion'; pieces = @($prefix, $switches); indexes = @(0, 1); chars = $characters; count = 2; done = $false; status = 'inconclusive' },
    @{ id = 'dropped-piece'; pieces = @($prefix, $switches); indexes = @(0, 2); chars = $characters; count = 3; done = $true; status = 'inconclusive' },
    @{ id = 'truncated-piece'; pieces = @($prefix, $switches.Substring(0, $switches.Length - 20)); indexes = @(0, 1); chars = $characters; count = 2; done = $true; status = 'inconclusive' },
    @{ id = 'repeated-piece'; pieces = @($prefix, $switches); indexes = @(0, 0); chars = $characters; count = 2; done = $true; status = 'inconclusive' },
    @{ id = 'out-of-order'; pieces = @($prefix, $switches); indexes = @(1, 0); chars = $characters; count = 2; done = $true; status = 'inconclusive' },
    @{ id = 'invalid-count'; pieces = @($prefix, $switches); indexes = @(0, 1); chars = '9223372036854775808'; count = 2; done = $true; status = 'inconclusive' },
    @{ id = 'native-report-failure'; failed = $true; status = 'fail' }
)
foreach ($case in $reportFixtures) {
    $script:fixtureLogLines = @('I/HushfeedAudit: fixture')
    if ($case.failed) {
        $script:fixtureLogLines += 'I/HushfeedProbe: failed report', 'java.lang.IllegalStateException: fixture export failure'
    } else {
        for ($piece = 0; $piece -lt $case.pieces.Count; $piece++) {
            $rows = $case.pieces[$piece] -split "`n"
            $script:fixtureLogLines += "I/HushfeedProbe: report[$($case.indexes[$piece])] $($rows[0])"
            if ($rows.Count -gt 1) {
                foreach ($row in $rows[1..($rows.Count - 1)]) {
                    $script:fixtureLogLines += if ($row) { $row } else { 'I/HushfeedProbe:' }
                }
            }
        }
        if ($case.done) { $script:fixtureLogLines += "I/HushfeedProbe: ok report $($case.chars) chars in $($case.count) pieces" }
    }
    $reportCheck = [pscustomobject]@{ id = $case.id; auto = $true; steps = @([pscustomobject]@{
        probe = 'report'; expect = 'registered_switches:[^\n]*\bcopyCommentsWithoutUsername\b'
    }) }
    $restarted = $false
    $reportResult = Invoke-Check $entry $reportCheck @{} ([ref]$restarted)
    Assert-Audit ($reportResult.status -eq $case.status) "Report transport outcome changed for $($case.id): $($reportResult.status)."
    $savedReport = Get-Content -LiteralPath (Join-Path $OutDir $reportResult.evidence[0]) -Raw
    Assert-Audit ($savedReport -match 'MORPHE DIAGNOSTIC REPORT|failed report') "Partial report evidence was lost for $($case.id)."
    if ($case.id -eq 'complete') {
        Assert-Audit ($savedReport.Contains($prefix + $switches.TrimEnd())) 'Report assembly lost Unicode, blank rows or a trailing piece-boundary space.'
    }
}
# logcat suppresses an empty final line in a message; it cannot preserve a terminal LF.
$legacyBody = "MORPHE DIAGNOSTIC REPORT" + [char]10 + 'registered_switches: copyCommentsWithoutUsername' + [char]10
$script:fixtureLogLines = @('I/HushfeedAudit: fixture', 'I/HushfeedProbe: report[0] MORPHE DIAGNOSTIC REPORT', 'registered_switches: copyCommentsWithoutUsername',
    "I/HushfeedProbe: ok report $($legacyBody.Length) chars in 1 pieces")
$legacyDroppedLf = & $script:fixtureCollector 'report' $null 0 '.'
Assert-Audit (Test-Pattern $legacyDroppedLf '^unavailable report:') 'A legacy report with a suppressed terminal LF was repaired without evidence.'

function New-EncodedReportLines([string]$Text, [string]$Fault = '') {
    [byte[]]$bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    if ($Fault -eq 'invalid-utf8') { $bytes = [byte[]]@(0xc3, 0x28) }
    $wire = [Convert]::ToBase64String($bytes)
    if ($Fault -eq 'invalid-base64') { $wire = '!' + $wire.Substring(1) }
    if ($Fault -eq 'noncanonical-base64') { $wire = $wire.Insert(4, ' ') }
    if ($Fault -eq 'truncated-piece') { $wire = $wire.Substring(0, $wire.Length - 4) }
    $chunks = New-Object System.Collections.Generic.List[string]
    for ($at = 0; $at -lt $wire.Length; $at += 3000) { $chunks.Add($wire.Substring($at, [Math]::Min(3000, $wire.Length - $at))) }
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $digest = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
    if ($Fault -eq 'bad-hash') { $digest = '0' * 64 }
    $length = $bytes.Length; $characters = $Text.Length
    if ($Fault -eq 'bad-bytes') { $length++ }
    if ($Fault -eq 'bad-characters') { $characters++ }
    if ($Fault -eq 'oversized-bytes') { $length = 4194305 }
    if ($Fault -eq 'oversized-characters') { $characters = 1048577 }
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('I/HushfeedAudit: fixture')
    for ($piece = 0; $piece -lt $chunks.Count; $piece++) {
        if ($Fault -eq 'dropped-piece' -and $piece -eq 1) { continue }
        $index = $piece
        if ($Fault -eq 'out-of-order' -and $piece -lt 2) { $index = 1 - $piece }
        if ($Fault -eq 'repeated-piece' -and $piece -eq 1) { $index = 0 }
        $kind = if ($Fault -eq 'mixed-transport' -and $piece -eq 0) { 'report' } else { 'report64' }
        $lines.Add("I/HushfeedProbe: $kind[$index] $($chunks[$piece])")
    }
    if ($Fault -ne 'missing-completion') {
        $completion = "I/HushfeedProbe: ok report $characters chars in $($chunks.Count) pieces encoding=base64-utf8 bytes=$length sha256=$digest"
        if ($Fault -eq 'legacy-completion') { $completion = "I/HushfeedProbe: ok report $characters chars in $($chunks.Count) pieces" }
        $lines.Add($completion)
        if ($Fault -eq 'duplicate-completion') { $lines.Add($completion) }
    }
    return @($lines)
}
$longUnicodeReport = $prefix + (($([char]0x754c) + [char]::ConvertFromUtf32(0x1f642)) * 2000) + $switches
foreach ($body in @($prefix + $switches, $longUnicodeReport, '')) {
    $script:fixtureLogLines = @(New-EncodedReportLines $body)
    $decoded = & $script:fixtureCollector 'report' $null 0 '.'
    Assert-Audit (-not (Test-Pattern $decoded '^unavailable report:')) 'A complete encoded report was unavailable.'
    $separator = $decoded.IndexOf([string][char]10)
    Assert-Audit ($separator -ge 0 -and $decoded.Substring($separator + 1) -ceq $body) 'Encoded report lost Unicode, whitespace or terminal LF.'
    foreach ($line in $script:fixtureLogLines) {
        Assert-Audit ([Text.Encoding]::UTF8.GetByteCount($line) -lt 3900) 'Report frame can exceed the native logcat message byte limit.'
    }
}
foreach ($fault in @('bad-bytes', 'bad-characters', 'bad-hash', 'invalid-utf8', 'invalid-base64', 'noncanonical-base64',
        'truncated-piece', 'dropped-piece', 'out-of-order', 'repeated-piece', 'mixed-transport', 'legacy-completion',
        'missing-completion', 'duplicate-completion', 'oversized-bytes', 'oversized-characters')) {
    $script:fixtureLogLines = @(New-EncodedReportLines $longUnicodeReport $fault)
    $damaged = & $script:fixtureCollector 'report' $null 0 '.'
    Assert-Audit (Test-Pattern $damaged '^unavailable report:') "Damaged encoded report was accepted: $fault."
    Assert-Audit ($damaged.Length -gt 60) "Damaged report evidence was discarded: $fault."
}
Remove-Item -LiteralPath 'Function:\New-EncodedReportLines'
$regionCatalog = Get-Content -LiteralPath (Join-Path (Split-Path -Parent $runner) 'catalog.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$regionCheck = @($regionCatalog | Where-Object name -eq 'Show commenter handle and region')[0].checks | Where-Object id -eq 'sheet-text'
$regionPattern = @($regionCheck.steps | Where-Object log)[0].log
$seedModes = @($regionCheck.steps | Where-Object probe -eq 'seed-commenter-region' | ForEach-Object { $_.extras.region })
Assert-Audit (($seedModes -join ',') -ceq 'clear,AQ,clear') 'The seeded handle fixture can be shadowed by an old UID country or survive the check.'
foreach ($regionSource in 'uid', 'handle') {
    Assert-Audit (Test-Pattern "Commenter region: remembered by $regionSource answered, showing AQ" $regionPattern) "The seeded $regionSource fallback no longer matches its audit check."
}
foreach ($line in @('Commenter region: remembered by handle answered, showing aq',
        'Commenter region: remembered by handle answered, showing US',
        'Commenter region: account region answered, showing AQ',
        "Commenter region: the comment's account carried none, showing AQ")) {
    Assert-Audit (-not (Test-Pattern $line $regionPattern)) 'A non-seeded or stale commenter-region log passed.'
}
foreach ($name in 'Save-Shot', 'Invoke-AuditProbe') {
    $definition = $syntax.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true)
    . ([scriptblock]::Create($definition.Extent.Text))
}
Remove-Item -LiteralPath 'Function:\Invoke-DeviceShell', 'Function:\Restart-TikTok'
$Serial = 'fixture-device'; $LeaseDir = $fixture; $ChatIdentity = 'audit-fixture'; $LeaseMinutes = 30
$script:auditLease = $null
Grant-AuditLease
$leaseFile = $script:auditLease.Path
$leaseToken = $script:auditLease.OwnershipToken
Grant-AuditLease
Assert-Audit ((Read-DeviceLeaseRecord $script:auditLease).ownershipToken -eq $leaseToken) 'Lease renewal lost ownership.'
$script:deviceCommands = 0
function Invoke-FixtureAdb { $script:deviceCommands++ }
$adbExe = 'Invoke-FixtureAdb'
Invoke-AuditAdb shell 'fixture'
Assert-Audit ($script:deviceCommands -eq 1) 'Owned device command was refused.'
$Serial = 'different-device'
$rejected = $false
try { Invoke-AuditAdb shell 'fixture' } catch { $rejected = $true }
Assert-Audit ($rejected -and $script:deviceCommands -eq 1) 'Audit command accepted a lease for a different serial.'
$rejected = $false
try { Start-ScreenRecord 1 } catch { $rejected = $_.Exception.Message -like '*ownership or serial*' }
Assert-Audit $rejected 'Recorder started without the exact serial lease.'
$Serial = 'fixture-device'
Revoke-AuditLease
Assert-Audit (-not (Test-Path -LiteralPath $leaseFile)) 'Owned lease was not released.'
Grant-AuditLease
# A held Windows handle prevents path deletion. Close that handle to simulate lost ownership,
# then remove the fixture file; renewal must still reject the previously acquired context.
$script:auditLease.Stream.Dispose()
Remove-Item -LiteralPath $leaseFile -Force
$rejected = $false
try { Grant-AuditLease } catch { $rejected = $true }
Assert-Audit ($rejected -and -not (Test-Path -LiteralPath $leaseFile)) 'A missing acquired lease was silently recreated.'
Revoke-AuditLease
$script:auditLease = $null # The stopped fixture is explicitly recovered for the next case.
foreach ($expiry in [DateTime]::UtcNow.AddMinutes(10), [DateTime]::UtcNow.AddMinutes(-10)) {
    $otherLease = @{ ownershipToken = 'someone-else'; expiresUtc = $expiry.ToString('o') } | ConvertTo-Json
    [IO.File]::WriteAllText($leaseFile, $otherLease)
    $rejected = $false
    try { Grant-AuditLease } catch { $rejected = $true }
    Assert-Audit $rejected 'Another test lease was reclaimed without confirming it stopped.'
    Revoke-AuditLease
    Assert-Audit ((Get-Content -LiteralPath $leaseFile -Raw) -eq $otherLease) 'Another test lease was changed or released.'
    $rejected = $false
    try { Invoke-AuditAdb shell 'am force-stop fixture' } catch { $rejected = $true }
    Assert-Audit ($rejected -and $script:deviceCommands -eq 1) 'Device command was sent after losing the lease.'
}
Remove-Item -LiteralPath $leaseFile -Force
foreach ($expiry in [DateTime]::UtcNow.AddMinutes(-5).ToString('o'), [DateTimeOffset]::UtcNow.AddMinutes(-5).ToOffset([TimeSpan]::FromHours(-4)).ToString('o')) {
    Grant-AuditLease
    # Mutate only the owned stream; external writes are now blocked while the context is held.
    $held = Read-DeviceLeaseRecord $script:auditLease
    $held.expiresUtc = $expiry
    Write-DeviceLeaseRecord $script:auditLease $held
    Assert-Audit (-not (Test-AuditLeaseOwnership)) 'Expired same-token lease became current after timezone conversion.'
    $rejected = $false
    try { Invoke-AuditAdb shell 'fixture' } catch { $rejected = $true }
    Assert-Audit ($rejected -and $script:deviceCommands -eq 1) 'Expired same-token lease sent a device command.'
    $script:auditLease.Stream.Position = 0
    $leaseReader = [IO.StreamReader]::new($script:auditLease.Stream, [Text.Encoding]::UTF8, $true, 1024, $true)
    try { $expiredLease = $leaseReader.ReadToEnd() } finally { $leaseReader.Dispose() }
    $rejected = $false
    try { Grant-AuditLease } catch { $rejected = $true }
    Assert-Audit ($rejected -and -not (Test-AuditLeaseOwnership)) 'Renewal resurrected an expired same-token lease.'
    Revoke-AuditLease
    Assert-Audit ((Get-Content -LiteralPath $leaseFile -Raw) -ceq $expiredLease) 'Expired same-token lease was renewed or removed.'
    Remove-Item -LiteralPath $leaseFile -Force
    $script:auditLease = $null # Explicit recovery after the expired fake test stopped.
}
# Exercise the real finalizer: a restoration failure must still close/release the owned handle.
Grant-AuditLease
$apkZip = [pscustomobject]@{}
$apkZip | Add-Member ScriptMethod Dispose { }
$script:recorder = $null
$deviceStarted = $true
function Restore-NightMode { throw 'Fixture restoration failure.' }
$mainCleanup = $syntax.Find({ param($node)
    $node -is [Management.Automation.Language.TryStatementAst] -and $null -ne $node.Finally -and
        $node.Finally.Extent.Text -match '\$apkZip\.Dispose\('
}, $true)
$rejected = $false
try { & ([scriptblock]::Create(($mainCleanup.Finally.Statements.Extent.Text -join "`n"))) }
catch { $rejected = $_.Exception.Message -eq 'Fixture restoration failure.' }
Assert-Audit $rejected 'The fixture did not exercise a restoration failure.'
Assert-Audit ($null -eq $script:auditLease -and -not (Test-Path -LiteralPath $leaseFile)) 'Restoration failure retained the device lease.'
# Failed capture or transfer must never certify an older local screenshot or recording.
$script:captureFailure = ''
$script:ownsCaptureLease = $true
$script:captureCommands = @()
function Test-AuditLeaseOwnership { return $script:ownsCaptureLease }
function Invoke-DeviceShell([string]$Command) {
    $script:captureCommands += $Command
    $global:LASTEXITCODE = if ($Command -like 'screencap*' -and $script:captureFailure -eq 'capture') { 1 } else { 0 }
}
function Invoke-AuditAdb {
    if ($args[0] -eq 'pull') {
        [IO.File]::WriteAllText($args[2], $(if ($script:captureFailure -eq 'empty') { '' } else { 'new capture' }))
        $global:LASTEXITCODE = if ($script:captureFailure -eq 'pull') { 1 } else { 0 }
    }
}
$firstShot = Save-Shot 'same-name'
$secondShot = Save-Shot 'same-name'
Assert-Audit ($firstShot -ne $secondShot) 'Repeated screenshots overwrite prior evidence.'
foreach ($failure in 'capture', 'pull', 'empty') {
    $script:captureFailure = $failure
    $rejected = $false
    try { [void](Save-Shot 'same-name') } catch { $rejected = $true }
    Assert-Audit $rejected "Screenshot $failure failure was certified."
    Assert-Audit ((Get-Content -LiteralPath (Join-Path $fixture $firstShot) -Raw) -eq 'new capture') 'A failed capture replaced older evidence.'
}
foreach ($failure in 'recorder', 'pull', 'empty', 'timeout', 'existing', 'success') {
    $script:captureFailure = $failure
    $script:recordingRemote = '/fixture/unique-recording.mp4'
    $script:recorder = [pscustomobject]@{ ExitCode = $(if ($failure -eq 'recorder') { 1 } else { 0 }) }
    $script:recorder | Add-Member ScriptMethod WaitForExit { param($Timeout) return $script:captureFailure -ne 'timeout' }
    $script:recorder | Add-Member ScriptMethod Kill { }
    $script:recorder | Add-Member ScriptMethod Dispose { }
    $recording = Join-Path $fixture "$failure.mp4"
    if ($failure -eq 'existing') { [IO.File]::WriteAllText($recording, 'old recording') }
    $rejected = $false
    try { Stop-ScreenRecord $recording } catch { $rejected = $true }
    Assert-Audit ($rejected -eq ($failure -ne 'success')) "Recording outcome was wrong for $failure."
    Assert-Audit ($null -eq $script:recorder) 'Recorder handle survived completion.'
    if ($failure -eq 'existing') { Assert-Audit ((Get-Content $recording -Raw) -eq 'old recording') 'Prior recording evidence was overwritten.' }
}
$script:captureFailure = ''
foreach ($owned in $true, $false) {
    $script:ownsCaptureLease = $owned
    $script:disposedRecorder = $false
    $script:killedRecorder = $false
    $script:captureCommands = @()
    $script:recordingRemote = '/fixture/interrupted.mp4'
    $script:recorder = [pscustomobject]@{ HasExited = $false }
    $script:recorder | Add-Member ScriptMethod Kill { $script:killedRecorder = $true }
    $script:recorder | Add-Member ScriptMethod Dispose { $script:disposedRecorder = $true }
    function Invoke-DumpTap { throw 'Tap failed during recording' }
    function Restart-TikTok { }
    $restarted = $false
    $interrupted = Invoke-Check $entry $tapCheck @{} ([ref]$restarted)
    Assert-Audit ($interrupted.status -eq 'fail' -and $script:killedRecorder -and $script:disposedRecorder -and $null -eq $script:recorder) 'Failed intermediate step leaked its recorder.'
    Assert-Audit (($script:captureCommands -contains 'rm -f /fixture/interrupted.mp4') -eq $owned) 'Recording cleanup ignored lease ownership.'
}
function Write-FixtureJson([string]$Path, $Value) { $Value | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $Path -Encoding UTF8 }
function New-FixtureZip([string]$Path, [string]$EntryName, [string]$Content) {
    $zip = [IO.Compression.ZipFile]::Open($Path, 'Create')
    try {
        $writer = [IO.StreamWriter]::new($zip.CreateEntry($EntryName).Open())
        try { $writer.Write($Content) } finally { $writer.Dispose() }
    } finally { $zip.Dispose() }
}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $fixtureApk = Join-Path $fixture 'fixture.apk'
    $fixtureBundle = Join-Path $fixture 'fixture.mpp'
    $receiptPath = Join-Path $fixture 'receipt.json'
    New-FixtureZip $fixtureApk 'marker.txt' 'present'
    $manifest = "Manifest-Version: 1.0`nVersion: 1.0.0`nTimestamp: 1000`nPatcher-Version: 1.15.1`n" +
        "Hushfeed-Source-Commit: $($source.commit)`nHushfeed-Source-Clean: true`n" +
        "Hushfeed-Source-Start: $($source.startFingerprint)`nHushfeed-Source-End: $($source.endFingerprint)`n"
    New-FixtureZip $fixtureBundle 'META-INF/MANIFEST.MF' $manifest
    $receipt = [ordered]@{
        schemaVersion = 2; kind = 'hushfeed-device-build'; source = $source
        apk = @{ package = $app.package; versionName = $app.version; versionCode = $app.versionCode
            sha256 = (Get-FileHash $fixtureApk -Algorithm SHA256).Hash.ToLowerInvariant() }
        sourceApk = @{ package = $app.package; versionName = $app.version; versionCode = '1'; sha256 = 'f' * 64 }
        sourceApkSha256 = 'f' * 64; patches = @('Static','Second'); options = @('Static|fixture=true')
        patchReportSha256 = 'e' * 64
        bundle = @{ sha256 = (Get-FileHash $fixtureBundle -Algorithm SHA256).Hash.ToLowerInvariant() }
    }
    Write-FixtureJson $receiptPath $receipt
    Assert-Audit ((Read-AuditBuildIdentity $fixtureApk $receiptPath $fixtureBundle $inputRepo $app).status -eq 'verified') 'Valid build receipt rejected.'
    Assert-Audit ((Get-AuditHash (Read-AuditBuildIdentity $fixtureApk $receiptPath $fixtureBundle $inputRepo $app).executionInputs) -ceq
        (Get-AuditHash $build.executionInputs)) 'Build identity omitted its exact executable inputs.'
    $nativeIdentity = Read-AuditBuildIdentity $fixtureApk $receiptPath $fixtureBundle $inputRepo $app
    foreach ($field in @('apk','sourceApk','sourceApkSha256','bundle','patches','options','patchReportSha256')) {
        Assert-Audit ((Get-AuditHash $nativeIdentity.$field) -ceq (Get-AuditHash $receipt[$field])) "Build reader dropped or changed $field."
    }
    # Execute the producer's real receipt assignment with observed-manifest stand-ins and real hashes.
    $producerErrors = $null
    $producerAst = [Management.Automation.Language.Parser]::ParseFile(
        (Join-Path (Split-Path -Parent $PSScriptRoot) 'scripts/patch-for-device.ps1'), [ref]$null, [ref]$producerErrors)
    Assert-Audit (@($producerErrors).Count -eq 0) 'Device build producer failed to parse.'
    $assignment = $producerAst.Find({ param($node)
        $node -is [Management.Automation.Language.AssignmentStatementAst] -and
        $node.Left -is [Management.Automation.Language.VariableExpressionAst] -and
        $node.Left.VariablePath.UserPath -ceq 'auditBuildReceipt'
    }, $true)
    Assert-Audit ($null -ne $assignment) 'Device build producer has no receipt assignment.'
    $producerReport = Join-Path $fixture 'producer-report.json'
    [IO.File]::WriteAllText($producerReport, '{"appliedPatches":["Static","Second"]}')
    $producedReceipt = & {
        param($Definition, $OutputPath, $ReportPath, $Original, $BundleHash, $BuildSource, $AppFacts)
        $sourceApkFacts = [pscustomobject]@{ package = $AppFacts.package; versionName = $AppFacts.version; versionCode = '1' }
        $patchedApkFacts = [pscustomobject]@{ package = $AppFacts.package; versionName = $AppFacts.version; versionCode = '2147483647' }
        $out = $OutputPath; $result = $ReportPath; $auditInputHash = $Original; $auditBundleHash = $BundleHash
        $auditBundleSource = $BuildSource; $names = @('Static','Second'); $Option = @('Static|fixture=true')
        . ([scriptblock]::Create($Definition))
        return $auditBuildReceipt
    } $assignment.Extent.Text $fixtureApk $producerReport ('f' * 64) $receipt.bundle.sha256 $source $app
    Assert-Audit ($producedReceipt.schemaVersion -eq 2 -and $producedReceipt.sourceApk.versionCode -ceq '1' -and
        $producedReceipt.apk.versionCode -ceq '2147483647') 'Producer lost separate stock and patched manifest identities.'
    $producerApp = Copy-Json $app; $producerApp.versionCode = '2147483647'
    Write-FixtureJson $receiptPath $producedReceipt
    $roundtrip = Read-AuditBuildIdentity $fixtureApk $receiptPath $fixtureBundle $inputRepo $producerApp
    Assert-Audit ($roundtrip.status -ceq 'verified' -and $roundtrip.patchReportSha256 -ceq
        (Get-FileHash -LiteralPath $producerReport -Algorithm SHA256).Hash.ToLowerInvariant()) 'Producer/reader roundtrip lost the actual report identity.'
    Write-FixtureJson $receiptPath $receipt
    $oldDeviceReceipt = Copy-Json $receipt
    $oldDeviceReceipt.schemaVersion = 1
    Write-FixtureJson $receiptPath $oldDeviceReceipt
    Assert-Audit ((Read-AuditBuildIdentity $fixtureApk $receiptPath $fixtureBundle $inputRepo $app).status -eq 'historical') 'A legacy device receipt was retroactively qualified.'
    Write-FixtureJson $receiptPath $receipt
    Assert-Audit ((Read-AuditBuildIdentity $fixtureApk '' '' $inputRepo).status -eq 'unbound') 'Absent receipt invented source identity.'
    foreach ($mutation in 'apk', 'bundle', 'source') {
        $invalid = Copy-Json $receipt
        if ($mutation -eq 'source') { $invalid.source.clean = $false } else { $invalid.$mutation.sha256 = '0' * 64 }
        Write-FixtureJson $receiptPath $invalid
        $rejected = $false
        try { [void](Read-AuditBuildIdentity $fixtureApk $receiptPath $fixtureBundle $inputRepo $app) } catch { $rejected = $true }
        Assert-Audit $rejected "Mismatched $mutation accepted."
    }
    Write-FixtureJson $receiptPath $receipt
    $staticEntry = [pscustomobject]@{ name = 'Static'; kind = 'static'; category = 'Test'; settings = @(); checks = @(
        [pscustomobject]@{ id = 'present'; what = "App's marker <here> & there"; auto = $true; steps = @([pscustomobject]@{ apk = 'entry-present'; path = 'marker.txt' }) }
    ) }
    $fixtureCatalog = Join-Path $fixture 'catalog.json'
    $staticEntry.checks[0].what += [char]0xB7 + [char]::ConvertFromUtf32(0x1F525)
    $fixtureResults = Join-Path $fixture 'results.json'
    $fakeAapt = Join-Path $fixture 'aapt.ps1'
    Write-FixtureJson $fixtureCatalog @($staticEntry)
    [IO.File]::WriteAllText($fakeAapt, 'Write-Output "package: name=''com.zhiliaoapp.musically'' versionCode=''1'' versionName=''47.1.4''"')
    $shell = (Get-Process -Id $PID).Path
    $originalRunner = Join-Path $PSScriptRoot 'run-audit.ps1'
    $runner = Join-Path $inputRepo 'audit/run-audit.ps1'
    & $shell -NoProfile -File $runner -NoDevice -Catalog $fixtureCatalog -Apk $fixtureApk -Aapt2 $fakeAapt -Results $fixtureResults -OutDir (Join-Path $fixture 'evidence') -BuildReceipt $receiptPath -Bundle $fixtureBundle
    Assert-Audit ($LASTEXITCODE -eq 0) 'Static CLI run failed.'
    $recorded = Get-Content $fixtureResults -Raw | ConvertFrom-Json
    Assert-Audit ($recorded.qualification.currentPass -eq 1) 'Static run did not bind evidence.'
    Assert-Audit ($recorded.patches[0].checks[0].definitionHash -eq (Get-AuditDefinitionHash $staticEntry $staticEntry.checks[0])) 'Runner omitted exact definition.'
    # Without the evidence folder every receipt reads as stale, so both read-only modes refuse to guess.
    $resultsBefore = [IO.File]::ReadAllText($fixtureResults)
    $emptyVerdicts = Join-Path $fixture 'empty-verdicts.json'
    [IO.File]::WriteAllText($emptyVerdicts, '{}')
    $ErrorActionPreference = 'Continue'
    try {
        & $shell -NoProfile -File $runner -ValidateResults -Catalog $fixtureCatalog -Results $fixtureResults 2>&1 | Out-Null
        $validateStatus = $LASTEXITCODE
        & $shell -NoProfile -File $runner -ApplyVerdicts $emptyVerdicts -Catalog $fixtureCatalog -Results $fixtureResults 2>&1 | Out-Null
        $applyStatus = $LASTEXITCODE
        # A review of one APK's screenshots can't land on results recorded for another.
        $otherApk = Join-Path $fixture 'other.apk'
        New-FixtureZip $otherApk 'marker.txt' 'another build'
        & $shell -NoProfile -File $runner -ApplyVerdicts $emptyVerdicts -Catalog $fixtureCatalog -Results $fixtureResults -OutDir (Join-Path $fixture 'evidence') -Apk $otherApk 2>&1 | Out-Null
        $otherApkStatus = $LASTEXITCODE
    } finally { $ErrorActionPreference = 'Stop' }
    Assert-Audit ($validateStatus -ne 0) 'Validation without -OutDir reported a result.'
    Assert-Audit ($applyStatus -ne 0 -and [IO.File]::ReadAllText($fixtureResults) -ceq $resultsBefore) 'Verdicts were applied without -OutDir.'
    Assert-Audit ($otherApkStatus -ne 0 -and [IO.File]::ReadAllText($fixtureResults) -ceq $resultsBefore) 'Verdicts were applied to results recorded for another APK.'
    # Validate a persisted receipt in the other supported shell, not just twice in one host.
    $otherShell = if ($PSVersionTable.PSVersion.Major -ge 6 -and $env:WINDIR) {
        Join-Path $env:WINDIR 'System32/WindowsPowerShell/v1.0/powershell.exe'
    } elseif (Get-Command pwsh -ErrorAction SilentlyContinue) { (Get-Command pwsh).Source }
    if ($otherShell -and (Test-Path -LiteralPath $otherShell)) {
        $modulePathBefore = $env:PSModulePath
        try {
            if ($PSVersionTable.PSVersion.Major -ge 6) {
                $env:PSModulePath = "$env:WINDIR\System32\WindowsPowerShell\v1.0\Modules;$env:ProgramFiles\WindowsPowerShell\Modules"
            }
            $crossResult = & $otherShell -NoProfile -ExecutionPolicy Bypass -File $runner -ValidateResults -Catalog $fixtureCatalog -Results $fixtureResults -OutDir (Join-Path $fixture 'evidence')
            Assert-Audit ($LASTEXITCODE -eq 0) 'The other PowerShell host rejected an unchanged persisted receipt.'
            Assert-Audit (($crossResult | ConvertFrom-Json).currentPass -eq 1) 'Cross-shell validation lost its current pass.'
        } finally { $env:PSModulePath = $modulePathBefore }
    }
    # -Validate holds every TikTok version a pattern spells out to the declared target, so a check
    # still asking for the old version after a retarget fails here instead of on every device run.
    & $shell -NoProfile -File $originalRunner -Validate 2>&1 | Out-Null
    Assert-Audit ($LASTEXITCODE -eq 0) 'The shipped catalog failed -Validate.'
    $stale = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'catalog.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $stale[0].checks[0].steps += [pscustomobject]@{ probe = 'report'; expect = 'tiktok_version: 12\.3\.4' }
    $staleCatalog = Join-Path $fixture 'stale-catalog.json'
    Write-FixtureJson $staleCatalog $stale
    $staleOutput = & $shell -NoProfile -File $originalRunner -Validate -Catalog $staleCatalog 2>&1 | Out-String
    Assert-Audit ($LASTEXITCODE -eq 1 -and $staleOutput -match 'names TikTok 12\.3\.4, not the declared') 'A pattern naming an undeclared TikTok version passed -Validate.'
    # Carry an unchanged historical result while running a different patch, then change its definition.
    $second = Copy-Json $staticEntry
    $second.name = 'Second'
    $staticEntry.checks[0].what = 'Changed marker criterion'
    Write-FixtureJson $fixtureCatalog @($staticEntry, $second)
    & $shell -NoProfile -File $runner -NoDevice -Only Second -Catalog $fixtureCatalog -Apk $fixtureApk -Aapt2 $fakeAapt -Results $fixtureResults -OutDir (Join-Path $fixture 'second-evidence') -BuildReceipt $receiptPath -Bundle $fixtureBundle
    Assert-Audit ($LASTEXITCODE -eq 0) 'Partial CLI run failed.'
    $merged = Get-Content $fixtureResults -Raw | ConvertFrom-Json
    Assert-Audit ($merged.qualification.changed -contains 'Static/present') 'Partial rerun promoted an old definition.'
    Assert-Audit ($merged.qualification.currentChecks -eq 1) 'Partial rerun counted stale evidence current.'
    Assert-Audit ($merged.patches[0].checks[0].status -eq 'pass') 'Historical verdict was rewritten.'
} finally {
    if ($null -ne $script:auditLease -and $null -ne $script:auditLease.Stream) { $script:auditLease.Stream.Dispose() }
    $resolved = [IO.Path]::GetFullPath($fixture)
    if (-not $resolved.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path -Leaf $resolved) -notlike 'hushfeed-audit-test-*') { throw 'Unsafe fixture cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
Write-Host "Audit evidence contracts passed: $script:passed"
