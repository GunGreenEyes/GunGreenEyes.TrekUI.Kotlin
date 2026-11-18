package com.gungreeneyes.trekui

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.UserHandle
import java.util.Locale

/**
 * One launchable activity in one user profile (main, work profile, Secure Folder), as listed by
 * [AppRepo]. Holds the label, a stable key for preferences, the console drawer category and
 * the icons (raw system icon plus the console-styled one made by [IconFactory]).
 *
 * Categorising is a pure function of package name, label and the app's declared category
 * ([categorize]), unit-tested in `AppInfoTest`.
 */
internal class AppInfo(
    @JvmField val cn: ComponentName,
    @JvmField val user: UserHandle?,
    /** Serial number of the user profile (stable across reboots, unlike the user id). */
    @JvmField val serial: Long,
    /** True for the person's own profile (not work profile / Secure Folder). */
    @JvmField val mainProfile: Boolean,
    label: String?,
    ai: ApplicationInfo?,
) {
    /** Display label (the package name when the app has none). */
    @JvmField
    val label: String = if (label.isNullOrEmpty()) cn.packageName else label

    /** [label] in lower case, for searching. */
    @JvmField
    val lower: String = this.label.lowercase(Locale.getDefault())

    /** The package. */
    @JvmField
    val pkg: String = cn.packageName

    /** The preference key (see [key]). */
    @JvmField
    val key: String = key(cn.flattenToShortString(), serial)

    /** One of the `CAT_*` constants (never [CAT_ALL]). */
    @JvmField
    val category: Int = categorize(pkg, lower, ai)

    /** The system's (badged) icon. */
    @JvmField
    var rawIcon: Drawable? = null

    /** The icon drawn in the current interface's style. */
    @JvmField
    var icon: Drawable? = null

    /** Glyph name chosen by [IconFactory.assignGlyphs] for interfaces that draw glyphs. */
    @JvmField
    var glyph: String? = null

    /** The system icon before an icon pack replaced [rawIcon] (see [IconPacks.apply]). */
    @JvmField
    var sysIcon: Drawable? = null

    /** True when the user picked [glyph] for this app (CHANGE ICON): drawn in every icon style. */
    @JvmField
    var glyphPinned: Boolean = false

    companion object {
        /** Drawer categories; indexes into [CAT_NAMES]. */
        const val CAT_ALL = 0
        const val CAT_COMM = 1
        const val CAT_MEDIA = 2
        const val CAT_WORK = 3
        const val CAT_NAV = 4
        const val CAT_GAMES = 5
        const val CAT_SYSTEM = 6
        const val CAT_OTHER = 7

        @JvmField
        val CAT_NAMES: Array<String> = arrayOf("ALL", "COMM", "MEDIA", "WORK", "NAV", "GAMES", "SYSTEM", "OTHER")

        /** Preference key for a component in a profile: "pkg/.Activity#serial". */
        @JvmStatic
        fun key(flat: String, serial: Long): String = "$flat#$serial"

        private fun any(s: String, vararg parts: String): Boolean = parts.any { s.contains(it) }

        /**
         * Picks the drawer category: games first (declared), then well-known package/label words for
         * communications and navigation, then the app's declared category, then media / work words,
         * then system packages; everything else is OTHER.
         *
         * @param pkg   package name
         * @param label lower-case label
         * @param ai    application info, or null when unknown
         */
        @Suppress("DEPRECATION") // FLAG_IS_GAME: older games still set only the flag
        @JvmStatic
        fun categorize(pkg: String, label: String?, ai: ApplicationInfo?): Int {
            val p = pkg.lowercase(Locale.US)
            val l = label?.lowercase(Locale.getDefault()) ?: ""
            if (ai != null) {
                if ((ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0 || ai.category == ApplicationInfo.CATEGORY_GAME) return CAT_GAMES
            }
            if (any(
                    p, "dialer", "messag", ".mms", "contacts", ".email", "android.gm", "whatsapp", "telegram", "thoughtcrime",
                    "signal", "discord", "slack", "teams", "zoom", "skype", "facebook", "instagram", "twitter", "reddit",
                    "snapchat", "linkedin", "tencent.mm", "viber", "bluesky", "barcelona", "outlook", "duo", "meet", "voice",
                ) ||
                any(l, "phone", "messages", "mail", "chat", "contacts")
            ) {
                return CAT_COMM
            }
            if (any(p, "maps", "waze", "uber", "lyft", "transit", "navigation", "gps", "airbnb", "expedia", "tripadvisor")) return CAT_NAV
            if (ai != null) {
                when (ai.category) {
                    ApplicationInfo.CATEGORY_SOCIAL -> return CAT_COMM
                    ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> return CAT_MEDIA
                    ApplicationInfo.CATEGORY_MAPS -> return CAT_NAV
                    ApplicationInfo.CATEGORY_PRODUCTIVITY, ApplicationInfo.CATEGORY_NEWS -> return CAT_WORK
                    ApplicationInfo.CATEGORY_ACCESSIBILITY -> return CAT_SYSTEM
                }
            }
            if (any(
                    p, "camera", "gallery", "photos", "music", "spotify", "youtube", "netflix", "video", "plex", "hulu",
                    "disney", "pandora", "podcast", "audible", "twitch", "tv", "radio", "soundcloud", "pocketcasts",
                ) ||
                any(l, "camera", "gallery", "music", "video", "photo", "tv")
            ) {
                return CAT_MEDIA
            }
            if (any(
                    p, "calendar", "notes", "keep", "docs", "sheets", "slides", "office", "drive", "calculator", "clock",
                    "todo", "tasks", "notion", "evernote", "dropbox", "onedrive", "word", "excel", "powerpoint", "copilot",
                    "zendesk", "jira", "trello", "asana", "authenticator", "bank", "finance", "chatgpt", "claude", "gemini",
                ) ||
                any(l, "calendar", "notes", "calculator", "clock", "files", "docs")
            ) {
                return CAT_WORK
            }
            if (p.startsWith("com.android.") || p.startsWith("com.samsung.android.") || p.startsWith("com.sec.") ||
                any(p, "settings", "lool", "smartthings", "oneconnect", "wearable", "bixby", "knox", "store", "vending") ||
                (ai != null && (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
            ) {
                return CAT_SYSTEM
            }
            return CAT_OTHER
        }
    }
}
