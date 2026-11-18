# TrekUI Lock Screen

TrekUI Lock Screen (`:lockscreen`, `com.gungreeneyes.trekui.lockscreen`; develop flavor `.dev`) is a wake screen. When the screen turns on while the phone is locked, it shows a console drawn in the user's TrekUI interface and sub-theme, over Android's own lock screen. It shows a big clock, the date, a decimal date, the next alarm, battery, media controls, the notification list, CAMERA and TORCH. Swipe up, or tap the UNLOCK bar, to hand over to Android's keyguard.

It is **a look, not a lock**. It never replaces, bypasses or weakens the system keyguard.

## Design

| Piece | Class | What it does |
|---|---|---|
| Trigger + data | `LockListener` (`NotificationListenerService`) | While the system has it bound, it registers a dynamic `SCREEN_ON` / `SCREEN_OFF` / `USER_PRESENT` receiver. It also rebuilds the filtered, redacted notification list into `LockHub` (in memory only) and observes the launcher's SUITE › LOCK SCREEN switch. |
| Wake screen | `WakeActivity` + `WakeView` | `setShowWhenLocked(true)` / `setTurnScreenOn(true)` on API 27+, and the equivalent window flags on API 26. `singleTask`, its own task affinity, excluded from recents. `WakeView` draws everything on a Canvas and exposes its controls to TalkBack as virtual views (`AccessibilityNodeProvider`). |
| Settings / setup | `SettingsActivity` (launcher icon) | WAKE SCREEN switch, NOTIFICATION ACCESS setup (a prominent disclosure first), DISPLAY OVER OTHER APPS (Android 10+), SENSITIVE CONTENT, INTERFACE / COLOR THEME (only when the launcher is absent), PREVIEW and the licence. Licensing runs only here. |
| Theme | `LockTheme` | Follows the launcher via `SuiteClient.launcherTheme` → `TrekPrefs.setThemeOverride`, with the last answer cached for a cold start. This is the same model as Messages and Keyboard. |
| Look | `com.gungreeneyes.trekui.LockLook` | An `internal object` facade over `:core`'s `Skin`, `Palette`, `Ui`, `ConsoleFrame` and `ConsoleDialog`, the same pattern as `MessagesLook`. |
| Pure rules (JVM-tested) | `logic/DecimalDate`, `Redaction`, `EnableRule`, `WakeTrigger` | The formula, privacy, switch resolution and debounce. |

### When it shows

`WakeTrigger.onScreenOn` lets the wake screen through only when all of these hold:

- `EnableRule` says ACTIVE (see below).
- The keyguard is showing (`KeyguardManager.isKeyguardLocked`), so an unlocked phone is never covered.
- No call is ringing or active (`AudioManager.getMode`, which needs no permission).
- The wake screen isn't already in front.
- At least 1.5 s have passed since the last accepted screen-on. This folds power-key bounces and proximity flicker into one.

If the user unlocks some other way (fingerprint on the keyguard, face, Smart Lock), `USER_PRESENT` closes the wake screen. `onResume` also closes it whenever the keyguard is no longer locked.

### On / off

`EnableRule.resolve(launcherFlag, ownSwitch, hasAccess)`:

1. If the launcher's CONFIG › SUITE › LOCK SCREEN switch reads FALSE, the wake screen is **off**, whatever the app's own switch says.
2. If the app's own WAKE SCREEN switch (default on) is off, the wake screen is **off**. When the launcher is absent, untrusted or too old (`SuiteClient.launcherLockscreenEnabled` returns null), only this switch counts, so the app works on its own.
3. If notification access is missing, it **needs setup**.
4. Otherwise it is **active**.

The launcher's switch is read across processes, so it is cached. It is refreshed when the listener connects, on every screen-off, whenever the launcher calls `SuiteProvider.notifyChanged` (through a `ContentObserver`), and when settings open. A wake never waits on IPC.

### Security: why it never weakens the lock

- The activity only *occludes* the keyguard (`showWhenLocked`). It never calls a dismiss-without-credentials API, holds no Device Admin, and has no AccessibilityService.
- Every way past it calls `KeyguardManager.requestDismissKeyguard`, so Android asks for the PIN, pattern, password or biometric. A notification's `contentIntent`, the purchase screen and the regular camera run only in `onDismissSucceeded`. Cancelling the bouncer leaves the wake screen up.
- CAMERA uses `MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE`, the camera app's own locked mode, which cannot browse the gallery. If a device has no secure camera, the regular camera opens only after a real unlock.
- TORCH only calls `CameraManager.setTorchMode`, which needs no permission.
- Media keys send play, pause, previous and next to the active session, as Android's own lock screen does.
- BACK just finishes the activity, and the system keyguard is still there.
- The licence never gates unlocking. After a trial session ends, a small **UNLOCK APP** chip appears. It opens the purchase screen ("One purchase unlocks every TrekUI app.") only after the device has been unlocked.

### Privacy of the notification list

`Redaction.decide` is never less private than Android's own lock screen:

1. If the system's "notifications on lock screen" setting (`lock_screen_show_notifications`) is off, nothing is listed.
2. The effective visibility is the more private of `Notification.visibility` and the channel's `getLockscreenVisibility()`.
3. `VISIBILITY_SECRET` notifications are never listed.
4. `VISIBILITY_PUBLIC` notifications are shown in full.
5. `VISIBILITY_PRIVATE` (the default) notifications are shown in full only when one of these holds:
   - the keyguard is not secure, or
   - the app's **SHOW SENSITIVE CONTENT** switch (default **off**) is on **and** the system does not forbid sensitive content (`lock_screen_allow_private_notifications`).

   Otherwise the list shows the app name plus the app's own `publicVersion` text, or "Contents hidden".

These system values are hidden Secure settings. Where Android does not let apps read them, the show setting counts as on and the sensitive-content setting counts as unknown, so only the app's own switch (off by default) decides. Private text is dropped in `LockListener` before it reaches `LockHub`, so the view never holds it. Group summaries, media-session notifications (shown as media controls instead), minimised channels (importance below LOW), suspended apps and the app's own notifications are skipped.

### Decimal date (original formula)

`value = (year - 2000) * 1000 + 1000 * elapsed / yearLength`. Here `elapsed` is the time since 1 January 00:00 local time, and `yearLength` is 365 or 366 days. Each year spans exactly 1000 units, so one unit is about 8.8 hours and one tenth is about 53 minutes. The value is shown truncated to one decimal, for example `26878.0` at noon on 17 November 2026. It only grows through a year and never shows the next year early.

## Play policy reasoning: why this trigger

`ACTION_SCREEN_ON` cannot be declared in the manifest. Something has to be alive to register a receiver for it. These are the options:

| Option | Verdict |
|---|---|
| **NotificationListenerService + dynamic receiver** (chosen) | The app needs notification access anyway, for its list and media sessions. The system keeps a granted listener bound, so the app has no foreground service, no persistent notification and no FGS type to justify. Notification access is a special access the user grants in system settings. Play needs no declaration form for it, only a prominent disclosure and an honest listing. |
| Foreground service with a receiver | Needs a permanent notification and an FGS type. None of Android 14's types (`specialUse` aside) fits "wait for screen-on", and `specialUse` gets reviewed. Not needed, so not used. |
| AccessibilityService | Play reviews non-accessibility-tool uses case by case, and it would be a far broader capability than this needs. Never used. |
| Device Admin | Deprecated for this kind of use, and it gives powers that a cosmetic layer must not have. Never used. |
| Full-screen-intent notification | Since Android 14, Play limits `USE_FULL_SCREEN_INTENT` to calling and alarm apps. Not used. |

**Background activity starts (Android 10+).** A notification listener alone is not on Android's list of background-start exemptions. The documented exemption the app can use is **"Display over other apps"** (`SYSTEM_ALERT_WINDOW`), and settings offer it as an optional step. The app never draws an overlay window. The permission only lets the listener start the wake screen. Without it, on Android 10+ the system may block the start; nothing breaks, and the normal lock screen shows. `SYSTEM_ALERT_WINDOW` is a special access the user grants in system settings. Play needs no declaration form for it.

## Play Console texts

**Prominent disclosure** (`disclosure_body` in `lockscreen/src/main/res/values/strings.xml`; keep both identical). It is shown before the notification-access screen on first launch and from SET UP:

> **NOTIFICATION ACCESS**
>
> TrekUI Lock Screen asks for notification access to show your wake screen. With it, the app can:
>
> • notice when the screen turns on, so the wake screen appears over the lock screen;
> • list your notifications on the wake screen (private ones only as the app name unless you choose otherwise);
> • show media controls for what is playing.
>
> Notifications are read only on this device, kept only in memory while shown, and never stored, shared or sent anywhere. You can turn access off in Android's settings at any time.
>
> [AGREE • OPEN SETTINGS]  [NO THANKS]

**Listing sentence:** "TrekUI Lock Screen uses notification access to appear when your screen turns on and to show your notifications and media controls on its wake screen. Unlocking always uses your phone's own PIN, pattern, password or biometric. Nothing it reads leaves your device."

**Data safety** (Play Console › App content › Data safety):

- **Data collected:** none. **Data shared:** none.
- Notification content, media metadata, battery state and the clock are read **on the device only**. They are held in memory while the wake screen shows, never written to storage (only the app's own switches and theme choice are stored, in private preferences, with backup off), and never sent.
- The app has no analytics, ads or crash-reporting SDKs. The only network use is Google Play Billing for the one-time unlock, which Google handles.
- Encryption in transit: not applicable (no app data is sent).
- Deletion: uninstalling removes everything; there is nothing server-side.

## Follow-ups (not in LCA-T33)

- **Device check:** confirm on real Android 10, 12, 14, 15 and 16 devices that the listener's start goes through, with and without "Display over other apps". If an OEM blocks it even with the grant (some add their own "pop-up windows while running in background" switch), add that OEM's settings shortcut to the setup flow. Record the results here.
- **Notification actions:** the list opens a notification's `contentIntent` after unlock, but does not yet show its action buttons, inline reply or swipe-to-dismiss (`cancelNotification`).
- **Album art and app icons:** rows use an accent bar and the app name. Small icons and media artwork are not drawn yet.
- **Launcher badge:** the launcher's SUITE row could show "needs access / needs overlay" by reading a new `lockscreen_status` suite path. That needs an additive `SuiteContract` path in `:core`.
- **Robolectric screenshots** of `WakeView` in each interface family × portrait/landscape × phone/tablet for the capture matrix.
