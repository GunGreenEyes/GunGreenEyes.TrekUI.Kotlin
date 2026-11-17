package com.gungreeneyes.trekui.licensing;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thin wrapper around Google Play Billing Library 9 for the single one-time unlock product.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Connect once per process with pending purchases enabled for one-time products and
 *       automatic service reconnection; retry the initial setup with exponential back-off when
 *       Play is temporarily unavailable.</li>
 *   <li>Restore purchases on every connection and (throttled) on every home resume, which also
 *       picks up pending purchases that completed while the launcher was not running and refunds.</li>
 *   <li>Query the product's localized price for the BUY button.</li>
 *   <li>Launch the purchase flow and process the result.</li>
 *   <li>Acknowledge every completed purchase (Play refunds unacknowledged purchases after 3 days).</li>
 * </ul>
 * Offline-safe: no failure path ever removes a cached unlock; {@link Entitlement} only revokes on
 * a successful, authoritative "not owned" answer. All listener callbacks arrive on the main thread.
 */
public final class BillingManager implements PurchasesUpdatedListener {
    private static final String TAG = "TrekUiBilling";
    private static final int MAX_SETUP_RETRIES = 6;
    private static final long RETRY_BASE_MS = 1_000L;
    private static final long RETRY_MAX_MS = 60_000L;
    private static final long RESUME_REFRESH_INTERVAL_MS = 5 * 60_000L;

    /** Snapshot of what the purchase screen needs to show. */
    public static final class State {
        /** True while connected to Play. */
        public final boolean connected;
        /** Localized price from Play, or null until known. */
        public final String formattedPrice;
        /** True if the unlock is owned (or simulated/overridden in test builds). */
        public final boolean unlocked;
        /** True if a purchase is waiting for payment. */
        public final boolean pending;
        /** Last user-visible problem, or null. */
        public final String message;

        State(boolean connected, String formattedPrice, boolean unlocked, boolean pending, String message) {
            this.connected = connected;
            this.formattedPrice = formattedPrice;
            this.unlocked = unlocked;
            this.pending = pending;
            this.message = message;
        }
    }

    /** Receives {@link State} changes on the main thread. */
    public interface Listener {
        void onBillingStateChanged(State state);
    }

    /** Outcome of {@link #launchPurchase(Activity)}. */
    public enum LaunchResult { STARTED, ALREADY_UNLOCKED, NOT_READY, ERROR }

    private final Context appContext;
    private final Entitlement entitlement;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private BillingClient client;
    private boolean connecting;
    private int setupRetries;
    private ProductDetails productDetails;
    private String message;
    private long lastPurchasesQuery;
    /** A BUY tap that arrived before product details were loaded. */
    private WeakReference<Activity> deferredLaunch;

    BillingManager(Context context, Entitlement entitlement) {
        this.appContext = context.getApplicationContext();
        this.entitlement = entitlement;
    }

    /** Registers a listener and immediately delivers the current state. */
    public void addListener(Listener listener) {
        listeners.add(listener);
        listener.onBillingStateChanged(state());
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** The current state snapshot. */
    public State state() {
        String price = null;
        ProductDetails details = productDetails;
        if (details != null && details.getOneTimePurchaseOfferDetails() != null) {
            price = details.getOneTimePurchaseOfferDetails().getFormattedPrice();
        }
        BillingClient c = client;
        return new State(c != null && c.isReady(), price, entitlement.isUnlocked(), entitlement.isPending(), message);
    }

    /** Connects (if needed); on success restores purchases and loads the price. Main thread. */
    public void start() {
        if (client == null) {
            client = BillingClient.newBuilder(appContext)
                    .setListener(this)
                    .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                    .enableAutoServiceReconnection()
                    .build();
        }
        if (client.isReady()) {
            refreshPurchases();
            if (productDetails == null) queryProductDetails();
            return;
        }
        if (connecting) return;
        connecting = true;
        try {
            client.startConnection(new BillingClientStateListener() {
                @Override public void onBillingSetupFinished(BillingResult result) {
                    main.post(() -> onSetupFinished(result));
                }

                @Override public void onBillingServiceDisconnected() {
                    // With enableAutoServiceReconnection() the library reconnects on the next call.
                    main.post(() -> {
                        connecting = false;
                        notifyListeners();
                    });
                }
            });
        } catch (RuntimeException e) {
            // e.g. no Play Store on the device (emulator without Google APIs, de-Googled ROM).
            connecting = false;
            Log.w(TAG, "startConnection failed", e);
            setMessage("GOOGLE PLAY UNAVAILABLE");
        }
    }

    private void onSetupFinished(BillingResult result) {
        connecting = false;
        int code = result.getResponseCode();
        if (code == BillingClient.BillingResponseCode.OK) {
            setupRetries = 0;
            message = null;
            refreshPurchases();
            queryProductDetails();
            notifyListeners();
            return;
        }
        Log.w(TAG, "Billing setup failed: " + code + " " + result.getDebugMessage());
        if (code == BillingClient.BillingResponseCode.BILLING_UNAVAILABLE) {
            setMessage("GOOGLE PLAY BILLING UNAVAILABLE ON THIS DEVICE");
            return; // retrying will not help
        }
        if (setupRetries < MAX_SETUP_RETRIES) {
            long delay = Math.min(RETRY_MAX_MS, RETRY_BASE_MS << setupRetries);
            setupRetries++;
            main.postDelayed(this::start, delay);
        }
        setMessage("CONNECTING TO GOOGLE PLAY…");
    }

    /** Restores purchases now (on connect and when the purchase screen opens). */
    public void refreshPurchases() {
        BillingClient c = client;
        if (c == null || !c.isReady()) {
            start();
            return;
        }
        lastPurchasesQuery = SystemClock.elapsedRealtime();
        c.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
                (result, purchases) -> main.post(() -> {
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        handlePurchases(purchases, true);
                    } else {
                        Log.w(TAG, "queryPurchases: " + result.getResponseCode() + " " + result.getDebugMessage());
                    }
                }));
    }

    /** Called on every home resume; re-queries at most every few minutes. */
    void refreshIfStale() {
        if (lastPurchasesQuery == 0L
                || SystemClock.elapsedRealtime() - lastPurchasesQuery > RESUME_REFRESH_INTERVAL_MS) {
            refreshPurchases();
        }
    }

    private void queryProductDetails() {
        BillingClient c = client;
        if (c == null || !c.isReady()) return;
        QueryProductDetailsParams.Product product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(entitlement.productId())
                .setProductType(BillingClient.ProductType.INAPP)
                .build();
        c.queryProductDetailsAsync(
                QueryProductDetailsParams.newBuilder().setProductList(Collections.singletonList(product)).build(),
                (result, detailsResult) -> main.post(() -> {
                    if (result.getResponseCode() != BillingClient.BillingResponseCode.OK || detailsResult == null) {
                        Log.w(TAG, "queryProductDetails: " + result.getResponseCode() + " " + result.getDebugMessage());
                        failDeferredLaunch("PRICE UNAVAILABLE — TRY AGAIN");
                        return;
                    }
                    for (ProductDetails d : detailsResult.getProductDetailsList()) {
                        if (entitlement.productId().equals(d.getProductId())) productDetails = d;
                    }
                    if (productDetails == null) {
                        // Product not created/active in Play Console, or this build is not from Play.
                        failDeferredLaunch("UNLOCK NOT AVAILABLE FROM GOOGLE PLAY YET");
                        return;
                    }
                    Activity pending = deferredLaunch == null ? null : deferredLaunch.get();
                    deferredLaunch = null;
                    if (pending != null && !pending.isFinishing()) launchPurchase(pending);
                    notifyListeners();
                }));
    }

    private void failDeferredLaunch(String text) {
        if (deferredLaunch != null) {
            deferredLaunch = null;
            setMessage(text);
        } else {
            notifyListeners();
        }
    }

    /**
     * Opens Google Play's purchase sheet for the unlock. If the price has not loaded yet the
     * request is remembered and started as soon as it has.
     */
    public LaunchResult launchPurchase(Activity activity) {
        if (entitlement.hasVerifiedPurchase()) return LaunchResult.ALREADY_UNLOCKED;
        BillingClient c = client;
        if (c == null || !c.isReady() || productDetails == null) {
            deferredLaunch = new WeakReference<>(activity);
            if (c != null && c.isReady()) queryProductDetails(); else start();
            setMessage("CONTACTING GOOGLE PLAY…");
            return LaunchResult.NOT_READY;
        }
        BillingFlowParams.ProductDetailsParams.Builder item = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails);
        ProductDetails.OneTimePurchaseOfferDetails offer = productDetails.getOneTimePurchaseOfferDetails();
        if (offer != null && offer.getOfferToken() != null) item.setOfferToken(offer.getOfferToken());
        BillingResult result = c.launchBillingFlow(activity, BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(item.build()))
                .build());
        int code = result.getResponseCode();
        if (code == BillingClient.BillingResponseCode.OK) {
            message = null;
            notifyListeners();
            return LaunchResult.STARTED;
        }
        if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            refreshPurchases();
            return LaunchResult.ALREADY_UNLOCKED;
        }
        Log.w(TAG, "launchBillingFlow: " + code + " " + result.getDebugMessage());
        setMessage(describe(code));
        return LaunchResult.ERROR;
    }

    /** Purchase-flow results (and purchases completed elsewhere while connected). */
    @Override
    public void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        main.post(() -> {
            int code = result.getResponseCode();
            if (code == BillingClient.BillingResponseCode.OK) {
                message = null;
                handlePurchases(purchases, false);
            } else if (code == BillingClient.BillingResponseCode.USER_CANCELED) {
                setMessage(null);
            } else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                refreshPurchases();
            } else {
                Log.w(TAG, "onPurchasesUpdated: " + code + " " + result.getDebugMessage());
                setMessage(describe(code));
            }
        });
    }

    private void handlePurchases(List<Purchase> purchases, boolean authoritative) {
        List<PurchaseRecord> records = new ArrayList<>();
        if (purchases != null) {
            for (Purchase p : purchases) {
                boolean valid = PurchaseVerifier.verify(p.getOriginalJson(), p.getSignature());
                if (!valid) Log.w(TAG, "Purchase signature did not verify; ignoring it");
                records.add(new PurchaseRecord(p.getProducts(), p.getPurchaseState(), p.getPurchaseToken(),
                        p.getOrderId(), p.getPurchaseTime(), p.isAcknowledged(), valid));
                if (valid && p.getPurchaseState() == Purchase.PurchaseState.PURCHASED && !p.isAcknowledged()
                        && p.getProducts().contains(entitlement.productId())) {
                    acknowledge(p.getPurchaseToken());
                }
            }
        }
        Entitlement.Result r = entitlement.apply(records, authoritative);
        if (r == Entitlement.Result.PENDING) {
            message = "PURCHASE PENDING — IT UNLOCKS AS SOON AS PAYMENT COMPLETES";
        }
        notifyListeners();
    }

    private void acknowledge(String token) {
        BillingClient c = client;
        if (c == null || !c.isReady()) return; // retried on the next queryPurchases
        c.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build(), result -> {
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "acknowledge failed: " + result.getResponseCode() + " " + result.getDebugMessage());
            }
        });
    }

    private static String describe(int code) {
        switch (code) {
            case BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE:
            case BillingClient.BillingResponseCode.SERVICE_DISCONNECTED:
            case BillingClient.BillingResponseCode.NETWORK_ERROR:
                return "GOOGLE PLAY UNREACHABLE — CHECK YOUR CONNECTION";
            case BillingClient.BillingResponseCode.BILLING_UNAVAILABLE:
                return "GOOGLE PLAY BILLING UNAVAILABLE ON THIS DEVICE";
            case BillingClient.BillingResponseCode.ITEM_UNAVAILABLE:
                return "UNLOCK NOT AVAILABLE FROM GOOGLE PLAY YET";
            case BillingClient.BillingResponseCode.DEVELOPER_ERROR:
                return "THIS BUILD CANNOT BUY — INSTALL FROM GOOGLE PLAY";
            default:
                return "PURCHASE FAILED (CODE " + code + ")";
        }
    }

    private void setMessage(String text) {
        message = text;
        notifyListeners();
    }

    /** Re-broadcasts the current state (e.g. after a debug override changed it). */
    void notifyListeners() {
        State s = state();
        for (Listener l : listeners) l.onBillingStateChanged(s);
    }
}
