package com.gungreeneyes.trekui.messages.data

/**
 * Pure conversation logic (unit-tested): groups loose messages into conversations and merges the SMS and
 * MMS rows of one thread into a single time-ordered list.
 *
 * Grouping key: the provider's thread id when it has one; otherwise the sender's
 * [PhoneNumbers.key], so messages without a thread (search hits from odd providers, freshly
 * received rows) still land with the right person.
 */
internal object ThreadGrouper {
    /**
     * One conversation as the list shows it.
     *
     * @property addresses distinct participants (normalised), in first-seen order
     */
    data class Summary(
        val threadId: Long,
        val addresses: List<String>,
        val latest: MessageItem,
        val unread: Int,
        val count: Int,
    )

    /** Orders messages oldest first; equal times keep SMS before MMS, then by id (stable, total). */
    val CHRONOLOGICAL: Comparator<MessageItem> =
        compareBy<MessageItem> { it.dateMillis }.thenBy { it.mms }.thenBy { it.id }

    /** The grouping key of a message (see class docs). */
    fun groupKey(m: MessageItem): String =
        if (m.threadId > 0) "t${m.threadId}" else "a" + PhoneNumbers.key(m.address)

    /**
     * Groups [messages] into conversations, newest conversation first. Each summary carries the
     * latest message, the number of unread incoming messages and the total count.
     */
    fun group(messages: Collection<MessageItem>): List<Summary> {
        val byKey = LinkedHashMap<String, MutableList<MessageItem>>()
        for (m in messages) byKey.getOrPut(groupKey(m)) { ArrayList() }.add(m)
        val out = ArrayList<Summary>(byKey.size)
        for (list in byKey.values) {
            var latest = list.first()
            var unread = 0
            val who = LinkedHashSet<String>()
            val whoKeys = HashSet<String>()
            for (m in list) {
                if (CHRONOLOGICAL.compare(m, latest) > 0) latest = m
                if (m.unread) unread++
                val k = PhoneNumbers.key(m.address)
                if (k.isNotEmpty() && whoKeys.add(k)) who.add(PhoneNumbers.normalize(m.address))
            }
            out.add(Summary(latest.threadId, ArrayList(who), latest, unread, list.size))
        }
        out.sortWith { a, b -> CHRONOLOGICAL.compare(b.latest, a.latest) }
        return out
    }

    /**
     * Merges the SMS and MMS rows of one thread oldest first, dropping duplicates (same
     * [MessageItem.uid]). Either list may be null.
     */
    fun merge(sms: List<MessageItem>?, mms: List<MessageItem>?): List<MessageItem> {
        val unique = LinkedHashMap<String, MessageItem>()
        sms?.forEach { unique.putIfAbsent(it.uid, it) }
        mms?.forEach { unique.putIfAbsent(it.uid, it) }
        return unique.values.sortedWith(CHRONOLOGICAL)
    }

    /**
     * Keeps only the newest [max] messages of a chronological list (a long thread opens on its
     * recent history).
     */
    fun newest(chronological: List<MessageItem>, max: Int): List<MessageItem> =
        if (chronological.size <= max) chronological else chronological.takeLast(max)

    /**
     * True if a typed recipient list names exactly the participants of a conversation (order and
     * formatting ignored).
     */
    fun sameParticipants(a: Collection<String>, b: Collection<String>): Boolean {
        val ka = PhoneNumbers.setKey(a)
        return ka.isNotEmpty() && ka == PhoneNumbers.setKey(b)
    }
}
