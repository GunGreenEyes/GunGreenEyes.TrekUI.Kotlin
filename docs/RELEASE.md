# Releasing

Two pipelines in `.github/workflows/` build the TrekUI suite: the launcher (`:app`) and the companion apps TrekUI Messages (`:messages`), TrekUI Keyboard (`:keyboard`) and TrekUI Lock Screen (`:lockscreen`).

| | Develop | Release |
|---|---|---|
| Workflow | `develop.yml` (+ `emulator.yml`) | `release.yml` (+ `emulator.yml`, `pages.yml`) |
| Trigger | every push to `main` (except docs-only changes), every PR, manual | `scripts/release/ship.*`, or **Run workflow** (also from the GitHub mobile app) |
| Builds | `assembleDevelopDebug` (`*.dev` packages) | `bundleProductionRelease` + `assembleProductionRelease` for all four apps, signed with the one upload key |
| Tests | unit + Robolectric + lint; emulator matrix (quick on PRs, full on `main`) | unit tests (develop and production), full emulator matrix |
| Output | artifact `TrekUI-develop-debug-apk` (14 days), test reports, captures | one GitHub Release (every app's APK, AAB and mapping, changelog, SHA256SUMS, site-media.zip), Google Play upload of every app, GitHub Pages refresh |

Other workflows:

- **`codeql.yml`:** static analysis of the Java code, run on pushes, PRs and weekly.
- **`pages.yml`:** rebuilds the product site (`site/`) when the site, `PRIVACY.md` or the listing changes.
- **Dependabot:** opens PRs for version bumps.

## Develop pipeline

Every push to `main` produces an installable APK:

1. Open the **Actions** tab, then the **Develop** workflow, then the newest run.
2. Under **Artifacts**, download `TrekUI-develop-debug-apk` (a zip containing the APK).
3. Install it on any device. It installs as **TrekUI Dev** next to the store build.

This is what makes phone-only development possible ([REMOTE_DEVELOPMENT.md](REMOTE_DEVELOPMENT.md)). Pushes that change only Markdown or `docs/` skip the build.

## Release pipeline

**In short:** `scripts/release/ship.ps1` (Windows) or `scripts/release/ship.sh` ships all four apps to the Play **internal** track in one command. For agents, "release" or "ship it" means exactly that; the production track only when the owner explicitly says so.

### The apps

All four apps share one `versionCode` / `versionName` (`version.properties`) and one upload key, and ship together in every run.

| App | Module | Package | Release files | Listing and notes |
|---|---|---|---|---|
| TrekUI Launcher | `:app` | `com.gungreeneyes.trekui` | `TrekUI-<ver>.apk` / `.aab` / `-mapping.txt` | `fastlane/metadata/launcher/android/en-US/` |
| TrekUI Messages | `:messages` | `com.gungreeneyes.trekui.messages` | `TrekUI-Messages-<ver>.*` | `fastlane/metadata/messages/android/en-US/` |
| TrekUI Keyboard | `:keyboard` | `com.gungreeneyes.trekui.keyboard` | `TrekUI-Keyboard-<ver>.*` | `fastlane/metadata/keyboard/android/en-US/` |
| TrekUI Lock Screen | `:lockscreen` | `com.gungreeneyes.trekui.lockscreen` | `TrekUI-LockScreen-<ver>.*` | `fastlane/metadata/lockscreen/android/en-US/` |

The four apps must be signed with the **same** key: the suite permission is signature-level, so a sibling signed differently can't talk to the others. The build job fails if any APK's certificate differs from the launcher's, or is the debug key.

### One-time setup

**Shortcut:** `.\scripts\release\setup-owner.ps1` handles steps 1–3 below, plus GitHub Pages and the weekly upgrade queue, and is safe to re-run. It finishes by printing the Play Console checklist. Run it again with `-ServiceAccountJson <file>` once the Play service account exists. The steps below are the manual equivalent.

1. **Upload key and secrets.**
   - Run the key helper in `scripts/release/` (`create-upload-key.*`). It creates an upload keystore outside the repo and prints the `gh secret set` commands.
   - Set these as **repository** secrets (Settings › Secrets and variables › Actions › Repository secrets), not environment secrets, so every job and both Play environments can read them:

     | Secret | What |
     |---|---|
     | `TREKUI_KEYSTORE_BASE64` | the upload keystore, base64 |
     | `TREKUI_STORE_PASSWORD` | keystore password |
     | `TREKUI_KEY_ALIAS` | key alias |
     | `TREKUI_KEY_PASSWORD` | key password |
     | `PLAY_SERVICE_ACCOUNT_JSON` | the Play service account's JSON key |

   - Back the keystore and its passwords up somewhere safe, such as a password manager.
   - Locally the same values come from `signing/release.properties` (or the `TREKUI_*` environment variables). Without them a release build falls back to the debug key, so local builds still work; CI refuses such a build.
2. **Play service account.**
   - In Google Cloud, create a service account and a JSON key, and store the JSON as `PLAY_SERVICE_ACCOUNT_JSON`.
   - In Play Console, open **Users and permissions**, invite the service account's email and give it access to **all four apps**: *Release to testing tracks*, *Release apps to production* and *View app information* (plus *Edit store listing* for `update-listing`).
3. **The `google-play` environment (the production approval gate).** You have to create it yourself:
   1. Settings › Environments › **New environment**, name it `google-play`.
   2. Tick **Required reviewers** and add yourself. Save.
   3. Optionally, under **Deployment branches and tags**, allow only `main` and `v*` tags.

   Every production upload and every listing update then waits until you approve it, from the run page or the GitHub mobile app. Testing-track uploads run in `google-play-testing`, which GitHub creates on first use without reviewers, so internal releases never wait.
4. **Create each app in Play Console and upload its first release by hand.** The Play API can't create an app or its first release. For each of the four packages:
   1. create the app in Play Console and fill in the required App content pages (privacy policy, data safety, ads, content rating, target audience; Messages also needs the SMS permissions declaration, see [MESSAGES.md](MESSAGES.md));
   2. upload the first AAB (from a GitHub Release, or a run's `release-binaries` artifact) to **Internal testing** by hand.

   Until an app's first release is published, its leg of the `play` job fails while the other apps still upload; run with `status: draft` if you want the upload to wait as a draft.
5. **New personal developer accounts: 12 testers for 14 days.** A personal Play developer account created after November 2023 must run a closed test (`alpha`) with at least 12 opted-in testers for 14 continuous days before it can apply for production access, separately for each app. Start the closed tests early and keep the same testers opted in on all four apps.
6. **GitHub Pages.** Open **Settings › Pages** and set the source to **GitHub Actions**.

### Shipping a release (one command)

```powershell
.\scripts\release\ship.ps1                    # patch bump, internal track
.\scripts\release\ship.ps1 minor -Track beta
.\scripts\release\ship.ps1 -DryRun            # show the version, notes and commands; change nothing
```

```sh
scripts/release/ship.sh                       # patch bump, internal track
scripts/release/ship.sh minor --track beta
scripts/release/ship.sh patch --track production --rollout 0.1
scripts/release/ship.sh --dry-run
```

The ship script, in order:

1. **Bumps** `major`, `minor` or `patch` (default `patch`) through `bump-version.*`: `versionCode` + 1 and the new `versionName` in `version.properties`, and a row in `fastlane/release-history.json`. `versionCode` must be higher than any build ever uploaded to Play; `versionName` follows semver.
2. **Writes the changelog** from the commit subjects since the last `v*` tag:
   - a new section at the top of `CHANGELOG.md` (full subjects, ticket IDs kept);
   - a Play "What's new" per app in `fastlane/metadata/<app>/android/en-US/changelogs/<versionCode>.txt`, 500 characters at most, from the commits that touched that app or `:core`. Ticket IDs and maintenance commits (docs, logs, tests) are left out. Check them with `--dry-run` first; you can still edit them in Play Console afterwards.
3. **Commits** only those files (explicit paths, so other work in the checkout is left alone), **tags** `v<versionName>` and **pushes** `main` and the tag together (`--atomic`).
4. **Starts** `release.yml` on the tag with `gh workflow run` and the chosen track, and prints the Actions run URL.

Options (bash / PowerShell):

| Option | Default | Use |
|---|---|---|
| `major` / `minor` / `patch` | `patch` | the version bump |
| `--track` / `-Track` | `internal` | `internal`, `alpha`, `beta` or `production` |
| `--rollout` / `-Rollout` | `0.1` | production rollout fraction |
| `--status` / `-Status` | `completed` | `draft` until an app's first release is published |
| `--no-play` / `-NoPlay` | | build and create the GitHub Release only |
| `--update-listing` / `-UpdateListing` | | also push every app's store listing |
| `--ticket ID` / `-Ticket ID` | | prefix for the release commit message |
| `--no-dispatch` / `-NoDispatch` | | stop after the push |
| `--dry-run` / `-DryRun` | | print the plan; write, commit and push nothing |

The script needs the GitHub CLI signed in (`gh auth login`), a clean `version.properties` / `CHANGELOG.md` / `fastlane/`, and `main` up to date with `origin`.

A tag push on its own does not start a release; the ship script (or **Run workflow**) does, so every release has an explicit track.

### From the phone

GitHub mobile app › the repository › **Actions** › **Release** › **Run workflow**:

1. Leave the branch on **main** and set `bump` to `patch` (or `minor` / `major`). The `prepare` job runs `ship.sh --no-dispatch` on the runner (bump, changelogs, commit, tag, push), and the same run builds and uploads that commit.
2. Pick the `track` (and, for production, the `rollout`), then **Run workflow**.
3. For production, approve the `google-play` deployment from the run page when it asks.

`bump` = `none` re-runs the current `version.properties`. That is only useful if that `versionCode` was never uploaded, because Play refuses a reused one.

From Claude Code on the phone or the web, "ship it" runs the ship script instead.

### What the workflow does

1. **prepare:** the optional on-runner bump described above.
2. **build:** checks that a tag ref matches `versionName`, runs every module's unit tests, builds every app's signed production AAB and APK, verifies all four are signed with the same non-debug key, and collects `TrekUI*-<ver>.apk` / `.aab` / `-mapping.txt` plus each app's "What's new".
3. **emulator:** the full launcher matrix, recording screenshots and video.
4. **github:** one GitHub Release `v<ver>` with every app's APK, AAB and mapping file, `CHANGELOG.txt` (all four apps' notes), `SHA256SUMS.txt` and `site-media.zip`.
5. **play:** one matrix leg per app (a failing app doesn't stop the others) uploads the AAB, mapping and "What's new" to the chosen track. Production runs in the `google-play` environment and waits for your approval.
6. **listing** (when `update-listing` is on): pushes each app's listing text and images with `fastlane supply`. The launcher's screenshots come from this run's emulator captures.
7. **pages:** rebuilds the site with this release's media.

### Workflow inputs

| Input | Values | Default | Use |
|---|---|---|---|
| `track` | `internal`, `alpha` (closed), `beta` (open), `production` | `internal` | Play track for all four apps |
| `rollout` | a fraction, e.g. `0.1` | `0.1` | production only: staged rollout (`1` = everyone at once) |
| `status` | `completed`, `draft` | `completed` | `draft` until an app's first release is published |
| `upload-to-play` | on / off | on | off: build and create the GitHub Release only |
| `update-listing` | on / off | off | also push every app's store listing |
| `bump` | `none`, `patch`, `minor`, `major` | `none` | phone-only releases: bump on the runner first |

Raise or halt a staged production rollout in Play Console (Release › Production › Manage rollout), or promote a tested internal or closed release there instead of uploading to production directly.

## Play tracks

| Track | Workflow name | Audience | Use |
|---|---|---|---|
| Internal testing | `internal` | up to 100 named testers, available within minutes | every release lands here first |
| Closed testing | `alpha` | tester lists / Google Groups | new personal developer accounts must run a closed test (at least 12 testers for 14 continuous days) before they can apply for production access |
| Open testing | `beta` | anyone with the link | public beta |
| Production | `production` (approval in `google-play`) | everyone | staged rollout (for example 10%, then 100%), or promote a tested release in Play Console |

## Hotfix

1. Fix on `main`, then ship (patch bump, internal track).
2. After a quick look on the internal track, promote it in Play Console, or ship again with `--track production --rollout 0.2`.

If a bad build is already in production, **halt the rollout** in Play Console, because a build cannot be pulled back. Then ship the fix with a higher `versionCode`.

## Before every production promotion

- [ ] CHANGELOG, Play "What's new" and `versionName` agree
- [ ] the emulator matrix is green, and the screenshots on the Pages site look right
- [ ] the production APK contains no `res/raw` recordings (the release build never uses `personal_sounds/`)
- [ ] Data safety and permissions declarations still match each app's manifest ([PLAY_STORE.md](PLAY_STORE.md))
- [ ] the in-app product `trekui_unlock` is active, and a licence-tester purchase works
