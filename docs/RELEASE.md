# Releasing

Two pipelines in `.github/workflows/` build the app.

| | Develop | Release |
|---|---|---|
| Workflow | `develop.yml` (+ `emulator.yml`) | `release.yml` (+ `emulator.yml`, `pages.yml`) |
| Trigger | every push to `main` (except docs-only changes), every PR, manual | a tag `v*` pushed, or manual run |
| Builds | `assembleDevelopDebug` (`com.gungreeneyes.trekui.dev`) | `bundleProductionRelease` + `assembleProductionRelease` (`com.gungreeneyes.trekui`), signed with the upload key |
| Tests | unit + Robolectric + lint; emulator matrix (quick on PRs, full on `main`) | unit tests (develop and production), full emulator matrix |
| Output | artifact `TrekUI-develop-debug-apk` (14 days), test reports, captures | GitHub Release (APK, AAB, mapping, changelog, SHA256SUMS, site-media.zip), Google Play upload, GitHub Pages refresh |

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

### One-time setup

1. **Upload key.**
   - Run the key helper in `scripts/release/` (`create-upload-key.*`). It creates an upload keystore outside the repo and prints the `gh secret set` commands.
   - Set these repository secrets: `TREKUI_KEYSTORE_BASE64`, `TREKUI_STORE_PASSWORD`, `TREKUI_KEY_ALIAS` and `TREKUI_KEY_PASSWORD`.
   - Back the keystore and its passwords up somewhere safe, such as a password manager.
2. **Play service account.**
   - In Google Cloud, create a service account and a JSON key.
   - In Play Console, open **Users and permissions** and invite the service account's email. Give it the permissions *Release to testing tracks* and *Release apps to production* (or only testing), plus *View app information*.
   - Store the JSON as the secret `PLAY_SERVICE_ACCOUNT_JSON`.
   - Create a GitHub environment named `google-play`. You can add required reviewers to it, so every Play upload needs approval.
3. **The first upload to Play must be manual.** Play Console does not accept API uploads for an app that has never had a release. Upload the first AAB (from the GitHub Release or `:app:dist`) by hand to the internal testing track. Until that first release is published, run the workflow manually with `status: draft`.
4. **GitHub Pages.** Open **Settings › Pages** and set the source to **GitHub Actions**.

### Cutting a release

1. **Bump the version** in `version.properties`. `versionCode` must be higher than any build ever uploaded to Play, and `versionName` follows semver:

   ```properties
   versionCode=42
   versionName=6.2.1
   ```

2. **Write the Play release notes** in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, in 500 characters or fewer. They become the GitHub Release body and the Play "What's new" text.
3. **Update `CHANGELOG.md`.**
4. **Commit, then tag with exactly `v<versionName>` and push:**

   ```sh
   git commit -am "LCA-Tnn: Release 6.2.1"
   git tag v6.2.1
   git push origin main v6.2.1
   ```

   The workflow fails if the tag does not match `versionName`.

5. **Let the workflow run:**
   1. **build:** runs the unit tests, builds the signed AAB and APK, and verifies they are not signed with the debug key.
   2. **emulator:** runs the full matrix, recording screenshots and video.
   3. **github:** creates the GitHub Release with `TrekUI-<ver>.apk`, `.aab`, `-mapping.txt`, `CHANGELOG.txt`, `SHA256SUMS.txt` and `site-media.zip`.
   4. **play:** uploads the AAB and mapping to the **internal** track.
   5. **pages:** rebuilds the site with this release's media.
6. **Promote in Play Console.** Go to **Testing › Internal**, open the release and choose **Promote** to closed testing, open testing or **Production**. Promotion is always manual.

### Manual runs

**Actions › Release › Run workflow** takes these options:

- `track`: internal, alpha (closed) or beta (open).
- `status`: completed, or draft (for before the app's first release is published).
- `upload-to-play`: set it off to only build and attach files to a GitHub Release.
- `update-listing`: also pushes the store listing text and screenshots with `fastlane supply`. The screenshots come from this run's emulator captures, via `scripts/release/fill-play-screenshots.py`.

## Play tracks

| Track | Workflow name | Audience | Use |
|---|---|---|---|
| Internal testing | `internal` | up to 100 named testers, available within minutes | every tagged release lands here first |
| Closed testing | `alpha` | tester lists / Google Groups | new personal developer accounts must run a closed test (currently at least 12 testers for 14 continuous days) before they can apply for production access |
| Open testing | `beta` | anyone with the link | public beta |
| Production | – (manual promotion) | everyone | staged rollout recommended (for example 20%, then 100%) |

## Hotfix

1. Fix on `main` and bump `versionCode` and the patch version.
2. Tag and push.
3. After a quick look on the internal track, promote straight to production with a staged rollout.

If a bad build is already in production, **halt the rollout** in Play Console, because a build cannot be pulled back. Then ship the fix with a higher `versionCode`.

## Before every production promotion

- [ ] CHANGELOG, Play "What's new" and `versionName` agree
- [ ] the emulator matrix is green, and the screenshots on the Pages site look right
- [ ] the production APK contains no `res/raw` recordings (the release build never uses `personal_sounds/`)
- [ ] Data safety and permissions declarations still match the manifest ([PLAY_STORE.md](PLAY_STORE.md))
- [ ] the in-app product `trekui_unlock` is active, and a licence-tester purchase works
