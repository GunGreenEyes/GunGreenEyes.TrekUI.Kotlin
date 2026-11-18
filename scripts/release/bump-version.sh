#!/usr/bin/env bash
# Bumps the app version, writes the Play changelog and creates the release tag (does NOT push).
#
#   scripts/release/bump-version.sh                             next whole number, e.g. 6 -> 7
#   scripts/release/bump-version.sh next --notes "New Collective layout"
#   scripts/release/bump-version.sh 9 --notes-file notes.txt --ticket LCA-T12
#
# Versions are whole numbers (6, 7, 8, ...); versionCode is a separate, strictly increasing integer.
# The argument is 'next' (the default) or a whole number greater than the current one; an old X.Y.Z
# versionName counts as its major (6.2.0 -> next is 7). 'major' is still accepted as an alias of 'next'
# for one transition release; 'minor' and 'patch' are rejected.
#
# What it does:
#   1. version.properties: versionCode + 1, versionName = the new version
#   2. fastlane/metadata/<app>/android/en-US/changelogs/<versionCode>.txt for every app (launcher, messages,
#      keyboard, lockscreen): the release notes (max 500 chars, Play's limit). Without --notes/--notes-file
#      they are built from commit subjects since the last tag. scripts/release/ship.* then writes per-app notes.
#   3. fastlane/release-history.json: adds {versionCode, versionName, date} (the site's changelog page)
#   4. commits those three files and creates the annotated tag v<versionName>
# Then push and start the release with scripts/release/ship.sh (which runs this script for you).
#
# Options: --notes TEXT | --notes-file FILE, --ticket ID (commit message prefix), --no-commit (files only)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

usage() { sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-1}"; }

BUMP="next"
case "${1:-}" in
  -h|--help) usage 0;;
  ""|--*) ;;
  *) BUMP="$1"; shift;;
esac
NOTES=""; NOTES_FILE=""; TICKET=""; COMMIT=1
while [ $# -gt 0 ]; do
  case "$1" in
    --notes) NOTES="$2"; shift 2;;
    --notes-file) NOTES_FILE="$2"; shift 2;;
    --ticket) TICKET="$2"; shift 2;;
    --no-commit) COMMIT=0; shift;;
    -h|--help) usage 0;;
    *) echo "Unknown option: $1" >&2; usage;;
  esac
done

prop() { grep "^$1=" version.properties | cut -d= -f2 | tr -d '\r'; }
OLD_NAME="$(prop versionName)"; OLD_CODE="$(prop versionCode)"
# Whole-number versions; an old X.Y.Z name counts as its major (6.2.0 -> 6).
CURRENT="${OLD_NAME%%[!0-9]*}"
[ -n "$CURRENT" ] || { echo "versionName '$OLD_NAME' in version.properties does not start with a number" >&2; exit 1; }
CURRENT=$((10#$CURRENT))
case "$BUMP" in
  next|major) NEW_NAME="$((CURRENT + 1))";;
  minor|patch) echo "There are no minor/patch versions any more: versions are whole numbers. Use 'next' (or no argument) for $((CURRENT + 1)), or a whole number greater than $CURRENT." >&2; exit 1;;
  *[!0-9]*) echo "First argument must be 'next' or a whole number greater than $CURRENT (got '$BUMP')." >&2; exit 1;;
  *) if [ "$((10#$BUMP))" -le "$CURRENT" ]; then echo "Version $BUMP must be greater than the current version $CURRENT." >&2; exit 1; fi
     NEW_NAME="$((10#$BUMP))";;
esac
NEW_CODE=$((OLD_CODE + 1))
TAG="v$NEW_NAME"

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "Tag $TAG already exists." >&2; exit 1
fi
if [ "$COMMIT" = 1 ] && ! git diff --quiet -- version.properties fastlane/; then
  echo "version.properties or fastlane/ has uncommitted changes; commit or stash them first." >&2; exit 1
fi

# Release notes
if [ -n "$NOTES_FILE" ]; then
  NOTES="$(cat "$NOTES_FILE")"
elif [ -z "$NOTES" ]; then
  LAST_TAG="$(git describe --tags --abbrev=0 --match 'v*' 2>/dev/null || true)"
  RANGE="${LAST_TAG:+$LAST_TAG..}HEAD"
  NOTES="$(git log --no-merges --pretty=format:%s "$RANGE" \
    | sed -E 's/^[A-Z]+(\.[A-Z]+)?-T[0-9]+:[[:space:]]*//' \
    | grep -viE '^(ci|deps|chore|docs|test)[:(]|^Release [0-9]' | awk '!seen[$0]++' | head -n 12 | sed 's/^/• /')"
  [ -n "$NOTES" ] || NOTES="• Fixes and improvements"
fi
NOTES="$(printf '%s' "$NOTES" | head -c 500)"

# 1. version.properties (keeps any other lines)
sed -i.bak -E "s/^versionCode=.*/versionCode=$NEW_CODE/; s/^versionName=.*/versionName=$NEW_NAME/" version.properties
rm -f version.properties.bak

# 2. changelog, one per app's metadata folder (all apps share one versionCode)
CHANGELOGS=()
for meta in fastlane/metadata/*/android/en-US; do
  [ -d "$meta" ] || continue
  mkdir -p "$meta/changelogs"
  printf '%s\n' "$NOTES" > "$meta/changelogs/$NEW_CODE.txt"
  CHANGELOGS+=("$meta/changelogs/$NEW_CODE.txt")
done
[ ${#CHANGELOGS[@]} -gt 0 ] || { echo "No fastlane/metadata/<app>/android/en-US folders found." >&2; exit 1; }
CHANGELOG="${CHANGELOGS[0]}"

# 3. release history (node is already needed for the site build)
HISTORY="fastlane/release-history.json"
node -e '
  const fs = require("fs"); const [file, code, name] = process.argv.slice(1);
  let h = []; try { h = JSON.parse(fs.readFileSync(file, "utf8")); } catch {}
  h = h.filter((e) => String(e.versionCode) !== code);
  h.unshift({ versionCode: Number(code), versionName: name, date: new Date().toISOString().slice(0, 10) });
  fs.writeFileSync(file, JSON.stringify(h, null, 2) + "\n");
' "$HISTORY" "$NEW_CODE" "$NEW_NAME"

echo "Version $OLD_NAME ($OLD_CODE) -> $NEW_NAME ($NEW_CODE)"
echo "Release notes (${CHANGELOGS[*]}):"; cat "$CHANGELOG"

if [ "$COMMIT" = 1 ]; then
  MSG="Release $NEW_NAME ($NEW_CODE)"
  [ -n "$TICKET" ] && MSG="$TICKET: $MSG"
  git add version.properties "${CHANGELOGS[@]}" "$HISTORY"
  git commit -q -m "$MSG" -- version.properties "${CHANGELOGS[@]}" "$HISTORY"
  git tag -a "$TAG" -m "TrekUI $NEW_NAME ($NEW_CODE)" -m "$NOTES"
  echo
  echo "Committed and tagged $TAG (not pushed). To publish the release run:"
  echo "  git push origin main $TAG && gh workflow run release.yml --ref $TAG"
  echo "(scripts/release/ship.sh does all of this in one step.)"
fi
