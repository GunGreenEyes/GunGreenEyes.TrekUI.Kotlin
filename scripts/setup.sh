#!/usr/bin/env bash
# Prepares macOS, Linux or Termux (Android) to build TrekUI Launcher from a fresh clone.
#
# Idempotent: anything already installed is detected and left alone, so it is safe to re-run.
#   1. Tools: JDK 21, Git, GitHub CLI, Python 3, Node.js, ffmpeg
#      (Homebrew on macOS, apt or dnf on Linux, pkg on Termux).
#   2. Android SDK: downloads the command-line tools if no SDK exists, accepts the licences and
#      installs platform-tools, emulator, platforms;android-37.0, build-tools;37.0.0
#      (+ emulator system images with --emulators).
#   3. Writes local.properties (sdk.dir) and prints the ANDROID_HOME line for your shell profile.
#   4. Checks emulator acceleration (KVM on Linux, Hypervisor.framework on macOS).
#   5. Smoke build: ./gradlew assembleDevelopDebug
#
# Termux (building on the phone itself, how the original developer built it):
#   - Tools come from Termux packages; adb comes from android-tools, aapt2 from the aapt package.
#   - The SDK's build-tools are x86_64 Linux binaries that cannot run on an ARM phone, so Gradle is
#     pointed at Termux's own aapt2 through ~/.gradle/gradle.properties
#     (android.aapt2FromMavenOverride). That is a per-machine setting and never goes in the repo.
#   - Emulators do not run on Termux. Install the APK on the same phone with termux-open or adb.
#
# Usage: scripts/setup.sh [--dry-run] [--emulators] [--skip-build] [--sdk-root DIR]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DRY_RUN=0
EMULATORS=0
SKIP_BUILD=0
SDK_ROOT_ARG=""

# Keep in step with compileSdk/targetSdk in app/build.gradle.kts.
SDK_PACKAGES=("platforms;android-37.0" "build-tools;37.0.0")
HOST_ONLY_PACKAGES=("platform-tools" "emulator")   # not usable on Termux (x86_64 binaries)
API_LEVELS_PHONE=(26 27 28 29 30 31 32 33 34 35 36)
API_LEVELS_TABLET=(35 36)

usage() { sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run|-n) DRY_RUN=1 ;;
    --emulators) EMULATORS=1 ;;
    --skip-build) SKIP_BUILD=1 ;;
    --sdk-root) SDK_ROOT_ARG="${2:?--sdk-root needs a directory}"; shift ;;
    --sdk-root=*) SDK_ROOT_ARG="${1#*=}" ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 2 ;;
  esac
  shift
done

PROBLEMS=()
section() { printf '\n== %s\n' "$1"; }
ok()      { printf '  [ok]   %s\n' "$1"; }
todo()    { printf '  [do]   %s\n' "$1"; }
warn()    { printf '  [warn] %s\n' "$1"; PROBLEMS+=("$1"); }
have()    { command -v "$1" >/dev/null 2>&1; }

# run "description" cmd args... : prints the action; executes it unless --dry-run.
run() {
  local desc="$1"; shift
  if [ "$DRY_RUN" -eq 1 ]; then printf '  [dry]  %s\n' "$desc"; return 0; fi
  todo "$desc"
  "$@"
}

# ---------------------------------------------------------------------------------------------
# Platform detection
if [ -n "${TERMUX_VERSION:-}" ] || [[ "${PREFIX:-}" == *com.termux* ]]; then
  PLATFORM=termux
elif [ "$(uname -s)" = "Darwin" ]; then
  PLATFORM=macos
elif have apt-get; then
  PLATFORM=apt
elif have dnf; then
  PLATFORM=dnf
else
  PLATFORM=unknown
fi
case "$(uname -m)" in
  arm64|aarch64) ABI=arm64-v8a ;;
  *) ABI=x86_64 ;;
esac
SUDO=""
if [ "$PLATFORM" = apt ] || [ "$PLATFORM" = dnf ]; then
  [ "$(id -u)" -ne 0 ] && SUDO="sudo"
fi

echo "TrekUI Launcher setup ($PLATFORM, $ABI)  repo: $REPO_ROOT"
[ "$DRY_RUN" -eq 1 ] && echo "Dry run (--dry-run): nothing will be changed."

# ---------------------------------------------------------------------------------------------
section "Tools"

java_major() {
  local java_bin="${1:-java}"
  "$java_bin" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1
}

find_jdk21() {
  local c
  for c in "${JAVA_HOME:-}" \
           "${PREFIX:-/nonexistent}/lib/jvm/java-21-openjdk" \
           /usr/lib/jvm/java-21-openjdk* /usr/lib/jvm/temurin-21* /usr/lib/jvm/java-21* \
           /Library/Java/JavaVirtualMachines/temurin-21*.jdk/Contents/Home \
           /opt/homebrew/opt/openjdk@21 /usr/local/opt/openjdk@21; do
    if [ -n "$c" ] && [ -x "$c/bin/java" ] && [ "$(java_major "$c/bin/java")" = "21" ]; then
      echo "$c"; return 0
    fi
  done
  if [ "$PLATFORM" = macos ] && [ -x /usr/libexec/java_home ]; then
    /usr/libexec/java_home -v 21 2>/dev/null && return 0
  fi
  if have java && [ "$(java_major java)" = "21" ]; then
    dirname "$(dirname "$(readlink -f "$(command -v java)" 2>/dev/null || command -v java)")"
    return 0
  fi
  return 1
}

# install_pkgs <package names for the current package manager...>
install_pkgs() {
  case "$PLATFORM" in
    termux) run "pkg install -y $*" pkg install -y "$@" ;;
    macos)
      if ! have brew; then warn "Homebrew is missing (https://brew.sh); cannot install: $*"; return 0; fi
      run "brew install $*" brew install "$@" ;;
    apt) run "apt-get install -y $*" $SUDO apt-get install -y "$@" ;;
    dnf) run "dnf install -y $*" $SUDO dnf install -y "$@" ;;
    *) warn "Unknown package manager; install by hand: $*" ;;
  esac
}

if [ "$PLATFORM" = apt ] && [ "$DRY_RUN" -eq 0 ]; then $SUDO apt-get update -qq || true; fi
if [ "$PLATFORM" = termux ] && [ "$DRY_RUN" -eq 0 ]; then pkg update -y || true; fi

JDK_HOME="$(find_jdk21 || true)"
if [ -n "$JDK_HOME" ]; then ok "JDK 21: $JDK_HOME"; else
  case "$PLATFORM" in
    termux) install_pkgs openjdk-21 ;;
    macos)  run "brew install --cask temurin@21" brew install --cask temurin@21 ;;
    apt)    install_pkgs openjdk-21-jdk ;;
    dnf)    install_pkgs java-21-openjdk-devel ;;
    *)      warn "Install a JDK 21 by hand" ;;
  esac
  JDK_HOME="$(find_jdk21 || true)"
fi
if [ -n "$JDK_HOME" ]; then export JAVA_HOME="$JDK_HOME"; elif [ "$DRY_RUN" -eq 0 ]; then warn "JDK 21 not found; Gradle needs it"; fi

# tool  command  termux-pkg  brew-pkg  apt-pkg  dnf-pkg
TOOLS=(
  "Git|git|git|git|git|git"
  "GitHub CLI|gh|gh|gh|gh|gh"
  "Python 3|python3|python|python|python3|python3"
  "Node.js|node|nodejs-lts|node|nodejs|nodejs"
  "ffmpeg|ffmpeg|ffmpeg|ffmpeg|ffmpeg|ffmpeg-free"
  "unzip|unzip|unzip|unzip|unzip|unzip"
  "curl|curl|curl|curl|curl|curl"
)
if [ "$PLATFORM" = termux ]; then
  TOOLS+=("adb (android-tools)|adb|android-tools|-|-|-" "aapt2|aapt2|aapt2|-|-|-" "aapt|aapt|aapt|-|-|-")
fi
for row in "${TOOLS[@]}"; do
  IFS='|' read -r label cmd p_termux p_brew p_apt p_dnf <<<"$row"
  if have "$cmd"; then ok "$label: $(command -v "$cmd")"; continue; fi
  case "$PLATFORM" in
    termux) pkg_name="$p_termux" ;; macos) pkg_name="$p_brew" ;;
    apt) pkg_name="$p_apt" ;; dnf) pkg_name="$p_dnf" ;; *) pkg_name="" ;;
  esac
  if [ -z "$pkg_name" ] || [ "$pkg_name" = "-" ]; then warn "$label missing; install it by hand"; continue; fi
  install_pkgs "$pkg_name" || warn "$label could not be installed ($pkg_name); on older distros gh needs https://cli.github.com/packages"
done

# ---------------------------------------------------------------------------------------------
section "Art tools (pictogram renderer)"
# tools/art/render.js needs @resvg/resvg-js; npm installs it into tools/art/node_modules (gitignored).
if [ -d "$REPO_ROOT/tools/art/node_modules/@resvg/resvg-js" ]; then ok "@resvg/resvg-js present"
elif have npm; then run "npm install --prefix tools/art" npm install --prefix "$REPO_ROOT/tools/art" --no-audit --no-fund || warn "npm install in tools/art failed (only needed to re-render pictograms)"
else warn "npm missing: install Node.js, then run npm install --prefix tools/art (only needed to re-render pictograms)"; fi

# ---------------------------------------------------------------------------------------------
section "Android SDK"

resolve_sdk_root() {
  if [ -n "$SDK_ROOT_ARG" ]; then echo "$SDK_ROOT_ARG"; return; fi
  if [ -n "${ANDROID_HOME:-}" ]; then echo "$ANDROID_HOME"; return; fi
  if [ -n "${ANDROID_SDK_ROOT:-}" ]; then echo "$ANDROID_SDK_ROOT"; return; fi
  if [ -f "$REPO_ROOT/local.properties" ]; then
    local d; d="$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' "$REPO_ROOT/local.properties" | head -1)"
    if [ -n "$d" ] && [ -d "$d" ]; then echo "$d"; return; fi
  fi
  case "$PLATFORM" in
    macos) echo "$HOME/Library/Android/sdk" ;;
    termux) echo "$HOME/android-sdk" ;;
    *) echo "$HOME/Android/Sdk" ;;
  esac
}
SDK="$(resolve_sdk_root)"
ok "SDK root: $SDK"

# Newest command-line tools build for this OS from Google's repository index (pinned fallback).
cmdline_tools_build() {
  local os="$1" fallback="$2" latest
  latest="$(curl -fsSL https://dl.google.com/android/repository/repository2-3.xml 2>/dev/null |
            grep -o "commandlinetools-$os-[0-9]*_latest.zip" | sed 's/[^0-9]//g' | sort -n | tail -1 || true)"
  echo "${latest:-$fallback}"
}

SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ -x "$SDKMANAGER" ]; then ok "cmdline-tools present"; else
  if [ "$PLATFORM" = macos ]; then OS_TAG=mac; FALLBACK=15641748; else OS_TAG=linux; FALLBACK=16111833; fi
  BUILD="$(cmdline_tools_build "$OS_TAG" "$FALLBACK")"
  URL="https://dl.google.com/android/repository/commandlinetools-${OS_TAG}-${BUILD}_latest.zip"
  install_cmdline_tools() {
    local tmp; tmp="$(mktemp -d)"
    curl -fL "$URL" -o "$tmp/cmdline-tools.zip"
    unzip -q "$tmp/cmdline-tools.zip" -d "$tmp"
    mkdir -p "$SDK/cmdline-tools"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$tmp"
  }
  run "download $URL into $SDK/cmdline-tools/latest" install_cmdline_tools
fi

WANTED=("${SDK_PACKAGES[@]}")
if [ "$PLATFORM" != termux ]; then WANTED=("${HOST_ONLY_PACKAGES[@]}" "${WANTED[@]}"); fi
if [ "$EMULATORS" -eq 1 ]; then
  if [ "$PLATFORM" = termux ]; then
    warn "--emulators ignored: the Android emulator does not run on Termux"
  elif [ -f "$REPO_ROOT/scripts/avd/matrix.csv" ]; then
    # Images used by the emulator matrix: unique (api, tag) pairs from the CSV's header-named columns.
    while IFS= read -r img; do
      [ -n "$img" ] && WANTED+=("$img")
    done < <(awk -F, -v abi="$ABI" '
      NR == 1 { for (i = 1; i <= NF; i++) { gsub(/\r/, "", $i); col[$i] = i }; next }
      $1 ~ /^#/ || $col["api"] == "" { next }
      { gsub(/\r/, ""); key = "system-images;android-" $col["api"] ";" $col["tag"] ";" abi
        if (!(key in seen)) { seen[key] = 1; print key } }' "$REPO_ROOT/scripts/avd/matrix.csv")
  else
    for api in "${API_LEVELS_PHONE[@]}"; do
      tag=google_apis; [ "$api" = 27 ] && [ "$ABI" = x86_64 ] && tag=default
      WANTED+=("system-images;android-$api;$tag;$ABI")
    done
    for api in "${API_LEVELS_TABLET[@]}"; do WANTED+=("system-images;android-$api;google_apis_tablet;$ABI"); done
  fi
fi
MISSING=()
for p in "${WANTED[@]}"; do
  [ -d "$SDK/${p//;//}" ] || MISSING+=("$p")
done
if [ "${#MISSING[@]}" -eq 0 ]; then ok "all SDK packages installed"; else
  accept_licences() { yes 2>/dev/null | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null || true; }
  run "accept Android SDK licences" accept_licences
  run "sdkmanager install ${MISSING[*]}" "$SDKMANAGER" --sdk_root="$SDK" "${MISSING[@]}"
fi

# ---------------------------------------------------------------------------------------------
section "Environment"

PROFILE_LINE="export ANDROID_HOME=\"$SDK\""
case "${SHELL:-}" in *zsh) RC="$HOME/.zshrc" ;; *) RC="$HOME/.bashrc" ;; esac
if [ -f "$RC" ] && grep -qF "$PROFILE_LINE" "$RC"; then ok "ANDROID_HOME set in $RC"; else
  append_profile() { printf '\n# Android SDK (added by TrekUI scripts/setup.sh)\n%s\n' "$PROFILE_LINE" >> "$RC"; }
  run "append '$PROFILE_LINE' to $RC" append_profile
fi
export ANDROID_HOME="$SDK"

LOCAL_PROPS="$REPO_ROOT/local.properties"
WANTED_LINE="sdk.dir=$SDK"
if [ -f "$LOCAL_PROPS" ] && grep -qxF "$WANTED_LINE" "$LOCAL_PROPS"; then ok "local.properties: $WANTED_LINE"; else
  write_local_props() {
    local rest=""
    [ -f "$LOCAL_PROPS" ] && rest="$(grep -v '^[[:space:]]*sdk\.dir[[:space:]]*=' "$LOCAL_PROPS" || true)"
    { echo "$WANTED_LINE"; [ -n "$rest" ] && echo "$rest"; } > "$LOCAL_PROPS"
  }
  run "write local.properties ($WANTED_LINE)" write_local_props
fi

if [ "$PLATFORM" = termux ]; then
  GRADLE_PROPS="$HOME/.gradle/gradle.properties"
  AAPT2_LINE="android.aapt2FromMavenOverride=$PREFIX/bin/aapt2"
  if [ -f "$GRADLE_PROPS" ] && grep -qxF "$AAPT2_LINE" "$GRADLE_PROPS"; then ok "aapt2 override in $GRADLE_PROPS"; else
    write_aapt2() {
      mkdir -p "$HOME/.gradle"
      local rest=""
      [ -f "$GRADLE_PROPS" ] && rest="$(grep -v '^android\.aapt2FromMavenOverride=' "$GRADLE_PROPS" || true)"
      # A phone has less RAM than a desktop; this also overrides the repo's 4 GB Gradle heap.
      { [ -n "$rest" ] && echo "$rest"; echo "$AAPT2_LINE"
        grep -q '^org.gradle.jvmargs=' "$GRADLE_PROPS" 2>/dev/null || echo "org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8"; } > "$GRADLE_PROPS.tmp"
      mv "$GRADLE_PROPS.tmp" "$GRADLE_PROPS"
    }
    run "write $AAPT2_LINE to $GRADLE_PROPS (per machine, not the repo)" write_aapt2
  fi
fi

# ---------------------------------------------------------------------------------------------
section "Virtualisation (emulator acceleration)"
case "$PLATFORM" in
  termux) ok "not applicable: emulators do not run on Termux; install the APK on this phone instead" ;;
  macos)
    if [ "$(sysctl -n kern.hv_support 2>/dev/null || echo 0)" = "1" ]; then ok "Hypervisor.framework available"
    else warn "Hypervisor.framework not available; the emulator will be slow or will not start"; fi ;;
  *)
    if [ -r /dev/kvm ] && [ -w /dev/kvm ]; then ok "/dev/kvm usable"
    elif [ -e /dev/kvm ]; then warn "/dev/kvm exists but is not accessible: sudo usermod -aG kvm \$USER, then log out and in"
    else warn "KVM not available: enable VT-x/AMD-V in BIOS/UEFI and load kvm_intel or kvm_amd"; fi ;;
esac

# ---------------------------------------------------------------------------------------------
if [ "$SKIP_BUILD" -eq 1 ]; then
  section "Smoke build skipped (--skip-build)"
else
  section "Smoke build"
  smoke_build() { (cd "$REPO_ROOT" && chmod +x gradlew && ./gradlew assembleDevelopDebug --console=plain); }
  run "./gradlew assembleDevelopDebug" smoke_build
  if [ "$DRY_RUN" -eq 0 ]; then
    ok "APK: app/build/outputs/apk/develop/debug/"
    if [ "$PLATFORM" = termux ]; then
      echo "  Install it on this phone:  termux-setup-storage (once), then"
      echo "    cp app/build/outputs/apk/develop/debug/*.apk ~/storage/downloads/ && termux-open ~/storage/downloads/\$(ls app/build/outputs/apk/develop/debug/ | grep apk\$ | head -1)"
    fi
  fi
fi

echo
if [ "${#PROBLEMS[@]}" -gt 0 ]; then
  echo "Done, with things to fix by hand:"
  for p in "${PROBLEMS[@]}"; do echo "  - $p"; done
else
  echo "Done. Open a new shell (or: source $RC) so ANDROID_HOME applies."
fi
