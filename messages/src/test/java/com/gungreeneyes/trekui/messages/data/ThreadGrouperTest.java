package com.gungreeneyes.trekui.messages.data;

import static com.google.common.truth.Truth.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class ThreadGrouperTest {
    private static MessageItem in(long id, long thread, String from, long t, boolean read) {
        return MessageItem.sms(id, thread, from, t, "m" + id, read, false);
    }

    private static MessageItem out(long id, long thread, String to, long t) {
        return MessageItem.sms(id, thread, to, t, "m" + id, true, true);
    }

    @Test
    public void groupsByThreadNewestFirstWithUnreadCounts() {
        List<ThreadGrouper.Summary> s = ThreadGrouper.group(Arrays.asList(
                in(1, 10, "5550102345", 100, false),
                out(2, 10, "5550102345", 200),
                in(3, 20, "5559990000", 300, false),
                in(4, 20, "5559990000", 50, true)));
        assertThat(s).hasSize(2);
        assertThat(s.get(0).threadId).isEqualTo(20);
        assertThat(s.get(0).unread).isEqualTo(1);
        assertThat(s.get(0).count).isEqualTo(2);
        assertThat(s.get(1).latest.id).isEqualTo(2);
        assertThat(s.get(1).unread).isEqualTo(1);
    }

    @Test
    public void messagesWithoutThreadGroupBySender() {
        List<ThreadGrouper.Summary> s = ThreadGrouper.group(Arrays.asList(
                in(1, 0, "+1 555 010 2345", 100, true),
                in(2, 0, "(555) 010-2345", 200, true)));
        assertThat(s).hasSize(1);
        assertThat(s.get(0).addresses).containsExactly("+15550102345");
    }

    @Test
    public void mergeOrdersSmsAndMmsAndDropsDuplicates() {
        MessageItem sms1 = in(1, 5, "1", 100, true);
        MessageItem sms2 = in(2, 5, "1", 300, true);
        MessageItem mms = new MessageItem(1, 5, "1", 200, "", true, false, true, null, -1,
                Arrays.asList("content://mms/part/9"));
        List<MessageItem> merged = ThreadGrouper.merge(Arrays.asList(sms2, sms1, sms1), Arrays.asList(mms));
        assertThat(merged).containsExactly(sms1, mms, sms2).inOrder();
    }

    @Test
    public void newestKeepsTail() {
        List<MessageItem> all = Arrays.asList(in(1, 1, "1", 1, true), in(2, 1, "1", 2, true), in(3, 1, "1", 3, true));
        assertThat(ThreadGrouper.newest(all, 2)).containsExactly(all.get(1), all.get(2)).inOrder();
    }

    @Test
    public void sameParticipants() {
        assertThat(ThreadGrouper.sameParticipants(Arrays.asList("5550102345", "5559990000"),
                Arrays.asList("+1 555 999 0000", "555-010-2345"))).isTrue();
        assertThat(ThreadGrouper.sameParticipants(Arrays.asList("5550102345"),
                Arrays.asList("5550102345", "5559990000"))).isFalse();
    }
}
