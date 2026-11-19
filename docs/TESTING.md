# Testing

There are three layers of tests, from fastest to slowest:

| Layer | Where | Runs on | Command |
|---|---|---|---|
| Unit tests (pure Kotlin logic) | `<module>/src/test/kotlin/…` | JVM | `./gradlew testDevelopDebugUnitTest` |
| Robolectric view and screenshot tests | `app/src/test/kotlin/…` | JVM with Robolectric | same task |
| Instrumented scenarios with screen capture | `app/src/androidTest/kotlin/…` | emulator or device | `./gradlew connectedDevelopDebugAndroidTest` |

Every test is Kotlin. The tests were converted from the Java edition together with the code they cover, with the same assertions, steps, waits and timeouts. Unit tests live in each module: `app/src/test/kotlin/` (the launcher, plus `:core` classes it exercises, such as `SkinTest`), `messages/src/test/kotlin/`, `keyboard/src/test/kotlin/` and `lockscreen/src/test/kotlin/`.

CI runs all three: unit tests on every push and PR, and the emulator matrix on every push to `main`, PR and release (see [RELEASE.md](RELEASE.md)).

## Unit tests

- **Naming.** Name a test after the class it covers, `<Class>Test.kt`, in the same package. Examples: `SkinTest`, `SoundProcessorTest`, `licensing/TrialClockTest`.
- **Visibility.** A test sees `internal` declarations of its own module only. `:core` declarations that `:app` tests use are public (see [ARCHITECTURE.md](ARCHITECTURE.md#kotlin-conventions-in-this-codebase)).
- **Who writes them.** Anyone changing a class adds or updates its test.
- **Keep decisions pure.** Keep logic in pure functions or small state holders that do not touch Android, so it runs on the plain JVM without Robolectric. Examples: `HomeLogic`, `ScreenMetrics.compute`, `SoundProcessor`, `licensing/TrialClock` with a fake `TimeSource` and `KeyValueStore`.
- **Frameworks.** JUnit 4 and Google Truth.
- **Android stubs.** `unitTests.isReturnDefaultValues = true`, so stray Android calls return defaults instead of throwing.
- **Resources.** `unitTests.isIncludeAndroidResources = true`, so Robolectric tests can inflate resources and read `assets/`.

```sh
./gradlew testDevelopDebugUnitTest                          # all
./gradlew testDevelopDebugUnitTest --tests '*SkinTest'      # one class
```

Reports go to `app/build/reports/tests/testDevelopDebugUnitTest/index.html`.

## Robolectric screenshots

The Robolectric tests build real launcher views (home, drawer, frames and so on) in each interface and at several screen sizes, then render them to PNG on the JVM. No emulator is needed, so they are a quick visual check of all five interfaces.

- **Output:** `assets/robolectric/` at the repository root.
- **In CI:** the images are uploaded as the `capture-robolectric` artifact and shown on the GitHub Pages site.
- **What they cannot show:** they show layout and drawing, not real device behaviour such as insets, the default-home role or system dialogs. The instrumented scenarios cover those.

## Instrumented scenarios (Given/When/Then)

Behaviour is written as Gherkin in `app/src/androidTest/assets/features/*.feature`. There is one feature file per area: home, interfaces, rotation, settings and trial. Each scenario has a matching JUnit 4 test method:

| Feature file | Test class |
|---|---|
| `home.feature` | `HomeScenarios` |
| `interfaces.feature` | `InterfaceScenarios` |
| `rotation.feature` | `RotationScenarios` |
| `settings.feature` | `SettingsScenarios` |
| `trial.feature` | `TrialScenarios` |

The supporting classes are:

- **`AppScenario`:** the base class. It sets the orientation, resets preferences to a quiet known state (no boot animation, no welcome dialog, trial forced unlocked in debug builds), and starts and stops a screen recording per scenario.
- **`Gherkin`:** logs each Given/When/Then step to logcat under the tag `TREKUI-GHERKIN`, so a failure shows exactly which step it reached.
- **`MediaCapture`:** screenshots and screen recordings, written on the device to `/sdcard/trekui-test/`. The names follow `<scenario>[-<step>]__<device>__api<N>__<orientation>.png|.mp4`.
- **`LayoutAudit`:** checks that nothing is clipped or hidden under a system bar or display cutout.
- **`Interface`:** enumerates the five interfaces.

Cucumber is not used on purpose. Plain JUnit 4 keeps the toolchain small and runs on every API level from 23 up.

**Android 6.0–7.1.** The harness adapts to the old platforms: `HomeScenarios` makes TrekUI the home app by pressing its radio button in Settings › Home (on the old versions its title block swallows the tap), it has no shell home-app fallback below 7.0 (API 23 has no `cmd` binary), and `MediaCapture` skips `screenrecord` below 8.0 (a recording that cannot start kills the whole instrumentation there), so those devices produce screenshots only.

To add a scenario, write it in the right `.feature` file, then add a method with the scenario's name to the matching `*Scenarios` class in `app/src/androidTest/kotlin/com/gungreeneyes/trekui/` and call `Gherkin` for each step. The scenarios reach into the launcher through `AppHooks`, which uses reflection; keep the members it names (`MainActivity.openConfig`, `requestDefaultHome`, `Skin.faction`, `AppRepo.get`, …) static and un-renamed, or update `AppHooks` with them.

```sh
./gradlew connectedDevelopDebugAndroidTest                                    # every connected device
./gradlew connectedDevelopDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.gungreeneyes.trekui.HomeScenarios
```

Instrumentation arguments: `trekuiOrientation` (natural | portrait | landscape) and `trekuiDevice` (the name used in media file names).

## Emulator matrix

`scripts/avd/matrix.csv` lists the test devices: one row per AVD. Each row gives the device profile, API level (23 to 37), system-image tag, optional screen override (width, height, density), orientation and form factor (phone, tablet, foldable). Together the rows cover:

- aspect ratios from 4:3 to 21:9;
- densities from mdpi to xxxhdpi;
- a foldable;
- tablets in both orientations;
- a 16 KB page-size image.

```powershell
scripts\setup.ps1 -Emulators -SkipBuild        # install the system images the matrix needs
scripts\avd\create-avds.ps1                    # create AVDs named trekui_<id>_api<api>
scripts\capture\run-matrix.ps1                 # boot each, run the scenarios, pull media (see its header)
scripts\capture\run-matrix.ps1 -Parallel 1     # one emulator at a time, for a machine short of RAM
```

`bash scripts/setup.sh --emulators` installs the same images on macOS and Linux. It installs arm64 images on Apple silicon.

Emulators need hardware acceleration:

- **Windows:** WHPX ("Windows Hypervisor Platform").
- **Linux:** KVM.
- **macOS:** Hypervisor.framework.

The setup scripts check for it and say how to turn it on.

The local runner boots each emulator with `-gpu auto` (the host GPU when there is one; SwiftShader froze API 35/36 guests on the heaviest screens) and turns Wi-Fi and mobile data off once it has booted, so Play-services downloads cannot stall it. Each emulator gets 2 GB of RAM (`-MemoryMb`); three run at once by default.

### In CI

`.github/workflows/emulator.yml` runs a matrix on GitHub's Ubuntu runners with KVM. It has two profiles:

- **quick** (PRs): three devices (phone API 26, phone API 36, tablet API 36).
- **full** (`main` and releases): 11 devices, phones on API 23, 24, 26, 29, 31, 33, 35 and 36, plus a landscape phone, a tablet and a foldable on API 36.

The Android 6.0 and 7.0 phones (API 23, 24) run on the 32-bit x86 images with 2 GB of RAM, because the x86_64 images of that age never finish booting on hosted runners. The snapshot-creation step is limited to 20 minutes, and the guests run offline (no scenario needs the network). The foldable renders through ANGLE (`-gpu swangle_indirect`), because its large unfolded screen took the emulator down under SwiftShader; every other device uses `swiftshader_indirect`.

For each device, `.github/scripts/emulator-run.sh` records the screen, runs `connectedDevelopDebugAndroidTest`, pulls the screenshots, stitches the video and writes `meta.json` with the JUnit results. Each device's media is uploaded as the artifact `capture-<device>-api<N>`.

## BlueStacks

BlueStacks is a convenient "real" Android 9 or 11 environment on Windows and macOS for manual checks. The `scripts/bluestacks.*` helpers find the BlueStacks ADB port, connect, install the develop APK and make it the home app.

To do it by hand:

1. Enable **Settings › Advanced › Android Debug Bridge** in BlueStacks.
2. Run `adb connect 127.0.0.1:<port>`.
3. Run `./gradlew installDevelopDebug`.

Use a Pie 64-bit or Android 11 instance; Nougat instances work too (minSdk is 23).

## Where test media goes

All generated screenshots, videos and test metadata go into **`assets/` at the repository root**, never into `app/src/main/assets/`, which holds the app's own bundled assets:

```
assets/
  robolectric/                 JVM screenshots
  <device>-api<N>/             one folder per emulator run: PNGs, MP4, meta.json
```

The root `assets/` folder is gitignored, apart from its index file. CI uploads the media as artifacts, bundles it into `site-media.zip` on each GitHub Release, and the Pages workflow publishes it at <https://gungreeneyes.github.io/TrekUI.Kotlin/>.

## Manual checklist before a release

- Set as default home on a real phone, then press Home from another app and confirm it returns to TrekUI.
- Switch through all five interfaces in portrait and landscape.
- Check a foldable or tablet in multi-window: resize the window and confirm nothing is clipped.
- Trial flow on a develop build:
  - shorten the 12-hour session with the debug hook (`adb shell am start -n com.gungreeneyes.trekui.dev/com.gungreeneyes.trekui.MainActivity --ei trekui.debug.trial_seconds 30`, or `--ez trekui.debug.expire_now true`), then confirm the purchase screen appears on Home only;
  - check that NEW SESSION works;
  - check a real test purchase on the production build from the internal track with a licence-tester account. The develop app ID cannot buy the product, so use its simulated purchase instead; see [PLAY_BILLING_SETUP.md](PLAY_BILLING_SETUP.md).
- Sound download from CONFIG › AUDIO SYSTEMS, then play a few events.
- Weather with both city and device location, and CLOAK on and off.
