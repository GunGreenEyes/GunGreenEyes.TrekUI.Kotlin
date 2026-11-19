package com.gungreeneyes.trekui.messages.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeliveryStatusTest {
    private val complete = DeliveryStatus.COMPLETE
    private val pending = DeliveryStatus.PENDING
    private val failed = DeliveryStatus.FAILED

    @Test
    fun tpStatusRanges() {
        assertThat(DeliveryStatus.fromTp(0x00)).isEqualTo(complete)
        assertThat(DeliveryStatus.fromTp(0x1F)).isEqualTo(complete)
        assertThat(DeliveryStatus.fromTp(0x20)).isEqualTo(pending)
        assertThat(DeliveryStatus.fromTp(0x3F)).isEqualTo(pending)
        assertThat(DeliveryStatus.fromTp(0x40)).isEqualTo(failed)
    }

    @Test
    fun aFailedEarlierPartIsNotOverwrittenByTheLastPartsComplete() {
        assertThat(DeliveryStatus.merge(mapOf(0 to failed, 1 to complete, 2 to complete), 3)).isEqualTo(failed)
    }

    @Test
    fun worstPartWins() {
        assertThat(DeliveryStatus.merge(mapOf(0 to pending, 1 to complete), 2)).isEqualTo(pending)
        assertThat(DeliveryStatus.merge(mapOf(0 to complete, 1 to complete), 2)).isEqualTo(complete)
        assertThat(DeliveryStatus.merge(mapOf(0 to pending, 1 to failed), 2)).isEqualTo(failed)
    }

    @Test
    fun partsNotYetReportedCountAsPending() {
        assertThat(DeliveryStatus.merge(mapOf(2 to complete), 3)).isEqualTo(pending)
        assertThat(DeliveryStatus.settled(mapOf(2 to complete), 3)).isFalse()
        assertThat(DeliveryStatus.settled(mapOf(0 to failed, 1 to complete), 2)).isTrue()
        assertThat(DeliveryStatus.settled(mapOf(0 to pending, 1 to complete), 2)).isFalse()
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val m = mapOf(0 to failed, 3 to complete)
        assertThat(DeliveryStatus.decode(DeliveryStatus.encode(m))).isEqualTo(m)
        assertThat(DeliveryStatus.decode(null)).isEmpty()
        assertThat(DeliveryStatus.decode("x:1,2:y,4:32")).containsExactly(4, pending)
    }
}
