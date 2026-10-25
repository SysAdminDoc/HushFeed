#Requires -Version 7.0
<#
Copyright 2026 Hushfeed contributors
https://github.com/SysAdminDoc/hushfeed
Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).

Ownership is reviewed explicitly; types, defaults and numeric ranges come from the Java declarations.
Run scripts/gen-patch-defaults.ps1 [-Check]. Dot-source to reuse the parser in contract tests.
#>
[CmdletBinding()]
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [switch]$Check
)

$ErrorActionPreference = 'Stop'

function Read-PatchDefaultsSource {
    param([string]$Root, [string]$Path)
    [IO.File]::ReadAllText((Join-Path $Root $Path), [Text.Encoding]::UTF8)
}

function Remove-PatchDefaultsComments {
    param([string]$Text)
    [regex]::Replace($Text, '"(?:\\.|[^"\\])*"|''(?:\\.|[^''\\])*''|//[^\n]*|/\*[\s\S]*?\*/', {
        param($Token)
        if ($Token.Value.StartsWith('/')) { return ' ' * $Token.Length }
        $Token.Value
    })
}

function Get-PatchDefaultsArguments {
    param([string]$Text, [int]$Start)
    $values = [Collections.Generic.List[string]]::new()
    $pattern = [regex]'"(?:\\.|[^"\\])*"|''(?:\\.|[^''\\])*''|[(){}\[\],]'
    $depth = 0
    $begin = $Start
    for ($token = $pattern.Match($Text, $Start); $token.Success; $token = $token.NextMatch()) {
        $symbol = $token.Value
        if ($symbol -in @('(', '{', '[')) { $depth++ }
        elseif ($symbol -eq ')' -and $depth -eq 0) {
            $values.Add($Text.Substring($begin, $token.Index - $begin).Trim())
            return [pscustomobject]@{ Values = $values.ToArray(); End = $token.Index + $token.Length }
        }
        elseif ($symbol -in @(')', '}', ']')) { $depth-- }
        elseif ($symbol -eq ',' -and $depth -eq 0) {
            $values.Add($Text.Substring($begin, $token.Index - $begin).Trim())
            $begin = $token.Index + $token.Length
        }
    }
    throw 'Unterminated setting constructor'
}

function ConvertFrom-PatchDefaultsNumber {
    param([string]$Expression)
    $text = $Expression.Trim().Replace('_', '') -creplace '[fFlL]$', ''
    # Evaluate only the small arithmetic used by ranges; never execute source code.
    $number = [regex]::Match($text, '^(?<product>-?\d+(?:\.\d+)?(?:\s*\*\s*\d+)?)(?:\s*-\s*(?<subtract>\d+))?$')
    if (-not $number.Success) { throw "Unsupported numeric default: $Expression" }
    $result = [decimal]1
    foreach ($factor in $number.Groups['product'].Value.Split('*')) {
        $result *= [decimal]::Parse($factor.Trim(), [Globalization.CultureInfo]::InvariantCulture)
    }
    if ($number.Groups['subtract'].Success) {
        $result -= [decimal]::Parse($number.Groups['subtract'].Value, [Globalization.CultureInfo]::InvariantCulture)
    }
    if ($result -eq [decimal]::Truncate($result)) { return [long]$result }
    return [double]$result
}

function Get-PatchDefaultsTabs {
    param([string]$Root, [string]$ClassName)
    $source = Read-PatchDefaultsSource $Root "extensions/tiktok/src/main/java/app/morphe/extension/tiktok/navigation/$ClassName.java"
    $constants = [Collections.Generic.Dictionary[string, string]]::new([StringComparer]::Ordinal)
    foreach ($entry in [regex]::Matches($source, 'static final String (\w+) = "([^"]*)"')) {
        $constants[$entry.Groups[1].Value] = $entry.Groups[2].Value
    }
    $keys = foreach ($entry in [regex]::Matches($source, 'new TabOption\((\w+),')) {
        $name = $entry.Groups[1].Value
        if (-not $constants.ContainsKey($name)) { throw "Unknown tab: $ClassName.$name" }
        $constants[$name]
    }
    $keys -join ','
}

function Get-PatchDefaultsConstant {
    param([string]$Root, [string]$Path, [string]$Name)
    $source = Remove-PatchDefaultsComments (Read-PatchDefaultsSource $Root $Path)
    $constant = [regex]::Match($source, ('static final (?:int|String) ' + [regex]::Escape($Name) + ' = ([^;]+);'))
    if (-not $constant.Success) { throw "Missing constant: $Name" }
    ConvertFrom-PatchDefaultsValue $Root $constant.Groups[1].Value.Trim()
}

function ConvertFrom-PatchDefaultsValue {
    param([string]$Root, [string]$Expression)
    switch -CaseSensitive ($Expression) {
        '"0-" + Long.MAX_VALUE' { return '0-9223372036854775807' }
        'NavigationTabOptions.defaultEnabledKeys()' { return Get-PatchDefaultsTabs $Root 'NavigationTabOptions' }
        'BottomNavigationTabOptions.defaultEnabledKeys()' { return Get-PatchDefaultsTabs $Root 'BottomNavigationTabOptions' }
        'SHARE_HIDDEN_ITEMS_FOLLOW_VIDEO' {
            return Get-PatchDefaultsConstant $Root 'extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/Settings.java' 'SHARE_HIDDEN_ITEMS_FOLLOW_VIDEO'
        }
        'AppLanguage.DEFAULT' { return 'DEFAULT' }
    }
    if ($Expression.StartsWith('"')) { return ConvertFrom-Json -InputObject $Expression }
    if ($Expression -cmatch '^(TRUE|FALSE|true|false)$') { return $Expression.ToLowerInvariant() -ceq 'true' }
    ConvertFrom-PatchDefaultsNumber $Expression
}

function Get-PatchDefaultsDeclarations {
    param([string]$Root)
    $declarations = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    foreach ($path in @(
        'extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/Settings.java',
        'extensions/shared/library/src/main/java/app/morphe/extension/shared/settings/BaseSettings.java'
    )) {
        $source = Remove-PatchDefaultsComments (Read-PatchDefaultsSource $Root $path)
        $pattern = '(?:public|private)\s+static\s+final\s+(\w*Setting)(?:<[^>]+>)?\s+(\w+)\s*=\s*new\s+(\w*Setting)(?:<[^>]*>)?\('
        foreach ($declaration in [regex]::Matches($source, $pattern)) {
            $arguments = Get-PatchDefaultsArguments $source ($declaration.Index + $declaration.Length)
            $field = $declaration.Groups[2].Value
            $entry = [ordered]@{
                field = $field
                key = ConvertFrom-Json -InputObject $arguments.Values[0]
                type = $declaration.Groups[1].Value.Replace('Setting', '').ToLowerInvariant()
                default = $arguments.Values[1]
            }
            # These StringSetting subclasses take a list-kind boolean; their real default is empty.
            if ($declaration.Groups[3].Value -ceq 'FeedRuleStringSetting') { $entry.default = '' }
            $range = [regex]::Match($source.Substring($arguments.End), '^\s*\.withRange\(([^)]+)\)')
            if ($range.Success) {
                $offline = 'extensions/tiktok/src/main/java/app/morphe/extension/tiktok/offline/CustomOfflineVideosLimitPatch.java'
                $rangeText = [regex]::Replace($range.Groups[1].Value, 'CustomOfflineVideosLimitPatch\.(MIN_LIMIT|MAX_LIMIT)', {
                    param($Constant)
                    [string](Get-PatchDefaultsConstant $Root $offline $Constant.Groups[1].Value)
                })
                $limits = @($rangeText.Split(',') | ForEach-Object { ConvertFrom-PatchDefaultsNumber $_ })
                $entry.minimum = $limits[0]
                $entry.maximum = $limits[1]
            }
            if ($declarations.ContainsKey($field)) { throw "Duplicate setting field: $field" }
            $declarations.Add($field, $entry)
        }
    }
    return $declarations
}

function Get-PatchDefaultsCatalog {
    param([string]$Root)
    $ownership = ConvertFrom-Json -AsHashtable -InputObject (Read-PatchDefaultsSource $Root 'patches/settings-defaults-owners.json')
    $declarations = Get-PatchDefaultsDeclarations $Root
    $used = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $settings = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    $patches = [Collections.Specialized.OrderedDictionary]::new([StringComparer]::Ordinal)
    $names = [string[]]@($ownership.patches.Keys)
    [Array]::Sort($names, [StringComparer]::Ordinal)
    foreach ($name in $names) {
        $keys = @(foreach ($field in $ownership.patches[$name]) {
            if (-not $declarations.ContainsKey($field)) { throw "Unknown field: ${name}: $field" }
            if ($ownership.excluded.Contains($field)) { throw "Field is both configurable and excluded: $field" }
            [void]$used.Add($field)
            $entry = $declarations[$field]
            if (-not $settings.ContainsKey($entry.key)) {
                $item = [ordered]@{}
                foreach ($property in $entry.Keys) { $item[$property] = $entry[$property] }
                if ($entry.default -cne '') { $item.default = ConvertFrom-PatchDefaultsValue $Root $entry.default }
                if ($entry.type -ceq 'enum') {
                    $language = Remove-PatchDefaultsComments (Read-PatchDefaultsSource $Root 'extensions/shared/library/src/main/java/app/morphe/extension/shared/settings/AppLanguage.java')
                    $language = $language.Substring($language.IndexOf('public enum AppLanguage', [StringComparison]::Ordinal))
                    $item.values = @([regex]::Matches($language, '(?m)^\s*([A-Z][A-Z_]*)\s*[,;]') | ForEach-Object { $_.Groups[1].Value })
                }
                $settings.Add($entry.key, $item)
            }
            $entry.key
        })
        $unique = [Collections.Generic.HashSet[string]]::new([string[]]$keys, [StringComparer]::Ordinal)
        if ($unique.Count -ne $keys.Count) { throw "Duplicate option: $name" }
        $patches.Add($name, $keys)
    }
    foreach ($field in $declarations.Keys) {
        if (-not $used.Contains($field) -and -not $ownership.excluded.Contains($field)) {
            throw "Assign a patch or a reason to exclude $field"
        }
    }
    foreach ($field in $ownership.excluded.Keys) {
        if (-not $declarations.ContainsKey($field) -or [string]::IsNullOrWhiteSpace($ownership.excluded[$field])) {
            throw "Invalid exclusion: $field"
        }
    }
    $sortedSettings = [Collections.Specialized.OrderedDictionary]::new([StringComparer]::Ordinal)
    $keys = [string[]]@($settings.Keys)
    [Array]::Sort($keys, [StringComparer]::Ordinal)
    foreach ($key in $keys) { $sortedSettings.Add($key, $settings[$key]) }
    $catalog = [ordered]@{ patches = $patches; settings = $sortedSettings }
    (ConvertTo-Json -InputObject $catalog -Depth 32).Replace("`r`n", "`n") + "`n"
}

if ($MyInvocation.InvocationName -ne '.') {
    $outputPath = Join-Path $Root 'patches/src/main/resources/settings-defaults.json'
    $generated = Get-PatchDefaultsCatalog $Root
    if ($Check) {
        if (-not [IO.File]::Exists($outputPath) -or [IO.File]::ReadAllText($outputPath) -cne $generated) {
            throw 'Settings defaults catalog is stale: run scripts/gen-patch-defaults.ps1'
        }
    }
    else { [IO.File]::WriteAllText($outputPath, $generated, [Text.UTF8Encoding]::new($false)) }
    $catalog = ConvertFrom-Json -AsHashtable -InputObject $generated
    Write-Host "Settings defaults catalog: $($catalog.settings.Count) settings"
}
