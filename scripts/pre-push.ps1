<#
.SYNOPSIS
    Seconds-long check on what a push would publish.

.DESCRIPTION
    Runs from .git/hooks/pre-push (scripts/install-hooks.ps1 writes it). Reads git's ref lines on
    stdin and refuses a push whose new commits name an AI tool as author, committer or co-author,
    or whose added lines carry a device serial or a home folder path. Builds and tests belong to
    the work and to scripts/release/release.ps1, not to a push. Set HUSHFEED_SKIP_PRE_PUSH=1 to
    push anyway.
#>
param(
    [string]$RemoteName,
    [string]$RemoteUrl
)

$ErrorActionPreference = 'Stop'
if ($env:HUSHFEED_SKIP_PRE_PUSH -eq '1') { exit 0 }

$noObject = '0' * 40
$aiAddress = '(?i)(noreply@anthropic|@anthropic\.com|copilot@|codex@|noreply@openai|@openai\.com)'
$aiTrailer = '(?im)^\s*co-authored-by:.*(claude|anthropic|copilot|codex|openai|chatgpt)'
$homePath = '(?i)c:[\\/]+users[\\/]+[a-z0-9_.-]+[\\/]'
# Samsung serials: R, a digit, nine more. Hashes are lowercase hex and constants have no digit there.
$serial = '(?<![A-Za-z0-9])R[0-9][0-9A-Z]{9}(?![A-Za-z0-9])'
$problems = [Collections.Generic.List[string]]::new()

foreach ($line in ([Console]::In.ReadToEnd() -split "`r?`n")) {
    $fields = -split $line
    if ($fields.Count -lt 4 -or $fields[1] -eq $noObject) { continue }
    [string[]]$range = if ($fields[3] -eq $noObject) { @($fields[1], '--not', '--remotes') } else { "$($fields[3])..$($fields[1])" }
    foreach ($commit in @(& git.exe rev-list @range)) {
        $people = (& git.exe show -s --format='%ae%n%ce' $commit) -join "`n"
        $message = (& git.exe show -s --format='%B' $commit) -join "`n"
        if ($people -match $aiAddress -or $message -match $aiTrailer) {
            $problems.Add("$commit names an AI tool as author, committer or co-author.")
        }
        foreach ($added in @(& git.exe show --format= --unified=0 --no-color --no-ext-diff $commit)) {
            if (-not $added.StartsWith('+') -or $added.StartsWith('+++')) { continue }
            if ($added -cmatch $serial -or $added -match $homePath) {
                $problems.Add("$commit adds a device serial or home folder path: $($added.Substring(0, [Math]::Min(100, $added.Length)))")
            }
        }
    }
}

if ($problems.Count) {
    $problems | ForEach-Object { [Console]::Error.WriteLine($_) }
    [Console]::Error.WriteLine('Fix those commits, or push with HUSHFEED_SKIP_PRE_PUSH=1 if a line is a false match.')
    exit 1
}
exit 0
