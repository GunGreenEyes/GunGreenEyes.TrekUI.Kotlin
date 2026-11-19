package com.gungreeneyes.trekui.licensing

import com.gungreeneyes.trekui.core.SuiteContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** JVM tests for [Entitlement]: purchase caching, revocation, pending, tamper and test hooks. */
class EntitlementTest {

    private lateinit var store: MemoryKeyValueStore

    @Before
    fun setUp() {
        store = MemoryKeyValueStore()
    }

    private fun release(): Entitlement = Entitlement(store, PKG, PRODUCT, false, false)

    @Test
    fun lockedByDefault() {
        val e = release()
        assertFalse(e.isUnlocked())
        assertEquals(Entitlement.Source.NONE, e.source())
    }

    @Test
    fun verifiedPurchaseUnlocksAndIsCached() {
        val e = release()
        assertEquals(
            Entitlement.Result.UNLOCKED,
            e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PURCHASED, true)), false),
        )
        assertTrue(e.isUnlocked())
        // a new instance (new process) reads the cache without asking Play
        assertTrue(release().isUnlocked())
        assertEquals(Entitlement.Source.PURCHASE, release().source())
    }

    @Test
    fun otherProductsAreIgnored() {
        val e = release()
        e.apply(list(purchase("something_else", PurchaseRecord.STATE_PURCHASED, true)), false)
        assertFalse(e.isUnlocked())
    }

    @Test
    fun badSignatureDoesNotUnlock() {
        val e = release()
        e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PURCHASED, false)), true)
        assertFalse(e.isUnlocked())
    }

    @Test
    fun pendingPurchaseDoesNotUnlock() {
        val e = release()
        assertEquals(
            Entitlement.Result.PENDING,
            e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PENDING, true)), false),
        )
        assertFalse(e.isUnlocked())
        assertTrue(e.isPending())
        // payment completes later
        e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PURCHASED, true)), true)
        assertTrue(e.isUnlocked())
        assertFalse(e.isPending())
    }

    @Test
    fun authoritativeEmptyListRevokes() {
        val e = release()
        e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PURCHASED, true)), false)
        assertEquals(Entitlement.Result.NOT_OWNED, e.apply(emptyList(), true))
        assertFalse(e.isUnlocked())
    }

    @Test
    fun nonAuthoritativeEmptyListKeepsUnlock() {
        val e = release()
        e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PURCHASED, true)), false)
        assertEquals(Entitlement.Result.UNCHANGED, e.apply(emptyList(), false))
        assertEquals(Entitlement.Result.UNCHANGED, e.apply(null, false))
        assertTrue(e.isUnlocked())
    }

    @Test
    fun authoritativeListClearsStalePending() {
        val e = release()
        e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PENDING, true)), false)
        e.apply(emptyList(), true) // pending purchase was cancelled
        assertFalse(e.isPending())
    }

    @Test
    fun tamperedTokenIsNotTrusted() {
        val e = release()
        e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PURCHASED, true)), false)
        store.putString(Entitlement.KEY_TOKEN, "forged")
        assertFalse(release().isUnlocked())
    }

    @Test
    fun handWrittenDigestWithoutInstallIdIsNotTrusted() {
        store.putString(Entitlement.KEY_TOKEN, "anything")
        store.putString(Entitlement.KEY_DIGEST, "0000")
        assertFalse(release().isUnlocked())
    }

    @Test
    fun cacheCopiedToAnotherInstallIsNotTrusted() {
        val e = release()
        e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PURCHASED, true)), false)
        store.putString(Entitlement.KEY_INSTALL, "another-install")
        assertFalse(release().isUnlocked())
    }

    @Test
    fun digestBindsPackageName() {
        val e = release()
        e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PURCHASED, true)), false)
        assertFalse(Entitlement(store, "com.example.other", PRODUCT, false, false).isUnlocked())
    }

    @Test
    fun debugOverrideIgnoredInReleaseBuilds() {
        val e = release()
        assertFalse(e.setDebugOverride(Entitlement.Override.UNLOCKED))
        store.putString(Entitlement.KEY_OVERRIDE, Entitlement.Override.UNLOCKED.name)
        assertFalse(e.isUnlocked())
        assertEquals(Entitlement.Override.NONE, e.debugOverride())
    }

    @Test
    fun debugOverrideHonouredInDebugBuilds() {
        val e = Entitlement(store, PKG, PRODUCT, true, false)
        assertTrue(e.setDebugOverride(Entitlement.Override.UNLOCKED))
        assertEquals(Entitlement.Source.DEBUG_OVERRIDE, e.source())
        // forced lock wins over a real purchase, so the trial can be screenshot on a dev device
        e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PURCHASED, true)), false)
        e.setDebugOverride(Entitlement.Override.LOCKED)
        assertFalse(e.isUnlocked())
        e.setDebugOverride(Entitlement.Override.NONE)
        assertEquals(Entitlement.Source.PURCHASE, e.source())
    }

    @Test
    fun corruptOverrideValueIsIgnored() {
        val e = Entitlement(store, PKG, PRODUCT, true, false)
        store.putString(Entitlement.KEY_OVERRIDE, "BOGUS")
        assertEquals(Entitlement.Override.NONE, e.debugOverride())
        assertFalse(e.isUnlocked())
    }

    @Test
    fun simulatedPurchaseOnlyWhereAllowed() {
        val production = release()
        assertFalse(production.setSimulatedPurchase(true))
        store.putBoolean(Entitlement.KEY_SIMULATED, true)
        assertFalse(production.isUnlocked())

        val develop = Entitlement(store, PKG, PRODUCT, true, true)
        assertTrue(develop.setSimulatedPurchase(true))
        assertEquals(Entitlement.Source.SIMULATED, develop.source())
        // an authoritative "not owned" from Play does not clear a simulation
        develop.apply(emptyList(), true)
        assertTrue(develop.isUnlocked())
        develop.setSimulatedPurchase(false)
        assertFalse(develop.isUnlocked())
    }

    @Test
    fun suiteGrantUnlocksButIsNeverShared() {
        val e = release()
        assertTrue(e.grantSuite(SIBLING, 1_000L))
        assertEquals(Entitlement.Source.SUITE, e.source())
        assertFalse(e.shareableUnlock())
        e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PURCHASED, true)), false)
        assertTrue(e.shareableUnlock())
    }

    @Test
    fun suiteGrantRevokedOnlyForItsGrantor() {
        val e = release()
        e.grantSuite(SIBLING, 1_000L)
        assertFalse(e.revokeSuite("com.gungreeneyes.trekui.keyboard"))
        assertTrue(e.isUnlocked())
        assertTrue(e.revokeSuite(SIBLING))
        assertFalse(e.isUnlocked())
        assertFalse(e.revokeSuite(SIBLING))
    }

    @Test
    fun ownPurchaseRowParsing() {
        fun row(unlocked: Boolean, source: String) = SuiteContract.unlockRow(SIBLING, unlocked, source)
        assertEquals(true, SuiteContract.parseOwnPurchase(row(true, "PURCHASE")))
        assertEquals(false, SuiteContract.parseOwnPurchase(row(true, "SUITE"))) // an older sibling's grant
        assertEquals(false, SuiteContract.parseOwnPurchase(row(false, "SUITE")))
        assertEquals(null, SuiteContract.parseOwnPurchase(null))
    }

    private companion object {
        const val SIBLING = "com.gungreeneyes.trekui.messages"
        const val PKG = "com.gungreeneyes.trekui"
        const val PRODUCT = "trekui_unlock"

        fun purchase(product: String, state: Int, signatureValid: Boolean): PurchaseRecord =
            PurchaseRecord(listOf(product), state, "token-$product", "GPA.1234", 1_700_000_000_000L, false, signatureValid)

        fun list(vararg records: PurchaseRecord): List<PurchaseRecord> = records.asList()
    }
}
