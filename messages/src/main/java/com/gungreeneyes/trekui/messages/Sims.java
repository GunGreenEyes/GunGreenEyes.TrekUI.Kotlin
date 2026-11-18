package com.gungreeneyes.trekui.messages;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dual-SIM support: the active SIM subscriptions, the system's default SMS SIM, and an
 * {@link SmsManager} bound to a chosen subscription. Everything degrades to "one default SIM" when
 * READ_PHONE_STATE is missing or the device has no telephony.
 */
public final class Sims {
    /** A SIM the user can send from. */
    public static final class Sim {
        public final int subId;
        public final int slot;
        public final String label;

        Sim(int subId, int slot, String label) {
            this.subId = subId;
            this.slot = slot;
            this.label = label;
        }
    }

    private Sims() {}

    /** Active SIMs in slot order (empty when unknown). */
    public static List<Sim> active(Context c) {
        if (c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return Collections.emptyList();
        }
        List<Sim> out = new ArrayList<>();
        try {
            SubscriptionManager sm = c.getSystemService(SubscriptionManager.class);
            List<SubscriptionInfo> list = sm == null ? null : sm.getActiveSubscriptionInfoList();
            if (list != null) {
                for (SubscriptionInfo i : list) {
                    CharSequence name = i.getDisplayName();
                    String label = "SIM " + (i.getSimSlotIndex() + 1)
                            + (name == null || name.length() == 0 ? "" : " " + name);
                    out.add(new Sim(i.getSubscriptionId(), i.getSimSlotIndex(), label));
                }
            }
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
        out.sort((a, b) -> Integer.compare(a.slot, b.slot));
        return out;
    }

    /** The system's default SMS subscription, or -1 (INVALID_SUBSCRIPTION_ID) when none is set. */
    public static int defaultSubId() {
        return SmsManager.getDefaultSmsSubscriptionId();
    }

    /** The SmsManager for {@code subId}; the default SIM's for -1 / invalid. */
    @SuppressWarnings("deprecation")
    public static SmsManager manager(Context c, int subId) {
        boolean valid = subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID && subId >= 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SmsManager base = c.getSystemService(SmsManager.class);
            return valid ? base.createForSubscriptionId(subId) : base;
        }
        return valid ? SmsManager.getSmsManagerForSubscriptionId(subId) : SmsManager.getDefault();
    }

    /** Short label of {@code subId} among {@code sims} ("SIM 2"), or "" when single-SIM / unknown. */
    public static String shortLabel(List<Sim> sims, int subId) {
        if (sims.size() < 2) return "";
        for (Sim s : sims) if (s.subId == subId) return "SIM " + (s.slot + 1);
        return "";
    }

    /** The subscription a received SMS/MMS arrived on, from the broadcast's extras, or -1. */
    public static int subIdFrom(android.content.Intent intent) {
        if (intent == null) return -1;
        int id = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1);
        if (id < 0) id = intent.getIntExtra("subscription", -1);
        return id;
    }
}
