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
| TUK-T11 | 2026-11-18 | chore | open | Phase 7 parity + release: full unit suite, CI matrix, local 24-device matrix and device report, docs rewritten for Kotlin, shipped as the next whole-number version over the Java edition | docs/KOTLIN_PORT.md |
| TUK-T12 | 2026-11-18 | docs | closed | Docs describe the Kotlin codebase (README, ARCHITECTURE, BUILD, TESTING, CONTRIBUTING, agent guides, port status) | TUK-T4..T8 done |
| TUK-T13 | 2026-11-18 | fix | closed | Sound tooling and comments read the Kotlin sources (Sfx.kt, SourceSoundLibrary.kt, Stations.kt, BootSequence.kt); output identical to the Java-era tools | equivalence diff |
| TUK-T14 | 2026-11-18 | feature | closed | Support every Android phone from 2016 on: minSdk 26 -> 23 in the Kotlin edition (mirror of the Java change), with version guards and Android 6/7/7.1 matrix devices | 27/27 local devices (Android 6.0-17, 837/837 runs) + CI 11/11, 2026-11-19 |
| TUK-T15 | 2026-11-18 | chore | closed | Quarterly platform job (copy of TrekUI.Java LCA-T66): scheduled task on the 1st of Jan/Apr/Jul/Oct 03:00 checks Android/Kotlin/AGP/Gradle, files upgrade tickets and starts a CLI | register-quarterly-task.ps1; dry-run verified |
| TUK-T16 | 2026-11-18 | fix | closed | Port of the Android 6.0 SIGSEGV fix: read ApplicationInfo.category (API 26) through a nested API-26 object so ART on API 23 never compiles the field access inline | 27/27 local devices (Android 6.0-17, 837/837 runs) + CI 11/11, 2026-11-19 |
| TUK-T17 | 2026-11-18 | chore | open | Take over from TrekUI.Java: scheduled upgrade tasks run from this repo and skip archived editions, first Kotlin release (45) with full device verification and report | LCA-T68 |
| TUK-T18 | 2026-11-18 | fix | closed | CI foldable (API 36) loses its emulator on the COLLECTIVE drawer screens under SwiftShader (both editions; Kotlin 4 attempts in a row): run the foldable on the swangle_indirect renderer | 27/27 local devices (Android 6.0-17, 837/837 runs) + CI 11/11, 2026-11-19 |
| TUK-T19 | 2026-11-18 | chore | open | Release 45: docs brought up to date, every test green (unit, lint, 27 local emulator devices, CI), APKs published as GitHub release 45, and dist/ committed with one folder per version so earlier APKs stay downloadable | owner 2026-11-18 |
| TUK-T20 | 2026-11-18 | chore | closed | Product text says Federation, Empire and Collective, never Starfleet, Klingon or Borg: rename the sound-library ids and titles (alt_borg_computer, alt_klingon_transporter, the Starfleet priority message, the Klingon disruptor), comments, docs and the sound tools, carrying downloaded files and saved sound choices over to the new ids | SoundIdMigrationTest.cachedClipsUnderOldIdsAreRenamed |
| TUK-T21 | 2026-11-18 | feature | closed | Sound update channel on mindattic.com: the app checks https://www.mindattic.com/gungreeneyes/trekui/sfx/manifest.json first (checksum-verified, cached, falls back to the built-in sounds), so owned sounds can be added or fixed without an app update; a publish script uploads the owned sounds and the manifest over FTPS. Show recordings stay on their original sources | SoundUpdateTest.theLiveWwwRedirectToTheBareHostIsFollowed |
| TUK-T22 | 2026-11-18 | chore | open | Google Play readiness: signed AAB targeting API 36+, upload keystore created and backed up, 512 px icon, 1024x500 feature graphic, phone screenshots, short (<=80) and full (<=4000) descriptions, public privacy-policy page | owner 2026-11-18 Play checklist |
| TUK-T23 | 2026-11-18 | chore | closed | The franchise name and the console-style trademark word appear nowhere in the source: drop the three third-party sound sources whose URLs contain them (alternates only), update the sound tools and docs, reword old ticket titles | git grep finds neither word |
| TUK-T24 | 2026-11-19 | chore | closed | Free trial lasts 12 hours (720 minutes) in every build: was 24 hours in production (Play) and 5 minutes in the develop test builds | BuildConfig.TRIAL_SESSION_MINUTES = 720 in develop and production; unit tests green |
