# Emulator matrix capture (LCA-T6)

Runs the instrumented Given/When/Then scenarios (`app/src/androidTest`, scenario text in
`app/src/androidTest/assets/features/*.feature`) on every AVD in `scripts/avd/matrix.csv` and
collects screenshots, videos, JUnit XML and logs into the root `assets/` folder (gitignored; CI
publishes it to GitHub Pages).

## One-time setup

```powershell
# AVDs trekui_<id>_api<api> from matrix.csv (never touches other AVDs)
powershell -ExecutionPolicy Bypass -File scripts/avd/create-avds.ps1
# or: scripts/avd/create-avds.sh            (bash; --only REGEX --force --delete --install)
```

System images are looked up in `ANDROID_SDK_ROOT` (default `D:\Android\Sdk`) and in
`TREKUI_EXTRA_SDK_ROOTS` (default `R:\Android\sdk-extra`, where the API 37.0 and 37.2 ps16k images
were installed because D: is nearly full). `-Install` installs missing images with sdkmanager.

## Run

```powershell
powershell -ExecutionPolicy Bypass -File scripts/capture/run-matrix.ps1              # all rows, 3 at a time
powershell -ExecutionPolicy Bypass -File scripts/capture/run-matrix.ps1 -Only 'api3[67]' -Parallel 2
powershell -ExecutionPolicy Bypass -File scripts/capture/run-matrix.ps1 -Only '^pixel_7-api36' -SkipBuild -TestFilter '.*HomeScenarios.*'
# bash: scripts/capture/run-matrix.sh <same options>
```

Per row (`run-one.ps1`): cold boot headless (`-no-window -no-audio -no-boot-anim -no-snapshot
-wipe-data -gpu auto`, i.e. the host GPU when there is one; pass `-Gpu swiftshader_indirect` to force software rendering) -> wait for boot -> animations off, stay awake -> install the
develop debug APK + androidTest APK -> `am instrument` per test class and per interface (a crash only
loses that slice) -> pull `/sdcard/trekui-test` -> logcat + crash buffer -> `junit.xml`, `result.json`
-> `emu kill` and delete the AVD's user data. Finally `build-index.ps1` writes `assets/index.json`.

Options: `-NoVideo` (skip screenrecord), `-Gpu host`, `-BasePort 5580` (console ports 5580, 5582, 5584).

## Output

```
assets/<id>-api<api>[-<orientation>]/
  media/<Class>_<scenario>[-<step>]__<device>__api<N>__<portrait|landscape>.png|.mp4
  junit.xml   result.json   meta.json   logs/{runner,emulator,logcat,crash,instrument-NN}.txt
assets/robolectric/<interface>/<screen>.png     (see below)
assets/bluestacks/<instance>-home.png            (scripts/bluestacks.ps1)
assets/index.json                                (device, api, size, density, aspect, pass/fail, media paths)
```

## Fast screenshots without emulators (Robolectric)

```bash
TREKUI_SCREENSHOTS=1 ./gradlew :app:testDevelopDebugUnitTest --tests '*RobolectricScreenshots*' --rerun
powershell -File scripts/capture/build-index.ps1     # refresh index.json
```

`--rerun` matters: the env var is not a Gradle input, so without it an up-to-date test task
is skipped and nothing is rendered. Renders all 8 interface variants (the five interfaces plus the Command, Imperial and Collective deploying layouts) at 11 screen configurations (5:3 mdpi to 21:9 xxhdpi, foldable 6:5, 7"
and 4:3 tablets, landscape) with Robolectric native graphics into `assets/robolectric/`.

## Scenario ids and test arguments

Media and results are matched by `<SimpleClass>_<method>` with every run of characters outside
`[A-Za-z0-9_-]` replaced by `-` (`MediaCapture.sanitize` / `ConvertTo-ScenarioId`). Instrumentation
arguments: `trekuiDevice` (name in file names), `trekuiOrientation` (natural | portrait | landscape),
`trekuiVideo` (false disables screenrecord).
