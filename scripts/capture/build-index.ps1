<#
.SYNOPSIS
  Merges every assets/*/result.json (emulator runs), assets/robolectric/ and assets/bluestacks/ into
  assets/index.json - the single file the GitHub Pages gallery reads.

.DESCRIPTION
  index.json layout:
    { generated, summary: {runs, tests, passed, failed, skipped},
      runs: [ { run, device, api, orientation, form, screen: {width,height,density,bucket,aspect,widthDp,heightDp},
                status, summary, durationSec, junit, logs,
                tests: [ { class, name, scenario, status, message, screenshots[], videos[] } ] } ],
      robolectric: [ { interface, config, qualifiers, path } ],
      bluestacks: [ paths ] }
  All media paths are relative to assets/.
#>
[CmdletBinding()]
param([string]$AssetsDir = '')
$ErrorActionPreference = 'Stop'
if (-not $AssetsDir) { $AssetsDir = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'assets' }

$runs = @()
# Matrix rows by run key, to fill in profile/form/note for results written without meta.
. (Join-Path (Join-Path (Split-Path $PSScriptRoot -Parent) 'avd') 'avd-common.ps1')
$rowsByKey = @{}
$matrixCsv = Join-Path (Join-Path (Split-Path $PSScriptRoot -Parent) 'avd') 'matrix.csv'
if (Test-Path $matrixCsv) { foreach ($row in Import-Csv $matrixCsv) { $rowsByKey[(Get-RunKey $row)] = $row } }
foreach ($f in Get-ChildItem $AssetsDir -Directory | ForEach-Object { Join-Path $_.FullName 'result.json' } | Where-Object { Test-Path $_ }) {
    $r = Get-Content $f -Raw | ConvertFrom-Json
    $dir = Split-Path $f -Parent
    $name = Split-Path $dir -Leaf
    if (-not $r.meta -and $rowsByKey.ContainsKey($name)) {
        $m = $rowsByKey[$name]
        $r | Add-Member -Force -NotePropertyName meta -NotePropertyValue ([pscustomobject]@{ profile = $m.profile; tag = $m.tag; form = $m.form; note = $m.note })
    }
    $tests = @($r.tests | ForEach-Object {
        [ordered]@{ class = $_.class; name = $_.name; scenario = $_.scenario; status = $_.status
            message = $_.message; durationSec = $_.durationSec; screenshots = @($_.screenshots); videos = @($_.videos) } })
    $runs += [ordered]@{
        run = $name; avd = $r.avd; device = $r.device; api = $r.api; orientation = $r.orientation
        form = $r.meta.form; profile = $r.meta.profile; tag = $r.meta.tag; note = $r.meta.note
        screen = $r.screen; status = $r.status; error = $r.error; summary = $r.summary; durationSec = $r.durationSec
        started = $r.started
        junit = $(if (Test-Path (Join-Path $dir 'junit.xml')) { "$name/junit.xml" } else { $null })
        logs = @(Get-ChildItem (Join-Path $dir 'logs') -File -ErrorAction SilentlyContinue | ForEach-Object { "$name/logs/$($_.Name)" })
        tests = $tests
    }
}

$robo = @()
$roboDir = Join-Path $AssetsDir 'robolectric'
if (Test-Path $roboDir) {
    # robolectric/<interface>/<config>.png
    $robo = @(Get-ChildItem $roboDir -Recurse -Filter *.png | ForEach-Object {
        [ordered]@{ interface = $_.Directory.Name; config = $_.BaseName
            path = 'robolectric/' + $_.FullName.Substring($roboDir.Length + 1).Replace('\', '/') } })
}
$blue = @()
$blueDir = Join-Path $AssetsDir 'bluestacks'
if (Test-Path $blueDir) { $blue = @(Get-ChildItem $blueDir -File | ForEach-Object { "bluestacks/$($_.Name)" }) }

$all = @($runs | ForEach-Object { $_.tests } | ForEach-Object { $_ })
$index = [ordered]@{
    generated = (Get-Date).ToString('o')
    summary = [ordered]@{
        runs = $runs.Count; tests = $all.Count
        passed = @($all | Where-Object { $_.status -eq 'passed' }).Count
        failed = @($all | Where-Object { $_.status -eq 'failed' -or $_.status -eq 'error' }).Count
        skipped = @($all | Where-Object { $_.status -eq 'skipped' }).Count
        runsWithErrors = @($runs | Where-Object { $_.error }).Count
    }
    runs = $runs
    robolectric = $robo
    bluestacks = $blue
}
$out = Join-Path $AssetsDir 'index.json'
[IO.File]::WriteAllText($out, ($index | ConvertTo-Json -Depth 10))
Write-Host ("index.json: {0} runs, {1} tests ({2} passed, {3} failed, {4} skipped), {5} robolectric shots" -f `
    $runs.Count, $all.Count, $index.summary.passed, $index.summary.failed, $index.summary.skipped, $robo.Count)
