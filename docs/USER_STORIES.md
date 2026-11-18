---
codex: 1
project: TrekUI.Java
code: LCA
layer: stories
status: living
updated: 2026-11-17
---
<!-- GENERATED from MindAttic.Tickets/data/Tickets.db by `tickets db render`. Do not edit: this file is rewritten from the database; change stories and tickets with the tickets CLI. -->

# TrekUI.Java — User Stories
> ✅ done (shipped & tested) · 🟡 partial · ⬜ planned. Every ✅ cites the test.

## Priority backlog
_Nothing recorded yet._

## Tickets
> Permanent log of every change and fix — this repo's ticket system. Rows are append-only: never
> delete or reword one; only move its Status (open → closed / wont-do) and fill in Ref. Status is
> a word, not a glyph, so tickets don't count toward the story index. Keep this section last.

| ID | Date | Type | Status | Summary | Ref |
|---|---|---|---|---|---|
| LCA-T1 | 2026-11-17 | docs | closed | Start the permanent ticket log in USER_STORIES.md | — |
| LCA-T2 | 2026-11-17 | chore | open | Toolchain: Android SDK, system images API 26-36 phone/tablet/foldable, Gradle wrapper, AVD matrix | — |
| LCA-T3 | 2026-11-17 | feature | open | Modern Gradle project: merge 5.2 source + 6.1 Pipe overlay; KTS, version catalog, latest AGP, targetSdk 36, Java 17, develop/production flavors, APK/AAB to dist/ | — |
| LCA-T4 | 2026-11-17 | chore | closed | Code cleanup and comments: fix decompiler artifacts, modernize APIs, document every class/method | — |
| LCA-T5 | 2026-11-17 | feature | open | Responsive layout: sizes/padding/gutters from window size, density, aspect ratio, cutouts, foldables; edge-to-edge; phone to tablet | — |
| LCA-T6 | 2026-11-17 | feature | closed | Tests: unit tests for logic; instrumented tests, screenshots and video across emulator matrix (API 26-36, phone/tablet/foldable, resolutions, aspect ratios) | — |
| LCA-T7 | 2026-11-17 | feature | open | CI/CD: GitHub Actions develop + production pipelines, signed AAB, Play upload, screenshots auto-published to GitHub Pages every release | — |
| LCA-T8 | 2026-11-17 | feature | closed | Sound manifest: ship no copyrighted audio; catalogue every sound with public source URL + checksum; user downloads in-app | — |
| LCA-T9 | 2026-11-17 | feature | closed | Monetization: free with session timeout (default 24h) + purchase screen; one-time 0.99 USD Play Billing unlock; no ads; research other launchers | — |
| LCA-T10 | 2026-11-17 | docs | closed | Documentation: README, architecture, build, test, release, Play listing/data safety/privacy policy, editing from Claude Code and phone | — |
| LCA-T11 | 2026-11-17 | chore | open | Freeze finished+tested Java version and rename project to GunGreenEyes.LCARS.Java | — |
| LCA-T12 | 2026-11-17 | feature | open | Recreate the entire app in Kotlin as GunGreenEyes.LCARS.Kotlin | — |
| LCA-T13 | 2026-11-17 | feature | open | Investigate and build a web-browser version of the launcher | — |
| LCA-T14 | 2026-11-17 | feature | open | Competitive parity: match and exceed NSTEnterprises TREK LCARS4 (Total Launcher theme) and other LCARS launchers; feature matrix + gap tickets | — |
| LCA-T15 | 2026-11-17 | feature | closed | A1: original synthesized LCARS sound set bundled (no recordings), so the Play build is not silent | — |
| LCA-T16 | 2026-11-17 | feature | closed | A3+A2+B1: first-run tour; layout export/import via file picker (no secrets); auto snapshots before upgrades + undo | — |
| LCA-T17 | 2026-11-17 | feature | closed | B6+A13: Quick Settings tiles (CLOAK, RED ALERT); assignable red-alert mode with looping klaxon + mute | — |
| LCA-T18 | 2026-11-17 | feature | open | A5+B2: interface/text size settings and TalkBack accessibility for Canvas-drawn UI | — |
| LCA-T19 | 2026-11-17 | feature | closed | A4: host any Android widget inside an LCARS/Pipe frame panel | — |
| LCA-T20 | 2026-11-17 | feature | closed | A6+A9: OPEN GROUP app groups; assignable gestures (double-tap, two-finger swipe, pinch) + optional screen-lock action | — |
| LCA-T21 | 2026-11-17 | feature | closed | A8: 23rd-century and 25th-century Federation interface styles (original designs) | — |
| LCA-T22 | 2026-11-17 | feature | closed | A7: icon pack support, per-app pictogram picker, ~100 pictograms | — |
| LCA-T23 | 2026-11-17 | feature | closed | B4+B3: Home Assistant smart-home device; notification reply from the shade + unread badges on tiles | — |
| LCA-T24 | 2026-11-17 | feature | open | Wave 2: widgets for other launchers, screensaver, auto alert conditions, translations/locale dates, voice commands, Captain's Log, foldable tabletop mode, Wear OS face, Android TV, wallpaper library | — |
| LCA-T25 | 2026-11-17 | feature | closed | Rebrand store build to TrekUI Launcher: app ID com.gungreeneyes.trekui, personal flavor keeps com.gungreeneyes.trekui; listing/site/docs/in-app brand strings free of Star Trek/LCARS trademarks | — |
| LCA-T26 | 2026-11-17 | chore | closed | Full TrekUI rename after wave 2: Java package com.gungreeneyes.trekui, prefs/files/env vars/site/docs/listing/in-app names; remove all LCARS/old IDs (pre-v1, no migration) | — |
| LCA-T27 | 2026-11-17 | feature | open | Competitor-review requirements: cover and exceed every TREK Total Interface review theme (checklist in session scratchpad REVIEW_CHECKLIST.md) | — |
| LCA-T28 | 2026-11-17 | feature | open | Reviews A/B/C/E/F/G: default-home watchdog + return-to-standard, smart default app mapping (no dead buttons), layout lock, instant undo, in-app help, weather units/12-24h/locale dates, notification-access status, sound volume/stream/mute/DND | — |
| LCA-T29 | 2026-11-17 | feature | open | Reviews H/I: foldable posture, lock/home wallpaper generator + screensaver, voice commands, sensors + image panels, animation level, viewscreen launch, stardate formats, translations (de/fr/es), wallpaper library, ambient soundscape | — |
| LCA-T30 | 2026-11-17 | feature | closed | Multi-module split: extract :core library (skins, drawing, palettes, sounds, ScreenMetrics, shared unlock client) from :app so companion apps share the look | build: :core:assembleDebug + :app:assembleDevelopDebug/ProductionDebug |
| LCA-T31 | 2026-11-17 | feature | closed | TrekUI Messages: standalone default-SMS app (SMS/MMS, threads, compose, notifications, quick reply, blocking) in TrekUI styles; free, unlocked by launcher purchase | build: messages assemble |
| LCA-T32 | 2026-11-17 | feature | closed | TrekUI Keyboard: standalone IME in TrekUI styles with synthesized key sounds, themes, sizes, languages; free, unlocked by launcher purchase | build: keyboard assemble |
| LCA-T33 | 2026-11-17 | feature | closed | TrekUI Lock Screen: standalone show-when-locked wake screen (clock, notifications, media, quick actions) that hands off to the system keyguard; free, unlocked by launcher purchase | build: lockscreen assemble |
| LCA-T34 | 2026-11-17 | feature | open | Shared unlock: launcher exposes signature-protected unlock-status provider; companions query it; same Play signing key across the 4 apps; docs | — |
| LCA-T35 | 2026-11-17 | feature | closed | Sub-themes for every interface family (COMMAND: CLASSIC, DEEP BLUE, SUNSET DECK, NIGHT PADD, STELLAR SILVER, MODERN GRAY; original sets for IMPERIAL and COLLECTIVE); Messages/Keyboard/Lock Screen follow the chosen interface+sub-theme; launcher SUITE controls + home-screen widget/QS tiles to switch Messages/Keyboard/Lock Screen on and off | build: core+app assemble |
| LCA-T36 | 2026-11-17 | feature | open | Suite licensing: every TrekUI app sells the same $0.99 trekui_unlock and works standalone; entitlement shared + cached across siblings; one purchase unlocks all | — |
| LCA-T37 | 2026-11-17 | feature | closed | One-command release: ship script + release workflow builds, signs and uploads all four apps to Play (track/rollout/approval inputs), runnable from GitHub mobile and Claude Code | yaml+script validation, ship --dry-run |
| LCA-T38 | 2026-11-17 | chore | closed | Rename the GitHub repos to TrekUI.Java and TrekUI.Kotlin (the owner's names) and point every clone, Pages, issue and release link at TrekUI.Java | owner request 2026-11-17 |
| LCA-T39 | 2026-11-17 | feature | open | Suite follow-ups from LCA-T35: Quick Settings tiles + home-screen widget to switch Keyboard/Lock Screen/Messages on and off; app-theme and Quick Panel art for the six new COMMAND sub-themes (exports reuse the nearest original art today) | LCA-T35 report |
| LCA-T40 | 2026-11-17 | fix | closed | Ship-blocker bug batch from the emulator screenshots: clipped/overflowing labels (CLASSIC CONSOLE mdpi sidebar, NEXT CONSOLE landscape, COMMAND NAVIGATION tile), Pipe Midnight landscape icon row clipped, drawer too transparent, trial screen RESTORE clipped + wording + accessibility labels, Samsung text on non-Samsung devices, PipeSceneTest -0.0 and SoundCatalogTest manifest mismatch | build: core+app assemble; tests fixed by reading |
| LCA-T41 | 2026-11-17 | chore | closed | Stay current automatically: Dependabot for Gradle/AGP/AndroidX/Actions, a weekly platform watch that opens an upgrade issue when Google ships a new Android API level or Gradle, and a local weekly task that turns those issues into queued tickets | first run queued Dependabot PR #1 as LCA-T42 |
| LCA-T42 | 2026-11-17 | chore | open | Review and merge: ci: bump the actions group with 12 updates (https://github.com/GunGreenEyes/TrekUI.Java/pull/1) | GitHub pr#1 |
| LCA-T43 | 2026-11-17 | chore | closed | One owner setup script: upload key, GitHub release secrets, google-play approval environment, Pages via Actions, upgrade-queue task, then a printed Play Console checklist for the four apps | parse check; steps idempotent |
