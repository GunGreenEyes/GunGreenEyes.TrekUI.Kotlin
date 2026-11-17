#!/usr/bin/env bash
# Creates the TrekUI test-emulator matrix (AVDs trekui_<id>_api<api>) from scripts/avd/matrix.csv.
# Bash twin of create-avds.ps1 (Linux/macOS CI, Git Bash). See that file for the full description.
#
# Usage: scripts/avd/create-avds.sh [--only REGEX] [--force] [--delete] [--install]
# Env:   ANDROID_SDK_ROOT (default D:/Android/Sdk), TREKUI_EXTRA_SDK_ROOTS (colon-separated extra
#        SDK roots holding system images, default R:/Android/sdk-extra), ANDROID_AVD_HOME.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-D:/Android/Sdk}}"
EXTRA="${TREKUI_EXTRA_SDK_ROOTS:-R:/Android/sdk-extra}"
AVD_HOME="${ANDROID_AVD_HOME:-${ANDROID_USER_HOME:-$HOME/.android}/avd}"
ONLY=""; FORCE=0; DELETE=0; INSTALL=0
while [ $# -gt 0 ]; do
  case "$1" in
    --only) ONLY="$2"; shift 2 ;;
    --force) FORCE=1; shift ;;
    --delete) DELETE=1; shift ;;
    --install) INSTALL=1; shift ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
bin="$SDK/cmdline-tools/latest/bin"
avdmanager="$bin/avdmanager"; sdkmanager="$bin/sdkmanager"
[ -x "$avdmanager" ] || avdmanager="$bin/avdmanager.bat"
[ -x "$sdkmanager" ] || sdkmanager="$bin/sdkmanager.bat"

if [ "$DELETE" = 1 ]; then
  for ini in "$AVD_HOME"/trekui_*.ini; do
    [ -e "$ini" ] || continue
    n="$(basename "$ini" .ini)"; echo "deleting $n"
    rm -rf "$AVD_HOME/$n.avd" "$ini"
  done
  exit 0
fi

# set_ini FILE KEY VALUE: replace or append key=value
set_ini() {
  local f="$1" k="$2" v="$3"
  if grep -q "^${k}[[:space:]]*=" "$f" 2>/dev/null; then
    local esc; esc="$(printf '%s' "$v" | sed 's/[&|\\]/\\&/g')"
    sed -i "s|^${k}[[:space:]]*=.*|${k}=${esc}|" "$f"
  else
    printf '%s=%s\n' "$k" "$v" >> "$f"
  fi
}

find_root() { # rel -> first root containing it
  local rel="$1" r
  for r in "$SDK" ${EXTRA//:/ }; do
    [ -f "$r/$rel/system.img" ] && { echo "$r"; return; }
  done
}

declare -A seen=()
tail -n +2 "$here/matrix.csv" | while IFS=, read -r id profile api tag width height density orientation form note; do
  [ -n "$id" ] || continue
  name="trekui_${id}_api${api}"
  [ -z "${seen[$name]:-}" ] || continue; seen[$name]=1
  if [ -n "$ONLY" ] && ! [[ "$name" =~ $ONLY ]]; then continue; fi
  pkg="system-images;android-${api};${tag};x86_64"
  rel="system-images/android-${api}/${tag}/x86_64"
  root="$(find_root "$rel")"
  if [ -z "$root" ] && [ "$INSTALL" = 1 ]; then
    yes | "$sdkmanager" "--sdk_root=$SDK" "$pkg" >/dev/null; root="$(find_root "$rel")"
  fi
  if [ -z "$root" ]; then echo "skip $name: $pkg not installed (--install)"; continue; fi
  dir="$AVD_HOME/$name.avd"
  if [ -d "$dir" ] && [ "$FORCE" = 0 ]; then echo "exists  $name"; continue; fi
  create_pkg="$pkg"
  if [ "$root" != "$SDK" ]; then
    # avdmanager only accepts images in its own SDK: use a stand-in and re-point below.
    create_pkg="$(cd "$SDK/system-images" && ls -d android-*/*/x86_64 | grep -v tablet | sort -t- -k2 -V -r | head -1 | sed 's|/|;|g; s|^|system-images;|')"
  fi
  echo "create  $name ($profile, $pkg from $root)"
  echo no | "$avdmanager" --silent create avd --force --name "$name" --package "$create_pkg" --device "$profile" --sdcard 256M || true
  cfg="$dir/config.ini"
  [ -f "$cfg" ] || { echo "avdmanager failed for $name" >&2; continue; }
  for kv in hw.ramSize=2048 vm.heapSize=256 disk.dataPartition.size=4G hw.cpu.ncore=4 hw.keyboard=yes \
            hw.gpu.enabled=yes fastboot.forceColdBoot=yes fastboot.forceFastBoot=no showDeviceFrame=no; do
    set_ini "$cfg" "${kv%%=*}" "${kv#*=}"
  done
  set_ini "$cfg" avd.ini.displayname "TrekUI $id API $api"
  if [ -n "$width" ] && [ -n "$height" ] && [ -n "$density" ]; then
    set_ini "$cfg" hw.lcd.width "$width"; set_ini "$cfg" hw.lcd.height "$height"; set_ini "$cfg" hw.lcd.density "$density"
    set_ini "$cfg" skin.name "${width}x${height}"; set_ini "$cfg" skin.dynamic yes; set_ini "$cfg" skin.path _no_skin
  fi
  if [ "$root" != "$SDK" ]; then
    set_ini "$cfg" image.sysdir.1 "$root/$rel/"
    set_ini "$cfg" tag.id "$tag"; set_ini "$cfg" tag.ids "$tag"; set_ini "$cfg" target "android-$api"
    set_ini "$AVD_HOME/$name.ini" target "android-$api"
  fi
done
echo "AVDs in $AVD_HOME:"; ls "$AVD_HOME" | grep '^trekui_.*\.ini$' | sed 's/\.ini$//; s/^/  /'
