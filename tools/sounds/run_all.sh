#!/usr/bin/env bash
# Rebuilds core/src/main/assets/sounds/manifest.json from scratch:
#   1. crawl.py          download every audio file linked from the public index pages (resumable)
#   2. match.py          find each app sound in those files by spectral matching (offset, gain, confidence)
#   3. build_manifest.py write the app asset and re-render every recipe to verify it
#
# Usage: tools/sounds/run_all.sh <work dir outside the repo> [reference clip dir]
# The reference dir holds the processed 5.2 clips (<id>.ogg). It is the owner's local, gitignored
# copy; without it only the crawl runs. Downloads are untrusted data: everything runs with python -I,
# and the work dir must not be inside tools/sounds.
# Needs: Python 3.9+ with numpy, ffmpeg + ffprobe on PATH.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
work="${1:?work dir}"
ref="${2:-$repo/_sources/TREKUI_Launcher_5.2_Complete/2_Android_Source/TREKUI_Launcher/personal_sounds/res/raw}"
mkdir -p "$work/crawl" "$work/match"

python -I "$here/crawl.py" "$work/crawl"
if [ ! -d "$ref" ]; then
  echo "no reference clips at $ref: crawl done, skipping match/manifest" >&2
  exit 0
fi
python -I "$here/match.py" --ref "$ref" --crawl "$work/crawl" --src "$repo/core/src/main/kotlin/com/gungreeneyes/trekui" --src "$repo/app/src/main/kotlin/com/gungreeneyes/trekui" \
  --history "$repo/_sources/history-docs/SOUND_MANIFEST-5.x.md" --out "$work/match"
python -I "$here/build_manifest.py" --report "$work/match/match_report.json" --src "$repo/core/src/main/kotlin/com/gungreeneyes/trekui" --src "$repo/app/src/main/kotlin/com/gungreeneyes/trekui" \
  --crawl "$work/crawl" --ref "$ref" --out "$repo/core/src/main/assets/sounds/manifest.json" --verify
