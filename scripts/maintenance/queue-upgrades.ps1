<#
.SYNOPSIS
  Turns open platform-upgrade issues and Dependabot pull requests on GitHub into tickets in the local
  GunGreenEyes ticket database, so every new Android / Gradle / library release lands in the work queue.

.DESCRIPTION
  Safe to run any number of times: each issue or PR is queued once (remembered in
  %LOCALAPPDATA%\TrekUI\upgrade-queue.json). Runs weekly via register-upgrade-task.ps1; see
  docs/PLATFORM_UPDATES.md. Needs the GitHub CLI (gh, signed in) and the tickets CLI on PATH.
#>
[CmdletBinding()]
param(
    [string]$Repo = 'GunGreenEyes/TrekUI.Kotlin',
    [string]$TicketsRoot = 'D:/Projects/GunGreenEyes',
    [string]$RepoDir = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
)
$ErrorActionPreference = 'Stop'

$stateDir = Join-Path $env:LOCALAPPDATA 'TrekUI'
$statePath = Join-Path $stateDir ("upgrade-queue-" + ($Repo -replace '[\\/]', '_') + '.json')
New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
$queued = @{}
if (Test-Path $statePath) {
    (Get-Content -Raw $statePath | ConvertFrom-Json).PSObject.Properties | ForEach-Object { $queued[$_.Name] = $_.Value }
}

$items = @()
$issues = gh issue list --repo $Repo --state open --label platform-upgrade --json number,title,url | ConvertFrom-Json
foreach ($i in $issues) { $items += [pscustomobject]@{ Key = "issue#$($i.number)"; Title = $i.title; Url = $i.url } }
$prs = gh pr list --repo $Repo --state open --author 'app/dependabot' --json number,title,url | ConvertFrom-Json
foreach ($p in $prs) { $items += [pscustomobject]@{ Key = "pr#$($p.number)"; Title = "Review and merge: $($p.title)"; Url = $p.url } }

$new = 0
Push-Location $RepoDir
try {
    foreach ($it in $items) {
        if ($queued.ContainsKey($it.Key)) { continue }
        $out = tickets ticket new --type chore --summary "$($it.Title) ($($it.Url))" --ref "GitHub $($it.Key)" --root $TicketsRoot 2>&1
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not queue $($it.Key): $out"; continue }
        $id = ($out | Select-Object -Last 1).ToString().Trim()
        $queued[$it.Key] = $id
        $new++
        Write-Host "Queued $($it.Key) as $id - $($it.Title)"
    }
} finally {
    Pop-Location
}

$queued | ConvertTo-Json | Set-Content -Encoding utf8 $statePath
Write-Host "$new new upgrade ticket(s); $($items.Count) open upgrade item(s) on $Repo."
