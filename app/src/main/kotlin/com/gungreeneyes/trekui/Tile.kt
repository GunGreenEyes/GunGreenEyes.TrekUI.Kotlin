package com.gungreeneyes.trekui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * A home-screen shortcut (grid tile or dock slot): an app, an console function, or empty. Also the
 * customisation record for assignable home elements ("slots": elbows, blocks, titles, scene plaques),
 * stored together as JSON in the `slotsJson` pref. Tiles are (de)serialised with
 * [toJson] / [fromJson]; lists are JSON arrays under a pref key.
 *
 * Java interop: the fields are [JvmField] and the companion's functions [JvmStatic], so Java keeps
 * reading `tile.comp` and calling `Tile.load(prefs, key)`.
 */
internal class Tile {
    @JvmField
    var label: String? = ""

    /** Flattened ComponentName. */
    @JvmField
    var comp: String? = null

    /** User serial; -1 = main profile. */
    @JvmField
    var user: Long = -1

    /** One of [ACTIONS], or [A_GOVEE]. */
    @JvmField
    var action: String? = null

    /** Action argument (Govee device ID). */
    @JvmField
    var arg: String? = null

    /** Own sound: library clip ID, "none" = silent, null = the usual sounds. */
    @JvmField
    var sound: String? = null

    /** Palette slot, -1 = automatic. */
    @JvmField
    var color: Int = -1

    /** Neither an app nor a function. */
    fun isEmpty(): Boolean = comp == null && action == null

    /** Serialises the tile (optional fields are omitted when null). */
    fun toJson(): JSONObject {
        val o = JSONObject()
        try {
            o.put("label", label)
            if (comp != null) o.put("comp", comp)
            o.put("user", user)
            if (action != null) o.put("action", action)
            if (arg != null) o.put("arg", arg)
            if (sound != null) o.put("sound", sound)
            o.put("color", color)
        } catch (ignored: Exception) {
        }
        return o
    }

    companion object {
        const val A_APPS = "apps"
        const val A_SEARCH = "search"
        const val A_CONFIG = "config"
        const val A_ANDROID = "android"
        const val A_SHADE = "shade"
        const val A_PANEL = "samsung"
        const val A_CASCADE = "cascade"
        const val A_DIALER = "dialer"
        const val A_GOVEE = "govee"
        const val A_NEST = "nest"
        const val A_CLOAK = "cloak"
        const val A_RED_ALERT = "redalert"

        /** OPEN GROUP (arg = AppGroups group id) and LOCK SCREEN (LockScreen / LockService), LCA-T20. */
        const val A_GROUP = "group"
        const val A_LOCK = "lock"

        /** A Home Assistant entity (LCA-T23); [arg] is its entity_id, see [HomeAssistant.PANEL_SUFFIX]. */
        const val A_HA = "ha"

        @JvmField
        val ACTIONS: Array<String> = arrayOf(
            A_APPS, A_SEARCH, A_CONFIG, A_ANDROID, A_SHADE, A_PANEL, A_CASCADE, A_DIALER, A_CLOAK, A_RED_ALERT, A_LOCK,
        )

        @JvmField
        val ACTION_LABELS: Array<String> = arrayOf(
            "APP DRAWER", "SEARCH", "TREKUI CONFIG", "ANDROID SETTINGS", "TREKUI SHADE",
            "SAMSUNG PANEL", "DATA CASCADE", "TREKUI DIALER", "CLOAK (NIGHT MODE)", "RED ALERT", "LOCK SCREEN",
        )

        // ---- assignable console elements on the home screen ("slots") ----------------------------

        /** id, display name, has editable text. */
        @JvmField
        val SLOTS: Array<Array<String>> = arrayOf(
            arrayOf("title", "TREKUI TITLE", "1"), arrayOf("ship", "SHIP REGISTRY LINE", "1"),
            arrayOf("code0", "CODE BLOCK 1", "1"), arrayOf("code1", "CODE BLOCK 2", "1"),
            arrayOf("code2", "CODE BLOCK 3", "1"), arrayOf("code3", "CODE BLOCK 4", "1"),
            arrayOf("clock", "CHRONOMETER", "0"), arrayOf("date", "DATE", "0"),
            arrayOf("hElbowT", "HEADER TOP ELBOW", "0"), arrayOf("hBlockT0", "HEADER TOP SEGMENT", "0"),
            arrayOf("hElbowB", "HEADER BOTTOM ELBOW", "0"), arrayOf("hBlockB0", "HEADER BOTTOM SEGMENT 1", "0"),
            arrayOf("hBlockB1", "HEADER BOTTOM SEGMENT 2", "0"),
            arrayOf("ops", "OPERATIONS TITLE", "1"), arrayOf("foot", "FOOTER TEXT", "1"),
            arrayOf("mElbowT", "MAIN TOP ELBOW", "0"), arrayOf("mBlockT0", "MAIN TOP SEGMENT", "0"),
            arrayOf("mElbowB", "MAIN BOTTOM ELBOW", "0"), arrayOf("mBlockB0", "MAIN BOTTOM SEGMENT", "0"),
            arrayOf("nav0", "SIDEBAR: APPS", "1"), arrayOf("nav1", "SIDEBAR: SEARCH", "1"),
            arrayOf("nav2", "SIDEBAR: CONFIG", "1"), arrayOf("nav3", "SIDEBAR: BLOCK 47", "1"),
            arrayOf("capL", "DOCK LEFT CAP", "0"), arrayOf("capR", "DOCK RIGHT CAP", "0"),
        )

        /** Display name of a slot id (unknown ids are shown upper-cased). */
        @JvmStatic
        fun slotName(id: String): String = SLOTS.firstOrNull { it[0] == id }?.get(1) ?: id.uppercase(Locale.US)

        /** True when the slot's label text can be edited. */
        @JvmStatic
        fun slotHasText(id: String): Boolean = SLOTS.firstOrNull { it[0] == id }?.get(2) == "1"

        private fun slots(p: Prefs): JSONObject = try {
            JSONObject(p.s("slotsJson", "{}") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }

        /** Customisation for an element, or null if it's untouched. */
        @JvmStatic
        fun slot(p: Prefs, id: String): Tile? = slots(p).optJSONObject(id)?.let { fromJson(it) }

        /** Stores (or with null, clears) the customisation of slot `id` and bumps the layout. */
        @JvmStatic
        fun putSlot(p: Prefs, id: String, t: Tile?) {
            val all = slots(p)
            try {
                if (t == null) all.remove(id) else all.put(id, t.toJson())
            } catch (ignored: Exception) {
            }
            p.put("slotsJson", all.toString())
            p.bump()
        }

        @JvmStatic
        fun customisedSlots(p: Prefs): Int = slots(p).length()

        @JvmStatic
        fun clearSlots(p: Prefs) {
            p.put("slotsJson", "{}")
            p.bump()
        }

        /** Reads a tile written by [toJson]; missing fields take their defaults. */
        @JvmStatic
        fun fromJson(o: JSONObject): Tile = Tile().apply {
            label = o.optString("label", "")
            comp = if (o.has("comp")) o.optString("comp") else null
            user = o.optLong("user", -1)
            action = if (o.has("action")) o.optString("action") else null
            arg = if (o.has("arg")) o.optString("arg") else null
            sound = if (o.has("sound")) o.optString("sound") else null
            color = o.optInt("color", -1)
        }

        /** The tile list stored under `key`, or null when absent or corrupt (callers then use defaults). */
        @JvmStatic
        fun load(p: Prefs, key: String): MutableList<Tile>? {
            val raw = p.s(key, null) ?: return null
            val out = ArrayList<Tile>()
            try {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) out.add(fromJson(a.getJSONObject(i)))
            } catch (e: Exception) {
                return null
            }
            return out
        }

        /** Stores a tile list under `key`. */
        @JvmStatic
        fun save(p: Prefs, key: String, tiles: List<Tile>) {
            val a = JSONArray()
            for (t in tiles) a.put(t.toJson())
            p.put(key, a.toString())
        }

        /** Display label of a function tile's action, or "". */
        @JvmStatic
        fun actionLabel(action: String?): String = when (action) {
            A_GOVEE -> "LIGHTS"
            A_NEST -> "CLIMATE"
            A_GROUP -> "OPEN GROUP"
            A_HA -> "HOME"
            else -> {
                val i = if (action == null) -1 else ACTIONS.indexOf(action)
                if (i >= 0) ACTION_LABELS[i] else ""
            }
        }

        // ---- defaults -------------------------------------------------------------------------

        /** Package of the default (or first) activity for `i`, ignoring the system chooser. */
        private fun resolve(c: Context, i: Intent): String? {
            try {
                val ri = c.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
                if (ri?.activityInfo != null && "android" != ri.activityInfo.packageName &&
                    !ri.activityInfo.name.contains("ResolverActivity")
                ) {
                    return ri.activityInfo.packageName
                }
                val all = c.packageManager.queryIntentActivities(i, 0)
                if (all.isNotEmpty()) return all[0].activityInfo.packageName
            } catch (ignored: Exception) {
            }
            return null
        }

        private fun category(cat: String): Intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, cat)

        private fun app(repo: AppRepo, label: String, vararg pkgsOrNull: String?): Tile {
            val t = Tile()
            t.label = label
            for (pkg in pkgsOrNull) {
                val a = repo.findPackage(pkg)
                if (a != null) {
                    t.comp = a.cn.flattenToShortString()
                    break
                }
            }
            return t
        }

        private fun sms(c: Context): String? = try {
            Telephony.Sms.getDefaultSmsPackage(c)
        } catch (e: Exception) {
            null
        }

        /** First-run home tiles: common apps resolved on this device, plus CONFIG. */
        @JvmStatic
        fun defaultTiles(c: Context, r: AppRepo): MutableList<Tile> {
            val l = ArrayList<Tile>()
            l.add(app(r, "NAVIGATION", resolve(c, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))), "com.google.android.apps.maps"))
            l.add(app(r, "MEDIA", resolve(c, category(Intent.CATEGORY_APP_MUSIC)), "com.google.android.youtube", "com.spotify.music"))
            l.add(app(r, "GALLERY", resolve(c, category(Intent.CATEGORY_APP_GALLERY)), "com.sec.android.gallery3d", "com.google.android.apps.photos"))
            l.add(app(r, "CALENDAR", resolve(c, category(Intent.CATEGORY_APP_CALENDAR)), "com.samsung.android.calendar", "com.google.android.calendar"))
            l.add(app(r, "MAIL", resolve(c, category(Intent.CATEGORY_APP_EMAIL)), "com.google.android.gm", "com.samsung.android.email.provider"))
            l.add(app(r, "SETTINGS", "com.android.settings"))
            l.add(app(r, "CHRONO", resolve(c, Intent(AlarmClock.ACTION_SHOW_ALARMS)), "com.sec.android.app.clockpackage", "com.google.android.deskclock"))
            l.add(app(r, "CALCULATOR", resolve(c, category(Intent.CATEGORY_APP_CALCULATOR)), "com.sec.android.app.popupcalculator", "com.google.android.calculator"))
            l.add(app(r, "CONTACTS", resolve(c, category(Intent.CATEGORY_APP_CONTACTS)), "com.samsung.android.app.contacts", "com.google.android.contacts"))
            l.add(app(r, "STORE", "com.android.vending", "com.sec.android.app.samsungapps"))
            l.add(app(r, "FILES", resolve(c, category(Intent.CATEGORY_APP_FILES)), "com.sec.android.app.myfiles", "com.google.android.apps.nbu.files"))
            l.add(Tile().apply {
                label = "CONFIG"
                action = A_CONFIG
            })
            for (t in l) if (t.isEmpty()) t.label = ""
            return l
        }

        /** First-run dock: phone, messages, browser, camera, mail. */
        @JvmStatic
        fun defaultDock(c: Context, r: AppRepo): MutableList<Tile> {
            val l = ArrayList<Tile>()
            l.add(app(r, "PHONE", resolve(c, Intent(Intent.ACTION_DIAL)), "com.samsung.android.dialer", "com.google.android.dialer"))
            l.add(app(r, "MESSAGES", sms(c), "com.samsung.android.messaging", "com.google.android.apps.messaging"))
            l.add(
                app(
                    r, "BROWSER",
                    resolve(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)),
                    "com.android.chrome", "com.sec.android.app.sbrowser",
                ),
            )
            l.add(app(r, "CAMERA", resolve(c, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)), "com.sec.android.app.camera"))
            l.add(app(r, "MAIL", resolve(c, category(Intent.CATEGORY_APP_EMAIL)), "com.google.android.gm"))
            return l
        }
    }
}
