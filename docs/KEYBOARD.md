# TrekUI Keyboard

`:keyboard` is an input method (on-screen keyboard), `com.gungreeneyes.trekui.keyboard` (develop flavor
`com.gungreeneyes.trekui.keyboard.dev`), drawn in the TrekUI interfaces. It depends on `:core` for the look
(cells, console frame, fonts), the synthesized key sounds, licensing and the suite contract, and works with or
without the launcher installed.

## Features

- **Every interface:** keys are painted with the active interface's own cell drawables (`Ui.shape` /
  `Skin.cell`), so they look like the launcher's controls in Command, Imperial, Collective, Classic Console
  and Next Console, in the chosen sub-theme. With the launcher installed the keyboard
  follows its interface and sub-theme (`SuiteClient.launcherTheme`, re-read at most every 15 s while typing
  and on every resume of the settings screen); without it, settings › INTERFACE has its own picker.
- **Pages:** letters, two symbol pages (`?123`, `=\<`), a number pad (number and date/time fields) and a dial
  pad (phone fields), chosen from the field's `EditorInfo.inputType`. E-mail fields get `@` and web-address
  fields `/` next to the space bar. A small built-in emoji page.
- **Languages:** English (QWERTY), German (QWERTZ with ü ö ä), French (AZERTY), Spanish (QWERTY with ñ). Each
  is a subtype in `res/xml/method.xml`; the ones switched on in settings › LANGUAGES are cycled by the globe
  key and by a long press on the space bar. Choosing a subtype in Android's own picker switches too.
- **Typing:** shift (one letter), caps lock (double tap; the arrow gets a bar), automatic capitals where the
  field asks for them; long press for accents (the language's own first) and symbol variants, top-row letters
  carry their digit; slide along the space bar to move the cursor; swipe left from DELETE to delete whole
  words (further left, more words); hold DELETE to repeat; a second finger types the first key at once.
- **Enter key** labelled per the field's action: GO, SEARCH, SEND, NEXT, DONE, PREV, or the return arrow
  (newline) when the field has no action or sets `IME_FLAG_NO_ENTER_ACTION`.
- **Suggestion strip:** up to three completions of the word being typed from the personal dictionary
  (`UserDictionary`) and from words typed on this keyboard. Completions only (no autocorrect): a suggestion
  never changes a word unless it is tapped.
- **Emoji:** the emoji key opens the built-in page; a long press hands over to another enabled keyboard for
  its full emoji picker (Android has no system emoji picker an input method can open).
- **Voice:** a microphone key appears in the strip only when an enabled keyboard offers a voice subtype
  (e.g. a voice-typing app); it switches to that keyboard.
- **Sound and feel:** key clicks are the interface's synthesized tap clips from `:core` (`Sfx`, sound set
  SYNTHESIZED by default), silent when Android's touch sounds are off, in Silent/Vibrate and during calls, with
  the keyboard's own volume slider. Vibration uses `HapticFeedbackConstants.KEYBOARD_TAP`, so Android's
  touch-feedback setting applies; the keyboard has its own switch too.
- **Size:** S / M / L / XL key height, separately for portrait and landscape; in a short landscape window the
  keys never take more than half the height. One-handed mode (left or right, 82 % width; tap the empty side to
  move it). Optional number row.
- **Setup:** the settings screen (the app's launcher entry) walks through STEP 1 enable (Android's keyboard
  list) and STEP 2 select (Android's keyboard picker), shows READY for each finished step, and has a field to
  try the keyboard.

## Licensing

Same model as every TrekUI app: free download, 12-hour trial sessions, one
`trekui_unlock` purchase, and **one purchase unlocks every TrekUI app** (sibling unlocks are read from the
signature-protected `SuiteProvider` and cached). The settings activity runs `Licensing.init` /
`onAppResumed`. The keyboard itself **never blocks typing**: when a session is over, a slim UNLOCK chip appears
at the right of the suggestion strip and opens the purchase screen. A keyboard that stops working would lock
people out of their phone, so this is deliberate.

## Privacy

- **No network.** The app has no `INTERNET` permission (removed from the merged manifest, see below); nothing
  typed can leave the device.
- **No keystroke logging.** Keystrokes are passed straight to the app being typed in.
- **Learned words** (word + use count, nothing else: no context, no time stamps) live in
  `noBackupFilesDir/typed_words.tsv` in the app's private storage, capped at 3000 words. Only words of 2-32
  letters are counted; numbers and codes never are. Backups and device transfers exclude all app data
  (`allowBackup=false`, `data_extraction_rules.xml`). settings › FORGET LEARNED WORDS deletes the file;
  uninstalling deletes everything.
- **Incognito:** nothing is learned and no completions are shown in password fields (text, visible, web and
  numeric passwords) or when the app sets `IME_FLAG_NO_PERSONALIZED_LEARNING` (incognito tabs, private chats),
  or `TYPE_TEXT_FLAG_NO_SUGGESTIONS`; e-mail and web-address fields get no completions either
  (`FieldRules.noLearning` / `suggestionsAllowed`).
- **Personal dictionary:** read on the device only while the keyboard is the current keyboard (Android allows
  only the current keyboard to read it); never written.

### Play Console data safety

- Data collected: **none** (nothing is transmitted off the device; on-device processing is not "collection").
- Data shared: **none**.
- Security practices: data is not transmitted, so "encrypted in transit" does not apply; users can delete
  learned words in the app.
- Android shows its standard notice when a keyboard is enabled ("may be able to collect all the text you
  type"); setup STEP 1 explains that this keyboard has no internet access.
- Permissions: the suite permission (signature-level), `com.android.vending.BILLING`, and
  `BIND_INPUT_METHOD` guarding the service. Play Billing's transitive telemetry library (datatransport) asks for
  `INTERNET` and `ACCESS_NETWORK_STATE`; the keyboard's manifest strips both (`tools:node="remove"`). Billing
  itself talks to the Play Store app over IPC, so buying and restoring still work. Check the merged manifest
  after any Billing upgrade.

## Code

| Class | Role |
|---|---|
| `TrekKeyboardService` | `InputMethodService`: page per field, typing through the `InputConnection`, shift, strip, languages, emoji/voice hand-over, the UNLOCK chip |
| `KeyboardView` | One Canvas: suggestion strip + keys, touch (rollover, long-press popup, delete repeat and swipe, space-bar cursor), one-handed side |
| `KeyIcons` | Line-art glyphs for shift, delete, return, globe, emoji, microphone |
| `KeyboardSettingsActivity` | Setup steps and settings in the console frame (CONFIG vocabulary) |
| `KeyboardPrefs`, `KeyboardTheme`, `WordStore`, `KeyboardApp` | Settings (`kb.*` keys in the app's `trekui` prefs), following the launcher's theme, learned words + dictionary, `CoreConfig.Provider` |
| `com.gungreeneyes.trekui.KeyboardLook` | `internal object` facade over `:core`'s look and sound helpers (like `MessagesLook`) |
| `logic.*` | Pure, unit-tested: `Layouts` (tables), `Key` / `KeyLayout`, `Language`, `Alternates`, `ShiftState`, `FieldRules` (input type → page, enter action, incognito rule), `Suggestions` (ranking, learnable words), `WordEdit` (current word, delete-word), `Sizing` (presets, language cycle) |

Unit tests: `keyboard/src/test/kotlin/com/gungreeneyes/trekui/keyboard/logic/`.

## Not yet done

- TalkBack: the keys are one Canvas view without virtual accessibility nodes yet (needs an
  `AccessibilityNodeProvider` with one node per key and lift-to-type on hover exit).
- Live theme switch while the keyboard is open: the launcher calls `SuiteProvider.notifyChanged`; the keyboard
  re-reads on the next field (throttled) instead of registering a `ContentObserver`.
- Learned words are shared by all languages (no per-language word lists).
