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
| TUK-T2 | 2026-11-18 | chore | open | Kotlin edition, phase 0: seed from TrekUI.Java 43, point repo/site/release links at TrekUI.Kotlin, re-enable CI and Pages, write the port plan (docs/KOTLIN_PORT.md) | owner goal 2026-11-18 |
| TUK-T3 | 2026-11-18 | chore | open | Phase 1 build foundation: Kotlin 2 + Compose plugins, build-logic convention plugins for all five modules, ktlint + detekt in CI; Java code still builds and every test passes | docs/KOTLIN_PORT.md |
| TUK-T4 | 2026-11-18 | feature | closed | Phase 2: convert :core to Kotlin (palettes, sub-themes, skins, drawing helpers, ScreenMetrics, sounds, licensing, suite contract) with its tests ported; no behaviour change | :core 100% Kotlin; :app unit suite green |
| TUK-T5 | 2026-11-18 | feature | closed | Phase 3a: convert :messages to Kotlin with its tests; no behaviour change | :messages 100% Kotlin; 29 unit tests green; lint 0 errors |
| TUK-T6 | 2026-11-18 | feature | closed | Phase 3b: convert :keyboard to Kotlin with its tests; no behaviour change | :keyboard 100% Kotlin; 46 unit tests green; lint 0 errors; no network permission |
| TUK-T7 | 2026-11-18 | feature | closed | Phase 3c: convert :lockscreen to Kotlin with its tests; no behaviour change | :lockscreen 100% Kotlin; 26 unit tests green; lint 0 errors |
| TUK-T8 | 2026-11-18 | feature | closed | Phase 4: convert :app (the launcher, ~28k lines) to Kotlin package by package, unit tests and instrumented scenarios ported; no behaviour change | :app 100% Kotlin incl. unit + instrumented tests; 513 unit tests green; lint 0 errors |
| TUK-T9 | 2026-11-18 | feature | open | Phase 5: Compose console design system in :core (frames, cards, keys, dialogs, sub-themes) and the screens moved to Compose (companions first, then launcher interfaces), guarded by scenarios + Roborazzi screenshots | docs/KOTLIN_PORT.md |
| TUK-T10 | 2026-11-18 | feature | open | Phase 6: settings on DataStore with a one-time migration from the Java edition's trekui SharedPreferences, plus an upgrade test (Java build data -> Kotlin build) | docs/KOTLIN_PORT.md |
| TUK-T11 | 2026-11-18 | chore | open | Phase 7 parity + release: full unit suite, CI matrix, local 24-device matrix and device report, docs rewritten for Kotlin, shipped as the next whole-number version over the Java edition | docs/KOTLIN_PORT.md |
| TUK-T12 | 2026-11-18 | docs | closed | Docs describe the Kotlin codebase (README, ARCHITECTURE, BUILD, TESTING, CONTRIBUTING, agent guides, port status) | TUK-T4..T8 done |
