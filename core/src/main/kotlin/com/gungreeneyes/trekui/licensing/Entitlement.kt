package com.gungreeneyes.trekui.licensing

import java.security.MessageDigest
import java.util.UUID

/**
 * Cached "is this copy unlocked?" state.
 *
 * The source of truth is Google Play: [BillingManager] feeds every purchase list it receives into
 * [apply]. A verified, completed purchase of the unlock product is cached here so the launcher stays
 * unlocked offline and before Play has answered.
 *
 * Anti-tamper basics (deliberately not hostile): the cache is not a bare boolean but the purchase
 * token plus a SHA-256 digest binding it to this install, the package name and the product id. A
 * hand-edited or copied preferences file therefore reads as "not cached" and the copy simply falls
 * back to the trial until Play confirms the purchase again. Nothing is ever deleted or disabled because
 * of a mismatch, and a cached purchase is revoked only when Play gives a *successful* answer that the
 * product is not owned (for example after a refund); network or service errors never take an unlock
 * away.
 *
 * Suite unlock: one purchase of `trekui_unlock` in any TrekUI app unlocks them all. When a sibling app
 * reports (through its signature-protected suite provider, see `com.gungreeneyes.trekui.core.SuiteClient`)
 * that it is unlocked, [grantSuite] caches that here, bound to this install by the same kind of digest,
 * so uninstalling the app that was bought does not relock the others. A suite grant is never revoked by
 * Play answers about this app's own purchase; it is revoked ([revokeSuite]) when the granting sibling
 * answers that it no longer owns a purchase. Only an own purchase is ever shared ([shareableUnlock]).
 *
 * Two test hooks exist and are ignored in store builds:
 * - a debug override (forced unlocked / locked), honoured only when constructed with
 *   `debugBuild = true` (`CoreConfig.debugBuild`);
 * - a simulated purchase, honoured only when `simulationAllowed = true` (the `develop` flavor).
 *
 * The digest material and the persisted keys are unchanged from the Java edition: changing either would
 * make every existing user's cached unlock read as "not cached".
 *
 * The constructor and the `KEY_` constants are public (package-private in Java) because `:app`'s JVM
 * tests use them.
 *
 * @param store persistent cache
 * @param packageName this app's package (bound into the digest)
 * @param productId Play product id of the unlock
 * @param debugBuild honour the debug override (`CoreConfig.debugBuild`)
 * @param simulationAllowed honour a simulated purchase (develop flavor)
 */
class Entitlement(
    private val store: KeyValueStore,
    private val packageName: String,
    private val productId: String,
    private val debugBuild: Boolean,
    private val simulationAllowed: Boolean,
) {
    /** Why [isUnlocked] answered as it did. */
    enum class Source { NONE, PURCHASE, SUITE, SIMULATED, DEBUG_OVERRIDE }

    /** Debug-only forced state. */
    enum class Override { NONE, UNLOCKED, LOCKED }

    /** Outcome of [apply]. */
    enum class Result { UNLOCKED, PENDING, NOT_OWNED, UNCHANGED }

    /** The Play product id this entitlement represents. */
    fun productId(): String = productId

    /** True if the launcher should run without the trial limit. */
    @Synchronized
    fun isUnlocked(): Boolean = source() != Source.NONE

    /** The reason for the current unlocked/locked state. */
    @Synchronized
    fun source(): Source {
        if (debugBuild) {
            when (debugOverride()) {
                Override.UNLOCKED -> return Source.DEBUG_OVERRIDE
                Override.LOCKED -> return Source.NONE
                Override.NONE -> Unit
            }
        }
        return sourceIgnoringOverride()
    }

    /** True if a purchase is cached and its digest matches this install. */
    @Synchronized
    fun hasVerifiedPurchase(): Boolean {
        val token = store.getString(KEY_TOKEN, null)
        val stored = store.getString(KEY_DIGEST, null)
        if (token.isNullOrEmpty() || stored == null) return false
        return digestsEqual(stored, digest(token))
    }

    /** True if a sibling app's unlock is cached and its digest matches this install. */
    @Synchronized
    fun hasSuiteGrant(): Boolean {
        val from = store.getString(KEY_SUITE_FROM, null)
        val stored = store.getString(KEY_SUITE_DIGEST, null)
        if (from.isNullOrEmpty() || stored == null) return false
        val time = store.getLong(KEY_SUITE_TIME, 0L)
        return digestsEqual(stored, digest(suiteMaterial(from, time)))
    }

    /** Package of the sibling app whose unlock is cached (null when none). */
    @Synchronized
    fun suiteGrantSource(): String? = if (hasSuiteGrant()) store.getString(KEY_SUITE_FROM, null) else null

    /**
     * Caches "a sibling TrekUI app is unlocked" (integrity-hashed like a purchase). Called by the suite
     * client after a sibling's signature-protected provider answered unlocked. Keeps an existing valid
     * grant (and its original source) untouched.
     *
     * @param fromPackage the sibling's package name
     * @param time when the answer was received (ms)
     * @return true if the copy was not unlocked by a suite grant before
     */
    @Synchronized
    fun grantSuite(fromPackage: String?, time: Long): Boolean {
        if (fromPackage.isNullOrEmpty()) return false
        if (hasSuiteGrant()) return false
        store.putString(KEY_SUITE_FROM, fromPackage)
        store.putLong(KEY_SUITE_TIME, time)
        store.putString(KEY_SUITE_DIGEST, digest(suiteMaterial(fromPackage, time)))
        return true
    }

    /**
     * Clears the cached suite grant if it came from [fromPackage] (that sibling no longer reports an own
     * purchase, e.g. after a refund). Returns true if a grant was removed.
     */
    @Synchronized
    fun revokeSuite(fromPackage: String?): Boolean {
        if (fromPackage.isNullOrEmpty() || suiteGrantSource() != fromPackage) return false
        store.remove(KEY_SUITE_FROM)
        store.remove(KEY_SUITE_TIME)
        store.remove(KEY_SUITE_DIGEST)
        return true
    }

    /**
     * True if this copy may tell sibling apps it is unlocked: only its own verified purchase, or (develop
     * flavor only) a simulated purchase. A suite grant is never re-shared, so grants cannot keep each
     * other alive after the real purchase is refunded. Debug overrides are never shared.
     */
    @Synchronized
    fun shareableUnlock(): Boolean =
        hasVerifiedPurchase() || (simulationAllowed && store.getBoolean(KEY_SIMULATED, false))

    private fun sourceIgnoringOverride(): Source = when {
        hasVerifiedPurchase() -> Source.PURCHASE
        hasSuiteGrant() -> Source.SUITE
        simulationAllowed && store.getBoolean(KEY_SIMULATED, false) -> Source.SIMULATED
        else -> Source.NONE
    }

    /** True if Play last reported a pending (not yet paid) purchase. */
    @Synchronized
    fun isPending(): Boolean = store.getBoolean(KEY_PENDING, false)

    /**
     * Folds a purchase list from Play into the cache.
     *
     * @param purchases purchases reported by Play (may include other products)
     * @param authoritative true when this is the complete owned-purchases list from a successful
     *   `queryPurchasesAsync`; only then may a cached purchase be revoked. Purchase-flow updates are
     *   partial and pass false.
     * @return what changed
     */
    @Synchronized
    fun apply(purchases: List<PurchaseRecord?>?, authoritative: Boolean): Result {
        var owned: PurchaseRecord? = null
        var ownedToken: String? = null
        var pending = false
        for (p in purchases.orEmpty()) {
            if (p == null || !p.covers(productId)) continue
            val token = p.purchaseToken
            if (p.state == PurchaseRecord.STATE_PURCHASED && p.signatureValid && !token.isNullOrEmpty()) {
                owned = p
                ownedToken = token
            } else if (p.state == PurchaseRecord.STATE_PENDING) {
                pending = true
            }
        }
        if (owned != null && ownedToken != null) {
            grant(ownedToken, owned.orderId, owned.purchaseTime)
            return Result.UNLOCKED
        }
        if (authoritative) {
            store.putBoolean(KEY_PENDING, pending)
            revokePurchase()
            return if (pending) Result.PENDING else Result.NOT_OWNED
        }
        if (pending) {
            store.putBoolean(KEY_PENDING, true)
            return Result.PENDING
        }
        return Result.UNCHANGED
    }

    /** Caches a verified purchase. */
    @Synchronized
    internal fun grant(purchaseToken: String, orderId: String?, purchaseTime: Long) {
        store.putString(KEY_TOKEN, purchaseToken)
        store.putString(KEY_ORDER, orderId.orEmpty())
        store.putLong(KEY_TIME, purchaseTime)
        store.putString(KEY_DIGEST, digest(purchaseToken))
        store.putBoolean(KEY_PENDING, false)
    }

    /** Clears the cached purchase (Play confirmed it is no longer owned, e.g. refunded). */
    @Synchronized
    internal fun revokePurchase() {
        store.remove(KEY_TOKEN)
        store.remove(KEY_ORDER)
        store.remove(KEY_TIME)
        store.remove(KEY_DIGEST)
    }

    /** develop flavor only: pretend the unlock was bought (for tests and screenshots). */
    @Synchronized
    fun setSimulatedPurchase(simulated: Boolean): Boolean {
        if (!simulationAllowed) return false
        store.putBoolean(KEY_SIMULATED, simulated)
        return true
    }

    /** Debug builds only: force the state regardless of Play. Ignored in release builds. */
    @Synchronized
    fun setDebugOverride(override: Override?): Boolean {
        if (!debugBuild) return false
        store.putString(KEY_OVERRIDE, (override ?: Override.NONE).name)
        return true
    }

    /** The stored debug override (always NONE in release builds); an unknown stored name reads as NONE. */
    @Synchronized
    fun debugOverride(): Override {
        if (!debugBuild) return Override.NONE
        val name = store.getString(KEY_OVERRIDE, Override.NONE.name)
        return Override.entries.firstOrNull { it.name == name } ?: Override.NONE
    }

    /** A random id created once per install; part of the digest so a cache can't be copied over. */
    private fun installId(): String {
        val stored = store.getString(KEY_INSTALL, null)
        if (!stored.isNullOrEmpty()) return stored
        return UUID.randomUUID().toString().also { store.putString(KEY_INSTALL, it) }
    }

    /** SHA-256 over salt, install id, package, product and token, hex encoded (lower case). */
    internal fun digest(purchaseToken: String): String {
        val material = "$SALT|${installId()}|$packageName|$productId|$purchaseToken"
        // SHA-256 is mandatory on every JVM and Android, so getInstance cannot fail here.
        val hash = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return buildString(hash.size * 2) {
            for (b in hash) {
                append(Character.forDigit((b.toInt() shr 4) and 0xF, 16))
                append(Character.forDigit(b.toInt() and 0xF, 16))
            }
        }
    }

    companion object {
        const val KEY_TOKEN: String = "ent.token"
        const val KEY_ORDER: String = "ent.order"
        const val KEY_TIME: String = "ent.time"
        const val KEY_DIGEST: String = "ent.digest"
        const val KEY_INSTALL: String = "ent.install_id"
        const val KEY_PENDING: String = "ent.pending"
        const val KEY_SIMULATED: String = "ent.simulated"
        const val KEY_OVERRIDE: String = "debug.override"
        const val KEY_SUITE_FROM: String = "ent.suite.from"
        const val KEY_SUITE_TIME: String = "ent.suite.time"
        const val KEY_SUITE_DIGEST: String = "ent.suite.digest"

        /** Mixed into the digest; not a secret, it just makes the stored value non-obvious to forge. */
        private const val SALT = "TrekUI/ent/v1/7c2e9a41"

        /** The token-like string a suite grant's digest is computed over. */
        private fun suiteMaterial(fromPackage: String, time: Long): String = "suite|$fromPackage|$time"

        /** Constant-time comparison of two hex digests. */
        private fun digestsEqual(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
