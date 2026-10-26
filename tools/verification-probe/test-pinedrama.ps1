[CmdletBinding()]
param([string]$Root, [string]$JavaHome = $env:JAVA_HOME, [string]$Sdk = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome) { $JavaHome = 'C:/Program Files/Android/Android Studio/jbr' }
if (-not $Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$java = Join-Path $JavaHome 'bin/java.exe'
$javac = Join-Path $JavaHome 'bin/javac.exe'
$platform = Get-ChildItem -LiteralPath (Join-Path $Sdk 'platforms') -Directory |
    Sort-Object { [int]($_.Name -replace '\D', '') } -Descending | Select-Object -First 1
$android = Join-Path $platform.FullName 'android.jar'
foreach ($file in @($java, $javac, $android)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing PineDrama contract input: $file" }
}
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$output = Join-Path $tempRoot ('hushfeed-pinedrama-contracts-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($output)
try {
    $sources = @('src/app/hushfeed/verification/PineDramaCapabilities.java', 'src/app/hushfeed/verification/PineDramaProbe.java',
        'tests/app/hushfeed/verification/PineDramaCapabilitiesContract.java') | ForEach-Object { Join-Path $PSScriptRoot $_ }
    $before = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $javac -J-Xmx192m -J-XX:ActiveProcessorCount=2 --release 11 -encoding UTF-8 -cp $android -d $output @sources
        $status = $LASTEXITCODE
    } finally { $ErrorActionPreference = $before }
    if ($status) { throw 'PineDrama probe compilation failed.' }
    & $java -Xmx128m -XX:ActiveProcessorCount=2 -cp "$output;$android" app.hushfeed.verification.PineDramaCapabilitiesContract
    if ($LASTEXITCODE) { throw 'PineDrama capability contract failed.' }
} finally {
    $absolute = [IO.Path]::GetFullPath($output)
    if (-not $absolute.StartsWith($tempRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($absolute) -notmatch '^hushfeed-pinedrama-contracts-[a-f0-9]{32}$') { throw 'Owned PineDrama contract cleanup guard failed.' }
    Remove-Item -LiteralPath $absolute -Recurse -Force
}
