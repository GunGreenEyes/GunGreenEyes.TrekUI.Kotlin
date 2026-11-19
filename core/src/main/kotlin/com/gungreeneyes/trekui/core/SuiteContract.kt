package com.gungreeneyes.trekui.core

import com.gungreeneyes.trekui.TrekPrefs

/**
 * The contract between TrekUI apps (launcher, Messages, Keyboard, Lock Screen): package names, provider
 * authorities, the shared permission, URI paths, column names and the parsing/encoding of rows.
 *
 * Every TrekUI app registers a [SuiteProvider] at authority `<applicationId>.suite`,
 * readable only by apps holding the signature-level permission [PERMISSION] (declared in :core's
 * manifest, so every app declares and requests it; only apps signed with the same key are granted it).
 * The develop flavor adds `.dev` to every package and uses its own permission
 * (`com.gungreeneyes.trekui.dev.permission.SUITE`) so a debug-signed develop build installs next to
 * the Play build without a duplicate-permission conflict; develop builds only see develop siblings.
 *
 * Paths (one row each):
 * - `unlock`: [COL_VERSION], [COL_PACKAGE], [COL_UNLOCKED] (1/0), [COL_SOURCE] (Entitlement.Source name).
 * - `theme`: [COL_VERSION], [COL_PACKAGE], [COL_FACTION], [COL_SUB_THEME], [COL_LETTERING],
 *   [COL_SOUND_SET] (may be empty). Every app answers with its own current choice; companions only read
 *   the launcher's. The sub-theme is clamped into the faction's list (`SubThemes`) when read.
 * - `lockscreen`: [COL_VERSION], [COL_PACKAGE], [COL_ENABLED] (1/0): the launcher's SUITE › LOCK SCREEN
 *   switch; the wake-screen companion stays off while it reads 0.
 *
 * All values travel as strings; [parseUnlock] and [parseTheme] validate them, so a
 * malformed or newer-format row reads as "no answer" instead of crashing the caller.
 *
 * Constants are `const val`s (Java `switch` cases and annotations keep working); the column arrays and
 * the app list are [JvmField]s; every function is [JvmStatic] so Java keeps calling `SuiteContract.x(...)`.
 */
object SuiteContract {
    /** Contract version written into every row; readers accept this and newer (additive changes only). */
    const val VERSION = 1

    /** The one-time Play product every TrekUI app sells; one purchase unlocks all of them. */
    const val UNLOCK_PRODUCT_ID = "trekui_unlock"

    /** Base package of the suite (the launcher's application id). */
    const val BASE = "com.gungreeneyes.trekui"

    /** Application-id suffix of the develop flavor. */
    const val DEV_SUFFIX = ".dev"

    /** Production package names of the TrekUI apps. */
    const val LAUNCHER = BASE
    const val MESSAGES = "$BASE.messages"
    const val KEYBOARD = "$BASE.keyboard"
    const val LOCKSCREEN = "$BASE.lockscreen"

    /** Every TrekUI app, launcher first. */
    @JvmField
    val APPS: List<String> = listOf(LAUNCHER, MESSAGES, KEYBOARD, LOCKSCREEN)

    /** The signature-level permission guarding every suite provider (production builds). */
    const val PERMISSION = "$BASE.permission.SUITE"

    /** Authority suffix after the application id. */
    const val AUTHORITY_SUFFIX = ".suite"

    const val PATH_UNLOCK = "unlock"
    const val PATH_THEME = "theme"

    /** The launcher's LOCK SCREEN switch for the wake-screen companion (additive, LCA-T35). */
    const val PATH_LOCKSCREEN = "lockscreen"

    const val COL_VERSION = "version"
    const val COL_PACKAGE = "package"
    const val COL_UNLOCKED = "unlocked"
    const val COL_SOURCE = "source"
    const val COL_FACTION = "faction"
    const val COL_SUB_THEME = "sub_theme"
    const val COL_LETTERING = "lettering"
    const val COL_SOUND_SET = "sound_set"
    const val COL_ENABLED = "enabled"

    @JvmField
    val UNLOCK_COLUMNS: Array<String> = arrayOf(COL_VERSION, COL_PACKAGE, COL_UNLOCKED, COL_SOURCE)

    @JvmField
    val THEME_COLUMNS: Array<String> =
        arrayOf(COL_VERSION, COL_PACKAGE, COL_FACTION, COL_SUB_THEME, COL_LETTERING, COL_SOUND_SET)

    @JvmField
    val LOCKSCREEN_COLUMNS: Array<String> = arrayOf(COL_VERSION, COL_PACKAGE, COL_ENABLED)

    /**
     * The explicit action the launcher sends to TrekUI Messages (`setPackage(messages)`) on
     * Android 10+ to have it ask for the default SMS role itself (RoleManager only lets an app request
     * a role for itself). Messages handles it in an exported activity that calls
     * `RoleManager.createRequestRoleIntent(ROLE_SMS)` and finishes.
     */
    const val ACTION_REQUEST_SMS_ROLE = "$BASE.action.REQUEST_SMS_ROLE"

    /** Sound set ids accepted in a theme row (Sfx.SET_LIBRARY, SET_SYNTH, SET_SILENT). */
    internal val SOUND_SETS: List<String> = listOf("library", "synth", "silent")

    /** Highest Imperial lettering id (Skin.PLAIN). */
    internal const val MAX_LETTERING = 2

    // ---- packages and authorities --------------------------------------------------------------

    /** True for a develop-flavor package (`….dev`). */
    @JvmStatic
    fun isDevPackage(pkg: String?): Boolean = pkg != null && pkg.endsWith(DEV_SUFFIX)

    /** The flavor suffix of `ownPackage`: ".dev" or "". */
    @JvmStatic
    fun variantSuffix(ownPackage: String?): String = if (isDevPackage(ownPackage)) DEV_SUFFIX else ""

    /** `pkg` without a develop suffix. */
    @JvmStatic
    fun basePackage(pkg: String?): String? = when {
        pkg == null -> null
        isDevPackage(pkg) -> pkg.substring(0, pkg.length - DEV_SUFFIX.length)
        else -> pkg
    }

    /** True if `pkg` is one of the TrekUI apps (either flavor). */
    @JvmStatic
    fun isSuitePackage(pkg: String?): Boolean = basePackage(pkg)?.let { it in APPS } ?: false

    /** The package of suite app `app` (a production name from [APPS]) in the caller's flavor. */
    @JvmStatic
    fun packageFor(app: String?, ownPackage: String?): String = app + variantSuffix(ownPackage)

    /** The launcher's package in the caller's flavor. */
    @JvmStatic
    fun launcherPackage(ownPackage: String?): String = packageFor(LAUNCHER, ownPackage)

    /** True if `ownPackage` is the launcher (either flavor). */
    @JvmStatic
    fun isLauncher(ownPackage: String?): Boolean = LAUNCHER == basePackage(ownPackage)

    /** Every other suite app's package in the caller's flavor (launcher first). */
    @JvmStatic
    fun siblingPackages(ownPackage: String?): List<String> {
        val base = basePackage(ownPackage)
        return APPS.filter { it != base }.map { packageFor(it, ownPackage) }
    }

    /** Provider authority of the suite app `pkg`. */
    @JvmStatic
    fun authority(pkg: String?): String = pkg + AUTHORITY_SUFFIX

    /** `content://<pkg>.suite/<path>` as a string (parse with `Uri.parse`). */
    @JvmStatic
    fun uriString(pkg: String?, path: String?): String = "content://${authority(pkg)}/$path"

    /** The suite permission in the caller's flavor (develop builds use a separate one). */
    @JvmStatic
    fun permission(ownPackage: String?): String = "$BASE${variantSuffix(ownPackage)}.permission.SUITE"

    // ---- rows ------------------------------------------------------------------------------------

    /** The `unlock` row an app publishes (mutable, insertion-ordered). */
    @JvmStatic
    fun unlockRow(pkg: String?, unlocked: Boolean, source: String?): MutableMap<String, String?> =
        linkedMapOf(
            COL_VERSION to VERSION.toString(),
            COL_PACKAGE to pkg,
            COL_UNLOCKED to if (unlocked) "1" else "0",
            COL_SOURCE to (source ?: ""),
        )

    /** The `theme` row an app publishes (mutable, insertion-ordered). */
    @JvmStatic
    fun themeRow(pkg: String?, t: SuiteTheme): MutableMap<String, String?> =
        linkedMapOf(
            COL_VERSION to VERSION.toString(),
            COL_PACKAGE to pkg,
            COL_FACTION to t.faction.toString(),
            COL_SUB_THEME to t.subTheme.toString(),
            COL_LETTERING to t.lettering.toString(),
            COL_SOUND_SET to (t.soundSet ?: ""),
        )

    /** Values of `row` in the order of `columns` (for a MatrixCursor). */
    @JvmStatic
    fun values(row: Map<String, String?>, columns: Array<String>): Array<Any?> =
        Array(columns.size) { row[columns[it]] }

    /**
     * Reads an `unlock` row: TRUE / FALSE, or null when the row is missing, of an unsupported
     * version, or malformed.
     */
    @JvmStatic
    fun parseUnlock(row: Map<String, String?>?): Boolean? =
        if (row == null || !versionOk(row)) null else parseFlag(row[COL_UNLOCKED])

    /**
     * Reads an `unlock` row as "this sibling owns its own purchase": TRUE / FALSE, or null when there is
     * no valid answer. An unlock that only comes from a suite grant (source [SOURCE_SUITE], published by
     * older versions) counts as FALSE, so grants never vouch for each other.
     */
    @JvmStatic
    fun parseOwnPurchase(row: Map<String, String?>?): Boolean? {
        val unlocked = parseUnlock(row) ?: return null
        return unlocked && row?.get(COL_SOURCE) != SOURCE_SUITE
    }

    /** The `source` value of an unlock that only comes from a sibling's grant (Entitlement.Source.SUITE). */
    const val SOURCE_SUITE = "SUITE"

    /**
     * Reads a `theme` row into a [SuiteTheme], or null when the row is missing, of an
     * unsupported version, or out of range. A retired interface (ids 3-8) reads as Command at sub-theme
     * 0. Sub-themes are clamped into the faction's theme list; an
     * unknown sound set reads as "not chosen" (null).
     */
    @JvmStatic
    fun parseTheme(row: Map<String, String?>?): SuiteTheme? {
        if (row == null || !versionOk(row)) return null
        var faction = parseInt(row[COL_FACTION])
        var sub = parseInt(row[COL_SUB_THEME])
        val lettering = parseInt(row[COL_LETTERING])
        if (faction == null || faction < 0 || faction > TrekPrefs.MAX_FACTION) return null
        // a launcher still on a retired interface (ids 3-8) is followed as Command at its first sub-theme
        if (TrekPrefs.retiredFaction(faction)) {
            faction = TrekPrefs.clampFaction(faction)
            sub = 0
        }
        val s = TrekPrefs.clampSubTheme(faction, sub ?: 0)
        val l = if (lettering == null || lettering < 0 || lettering > MAX_LETTERING) 0 else lettering
        val set = row[COL_SOUND_SET]?.takeIf { it in SOUND_SETS }
        return SuiteTheme(faction, s, l, set)
    }

    /** The `lockscreen` row the launcher publishes: is its LOCK SCREEN switch on? (mutable, insertion-ordered) */
    @JvmStatic
    fun lockscreenRow(pkg: String?, enabled: Boolean): MutableMap<String, String?> =
        linkedMapOf(
            COL_VERSION to VERSION.toString(),
            COL_PACKAGE to pkg,
            COL_ENABLED to if (enabled) "1" else "0",
        )

    /**
     * Reads a `lockscreen` row: TRUE / FALSE, or null when the row is missing, of an unsupported
     * version, or malformed (an older launcher without the path: the companion uses its own setting).
     */
    @JvmStatic
    fun parseLockscreen(row: Map<String, String?>?): Boolean? =
        if (row == null || !versionOk(row)) null else parseFlag(row[COL_ENABLED])

    // ---- store links -----------------------------------------------------------------------------

    /** Play Store web link of suite app `pkg` (develop packages link to their production listing). */
    @JvmStatic
    fun playUrl(pkg: String?): String = "https://play.google.com/store/apps/details?id=${basePackage(pkg)}"

    /** `market://` link of suite app `pkg` (opens the Play Store app directly). */
    @JvmStatic
    fun marketUri(pkg: String?): String = "market://details?id=${basePackage(pkg)}"

    private fun versionOk(row: Map<String, String?>): Boolean {
        val v = parseInt(row[COL_VERSION])
        return v != null && v >= 1
    }

    /** "1" → true, "0" → false, anything else → null. */
    private fun parseFlag(v: String?): Boolean? = when (v) {
        "1" -> true
        "0" -> false
        else -> null
    }

    /** `Integer.parseInt` after Java's `String.trim()` (strips chars <= ' '), null when not a number. */
    private fun parseInt(s: String?): Int? {
        if (s == null) return null
        return try {
            Integer.parseInt(s.trim { it <= ' ' })
        } catch (e: NumberFormatException) {
            null
        }
    }
}
