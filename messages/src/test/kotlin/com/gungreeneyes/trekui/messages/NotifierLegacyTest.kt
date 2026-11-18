package com.gungreeneyes.trekui.messages

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** TUK-T14: the pure part of the Android 6.0 notification fallback ([LegacyNotifications]; Java edition LCA-T65). */
class NotifierLegacyTest {

    @Test
    fun inboxLineNamesTheSender() {
        assertThat(LegacyNotifications.inboxLine("Picard", "Engage")).isEqualTo("Picard: Engage")
        assertThat(LegacyNotifications.inboxLine(null, "Engage")).isEqualTo(": Engage")
        assertThat(LegacyNotifications.inboxLine("Data", null)).isEqualTo("Data: ")
    }
}
