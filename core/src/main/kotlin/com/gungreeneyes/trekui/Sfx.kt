package com.gungreeneyes.trekui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.io.IOException
import java.util.Random

/**
 * The launcher's interface sounds. Eight events (button press, navigation, app launch, confirmation,
 * error, communications, diagnostics, red alert) each play an imported file, a library clip chosen
 * per interface, or nothing. Short clips go through one [SoundPool]; long ones (over ~5 s)
 * through a [MediaPlayer], since SoundPool may cut long samples short.
 *
 * Library clips are looked up by id, in this order (see [SoundCache]): the clip the user
 * downloaded via DOWNLOAD SOUND LIBRARY, then a res/raw resource of that name (personal develop
 * builds only), then the launcher's own synthesized clips (assets, every build), then silence.
 * The production build ships no recordings: out of the box every event plays its synthesized clip
 * ([SYNTH_EVENT]); the SOUND SET choice ([soundSet]) picks library / synthesized /
 * silent. Nothing here may crash when a clip is missing.
 *
 * Callers use the legacy action codes ([TAP], [OPEN], …) via [play]; each maps
 * to one of the eight events through `EVENT_OF`.
 *
 * Java interop: the codes and ids are `const`, the tables [JvmField]s and every entry point
 * [JvmStatic], so Java keeps calling `Sfx.play(c, Sfx.TAP)`. Every function the Java edition marked
 * `synchronized` (and its two callback blocks, which locked `Sfx.class` too) holds the one private
 * `lock`, so the mutual exclusion is unchanged.
 */
object Sfx {
    /** The single monitor of the Java edition's `synchronized` statics and callback blocks. */
    private val lock = Any()

    // ---- legacy action codes (index into EVENT_OF) ------------------------------------------
    const val TAP = 0
    const val PAGE = 1
    const val CONFIRM = 2
    const val DENY = 3
    const val OPEN = 4
    const val CLOSE = 5
    const val STARTUP = 6
    const val COMM = 7
    const val ALERT = 8
    const val POWER = 9
    const val WARNING = 10
    const val ERROR = 11
    const val LAUNCH = 12
    const val SEARCH = 13
    const val RESULT = 14
    const val LONG_PRESS = 15
    const val TOGGLE_ON = 16
    const val TOGGLE_OFF = 17
    const val CANCEL = 18
    const val SCAN_START = 19
    const val SCAN_DONE = 20
    const val STATUS_OK = 21
    const val SCANNER = 22
    const val CRITICAL = 23
    const val SYS_WARNING = 24
    const val CHANNEL_CLOSE = 25
    const val TAP_SOFT = 26
    const val HAIL = 27

    // ---- the eight events --------------------------------------------------------------------
    const val EVENTS = 8
    const val E_BUTTON = 0
    const val E_NAV = 1
    const val E_LAUNCH = 2
    const val E_CONFIRM = 3
    const val E_ERROR = 4
    const val E_COMMS = 5
    const val E_DIAG = 6
    const val E_RED = 7
    /** Up to this many imported files rotate for one event. */
    const val MAX_VARIANTS = 8

    @JvmField
    val EVENT_KEYS = arrayOf("button", "navigation", "launch", "confirm", "error", "comms", "diagnostics", "red_alert")

    @JvmField
    val EVENT_NAMES = arrayOf("BUTTON PRESS", "NAVIGATION", "APP LAUNCH", "INPUT CONFIRMATION", "ERROR", "COMMUNICATIONS", "SYSTEM DIAGNOSTICS", "RED ALERT")

    @JvmField
    val EVENT_DESC = arrayOf("TrekUI buttons, tiles, toggles, long-press", "Drawer open/close, search, category switch, back", "Launching an app", "Saved edits, permission granted, scan complete, default home set", "Failed action, unavailable function, permission denied", "Phone, messaging, chat and meeting apps (not email), the TrekUI dialer, appointment reminders", "Startup, weather scan, header scanner, charger, low battery", "Critical only: battery at 5%, storage below 5%")

    /** The button-press rotation: the chirp plus six single beeps cut from computer sequences. */
    @JvmField
    val TAP_SET_CLIPS = arrayOf("ui_button_tap", "tap_rot_1", "tap_rot_2", "tap_rot_3", "tap_rot_4", "tap_rot_5", "tap_rot_6")
    /** Library id meaning "the tap rotation". */
    const val TAP_SET = "#taps"
    private val BASE_LIBRARY = arrayOf(TAP_SET, "ui_button_tap", "ui_panel_open", "ui_screen_open", "ui_screen_close", "alert_general_short", "ui_denied", "comms_hail", "comms_hailing_open", "comms_incoming_transmission", "alert_warning", "alert_red_tng", "tng_transporter", "system_transfer_complete", "voice_accessing_library", "voice_affirmative", "voice_unable_to_comply", "voice_command_codes_verified", "voice_command_functions_offline", "voice_autodefense", "voice_decoupling_sequence", "voice_priority_one_message", "alt_borg_computer", "alt_forcefield_touch", "alt_klingon_transporter")
    private val BASE_LABELS = arrayOf("TAP ROTATION (7 SOUNDS)", "CLASSIC CHIRP", "PANEL DATA BURST", "VIEWSCREEN-ON SEQUENCE", "VIEWSCREEN-OFF SEQUENCE", "SHORT ALERT TONE", "DENY BEEP", "HAIL BEEP", "HAILING FREQUENCIES OPEN", "INCOMING TRANSMISSION", "WARNING ALARM", "RED ALERT", "TRANSPORTER", "TRANSFER COMPLETE", "VOICE: ACCESSING LIBRARY COMPUTER DATA", "VOICE: AFFIRMATIVE", "VOICE: UNABLE TO COMPLY", "VOICE: COMMAND CODES VERIFIED", "VOICE: COMMAND FUNCTIONS OFFLINE", "VOICE: AUTO-DEFENSE", "VOICE: DECOUPLING SEQUENCE", "VOICE: PRIORITY ONE MESSAGE", "COLLECTIVE COMPUTER", "FORCEFIELD TOUCH", "IMPERIAL TRANSPORTER (LONG)")
    private val BASE_ORIGINAL = arrayOf("tng_chirp_clean + 6 beeps cut from scrshow / tng_viewscreen_on / tng_viewscreen_off", "tng_chirp_clean.mp3", "scrshow.mp3", "tng_viewscreen_on.mp3", "tng_viewscreen_off.mp3", "alarm03.mp3", "denybeep3.mp3", "hailbeep_clean.mp3", "hailingfrequencies_open3.mp3 (trekcore)", "incomingtransmission_clean.mp3", "alert04.mp3", "tng_red_alert3.mp3", "tng_transporter1.mp3", "transfercomplete_clean.mp3", "accessinglibrarycomputerdata_clean.mp3", "affirmative1_ep.mp3", "unabletocomply1_ep.mp3", "commandcodesverified_ep.mp3", "commandfunctionsoffline_ep.mp3", "autodefense_ep.mp3", "initiatingdecouplingsequence_ep.mp3", "priorityonemessagefromstarfleet_ep.mp3", "borg_computer_beep.mp3", "forcefield_touch.mp3", "imperial transporter (faction bundle)")

    // ---- the launcher's own synthesized set (assets/sounds/synth, in every build) --------------
    /** Six synthesized console beeps that rotate for button presses. */
    @JvmField
    val SYNTH_TAP_CLIPS = arrayOf("synth_tap_1", "synth_tap_2", "synth_tap_3", "synth_tap_4", "synth_tap_5", "synth_tap_6")
    /** Library id meaning "the synthesized tap rotation". */
    const val SYNTH_TAPS = "#synthtaps"
    /** Synthesized clip per event (button press = the tap rotation; red alert is a seamless 1 s klaxon loop). */
    @JvmField
    val SYNTH_EVENT = arrayOf(SYNTH_TAPS, "synth_nav", "synth_launch", "synth_confirm", "synth_error", "synth_comms", "synth_diag", "synth_red_alert")
    /** Synthesized start-up sweep (BootSequence falls back to it). */
    const val SYNTH_BOOT = "synth_boot"

    // Interface-specific synthesized families (same event layout, different character):
    // Imperial = harsh / metallic / low, Collective = cold digital / drone. Four tap beeps each.
    /** Synthesized tap rotation of the Imperial family. */
    const val SYNTH_KL_TAPS = "#synthtaps_kl"
    /** Synthesized tap rotation of the Collective family. */
    const val SYNTH_BG_TAPS = "#synthtaps_bg"
    @JvmField
    val SYNTH_KL_EVENT = family("kl", SYNTH_KL_TAPS)
    @JvmField
    val SYNTH_BG_EVENT = family("bg", SYNTH_BG_TAPS)
    /** Prefix of the synthesized family of the retired interfaces (ids 3-8); its clips are no longer shipped. */
    private const val RETIRED_FAMILY = "pp"
    private const val FAMILY_TAPS = 4

    /** {taps, synth_<fam>_nav, …, synth_<fam>_red_alert} in event order. */
    private fun family(fam: String, taps: String): Array<String> {
        val parts = arrayOf("nav", "launch", "confirm", "error", "comms", "diag", "red_alert")
        return Array(EVENTS) { i -> if (i == 0) taps else "synth_" + fam + "_" + parts[i - 1] }
    }

    private val SYNTH_LIBRARY: Array<String>
    private val SYNTH_LABELS: Array<String>
    private val SYNTH_ORIGINAL: Array<String>

    init {
        val ids = ArrayList<String>()
        val labels = ArrayList<String>()
        val commandIds = arrayOf(SYNTH_TAPS, "synth_tap_1", "synth_tap_2", "synth_tap_3", "synth_tap_4", "synth_tap_5", "synth_tap_6", "synth_nav", "synth_launch", "synth_confirm", "synth_error", "synth_comms", "synth_diag", "synth_red_alert", "synth_boot")
        val commandLabels = arrayOf("SYNTHESIZED: TAP ROTATION (6 SOUNDS)", "SYNTHESIZED: TAP 1", "SYNTHESIZED: TAP 2", "SYNTHESIZED: TAP 3", "SYNTHESIZED: TAP 4", "SYNTHESIZED: TAP 5", "SYNTHESIZED: TAP 6", "SYNTHESIZED: NAVIGATION CHIRP", "SYNTHESIZED: LAUNCH ARPEGGIO", "SYNTHESIZED: CONFIRM CHIME", "SYNTHESIZED: ERROR BUZZ", "SYNTHESIZED: COMMS HAIL", "SYNTHESIZED: DIAGNOSTIC BLIPS", "SYNTHESIZED: RED ALERT KLAXON", "SYNTHESIZED: POWER-UP SWEEP")
        ids.addAll(commandIds)
        labels.addAll(commandLabels)
        val fams = arrayOf(arrayOf("kl", "SYNTHESIZED IMPERIAL", SYNTH_KL_TAPS), arrayOf("bg", "SYNTHESIZED COLLECTIVE", SYNTH_BG_TAPS))
        val parts = arrayOf("nav", "launch", "confirm", "error", "comms", "diag", "red_alert", "boot")
        val partNames = arrayOf("NAVIGATION", "LAUNCH", "CONFIRM", "ERROR", "COMMS", "DIAGNOSTICS", "RED ALERT LOOP", "START-UP")
        for (f in fams) {
            ids.add(f[2])
            labels.add(f[1] + ": TAP ROTATION (" + FAMILY_TAPS + " SOUNDS)")
            for (i in 1..FAMILY_TAPS) {
                ids.add("synth_" + f[0] + "_tap_" + i)
                labels.add(f[1] + ": TAP " + i)
            }
            for (i in parts.indices) {
                ids.add("synth_" + f[0] + "_" + parts[i])
                labels.add(f[1] + ": " + partNames[i])
            }
        }
        SYNTH_LIBRARY = ids.toTypedArray()
        SYNTH_LABELS = labels.toTypedArray()
        SYNTH_ORIGINAL = Array(SYNTH_LIBRARY.size) { "original synthesis (tools/sounds/synth/make_synth.py)" }
    }

    /** SOUND SET (CONFIG › AUDIO): the downloaded / bundled library, with synthesized clips wherever it is missing (default). */
    const val SET_LIBRARY = "library"
    /** SOUND SET: only the synthesized clips. */
    const val SET_SYNTH = "synth"
    /** SOUND SET: no built-in sounds (imported files still play). */
    const val SET_SILENT = "silent"
    @JvmField
    val SET_KEYS = arrayOf(SET_LIBRARY, SET_SYNTH, SET_SILENT)
    // Display names and descriptions of the sets: R.array.sound_set_names / sound_set_descriptions (same order as SET_KEYS).

    /** Every library id (index 0 is the tap rotation), with its label and the original file it came from. */
    @JvmField
    val LIBRARY: Array<String> = BASE_LIBRARY + SourceSoundLibrary.NAMES + SYNTH_LIBRARY
    @JvmField
    val LIBRARY_LABELS: Array<String> = BASE_LABELS + SourceSoundLibrary.LABELS + SYNTH_LABELS
    @JvmField
    val LIBRARY_ORIGINAL: Array<String> = BASE_ORIGINAL + SourceSoundLibrary.ORIGINALS + SYNTH_ORIGINAL
    /** Command defaults per event. */
    @JvmField
    val DEFAULT_RES = arrayOf(TAP_SET, "ui_panel_open", "ui_screen_open", "alert_general_short", "ui_denied", "comms_hailing_open", "ui_screen_close", "alert_red_tng")
    /** Imperial defaults: a few library clips where the 5.x bundle chose them, the harsh synthesized set elsewhere. */
    @JvmField
    val IMPERIAL_DEFAULT = arrayOf(SYNTH_KL_TAPS, "ui_screen_open", SYNTH_KL_EVENT[E_LAUNCH], SYNTH_KL_EVENT[E_CONFIRM], "ui_denied", SYNTH_KL_EVENT[E_COMMS], "ui_screen_close", "alert_warning")
    /** Collective defaults: the Collective computer and a few library clips, the cold synthesized set elsewhere. */
    @JvmField
    val COLLECTIVE_DEFAULT = arrayOf(SYNTH_BG_TAPS, SYNTH_BG_EVENT[E_NAV], SYNTH_BG_EVENT[E_LAUNCH], "alt_borg_computer", "ui_denied", SYNTH_BG_EVENT[E_COMMS], "ui_screen_close", SYNTH_BG_EVENT[E_RED])
    /**
     * Defaults per interface id (Skin.faction): Command, Imperial, Collective, the retired ids 3-8
     * (Command sounds; such a choice reads as Command anyway), then the 23rd/25th-century eras
     * (Command sounds).
     */
    @JvmField
    val FACTION_DEFAULT = arrayOf(DEFAULT_RES, IMPERIAL_DEFAULT, COLLECTIVE_DEFAULT, DEFAULT_RES, DEFAULT_RES, DEFAULT_RES, DEFAULT_RES, DEFAULT_RES, DEFAULT_RES, DEFAULT_RES, DEFAULT_RES)

    /** The synthesized family of an interface: Imperial, Collective, else the console set. */
    @JvmStatic
    fun synthEventFor(faction: Int): Array<String> = when (faction) {
        Skin.IMPERIAL -> SYNTH_KL_EVENT
        Skin.COLLECTIVE -> SYNTH_BG_EVENT
        else -> SYNTH_EVENT
    }

    /**
     * True for a stored per-event choice from the retired interfaces' synthesized family (its tap
     * rotation or one of its clips): those files are gone, so the choice reads as the interface default.
     */
    @JvmStatic
    fun retiredClip(id: String?): Boolean =
        id != null && (id == SYNTH_TAPS + "_" + RETIRED_FAMILY || id.startsWith("synth_" + RETIRED_FAMILY + "_"))

    /** The synthesized start-up sound of an interface (BootSequence's fallback). */
    @JvmStatic
    fun synthBootFor(faction: Int): String {
        val red = synthEventFor(faction)[E_RED]
        return red.substring(0, red.length - "red_alert".length) + "boot"
    }

    /** Legacy action code → event. */
    private val EVENT_OF = intArrayOf(0, 1, 3, 4, 1, 1, 6, 5, 5, 6, 6, 4, 2, 1, 2, 0, 0, 0, 1, 6, 3, 3, 6, 7, 7, 6, 0, 5)

    /** Clip files above this size play through MediaPlayer (about 5 s of 44.1 kHz mono 16-bit). */
    private const val LONG_FILE_BYTES = 480_000L
    /** After an explicit clip, ordinary events stay quiet this long so they don't talk over it. */
    private const val QUIET_AFTER_CLIP_MS = 600L
    /** A clip still loading plays when ready only if the request is this recent. */
    private const val PENDING_PLAY_WINDOW_MS = 2500L
    /** The same event retriggered faster than this is ignored (double taps). */
    private const val SAME_EVENT_GAP_MS = 80L
    /** A new event this soon after a button sound cuts the button sound off. */
    private const val BUTTON_CUTOFF_MS = 150L

    private var pool: SoundPool? = null
    private var longPlayer: MediaPlayer? = null
    private var lastButtonAt = 0L
    private var lastButtonStream = 0
    private var lastPlay = 0L
    private var pendingAt = 0L
    private var pendingSample = 0
    private var pendingVol = 0f
    private var quietUntil = 0L
    private var lastEvent = -1
    /** SoundPool sample ids per event (imported variants or library clips). */
    private val samples = Array(EVENTS) { IntArray(MAX_VARIANTS) }
    private val variants = IntArray(EVENTS)
    /** Shuffle-bag rotation per event: every variant once per round, never twice in a row. */
    private val bag = Array(EVENTS) { IntArray(MAX_VARIANTS) }
    private val bagPos = IntArray(EVENTS)
    private val lastPick = IntArray(EVENTS)
    private val loadedSet = HashSet<Int>()
    private val rnd = Random()
    /** Library clip id → SoundPool sample id (0 = not playable). */
    private val clipSamples = HashMap<String, Int>()

    /** Run after every [release]; the launcher's RED ALERT forgets its looping stream here. */
    @JvmField
    @Volatile
    var onRelease: Runnable? = null

    private fun attributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    @JvmStatic
    fun defaultFor(event: Int): String = FACTION_DEFAULT[Skin.faction][event]

    /** Prefs key of the chosen library clip for an event in the current interface. */
    @JvmStatic
    fun builtinKey(event: Int): String = if (Skin.command()) "sndBi$event" else "sndBi" + Skin.faction + "_" + event

    @JvmStatic
    fun reloadProfile(context: Context) {
        reload(context)
    }

    /**
     * res/raw identifier of a bundled clip, or 0. Only personal develop builds bundle any; to ask
     * "can this clip play?" use [available], which also sees downloaded clips.
     */
    @JvmStatic
    fun resId(context: Context, id: String?): Int = if (id == null) 0 else SoundCache.rawId(context, id)

    /** True if the library clip can play: downloaded (cache), bundled (res/raw) or synthesized (assets). */
    @JvmStatic
    fun available(context: Context, id: String?): Boolean = when {
        TAP_SET == id -> SoundCache.available(context, TAP_SET_CLIPS[0])
        isSynthTaps(id) -> SoundCache.available(context, clipsOf(id)[0])
        else -> SoundCache.available(context, id)
    }

    /** The chosen SOUND SET ([SET_LIBRARY] by default). */
    @JvmStatic
    fun soundSet(context: Context): String {
        val s = TrekPrefs.get(context).soundSetChoice(SET_LIBRARY)
        return if (SET_SYNTH == s || SET_SILENT == s) s else SET_LIBRARY
    }

    /** Stores the SOUND SET and reloads every event's samples. */
    @JvmStatic
    fun setSoundSet(context: Context, set: String?) {
        TrekPrefs.get(context).put(TrekPrefs.KEY_SOUND_SET, set)
        reload(context)
    }

    /** True for synthesized clip ids (and the synthesized tap rotation). */
    @JvmStatic
    fun isSynth(id: String?): Boolean = id != null && (isSynthTaps(id) || id.startsWith(SoundCache.SYNTH_PREFIX))

    /** True for any synthesized tap-rotation id ([SYNTH_TAPS] and the family variants). */
    @JvmStatic
    fun isSynthTaps(id: String?): Boolean = id != null && id.startsWith(SYNTH_TAPS)

    /** True for rotation ids that stand for several clips (never exported or preloaded as one file). */
    @JvmStatic
    fun isRotation(id: String?): Boolean = TAP_SET == id || isSynthTaps(id)

    /**
     * The clip an event plays when it has no imported file, after applying the SOUND SET:
     * SILENT gives nothing; SYNTHESIZED the synthesized clip (or the user's synthesized pick);
     * DOWNLOADED LIBRARY the interface's library clip, falling back to the synthesized clip when that
     * one is not downloaded / bundled. An event set to "none" by the interface or the user stays silent.
     * Pure decision logic (unit-tested).
     *
     * @param set             the sound set
     * @param chosen          the event's library clip ([builtinFor]); may be [TAP_SET] or "none"
     * @param event           event index
     * @param libraryPlayable whether `chosen` can play (downloaded / bundled)
     */
    @JvmStatic
    fun eventClip(set: String?, chosen: String?, event: Int, libraryPlayable: Boolean): String =
        eventClip(set, chosen, event, libraryPlayable, SYNTH_EVENT)

    /** As above, falling back to the given interface's synthesized family ([synthEventFor]). */
    @JvmStatic
    fun eventClip(set: String?, chosen: String?, event: Int, libraryPlayable: Boolean, synth: Array<String>): String {
        if (SET_SILENT == set || chosen == null || "none" == chosen) return "none"
        if (isSynth(chosen)) return chosen
        if (SET_SYNTH == set) return synth[event]
        return if (libraryPlayable) chosen else synth[event]
    }

    /**
     * Expands rotation ids into their clips. The two fixed rotations return their shared tables
     * ([TAP_SET_CLIPS], [SYNTH_TAP_CLIPS]) as the Java edition did; any other id comes back alone.
     */
    @JvmStatic
    fun clipsOf(id: String?): Array<String> {
        if (TAP_SET == id) return TAP_SET_CLIPS
        if (SYNTH_TAPS == id) return SYNTH_TAP_CLIPS
        if (id != null && isSynthTaps(id)) {
            val fam = id.substring(SYNTH_TAPS.length + 1) // "#synthtaps_kl" -> "kl"
            return Array(FAMILY_TAPS) { i -> "synth_" + fam + "_tap_" + (i + 1) }
        }
        @Suppress("UNCHECKED_CAST") // a null id comes back as {null}, exactly like the Java edition
        return arrayOf(id) as Array<String>
    }

    /** True if the library is present at all (downloaded or bundled); false on a fresh production install. */
    @JvmStatic
    fun hasLibrary(context: Context): Boolean = SoundCache.available(context, TAP_SET_CLIPS[0])

    /** The library clip for an event: the user's choice, or the interface default. */
    @JvmStatic
    fun builtinFor(context: Context, event: Int): String {
        Skin.faction = TrekPrefs.get(context).faction()
        val s: String? = TrekPrefs.get(context).s(builtinKey(event), null)
        return if (s != null && !retiredClip(s)) s else defaultFor(event)
    }

    @JvmStatic
    fun libraryLabel(id: String): String {
        for (i in LIBRARY.indices) {
            if (LIBRARY[i] == id) return LIBRARY_LABELS[i]
        }
        return id
    }

    /** True if the event would make a sound (imported file, or an available library / synthesized clip). */
    @JvmStatic
    fun hasSound(context: Context, event: Int): Boolean {
        if (variantCount(context, event) > 0) return true
        val clip = resolvedClip(context, event)
        return "none" != clip && available(context, clip)
    }

    /** The clip an event plays under the current SOUND SET (see [eventClip]). */
    @JvmStatic
    fun resolvedClip(context: Context, event: Int): String {
        val chosen = builtinFor(context, event)
        return eventClip(soundSet(context), chosen, event, "none" != chosen && available(context, chosen), synthEventFor(Skin.faction))
    }

    @JvmStatic
    fun enabled(context: Context): Boolean = TrekPrefs.get(context).b("sfx", true)

    @JvmStatic
    fun volume(context: Context): Int = TrekPrefs.get(context).i("sfxVol", 55)

    @JvmStatic
    fun eventOn(context: Context, event: Int): Boolean = TrekPrefs.get(context).b("sndOn$event", true)

    @JvmStatic
    fun followTouch(context: Context): Boolean = TrekPrefs.get(context).b("sfxTouch", false)

    /** Folder of per-event imported files. */
    @JvmStatic
    fun dir(context: Context): File = File(context.filesDir, "trekui_sounds")

    @JvmStatic
    fun file(context: Context, event: Int, variant: Int): File = File(dir(context), EVENT_KEYS[event] + "_" + variant + ".wav")

    @JvmStatic
    fun variantCount(context: Context, event: Int): Int = TrekPrefs.get(context).i("snd" + event + "n", 0)

    @JvmStatic
    fun variantName(context: Context, event: Int, variant: Int): String? =
        TrekPrefs.get(context).s("snd" + event + "name" + variant, "?")

    @JvmStatic
    fun source(context: Context, event: Int): String? = TrekPrefs.get(context).s("snd" + event + "src", "")

    @JvmStatic
    fun assignedEvents(context: Context): Int = (0 until EVENTS).count { hasSound(context, it) }

    /** Creates the SoundPool and loads every event's samples (imports first, else the library clip). */
    @JvmStatic
    fun init(context: Context) {
        synchronized(lock) {
            if (pool != null || !enabled(context)) return
            val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attributes()).build()
            pool = p
            loadedSet.clear()
            p.setOnLoadCompleteListener { soundPool, sampleId, status ->
                if (status != 0) return@setOnLoadCompleteListener
                synchronized(lock) {
                    loadedSet.add(sampleId)
                    if (sampleId == pendingSample) {
                        pendingSample = 0
                        if (SystemClock.uptimeMillis() - pendingAt < PENDING_PLAY_WINDOW_MS) {
                            soundPool.play(sampleId, pendingVol, pendingVol, 1, 0, 1.0f)
                        }
                    }
                }
            }
            for (e in 0 until EVENTS) {
                var n = 0
                var v = 0
                while (v < Math.min(variantCount(context, e), MAX_VARIANTS)) {
                    val f = file(context, e, v)
                    if (f.isFile) {
                        val sample = p.load(f.absolutePath, 1)
                        if (sample != 0) samples[e][n++] = sample
                    }
                    v++
                }
                if (n == 0) {
                    for (id in clipsOf(resolvedClip(context, e))) {
                        if ("none" != id && n < MAX_VARIANTS) {
                            val sample = loadClip(context, id)
                            if (sample != 0) samples[e][n++] = sample
                        }
                    }
                }
                variants[e] = n
                bagPos[e] = 0
                lastPick[e] = -1
            }
        }
    }

    /** Loads a library clip into the pool (cache → res/raw → nothing); returns its sample id or 0. */
    private fun loadClip(context: Context, id: String): Int {
        clipSamples[id]?.let { return it }
        var sample = 0
        try {
            val p = pool!!
            if (id.startsWith(SoundLibrary.PREFIX)) {
                val f = SoundLibrary.file(context, id)
                if (f.isFile) sample = p.load(f.absolutePath, 1)
            } else {
                when (SoundCache.where(context, id)) {
                    SoundCache.CACHE -> sample = p.load(SoundCache.file(context, id).absolutePath, 1)
                    SoundCache.RAW -> sample = p.load(context.applicationContext, SoundCache.rawId(context, id), 1)
                    SoundCache.ASSET -> sample = try {
                        // SoundPool dups the descriptor; closing ours is safe
                        context.assets.openFd(SoundCache.assetPath(id)).use { fd -> p.load(fd, 1) }
                    } catch (e: IOException) {
                        0
                    }
                    else -> {}
                }
            }
        } catch (e: RuntimeException) {
            sample = 0 // unreadable file: treat as silent
        }
        clipSamples[id] = sample
        return sample
    }

    /** Clips over ~5 s play through MediaPlayer instead of SoundPool. */
    @JvmStatic
    fun isLong(context: Context, id: String): Boolean {
        if (SourceSoundLibrary.LONG.contains(id) || "alt_klingon_transporter" == id) return true
        val f = when {
            id.startsWith(SoundLibrary.PREFIX) -> SoundLibrary.file(context, id)
            SoundCache.isCached(context, id) -> SoundCache.file(context, id)
            else -> return false
        }
        return f.isFile && f.length() > LONG_FILE_BYTES
    }

    private fun playLong(context: Context, id: String, vol: Float) {
        try {
            longPlayer?.let {
                it.release()
                longPlayer = null
            }
            val mp: MediaPlayer
            var f: File? = null
            if (id.startsWith(SoundLibrary.PREFIX)) f = SoundLibrary.file(context, id)
            else if (SoundCache.isCached(context, id)) f = SoundCache.file(context, id)
            if (f != null) {
                if (!f.isFile) return
                mp = MediaPlayer()
                mp.setAudioAttributes(attributes())
                mp.setDataSource(f.absolutePath)
                mp.prepare()
            } else {
                val res = SoundCache.rawId(context, id)
                if (res == 0) return
                mp = MediaPlayer.create(context.applicationContext, res, attributes(), 0) ?: return
            }
            mp.setVolume(vol, vol)
            mp.setOnCompletionListener { done ->
                done.release()
                synchronized(lock) {
                    if (longPlayer === done) longPlayer = null
                }
            }
            longPlayer = mp
            mp.start()
        } catch (ignored: Exception) {
            // a missing or unreadable clip is silent
        }
    }

    /** Loads clips ahead of time so their first play is instant. */
    @JvmStatic
    fun preload(context: Context, ids: Iterable<String?>) {
        synchronized(lock) {
            if (pool == null) init(context)
            if (pool == null) return
            for (id in ids) {
                if (id != null && "none" != id && !isRotation(id) && !isLong(context, id)) loadClip(context, id)
            }
        }
    }

    /**
     * Plays one library clip (or the tap rotation).
     *
     * @param explicit true for previews / user-chosen station sounds: ignores the per-event switch and
     *                 does not start the quiet period
     */
    @JvmStatic
    fun playClip(context: Context, clipId: String?, explicit: Boolean) {
        synchronized(lock) {
            if (clipId == null || clipId.isEmpty()) return
            var id: String = clipId
            val now = SystemClock.uptimeMillis()
            if (!explicit) quietUntil = now + QUIET_AFTER_CLIP_MS
            if ("none" == id) return
            if (TAP_SET == id) {
                quietUntil = 0L
                playEvent(context, E_BUTTON, explicit)
                if (!explicit) quietUntil = now + QUIET_AFTER_CLIP_MS
                return
            }
            if (isSynthTaps(id)) {
                val taps = clipsOf(id)
                id = taps[rnd.nextInt(taps.size)]
            }
            if (!enabled(context) || !audioAllowed(context)) return
            if (isLong(context, id)) {
                playLong(context, id, gain(context))
                return
            }
            if (pool == null) init(context)
            val p = pool ?: return
            val sample = loadClip(context, id)
            if (sample == 0) return
            val vol = gain(context)
            if (!loadedSet.contains(sample)) {
                pendingSample = sample
                pendingAt = now
                pendingVol = vol
            } else {
                if (now - lastButtonAt < BUTTON_CUTOFF_MS && lastButtonStream != 0) p.stop(lastButtonStream)
                p.play(sample, vol, vol, 1, 0, 1.0f)
            }
        }
    }

    @JvmStatic
    fun release() {
        synchronized(lock) {
            clipSamples.clear()
            pool?.let {
                it.release()
                pool = null
                loadedSet.clear()
            }
            for (e in 0 until EVENTS) variants[e] = 0
            onRelease?.run() // looping streams (RED ALERT's klaxon) died with the pool
        }
    }

    /**
     * Starts an event's sound looping until [stopLoop] (RED ALERT's klaxon). Same rules as
     * [playEvent]: nothing plays with sounds off, the event switched off, Silent/Vibrate or a call.
     *
     * @return the SoundPool stream id; 0 when there is nothing to play (silent); -1 while the clip is
     *         still loading (call again shortly)
     */
    @JvmStatic
    fun loopEvent(context: Context, event: Int): Int {
        return synchronized(lock) {
            if (event < 0 || event >= EVENTS) return 0
            if (!enabled(context) || !eventOn(context, event) || !audioAllowed(context)) return 0
            if (pool == null) init(context)
            val p = pool
            if (p == null || variants[event] == 0) return 0
            val sample = samples[event][0]
            if (!loadedSet.contains(sample)) return -1
            val vol = gain(context)
            p.play(sample, vol, vol, 2, -1, 1.0f)
        }
    }

    /** Stops a stream started by [loopEvent]. */
    @JvmStatic
    fun stopLoop(stream: Int) {
        synchronized(lock) {
            val p = pool
            if (p != null && stream > 0) p.stop(stream)
        }
    }

    /** Drops and reloads every sample (after imports, downloads or a changed choice). */
    @JvmStatic
    fun reload(context: Context) {
        synchronized(lock) {
            release()
            init(context)
        }
    }

    /** False in Silent/Vibrate, during calls, or (if FOLLOW ANDROID TOUCH SOUNDS) when touch sounds are off. */
    @JvmStatic
    fun audioAllowed(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
        if (am != null) {
            if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
            val mode = am.mode
            if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE) {
                return false
            }
        }
        if (followTouch(context)) {
            try {
                if (Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 0) return false
            } catch (ignored: Exception) {
                // setting unreadable: play
            }
        }
        return true
    }

    /** Volume slider (0..100) squared for a perceptual curve; 10% while cloaked. */
    private fun gain(context: Context): Float {
        val v = Math.max(0.0f, Math.min(1.0f, volume(context) / 100.0f))
        val g = v * v
        val cloaked = Ui.cloak || TrekPrefs.get(context).l("cloakUntil", 0L) > System.currentTimeMillis()
        return if (cloaked) g * 0.1f else g
    }

    /** Next variant from the event's shuffle bag (never the same one twice in a row). */
    private fun nextVariant(event: Int): Int {
        val count = variants[event]
        if (count <= 1) return 0
        val b = bag[event]
        if (bagPos[event] == 0) {
            for (i in 0 until count) b[i] = i
            for (i in count - 1 downTo 1) {
                val j = rnd.nextInt(i + 1)
                val t = b[i]
                b[i] = b[j]
                b[j] = t
            }
            if (b[0] == lastPick[event]) {
                val t = b[0]
                b[0] = b[count - 1]
                b[count - 1] = t
            }
        }
        val pick = b[bagPos[event]]
        bagPos[event] = (bagPos[event] + 1) % count
        lastPick[event] = pick
        return pick
    }

    /** Plays the event for a legacy action code ([TAP], [OPEN], …). */
    @JvmStatic
    fun play(context: Context, action: Int) {
        if (action >= 0 && action < EVENT_OF.size) playEvent(context, EVENT_OF[action], false)
    }

    /**
     * Plays one of the eight events.
     *
     * @param explicit true for previews: ignores the per-event switch and the quiet period
     */
    @JvmStatic
    fun playEvent(context: Context, event: Int, explicit: Boolean) {
        synchronized(lock) {
            if (event < 0 || event >= EVENTS) return
            if (!enabled(context) || !(explicit || eventOn(context, event)) || !audioAllowed(context)) return
            val now = SystemClock.uptimeMillis()
            if (!explicit && now < quietUntil && event != E_ERROR && event != E_RED) return
            if (pool == null) init(context)
            val p = pool
            if (p == null || variants[event] == 0) return
            if (event == lastEvent && now - lastPlay < SAME_EVENT_GAP_MS) return
            lastEvent = event
            lastPlay = now
            val sample = samples[event][nextVariant(event)]
            val vol = gain(context)
            if (!loadedSet.contains(sample)) {
                pendingSample = sample
                pendingAt = now
                pendingVol = vol
                return
            }
            if (event != E_BUTTON && now - lastButtonAt < BUTTON_CUTOFF_MS && lastButtonStream != 0) p.stop(lastButtonStream)
            val stream = p.play(sample, vol, vol, if (event == E_RED) 2 else 1, 0, 1.0f)
            if (event == E_BUTTON) {
                lastButtonStream = stream
                lastButtonAt = now
            }
        }
    }
}
