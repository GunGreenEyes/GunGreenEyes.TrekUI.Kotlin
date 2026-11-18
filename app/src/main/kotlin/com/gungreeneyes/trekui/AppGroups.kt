package com.gungreeneyes.trekui

import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.Locale

/**
 * App groups for the OPEN GROUP function (gap A6): named sets of up to [MAX_APPS] apps that a
 * tile, slot, dock icon or gesture unfolds as a [GroupPanel].
 *
 * Groups are stored as one JSON array in the `"trekui"` preferences under [KEY]. Every
 * key this feature writes starts with [KEY_PREFIX] so a layout export (which copies the whole
 * preference file) carries the groups along. A tile points at a group by its stable [Group.id],
 * so renaming or reordering a group never breaks an assignment.
 *
 * The encoding and the list edits are pure functions (no Android types) so they are tested
 * on the JVM in `AppGroupsTest`.
 */
internal object AppGroups {

    /** Prefix shared by every preference key of the app-group feature. */
    const val KEY_PREFIX = "group."

    /** Preference key holding the JSON array of groups. */
    const val KEY = KEY_PREFIX + "list"

    /** Most apps one group holds (the panels lay out at most twelve keys). */
    const val MAX_APPS = 12

    /** Most groups the user can create. */
    const val MAX_GROUPS = 24

    /** Longest group name, in characters. */
    const val MAX_NAME = 20

    /** Name given to a group created without one. */
    const val DEFAULT_NAME = "GROUP"

    /** One app in a group: a flattened component name plus the profile's user serial (-1 = main profile). */
    class Member(
        @JvmField val comp: String,
        @JvmField val user: Long,
    ) {
        /** True when both point at the same app in the same profile. */
        fun same(other: Member?): Boolean = other != null && comp == other.comp && user == other.user
    }

    /** A named, ordered set of apps. */
    class Group(
        @JvmField val id: String,
        @JvmField var name: String,
    ) {
        @JvmField
        val apps: MutableList<Member> = ArrayList()
    }

    // ---- encoding ------------------------------------------------------------------------------

    /**
     * Decodes the stored JSON. Corrupt input gives an empty list; groups without an id, duplicate ids,
     * members without a component and duplicate members are dropped, and each group is capped at
     * [MAX_APPS] apps, so a hand-edited or imported layout can never crash the home screen.
     */
    @JvmStatic
    fun parse(raw: String?): MutableList<Group> {
        val out = ArrayList<Group>()
        if (raw.isNullOrEmpty()) return out
        try {
            val array = JSONArray(raw)
            var i = 0
            while (i < array.length() && out.size < MAX_GROUPS) {
                val o = array.optJSONObject(i++) ?: continue
                val id = o.optString("id", "")
                if (id.isEmpty() || find(out, id) != null) continue
                val g = Group(id, cleanName(o.optString("name", DEFAULT_NAME)))
                val apps = o.optJSONArray("apps")
                if (apps != null) {
                    for (k in 0 until apps.length()) {
                        val a = apps.optJSONObject(k) ?: continue
                        val comp = a.optString("comp", "")
                        if (comp.isEmpty()) continue
                        addApp(g, Member(comp, a.optLong("user", -1)))
                    }
                }
                out.add(g)
            }
        } catch (e: Exception) {
            return ArrayList()
        }
        return out
    }

    /** Encodes groups as the JSON array [parse] reads. */
    @JvmStatic
    fun encode(groups: List<Group>): String {
        val array = JSONArray()
        try {
            for (g in groups) {
                val apps = JSONArray()
                for (m in g.apps) apps.put(JSONObject().put("comp", m.comp).put("user", m.user))
                array.put(JSONObject().put("id", g.id).put("name", g.name).put("apps", apps))
            }
        } catch (ignored: Exception) {
            // JSONObject.put only throws for non-finite numbers, which never occur here
        }
        return array.toString()
    }

    // ---- persistence ---------------------------------------------------------------------------

    /** The stored groups (a fresh, editable copy). */
    @JvmStatic
    fun load(prefs: Prefs): MutableList<Group> = parse(prefs.s(KEY, null))

    /** Stores the groups and marks the home layout stale so tiles showing a group name refresh. */
    @JvmStatic
    fun save(prefs: Prefs, groups: List<Group>) {
        prefs.put(KEY, encode(groups))
        prefs.bump()
    }

    // ---- pure edits ----------------------------------------------------------------------------

    /** The group with `id`, or null. */
    @JvmStatic
    fun find(groups: List<Group>, id: String?): Group? {
        if (id == null) return null
        return groups.firstOrNull { it.id == id }
    }

    /** A new id not used by any group: "g1", "g2", … */
    @JvmStatic
    fun newId(groups: List<Group>): String {
        var n = 1
        while (find(groups, "g$n") != null) n++
        return "g$n"
    }

    /** Upper-cases, trims and caps a group name; blank names become [DEFAULT_NAME]. */
    @JvmStatic
    fun cleanName(name: String?): String {
        val n = name?.javaTrim()?.uppercase(Locale.getDefault()) ?: ""
        if (n.isEmpty()) return DEFAULT_NAME
        return if (n.length > MAX_NAME) n.substring(0, MAX_NAME).javaTrim() else n
    }

    /** Appends a new group named `name` and returns it, or null when [MAX_GROUPS] exist. */
    @JvmStatic
    fun create(groups: MutableList<Group>, name: String?): Group? {
        if (groups.size >= MAX_GROUPS) return null
        val g = Group(newId(groups), cleanName(name))
        groups.add(g)
        return g
    }

    /** Index of `member` in the group, or -1. */
    @JvmStatic
    fun indexOf(g: Group, member: Member?): Int = g.apps.indexOfFirst { it.same(member) }

    /** Adds an app at the end; false when it is already there or the group is full. */
    @JvmStatic
    fun addApp(g: Group, member: Member): Boolean {
        if (indexOf(g, member) >= 0 || g.apps.size >= MAX_APPS) return false
        g.apps.add(member)
        return true
    }

    /** Removes an app; false when it was not in the group. */
    @JvmStatic
    fun removeApp(g: Group, member: Member?): Boolean {
        val i = indexOf(g, member)
        if (i < 0) return false
        g.apps.removeAt(i)
        return true
    }

    /**
     * Moves the item at `from` one step (`delta` -1 = earlier, +1 = later). Returns false
     * (and changes nothing) at either end or for an invalid index.
     */
    @JvmStatic
    fun <T> move(list: MutableList<T>, from: Int, delta: Int): Boolean {
        val to = from + delta
        if (from < 0 || from >= list.size || to < 0 || to >= list.size) return false
        Collections.swap(list, from, to)
        return true
    }

    /** Removes the group with `id`; false when there was none. */
    @JvmStatic
    fun delete(groups: MutableList<Group>, id: String?): Boolean {
        val g = find(groups, id)
        return g != null && groups.remove(g)
    }
}
