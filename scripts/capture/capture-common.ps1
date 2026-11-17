# Helpers shared by scripts/capture/*.ps1 (dot-source this file). PowerShell 5.1 and 7 compatible.

<# Android density bucket name for a dpi value. #>
function Get-DensityBucket([int]$Dpi) {
    if ($Dpi -le 0) { return 'unknown' }
    if ($Dpi -le 140) { return 'ldpi' }
    if ($Dpi -le 200) { return 'mdpi' }
    if ($Dpi -le 280) { return 'hdpi' }
    if ($Dpi -le 400) { return 'xhdpi' }
    if ($Dpi -le 560) { return 'xxhdpi' }
    return 'xxxhdpi'
}

<# Nearest common aspect-ratio label (long side : short side) for a screen size, e.g. "20:9". #>
function Get-AspectLabel([int]$W, [int]$H) {
    if ($W -le 0 -or $H -le 0) { return 'unknown' }
    $r = [math]::Max($W, $H) / [math]::Min($W, $H)
    $known = [ordered]@{ '1:1' = 1.0; '6:5' = 1.2; '4:3' = 4 / 3; '3:2' = 1.5; '16:10' = 1.6; '5:3' = 5 / 3; '16:9' = 16 / 9
        '18:9' = 2.0; '18.5:9' = 18.5 / 9; '19:9' = 19 / 9; '19.5:9' = 19.5 / 9; '20:9' = 20 / 9; '21:9' = 21 / 9; '22:9' = 22 / 9 }
    $best = $null; $bestD = 99
    foreach ($k in $known.Keys) { $d = [math]::Abs($known[$k] - $r); if ($d -lt $bestD) { $bestD = $d; $best = $k } }
    return $best
}

<# Same rule as MediaCapture.sanitize() in the tests: <SimpleClass>_<method> with odd chars -> '-'. #>
function ConvertTo-ScenarioId([string]$Class, [string]$Method) {
    $simple = ($Class -split '\.')[-1]
    $s = ("{0}_{1}" -f $simple, $Method) -replace '[^A-Za-z0-9_-]+', '-' -replace '-+', '-'
    return $s.TrimEnd('-')
}

<#
  Parses `am instrument -r` raw output into test records:
  @{ class; name; status = passed|failed|error|skipped; message; stack; durationSec }.
  A process crash marks the running test as error with the crash message.
#>
function ConvertFrom-InstrumentOutput([string]$Raw) {
    $tests = New-Object System.Collections.ArrayList
    if (-not $Raw) { return @() }
    $bundle = @{}; $lastKey = $null; $current = $null; $startAt = Get-Date
    $resultMsg = $null
    foreach ($line in ($Raw -split "`r?`n")) {
        if ($line -match '^INSTRUMENTATION_STATUS: ([^=]+)=(.*)$') {
            $lastKey = $Matches[1]; $bundle[$lastKey] = $Matches[2]
        } elseif ($line -match '^INSTRUMENTATION_STATUS_CODE: (-?\d+)') {
            $code = [int]$Matches[1]
            if ($code -eq 1) {
                $current = [ordered]@{ class = $bundle['class']; name = $bundle['test']; status = 'running'; message = $null; stack = $null; durationSec = 0 }
                $startAt = Get-Date
                [void]$tests.Add($current)
            } elseif ($current) {
                switch ($code) {
                    0 { $current.status = 'passed' }
                    -2 { $current.status = 'failed' }
                    -1 { $current.status = 'error' }
                    -3 { $current.status = 'skipped'; $current.message = 'ignored' }
                    -4 { $current.status = 'skipped' }
                    default { $current.status = "code$code" }
                }
                if ($bundle['stack']) {
                    $current.stack = $bundle['stack']
                    $current.message = (($bundle['stack'] -split "`n")[0..[math]::Min(12, ($bundle['stack'] -split "`n").Count - 1)] -join "`n").Trim()
                }
                $current.durationSec = [math]::Round(((Get-Date) - $startAt).TotalSeconds, 1)
                $current = $null
            }
            $bundle = @{}; $lastKey = $null
        } elseif ($line -match '^INSTRUMENTATION_RESULT: ([^=]+)=(.*)$') {
            $lastKey = 'result:' + $Matches[1]; $bundle[$lastKey] = $Matches[2]
            if ($Matches[1] -eq 'shortMsg' -or $Matches[1] -eq 'longMsg') { $resultMsg = $Matches[2] }
        } elseif ($line -match '^INSTRUMENTATION_(CODE|FAILED|ABORTED)') {
            $lastKey = $null
        } elseif ($lastKey) {
            $bundle[$lastKey] += "`n" + $line
        }
    }
    foreach ($t in $tests) {
        if ($t.status -eq 'running') {
            $t.status = 'error'
            $t.message = if ($resultMsg) { "instrumentation aborted: $resultMsg" } else { 'instrumentation aborted (no result reported)' }
        }
    }
    if ($tests.Count -eq 0 -and $resultMsg) {
        [void]$tests.Add([ordered]@{ class = 'runner'; name = 'instrumentation'; status = 'error'; message = $resultMsg; stack = $null; durationSec = 0 })
    }
    return @($tests)
}

<# Writes a JUnit XML report (one testsuite) for the parsed tests. #>
function Write-JUnitXml($Tests, [string]$Suite, [string]$Path) {
    $esc = { param($s) [System.Security.SecurityElement]::Escape("$s") }
    $fail = @($Tests | Where-Object status -eq 'failed').Count
    $err = @($Tests | Where-Object { $_.status -ne 'failed' -and $_.status -ne 'passed' -and $_.status -ne 'skipped' }).Count
    $skip = @($Tests | Where-Object status -eq 'skipped').Count
    $time = ($Tests | Measure-Object -Property durationSec -Sum).Sum
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine('<?xml version="1.0" encoding="UTF-8"?>')
    [void]$sb.AppendLine(('<testsuite name="{0}" tests="{1}" failures="{2}" errors="{3}" skipped="{4}" time="{5}">' -f (& $esc $Suite), $Tests.Count, $fail, $err, $skip, $time))
    foreach ($t in $Tests) {
        [void]$sb.Append(('  <testcase classname="{0}" name="{1}" time="{2}"' -f (& $esc $t.class), (& $esc $t.name), $t.durationSec))
        switch ($t.status) {
            'passed' { [void]$sb.AppendLine(' />') }
            'skipped' { [void]$sb.AppendLine(('><skipped message="{0}" /></testcase>' -f (& $esc $t.message))) }
            'failed' { [void]$sb.AppendLine(('><failure message="{0}">{1}</failure></testcase>' -f (& $esc (($t.message -split "`n")[0])), (& $esc $t.stack))) }
            default { [void]$sb.AppendLine(('><error message="{0}">{1}</error></testcase>' -f (& $esc (($t.message -split "`n")[0])), (& $esc $t.stack))) }
        }
    }
    [void]$sb.AppendLine('</testsuite>')
    [IO.File]::WriteAllText($Path, $sb.ToString())
}
