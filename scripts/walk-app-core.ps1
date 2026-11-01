<#
.SYNOPSIS
    The parts of the whole-app walk that don't need a phone: each app's labels and planned
    screens, reading a UI dump, refusing taps on controls that act on someone's account, finding
    the app's crashes in logcat and turning what the walk reached into PASS, PARTIAL or FAIL.

.DESCRIPTION
    Dot-sourced by walk-app.ps1, which drives the phone.
#>

$WalkPackage = 'com.zhiliaoapp.musically'

# Labels the walk never taps, by text or content description, in every language a walk phone
# runs. Each one acts on a real account or another person (a follow, a like, a message, a post,
# a block) or changes Hushfeed's settings in one tap (a quick setup or preset). A walk only
# opens screens and sheets and leaves them with Back, so nothing it needs to touch can match.
$WalkDeniedLabel = '(?i)^\W*(follow|follow back|unfollow|message|send|post|repost|like|add friends?|add (a )?person|add people|invite|confirm|block|report|delete|remove|log ?out|switch account|download|save|duet|stitch|go live|subscribe|buy|shop now|install|claim|back to defaults|use "|' +
    'segui|segui anche tu|smetti di seguire|messaggio|invia|pubblica|ripubblica|metti mi piace|mi piace|aggiungi amic[oi]|aggiungi person[ae]|invita|conferma|blocca|segnala|elimina|rimuovi|esci|cambia account|scarica|salva|duetto|vai in live|iscriviti|acquista|installa|riscatta|ripristina predefiniti|usa ")\b'

# TikTok's labels the walk looks for, as regexes over every language a walk phone runs. TikTok
# takes its language from its own settings, over the phone's and Android's per-app locale, so a
# phone set to another language needs its words added here (the S22 runs English, Login2
# Italian). Hushfeed's own titles come from its translation tables instead (Get-WalkTitleLabel).
$WalkLabels = @{
    Home = '^Home$'
    Friends = '^(Friends|Amici)$'
    Inbox = '^(Inbox|Posta in arrivo)$'
    Profile = '^(Profile|Profilo)$'
    ForYou = '^(For You|Per te)$'
    Following = '^(Following|Seguiti)$'
    Search = '^(Search|Cerca)$'
    CommentsButton = '^(Read or add comments|Leggi o aggiungi commenti)'
    CommentsSheet = '(?i)^\W*(add comment|aggiungi un commento|\d[\d.,]*[KkMm]? (comments?|commenti)\b)'
    ShareButton = '^(Share video|Condividi il video)'
    ShareSheet = '(?i)^\W*(copy link|send to|share to|not interested|copia link|invia a|condividi su|non mi interessa)\b'
    ActivityRow = '^(Activit(y|ies)|Attivit\xE0)( (and|&) new followers| e nuovi follower)?$'
    ActivityTitle = '^(Activit(y|ies)|Attivit\xE0)$'
    ProfileMenu = '^(Profile menu|Men\xF9 del profilo)$'
    SettingsAndPrivacy = '^(Settings and privacy|Impostazioni e privacy)$'
}

# What a fresh install can show before its bottom tabs: a sign-in sheet (Close) and an age question
# (At least 18, then Continue). None of them signs in or acts on an account.
$WalkLaunchGateLabel = '^(Close|At least 18|Continue)$'

# What the walk opens. MainFeed is swiped first, OtherFeeds are visited from it and Tabs are the
# bottom tabs after Home. The receipt's profile name comes from walk-receipt.ps1's $WalkProfilePackages.
$WalkProfiles = @{
    'com.zhiliaoapp.musically' = @{
        Package = 'com.zhiliaoapp.musically'; App = 'TikTok'; Labels = $WalkLabels
        MainFeed = 'ForYou'; MainFeedName = 'For You'; OtherFeeds = @('Following'); Tabs = @('Friends', 'Inbox', 'Profile'); Activity = $true
    }
}

function Get-WalkProfile {
    <#
    .SYNOPSIS
        The walk profile for -Package, or an error naming the packages a walk knows.
    #>
    param([Parameter(Mandatory = $true)][string]$Package)
    if (-not $WalkProfiles.ContainsKey($Package)) {
        throw "There's no walk profile for $Package. Known: $((@($WalkProfiles.Keys) | Sort-Object) -join ', ')."
    }
    $WalkProfiles[$Package]
}

function Get-WalkPlannedScreens {
    <#
    .SYNOPSIS
        Every screen a PASS walk of this profile reaches, in walk order.
    #>
    param([Parameter(Mandatory = $true)][hashtable]$AppProfile, [Parameter(Mandatory = $true)][string[]]$HushfeedPages)
    $screens = [Collections.Generic.List[string]]::new()
    $screens.Add('launch'); $screens.Add("feed: $($AppProfile.MainFeedName)")
    $screens.Add('sheet: comments'); $screens.Add('sheet: share')
    foreach ($feed in $AppProfile.OtherFeeds) { $screens.Add("feed: $feed") }
    $screens.Add('search')
    foreach ($tab in $AppProfile.Tabs) {
        $screens.Add("tab: $tab")
        if ($tab -eq 'Inbox' -and $AppProfile.Activity) { $screens.Add('inbox: Activity') }
    }
    $screens.Add('settings: Hushfeed'); $screens.Add('settings: Search settings')
    foreach ($page in $HushfeedPages) { $screens.Add("settings: $page") }
    $screens.ToArray()
}

function Get-HushfeedTranslations {
    <#
    .SYNOPSIS
        Every translation of each English settings string, read from the extension's
        L10nTranslations<Language>.java tables: English text -> list of translated texts.
    #>
    param([Parameter(Mandatory = $true)][string]$Root)
    $dir = Join-Path $Root 'extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings'
    $rows = @{}
    foreach ($file in Get-ChildItem -LiteralPath $dir -Filter 'L10nTranslations?*.java') {
        $source = [IO.File]::ReadAllText($file.FullName, [Text.Encoding]::UTF8)
        foreach ($m in [regex]::Matches($source, 'table\.put\(\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*\)')) {
            $english = [regex]::Unescape($m.Groups[1].Value)
            if (-not $rows.ContainsKey($english)) { $rows[$english] = [Collections.Generic.List[string]]::new() }
            $rows[$english].Add([regex]::Unescape($m.Groups[2].Value))
        }
    }
    $rows
}

function Get-WalkTitleLabel {
    <#
    .SYNOPSIS
        A regex matching a Hushfeed title exactly, case included (so "Privacy" isn't a "PRIVACY"
        section header), in English or any of its translations.
    #>
    param([Parameter(Mandatory = $true)][string]$English, [hashtable]$Translations = @{})
    $names = @($English) + @(if ($Translations.ContainsKey($English)) { $Translations[$English] })
    '(?-i)^(' + ((@($names | Select-Object -Unique | ForEach-Object { [regex]::Escape($_) })) -join '|') + ')$'
}

function ConvertFrom-UiDump {
    <#
    .SYNOPSIS
        The nodes of a uiautomator dump, each with its labels, flags and screen bounds.
    #>
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Xml)
    if (-not $Xml.Trim()) { return @() }
    $doc = [xml]$Xml
    foreach ($node in $doc.SelectNodes('//node')) {
        $bounds = [regex]::Match($node.GetAttribute('bounds'), '^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$')
        if (-not $bounds.Success) { continue }
        $left = [int]$bounds.Groups[1].Value; $top = [int]$bounds.Groups[2].Value
        $right = [int]$bounds.Groups[3].Value; $bottom = [int]$bounds.Groups[4].Value
        $id = $node.GetAttribute('resource-id')
        [pscustomobject]@{
            Text = $node.GetAttribute('text'); Desc = $node.GetAttribute('content-desc')
            Id = if ($id.Contains('/')) { $id.Substring($id.IndexOf('/') + 1) } else { $id }
            Class = $node.GetAttribute('class'); Package = $node.GetAttribute('package')
            Clickable = $node.GetAttribute('clickable') -eq 'true'; Checked = $node.GetAttribute('checked') -eq 'true'
            Selected = $node.GetAttribute('selected') -eq 'true'; Scrollable = $node.GetAttribute('scrollable') -eq 'true'
            Left = $left; Top = $top; Right = $right; Bottom = $bottom
            X = [int][Math]::Floor(($left + $right) / 2); Y = [int][Math]::Floor(($top + $bottom) / 2)
        }
    }
}

function Find-UiNode {
    <#
    .SYNOPSIS
        The nodes whose text or content description matches -Label (a regex), with a usable area.
    #>
    param([AllowEmptyCollection()][object[]]$Nodes = @(), [Parameter(Mandatory = $true)][string]$Label, [string]$Id)
    @($Nodes | Where-Object {
            ($_.Text -match $Label -or $_.Desc -match $Label) -and
            (-not $Id -or $_.Id -ceq $Id) -and $_.Right -gt $_.Left -and $_.Bottom -gt $_.Top
        })
}

function Test-WalkTapAllowed {
    <#
    .SYNOPSIS
        Whether a walk may tap this node: false for anything labeled like a follow, like, message
        or post, whatever the walk was looking for.
    .DESCRIPTION
        -Title is a Get-WalkTitleLabel regex. A Hushfeed page row (id "title") whose text is that
        title exactly may be tapped even when a word on the list spells it, like Italian's
        "Download" for the Downloads page: it only opens Hushfeed's own page.
    #>
    param([Parameter(Mandatory = $true)]$Node, [string]$Title)
    if ($Title -and $Node.Id -ceq 'title' -and $Node.Text -match $Title) { return $true }
    -not ($Node.Text -match $WalkDeniedLabel -or $Node.Desc -match $WalkDeniedLabel)
}

function Get-UiFingerprint {
    <#
    .SYNOPSIS
        A string that changes when what a dump shows changes, used to tell a scroll that moved
        from one that hit the bottom.
    #>
    param([AllowEmptyCollection()][object[]]$Nodes = @())
    (@($Nodes | Where-Object { $_.Text -or $_.Desc } | ForEach-Object { "$($_.Text)|$($_.Desc)|$($_.Top)" }) -join "`n")
}

function Get-WalkCrashes {
    <#
    .SYNOPSIS
        The crashes and ANRs of -Package (and its own processes, like :push) in logcat lines.
    .DESCRIPTION
        A Java crash is "FATAL EXCEPTION" with "Process: <package>" on one of the next lines. A
        native crash is libc's "Fatal signal" or debuggerd's ">>> <package> <<<" for the same pid,
        which count once. ActivityManager's "ANR in <package>" is an ANR. A process that dies
        because the walk force-stopped it or Hushfeed restarted it logs none of these.
    #>
    param([AllowEmptyCollection()][string[]]$Lines = @(), [string]$Package = $WalkPackage)
    $seen = @{}
    $crashes = [Collections.Generic.List[object]]::new()
    $ours = {
        param([string]$Name)
        $base = $Name.Split(':')[0]
        $base.Length -ge 8 -and ($Package -ceq $base -or $Package.EndsWith($base, [StringComparison]::Ordinal))
    }
    for ($i = 0; $i -lt $Lines.Count; $i++) {
        $line = [string]$Lines[$i]
        $kind = $null; $process = $null; $processId = $null; $detail = $line.Trim()
        if ($line -match 'FATAL EXCEPTION') {
            for ($j = $i + 1; $j -lt [Math]::Min($Lines.Count, $i + 4); $j++) {
                $m = [regex]::Match([string]$Lines[$j], 'Process: ([\w.:]+), PID: (\d+)')
                if ($m.Success) {
                    if (& $ours $m.Groups[1].Value) {
                        $kind = 'java'; $process = $m.Groups[1].Value; $processId = $m.Groups[2].Value
                        if ($j + 1 -lt $Lines.Count) {
                            # The message without logcat's time, pid and tag columns.
                            $message = [regex]::Match([string]$Lines[$j + 1], '^(?:\S+\s+\S+\s+\d+\s+\d+\s+[VDIWEFA]\s+[^:]*|[VDIWEFA]/[^(]*\(\s*\d+\)):\s?(.*)$')
                            $detail = if ($message.Success) { $message.Groups[1].Value.Trim() } else { ([string]$Lines[$j + 1]).Trim() }
                        }
                    }
                    break
                }
            }
        } elseif ($line -match 'Fatal signal \d+') {
            $m = [regex]::Match($line, 'pid (\d+) \(([^)]+)\)')
            if ($m.Success -and (& $ours $m.Groups[2].Value)) { $kind = 'native'; $process = $m.Groups[2].Value; $processId = $m.Groups[1].Value }
        } elseif ($line -match '>>> ([\w.:]+) <<<') {
            $name = $Matches[1]
            $m = [regex]::Match($line, 'pid: (\d+)')
            if (& $ours $name) { $kind = 'native'; $process = $name; $processId = if ($m.Success) { $m.Groups[1].Value } else { "line$i" } }
        } elseif ($line -match 'ANR in ([\w.:]+)') {
            if (& $ours $Matches[1]) { $kind = 'anr'; $process = $Matches[1]; $processId = "line$i" }
        }
        if (-not $kind) { continue }
        $key = "$kind|$processId"
        if ($seen.ContainsKey($key)) { continue }
        $seen[$key] = $true
        $crashes.Add([pscustomobject]@{ Kind = $kind; Process = $process; Pid = $processId; Detail = $detail })
    }
    $crashes.ToArray()
}

function Get-WalkVerdict {
    <#
    .SYNOPSIS
        PASS when every planned screen was reached with no crash and no fault, PARTIAL when the
        walk skipped a screen, FAIL on any crash or fault (TikTok died, left the foreground, or
        the walk couldn't go on).
    #>
    param(
        [AllowEmptyCollection()][string[]]$Planned = @(),
        [AllowEmptyCollection()][string[]]$Reached = @(),
        [AllowEmptyCollection()][object[]]$Crashes = @(),
        [AllowEmptyCollection()][string[]]$Faults = @()
    )
    $missing = @($Planned | Where-Object { $Reached -notcontains $_ })
    $problems = [Collections.Generic.List[string]]::new()
    foreach ($crash in $Crashes) { $problems.Add("$($crash.Kind) crash in $($crash.Process) (pid $($crash.Pid)): $($crash.Detail)") }
    foreach ($fault in $Faults) { $problems.Add($fault) }
    foreach ($screen in $missing) { $problems.Add("not reached: $screen") }
    $full = $Planned.Count -gt 0 -and $missing.Count -eq 0
    $result = if ($Crashes.Count -gt 0 -or $Faults.Count -gt 0 -or $Reached.Count -eq 0) { 'FAIL' }
    elseif (-not $full) { 'PARTIAL' } else { 'PASS' }
    [pscustomobject]@{ Result = $result; Full = $full; Missing = $missing; Problems = $problems.ToArray() }
}

function ConvertFrom-WalkPackageDump {
    param([string]$Text, [string]$ExpectedPackage = $WalkPackage)
    $packages = [regex]::Matches($Text, '(?m)^\s*Package \[([A-Za-z0-9_.]+)\]\s*\(')
    $versions = [regex]::Matches($Text, '(?m)^\s*versionName=(\S+)\s*$')
    $codes = [regex]::Matches($Text, '(?m)^\s*versionCode=([0-9]+)\b')
    [long]$code = 0
    if ($packages.Count -ne 1 -or $packages[0].Groups[1].Value -cne $ExpectedPackage -or
            $versions.Count -ne 1 -or $codes.Count -ne 1 -or
            -not [long]::TryParse($codes[0].Groups[1].Value, [ref]$code) -or $code -le 0) {
        throw 'The installed package dump has no unambiguous package/version identity.'
    }
    return [pscustomobject]@{
        package = $packages[0].Groups[1].Value; versionName = $versions[0].Groups[1].Value; versionCode = $codes[0].Groups[1].Value
    }
}
