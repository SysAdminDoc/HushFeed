<# Offline contracts for the real receipt producer's package routing and input snapshots. #>
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'release-receipt.ps1')
. (Join-Path $PSScriptRoot 'patch-target.ps1')
$script:producerChecks=0
function Assert-Producer([bool]$Condition,[string]$Message){if(-not $Condition){throw $Message};$script:producerChecks++}
$parent=[IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$work=Join-Path $parent ('hushfeed-release-producer-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($work)
try{
    $catalog=Get-Content -LiteralPath (Join-Path (Split-Path -Parent $PSScriptRoot) 'patches-list.json') -Raw|ConvertFrom-Json
    $expectedTargets=@(Get-DeclaredPatchTargets $catalog)
    $globalPath=Join-Path $work 'global.apk';$pinePath=Join-Path $work 'drama.apk'
    [IO.File]::WriteAllText($globalPath,'synthetic global fixture bytes');[IO.File]::WriteAllText($pinePath,'synthetic drama fixture bytes')
    $script:metadata=@{}
    $script:metadata[$globalPath]=[pscustomobject]@{package='com.zhiliaoapp.musically';versionName='47.5.3';versionCode='2024705030'}
    $script:metadata[$pinePath]=[pscustomobject]@{package='com.ss.android.ttmd.video';versionName='47.5.3';versionCode='2024705030'}
    $script:mutateMetadataPath=''
    # Only aapt metadata is stubbed. Input hashing, selection and missing-target guards are real.
    function Get-ApkManifestFacts([string]$Apk,[string]$Aapt2){
        if($script:mutateMetadataPath -ceq $Apk){[IO.File]::AppendAllText($Apk,'changed during manifest read');$script:mutateMetadataPath=''}
        return $script:metadata[$Apk]
    }
    $builder=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'build-release-receipt.ps1'))
    $first=$builder.IndexOf('$fixtureInputs = ');$last=$builder.IndexOf('$auditFacts = ',$first)
    Assert-Producer ($first -gt 0 -and $last -gt $first) 'The producer lost its pre-patching input snapshot.'
    $preflight=[scriptblock]::Create($builder.Substring($first,$last-$first)+[char]10+'return [pscustomobject]@{inputs=$fixtureInputs.ToArray();prepared=$prepared.ToArray()}')
    $Fixture=@($globalPath,$pinePath);$Option=@();$Aapt2='synthetic-aapt'
    $captured=& $preflight
    $pine=@($captured.prepared|Where-Object {$_.source.package -ceq 'com.ss.android.ttmd.video'})
    $global=@($captured.prepared|Where-Object {$_.source.package -ceq 'com.zhiliaoapp.musically'})
    Assert-Producer ($pine.Count -eq 1 -and $pine[0].selection.patches.Count -eq 98) 'PineDrama did not receive its own 98-patch subset.'
    Assert-Producer ($global.Count -eq 1 -and $global[0].selection.patches.Count -gt 98 -and $global[0].source.sha256 -cne $pine[0].source.sha256) 'Same-version package inputs were merged.'
    Assert-Producer ($pine[0].source.sha256 -ceq (Get-Sha256Hex $pinePath).ToLowerInvariant()) 'The producer did not capture actual source APK bytes.'
    $Fixture=@($globalPath);$missing=$false
    try{$null=& $preflight}catch{$missing=$true}
    Assert-Producer $missing 'The producer accepted a missing advertised package.'
    $Fixture=@($globalPath,$pinePath);$script:metadata[$globalPath].package='com.ss.android.ttmd.video';$wrong=$false
    try{$null=& $preflight}catch{$wrong=$true}
    Assert-Producer $wrong 'A same-version second package filled the global fixture slot.'
    $script:metadata[$globalPath].package='com.zhiliaoapp.musically';$script:mutateMetadataPath=$pinePath;$raced=$false
    try{$null=& $preflight}catch{$raced=$true}
    Assert-Producer $raced 'An APK changed while its manifest facts were captured.'
    $first=$builder.IndexOf('            $enable = @()');$last=$builder.IndexOf('            # Kept, not dropped:',$first)
    Assert-Producer ($first -gt 0 -and $last -gt $first) 'The producer lost the explicit CLI patch selection.'
    $route=[scriptblock]::Create($builder.Substring($first,$last-$first)+[char]10+'return ,$arguments')
    $fixturePatchNames=@('Synthetic A','Synthetic B');$fixtureOptions=@('Synthetic A|flag=true');$forced=$false
    $Bundle='fixture.mpp';$out='patched.apk';$temp='tmp';$resultPath='result.json';$apk=$pinePath
    $arguments=& $route
    $a=[Array]::IndexOf($arguments,'Synthetic A');$b=[Array]::IndexOf($arguments,'Synthetic B')
    Assert-Producer ($a -gt 0 -and $b -gt $a -and $arguments[$a+1] -ceq '-O' -and $arguments[$a+2] -ceq 'flag=true') 'CLI option overrides were not attached to their selected patch.'
    Assert-Producer ($arguments[-1] -ceq $pinePath -and '-f' -cnotin $arguments -and '--exclusive' -cin $arguments) 'The producer patched a different input or forced a declared target.'
    Write-Output ('Release producer contracts passed: '+$script:producerChecks)
}finally{
    $resolved=[IO.Path]::GetFullPath($work)
    if(-not $resolved.StartsWith($parent,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $resolved) -notlike 'hushfeed-release-producer-*'){throw 'Unsafe producer fixture cleanup path.'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
