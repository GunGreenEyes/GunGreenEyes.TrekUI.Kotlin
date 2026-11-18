# Building

## Toolchain

| | Version | Where it is set |
|---|---|---|
| JDK (to run Gradle) | 21 | your machine (`JAVA_HOME`); CI uses Temurin 21 |
| Language | Kotlin 2 (K2), through AGP 9's built-in Kotlin support | no `org.jetbrains.kotlin.android` plugin is applied (it would conflict with AGP's) |
| JVM target (bytecode level) | 17 | `build-logic` (`TrekUiSdk.JAVA`, `compileOptions`; AGP's built-in Kotlin follows `targetCompatibility`) |
| Gradle | wrapper (9.x) | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | see catalog | `gradle/libs.versions.toml` (`agp`) |
| compileSdk / targetSdk | 37 | `build-logic` (`TrekUiSdk`) |
| minSdk | 23 (Android 6.0) | `build-logic` (`TrekUiSdk`) |
| ktlint / detekt | see catalog | `gradle/libs.versions.toml` (`ktlint`, `detekt`) |
| SDK packages | `platforms;android-37.0`, `build-tools;37.0.0`, `platform-tools` | installed by `scripts/setup.*` |

All dependency versions live in the version catalog `gradle/libs.versions.toml`. Bump them there only; Dependabot opens PRs for updates.

### Source layout

Every module (`:core`, `:app`, `:messages`, `:keyboard`, `:lockscreen`) keeps its Kotlin in `src/main/kotlin/`, its unit tests in `src/test/kotlin/`, and the launcher's instrumented scenarios in `app/src/androidTest/kotlin/`. There are no Java sources.

### Build logic: convention plugins

What the five modules share lives once, in the included build `build-logic/` (`settings.gradle.kts`: `pluginManagement { includeBuild("build-logic") }`). It is compiled with the build and reads the same version catalog. AGP and detekt are declared `apply false` in the root `build.gradle.kts` and are `compileOnly` in `build-logic`, so every module runs the catalog's versions.

| File in `build-logic/convention/src/main/kotlin/` | Plugin id | What it sets |
|---|---|---|
| `TrekUiAndroid.kt` | (shared code) | compileSdk/minSdk/targetSdk 37/23/37, Java/Kotlin target 17, the instrumentation runner, `unitTests.isReturnDefaultValues`, lint (`abortOnError = false`, `checkReleaseBuilds`, XML + HTML reports) |
| `AndroidApplicationConventionPlugin.kt` | `trekui.android.application` | the above plus the version from `version.properties`, the `TRIAL_SESSION_MINUTES` and `UNLOCK_PRODUCT_ID` fields, the `develop`/`production` flavors (`.dev` id suffix, `-dev` name suffix, 5-minute trial, the per-flavor `trekuiSuitePermission` placeholder), release signing (below), R8 + resource shrinking with `proguard-rules.pro`, `buildConfig`/`resValues`, the META-INF packaging exclude |
| `AndroidLibraryConventionPlugin.kt` | `trekui.android.library` | the shared part, for `:core` |
| `QualityConventionPlugin.kt` | `trekui.quality` | ktlint and detekt (below); applied by both Android plugins |

A module's own `build.gradle.kts` keeps only its plugin alias (`libs.plugins.trekui.android.application` or `.library`), its namespace and applicationId, the flavor `app_name` strings, its dependencies and what is really its own: `:app`'s `CALL_LOG_ENABLED` field (true only in `develop`), Robolectric's `isIncludeAndroidResources` and `animationsDisabled`; `:core`'s `consumer-rules.pro` and `isIncludeAndroidResources`. The keyboard's missing network permissions come from its manifest, not the build. To change something every app shares, change the convention, not a module. The root `build.gradle.kts` keeps the `dist` task.

### Style checks: ktlint and detekt

```sh
./gradlew ktlintCheck            # ktlint (official CLI, catalog version) on each module's src/**/*.kt and build.gradle.kts
./gradlew ktlintFormat           # rewrite those files to the rules
./gradlew detekt                 # detekt, default rules (buildUponDefaultConfig)
./gradlew detektBaseline         # rewrite each module's detekt-baseline.xml
```

Both are part of `check` and run in the CI build job. The ktlint rules are in the root `.editorconfig`: Android Studio code style, 120 columns, with the wrapping and trailing-comma rules that fight the compact house style turned off. What the code already broke when the checks arrived (TUK-T3) is listed per module in `<module>/ktlint-baseline.xml` and `<module>/detekt-baseline.xml`, so only new findings fail. When you clean up a file, remove its entries: regenerate the detekt baseline with `detektBaseline`, and the ktlint one by deleting `<module>/ktlint-baseline.xml` and running `./gradlew :<module>:ktlintCheck` once (ktlint writes the file and fails that run; the next run passes). ktlint baselines record line numbers, so editing an old file can surface its old findings; fix them in the lines you touch. detekt 1.23 runs its own Kotlin compiler (catalog `detekt-kotlin`, pinned by the convention), independent of the Kotlin the build compiles with.

### Fresh machine

```sh
powershell -ExecutionPolicy Bypass -File scripts\setup.ps1     # Windows (winget, choco fallback)
bash scripts/setup.sh                                         # macOS (brew), Linux (apt/dnf), Termux (pkg)
```

Both scripts are idempotent and finish with a check build. Options:

| Windows | macOS/Linux/Termux | Effect |
|---|---|---|
| `-WhatIf` | `--dry-run` | print every action, change nothing |
| `-Emulators` | `--emulators` | also install the emulator system images listed in `scripts/avd/matrix.csv` |
| `-SkipBuild` | `--skip-build` | skip the final `assembleDevelopDebug` |
| `-SdkRoot D:\Android\Sdk` | `--sdk-root DIR` | SDK location (default: `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `local.properties`, then `D:\Android\Sdk` / `%LOCALAPPDATA%\Android\Sdk` / `~/Library/Android/sdk` / `~/Android/Sdk` / `~/android-sdk` on Termux) |

The scripts write `local.properties` (`sdk.dir=` with forward slashes, which `.properties` files accept on Windows without escaping). It is gitignored.

On **Termux** the SDK's build tools are x86_64 binaries that cannot run on the phone. The script installs Termux's `aapt2` and points Gradle at it with `android.aapt2FromMavenOverride=$PREFIX/bin/aapt2` in `~/.gradle/gradle.properties`. That is a per-machine file, so the setting never goes in the repository. It also lowers the Gradle heap to 2 GB there. If resource linking fails after an AGP upgrade, the Termux `aapt2` is probably too old for the new AGP. Update it with `pkg upgrade aapt`.

## Product flavors

One dimension, `channel`:

| Flavor | applicationId | App name | Version suffix | Trial session | Purpose |
|---|---|---|---|---|---|
| `develop` | `com.gungreeneyes.trekui.dev` | TrekUI Dev | `-dev` | 5 minutes | everyday development, CI, emulator tests. It installs next to the store build. |
| `production` | `com.gungreeneyes.trekui` | TrekUI | – | 24 hours (1440 min) | Google Play and GitHub Releases |

Flavor-specific values are `BuildConfig` fields (`TRIAL_SESSION_MINUTES`, `UNLOCK_PRODUCT_ID`) and `resValue` strings. Flavor-specific sources and resources go under `app/src/develop/` or `app/src/production/`.

**Personal sounds:** a local `develop` build picks up `personal_sounds/res` as an extra resource directory when that folder exists. That is the owner's own recordings, gitignored. CI (`CI` set) never does, and `production` never does.

## Build types

| Type | Minify/shrink (R8) | Signing | Debuggable |
|---|---|---|---|
| `debug` | no | `signing/debug.keystore` (the original 5.x key, so sideloaded updates install over old phones) | yes |
| `release` | yes (`proguard-rules.pro`) | release key from `signing/release.properties` or `TREKUI_*` env vars. Without it, the debug key, so a local release build still installs. | no |

Variants: `developDebug`, `developRelease`, `productionDebug`, `productionRelease`.

## Common tasks

```sh
./gradlew assembleDevelopDebug                 # APK: app/build/outputs/apk/develop/debug/
./gradlew installDevelopDebug                  # install on the connected device/emulator
./gradlew assembleProductionRelease            # APK: app/build/outputs/apk/production/release/
./gradlew bundleProductionRelease              # AAB: app/build/outputs/bundle/productionRelease/
./gradlew dist                                 # all of the above for all four apps, copied to dist/<version>/
./gradlew testDevelopDebugUnitTest             # JVM + Robolectric tests (see TESTING.md)
./gradlew lintDevelopDebug                     # lint report: app/build/reports/
./gradlew ktlintCheck detekt                   # style checks (see "Style checks")
```

On Windows use `gradlew.bat` (or `./gradlew.bat` from Git Bash).

### `dist`

The root project's `dist` task builds `assembleDevelopDebug`, `assembleProductionRelease` and `bundleProductionRelease` for every app (`:app`, `:messages`, `:keyboard`, `:lockscreen`), then copies the APKs and AABs into `dist/<versionName>/` at the repository root, each named `<App>-<versionName>-<flavor>-<buildType>` so the four apps never collide:

```
dist/45/TrekUI-45-develop-debug.apk               dist/45/TrekUI-Messages-45-develop-debug.apk
dist/45/TrekUI-45-production-release.apk          dist/45/TrekUI-Messages-45-production-release.apk
dist/45/TrekUI-45-production-release.aab          dist/45/TrekUI-Messages-45-production-release.aab
dist/45/TrekUI-Keyboard-45-…                      dist/45/TrekUI-LockScreen-45-…   (same three each)
```

`dist/` is committed: each release gets its own `dist/<version>/` folder and earlier versions are kept (for example `dist/44/`), so the prebuilt APKs of every version stay in the repository. They are also attached to each GitHub release.

## Versioning: `version.properties`

```properties
versionCode=45
versionName=45
```

Both values are read by the `trekui.android.application` convention for every app. This file is the single source of the version.

- **`versionCode`:** an integer that must go up for every build uploaded to Google Play. Never reuse one.
- **`versionName`:** a whole number (42, 43, 44, ...), one higher each release, and always equal to `versionCode`; no minor or patch part. The release workflow refuses a tag `v<N>` that does not match it.
- **Release notes:** put the Play release notes for each build and app in `fastlane/metadata/<app>/android/en-US/changelogs/<versionCode>.txt` (`<app>` is `launcher`, `messages`, `keyboard` or `lockscreen`; 500 characters at most). `scripts/release/ship.*` writes them.

See [RELEASE.md](RELEASE.md) for the bump, tag and publish steps.

## Signing

- **Debug key.** `signing/debug.keystore` is the original 5.x key (password `android`, alias `androiddebugkey`). It exists only on the owner's machine: everything in `signing/` is gitignored except a README. A fresh clone without it signs with the Android SDK's default debug key instead. Ask the owner if you need updates to install over the owner's phones.
- **Release (upload) key, locally.** Create `signing/release.properties` (gitignored):

  ```properties
  STORE_FILE=signing/upload.jks
  STORE_PASSWORD=…
  KEY_ALIAS=upload
  KEY_PASSWORD=…
  ```

- **Release key, in CI.** The same four values come from environment variables: `TREKUI_STORE_FILE`, `TREKUI_STORE_PASSWORD`, `TREKUI_KEY_ALIAS` and `TREKUI_KEY_PASSWORD`. The release workflow decodes the keystore from the `TREKUI_KEYSTORE_BASE64` secret. `scripts/release/` has a helper that creates an upload key and prints the `gh secret set` commands.
- **Google Play App Signing.** Google holds the app signing key and you hold only the upload key. If the upload key is lost, Play Console can reset it. Play-installed copies are therefore signed differently from sideloaded debug-key builds, and one cannot update the other.

Never commit keystores, passwords or `play-service-account*.json`. `.gitignore` blocks them; don't weaken it.

## Assets and generators

Generated art is committed under `core/src/main/assets/` (fonts, glyphs, sounds) and `app/src/main/assets/` (badges, quick-panel and app-theme art, wallpapers), so a build needs no Python or Node. To regenerate it:

| Tool | Output |
|---|---|
| `tools/art/glyph_render.py`, `glyphs_extra.py`, `render.js` | The extended pictogram set (bold stroke glyphs) |
| `tools/legacy/*.py` | Console glyphs, Warrior Blade font, Quick Panel art, app-theme wallpapers, NavStar device art |
| `tools/art/retitle_wallpapers.py` | Re-letters the three bundled Command wallpapers |
| `tools/sounds/*.py` | the sound catalogue `assets/sounds/manifest.json` (see [SOUNDS.md](SOUNDS.md)) |

Some generators need Python 3, Pillow, Node.js and ffmpeg. The setup scripts install all of these except Pillow (`pip install pillow`).

## Troubleshooting

| Symptom | Fix |
|---|---|
| `SDK location not found` | run the setup script, or write `sdk.dir=C:/path/to/Sdk` into `local.properties` |
| `Unsupported class file major version` / Gradle refuses to start | `JAVA_HOME` must point to JDK 21 |
| `Timeout waiting to lock …` | another Gradle process is using the same checkout; wait or `./gradlew --stop` |
| Install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | a copy signed with another key is installed; uninstall it first (`adb uninstall com.gungreeneyes.trekui.dev`) |
| Termux: `aapt2 … Syntax error` or `cannot execute binary file` | the aapt2 override is missing; re-run `bash scripts/setup.sh` |
