# Synthetic source and native facts for offline walk contracts. No native result is asserted here.
function New-WalkNativeFixture([string]$Directory) {
    [void][IO.Directory]::CreateDirectory($Directory)
    $bundle = Join-Path $Directory 'patches-0.79.0.mpp'
    $source = [pscustomobject]@{ commit = 'a' * 40; clean = $true; startFingerprint = 'C' * 64; endFingerprint = 'C' * 64 }
    $manifest = @('Manifest-Version: 1.0', 'Version: 0.79.0', 'Timestamp: 1792886400000', 'Patcher-Version: 2.0.0',
        "Hushfeed-Source-Commit: $($source.commit)", 'Hushfeed-Source-Clean: true',
        "Hushfeed-Source-Start: $($source.startFingerprint)", "Hushfeed-Source-End: $($source.endFingerprint)", '') -join [string][char]10
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::Open($bundle, [IO.Compression.ZipArchiveMode]::Create)
    try {
        $entry = $zip.CreateEntry('META-INF/MANIFEST.MF')
        $writer = [IO.StreamWriter]::new($entry.Open(), [Text.UTF8Encoding]::new($false))
        try { $writer.Write($manifest) } finally { $writer.Dispose() }
    } finally { $zip.Dispose() }
    $sourceApk = [pscustomobject]@{ package = 'com.zhiliaoapp.musically'; versionName = '47.5.3'; versionCode = '2024705030'; sha256 = 'b' * 64 }
    $installed = [pscustomobject]@{ package = $sourceApk.package; versionName = $sourceApk.versionName; versionCode = '2147483647'; sha256 = '4' * 64 }
    $build = [pscustomobject]@{
        status = 'verified'; nativeBuildSchema = 2; receiptSha256 = 'd' * 64; source = $source
        sourceApk = $sourceApk; sourceApkSha256 = $sourceApk.sha256; apk = $installed
        bundle = [pscustomobject]@{ sha256 = (Get-FileHash -LiteralPath $bundle -Algorithm SHA256).Hash.ToLowerInvariant() }
        patches = @('Example', 'Version code'); options = @(); patchReportSha256 = 'e' * 64
    }
    return [pscustomobject]@{
        Bundle = $bundle; SourceApk = $sourceApk; Installed = $installed; Build = $build; Commit = $source.commit
        Expected = @{ ExpectedSourceApk = $sourceApk; ExpectedCommit = $source.commit; ExpectedPatches = $build.patches; ExpectedOptions = @() }
    }
}
