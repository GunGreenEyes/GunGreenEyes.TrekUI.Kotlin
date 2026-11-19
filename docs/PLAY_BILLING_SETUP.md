# Play Billing setup: the `trekui_unlock` product

This page covers the one-time $0.99 unlock described in [MONETIZATION.md](MONETIZATION.md). The app
expects a **one-time product** with ID `trekui_unlock` (`BuildConfig.UNLOCK_PRODUCT_ID`) on the
**production** application ID `com.gungreeneyes.trekui`.

The develop flavor (`com.gungreeneyes.trekui.dev`) is a different app as far as Play is concerned. It
cannot buy the product unless you create a separate Play app for it. For develop builds, use the
simulated purchase or the debug overrides instead (see "Test hooks" in MONETIZATION.md).

Play Console labels move around now and then. The steps below use the 2026 names.

## 1. Prerequisites (one-time)

1. **Payments profile.** In Play Console, go to **Setup, Payments profile** and create or link a
   merchant (payments) profile. You can't create in-app products without one.
2. **Upload a build with billing.** In-app products can only be created once the app has a build
   that contains the Billing Library.
   1. Build it: `./gradlew.bat :app:bundleProductionRelease`.
   2. Upload the AAB to the **Internal testing** track (**Test and release, Testing, Internal testing,
      Create new release**).
   3. Roll the release out to internal testers.
3. *(Optional)* **Licensing key.** Copy the key from **Monetize with Play, Monetization setup,
   Licensing**. Paste it into `PurchaseVerifier.BASE64_PUBLIC_KEY` to turn on on-device signature
   checks.

## 2. Create the product

1. Go to **Monetize with Play, Products, One-time products**. Older consoles call this
   **In-app products**. Choose **Create one-time product**.
2. Fill in the product:
   - **Product ID:** `trekui_unlock`. This is permanent and can never be reused or renamed.
   - **Name:** `TrekUI Unlock`.
   - **Description:** `Removes the trial session limit. One-time purchase, no ads, no subscription.`
3. Add a **purchase option** (Play Billing Library 8 and later sells one-time products through
   purchase options):
   - **Type:** *Buy*, not *Rent*.
   - **Purchase option ID:** for example `buy`.
   - Leave it as the only option. The app uses the default offer that
     `getOneTimePurchaseOfferDetails()` returns.
4. Set the **price**:
   1. Choose **Set price**, enter **USD 0.99**, and accept the converted local prices. Play
      rounds them to local price points; adjust any you like.
   2. Check the **tax/category** setting. *Digital content / app functionality* is right for this
      product.
5. **Activate** the product. It can take a little while to become visible to the Billing Library.
6. Do **not** mark it as consumable. The app never consumes it; it acknowledges it, which keeps it
   owned for good and restorable on any device signed in to the same account.

## 3. License testers (free test purchases)

1. Go to **Settings** (the gear icon at the developer-account level), then **License testing**.
2. Add the Gmail accounts of the testers. Use an email list, for example "TrekUI testers".
3. Set **License response** to **RESPOND_NORMALLY**.
4. In the app's **Internal testing** track, add the same email list under **Testers**. Each tester
   must:
   1. Open the opt-in link.
   2. Accept the invite.
   3. Install the app from Play using that link.

   The first install must come from Play, or Play cannot recognize the app as yours. After that,
   sideloading a build that is **signed with the same key and has the same versionCode or lower**
   works for billing too.
5. When a tester makes a purchase, the Play sheet shows **"Test card, always approves"** plus
   options such as **"Slow test card, approves after a few minutes"** (which produces a PENDING
   purchase) and **"Test card, always declines"**. Testers are not charged.

## 4. Test plan

| Case | How | Expected |
|---|---|---|
| Trial expiry | Install a debug build of the **production** flavor (the develop flavor works too for UI-only checks). Both last 12 hours, so speed it up with `--ei trekui.debug.trial_seconds 30`. Press Home after the time is up. | The SESSION COMPLETE screen appears. Other apps are never interrupted. |
| Price | Open the screen. | The button reads `BUY $0.99`, or the local price. If you see `UNLOCK NOT AVAILABLE FROM GOOGLE PLAY YET`, the product is not active, or the build was not installed from Play by a tester. |
| Purchase | Tap BUY and pick "Test card, always approves". | The screen shows UNLOCKED and closes. The trial no longer appears. Play Console, **Order management**, lists the test order as acknowledged. |
| Pending | Pick "Slow test card, approves after a few minutes". | The screen shows PURCHASE PENDING and the trial continues. After approval it unlocks on the next home resume (purchases are re-checked at most every 5 minutes) or immediately via RESTORE PURCHASE. |
| Declined | Pick "Test card, always declines". | An error message appears and the copy stays locked. |
| Restore | Clear app data, or install on a second device with the same account, then tap RESTORE PURCHASE (or just wait: purchases are restored on start). | It unlocks. |
| Offline | Unlock, then turn on airplane mode and reboot. | It stays unlocked, because the purchase is cached. |
| Refund | In **Order management**, refund the test order *and* revoke the entitlement. Relaunch the app while online. | It locks again on the next successful purchase query. |
| Switch home | On the expiry screen, tap USE ANOTHER HOME APP. | The system "Default home app" picker opens. |
| New session | On the expiry screen, wait for the countdown, then tap NEW SESSION. | Back to the home screen with a full session. |

## 5. Before going live

- Add the trial disclosure to the store listing, as required by the Play Deceptive Behavior
  policy. Example wording:
  "Free to try: every feature works in 12-hour sessions. A one-time $0.99 in-app purchase removes
  the limit. No ads, no subscription."
- Make sure the release is built with **Play Billing Library 8 or later**. This project uses
  9.1.0, which is accepted until Aug 31, 2028.
- The **Data safety** form: purchase history is handled by Google Play. The app itself sends
  nothing to its own servers.
- Promote the internal release through closed or open testing to production as usual.
