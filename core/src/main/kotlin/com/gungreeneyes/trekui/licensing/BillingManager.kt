package com.gungreeneyes.trekui.licensing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thin wrapper around Google Play Billing Library 9 for the single one-time unlock product.
 *
 * Responsibilities:
 * - Connect once per process with pending purchases enabled for one-time products and automatic
 *   service reconnection; retry the initial setup with exponential back-off when Play is temporarily
 *   unavailable.
 * - Restore purchases on every connection and (throttled) on every home resume, which also picks up
 *   pending purchases that completed while the launcher was not running and refunds.
 * - Query the product's localized price for the BUY button.
 * - Launch the purchase flow and process the result.
 * - Acknowledge every completed purchase (Play refunds unacknowledged purchases after 3 days).
 *
 * Offline-safe: no failure path ever removes a cached unlock; [Entitlement] only revokes on a
 * successful, authoritative "not owned" answer. All listener callbacks arrive on the main thread.
 */
class BillingManager internal constructor(
    context: Context,
    private val entitlement: Entitlement,
) : PurchasesUpdatedListener {

    /**
     * Snapshot of what the purchase screen needs to show.
     *
     * @property connected true while connected to Play
     * @property formattedPrice localized price from Play, or null until known
     * @property unlocked true if the unlock is owned (or simulated/overridden in test builds)
     * @property pending true if a purchase is waiting for payment
     * @property message last user-visible problem, or null
     */
    data class State(
        @JvmField val connected: Boolean,
        @JvmField val formattedPrice: String?,
        @JvmField val unlocked: Boolean,
        @JvmField val pending: Boolean,
        @JvmField val message: String?,
    )

    /** Receives [State] changes on the main thread. */
    fun interface Listener {
        fun onBillingStateChanged(state: State)
    }

    /** Outcome of [launchPurchase]. */
    enum class LaunchResult { STARTED, ALREADY_UNLOCKED, NOT_READY, ERROR }

    private val appContext: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    private var client: BillingClient? = null
    private var connecting = false
    private var setupRetries = 0
    private var productDetails: ProductDetails? = null
    private var message: String? = null
    private var lastPurchasesQuery = 0L

    /** A BUY tap that arrived before product details were loaded. */
    private var deferredLaunch: WeakReference<Activity>? = null

    /** Registers a listener and immediately delivers the current state. */
    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onBillingStateChanged(state())
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** The current state snapshot. */
    fun state(): State = State(
        connected = client?.isReady == true,
        formattedPrice = productDetails?.oneTimePurchaseOfferDetails?.formattedPrice,
        unlocked = entitlement.isUnlocked(),
        pending = entitlement.isPending(),
        message = message,
    )

    /** Connects (if needed); on success restores purchases and loads the price. Main thread. */
    fun start() {
        val c = client ?: BillingClient.newBuilder(appContext)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
            .also { client = it }
        if (c.isReady) {
            refreshPurchases()
            if (productDetails == null) queryProductDetails()
            return
        }
        if (connecting) return
        connecting = true
        try {
            c.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    main.post { onSetupFinished(result) }
                }

                override fun onBillingServiceDisconnected() {
                    // With enableAutoServiceReconnection() the library reconnects on the next call.
                    main.post {
                        connecting = false
                        notifyListeners()
                    }
                }
            })
        } catch (e: RuntimeException) {
            // e.g. no Play Store on the device (emulator without Google APIs, de-Googled ROM).
            connecting = false
            Log.w(TAG, "startConnection failed", e)
            setMessage("GOOGLE PLAY UNAVAILABLE")
        }
    }

    private fun onSetupFinished(result: BillingResult) {
        connecting = false
        val code = result.responseCode
        if (code == BillingClient.BillingResponseCode.OK) {
            setupRetries = 0
            message = null
            refreshPurchases()
            queryProductDetails()
            notifyListeners()
            return
        }
        Log.w(TAG, "Billing setup failed: $code ${result.debugMessage}")
        if (code == BillingClient.BillingResponseCode.BILLING_UNAVAILABLE) {
            setMessage("GOOGLE PLAY BILLING UNAVAILABLE ON THIS DEVICE")
            return // retrying will not help
        }
        if (setupRetries < MAX_SETUP_RETRIES) {
            val delay = minOf(RETRY_MAX_MS, RETRY_BASE_MS shl setupRetries)
            setupRetries++
            main.postDelayed({ start() }, delay)
        }
        setMessage("CONNECTING TO GOOGLE PLAY…")
    }

    /** Restores purchases now (on connect and when the purchase screen opens). */
    fun refreshPurchases() {
        val c = client
        if (c == null || !c.isReady) {
            start()
            return
        }
        lastPurchasesQuery = SystemClock.elapsedRealtime()
        c.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        ) { result, purchases ->
            main.post {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    handlePurchases(purchases, true)
                } else {
                    Log.w(TAG, "queryPurchases: ${result.responseCode} ${result.debugMessage}")
                }
            }
        }
    }

    /** Called on every home resume; re-queries at most every few minutes. */
    internal fun refreshIfStale() {
        if (lastPurchasesQuery == 0L ||
            SystemClock.elapsedRealtime() - lastPurchasesQuery > RESUME_REFRESH_INTERVAL_MS
        ) {
            refreshPurchases()
        }
    }

    private fun queryProductDetails() {
        val c = client
        if (c == null || !c.isReady) return
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(entitlement.productId())
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        c.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build(),
        ) { result, detailsResult ->
            main.post {
                // detailsResult is declared non-null by the library; the check is kept from the Java edition.
                @Suppress("SENSELESS_COMPARISON")
                if (result.responseCode != BillingClient.BillingResponseCode.OK || detailsResult == null) {
                    Log.w(TAG, "queryProductDetails: ${result.responseCode} ${result.debugMessage}")
                    failDeferredLaunch("PRICE UNAVAILABLE — TRY AGAIN")
                    return@post
                }
                for (d in detailsResult.productDetailsList) {
                    if (entitlement.productId() == d.productId) productDetails = d
                }
                if (productDetails == null) {
                    // Product not created/active in Play Console, or this build is not from Play.
                    failDeferredLaunch("UNLOCK NOT AVAILABLE FROM GOOGLE PLAY YET")
                    return@post
                }
                val pending = deferredLaunch?.get()
                deferredLaunch = null
                if (pending != null && !pending.isFinishing) launchPurchase(pending)
                notifyListeners()
            }
        }
    }

    private fun failDeferredLaunch(text: String) {
        if (deferredLaunch != null) {
            deferredLaunch = null
            setMessage(text)
        } else {
            notifyListeners()
        }
    }

    /**
     * Opens Google Play's purchase sheet for the unlock. If the price has not loaded yet the request is
     * remembered and started as soon as it has.
     */
    fun launchPurchase(activity: Activity): LaunchResult {
        if (entitlement.hasVerifiedPurchase()) return LaunchResult.ALREADY_UNLOCKED
        val c = client
        val details = productDetails
        if (c == null || !c.isReady || details == null) {
            deferredLaunch = WeakReference(activity)
            if (c != null && c.isReady) queryProductDetails() else start()
            setMessage("CONTACTING GOOGLE PLAY…")
            return LaunchResult.NOT_READY
        }
        val item = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details)
        details.oneTimePurchaseOfferDetails?.offerToken?.let { item.setOfferToken(it) }
        val result = c.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(item.build())).build(),
        )
        return when (val code = result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                message = null
                notifyListeners()
                LaunchResult.STARTED
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                refreshPurchases()
                LaunchResult.ALREADY_UNLOCKED
            }
            else -> {
                Log.w(TAG, "launchBillingFlow: $code ${result.debugMessage}")
                setMessage(describe(code))
                LaunchResult.ERROR
            }
        }
    }

    /** Purchase-flow results (and purchases completed elsewhere while connected). */
    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        main.post {
            when (val code = result.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    message = null
                    handlePurchases(purchases, false)
                }
                BillingClient.BillingResponseCode.USER_CANCELED -> setMessage(null)
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refreshPurchases()
                else -> {
                    Log.w(TAG, "onPurchasesUpdated: $code ${result.debugMessage}")
                    setMessage(describe(code))
                }
            }
        }
    }

    private fun handlePurchases(purchases: List<Purchase>?, authoritative: Boolean) {
        val records = purchases.orEmpty().map { p ->
            val valid = PurchaseVerifier.verify(p.originalJson, p.signature)
            if (!valid) Log.w(TAG, "Purchase signature did not verify; ignoring it")
            if (valid && p.purchaseState == Purchase.PurchaseState.PURCHASED && !p.isAcknowledged &&
                p.products.contains(entitlement.productId())
            ) {
                acknowledge(p.purchaseToken)
            }
            PurchaseRecord(
                p.products, p.purchaseState, p.purchaseToken,
                p.orderId, p.purchaseTime, p.isAcknowledged, valid,
            )
        }
        if (entitlement.apply(records, authoritative) == Entitlement.Result.PENDING) {
            message = "PURCHASE PENDING — IT UNLOCKS AS SOON AS PAYMENT COMPLETES"
        }
        notifyListeners()
    }

    private fun acknowledge(token: String) {
        val c = client
        if (c == null || !c.isReady) return // retried on the next queryPurchases
        c.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "acknowledge failed: ${result.responseCode} ${result.debugMessage}")
            }
        }
    }

    private fun setMessage(text: String?) {
        message = text
        notifyListeners()
    }

    /** Re-broadcasts the current state (e.g. after a debug override changed it). */
    internal fun notifyListeners() {
        val s = state()
        for (l in listeners) l.onBillingStateChanged(s)
    }

    private companion object {
        const val TAG = "TrekUiBilling"
        const val MAX_SETUP_RETRIES = 6
        const val RETRY_BASE_MS = 1_000L
        const val RETRY_MAX_MS = 60_000L
        const val RESUME_REFRESH_INTERVAL_MS = 5 * 60_000L

        fun describe(code: Int): String = when (code) {
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
            BillingClient.BillingResponseCode.NETWORK_ERROR,
            -> "GOOGLE PLAY UNREACHABLE — CHECK YOUR CONNECTION"
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "GOOGLE PLAY BILLING UNAVAILABLE ON THIS DEVICE"
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "UNLOCK NOT AVAILABLE FROM GOOGLE PLAY YET"
            BillingClient.BillingResponseCode.DEVELOPER_ERROR -> "THIS BUILD CANNOT BUY — INSTALL FROM GOOGLE PLAY"
            else -> "PURCHASE FAILED (CODE $code)"
        }
    }
}
