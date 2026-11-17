<#
.SYNOPSIS
  Creates the TrekUI test-emulator matrix (AVDs named trekui_<id>_api<api>) from scripts/avd/matrix.csv.

.DESCRIPTION
  One AVD per distinct (id, api) row of matrix.csv. Device profiles come from avdmanager
  (small_phone, pixel_7, pixel_fold, pixel_tablet, medium_tablet, ...); rows with width/height/
  density override the screen through hw.lcd.* so the matrix covers aspect ratios 5:3, 16:9, 16:10,
  4:3, 18.5:9, 19.5:9, 20:9, 21:9 and densities mdpi..xxxhdpi.

  System images are looked up in -SdkRoot first, then in each -ExtraSdkRoots folder (e.g. API 37
  images installed elsewhere because D: is full). For an image outside -SdkRoot the AVD's
  image.sysdir.1 is written as an absolute path, which the emulator accepts.

  The AVDs are cold-boot only (no snapshots) with a 4 GB data partition; scripts/capture/run-matrix.ps1
  wipes their user data after every run, so the matrix costs almost no disk at rest.
  Existing non-trekui AVDs (e.g. "gaiden", "pixel_7_-_api_36_0") are never touched.

.PARAMETER Only      Regex on the AVD name: create just the matching ones.
.PARAMETER Force     Re-create AVDs that already exist.
.PARAMETER Delete    Delete every trekui_* AVD instead of creating.
.PARAMETER Install   Install missing system images with sdkmanager first (into -InstallRoot).

.EXAMPLE
  pwsh scripts/avd/create-avds.ps1
  pwsh scripts/avd/create-avds.ps1 -Only 'api37' -Force
#>
[CmdletBinding()]
param(
    [string]$SdkRoot = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:\Android\Sdk' }),
    [string[]]$ExtraSdkRoots = @($(if ($env:TREKUI_EXTRA_SDK_ROOTS) { $env:TREKUI_EXTRA_SDK_ROOTS -split ';' } else { 'R:\Android\sdk-extra' })),
    [string]$Matrix = '',
    [string]$Only = '',
    [switch]$Force,
    [switch]$Delete,
    [switch]$Install,
    [string]$InstallRoot = ''
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'avd-common.ps1')
Initialize-Java
if (-not $Matrix) { $Matrix = Join-Path $PSScriptRoot 'matrix.csv' }

$avdHome = Get-AvdHome
$avdmanager = Join-Path $SdkRoot 'cmdline-tools\latest\bin\avdmanager.bat'
$sdkmanager = Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
if ($PSVersionTable.PSEdition -eq 'Core' -and -not $IsWindows) {
    $avdmanager = Join-Path $SdkRoot 'cmdline-tools/latest/bin/avdmanager'
    $sdkmanager = Join-Path $SdkRoot 'cmdline-tools/latest/bin/sdkmanager'
}

if ($Delete) {
    Get-ChildItem $avdHome -Filter 'trekui_*.ini' -ErrorAction SilentlyContinue | ForEach-Object {
        $name = $_.BaseName
        Write-Host "deleting $name"
        Remove-Item -Recurse -Force (Join-Path $avdHome "$name.avd") -ErrorAction SilentlyContinue
        Remove-Item -Force $_.FullName
    }
    return
}

$rows = Import-Csv $Matrix
$seen = @{}
foreach ($r in $rows) {
    $name = Get-AvdName $r
    if ($seen.ContainsKey($name)) { continue }
    $seen[$name] = $true
    if ($Only -and $name -notmatch $Only) { continue }

    $pkg = "system-images;android-$($r.api);$($r.tag);x86_64"
    $rel = [IO.Path]::Combine('system-images', "android-$($r.api)", $r.tag, 'x86_64')
    $root = Find-ImageRoot -Rel $rel -Roots (@($SdkRoot) + $ExtraSdkRoots)
    if (-not $root -and $Install) {
        $target = if ($InstallRoot) { $InstallRoot } else { $SdkRoot }
        Write-Host "installing $pkg into $target"
        'y','y','y','y','y' | & $sdkmanager "--sdk_root=$target" $pkg | Out-Host
        $root = Find-ImageRoot -Rel $rel -Roots (@($SdkRoot) + $ExtraSdkRoots + @($target))
    }
    if (-not $root) { Write-Warning "skip ${name}: image $pkg not installed (use -Install)"; continue }

    $dir = Join-Path $avdHome "$name.avd"
    if ((Test-Path $dir) -and -not $Force) { Write-Host "exists  $name"; continue }

    Write-Host "create  $name  ($($r.profile), $pkg from $root)"
    # avdmanager only accepts images inside its own SDK. For an image in an extra root, create the AVD
    # from a stand-in image of the main SDK and re-point it below (image.sysdir.1, tag, target).
    $createPkg = $pkg
    if ($root -ne $SdkRoot) {
        $standIn = Get-ChildItem (Join-Path $SdkRoot 'system-images') -Directory | Sort-Object { [double]($_.Name -replace '[^0-9.]', '') } -Descending |
            ForEach-Object { $api = $_.Name; Get-ChildItem $_.FullName -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'x86_64/system.img') } |
                Where-Object { $_.Name -notmatch 'tablet' } | ForEach-Object { "system-images;$api;$($_.Name);x86_64" } } | Select-Object -First 1
        if (-not $standIn) { Write-Warning "skip ${name}: no stand-in image in $SdkRoot"; continue }
        $createPkg = $standIn
    }
    $oldPref = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'   # avdmanager writes progress to stderr
    try {
        'no' | & $avdmanager --silent create avd --force --name $name --package $createPkg --device $r.profile --sdcard 256M 2>&1 |
            ForEach-Object { "$_" } | Out-Host
    } finally { $ErrorActionPreference = $oldPref }
    if (-not (Test-Path (Join-Path $dir 'config.ini'))) { Write-Warning "avdmanager failed for $name"; continue }

    $set = [ordered]@{
        'hw.ramSize'               = '2048'
        'vm.heapSize'              = '256'
        'disk.dataPartition.size'  = '4G'
        'hw.cpu.ncore'             = '4'
        'hw.keyboard'              = 'yes'
        'hw.gpu.enabled'           = 'yes'
        'fastboot.forceColdBoot'   = 'yes'
        'fastboot.forceFastBoot'   = 'no'
        'showDeviceFrame'          = 'no'
        'avd.ini.displayname'      = "TrekUI $($r.id) API $($r.api)"
    }
    if ($r.width -and $r.height -and $r.density) {
        $set['hw.lcd.width'] = $r.width
        $set['hw.lcd.height'] = $r.height
        $set['hw.lcd.density'] = $r.density
        $set['skin.name'] = "$($r.width)x$($r.height)"
        $set['skin.dynamic'] = 'yes'
        $set['skin.path'] = '_no_skin'
    }
    if ($root -ne $SdkRoot) {
        # Absolute image path: the emulator from the main SDK can boot an image installed elsewhere.
        $set['image.sysdir.1'] = (Join-Path $root $rel) + [IO.Path]::DirectorySeparatorChar
        $set['tag.id'] = $r.tag
        $set['tag.ids'] = $r.tag
        $set['target'] = "android-$($r.api)"
        Set-IniValues -Path (Join-Path $avdHome "$name.ini") -Values ([ordered]@{ 'target' = "android-$($r.api)" })
    }
    Set-IniValues -Path (Join-Path $dir 'config.ini') -Values $set
}
Write-Host "AVDs in ${avdHome}:"
Get-ChildItem $avdHome -Filter 'trekui_*.ini' | ForEach-Object { '  ' + $_.BaseName }
