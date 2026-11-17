package com.gungreeneyes.trekui.core;

import com.gungreeneyes.trekui.TrekPrefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The contract between TrekUI apps (launcher, Messages, Keyboard, Lock Screen): package names, provider
 * authorities, the shared permission, URI paths, column names and the parsing/encoding of rows.
 *
 * <p>Every TrekUI app registers a {@link SuiteProvider} at authority {@code <applicationId>.suite},
 * readable only by apps holding the signature-level permission {@link #PERMISSION} (declared in :core's
 * manifest, so every app declares and requests it; only apps signed with the same key are granted it).
 * The develop flavor adds {@code .dev} to every package and uses its own permission
 * ({@code com.gungreeneyes.trekui.dev.permission.SUITE}) so a debug-signed develop build installs next to
 * the Play build without a duplicate-permission conflict; develop builds only see develop siblings.
 *
 * <p>Paths (one row each):
 * <ul>
 *   <li>{@code unlock}: {@link #COL_VERSION}, {@link #COL_PACKAGE}, {@link #COL_UNLOCKED} (1/0),
 *       {@link #COL_SOURCE} (Entitlement.Source name).</li>
 *   <li>{@code theme}: {@link #COL_VERSION}, {@link #COL_PACKAGE}, {@link #COL_FACTION},
 *       {@link #COL_SUB_THEME}, {@link #COL_LETTERING}, {@link #COL_SOUND_SET} (may be empty). Every app
 *       answers with its own current choice; companions only read the launcher's. The sub-theme is
 *       clamped into the faction's list ({@code SubThemes}) when read.</li>
 *   <li>{@code lockscreen}: {@link #COL_VERSION}, {@link #COL_PACKAGE}, {@link #COL_ENABLED} (1/0): the
 *       launcher's SUITE › LOCK SCREEN switch; the wake-screen companion stays off while it reads 0.</li>
 * </ul>
 * All values travel as strings; {@link #parseUnlock} and {@link #parseTheme} validate them, so a
 * malformed or newer-format row reads as "no answer" instead of crashing the caller.
 */
public final class SuiteContract {
    private SuiteContract() {}

    /** Contract version written into every row; readers accept this and newer (additive changes only). */
    public static final int VERSION = 1;

    /** The one-time Play product every TrekUI app sells; one purchase unlocks all of them. */
    public static final String UNLOCK_PRODUCT_ID = "trekui_unlock";

    /** Base package of the suite (the launcher's application id). */
    public static final String BASE = "com.gungreeneyes.trekui";
    /** Application-id suffix of the develop flavor. */
    public static final String DEV_SUFFIX = ".dev";

    /** Production package names of the TrekUI apps. */
    public static final String LAUNCHER = BASE;
    public static final String MESSAGES = BASE + ".messages";
    public static final String KEYBOARD = BASE + ".keyboard";
    public static final String LOCKSCREEN = BASE + ".lockscreen";
    /** Every TrekUI app, launcher first. */
    public static final List<String> APPS = Collections.unmodifiableList(
            java.util.Arrays.asList(LAUNCHER, MESSAGES, KEYBOARD, LOCKSCREEN));

    /** The signature-level permission guarding every suite provider (production builds). */
    public static final String PERMISSION = BASE + ".permission.SUITE";
    /** Authority suffix after the application id. */
    public static final String AUTHORITY_SUFFIX = ".suite";

    public static final String PATH_UNLOCK = "unlock";
    public static final String PATH_THEME = "theme";
    /** The launcher's LOCK SCREEN switch for the wake-screen companion (additive, LCA-T35). */
    public static final String PATH_LOCKSCREEN = "lockscreen";

    public static final String COL_VERSION = "version";
    public static final String COL_PACKAGE = "package";
    public static final String COL_UNLOCKED = "unlocked";
    public static final String COL_SOURCE = "source";
    public static final String COL_FACTION = "faction";
    public static final String COL_SUB_THEME = "sub_theme";
    public static final String COL_LETTERING = "lettering";
    public static final String COL_SOUND_SET = "sound_set";
    public static final String COL_ENABLED = "enabled";

    public static final String[] UNLOCK_COLUMNS = {COL_VERSION, COL_PACKAGE, COL_UNLOCKED, COL_SOURCE};
    public static final String[] THEME_COLUMNS = {COL_VERSION, COL_PACKAGE, COL_FACTION, COL_SUB_THEME,
            COL_LETTERING, COL_SOUND_SET};
    public static final String[] LOCKSCREEN_COLUMNS = {COL_VERSION, COL_PACKAGE, COL_ENABLED};

    /** Sound set ids accepted in a theme row (Sfx.SET_LIBRARY, SET_SYNTH, SET_SILENT). */
    static final List<String> SOUND_SETS = Collections.unmodifiableList(
            java.util.Arrays.asList("library", "synth", "silent"));
    /** Highest Imperial lettering id (Skin.PLAIN). */
    static final int MAX_LETTERING = 2;

    // ---- packages and authorities --------------------------------------------------------------

    /** True for a develop-flavor package ({@code ….dev}). */
    public static boolean isDevPackage(String pkg) {
        return pkg != null && pkg.endsWith(DEV_SUFFIX);
    }

    /** The flavor suffix of {@code ownPackage}: ".dev" or "". */
    public static String variantSuffix(String ownPackage) {
        return isDevPackage(ownPackage) ? DEV_SUFFIX : "";
    }

    /** {@code pkg} without a develop suffix. */
    public static String basePackage(String pkg) {
        if (pkg == null) return null;
        return isDevPackage(pkg) ? pkg.substring(0, pkg.length() - DEV_SUFFIX.length()) : pkg;
    }

    /** True if {@code pkg} is one of the TrekUI apps (either flavor). */
    public static boolean isSuitePackage(String pkg) {
        return APPS.contains(basePackage(pkg));
    }

    /** The package of suite app {@code app} (a production name from {@link #APPS}) in the caller's flavor. */
    public static String packageFor(String app, String ownPackage) {
        return app + variantSuffix(ownPackage);
    }

    /** The launcher's package in the caller's flavor. */
    public static String launcherPackage(String ownPackage) {
        return packageFor(LAUNCHER, ownPackage);
    }

    /** True if {@code ownPackage} is the launcher (either flavor). */
    public static boolean isLauncher(String ownPackage) {
        return LAUNCHER.equals(basePackage(ownPackage));
    }

    /** Every other suite app's package in the caller's flavor (launcher first). */
    public static List<String> siblingPackages(String ownPackage) {
        List<String> out = new ArrayList<>();
        String base = basePackage(ownPackage);
        for (String app : APPS) if (!app.equals(base)) out.add(packageFor(app, ownPackage));
        return out;
    }

    /** Provider authority of the suite app {@code pkg}. */
    public static String authority(String pkg) {
        return pkg + AUTHORITY_SUFFIX;
    }

    /** {@code content://<pkg>.suite/<path>} as a string (parse with {@code Uri.parse}). */
    public static String uriString(String pkg, String path) {
        return "content://" + authority(pkg) + "/" + path;
    }

    /** The suite permission in the caller's flavor (develop builds use a separate one). */
    public static String permission(String ownPackage) {
        return BASE + variantSuffix(ownPackage) + ".permission.SUITE";
    }

    // ---- rows ------------------------------------------------------------------------------------

    /** The {@code unlock} row an app publishes. */
    public static Map<String, String> unlockRow(String pkg, boolean unlocked, String source) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(COL_VERSION, String.valueOf(VERSION));
        m.put(COL_PACKAGE, pkg);
        m.put(COL_UNLOCKED, unlocked ? "1" : "0");
        m.put(COL_SOURCE, source == null ? "" : source);
        return m;
    }

    /** The {@code theme} row an app publishes. */
    public static Map<String, String> themeRow(String pkg, SuiteTheme t) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(COL_VERSION, String.valueOf(VERSION));
        m.put(COL_PACKAGE, pkg);
        m.put(COL_FACTION, String.valueOf(t.faction));
        m.put(COL_SUB_THEME, String.valueOf(t.subTheme));
        m.put(COL_LETTERING, String.valueOf(t.lettering));
        m.put(COL_SOUND_SET, t.soundSet == null ? "" : t.soundSet);
        return m;
    }

    /** Values of {@code row} in the order of {@code columns} (for a MatrixCursor). */
    public static Object[] values(Map<String, String> row, String[] columns) {
        Object[] out = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) out[i] = row.get(columns[i]);
        return out;
    }

    /**
     * Reads an {@code unlock} row: TRUE / FALSE, or null when the row is missing, of an unsupported
     * version, or malformed.
     */
    public static Boolean parseUnlock(Map<String, String> row) {
        if (!versionOk(row)) return null;
        String v = row.get(COL_UNLOCKED);
        if ("1".equals(v)) return Boolean.TRUE;
        if ("0".equals(v)) return Boolean.FALSE;
        return null;
    }

    /**
     * Reads a {@code theme} row into a {@link SuiteTheme}, or null when the row is missing, of an
     * unsupported version, or out of range. Sub-themes are clamped into the faction's theme list; an
     * unknown sound set reads as "not chosen" (null).
     */
    public static SuiteTheme parseTheme(Map<String, String> row) {
        if (!versionOk(row)) return null;
        Integer faction = parseInt(row.get(COL_FACTION));
        Integer sub = parseInt(row.get(COL_SUB_THEME));
        Integer lettering = parseInt(row.get(COL_LETTERING));
        if (faction == null || faction < 0 || faction > TrekPrefs.MAX_FACTION) return null;
        int s = TrekPrefs.clampSubTheme(faction, sub == null ? 0 : sub);
        int l = lettering == null || lettering < 0 || lettering > MAX_LETTERING ? 0 : lettering;
        String set = row.get(COL_SOUND_SET);
        if (set != null && !SOUND_SETS.contains(set)) set = null;
        return new SuiteTheme(faction, s, l, set);
    }

    /** The {@code lockscreen} row the launcher publishes: is its LOCK SCREEN switch on? */
    public static Map<String, String> lockscreenRow(String pkg, boolean enabled) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(COL_VERSION, String.valueOf(VERSION));
        m.put(COL_PACKAGE, pkg);
        m.put(COL_ENABLED, enabled ? "1" : "0");
        return m;
    }

    /**
     * Reads a {@code lockscreen} row: TRUE / FALSE, or null when the row is missing, of an unsupported
     * version, or malformed (an older launcher without the path: the companion uses its own setting).
     */
    public static Boolean parseLockscreen(Map<String, String> row) {
        if (!versionOk(row)) return null;
        String v = row.get(COL_ENABLED);
        if ("1".equals(v)) return Boolean.TRUE;
        if ("0".equals(v)) return Boolean.FALSE;
        return null;
    }

    // ---- store links -----------------------------------------------------------------------------

    /** Play Store web link of suite app {@code pkg} (develop packages link to their production listing). */
    public static String playUrl(String pkg) {
        return "https://play.google.com/store/apps/details?id=" + basePackage(pkg);
    }

    /** {@code market://} link of suite app {@code pkg} (opens the Play Store app directly). */
    public static String marketUri(String pkg) {
        return "market://details?id=" + basePackage(pkg);
    }

    /**
     * The explicit action the launcher sends to TrekUI Messages ({@code setPackage(messages)}) on
     * Android 10+ to have it ask for the default SMS role itself (RoleManager only lets an app request
     * a role for itself). Messages handles it in an exported activity that calls
     * {@code RoleManager.createRequestRoleIntent(ROLE_SMS)} and finishes.
     */
    public static final String ACTION_REQUEST_SMS_ROLE = BASE + ".action.REQUEST_SMS_ROLE";

    private static boolean versionOk(Map<String, String> row) {
        if (row == null) return false;
        Integer v = parseInt(row.get(COL_VERSION));
        return v != null && v >= 1;
    }

    private static Integer parseInt(String s) {
        if (s == null) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
