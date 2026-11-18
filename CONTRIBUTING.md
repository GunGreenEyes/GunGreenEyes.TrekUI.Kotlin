# Contributing

Thanks for helping with TrekUI Launcher. Contributions of code, art generators, translations of the docs and bug reports are all welcome.

## Getting set up

```sh
git clone https://github.com/GunGreenEyes/TrekUI.Kotlin && cd TrekUI.Kotlin
powershell -ExecutionPolicy Bypass -File scripts\setup.ps1     # Windows
bash scripts/setup.sh                                         # macOS / Linux / Termux
```

The setup script installs JDK 21, the Android SDK and the other tools, then runs a test build. Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) before making larger changes.

No computer? See [docs/REMOTE_DEVELOPMENT.md](docs/REMOTE_DEVELOPMENT.md) for the phone-only workflow.

## Tickets

Work is tracked as tickets with the prefix **`LCA-T<n>`** in the ticket log at the end of [docs/USER_STORIES.md](docs/USER_STORIES.md).

- **Append-only.** Never delete or reword a ticket row. Only change its Status (open → closed / wont-do) and fill in Ref.
- **New tickets** take the next free number.
- **Commit messages** start with the ticket ID, for example `LCA-T12: Fix drawer search in landscape`.

GitHub issues are welcome too. The maintainer turns them into tickets.

## Code conventions

- **Pure Java**, Java 17 language level, with `minSdk 26`. No Kotlin, no XML layouts, no Compose. Views are built in code and draw on a `Canvas`; follow the existing classes.
- **Size through `ScreenMetrics`.** Use `ScreenMetrics.of(context)` for margins, gutters, scale and column counts. Never hard-code screen sizes, column counts or orientation.
  - Every screen must work in portrait and landscape, on phones through tablets, on foldables and in multi-window.
  - Handle window insets (system bars, display cutout) so layouts stay edge-to-edge.
- **Javadoc** on every class (what it is and how it fits) and on every non-trivial method. Use meaningful names and named constants, not magic numbers.
- **Modern APIs.** Avoid deprecated calls where a replacement exists on API 26, using `Build.VERSION` guards.
  - No network or disk I/O on the main thread. Network goes through `Http`, which is HTTPS only.
  - Don't keep `Activity` contexts in static fields.
- **All five interfaces.** A feature must work in every interface. Look at how `Skin`, `ImperialDraw` and `CollectiveDraw` branch.
- **Dependencies.** Add new ones only through `gradle/libs.versions.toml`, and keep them few.

## Tests

- **Unit tests.** Every logic change needs one. Add or extend `app/src/test/java/com/gungreeneyes/trekui/<Class>Test.java`. Put decision logic in pure functions so it can be tested on the JVM.
- **UI behaviour.** Add a Given/When/Then scenario to `app/src/androidTest/assets/features/*.feature` and its matching `*Scenarios` test.
- **Media.** Generated screenshots and videos go to the root `assets/` folder, which is gitignored, and never to `app/src/main/assets/`.
- **Before you open a PR**, run:

  ```sh
  ./gradlew assembleDevelopDebug testDevelopDebugUnitTest lintDevelopDebug
  ```

More detail is in [docs/TESTING.md](docs/TESTING.md).

## Things that must never be committed

- **Copyrighted audio** (`*.mp3`, `*.ogg`, `*.wav`), or anything from the shows. Sounds are downloaded by the user at runtime, from the catalogue in `app/src/main/assets/sounds/manifest.json`. See [docs/SOUNDS.md](docs/SOUNDS.md).
- **Signing material:** keystores, `signing/release.properties`, Play service-account JSON or passwords.
- **Import material and build output:** `_sources/`, zips, `build/` and `dist/`.

`.gitignore` blocks all of these. Do not weaken it.

## Pull requests

1. Branch from `main` (outside contributors fork first), keep the change focused, and reference the ticket.
2. CI must be green:
   - **Develop:** build, unit tests and lint, plus the quick emulator matrix.
   - **CodeQL.**
3. For visual changes, attach before and after screenshots. The CI `capture-*` artifacts are fine.
4. Update the docs when behaviour changes: README, ARCHITECTURE, and CHANGELOG under the next version.

## Licensing of contributions

- **Fonts and third-party assets** must be OFL, Apache 2.0, CC0/CC BY, or the contributor's own work. Add the licence text next to the asset (fonts go in `app/src/main/assets/fonts/`).
- **Original material only.** Do not add third-party logos, insignia, screenshots, recordings, names from other franchises, or traced copies of anyone's artwork. The look must come from original geometry, as it does now.

## Code of conduct

Be kind, assume good faith, and keep discussion about the work.
