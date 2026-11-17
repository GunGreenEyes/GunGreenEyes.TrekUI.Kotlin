---
codex: 1
project: LCARS
code: LCA
layer: stories
status: living
updated: 2026-11-17
---

# LCARS — User Stories
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
| LCA-T6 | 2026-11-17 | feature | open | Tests: unit tests for logic; instrumented tests, screenshots and video across emulator matrix (API 26-36, phone/tablet/foldable, resolutions, aspect ratios) | — |
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
| LCA-T25 | 2026-11-17 | feature | open | Rebrand store build to TrekUI Launcher: app ID com.gungreeneyes.trekui, personal flavor keeps com.gungreeneyes.trekui; listing/site/docs/in-app brand strings free of Star Trek/LCARS trademarks | — |
| LCA-T26 | 2026-11-17 | chore | open | Full TrekUI rename after wave 2: Java package com.gungreeneyes.trekui, prefs/files/env vars/site/docs/listing/in-app names; remove all LCARS/old IDs (pre-v1, no migration) | — |
