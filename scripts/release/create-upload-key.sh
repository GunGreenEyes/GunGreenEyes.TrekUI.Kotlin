#!/usr/bin/env bash
# Creates the Google Play UPLOAD key (Play App Signing keeps the real app-signing key) and prints the
# exact `gh secret set` commands that give .github/workflows/release.yml access to it.
#
#   scripts/release/create-upload-key.sh
#
# Writes (all gitignored under signing/):
#   signing/trekui-upload.jks              PKCS12 keystore, alias trekui-upload, RSA 4096, valid ~27 years
#   signing/trekui-upload.jks.base64.txt   the keystore base64-encoded (value of TREKUI_KEYSTORE_BASE64)
#   signing/trekui-upload-cert.pem         the upload certificate (Play Console asks for it on a key reset)
#   signing/release.properties            STORE_FILE/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD for local
#                                         `gradlew bundleProductionRelease` (read by app/build.gradle.kts)
# Refuses to overwrite an existing keystore. BACK UP signing/ somewhere safe (password manager): if the
# upload key is lost, Play support must reset it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
REPO="GunGreenEyes/GunGreenEyes.TrekUI.Java"
ALIAS="trekui-upload"
STORE="signing/trekui-upload.jks"

KEYTOOL="keytool"
if [ -n "${JAVA_HOME:-}" ] && { [ -x "$JAVA_HOME/bin/keytool" ] || [ -x "$JAVA_HOME/bin/keytool.exe" ]; }; then
  KEYTOOL="$JAVA_HOME/bin/keytool"
fi
command -v "$KEYTOOL" >/dev/null 2>&1 || { echo "keytool not found: set JAVA_HOME to a JDK (17+)." >&2; exit 1; }

mkdir -p signing
if [ -e "$STORE" ] || [ -e signing/release.properties ]; then
  echo "$STORE or signing/release.properties already exists; not overwriting. Move them away first." >&2
  exit 1
fi

# PKCS12 uses one password for store and key.
PASSWORD="$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)"

"$KEYTOOL" -genkeypair -v \
  -keystore "$STORE" -storetype PKCS12 \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASSWORD" -keypass "$PASSWORD" \
  -dname "CN=TrekUI Launcher Upload, O=TrekUI Launcher" >/dev/null

"$KEYTOOL" -exportcert -rfc -keystore "$STORE" -alias "$ALIAS" -storepass "$PASSWORD" > signing/trekui-upload-cert.pem
base64 < "$STORE" | tr -d '\r\n' > signing/trekui-upload.jks.base64.txt

cat > signing/release.properties <<EOF
# Local release signing (gitignored). CI uses the TREKUI_* GitHub secrets instead.
STORE_FILE=$STORE
STORE_PASSWORD=$PASSWORD
KEY_ALIAS=$ALIAS
KEY_PASSWORD=$PASSWORD
EOF

echo
echo "Upload key created: $STORE (alias $ALIAS)"
"$KEYTOOL" -list -v -keystore "$STORE" -storepass "$PASSWORD" -alias "$ALIAS" | grep -E 'SHA1:|SHA256:' || true
echo
echo "TREKUI_KEYSTORE_BASE64 (also in signing/trekui-upload.jks.base64.txt):"
cat signing/trekui-upload.jks.base64.txt; echo
echo
echo "Run these from the repository root (needs the GitHub CLI, logged in with 'gh auth login'):"
echo
echo "gh secret set TREKUI_KEYSTORE_BASE64 --repo $REPO < signing/trekui-upload.jks.base64.txt"
echo "gh secret set TREKUI_STORE_PASSWORD  --repo $REPO --body '$PASSWORD'"
echo "gh secret set TREKUI_KEY_ALIAS       --repo $REPO --body '$ALIAS'"
echo "gh secret set TREKUI_KEY_PASSWORD    --repo $REPO --body '$PASSWORD'"
echo "gh secret set PLAY_SERVICE_ACCOUNT_JSON --repo $REPO < path/to/play-service-account.json"
echo
echo "Back up the whole signing/ folder and the password now; they are not in git."
