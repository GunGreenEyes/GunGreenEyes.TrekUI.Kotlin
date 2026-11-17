package com.gungreeneyes.trekui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Telephony;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A home-screen shortcut (grid tile or dock slot): an app, an console function, or empty. Also the
 * customisation record for assignable home elements ("slots": elbows, blocks, titles, scene plaques),
 * stored together as JSON in the {@code slotsJson} pref. Tiles are (de)serialised with
 * {@link #toJson()} / {@link #fromJson}; lists are JSON arrays under a pref key.
 */
final class Tile {
    static final String A_APPS = "apps", A_SEARCH = "search", A_CONFIG = "config", A_ANDROID = "android",
            A_SHADE = "shade", A_PANEL = "samsung", A_CASCADE = "cascade", A_DIALER = "dialer", A_GOVEE = "govee", A_NEST = "nest", A_CLOAK = "cloak",
            A_RED_ALERT = "redalert";
    /** OPEN GROUP (arg = AppGroups group id) and LOCK SCREEN (LockScreen / LockService), LCA-T20. */
    static final String A_GROUP = "group", A_LOCK = "lock";
    /** A Home Assistant entity (LCA-T23); {@link #arg} is its entity_id, see {@link HomeAssistant#PANEL_SUFFIX}. */
    static final String A_HA = "ha";
    static final String[] ACTIONS = {A_APPS, A_SEARCH, A_CONFIG, A_ANDROID, A_SHADE, A_PANEL, A_CASCADE, A_DIALER, A_CLOAK, A_RED_ALERT, A_LOCK};
    static final String[] ACTION_LABELS = {"APP DRAWER", "SEARCH", "TREKUI CONFIG", "ANDROID SETTINGS", "TREKUI SHADE",
            "SAMSUNG PANEL", "DATA CASCADE", "TREKUI DIALER", "CLOAK (NIGHT MODE)", "RED ALERT", "LOCK SCREEN"};

    // ---- assignable console elements on the home screen ("slots") --------------------------------
    /** id, display name, has editable text. */
    static final String[][] SLOTS = {
        {"title", "TREKUI TITLE", "1"}, {"ship", "SHIP REGISTRY LINE", "1"},
        {"code0", "CODE BLOCK 1", "1"}, {"code1", "CODE BLOCK 2", "1"}, {"code2", "CODE BLOCK 3", "1"}, {"code3", "CODE BLOCK 4", "1"},
        {"clock", "CHRONOMETER", "0"}, {"date", "DATE", "0"},
        {"hElbowT", "HEADER TOP ELBOW", "0"}, {"hBlockT0", "HEADER TOP SEGMENT", "0"},
        {"hElbowB", "HEADER BOTTOM ELBOW", "0"}, {"hBlockB0", "HEADER BOTTOM SEGMENT 1", "0"}, {"hBlockB1", "HEADER BOTTOM SEGMENT 2", "0"},
        {"ops", "OPERATIONS TITLE", "1"}, {"foot", "FOOTER TEXT", "1"},
        {"mElbowT", "MAIN TOP ELBOW", "0"}, {"mBlockT0", "MAIN TOP SEGMENT", "0"},
        {"mElbowB", "MAIN BOTTOM ELBOW", "0"}, {"mBlockB0", "MAIN BOTTOM SEGMENT", "0"},
        {"nav0", "SIDEBAR: APPS", "1"}, {"nav1", "SIDEBAR: SEARCH", "1"}, {"nav2", "SIDEBAR: CONFIG", "1"}, {"nav3", "SIDEBAR: BLOCK 47", "1"},
        {"capL", "DOCK LEFT CAP", "0"}, {"capR", "DOCK RIGHT CAP", "0"},
    };

    /** Display name of a slot id (unknown ids are shown upper-cased). */
    static String slotName(String id) {
        for (String[] s : SLOTS) if (s[0].equals(id)) return s[1];
        return id.toUpperCase(java.util.Locale.US);
    }

    /** True when the slot's label text can be edited. */
    static boolean slotHasText(String id) {
        for (String[] s : SLOTS) if (s[0].equals(id)) return "1".equals(s[2]);
        return false;
    }

    private static JSONObject slots(Prefs p) {
        try { return new JSONObject(p.s("slotsJson", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    /** Customisation for an element, or null if it's untouched. */
    static Tile slot(Prefs p, String id) {
        JSONObject o = slots(p).optJSONObject(id);
        return o == null ? null : fromJson(o);
    }

    /** Stores (or with null, clears) the customisation of slot {@code id} and bumps the layout. */
    static void putSlot(Prefs p, String id, Tile t) {
        JSONObject all = slots(p);
        try {
            if (t == null) all.remove(id); else all.put(id, t.toJson());
        } catch (Exception ignored) {
        }
        p.put("slotsJson", all.toString());
        p.bump();
    }

    static int customisedSlots(Prefs p) { return slots(p).length(); }

    static void clearSlots(Prefs p) { p.put("slotsJson", "{}"); p.bump(); }

    String label = "";
    String comp;      // flattened ComponentName
    long user = -1;   // user serial; -1 = main profile
    String action;    // one of ACTIONS, or A_GOVEE
    String arg;       // action argument (Govee device ID)
    String sound;     // own sound: library clip ID, "none" = silent, null = the usual sounds
    int color = -1;   // palette slot, -1 = automatic

    /** Neither an app nor a function. */
    boolean isEmpty() { return comp == null && action == null; }

    /** Serialises the tile (optional fields are omitted when null). */
    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("label", label);
            if (comp != null) o.put("comp", comp);
            o.put("user", user);
            if (action != null) o.put("action", action);
            if (arg != null) o.put("arg", arg);
            if (sound != null) o.put("sound", sound);
            o.put("color", color);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** Reads a tile written by {@link #toJson()}; missing fields take their defaults. */
    static Tile fromJson(JSONObject o) {
        Tile t = new Tile();
        t.label = o.optString("label", "");
        t.comp = o.has("comp") ? o.optString("comp") : null;
        t.user = o.optLong("user", -1);
        t.action = o.has("action") ? o.optString("action") : null;
        t.arg = o.has("arg") ? o.optString("arg") : null;
        t.sound = o.has("sound") ? o.optString("sound") : null;
        t.color = o.optInt("color", -1);
        return t;
    }

    /** The tile list stored under {@code key}, or null when absent or corrupt (callers then use defaults). */
    static List<Tile> load(Prefs p, String key) {
        List<Tile> out = new ArrayList<>();
        String raw = p.s(key, null);
        if (raw == null) return null;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) out.add(fromJson(a.getJSONObject(i)));
        } catch (Exception e) {
            return null;
        }
        return out;
    }

    /** Stores a tile list under {@code key}. */
    static void save(Prefs p, String key, List<Tile> tiles) {
        JSONArray a = new JSONArray();
        for (Tile t : tiles) a.put(t.toJson());
        p.put(key, a.toString());
    }

    /** Display label of a function tile's action, or "". */
    static String actionLabel(String action) {
        if (A_GOVEE.equals(action)) return "LIGHTS";
        if (A_NEST.equals(action)) return "CLIMATE";
        if (A_GROUP.equals(action)) return "OPEN GROUP";
        if (A_HA.equals(action)) return "HOME";
        for (int i = 0; i < ACTIONS.length; i++) if (ACTIONS[i].equals(action)) return ACTION_LABELS[i];
        return "";
    }

    // ---- defaults -----------------------------------------------------------------------------

    /** Package of the default (or first) activity for {@code i}, ignoring the system chooser. */
    private static String resolve(Context c, Intent i) {
        try {
            ResolveInfo ri = c.getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)
                    && !ri.activityInfo.name.contains("ResolverActivity")) return ri.activityInfo.packageName;
            List<ResolveInfo> all = c.getPackageManager().queryIntentActivities(i, 0);
            if (!all.isEmpty()) return all.get(0).activityInfo.packageName;
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Intent category(String cat) {
        return Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, cat);
    }

    private static Tile app(Context c, AppRepo repo, String label, String... pkgsOrNull) {
        Tile t = new Tile();
        t.label = label;
        for (String pkg : pkgsOrNull) {
            AppInfo a = repo.findPackage(pkg);
            if (a != null) { t.comp = a.cn.flattenToShortString(); break; }
        }
        return t;
    }

    private static String sms(Context c) {
        try { return Telephony.Sms.getDefaultSmsPackage(c); } catch (Exception e) { return null; }
    }

    /** First-run home tiles: common apps resolved on this device, plus CONFIG. */
    static List<Tile> defaultTiles(Context c, AppRepo r) {
        List<Tile> l = new ArrayList<>();
        l.add(app(c, r, "NAVIGATION", resolve(c, new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))), "com.google.android.apps.maps"));
        l.add(app(c, r, "MEDIA", resolve(c, category(Intent.CATEGORY_APP_MUSIC)), "com.google.android.youtube", "com.spotify.music"));
        l.add(app(c, r, "GALLERY", resolve(c, category(Intent.CATEGORY_APP_GALLERY)), "com.sec.android.gallery3d", "com.google.android.apps.photos"));
        l.add(app(c, r, "CALENDAR", resolve(c, category(Intent.CATEGORY_APP_CALENDAR)), "com.samsung.android.calendar", "com.google.android.calendar"));
        l.add(app(c, r, "MAIL", resolve(c, category(Intent.CATEGORY_APP_EMAIL)), "com.google.android.gm", "com.samsung.android.email.provider"));
        l.add(app(c, r, "SETTINGS", "com.android.settings"));
        l.add(app(c, r, "CHRONO", resolve(c, new Intent(AlarmClock.ACTION_SHOW_ALARMS)), "com.sec.android.app.clockpackage", "com.google.android.deskclock"));
        l.add(app(c, r, "CALCULATOR", resolve(c, category(Intent.CATEGORY_APP_CALCULATOR)), "com.sec.android.app.popupcalculator", "com.google.android.calculator"));
        l.add(app(c, r, "CONTACTS", resolve(c, category(Intent.CATEGORY_APP_CONTACTS)), "com.samsung.android.app.contacts", "com.google.android.contacts"));
        l.add(app(c, r, "STORE", "com.android.vending", "com.sec.android.app.samsungapps"));
        l.add(app(c, r, "FILES", resolve(c, category(Intent.CATEGORY_APP_FILES)), "com.sec.android.app.myfiles", "com.google.android.apps.nbu.files"));
        Tile cfg = new Tile();
        cfg.label = "CONFIG";
        cfg.action = A_CONFIG;
        l.add(cfg);
        for (Tile t : l) if (t.isEmpty()) t.label = "";
        return l;
    }

    /** First-run dock: phone, messages, browser, camera, mail. */
    static List<Tile> defaultDock(Context c, AppRepo r) {
        List<Tile> l = new ArrayList<>();
        l.add(app(c, r, "PHONE", resolve(c, new Intent(Intent.ACTION_DIAL)), "com.samsung.android.dialer", "com.google.android.dialer"));
        l.add(app(c, r, "MESSAGES", sms(c), "com.samsung.android.messaging", "com.google.android.apps.messaging"));
        l.add(app(c, r, "BROWSER", resolve(c, new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)),
                "com.android.chrome", "com.sec.android.app.sbrowser"));
        l.add(app(c, r, "CAMERA", resolve(c, new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)), "com.sec.android.app.camera"));
        l.add(app(c, r, "MAIL", resolve(c, category(Intent.CATEGORY_APP_EMAIL)), "com.google.android.gm"));
        return l;
    }
}
