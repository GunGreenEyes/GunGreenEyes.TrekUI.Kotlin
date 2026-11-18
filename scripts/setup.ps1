<#
.SYNOPSIS
    Prepares a Windows machine to build TrekUI Launcher from a fresh clone.

.DESCRIPTION
    Idempotent: anything already installed is detected and left alone, so it is safe to re-run.

      1. Tools: JDK 21 (Temurin), Git, GitHub CLI, Python 3, Node.js LTS, ffmpeg
         (winget first, Chocolatey as a fallback).
      2. Android SDK: downloads the command-line tools if no SDK exists, accepts the licences and
         installs platform-tools, emulator, platforms;android-37.0 and build-tools;37.0.0.
         With -Emulators it also installs the system images used by scripts/avd.
      3. Sets ANDROID_HOME (user environment) and writes local.properties (sdk.dir with forward slashes).
      4. Checks hardware virtualisation (WHPX / HAXM / AEHD) for the emulator and says how to enable it.
      5. Check build: gradlew assembleDevelopDebug.

    Use -WhatIf to print every action without changing anything.

.PARAMETER Emulators
    Also install the emulator system images (several GB).

.PARAMETER SkipBuild
    Skip the final check build.

.PARAMETER SdkRoot
    Android SDK location. Default: ANDROID_HOME, ANDROID_SDK_ROOT, local.properties sdk.dir,
    D:\Android\Sdk (if a D: drive exists), otherwise %LOCALAPPDATA%\Android\Sdk.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\setup.ps1
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\setup.ps1 -Emulators -WhatIf
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [switch]$Emulators,
    [switch]$SkipBuild,
    [string]$SdkRoot
)

$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot

# Pinned SDK packages; keep in step with compileSdk/targetSdk in app/build.gradle.kts.
$SdkPackages = @('platform-tools', 'emulator', 'platforms;android-37.0', 'build-tools;37.0.0')
# Emulator images for the test matrix. When scripts/avd/matrix.csv exists (columns include api and
# tag), the images are taken from it instead; this list is the fallback.
$DefaultImages = @(
    'system-images;android-26;google_apis;x86_64',
    'system-images;android-27;default;x86_64',
    'system-images;android-28;google_apis;x86_64',
    'system-images;android-29;google_apis;x86_64',
    'system-images;android-30;google_apis;x86_64',
    'system-images;android-31;google_apis;x86_64',
    'system-images;android-32;google_apis;x86_64',
    'system-images;android-33;google_apis;x86_64',
    'system-images;android-34;google_apis;x86_64',
    'system-images;android-35;google_apis;x86_64',
    'system-images;android-35;google_apis_tablet;x86_64',
    'system-images;android-36;google_apis;x86_64',
    'system-images;android-36;google_apis_tablet;x86_64'
)
# Used when the live repository index cannot be read.
$FallbackCmdlineToolsBuild = '16111833'

$script:Problems = New-Object System.Collections.Generic.List[string]

function Write-Section([string]$Text) { Write-Host ''; Write-Host "== $Text" -ForegroundColor Cyan }
function Write-Ok([string]$Text) { Write-Host "  [ok]   $Text" -ForegroundColor Green }
function Write-Todo([string]$Text) { Write-Host "  [do]   $Text" -ForegroundColor Yellow }
function Write-Warn([string]$Text) { Write-Host "  [warn] $Text" -ForegroundColor Magenta; $script:Problems.Add($Text) }

<# Runs $Action unless -WhatIf is set, in which case it only prints $Description. #>
function Invoke-Step([string]$Description, [scriptblock]$Action) {
    if ($PSCmdlet.ShouldProcess($Description, 'Run')) {
        Write-Todo $Description
        & $Action
    }
}

<# Reloads PATH from the registry so tools installed a moment ago are found without a new shell. #>
function Update-SessionPath {
    $machine = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $user = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = "$machine;$user"
}

function Test-Command([string]$Name) { return [bool](Get-Command $Name -ErrorAction SilentlyContinue) }

<# Installs one tool with winget (exact id) or, failing that, Chocolatey. #>
function Install-Tool([string]$Label, [string]$WingetId, [string]$ChocoId) {
    if (Test-Command 'winget') {
        Invoke-Step "winget install $WingetId ($Label)" {
            & winget install --id $WingetId --exact --silent --accept-source-agreements --accept-package-agreements
            if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne -1978335189) {  # -1978335189 = already installed
                throw "winget exited with $LASTEXITCODE"
            }
        }
    } elseif (Test-Command 'choco') {
        Invoke-Step "choco install $ChocoId ($Label)" {
            & choco install $ChocoId -y
            if ($LASTEXITCODE -ne 0) { throw "choco exited with $LASTEXITCODE" }
        }
    } else {
        Write-Warn "$Label is missing and neither winget nor choco is available. Install it by hand."
        return
    }
    Update-SessionPath
}

<# Returns the home of an installed JDK 21, or $null. #>
function Find-Jdk21 {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    foreach ($base in @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft", "$env:ProgramFiles\Zulu")) {
        if (Test-Path $base) {
            $candidates += Get-ChildItem $base -Directory -Filter '*21*' -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending | ForEach-Object { $_.FullName }
        }
    }
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += (Split-Path -Parent (Split-Path -Parent $onPath.Source)) }
    foreach ($jhome in $candidates) {
        $release = Join-Path $jhome 'release'
        if ((Test-Path (Join-Path $jhome 'bin\java.exe')) -and (Test-Path $release)) {
            if ((Get-Content $release -Raw) -match 'JAVA_VERSION="21') { return $jhome }
        }
    }
    return $null
}

<# Picks the SDK directory from -SdkRoot, the environment, local.properties or a default. #>
function Resolve-SdkRoot {
    if ($SdkRoot) { return $SdkRoot }
    if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { return $env:ANDROID_SDK_ROOT }
    $lp = Join-Path $RepoRoot 'local.properties'
    if (Test-Path $lp) {
        $line = Select-String -Path $lp -Pattern '^\s*sdk\.dir\s*=\s*(.+)$' | Select-Object -First 1
        if ($line) { return ($line.Matches[0].Groups[1].Value.Trim() -replace '\\:', ':' -replace '\\\\', '\') }
    }
    if (Test-Path 'D:\') { return 'D:\Android\Sdk' }
    return (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
}

<# Newest command-line tools build number from Google's repository index, or the pinned fallback. #>
function Get-CmdlineToolsBuild {
    try {
        $xml = (Invoke-WebRequest -UseBasicParsing 'https://dl.google.com/android/repository/repository2-3.xml').Content
        $builds = [regex]::Matches($xml, 'commandlinetools-win-(\d+)_latest\.zip') | ForEach-Object { [int64]$_.Groups[1].Value }
        if ($builds) { return ($builds | Sort-Object -Descending | Select-Object -First 1).ToString() }
    } catch { }
    return $FallbackCmdlineToolsBuild
}

# ---------------------------------------------------------------------------------------------
Write-Host "TrekUI Launcher setup (Windows)  repo: $RepoRoot" -ForegroundColor Cyan
if ($WhatIfPreference) { Write-Host 'Dry run (-WhatIf): nothing will be changed.' -ForegroundColor Yellow }

Write-Section 'Tools'
$jdk = Find-Jdk21
if ($jdk) { Write-Ok "JDK 21: $jdk" } else {
    Install-Tool 'JDK 21' 'EclipseAdoptium.Temurin.21.JDK' 'temurin21'
    $jdk = Find-Jdk21
}
if ($jdk) {
    $env:JAVA_HOME = $jdk
    if ([Environment]::GetEnvironmentVariable('JAVA_HOME', 'User') -ne $jdk -and
        [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine') -ne $jdk) {
        Invoke-Step "set user JAVA_HOME=$jdk" { [Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk, 'User') }
    }
} elseif (-not $WhatIfPreference) {
    Write-Warn 'JDK 21 still not found; Gradle needs it. Install Temurin 21 and set JAVA_HOME.'
}

$tools = @(
    @{ Label = 'Git';         Cmd = 'git';    Winget = 'Git.Git';                Choco = 'git' },
    @{ Label = 'GitHub CLI';  Cmd = 'gh';     Winget = 'GitHub.cli';             Choco = 'gh' },
    @{ Label = 'Python 3';    Cmd = 'python'; Winget = 'Python.Python.3.13';     Choco = 'python' },
    @{ Label = 'Node.js LTS'; Cmd = 'node';   Winget = 'OpenJS.NodeJS.LTS';      Choco = 'nodejs-lts' },
    @{ Label = 'ffmpeg';      Cmd = 'ffmpeg'; Winget = 'Gyan.FFmpeg';            Choco = 'ffmpeg' }
)
foreach ($t in $tools) {
    $found = Get-Command $t.Cmd -ErrorAction SilentlyContinue
    # The Microsoft Store "python" stub is not a real interpreter.
    if ($found -and $t.Cmd -eq 'python' -and $found.Source -like '*WindowsApps*') { $found = $null }
    if ($found) { Write-Ok "$($t.Label): $($found.Source)" } else { Install-Tool $t.Label $t.Winget $t.Choco }
}

Write-Section 'Art tools (pictogram renderer)'
# tools/art/render.js needs @resvg/resvg-js; npm installs it into tools/art/node_modules (gitignored).
$artDir = Join-Path $RepoRoot 'toolsrt'
if (Test-Path (Join-Path $artDir 'node_modules\@resvgesvg-js')) { Write-Ok '@resvg/resvg-js present' }
elseif (Test-Command 'npm') {
    Invoke-Step 'npm install --prefix tools/art' {
        & npm install --prefix $artDir --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { Write-Warn 'npm install in tools/art failed (only needed to re-render pictograms)' }
    }
} else { Write-Warn 'npm missing: install Node.js, then run npm install --prefix tools/art (only needed to re-render pictograms)' }

Write-Section 'Android SDK'
$sdk = [IO.Path]::GetFullPath(((Resolve-SdkRoot) -replace '/', '\')).TrimEnd('\')
Write-Ok "SDK root: $sdk"
$sdkManager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if (Test-Path $sdkManager) { Write-Ok 'cmdline-tools present' } else {
    $build = Get-CmdlineToolsBuild
    $url = "https://dl.google.com/android/repository/commandlinetools-win-${build}_latest.zip"
    Invoke-Step "download $url into $sdk\cmdline-tools\latest" {
        $tmp = Join-Path ([IO.Path]::GetTempPath()) ("trekui-cmdline-" + [Guid]::NewGuid())
        New-Item -ItemType Directory -Force $tmp | Out-Null
        $zip = Join-Path $tmp 'cmdline-tools.zip'
        Invoke-WebRequest -UseBasicParsing $url -OutFile $zip
        Expand-Archive $zip -DestinationPath $tmp -Force
        New-Item -ItemType Directory -Force (Join-Path $sdk 'cmdline-tools') | Out-Null
        Move-Item (Join-Path $tmp 'cmdline-tools') (Join-Path $sdk 'cmdline-tools\latest')
        Remove-Item $tmp -Recurse -Force
    }
}

<# Runs sdkmanager with the SDK root and the given arguments. #>
function Invoke-SdkManager([string[]]$Arguments) {
    & $sdkManager "--sdk_root=$sdk" @Arguments
    if ($LASTEXITCODE -ne 0) { throw "sdkmanager $Arguments exited with $LASTEXITCODE" }
}

# A package is installed when its directory exists (sdkmanager uses ';' as the path separator).
$missing = @($SdkPackages | Where-Object { -not (Test-Path (Join-Path $sdk ($_ -replace ';', '\'))) })
if ($Emulators) {
    $matrixFile = Join-Path $RepoRoot 'scripts\avd\matrix.csv'
    $images = if (Test-Path $matrixFile) {
        Import-Csv $matrixFile | Where-Object { $_.api -and $_.tag -and -not $_.id.StartsWith('#') } |
            ForEach-Object { "system-images;android-$($_.api);$($_.tag);x86_64" } | Sort-Object -Unique
    } else { $DefaultImages }
    $missing += @($images | Where-Object { -not (Test-Path (Join-Path $sdk ($_ -replace ';', '\'))) })
}
if ($missing.Count -eq 0) { Write-Ok 'all SDK packages installed' } else {
    Invoke-Step 'accept Android SDK licences' {
        $yes = ("y`n" * 40)
        $yes | & $sdkManager "--sdk_root=$sdk" --licenses | Out-Null
    }
    Invoke-Step ("sdkmanager install " + ($missing -join ' ')) { Invoke-SdkManager $missing }
}

Write-Section 'Environment'
$envSdk = @([Environment]::GetEnvironmentVariable('ANDROID_HOME', 'User'), [Environment]::GetEnvironmentVariable('ANDROID_HOME', 'Machine')) |
    Where-Object { $_ } | ForEach-Object { ($_ -replace '/', '\').TrimEnd('\') }
if ($envSdk -contains $sdk) { Write-Ok "ANDROID_HOME=$sdk" } else {
    Invoke-Step "set user ANDROID_HOME=$sdk" { [Environment]::SetEnvironmentVariable('ANDROID_HOME', $sdk, 'User') }
}
$env:ANDROID_HOME = $sdk
$localProps = Join-Path $RepoRoot 'local.properties'
$wanted = 'sdk.dir=' + ($sdk -replace '\\', '/')   # forward slashes need no escaping in .properties
$current = if (Test-Path $localProps) { Get-Content $localProps } else { @() }
if ($current -contains $wanted) { Write-Ok "local.properties: $wanted" } else {
    Invoke-Step "write local.properties ($wanted)" {
        $kept = @($current | Where-Object { $_ -notmatch '^\s*sdk\.dir\s*=' })
        [IO.File]::WriteAllLines($localProps, [string[]](@($wanted) + $kept))
    }
}

Write-Section 'Virtualisation (emulator acceleration)'
$emulatorExe = Join-Path $sdk 'emulator\emulator.exe'
if (Test-Path $emulatorExe) {
    $check = & $emulatorExe -accel-check 2>&1 | Out-String
    if ($check -match 'is installed and usable|WHPX.*usable|AEHD.*usable|HAXM.*usable') {
        Write-Ok (($check -split "`n" | Where-Object { $_ -match 'usable' } | Select-Object -First 1).Trim())
    } else {
        Write-Warn ('Emulator acceleration not usable. Enable virtualisation (VT-x/AMD-V/SVM) in the BIOS/UEFI, then run as admin: ' +
            'Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All  (reboot afterwards). ' +
            'Details: emulator -accel-check')
    }
} else {
    $dryRun = $WhatIfPreference; $WhatIfPreference = $false   # module import would otherwise echo "What if: Set Alias"
    Import-Module CimCmdlets -ErrorAction SilentlyContinue
    $WhatIfPreference = $dryRun
    $cpu = Get-CimInstance Win32_Processor -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cpu -and -not $cpu.VirtualizationFirmwareEnabled -and -not (Get-CimInstance Win32_ComputerSystem).HypervisorPresent) {
        Write-Warn 'Virtualisation is off in firmware; the emulator will not run until it is enabled in BIOS/UEFI.'
    } else { Write-Ok 'emulator not installed yet; re-run after install to check acceleration' }
}

if ($SkipBuild) {
    Write-Section 'Check build skipped (-SkipBuild)'
} else {
    Write-Section 'Check build'
    Invoke-Step 'gradlew assembleDevelopDebug' {
        Push-Location $RepoRoot
        try {
            & .\gradlew.bat assembleDevelopDebug --console=plain
            if ($LASTEXITCODE -ne 0) { throw "Gradle build failed ($LASTEXITCODE)" }
            Write-Ok 'APK: app\build\outputs\apk\develop\debug\'
        } finally { Pop-Location }
    }
}

Write-Host ''
if ($script:Problems.Count -gt 0) {
    Write-Host 'Done, with things to fix by hand:' -ForegroundColor Yellow
    $script:Problems | ForEach-Object { Write-Host "  - $_" }
} else {
    Write-Host 'Done. Open a new terminal so ANDROID_HOME / JAVA_HOME / PATH changes apply.' -ForegroundColor Green
}
