# Google Play Console checklist

This is a step-by-step list for publishing **TrekUI Launcher** (`com.gungreeneyes.trekui`, the `production` flavor) on Google Play. The answers below come from the app as it is built today:

- the permissions in `app/src/main/AndroidManifest.xml`;
- the network code in `Http`, `Weather`, `Govee`, `Nest` and the sound downloader;
- Play Billing in `licensing/`.

If any of those change, re-check the matching section here.

> Play Console wording moves around. The section names below were current when this was written. If a question isn't covered here, answer it from what the app does: it keeps everything on the device, and its only network traffic is the requests the user turns on.

---

## 0. Before you start

- [ ] **Developer account.** You need a Google Play developer account (one-time US$25 fee) with identity verification completed.
  - **New personal accounts:** you must run a **closed test with at least 12 testers for 14 continuous days** before you can apply for production access. Plan for it: see [RELEASE.md](RELEASE.md#play-tracks).
- [ ] **Payments profile.** Set up a merchant (payments) profile with a bank account and tax info. You cannot sell the $0.99 unlock without one.
- [ ] **Read section 9 (IP risk) before choosing the store title and graphics.**

## 1. Create the app

**Home › Create app**

| Field | Value |
|---|---|
| App name | 30 characters max: **TrekUI Launcher**. See section 9. |
| Default language | English (United States), en-US |
| App or game | App |
| Free or paid | **Free**. The unlock is an in-app product. Free apps can never become paid later. |
| Declarations | Accept the Developer Program Policies and the US export laws declaration |

The **package name is fixed by the first upload**: `com.gungreeneyes.trekui`. The develop flavor (`.dev`) is never uploaded.

**First AAB.**

1. Build it with the release workflow, or locally with `./gradlew bundleProductionRelease` using the upload key.
2. Upload it by hand to **Testing › Internal testing**. The API cannot create an app's first release.
3. Accept **Play App Signing** (Google-managed app signing key). The key in `TREKUI_KEYSTORE_BASE64` is the **upload key**.

## 2. Monetize: the unlock product

Step-by-step Billing setup and test purchases: [PLAY_BILLING_SETUP.md](PLAY_BILLING_SETUP.md).

**Monetize › Products › In-app products** (this needs an uploaded AAB that declares `com.android.vending.BILLING`, which the app does).

| Field | Value |
|---|---|
| Product ID | `trekui_unlock`. This must equal `UNLOCK_PRODUCT_ID` in `app/build.gradle.kts`, and it can never be changed or reused. |
| Type | One-time product (non-consumable; the app acknowledges and never consumes it) |
| Name | TrekUI Unlock |
| Description | Use TrekUI as your permanent home screen. One-time purchase, no ads, no subscription. |
| Default price | US$0.99. Let Play convert it to local prices, then check the rounding. |
| Status | Active |

Also:

- [ ] **Licence testers.** Under **Setup › License testing**, add tester Gmail accounts so they can buy with test cards and get no charge.
- [ ] **Optional.** Under **Monetization setup › Licensing**, copy the **Base64 RSA public key** into `licensing/PurchaseVerifier.BASE64_PUBLIC_KEY` to turn on signature checks. It is a public key, so it is safe to commit.

## 3. App content (Policy › App content)

### Privacy policy

- [ ] URL: **<https://gungreeneyes.github.io/TrekUI.Java/privacy.html>**, published by `pages.yml` from [PRIVACY.md](../PRIVACY.md). Check that the link works before you submit. If the site has not published it yet, the GitHub URL of `PRIVACY.md` also works: <https://github.com/GunGreenEyes/TrekUI.Java/blob/main/PRIVACY.md>.

### Ads

- [ ] **No, my app does not contain ads.**

### App access

- [ ] **All functionality is available without special access.** There is no login. The trial runs for 24 hours per session, which is enough for review. Optionally add this note:

  > "Nest thermostat and Govee light controls need the user's own devices/accounts and are optional; everything else works offline without an account."

### Content rating (IARC questionnaire)

- **Category:** *All Other App Types*. A launcher is a utility/personalization app and is neither a game nor a social or communication app.
- Answer truthfully. For this app:

| Question area | Answer | Why |
|---|---|---|
| Violence | **No** for realistic violence. If asked about fantasy or cartoon violence: **No**, or *mild fantasy* to be cautious | The "bridge stations" are abstract toy consoles: a reticle and a shield bubble, marked SIMULATION, with no characters or harm shown |
| Sexuality, nudity | No | |
| Language (profanity) | No | |
| Controlled substances: **tobacco** | **Yes: references or depictions of tobacco** | The six Pipe Lounge interfaces are built around pipe smoking: a pipe register, tobacco blend catalogue, smoking journal and paintings of smoking rooms. There is no sale and no encouragement to minors |
| Alcohol, drugs | No (unless the Pipe artwork shows drinks; check the painted scenes) | |
| Gambling (real or simulated) | No | |
| User interaction: users can communicate or share content | No | No chat, no accounts, no user-generated content shared with others |
| Shares user location with other users | No | Weather coordinates go to a weather API, not to other users |
| Digital purchases | **Yes** | The $0.99 in-app unlock |
| Unrestricted internet / web browser | No | Web search hands off to the user's browser |

- **Expected result:** because of the tobacco references, probably **PEGI 12 / USK 12 / ESRB Teen**, not "Everyone". Ship the rating you get. Never under-declare to get a lower one; the rating is enforced, and a wrong one can get the app suspended.

> **Option:** if you want an "Everyone" rating, ship the Pipe Lounge interfaces in a separate listing or remove them from the production flavor. This is a product decision for the owner.

### Target audience and content

- [ ] **Age groups: 18 and over.** If you prefer, choose 16–17 and 18+.
  - Do **not** pick any group under 13. That puts the app under the **Families policy**, which a tobacco-themed app cannot meet.
  - The 18+ choice also fits the IP-licensing caution in section 9.
- [ ] **Unintended appeal to children:** answer *No*. The store listing must not use cartoon or child-oriented art.

### News app, COVID-19, government, financial features, health

- [ ] Not a news app. Not a COVID-19 app. Not a government app.
- [ ] Financial features: **none**. Health features: **none**.

### Data safety

These answers are derived from the code. "Collected" in Play's sense means data that is **sent off the device** by the app. Data that is only processed on the device does not count. Data sent to a **service provider** acting on the developer's behalf, or sent at the user's explicit request to a service they chose, is not "shared".

**Overview questions**

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes**. Approximate location and app interactions are sent ephemerally, see below. |
| Is all of the user data collected by your app encrypted in transit? | **Yes**. All internet traffic is HTTPS only (`Http` refuses plain HTTP, and the manifest sets `usesCleartextTraffic="false"`). Govee LAN commands stay inside the user's local network and are not "collected". |
| Do you provide a way for users to request that their data is deleted? | **Yes**. There is no account and no server. Everything is on the device: CONFIG settings, *Clear storage* in App info, or uninstalling deletes it all. Say so in the form. |

**Data types**

| Data type | Collected? | Shared? | Notes for the form |
|---|---|---|---|
| **Location › Approximate location** | **Yes**, only if the user picks "device location" for weather | No (Open-Meteo is the weather service the user turns on) | Purpose: **App functionality**. **Processed ephemerally: Yes.** **Optional**: the user can choose a city or turn weather off. The coarse (approximate) latitude/longitude Android gives the app, or the chosen city's coordinates, is sent to `api.open-meteo.com`. Nothing is stored server-side by the developer. |
| Location › Precise location | No | No | The app requests only `ACCESS_COARSE_LOCATION`. |
| **App activity › Other user-generated content** or **App info › Other** | Optional: declare **Yes** to be safe for the **city name** typed into weather search | No | Sent to `geocoding-api.open-meteo.com` to find coordinates; ephemeral; optional. |
| Personal info (name, email, address, phone number) | No | No | Contacts are read on the device for the dialer and never sent. |
| Contacts | No | No | Read on the device only (`READ_CONTACTS`). |
| Calendar events | No | No | Read on the device only. |
| Call logs | No | No | Read on the device only, and only if kept; see section 4. |
| Messages, photos, videos, audio files | No | No | Imported sound files stay on the device and are excluded from backup. |
| Files and docs | No | No | Pipe collection export/import writes to a file the user picks. |
| Health, fitness, financial info | No | No | |
| **Financial info › Purchase history** | **No**, by the developer | No | Google Play Billing processes the purchase under Google's own policies. The app gets an entitlement on the device and sends nothing to the developer. |
| App activity (app interactions, installed apps, search history) | No | No | The installed-app list is read on the device via `LauncherApps` and never leaves it. App search runs on the device; a web search opens the user's browser. |
| Web browsing | No | No | |
| App info and performance (crash logs, diagnostics) | No | No | No analytics or crash SDK. Play Console vitals come from Android/Google, not from the app. |
| Device or other IDs | No | No | No advertising ID or other identifiers. |

**User-initiated connections to third parties.** These are not "collected" by the developer, but describe them in the privacy policy:

- **Govee cloud API** (`openapi.api.govee.com`), only when the user enters their own Govee API key. It sends that key and on/off commands for the user's lights.
- **Google Nest / Smart Device Management** (`smartdevicemanagement.googleapis.com`, `www.googleapis.com/oauth2`), only after the user links their own Device Access project. It sends OAuth tokens and thermostat commands to Google.
- **Sound downloads**, only when the user taps DOWNLOAD SOUND LIBRARY. The app sends plain HTTPS GET requests to the public sites listed in the sound manifest and to GitHub. No user data is attached.

If Play's form asks whether the data is "shared" for these, answer **No**. They are transfers the user starts, to a service the user chose and signed into.

## 4. Sensitive permissions

| Permission | Play status | What to do |
|---|---|---|
| `QUERY_ALL_PACKAGES` | **Not used** | Nothing to declare. App lists come from `LauncherApps`, and visibility is declared with `<queries>` (launcher, dial, browser, geo, web search, camera, media browser services, three named media apps). A HOME launcher would qualify for an exception anyway, but none is needed. |
| `READ_CALL_LOG` | **Restricted** (SMS and Call Log policy) | See below. **Needs a decision before the first production release.** |
| `CALL_PHONE` | Dangerous (runtime prompt), **not restricted** | No declaration form. It is used only after the user allows "make phone calls" in the console dialer. Without it, numbers are handed to the phone app. Emergency numbers always go through the phone app. |
| `READ_CONTACTS`, `READ_CALENDAR`, `ACCESS_COARSE_LOCATION` | Dangerous, runtime prompt | No form. Each is optional and asked for when first used. Explain them in the description. Foreground location only, so no background-location declaration. |
| `WRITE_SETTINGS` | Special app access | No form. The user grants it in system settings (brightness, auto-rotate, setting a ringtone). |
| Notification listener (`MediaListener`) | Special app access | No form. It is used for track info in the media panel and the shade's notification list; nothing is stored or sent. Say so in the description. |
| `REQUEST_DELETE_PACKAGES` | Normal | No form. It is for the drawer's Uninstall option; Android always asks the user to confirm. |
| `EXPAND_STATUS_BAR`, `SET_WALLPAPER`, `CHANGE_WIFI_MULTICAST_STATE`, `ACCESS_NETWORK_STATE`, `INTERNET`, `BLUETOOTH` (≤ API 30), `BILLING` | Normal | Nothing to declare. |

### Accessibility API disclosure (LOCK SCREEN, LCA-T20)

The optional **LOCK SCREEN** function uses an `AccessibilityService` (`LockService`) only to call `performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)` (Android 9+). It is off until the user turns it on. There is **no Device Admin / `DevicePolicyManager` fallback**.

- **What the service can do.** `res/xml/lock_service.xml` subscribes to no accessibility events and sets `canRetrieveWindowContent="false"`, so it never sees screen content, typing or taps. `onAccessibilityEvent` is empty.
- **Play Console › App content › Accessibility API.** Declare the API use. The app is **not** an accessibility tool (do not set `isAccessibilityTool`). Use: "Locks the screen when the user taps a launcher tile, element or gesture they assigned to LOCK SCREEN. No data is collected, observed or shared." Attach a short video of the flow: assign LOCK SCREEN, tap it, see the disclosure, AGREE, enable *TrekUI Screen Lock* in Accessibility settings, tap again and the phone locks.
- **Prominent disclosure.** `LockScreen.showDisclosure` shows this text in-app *before* sending the user to Accessibility settings, every time LOCK SCREEN runs while the service is off and from CONFIG › GESTURES › SET UP LOCK SCREEN. Only **AGREE • OPEN SETTINGS** continues; **NO THANKS** changes nothing. Keep this copy and `lock_disclosure_body` in `res/values/strings_groups_gestures.xml` identical:

  > **LOCK SCREEN • ACCESSIBILITY**
  >
  > TrekUI Launcher uses Android's Accessibility Service API for one purpose only: to lock your screen when YOU run the LOCK SCREEN function from a tile, element or gesture you set up.
  >
  > The service cannot see or read what is on your screen, does not observe what you type or tap, and does not collect, store or share any data. It performs no action other than locking the screen when you ask.
  >
  > If you agree, Android's Accessibility settings open: choose "TrekUI Screen Lock" and turn it on. You can turn it off there at any time; LOCK SCREEN then stops working and nothing else changes.
  >
  > [AGREE • OPEN SETTINGS]  [NO THANKS]

- **Accessibility settings description** (`lock_service_description`): "Lets TrekUI Launcher lock the screen when you use its LOCK SCREEN function (a tile, element or gesture you assigned). It cannot read screen content, does not observe your actions, and collects no data."
- **Data safety.** Nothing changes: the service collects and shares no data.
- **Listing.** Mention it in the full description, for example: "Optional LOCK SCREEN uses Android's accessibility service only to lock the screen when you ask; it reads nothing."
- **Risk.** Google reviews non-accessibility-tool uses of the API case by case. If the declaration is rejected, remove `LockService` from the production manifest with a `tools:node="remove"` overlay (as for `READ_CALL_LOG`); LOCK SCREEN then shows "needs the service" and everything else is unaffected.

### `READ_CALL_LOG`: the options

Google Play allows the Call Log permissions only for apps that are the user's **default Phone (dialer), SMS or Assistant handler**, or that fit a listed exception. The exceptions are things like backup and restore, caller ID and spam blocking, connected-device companion apps, device automation, and in-vehicle use. A launcher with a built-in dialer that is *not* the default phone app fits none of them. A declaration that says "launcher dialer shows recent calls" will very likely be rejected, and the release blocked.

| Option | Effort | Result |
|---|---|---|
| **A. Remove `READ_CALL_LOG` from the production build** (recommended) | Small | Add `app/src/production/AndroidManifest.xml` with `<uses-permission android:name="android.permission.READ_CALL_LOG" tools:node="remove"/>`. In production, hide the dialer's RECENTS tab, or show a "RECENT CALLS: OPEN PHONE APP" key that fires an intent to the system call log. The keypad (with contact matching), CONTACTS and calling all keep working. The develop/sideload build keeps full RECENTS. |
| B. Become a full default dialer | Large | The app must hold `ROLE_DIALER` and implement an `InCallService` with a complete in-call UI (incoming calls, audio routing, emergency calls). Only then may it request `READ_CALL_LOG`. This is a big, risky scope increase for a launcher. |
| C. Submit the Permissions Declaration Form anyway | Small | Very likely rejected. It also delays every release while under review. |

**Recommendation: A.** `CALL_PHONE` can stay. If you keep it, describe it honestly in the listing ("the console dialer can place calls directly if you allow it").

> The manifest belongs to the core UI code. Option A means adding a `production` manifest overlay and a small UI change in `DialerView`. These are tracked as follow-up work, not done in the docs.

## 5. Store listing (Grow › Store presence › Main store listing)

The listing text lives in `fastlane/metadata/android/en-US/`. The release workflow can push it with `update-listing: true` ([RELEASE.md](RELEASE.md)).

| Element | Limit / size | Notes |
|---|---|---|
| App name | 30 characters | See section 9 |
| Short description | 80 characters | e.g. "Starship-console home screen: 9 interfaces, live panels, no ads." |
| Full description | 4000 characters | Features, then the permission explanations, then the one-line originality notice ("an independent, original design; ships no third-party recordings or artwork") |
| App icon | **512 × 512** PNG, 32-bit with alpha, ≤ 1 MB | Full square. Play applies the mask and shadow. Original art only: no third-party logos or insignia |
| Feature graphic | **1024 × 500** JPG or 24-bit PNG (no alpha) | Required |
| Phone screenshots | **2–8** (aim for 8). JPG or 24-bit PNG, each side 320–3840 px, long side no more than 2× the short side | To be eligible for promotion: at least 4 screenshots of at least 1080 px on the short side, e.g. 1080 × 1920 or 1080 × 2400 |
| 7-inch tablet screenshots | up to 8, same format rules | Recommended: the app supports tablets. Source: the emulator matrix tablet runs |
| 10-inch tablet screenshots | up to 8 | Same |
| Chromebook, Wear, TV, XR | – | Not applicable |
| Promo video | YouTube URL | Optional. The CI-captured MP4s are a good source |

- **Screenshot source.** The CI emulator runs write screenshots to `assets/<device>-api<N>/`, and `scripts/release/fill-play-screenshots.py` copies a selection into `fastlane/metadata/android/en-US/images/{phoneScreenshots,sevenInchScreenshots,tenInchScreenshots}`.
- **Interface coverage.** Show at least one Command, one Imperial, one Collective and one Pipe Lounge interface.

**Store settings**

| Field | Value |
|---|---|
| Category | **Personalization** |
| Tags | Launcher, Home screen, Theme, Customization |
| Contact email | a project email, required and shown publicly |
| Website | <https://gungreeneyes.github.io/TrekUI.Java/> |

## 6. Pricing and distribution

- [ ] **Countries/regions:** all, or a chosen list.
- [ ] **Price:** free, with the in-app product `trekui_unlock` at US$0.99 (section 2). No subscriptions and no ads.
- [ ] **Devices:** phones, tablets and foldables. Exclude Wear OS, TV and Auto if Play offers them; the manifest has no leanback or watch features.
- [ ] **Programs:** do not opt into *Designed for Families*. You may skip Google Play Pass.
- [ ] **Managed Google Play / enterprise:** optional.

## 7. Release

1. **Testing › Internal testing:** create the first release by hand, then add the testers' emails.
2. **Testing › Closed testing:** this is required for new personal accounts: 12 or more testers for 14 continuous days. Collect feedback.
3. **Production access:** apply in the Dashboard once Play says the account is eligible.
4. **Production:** promote a tested build, using a **staged rollout** (for example 20%, then 50%, then 100%).
5. Later releases go through the release workflow: tag `v*`, the build lands on internal, then you promote ([RELEASE.md](RELEASE.md)).

## 8. Final pre-submit checklist

- [ ] The privacy policy URL works and matches the Data safety answers
- [ ] Data safety is complete, matching section 3
- [ ] There is no `READ_CALL_LOG` in the production AAB, or a decision was made under section 4
- [ ] The Accessibility API declaration for `LockService` (LOCK SCREEN) is submitted with its video, and the in-app disclosure matches section 4
- [ ] Content rating is done (tobacco references declared) and target audience is 18+
- [ ] Ads: No. App access: all functionality available.
- [ ] `trekui_unlock` is active at US$0.99, and a licence-tester purchase and restore both work
- [ ] The listing has the icon, feature graphic, 4 or more phone screenshots and tablet screenshots, plus the originality notice in the description
- [ ] No recordings are in the AAB. Check with `unzip -l app-production-release.aab | grep -E '\.(mp3|ogg|wav)$'`, which should print nothing.
- [ ] `targetSdk` meets Play's current target API requirement (37 today)

## 9. Intellectual property and trademark risk

This is the biggest risk to the listing. Read it before choosing the name and graphics.

- **Trademarks.** The app, listing, site and graphics use only this project's own names (TrekUI, Command, Imperial, Collective, Classic Console, Next Console, Pipe Lounge) and original art. Keep it that way: no third-party franchise names, logos, insignia, ship names, screenshots or likenesses anywhere, including keywords and screenshots. Google Play's **Intellectual Property** and **Impersonation** policies let rights holders file complaints, and repeated strikes can terminate the developer account.
- **Name check.** Before the first upload, run a trademark search for "TrekUI" in the target markets.
- **Audio.** The app ships no recordings. The **in-app sound download** fetches third-party recordings from public sound sites at the user's request, and a reviewer or rights holder may see that as facilitating infringement. For the safest listing, keep the download as an opt-in with a clear notice, or turn it off in the production flavor, leaving "import your own sounds" as the only path.
- **Warrior Blade** is the project's own font, designed from scratch.
- **Pipe Lounge:** no third-party IP. The art comes from the owner's PIPE_COLLECTION set, and the fonts are OFL or Apache. The tobacco theme affects the content rating (section 3), not IP.
- **Fallback plan.** If the listing is taken down, the GitHub Releases APK and the GitHub Pages site stay available for sideloading.
