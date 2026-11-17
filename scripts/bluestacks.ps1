<#
.SYNOPSIS
  Installs the TrekUI develop debug APK on BlueStacks 5 (BlueStacks_nxt), launches it and saves
  screenshots into assets/bluestacks/.

.DESCRIPTION
  - Uses the Android SDK adb (falls back to BlueStacks' HD-Adb.exe, which would reset the adb server).
  - Reads every "...status.adb_port" (and "...adb_port") from C:\ProgramData\BlueStacks_nxt\bluestacks.conf
    and connects to 127.0.0.1:<port> (plus the default 5555).
  - Starts the instance with HD-Player.exe if nothing answers (-Start, default on) and waits for boot.
  - Installs the APK (-Apk, default the develop debug APK; builds it with -Build), launches the launcher
    activity (debug-unlocked so the trial screen does not cover it) and saves
    assets/bluestacks/<instance>-home.png, <instance>-result.json and the crash log.
  BlueStacks must have "Android Debug Bridge" enabled (Settings > Advanced > ADB).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts/bluestacks.ps1
  powershell -ExecutionPolicy Bypass -File scripts/bluestacks.ps1 -Instance Pie64 -Build
#>
[CmdletBinding()]
param(
    [string]$BlueStacksDir = "$env:ProgramFiles\BlueStacks_nxt",
    [string]$Conf = "$env:ProgramData\BlueStacks_nxt\bluestacks.conf",
    [string]$Instance = '',
    [string]$Apk = '',
    [switch]$Build,
    [switch]$EnableAdb,
    [bool]$Start = $true,
    [int]$BootTimeoutSec = 240,
    [string]$OutDir = ''
)
$ErrorActionPreference = 'Continue'
$repo = Split-Path $PSScriptRoot -Parent
if (-not $OutDir) { $OutDir = Join-Path $repo 'assets\bluestacks' }
if (-not $Apk) { $Apk = Join-Path $repo 'app\build\outputs\apk\develop\debug\app-develop-debug.apk' }
$pkg = 'com.gungreeneyes.trekui.dev'
$activity = "$pkg/com.gungreeneyes.trekui.MainActivity"

# Prefer the SDK's adb: BlueStacks' older HD-Adb restarts the shared adb server (port 5037) and
# disconnects every running emulator; any adb can talk to BlueStacks over 127.0.0.1:<port>.
$sdkAdb = Join-Path $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { 'D:\Android\Sdk' }) 'platform-tools\adb.exe'
$adb = if (Test-Path $sdkAdb) { $sdkAdb } else { Join-Path $BlueStacksDir 'HD-Adb.exe' }
$player = Join-Path $BlueStacksDir 'HD-Player.exe'
if (-not (Test-Path $adb)) { throw "BlueStacks adb not found at $adb (is BlueStacks 5 installed?)" }

if ($Build) {
    Push-Location $repo
    try { & .\gradlew.bat :app:assembleDevelopDebug --console=plain -q } finally { Pop-Location }
}
if (-not (Test-Path $Apk)) { throw "APK not found: $Apk (use -Build)" }

# ---- ports and instances from bluestacks.conf -------------------------------------------------------
$ports = [ordered]@{}
$instances = @()
if (Test-Path $Conf) {
    foreach ($line in Get-Content $Conf) {
        if ($line -match '^bst\.instance\.([^.]+)\.(status\.)?adb_port="(\d+)"') {
            if (-not $Instance -or $Matches[1] -eq $Instance) { $ports[$Matches[3]] = $Matches[1] }
            if ($instances -notcontains $Matches[1]) { $instances += $Matches[1] }
        }
    }
}
if ($ports.Count -eq 0) { $ports['5555'] = $(if ($Instance) { $Instance } else { 'default' }) }
if (-not $Instance) { $Instance = @($ports.Values)[0] }
Write-Host "BlueStacks instances: $($instances -join ', ')  ports: $($ports.Keys -join ', ')"

function Connect-Any {
    foreach ($p in $ports.Keys) {
        $out = (& $adb connect "127.0.0.1:$p" 2>&1) -join ' '
        if ($out -match 'connected to') {
            $state = (& $adb -s "127.0.0.1:$p" get-state 2>&1) -join ' '
            if ($state -match 'device') { return "127.0.0.1:$p" }
        }
    }
    return $null
}

# BlueStacks only accepts adb connections when "Android Debug Bridge" is on (bst.enable_adb_access).
# With -EnableAdb the script switches it on itself: BlueStacks is closed, the flag set, then restarted.
if ((Test-Path $Conf) -and ((Get-Content $Conf) -match '^bst\.enable_adb_access="0"')) {
    if (-not $EnableAdb) {
        throw "ADB is disabled in BlueStacks: turn on Settings > Advanced > Android Debug Bridge, or rerun with -EnableAdb"
    }
    Write-Host 'enabling BlueStacks ADB access (bst.enable_adb_access="1"), restarting BlueStacks'
    Get-Process HD-Player, BlueStacksAppplayerWeb, HD-Adb -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Sleep 5
    $text = [IO.File]::ReadAllText($Conf) -replace 'bst\.enable_adb_access="0"', 'bst.enable_adb_access="1"'
    [IO.File]::WriteAllText($Conf, $text)
}

$serial = Connect-Any
if (-not $serial -and $Start) {
    if (-not (Test-Path $player)) { throw "HD-Player.exe not found at $player" }
    Write-Host "starting BlueStacks instance $Instance"
    Start-Process $player -ArgumentList @('--instance', $Instance) | Out-Null
    $deadline = (Get-Date).AddSeconds($BootTimeoutSec)
    while (-not $serial -and (Get-Date) -lt $deadline) { Start-Sleep 5; $serial = Connect-Any }
}
if (-not $serial) { throw "no BlueStacks instance answered on adb ports $($ports.Keys -join ', ') (enable ADB in BlueStacks settings)" }

$deadline = (Get-Date).AddSeconds($BootTimeoutSec)
while ((Get-Date) -lt $deadline) {
    if (((& $adb -s $serial shell getprop sys.boot_completed 2>&1) -join '').Trim() -eq '1') { break }
    Start-Sleep 3
}
$release = ((& $adb -s $serial shell getprop ro.build.version.release) -join '').Trim()
$sdk = ((& $adb -s $serial shell getprop ro.build.version.sdk) -join '').Trim()
$size = ((& $adb -s $serial shell wm size) -join ' ')
$density = ((& $adb -s $serial shell wm density) -join ' ')
Write-Host "connected $serial  Android $release (API $sdk)  $size  $density"

# ---- install + launch -----------------------------------------------------------------------------
New-Item -ItemType Directory -Force $OutDir | Out-Null
$install = (& $adb -s $serial install -r -t -g $Apk 2>&1) -join ' '
Write-Host "install: $install"
if ($install -notmatch 'Success') { throw "install failed: $install" }

function Save-Shot([string]$name) {
    $remote = "/sdcard/trekui-$name.png"
    & $adb -s $serial shell screencap -p $remote | Out-Null
    $local = Join-Path $OutDir "$Instance-$name.png"
    & $adb -s $serial pull $remote $local | Out-Null
    & $adb -s $serial shell rm $remote | Out-Null
    Write-Host "screenshot $local"
}

& $adb -s $serial shell am force-stop $pkg | Out-Null
& $adb -s $serial shell am start -W -n $activity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER --ez trekui.debug.unlock true 2>&1 | Out-Host
Start-Sleep 6
$top = ((& $adb -s $serial shell dumpsys activity activities 2>&1) | Select-String -Pattern 'mResumedActivity|topResumedActivity' | Select-Object -First 1) -join ''
Save-Shot 'home'
$running = ((& $adb -s $serial shell pidof $pkg 2>&1) -join '').Trim()
$crash = (& $adb -s $serial logcat -d -b crash 2>&1) -join "`n"
[IO.File]::WriteAllText((Join-Path $OutDir "$Instance-logcat-crash.txt"), $crash)

$summary = [ordered]@{ instance = $Instance; serial = $serial; android = $release; api = $sdk; size = $size.Trim(); density = $density.Trim()
    installed = $true; running = [bool]$running; resumed = $top.Trim(); crashed = ($crash -match $pkg); time = (Get-Date).ToString('o') }
[IO.File]::WriteAllText((Join-Path $OutDir "$Instance-result.json"), ($summary | ConvertTo-Json))
$summary | Format-List | Out-Host
if (-not $running) { Write-Warning 'launcher process is not running after launch - see the crash log'; exit 1 }
