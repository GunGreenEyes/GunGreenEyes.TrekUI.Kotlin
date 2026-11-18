package com.gungreeneyes.trekui.messages

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

/**
 * Dual-SIM support: the active SIM subscriptions, the system's default SMS SIM, and an
 * [SmsManager] bound to a chosen subscription. Everything degrades to "one default SIM" when
 * READ_PHONE_STATE is missing or the device has no telephony.
 */
internal object Sims {
    /** A SIM the user can send from. */
    data class Sim(val subId: Int, val slot: Int, val label: String)

    /** Active SIMs in slot order (empty when unknown). */
    fun active(c: Context): List<Sim> {
        if (c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val out = ArrayList<Sim>()
        try {
            val sm = c.getSystemService(SubscriptionManager::class.java)
            sm?.activeSubscriptionInfoList?.forEach { i ->
                val name = i.displayName
                val label = "SIM " + (i.simSlotIndex + 1) + if (name.isNullOrEmpty()) "" else " $name"
                out.add(Sim(i.subscriptionId, i.simSlotIndex, label))
            }
        } catch (e: RuntimeException) {
            return emptyList()
        }
        out.sortBy { it.slot }
        return out
    }

    /** The system's default SMS subscription, or -1 (INVALID_SUBSCRIPTION_ID) when none is set. */
    fun defaultSubId(): Int = SmsManager.getDefaultSmsSubscriptionId()

    /** The SmsManager for [subId]; the default SIM's for -1 / invalid. */
    @Suppress("DEPRECATION")
    fun manager(c: Context, subId: Int): SmsManager {
        val valid = subId >= 0 // INVALID_SUBSCRIPTION_ID is -1
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = c.getSystemService(SmsManager::class.java)
            return if (valid) base.createForSubscriptionId(subId) else base
        }
        return if (valid) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
    }

    /** Short label of [subId] among [sims] ("SIM 2"), or "" when single-SIM / unknown. */
    fun shortLabel(sims: List<Sim>, subId: Int): String {
        if (sims.size < 2) return ""
        return sims.firstOrNull { it.subId == subId }?.let { "SIM " + (it.slot + 1) } ?: ""
    }

    /** The subscription a received SMS/MMS arrived on, from the broadcast's extras, or -1. */
    fun subIdFrom(intent: Intent?): Int {
        if (intent == null) return -1
        val id = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1)
        return if (id < 0) intent.getIntExtra("subscription", -1) else id
    }
}
