<#
.SYNOPSIS
  Quarterly platform check for both TrekUI editions: finds newer stable Android API levels, Kotlin,
  Android Gradle Plugin and Gradle releases, files one upgrade ticket per repo and finding, and starts
  a CLI on each repo that got new tickets to implement them.

.DESCRIPTION
  Runs from the Windows scheduled task "TrekUI quarterly upgrade" (1 January, April, July and October
  at 03:00; register it with register-quarterly-task.ps1). Safe to run any number of times: every
  finding is filed once per repo (remembered in %LOCALAPPDATA%\TrekUI\quarterly-upgrade.json).

  Sources (public, no sign-in):
    Android  https://dl.google.com/android/repository/repository2-3.xml   (stable platforms;android-N)
    Kotlin   https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml
    AGP      https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml
    Gradle   https://services.gradle.org/versions/current

  What each repo is on is read from its sources: compileSdk (module build files, or TrekUiSdk.COMPILE
  in build-logic), agp in gradle/libs.versions.toml, the wrapper's distributionUrl, and kotlin in the
  version catalog. A repo that rides AGP's built-in Kotlin has no Kotlin pin, so the newest Kotlin it
  was checked against is kept in the state file (-Seed records today's releases as the baseline
  without filing anything). Kotlin is only checked for repos with Kotlin sources.

  minSdk is never touched: the owner's floor is API 23 (every phone from 2016 on).
  See docs/PLATFORM_UPDATES.md.

.EXAMPLE
  pwsh scripts/maintenance/quarterly-upgrade.ps1 -DryRun      # print what would be filed
  pwsh scripts/maintenance/quarterly-upgrade.ps1 -Seed        # record today's Kotlin as the baseline
  pwsh scripts/maintenance/quarterly-upgrade.ps1 -NoCli       # file tickets, start nothing
#>
[CmdletBinding()]
param(
    [string[]]$RepoDirs = @(),
    [string]$TicketsRoot = 'D:/Projects/GunGreenEyes',
    [string]$Account = '',          # orchestrator login for the CLI; empty = the Auto login
    [switch]$DryRun,
    [switch]$Seed,
    [switch]$NoCli
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$stateDir = Join-Path $env:LOCALAPPDATA 'TrekUI'
New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
$statePath = Join-Path $stateDir 'quarterly-upgrade.json'
$logPath = Join-Path $stateDir 'quarterly-upgrade.log'
if (-not $DryRun) { try { Start-Transcript -Path $logPath -Append | Out-Null } catch { } }

if (-not $RepoDirs -or $RepoDirs.Count -eq 0) {
    $parent = Split-Path (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path -Parent
    $RepoDirs = @('TrekUI.Java', 'TrekUI.Kotlin') | ForEach-Object { Join-Path $parent $_ } | Where-Object { Test-Path (Join-Path $_ 'settings.gradle.kts') }
}

# ---- newest stable releases ----------------------------------------------------------------------
function Get-Text([string]$Url) {
    for ($i = 1; $i -le 3; $i++) {
        try { return (Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 60).Content }
        catch { if ($i -eq 3) { throw "fetching $Url failed: $($_.Exception.Message)" }; Start-Sleep -Seconds (10 * $i) }
    }
}
function Get-NewestStable([string]$MetadataUrl) {
    $xml = [xml](Get-Text $MetadataUrl)
    $xml.metadata.versioning.versions.version |
        Where-Object { $_ -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_ } | Select-Object -Last 1
}

$latest = [ordered]@{}
$repoXml = [xml](Get-Text 'https://dl.google.com/android/repository/repository2-3.xml')
$latest.android = ($repoXml.SelectNodes("//*[local-name()='remotePackage']") | Where-Object {
        # Stable only: "android-36" and, since API 36.1, "android-37.2" (minor SDK releases); betas carry
        # a "-betaN" suffix and previews are "android-canary-<date>", so the pattern drops both.
        $_.path -match '^platforms;android-\d+(\.\d+)?$' -and
        ($_.SelectSingleNode("*[local-name()='channelRef']").ref -eq 'channel-0')
    } | ForEach-Object { [int](($_.path -replace '^platforms;android-', '') -replace '\..*$', '') } | Measure-Object -Maximum).Maximum
$latest.kotlin = Get-NewestStable 'https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml'
$latest.agp = Get-NewestStable 'https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml'
$latest.gradle = (Get-Text 'https://services.gradle.org/versions/current' | ConvertFrom-Json).version
Write-Host ("newest stable: Android API {0}, Kotlin {1}, AGP {2}, Gradle {3}" -f $latest.android, $latest.kotlin, $latest.agp, $latest.gradle)

# ---- state ---------------------------------------------------------------------------------------
$state = @{}
if (Test-Path $statePath) {
    (Get-Content -Raw $statePath | ConvertFrom-Json).PSObject.Properties | ForEach-Object {
        $r = @{ kotlin = $_.Value.kotlin; tickets = @{} }
        if ($_.Value.tickets) { $_.Value.tickets.PSObject.Properties | ForEach-Object { $r.tickets[$_.Name] = $_.Value } }
        $state[$_.Name] = $r
    }
}

# ---- what each repo is on ------------------------------------------------------------------------
function Get-RepoVersions([string]$Dir) {
    $v = [ordered]@{ android = $null; kotlin = $null; agp = $null; gradle = $null; usesKotlin = $false }
    $files = @(Get-ChildItem $Dir -Recurse -File -Include '*.gradle.kts', '*.kt' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '[\\/](build|\.gradle|src[\\/](main|test|androidTest)[\\/](java|kotlin)[\\/]com)[\\/]' })
    $sdk = $files | Select-String -Pattern '(compileSdk|COMPILE)\s*=\s*(\d+)\b' | ForEach-Object { [int]$_.Matches[0].Groups[2].Value }
    if ($sdk) { $v.android = ($sdk | Measure-Object -Maximum).Maximum }
    $catalog = Join-Path $Dir 'gradle/libs.versions.toml'
    if (Test-Path $catalog) {
        $toml = Get-Content -Raw $catalog
        if ($toml -match '(?m)^agp\s*=\s*"([^"]+)"') { $v.agp = $Matches[1] }
        if ($toml -match '(?m)^kotlin\s*=\s*"([^"]+)"') { $v.kotlin = $Matches[1] }
    }
    $wrapper = Join-Path $Dir 'gradle/wrapper/gradle-wrapper.properties'
    if ((Test-Path $wrapper) -and ((Get-Content -Raw $wrapper) -match 'gradle-([0-9][^-/]*)-(bin|all)\.zip')) { $v.gradle = $Matches[1] }
    $v.usesKotlin = [bool](Get-ChildItem $Dir -Directory -Recurse -Filter kotlin -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '[\\/]src[\\/]main[\\/]kotlin$' -and $_.FullName -notmatch '[\\/]build-logic[\\/]' } | Select-Object -First 1)
    $v
}
function Test-Newer([string]$Have, [string]$Want) {
    if (-not $Want) { return $false }
    if (-not $Have) { return $true }
    try { return [version]$Want -gt [version]$Have } catch { return $Want -ne $Have }
}

# ---- ticket text ---------------------------------------------------------------------------------
$common = @'
Verify: release lint 0 errors in every module, unit tests green, `./gradlew dist` builds. Keep minSdk 23
(the owner's floor: every phone from 2016 on) - never raise it. Update docs/PLATFORM_UPDATES.md and the
CHANGELOG "Unreleased" section. One commit per ticket on main, subject ending with this ticket's ID.
'@
function Get-Plan([string]$Kind, $Have, $Want, [bool]$Kotlin) {
    switch ($Kind) {
        'android' {
            $where = if ($Kotlin) { 'TrekUiSdk.COMPILE and TrekUiSdk.TARGET in build-logic' } else { 'compileSdk and targetSdk in every module build.gradle.kts' }
            return @{
                summary = "Support Android API $Want (compileSdk/targetSdk $Have -> $Want) - quarterly platform check"
                note    = @"
Android API $Want is stable (repo is on $Have).
1. sdkmanager "platforms;android-$Want" "build-tools;<newest>" (D:\Android\Sdk) and the emulator image
   "system-images;android-$Want;google_apis;x86_64" into R:\Android\sdk-extra.
2. Raise $where to $Want.
3. Read the Android $Want behaviour changes (all apps and apps targeting $Want) and adapt the launcher,
   Messages (default SMS role), Keyboard (IME) and Lock Screen; guard any new API with SDK_INT checks.
4. Add an API $Want phone to scripts/avd/matrix.csv (create it with scripts/avd/create-avds.ps1) and to
   the CI emulator matrix in .github/workflows/emulator.yml.
$common
"@
            }
        }
        'kotlin' {
            return @{
                summary = "Build on Kotlin $Want (was $Have) - quarterly platform check"
                note    = @"
Kotlin $Want is the newest stable release (repo last checked against $Have).
1. If AGP's built-in Kotlin is older than $Want, pin it (kotlin-gradle-plugin $Want on the build
   classpath via the version catalog) and confirm with ./gradlew buildEnvironment.
2. Raise detekt/ktlint and any Kotlin-coupled tool to versions that support $Want.
3. Fix new compiler warnings; adopt new stable language features only where they simplify code.
$common
"@
            }
        }
        'agp' {
            return @{
                summary = "Upgrade the Android Gradle Plugin $Have -> $Want - quarterly platform check"
                note    = @"
AGP $Want is the newest stable release (repo is on $Have).
1. Read the AGP $Want release notes; run the AGP Upgrade Assistant changes by hand (agp in
   gradle/libs.versions.toml), including any new minimum Gradle / JDK / build-tools.
2. Fix deprecations it reports in the build scripts (and build-logic, if present).
$common
"@
            }
        }
        'gradle' {
            return @{
                summary = "Upgrade the Gradle wrapper $Have -> $Want - quarterly platform check"
                note    = @"
Gradle $Want is the current release (wrapper is on $Have).
1. ./gradlew wrapper --gradle-version $Want --distribution-type bin (run it twice so the wrapper jar
   updates too); keep validateDistributionUrl=true and the distributionSha256Sum if present.
2. Check AGP's compatibility table first: if the AGP in use does not support $Want, leave a note and
   close this ticket as blocked by the AGP upgrade instead.
3. Fix new deprecation warnings (--warning-mode all).
$common
"@
            }
        }
    }
}

# ---- per repo ------------------------------------------------------------------------------------
$started = @()
foreach ($dir in $RepoDirs) {
    $name = Split-Path $dir -Leaf
    if (-not $state.ContainsKey($name)) { $state[$name] = @{ kotlin = $null; tickets = @{} } }
    $st = $state[$name]
    $have = Get-RepoVersions $dir
    $kotlinHave = if ($have.kotlin) { $have.kotlin } else { $st.kotlin }
    Write-Host ("{0}: Android API {1}, Kotlin {2}, AGP {3}, Gradle {4}" -f $name, $have.android,
        $(if ($have.usesKotlin) { if ($kotlinHave) { $kotlinHave } else { '(baseline not set)' } } else { 'n/a' }), $have.agp, $have.gradle)

    if ($Seed) {
        if ($have.usesKotlin -and -not $have.kotlin) { $st.kotlin = $latest.kotlin; Write-Host "  Kotlin baseline set to $($latest.kotlin)" }
        continue
    }

    $findings = @()
    if ($have.android -and [int]$latest.android -gt [int]$have.android) { $findings += @{ kind = 'android'; have = "$($have.android)"; want = "$($latest.android)" } }
    if ($have.usesKotlin -and $kotlinHave -and (Test-Newer $kotlinHave $latest.kotlin)) { $findings += @{ kind = 'kotlin'; have = $kotlinHave; want = $latest.kotlin } }
    if (Test-Newer $have.agp $latest.agp) { $findings += @{ kind = 'agp'; have = $have.agp; want = $latest.agp } }
    if (Test-Newer $have.gradle $latest.gradle) { $findings += @{ kind = 'gradle'; have = $have.gradle; want = $latest.gradle } }
    if ($have.usesKotlin -and -not $kotlinHave) { $st.kotlin = $latest.kotlin }   # first run: baseline, nothing to file

    $newIds = @()
    foreach ($f in $findings) {
        $key = "$($f.kind)-$($f.want)"
        if ($st.tickets.ContainsKey($key)) { Write-Host "  already filed: $key as $($st.tickets[$key])"; continue }
        $plan = Get-Plan $f.kind $f.have $f.want $have.usesKotlin
        if ($DryRun) { Write-Host "  would file: $($plan.summary)"; continue }
        Push-Location $dir
        try {
            $out = tickets ticket new --type chore --summary $plan.summary --ref "quarterly-upgrade $key" --root $TicketsRoot 2>&1
            if ($LASTEXITCODE -ne 0) { Write-Warning "could not file $key for ${name}: $out"; continue }
            $id = ($out | Select-Object -Last 1).ToString().Trim()
            tickets ticket note $id $plan.note --root $TicketsRoot 2>&1 | Out-Null
        } finally { Pop-Location }
        $st.tickets[$key] = $id
        if ($f.kind -eq 'kotlin') { $st.kotlin = $f.want }
        $newIds += $id
        Write-Host "  filed $id - $($plan.summary)"
    }

    if ($newIds.Count -gt 0 -and -not $NoCli -and -not $DryRun) {
        $prompt = ("Quarterly platform upgrade for $name, filed by the scheduled task 'TrekUI quarterly upgrade' on the " +
            "owner's standing order (keep TrekUI current with every new Android and Kotlin release). Implement these tickets " +
            "in this repo, oldest first: $($newIds -join ', '). Read each with: tickets ticket show <ID> --root $TicketsRoot " +
            "(its note has the steps). Work on main; one commit per ticket, subject ending with its ID; build, release lint " +
            "and unit tests are the verification this order authorises; never change minSdk (23). Push when green and close " +
            "each ticket with tickets ticket close <ID> --root $TicketsRoot. If a ticket is blocked (e.g. Gradle waiting on " +
            "AGP), note why on the ticket and leave it open.") -replace '"', "'"
        $orch = Get-Command orchestrator -ErrorAction SilentlyContinue
        if (-not $orch) { Write-Warning "orchestrator CLI not found; tickets $($newIds -join ', ') wait for /allocate"; continue }
        $oargs = @('start', $name, '--prompt', $prompt)
        if ($Account) { $oargs += @('--account', $Account) }
        & $orch.Source @oargs
        if ($LASTEXITCODE -eq 0) { $started += $name; Write-Host "  started a CLI on $name" }
        else { Write-Warning "orchestrator start $name failed ($LASTEXITCODE); tickets $($newIds -join ', ') wait for /allocate" }
    }
}

if (-not $DryRun) {
    $out = @{}
    foreach ($k in $state.Keys) { $out[$k] = @{ kotlin = $state[$k].kotlin; tickets = $state[$k].tickets } }
    $out | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 $statePath
    try { Stop-Transcript | Out-Null } catch { }
}
Write-Host ("done; CLI started on: {0}" -f $(if ($started) { $started -join ', ' } else { 'none' }))
