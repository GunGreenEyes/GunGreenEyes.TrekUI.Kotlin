# AGENTS.md: TrekUI Launcher project guide for coding agents

This guide is for Claude Code (terminal, web or phone app), Cowork, Codex and any other agent working in this repository. Humans should read [README.md](README.md) and [CONTRIBUTING.md](CONTRIBUTING.md).

## What this is

This is the **Kotlin edition** of the TrekUI suite: TrekUI Launcher, an Android **home-screen launcher** (package `com.gungreeneyes.trekui`), plus Messages, Keyboard and Lock Screen on a shared `:core`. It was converted from [TrekUI.Java](https://github.com/GunGreenEyes/TrekUI.Java) with no behaviour change, keeps the same application IDs and signing, and will replace the Java edition on Play as a normal update. Plan and status: [docs/KOTLIN_PORT.md](docs/KOTLIN_PORT.md).

- **Code style:** idiomatic Kotlin 2 (AGP 9's built-in Kotlin, JVM target 17), all sources in `<module>/src/main/kotlin/`; no Java sources. All views are custom and drawn on a Canvas. There are **no XML layouts** and no Compose yet (phase 5). ktlint and detekt are planned for phase 1 and not in the build yet.
- **Five interfaces**, all starship consoles, chosen by `Skin.faction`: Command 0, Imperial 1, Collective 2, Classic Console 9 and Next Console 10.
  - Ids 3–8 are retired; a saved choice of one reads as Command (sub-theme 0).
- **Entry points:** `MainActivity` (the HOME activity) builds everything. `SettingsActivity` is CONFIG.
- **Architecture:** see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Build and test

You need JDK 21 and an Android SDK with platform 37 and build-tools 37. On a fresh machine, run `scripts/setup.ps1` (Windows) or `scripts/setup.sh` (macOS, Linux, Termux). Add `-WhatIf` / `--dry-run` to preview.

```sh
./gradlew assembleDevelopDebug                 # main dev build -> app/build/outputs/apk/develop/debug/
./gradlew testDevelopDebugUnitTest             # JVM + Robolectric tests
./gradlew lintDevelopDebug
./gradlew connectedDevelopDebugAndroidTest     # instrumented scenarios (needs emulator/device)
./gradlew dist                                 # all APKs/AABs -> dist/<version>/ (committed)
```

On Windows, use `gradlew.bat` and set `JAVA_HOME` to the JDK 21. If Gradle waits on a lock, another build is running in the same checkout, so wait for it.

**Flavors:**

| Flavor | Application ID | Name | Trial | Notes |
|---|---|---|---|---|
| `develop` | `com.gungreeneyes.trekui.dev` | TrekUI Dev | 12 hours (720 min) | Use this for everything you build and test |
| `production` | `com.gungreeneyes.trekui` | TrekUI | 12 hours | Google Play |

Build types are `debug` and `release` (R8, upload-key signing). The version lives in `version.properties`.

**CI:** every push to `main` builds a develop APK, available as the artifact `TrekUI-develop-debug-apk`. The release pipeline builds, signs and uploads all four apps; start it with the ship script (below). Details are in [docs/RELEASE.md](docs/RELEASE.md).

## Releasing ("release", "ship it")

When the owner says **"release"** or **"ship it"**, run the ship script with the **internal** track:

```sh
scripts/release/ship.sh --track internal          # Windows: .\scripts\release\ship.ps1 -Track internal
```

- It bumps to the next whole-number version (42, 43, 44, ...; one number is both versionName and versionCode; give an explicit higher number only if the owner asks), writes `CHANGELOG.md` and each app's Play notes, commits and tags `v<versionName>`, pushes, starts `release.yml` and prints the Actions run URL. Report that URL.
- Use `--track production` (with `--rollout`, default `0.1`) **only when the owner explicitly says production**. Production then waits for the owner's approval in the `google-play` environment.
- Run `--dry-run` first when unsure; it changes nothing.
- One run ships all four apps (launcher, messages, keyboard, lockscreen) with one shared version.

## Rules

1. **Size with `ScreenMetrics`.** Use `ScreenMetrics.of(context)` (`marginPx()`, `gutterPx()`, `scaledPx(dp)`, `uiScale`, `columns(minTileDp, min, max)`, `isTwoPane()`, `isTall()`, `isLandscape()`).
   - Never hard-code pixel sizes, column counts or orientation.
   - Every screen must work in portrait and landscape, on phones, tablets and foldables, and in multi-window.
   - Handle window insets (system bars, cutouts) so layouts stay edge-to-edge.
2. **No copyrighted audio, ever.**
   - Never add `*.mp3`, `*.ogg` or `*.wav` files, anything under `personal_sounds/`, or recordings in `res/raw`.
   - Sounds are downloaded by the user at runtime, from the catalogue in `core/src/main/assets/sounds/manifest.json` (see `docs/SOUNDS.md`).
   - The production build ships no recordings.
3. **No secrets.** Never commit keystores, `signing/*`, passwords, `play-service-account*.json`, `_sources/` or zips. Don't weaken `.gitignore`.
4. **Network:** go through `Http` (HTTPS only), never on the main thread. Panels do work only between `resume()` and `pause()`.
5. **Code quality.**
   - Write idiomatic Kotlin: `val` over `var`, null-safety instead of `!!` where you can, `object`s for stateless helpers, `internal` for what only one module uses.
   - Keep `@JvmStatic` / `@JvmField` and the names of members the instrumented tests reach by reflection (`AppHooks`, `AppScenario`; see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#kotlin-conventions-in-this-codebase)).
   - Put KDoc on every class and on non-trivial methods.
   - Use meaningful names (some old classes came from a decompiler, so rename leftovers like `i2` and `f3` when you touch them).
   - Use named constants, such as `TypedValue.COMPLEX_UNIT_SP` instead of `2`.
   - Avoid deprecated APIs; guard newer ones with `Build.VERSION`.
   - Don't keep Activity contexts in static fields.
6. **Every interface.** A feature must work in all five interfaces. Follow how `Skin`, `ImperialDraw` and `CollectiveDraw` branch.
7. **Keep behaviour** unless you are fixing a bug. Say which bugs you fixed in the commit message.

## Tickets and commits

- **Ticket log.** Tickets use the prefix **`TUK-T<n>`**, logged at the end of `docs/USER_STORIES.md`. Rows are append-only: change only Status and Ref. `LCA-T<n>` IDs in the history come from the Java edition.
- **Commit messages** end with the ticket ID, for example `… (TUK-T12)`.
- **Staging.** Stage explicit paths (`git add <paths>`), not `git add -A`, because other sessions may have work in progress in the same checkout.
- **History.** Prefer new commits over amending. Don't force-push `main`.

## Where things go

| What | Where |
|---|---|
| Launcher code | `app/src/main/kotlin/com/gungreeneyes/trekui/` |
| Shared code (look, sounds, suite contract, licensing in `…/licensing/`) | `core/src/main/kotlin/com/gungreeneyes/trekui/` |
| Companion code | `messages/`, `keyboard/`, `lockscreen/` `src/main/kotlin/com/gungreeneyes/trekui/…` |
| Bundled shared assets (fonts, glyphs, sound manifest) | `core/src/main/assets/` |
| Launcher assets (badges, quick-panel and app-theme art, wallpapers) | `app/src/main/assets/` |
| Unit tests (`<Class>Test.kt`, same package) | `<module>/src/test/kotlin/…`; launcher: `app/src/test/kotlin/com/gungreeneyes/trekui/` |
| Instrumented scenarios | `app/src/androidTest/kotlin/…` (`*Scenarios`); Gherkin text in `app/src/androidTest/assets/features/*.feature` |
| Generated screenshots, videos and test media | **root `assets/`** (`assets/robolectric/`, `assets/<device>-api<N>/`). Gitignored, published to GitHub Pages by CI |
| Emulator matrix | `scripts/avd/matrix.csv`, `scripts/avd/*.ps1`, `scripts/capture/` |
| Play listing text and images | `fastlane/metadata/<app>/android/en-US/` (`launcher`, `messages`, `keyboard`, `lockscreen`) |
| Release notes per build | `fastlane/metadata/<app>/android/en-US/changelogs/<versionCode>.txt` (written by `scripts/release/ship.*`) |
| Docs | `docs/` |
| Asset generators | `tools/` (Python/Node; output is committed) |

## Useful facts

- **Settings flow:** settings live in `Prefs` (`SharedPreferences("trekui")`). After a change that affects the home, call `Prefs.bump()`, and `MainActivity` rebuilds its UI on the next resume.
- **Testing the trial:** debug builds accept intent extras that force the trial state (`Licensing.EXTRA_DEBUG_*`), which is useful in tests.
- **Phones as test devices:** `adb install -r app/build/outputs/apk/develop/debug/*.apk`, then press Home and choose TrekUI Dev.
- **No network for weather in tests:** the weather panel shows placeholders without network, and that is expected.
