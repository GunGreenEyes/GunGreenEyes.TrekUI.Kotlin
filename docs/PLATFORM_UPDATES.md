# Staying current: phones and platform updates

## Which phones

TrekUI works on **every phone from 2016 onward**. Those phones shipped Android 6.0–7.1 or newer. `minSdk` is 23 (Android 6.0), the lowest AndroidX and Play Billing support (TUK-T14 lowered it from 26). Don't raise it without a strong reason. APIs newer than 23 are always guarded with `Build.VERSION.SDK_INT` checks (or `@RequiresApi` helpers) with a fallback for older versions; release lint (`NewApi`, `InlinedApi`) must stay at zero errors. Android 6.0–7.1 show the PNG launcher icons in `res/mipmap-<density>/` (made by `scripts/release/make-legacy-icons.py`); 8.0+ show the adaptive icon. The emulator matrix (`scripts/capture/run-matrix.ps1`) covers phones, a tablet and a foldable, from the oldest supported API to the newest.

### Features that need a newer Android

Everything works on 6.0 and 7.x; these few things are reduced there because the platform has no equivalent:

| Feature | Needs | On older versions |
|---|---|---|
| Adaptive launcher icon | 8.0 (API 26) | The PNG icons in `res/mipmap-<density>/`. |
| Quick Settings tiles (CLOAK, RED ALERT) | 7.0 (API 24) | Not offered on 6.0; the same functions stay on the console. |
| App shortcuts in the drawer's long-press menu | 7.1 (API 25) | Not shown on 6.0 / 7.0 (apps had none then). |
| Separate home and lock wallpapers | 7.0 (API 24) | 6.0 has one wallpaper; APPLY sets it without asking where. |
| Inline reply in a Messages notification | 7.0 (API 24) | On 6.0 REPLY opens the conversation; the notification is an inbox list instead of a conversation. |
| System block list (shared with the dialer) | 7.0 (API 24) | On 6.0 Messages keeps blocked numbers in its own list (as it does prefix rules). |
| Notification channels | 8.0 (API 26) | Priority and default sound/vibration stand in for the channel importance. |
| Variable font weights (Antonio) | 8.0 (API 26) | The font's regular weight, synthetically bolded for heavy text. |
| Widget orphan clean-up | 8.0 (API 26) | Skipped: an abandoned widget id stays allocated, unused. |
| Lock Screen unlock | 8.0 requestDismissKeyguard | FLAG_DISMISS_KEYGUARD: the system bouncer still asks for the PIN, pattern, password or fingerprint. |
| Per-channel badge / lock-screen visibility, app category | 8.0 (API 26) | Every notification may badge; visibility comes from the notification; apps are grouped by name. |

## Upgrades are queued automatically

Nobody has to remember to check for new releases:

| What | Watched by | Result |
|---|---|---|
| AGP, AndroidX, Play Billing, test libraries, GitHub Actions | Dependabot (`.github/dependabot.yml`), weekly | A pull request per group, built and tested by CI |
| A new stable Android API level (compileSdk/targetSdk) | `.github/workflows/platform-watch.yml`, Mondays | An issue labelled `platform-upgrade` with the upgrade checklist |
| A new Gradle release | the same workflow | An issue labelled `platform-upgrade` |
| All of the above, on this machine | `scripts/maintenance/queue-upgrades.ps1`, run weekly by the scheduled task from `register-upgrade-task.ps1` | One ticket per issue or Dependabot PR in the GunGreenEyes ticket database, so the upgrade shows up in the normal work queue |

Each item is queued once; re-running is harmless. To run the watch now: **Actions › Platform watch › Run workflow**, then `scripts/maintenance/queue-upgrades.ps1`.

### Scheduled jobs: the quarterly upgrade

On top of the weekly watch, a Windows scheduled task, **TrekUI quarterly upgrade**, makes sure upgrades actually get done. It runs on the **1st of January, April, July and October at 03:00**, wakes the PC if needed, and runs at the next chance if the PC was off. It runs `scripts/maintenance/quarterly-upgrade.ps1`, which:

1. reads the newest stable releases: Android API level (Google's SDK repository, minor releases such as `android-37.2` count as their API level, betas and canaries don't), Kotlin (Maven Central), AGP (Google Maven) and Gradle (services.gradle.org);
2. reads what TrekUI.Java and TrekUI are on (compileSdk, `agp` in the version catalog, the Gradle wrapper, and for Kotlin sources the Kotlin version: the catalog pin, or the release last checked when the build uses AGP's built-in Kotlin);
3. files one ticket per newer release and repo, with the upgrade steps in its note (filed once; state in `%LOCALAPPDATA%\TrekUI\quarterly-upgrade.json`);
4. opens a CLI in each repo that got tickets (`orchestrator start <repo> --prompt ...`) to implement them on main, with lint and unit tests as the check. When Orchestrator isn't there the tickets wait for `/allocate`.

An edition whose repository is archived on GitHub is skipped, so TrekUI.Java gets no more upgrade tickets once it is archived after release 45, even while its folder stays on disk.

minSdk is never changed by the job: it stays at 23.

```powershell
scripts/maintenance/register-quarterly-task.ps1            # register once per machine, from TrekUI
scripts/maintenance/quarterly-upgrade.ps1 -DryRun          # what would be filed, nothing written
Start-ScheduledTask -TaskName 'TrekUI quarterly upgrade'   # run it now
```

The log is `%LOCALAPPDATA%\TrekUI\quarterly-upgrade.log`.

Both scheduled tasks, **TrekUI quarterly upgrade** and the weekly **TrekUI upgrade queue** (`register-upgrade-task.ps1`), are registered from the TrekUI repository.

## Doing an Android API upgrade

The issue lists the steps: read the behaviour changes, bump `compileSdk`/`targetSdk` in every module, update the SDK packages in the workflows and AVD scripts, add the new system image to the emulator matrix, and ship. Google Play requires apps to target an API level within a year of the newest release (the deadline is each August), so don't let these issues sit.
