# Building

## Toolchain

| | Version | Where it is set |
|---|---|---|
| JDK (to run Gradle) | 21 | your machine (`JAVA_HOME`) |
| Java source/target level | 17 | `app/build.gradle.kts` `compileOptions` |
| Gradle | wrapper (9.x) | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | see catalog | `gradle/libs.versions.toml` (`agp`) |
| compileSdk / targetSdk | 37 | `app/build.gradle.kts` |
| minSdk | 26 (Android 8.0) | `app/build.gradle.kts` |
| SDK packages | `platforms;android-37.0`, `build-tools;37.0.0`, `platform-tools` | installed by `scripts/setup.*` |

All dependency versions live in the version catalog `gradle/libs.versions.toml`. Bump them there only; Dependabot opens PRs for updates.

### Fresh machine

```sh
powershell -ExecutionPolicy Bypass -File scripts\setup.ps1     # Windows (winget, choco fallback)
bash scripts/setup.sh                                         # macOS (brew), Linux (apt/dnf), Termux (pkg)
```

Both scripts are idempotent and finish with a smoke build. Options:

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
./gradlew dist                                 # all of the above for all four apps, copied to dist/
./gradlew testDevelopDebugUnitTest             # JVM + Robolectric tests (see TESTING.md)
./gradlew lintDevelopDebug                     # lint report: app/build/reports/
```

On Windows use `gradlew.bat` (or `./gradlew.bat` from Git Bash).

### `dist`

The root project's `dist` task builds `assembleDevelopDebug`, `assembleProductionRelease` and `bundleProductionRelease` for every app (`:app`, `:messages`, `:keyboard`, `:lockscreen`), then copies the APKs and AABs into `dist/` at the repository root, each named `<App>-<versionName>-<flavor>-<buildType>` so the four apps never collide:

```
dist/TrekUI-42-develop-debug.apk               dist/TrekUI-Messages-42-develop-debug.apk
dist/TrekUI-42-production-release.apk          dist/TrekUI-Messages-42-production-release.apk
dist/TrekUI-42-production-release.aab          dist/TrekUI-Messages-42-production-release.aab
dist/TrekUI-Keyboard-42-…                      dist/TrekUI-LockScreen-42-…   (same three each)
```

`dist/` is gitignored.

## Versioning: `version.properties`

```properties
versionCode=42
versionName=6
```

Both values are read by every app module's `build.gradle.kts`. This file is the single source of the version.

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

Generated art is committed under `app/src/main/assets/`, so a build needs no Python or Node. To regenerate it:

| Tool | Output |
|---|---|
| `tools/art/*.py`, `render.js`, `build.sh` | Pipe Lounge textures, badges, pictograms and scene hotspot maps |
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
