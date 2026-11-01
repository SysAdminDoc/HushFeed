#Requires -Version 7
<#
.SYNOPSIS
    One command for a Hushfeed release: cut, test, bundle, apply, walk, publish, index.

.DESCRIPTION
    Works in its own detached worktree under the temp folder, made from origin/main, so the main
    checkout can hold anything. A rerun picks up at the first unfinished step. A fix goes in the
    worktree as a commit; the rerun then rebuilds the bundle and repeats apply and walk, not the
    tests. Commits landing on main after the cut stay out of this release. A walk takes the first
    free device and never waits for one, and anything but PASS stops the run.

    Device, key and fixture details live in the untracked JSON file $env:HUSH_RELEASE_CONFIG
    names, under this repo's name:
      { "hushfeed": { "fixtureDirs": ["..."], "labels": { "<serial>": "a Galaxy phone" },
                      "keys": { "default": { "Keystore": "...", "KeyAlias": "...", "KeystoreType": "...",
                                             "KeystorePassword": "", "KeyPassword": "..." } },
                      "walk": { "<package>": ["<serial>", "avd:<name>"] } } }
    With $env:HUSH_RELEASE_STATE_DIR set, <repo>-<version>.json there names the running step.

.PARAMETER Summary
    The release text. Paragraphs before the first heading are the notes intro. Optional sections:
    "## README", "## Manager" and "## Checked" (bullets added under the generated list). Missing
    sections fall back to the first paragraph.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version,
    [string]$Summary,
    [ValidateSet('cut', 'test', 'bundle', 'apply', 'walk', 'publish', 'index')][string]$From,
    [switch]$DryRun,
    # Holds the release for something only the owner can do, with one line saying what.
    [string]$NeedsMatt,
    [switch]$Abandon,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../common.ps1')
. (Join-Path $PSScriptRoot '../patch-target.ps1')

# What differs from repo to repo.
$Repo = @{
    Name = 'hushfeed'; Title = 'Hushfeed'; Repository = 'SysAdminDoc/HushFeed'; Prefix = 'HUSHFEED'; Verify = 'com.zhiliaoapp.musically'
    Tests = @('--continue', ':extensions:tiktok:test', ':patches:test', ':extensions:shared:library:lint', ':extensions:tiktok:lint')
}

# A background job starts with whatever its parent had, and a shell opened before a User variable
# changed still holds the old value.
$env:PATH = @($env:PATH, [Environment]::GetEnvironmentVariable('PATH', 'Machine'), [Environment]::GetEnvironmentVariable('PATH', 'User')) -join ';'
foreach ($entry in [Environment]::GetEnvironmentVariables('User').GetEnumerator()) {
    if ($entry.Key -match '^(HUSH|BUILD_QUEUE_SCRIPT$|JAVA_HOME$|ANDROID_HOME$)') { Set-Item "Env:$($entry.Key)" $entry.Value }
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$env:PATH = (Join-Path $env:ANDROID_HOME 'platform-tools') + ';' + $env:PATH
$env:BUILD_QUEUE_PRIORITY = 'release'
if ($env:BUILD_QUEUE_SCRIPT -and (Test-Path -LiteralPath $env:BUILD_QUEUE_SCRIPT -PathType Leaf)) { . $env:BUILD_QUEUE_SCRIPT }

$Steps = 'cut', 'test', 'bundle', 'apply', 'walk', 'publish', 'index'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$tag = "v$Version"
$work = Join-Path ([IO.Path]::GetTempPath()) "hush-release/$($Repo.Name)-$Version"
$stateDir = Join-Path $work "build/release/$Version"
$statePath = Join-Path $stateDir 'state.json'
$assets = Join-Path $stateDir 'assets'
$bundleName = "patches-$Version.mpp"
$bundlePath = Join-Path $assets $bundleName
$statusFile = if ($env:HUSH_RELEASE_STATE_DIR) { Join-Path $env:HUSH_RELEASE_STATE_DIR "$($Repo.Name)-$Version.json" }

function Say([string]$Text) { Write-Host "[release] $Text" -ForegroundColor Cyan }
function Get-RepoEnv([string]$Name) { [Environment]::GetEnvironmentVariable("$($Repo.Prefix)_$Name") }
function Get-PatchList { Get-Content -LiteralPath (Join-Path $work 'patches-list.json') -Raw | ConvertFrom-Json }
function Get-Targets { @(Get-DeclaredPatchTargets -PatchList (Get-PatchList)) }
function Save-State { $state | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $statePath -Encoding utf8NoBOM }

function Get-MachineConfig {
    if (-not $env:HUSH_RELEASE_CONFIG -or -not (Test-Path -LiteralPath $env:HUSH_RELEASE_CONFIG)) { return $null }
    return (Get-Content -LiteralPath $env:HUSH_RELEASE_CONFIG -Raw | ConvertFrom-Json).($Repo.Name)
}

function Set-Status([string]$Step, [string]$Status, [string]$Note = '') {
    if (-not $statusFile) { return }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $statusFile) | Out-Null
    [ordered]@{ repo = $Repo.Name; version = $Version; step = $Step; status = $Status; pid = $PID; log = (Join-Path $stateDir 'release.log'); note = $Note } |
        ConvertTo-Json | Set-Content -LiteralPath $statusFile -Encoding utf8NoBOM
}

function Invoke-Checked([string]$What, [scriptblock]$Command) {
    $global:LASTEXITCODE = 0
    & $Command 2>&1 | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit $LASTEXITCODE)" }
}

# Plain functions, so every dash word reaches the program.
function Invoke-Program {
    $global:LASTEXITCODE = 0
    $program, $rest = $args
    & $program @rest 2>&1 | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) { throw "$($args -join ' ') failed (exit $LASTEXITCODE)" }
}
function Invoke-Git { Invoke-Program git.exe -C $work @args }
function Invoke-ReleaseText { Invoke-Program py.exe '-3.13' -X utf8 (Join-Path $work 'tools/release_text.py') @args }

function Invoke-Gradle([string[]]$Tasks) {
    $wrapper = Get-RepoEnv 'BUILD_WRAPPER'
    Invoke-Checked "Gradle $($Tasks -join ' ')" { & $wrapper -ProjectDir $work -Tasks $Tasks }
}

function Invoke-Queued([string]$Label, [scriptblock]$Command) {
    if (Get-Command Invoke-InBuildQueue -ErrorAction SilentlyContinue) {
        $code = Invoke-InBuildQueue -Label "$($Repo.Name) $Label" -Priority release -ScriptBlock $Command
        if ($code -ne 0) { throw "$Label failed (exit $code)" }
    } else { Invoke-Checked $Label $Command }
}

function Get-Apk([string]$Package, [string]$PackageVersion) {
    $folders = @((Get-RepoEnv 'FIXTURE_DIR')) + @((Get-MachineConfig).fixtureDirs) | Where-Object { $_ }
    foreach ($folder in $folders) {
        $found = @(Get-ChildItem -LiteralPath $folder -Filter "$($Package)_$PackageVersion-*.apk" -File -ErrorAction SilentlyContinue | Sort-Object Name)
        if ($found) { return $found[0].FullName }
    }
    throw "No $Package $PackageVersion APK in: $($folders -join ', ')"
}

function Read-ReleaseText {
    $text = (Get-Content -LiteralPath (Join-Path $stateDir 'summary.md') -Raw) -replace "`r`n", "`n"
    $parts = @{ Intro = ($text -split '(?m)^## ', 2)[0].Trim(); README = ''; Manager = ''; Checked = '' }
    foreach ($match in [regex]::Matches($text, '(?ms)^## (README|Manager|Checked)\s*\n(.*?)(?=^## |\z)')) {
        $parts[$match.Groups[1].Value] = $match.Groups[2].Value.Trim()
    }
    if (-not $parts.README) { $parts.README = ($parts.Intro -split "`n`n", 2)[0] }
    if (-not $parts.Manager) { $parts.Manager = $parts.Intro }
    return $parts
}

function Write-Text([string]$Name, [string]$Text) {
    $path = Join-Path $stateDir $Name
    [IO.File]::WriteAllText($path, $Text + "`n", [Text.UTF8Encoding]::new($false))
    return $path
}

function Push-Main {
    # Rebasing a tagged fix would leave the tag off main, so that case merges instead.
    foreach ($attempt in 1, 2) {
        Invoke-Git fetch -q origin main
        $tagged = git.exe -C $work tag --list $tag
        $global:LASTEXITCODE = 0
        if ($tagged) { git.exe -C $work merge-base --is-ancestor $tag origin/main }
        if ($tagged -and $LASTEXITCODE -ne 0) { Invoke-Git merge --no-edit origin/main } else { Invoke-Git rebase origin/main }
        git.exe -C $work push origin HEAD:main 2>&1 | ForEach-Object { Write-Host $_ }
        if ($LASTEXITCODE -eq 0) { return }
    }
    throw 'Could not push to main twice in a row.'
}

function Step-cut {
    $properties = Join-Path $work 'gradle.properties'
    $changelog = Get-Content -LiteralPath (Join-Path $work 'CHANGELOG.md') -Raw
    if ((Get-Content -LiteralPath $properties -Raw) -match "(?m)^version = $([regex]::Escape($Version))\s*$" -and $changelog -match "(?m)^## $([regex]::Escape($Version)) \(") {
        Say "main already reads $Version"
        return
    }
    Invoke-ReleaseText cut --version $Version
    [IO.File]::WriteAllText($properties, ([IO.File]::ReadAllText($properties) -replace '(?m)^version = \d+\.\d+\.\d+', "version = $Version"))
    $readme = Join-Path $work 'README.md'
    [IO.File]::WriteAllText($readme, ([IO.File]::ReadAllText($readme) -replace 'badge/version-\d+\.\d+\.\d+-', "badge/version-$Version-"))
    Invoke-Gradle @(':patches:generatePatchesList')
    Invoke-ReleaseText guide
    Invoke-Program py.exe '-3.13' -X utf8 (Join-Path $work 'tools/gen-release-notes.py')
    Invoke-Git add -A
    Invoke-Git commit -q -m "chore(release): prepare $Version for publication"
    Push-Main
}

function Step-test { Invoke-Gradle $Repo.Tests }

function Step-bundle {
    Invoke-Gradle @(':patches:buildAndroid')
    $built = Join-Path $work "patches/build/release/$bundleName"
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($built)
    try { $hasDex = [bool]($zip.Entries | Where-Object FullName -EQ 'classes.dex') } finally { $zip.Dispose() }
    if (-not $hasDex) { throw 'The bundle has no classes.dex, so Manager would load no patches.' }
    # Manager pins the first key a source signs with and refuses any other.
    $signature = Get-BundleSignature -Path $built
    if ($null -eq $signature -or $signature.PublicKey -ne (Get-BundlePublicKey -Root $work)) {
        throw "The bundle isn't signed by morphe-signing.pub. Set $($Repo.Prefix)_BUNDLE_SIGNING_KEY."
    }
    New-Item -ItemType Directory -Force -Path $assets | Out-Null
    Copy-Item -LiteralPath $built -Destination $assets -Force
    $state.bundle = (Get-FileHash -Algorithm SHA256 -LiteralPath $bundlePath).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $assets 'SHA256SUMS.txt'), "$($state.bundle)  $bundleName`n", [Text.UTF8Encoding]::new($false))
    $state.head = (git.exe -C $work rev-parse HEAD).Trim()
    Say "$($state.bundle)  $bundleName"
}

function Step-apply {
    foreach ($target in Get-Targets) {
        foreach ($packageVersion in @($target.PackageVersions)) {
            $apk = Get-Apk $target.PackageName $packageVersion
            $scratch = Join-Path $stateDir "apply-$($target.PackageName)-$packageVersion"
            New-Item -ItemType Directory -Force -Path $scratch | Out-Null
            Say "applying every patch to $($target.PackageName) $packageVersion"
            Invoke-Queued "apply $($target.PackageName)" {
                & (Join-Path $work 'scripts/verify-all-patches.ps1') -Apk $apk -DesktopJar (Get-RepoEnv 'DESKTOP_JAR') -WorkDir $scratch -Bundle $bundlePath
            }
            Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

function Test-DeviceFree([string]$Device) {
    $lease = Join-Path (Get-RepoEnv 'DEVICE_LEASE_DIR') "$Device.json"
    if (-not (Test-Path -LiteralPath $lease)) { return $true }
    # Anything unreadable in someone else's lease file counts as busy.
    $record = try { Get-Content -LiteralPath $lease -Raw | ConvertFrom-Json -DateKind String } catch { $null }
    $until = [DateTimeOffset]::MinValue
    if (-not $record -or -not [DateTimeOffset]::TryParse([string]$record.expiresUtc, [ref]$until) -or $until -gt [DateTimeOffset]::UtcNow) {
        Say "$Device is leased to $($record.project) ($($record.purpose))"
        return $false
    }
    Say "$Device has a lapsed lease from $($record.project), clearing it"
    Remove-Item -LiteralPath $lease -Force
    return $true
}

function Get-Attached { @(adb.exe devices | Select-String '^(\S+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value }) }

function Start-Avd([string]$Name) {
    foreach ($running in Get-Attached | Where-Object { $_ -like 'emulator-*' }) {
        if ("$(adb.exe -s $running emu avd name 2>$null)" -like "$Name *") {
            if (Test-DeviceFree $running) { return @{ Serial = $running; Started = $false } }
            return $null
        }
    }
    $started = "emulator-$(5600 + 2 * (Get-Random -Maximum 40))"
    Say "starting $Name on $started"
    Start-Process (Join-Path $env:ANDROID_HOME 'emulator/emulator.exe') -WindowStyle Hidden -ArgumentList @(
        '-avd', $Name, '-port', $started.Substring(9), '-no-window', '-no-audio', '-no-boot-anim', '-no-snapshot-save', '-gpu', 'host', '-cores', '2', '-memory', '3072')
    $deadline = (Get-Date).AddMinutes(5)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 5
        if ("$(adb.exe -s $started shell getprop sys.boot_completed 2>$null)" -match '1') { return @{ Serial = $started; Started = $true } }
    }
    adb.exe -s $started emu kill 2>$null | Out-Null
    throw "$Name didn't boot in five minutes."
}

function Select-Device([string]$Package) {
    [string[]]$candidates = if ($Serial) { @($Serial) } else { @((Get-MachineConfig).walk.$Package) }
    if (-not $candidates) { throw "No walk devices configured for $Package." }
    $attached = Get-Attached
    foreach ($candidate in $candidates) {
        if ($candidate -like 'avd:*') {
            $avd = Start-Avd $candidate.Substring(4)
            if ($avd) { return $avd }
        } elseif ($candidate -in $attached -and (Test-DeviceFree $candidate)) {
            return @{ Serial = $candidate; Started = $false }
        }
    }
    throw "Every device for $Package is busy or offline: $($candidates -join ', '). Check whether a lease holder is idle, then rerun."
}

function Step-walk {
    $config = Get-MachineConfig
    $appNames = (Get-PatchList).appNames
    Set-Item "Env:$($Repo.Prefix)_CHAT_IDENTITY" "release-$($Repo.Name)-$Version"
    foreach ($target in Get-Targets) {
        $package = $target.PackageName
        # A walk that passed on this exact bundle stands.
        if ($state.walks[$package] -and $state.walks[$package].bundle -eq $state.bundle) { Say "$package already walked on this bundle"; continue }
        $device = Select-Device $package
        $key = if ($config.keys.($device.Serial)) { $config.keys.($device.Serial) } else { $config.keys.default }
        $patchArgs = @{ Apk = Get-Apk $package $target.PackageVersion; OutDir = (Join-Path $stateDir "patched-$package") }
        foreach ($property in $key.PSObject.Properties) { $patchArgs[$property.Name] = $property.Value }
        New-Item -ItemType Directory -Force -Path $patchArgs.OutDir | Out-Null
        $receipt = Join-Path $stateDir "walk-$package.json"
        Say "walking $package on $($device.Serial)"
        try {
            Invoke-Queued "walk $package" {
                & (Join-Path $work 'scripts/walk-app.ps1') -Serial $device.Serial -Package $package -Bundle $bundlePath -Out $receipt -PatchArgs $patchArgs
            }
            # ART's verifier reads every class, so it sees a bad hook on a screen the walk never opened.
            if ($package -eq $Repo.Verify) { Invoke-Queued "verify $package" { & (Join-Path $work 'scripts/verify-injected-registers.ps1') -CleanApk $patchArgs.Apk -PatchedApk (Resolve-Path (Join-Path $patchArgs.OutDir '*-signed.apk')).Path -Serial $device.Serial } }
        } catch {
            $why = if (Test-Path -LiteralPath $receipt) { @((Get-Content -LiteralPath $receipt -Raw | ConvertFrom-Json).problems) -join '; ' }
            throw "$package didn't pass the walk or the verifier: $why ($($_.Exception.Message))"
        } finally {
            if ($device.Started) { adb.exe -s $device.Serial emu kill 2>$null | Out-Null }
            Remove-Item -LiteralPath $patchArgs.OutDir -Recurse -Force -ErrorAction SilentlyContinue
        }
        $walk = Get-Content -LiteralPath $receipt -Raw | ConvertFrom-Json
        if ($walk.result -ne 'PASS') { throw "The walk of $package ended $($walk.result): $($walk.problems -join '; ')" }
        $label = if ($config.labels.($device.Serial)) { $config.labels.($device.Serial) } else { 'an Android emulator' }
        $state.walks[$package] = @{ app = $appNames.$package; version = $target.PackageVersion; device = $label; screens = $walk.screens; patches = @($target.PatchNames).Count; bundle = $state.bundle }
        Save-State
    }
}

function Step-publish {
    $text = Read-ReleaseText
    $checked = @(foreach ($walk in $state.walks.Values) {
        "* All $($walk.patches) $($walk.app) patches applied to $($walk.app) $($walk.version) with none failing."
        "* This exact bundle was patched onto $($walk.app) $($walk.version) and run on $($walk.device). A walk through the whole app opened $($walk.screens) screens with no crash."
    }) -join "`n"
    if ($text.Checked) { $checked += "`n" + $text.Checked }
    $notes = Join-Path $stateDir 'notes.md'
    Invoke-ReleaseText github-notes --version $Version --intro (Write-Text 'intro.txt' $text.Intro) --checked (Write-Text 'checked.txt' $checked) --out $notes
    $head = (git.exe -C $work rev-parse HEAD).Trim()
    $remote = ((git.exe -C $work ls-remote origin "refs/tags/$tag") -split '\s+')[0]
    if ($remote -and $remote -ne $head) { throw "$tag on GitHub is $remote, not $head." }
    if (-not $remote) { Invoke-Git tag -f $tag $head; Invoke-Git push -q origin "refs/tags/$tag" }
    $repository = $Repo.Repository
    $release = gh release view $tag --repo $repository --json isDraft 2>$null | ConvertFrom-Json
    $global:LASTEXITCODE = 0
    if ($release -and -not $release.isDraft) { Say "$tag is already public"; return }
    if ($release) {
        Invoke-Checked 'release notes' { gh release edit $tag --repo $repository --notes-file $notes }
    } else {
        Invoke-Checked 'draft' { gh release create $tag --repo $repository --draft --verify-tag --title $tag --notes-file $notes }
    }
    $files = @(Get-ChildItem -LiteralPath $assets -File)
    Invoke-Checked 'upload' { gh release upload $tag --repo $repository --clobber @($files.FullName) }
    $back = (New-Item -ItemType Directory -Force -Path (Join-Path $stateDir 'downloaded')).FullName
    Invoke-Checked 'download' { gh release download $tag --repo $repository --dir $back --clobber }
    foreach ($file in $files) {
        $hosted = Join-Path $back $file.Name
        if (-not (Test-Path -LiteralPath $hosted) -or (Get-FileHash -LiteralPath $hosted).Hash -ne (Get-FileHash -LiteralPath $file.FullName).Hash) {
            throw "The hosted $($file.Name) isn't the file that was uploaded. $tag is still a draft."
        }
    }
    Invoke-Checked 'publish' { gh release edit $tag --repo $repository --draft=false --latest }
    Say "$tag is public"
}

function Step-index {
    $text = Read-ReleaseText
    $repository = $Repo.Repository
    $created = (gh release view $tag --repo $repository --json publishedAt --jq .publishedAt) -replace 'Z$', ''
    $apps = @($state.walks.Values | ForEach-Object { $_.app }) -join ' and '
    $devices = @($state.walks.Values | ForEach-Object { $_.device } | Select-Object -Unique) -join ' and '
    Invoke-ReleaseText index --version $Version --created $created --readme-summary (Write-Text 'readme.txt' $text.README) `
        --bundle-summary (Write-Text 'bundle.txt' $text.Manager) --device-line "This bundle ran through a whole-app walk of $apps on $devices with no crash."
    Invoke-Git add -A
    if (git.exe -C $work status --porcelain) { Invoke-Git commit -q -m "chore(release): point Manager to the verified $Version bundle" }
    Push-Main
    $hosted = gh api "repos/$repository/contents/patches-bundle.json" -H 'Accept: application/vnd.github.raw' | ConvertFrom-Json
    if ($hosted.version -ne $Version) { throw "GitHub's patches-bundle.json reads $($hosted.version), not $Version." }
    $description = [string](gh repo view $repository --json description --jq .description)
    $updated = $description -replace "$($Repo.Title) v\d+\.\d+\.\d+", "$($Repo.Title) v$Version" -replace '\b\d+ patches\b', "$(@((Get-PatchList).patches).Count) patches"
    if ($updated -ne $description) { gh repo edit $repository --description $updated }
    $global:LASTEXITCODE = 0
}

function Remove-ReleaseWork {
    Set-Location $root
    git.exe -C $root worktree remove --force $work 2>$null
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
    git.exe -C $root worktree prune
    if ($statusFile -and (Test-Path -LiteralPath $statusFile)) { Remove-Item -LiteralPath $statusFile -Force }
    $global:LASTEXITCODE = 0
}

if ($NeedsMatt) { Set-Status 'hold' 'needs-matt' $NeedsMatt; Say "held: $NeedsMatt"; exit 0 }
if ($Abandon) { Remove-ReleaseWork; Say "dropped the $Version worktree and state"; exit 0 }

$problems = [Collections.Generic.List[string]]::new()
foreach ($needed in 'BUILD_WRAPPER', 'DESKTOP_JAR', 'DEVICE_LEASE_DIR', 'BUNDLE_SIGNING_KEY') {
    if (-not (Get-RepoEnv $needed)) { $problems.Add("$($Repo.Prefix)_$needed isn't set") }
}
if (-not $env:JAVA_HOME) { $problems.Add('JAVA_HOME isn''t set (a JDK 21 or newer)') }
if (-not (Get-MachineConfig)) { $problems.Add("HUSH_RELEASE_CONFIG has no $($Repo.Name) entry") }
if (-not (Test-Path -LiteralPath $stateDir) -and -not $Summary) { $problems.Add('-Summary names the release text') }
if ($Summary -and -not (Test-Path -LiteralPath $Summary)) { $problems.Add("There's no summary at $Summary") }
gh auth status 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { $problems.Add('gh is not signed in') }
$global:LASTEXITCODE = 0

if ($DryRun) {
    if (-not (Test-Path -LiteralPath (Join-Path $work '.git'))) { $work = $root }
    foreach ($target in Get-Targets) {
        foreach ($packageVersion in @($target.PackageVersions)) {
            try { Say "$($target.PackageName) ${packageVersion}: $(Get-Apk $target.PackageName $packageVersion)" } catch { $problems.Add($_.Exception.Message) }
        }
        if (-not (Get-MachineConfig).walk.($target.PackageName)) { $problems.Add("No walk devices configured for $($target.PackageName)") }
    }
    Say "attached: $((Get-Attached) -join ', ')"
    $problems | ForEach-Object { Write-Host "[release] problem: $_" -ForegroundColor Red }
    if ($problems.Count) { exit 1 }
    Say 'ready'; exit 0
}
if ($problems.Count) { throw "Can't start: $($problems -join '; ')" }

$env:MORPHE_SIGNING_KEY = Get-RepoEnv 'BUNDLE_SIGNING_KEY'
$env:GITHUB_ACTOR = gh api user --jq .login
$env:GITHUB_TOKEN = gh auth token

if (-not (Test-Path -LiteralPath (Join-Path $work '.git'))) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $work) | Out-Null
    git.exe -C $root fetch -q origin
    git.exe -C $root worktree prune
    Invoke-Checked 'worktree' { git.exe -C $root worktree add -q --detach $work origin/main }
    $sdk = Join-Path $root 'local.properties'
    if (Test-Path -LiteralPath $sdk) { Copy-Item -LiteralPath $sdk -Destination $work }
}
New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
if ($Summary) { Copy-Item -LiteralPath $Summary -Destination (Join-Path $stateDir 'summary.md') -Force }
Start-Transcript -LiteralPath (Join-Path $stateDir 'release.log') -Append | Out-Null

$state = if (Test-Path -LiteralPath $statePath) { Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json -AsHashtable } else {
    @{ done = @{}; head = ''; bundle = ''; walks = @{}; started = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() }
}
if ($From) { foreach ($step in $Steps[$Steps.IndexOf($From)..($Steps.Count - 1)]) { [void]$state.done.Remove($step) } }
# A fix committed in the worktree needs a new bundle and a new walk, but not another test run.
if ($state.head -and -not $state.done.ContainsKey('publish') -and (git.exe -C $work rev-parse HEAD).Trim() -ne $state.head) {
    Say 'HEAD moved since the bundle was built, so bundle, apply and walk run again'
    foreach ($step in 'bundle', 'apply', 'walk') { [void]$state.done.Remove($step) }
}

foreach ($step in $Steps) {
    if ($state.done.ContainsKey($step)) { continue }
    if ($step -notin 'cut', 'index' -and (git.exe -C $work status --porcelain)) {
        Set-Status $step 'failed' "Uncommitted changes in $work"
        throw "Commit the changes in $work first, then rerun."
    }
    Set-Status $step 'running'
    Say "=== $step"
    $clock = [Diagnostics.Stopwatch]::StartNew()
    try {
        & "Step-$step"
    } catch {
        Save-State
        Set-Status $step 'failed' $_.Exception.Message
        Write-Host "[release] $step failed: $($_.Exception.Message)" -ForegroundColor Red
        Write-Host "[release] fix it in $work, commit, and run this again. It resumes here. Log: $(Join-Path $stateDir 'release.log')"
        Stop-Transcript | Out-Null
        exit 1
    }
    $state.done[$step] = [int]$clock.Elapsed.TotalSeconds
    Save-State
    Say "$step took $([math]::Round($clock.Elapsed.TotalMinutes, 1)) min"
}

$times = ($Steps | ForEach-Object { "$_ $([math]::Round($state.done[$_] / 60, 1))" }) -join ', '
$total = [math]::Round(([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - $state.started) / 60, 1)
Say "$($Repo.Title) $tag is out, $total min from first start to finish ($times)"
if ($env:HUSH_RELEASE_STATE_DIR) { Add-Content -LiteralPath (Join-Path $env:HUSH_RELEASE_STATE_DIR 'history.log') -Value "$(Get-Date -Format s) $($Repo.Name) $tag $total min: $times" }
Stop-Transcript | Out-Null
Remove-ReleaseWork
