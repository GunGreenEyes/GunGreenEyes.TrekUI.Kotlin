package com.gungreeneyes.trekui;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;

import java.util.Locale;

/**
 * One launchable activity in one user profile (main, work profile, Secure Folder), as listed by
 * {@link AppRepo}. Holds the label, a stable key for preferences, the console drawer category and
 * the icons (raw system icon plus the console-styled one made by {@link IconFactory}).
 *
 * <p>Categorising is a pure function of package name, label and the app's declared category
 * ({@link #categorize}), unit-tested in {@code AppInfoTest}.
 */
final class AppInfo {
    /** Drawer categories; indexes into {@link #CAT_NAMES}. */
    static final int CAT_ALL = 0, CAT_COMM = 1, CAT_MEDIA = 2, CAT_WORK = 3, CAT_NAV = 4, CAT_GAMES = 5,
            CAT_SYSTEM = 6, CAT_OTHER = 7;
    static final String[] CAT_NAMES = {"ALL", "COMM", "MEDIA", "WORK", "NAV", "GAMES", "SYSTEM", "OTHER"};

    final ComponentName cn;
    final UserHandle user;
    /** Serial number of the user profile (stable across reboots, unlike the user id). */
    final long serial;
    /** True for the person's own profile (not work profile / Secure Folder). */
    final boolean mainProfile;
    /** Display label, its lower-case form for searching, the preference key, and the package. */
    final String label, lower, key, pkg;
    /** One of the {@code CAT_*} constants (never {@link #CAT_ALL}). */
    final int category;
    /** The system's (badged) icon, and the icon drawn in the current interface's style. */
    Drawable rawIcon, icon;
    /** Glyph name chosen by {@link IconFactory#assignGlyphs} for interfaces that draw glyphs. */
    String glyph;
    /** The system icon before an icon pack replaced {@link #rawIcon} (see {@link IconPacks#apply}). */
    Drawable sysIcon;
    /** True when the user picked {@link #glyph} for this app (CHANGE ICON): drawn in every icon style. */
    boolean glyphPinned;

    AppInfo(ComponentName cn, UserHandle user, long serial, boolean mainProfile, String label, ApplicationInfo ai) {
        this.cn = cn;
        this.user = user;
        this.serial = serial;
        this.mainProfile = mainProfile;
        this.label = label == null || label.isEmpty() ? cn.getPackageName() : label;
        this.lower = this.label.toLowerCase(Locale.getDefault());
        this.pkg = cn.getPackageName();
        this.key = key(cn.flattenToShortString(), serial);
        this.category = categorize(pkg, this.lower, ai);
    }

    /** Preference key for a component in a profile: "pkg/.Activity#serial". */
    static String key(String flat, long serial) {
        return flat + "#" + serial;
    }

    private static boolean any(String s, String... parts) {
        for (String p : parts) if (s.contains(p)) return true;
        return false;
    }

    /**
     * Picks the drawer category: games first (declared), then well-known package/label words for
     * communications and navigation, then the app's declared category, then media / work words,
     * then system packages; everything else is OTHER.
     *
     * @param pkg   package name
     * @param label lower-case label
     * @param ai    application info, or null when unknown
     */
    @SuppressWarnings("deprecation") // FLAG_IS_GAME: older games still set only the flag
    static int categorize(String pkg, String label, ApplicationInfo ai) {
        String p = pkg.toLowerCase(Locale.US);
        String l = label == null ? "" : label.toLowerCase(Locale.getDefault());
        if (ai != null) {
            if ((ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0 || ai.category == ApplicationInfo.CATEGORY_GAME) return CAT_GAMES;
        }
        if (any(p, "dialer", "messag", ".mms", "contacts", ".email", "android.gm", "whatsapp", "telegram", "thoughtcrime",
                "signal", "discord", "slack", "teams", "zoom", "skype", "facebook", "instagram", "twitter", "reddit",
                "snapchat", "linkedin", "tencent.mm", "viber", "bluesky", "barcelona", "outlook", "duo", "meet", "voice")
                || any(l, "phone", "messages", "mail", "chat", "contacts")) return CAT_COMM;
        if (any(p, "maps", "waze", "uber", "lyft", "transit", "navigation", "gps", "airbnb", "expedia", "tripadvisor")) return CAT_NAV;
        if (ai != null) {
            switch (ai.category) {
                case ApplicationInfo.CATEGORY_SOCIAL: return CAT_COMM;
                case ApplicationInfo.CATEGORY_AUDIO:
                case ApplicationInfo.CATEGORY_VIDEO:
                case ApplicationInfo.CATEGORY_IMAGE: return CAT_MEDIA;
                case ApplicationInfo.CATEGORY_MAPS: return CAT_NAV;
                case ApplicationInfo.CATEGORY_PRODUCTIVITY:
                case ApplicationInfo.CATEGORY_NEWS: return CAT_WORK;
                case ApplicationInfo.CATEGORY_ACCESSIBILITY: return CAT_SYSTEM;
                default: break;
            }
        }
        if (any(p, "camera", "gallery", "photos", "music", "spotify", "youtube", "netflix", "video", "plex", "hulu",
                "disney", "pandora", "podcast", "audible", "twitch", "tv", "radio", "soundcloud", "pocketcasts")
                || any(l, "camera", "gallery", "music", "video", "photo", "tv")) return CAT_MEDIA;
        if (any(p, "calendar", "notes", "keep", "docs", "sheets", "slides", "office", "drive", "calculator", "clock",
                "todo", "tasks", "notion", "evernote", "dropbox", "onedrive", "word", "excel", "powerpoint", "copilot",
                "zendesk", "jira", "trello", "asana", "authenticator", "bank", "finance", "chatgpt", "claude", "gemini")
                || any(l, "calendar", "notes", "calculator", "clock", "files", "docs")) return CAT_WORK;
        if (p.startsWith("com.android.") || p.startsWith("com.samsung.android.") || p.startsWith("com.sec.")
                || any(p, "settings", "lool", "smartthings", "oneconnect", "wearable", "bixby", "knox", "store", "vending")
                || (ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0)) return CAT_SYSTEM;
        return CAT_OTHER;
    }
}
