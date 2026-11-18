#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner (one emulator per matrix entry).
#
#  1. sets the orientation (ORIENTATION=landscape locks rotation to 90 degrees),
#  2. starts a background `adb shell screenrecord` loop (screenrecord stops itself after 180 s,
#     so the loop records numbered segments until the tests end),
#  3. runs ./gradlew :app:connectedDevelopDebugAndroidTest (the only module with device tests; add the
#     others here when they get some), retrying once if the emulator drops off adb,
#  4. pulls every screenshot the instrumented tests left on the device or in Gradle's
#     additional-test-output folder into assets/<device>-api<N>/,
#  5. if the tests produced no screenshots, takes a fallback tour (launch + screencap),
#  6. stitches the video segments into one mp4 and writes assets/<device>-api<N>/meta.json
#     (device, API, orientation, JUnit totals and per-test results) for the Pages site.
#
# Hook for the testing agent: if scripts/capture/ci-capture.sh exists it is run after the tests with
# DEVICE, API, ORIENTATION, PROFILE, OUT_DIR and APP_ID in the environment, and may add any media to OUT_DIR.
#
# Env (set by .github/workflows/emulator.yml): DEVICE, API, ORIENTATION, PROFILE, TARGET.
# Exit code is the Gradle test result, so a failing test fails the job (after media is saved).
set -uo pipefail

DEVICE="${DEVICE:?}"; API="${API:?}"; ORIENTATION="${ORIENTATION:-portrait}"
PROFILE="${PROFILE:-}"; TARGET="${TARGET:-google_apis}"
APP_ID="com.gungreeneyes.trekui.dev"
RUN_ID="${DEVICE}-api${API}"
OUT_DIR="assets/${RUN_ID}"
WORK="$(mktemp -d)"
mkdir -p "$OUT_DIR"

log() { echo "::group::$*"; }
endlog() { echo "::endgroup::"; }

adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done'
adb shell input keyevent 82 >/dev/null 2>&1 || true   # dismiss keyguard

# Lock rotation; rotate 90 degrees only when the wanted orientation differs from the panel's natural one
# (a phone is naturally portrait, Pixel Tablet naturally landscape).
natural=$(adb shell wm size | tail -n1 | awk '{print $NF}' | tr -d '\r')
nat_w=${natural%x*}; nat_h=${natural#*x}
if [ "${nat_w:-0}" -gt "${nat_h:-0}" ]; then natural_orientation=landscape; else natural_orientation=portrait; fi
adb shell settings put system accelerometer_rotation 0
if [ "$ORIENTATION" = "$natural_orientation" ]; then
  adb shell settings put system user_rotation 0
else
  adb shell settings put system user_rotation 1
fi
# Clean status bar for screenshots (demo mode; harmless if unsupported).
# Offline: a fresh google_apis image downloads and applies Play-services modules and flag syncs in its
# first minutes, which froze API 34-36 guests mid-run. No scenario needs the network.
adb shell svc wifi disable >/dev/null 2>&1 || true
adb shell svc data disable >/dev/null 2>&1 || true
adb shell settings put global sysui_demo_allowed 1 >/dev/null 2>&1 || true
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1047 >/dev/null 2>&1 || true
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null 2>&1 || true
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null 2>&1 || true

# ---- video: segment loop ----------------------------------------------------------------------------
STOP_FLAG="$WORK/stop"
adb shell rm -rf /sdcard/trekui-rec /sdcard/trekui-test >/dev/null 2>&1; adb shell mkdir -p /sdcard/trekui-rec
# The instrumented tests record their own per-scenario videos (MediaCapture); two screenrecord
# processes at once fail on most images, so the whole-run recording only runs when they don't.
if grep -rqs screenrecord app/src/androidTest; then touch "$STOP_FLAG"; echo "Tests record their own video; whole-run recording off."; fi
(
  i=0
  while [ ! -f "$STOP_FLAG" ] && [ $i -lt 20 ]; do
    i=$((i + 1))
    start=$(date +%s)
    adb shell screenrecord --bit-rate 6000000 --time-limit 175 "/sdcard/trekui-rec/part-$(printf %02d $i).mp4" >/dev/null 2>&1
    # screenrecord unsupported or failing: don't spin.
    if [ $(( $(date +%s) - start )) -lt 3 ]; then echo "screenrecord exited immediately; video disabled"; break; fi
  done
) &
REC_PID=$!

# ---- instrumented tests ------------------------------------------------------------------------------
log "connectedDevelopDebugAndroidTest on ${RUN_ID} (${ORIENTATION})"
# trekuiDevice names the files MediaCapture writes: <scenario>__<device>__api<N>__<orientation>.png
run_tests() {
  ./gradlew --no-daemon --console=plain :app:connectedDevelopDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.trekuiDevice=${DEVICE}"
}
run_tests
TEST_RC=$?
# Hosted-runner emulators sometimes drop off adb as the tests start ("device offline"); that is the
# runner, not a test result, so reconnect, wait for a full boot and run the suite once more.
if [ $TEST_RC -ne 0 ] && [ "$(timeout 10 adb get-state 2>/dev/null)" != "device" ]; then
  echo "::warning::emulator went offline during the tests; reconnecting and retrying once"
  adb reconnect offline >/dev/null 2>&1 || true
  if timeout 300 adb wait-for-device && \
     timeout 300 adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'; then
    adb shell input keyevent 82 >/dev/null 2>&1 || true
    run_tests
    TEST_RC=$?
  fi
fi
endlog

# ---- stop video -------------------------------------------------------------------------------------
touch "$STOP_FLAG"
adb shell pkill -INT screenrecord >/dev/null 2>&1 || adb shell killall -INT screenrecord >/dev/null 2>&1 || true
sleep 3
kill "$REC_PID" >/dev/null 2>&1 || true
wait "$REC_PID" 2>/dev/null || true

# ---- collect screenshots ----------------------------------------------------------------------------
log "Collecting screenshots"
# Gradle's additional test output (TestStorage / AdditionalTestOutput).
if [ -d app/build/outputs/connected_android_test_additional_output ]; then
  find app/build/outputs/connected_android_test_additional_output -type f \( -name '*.png' -o -name '*.jpg' -o -name '*.webp' -o -name '*.mp4' \) \
    -exec cp -n {} "$OUT_DIR/" \;
fi
# Folders instrumented tests may write to on the device.
for dir in /sdcard/trekui-test /sdcard/Pictures/trekui /sdcard/Pictures/screenshots /sdcard/Download/trekui \
           "/sdcard/Android/data/${APP_ID}/files/Pictures" "/sdcard/Android/data/${APP_ID}/files/screenshots" \
           "/sdcard/Android/media/${APP_ID}" "/sdcard/Android/media/${APP_ID}.test"; do
  if adb shell "[ -d '$dir' ]" >/dev/null 2>&1 && [ -n "$(adb shell ls "$dir" 2>/dev/null)" ]; then
    mkdir -p "$WORK/pull"
    timeout 300 adb pull "$dir" "$WORK/pull/" >/dev/null 2>&1 || true
  fi
done
if [ -d "$WORK/pull" ]; then
  find "$WORK/pull" -type f \( -name '*.png' -o -name '*.jpg' -o -name '*.webp' -o -name '*.mp4' \) -exec cp -n {} "$OUT_DIR/" \;
fi

# Testing agent's own capture hook.
if [ -f scripts/capture/ci-capture.sh ]; then
  DEVICE="$DEVICE" API="$API" ORIENTATION="$ORIENTATION" PROFILE="$PROFILE" OUT_DIR="$OUT_DIR" APP_ID="$APP_ID" \
    bash scripts/capture/ci-capture.sh || echo "::warning::scripts/capture/ci-capture.sh failed"
fi

# Fallback tour when nothing was captured: install, launch, screencap.
if [ -z "$(find "$OUT_DIR" -maxdepth 1 -name '*.png' -print -quit)" ]; then
  APK="$(find app/build/outputs/apk/develop/debug -name '*.apk' | head -n 1)"
  if [ -n "$APK" ]; then
    adb install -r -g "$APK" >/dev/null 2>&1 || adb install -r "$APK" >/dev/null 2>&1 || true
    adb shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
    sleep 8
    adb exec-out screencap -p > "$OUT_DIR/01-start.png"
    sleep 6
    adb exec-out screencap -p > "$OUT_DIR/02-home.png"
    # Center tap deploys the home layout's master control in most interfaces.
    w=$nat_w; h=$nat_h
    if [ "$ORIENTATION" = "landscape" ] && [ "$w" -lt "$h" ]; then t=$w; w=$h; h=$t; fi
    if [ "$ORIENTATION" = "portrait" ] && [ "$w" -gt "$h" ]; then t=$w; w=$h; h=$t; fi
    adb shell input tap $((w / 2)) $((h / 2)) >/dev/null 2>&1 || true
    sleep 3
    adb exec-out screencap -p > "$OUT_DIR/03-deployed.png"
  fi
fi
find "$OUT_DIR" -maxdepth 1 -type f -size 0 -delete
endlog

# ---- video: pull + stitch ---------------------------------------------------------------------------
log "Collecting video"
mkdir -p "$WORK/rec"
timeout 300 adb pull /sdcard/trekui-rec "$WORK/rec/" >/dev/null 2>&1 || true
mapfile -t parts < <(find "$WORK/rec" -name 'part-*.mp4' -size +10k | sort)
if [ ${#parts[@]} -gt 0 ]; then
  if command -v ffmpeg >/dev/null 2>&1; then
    : > "$WORK/concat.txt"
    for p in "${parts[@]}"; do echo "file '$p'" >> "$WORK/concat.txt"; done
    ffmpeg -loglevel error -y -f concat -safe 0 -i "$WORK/concat.txt" -c copy -movflags +faststart "$OUT_DIR/run.mp4" \
      || cp "${parts[0]}" "$OUT_DIR/run.mp4"
  else
    cp "${parts[0]}" "$OUT_DIR/run.mp4"
  fi
fi
endlog

# ---- logcat for failures ----------------------------------------------------------------------------
# timeout: with the emulator gone, adb logcat waits for a device forever (hung CI jobs to the 60 min limit)
timeout 60 adb logcat -d -v time > "$WORK/logcat.txt" 2>/dev/null || true
mkdir -p build/ci-logs && cp "$WORK/logcat.txt" "build/ci-logs/logcat-${RUN_ID}.txt" 2>/dev/null || true

# ---- meta.json ------------------------------------------------------------------------------------------
python3 .github/scripts/emulator-meta.py \
  --out "$OUT_DIR" --device "$DEVICE" --api "$API" --orientation "$ORIENTATION" \
  --profile "$PROFILE" --target "$TARGET" --exit-code "$TEST_RC" \
  --results app/build/outputs/androidTest-results/connected

echo "Captured into $OUT_DIR:"; ls -la "$OUT_DIR"

# Stop the emulator here: on some images its own shutdown hangs after "removeAll" and the runner action
# waits for it until the job limit, even though every result is already saved.
timeout 20 adb emu kill >/dev/null 2>&1 || true
sleep 5
pkill -9 -f qemu-system >/dev/null 2>&1 || true
# The `emulator` launcher process can hang on its own after qemu is gone; the runner action waits on it.
pkill -9 -x emulator >/dev/null 2>&1 || true
pkill -9 -f 'emulator/emulator' >/dev/null 2>&1 || true
pkill -9 -f crashpad_handler >/dev/null 2>&1 || true
exit $TEST_RC
