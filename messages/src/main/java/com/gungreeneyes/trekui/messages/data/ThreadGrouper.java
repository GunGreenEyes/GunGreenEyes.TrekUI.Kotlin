package com.gungreeneyes.trekui.messages.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure conversation logic (unit-tested): groups loose messages into conversations and merges the SMS and
 * MMS rows of one thread into a single time-ordered list.
 *
 * <p>Grouping key: the provider's thread id when it has one; otherwise the sender's
 * {@link PhoneNumbers#key}, so messages without a thread (search hits from odd providers, freshly
 * received rows) still land with the right person.
 */
public final class ThreadGrouper {
    private ThreadGrouper() {}

    /** One conversation as the list shows it. */
    public static final class Summary {
        public final long threadId;
        /** Distinct participants (normalised), in first-seen order. */
        public final List<String> addresses;
        public final MessageItem latest;
        public final int unread;
        public final int count;

        Summary(long threadId, List<String> addresses, MessageItem latest, int unread, int count) {
            this.threadId = threadId;
            this.addresses = addresses;
            this.latest = latest;
            this.unread = unread;
            this.count = count;
        }
    }

    /** Orders messages oldest first; equal times keep SMS before MMS, then by id (stable, total). */
    public static final Comparator<MessageItem> CHRONOLOGICAL = (a, b) -> {
        int c = Long.compare(a.dateMillis, b.dateMillis);
        if (c != 0) return c;
        c = Boolean.compare(a.mms, b.mms);
        return c != 0 ? c : Long.compare(a.id, b.id);
    };

    /** The grouping key of a message (see class docs). */
    public static String groupKey(MessageItem m) {
        return m.threadId > 0 ? "t" + m.threadId : "a" + PhoneNumbers.key(m.address);
    }

    /**
     * Groups {@code messages} into conversations, newest conversation first. Each summary carries the
     * latest message, the number of unread incoming messages and the total count.
     */
    public static List<Summary> group(Collection<MessageItem> messages) {
        Map<String, List<MessageItem>> byKey = new LinkedHashMap<>();
        for (MessageItem m : messages) {
            byKey.computeIfAbsent(groupKey(m), k -> new ArrayList<>()).add(m);
        }
        List<Summary> out = new ArrayList<>(byKey.size());
        for (List<MessageItem> list : byKey.values()) {
            MessageItem latest = null;
            int unread = 0;
            Set<String> who = new LinkedHashSet<>();
            Set<String> whoKeys = new LinkedHashSet<>();
            for (MessageItem m : list) {
                if (latest == null || CHRONOLOGICAL.compare(m, latest) > 0) latest = m;
                if (m.unread()) unread++;
                String k = PhoneNumbers.key(m.address);
                if (!k.isEmpty() && whoKeys.add(k)) who.add(PhoneNumbers.normalize(m.address));
            }
            out.add(new Summary(latest.threadId, new ArrayList<>(who), latest, unread, list.size()));
        }
        out.sort((a, b) -> CHRONOLOGICAL.compare(b.latest, a.latest));
        return out;
    }

    /**
     * Merges the SMS and MMS rows of one thread oldest first, dropping duplicates (same
     * {@link MessageItem#uid}). Either list may be null.
     */
    public static List<MessageItem> merge(List<MessageItem> sms, List<MessageItem> mms) {
        Map<String, MessageItem> unique = new LinkedHashMap<>();
        if (sms != null) for (MessageItem m : sms) unique.putIfAbsent(m.uid(), m);
        if (mms != null) for (MessageItem m : mms) unique.putIfAbsent(m.uid(), m);
        List<MessageItem> out = new ArrayList<>(unique.values());
        out.sort(CHRONOLOGICAL);
        return out;
    }

    /**
     * Keeps only the newest {@code max} messages of a chronological list (a long thread opens on its
     * recent history).
     */
    public static List<MessageItem> newest(List<MessageItem> chronological, int max) {
        if (chronological.size() <= max) return chronological;
        return new ArrayList<>(chronological.subList(chronological.size() - max, chronological.size()));
    }

    /**
     * True if a typed recipient list names exactly the participants of a conversation (order and
     * formatting ignored).
     */
    public static boolean sameParticipants(Collection<String> a, Collection<String> b) {
        String ka = PhoneNumbers.setKey(a);
        return !ka.isEmpty() && ka.equals(PhoneNumbers.setKey(b));
    }
}
