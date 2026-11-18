<#
.SYNOPSIS
  Runs the instrumented TrekUI scenarios on every AVD of scripts/avd/matrix.csv and collects
  screenshots, videos and reports into the root assets/ folder.

.DESCRIPTION
  1. Builds the develop debug APK and its androidTest APK (skip with -SkipBuild) and copies both to
     build/trekui-capture/ so other builds cannot swap them mid-run.
  2. Runs scripts/capture/run-one.ps1 for each matrix row as a background job, at most -Parallel
     emulators at a time (each on its own console port), each headless and cold-booted with wiped data.
  3. Each run writes assets/<device>-api<N>[-<orientation>]/ (media/, logs/, junit.xml, result.json),
     then the emulator is killed and its user data deleted.
  4. scripts/capture/build-index.ps1 merges all result.json files into assets/index.json.

  Create the AVDs first with scripts/avd/create-avds.ps1.

.EXAMPLE
  pwsh scripts/capture/run-matrix.ps1                     # full matrix, 3 at a time
  pwsh scripts/capture/run-matrix.ps1 -Only 'api3[67]' -Parallel 2
  pwsh scripts/capture/run-matrix.ps1 -Only pixel_7-api36 -TestFilter '.*HomeScenarios.*' -SkipBuild
#>
[CmdletBinding()]
param(
    [string]$Only = '',
    [int]$Parallel = 3,
    [switch]$SkipBuild,
    [string]$TestFilter = '',
    [string]$Gpu = 'swiftshader_indirect',
    [string]$SdkRoot = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { 'D:\Android\Sdk' }),
    [string]$Matrix = '',
    [string]$AssetsDir = '',
    [int]$BasePort = 5580,
    [switch]$NoVideo,
    [string]$FromCommit = '',
    [int]$MemoryMb = 2048
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
. (Join-Path $repo 'scripts/avd/avd-common.ps1')
Initialize-Java
if (-not $Matrix) { $Matrix = Join-Path $repo 'scripts/avd/matrix.csv' }
if (-not $AssetsDir) { $AssetsDir = Join-Path $repo 'assets' }
$env:ANDROID_SDK_ROOT = $SdkRoot

# ---- build ---------------------------------------------------------------------------------------
$stage = Join-Path $repo 'build/trekui-capture'
New-Item -ItemType Directory -Force $stage | Out-Null
# -FromCommit HEAD builds a clean export of that commit (git archive) outside the working tree, so
# half-finished edits of other contributors cannot break or leak into the tested APKs.
$src = $repo
if ($FromCommit) {
    $src = Join-Path ([IO.Path]::GetTempPath()) 'trekui-head'
    if (Test-Path $src) { Remove-Item -Recurse -Force $src }
    New-Item -ItemType Directory -Force $src | Out-Null
    $tar = Join-Path ([IO.Path]::GetTempPath()) 'trekui-head.tar'
    & git -C $repo archive --format=tar -o $tar $FromCommit
    if ($LASTEXITCODE -ne 0) { throw "git archive $FromCommit failed" }
    # Windows' own bsdtar: Git's GNU tar (often first on PATH) reads "R:\..." as a remote host:path.
    $tarExe = Join-Path $env:SystemRoot 'System32\tar.exe'
    if (-not $env:SystemRoot -or -not (Test-Path $tarExe)) { $tarExe = 'tar' }
    & $tarExe -xf $tar -C $src
    if ($LASTEXITCODE -ne 0) { throw "extracting $tar failed" }
    Remove-Item $tar -Force
    foreach ($f in @('local.properties', 'signing')) {
        if (Test-Path (Join-Path $repo $f)) { Copy-Item -Recurse -Force (Join-Path $repo $f) $src }
    }
    Write-Host "building $FromCommit ($(& git -C $repo rev-parse --short $FromCommit)) in $src"
}
if (-not $SkipBuild) {
    Push-Location $src
    try {
        $gradlew = if ($IsLinux -or $IsMacOS) { './gradlew' } else { '.\gradlew.bat' }
        & $gradlew :app:assembleDevelopDebug :app:assembleDevelopDebugAndroidTest --console=plain -q
        if ($LASTEXITCODE -ne 0) { throw "gradle build failed ($LASTEXITCODE)" }
    } finally { Pop-Location }
}
$appApk = Join-Path $stage 'app-develop-debug.apk'
$testApk = Join-Path $stage 'app-develop-debug-androidTest.apk'
Copy-Item (Join-Path $src 'app/build/outputs/apk/develop/debug/app-develop-debug.apk') $appApk -Force
Copy-Item (Join-Path $src 'app/build/outputs/apk/androidTest/develop/debug/app-develop-debug-androidTest.apk') $testApk -Force

# ---- schedule ------------------------------------------------------------------------------------
$rows = @(Import-Csv $Matrix | Where-Object { -not $Only -or (Get-RunKey $_) -match $Only -or (Get-AvdName $_) -match $Only })
$avdHome = Get-AvdHome
$rows = @($rows | Where-Object {
    $ok = Test-Path (Join-Path $avdHome "$(Get-AvdName $_).avd/config.ini")
    if (-not $ok) { Write-Warning "no AVD $(Get-AvdName $_) - run scripts/avd/create-avds.ps1" }
    $ok
})
Write-Host "matrix: $($rows.Count) run(s), $Parallel at a time"
$runOne = Join-Path $PSScriptRoot 'run-one.ps1'
$psExe = (Get-Process -Id $PID).Path
$queue = New-Object System.Collections.Queue
foreach ($r in $rows) { $queue.Enqueue($r) }
$slots = @{}   # slot index -> process
$t0 = Get-Date

while ($queue.Count -gt 0 -or $slots.Count -gt 0) {
    foreach ($k in @($slots.Keys)) {
        if ($slots[$k].Process.HasExited) {
            Write-Host ("[{0:HH:mm:ss}] finished {1} (exit {2})" -f (Get-Date), $slots[$k].Key, $slots[$k].Process.ExitCode)
            $slots.Remove($k)
        }
    }
    while ($queue.Count -gt 0 -and $slots.Count -lt $Parallel) {
        $slot = 0; while ($slots.ContainsKey($slot)) { $slot++ }
        $r = $queue.Dequeue()
        $key = Get-RunKey $r
        $out = Join-Path $AssetsDir $key
        New-Item -ItemType Directory -Force $out | Out-Null
        $meta = ([ordered]@{ profile = $r.profile; tag = $r.tag; form = $r.form; note = $r.note
            requested = if ($r.width) { "$($r.width)x$($r.height)@$($r.density)" } else { 'profile default' } } | ConvertTo-Json -Compress)
        $metaFile = Join-Path $out 'meta.json'
        [IO.File]::WriteAllText($metaFile, $meta)
        $pargs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $runOne,
            '-Avd', (Get-AvdName $r), '-Device', $r.id, '-Api', $r.api, '-Orientation', $r.orientation,
            '-AppApk', $appApk, '-TestApk', $testApk, '-OutDir', $out, '-Port', ($BasePort + 2 * $slot),
            '-SdkRoot', $SdkRoot, '-Gpu', $Gpu, '-MetaFile', $metaFile)
        if ($TestFilter) { $pargs += @('-TestFilter', $TestFilter) }
        if ($NoVideo) { $pargs += '-NoVideo' }
        $pargs += @('-MemoryMb', $MemoryMb)
        $p = Start-Process -FilePath $psExe -ArgumentList $pargs -PassThru -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $out 'run.out.txt') -RedirectStandardError (Join-Path $out 'run.err.txt')
        $slots[$slot] = @{ Process = $p; Key = $key }
        Write-Host ("[{0:HH:mm:ss}] started  {1} on port {2}" -f (Get-Date), $key, ($BasePort + 2 * $slot))
        Start-Sleep -Seconds 20   # stagger cold boots
    }
    Start-Sleep -Seconds 10
}

& (Join-Path $PSScriptRoot 'build-index.ps1') -AssetsDir $AssetsDir
Write-Host ("matrix done in {0:N0} min -> {1}" -f ((Get-Date) - $t0).TotalMinutes, (Join-Path $AssetsDir 'index.json'))
