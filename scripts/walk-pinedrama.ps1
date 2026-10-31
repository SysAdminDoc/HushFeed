<#
.SYNOPSIS
    Exercises PineDrama's native launcher, inline Comments controls and native Back restoration.
.DESCRIPTION
    Walks an already installed, signed, merged PineDrama build. Build/install the separate probe
    with tools/verification-probe/build.ps1 -Profile pinedrama-47.5.3-core-walk-v2 first. Requires
    the complete declared 98-patch selection and exact reviewed merged stock APK. Does not post,
    follow, message, change preferences or qualify any served-content or server entitlement flow.
    The result is always INCOMPLETE and publication-ineligible, even when every core step passes.
    The owned entry hook is exercised directly; native Profile-to-settings entry remains owed.
#>
[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][string]$StockApk, [Parameter(Mandatory = $true)][string]$PatchedApk,
    [Parameter(Mandatory = $true)][string]$AuditBuild, [Parameter(Mandatory = $true)][string]$Bundle,
    [Parameter(Mandatory = $true)][string]$Aapt2, [Parameter(Mandatory = $true)][string]$Out,
    [string[]]$ExpectedOptions = @(), [string]$LeaseDir = $env:HUSHFEED_DEVICE_LEASE_DIR,
    [string]$ChatIdentity = $env:HUSHFEED_CHAT_IDENTITY, [string]$Adb = 'adb',
    # In-process controller ownership spans startup, install, walk, rollback and shutdown.
    [AllowNull()]$Lease)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $root 'tools/verification-probe/pinedrama-core.ps1')
. (Join-Path $PSScriptRoot 'device-lease.ps1')
. (Join-Path $PSScriptRoot 'walk-app-core.ps1')
$plan = New-PineDramaCorePlan -StockApk $StockApk -PatchedApk $PatchedApk -AuditBuild $AuditBuild `
    -Bundle $Bundle -GitRoot $root -Aapt2 $Aapt2 -ExpectedOptions $ExpectedOptions
$profile = Get-VerificationProbeProfile $plan.profile
$Out = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Out)
$work = Split-Path -Parent $Out
if (Test-Path -LiteralPath $Out) { throw 'Choose a new PineDrama core result path. Existing evidence is preserved.' }
[void][IO.Directory]::CreateDirectory($work)
$run = [guid]::NewGuid().ToString('N')
$checks = [Collections.Generic.List[object]]::new()
$evidence = [Collections.Generic.List[object]]::new()
$problems = [Collections.Generic.List[string]]::new()
$script:pineLease = $null
$script:firstPid = ''
$script:sameProcess = $true

function Invoke-PineAdb {
    Assert-DeviceLease -Lease $script:pineLease -Serial $Serial
    $before = $ErrorActionPreference
    try { $ErrorActionPreference = 'Continue'; $lines = @(& $Adb -s $Serial @args 2>&1 | ForEach-Object { [string]$_ }); $status = $LASTEXITCODE }
    finally { $ErrorActionPreference = $before }
    if ($status) { throw 'A leased PineDrama device command failed.' }
    return $lines
}
function Read-PineInstalled {
    $paths = @(Invoke-PineAdb shell pm path $profile.Package | Where-Object { $_ -cmatch '^package:' })
    if ($paths.Count -ne 1 -or $paths[0] -cnotmatch '^package:.+/base\.apk$') { throw 'The PineDrama core walk refuses split or ambiguous installations.' }
    $hash = ((Invoke-PineAdb shell sha256sum $paths[0].Substring(8)) -join '').Trim().Split(' ')[0].ToLowerInvariant()
    if ($hash -cnotmatch '^[a-f0-9]{64}$') { throw 'Cannot identify the installed PineDrama APK.' }
    $facts = ConvertFrom-WalkPackageDump -Text ((Invoke-PineAdb shell dumpsys package $profile.Package) -join [char]10) -ExpectedPackage $profile.Package
    $facts | Add-Member -NotePropertyName sha256 -NotePropertyValue $hash
    foreach ($field in @('package','versionName','versionCode','sha256')) {
        if ([string]$facts.$field -cne [string]$plan.inspection.build.apk.$field) { throw 'Installed PineDrama differs from the exact planned build.' }
    }
    return $facts
}
function Test-PineProcess {
    try { $now = ((Invoke-PineAdb shell pidof $profile.Package) -join ' ').Trim().Split(' ')[0] }
    catch { $script:sameProcess = $false; throw }
    if (-not $script:firstPid) { $script:firstPid = $now }
    if (-not $now -or $now -cne $script:firstPid) { $script:sameProcess = $false; throw 'PineDrama process ownership changed during the core walk.' }
}
function Save-PineEvidence([string]$Step, $Observation, [string]$RequestId) {
    $name = "$run-$Step-$RequestId"
    $json = Join-Path $work ($name + '.json')
    $record = [pscustomobject]@{ step = $Step; requestId = $RequestId; proof = $Observation }
    [IO.File]::WriteAllText($json, ($record | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
    $remote = "/sdcard/hushfeed-pine-core-$run.png"
    $image = Join-Path $work ($name + '.png')
    Invoke-PineAdb shell screencap -p $remote | Out-Null
    Invoke-PineAdb pull $remote $image | Out-Null
    foreach ($path in @($json,$image)) {
        $evidence.Add([pscustomobject]@{ file = [IO.Path]::GetFileName($path); sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() })
    }
    return [IO.Path]::GetFileName($json)
}
function Wait-PineRoute([string]$Step, [string]$Field, [switch]$Restored, [switch]$Retained) {
    $answer = $null
    for ($attempt = 0; $attempt -lt 15; $attempt++) {
        Update-DeviceLease -Lease $script:pineLease -LeaseMinutes 30
        Test-PineProcess
        $requestId = $null
        try { $answer = Invoke-PineDramaCoreAction -Serial $Serial -Action snapshot -Plan $plan -Lease $script:pineLease -GitRoot $root -Adb $Adb -RequestId ([ref]$requestId) }
        catch { if ($attempt -eq 14) { throw }; Start-Sleep -Milliseconds 500; continue }
        if ($answer.$Field -and (-not $Restored -or $answer.nativeOwnerRestored) -and
                (-not $Retained -or $answer.settingsOwnerRetained)) {
            $observationFile = Save-PineEvidence $Step $answer $requestId
            $checks.Add([pscustomobject]@{ id = $Step; status = 'pass'; observed = $true; requestId = $requestId; proof = $answer; evidence = $observationFile })
            return
        }
    }
    $checks.Add([pscustomobject]@{ id = $Step; status = 'inconclusive'; observed = $false })
    throw "PineDrama core route unavailable: $Step. Account, onboarding or native route prerequisites remain unverified."
}
function Send-PineAction([string]$Action) {
    Test-PineProcess
    $null = Invoke-PineDramaCoreAction -Serial $Serial -Action $Action -Plan $plan -Lease $script:pineLease -GitRoot $root -Adb $Adb
}

$started = [DateTimeOffset]::UtcNow
$installedBefore = $null
$installedAfter = $null
$logSince = ''
$crashes = @()
$walkLease = New-PineDramaWalkLease -Serial $Serial -Lease $Lease -LeaseDir $LeaseDir -ChatIdentity $ChatIdentity
$script:pineLease = $walkLease.Lease
try {
    $installedBefore = Read-PineInstalled
    Invoke-PineAdb shell input keyevent KEYCODE_WAKEUP | Out-Null
    $logSince = ((Invoke-PineAdb shell date +%s) -join '').Trim()
    if ($logSince -cnotmatch '^\d+$') { throw 'Cannot establish the native crash observation start.' }
    Invoke-PineAdb shell am force-stop $profile.Package | Out-Null
    Invoke-PineAdb shell am instrument "$($profile.ProbePackage)/$($profile.Instrumentation)" | Out-Null
    Invoke-PineAdb shell am start -W -n "$($profile.Package)/$($plan.definition.anchors.launcher)" `
        -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | Out-Null
    Wait-PineRoute launch nativeMainForeground
    Send-PineAction open-settings
    Wait-PineRoute settings-home commentsCollapsed
    Send-PineAction expand-comments
    Wait-PineRoute comments-expanded commentsExpanded -Retained
    Send-PineAction collapse-comments
    Wait-PineRoute comments-collapsed commentsCollapsed -Retained
    Send-PineAction back-native
    Wait-PineRoute native-return nativeMainForeground -Restored
} catch { $problems.Add($_.Exception.Message); Write-Warning $_.Exception.Message }
finally {
    try {
        $installedAfter = Read-PineInstalled
        if ($logSince) {
            $lines = @(Invoke-PineAdb logcat -d -b crash -t "$logSince.000") +
                @(Invoke-PineAdb logcat -d -b main -b system -t "$logSince.000" -s AndroidRuntime:E ActivityManager:E libc:F DEBUG:F)
            $path = Join-Path $work ("$run-crashes.txt")
            [IO.File]::WriteAllLines($path, $lines, [Text.UTF8Encoding]::new($false))
            $evidence.Add([pscustomobject]@{ file = [IO.Path]::GetFileName($path); sha256 = (Get-FileHash $path -Algorithm SHA256).Hash.ToLowerInvariant() })
            $crashes = @(Get-WalkCrashes -Lines $lines -Package $profile.Package)
        }
        Invoke-PineAdb shell rm -f "/sdcard/hushfeed-pine-core-$run.png" | Out-Null
    } finally { Remove-PineDramaWalkLease -Context $walkLease }
}
if ($null -eq $installedBefore -or $null -eq $installedAfter) { throw 'No PineDrama core receipt can be issued without both installed identities.' }
$result = New-PineDramaCoreResult -Plan $plan -Checks $checks.ToArray() -InstalledBefore $installedBefore -InstalledAfter $installedAfter `
    -SameProcess $script:sameProcess -Crashes $crashes.Count -Problems $problems.ToArray() -Evidence $evidence.ToArray() -GitRoot $root
$result | Add-Member -NotePropertyName started -NotePropertyValue $started.ToString('o')
$result | Add-Member -NotePropertyName finished -NotePropertyValue ([DateTimeOffset]::UtcNow.ToString('o'))
$resultProblem = Get-PineDramaCoreResultProblem $plan $result -Directory $work -GitRoot $root
if ($resultProblem) { throw $resultProblem }
[IO.File]::WriteAllText($Out, ($result | ConvertTo-Json -Depth 30) + [char]10, [Text.UTF8Encoding]::new($false))
Write-Host "PineDrama core $($result.coreResult). Whole-app qualification INCOMPLETE. $Out"
if ($result.coreResult -cne 'PASS') { exit 1 }
