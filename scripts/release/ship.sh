#!/usr/bin/env bash
# Ships the whole TrekUI suite (launcher, messages, keyboard, lockscreen) to Google Play in one command.
#
#   scripts/release/ship.sh                       next whole number (e.g. 6 -> 7), internal track
#   scripts/release/ship.sh --track beta
#   scripts/release/ship.sh 9 --track production --rollout 0.1
#   scripts/release/ship.sh --dry-run             print what would happen; writes, commits and pushes nothing
#
# In order:
#   1. bump to the next whole-number version (or the given one, which must be greater) with
#      scripts/release/bump-version.sh: one shared versionCode (always +1) and versionName for every app
#      in version.properties, plus fastlane/release-history.json. Versions are whole numbers (6, 7, 8, ...);
#      versionCode is a separate, strictly increasing integer.
#   2. changelog from the commits since the last v* tag: a new section at the top of CHANGELOG.md and a
#      Play "What's new" text per app in fastlane/metadata/<app>/android/en-US/changelogs/<versionCode>.txt
#      (500 characters at most; each app lists the commits that touched it or :core)
#   3. commit those files only (explicit paths), tag v<versionName>, push main and the tag
#   4. gh workflow run release.yml on the tag with the chosen track, then print the Actions run URL
#
# Options:
#   --track internal|alpha|beta|production   Play track (default internal; production waits for approval
#                                             in the google-play environment)
#   --rollout F        production rollout fraction, 0 < F <= 1 (default 0.1)
#   --status S         completed|draft (default completed; draft until each app's first release is live)
#   --no-play          build and create the GitHub Release only, no Play upload
#   --update-listing   also push every app's store listing
#   --ticket ID        prefix the release commit message with a ticket ID
#   --no-dispatch      stop after the push (used by release.yml's own bump on the runner)
#   --dry-run          print the plan, the notes and the commands; change nothing
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

usage() { sed -n '2,31p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-1}"; }

VERSION="next"; TRACK="internal"; ROLLOUT="0.1"; STATUS="completed"; PLAY="true"; LISTING="false"
TICKET=""; DISPATCH=1; DRY=0; REMOTE="origin"
while [ $# -gt 0 ]; do
  case "$1" in
    next|major|minor|patch|[0-9]*) VERSION="$1"; shift;;
    --track) TRACK="$2"; shift 2;;
    --rollout) ROLLOUT="$2"; shift 2;;
    --status) STATUS="$2"; shift 2;;
    --no-play) PLAY="false"; shift;;
    --update-listing) LISTING="true"; shift;;
    --ticket) TICKET="$2"; shift 2;;
    --no-dispatch) DISPATCH=0; shift;;
    --dry-run|-n) DRY=1; shift;;
    -h|--help) usage 0;;
    *) echo "Unknown argument: $1" >&2; usage;;
  esac
done
case "$TRACK" in internal|alpha|beta|production) ;; *) echo "--track must be internal, alpha, beta or production" >&2; exit 1;; esac
case "$STATUS" in completed|draft) ;; *) echo "--status must be completed or draft" >&2; exit 1;; esac
awk -v f="$ROLLOUT" 'BEGIN { exit !(f > 0 && f <= 1) }' || { echo "--rollout must be a fraction in (0, 1]" >&2; exit 1; }

# app-folder:module (the module whose commits go into that app's Play notes; :core counts for all)
APPS="launcher:app messages:messages keyboard:keyboard lockscreen:lockscreen"

fail() { if [ "$DRY" = 1 ]; then echo "WARNING (a real run would stop here): $*" >&2; else echo "$*" >&2; exit 1; fi; }

# --- preflight -----------------------------------------------------------------------------------------
BRANCH="$(git symbolic-ref --short -q HEAD || echo '(detached)')"
[ "$BRANCH" = "main" ] || fail "Ship from main (current branch: $BRANCH)."
if git fetch -q "$REMOTE" main --tags 2>/dev/null; then
  BEHIND="$(git rev-list --count HEAD.."$REMOTE"/main)"
  [ "$BEHIND" = 0 ] || fail "main is $BEHIND commit(s) behind $REMOTE/main; pull first."
else
  fail "Could not fetch $REMOTE."
fi
git diff --quiet HEAD -- version.properties CHANGELOG.md fastlane/ \
  || fail "version.properties, CHANGELOG.md or fastlane/ has uncommitted changes; commit or stash them first."
if [ "$DISPATCH" = 1 ] && [ "$DRY" = 0 ]; then
  command -v gh >/dev/null || fail "The GitHub CLI (gh) is needed to start the release workflow."
  gh auth status >/dev/null 2>&1 || fail "gh is not signed in (gh auth login)."
fi

# --- 1. version ------------------------------------------------------------------------------------------
prop() { grep "^$1=" version.properties | cut -d= -f2 | tr -d '\r'; }
OLD_NAME="$(prop versionName)"; OLD_CODE="$(prop versionCode)"
# Whole-number versions; an old X.Y.Z name counts as its major (6.2.0 -> 6).
CURRENT="${OLD_NAME%%[!0-9]*}"
[ -n "$CURRENT" ] || { echo "versionName '$OLD_NAME' in version.properties does not start with a number" >&2; exit 1; }
CURRENT=$((10#$CURRENT))
case "$VERSION" in
  next|major) NEW_NAME="$((CURRENT + 1))";;
  minor|patch) echo "There are no minor/patch versions any more: versions are whole numbers. Leave the version out (next: $((CURRENT + 1))) or give a whole number greater than $CURRENT." >&2; exit 1;;
  *[!0-9]*) echo "The version must be a whole number greater than $CURRENT, or left out for the next one (got '$VERSION')." >&2; exit 1;;
  *) if [ "$((10#$VERSION))" -le "$CURRENT" ]; then echo "Version $VERSION must be greater than the current version $CURRENT." >&2; exit 1; fi
     NEW_NAME="$((10#$VERSION))";;
esac
NEW_CODE=$((OLD_CODE + 1))
TAG="v$NEW_NAME"
DATE="$(date +%Y-%m-%d)"
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && fail "Tag $TAG already exists."
if git ls-remote --exit-code --tags "$REMOTE" "refs/tags/$TAG" >/dev/null 2>&1; then fail "Tag $TAG already exists on $REMOTE."; fi

# --- 2. changelog ----------------------------------------------------------------------------------------
LAST_TAG="$(git describe --tags --abbrev=0 --match 'v*' 2>/dev/null || true)"
RANGE="${LAST_TAG:+$LAST_TAG..}HEAD"

# Commit subjects worth telling users about (no merges, release commits or ci/deps/chore/docs/test).
subjects() {
  git log --no-merges --pretty=format:%s "$RANGE" "$@" \
    | grep -viE '^([A-Z]+(\.[A-Z]+)?-T[0-9]+:[[:space:]]*)?((ci|deps|chore|docs|test)[:(]|Release [0-9])' \
    | awk '!seen[$0]++' || true
}

# Play "What's new": ticket IDs stripped, each line cut at a word to ~110 bytes, whole lines only, and at
# most 500 bytes in total (so at most 500 characters, Play's limit).
play_notes() {
  LC_ALL=C awk '
    {
      s = $0
      sub(/^[A-Z]+(\.[A-Z]+)?-T[0-9]+:[ \t]*/, "", s)
      sub(/[ \t]*\(([A-Z]+(\.[A-Z]+)?-T[0-9]+(, *)?)+\)[ \t]*$/, "", s)
      # maintenance work users never see stays in CHANGELOG.md only
      if (s ~ /^(Document|Log|Regenerate|Record|Unit-test|Test|Queue|Sync|Point|Export|Refactor|Rename|Bump|Merge)[ :]/) next
      if (length(s) > 110) { s = substr(s, 1, 110); sub(/[ ,;:]+[^ ]*$/, "", s); s = s "..." }
      line = "\342\200\242 " s
      add = length(line) + (n ? 1 : 0)
      if (total + add > 500) next
      out = out (n ? "\n" : "") line; total += add; n++
    }
    END { if (n) print out; else print "\342\200\242 Fixes and improvements" }'
}

ALL_SUBJECTS="$(subjects)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
printf '%s\n' "$ALL_SUBJECTS" | sed '/^$/d' | play_notes > "$TMP/notes-all.txt"
for entry in $APPS; do
  app="${entry%%:*}"; module="${entry#*:}"
  subjects -- "$module/" core/ | sed '/^$/d' | play_notes > "$TMP/notes-$app.txt"
done

{
  echo "## $NEW_NAME (versionCode $NEW_CODE, $DATE)"
  echo
  if [ -n "$ALL_SUBJECTS" ]; then printf '%s\n' "$ALL_SUBJECTS" | sed '/^$/d' | head -n 40 | sed 's/^/- /'
  else echo "- Fixes and improvements"; fi
  echo
} > "$TMP/changelog-section.md"

echo "TrekUI suite $OLD_NAME ($OLD_CODE) -> $NEW_NAME ($NEW_CODE), tag $TAG"
echo "Commits since ${LAST_TAG:-the first commit}: $(printf '%s' "$ALL_SUBJECTS" | grep -c . || true) user-facing"
echo
for entry in $APPS; do
  app="${entry%%:*}"
  echo "--- fastlane/metadata/$app/android/en-US/changelogs/$NEW_CODE.txt ($(LC_ALL=C wc -c < "$TMP/notes-$app.txt" | tr -d ' ') bytes)"
  cat "$TMP/notes-$app.txt"
done
echo
echo "--- CHANGELOG.md (new section at the top)"
cat "$TMP/changelog-section.md"

MSG="Release $NEW_NAME ($NEW_CODE)"
[ -n "$TICKET" ] && MSG="$TICKET: $MSG"
DISPATCH_ARGS=(-f track="$TRACK" -f rollout="$ROLLOUT" -f status="$STATUS" -f upload-to-play="$PLAY" -f update-listing="$LISTING" -f bump=none)

if [ "$DRY" = 1 ]; then
  echo "--- Dry run: nothing written, committed, tagged or pushed. A real run would:"
  echo "  scripts/release/bump-version.sh $NEW_NAME --notes-file <notes> --no-commit"
  echo "  write the four per-app changelogs above and prepend CHANGELOG.md"
  echo "  git commit -m \"$MSG\" -- version.properties CHANGELOG.md fastlane/release-history.json fastlane/metadata/*/android/en-US/changelogs/$NEW_CODE.txt"
  echo "  git tag -a $TAG"
  echo "  git push --atomic $REMOTE HEAD:refs/heads/main refs/tags/$TAG"
  if [ "$DISPATCH" = 1 ]; then echo "  gh workflow run release.yml --ref $TAG ${DISPATCH_ARGS[*]}"; fi
  exit 0
fi

# --- write -------------------------------------------------------------------------------------------------
bash scripts/release/bump-version.sh "$NEW_NAME" --notes-file "$TMP/notes-all.txt" --no-commit >/dev/null
[ "$(prop versionCode)" = "$NEW_CODE" ] || { echo "bump-version.sh wrote an unexpected versionCode" >&2; exit 1; }
FILES=(version.properties CHANGELOG.md fastlane/release-history.json)
for entry in $APPS; do
  app="${entry%%:*}"
  f="fastlane/metadata/$app/android/en-US/changelogs/$NEW_CODE.txt"
  mkdir -p "$(dirname "$f")"
  cp "$TMP/notes-$app.txt" "$f"
  FILES+=("$f")
done
for f in fastlane/metadata/*/android/en-US/changelogs/"$NEW_CODE".txt; do
  case " ${FILES[*]} " in *" $f "*) ;; *) FILES+=("$f");; esac
done
awk -v sec="$TMP/changelog-section.md" '
  !done && /^## / { while ((getline l < sec) > 0) print l; done = 1 }
  { print }
  END { if (!done) while ((getline l < sec) > 0) print l }' CHANGELOG.md > "$TMP/CHANGELOG.md"
cp "$TMP/CHANGELOG.md" CHANGELOG.md

# --- 3. commit, tag, push ----------------------------------------------------------------------------
git add -- "${FILES[@]}"
git commit -q -m "$MSG" -- "${FILES[@]}"
git tag -a "$TAG" -m "TrekUI $NEW_NAME ($NEW_CODE)" -m "$(cat "$TMP/notes-all.txt")"
git push -q --atomic "$REMOTE" HEAD:refs/heads/main "refs/tags/$TAG"
echo "Committed, tagged and pushed $TAG ($(git rev-parse --short HEAD))."

[ "$DISPATCH" = 1 ] || exit 0

# --- 4. start the release workflow -------------------------------------------------------------------
gh workflow run release.yml --ref "$TAG" "${DISPATCH_ARGS[@]}"
URL=""
for _ in $(seq 1 15); do
  sleep 2
  URL="$(gh run list --workflow release.yml --branch "$TAG" --event workflow_dispatch --limit 1 --json url --jq '.[0].url' 2>/dev/null || true)"
  [ -n "$URL" ] && break
done
if [ -z "$URL" ]; then
  REPO="$(gh repo view --json nameWithOwner --jq .nameWithOwner 2>/dev/null || echo GunGreenEyes/TrekUI.Java)"
  URL="https://github.com/$REPO/actions/workflows/release.yml"
fi
echo "Release $NEW_NAME started on the $TRACK track: $URL"
