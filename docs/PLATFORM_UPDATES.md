# Staying current: phones and platform updates

## Which phones

TrekUI works on **every phone from 2016 onward**. Those phones shipped Android 6.0–7.1 or newer. `minSdk` is 23 (Android 6.0), the lowest AndroidX and Play Billing support (TUK-T14 lowered it from 26). Don't raise it without a strong reason. APIs newer than 23 are always guarded with `Build.VERSION.SDK_INT` checks (or `@RequiresApi` helpers) with a fallback for older versions; release lint (`NewApi`, `InlinedApi`) must stay at zero errors. Android 6.0–7.1 show the PNG launcher icons in `res/mipmap-<density>/` (made by `scripts/release/make-legacy-icons.py`); 8.0+ show the adaptive icon. The emulator matrix (`scripts/capture/run-matrix.ps1`) covers phones, a tablet and a foldable, from the oldest supported API to the newest.

## Upgrades are queued automatically

Nobody has to remember to check for new releases:

| What | Watched by | Result |
|---|---|---|
| AGP, AndroidX, Play Billing, test libraries, GitHub Actions | Dependabot (`.github/dependabot.yml`), weekly | A pull request per group, built and tested by CI |
| A new stable Android API level (compileSdk/targetSdk) | `.github/workflows/platform-watch.yml`, Mondays | An issue labelled `platform-upgrade` with the upgrade checklist |
| A new Gradle release | the same workflow | An issue labelled `platform-upgrade` |
| All of the above, on this machine | `scripts/maintenance/queue-upgrades.ps1`, run weekly by the scheduled task from `register-upgrade-task.ps1` | One ticket per issue or Dependabot PR in the GunGreenEyes ticket database, so the upgrade shows up in the normal work queue |

Each item is queued once; re-running is harmless. To run the watch now: **Actions › Platform watch › Run workflow**, then `scripts/maintenance/queue-upgrades.ps1`.

## Doing an Android API upgrade

The issue lists the steps: read the behaviour changes, bump `compileSdk`/`targetSdk` in every module, update the SDK packages in the workflows and AVD scripts, add the new system image to the emulator matrix, and ship. Google Play requires apps to target an API level within a year of the newest release (the deadline is each August), so don't let these issues sit.
