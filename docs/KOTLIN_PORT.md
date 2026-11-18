# TrekUI Kotlin edition: port plan

TrekUI.Kotlin rebuilds the TrekUI suite (Launcher, Messages, Keyboard, Lock Screen and the shared `:core`)
in modern Kotlin and Jetpack Compose, starting from the final TrekUI.Java release (44: the five starship
interfaces only, the Pipe Lounge interfaces removed). When it reaches parity it
replaces the Java edition on Google Play as an ordinary update: **same application IDs, same signing key,
same `trekui_unlock` product**, so users keep their settings and purchase.

## Status

The Java-to-Kotlin conversion is done: every source, unit test and instrumented scenario in all five modules
is Kotlin, and no Java sources remain. Behaviour, application IDs, signing, versioning, CI and the device
matrices are unchanged from the Java edition.

| Phase | State | Tickets |
|---|---|---|
| 0. Seed | done (TrekUI.Java 43 copied, links and CI/Pages pointed here, the final TrekUI.Java `main` merged in) | TUK-T2 |
| 1. Build foundation (`build-logic`, ktlint, detekt, Compose plugin) | **not started; next** | TUK-T3 |
| 2. `:core` to Kotlin | done | TUK-T4 |
| 3. Companions to Kotlin (`:messages`, `:keyboard`, `:lockscreen`) | done | TUK-T5, TUK-T6, TUK-T7 |
| 4. `:app` to Kotlin, unit tests and instrumented scenarios included | done | TUK-T8 |
| 5. Compose | not started | TUK-T9 |
| 6. Settings on DataStore, with the upgrade test | not started | TUK-T10 |
| 7. Parity and release | in progress: version 44 merged from Java; 614 unit tests and CI 9/9 pass; `v44` test pre-release with APKs; the local 24-device matrix is running | TUK-T11 |

Next: phase 1 (the build foundation), then phase 5 (Compose), phase 6 (DataStore) and phase 7 (parity and
release, shipped as the next whole-number version, 44 or later, over the Java edition).

## The bar (definition of done)

- Every feature of the final TrekUI.Java release works the same or better: the five starship interfaces
  (COMMAND, IMPERIAL, COLLECTIVE, CLASSIC CONSOLE, NEXT CONSOLE) with their sub-themes, and the three companions. Nothing is dropped silently.
- **Every phone from 2022 onward**, every resolution and aspect ratio: `minSdk 26`, latest `targetSdk`,
  phones, tall phones, foldables (inner and outer), tablets in both orientations, large font and display size.
- **The same level of testing or higher**: every Java unit test has a Kotlin equivalent; the instrumented
  scenarios (`app/src/androidTest`) keep passing; the CI 9-emulator matrix and the local 24-device matrix
  (`scripts/avd/matrix.csv`) both pass; release lint has zero errors.
- **The same level of documentation or higher**: README, ARCHITECTURE, BUILD, TESTING, RELEASE, PLAY_STORE,
  PLATFORM_UPDATES and the per-app docs describe the Kotlin code as it is.
- Users upgrading from the Java edition keep their settings (`trekui` preferences) and their unlock.

## Technology

| Area | Choice |
|---|---|
| Language | Kotlin 2 (K2 compiler), JVM target 17, explicit API mode in `:core` |
| Concurrency | Kotlin coroutines and Flow (replacing Handler/Thread/Executor code) |
| UI | Jetpack Compose (BOM-pinned). The console look is drawn with Compose `Canvas`/`DrawScope`, with the palettes and sub-themes as a Compose theme in `:core`. No Material look. |
| Settings | AndroidX DataStore, with a one-time migration from the existing `trekui` SharedPreferences |
| Serialization | kotlinx.serialization (layouts, snapshots, sound manifest) |
| Build | Gradle Kotlin DSL, version catalog, convention plugins in `build-logic/` shared by all five modules |
| Quality | ktlint + detekt in CI; R8 full mode; baseline profiles for the launcher |
| Tests | JUnit4 + Truth (as today), kotlinx-coroutines-test, Robolectric, Compose UI tests, the existing UiAutomator scenarios, Roborazzi screenshot tests |

## Phases

Each phase is one or more tickets (code `TUK`). CI stays green after every commit. During the
Java-to-Kotlin phases behaviour does not change; Kotlin and Java coexist in a module while it converts.

0. **Seed** (TUK-T2): copy of TrekUI.Java 43, links point here, CI and Pages on, this plan. **Before phase 1,
   merge the final TrekUI.Java `main` into this repo** (it adds the Pipe Lounge removal, LCA-T58, and the test
   fixes from the device matrix), resolving only the link and ticket-view files this repo changed.
1. **Build foundation**: Kotlin and Compose plugins, `build-logic` convention plugins, ktlint/detekt, CI updated.
2. **`:core` to Kotlin**: palettes, skins, drawing helpers, sounds, licensing and the suite contract; tests ported.
3. **Companions to Kotlin** (in parallel): `:messages`, `:keyboard`, `:lockscreen`, with their tests.
4. **`:app` to Kotlin**: the launcher (about 28,000 lines), converted package by package.
5. **Compose**: the console design system in `:core` as composables (frames, cards, keys, dialogs), then
   the companion screens, then the launcher's screens, interface by interface, with the scenarios and
   screenshot tests guarding each one. Keyboard keys and the wake screen stay single-canvas for speed.
6. **Settings on DataStore** with migration from SharedPreferences, plus an upgrade test that installs the
   Java edition's data and opens the Kotlin edition.
7. **Parity and release**: full unit suite, CI matrix, the local 24-device matrix and the device test report;
   docs rewritten for the Kotlin code; shipped as the next whole-number version over the Java edition.
8. **After parity**: the browser demo with Compose Multiplatform (wasm), and the Kotlin-only items moved from
   the Java edition (LCA-T12/T13/T14/T18/T24/T27/T28/T29/T39).

## Findings from the first conversion

- AGP 9 compiles Kotlin with its built-in Kotlin support: no `org.jetbrains.kotlin.android` plugin is applied
  (it would conflict). Kotlin sources live in `src/<set>/kotlin`, next to the Java ones in `src/<set>/java`.
- The Java code shares the package `com.gungreeneyes.trekui` across `:core` and `:app` and relies on
  package-private access between them (and from `:app` tests into `:core`). Kotlin has no package-private
  visibility and `internal` is per module, so converted declarations that other modules or their tests use
  are `public`. Tighten them to `internal` only once every user is in the same module.
- Constants in an interface become `companion object` `const val`s; Java callers keep `TimeSource.UNKNOWN_BOOT`.

## Rules

- One ticket per change, commit message ends with its ID, straight to `main`.
- Port the tests together with the code they cover; never delete a test to get green.
- No behaviour change while converting (phases 2–4). Behaviour changes come only with Compose (phase 5) and
  must keep the scenarios and screenshots passing.
- Tests run in CI on every push; the local emulator matrix runs when the owner asks.
