function Compare-VerifierTallies {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][System.Collections.IDictionary]$Clean,
        [Parameter(Mandatory = $true)][System.Collections.IDictionary]$Patched
    )

    $cleanCounts = [System.Collections.Generic.Dictionary[string, int]]::new(
        [System.StringComparer]::Ordinal)
    foreach ($entry in $Clean.GetEnumerator()) {
        $cleanCounts[[string]$entry.Key] = [int]$entry.Value
    }
    $patchedCounts = [System.Collections.Generic.Dictionary[string, int]]::new(
        [System.StringComparer]::Ordinal)
    foreach ($entry in $Patched.GetEnumerator()) {
        $patchedCounts[[string]$entry.Key] = [int]$entry.Value
    }

    $cleanTotal = 0
    foreach ($count in $cleanCounts.Values) { $cleanTotal += $count }
    $patchedTotal = 0
    foreach ($count in $patchedCounts.Values) { $patchedTotal += $count }

    $deltas = @()
    $messageSet = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal)
    foreach ($message in $cleanCounts.Keys) { [void]$messageSet.Add($message) }
    foreach ($message in $patchedCounts.Keys) { [void]$messageSet.Add($message) }
    $messages = @($messageSet | Sort-Object -CaseSensitive)
    foreach ($message in $messages) {
        $before = if ($cleanCounts.ContainsKey($message)) { $cleanCounts[$message] } else { 0 }
        $after = if ($patchedCounts.ContainsKey($message)) { $patchedCounts[$message] } else { 0 }
        if ($before -eq $after) { continue }
        $deltas += [pscustomobject]@{
            Kind = if ($after -gt $before) { 'extra' } else { 'missing' }
            Message = "$message"
            Clean = $before
            Patched = $after
            Difference = [Math]::Abs($after - $before)
        }
    }

    [pscustomobject]@{
        Valid = $cleanTotal -gt 0 -and $patchedTotal -gt 0 -and $deltas.Count -eq 0
        CleanTotal = $cleanTotal
        PatchedTotal = $patchedTotal
        HasEvidence = $cleanTotal -gt 0 -and $patchedTotal -gt 0
        Deltas = $deltas
    }
}
