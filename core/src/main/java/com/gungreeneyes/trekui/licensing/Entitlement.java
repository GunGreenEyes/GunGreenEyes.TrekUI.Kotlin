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
 * <p>Suite unlock: one purchase of {@code trekui_unlock} in any TrekUI app unlocks them all. When a
 * sibling app reports (through its signature-protected suite provider, see
 * {@code com.gungreeneyes.trekui.core.SuiteClient}) that it is unlocked, {@link #grantSuite} caches that
 * here, bound to this install by the same kind of digest, so uninstalling the app that was bought does
 * not relock the others. A suite grant is never revoked by Play answers about this app's own purchase.
 *
 * <p>Two test hooks exist and are ignored in store builds:
 * <ul>
 *   <li>a debug override (forced unlocked / locked), honoured only when constructed with
 *       {@code debugBuild = true} ({@code CoreConfig.debugBuild});</li>
 *   <li>a simulated purchase, honoured only when {@code simulationAllowed = true}
 *       (the {@code develop} flavor).</li>
 * </ul>
 */
public final class Entitlement {
    /** Why {@link #isUnlocked()} answered as it did. */
    public enum Source { NONE, PURCHASE, SUITE, SIMULATED, DEBUG_OVERRIDE }

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
    static final String KEY_SUITE_FROM = "ent.suite.from";
    static final String KEY_SUITE_TIME = "ent.suite.time";
    static final String KEY_SUITE_DIGEST = "ent.suite.digest";

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
     * @param debugBuild        honour the debug override ({@code CoreConfig.debugBuild})
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
        if (hasSuiteGrant()) return Source.SUITE;
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

    /** True if a sibling app's unlock is cached and its digest matches this install. */
    public synchronized boolean hasSuiteGrant() {
        String from = store.getString(KEY_SUITE_FROM, null);
        String digest = store.getString(KEY_SUITE_DIGEST, null);
        if (from == null || from.isEmpty() || digest == null) return false;
        long time = store.getLong(KEY_SUITE_TIME, 0L);
        return MessageDigest.isEqual(
                digest.getBytes(StandardCharsets.UTF_8),
                digest(suiteMaterial(from, time)).getBytes(StandardCharsets.UTF_8));
    }

    /** Package of the sibling app whose unlock is cached (null when none). */
    public synchronized String suiteGrantSource() {
        return hasSuiteGrant() ? store.getString(KEY_SUITE_FROM, null) : null;
    }

    /**
     * Caches "a sibling TrekUI app is unlocked" (integrity-hashed like a purchase). Called by the suite
     * client after a sibling's signature-protected provider answered unlocked. Keeps an existing valid
     * grant (and its original source) untouched.
     *
     * @param fromPackage the sibling's package name
     * @param time        when the answer was received (ms)
     * @return true if the copy was not unlocked by a suite grant before
     */
    public synchronized boolean grantSuite(String fromPackage, long time) {
        if (fromPackage == null || fromPackage.isEmpty()) return false;
        if (hasSuiteGrant()) return false;
        store.putString(KEY_SUITE_FROM, fromPackage);
        store.putLong(KEY_SUITE_TIME, time);
        store.putString(KEY_SUITE_DIGEST, digest(suiteMaterial(fromPackage, time)));
        return true;
    }

    /** The token-like string a suite grant's digest is computed over. */
    private static String suiteMaterial(String fromPackage, long time) {
        return "suite|" + fromPackage + '|' + time;
    }

    /**
     * True if this copy may tell sibling apps it is unlocked: a real purchase or a cached suite grant,
     * or (develop flavor only) a simulated purchase. Debug overrides are never shared.
     */
    public synchronized boolean shareableUnlock() {
        Source s = sourceIgnoringOverride();
        return s == Source.PURCHASE || s == Source.SUITE || s == Source.SIMULATED;
    }

    private Source sourceIgnoringOverride() {
        if (hasVerifiedPurchase()) return Source.PURCHASE;
        if (hasSuiteGrant()) return Source.SUITE;
        if (simulationAllowed && store.getBoolean(KEY_SIMULATED, false)) return Source.SIMULATED;
        return Source.NONE;
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
