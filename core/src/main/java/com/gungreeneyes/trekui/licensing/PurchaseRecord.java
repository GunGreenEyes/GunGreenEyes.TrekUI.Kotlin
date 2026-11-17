package com.gungreeneyes.trekui.licensing;

import java.util.Collections;
import java.util.List;

/**
 * A Play Billing purchase reduced to the fields the {@link Entitlement} rules need.
 *
 * <p>Keeping this a plain value class (instead of passing {@code com.android.billingclient.api.Purchase})
 * lets the entitlement logic be unit-tested on the JVM. {@link BillingManager} builds these from
 * real purchases, filling {@link #signatureValid} via {@link PurchaseVerifier}.
 */
public final class PurchaseRecord {
    /** Mirrors {@code Purchase.PurchaseState.UNSPECIFIED_STATE}. */
    public static final int STATE_UNSPECIFIED = 0;
    /** Mirrors {@code Purchase.PurchaseState.PURCHASED}. */
    public static final int STATE_PURCHASED = 1;
    /** Mirrors {@code Purchase.PurchaseState.PENDING} (e.g. cash payment not completed yet). */
    public static final int STATE_PENDING = 2;

    public final List<String> productIds;
    public final int state;
    public final String purchaseToken;
    public final String orderId;
    public final long purchaseTime;
    public final boolean acknowledged;
    /** True when the purchase JSON's signature verified (or no verification key is configured). */
    public final boolean signatureValid;

    public PurchaseRecord(List<String> productIds, int state, String purchaseToken, String orderId,
                          long purchaseTime, boolean acknowledged, boolean signatureValid) {
        this.productIds = productIds == null ? Collections.emptyList() : productIds;
        this.state = state;
        this.purchaseToken = purchaseToken;
        this.orderId = orderId;
        this.purchaseTime = purchaseTime;
        this.acknowledged = acknowledged;
        this.signatureValid = signatureValid;
    }

    /** True if this purchase is for {@code productId}. */
    public boolean covers(String productId) {
        return productIds.contains(productId);
    }
}
