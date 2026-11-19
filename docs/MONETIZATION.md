# Monetization

TrekUI is free to download and has no ads. An unlicensed copy has every feature, but each
**session** ends after `TRIAL_SESSION_MINUTES` (24 h in production). The launcher then shows a screen
explaining that a one-time **$0.99** purchase is needed to use it as a permanent launcher.
Restarting the launcher, or the device, starts a new session.

This page covers what other launchers do (researched 2026-11-17), the Play policy constraints
that apply, the design we chose, and how to change it.

---

## 1. What other launchers do

Prices are from the US Google Play listings fetched on 2026-11-17 unless a note says otherwise.
"Unverified" means we could not tie the figure to that app with certainty.

| Launcher | Model | Price | Notes |
|---|---|---|---|
| Nova Launcher | Free tier plus Prime. The free app is now flagged "Contains ads". | The "Lifetime Prime" key app is $29.99 one-time. Press in Feb 2026 reported a $4.99/yr subscription with a 7-day trial for new users; we could not confirm it is still offered. | Owned by Branch from 2022. Bought by Instabridge around Jan 2026. Existing Prime licences stay ad-free. [1][2][3] |
| Niagara Launcher | Free tier with no ads, plus Pro. | $24.99/yr or $74.99 lifetime. The help centre still lists older prices of $13.99/yr and $42.99. | Pro adds themes, icon/font customisation, widgets and more. No in-app trial found. [4][5] |
| Smart Launcher 6 | Free tier plus Pro, sold as lifetime or subscription. | Varies by country. A deal post showed about $20.99 lifetime (unverified). | [6] |
| Action Launcher | Free, "Contains ads". Plus is a one-time unlock, plus an optional supporter badge. | Plus about $7 (unverified). | About half the Plus features became free in v50 (2022). [7][8] |
| Hyperion | Free tier plus in-app Pro unlock. "Hyperion Supreme" is a separate paid app. | Supreme is $1.99. | [9] |
| Total Launcher | Free tier plus a separate "Total Launcher Key" app. | Key is $7.99 one-time. | The key unlocks extra pages, gestures and more. [10] |
| Lawnchair | Free and open source. No ads, no in-app purchases. | $0 | [11] |
| Kvaesitso | Free, GPLv3. Not on Google Play (F-Droid and GitHub only). | $0 | [12] |
| Olauncher | Free. No ads, no in-app purchases. | $0 | Its developer sells a separate "Pro Launcher" with in-app purchases of $9.99 to $24.99 (model unverified). [13][14] |
| A paid series of themed console launchers | Paid up front, with no in-app purchases and no ads. | $4.49 each | The only console-style launchers we found on Play. Possibly tied to Total Launcher (unverified). [15] |

**What this shows**

- None of the major launchers uses a "fully functional per session, then a purchase screen" model.
  The usual model is a permanently free tier with features held back, then a one-time or
  subscription unlock.
- Nova's trial is a Play-managed subscription free trial, not a time lock inside the app.
- At $0.99 one-time, TrekUI is the cheapest paid unlock in this set. The closest are Hyperion
  Supreme ($1.99) and the themed console launchers ($4.49 up front).
- Subscriptions (Nova, Niagara, Smart Launcher) draw complaints and need extra disclosure. A
  one-time unlock suits a niche themed launcher better.

## 2. Google Play constraints

| Topic | What it requires | How the design meets it |
|---|---|---|
| **Payments** [16] | Digital features must be sold through Google Play Billing, and prices and terms must be clearly disclosed. | We sell a single managed one-time product, `trekui_unlock`, through Play Billing Library 9. The price comes from Play (`getFormattedPrice`). |
| **Deceptive behaviour, behaviour transparency** [17] | No "undocumented or hidden features … designed to be activated … after a certain amount of time". The listing must match what the app does. | The session limit is explained on the purchase screen. **It must also be stated in the store listing** (fastlane metadata, owned by CI), for example: "Free to try: every feature works for 12-hour sessions; a one-time $0.99 purchase removes the limit. No ads." |
| **Device and network abuse** [18] | Apps must not "interfere with, disrupt, damage" the device, and must not use full-screen intents to force interaction. | The screen appears only when the TrekUI home screen itself comes to the front, never over another app. It always offers **USE ANOTHER HOME APP** (`Settings.ACTION_HOME_SETTINGS`, falling back to `ACTION_SETTINGS`) and **NEW SESSION**. Recents, the notification shade, the lock-screen emergency dialer and Back keep working. Nothing on the device is changed. |
| **Subscriptions policy** [19] | Only applies to subscriptions. | Not used. |
| **Billing Library version** [20] | New apps and updates need PBL 8 or later from Aug 31 2026 (extension to Nov 1 2026). PBL 9 is accepted until Aug 31 2028. | We use `com.android.billingclient:billing:9.1.0`. |
| **Pending purchases and acknowledgement** [21] | Pending purchases must be enabled for one-time products. Entitlement is granted only in the PURCHASED state. Purchases must be acknowledged within 3 days or they are refunded. Purchases must be queried on start and resume. | `enablePendingPurchases(PendingPurchasesParams…enableOneTimeProducts())`. PENDING never unlocks. Every PURCHASED unlock is acknowledged. `queryPurchasesAsync` runs on connect, when the purchase screen opens, and on home resume (at most every 5 minutes). |

We found no official Play guidance specifically about time-limited trials of a *one-time*
product. The design above follows the general disclosure and non-interference rules.

## 3. Chosen design

Package `com.gungreeneyes.trekui.licensing`:

| Class | Role |
|---|---|
| `Licensing` | Public facade: `init(Activity)`, `onHomeResumed(Activity)`, `isUnlocked(Context)`, `trialRemainingMillis(Context)` and `openPurchaseScreen(Context)`, plus debug hooks. |
| `TrialClock` | Session timing with `SystemClock.elapsedRealtime()`, so changing the wall clock has no effect. Rules are listed below the table. |
| `Entitlement` | Cached purchase state (see "Entitlement" below). |
| `BillingManager` | Wraps Play Billing Library 9. Connects with auto-reconnection and exponential back-off retry. Restores purchases on start and resume, queries the price, launches the purchase flow, acknowledges purchases and handles pending ones. |
| `PurchaseVerifier` | Optional RSA check of the purchase signature. It is off while `BASE64_PUBLIC_KEY` is empty; paste the Play Console licensing key there to turn it on. |
| `PurchaseActivity` | console-styled full-screen panel: elbow header, Antonio font, sized with `ScreenMetrics`, edge-to-edge insets. Buttons: **BUY $0.99** (localized price), **RESTORE PURCHASE**, **NEW SESSION** (enabled after a 10 s countdown) and **USE ANOTHER HOME APP**. In voluntary mode, NEW SESSION is replaced by **NOT NOW**. |

**TrialClock rules**

- Session start and boot count are persisted, so a session survives the system killing the
  launcher process.
- A new session starts on first run, after a reboot (detected by `Settings.Global.BOOT_COUNT` or
  by the uptime going backwards), on NEW SESSION, and on the first process start after the expiry
  screen has been shown.

**Entitlement**

- A purchase is cached only when Play reports it as PURCHASED and its signature is valid. The
  cache stores the purchase token plus a SHA-256 digest that binds it to a per-install random ID,
  the package name and the product.
- If the cache has been edited or copied from another install, it simply reads as "not
  purchased". Nothing is punished.
- A cached purchase is revoked only when a *successful* `queryPurchasesAsync` no longer lists it
  (for example after a refund). Being offline, or a Play error, never locks a buyer out.

**Session flow**

1. `MainActivity.onCreate` calls `Licensing.init`. It continues the session (or starts a new one
   after a relaunch or reboot) and connects to Play.
2. `MainActivity.onResume` calls `Licensing.onHomeResumed`. If the copy is unlocked, it does
   nothing more.
3. If the session has expired, it marks the expiry screen as shown and opens `PurchaseActivity`.
4. Otherwise it schedules a re-check for the moment the session ends. The re-check is cancelled
   when the home screen is paused, so another app the user is in is never interrupted.

**Why not more aggressive anti-piracy?** For a $0.99 unlock, hostile checks would cost more
support and goodwill than they recover. Play's own purchase records are the source of truth.
Server-side verification with the Play Developer API is the next step if piracy becomes
noticeable.

### Test hooks

None of these hooks do anything in the store build.

- **develop flavor:** long-press the header of the purchase screen to simulate a purchase. Do it
  again to clear the simulation.
- **Debug builds:** pass intent extras to the home activity. On a cold start they are read from
  `getIntent()`. With MainActivity already running, they only work if it calls
  `setIntent(intent)` in `onNewIntent`.

  ```
  adb shell am start -n com.gungreeneyes.trekui.dev/com.gungreeneyes.trekui.MainActivity --ez trekui.debug.unlock true
  adb shell am start -n com.gungreeneyes.trekui.dev/com.gungreeneyes.trekui.MainActivity --ez trekui.debug.unlock false
  adb shell am start -n com.gungreeneyes.trekui.dev/com.gungreeneyes.trekui.MainActivity --ez trekui.debug.clear_override true
  adb shell am start -n com.gungreeneyes.trekui.dev/com.gungreeneyes.trekui.MainActivity --ei trekui.debug.trial_seconds 30
  adb shell am start -n com.gungreeneyes.trekui.dev/com.gungreeneyes.trekui.MainActivity --ez trekui.debug.reset_trial true
  adb shell am start -n com.gungreeneyes.trekui.dev/com.gungreeneyes.trekui.MainActivity --ez trekui.debug.expire_now true
  ```

- **Instrumentation tests:** call `Licensing.debugSetUnlocked(ctx, true|false|null)` and
  `Licensing.debugSetTrialSeconds(ctx, n)` directly.

## 4. Changing the price or the limit

- **Price:** change it in Play Console under Monetize, Products, In-app products, `trekui_unlock`,
  Edit price. No app update is needed: the button shows the localized price from Play. The `$0.99`
  text in `PurchaseActivity` (`FALLBACK_PRICE` and the body copy) is shown only before Play
  answers, so update it to match.
- **Session length:** edit `buildConfigField("long", "TRIAL_SESSION_MINUTES", …)` in
  `app/build.gradle.kts`. It is set in `defaultConfig` (production) and overridden in the `develop`
  flavor. A value of 0 or less disables the limit. The purchase screen's wording follows it
  automatically.
- **Product ID:** `UNLOCK_PRODUCT_ID` in `app/build.gradle.kts`. It must match the Play Console
  product exactly, and a product ID can never be reused once created.
- **NEW SESSION countdown:** `PurchaseActivity.RELAUNCH_DELAY_SECONDS`.
- **Signature verification:** set `PurchaseVerifier.BASE64_PUBLIC_KEY`.

Play Console setup and testing: see [PLAY_BILLING_SETUP.md](PLAY_BILLING_SETUP.md).

## Sources

1. https://play.google.com/store/apps/details?id=com.teslacoilsw.launcher.prime and https://play.google.com/store/apps/details?id=com.teslacoilsw.launcher
2. https://piunikaweb.com/2026/02/06/nova-launcher-rolls-out-stability-fixes-confirms-4-99-annual-subscription-for-new-users/
3. https://gigazine.net/gsc_news/en/20260121-instabridge-acquired-nova-launcher
4. https://niagaralauncher.com/ and https://play.google.com/store/apps/details?id=bitpit.launcher
5. https://help.niagaralauncher.app/article/104-price-of-niagara-pro
6. https://docs.smartlauncher.net/faq/purchases-and-plans/price-of-smart-launcher and https://play.google.com/store/apps/details?id=ginlemon.flowerfree
7. https://actionlauncher.com/faq and https://play.google.com/store/apps/details?id=com.actionlauncher.playstore
8. https://9to5google.com/2022/06/24/action-launcher-v50/
9. https://play.google.com/store/apps/details?id=projekt.launcher and https://play.google.com/store/apps/details?id=projekt.launcher.launcherkey
10. https://play.google.com/store/apps/details?id=com.ss.launcher2 and https://play.google.com/store/apps/details?id=com.ss.launcher2.key
11. https://play.google.com/store/apps/details?id=app.lawnchair.play
12. https://f-droid.org/en/packages/de.mm20.launcher2.release/
13. https://play.google.com/store/apps/details?id=app.olauncher
14. https://play.google.com/store/apps/details?id=app.prolauncher
15. Google Play listings of the themed console launcher series (checked November 2026; links kept in the owner's research notes).
16. Payments policy: https://support.google.com/googleplay/android-developer/answer/9858738
17. Deceptive Behavior policy: https://support.google.com/googleplay/android-developer/answer/9888077
18. Device and Network Abuse policy: https://support.google.com/googleplay/android-developer/answer/9888379
19. Subscriptions policy: https://support.google.com/googleplay/android-developer/answer/9900533
20. Billing Library deprecation FAQ: https://developer.android.com/google/play/billing/deprecation-faq
21. Integrate the Billing Library: https://developer.android.com/google/play/billing/integrate
