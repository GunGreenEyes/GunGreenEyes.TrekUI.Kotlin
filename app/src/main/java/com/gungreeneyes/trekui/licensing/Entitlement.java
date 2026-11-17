package com.gungreeneyes.trekui.licensing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

/**
 * Cached "is this copy unlocked?" state.
 *
 * <p>The source of truth is Google Play: {@link BillingManager} feeds every purchase list it
 * receives into {@link #apply(List, boolean)}. A verified, completed purchase of the unlock
 * product is cached here so the launcher stays unlocked offline and before Play has answered.
 *
 * <p>Anti-tamper basics (deliberately not hostile): the cache is not a bare boolean but the
 * purchase token plus a SHA-256 digest binding it to this install, the package name and the
 * product id. A hand-edited or copied preferences file therefore reads as "not cached" and the
 * copy simply falls back to the trial until Play confirms the purchase again. Nothing is ever
 * deleted or disabled because of a mismatch, and a cached purchase is revoked only when Play
 * gives a <em>successful</em> answer that the product is not owned (for example after a refund);
 * network or service errors never take an unlock away.
 *
 * <p>Two test hooks exist and are ignored in store builds:
 * <ul>
 *   <li>a debug override (forced unlocked / locked), honoured only when constructed with
 *       {@code debugBuild = true} ({@code BuildConfig.DEBUG});</li>
 *   <li>a simulated purchase, honoured only when {@code simulationAllowed = true}
 *       (the {@code develop} flavor).</li>
 * </ul>
 */
public final class Entitlement {
    /** Why {@link #isUnlocked()} answered as it did. */
    public enum Source { NONE, PURCHASE, SIMULATED, DEBUG_OVERRIDE }

    /** Debug-only forced state. */
    public enum Override { NONE, UNLOCKED, LOCKED }

    /** Outcome of {@link #apply(List, boolean)}. */
    public enum Result { UNLOCKED, PENDING, NOT_OWNED, UNCHANGED }

    static final String KEY_TOKEN = "ent.token";
    static final String KEY_ORDER = "ent.order";
    static final String KEY_TIME = "ent.time";
    static final String KEY_DIGEST = "ent.digest";
    static final String KEY_INSTALL = "ent.install_id";
    static final String KEY_PENDING = "ent.pending";
    static final String KEY_SIMULATED = "ent.simulated";
    static final String KEY_OVERRIDE = "debug.override";

    /** Mixed into the digest; not a secret, it just makes the stored value non-obvious to forge. */
    private static final String SALT = "TrekUI/ent/v1/7c2e9a41";

    private final KeyValueStore store;
    private final String packageName;
    private final String productId;
    private final boolean debugBuild;
    private final boolean simulationAllowed;

    /**
     * @param store             persistent cache
     * @param packageName       this app's package (bound into the digest)
     * @param productId         Play product id of the unlock
     * @param debugBuild        honour the debug override ({@code BuildConfig.DEBUG})
     * @param simulationAllowed honour a simulated purchase (develop flavor)
     */
    Entitlement(KeyValueStore store, String packageName, String productId,
                boolean debugBuild, boolean simulationAllowed) {
        this.store = store;
        this.packageName = packageName;
        this.productId = productId;
        this.debugBuild = debugBuild;
        this.simulationAllowed = simulationAllowed;
    }

    /** The Play product id this entitlement represents. */
    public String productId() {
        return productId;
    }

    /** True if the launcher should run without the trial limit. */
    public synchronized boolean isUnlocked() {
        return source() != Source.NONE;
    }

    /** The reason for the current unlocked/locked state. */
    public synchronized Source source() {
        if (debugBuild) {
            Override o = debugOverride();
            if (o == Override.UNLOCKED) return Source.DEBUG_OVERRIDE;
            if (o == Override.LOCKED) return Source.NONE;
        }
        if (hasVerifiedPurchase()) return Source.PURCHASE;
        if (simulationAllowed && store.getBoolean(KEY_SIMULATED, false)) return Source.SIMULATED;
        return Source.NONE;
    }

    /** True if a purchase is cached and its digest matches this install. */
    public synchronized boolean hasVerifiedPurchase() {
        String token = store.getString(KEY_TOKEN, null);
        String digest = store.getString(KEY_DIGEST, null);
        if (token == null || token.isEmpty() || digest == null) return false;
        return MessageDigest.isEqual(
                digest.getBytes(StandardCharsets.UTF_8),
                digest(token).getBytes(StandardCharsets.UTF_8));
    }

    /** True if Play last reported a pending (not yet paid) purchase. */
    public synchronized boolean isPending() {
        return store.getBoolean(KEY_PENDING, false);
    }

    /**
     * Folds a purchase list from Play into the cache.
     *
     * @param purchases     purchases reported by Play (may include other products)
     * @param authoritative true when this is the complete owned-purchases list from a successful
     *                      {@code queryPurchasesAsync}; only then may a cached purchase be revoked.
     *                      Purchase-flow updates are partial and pass false.
     * @return what changed
     */
    public synchronized Result apply(List<PurchaseRecord> purchases, boolean authoritative) {
        PurchaseRecord owned = null;
        boolean pending = false;
        if (purchases != null) {
            for (PurchaseRecord p : purchases) {
                if (p == null || !p.covers(productId)) continue;
                if (p.state == PurchaseRecord.STATE_PURCHASED && p.signatureValid
                        && p.purchaseToken != null && !p.purchaseToken.isEmpty()) {
                    owned = p;
                } else if (p.state == PurchaseRecord.STATE_PENDING) {
                    pending = true;
                }
            }
        }
        if (owned != null) {
            grant(owned.purchaseToken, owned.orderId, owned.purchaseTime);
            return Result.UNLOCKED;
        }
        if (authoritative) {
            store.putBoolean(KEY_PENDING, pending);
            revokePurchase();
            return pending ? Result.PENDING : Result.NOT_OWNED;
        }
        if (pending) {
            store.putBoolean(KEY_PENDING, true);
            return Result.PENDING;
        }
        return Result.UNCHANGED;
    }

    /** Caches a verified purchase. */
    synchronized void grant(String purchaseToken, String orderId, long purchaseTime) {
        store.putString(KEY_TOKEN, purchaseToken);
        store.putString(KEY_ORDER, orderId == null ? "" : orderId);
        store.putLong(KEY_TIME, purchaseTime);
        store.putString(KEY_DIGEST, digest(purchaseToken));
        store.putBoolean(KEY_PENDING, false);
    }

    /** Clears the cached purchase (Play confirmed it is no longer owned, e.g. refunded). */
    synchronized void revokePurchase() {
        store.remove(KEY_TOKEN);
        store.remove(KEY_ORDER);
        store.remove(KEY_TIME);
        store.remove(KEY_DIGEST);
    }

    /** develop flavor only: pretend the unlock was bought (for tests and screenshots). */
    public synchronized boolean setSimulatedPurchase(boolean simulated) {
        if (!simulationAllowed) return false;
        store.putBoolean(KEY_SIMULATED, simulated);
        return true;
    }

    /** Debug builds only: force the state regardless of Play. Ignored in release builds. */
    public synchronized boolean setDebugOverride(Override override) {
        if (!debugBuild) return false;
        store.putString(KEY_OVERRIDE, (override == null ? Override.NONE : override).name());
        return true;
    }

    /** The stored debug override (always NONE in release builds). */
    public synchronized Override debugOverride() {
        if (!debugBuild) return Override.NONE;
        try {
            return Override.valueOf(store.getString(KEY_OVERRIDE, Override.NONE.name()));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Override.NONE;
        }
    }

    /** A random id created once per install; part of the digest so a cache can't be copied over. */
    private String installId() {
        String id = store.getString(KEY_INSTALL, null);
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            store.putString(KEY_INSTALL, id);
        }
        return id;
    }

    /** SHA-256 over salt, install id, package, product and token, hex encoded. */
    String digest(String purchaseToken) {
        String material = SALT + '|' + installId() + '|' + packageName + '|' + productId + '|' + purchaseToken;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // mandatory on every JVM/Android
        }
    }
}
