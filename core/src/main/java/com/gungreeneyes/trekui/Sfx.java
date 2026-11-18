package com.gungreeneyes.trekui;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The launcher's interface sounds. Eight events (button press, navigation, app launch, confirmation,
 * error, communications, diagnostics, red alert) each play an imported file, a library clip chosen
 * per interface, or nothing. Short clips go through one {@link SoundPool}; long ones (over ~5 s)
 * through a {@link MediaPlayer}, since SoundPool may cut long samples short.
 *
 * <p>Library clips are looked up by id, in this order (see {@link SoundCache}): the clip the user
 * downloaded via DOWNLOAD SOUND LIBRARY, then a res/raw resource of that name (personal develop
 * builds only), then the launcher's own synthesized clips (assets, every build), then silence.
 * The production build ships no recordings: out of the box every event plays its synthesized clip
 * ({@link #SYNTH_EVENT}); the SOUND SET choice ({@link #soundSet}) picks library / synthesized /
 * silent. Nothing here may crash when a clip is missing.
 *
 * <p>Callers use the legacy action codes ({@link #TAP}, {@link #OPEN}, …) via {@link #play}; each maps
 * to one of the eight events through {@link #EVENT_OF}.
 */
final class Sfx {
    // ---- legacy action codes (index into EVENT_OF) ------------------------------------------
    static final int TAP = 0;
    static final int PAGE = 1;
    static final int CONFIRM = 2;
    static final int DENY = 3;
    static final int OPEN = 4;
    static final int CLOSE = 5;
    static final int STARTUP = 6;
    static final int COMM = 7;
    static final int ALERT = 8;
    static final int POWER = 9;
    static final int WARNING = 10;
    static final int ERROR = 11;
    static final int LAUNCH = 12;
    static final int SEARCH = 13;
    static final int RESULT = 14;
    static final int LONG_PRESS = 15;
    static final int TOGGLE_ON = 16;
    static final int TOGGLE_OFF = 17;
    static final int CANCEL = 18;
    static final int SCAN_START = 19;
    static final int SCAN_DONE = 20;
    static final int STATUS_OK = 21;
    static final int SCANNER = 22;
    static final int CRITICAL = 23;
    static final int SYS_WARNING = 24;
    static final int CHANNEL_CLOSE = 25;
    static final int TAP_SOFT = 26;
    static final int HAIL = 27;

    // ---- the eight events --------------------------------------------------------------------
    static final int EVENTS = 8;
    static final int E_BUTTON = 0;
    static final int E_NAV = 1;
    static final int E_LAUNCH = 2;
    static final int E_CONFIRM = 3;
    static final int E_ERROR = 4;
    static final int E_COMMS = 5;
    static final int E_DIAG = 6;
    static final int E_RED = 7;
    /** Up to this many imported files rotate for one event. */
    static final int MAX_VARIANTS = 8;

    static final String[] EVENT_KEYS = {"button", "navigation", "launch", "confirm", "error", "comms", "diagnostics", "red_alert"};
    static final String[] EVENT_NAMES = {"BUTTON PRESS", "NAVIGATION", "APP LAUNCH", "INPUT CONFIRMATION", "ERROR", "COMMUNICATIONS", "SYSTEM DIAGNOSTICS", "RED ALERT"};
    static final String[] EVENT_DESC = {"TrekUI buttons, tiles, toggles, long-press", "Drawer open/close, search, category switch, back", "Launching an app", "Saved edits, permission granted, scan complete, default home set", "Failed action, unavailable function, permission denied", "Phone, messaging, chat and meeting apps (not email), the TrekUI dialer, appointment reminders", "Startup, weather scan, header scanner, charger, low battery", "Critical only: battery at 5%, storage below 5%"};

    /** The button-press rotation: the chirp plus six single beeps cut from computer sequences. */
    static final String[] TAP_SET_CLIPS = {"ui_button_tap", "tap_rot_1", "tap_rot_2", "tap_rot_3", "tap_rot_4", "tap_rot_5", "tap_rot_6"};
    /** Library id meaning "the tap rotation". */
    static final String TAP_SET = "#taps";
    private static final String[] BASE_LIBRARY = {TAP_SET, "ui_button_tap", "ui_panel_open", "ui_screen_open", "ui_screen_close", "alert_general_short", "ui_denied", "comms_hail", "comms_hailing_open", "comms_incoming_transmission", "alert_warning", "alert_red_tng", "tng_transporter", "system_transfer_complete", "voice_accessing_library", "voice_affirmative", "voice_unable_to_comply", "voice_command_codes_verified", "voice_command_functions_offline", "voice_autodefense", "voice_decoupling_sequence", "voice_priority_one_message", "alt_borg_computer", "alt_forcefield_touch", "alt_klingon_transporter"};
    private static final String[] BASE_LABELS = {"TAP ROTATION (7 SOUNDS)", "CLASSIC CHIRP", "PANEL DATA BURST", "VIEWSCREEN-ON SEQUENCE", "VIEWSCREEN-OFF SEQUENCE", "SHORT ALERT TONE", "DENY BEEP", "HAIL BEEP", "HAILING FREQUENCIES OPEN", "INCOMING TRANSMISSION", "WARNING ALARM", "RED ALERT", "TRANSPORTER", "TRANSFER COMPLETE", "VOICE: ACCESSING LIBRARY COMPUTER DATA", "VOICE: AFFIRMATIVE", "VOICE: UNABLE TO COMPLY", "VOICE: COMMAND CODES VERIFIED", "VOICE: COMMAND FUNCTIONS OFFLINE", "VOICE: AUTO-DEFENSE", "VOICE: DECOUPLING SEQUENCE", "VOICE: PRIORITY ONE MESSAGE", "COLLECTIVE COMPUTER", "FORCEFIELD TOUCH", "IMPERIAL TRANSPORTER (LONG)"};
    private static final String[] BASE_ORIGINAL = {"tng_chirp_clean + 6 beeps cut from scrshow / tng_viewscreen_on / tng_viewscreen_off", "tng_chirp_clean.mp3", "scrshow.mp3", "tng_viewscreen_on.mp3", "tng_viewscreen_off.mp3", "alarm03.mp3", "denybeep3.mp3", "hailbeep_clean.mp3", "hailingfrequencies_open3.mp3 (trekcore)", "incomingtransmission_clean.mp3", "alert04.mp3", "tng_red_alert3.mp3", "tng_transporter1.mp3", "transfercomplete_clean.mp3", "accessinglibrarycomputerdata_clean.mp3", "affirmative1_ep.mp3", "unabletocomply1_ep.mp3", "commandcodesverified_ep.mp3", "commandfunctionsoffline_ep.mp3", "autodefense_ep.mp3", "initiatingdecouplingsequence_ep.mp3", "priorityonemessagefromstarfleet_ep.mp3", "borg_computer_beep.mp3", "forcefield_touch.mp3", "imperial transporter (faction bundle)"};
    // ---- the launcher's own synthesized set (assets/sounds/synth, in every build) --------------
    /** Six synthesized console beeps that rotate for button presses. */
    static final String[] SYNTH_TAP_CLIPS = {"synth_tap_1", "synth_tap_2", "synth_tap_3", "synth_tap_4", "synth_tap_5", "synth_tap_6"};
    /** Library id meaning "the synthesized tap rotation". */
    static final String SYNTH_TAPS = "#synthtaps";
    /** Synthesized clip per event (button press = the tap rotation; red alert is a seamless 1 s klaxon loop). */
    static final String[] SYNTH_EVENT = {SYNTH_TAPS, "synth_nav", "synth_launch", "synth_confirm", "synth_error", "synth_comms", "synth_diag", "synth_red_alert"};
    /** Synthesized start-up sweep (BootSequence falls back to it). */
    static final String SYNTH_BOOT = "synth_boot";

    // Interface-specific synthesized families (same event layout, different character):
    // Imperial = harsh / metallic / low, Collective = cold digital / drone. Four tap beeps each.
    /** Synthesized tap rotations of the Imperial and Collective families. */
    static final String SYNTH_KL_TAPS = "#synthtaps_kl", SYNTH_BG_TAPS = "#synthtaps_bg";
    static final String[] SYNTH_KL_EVENT = family("kl", SYNTH_KL_TAPS);
    static final String[] SYNTH_BG_EVENT = family("bg", SYNTH_BG_TAPS);
    /** Prefix of the synthesized family of the retired interfaces (ids 3-8); its clips are no longer shipped. */
    private static final String RETIRED_FAMILY = "pp";
    private static final int FAMILY_TAPS = 4;

    /** {taps, synth_<fam>_nav, …, synth_<fam>_red_alert} in event order. */
    private static String[] family(String fam, String taps) {
        String[] parts = {"nav", "launch", "confirm", "error", "comms", "diag", "red_alert"};
        String[] out = new String[EVENTS];
        out[0] = taps;
        for (int i = 0; i < parts.length; i++) out[i + 1] = "synth_" + fam + "_" + parts[i];
        return out;
    }

    private static final String[] SYNTH_LIBRARY;
    private static final String[] SYNTH_LABELS;
    private static final String[] SYNTH_ORIGINAL;

    static {
        java.util.List<String> ids = new java.util.ArrayList<>(), labels = new java.util.ArrayList<>();
        String[] commandIds = {SYNTH_TAPS, "synth_tap_1", "synth_tap_2", "synth_tap_3", "synth_tap_4", "synth_tap_5", "synth_tap_6", "synth_nav", "synth_launch", "synth_confirm", "synth_error", "synth_comms", "synth_diag", "synth_red_alert", "synth_boot"};
        String[] commandLabels = {"SYNTHESIZED: TAP ROTATION (6 SOUNDS)", "SYNTHESIZED: TAP 1", "SYNTHESIZED: TAP 2", "SYNTHESIZED: TAP 3", "SYNTHESIZED: TAP 4", "SYNTHESIZED: TAP 5", "SYNTHESIZED: TAP 6", "SYNTHESIZED: NAVIGATION CHIRP", "SYNTHESIZED: LAUNCH ARPEGGIO", "SYNTHESIZED: CONFIRM CHIME", "SYNTHESIZED: ERROR BUZZ", "SYNTHESIZED: COMMS HAIL", "SYNTHESIZED: DIAGNOSTIC BLIPS", "SYNTHESIZED: RED ALERT KLAXON", "SYNTHESIZED: POWER-UP SWEEP"};
        ids.addAll(java.util.Arrays.asList(commandIds));
        labels.addAll(java.util.Arrays.asList(commandLabels));
        String[][] fams = {{"kl", "SYNTHESIZED IMPERIAL", SYNTH_KL_TAPS}, {"bg", "SYNTHESIZED COLLECTIVE", SYNTH_BG_TAPS}};
        String[] parts = {"nav", "launch", "confirm", "error", "comms", "diag", "red_alert", "boot"};
        String[] partNames = {"NAVIGATION", "LAUNCH", "CONFIRM", "ERROR", "COMMS", "DIAGNOSTICS", "RED ALERT LOOP", "START-UP"};
        for (String[] f : fams) {
            ids.add(f[2]);
            labels.add(f[1] + ": TAP ROTATION (" + FAMILY_TAPS + " SOUNDS)");
            for (int i = 1; i <= FAMILY_TAPS; i++) {
                ids.add("synth_" + f[0] + "_tap_" + i);
                labels.add(f[1] + ": TAP " + i);
            }
            for (int i = 0; i < parts.length; i++) {
                ids.add("synth_" + f[0] + "_" + parts[i]);
                labels.add(f[1] + ": " + partNames[i]);
            }
        }
        SYNTH_LIBRARY = ids.toArray(new String[0]);
        SYNTH_LABELS = labels.toArray(new String[0]);
        SYNTH_ORIGINAL = new String[SYNTH_LIBRARY.length];
        java.util.Arrays.fill(SYNTH_ORIGINAL, "original synthesis (tools/sounds/synth/make_synth.py)");
    }

    /** SOUND SET (CONFIG › AUDIO): the downloaded / bundled library, with synthesized clips wherever it is missing (default). */
    static final String SET_LIBRARY = "library";
    /** SOUND SET: only the synthesized clips. */
    static final String SET_SYNTH = "synth";
    /** SOUND SET: no built-in sounds (imported files still play). */
    static final String SET_SILENT = "silent";
    static final String[] SET_KEYS = {SET_LIBRARY, SET_SYNTH, SET_SILENT};
    // Display names and descriptions of the sets: R.array.sound_set_names / sound_set_descriptions (same order as SET_KEYS).

    /** Every library id (index 0 is the tap rotation), with its label and the original file it came from. */
    static final String[] LIBRARY = join(join(BASE_LIBRARY, SourceSoundLibrary.NAMES), SYNTH_LIBRARY);
    static final String[] LIBRARY_LABELS = join(join(BASE_LABELS, SourceSoundLibrary.LABELS), SYNTH_LABELS);
    static final String[] LIBRARY_ORIGINAL = join(join(BASE_ORIGINAL, SourceSoundLibrary.ORIGINALS), SYNTH_ORIGINAL);
    /** Command defaults per event. */
    static final String[] DEFAULT_RES = {TAP_SET, "ui_panel_open", "ui_screen_open", "alert_general_short", "ui_denied", "comms_hailing_open", "ui_screen_close", "alert_red_tng"};
    /** Imperial defaults: a few library clips where the 5.x bundle chose them, the harsh synthesized set elsewhere. */
    static final String[] IMPERIAL_DEFAULT = {SYNTH_KL_TAPS, "ui_screen_open", SYNTH_KL_EVENT[E_LAUNCH], SYNTH_KL_EVENT[E_CONFIRM], "ui_denied", SYNTH_KL_EVENT[E_COMMS], "ui_screen_close", "alert_warning"};
    /** Collective defaults: the Collective computer and a few library clips, the cold synthesized set elsewhere. */
    static final String[] COLLECTIVE_DEFAULT = {SYNTH_BG_TAPS, SYNTH_BG_EVENT[E_NAV], SYNTH_BG_EVENT[E_LAUNCH], "alt_borg_computer", "ui_denied", SYNTH_BG_EVENT[E_COMMS], "ui_screen_close", SYNTH_BG_EVENT[E_RED]};
    /**
     * Defaults per interface id (Skin.faction): Command, Imperial, Collective, the retired ids 3-8
     * (Command sounds; such a choice reads as Command anyway), then the 23rd/25th-century eras
     * (Command sounds).
     */
    static final String[][] FACTION_DEFAULT = {DEFAULT_RES, IMPERIAL_DEFAULT, COLLECTIVE_DEFAULT, DEFAULT_RES, DEFAULT_RES, DEFAULT_RES, DEFAULT_RES, DEFAULT_RES, DEFAULT_RES, DEFAULT_RES, DEFAULT_RES};

    /** The synthesized family of an interface: Imperial, Collective, else the console set. */
    static String[] synthEventFor(int faction) {
        if (faction == Skin.IMPERIAL) return SYNTH_KL_EVENT;
        if (faction == Skin.COLLECTIVE) return SYNTH_BG_EVENT;
        return SYNTH_EVENT;
    }

    /**
     * True for a stored per-event choice from the retired interfaces' synthesized family (its tap
     * rotation or one of its clips): those files are gone, so the choice reads as the interface default.
     */
    static boolean retiredClip(String id) {
        return id != null && (id.equals(SYNTH_TAPS + "_" + RETIRED_FAMILY) || id.startsWith("synth_" + RETIRED_FAMILY + "_"));
    }

    /** The synthesized start-up sound of an interface (BootSequence's fallback). */
    static String synthBootFor(int faction) {
        String red = synthEventFor(faction)[E_RED];
        return red.substring(0, red.length() - "red_alert".length()) + "boot";
    }
    /** Legacy action code → event. */
    private static final int[] EVENT_OF = {0, 1, 3, 4, 1, 1, 6, 5, 5, 6, 6, 4, 2, 1, 2, 0, 0, 0, 1, 6, 3, 3, 6, 7, 7, 6, 0, 5};

    /** Clip files above this size play through MediaPlayer (about 5 s of 44.1 kHz mono 16-bit). */
    private static final long LONG_FILE_BYTES = 480_000;
    /** After an explicit clip, ordinary events stay quiet this long so they don't talk over it. */
    private static final long QUIET_AFTER_CLIP_MS = 600;
    /** A clip still loading plays when ready only if the request is this recent. */
    private static final long PENDING_PLAY_WINDOW_MS = 2500;
    /** The same event retriggered faster than this is ignored (double taps). */
    private static final long SAME_EVENT_GAP_MS = 80;
    /** A new event this soon after a button sound cuts the button sound off. */
    private static final long BUTTON_CUTOFF_MS = 150;

    private static SoundPool pool;
    private static MediaPlayer longPlayer;
    private static long lastButtonAt;
    private static int lastButtonStream;
    private static long lastPlay;
    private static long pendingAt;
    private static int pendingSample;
    private static float pendingVol;
    private static long quietUntil;
    private static int lastEvent = -1;
    /** SoundPool sample ids per event (imported variants or library clips). */
    private static final int[][] samples = new int[EVENTS][MAX_VARIANTS];
    private static final int[] variants = new int[EVENTS];
    /** Shuffle-bag rotation per event: every variant once per round, never twice in a row. */
    private static final int[][] bag = new int[EVENTS][MAX_VARIANTS];
    private static final int[] bagPos = new int[EVENTS];
    private static final int[] lastPick = new int[EVENTS];
    private static final Set<Integer> loadedSet = new HashSet<>();
    private static final Random rnd = new Random();
    /** Library clip id → SoundPool sample id (0 = not playable). */
    private static final Map<String, Integer> clipSamples = new HashMap<>();

    private Sfx() {}

    private static String[] join(String[] a, String[] b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static AudioAttributes attributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
    }

    static String defaultFor(int event) {
        return FACTION_DEFAULT[Skin.faction][event];
    }

    /** Prefs key of the chosen library clip for an event in the current interface. */
    static String builtinKey(int event) {
        return Skin.command() ? "sndBi" + event : "sndBi" + Skin.faction + "_" + event;
    }

    static void reloadProfile(Context context) {
        reload(context);
    }

    /**
     * res/raw identifier of a bundled clip, or 0. Only personal develop builds bundle any; to ask
     * "can this clip play?" use {@link #available}, which also sees downloaded clips.
     */
    static int resId(Context context, String id) {
        if (id == null) return 0;
        return SoundCache.rawId(context, id);
    }

    /** True if the library clip can play: downloaded (cache), bundled (res/raw) or synthesized (assets). */
    static boolean available(Context context, String id) {
        if (TAP_SET.equals(id)) return SoundCache.available(context, TAP_SET_CLIPS[0]);
        if (isSynthTaps(id)) return SoundCache.available(context, clipsOf(id)[0]);
        return SoundCache.available(context, id);
    }

    /** The chosen SOUND SET ({@link #SET_LIBRARY} by default). */
    static String soundSet(Context context) {
        String s = TrekPrefs.get(context).soundSetChoice(SET_LIBRARY);
        return SET_SYNTH.equals(s) || SET_SILENT.equals(s) ? s : SET_LIBRARY;
    }

    /** Stores the SOUND SET and reloads every event's samples. */
    static void setSoundSet(Context context, String set) {
        TrekPrefs.get(context).put(TrekPrefs.KEY_SOUND_SET, set);
        reload(context);
    }

    /** True for synthesized clip ids (and the synthesized tap rotation). */
    static boolean isSynth(String id) {
        return id != null && (isSynthTaps(id) || id.startsWith(SoundCache.SYNTH_PREFIX));
    }

    /** True for any synthesized tap-rotation id ({@link #SYNTH_TAPS} and the family variants). */
    static boolean isSynthTaps(String id) {
        return id != null && id.startsWith(SYNTH_TAPS);
    }

    /** True for rotation ids that stand for several clips (never exported or preloaded as one file). */
    static boolean isRotation(String id) {
        return TAP_SET.equals(id) || isSynthTaps(id);
    }

    /**
     * The clip an event plays when it has no imported file, after applying the SOUND SET:
     * SILENT gives nothing; SYNTHESIZED the synthesized clip (or the user's synthesized pick);
     * DOWNLOADED LIBRARY the interface's library clip, falling back to the synthesized clip when that
     * one is not downloaded / bundled. An event set to "none" by the interface or the user stays silent.
     * Pure decision logic (unit-tested).
     *
     * @param set             the sound set
     * @param chosen          the event's library clip ({@link #builtinFor}); may be {@link #TAP_SET} or "none"
     * @param event           event index
     * @param libraryPlayable whether {@code chosen} can play (downloaded / bundled)
     */
    static String eventClip(String set, String chosen, int event, boolean libraryPlayable) {
        return eventClip(set, chosen, event, libraryPlayable, SYNTH_EVENT);
    }

    /** As above, falling back to the given interface's synthesized family ({@link #synthEventFor}). */
    static String eventClip(String set, String chosen, int event, boolean libraryPlayable, String[] synth) {
        if (SET_SILENT.equals(set) || chosen == null || "none".equals(chosen)) return "none";
        if (isSynth(chosen)) return chosen;
        if (SET_SYNTH.equals(set)) return synth[event];
        return libraryPlayable ? chosen : synth[event];
    }

    /** Expands rotation ids into their clips. */
    static String[] clipsOf(String id) {
        if (TAP_SET.equals(id)) return TAP_SET_CLIPS;
        if (SYNTH_TAPS.equals(id)) return SYNTH_TAP_CLIPS;
        if (isSynthTaps(id)) {
            String fam = id.substring(SYNTH_TAPS.length() + 1); // "#synthtaps_kl" -> "kl"
            String[] out = new String[FAMILY_TAPS];
            for (int i = 0; i < FAMILY_TAPS; i++) out[i] = "synth_" + fam + "_tap_" + (i + 1);
            return out;
        }
        return new String[]{id};
    }

    /** True if the library is present at all (downloaded or bundled); false on a fresh production install. */
    static boolean hasLibrary(Context context) {
        return SoundCache.available(context, TAP_SET_CLIPS[0]);
    }

    /** The library clip for an event: the user's choice, or the interface default. */
    static String builtinFor(Context context, int event) {
        Skin.faction = TrekPrefs.get(context).faction();
        String s = TrekPrefs.get(context).s(builtinKey(event), null);
        return s != null && !retiredClip(s) ? s : defaultFor(event);
    }

    static String libraryLabel(String id) {
        for (int i = 0; i < LIBRARY.length; i++) {
            if (LIBRARY[i].equals(id)) return LIBRARY_LABELS[i];
        }
        return id;
    }

    /** True if the event would make a sound (imported file, or an available library / synthesized clip). */
    static boolean hasSound(Context context, int event) {
        if (variantCount(context, event) > 0) return true;
        String clip = resolvedClip(context, event);
        return !"none".equals(clip) && available(context, clip);
    }

    /** The clip an event plays under the current SOUND SET (see {@link #eventClip}). */
    static String resolvedClip(Context context, int event) {
        String chosen = builtinFor(context, event);
        return eventClip(soundSet(context), chosen, event, !"none".equals(chosen) && available(context, chosen), synthEventFor(Skin.faction));
    }

    static boolean enabled(Context context) {
        return TrekPrefs.get(context).b("sfx", true);
    }

    static int volume(Context context) {
        return TrekPrefs.get(context).i("sfxVol", 55);
    }

    static boolean eventOn(Context context, int event) {
        return TrekPrefs.get(context).b("sndOn" + event, true);
    }

    static boolean followTouch(Context context) {
        return TrekPrefs.get(context).b("sfxTouch", false);
    }

    /** Folder of per-event imported files. */
    static File dir(Context context) {
        return new File(context.getFilesDir(), "trekui_sounds");
    }

    static File file(Context context, int event, int variant) {
        return new File(dir(context), EVENT_KEYS[event] + "_" + variant + ".wav");
    }

    static int variantCount(Context context, int event) {
        return TrekPrefs.get(context).i("snd" + event + "n", 0);
    }

    static String variantName(Context context, int event, int variant) {
        return TrekPrefs.get(context).s("snd" + event + "name" + variant, "?");
    }

    static String source(Context context, int event) {
        return TrekPrefs.get(context).s("snd" + event + "src", "");
    }

    static int assignedEvents(Context context) {
        int n = 0;
        for (int e = 0; e < EVENTS; e++) {
            if (hasSound(context, e)) n++;
        }
        return n;
    }

    /** Creates the SoundPool and loads every event's samples (imports first, else the library clip). */
    static synchronized void init(Context context) {
        if (pool != null || !enabled(context)) return;
        pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attributes()).build();
        loadedSet.clear();
        pool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override
            public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                if (status != 0) return;
                synchronized (Sfx.class) {
                    loadedSet.add(sampleId);
                    if (sampleId == pendingSample) {
                        pendingSample = 0;
                        if (SystemClock.uptimeMillis() - pendingAt < PENDING_PLAY_WINDOW_MS) {
                            soundPool.play(sampleId, pendingVol, pendingVol, 1, 0, 1.0f);
                        }
                    }
                }
            }
        });
        for (int e = 0; e < EVENTS; e++) {
            int n = 0;
            for (int v = 0; v < Math.min(variantCount(context, e), MAX_VARIANTS); v++) {
                File f = file(context, e, v);
                int sample;
                if (f.isFile() && (sample = pool.load(f.getAbsolutePath(), 1)) != 0) samples[e][n++] = sample;
            }
            if (n == 0) {
                String[] clips = clipsOf(resolvedClip(context, e));
                for (String id : clips) {
                    int sample;
                    if (!"none".equals(id) && n < MAX_VARIANTS && (sample = loadClip(context, id)) != 0) samples[e][n++] = sample;
                }
            }
            variants[e] = n;
            bagPos[e] = 0;
            lastPick[e] = -1;
        }
    }

    /** Loads a library clip into the pool (cache → res/raw → nothing); returns its sample id or 0. */
    private static int loadClip(Context context, String id) {
        Integer known = clipSamples.get(id);
        if (known != null) return known;
        int sample = 0;
        try {
            if (id.startsWith(SoundLibrary.PREFIX)) {
                File f = SoundLibrary.file(context, id);
                if (f.isFile()) sample = pool.load(f.getAbsolutePath(), 1);
            } else {
                switch (SoundCache.where(context, id)) {
                    case SoundCache.CACHE:
                        sample = pool.load(SoundCache.file(context, id).getAbsolutePath(), 1);
                        break;
                    case SoundCache.RAW:
                        sample = pool.load(context.getApplicationContext(), SoundCache.rawId(context, id), 1);
                        break;
                    case SoundCache.ASSET:
                        try (android.content.res.AssetFileDescriptor fd = context.getAssets().openFd(SoundCache.assetPath(id))) {
                            sample = pool.load(fd, 1); // SoundPool dups the descriptor; closing ours is safe
                        } catch (java.io.IOException e) {
                            sample = 0;
                        }
                        break;
                    default:
                        break;
                }
            }
        } catch (RuntimeException e) {
            sample = 0; // unreadable file: treat as silent
        }
        clipSamples.put(id, sample);
        return sample;
    }

    /** Clips over ~5 s play through MediaPlayer instead of SoundPool. */
    static boolean isLong(Context context, String id) {
        if (SourceSoundLibrary.LONG.contains(id) || "alt_klingon_transporter".equals(id)) return true;
        File f;
        if (id.startsWith(SoundLibrary.PREFIX)) f = SoundLibrary.file(context, id);
        else if (SoundCache.isCached(context, id)) f = SoundCache.file(context, id);
        else return false;
        return f.isFile() && f.length() > LONG_FILE_BYTES;
    }

    private static void playLong(Context context, String id, float vol) {
        try {
            if (longPlayer != null) {
                longPlayer.release();
                longPlayer = null;
            }
            MediaPlayer mp;
            File f = null;
            if (id.startsWith(SoundLibrary.PREFIX)) f = SoundLibrary.file(context, id);
            else if (SoundCache.isCached(context, id)) f = SoundCache.file(context, id);
            if (f != null) {
                if (!f.isFile()) return;
                mp = new MediaPlayer();
                mp.setAudioAttributes(attributes());
                mp.setDataSource(f.getAbsolutePath());
                mp.prepare();
            } else {
                int res = SoundCache.rawId(context, id);
                if (res == 0) return;
                mp = MediaPlayer.create(context.getApplicationContext(), res, attributes(), 0);
                if (mp == null) return;
            }
            mp.setVolume(vol, vol);
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer done) {
                    done.release();
                    synchronized (Sfx.class) {
                        if (longPlayer == done) longPlayer = null;
                    }
                }
            });
            longPlayer = mp;
            mp.start();
        } catch (Exception ignored) {
            // a missing or unreadable clip is silent
        }
    }

    /** Loads clips ahead of time so their first play is instant. */
    static synchronized void preload(Context context, Iterable<String> ids) {
        if (pool == null) init(context);
        if (pool == null) return;
        for (String id : ids) {
            if (id != null && !"none".equals(id) && !isRotation(id) && !isLong(context, id)) loadClip(context, id);
        }
    }

    /**
     * Plays one library clip (or the tap rotation).
     *
     * @param explicit true for previews / user-chosen station sounds: ignores the per-event switch and
     *                 does not start the quiet period
     */
    static synchronized void playClip(Context context, String id, boolean explicit) {
        if (id == null || id.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        if (!explicit) quietUntil = now + QUIET_AFTER_CLIP_MS;
        if ("none".equals(id)) return;
        if (TAP_SET.equals(id)) {
            quietUntil = 0L;
            playEvent(context, E_BUTTON, explicit);
            if (!explicit) quietUntil = now + QUIET_AFTER_CLIP_MS;
            return;
        }
        if (isSynthTaps(id)) {
            String[] taps = clipsOf(id);
            id = taps[rnd.nextInt(taps.length)];
        }
        if (!enabled(context) || !audioAllowed(context)) return;
        if (isLong(context, id)) {
            playLong(context, id, gain(context));
            return;
        }
        if (pool == null) init(context);
        int sample;
        if (pool == null || (sample = loadClip(context, id)) == 0) return;
        float vol = gain(context);
        if (!loadedSet.contains(sample)) {
            pendingSample = sample;
            pendingAt = now;
            pendingVol = vol;
        } else {
            if (now - lastButtonAt < BUTTON_CUTOFF_MS && lastButtonStream != 0) pool.stop(lastButtonStream);
            pool.play(sample, vol, vol, 1, 0, 1.0f);
        }
    }

    static synchronized void release() {
        clipSamples.clear();
        if (pool != null) {
            pool.release();
            pool = null;
            loadedSet.clear();
        }
        for (int e = 0; e < EVENTS; e++) variants[e] = 0;
        Runnable r = onRelease;
        if (r != null) r.run(); // looping streams (RED ALERT's klaxon) died with the pool
    }

    /** Run after every {@link #release()}; the launcher's RED ALERT forgets its looping stream here. */
    static volatile Runnable onRelease;

    /**
     * Starts an event's sound looping until {@link #stopLoop} (RED ALERT's klaxon). Same rules as
     * {@link #playEvent}: nothing plays with sounds off, the event switched off, Silent/Vibrate or a call.
     *
     * @return the SoundPool stream id; 0 when there is nothing to play (silent); -1 while the clip is
     *         still loading (call again shortly)
     */
    static synchronized int loopEvent(Context context, int event) {
        if (event < 0 || event >= EVENTS) return 0;
        if (!enabled(context) || !eventOn(context, event) || !audioAllowed(context)) return 0;
        if (pool == null) init(context);
        if (pool == null || variants[event] == 0) return 0;
        int sample = samples[event][0];
        if (!loadedSet.contains(sample)) return -1;
        float vol = gain(context);
        return pool.play(sample, vol, vol, 2, -1, 1.0f);
    }

    /** Stops a stream started by {@link #loopEvent}. */
    static synchronized void stopLoop(int stream) {
        if (pool != null && stream > 0) pool.stop(stream);
    }

    /** Drops and reloads every sample (after imports, downloads or a changed choice). */
    static synchronized void reload(Context context) {
        release();
        init(context);
    }

    /** False in Silent/Vibrate, during calls, or (if FOLLOW ANDROID TOUCH SOUNDS) when touch sounds are off. */
    static boolean audioAllowed(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            if (am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return false;
            int mode = am.getMode();
            if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE) {
                return false;
            }
        }
        if (followTouch(context)) {
            try {
                if (Settings.System.getInt(context.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1) == 0) return false;
            } catch (Exception ignored) {
                // setting unreadable: play
            }
        }
        return true;
    }

    /** Volume slider (0..100) squared for a perceptual curve; 10% while cloaked. */
    private static float gain(Context context) {
        float v = Math.max(0.0f, Math.min(1.0f, volume(context) / 100.0f));
        float g = v * v;
        boolean cloaked = Ui.cloak || TrekPrefs.get(context).l("cloakUntil", 0L) > System.currentTimeMillis();
        return cloaked ? g * 0.1f : g;
    }

    /** Next variant from the event's shuffle bag (never the same one twice in a row). */
    private static int nextVariant(int event) {
        int count = variants[event];
        if (count <= 1) return 0;
        if (bagPos[event] == 0) {
            for (int i = 0; i < count; i++) bag[event][i] = i;
            for (int i = count - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int t = bag[event][i];
                bag[event][i] = bag[event][j];
                bag[event][j] = t;
            }
            if (bag[event][0] == lastPick[event]) {
                int t = bag[event][0];
                bag[event][0] = bag[event][count - 1];
                bag[event][count - 1] = t;
            }
        }
        int pick = bag[event][bagPos[event]];
        bagPos[event] = (bagPos[event] + 1) % count;
        lastPick[event] = pick;
        return pick;
    }

    /** Plays the event for a legacy action code ({@link #TAP}, {@link #OPEN}, …). */
    static void play(Context context, int action) {
        if (action >= 0 && action < EVENT_OF.length) playEvent(context, EVENT_OF[action], false);
    }

    /**
     * Plays one of the eight events.
     *
     * @param explicit true for previews: ignores the per-event switch and the quiet period
     */
    static synchronized void playEvent(Context context, int event, boolean explicit) {
        if (event < 0 || event >= EVENTS) return;
        if (!enabled(context) || !(explicit || eventOn(context, event)) || !audioAllowed(context)) return;
        long now = SystemClock.uptimeMillis();
        if (!explicit && now < quietUntil && event != E_ERROR && event != E_RED) return;
        if (pool == null) init(context);
        if (pool == null || variants[event] == 0) return;
        if (event == lastEvent && now - lastPlay < SAME_EVENT_GAP_MS) return;
        lastEvent = event;
        lastPlay = now;
        int sample = samples[event][nextVariant(event)];
        float vol = gain(context);
        if (!loadedSet.contains(sample)) {
            pendingSample = sample;
            pendingAt = now;
            pendingVol = vol;
            return;
        }
        if (event != E_BUTTON && now - lastButtonAt < BUTTON_CUTOFF_MS && lastButtonStream != 0) pool.stop(lastButtonStream);
        int stream = pool.play(sample, vol, vol, event == E_RED ? 2 : 1, 0, 1.0f);
        if (event == E_BUTTON) {
            lastButtonStream = stream;
            lastButtonAt = now;
        }
    }
}
