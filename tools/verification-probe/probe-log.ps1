# The probe answers in logcat under HushfeedProbe. The phones are shared, so the buffer is never
# cleared: a unique marker line under the same tag starts each broadcast's answer, and only the
# lines after its last copy are read.
#
# Every command also needs the caller's lease on that exact serial (scripts/device-lease.ps1),
# checked again before each one, so a lease that lapses or changes hands between the marker and
# the log read stops the action there.

. (Join-Path $PSScriptRoot '../../scripts/device-lease.ps1')
. (Join-Path $PSScriptRoot 'profile.ps1')

function Select-ProbeRun {
    param([string[]]$Lines, [Parameter(Mandatory = $true)][string]$Marker, [string]$Serial)
    $Lines = @($Lines)
    for ($i = $Lines.Count - 1; $i -ge 0; $i--) {
        if ("$($Lines[$i])".Trim() -eq $Marker) { return @($Lines | Select-Object -Skip ($i + 1)) }
    }
    throw "The log on $Serial no longer holds the start of this probe answer. Retry when the phone is quieter."
}

function Invoke-ProbeAction {
    param(
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$Action,
        [Parameter(Mandatory = $true)][AllowNull()]$Lease,
        [int]$WaitMs = 1200,
        [string[]]$Extras = @(),
        [ValidateSet('tiktok-whole-app-v1', 'pinedrama-47.5.3-capabilities-v1', IgnoreCase = $false)][string]$Profile = 'tiktok-whole-app-v1',
        $InspectionPlan,
        # The fake-device fixtures name a command of their own here.
        [string]$Adb = 'adb'
    )
    $target = Get-VerificationProbeProfile $Profile
    if ($target.Restricted) {
        if ($Action -cne 'capabilities' -or $Extras.Count -ne 0) { throw 'PineDrama permits only its read-only capability action, without caller extras.' }
        $problem = Get-PineDramaInspectionProblem $InspectionPlan
        if ($problem) { throw $problem }
        $Extras = @('-e', 'profile', $Profile, '-e', 'versionCode', [string]$InspectionPlan.build.apk.versionCode,
            '-e', 'apkSha256', [string]$InspectionPlan.build.apk.sha256)
    }
    $marker = "hushfeed-probe-$([guid]::NewGuid().ToString('N'))"
    Assert-DeviceLease -Lease $Lease -Serial $Serial
    & $Adb -s $Serial shell log -t $target.LogTag $marker
    if ($LASTEXITCODE -ne 0) { throw "Could not mark logcat on $Serial before the $Action probe action." }
    Assert-DeviceLease -Lease $Lease -Serial $Serial
    & $Adb -s $Serial shell am broadcast -a app.hushfeed.verification.PROBE -p $target.Package `
        -e action $Action @Extras | Out-Null
    Start-Sleep -Milliseconds $WaitMs
    Assert-DeviceLease -Lease $Lease -Serial $Serial
    $lines = @(& $Adb -s $Serial logcat -d -s "$($target.LogTag):V" -v raw)
    if ($LASTEXITCODE -ne 0) { throw "Could not read logcat on $Serial after the $Action probe action." }
    Select-ProbeRun -Lines $lines -Marker $marker -Serial $Serial
}
