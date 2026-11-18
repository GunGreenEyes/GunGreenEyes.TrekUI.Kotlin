package com.gungreeneyes.trekui.messages.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThreadGrouperTest {
    private fun incoming(id: Long, thread: Long, from: String, t: Long, read: Boolean): MessageItem =
        MessageItem.sms(id, thread, from, t, "m$id", read, false)

    private fun outgoing(id: Long, thread: Long, to: String, t: Long): MessageItem =
        MessageItem.sms(id, thread, to, t, "m$id", true, true)

    @Test
    fun groupsByThreadNewestFirstWithUnreadCounts() {
        val s = ThreadGrouper.group(
            listOf(
                incoming(1, 10, "5550102345", 100, false),
                outgoing(2, 10, "5550102345", 200),
                incoming(3, 20, "5559990000", 300, false),
                incoming(4, 20, "5559990000", 50, true),
            ),
        )
        assertThat(s).hasSize(2)
        assertThat(s[0].threadId).isEqualTo(20L)
        assertThat(s[0].unread).isEqualTo(1)
        assertThat(s[0].count).isEqualTo(2)
        assertThat(s[1].latest.id).isEqualTo(2L)
        assertThat(s[1].unread).isEqualTo(1)
    }

    @Test
    fun messagesWithoutThreadGroupBySender() {
        val s = ThreadGrouper.group(
            listOf(
                incoming(1, 0, "+1 555 010 2345", 100, true),
                incoming(2, 0, "(555) 010-2345", 200, true),
            ),
        )
        assertThat(s).hasSize(1)
        assertThat(s[0].addresses).containsExactly("+15550102345")
    }

    @Test
    fun mergeOrdersSmsAndMmsAndDropsDuplicates() {
        val sms1 = incoming(1, 5, "1", 100, true)
        val sms2 = incoming(2, 5, "1", 300, true)
        val mms = MessageItem(
            id = 1, threadId = 5, address = "1", dateMillis = 200, body = "", read = true, outgoing = false, mms = true,
            subId = -1, images = listOf("content://mms/part/9"),
        )
        val merged = ThreadGrouper.merge(listOf(sms2, sms1, sms1), listOf(mms))
        assertThat(merged).containsExactly(sms1, mms, sms2).inOrder()
    }

    @Test
    fun newestKeepsTail() {
        val all = listOf(incoming(1, 1, "1", 1, true), incoming(2, 1, "1", 2, true), incoming(3, 1, "1", 3, true))
        assertThat(ThreadGrouper.newest(all, 2)).containsExactly(all[1], all[2]).inOrder()
    }

    @Test
    fun sameParticipants() {
        assertThat(
            ThreadGrouper.sameParticipants(listOf("5550102345", "5559990000"), listOf("+1 555 999 0000", "555-010-2345")),
        ).isTrue()
        assertThat(ThreadGrouper.sameParticipants(listOf("5550102345"), listOf("5550102345", "5559990000"))).isFalse()
    }
}
