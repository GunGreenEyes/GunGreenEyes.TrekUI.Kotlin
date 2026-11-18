package com.gungreeneyes.trekui.licensing

/**
 * A Play Billing purchase reduced to the fields the [Entitlement] rules need.
 *
 * Keeping this a plain value class (instead of passing `com.android.billingclient.api.Purchase`) lets
 * the entitlement logic be unit-tested on the JVM. [BillingManager] builds these from real purchases,
 * filling [signatureValid] via [PurchaseVerifier].
 *
 * Not a `data class`: the constructor accepts a null product list (Java callers may pass one) and
 * stores an empty list instead, which a data class's property-only primary constructor cannot do.
 */
class PurchaseRecord(
    productIds: List<String>?,
    @JvmField val state: Int,
    @JvmField val purchaseToken: String?,
    @JvmField val orderId: String?,
    @JvmField val purchaseTime: Long,
    @JvmField val acknowledged: Boolean,
    /** True when the purchase JSON's signature verified (or no verification key is configured). */
    @JvmField val signatureValid: Boolean,
) {
    @JvmField
    val productIds: List<String> = productIds.orEmpty()

    /** True if this purchase is for [productId]. */
    fun covers(productId: String): Boolean = productId in productIds

    companion object {
        /** Mirrors `Purchase.PurchaseState.UNSPECIFIED_STATE`. */
        const val STATE_UNSPECIFIED: Int = 0

        /** Mirrors `Purchase.PurchaseState.PURCHASED`. */
        const val STATE_PURCHASED: Int = 1

        /** Mirrors `Purchase.PurchaseState.PENDING` (e.g. cash payment not completed yet). */
        const val STATE_PENDING: Int = 2
    }
}
