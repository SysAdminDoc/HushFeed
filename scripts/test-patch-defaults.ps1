#Requires -Version 7.0
<# Copyright 2026 Hushfeed contributors. https://github.com/SysAdminDoc/hushfeed #>
[CmdletBinding()]
param([string]$Root = (Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'gen-patch-defaults.ps1') -Root $Root

function Assert-PatchDefaults {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-PatchDefaultsThrows {
    param([scriptblock]$Action, [string]$Pattern)
    try { & $Action | Out-Null }
    catch {
        if ($_.Exception.Message -like $Pattern) { return }
        throw "Expected '$Pattern', got '$($_.Exception.Message)'"
    }
    throw "Expected '$Pattern', but no error was raised"
}

$generated = Get-PatchDefaultsCatalog $Root
$existing = Read-PatchDefaultsSource $Root 'patches/src/main/resources/settings-defaults.json'
Assert-PatchDefaults ($existing -ceq $generated) 'Settings defaults catalog is stale: run scripts/gen-patch-defaults.ps1'
$defaults = ConvertFrom-Json -AsHashtable -InputObject $generated
$catalog = ConvertFrom-Json -AsHashtable -InputObject (Read-PatchDefaultsSource $Root 'patches-list.json')
$patchesByName = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
foreach ($patch in $catalog.patches) { $patchesByName.Add($patch.name, $patch) }

foreach ($name in $defaults.patches.Keys) {
    Assert-PatchDefaults ($patchesByName.ContainsKey($name)) "Unknown patch owner: $name"
    $keys = [string[]]$defaults.patches[$name]
    $unique = [Collections.Generic.HashSet[string]]::new($keys, [StringComparer]::Ordinal)
    Assert-PatchDefaults ($keys.Count -eq $unique.Count) "Duplicate option: $name"
    foreach ($key in $keys) {
        Assert-PatchDefaults ($defaults.settings.Contains($key)) "Missing setting: ${name}: $key"
        $setting = $defaults.settings[$key]
        $option = @($patchesByName[$name].options | Where-Object { $_.key -ceq $key })
        Assert-PatchDefaults ($option.Count -eq 1) "Regenerate patches-list.json: ${name}: $key"
        $actual = $option[0].default
        $expected = $setting.default
        # PowerShell's comparison operators coerce strings and booleans, so check types first.
        if ($setting.type -cin @('string', 'enum')) {
            Assert-PatchDefaults ($expected -is [string] -and $actual -is [string]) "Expected a string: $key"
        }
        elseif ($setting.type -ceq 'boolean') {
            Assert-PatchDefaults ($expected -is [bool] -and $actual -is [bool]) "Expected a boolean: $key"
        }
        else {
            $numericTypes = @([long], [int], [double], [decimal], [float], [bigint])
            Assert-PatchDefaults ($null -ne $expected -and $expected.GetType() -in $numericTypes -and
                $null -ne $actual -and $actual.GetType() -in $numericTypes) "Expected a number: $key"
        }
        Assert-PatchDefaults ($actual -ceq $expected) "Default differs from patches-list.json: ${name}: $key"
        if ($setting.Contains('minimum')) {
            Assert-PatchDefaults ($expected -ge $setting.minimum -and $expected -le $setting.maximum) "Default is outside its range: $key"
        }
        if ($setting.Contains('values')) {
            Assert-PatchDefaults ($setting.values -ccontains $expected) "Default is not an allowed value: $key"
        }
    }
}

Assert-PatchDefaults ($defaults.settings.simspoof.default -ceq $false) 'SIM spoof stock default changed'
Assert-PatchDefaults ($defaults.settings.simspoof_iso.default -ceq 'us') 'SIM country stock default changed'
Assert-PatchDefaults ($defaults.patches['Show author region'] -ccontains 'region_as_flag') 'Author region lacks the flag option'
Assert-PatchDefaults ($defaults.patches['Show commenter handle and region'] -ccontains 'region_as_flag') 'Commenter region lacks the flag option'
Assert-PatchDefaults ($defaults.settings.blocked_caption_words.default -ceq '') 'Caption filter default must be an empty string'
Assert-PatchDefaults ($defaults.settings.min_max_views.default -ceq '0-9223372036854775807') 'Long range lost precision'
Assert-PatchDefaults ((ConvertFrom-PatchDefaultsNumber '24 * 60 - 1') -eq 1439) 'Range arithmetic failed'
Assert-PatchDefaults ((ConvertFrom-PatchDefaultsNumber '24*60-1') -eq 1439) 'Compact range arithmetic failed'
Assert-PatchDefaults ((ConvertFrom-PatchDefaultsNumber '-1L') -eq -1) 'Signed long default failed'
Assert-PatchDefaults ((ConvertFrom-PatchDefaultsNumber '1.5f') -eq 1.5) 'Float default failed'
Assert-PatchDefaults ((ConvertFrom-PatchDefaultsNumber '10_000') -eq 10000) 'Numeric separators failed'
Assert-PatchDefaultsThrows { ConvertFrom-PatchDefaultsNumber 'process.exit()' } '*Unsupported numeric default*'
Assert-PatchDefaultsThrows { ConvertFrom-PatchDefaultsNumber '$(Get-Content secret)' } '*Unsupported numeric default*'
$arguments = Get-PatchDefaultsArguments '"a,b", call(1, 2), true)' 0
Assert-PatchDefaults ($arguments.Values.Count -eq 3 -and $arguments.Values[0] -ceq '"a,b"' -and
    $arguments.Values[1] -ceq 'call(1, 2)' -and $arguments.Values[2] -ceq 'true') 'Constructor argument parsing failed'
Assert-PatchDefaultsThrows { Get-PatchDefaultsArguments '"unclosed", call(' 0 } '*Unterminated setting constructor*'
Assert-PatchDefaults ((Remove-PatchDefaultsComments '"//text" // comment').Trim() -ceq '"//text"') 'Comment stripping changed a string literal'
Assert-PatchDefaults ((Remove-PatchDefaultsComments '"/*text*/" /* comment */').Trim() -ceq '"/*text*/"') 'Block comment stripping changed a string literal'

# All public TikTok bytecode declarations must pass through the common option builder.
$patchRoot = Join-Path $Root 'patches/src/main/kotlin/app/morphe/patches/tiktok'
foreach ($file in Get-ChildItem -LiteralPath $patchRoot -Recurse -File -Filter '*.kt') {
    if ($file.Name -eq 'DefaultSettings.kt') { continue }
    Assert-PatchDefaults (-not [IO.File]::ReadAllText($file.FullName).Contains('import app.morphe.patcher.patch.bytecodePatch')) $file.FullName
}
Write-Host "Patch defaults contracts passed: $($defaults.settings.Count) settings, $($defaults.patches.Count) patches"
