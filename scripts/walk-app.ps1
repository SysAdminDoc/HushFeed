<#
.SYNOPSIS
    Walks the whole patched TikTok on a release phone or an emulator and writes the
    walk-receipt.json release.ps1 reads.

.DESCRIPTION
    Patches -Package's app from the bundle being released with patch-for-device.ps1 and installs
    it (or, with -AuditBuild, walks the build already installed from that receipt), then opens
    every screen the patches reach: For You and Following with swipes, the comment and share
    sheets, search, the Friends, Inbox and Profile tabs and Inbox's Activity page. It then expands
    every Hushfeed settings section, scrolls it to its end and collapses it, plus Search settings.
    Hushfeed opens from Profile's menu, or from a long press on Home when that route has no
    Hushfeed row. After each step
    the app has to still be the foreground app on the same process. Logcat's crash lines for it
    decide the rest.

    The walk only opens screens or inline sections and closes them without changing controls. Every tap targets a node from a dump
    taken after the last swipe, sheet or Back, and Test-WalkTapAllowed refuses anything labeled
    like a follow, like, message, post or quick setup. A playing video blocks uiautomator, so a
    feed dump that fails pauses the video with one tap on its long_press_layout (read from
    dumpsys) and dumps again.

    TikTok shows the language picked in its own settings whatever the phone or per-app locale
    says, so the walk finds the app's screens through its profile's labels (walk-app-core.ps1, in
    English and Italian) and Hushfeed's through its own translation tables.

    The receipt is PASS only when every planned screen was reached, the app never crashed, died
    or left the foreground, and the installed APK is the one patched from -Bundle.

.EXAMPLE
    ./scripts/walk-app.ps1 -Serial $env:HUSHFEED_DEVICE_SERIAL -Bundle patches/build/release/patches-0.78.0.mpp `
        -PatchArgs @{ Apk = 'C:/fixtures/tiktok.apk'; Keystore = 'C:/keys/device.keystore'; KeyAlias = 'Morphe' }

#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    # The app to walk, one of the packages in walk-app-core.ps1's $WalkProfiles.
    [string]$Package = 'com.zhiliaoapp.musically',
    # The bundle the receipt vouches for.
    [string]$Bundle,
    # Walk the build already installed, described by the .audit-build.json patch-for-device.ps1
    # left beside its APK, instead of patching and installing a new one.
    [string]$AuditBuild,
    # The exact signed APK beside -AuditBuild, or an explicit path if the receipt was renamed.
    [string]$PatchedApk,
    [string]$LeaseDir = $env:HUSHFEED_DEVICE_LEASE_DIR,
    [string]$ChatIdentity = $env:HUSHFEED_CHAT_IDENTITY,
    [string]$Out,
    [string]$Query = 'cats',
    # Passed to patch-for-device.ps1 as they are: Apk, DesktopJar, Java, Keystore, KeyAlias,
    # KeystoreType, KeystorePassword, KeyPassword, Exclude, Option, OutDir.
    [hashtable]$PatchArgs = @{}
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'device-lease.ps1')
. (Join-Path $PSScriptRoot 'walk-receipt.ps1')
. (Join-Path $PSScriptRoot 'walk-app-core.ps1')

$appProfile = Get-WalkProfile $Package
$WalkPackage = $appProfile.Package
$WalkLabels = $appProfile.Labels
$appName = $appProfile.App
$receiptProfile = Get-WalkProfileName $WalkPackage
if (-not $receiptProfile) { throw "walk-receipt.ps1 has no receipt profile for $WalkPackage." }

if (-not $LeaseDir) { throw '-LeaseDir or HUSHFEED_DEVICE_LEASE_DIR names the shared device lease folder.' }
if (-not $ChatIdentity) { throw '-ChatIdentity or HUSHFEED_CHAT_IDENTITY names who is driving the phone.' }
if (-not $Out) { $Out = Join-Path $root 'build/walk/walk-receipt.json' }
$work = Split-Path -Parent ([IO.Path]::GetFullPath($Out))
$dumps = Join-Path $work 'screens'
if (Test-Path -LiteralPath $dumps) { Remove-Item -LiteralPath $dumps -Recurse -Force }
New-Item -ItemType Directory -Path $dumps -Force | Out-Null
$logPath = Join-Path $work 'walk-log.txt'
Set-Content -LiteralPath $logPath -Value @() -Encoding UTF8
function Write-WalkLog([string]$Message) {
    $line = "[walk] $([DateTime]::Now.ToString('HH:mm:ss')) $Message"
    Write-Host $line
    Add-Content -LiteralPath $logPath -Value $line -Encoding UTF8
}

# The bundle patch-for-device.ps1 patches with is the release bundle; the receipt can only
# vouch for -Bundle when they're the same bytes.
$releaseBundle = Get-ReleaseBundlePath -Root $root
if (-not $Bundle) { $Bundle = $releaseBundle }
foreach ($path in $Bundle, $releaseBundle) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "There's no bundle at $path. Build it first." }
}
$bundleSha256 = (Get-FileHash -LiteralPath $Bundle -Algorithm SHA256).Hash.ToLowerInvariant()
if ((Get-FileHash -LiteralPath $releaseBundle -Algorithm SHA256).Hash.ToLowerInvariant() -cne $bundleSha256) {
    throw "$Bundle and $releaseBundle differ, so an APK patched now wouldn't be patched from $Bundle."
}

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter adb.exe -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName }
if (-not $adb) { throw 'No adb found. Put it on the PATH or install the platform tools.' }

if (-not $AuditBuild) {
    $patch = @{} + $PatchArgs
    $patch.Serial = $Serial; $patch.LeaseDir = $LeaseDir; $patch.ChatIdentity = $ChatIdentity
    if (-not $patch.ContainsKey('OutDir')) { $patch.OutDir = Join-Path $work 'apk' }
    Write-WalkLog "patching and installing from $releaseBundle"
    & (Join-Path $PSScriptRoot 'patch-for-device.ps1') @patch
    $AuditBuild = Join-Path $patch.OutDir "hushfeed-$(Get-BundleVersion -Root $root)-signed.apk.audit-build.json"
}
if (-not (Test-Path -LiteralPath $AuditBuild -PathType Leaf)) { throw "There's no build receipt at $AuditBuild." }
$build = Get-Content -LiteralPath $AuditBuild -Raw | ConvertFrom-Json
if ([string]$build.bundle.sha256 -ine $bundleSha256) {
    throw "The installed build was patched with bundle $($build.bundle.sha256), not $bundleSha256 ($Bundle)."
}
if ($build.schemaVersion -ne 2) { throw 'The walk needs a schema 2 device-build receipt with complete native APK identity.' }
if (-not $PatchedApk) {
    if (-not $AuditBuild.EndsWith('.audit-build.json', [StringComparison]::OrdinalIgnoreCase)) {
        throw '-PatchedApk is required when the device-build receipt has been renamed.'
    }
    $PatchedApk = $AuditBuild.Substring(0, $AuditBuild.Length - '.audit-build.json'.Length)
}
$walkBuild = Read-AuditBuildIdentity -ApkPath $PatchedApk -ReceiptPath $AuditBuild -BundlePath $Bundle -GitRoot $root `
    -App ([pscustomobject]@{ package = $WalkPackage; version = $build.apk.versionName; versionCode = $build.apk.versionCode })
if ($walkBuild.status -cne 'verified') { throw 'The walk has no verified native build identity.' }
$apkSha256 = ([string]$walkBuild.apk.sha256).ToLowerInvariant()

$planned = [Collections.Generic.List[string]]::new()
$reached = [Collections.Generic.List[string]]::new()
$faults = [Collections.Generic.List[string]]::new()
$script:dumpCount = 0
$script:lease = $null
$translations = Get-HushfeedTranslations -Root $root
$remoteDump = '/sdcard/hushfeed-walk.xml'

function Invoke-WalkAdb {
    Assert-DeviceLease -Lease $script:lease -Serial $Serial
    $preference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $said = @(& $adb -s $Serial @args 2>&1 | ForEach-Object { [string]$_ }) }
    finally { $ErrorActionPreference = $preference }
    return , $said
}

function Get-WalkForeground {
    $said = (Invoke-WalkAdb shell 'dumpsys activity activities | grep -E "topResumedActivity|ResumedActivity"') -join "`n"
    $m = [regex]::Match($said, 'ActivityRecord\{\S+ \S+ ([\w.]+)/([\w.$]+)')
    if ($m.Success) { return "$($m.Groups[1].Value)/$($m.Groups[2].Value)" }
    return ''
}


function Get-WalkInstalledIdentity {
    $paths = @(Invoke-WalkAdb shell pm path $WalkPackage | Where-Object { $_ -match '^package:' })
    if ($paths.Count -ne 1 -or $paths[0] -notmatch '^package:.*base\.apk$') {
        throw 'The walk needs one unambiguous installed base APK and no split APKs.'
    }
    $hash = ((Invoke-WalkAdb shell sha256sum $paths[0].Substring(8)) -join '').Trim().Split(' ')[0].ToLowerInvariant()
    if ($hash -cnotmatch '^[a-f0-9]{64}$') { throw 'The installed APK hash could not be read.' }
    $facts = ConvertFrom-WalkPackageDump -Text ((Invoke-WalkAdb shell dumpsys package $WalkPackage) -join [string][char]10) -ExpectedPackage $WalkPackage
    $facts | Add-Member -NotePropertyName sha256 -NotePropertyValue $hash
    return $facts
}

function Get-WalkPid {
    return ((Invoke-WalkAdb shell "pidof $WalkPackage") -join ' ').Trim().Split(' ')[0]
}

function Test-WalkAlive([string]$Step) {
    Update-DeviceLease -Lease $script:lease -LeaseMinutes 30
    $now = Get-WalkPid
    $top = Get-WalkForeground
    if (-not $now) { $faults.Add("$appName's process was gone after $Step"); return $false }
    if ($now -ne $script:appPid) { $faults.Add("$appName's process changed from $($script:appPid) to $now during $Step"); $script:appPid = $now; return $false }
    if (-not $top.StartsWith("$WalkPackage/", [StringComparison]::Ordinal)) { $faults.Add("$appName left the foreground during $Step ($top was on top)"); return $false }
    return $true
}

function Suspend-WalkVideo {
    # One tap on the playing video's own surface pauses it. dumpsys reads the view tree while
    # the video plays, where uiautomator can't.
    $top = Invoke-WalkAdb shell dumpsys activity top
    $layout = $top | Where-Object { $_ -match 'app:id/long_press_layout\}' } | ForEach-Object {
        $m = [regex]::Match($_, ' 0,0-(\d+),(\d+) ')
        if ($m.Success -and [int]$m.Groups[1].Value -gt 400) { [pscustomobject]@{ W = [int]$m.Groups[1].Value; H = [int]$m.Groups[2].Value } }
    } | Select-Object -First 1
    if (-not $layout -or -not (Get-WalkForeground).StartsWith("$WalkPackage/", [StringComparison]::Ordinal)) { return $false }
    Invoke-WalkAdb shell input tap ([int]($layout.W / 2)) ([int]($layout.H / 2)) | Out-Null
    Start-Sleep -Milliseconds 1200
    return $true
}

function Get-WalkScreen([string]$Name, [switch]$AllowPause) {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $local = Join-Path $dumps 'last.xml'
        Remove-Item -LiteralPath $local -Force -ErrorAction SilentlyContinue
        Invoke-WalkAdb shell "rm -f $remoteDump; uiautomator dump $remoteDump >/dev/null 2>&1" | Out-Null
        Invoke-WalkAdb pull $remoteDump $local | Out-Null
        if (Test-Path -LiteralPath $local) {
            $xml = [IO.File]::ReadAllText($local, [Text.Encoding]::UTF8)
            if ($xml.Contains('</hierarchy>')) {
                $script:dumpCount++
                $safe = ($Name -replace '[^\w]+', '-').Trim('-')
                Move-Item -LiteralPath $local -Destination (Join-Path $dumps ('{0:d3}-{1}.xml' -f $script:dumpCount, $safe)) -Force
                $nodes = @(ConvertFrom-UiDump -Xml $xml)
                if ($nodes.Count -gt 0) { return , $nodes }
            }
        }
        if ($AllowPause -and $attempt -eq 1) { [void](Suspend-WalkVideo) } else { Start-Sleep -Seconds 2 }
    }
    Write-WalkLog "no UI dump for $Name"
    return , @()
}

function Save-WalkShot([string]$Name) {
    $safe = ($Name -replace '[^\w]+', '-').Trim('-')
    $local = Join-Path $dumps ('{0:d3}-{1}.png' -f $script:dumpCount, $safe)
    Invoke-WalkAdb shell 'screencap -p /sdcard/hushfeed-walk.png' | Out-Null
    Invoke-WalkAdb pull /sdcard/hushfeed-walk.png $local | Out-Null
}

function Invoke-WalkTap($Node, [string]$What, [int]$WaitMs = 2000, [string]$Title) {
    if (-not (Test-WalkTapAllowed -Node $Node -Title $Title)) { throw "Refused to tap '$($Node.Text)$($Node.Desc)' for $What." }
    Write-WalkLog "tap $What at $($Node.X),$($Node.Y)"
    Invoke-WalkAdb shell input tap $Node.X $Node.Y | Out-Null
    Start-Sleep -Milliseconds $WaitMs
}

function Invoke-WalkSwipe([int]$Width, [int]$Height, [switch]$Down) {
    # Up starts above the caption block: on a text post the creator's name reaches 0.72 of the
    # height, and a swipe that started there opened the profile on the Login2 emulator.
    $x = [int]($Width / 2)
    $from = [int]($Height * 0.55); $to = [int]($Height * 0.18)
    if ($Down) { $from = [int]($Height * 0.30); $to = [int]($Height * 0.72) }
    Invoke-WalkAdb shell input swipe $x $from $x $to 450 | Out-Null
    Start-Sleep -Milliseconds 1300
}

function Invoke-WalkBack([string]$What) {
    Write-WalkLog "back from $What"
    Invoke-WalkAdb shell input keyevent KEYCODE_BACK | Out-Null
    Start-Sleep -Milliseconds 1500
}

function Close-WalkKeyboard {
    if ((Invoke-WalkAdb shell 'dumpsys input_method | grep -c "mInputShown=true"') -join '' -match '^[1-9]') {
        Invoke-WalkBack 'the keyboard'
    }
}

function Find-WalkNav($Nodes, [string]$Label, [int]$Height) {
    @(Find-UiNode -Nodes $Nodes -Label $Label | Where-Object { $_.Desc -cmatch $Label -and $_.Top -gt $Height * 0.85 }) | Select-Object -First 1
}

# A feed video's own button (comments, share). LIVE previews in the feed have none, so swipe on
# until a video shows one.
function Find-WalkVideoButton([string]$Label, [string]$What, [int]$Width, [int]$Height) {
    for ($i = 0; $i -lt 8; $i++) {
        $nodes = Get-WalkScreen "before $What" -AllowPause
        $button = @(Find-UiNode -Nodes $nodes -Label $Label | Where-Object Clickable) | Select-Object -First 1
        if ($button) { return $button }
        if ($nodes -and -not (Find-WalkNav $nodes $WalkLabels.Home $Height)) {
            # Off the feed (a profile, a LIVE): Back to it, since swipes there never find a button.
            Write-WalkLog "off the feed before $What"
            if (-not (Restore-WalkTabs $What $Height)) { return $null }
            continue
        }
        Invoke-WalkSwipe $Width $Height
        if (-not (Test-WalkAlive "$What swipe")) { return $null }
    }
    return $null
}

function Complete-WalkScreen([string]$Name) {
    if (Test-WalkAlive $Name) {
        $reached.Add($Name)
        Save-WalkShot $Name
        Write-WalkLog "reached $Name"
    } else {
        Write-WalkLog "lost $appName at $Name"
    }
}

# Leaves sheets, pages and the keyboard with Back until the bottom tabs show, never Back from
# a feed tab itself, which would close TikTok.
function Restore-WalkTabs([string]$From, [int]$Height) {
    for ($i = 0; $i -lt 6; $i++) {
        Close-WalkKeyboard
        $nodes = Get-WalkScreen "restore-$From" -AllowPause
        if (Find-WalkNav $nodes $WalkLabels.Home $Height) { return $nodes }
        if (-not (Get-WalkForeground).StartsWith("$WalkPackage/", [StringComparison]::Ordinal)) { return @() }
        Invoke-WalkBack $From
    }
    return @()
}

function Select-WalkRow($Nodes, [string]$Label, [string]$Id, [int]$Height) {
    # The band starts under the toolbar, which ends at 0.095 of the height on the S22, where the
    # first row's title (Search settings) sits at 0.117: a 0.12 cutoff never saw it.
    return @(Find-UiNode -Nodes $Nodes -Label $Label -Id $Id |
            Where-Object { $_.Top -gt $Height * 0.10 -and $_.Bottom -lt $Height * 0.95 }) | Select-Object -First 1
}

function Find-WalkRow([string]$Label, [int]$Width, [int]$Height, [string]$Name, [string]$Id) {
    # Settings rows: scroll the page up until the row shows or the top stops moving, then down
    # until it shows. Hushfeed's page rows carry the id "title", which keeps the walk off
    # Hushfeed home's quick setup buttons (one of them is "Privacy", like the page). The way up
    # reads every screen: a header being collapsed sits above its controls, and an expanded Feed
    # filter runs about twelve screens, so eight blind swipes up left the walk under the header.
    $before = ''
    for ($i = 0; $i -lt 30; $i++) {
        $nodes = Get-WalkScreen "$Name-top"
        $row = Select-WalkRow $nodes $Label $Id $Height
        if ($row) { return $row }
        $print = Get-UiFingerprint $nodes
        if ($print -ceq $before) { break }
        $before = $print
        Invoke-WalkSwipe $Width $Height -Down
    }
    $before = ''
    for ($i = 0; $i -lt 12; $i++) {
        $nodes = Get-WalkScreen $Name
        $row = Select-WalkRow $nodes $Label $Id $Height
        if ($row) { return $row }
        $print = Get-UiFingerprint $nodes
        if ($print -ceq $before) { return $null }
        $before = $print
        Invoke-WalkSwipe $Width $Height
    }
    return $null
}

function Get-WalkSectionView([string]$Label, [int]$Width, [int]$Height, [string]$Name) {
    $row = Find-WalkRow $Label $Width $Height $Name 'title'
    if (-not $row) { return $null }
    if ($row.Top -gt $Height * 0.4) {
        # Leave room below the header. Refresh the dump before the next tap. The swipe is slow
        # because a fast one flings on, 1107 px for 711 asked, and carries the row off the top.
        $distance = [int][Math]::Min($row.Top - $Height * 0.25, $Height * 0.45)
        Invoke-WalkAdb shell input swipe ([int]($Width / 2)) ([int]($Height * 0.7)) `
            ([int]($Width / 2)) ([int]($Height * 0.7) - $distance) ($distance * 4) | Out-Null
        Start-Sleep -Milliseconds 1300
    }
    $nodes = Get-WalkScreen $Name
    $row = Select-WalkRow $nodes $Label 'title' $Height
    if (-not $row) { return $null }
    $next = @($nodes | Where-Object { $_.Id -ceq 'title' -and $_.Top -ge $row.Bottom -and
            $_.Bottom -lt $Height * 0.95 -and $_.Text } | Sort-Object Top) | Select-Object -First 1
    return [pscustomobject]@{ Row = $row; Next = $(if ($next) { $next.Text } else { '' }) }
}

function Invoke-WalkScrollToEnd([string]$Name, [int]$Width, [int]$Height, [string]$StopLabel, [string]$StartLabel) {
    $before = ''
    for ($i = 0; $i -lt 40; $i++) {
        $nodes = Get-WalkScreen "$Name-scroll"
        if (-not $nodes) { return $false }
        $cutoff = $Height * 0.12
        if ($StartLabel) {
            $header = @(Find-UiNode -Nodes $nodes -Label $StartLabel -Id 'title') | Select-Object -First 1
            if ($header) { $cutoff = [Math]::Max($cutoff, $header.Bottom) }
        }
        if ($StopLabel -and @(Find-UiNode -Nodes $nodes -Label $StopLabel -Id 'title' |
                Where-Object { $_.Top -ge $cutoff -and $_.Bottom -lt $Height * 0.95 })) { return $true }
        $print = Get-UiFingerprint $nodes
        if ($print -ceq $before) { return -not [bool]$StopLabel }
        $before = $print
        Invoke-WalkSwipe $Width $Height
        if (-not (Test-WalkAlive "$Name scroll")) { return $false }
    }
    return $false
}

function Test-WalkHushfeedHome([string]$HubLabel, [string]$Name) {
    $hub = Get-WalkScreen $Name
    return [bool]@(Find-UiNode -Nodes $hub -Label $HubLabel -Id 'title')
}

# Opens Hushfeed's home: Profile's menu > Settings and privacy > Hushfeed first, then a long press
# on Home (HomeTabSettingsShortcut) when that route has no Hushfeed row.
function Open-WalkHushfeed([string]$HubLabel, [int]$Width, [int]$Height) {
    $nodes = Get-WalkScreen 'profile'
    $menu = @(Find-UiNode -Nodes $nodes -Label $WalkLabels.ProfileMenu | Where-Object { $_.Top -lt $Height * 0.15 }) | Select-Object -First 1
    if (-not $menu) { Write-WalkLog 'no Profile menu button' }
    else {
        Invoke-WalkTap $menu 'Profile menu' 2500
        $sheet = Get-WalkScreen 'profile menu'
        $settings = @(Find-UiNode -Nodes $sheet -Label $WalkLabels.SettingsAndPrivacy) | Select-Object -First 1
        if (-not $settings) { Write-WalkLog 'no Settings and privacy in the profile menu' }
        else {
            Invoke-WalkTap $settings 'Settings and privacy' 3000
            $row = Find-WalkRow '^Hushfeed$' $Width $Height 'settings and privacy'
            if (-not $row) { Write-WalkLog 'no Hushfeed row in Settings and privacy' }
            else {
                Invoke-WalkTap $row 'Hushfeed' 3000
                if (Test-WalkHushfeedHome $HubLabel 'hushfeed home') { return $true }
                Write-WalkLog 'the Hushfeed row opened no Hushfeed home'
            }
        }
    }
    $nodes = Restore-WalkTabs 'the settings route' $Height
    $homeNav = Find-WalkNav $nodes $WalkLabels.Home $Height
    if (-not $homeNav) { Write-WalkLog 'no Home tab to long press'; return $false }
    if (-not (Test-WalkTapAllowed -Node $homeNav)) { throw "Refused to long press '$($homeNav.Text)$($homeNav.Desc)'." }
    Write-WalkLog "long press Home at $($homeNav.X),$($homeNav.Y)"
    Invoke-WalkAdb shell input swipe $homeNav.X $homeNav.Y $homeNav.X $homeNav.Y 1000 | Out-Null
    Start-Sleep -Seconds 3
    if (Test-WalkHushfeedHome $HubLabel 'hushfeed home from Home') { return $true }
    Write-WalkLog "Home's long press opened no Hushfeed home"
    return $false
}

$hushfeedPages = @('Feed filter', 'Feed tabs', 'Feed screen', 'Playback', 'Screen time', 'Comments', 'Downloads',
    'Share sheet', 'Inbox', 'Privacy', 'Region', 'App', 'Diagnostics', 'Backup and restore', 'About Hushfeed')
foreach ($screen in Get-WalkPlannedScreens -AppProfile $appProfile -HushfeedPages $hushfeedPages) { $planned.Add($screen) }

$started = [DateTimeOffset]::UtcNow
$device = ''; $appVersion = ''; $installedSha256 = ''; $installedPackage = ''; $installedVersionCode = ''
$crashes = @()
$script:lease = New-DeviceLease -LeaseDir $LeaseDir -Serial $Serial -Project 'hushfeed' -ChatIdentity $ChatIdentity `
    -Purpose 'whole-app walk' -LeaseMinutes 30
try {
    Invoke-WalkAdb shell input keyevent KEYCODE_WAKEUP | Out-Null
    $device = ((Invoke-WalkAdb shell getprop ro.product.model) -join '').Trim()
    $installed = Get-WalkInstalledIdentity
    $installedSha256 = $installed.sha256; $installedPackage = $installed.package
    $appVersion = $installed.versionName; $installedVersionCode = $installed.versionCode
    foreach ($field in @('package','versionName','versionCode','sha256')) {
        if ([string]$installed.$field -cne [string]$walkBuild.apk.$field) {
            throw "The installed app $field does not match the verified walked build."
        }
    }
    Write-WalkLog "$device, $appName $appVersion, APK $installedSha256"

    Invoke-WalkAdb shell am force-stop $WalkPackage | Out-Null
    # Other sessions read this phone's logs too, so the walk reads only what follows its start
    # (the phone's own clock, which logcat's -t takes as seconds.millis) and never clears them.
    $logSince = ((Invoke-WalkAdb shell date +%s) -join '').Trim()
    if ($logSince -notmatch '^\d+$') { throw "The phone's clock read '$logSince', not seconds." }
    Invoke-WalkAdb shell monkey -p $WalkPackage -c android.intent.category.LAUNCHER 1 | Out-Null
    $script:appPid = ''
    for ($i = 0; $i -lt 20 -and -not $script:appPid; $i++) { Start-Sleep -Seconds 1; $script:appPid = Get-WalkPid }
    if (-not $script:appPid) { throw "$appName did not start." }
    Start-Sleep -Seconds 8
    if (-not (Get-WalkForeground).StartsWith("$WalkPackage/", [StringComparison]::Ordinal)) {
        $focus = ((Invoke-WalkAdb shell 'dumpsys window | grep mCurrentFocus=Window') -join ' ').Trim()
        throw "$appName isn't in front after launch ($focus). If the phone is locked, unlock $Serial and run the walk again."
    }

    $feed = @(); $W = 0; $H = 0
    for ($i = 0; $i -lt 8 -and -not $feed; $i++) {
        $nodes = Get-WalkScreen 'launch' -AllowPause
        if ($nodes) {
            $W = ($nodes | Measure-Object -Property Right -Maximum).Maximum
            $H = ($nodes | Measure-Object -Property Bottom -Maximum).Maximum
        }
        if ($nodes -and (Find-WalkNav $nodes $WalkLabels.Home $H)) { $feed = $nodes; break }
        # A fresh install can open on a sign-in sheet or an age question first. Close the sheet
        # and answer the age question; nothing here signs in or touches an account.
        $gate = @(Find-UiNode -Nodes $nodes -Label $WalkLaunchGateLabel) | Select-Object -First 1
        if ($gate) { Invoke-WalkTap $gate "launch gate '$($gate.Text)$($gate.Desc)'" 2500 } else { Start-Sleep -Seconds 2 }
    }
    if (-not $feed) { throw "$appName's bottom tabs never showed after launch." }
    Complete-WalkScreen 'launch'

    # The main feed, with swipes.
    $mainName = $appProfile.MainFeedName
    $main = @(Find-UiNode -Nodes $feed -Label $WalkLabels[$appProfile.MainFeed] | Where-Object { $_.Top -lt $H * 0.15 }) | Select-Object -First 1
    if ($main -and -not $main.Selected) { Invoke-WalkTap $main $mainName 3000 }
    $alive = $true
    for ($i = 0; $i -lt 5 -and $alive; $i++) {
        Invoke-WalkSwipe $W $H
        Start-Sleep -Milliseconds 1500
        $alive = Test-WalkAlive "$mainName swipe $($i + 1)"
    }
    if ($alive) { Complete-WalkScreen "feed: $mainName" }

    # The comment and share sheets, opened and closed with Back.
    foreach ($sheet in @(@{ Name = 'sheet: comments'; Button = $WalkLabels.CommentsButton; Marker = $WalkLabels.CommentsSheet },
            @{ Name = 'sheet: share'; Button = $WalkLabels.ShareButton; Marker = $WalkLabels.ShareSheet })) {
        $button = Find-WalkVideoButton $sheet.Button $sheet.Name $W $H
        if (-not $button) { Write-WalkLog "no button for $($sheet.Name)"; continue }
        Invoke-WalkTap $button $sheet.Name 2500
        $open = Get-WalkScreen $sheet.Name
        $marker = @(Find-UiNode -Nodes $open -Label $sheet.Marker | Where-Object { $_.Top -gt $H * 0.25 }) | Select-Object -First 1
        if ($marker -or -not $open) { Complete-WalkScreen $sheet.Name } else { Write-WalkLog "$($sheet.Name) didn't show" }
        Close-WalkKeyboard
        Invoke-WalkBack $sheet.Name
        $null = Restore-WalkTabs $sheet.Name $H
    }

    # The other top feeds, each then back to the main feed.
    foreach ($other in $appProfile.OtherFeeds) {
        $nodes = Get-WalkScreen "before $other" -AllowPause
        $tabNode = @(Find-UiNode -Nodes $nodes -Label $WalkLabels[$other] | Where-Object { $_.Top -lt $H * 0.15 }) | Select-Object -First 1
        if (-not $tabNode) { Write-WalkLog "no $other feed tab"; continue }
        Invoke-WalkTap $tabNode $other 3000
        $alive = Test-WalkAlive $other
        for ($i = 0; $i -lt 2 -and $alive; $i++) { Invoke-WalkSwipe $W $H; Start-Sleep -Milliseconds 1500; $alive = Test-WalkAlive "$other swipe $($i + 1)" }
        if ($alive) { Complete-WalkScreen "feed: $other" }
        $nodes = Restore-WalkTabs $other $H
        $main = @(Find-UiNode -Nodes $nodes -Label $WalkLabels[$appProfile.MainFeed] | Where-Object { $_.Top -lt $H * 0.15 }) | Select-Object -First 1
        if ($main) { Invoke-WalkTap $main $mainName 2500 }
    }

    # Search with a harmless query.
    $nodes = Get-WalkScreen 'before search' -AllowPause
    $search = @(Find-UiNode -Nodes $nodes -Label $WalkLabels.Search | Where-Object { $_.Top -lt $H * 0.15 }) | Select-Object -First 1
    if ($search) {
        Invoke-WalkTap $search 'Search' 2500
        $page = Get-WalkScreen 'search page'
        $field = @($page | Where-Object { $_.Class -match 'EditText' }) | Select-Object -First 1
        if ($field) {
            Invoke-WalkAdb shell input text $Query | Out-Null
            Invoke-WalkAdb shell input keyevent KEYCODE_ENTER | Out-Null
            Start-Sleep -Seconds 4
            Complete-WalkScreen 'search'
        }
        $null = Restore-WalkTabs 'search' $H
    }

    # The other tabs, Inbox's Activity page, and Profile.
    foreach ($tab in $appProfile.Tabs) {
        $nodes = Restore-WalkTabs "before $tab" $H
        $nav = Find-WalkNav $nodes $WalkLabels[$tab] $H
        if (-not $nav) { Write-WalkLog "no $tab tab"; continue }
        Invoke-WalkTap $nav $tab 3500
        $page = Get-WalkScreen "tab $tab" -AllowPause
        if ($page) { Complete-WalkScreen "tab: $tab" }
        if ($tab -eq 'Inbox' -and $appProfile.Activity) {
            $activity = @(Find-UiNode -Nodes $page -Label $WalkLabels.ActivityRow | Where-Object { $_.Top -gt $H * 0.1 -and $_.Bottom -lt $H * 0.9 }) | Select-Object -First 1
            if (-not $activity) { Write-WalkLog 'no Activity row in Inbox' }
            if ($activity) {
                Invoke-WalkTap $activity 'Activity' 3000
                $open = Get-WalkScreen 'inbox Activity'
                if (@(Find-UiNode -Nodes $open -Label $WalkLabels.ActivityTitle | Where-Object { $_.Top -lt $H * 0.15 })) { Complete-WalkScreen 'inbox: Activity' }
                else { Write-WalkLog "Activity opened no page titled Activity" }
                Close-WalkKeyboard
                Invoke-WalkBack 'Activity'
            }
        }
    }

    # Hushfeed settings: expand and collapse each section on the same main page.
    $searchLabel = Get-WalkTitleLabel 'Search settings' $translations
    if (Open-WalkHushfeed $searchLabel $W $H) {
        Complete-WalkScreen 'settings: Hushfeed'
        $boundaryTitles = @($hushfeedPages) + @('Feature Gate Lab', 'Hushfeed', 'Report a problem', 'Support Hushfeed')
        $boundaryLabel = (@($boundaryTitles | ForEach-Object { Get-WalkTitleLabel $_ $translations }) -join '|')
        foreach ($title in $hushfeedPages) {
            $label = Get-WalkTitleLabel $title $translations
            $closed = Get-WalkSectionView $label $W $H "closed $title"
            if (-not $closed) { Write-WalkLog "no row for $title"; continue }
            if (-not $closed.Next -or $closed.Next -cnotmatch $boundaryLabel) { $faults.Add("$title was not collapsed before the walk"); break }
            Invoke-WalkTap $closed.Row "expand $title" 2500 $label
            $expanded = Get-WalkSectionView $label $W $H "expanded $title"
            if (-not $expanded -or -not $expanded.Next -or $expanded.Next -ceq $closed.Next -or
                    $expanded.Next -cmatch $boundaryLabel) { $faults.Add("$title showed no inline controls"); break }
            # The exact following row is the boundary. About also has a child named Hushfeed.
            $stopLabel = '(?-i)^' + [regex]::Escape($closed.Next) + '$'
            if (-not (Invoke-WalkScrollToEnd $title $W $H $stopLabel $label)) { $faults.Add("$title did not reach its final controls"); break }
            Save-WalkShot "$title expanded"
            $row = Find-WalkRow $label $W $H "collapse $title" 'title'
            if (-not $row) { $faults.Add("$title lost its inline header"); break }
            Invoke-WalkTap $row "collapse $title" 2000 $label
            $collapsed = Get-WalkSectionView $label $W $H "collapsed $title"
            if (-not $collapsed -or $collapsed.Next -cne $closed.Next) { $faults.Add("$title did not hide its inline controls"); break }
            Complete-WalkScreen "settings: $title"
        }
        $row = Find-WalkRow $searchLabel $W $H 'home for search' 'title'
        if ($row) {
            Invoke-WalkTap $row 'Search settings' 2500 $searchLabel
            # English words stay searchable beside a translation, so "pause" finds
            # the pause rows in any language; they read "pause" or "pausa".
            Invoke-WalkAdb shell input text 'pause' | Out-Null
            Start-Sleep -Seconds 2
            $results = Get-WalkScreen 'settings search'
            if (@(Find-UiNode -Nodes $results -Label '(?i)paus' | Where-Object { $_.Class -notmatch 'EditText' })) { Complete-WalkScreen 'settings: Search settings' }
            else { Write-WalkLog "settings search for 'pause' found nothing" }
            Close-WalkKeyboard
            Invoke-WalkBack 'Search settings'
        }
    }

    # Back to the feed for the last look at the app's health.
    $nodes = Restore-WalkTabs 'settings' $H
    $nav = Find-WalkNav $nodes $WalkLabels.Home $H
    if ($nav) { Invoke-WalkTap $nav 'Home' 3000 }
    Start-Sleep -Seconds 5
    [void](Test-WalkAlive 'the end of the walk')

    $finishedInstall = Get-WalkInstalledIdentity
    foreach ($field in @('package','versionName','versionCode','sha256')) {
        if ([string]$finishedInstall.$field -cne [string]$installed.$field) { $faults.Add("the installed app $field changed during the walk") }
    }

    $logcat = @(Invoke-WalkAdb logcat -d -b crash -t "$logSince.000") +
        @(Invoke-WalkAdb logcat -d -b main -b system -t "$logSince.000" -s 'AndroidRuntime:E' 'ActivityManager:E' 'libc:F' 'DEBUG:F')
    Set-Content -LiteralPath (Join-Path $work 'logcat-crashes.txt') -Value $logcat -Encoding UTF8
    $crashes = @(Get-WalkCrashes -Lines $logcat -Package $WalkPackage)
} catch {
    $faults.Add("the walk stopped: $($_.Exception.Message)")
    Write-WalkLog "stopped: $($_.Exception.Message)"
} finally {
    try {
        Invoke-WalkAdb shell "rm -f $remoteDump /sdcard/hushfeed-walk.png" | Out-Null
    } catch { Write-Warning $_.Exception.Message }
    Remove-DeviceLease -Lease $script:lease -WarnOnFailure
}

$verdict = Get-WalkVerdict -Planned $planned -Reached $reached -Crashes $crashes -Faults $faults
if (-not $device) { $device = 'unknown' }
$receipt = New-WalkReceipt -WalkProfile $receiptProfile -Result $verdict.Result -Full $verdict.Full -Serial $Serial -Device $device -AppVersion $appVersion `
    -InstalledPackage $installedPackage -InstalledVersionCode $installedVersionCode -NativeBuild $walkBuild `
    -BundleSha256 $bundleSha256 -InstalledApkSha256 $installedSha256 -Started $started -Finished ([DateTimeOffset]::UtcNow) `
    -Screens $reached.Count -Crashes $crashes.Count -Problems $verdict.Problems
[IO.File]::WriteAllText($Out, (($receipt | ConvertTo-Json -Depth 20) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
foreach ($problem in $verdict.Problems) { Write-WalkLog "problem: $problem" }
Write-WalkLog "$($reached.Count) of $($planned.Count) screens, $($crashes.Count) crashes. Receipt: $Out"
Write-WalkLog $verdict.Result
if ($verdict.Result -ne 'PASS') { exit 1 }
