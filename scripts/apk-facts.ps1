# What the patching and audit scripts read from a bundle's manifest and an APK. Dot-source it.

function Test-BuildSourceFacts {
    <# A timestamp is reproducible, but only the producer's source facts establish eligibility. #>
    param($Source, [Parameter(Mandatory = $true)][string]$ExpectedCommit)

    function Fail { param([string]$Reason) return [pscustomobject]@{ Valid = $false; Reason = $Reason } }
    if ($null -eq $Source) { return Fail 'The build has no source provenance record.' }
    if ([string]$Source.commit -notmatch '^[0-9a-f]{40}$' -or $Source.commit -ne $ExpectedCommit) {
        return Fail "The build source commit is $($Source.commit), not $ExpectedCommit."
    }
    if ($Source.clean -isnot [bool] -or -not $Source.clean) {
        return Fail 'The build source was dirty or could not be read; it is not known to be clean.'
    }
    if ([string]$Source.startFingerprint -notmatch '^[0-9A-F]{64}$' -or
            [string]$Source.endFingerprint -notmatch '^[0-9A-F]{64}$') {
        return Fail 'The build has no readable starting and ending source fingerprints.'
    }
    if ($Source.startFingerprint -ne $Source.endFingerprint) {
        return Fail 'The source inputs changed during the build.'
    }
    return [pscustomobject]@{ Valid = $true; Reason = $null }
}

function Get-BundleManifestFacts {
    <#
    .SYNOPSIS
        Version, timestamp, patcher and source facts out of META-INF/MANIFEST.MF.
    .DESCRIPTION
        The Gradle plugin pins the timestamp to the release commit's time in milliseconds, which
        is one input to reproducing a published hash. The separate source fields identify the
        commit and whether its inputs were clean and unchanged at the build boundaries.
    #>
    param([Parameter(Mandatory = $true)][string]$BundlePath)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $text = $null
    $archive = [System.IO.Compression.ZipFile]::OpenRead($BundlePath)
    try {
        $entry = $archive.Entries | Where-Object { $_.FullName -eq 'META-INF/MANIFEST.MF' }
        if (-not $entry) { throw "The bundle has no META-INF/MANIFEST.MF: $BundlePath" }
        $reader = New-Object System.IO.StreamReader($entry.Open())
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $archive.Dispose() }

    # Manifest lines wrap at 72 characters with a leading space on the continuation.
    $text = $text -replace "\r?\n ", ''
    $timestamp = [regex]::Match($text, '(?m)^Timestamp:\s*(\d+)\s*$')
    $version = [regex]::Match($text, '(?m)^Version:\s*(\S+)\s*$')
    $patcher = [regex]::Match($text, '(?m)^Patcher-Version:\s*(\S+)\s*$')
    if (-not $timestamp.Success) { throw "The bundle manifest has no Timestamp: $BundlePath" }
    if (-not $version.Success) { throw "The bundle manifest has no Version: $BundlePath" }
    if (-not $patcher.Success) { throw "The bundle manifest has no Patcher-Version: $BundlePath" }

    $sourceFields = @{}
    foreach ($name in @('Commit', 'Clean', 'Start', 'End')) {
        $matches = [regex]::Matches($text, "(?m)^Hushfeed-Source-${name}:([^`r`n]*)")
        if ($matches.Count -gt 1) { throw "The bundle repeats source field ${name}: $BundlePath" }
        $sourceFields[$name] = if ($matches.Count -eq 1) { $matches[0].Groups[1].Value.Trim() } else { '' }
    }

    return [pscustomobject]@{
        version        = $version.Groups[1].Value
        timestamp      = [long]$timestamp.Groups[1].Value
        patcherVersion = $patcher.Groups[1].Value
        source         = [pscustomobject]@{
            commit = $sourceFields.Commit
            clean = $sourceFields.Clean -ceq 'true'
            startFingerprint = $sourceFields.Start
            endFingerprint = $sourceFields.End
        }
    }
}

function Resolve-Aapt2 {
    <#
    .SYNOPSIS
        The aapt2 that reads a binary AndroidManifest.xml, or a throw saying where to get one.
    .DESCRIPTION
        Taken in order from -Explicit, HUSHFEED_AAPT2, and the newest build-tools directory of
        the SDK named in local.properties or ANDROID_HOME. Newest by version number, not by text:
        sorting build-tools as strings puts 9.0.0 above 37.0.0.
    #>
    param([string]$Explicit, [string]$Root)

    if ($Explicit) {
        if (-not (Test-Path -LiteralPath $Explicit -PathType Leaf)) {
            throw "No aapt2 at the path given: $Explicit"
        }
        return $Explicit
    }
    if ($env:HUSHFEED_AAPT2 -and (Test-Path -LiteralPath $env:HUSHFEED_AAPT2 -PathType Leaf)) {
        return $env:HUSHFEED_AAPT2
    }

    $sdk = $env:ANDROID_HOME
    $localProperties = if ($Root) { Join-Path $Root 'local.properties' } else { $null }
    if ($localProperties -and (Test-Path -LiteralPath $localProperties -PathType Leaf)) {
        $line = @(Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^\s*sdk\.dir\s*=' }) | Select-Object -First 1
        if ($line) { $sdk = ($line -replace '^\s*sdk\.dir\s*=\s*', '') -replace '\\\\', '\' }
    }
    if (-not $sdk) { throw 'No Android SDK: set ANDROID_HOME, sdk.dir or HUSHFEED_AAPT2.' }

    $buildTools = Join-Path $sdk 'build-tools'
    if (-not (Test-Path -LiteralPath $buildTools -PathType Container)) {
        throw "The SDK has no build-tools directory: $buildTools"
    }
    $candidate = Get-ChildItem -LiteralPath $buildTools -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending |
        Select-Object -First 1
    if (-not $candidate) { throw "No versioned build-tools under $buildTools." }

    $aapt2 = Join-Path $candidate.FullName 'aapt2.exe'
    if (-not (Test-Path -LiteralPath $aapt2 -PathType Leaf)) {
        $aapt2 = Join-Path $candidate.FullName 'aapt2'
    }
    if (-not (Test-Path -LiteralPath $aapt2 -PathType Leaf)) {
        throw "build-tools $($candidate.Name) carries no aapt2."
    }
    return $aapt2
}

function ConvertFrom-XmlTreeValue {
    <#
    .SYNOPSIS
        The value of one aapt2 xmltree attribute, without its decoration.
    .DESCRIPTION
        aapt2 prints a string as `="text" (Raw: "text")` and everything else bare. The quoted
        half is taken when it is there, because the raw half repeats it and an attribute whose
        value contains a quote would otherwise be cut at the wrong place.
    #>
    param([string]$Text)

    if ($null -eq $Text) { return $null }
    $value = $Text.Trim()
    $rawAt = $value.IndexOf(' (Raw: ')
    if ($rawAt -ge 0) { $value = $value.Substring(0, $rawAt).Trim() }
    if ($value.Length -ge 2 -and $value.StartsWith('"') -and $value.EndsWith('"')) {
        return $value.Substring(1, $value.Length - 2)
    }
    return $value
}

function Get-ApkManifestFacts {
    <#
    .SYNOPSIS
        Package identity, requested permissions and exported components, read off an APK.
    .DESCRIPTION
        Components count as exported only when the manifest says so. Every target this project
        patches is above API 31, where an intent filter without an explicit android:exported is
        refused at install time, so there is no implicit case left to infer.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Apk,
        [Parameter(Mandatory = $true)][string]$Aapt2
    )

    if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "APK not found: $Apk" }

    # Relaxed for the call: Windows PowerShell 5.1 throws on a native command's stderr under Stop, even redirected.
    $preference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $dump = @(& $Aapt2 dump xmltree --file AndroidManifest.xml $Apk 2>&1)
    } finally {
        $ErrorActionPreference = $preference
    }
    if ($LASTEXITCODE -ne 0) {
        throw "aapt2 could not read the manifest of ${Apk}: $($dump -join ' ')"
    }
    return ConvertFrom-ManifestXmlTree -Lines $dump -Source (Split-Path -Leaf $Apk)
}

function ConvertFrom-ManifestXmlTree {
    <#
    .SYNOPSIS
        The same reading, from lines already in hand, so the parser can be tested without an APK.
    #>
    param([string[]]$Lines, [string]$Source)

    $componentElements = @('activity', 'activity-alias', 'service', 'receiver', 'provider')
    $packageName = $null
    $versionName = $null
    $versionCode = $null
    $permissions = New-Object System.Collections.Generic.List[string]
    $exported = New-Object System.Collections.Generic.List[string]

    $element = $null
    $componentName = $null
    $componentExported = $false

    function Complete-Component {
        param($Name, $Kind, $IsExported, $List)
        if ($Kind -and $IsExported -and $Name) { $List.Add("${Kind}:${Name}") }
    }

    foreach ($raw in $Lines) {
        $line = [string]$raw
        $elementMatch = [regex]::Match($line, '^\s*E:\s*([A-Za-z0-9_\-]+)\s*\(line=')
        if ($elementMatch.Success) {
            Complete-Component -Name $componentName -Kind $element -IsExported $componentExported -List $exported
            $element = $elementMatch.Groups[1].Value
            $componentName = $null
            $componentExported = $false
            continue
        }

        $attributeMatch = [regex]::Match($line,
            '^\s*A:\s*(?:http://schemas\.android\.com/apk/res/android:)?([A-Za-z0-9_\-]+)(?:\(0x[0-9a-fA-F]+\))?=(.*)$')
        if (-not $attributeMatch.Success) { continue }
        $name = $attributeMatch.Groups[1].Value
        $value = ConvertFrom-XmlTreeValue -Text $attributeMatch.Groups[2].Value

        switch ($element) {
            'manifest' {
                if ($name -eq 'package') { $packageName = $value }
                elseif ($name -eq 'versionName') { $versionName = $value }
                elseif ($name -eq 'versionCode') { $versionCode = $value }
            }
            'uses-permission' {
                if ($name -eq 'name' -and $value) { $permissions.Add($value) }
            }
            'uses-permission-sdk-23' {
                if ($name -eq 'name' -and $value) { $permissions.Add($value) }
            }
            default {
                if ($componentElements -contains $element) {
                    if ($name -eq 'name') { $componentName = $value }
                    elseif ($name -eq 'exported') { $componentExported = ($value -eq 'true') }
                }
            }
        }
    }
    Complete-Component -Name $componentName -Kind $element -IsExported $componentExported -List $exported

    if (-not $packageName) { throw "No package name in the manifest of $Source." }

    # A component written as .Name is inside the package, and the two APKs have to spell the
    # same component the same way or every one of them reads as both added and removed.
    $qualified = New-Object System.Collections.Generic.List[string]
    foreach ($entry in $exported) {
        $kind, $name = $entry -split ':', 2
        if ($name.StartsWith('.')) { $name = $packageName + $name }
        elseif ($name -notmatch '\.') { $name = $packageName + '.' + $name }
        $qualified.Add("${kind}:${name}")
    }

    return [pscustomobject]@{
        package     = $packageName
        versionName = $versionName
        versionCode = $versionCode
        permissions = @($permissions | Sort-Object -Unique -CaseSensitive)
        exported    = @($qualified | Sort-Object -Unique -CaseSensitive)
    }
}

function Invoke-RepoGit {
    <#
    .SYNOPSIS
        git against the repository a caller names, with no inherited git environment.
    .DESCRIPTION
        `git -C <path>` sets the working directory and does not override GIT_DIR. Every script
        here runs from the pre-push hook, and a hook is a git child process with GIT_DIR and
        GIT_WORK_TREE already in its environment, so without this a -Root parameter is a
        suggestion rather than an instruction: git reads whichever repository the hook came from.
        On 2026-09-15 that turned a temporary fixture into three commits on the real branch.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $saved = @{}
    foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' })) {
        $saved[$variable.Name] = $variable.Value
        Remove-Item -LiteralPath ('Env:\' + $variable.Name) -ErrorAction SilentlyContinue
    }
    # Windows PowerShell 5.1 turns a native command's stderr into a terminating error under
    # Stop even when it is redirected. Relax for the call and restore afterwards.
    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        return & git -C $Root @Arguments 2>$null
    } finally {
        $ErrorActionPreference = $preference
        foreach ($name in $saved.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $saved[$name] }
    }
}
