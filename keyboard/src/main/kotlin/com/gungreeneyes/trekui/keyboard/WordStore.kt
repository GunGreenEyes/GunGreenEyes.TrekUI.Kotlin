package com.gungreeneyes.trekui.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.UserDictionary
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The two local word sources for suggestions:
 *
 * - **Typed words**: words typed on this keyboard with a use count, kept in memory and saved to
 *   `noBackupFilesDir/typed_words.tsv` (app-private, excluded from every backup) a few seconds
 *   after the last change. Capped at [MAX_WORDS]; the least used words are dropped first.
 *   [learn] is only called outside password / incognito fields (see FieldRules).
 * - **Personal dictionary**: Android's [UserDictionary], read in the background (only the
 *   current keyboard may read it) and refreshed at most every [DICTIONARY_TTL_MS].
 *
 * Nothing here touches the network.
 */
internal class WordStore private constructor(c: Context) {
    private val app: Context = c.applicationContext
    private val typed = HashMap<String, Int>()

    /** Personal dictionary words (original case → frequency), replaced whole by each background read. */
    @Volatile
    var dictionary: Map<String, Int> = emptyMap()
        private set

    private val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "keyboard-words").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val saveTask = Runnable { saveNow() }
    private var dictionaryAt = Long.MIN_VALUE / 2
    private var dictionaryLang: String? = null
    private var loaded = false

    /** A snapshot of the typed words (lower case → uses). */
    @Synchronized
    fun typed(): Map<String, Int> = HashMap(typed)

    /** Counts one use of [word] and schedules a save. */
    @Synchronized
    fun learn(word: String) {
        typed.merge(word.lowercase(Locale.ROOT), 1) { a, b -> a + b }
        if (typed.size > MAX_WORDS) prune()
        main.removeCallbacks(saveTask)
        main.postDelayed(saveTask, SAVE_DELAY_MS)
    }

    /** Forgets every learned word (memory and file). */
    @Synchronized
    fun clear() {
        typed.clear()
        main.removeCallbacks(saveTask)
        io.execute {
            val f = file()
            if (f.exists() && !f.delete()) f.deleteOnExit()
        }
    }

    /** Saves pending changes now (the keyboard is hidden). */
    fun flush() {
        main.removeCallbacks(saveTask)
        saveNow()
    }

    /** Re-reads the personal dictionary for [lang] in the background if stale. */
    fun refreshDictionary(lang: String) {
        val now = SystemClock.elapsedRealtime()
        if (lang == dictionaryLang && now - dictionaryAt < DICTIONARY_TTL_MS) return
        dictionaryAt = now
        dictionaryLang = lang
        io.execute { dictionary = readDictionary(lang) }
    }

    // ---- internals ----------------------------------------------------------------------------------

    private fun file(): File = File(app.noBackupFilesDir, FILE)

    /** Drops the least used tenth (oldest-first among equals is not tracked; ties drop arbitrarily). */
    private fun prune() {
        val drop = maxOf(1, typed.size - MAX_WORDS + MAX_WORDS / 10)
        typed.entries.sortedBy { it.value }.take(drop).map { it.key }.forEach { typed.remove(it) }
    }

    private fun load() {
        io.execute {
            val read = HashMap<String, Int>()
            val f = file()
            if (f.exists()) {
                try {
                    f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        for (line in lines) {
                            val tab = line.indexOf('\t')
                            if (tab <= 0) continue
                            // a damaged line is skipped
                            val uses = line.substring(tab + 1).trim().toIntOrNull() ?: continue
                            read[line.substring(0, tab)] = uses
                        }
                    }
                } catch (ignored: IOException) {
                    // unreadable: start empty
                }
            }
            synchronized(this) {
                read.forEach { (w, n) -> typed.merge(w, n) { a, b -> a + b } }
                loaded = true
            }
        }
    }

    private fun saveNow() {
        val snapshot: Map<String, Int>
        synchronized(this) {
            if (!loaded) return
            snapshot = HashMap(typed)
        }
        io.execute {
            val f = file()
            val tmp = File(f.path + ".tmp")
            try {
                tmp.writer(Charsets.UTF_8).use { w ->
                    for ((word, uses) in snapshot) {
                        w.write(word)
                        w.write('\t'.code)
                        w.write(uses.toString())
                        w.write('\n'.code)
                    }
                }
            } catch (e: IOException) {
                return@execute
            }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    /** The personal dictionary words for [lang] (and language-neutral ones). Blocking. */
    private fun readDictionary(lang: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        val cols = arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.FREQUENCY, UserDictionary.Words.LOCALE)
        try {
            app.contentResolver.query(UserDictionary.Words.CONTENT_URI, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val word = c.getString(0)
                    val locale = c.getString(2)
                    if (word.isNullOrEmpty()) continue
                    if (!locale.isNullOrEmpty() && !locale.lowercase(Locale.ROOT).startsWith(lang)) continue
                    out[word] = c.getInt(1)
                }
            }
        } catch (e: RuntimeException) {
            // not the current keyboard, or the provider is missing: no dictionary words
        }
        return out
    }

    companion object {
        const val MAX_WORDS = 3000
        const val SAVE_DELAY_MS = 4000L
        const val DICTIONARY_TTL_MS = 60_000L
        private const val FILE = "typed_words.tsv"

        private var instance: WordStore? = null

        @Synchronized
        fun get(c: Context): WordStore =
            instance ?: WordStore(c).also {
                instance = it
                it.load()
            }
    }
}
