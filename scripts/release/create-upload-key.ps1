<#
.SYNOPSIS
  Creates the Google Play UPLOAD key and prints the exact `gh secret set` commands for the release workflow.

.DESCRIPTION
  Play App Signing keeps the real app-signing key; this key only signs what CI uploads.
  Writes (all gitignored under signing\):
    signing\trekui-upload.jks             PKCS12 keystore, alias trekui-upload, RSA 4096, valid ~27 years
    signing\trekui-upload.jks.base64.txt  the keystore base64-encoded (value of TREKUI_KEYSTORE_BASE64)
    signing\trekui-upload-cert.pem        the upload certificate (Play Console asks for it on a key reset)
    signing\release.properties           STORE_FILE/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD for local release
                                         builds (read by app/build.gradle.kts)
  Refuses to overwrite an existing keystore. Back up signing\ and the password (password manager).

.EXAMPLE
  cd <your clone of the repository>
  .\scripts\release\create-upload-key.ps1
#>
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $Root
$Repo = "GunGreenEyes/TrekUI.Kotlin"
$Alias = "trekui-upload"
$StoreRel = "signing/trekui-upload.jks"
$Store = Join-Path $Root $StoreRel
$utf8 = New-Object System.Text.UTF8Encoding($false)

$keytool = "keytool"
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\keytool.exe"))) { $keytool = Join-Path $env:JAVA_HOME "bin\keytool.exe" }
if (-not (Get-Command $keytool -ErrorAction SilentlyContinue)) { throw "keytool not found: set JAVA_HOME to a JDK (17+)." }

New-Item -ItemType Directory -Force (Join-Path $Root "signing") | Out-Null
if ((Test-Path $Store) -or (Test-Path (Join-Path $Root "signing\release.properties"))) {
  throw "$StoreRel or signing\release.properties already exists; not overwriting. Move them away first."
}

# PKCS12 uses one password for store and key.
$chars = [char[]]'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789'
$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$Password = -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })

& $keytool -genkeypair -keystore $Store -storetype PKCS12 -alias $Alias -keyalg RSA -keysize 4096 -validity 10000 `
  -storepass $Password -keypass $Password -dname "CN=TrekUI Launcher Upload, O=TrekUI Launcher" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "keytool -genkeypair failed" }

$pem = & $keytool -exportcert -rfc -keystore $Store -alias $Alias -storepass $Password
[System.IO.File]::WriteAllText((Join-Path $Root "signing\trekui-upload-cert.pem"), (($pem -join "`n") + "`n"), $utf8)
$b64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($Store))
[System.IO.File]::WriteAllText((Join-Path $Root "signing\trekui-upload.jks.base64.txt"), $b64, $utf8)

$propsText = @"
# Local release signing (gitignored). CI uses the TREKUI_* GitHub secrets instead.
STORE_FILE=$StoreRel
STORE_PASSWORD=$Password
KEY_ALIAS=$Alias
KEY_PASSWORD=$Password
"@
[System.IO.File]::WriteAllText((Join-Path $Root "signing\release.properties"), ($propsText -replace "`r`n", "`n") + "`n", $utf8)

Write-Host ""
Write-Host "Upload key created: $StoreRel (alias $Alias)"
& $keytool -list -v -keystore $Store -storepass $Password -alias $Alias | Select-String -Pattern 'SHA1:|SHA256:'
Write-Host ""
Write-Host "TREKUI_KEYSTORE_BASE64 (also in signing\trekui-upload.jks.base64.txt):"
Write-Host $b64
Write-Host ""
Write-Host "Run these in PowerShell from $Root (needs the GitHub CLI, logged in with 'gh auth login'):"
Write-Host ""
Write-Host "cd $Root"
Write-Host "Get-Content signing\trekui-upload.jks.base64.txt -Raw | gh secret set TREKUI_KEYSTORE_BASE64 --repo $Repo"
Write-Host "gh secret set TREKUI_STORE_PASSWORD --repo $Repo --body '$Password'"
Write-Host "gh secret set TREKUI_KEY_ALIAS --repo $Repo --body '$Alias'"
Write-Host "gh secret set TREKUI_KEY_PASSWORD --repo $Repo --body '$Password'"
Write-Host "Get-Content C:\path\to\play-service-account.json -Raw | gh secret set PLAY_SERVICE_ACCOUNT_JSON --repo $Repo"
Write-Host ""
Write-Host "Back up the whole signing\ folder and the password now; they are not in git."
