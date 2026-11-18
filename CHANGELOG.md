# Changelog

This file records the notable changes to TrekUI Launcher. From 6 on, versions are whole numbers (6, 7, 8, ...); `versionCode` is a separate integer that rises every release. Both live in `version.properties`. Versions before 6.2 were earlier private builds; their entries below are summaries.

## 42

### Sub-themes and the TrekUI suite
- **TrekUI Messages (LCA-T31).** A new companion app (`:messages`): a full default SMS/MMS app drawn in the TrekUI interfaces. Conversation list, threads with delivery state, picture messages through the system photo picker, group sends, search across names and message text, drafts, blocking (system list plus prefix rules), dual-SIM choice, per-conversation notification channels with inline REPLY and MARK READ, and quick replies from the incoming-call screen. Follows the launcher's interface and sub-theme, or offers its own picker when the launcher isn't installed. Same trial; one purchase unlocks every TrekUI app.
- **TrekUI Keyboard (LCA-T32).** A new companion app (`:keyboard`): an on-screen keyboard drawn in every TrekUI interface and sub-theme (following the launcher's, or its own picker). English QWERTY, German QWERTZ, French AZERTY and Spanish layouts switched with a globe key or a long press on the space bar; symbols on two pages, number and dial pads for number and phone fields; shift and caps lock (double tap), long-press accents and digits, slide the space bar to move the cursor, swipe left from delete to remove whole words; the enter key shows GO / SEARCH / SEND / NEXT / DONE. A suggestion strip completes words from your personal dictionary and words you typed, and never learns in password or incognito fields. Synthesized key clicks (following Android's touch-sound setting, own volume), vibration, S-XL key heights for portrait and landscape, one-handed mode and a number row. No internet permission: nothing typed leaves the device. Typing never stops when a trial session ends; a small UNLOCK chip appears instead. See docs/KEYBOARD.md.
- **TrekUI Lock Screen (LCA-T33).** A new companion app (`:lockscreen`): a wake screen that appears over Android's lock screen when the screen turns on, drawn in the launcher's interface and sub-theme (or its own picker). Big clock and date, an original decimal date (each year is 1000 units), next alarm, battery and charging, media controls, the notification list with lock-screen privacy (secret notifications never shown; private ones only as the app name unless SHOW SENSITIVE CONTENT is on and Android allows it), CAMERA (the camera's secure mode) and TORCH. Swipe up or tap UNLOCK to hand over to Android's own PIN, pattern, password or biometric check; it never weakens the lock. Triggered from its notification listener, with no foreground service, accessibility service or device admin. Follows the launcher's CONFIG › SUITE › LOCK SCREEN switch and has its own. A trial that ends never blocks unlocking; a small UNLOCK APP chip appears instead. See docs/LOCKSCREEN.md.
- **Sub-themes for every interface (LCA-T35).** COMMAND gains DEEP BLUE, SUNSET DECK, NIGHT PADD, STELLAR SILVER and MODERN GRAY next to CLASSIC and its five earlier themes; IMPERIAL gets five (IMPERIAL TACTICAL, WAR BRONZE, DUSK RAIDER, ICE FORGE, IRON & FLAME) and COLLECTIVE five (THE COLLECTIVE, CYAN LATTICE, AMBER SIGNAL, VIOLET NODE, FROST NODE) that recolour the whole interface: plates, grids, glows, backdrop and lettering. Pick one in CONFIG › INTERFACE › COLOR THEME from a preview strip; it applies at once. The first theme of each interface is exactly the earlier look, and stored choices keep their meaning. The companion apps read the same choice through the suite provider.
- **CONFIG › SUITE (LCA-T35).** One place for the companion apps: KEYBOARD shows the active keyboard and opens the keyboard picker or the enable screen; LOCK SCREEN switches the TrekUI wake screen on or off; MESSAGES makes TrekUI Messages the default SMS app or restores the previous one. A companion that isn't installed shows INSTALL with its Google Play listing.

### Home panels
- **Android widget panels (LCA-T19).** Any installed app widget (Google, Gmail, clocks, notes, your work profile's widgets) can now sit in its own panel, framed by the active interface's bracket. Add one from a panel's long-press menu (**ADD WIDGET PANEL**) or from CONFIG › LAYOUT › **ADD ANDROID WIDGET PANEL**; up to 12 widget panels. They join the panel order, HIDE PANEL, CONFIG › PANELS and the reveal-hub info keys like the built-in panels. Long-press the title for **WIDGET SIZE** (five heights), **RECONFIGURE WIDGET** and **REMOVE WIDGET**. The widget is told its real size on every rotation, fold or window change, and a widget whose app was removed shows an OFFLINE notice with a REMOVE key.

### Launcher
- **First-run tour** (LCA-T16): after the default-home welcome, an eight-stop tour drawn in the current interface's frame (Command, Imperial, Collective or Pipe) explains long-press to ASSIGN, the app drawer, the shade, CONFIG, switching interfaces, layout backup and setting the default home. SKIP, BACK and NEXT; CONFIG › DEFAULT HOME › REPLAY TOUR shows it again.
- **Layout export and import** (LCA-T16): CONFIG › BACKUP › EXPORT LAYOUT / IMPORT LAYOUT through the system file picker. A versioned JSON file with tiles, dock, element assignments, panels, interface, colours and the other appearance settings; Govee and Nest credentials, Home Assistant settings, anything credential-like, device-bound values and the weather location are left out. Import validates the file, converts older formats, turns apps that aren't installed into empty slots and shows a preview before APPLY.
- **Automatic layout snapshots and undo** (LCA-T16): the layout is saved before an app update (versionCode change), an import, a reset, a restore and an interface switch; the newest 10 are kept in CONFIG › BACKUP with RESTORE, plus UNDO LAST CHANGE and SNAPSHOT NOW.
- **RED ALERT** (LCA-T17): a new assignable function (long-press › ASSIGN TREKUI FUNCTION › RED ALERT) and Quick Settings tile. A pulsing alarm frame over the home screen in every interface (console red bars, Imperial blades, Collective threat brackets, Pipe ember vignette), the RED ALERT sound looping, MUTE and STAND DOWN buttons, an auto-timeout (15 s to 10 min, CONFIG › INTERFACE & ACCESS). Silent in Silent/Vibrate, calls and Do Not Disturb; touches pass through the frame.
- **Home Assistant** (LCA-T23): a third smart-home source next to Govee and Nest. Enter your Home Assistant address and a long-lived access token in CONFIG › DEVICES › HOME ASSISTANT, tap IMPORT ENTITIES, then assign `HA: <entity>` to any tile, dock icon or segment: lights, switches, fans and input booleans toggle, scenes and scripts activate, climate entities open the TrekUI environmental control (the Nest dialog, now backend-neutral), and a light's CONTROLS panel has ON/OFF, an intensity slider and spectrum keys. HTTPS by default; plain `http://` only for `.local` / `.home.arpa` names on the home network, with a warning (a scoped network security config; IP addresses need HTTPS). Address and token live in their own private preferences file, excluded from backup, device transfer and layout export.
- **Reply from the console shade** (LCA-T23): notifications in the shade's log get REPLY (for apps with inline reply: Google Messages, Signal, WhatsApp…) and the app's own action keys. REPLY opens a console reply panel with the app's suggested replies; the text goes straight to the app through its reply intent and is never stored.
- **Unread badges** (LCA-T23): tiles, reveal-hub nodes and dock keys of apps with notifications show an unread count, in every interface (console pill, Imperial blade, Collective block, Pipe wax seal); a tile assigned to the console shade shows the total. Respects notification access and each app's "notification dot" setting; counts stay in memory. CONFIG › NOTIFICATION SHADE › NOTIFICATION BADGES turns them off.
- **App groups** (LCA-T20): OPEN GROUP is a new assignable function. A group is a named set of up to 12 apps that unfolds over the home screen in the interface's style: a cascade with a spine and keys sliding out, an Imperial tactical deploy, a Collective orbit, or a Pipe Lounge card. Create, add and remove apps, reorder, rename, move and delete groups in CONFIG › APP GROUPS, from ASSIGN TREKUI FUNCTION › NEW GROUP…, from the drawer's ADD TO GROUP, or by long-pressing an open group's title. Groups are stored under `group.` prefs, so layout export includes them.
- **Assignable gestures** (LCA-T20): double-tap on empty space, two-finger swipe up and down, pinch in and out, and swipe left and right on the home screen can each run any launcher function or app (CONFIG › GESTURES, stored under `gesture.` prefs). Unassigned gestures do nothing; swipe up (drawer) and swipe down (shade) are unchanged.
- **LOCK SCREEN** (LCA-T20): an optional function that locks the phone through an accessibility service that reads no events and no screen content (Android 9+). A prominent disclosure comes before Android's Accessibility settings. No Device Admin.
- **Quick Settings tiles** (LCA-T17): CLOAK and RED ALERT tiles with live on/off state; CONFIG offers the system add-tile prompt on Android 13+.
- **Two more console eras** (LCA-T21): CONFIG › INTERFACE › STARSHIP now lists **CLASSIC CONSOLE**, a 23rd-century style (chunky square console keys in primary colours, blinking indicator lamps, no elbows, plain sans lettering) and **NEXT CONSOLE**, a 25th-century style (thin translucent blue/grey glass, cut corners, fine tick rules, light lettering), each with a live preview on its card and three colour themes of its own. Both are original geometry; they keep every layout of the Command interface (grid and cascade), the drawer, dialogs, panels and its sound profile, and have their own start-up sequences. Included in the same one-time unlock.
- **Icon packs, CHANGE ICON and 81 more pictograms** (LCA-T22): third-party icon packs in the ADW / Nova / Go / Apex / Atom formats are found automatically and chosen in CONFIG › APP DRAWER › ICON PACK with a preview strip; their `appfilter.xml` is read off the main thread (calendar icons, and backplate/mask/overlay for apps the pack doesn't cover), and pack icons show in the FRAMED and NATIVE styles, the dock, tiles and every interface. The drawer's long-press menu has CHANGE ICON: a grid of every pictogram in the current style, a searchable pack grid, the app's own icon, or AUTOMATIC. The pictogram set grows from 28 to 109 (all original, generated by `tools/art/glyphs_extra.py` in the plain, engraved, deco and stamp styles), and AUTO PICTOGRAMS gives common apps a matching one from a package/label keyword table.

## 6.2.0 (versionCode 41): Modernization, Play-ready

This is the first release built from a regular Gradle project in this repository, and the first one made for Google Play.

### Project and build
- **One source tree.** The 5.2 source and the 6.1 Pipe Lounge patch set are merged into one Gradle project. The 6.1 classes were recovered by decompiling; their names and constants are being restored to readable ones, and every class gets Javadoc.
- **Gradle Kotlin DSL** with a version catalog. Toolchain:
  - Android Gradle Plugin 9.x and a Gradle 9 wrapper;
  - compileSdk and targetSdk 37, minSdk 26;
  - Java 17 language level, built with JDK 21.
- **Two product flavors:**
  - `develop` (`com.gungreeneyes.trekui.dev`, "TrekUI Dev"), which installs next to the store build;
  - `production` (`com.gungreeneyes.trekui`).
- **Release builds** use R8 shrinking and are signed with an upload key from local properties or CI secrets.
- **`./gradlew :app:dist`** copies every APK and AAB into `dist/`.
- **Bootstrap scripts.** `scripts/setup.ps1` (Windows) and `scripts/setup.sh` (macOS, Linux, Termux) install the toolchain on a fresh clone.

### Layout
- **Orientation lock removed.** The launcher now lays out in portrait and landscape, on phones, tablets and foldables (folded and open), and in multi-window.
- **`ScreenMetrics`** sizes everything from the window: Material 3 size classes, margins, gutters, UI scale and column counts.
- **Edge-to-edge** with status bar, navigation bar and display-cutout insets handled on every API level.

### Sounds
- **No recordings ship with the app.** The third-party recordings bundled in earlier private builds are gone from the APK.
- **New sound catalogue** (`assets/sounds/manifest.json`). It lists, for every sound, a public source URL, a SHA-256 checksum and how to cut the clip.
- **DOWNLOAD SOUND LIBRARY** fetches, verifies and processes the clips on the phone. Importing your own files still works.
- **Personal builds.** Local develop builds can still include the owner's own `personal_sounds/`. CI and production builds never do.

### Monetization
- **Free and ad-free, with trial sessions.** An unlicensed copy runs in 24-hour sessions; develop builds use 5 minutes.
- **The purchase screen** appears only on the home screen, never over another app. It offers the **one-time US$0.99 unlock** through Google Play Billing, and also restore, a new session, or switching to another home app.
- **The unlock** restores automatically on every connection and works offline once granted.

### Networking and privacy
- **One HTTPS-only client** with timeouts and size limits now serves weather, Govee cloud and Nest. Cleartext traffic is disabled.
- **No `QUERY_ALL_PACKAGES`.** App visibility uses `<queries>`.
- **Sound files are excluded from backup** and device transfer.
- **New privacy policy** ([PRIVACY.md](PRIVACY.md)).

### Testing and CI
- **Unit tests** for logic, including skins, sounds, licensing and screen metrics.
- **Robolectric screenshots** of the interfaces.
- **Instrumented Given/When/Then scenarios** with screenshots and screen recordings.
- **Emulator matrix:** API 26 to 37, phones, tablets and foldables, aspect ratios from 4:3 to 21:9, mdpi to xxxhdpi.
- **GitHub Actions:**
  - a develop APK on every push;
  - the emulator matrix;
  - a tagged release that produces a signed AAB and APK and a GitHub Release, and uploads to the Google Play internal track;
  - a GitHub Pages site with screenshots and videos;
  - CodeQL;
  - Dependabot.

### Documentation
- README, the architecture, build, testing and release guides, the Play Console checklist, the privacy policy and the contributing guide.
- A guide to developing from a phone: Claude Code on the web or mobile, Cowork, and Termux.

## 6.1: Painted scenes
- **Three more Pipe Lounge interfaces**, made from the PIPE_COLLECTION v3 art: **Ember & Briar**, **Tobacconist's Ledger** and **Midnight Ember**. Each home is a painting with launcher hotspots, live overlays and dock keys.
- **PipePages:** pipe register, blend catalogue, journal, ritual and session clock, reference shelves, and export/import.
- **PipeDataStore:** an offline collection store.
- **Shared materials.** Every other screen uses a matching material family: Briar → walnut/brass, Ledger → leather/kraft, Midnight → lacquer/gilt.

## 6.0.1
- **Signed with the original 5.x key**, so it installs over 5.2 without losing your layout.

## 6.0: Pipe Lounge
- **Three Pipe Lounge interfaces:** **The Tobacconist** (Victorian walnut and brass), **Ember Lounge** (Art Deco lacquer and gold) and **Blend Journal** (leather, kraft paper and red ink).
- **New interface families.** CONFIG › INTERFACE gains the families STARSHIP and PIPE LOUNGE.
- **PipeDraw, PipeHome and PipeBoot:** materials, homes and the start-up animation (a match is struck).
- **New fonts:** Cinzel, IM Fell English, Limelight, Josefin Sans and Rye (OFL), and Special Elite (Apache 2.0).
- **Built from the 5.2 APK.** The 5.2 source was not at hand, so 6.0 was made from the APK by partial decompilation and recompilation.

## 5.2
- **Release for the Galaxy S25 Ultra** (Android 15/16, One UI 7/8).
- **Grouped sound choosers, the source sound library and stations**, carried over from 5.0 and 5.1.

## 5.1
- **Grouped sound choosers.** Every sound list is chosen in two steps: first the group, with its count, then the sound.

## 5.0
- **Source sound library** (private build only): 395 clips, wired to the bridge stations and the start-up sequences.

## 4.9
- **Start-up sequence per interface:** the Command computer-core power-up, the Imperial triangle assembly, and the Collective network linking up.

## 4.8
- **Bridge stations:** six toy consoles under INFO › STATIONS (OPS, CONN, TACTICAL, ENGINEERING, SCIENCE, MEDICAL), with Imperial and Collective names for each.

## 4.6
- **INFO key.** On the deploying homes, the panels move behind an INFO key that deploys one key per panel.

## 4.5
- **Press-to-deploy homes:** Command **CONSOLE CASCADE** and Imperial **TACTICAL DEPLOY**.

## 4.4
- **Imperial colours** reworked: blood red, orange, warning yellow and amber.
- **Warrior Blade**, an original Imperial-style English font, with the IMPERIAL LETTERING options.

## 4.3
- **Collective HUB AND SPOKE layout:** nodes in orbits around a central hub, always fitting the screen.

## 4.0
- **Three interfaces:** Command, Imperial and Collective.
- **One spacing scale** for all padding.

## Earlier releases (exact version not recorded)
- **The launcher itself:**
  - assignable console elements;
  - live panels (chronometer, power, weather, schedule, audio, ship systems);
  - the console dialer and the console shade;
  - Samsung Quick Panel, Signal, Chrome and keyboard theming recipes;
  - CLOAK night mode;
  - Nest thermostat and Govee lights;
  - sound library and phone sounds;
  - widgets for other launchers;
  - six colour themes.
