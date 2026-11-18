<#
.SYNOPSIS
  One-time setup for publishing TrekUI: everything that can be scripted, then the Play Console steps
  that only the account owner can do. Safe to re-run: each step skips what is already in place.

.DESCRIPTION
  1. Creates the Play upload key (scripts/release/create-upload-key.ps1) unless signing\ already has one.
  2. Stores the release secrets in the GitHub repo: TREKUI_KEYSTORE_BASE64, TREKUI_STORE_PASSWORD,
     TREKUI_KEY_ALIAS, TREKUI_KEY_PASSWORD and (with -ServiceAccountJson) PLAY_SERVICE_ACCOUNT_JSON.
     Values are piped to gh, never printed.
  3. Creates the `google-play` environment with you as required reviewer (production uploads wait for you).
  4. Turns on GitHub Pages built by GitHub Actions (the project site and privacy policy).
  5. Registers the weekly "TrekUI upgrade queue" task (docs/PLATFORM_UPDATES.md).
  6. Prints the Play Console checklist (docs/PLAY_STORE.md has every form answer).
  Needs: GitHub CLI signed in as the repo owner (`gh auth login`), a JDK 17+ (JAVA_HOME) for the key.

.EXAMPLE
  .\scripts\release\setup-owner.ps1
  .\scripts\release\setup-owner.ps1 -ServiceAccountJson C:\keys\play-service-account.json
#>
[CmdletBinding()]
param(
    [string]$ServiceAccountJson,
    [string]$Repo = 'GunGreenEyes/TrekUI.Kotlin'
)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $Root

function Step($text) { Write-Host "`n== $text" -ForegroundColor Cyan }
function Done($text) { Write-Host "   OK  $text" -ForegroundColor Green }

Step 'GitHub CLI'
$login = gh api user -q .login
if ($LASTEXITCODE -ne 0 -or -not $login) { throw "Sign in first: gh auth login" }
$userId = gh api user -q .id
Done "signed in as $login"

Step 'Upload key'
$props = Join-Path $Root 'signing\release.properties'
if (Test-Path $props) { Done 'signing\release.properties already exists (key kept)' }
else { & (Join-Path $PSScriptRoot 'create-upload-key.ps1') | Out-Null; Done 'created signing\trekui-upload.jks' }
$p = @{}
Get-Content $props | Where-Object { $_ -match '^\s*([A-Z_]+)=(.*)$' } | ForEach-Object { $p[$Matches[1]] = $Matches[2].Trim() }
$b64Path = Join-Path $Root 'signing\trekui-upload.jks.base64.txt'
if (-not (Test-Path $b64Path)) {
    [IO.File]::WriteAllText($b64Path, [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $Root $p.STORE_FILE))))
}

Step 'Release secrets'
Get-Content -Raw $b64Path | gh secret set TREKUI_KEYSTORE_BASE64 --repo $Repo
$p.STORE_PASSWORD | gh secret set TREKUI_STORE_PASSWORD --repo $Repo
$p.KEY_ALIAS | gh secret set TREKUI_KEY_ALIAS --repo $Repo
$p.KEY_PASSWORD | gh secret set TREKUI_KEY_PASSWORD --repo $Repo
Done 'TREKUI_KEYSTORE_BASE64, TREKUI_STORE_PASSWORD, TREKUI_KEY_ALIAS, TREKUI_KEY_PASSWORD'
if ($ServiceAccountJson) {
    Get-Content -Raw $ServiceAccountJson | gh secret set PLAY_SERVICE_ACCOUNT_JSON --repo $Repo
    Done 'PLAY_SERVICE_ACCOUNT_JSON'
} elseif ((gh secret list --repo $Repo --json name -q '.[].name') -notcontains 'PLAY_SERVICE_ACCOUNT_JSON') {
    Write-Host '   --  PLAY_SERVICE_ACCOUNT_JSON not set yet: re-run with -ServiceAccountJson <file> after step 3 of the checklist below.' -ForegroundColor Yellow
}

Step 'Production approval gate (google-play environment)'
$body = @{ reviewers = @(@{ type = 'User'; id = [int64]$userId }) } | ConvertTo-Json -Depth 4 -Compress
$body | gh api -X PUT "repos/$Repo/environments/google-play" --input - | Out-Null
Done "production uploads now wait for $login to approve"

Step 'GitHub Pages (built by Actions)'
gh api "repos/$Repo/pages" 2>$null | Out-Null
if ($LASTEXITCODE -eq 0) { gh api -X PUT "repos/$Repo/pages" -f build_type=workflow | Out-Null }
else { gh api -X POST "repos/$Repo/pages" -f build_type=workflow | Out-Null }
Done "https://gungreeneyes.github.io/TrekUI.Kotlin/ (published by the Pages workflow on the next release)"

Step 'Weekly upgrade queue'
& (Join-Path $Root 'scripts\maintenance\register-upgrade-task.ps1')

Step 'Back up now'
Write-Host "   Copy the whole $Root\signing folder and its password to safe offline storage. It is not in git;"
Write-Host '   losing it means asking Google for an upload-key reset.'

Step 'Play Console (only you can do these; answers are in docs/PLAY_STORE.md)'
@'
   1. Developer account (US$25, identity check) and a payments profile (bank + tax) at play.google.com/console.
   2. Run a trademark search for "TrekUI" before the first upload; package names can never change afterwards.
   3. Setup > API access: create a service account, grant it Release + store-listing rights on all four apps,
      download its JSON key, then:  .\scripts\release\setup-owner.ps1 -ServiceAccountJson <that file>
   4. For EACH app (com.gungreeneyes.trekui, .messages, .keyboard, .lockscreen):
        a. Create app: Free, English (US).
        b. First build (once, for all four):  .\scripts\release\ship.ps1 -NoPlay   (check first with -DryRun).
           Download each AAB from the GitHub Release it creates and upload it by hand under
           Testing > Internal testing; accept Play App Signing. Later releases upload automatically.
        c. Monetize > In-app products: trekui_unlock, one-time, US$0.99, Active.
        d. App content: privacy policy URL, ads = No, content rating (no controlled substances), audience 18+,
           data safety, plus Accessibility API (launcher) / SMS permissions (Messages) declarations.
        e. Store listing: icon, feature graphic, screenshots, descriptions (fastlane/metadata/<app>).
   5. Setup > License testing: add tester accounts. Closed test: 12+ testers for 14 days (new personal accounts).
   6. Apply for production access, then  .\scripts\release\ship.ps1 -Track production -Rollout 0.1
'@ | Write-Host
Write-Host "`nSetup finished." -ForegroundColor Green
