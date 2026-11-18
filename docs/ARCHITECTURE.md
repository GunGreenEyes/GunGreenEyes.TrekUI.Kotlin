# Architecture

TrekUI Launcher is one Android application module (`:app`, package `com.gungreeneyes.trekui`). It is written in plain Java against the Android framework, with only a few AndroidX libraries (core, activity, window) and Play Billing.

There are **no XML layouts and no fragments**. Every screen is built in code from `View`/`ViewGroup` subclasses and `Drawable`s that paint themselves on a `Canvas`. That is how one codebase can render nine visually unrelated interfaces: the same view tree is decorated by whichever interface ("skin") is active.

> Other contributors are actively refactoring the code. Class names below are current at the time of writing. Small helpers come and go, but the shape described here is stable. The code itself is the reference: every class carries a Javadoc saying what it is and how it fits.

## Big picture

```mermaid
flowchart TD
    subgraph Android
        HOME[HOME intent] --> MA
        LA[LauncherApps] --> AR
    end
    MA[MainActivity<br/>home screen host] --> GR[GestureRoot<br/>swipe up/down, long-press]
    GR --> HOMEV[Home view]
    GR --> DR[DrawerView<br/>all apps + search]
    GR --> SH[ShadeView<br/>console shade]
    GR --> DI[DialerView]
    GR --> BOOT[BootSequence / PipeBoot]
    HOMEV -->|Starship| LF[ConsoleFrame + tiles + panels]
    HOMEV -->|deploying layouts| RL[RevealLayout<br/>CommandCascade / ImperialDeploy / CollectiveHub]
    HOMEV -->|Pipe drawn| PH[PipeHome]
    HOMEV -->|Pipe painted| PS[PipeScene]
    PS --> PP[PipePages] --> PDS[(PipeDataStore)]
    MA --> AR[AppRepo] 
    MA --> PANELS[Panel subclasses<br/>Weather, Calendar, Media, Power, Systems]
    MA --> SA[SettingsActivity<br/>CONFIG]
    SA --> PREFS[(Prefs<br/>SharedPreferences)]
    MA --> PREFS
    MA --> LIC[licensing.Licensing]
    PANELS --> HTTP[Http] 
    SKIN[Skin / Ui / Palette / IconFactory] -.styles.-> LF & RL & PH & PS & DR & SH & DI
```

## The home activity

`MainActivity` is the only `HOME` activity (`singleTask`, `clearTaskOnLaunch`, `excludeFromRecents`). Its life is a rebuild loop:

1. **`onCreate`**
   - Loads `Prefs` and the skin (`Skin.load`).
   - Starts `AppRepo`.
   - Wires licensing (`Licensing.init`).
   - Calls `buildUi()`.
   - Plays the start-up sequence once per boot, when enabled.
2. **`buildUi()`**
   - Tears down the current tree and builds a new `GestureRoot`, with the home view, `DrawerView`, `ShadeView` and `DialerView` stacked inside it (only the home view is visible).
   - Records `Prefs.version()` as the version this tree was built from.
3. **`onResume`**
   - Rebuilds the tree if the prefs version changed. `SettingsActivity` calls `Prefs.bump()` after any change that affects the home, so CONFIG never has to talk to the home directly.
   - Resumes the panels.
   - Tells licensing the home is in front.
4. **`onPause`** pauses every `Panel`, so nothing polls while another app is in front.
5. **Window insets** (status bar, navigation bar and display cutout) are read in `readInsets` and applied as padding in `applyInsets`, so the layout is edge-to-edge on every API level.

`buildHome()` chooses one of four home builders from the interface:

```mermaid
flowchart LR
    BH[buildHome] --> Q1{Skin.scene?}
    Q1 -->|yes, 6-8| PS[PipeScene.build]
    Q1 -->|no| Q2{Skin.pipe?}
    Q2 -->|yes, 3-5| PH[PipeHome.build]
    Q2 -->|no, 0-2| Q3{deploying layout<br/>chosen in CONFIG?}
    Q3 -->|no| CL[Classic console frame:<br/>header frame, code blocks,<br/>tile grid, panels, dock]
    Q3 -->|Command| FC[CommandCascade]
    Q3 -->|Imperial| KD[ImperialDeploy]
    Q3 -->|Collective| BH2[CollectiveHub]
```

### Slots, tiles and functions

- **Slots.** Every interactive element on the home screen is a *slot* with a string key: tiles, dock slots, elbows, bar segments, code blocks, titles, the clock and the date. `bindSlot` / `runSlot` / `editSlot` in `MainActivity` give each slot the same behaviour in every interface:
  - **Tap:** runs the assigned app or function, or the element's default behaviour.
  - **Long-press:** opens a menu to assign an app or function, rename, recolour, set a sound, or reset.
- **`Tile`.** Describes what a tile or dock slot launches: an app (component plus user), a console function (drawer, search, config, shade, dialer, cloak, `LIGHTS:` Govee device, `CLIMATE:` Nest device, `HA:` Home Assistant entity, …), or nothing.
- **`HomeLogic`.** Holds the pure decisions behind the home screen, so they are unit-testable.
- **App groups (OPEN GROUP).** `AppGroups` stores named app sets as one JSON array in the `group.list` pref (pure encode/parse/edit functions). A `Tile` with action `group` and the group id as `arg` opens `GroupPanel`, an overlay added to the home root that unfolds in the interface's style (Command spine cascade, Imperial deploy grid, Collective orbit, Pipe card), sized with `ScreenMetrics` inside the insets. `GroupEditor` holds the create / apps / reorder / rename / move / delete dialogs used from the home screen, the drawer's ADD TO GROUP and CONFIG › APP GROUPS.
- **Gestures.** `MainActivity.GestureRoot` offers every touch to `HomeGestureDetector` first. It recognises double-tap (only on empty space), two-finger swipes, pinch and horizontal swipes on the bare home screen, and runs the `Tile` stored under `gesture.<id>` (see `HomeGestures`, which also holds the thresholds). A gesture with nothing assigned is never taken, so the fixed swipe up (drawer) and swipe down (shade) behave as before. `GestureSettings` builds the CONFIG › GESTURES pickers.
- **LOCK SCREEN.** `LockScreen` runs the function through `LockService`, an optional `AccessibilityService` with no event types and no window access that only performs `GLOBAL_ACTION_LOCK_SCREEN` (API 28+). Play's prominent disclosure (`LockScreen.showDisclosure`) comes before Accessibility settings; see `docs/PLAY_STORE.md`. No Device Admin.

### Deploying layouts

`RevealLayout` is the base class of the press-to-deploy homes (`CommandCascade`, `ImperialDeploy`). `CollectiveHub` behaves the same way and implements the same `RevealHost` contract. A deploying home:

- starts folded down to one master control, deploys on a tap and folds again on the next;
- folds by itself after an app launches;
- always fits the space it is given, without scrolling.

Behind the **INFO** key, the panels appear as small keys (`RevealInfo`) or Collective nodes (`CollectiveInfo`). Tapping one opens that panel over the dimmed layout (`showHubPanel`). **STATIONS** opens `Stations`, the six simulated bridge consoles.

### Panels

`Panel` is the base class of the live home widgets: `WeatherPanel`, `CalendarPanel`, `MediaPanel`, `PowerPanel` and `SystemsPanel`. It has a `resume()` / `pause()` contract, and work happens only in between. The supporting classes are:

- **Readers:** `SysStats` (CPU, memory), `Weather` (Open-Meteo client), `MediaListener` (the notification-listener service: authorises reading media sessions, feeds the shade's notification log and the in-memory unread counts behind `NotificationBadges`), `SegBar` (level bars).
- **Card chrome:** `ConsoleCard` draws the bracketed frame around a panel in the active skin.
- **Hosted Android widgets:** `WidgetPanel` shows another app's widget inside a `ConsoleCard`. Its panel id is `appwidget<slot>`, so `Prefs.widgetOrder()` (through `WidgetSlots.panelIds`), HIDE PANEL, CONFIG › PANELS and the hub info nodes treat it like a built-in panel. `WidgetSlots` holds the pure logic: the `"slot:appWidgetId"` map in Prefs, the next free slot, orphaned ids and the row/height/dp size math (unit-tested). `WidgetHost` wraps the `AppWidgetHost` (host id `0x4C43`); `MainActivity` owns one and calls `startListening()` in `onStart` and `stopListening()` in `onStop`. Its views (`TrekUiHostView`) show a console error notice instead of Android's grey box. `WidgetPickActivity` is the translucent add flow: provider list across all profiles → `bindAppWidgetIdIfAllowed`, else `ACTION_APPWIDGET_BIND` → `startAppWidgetConfigureActivityForResult` when the provider needs configuring → save the slot and `bump()`. Any cancel deletes the allocated id; a fresh start deletes ids no slot references. The laid-out size is sent with `AppWidgetHostView.updateAppWidgetSize` (a `SizeF` list on API 31+, min/max dp before) whenever the widget area's size changes.

## Interfaces (skins)

The interface is an `int` in `Skin.faction`:

| Value | Interface | Family | Home builder | Material (`Skin.mat()`) |
|---|---|---|---|---|
| 0 | Command | Starship | ConsoleFrame / CommandCascade | – |
| 1 | Imperial | Starship | ConsoleFrame / ImperialDeploy | – |
| 2 | Collective | Starship | ConsoleFrame / CollectiveHub | – |
| 3 | The Tobacconist | Pipe (drawn) | PipeHome | 3 walnut/brass |
| 4 | Ember Lounge | Pipe (drawn) | PipeHome | 4 lacquer/gilt |
| 5 | Blend Journal | Pipe (drawn) | PipeHome | 5 leather/kraft |
| 6 | Ember & Briar | Pipe (painted) | PipeScene | 3 |
| 7 | Tobacconist's Ledger | Pipe (painted) | PipeScene | 5 |
| 8 | Midnight Ember | Pipe (painted) | PipeScene | 4 |
| 9 | Classic Console (23rd-century style) | Starship (Command era) | ConsoleFrame / CommandCascade | – |
| 10 | Next Console (25th-century style) | Starship (Command era) | ConsoleFrame / CommandCascade | – |

`Skin.command()` is true for 0, 9 and 10, so the eras reuse every Command code path; `Skin.era()` / `era23()` / `era25()` pick the era drawing. CONFIG lists the starship family in `SettingsLogic.STARSHIP_FACTIONS` order (0, 9, 10, 1, 2).

The painted scenes reuse a drawn material for every screen other than the home: the drawer, dialogs, settings and so on. `Skin.mat()` maps each interface to its material.

### Drawing vocabulary

| Class | Role |
|---|---|
| `Skin` | Current interface, palettes per interface, shape `Drawable`s for Imperial/Collective (`Chamfer`, `NodeCell`, `NodeNet`, `TacticalDisplay`), asset folders per interface |
| `Ui` | Shared helpers: dp/sp conversion with the responsive zoom, the spacing scale (HAIR 2 · XS 4 · GUTTER 6 · S 8 · M 12 · L 16 · XL 24 dp), fonts per interface, cloak state |
| `Palette` | The six Command colour themes (CLASSIC is the default) plus per-interface palettes; six accents used in rotation. `Palette.themesFor(faction)` / `Palette.of(faction, index)` is the one sub-theme lookup; Imperial and Collective palettes also carry chrome tones (bg, surface, grid, detail, ink) |
| `SubThemes` (`:core`, LCA-T35) | Pure `#RRGGBB` specs of every interface's sub-themes: Command 11 (0-5 the original themes, 6-10 DEEP BLUE .. MODERN GRAY), Imperial 5, Collective 5, eras from `Era`, Pipe Lounge 1 (fixed materials). `count` / `clamp` / `name` / `accents` are public for companions; `SuiteContract.parseTheme` clamps with it. Index 0 is always the original look. `TrekPrefs.palette()` calls `Skin.useSubTheme`, which points `Skin.imperialTheme` / `collectiveTheme` and `ImperialDraw.RED/ORANGE/YELLOW/DEEP` at the active Imperial / Collective theme, so `Skin.bg/surface/grid/detail/ink/glow` and the faction drawing follow it. Stored per interface under `TrekPrefs.themeKeyFor` (`theme`, `theme1`, `theme2`, `theme9`, `theme10`) |
| `ConsoleFrame` | The Command frame (elbows, bars, segments) and its Imperial/Collective counterparts; reports taps on each part to slots |
| `ImperialDraw`, `CollectiveDraw` | Faction-specific primitives (octagon plates, triangle rows; glowing nodes, traces, `TraceColumn`) |
| `Era`, `EraDraw`, `EraBoot` | The 23rd/25th-century Command eras: pure theme specs, contrast, lamp-blink and cut-corner rules (`Era`, unit-tested); console keys with blinking lamps, glass cells, frame corners, card brackets, icon frames, clock ornaments and CONFIG previews (`EraDraw`, reached from `Ui.shape/outline`, `ConsoleFrame`, `ConsoleCard`, `CommandCascade`, `IconFactory`); start-up sequences (`EraBoot`, from `BootSequence`). Themes: `Palette.ERA23` / `ERA25`, stored per era as `theme9` / `theme10` |
| `PipeDraw` | All Pipe materials and shapes (`Cell`, `Drawer`, `DecoPanel`, `Tag`, grain textures) |
| `IconFactory` | App icons in the active style: console pictograms, framed icons, faction/pipe badges, or native icons |
| `Pictograms`, `IconPacks`, `IconPackParser`, `IconPicker` | Icon customisation (LCA-T22): the 109-name pictogram catalogue and package/label keyword table (`Pictograms`, pure); icon-pack discovery through the `<queries>` theme intents, `appfilter.xml` / `drawable.xml` parsing (`IconPackParser`, pure) and the raw-icon swap inside `AppRepo`'s background load, with backplate/mask composition for uncovered apps (`IconPacks`); the drawer's CHANGE ICON dialog (`IconPicker`). Per-app choices are `appIcon.<key>` prefs (`glyph:`, `pack:`, `app`); `iconPack` and `autoGlyphs` hold the CONFIG choices. Glyph art: `tools/art/glyphs.py` + `glyphs_extra.py`, rendered by `glyph_render.py` |
| `Starfield` | Background drawn once into a bitmap |
| `ConsoleDialog` | Every menu and prompt, drawn in the active skin (no platform `AlertDialog` look) |

### Pipe Lounge

| Class | Role |
|---|---|
| `PipeHome` | The three drawn Pipe homes (slots, tiles, panels and dock in walnut/lacquer/kraft), plus their lore |
| `PipeScene` | The three painted homes. A `Stage` crops the painting from `assets/pipe/scenes` to the available area and maps launcher slots onto hotspots given in the paintings' 941×1672 coordinate space (generated by `tools/art/scene_maps.py`). A `Live` overlay draws the clock, weather and other live values |
| `PipePages` | Collection pages: pipe register, blend catalogue, journal, ritual and session clock, reference shelves, export/import |
| `PipeDataStore` | The offline collection store (its own prefs file, `pipe_collection_v3`) |
| `PipeBoot` | Start-up animation for the Pipe interfaces (a match is struck and the bowl lights) |

## Other screens

| Class | What it is |
|---|---|
| `DrawerView` | All-apps drawer: search, category filter, long-press app menu (shortcuts, add to home or dock, hide, uninstall, app info) |
| `AppRepo`, `AppInfo` | Installed-app catalogue from `LauncherApps`, including work profiles. Live updates go to `AppRepo.Listener` (the activity) |
| `ShadeView` | console shade: toggles, brightness and volume, notification log (via `MediaListener`) |
| `NotificationActions` | REPLY (RemoteInput) and action keys under shade notifications; the console reply panel |
| `NotificationBadges` | Unread counts per app from `MediaListener`, drawn as a per-interface badge on each tile / hub node / dock key's overlay (`attach` hook in each home builder, `refresh` on change) |
| `DialerView` | Keypad with T9 contact matching, recents and contacts. Places a call with `ACTION_CALL` when allowed, otherwise hands the number to the phone app |
| `BootSequence` | The starship start-up sequences |
| `Stations` | Six simulated bridge consoles (`Ops`, `Conn`, `Tactical`, `Engineering`, `Science`, `Medical`) |
| `Cloak` | Timed night mode. `Cloak.Receiver` ends it from an alarm |
| `RedAlert`, `RedAlertLogic`, `RedAlertOverlay` | RED ALERT mode: state in Prefs, pure state machine/timeouts/pulse (JVM-tested), the pulsing per-interface frame with MUTE / STAND DOWN that MainActivity adds on top of every home tree. The klaxon loops through `Sfx.loopEvent(E_RED)`; `RedAlert.Receiver` is the backup stand-down alarm |
| `QsTiles`, `CloakTileService`, `RedAlertTileService` | Quick Settings tiles; `QsTiles.refresh` re-reads them when Cloak or RED ALERT change, `QsTiles.requestAdd` is CONFIG's API 33+ add-tile prompt |
| `ThermostatDialog`, `Nest` | Climate control; `ThermostatDialog.Backend` is a Nest device or a Home Assistant climate entity |
| `HomeAssistant`, `HomeAssistantDialog` | Home Assistant REST (`/api/states`, `/api/services/…`): entity import, tile taps, the light / switch / scene panel; address and token in the private `trekui_ha` preferences (excluded from backup and export) |
| `Govee` | Govee lights: LAN discovery (UDP multicast 239.255.255.250:4001, replies on 4002, commands to 4003) with the Govee cloud API as a fallback |
| `Http` | The only HTTPS client: refuses plain HTTP (except `requestAllowingLocalHttp` to `.local` / `.home.arpa` names for Home Assistant, mirrored by `res/xml/network_security_config.xml`), sets timeouts, caps reply size, runs on a background executor |
| `StatusWidget`, `QuickWidget` | Widgets for other launchers |
| `SettingsActivity` | CONFIG: one scrolling page with sections (INTERFACE, COLOR THEME, HOME LAYOUT, APP DRAWER, CHRONOMETER, WEATHER, AUDIO SYSTEMS, SMART DEVICES, NOTIFICATION SHADE, APP THEMING, DEFAULT HOME, ABOUT, …) |
| `PhoneSounds` | Exports a clip as the ringtone, notification or alarm sound |
| `Onboarding` | The first-run tour: one `ConsoleDialog` panel redrawn per stop, so it takes the interface's frame and key names (`Skin.nav`). `MainActivity.onResume` calls `Onboarding.maybeStart` (after the default-home welcome); CONFIG's REPLAY TOUR sets `tourReplay` and finishes |
| `LayoutArchive` | The layout as versioned JSON (`format: trekui-layout`, `schema`, typed `prefs`). `isExcluded` keeps credentials, device-bound and transient keys out of every file and untouched on apply; `parse` validates and `migrate`s (schema 0 = a flat prefs dump); `clearMissingApps` empties slots for apps not installed. Pure and JVM-tested |
| `LayoutSnapshots` | Automatic snapshots in `filesDir/layouts/` (`yyyyMMdd-HHmmss-SSS_reason.json`, newest 10 kept, duplicates skipped). `checkUpgrade` in `MainActivity.onCreate` snapshots on a versionCode change; CONFIG snapshots before reset, interface switch, import and restore |
| `LayoutBackupUi` | CONFIG › BACKUP flows: SAF export (`ACTION_CREATE_DOCUMENT`) and import (`ACTION_OPEN_DOCUMENT`), preview / APPLY, UNDO LAST CHANGE, SNAPSHOT NOW, RESTORE. Reads and writes on a worker thread |

## Sounds

```mermaid
flowchart LR
    EV[UI event / slot sound] --> SFX[Sfx<br/>SoundPool, rotation, volume rules]
    SFX --> SL[SoundLibrary<br/>ids, groups, imported clips]
    SL --> SC{SoundCache}
    SC -->|downloaded| CACHE[(filesDir/sounds/*.wav)]
    SC -->|personal build| RAW[res/raw]
    SC -->|nothing| SILENT[silence]
    DL[Sound downloader] -->|manifest.json:<br/>URL + SHA-256 + cut| CAT[SoundCatalog]
    DL --> PROC[SoundProcessor<br/>cut, mono, gain, fades, WAV] --> CACHE
    IMP[SoundImporter] --> LIBF[(imported clips, not backed up)]
```

- **No recordings ship with the app.**
  - `SoundCatalog` reads `assets/sounds/manifest.json`. For each sound id, it gives where the original is publicly downloadable, its SHA-256 checksum and how to cut it.
  - The downloader fetches the originals when the user asks, verifies them and renders them with `SoundProcessor`.
  - `SoundCache` resolves an id to a downloaded clip, to a `res/raw` clip (only in personal develop builds made from the gitignored `personal_sounds/`), or to silence.
- **Imports.** `SoundImporter` turns user files or ZIPs into clips. `SoundPicker` is the two-step group/sound chooser.
- **Playback.** `Sfx` plays clips. It respects silent mode, calls and the touch-sounds setting.

[docs/SOUNDS.md](SOUNDS.md) covers the catalogue in detail.

## Settings and persistence

- **`Prefs`** is a singleton over `SharedPreferences("trekui")`. It stores the interface, palette, tiles, dock, slot assignments, panel order, the weather location mode, and the device credentials the user enters (Govee API key, Nest OAuth tokens).
  - Any change that alters the home calls `bump()`, and the home rebuilds on its next resume.
- **CONFIG › SUITE** (LCA-T35, `SettingsActivity.suiteSection` + `SuiteSettings`): KEYBOARD (active input method, `InputMethodManager.showInputMethodPicker`, the enable screen), LOCK SCREEN (`TrekPrefs.lockscreenEnabled`, key `suiteLockscreen`, published on the suite provider's additive `lockscreen` path; `SuiteClient.launcherLockscreenEnabled` reads it) and MESSAGES (default SMS: `Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT` up to Android 9, `SuiteContract.ACTION_REQUEST_SMS_ROLE` sent to TrekUI Messages on 10+ since `RoleManager` only requests roles for the caller; falls back to the default-apps screen; the previous SMS app is kept in `suitePrevSms` for RESTORE). INSTALL opens `SuiteContract.marketUri` / `playUrl`. Picking a sub-theme or flipping LOCK SCREEN calls `SuiteProvider.notifyChanged` so running companions re-read at once.
- **The Pipe collection** has its own store (`PipeDataStore`).
- **Licensing state** has its own store (`licensing.PrefsKeyValueStore`).
- **Layout files and snapshots** (`LayoutArchive`, `LayoutSnapshots`) cover the `trekui` prefs minus credentials, device-bound and transient keys. Applying one replaces those layout keys, leaves the excluded ones alone and calls `bump()`.
- **Backups.** Android Auto Backup covers the prefs. Imported and downloaded sound files are excluded (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`).

## Licensing

`com.gungreeneyes.trekui.licensing` is self-contained. `MainActivity` calls only `Licensing`.

```mermaid
sequenceDiagram
    participant MA as MainActivity
    participant L as Licensing
    participant T as TrialClock
    participant E as Entitlement
    participant B as BillingManager (Play Billing)
    MA->>L: init(activity) in onCreate
    L->>B: connect, restore purchases
    B-->>E: owned / not owned
    MA->>L: onHomeResumed(activity)
    L->>E: unlocked?
    alt not unlocked
        L->>T: session expired? (monotonic clock, resets per boot)
        T-->>L: expired
        L->>MA: start PurchaseActivity (buy / restore / new session / switch home)
    end
```

- **Sessions:** the length is `BuildConfig.TRIAL_SESSION_MINUTES`: 1440 in production, 5 in develop.
- **Product:** `BuildConfig.UNLOCK_PRODUCT_ID` = `trekui_unlock`.
- **Interruptions:** the purchase screen only ever replaces the home screen, never another app.

Design notes are in [docs/MONETIZATION.md](MONETIZATION.md).

## Responsive layout

`ScreenMetrics.of(context)` is the one source of truth for sizing. It provides:

- Material 3 width and height size classes;
- `marginPx()` and `gutterPx()`;
- `uiScale`: the short side relative to a 360 dp phone, clamped to 0.85–1.6;
- `columns(minTileDp, min, max)`;
- `isTwoPane()`, `isTall()` and `isLandscape()`.

Layout code asks `ScreenMetrics` instead of hard-coding pixel sizes, column counts or orientation. The orientation lock is gone, and every screen must work in portrait and landscape, from small phones to tablets, on folded and unfolded foldables, and in multi-window. The maths is in a pure `compute` function and is unit-tested.

## Threading

- **Main thread:** all view work runs here, and the Imperial and Collective drawing helpers share one `Paint` on the UI thread.
- **Background executors:** network calls (`Http`), Govee sockets, sound downloads and decoding, and app loading. Results are posted back to the main thread.
- **Visibility:** panels refresh only between `resume()` and `pause()`.
- **No background services:** the app has none, apart from the notification listener, which Android binds only when the user grants notification access.

## Build-time structure

- **Flavors:** `develop` (`.dev` suffix, "TrekUI Dev", 5-minute trial) and `production` (`com.gungreeneyes.trekui`, "TrekUI"). See [BUILD.md](BUILD.md).
- **Assets:** generated by the scripts in `tools/` (Python and Node). Generated output is committed under `app/src/main/assets/`, so building needs no Python.

## Companion app: TrekUI Messages (`:messages`)

A separate application module (`com.gungreeneyes.trekui.messages`, same flavors and signing as `:app`) that depends only on `:core`. It is a complete default SMS/MMS app: Telephony provider reads/writes (`MessageStore`, `MmsStore`), `SmsManager` SMS and MMS sending with status receivers, its own MMS PDU codec (`mms/PduCodec`), notifications with inline reply, `BlockedNumberContract` blocking and dual-SIM choice. Screens extend `BaseScreen` and draw through `com.gungreeneyes.trekui.MessagesLook`, a public facade (in the `:messages` module, same Java package as `:core`'s package-private `Ui`, `Skin`, `Palette`, `ConsoleFrame`, `ConsoleDialog`). It follows the launcher's interface/sub-theme via `SuiteClient.launcherTheme` → `TrekPrefs.setThemeOverride`, registers `SuiteProvider` for the shared unlock, and answers the launcher's `SuiteContract.ACTION_REQUEST_SMS_ROLE` in `RequestRoleActivity`. Details: [MESSAGES.md](MESSAGES.md).

## Companion app: TrekUI Keyboard (`:keyboard`)

A separate application module (`com.gungreeneyes.trekui.keyboard`, same flavors and signing as `:app`) that depends only on `:core`. `TrekKeyboardService` (an `InputMethodService`, subtypes per language in `res/xml/method.xml`) hosts one Canvas view, `KeyboardView`, that paints the suggestion strip and the keys with the interface's own cell drawables through `com.gungreeneyes.trekui.KeyboardLook` (the same public-facade pattern as `MessagesLook`). The decisions are pure and unit-tested in `keyboard/logic`: `Layouts` (page tables per language), `ShiftState`, `Alternates`, `FieldRules` (input type → page, enter action, the incognito rule), `Suggestions`, `WordEdit`, `Sizing`. Learned words live in `WordStore` (`noBackupFilesDir`), the personal dictionary is read from `UserDictionary`. It follows the launcher's interface via `SuiteClient.launcherTheme` → `TrekPrefs.setThemeOverride` (`KeyboardTheme`, throttled), registers `SuiteProvider`, and runs licensing only in `KeyboardSettingsActivity`; the IME shows an UNLOCK chip instead of ever blocking input. The merged manifest has no `INTERNET` permission. Details: [KEYBOARD.md](KEYBOARD.md).
