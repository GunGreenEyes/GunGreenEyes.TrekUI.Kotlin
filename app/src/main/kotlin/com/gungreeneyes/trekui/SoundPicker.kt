package com.gungreeneyes.trekui

import android.content.Context

/**
 * Choosing a sound in two steps: first a group (LAUNCHER, COMPUTER, CONSOLE, COMMS, ALERTS,
 * WEAPONS, WARP, TRANSPORTER, SCANNER, …, IMPORTED, each with its count), then the sound. The
 * group holding the current choice is marked ●, and so is the choice itself. Fixed options a caller
 * needs (DEFAULT, SILENT, IMPORT…) sit above the groups.
 */
internal object SoundPicker {
    fun interface Picked {
        /** value: a clip id, or one of the caller's own top-option values. */
        fun picked(value: String)
    }

    class Group(@JvmField val name: String) {
        @JvmField val items: MutableList<SoundLibrary.Entry> = ArrayList()
    }

    /** True for ids whose label carries a "GROUP: " prefix (source-library and synthesized clips). */
    private fun grouped(id: String): Boolean = id.startsWith("tk_") || Sfx.isSynth(id)

    /** "WEAPONS: BEAM 1" → group WEAPONS, name BEAM 1. Older bundled clips are LAUNCHER; imports are IMPORTED. */
    @JvmStatic
    fun groupOf(e: SoundLibrary.Entry): String {
        if (e.imported) return "IMPORTED"
        if (grouped(e.id)) {
            val k = e.label.indexOf(": ")
            if (k > 0) return e.label.substring(0, k)
        }
        return "LAUNCHER"
    }

    /** The sound's name inside its group (the group prefix removed). */
    @JvmStatic
    fun nameOf(e: SoundLibrary.Entry): String {
        if (grouped(e.id)) {
            val k = e.label.indexOf(": ")
            if (k > 0) return e.label.substring(k + 2)
        }
        return e.label
    }

    @JvmStatic
    fun groups(c: Context): List<Group> {
        val m = LinkedHashMap<String, Group>()
        m["LAUNCHER"] = Group("LAUNCHER")
        for (e in SoundLibrary.all(c)) {
            val g = groupOf(e)
            m.getOrPut(g) { Group(g) }.items.add(e)
        }
        val imp = m.remove("IMPORTED")
        val out = ArrayList<Group>()
        for (g in m.values) if (g.items.isNotEmpty()) out.add(g)
        if (imp != null && imp.items.isNotEmpty()) out.add(imp)
        return out
    }

    /**
     * @param topLabels  fixed options shown above the groups (may be empty)
     * @param topValues  what each fixed option returns
     * @param current    the current clip id (marked ●), or null
     * @param defaultId  a clip to tag "(DEFAULT)", or null
     */
    @JvmStatic
    fun pick(
        c: Context, title: String, topLabels: Array<String>, topValues: Array<String>,
        current: String?, defaultId: String?, cb: Picked,
    ) {
        val groups = groups(c)
        val labels = ArrayList<String>()
        labels.addAll(topLabels)
        var curGroup: String? = null
        if (current != null) {
            for (g in groups) for (e in g.items) if (e.id == current) curGroup = g.name
        }
        for (g in groups) {
            labels.add((if (g.name == curGroup) "● " else "") + g.name + " (" + g.items.size + ")  ›")
        }
        ConsoleDialog.options(c, title, labels.toTypedArray()) { k ->
            if (k < topLabels.size) {
                cb.picked(topValues[k])
            } else {
                showGroup(c, title, groups[k - topLabels.size], topLabels, topValues, current, defaultId, cb)
            }
        }
    }

    private fun showGroup(
        c: Context, title: String, g: Group, topLabels: Array<String>, topValues: Array<String>,
        current: String?, defaultId: String?, cb: Picked,
    ) {
        val labels = ArrayList<String>()
        labels.add("‹ ALL GROUPS")
        for (e in g.items) {
            labels.add((if (e.id == current) "● " else "") + nameOf(e) + (if (e.id == defaultId) " (DEFAULT)" else ""))
        }
        ConsoleDialog.options(c, title + " • " + g.name, labels.toTypedArray()) { k ->
            if (k == 0) {
                pick(c, title, topLabels, topValues, current, defaultId, cb)
            } else {
                cb.picked(g.items[k - 1].id)
            }
        }
    }
}
