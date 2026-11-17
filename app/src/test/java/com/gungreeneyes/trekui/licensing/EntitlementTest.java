package com.gungreeneyes.trekui.licensing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/** JVM tests for {@link Entitlement}: purchase caching, revocation, pending, tamper and test hooks. */
public class EntitlementTest {
    private static final String PKG = "com.gungreeneyes.trekui";
    private static final String PRODUCT = "trekui_unlock";

    private MemoryKeyValueStore store;

    @Before
    public void setUp() {
        store = new MemoryKeyValueStore();
    }

    private Entitlement release() {
        return new Entitlement(store, PKG, PRODUCT, false, false);
    }

    private static PurchaseRecord purchase(String product, int state, boolean signatureValid) {
        return new PurchaseRecord(Collections.singletonList(product), state, "token-" + product,
                "GPA.1234", 1_700_000_000_000L, false, signatureValid);
    }

    private static List<PurchaseRecord> list(PurchaseRecord... records) {
        return Arrays.asList(records);
    }

    @Test
    public void lockedByDefault() {
        Entitlement e = release();
        assertFalse(e.isUnlocked());
        assertEquals(Entitlement.Source.NONE, e.source());
    }

    @Test
    public void verifiedPurchaseUnlocksAndIsCached() {
        Entitlement e = release();
        assertEquals(Entitlement.Result.UNLOCKED,
                e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PURCHASED, true)), false));
        assertTrue(e.isUnlocked());
        // a new instance (new process) reads the cache without asking Play
        assertTrue(release().isUnlocked());
        assertEquals(Entitlement.Source.PURCHASE, release().source());
    }

    @Test
    public void otherProductsAreIgnored() {
        Entitlement e = release();
        e.apply(list(purchase("something_else", PurchaseRecord.STATE_PURCHASED, true)), false);
        assertFalse(e.isUnlocked());
    }

    @Test
    public void badSignatureDoesNotUnlock() {
        Entitlement e = release();
        e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PURCHASED, false)), true);
        assertFalse(e.isUnlocked());
    }

    @Test
    public void pendingPurchaseDoesNotUnlock() {
        Entitlement e = release();
        assertEquals(Entitlement.Result.PENDING,
                e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PENDING, true)), false));
        assertFalse(e.isUnlocked());
        assertTrue(e.isPending());
        // payment completes later
        e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PURCHASED, true)), true);
        assertTrue(e.isUnlocked());
        assertFalse(e.isPending());
    }

    @Test
    public void authoritativeEmptyListRevokes() {
        Entitlement e = release();
        e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PURCHASED, true)), false);
        assertEquals(Entitlement.Result.NOT_OWNED, e.apply(Collections.emptyList(), true));
        assertFalse(e.isUnlocked());
    }

    @Test
    public void nonAuthoritativeEmptyListKeepsUnlock() {
        Entitlement e = release();
        e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PURCHASED, true)), false);
        assertEquals(Entitlement.Result.UNCHANGED, e.apply(Collections.emptyList(), false));
        assertEquals(Entitlement.Result.UNCHANGED, e.apply(null, false));
        assertTrue(e.isUnlocked());
    }

    @Test
    public void authoritativeListClearsStalePending() {
        Entitlement e = release();
        e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PENDING, true)), false);
        e.apply(Collections.emptyList(), true); // pending purchase was cancelled
        assertFalse(e.isPending());
    }

    @Test
    public void tamperedTokenIsNotTrusted() {
        Entitlement e = release();
        e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PURCHASED, true)), false);
        store.putString(Entitlement.KEY_TOKEN, "forged");
        assertFalse(release().isUnlocked());
    }

    @Test
    public void handWrittenDigestWithoutInstallIdIsNotTrusted() {
        store.putString(Entitlement.KEY_TOKEN, "anything");
        store.putString(Entitlement.KEY_DIGEST, "0000");
        assertFalse(release().isUnlocked());
    }

    @Test
    public void cacheCopiedToAnotherInstallIsNotTrusted() {
        Entitlement e = release();
        e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PURCHASED, true)), false);
        store.putString(Entitlement.KEY_INSTALL, "another-install");
        assertFalse(release().isUnlocked());
    }

    @Test
    public void digestBindsPackageName() {
        Entitlement e = release();
        e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PURCHASED, true)), false);
        assertFalse(new Entitlement(store, "com.example.other", PRODUCT, false, false).isUnlocked());
    }

    @Test
    public void debugOverrideIgnoredInReleaseBuilds() {
        Entitlement e = release();
        assertFalse(e.setDebugOverride(Entitlement.Override.UNLOCKED));
        store.putString(Entitlement.KEY_OVERRIDE, Entitlement.Override.UNLOCKED.name());
        assertFalse(e.isUnlocked());
        assertEquals(Entitlement.Override.NONE, e.debugOverride());
    }

    @Test
    public void debugOverrideHonouredInDebugBuilds() {
        Entitlement e = new Entitlement(store, PKG, PRODUCT, true, false);
        assertTrue(e.setDebugOverride(Entitlement.Override.UNLOCKED));
        assertEquals(Entitlement.Source.DEBUG_OVERRIDE, e.source());
        // forced lock wins over a real purchase, so the trial can be screenshot on a dev device
        e.apply(list(purchase(PRODUCT, PurchaseRecord.STATE_PURCHASED, true)), false);
        e.setDebugOverride(Entitlement.Override.LOCKED);
        assertFalse(e.isUnlocked());
        e.setDebugOverride(Entitlement.Override.NONE);
        assertEquals(Entitlement.Source.PURCHASE, e.source());
    }

    @Test
    public void corruptOverrideValueIsIgnored() {
        Entitlement e = new Entitlement(store, PKG, PRODUCT, true, false);
        store.putString(Entitlement.KEY_OVERRIDE, "BOGUS");
        assertEquals(Entitlement.Override.NONE, e.debugOverride());
        assertFalse(e.isUnlocked());
    }

    @Test
    public void simulatedPurchaseOnlyWhereAllowed() {
        Entitlement production = release();
        assertFalse(production.setSimulatedPurchase(true));
        store.putBoolean(Entitlement.KEY_SIMULATED, true);
        assertFalse(production.isUnlocked());

        Entitlement develop = new Entitlement(store, PKG, PRODUCT, true, true);
        assertTrue(develop.setSimulatedPurchase(true));
        assertEquals(Entitlement.Source.SIMULATED, develop.source());
        // an authoritative "not owned" from Play does not clear a simulation
        develop.apply(Collections.emptyList(), true);
        assertTrue(develop.isUnlocked());
        develop.setSimulatedPurchase(false);
        assertFalse(develop.isUnlocked());
    }
}
