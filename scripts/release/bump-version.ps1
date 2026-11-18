<#
.SYNOPSIS
  Bumps the app version, writes the Play changelog and creates the release tag (does NOT push).

.EXAMPLE
  .\scripts\release\bump-version.ps1 patch
  .\scripts\release\bump-version.ps1 minor -Notes "New Collective layout"
  .\scripts\release\bump-version.ps1 7.0.0 -NotesFile notes.txt -Ticket LCA-T12

.DESCRIPTION
  1. version.properties: versionCode + 1, versionName = the new version
  2. fastlane/metadata/<app>/android/en-US/changelogs/<versionCode>.txt for every app (launcher, messages,
     keyboard, lockscreen): release notes (max 500 chars, Play's limit). Without -Notes/-NotesFile they are
     built from commit subjects since the last tag. scripts/release/ship.* then writes per-app notes.
  3. fastlane/release-history.json: adds {versionCode, versionName, date} (the site's changelog page)
  4. commits those three files and creates the annotated tag v<versionName>
  Then push and start the release with scripts/release/ship.ps1 (which runs this script for you).
#>
param(
  [Parameter(Mandatory = $true, Position = 0)][string]$Bump,
  [string]$Notes = "",
  [string]$NotesFile = "",
  [string]$Ticket = "",
  [switch]$NoCommit
)
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $Root
$utf8 = New-Object System.Text.UTF8Encoding($false)

$propsPath = Join-Path $Root "version.properties"
$props = [System.IO.File]::ReadAllLines($propsPath)
$oldName = (($props | Where-Object { $_ -match '^versionName=' }) -replace '^versionName=', '').Trim()
$oldCode = [int]((($props | Where-Object { $_ -match '^versionCode=' }) -replace '^versionCode=', '').Trim())
$parts = ($oldName -split '-')[0] -split '\.'
$maj = [int]$parts[0]; $min = [int]$parts[1]; $pat = [int]$parts[2]

switch -Regex ($Bump) {
  '^major$' { $newName = "$($maj + 1).0.0"; break }
  '^minor$' { $newName = "$maj.$($min + 1).0"; break }
  '^patch$' { $newName = "$maj.$min.$($pat + 1)"; break }
  '^\d+\.\d+\.\d+$' { $newName = $Bump; break }
  default { throw "First argument must be major, minor, patch or X.Y.Z" }
}
$newCode = $oldCode + 1
$tag = "v$newName"

git rev-parse -q --verify "refs/tags/$tag" *> $null
if ($LASTEXITCODE -eq 0) { throw "Tag $tag already exists." }
if (-not $NoCommit) {
  git diff --quiet -- version.properties fastlane/
  if ($LASTEXITCODE -ne 0) { throw "version.properties or fastlane/ has uncommitted changes; commit or stash them first." }
}

# Release notes
if ($NotesFile) {
  $Notes = [System.IO.File]::ReadAllText((Resolve-Path $NotesFile))
} elseif (-not $Notes) {
  $lastTag = git describe --tags --abbrev=0 --match 'v*' 2> $null
  $range = if ($LASTEXITCODE -eq 0 -and $lastTag) { "$lastTag..HEAD" } else { "HEAD" }
  $subjects = git log --no-merges --pretty=format:%s $range |
    ForEach-Object { $_ -replace '^[A-Z]+(\.[A-Z]+)?-T\d+:\s*', '' } |
    Where-Object { $_ -notmatch '^(ci|deps|chore|docs|test)[:(]' -and $_ -notmatch '^Release \d' } |
    Select-Object -Unique -First 12
  $bullet = [string][char]0x2022  # kept ASCII-only: Windows PowerShell 5.1 reads BOM-less scripts as ANSI
  $Notes = ($subjects | ForEach-Object { "$bullet $_" }) -join "`n"
  if (-not $Notes) { $Notes = "$bullet Fixes and improvements" }
}
$Notes = $Notes.Trim()
if ($Notes.Length -gt 500) { $Notes = $Notes.Substring(0, 500) }

# 1. version.properties
$props = $props | ForEach-Object {
  if ($_ -match '^versionCode=') { "versionCode=$newCode" }
  elseif ($_ -match '^versionName=') { "versionName=$newName" }
  else { $_ }
}
[System.IO.File]::WriteAllText($propsPath, (($props -join "`n") + "`n"), $utf8)

# 2. changelog, one per app's metadata folder (all apps share one versionCode)
$changelogRels = @()
foreach ($app in (Get-ChildItem (Join-Path $Root "fastlane/metadata") -Directory)) {
  $meta = Join-Path $app.FullName "android/en-US"
  if (-not (Test-Path $meta)) { continue }
  $rel = "fastlane/metadata/$($app.Name)/android/en-US/changelogs/$newCode.txt"
  $changelog = Join-Path $Root $rel
  New-Item -ItemType Directory -Force (Split-Path $changelog) | Out-Null
  [System.IO.File]::WriteAllText($changelog, ($Notes -replace "`r`n", "`n") + "`n", $utf8)
  $changelogRels += $rel
}
if ($changelogRels.Count -eq 0) { throw "No fastlane/metadata/<app>/android/en-US folders found." }

# 3. release history
$historyRel = "fastlane/release-history.json"
$historyPath = Join-Path $Root $historyRel
$history = @()
if (Test-Path $historyPath) {
  # Windows PowerShell 5.1 emits a JSON array as one object; ForEach-Object unrolls it.
  try { $history = @(Get-Content $historyPath -Raw | ConvertFrom-Json | ForEach-Object { $_ }) } catch { $history = @() }
}
$history = @($history | Where-Object { "$($_.versionCode)" -ne "$newCode" })
$entry = [pscustomobject]@{ versionCode = $newCode; versionName = $newName; date = (Get-Date -Format 'yyyy-MM-dd') }
$history = @($entry) + $history
[System.IO.File]::WriteAllText($historyPath, (ConvertTo-Json -InputObject $history -Depth 3) + "`n", $utf8)

Write-Host "Version $oldName ($oldCode) -> $newName ($newCode)"
Write-Host "Release notes ($($changelogRels -join ', ')):"
Write-Host $Notes

if (-not $NoCommit) {
  $msg = "Release $newName ($newCode)"
  if ($Ticket) { $msg = "${Ticket}: $msg" }
  git add version.properties @changelogRels $historyRel
  git commit -q -m $msg -- version.properties @changelogRels $historyRel
  if ($LASTEXITCODE -ne 0) { throw "git commit failed" }
  git tag -a $tag -m "TrekUI $newName ($newCode)" -m $Notes
  if ($LASTEXITCODE -ne 0) { throw "git tag failed" }
  Write-Host ""
  Write-Host "Committed and tagged $tag (not pushed). To publish the release run:"
  Write-Host "  git push origin main $tag; gh workflow run release.yml --ref $tag"
  Write-Host "(scripts/release/ship.ps1 does all of this in one step.)"
}
