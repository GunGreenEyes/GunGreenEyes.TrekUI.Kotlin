# CLAUDE.md

The project guide for agents is [AGENTS.md](AGENTS.md), imported here so Claude Code loads it:

@AGENTS.md

## In short (if the import above was not loaded)

- **Project.** TrekUI Launcher is an Android home launcher (`com.gungreeneyes.trekui`): pure Java, Canvas-drawn views, no XML layouts, five interfaces (`Skin.faction` 0–2, 9 and 10).
- **Build.**
  - `./gradlew assembleDevelopDebug` builds the app, `testDevelopDebugUnitTest` runs the tests, and `:app:dist` copies every APK and AAB to `dist/`.
  - It needs JDK 21 and SDK 37. Set them up with `scripts/setup.ps1` or `scripts/setup.sh`.
- **Flavors.** `develop` (`.dev`, 5-minute trial) for all dev work; `production` for Play.
- **Sizing.** Size everything with `ScreenMetrics`. Every screen must work in portrait and landscape, from phones to tablets and foldables.
- **Never commit:** copyrighted audio (`*.mp3`, `*.ogg`, `*.wav`), keystores, secrets or `_sources/`.
- **Tickets.** Use the `LCA-T<n>` prefix (log in `docs/USER_STORIES.md`); commit messages start with the ticket ID.
- **Release / "ship it".** Run `scripts/release/ship.sh --track internal` (Windows: `.\scripts\release\ship.ps1 -Track internal`): it bumps to the next whole-number version (42, 43, 44, ...; one number is both versionName and versionCode), writes the changelogs, commits, tags, pushes and starts the release of all four apps, then prints the run URL. Use `--track production` only when the owner explicitly says production ([docs/RELEASE.md](docs/RELEASE.md)).
- **Tests and media.** Unit tests go in `app/src/test/java/com/gungreeneyes/trekui/<Class>Test.java`. Generated screenshots and videos go to the root `assets/` folder, which is gitignored.

## Working from Claude Code on the web, the phone app, or Cowork

- **No Android SDK in the sandbox.** A cloud session usually has no Android SDK or emulator, so don't expect `./gradlew` to work there.
  - To check your work, push a commit; the **Develop** workflow builds the APK and runs the tests.
  - Then read the result with `gh run list --workflow Develop` and `gh run view <id> --log-failed`.
- **More detail** is in [docs/REMOTE_DEVELOPMENT.md](docs/REMOTE_DEVELOPMENT.md).
