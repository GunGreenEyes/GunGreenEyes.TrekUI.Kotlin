<#
.SYNOPSIS
  Boots one TrekUI test AVD, runs the instrumented scenarios on it and collects the media.

.DESCRIPTION
  Called by run-matrix.ps1 (one background job per emulator) but usable alone:

    pwsh scripts/capture/run-one.ps1 -Avd trekui_pixel_7_api36 -Device pixel_7 -Api 36 `
         -AppApk app.apk -TestApk test.apk -OutDir assets/pixel_7-api36

  Steps: cold-boot the AVD headless with wiped data -> wait for boot -> quiet the device (animations
  off, stay awake, keyguard dismissed) -> install the develop debug APK and its androidTest APK ->
  run the scenarios with `am instrument`, one instrumentation per test class (and one per interface
  for InterfaceScenarios, so a crash only loses that slice) -> pull /sdcard/trekui-test (PNG + MP4) ->
  save logcat and the crash buffer -> write junit.xml and result.json -> kill the emulator and delete
  its user data.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string]$Avd,
    [Parameter(Mandatory)] [string]$Device,
    [Parameter(Mandatory)] [string]$Api,
    [Parameter(Mandatory)] [string]$AppApk,
    [Parameter(Mandatory)] [string]$TestApk,
    [Parameter(Mandatory)] [string]$OutDir,
    [string]$Orientation = 'natural',
    [int]$Port = 5580,
    [string]$SdkRoot = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { 'D:\Android\Sdk' }),
    [string]$Gpu = 'host',   # the real GPU, forced: 'auto' can fall back to software SwiftShader (seen under Remote Desktop), which froze API 33-36 guests on the COLLECTIVE screens
    [string]$MetaFile = '',
    [string]$TestFilter = '',
    [int]$BootTimeoutSec = 900,
    [int]$SliceTimeoutSec = 900,
    [int]$MemoryMb = 2048,
    [switch]$KeepData,
    [switch]$NoVideo
)
$ErrorActionPreference = 'Continue'
. (Join-Path (Join-Path (Split-Path $PSScriptRoot -Parent) 'avd') 'avd-common.ps1')
. (Join-Path $PSScriptRoot 'capture-common.ps1')

$serial = "emulator-$Port"
$exe = if ($IsLinux -or $IsMacOS) { '' } else { '.exe' }
$adb = Join-Path $SdkRoot "platform-tools/adb$exe"
$emulator = Join-Path $SdkRoot "emulator/emulator$exe"
$appPkg = 'com.gungreeneyes.trekui.dev'
$testPkg = 'com.gungreeneyes.trekui.dev.test'
$runner = "$testPkg/androidx.test.runner.AndroidJUnitRunner"

New-Item -ItemType Directory -Force $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path
$logDir = Join-Path $OutDir 'logs'
New-Item -ItemType Directory -Force $logDir | Out-Null
Get-ChildItem $OutDir -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -ne 'meta.json' -and $_.Name -notlike 'run.*.txt' } |
    Remove-Item -Force -ErrorAction SilentlyContinue
Get-ChildItem $logDir, (Join-Path $OutDir 'media') -File -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
$started = Get-Date

function Log([string]$m) {
    $line = "[{0:HH:mm:ss}] [{1}] {2}" -f (Get-Date), $Avd, $m
    Write-Host $line
    try { [IO.File]::AppendAllText((Join-Path $logDir 'runner.log'), $line + [Environment]::NewLine) } catch { }  # a reader may hold the file
}
function Adb { & $adb -s $serial @args 2>&1 | ForEach-Object { "$_" } }
function Shell([string]$cmd) { (& $adb -s $serial shell $cmd 2>&1 | ForEach-Object { "$_" }) -join "`n" }

# Runs adb with a hard timeout, output to a file; returns $true if it finished in time.
function Invoke-AdbTimed([string[]]$AdbArgs, [string]$OutFile, [int]$TimeoutSec) {
    $p = Start-Process -FilePath $adb -ArgumentList (@('-s', $serial) + $AdbArgs) -NoNewWindow -PassThru `
        -RedirectStandardOutput $OutFile -RedirectStandardError "$OutFile.err"
    if (-not $p.WaitForExit($TimeoutSec * 1000)) { try { $p.Kill() } catch {}; return $false }
    return $true
}

$result = [ordered]@{
    run = (Split-Path $OutDir -Leaf); avd = $Avd; device = $Device; api = $Api; orientation = $Orientation
    meta = $(if ($MetaFile -and (Test-Path $MetaFile)) { Get-Content $MetaFile -Raw | ConvertFrom-Json } else { $null }); started = $started.ToString('o'); status = 'error'; error = $null
    screen = $null; tests = @(); summary = $null
}

$emu = $null
try {
    # ---- boot ------------------------------------------------------------------------------------
    # Never drive somebody else's emulator: the console port must be free before we boot.
    $busy = ((& $adb -s $serial get-state 2>&1) -join ' ') -match '^device|offline'
    if (-not $busy) { try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', $Port); $c.Close(); $busy = $true } catch { } }
    if ($busy) { $emu = $null; $serial = 'none'; throw "port $Port is already used by another emulator" }
    $avdIni = Get-IniValues (Join-Path (Get-AvdHome) "$Avd.avd/config.ini")
    $script:boots = 0

    # Cold-boots the AVD with wiped data and waits until the package manager answers.
    function Start-Emulator {
        $script:boots++
        $emuArgs = @('-avd', $Avd, '-port', "$Port", '-no-window', '-no-audio', '-no-boot-anim', '-no-snapshot',
                     '-wipe-data', '-gpu', $Gpu, '-memory', "$MemoryMb", '-cores', '4')
        $sys = $avdIni['image.sysdir.1']
        if ($sys -and [IO.Path]::IsPathRooted($sys)) { $emuArgs += @('-sysdir', $sys.TrimEnd('\', '/')) }
        Log "booting (#$($script:boots)): emulator $($emuArgs -join ' ')"
        $t0 = Get-Date
        $script:emu = Start-Process -FilePath $emulator -ArgumentList $emuArgs -PassThru -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $logDir "emulator-$($script:boots).log") -RedirectStandardError (Join-Path $logDir "emulator-$($script:boots).err.log")
        $deadline = (Get-Date).AddSeconds($BootTimeoutSec)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 5
            if ($script:emu.HasExited) { throw "emulator exited with code $($script:emu.ExitCode) (see logs/emulator*.log)" }
            $b = (Shell 'getprop sys.boot_completed').Trim()
            if ($b -match 'not found|offline') {
                # The adb server was restarted (e.g. by BlueStacks' older HD-Adb) and lost the emulator:
                # re-attach it through its adb port (console port + 1).
                & $adb connect "127.0.0.1:$($Port + 1)" 2>&1 | Out-Null
            }
            if ($b -eq '1' -and (Shell 'pm path android') -match 'package:') {
                $name = ((& $adb -s $serial emu avd name 2>&1) | Select-Object -First 1) -join ''
                if ($name.Trim() -ne $Avd) { throw "emulator on port $Port is '$($name.Trim())', not $Avd" }
                Log "booted in $([int]((Get-Date) - $t0).TotalSeconds)s"
                Start-Sleep -Seconds 10   # let the launcher/system UI settle after boot
                foreach ($c in @('settings put global window_animation_scale 0', 'settings put global transition_animation_scale 0',
                                 'settings put global animator_duration_scale 0', 'svc power stayon true',
                                 'settings put system screen_off_timeout 1800000', 'settings put secure immersive_mode_confirmations confirmed',
                                 'settings put secure show_ime_with_hard_keyboard 0', 'input keyevent 224', 'wm dismiss-keyguard',
                                 'input keyevent 82', 'am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS',
                                 'rm -rf /sdcard/trekui-test', 'mkdir -p /sdcard/trekui-test',
                                 # Offline: a freshly wiped google_apis image spends its first ~10 min downloading and
                                 # applying Play-services modules and flag syncs, which froze API 34-36 guests mid-run.
                                 # No scenario needs the network.
                                 'svc wifi disable', 'svc data disable')) {
                    Shell $c | Out-Null
                }
                return
            }
        }
        throw "boot timed out after $BootTimeoutSec s"
    }

    # Installs the app and test APKs (fresh, runtime permissions granted).
    function Install-Apks {
        Adb uninstall $appPkg | Out-Null
        Adb uninstall $testPkg | Out-Null
        $i1 = (Adb install -r -t -g $AppApk) -join ' '
        $i2 = (Adb install -r -t $TestApk) -join ' '
        Log "install app: $i1 | test: $i2"
        if ($i1 -notmatch 'Success') { throw "app install failed: $i1" }
        if ($i2 -notmatch 'Success') { throw "test install failed: $i2" }
    }

    # True if the emulator still answers a shell command within a few seconds.
    function Test-Alive {
        if ($script:emu.HasExited) { return $false }
        $p = Start-Process -FilePath $adb -ArgumentList @('-s', $serial, 'shell', 'echo', 'ok') -NoNewWindow -PassThru `
            -RedirectStandardOutput (Join-Path $logDir 'alive.txt') -RedirectStandardError (Join-Path $logDir 'alive.err.txt')
        if (-not $p.WaitForExit(20000)) { try { $p.Kill() } catch {}; return $false }
        return ((Get-Content (Join-Path $logDir 'alive.txt') -Raw) -match 'ok')
    }

    # Kills a hung/lost emulator (host memory pressure or a GPU stall can freeze one) and boots it again.
    function Restart-Emulator([string]$why) {
        Log "emulator lost ($why) - restarting"
        & $adb -s $serial emu kill 2>&1 | Out-Null
        if (-not $script:emu.WaitForExit(30000)) { try { Stop-Process -Id $script:emu.Id -Force } catch {} }
        Get-CimInstance Win32_Process -Filter "Name like 'qemu-system%'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -match "-avd $([regex]::Escape($Avd)) " } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 5
        Start-Emulator
        Install-Apks
    }

    Start-Emulator
    $emu = $script:emu
    # ---- screen facts ----------------------------------------------------------------------------
    $size = Shell 'wm size'; $dens = Shell 'wm density'
    $w = 0; $h = 0; $dpi = 0
    if ($size -match '(\d+)x(\d+)') { $w = [int]$Matches[1]; $h = [int]$Matches[2] }
    if ($dens -match '(\d+)') { $dpi = [int]$Matches[1] }
    $result.screen = [ordered]@{ width = $w; height = $h; density = $dpi; bucket = (Get-DensityBucket $dpi)
        aspect = (Get-AspectLabel $w $h); widthDp = [int]([math]::Round([math]::Min($w, $h) * 160 / [math]::Max($dpi, 1)))
        heightDp = [int]([math]::Round([math]::Max($w, $h) * 160 / [math]::Max($dpi, 1)))
        release = (Shell 'getprop ro.build.version.release').Trim(); sdk = (Shell 'getprop ro.build.version.sdk').Trim() }
    Log "screen $w x $h @ $dpi ($($result.screen.aspect), $($result.screen.bucket))"

    # ---- install ---------------------------------------------------------------------------------
    Install-Apks
    Adb logcat -c | Out-Null
    $mediaDir = Join-Path $OutDir 'media'
    New-Item -ItemType Directory -Force $mediaDir | Out-Null

    # ---- run -------------------------------------------------------------------------------------
    $slices = @()
    if ($TestFilter) {
        $slices += ,@('-e', 'tests_regex', $TestFilter)
    } else {
        foreach ($cls in @('HomeScenarios', 'SettingsScenarios', 'RotationScenarios', 'TrialScenarios')) {
            $slices += ,@('-e', 'class', "com.gungreeneyes.trekui.$cls")
        }
        foreach ($iface in @('COMMAND', 'COMMAND_REVEAL', 'IMPERIAL', 'IMPERIAL_REVEAL', 'COLLECTIVE', 'COLLECTIVE_HUB',
                             'CLASSIC_CONSOLE', 'NEXT_CONSOLE')) {
            # Single quotes: adb hands the line to the device shell, which would expand * and [ ].
            $slices += ,@('-e', 'tests_regex', "'.*InterfaceScenarios.*\[$iface\].*'")
        }
    }
    $allTests = @()
    $n = 0
    foreach ($s in $slices) {
        $n++
        $args2 = @('shell', 'am', 'instrument', '-w', '-r', '-e', 'trekuiDevice', $Device, '-e', 'trekuiOrientation', $Orientation, '-e', 'trekuiVideo', $(if ($NoVideo) { 'false' } else { 'true' })) + $s + @($runner)
        for ($attempt = 1; $attempt -le 2; $attempt++) {
            if (-not (Test-Alive)) {
                if ($script:boots -ge 4) { throw "emulator keeps dying (booted $($script:boots) times)" }
                Restart-Emulator "unresponsive before slice $n"
            }
            $raw = Join-Path $logDir ("instrument-{0:D2}{1}.txt" -f $n, $(if ($attempt -gt 1) { "-retry" } else { '' }))
            Log "instrument slice $n/$($slices.Count): $($s -join ' ')$(if ($attempt -gt 1) { ' (retry)' })"
            $ok = Invoke-AdbTimed -AdbArgs $args2 -OutFile $raw -TimeoutSec $SliceTimeoutSec
            $parsed = @(ConvertFrom-InstrumentOutput (Get-Content $raw -Raw -ErrorAction SilentlyContinue))
            # A slice that died with the emulator (device offline / not found) is run again once on a
            # fresh boot; a slice whose app process crashed keeps its result - that is a real finding.
            $lost = (-not $ok) -or ((Get-Content "$raw.err" -Raw -ErrorAction SilentlyContinue) -match 'not found|offline|closed') -or
                    ($parsed.Count -gt 0 -and -not (Test-Alive))
            if ($lost -and $attempt -eq 1) { Log "  -> device lost during slice $n"; continue }
            break
        }
        if (-not $ok) { $parsed += [ordered]@{ class = 'runner'; name = "slice $n timeout"; status = 'error'; message = "am instrument timed out after $SliceTimeoutSec s ($($s -join ' '))"; durationSec = $SliceTimeoutSec } }
        $allTests += $parsed
        $f = @($parsed | Where-Object { $_.status -ne 'passed' -and $_.status -ne 'skipped' }).Count
        Log ("  -> {0} tests, {1} not passing" -f $parsed.Count, $f)
        # Pull this slice's media now (an emulator that dies later cannot take it with it).
        Adb pull /sdcard/trekui-test/. $mediaDir | Out-Null
        Shell 'rm -rf /sdcard/trekui-test/*' | Out-Null
        # Recover from a crash: make sure the next slice starts from a clean, unlocked screen.
        Shell 'input keyevent 3' | Out-Null
    }

    # ---- collect ---------------------------------------------------------------------------------
    Invoke-AdbTimed -AdbArgs @('logcat', '-d', '-b', 'crash') -OutFile (Join-Path $logDir 'crash.txt') -TimeoutSec 60 | Out-Null
    Invoke-AdbTimed -AdbArgs @('logcat', '-d', '-v', 'threadtime') -OutFile (Join-Path $logDir 'logcat.txt') -TimeoutSec 120 | Out-Null

    $media = @(Get-ChildItem $mediaDir -File -ErrorAction SilentlyContinue)
    foreach ($t in $allTests) {
        $id = ConvertTo-ScenarioId "$($t.class)" "$($t.name)"
        $t.scenario = $id
        $mine = @($media | Where-Object { $_.Name.StartsWith("${id}__") -or $_.Name.StartsWith("${id}-") })
        $t.screenshots = @($mine | Where-Object { $_.Extension -eq '.png' } | ForEach-Object { "$($result.run)/media/$($_.Name)" })
        $t.videos = @($mine | Where-Object { $_.Extension -eq '.mp4' -and $_.Length -gt 0 } | ForEach-Object { "$($result.run)/media/$($_.Name)" })
    }
    $result.tests = $allTests
    $result.summary = [ordered]@{
        total = $allTests.Count
        passed = @($allTests | Where-Object status -eq 'passed').Count
        failed = @($allTests | Where-Object { $_.status -eq 'failed' -or $_.status -eq 'error' }).Count
        skipped = @($allTests | Where-Object status -eq 'skipped').Count
    }
    $result.status = if ($result.summary.failed -eq 0 -and $result.summary.total -gt 0) { 'passed' } else { 'failed' }
    Write-JUnitXml -Tests $allTests -Suite $result.run -Path (Join-Path $OutDir 'junit.xml')
    Log ("done: {0} passed, {1} failed, {2} skipped" -f $result.summary.passed, $result.summary.failed, $result.summary.skipped)
}
catch {
    $result.error = "$_"
    Log "ERROR: $_"
}
finally {
    $result.durationSec = [int]((Get-Date) - $started).TotalSeconds
    $result | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $OutDir 'result.json')
    # ---- shut down and free the disk -------------------------------------------------------------
    & $adb -s $serial emu kill 2>&1 | Out-Null
    if ($script:emu) { $emu = $script:emu }
    if ($emu) {
        if (-not $emu.WaitForExit(60000)) { try { Stop-Process -Id $emu.Id -Force } catch {} }
    }
    if (-not $KeepData) {
        $avdDir = Join-Path (Get-AvdHome) "$Avd.avd"
        Start-Sleep -Seconds 2
        Get-ChildItem $avdDir -ErrorAction SilentlyContinue | Where-Object {
            $_.Name -like 'userdata-qemu.img*' -or $_.Name -like 'cache.img*' -or $_.Name -like '*.qcow2' -or
            $_.Name -eq 'snapshots' -or $_.Name -like 'sdcard.img.qcow2' -or $_.Name -like 'encryptionkey.img*' -or $_.Name -eq 'tmpAdbCmds'
        } | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
    }
    Log "emulator stopped, data wiped=$(-not $KeepData)"
}
