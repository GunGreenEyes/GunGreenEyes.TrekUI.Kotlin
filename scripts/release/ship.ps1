<#
.SYNOPSIS
  Ships the whole TrekUI suite (launcher, messages, keyboard, lockscreen) to Google Play in one command.

.EXAMPLE
  .\scripts\release\ship.ps1                          # next whole number (e.g. 42 -> 43), internal track
  .\scripts\release\ship.ps1 -Track beta
  .\scripts\release\ship.ps1 9 -Track production -Rollout 0.1
  .\scripts\release\ship.ps1 -DryRun                  # print what would happen; change nothing

.DESCRIPTION
  In order:
  1. bump to the next whole-number version (or the given one, which must be greater) with
     scripts/release/bump-version.ps1: one shared versionCode (always +1) and versionName for every app
     in version.properties, plus fastlane/release-history.json. Versions are whole numbers (42, 43, 44, ...);
     versionCode is always the same number.
  2. changelog from the commits since the last v* tag: a new section at the top of CHANGELOG.md and a
     Play "What's new" text per app in fastlane/metadata/<app>/android/en-US/changelogs/<versionCode>.txt
     (500 characters at most; each app lists the commits that touched it or :core)
  3. commit those files only (explicit paths), tag v<versionName>, push main and the tag
  4. gh workflow run release.yml on the tag with the chosen track, then print the Actions run URL

  -Track internal|alpha|beta|production (default internal; production waits for approval in the
  google-play environment), -Rollout (production fraction, default 0.1), -Status completed|draft,
  -NoPlay, -UpdateListing, -Ticket ID (commit message prefix), -NoDispatch (stop after the push),
  -DryRun (print the plan, the notes and the commands; write, commit and push nothing).
  Same behaviour as ship.sh.
#>
param(
  [Parameter(Position = 0)][string]$Version = 'next',
  [ValidateSet('internal', 'alpha', 'beta', 'production')][string]$Track = 'internal',
  [double]$Rollout = 0.1,
  [ValidateSet('completed', 'draft')][string]$Status = 'completed',
  [switch]$NoPlay,
  [switch]$UpdateListing,
  [string]$Ticket = '',
  [switch]$NoDispatch,
  [switch]$DryRun,
  [string]$Remote = 'origin'
)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $Root
$utf8 = New-Object System.Text.UTF8Encoding($false)
$bullet = [string][char]0x2022   # kept ASCII-only: Windows PowerShell 5.1 reads BOM-less scripts as ANSI
if ($Rollout -le 0 -or $Rollout -gt 1) { throw '-Rollout must be a fraction in (0, 1]' }
$rolloutText = $Rollout.ToString([System.Globalization.CultureInfo]::InvariantCulture)

# app folder -> module whose commits go into that app's Play notes (:core counts for all)
$apps = [ordered]@{ launcher = 'app'; messages = 'messages'; keyboard = 'keyboard'; lockscreen = 'lockscreen' }

# Runs a native command with stderr discarded. Windows PowerShell 5.1 turns redirected native stderr into
# terminating errors under ErrorActionPreference Stop, so it is relaxed for the call; $LASTEXITCODE is kept.
function Invoke-Quiet([scriptblock]$cmd) {
  $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try { & $cmd 2> $null } finally { $ErrorActionPreference = $eap }
}

function Stop-Ship([string]$why) {
  if ($DryRun) { Write-Warning "a real run would stop here: $why" } else { throw $why }
}

# --- preflight ---------------------------------------------------------------------------------------
$branch = git symbolic-ref --short -q HEAD
if ($branch -ne 'main') { Stop-Ship "Ship from main (current branch: $branch)." }
Invoke-Quiet { git fetch -q $Remote main --tags } | Out-Null
if ($LASTEXITCODE -eq 0) {
  $behind = [int](git rev-list --count "HEAD..$Remote/main")
  if ($behind -gt 0) { Stop-Ship "main is $behind commit(s) behind $Remote/main; pull first." }
} else { Stop-Ship "Could not fetch $Remote." }
git diff --quiet HEAD -- version.properties CHANGELOG.md fastlane/
if ($LASTEXITCODE -ne 0) { Stop-Ship 'version.properties, CHANGELOG.md or fastlane/ has uncommitted changes; commit or stash them first.' }
if (-not $NoDispatch -and -not $DryRun) {
  if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw 'The GitHub CLI (gh) is needed to start the release workflow.' }
  Invoke-Quiet { gh auth status } | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'gh is not signed in (gh auth login).' }
}

# --- 1. version ----------------------------------------------------------------------------------------
$props = [System.IO.File]::ReadAllLines((Join-Path $Root 'version.properties'))
$oldName = (($props | Where-Object { $_ -match '^versionName=' }) -replace '^versionName=', '').Trim()
$oldCode = [int]((($props | Where-Object { $_ -match '^versionCode=' }) -replace '^versionCode=', '').Trim())
# Whole-number versions; an old X.Y.Z name counts as its major (6.2.0 -> 6).
if ($oldName -notmatch '^(\d+)') { throw "versionName '$oldName' in version.properties does not start with a number" }
$current = [int]$Matches[1]
# One number for both: versionName == versionCode (42, 43, ...).
$current = [Math]::Max($current, $oldCode)
switch -Regex ($Version) {
  '^(next|major)?$' { $newName = "$($current + 1)"; break }
  '^(minor|patch)$' { throw "There are no minor/patch versions any more: versions are whole numbers. Leave the version out (next: $($current + 1)) or give a whole number greater than $current." }
  '^\d+$' {
    if ([int]$Version -le $current) { throw "Version $Version must be greater than the current version $current." }
    $newName = "$([int]$Version)"; break
  }
  default { throw "The version must be a whole number greater than $current, or left out for the next one (got '$Version')." }
}
$newCode = [int]$newName
$tag = "v$newName"
$date = Get-Date -Format 'yyyy-MM-dd'
Invoke-Quiet { git rev-parse -q --verify "refs/tags/$tag" } | Out-Null
if ($LASTEXITCODE -eq 0) { Stop-Ship "Tag $tag already exists." }
Invoke-Quiet { git ls-remote --exit-code --tags $Remote "refs/tags/$tag" } | Out-Null
if ($LASTEXITCODE -eq 0) { Stop-Ship "Tag $tag already exists on $Remote." }

# --- 2. changelog --------------------------------------------------------------------------------------
$lastTag = Invoke-Quiet { git describe --tags --abbrev=0 --match 'v*' }
$range = if ($LASTEXITCODE -eq 0 -and $lastTag) { "$lastTag..HEAD" } else { 'HEAD' }
$global:LASTEXITCODE = 0

function Get-Subjects([string[]]$paths) {
  $logArgs = @('log', '--no-merges', '--pretty=format:%s', $range)
  if ($paths) { $logArgs += '--'; $logArgs += $paths }
  @(& git @logArgs | Where-Object {
      $_ -and $_ -notmatch '^([A-Z]+(\.[A-Z]+)?-T\d+:\s*)?((ci|deps|chore|docs|test)[:(]|Release \d)'
    } | Select-Object -Unique)
}

# Play "What's new": ticket IDs stripped, maintenance lines dropped, each line cut at a word to ~110
# characters, whole lines only, 500 characters at most in total (Play's limit).
function Get-PlayNotes([string[]]$subjects) {
  $lines = @(); $total = 0
  foreach ($s in $subjects) {
    $s = $s -replace '^[A-Z]+(\.[A-Z]+)?-T\d+:\s*', ''
    $s = $s -replace '\s*\(([A-Z]+(\.[A-Z]+)?-T\d+(,\s*)?)+\)\s*$', ''
    if ($s -match '^(Document|Log|Regenerate|Record|Unit-test|Test|Queue|Sync|Point|Export|Refactor|Rename|Bump|Merge)[ :]') { continue }
    if ($s.Length -gt 110) { $s = ($s.Substring(0, 110) -replace '[ ,;:]+[^ ]*$', '') + '...' }
    $line = "$bullet $s"
    $add = $line.Length + $(if ($lines.Count) { 1 } else { 0 })
    if ($total + $add -gt 500) { continue }
    $lines += $line; $total += $add
  }
  if (-not $lines.Count) { return "$bullet Fixes and improvements" }
  return ($lines -join "`n")
}

$allSubjects = Get-Subjects @()
$notesAll = Get-PlayNotes $allSubjects
$appNotes = [ordered]@{}
foreach ($app in $apps.Keys) { $appNotes[$app] = Get-PlayNotes (Get-Subjects @("$($apps[$app])/", 'core/')) }

$section = @("## $newName (versionCode $newCode, $date)", '')
if ($allSubjects.Count) { $section += @($allSubjects | Select-Object -First 40 | ForEach-Object { "- $_" }) }
else { $section += '- Fixes and improvements' }
$section += ''

$since = if ($lastTag) { $lastTag } else { 'the first commit' }
Write-Host "TrekUI suite $oldName ($oldCode) -> $newName ($newCode), tag $tag"
Write-Host "Commits since ${since}: $($allSubjects.Count) user-facing"
Write-Host ''
foreach ($app in $apps.Keys) {
  Write-Host "--- fastlane/metadata/$app/android/en-US/changelogs/$newCode.txt ($($appNotes[$app].Length) chars)"
  Write-Host $appNotes[$app]
}
Write-Host ''
Write-Host '--- CHANGELOG.md (new section at the top)'
Write-Host ($section -join "`n")

$msg = "Release $newName ($newCode)"
if ($Ticket) { $msg = "${Ticket}: $msg" }
$play = if ($NoPlay) { 'false' } else { 'true' }
$listing = if ($UpdateListing) { 'true' } else { 'false' }
$dispatchArgs = @('-f', "track=$Track", '-f', "rollout=$rolloutText", '-f', "status=$Status",
  '-f', "upload-to-play=$play", '-f', "update-listing=$listing", '-f', 'bump=none')

if ($DryRun) {
  Write-Host '--- Dry run: nothing written, committed, tagged or pushed. A real run would:'
  Write-Host "  scripts/release/bump-version.ps1 $newName -NotesFile <notes> -NoCommit"
  Write-Host '  write the four per-app changelogs above and prepend CHANGELOG.md'
  Write-Host "  git commit -m `"$msg`" -- version.properties CHANGELOG.md fastlane/release-history.json fastlane/metadata/*/android/en-US/changelogs/$newCode.txt"
  Write-Host "  git tag -a $tag"
  Write-Host "  git push --atomic $Remote HEAD:refs/heads/main refs/tags/$tag"
  if (-not $NoDispatch) { Write-Host "  gh workflow run release.yml --ref $tag $($dispatchArgs -join ' ')" }
  return
}

# --- write -----------------------------------------------------------------------------------------------
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("trekui-ship-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $tmp | Out-Null
try {
  $notesFile = Join-Path $tmp 'notes-all.txt'
  [System.IO.File]::WriteAllText($notesFile, $notesAll + "`n", $utf8)
  & (Join-Path $PSScriptRoot 'bump-version.ps1') $newName -NotesFile $notesFile -NoCommit | Out-Null
} finally { Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue }
$check = [System.IO.File]::ReadAllLines((Join-Path $Root 'version.properties')) | Where-Object { $_ -match '^versionCode=' }
if ($check -ne "versionCode=$newCode") { throw 'bump-version.ps1 wrote an unexpected versionCode' }

$files = @('version.properties', 'CHANGELOG.md', 'fastlane/release-history.json')
foreach ($app in $apps.Keys) {
  $rel = "fastlane/metadata/$app/android/en-US/changelogs/$newCode.txt"
  $path = Join-Path $Root $rel
  New-Item -ItemType Directory -Force (Split-Path $path) | Out-Null
  [System.IO.File]::WriteAllText($path, $appNotes[$app] + "`n", $utf8)
  $files += $rel
}
foreach ($dir in (Get-ChildItem (Join-Path $Root 'fastlane/metadata') -Directory)) {
  $rel = "fastlane/metadata/$($dir.Name)/android/en-US/changelogs/$newCode.txt"
  if ((Test-Path (Join-Path $Root $rel)) -and ($files -notcontains $rel)) { $files += $rel }
}

$changelogPath = Join-Path $Root 'CHANGELOG.md'
$old = [System.IO.File]::ReadAllText($changelogPath) -replace "`r`n", "`n"
$sectionText = ($section -join "`n") + "`n"
$m = [regex]::Match($old, '(?m)^## ')
$new = if ($m.Success) { $old.Substring(0, $m.Index) + $sectionText + $old.Substring($m.Index) } else { $old + "`n" + $sectionText }
[System.IO.File]::WriteAllText($changelogPath, $new, $utf8)

# --- 3. commit, tag, push --------------------------------------------------------------------------
git add -- @files
if ($LASTEXITCODE -ne 0) { throw 'git add failed' }
git commit -q -m $msg -- @files
if ($LASTEXITCODE -ne 0) { throw 'git commit failed' }
git tag -a $tag -m "TrekUI $newName ($newCode)" -m $notesAll
if ($LASTEXITCODE -ne 0) { throw 'git tag failed' }
git push -q --atomic $Remote HEAD:refs/heads/main "refs/tags/$tag"
if ($LASTEXITCODE -ne 0) { throw 'git push failed (the commit and tag are local; push them by hand, then run gh workflow run)' }
Write-Host "Committed, tagged and pushed $tag ($(git rev-parse --short HEAD))."

if ($NoDispatch) { return }

# --- 4. start the release workflow -----------------------------------------------------------------
gh workflow run release.yml --ref $tag @dispatchArgs
if ($LASTEXITCODE -ne 0) { throw 'gh workflow run failed' }
$url = ''
for ($i = 0; $i -lt 15 -and -not $url; $i++) {
  Start-Sleep -Seconds 2
  $url = Invoke-Quiet { gh run list --workflow release.yml --branch $tag --event workflow_dispatch --limit 1 --json url --jq '.[0].url' }
}
if (-not $url) {
  $repo = Invoke-Quiet { gh repo view --json nameWithOwner --jq .nameWithOwner }
  if (-not $repo) { $repo = 'GunGreenEyes/TrekUI' }
  $url = "https://github.com/$repo/actions/workflows/release.yml"
}
Write-Host "Release $newName started on the $Track track: $url"
