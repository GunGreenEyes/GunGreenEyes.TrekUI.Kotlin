---
codex: 1
project: TrekUI.Kotlin
code: TUK
layer: stories
status: living
updated: 2026-11-18
---
<!-- GENERATED from MindAttic.Tickets/data/Tickets.db by `tickets db render`. Do not edit: this file is rewritten from the database; change stories and tickets with the tickets CLI. -->

# TrekUI.Kotlin — User Stories
> ✅ done (shipped & tested) · 🟡 partial · ⬜ planned. Every ✅ cites the test.

## Priority backlog
_Nothing recorded yet._

## Tickets
> Permanent log of every change and fix — this repo's ticket system. Rows are append-only: never
> delete or reword one; only move its Status (open → closed / wont-do) and fill in Ref. Status is
> a word, not a glyph, so tickets don't count toward the story index. Keep this section last.

| ID | Date | Type | Status | Summary | Ref |
|---|---|---|---|---|---|
| TUK-T1 | 2026-11-18 | docs | closed | Start the permanent ticket log in the ticket database | — |
| TUK-T2 | 2026-11-18 | chore | closed | Kotlin edition, phase 0: seed from TrekUI.Java 43, point repo/site/release links at TrekUI.Kotlin, re-enable CI and Pages, write the port plan (docs/KOTLIN_PORT.md) | seeded, links, CI+Pages, plan; final Java merged |
| TUK-T3 | 2026-11-18 | chore | closed | Phase 1 build foundation: Kotlin 2 + Compose plugins, build-logic convention plugins for all five modules, ktlint + detekt in CI; Java code still builds and every test passes | conventions + ktlint/detekt; outputs identical |
| TUK-T4 | 2026-11-18 | feature | closed | Phase 2: convert :core to Kotlin (palettes, sub-themes, skins, drawing helpers, ScreenMetrics, sounds, licensing, suite contract) with its tests ported; no behaviour change | :core 100% Kotlin; :app unit suite green |
| TUK-T5 | 2026-11-18 | feature | closed | Phase 3a: convert :messages to Kotlin with its tests; no behaviour change | :messages 100% Kotlin; 29 unit tests green; lint 0 errors |
| TUK-T6 | 2026-11-18 | feature | closed | Phase 3b: convert :keyboard to Kotlin with its tests; no behaviour change | :keyboard 100% Kotlin; 46 unit tests green; lint 0 errors; no network permission |
| TUK-T7 | 2026-11-18 | feature | closed | Phase 3c: convert :lockscreen to Kotlin with its tests; no behaviour change | :lockscreen 100% Kotlin; 26 unit tests green; lint 0 errors |
| TUK-T8 | 2026-11-18 | feature | closed | Phase 4: convert :app (the launcher, ~28k lines) to Kotlin package by package, unit tests and instrumented scenarios ported; no behaviour change | :app 100% Kotlin incl. unit + instrumented tests; 513 unit tests green; lint 0 errors |
| TUK-T9 | 2026-11-18 | feature | open | Phase 5: Compose console design system in :core (frames, cards, keys, dialogs, sub-themes) and the screens moved to Compose (companions first, then launcher interfaces), guarded by scenarios + Roborazzi screenshots | docs/KOTLIN_PORT.md |
| TUK-T10 | 2026-11-18 | feature | open | Phase 6: settings on DataStore with a one-time migration from the Java edition's trekui SharedPreferences, plus an upgrade test (Java build data -> Kotlin build) | docs/KOTLIN_PORT.md |
| TUK-T11 | 2026-11-18 | chore | closed | Phase 7 parity + release: full unit suite, CI matrix, local 24-device matrix and device report, docs rewritten for Kotlin, shipped as the next whole-number version over the Java edition | 27/27 local devices + 11/11 CI on both editions; device report TrekUI-46-Complete-Report |
| TUK-T12 | 2026-11-18 | docs | closed | Docs describe the Kotlin codebase (README, ARCHITECTURE, BUILD, TESTING, CONTRIBUTING, agent guides, port status) | TUK-T4..T8 done |
| TUK-T13 | 2026-11-18 | fix | closed | Sound tooling and comments read the Kotlin sources (Sfx.kt, SourceSoundLibrary.kt, Stations.kt, BootSequence.kt); output identical to the Java-era tools | equivalence diff |
| TUK-T14 | 2026-11-18 | feature | closed | Support every Android phone from 2016 on: minSdk 26 -> 23 in the Kotlin edition (mirror of the Java change), with version guards and Android 6/7/7.1 matrix devices | 27/27 local devices (Android 6.0-17, 837/837 runs) + CI 11/11, 2026-11-19 |
| TUK-T15 | 2026-11-18 | chore | closed | Quarterly platform job (copy of TrekUI.Java LCA-T66): scheduled task on the 1st of Jan/Apr/Jul/Oct 03:00 checks Android/Kotlin/AGP/Gradle, files upgrade tickets and starts a CLI | register-quarterly-task.ps1; dry-run verified |
| TUK-T16 | 2026-11-18 | fix | closed | Port of the Android 6.0 SIGSEGV fix: read ApplicationInfo.category (API 26) through a nested API-26 object so ART on API 23 never compiles the field access inline | 27/27 local devices (Android 6.0-17, 837/837 runs) + CI 11/11, 2026-11-19 |
| TUK-T17 | 2026-11-18 | chore | closed | Take over from TrekUI.Java: scheduled upgrade tasks run from this repo and skip archived editions, first Kotlin release (45) with full device verification and report | TrekUI.Java archived 2026-11-19; tasks, Pages, signing, backlog here |
| TUK-T18 | 2026-11-18 | fix | closed | CI foldable (API 36) loses its emulator on the COLLECTIVE drawer screens under SwiftShader (both editions; Kotlin 4 attempts in a row): run the foldable on the swangle_indirect renderer | 27/27 local devices (Android 6.0-17, 837/837 runs) + CI 11/11, 2026-11-19 |
| TUK-T19 | 2026-11-18 | chore | closed | Release 45: docs brought up to date, every test green (unit, lint, 27 local emulator devices, CI), APKs published as GitHub release 45, and dist/ committed with one folder per version so earlier APKs stay downloadable | v45 published, dist/45 committed, docs current, every test green |
| TUK-T20 | 2026-11-18 | chore | closed | Product text says Federation, Empire and Collective, never Starfleet, Klingon or Borg: rename the sound-library ids and titles (alt_borg_computer, alt_klingon_transporter, the Starfleet priority message, the Klingon disruptor), comments, docs and the sound tools, carrying downloaded files and saved sound choices over to the new ids | SoundIdMigrationTest.cachedClipsUnderOldIdsAreRenamed |
| TUK-T21 | 2026-11-18 | feature | closed | Sound update channel on mindattic.com: the app checks https://www.mindattic.com/gungreeneyes/trekui/sfx/manifest.json first (checksum-verified, cached, falls back to the built-in sounds), so owned sounds can be added or fixed without an app update; a publish script uploads the owned sounds and the manifest over FTPS. Show recordings stay on their original sources | SoundUpdateTest.theLiveWwwRedirectToTheBareHostIsFollowed |
| TUK-T22 | 2026-11-18 | chore | closed | Google Play readiness: signed AAB targeting API 36+, upload keystore created and backed up, 512 px icon, 1024x500 feature graphic, phone screenshots, short (<=80) and full (<=4000) descriptions, public privacy-policy page | upload key + CI secrets, store art and screenshots for all four listings, AAB targets API 37, privacy page live |
| TUK-T23 | 2026-11-18 | chore | closed | The franchise name and the console-style trademark word appear nowhere in the source: drop the three third-party sound sources whose URLs contain them (alternates only), update the sound tools and docs, reword old ticket titles | git grep finds neither word |
| TUK-T24 | 2026-11-19 | chore | closed | Free trial lasts 12 hours (720 minutes) in every build: was 24 hours in production (Play) and 5 minutes in the develop test builds | BuildConfig.TRIAL_SESSION_MINUTES = 720 in develop and production; unit tests green |
| TUK-T25 | 2026-11-19 | feature | open | Investigate and build a web-browser version of the launcher | carried over from LCA-T13 (TrekUI.Java archived 2026-11-19) |
| TUK-T26 | 2026-11-19 | feature | open | Competitive parity: match and exceed the leading console-style launcher themes (incl. the NSTEnterprises Total Launcher theme); feature matrix + gap tickets | carried over from LCA-T14 (TrekUI.Java archived 2026-11-19) |
| TUK-T27 | 2026-11-19 | feature | open | A5+B2: interface/text size settings and TalkBack accessibility for Canvas-drawn UI | carried over from LCA-T18 (TrekUI.Java archived 2026-11-19) |
| TUK-T28 | 2026-11-19 | feature | open | Wave 2: widgets for other launchers, screensaver, auto alert conditions, translations/locale dates, voice commands, Captain's Log, foldable tabletop mode, Wear OS face, Android TV, wallpaper library | carried over from LCA-T24 (TrekUI.Java archived 2026-11-19) |
| TUK-T29 | 2026-11-19 | feature | open | Competitor-review requirements: cover and exceed every TREK Total Interface review theme (checklist in session scratchpad REVIEW_CHECKLIST.md) | carried over from LCA-T27 (TrekUI.Java archived 2026-11-19) |
| TUK-T30 | 2026-11-19 | feature | open | Reviews A/B/C/E/F/G: default-home watchdog + return-to-standard, smart default app mapping (no dead buttons), layout lock, instant undo, in-app help, weather units/12-24h/locale dates, notification-access status, sound volume/stream/mute/DND | carried over from LCA-T28 (TrekUI.Java archived 2026-11-19) |
| TUK-T31 | 2026-11-19 | feature | open | Reviews H/I: foldable posture, lock/home wallpaper generator + screensaver, voice commands, sensors + image panels, animation level, viewscreen launch, stardate formats, translations (de/fr/es), wallpaper library, ambient soundscape | carried over from LCA-T29 (TrekUI.Java archived 2026-11-19) |
| TUK-T32 | 2026-11-19 | feature | open | Suite follow-ups from LCA-T35: Quick Settings tiles + home-screen widget to switch Keyboard/Lock Screen/Messages on and off; app-theme and Quick Panel art for the six new COMMAND sub-themes (exports reuse the nearest original art today) | carried over from LCA-T39 (TrekUI.Java archived 2026-11-19) |
| TUK-T33 | 2026-11-19 | fix | closed | Messages: tapping or long-pressing a conversation row does nothing, so threads can't be opened from the list (found while capturing Play screenshots on Android 16) | Android 16 emulator: tap opens ThreadActivity, long-press shows BLOCK/DELETE/MARK READ |
| TUK-T34 | 2026-11-19 | chore | closed | Release 46: 12-hour trial in every build, Messages conversation rows open again, Play screenshots for all four apps; GitHub release, dist/46 | v46 published (15 assets), dist/46 committed |
