<#
.SYNOPSIS
    The mapping pass of a TikTok retarget: old build's names and literals moved to the new build's.

.DESCRIPTION
    Each fixture folder holds one universal APK (and the .apkm the reviewed profiles read);
    -OldFixture defaults to HUSHFEED_FIXTURE_DIR, the declared build's folder. The version name
    and code come from the APKs themselves. In order, into -WorkDir:

      restable.py   every resource of both builds (res-old.tsv, res-new.tsv)
      owners.py     every LX/LY class the tracked code names (owners.txt)
      Propagate     the tracer: class, member and resource id pairs by vote (propagate.tsv)
      rename.py     --write: member refs, then class names, outside history lines (rename.txt)
      swap.py       --write: version name, code and constants, outside history lines (swap.txt)
      identities.py --write: obfuscated-identities.txt follows the code (identities.txt)

    Then it prints the full-bundle patch run that goes past failing patches, so one run lists
    every patch the new build broke. Read every report before building: rename.txt's U lines
    (classes the tracer couldn't pair), M lines (member refs left alone) and R lines (lines that
    named the old build), and swap.txt's H lines (history left alone). Seeds ("old new" class
    pairs, one per line) start the tracer where same-named classes don't reach; -Extra adds
    ClassMatch picks on top of the tracer's own pairs.

    The tracked tree has to be clean: a second rename over an already renamed tree could move a
    name twice. `git checkout -- .` undoes a pass. The tracer holds about -Heap of memory and
    runs at Idle priority. -ReuseTrace keeps the work folder's propagate.tsv from an earlier run
    of the same two builds, for a rerun from a reset tree with -Extra. Run this under pwsh 7.

.EXAMPLE
    scripts/retarget/retarget.ps1 -OldFixture D:\fixtures\tiktok-4791 -NewFixture D:\fixtures\tiktok-4792
#>
[CmdletBinding()]
param(
    [string]$OldFixture,
    [Parameter(Mandatory = $true)][string]$NewFixture,
    [string]$WorkDir,
    [string]$Seeds,
    [string]$Extra,
    [string]$DesktopJar,
    [string]$Java,
    [string]$Aapt2,
    [string]$Heap = '3500m',
    [switch]$ReuseTrace
)

$ErrorActionPreference = 'Stop'
$scripts = Split-Path -Parent $PSScriptRoot
$root = Split-Path -Parent $scripts
. (Join-Path $scripts 'common.ps1')
. (Join-Path $scripts 'Resolve-Java.ps1')
. (Join-Path $scripts 'apk-facts.ps1')

function Get-FixtureApk {
    param([string]$Folder)
    if (-not (Test-Path -LiteralPath $Folder -PathType Container)) { throw "No fixture folder: $Folder" }
    $apks = @(Get-ChildItem -LiteralPath $Folder -Filter '*.apk' -File)
    if ($apks.Count -ne 1) { throw "$Folder should hold one universal .apk, found $($apks.Count)." }
    return $apks[0].FullName
}

function Invoke-RetargetPython {
    param([string]$Log, [string]$Script, [string[]]$Arguments)
    $preference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& py.exe '-3.13' '-X' 'utf8' (Join-Path $PSScriptRoot $Script) @Arguments 2>&1 | ForEach-Object { "$_" })
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $preference
    }
    [IO.File]::WriteAllLines($Log, [string[]]$output)
    if ($code -ne 0) { throw "$Script exited $code; the output is in $Log" }
    return , $output
}

# A shell opened before a User variable changed still holds the old value, and an old desktop
# jar fails the tracer or the patch run on a missing method, so the User values win.
foreach ($name in @('HUSHFEED_DESKTOP_JAR', 'HUSHFEED_FIXTURE_DIR', 'HUSHFEED_AAPT2', 'HUSHFEED_WORKDIR')) {
    $userValue = [Environment]::GetEnvironmentVariable($name, 'User')
    if ($userValue) { Set-Item -Path "Env:$name" -Value $userValue }
}
if (-not $OldFixture) { $OldFixture = $env:HUSHFEED_FIXTURE_DIR }
if (-not $OldFixture) { throw 'Name the old build with -OldFixture or HUSHFEED_FIXTURE_DIR.' }
$oldApk = Get-FixtureApk -Folder $OldFixture
$newApk = Get-FixtureApk -Folder $NewFixture
$Aapt2 = Resolve-Aapt2 -Explicit $Aapt2 -Root $root
$Java = Resolve-Java -Explicit $Java
$DesktopJar = Resolve-DesktopCli -Explicit $DesktopJar -Root $root -Required
$old = Get-ApkManifestFacts -Apk $oldApk -Aapt2 $Aapt2
$new = Get-ApkManifestFacts -Apk $newApk -Aapt2 $Aapt2
foreach ($facts in @(@($oldApk, $old), @($newApk, $new))) {
    # swap.py would read an empty code as "every 1 in the tree".
    if (-not ($facts[1].versionName -match '^\d+(\.\d+)+$') -or -not ($facts[1].versionCode -match '^\d+$')) {
        throw "Couldn't read a version name and code from $($facts[0]) (got '$($facts[1].versionName)', '$($facts[1].versionCode)')."
    }
}
if ($ReuseTrace -and $Seeds) { throw '-Seeds only feeds the tracer; drop -ReuseTrace to trace again with them.' }
if ($old.package -ne $new.package) { throw "The fixtures are different apps: $($old.package) and $($new.package)." }
if ($old.versionName -eq $new.versionName) { throw "Both fixtures are $($old.versionName)." }
Write-Host "[retarget] $($old.package) $($old.versionName) ($($old.versionCode)) -> $($new.versionName) ($($new.versionCode))"

$compat = Join-Path $root 'patches/src/main/kotlin/app/morphe/patches/shared/compat/AppCompatibilities.kt'
if (-not (Select-String -LiteralPath $compat -SimpleMatch "`"$($old.versionName)`"" -Quiet)) {
    # The names in the tree are the declared build's, so a trace from any other pair renames them wrong.
    throw "AppCompatibilities.kt doesn't declare $($old.versionName). Point -OldFixture (or HUSHFEED_FIXTURE_DIR) at the declared build's folder."
}
$dirty = @(& git.exe -C $root status --porcelain --untracked-files=no)
if ($LASTEXITCODE -ne 0) { throw "git status failed in $root" }
if ($dirty.Count -ne 0) {
    throw "The tracked tree has $($dirty.Count) changed files. Commit or undo them first, so the pass starts from one build's names."
}

if (-not $WorkDir) { $WorkDir = Join-Path ([IO.Path]::GetTempPath()) "hushfeed-retarget-$($new.versionName)" }
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
$WorkDir = (Resolve-Path -LiteralPath $WorkDir).Path
Write-Host "[retarget] work folder $WorkDir"

$resOld = Join-Path $WorkDir 'res-old.tsv'
$resNew = Join-Path $WorkDir 'res-new.tsv'
$owners = Join-Path $WorkDir 'owners.txt'
$seedFile = Join-Path $WorkDir 'seeds.tsv'
$propagate = Join-Path $WorkDir 'propagate.tsv'
foreach ($side in @(@($oldApk, $resOld), @($newApk, $resNew))) {
    $null = Invoke-RetargetPython -Log "$($side[1]).log" -Script 'restable.py' -Arguments @($Aapt2, $side[0], $side[1])
}
$null = Invoke-RetargetPython -Log (Join-Path $WorkDir 'owners.log') -Script 'owners.py' -Arguments @($root, $owners)
if ($Seeds) {
    Copy-Item -LiteralPath $Seeds -Destination $seedFile -Force
} else {
    [IO.File]::WriteAllText($seedFile, '')
}

# Which two APKs and seeds a trace came from, so -ReuseTrace can't pick up another pair's.
$stampFile = Join-Path $WorkDir 'propagate.stamp'
$stamp = @(
    "old $($old.versionName) $($old.versionCode) $((Get-FileHash -LiteralPath $oldApk -Algorithm SHA256).Hash)",
    "new $($new.versionName) $($new.versionCode) $((Get-FileHash -LiteralPath $newApk -Algorithm SHA256).Hash)",
    "seeds $((Get-FileHash -LiteralPath $seedFile -Algorithm SHA256).Hash)"
) -join "`n"
if ($ReuseTrace) {
    if (-not (Test-Path -LiteralPath $propagate -PathType Leaf)) { throw "-ReuseTrace: no propagate.tsv in $WorkDir" }
    $traced = if (Test-Path -LiteralPath $stampFile -PathType Leaf) { [IO.File]::ReadAllText($stampFile) } else { '' }
    if ($traced -ne $stamp) {
        throw "-ReuseTrace: $propagate came from other APKs or seeds (see $stampFile). Run without -ReuseTrace."
    }
    Write-Host "[retarget] reusing $propagate"
} else {
    if (Test-Path -LiteralPath $stampFile) { Remove-Item -LiteralPath $stampFile -Force }
    Write-Host "[retarget] tracing (about $Heap of memory, Idle priority)"
    $tracerArguments = @("-Xmx$Heap", '-cp', "`"$DesktopJar`"", "`"$(Join-Path $PSScriptRoot 'Propagate.java')`"",
        "`"$oldApk`"", "`"$newApk`"", "`"$seedFile`"", "`"$owners`"", "`"$propagate`"")
    $tracer = Start-Process -FilePath $Java -ArgumentList $tracerArguments -NoNewWindow -PassThru `
        -RedirectStandardOutput (Join-Path $WorkDir 'propagate.out') -RedirectStandardError (Join-Path $WorkDir 'propagate.err')
    $null = $tracer.Handle
    try { $tracer.PriorityClass = 'Idle' } catch { Write-Warning "Could not lower the tracer's priority: $($_.Exception.Message)" }
    $tracer.WaitForExit()
    if ($tracer.ExitCode -ne 0) { throw "The tracer exited $($tracer.ExitCode); see propagate.err in $WorkDir" }
    [IO.File]::WriteAllText($stampFile, $stamp)
    Get-Content -LiteralPath (Join-Path $WorkDir 'propagate.out') | Select-Object -Last 2 | ForEach-Object { Write-Host "  $_" }
}

$renameArguments = @($propagate, $root, "--old=$($old.versionName)", "--new=$($new.versionName)", '--write')
if ($Extra) { $renameArguments += "--extra=$Extra" }
$swapArguments = @($root, "--old=$($old.versionName)", "--new=$($new.versionName)",
    "--old-code=$($old.versionCode)", "--new-code=$($new.versionCode)", '--write')
$passes = @(
    @('rename.py', $renameArguments, 'rename.txt', '^=='),
    @('swap.py', $swapArguments, 'swap.txt', '^=='),
    @('identities.py', @($root, '--write'), 'identities.txt', '^(added|gone|written)')
)
foreach ($pass in $passes) {
    Write-Host "[retarget] $($pass[0])"
    $output = Invoke-RetargetPython -Log (Join-Path $WorkDir $pass[2]) -Script $pass[0] -Arguments $pass[1]
    $output | Where-Object { $_ -match $pass[3] } | ForEach-Object { Write-Host "  $_" }
}

$verifyAll = Join-Path $scripts 'verify-all-patches.ps1'
Write-Host ''
Write-Host "[retarget] Reports: $WorkDir (rename.txt, swap.txt, identities.txt). Next, build the bundle"
Write-Host '  (:patches:generatePatchesList then :patches:buildAndroid), and patch the new build with every patch,'
Write-Host '  past failures (the CLI runs with --continue-on-error):'
Write-Host "  & `"$verifyAll`" -Apk `"$newApk`" -DesktopJar `"$DesktopJar`" -WorkDir `"$(Join-Path $WorkDir 'verify')`""
