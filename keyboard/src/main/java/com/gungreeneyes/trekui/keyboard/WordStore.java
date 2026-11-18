package com.gungreeneyes.trekui.keyboard;

import android.content.Context;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.UserDictionary;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The two local word sources for suggestions:
 * <ul>
 *   <li><b>Typed words</b>: words typed on this keyboard with a use count, kept in memory and saved to
 *       {@code noBackupFilesDir/typed_words.tsv} (app-private, excluded from every backup) a few seconds
 *       after the last change. Capped at {@link #MAX_WORDS}; the least used words are dropped first.
 *       {@link #learn} is only called outside password / incognito fields (see FieldRules).</li>
 *   <li><b>Personal dictionary</b>: Android's {@link UserDictionary}, read in the background (only the
 *       current keyboard may read it) and refreshed at most every {@link #DICTIONARY_TTL_MS}.</li>
 * </ul>
 * Nothing here touches the network.
 */
final class WordStore {
    static final int MAX_WORDS = 3000;
    static final long SAVE_DELAY_MS = 4000;
    static final long DICTIONARY_TTL_MS = 60_000;
    private static final String FILE = "typed_words.tsv";

    private static WordStore instance;

    private final Context app;
    private final Map<String, Integer> typed = new HashMap<>();
    private volatile Map<String, Integer> dictionary = Collections.emptyMap();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "keyboard-words");
        t.setDaemon(true);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable saveTask = this::saveNow;
    private long dictionaryAt = Long.MIN_VALUE / 2;
    private String dictionaryLang;
    private boolean loaded;

    private WordStore(Context c) {
        app = c.getApplicationContext();
    }

    static synchronized WordStore get(Context c) {
        if (instance == null) {
            instance = new WordStore(c);
            instance.load();
        }
        return instance;
    }

    /** A snapshot of the typed words (lower case → uses). */
    synchronized Map<String, Integer> typed() {
        return new HashMap<>(typed);
    }

    Map<String, Integer> dictionary() {
        return dictionary;
    }

    /** Counts one use of {@code word} and schedules a save. */
    synchronized void learn(String word) {
        String w = word.toLowerCase(java.util.Locale.ROOT);
        typed.merge(w, 1, Integer::sum);
        if (typed.size() > MAX_WORDS) prune();
        main.removeCallbacks(saveTask);
        main.postDelayed(saveTask, SAVE_DELAY_MS);
    }

    /** Forgets every learned word (memory and file). */
    synchronized void clear() {
        typed.clear();
        main.removeCallbacks(saveTask);
        io.execute(() -> {
            File f = file();
            if (f.exists() && !f.delete()) f.deleteOnExit();
        });
    }

    /** Saves pending changes now (the keyboard is hidden). */
    void flush() {
        main.removeCallbacks(saveTask);
        saveNow();
    }

    /** Re-reads the personal dictionary for {@code lang} in the background if stale. */
    void refreshDictionary(String lang) {
        long now = SystemClock.elapsedRealtime();
        if (lang.equals(dictionaryLang) && now - dictionaryAt < DICTIONARY_TTL_MS) return;
        dictionaryAt = now;
        dictionaryLang = lang;
        io.execute(() -> dictionary = readDictionary(lang));
    }

    // ---- internals ----------------------------------------------------------------------------------

    private File file() {
        return new File(app.getNoBackupFilesDir(), FILE);
    }

    /** Drops the least used tenth (oldest-first among equals is not tracked; ties drop arbitrarily). */
    private void prune() {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(typed.entrySet());
        entries.sort(Map.Entry.comparingByValue());
        int drop = Math.max(1, typed.size() - MAX_WORDS + MAX_WORDS / 10);
        for (int i = 0; i < drop && i < entries.size(); i++) typed.remove(entries.get(i).getKey());
    }

    private void load() {
        io.execute(() -> {
            Map<String, Integer> read = new HashMap<>();
            File f = file();
            if (f.exists()) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        int tab = line.indexOf('\t');
                        if (tab <= 0) continue;
                        try {
                            read.put(line.substring(0, tab), Integer.parseInt(line.substring(tab + 1).trim()));
                        } catch (NumberFormatException ignored) {
                            // skip a damaged line
                        }
                    }
                } catch (IOException ignored) {
                    // unreadable: start empty
                }
            }
            synchronized (WordStore.this) {
                for (Map.Entry<String, Integer> e : read.entrySet()) typed.merge(e.getKey(), e.getValue(), Integer::sum);
                loaded = true;
            }
        });
    }

    private void saveNow() {
        final Map<String, Integer> snapshot;
        synchronized (this) {
            if (!loaded) return;
            snapshot = new HashMap<>(typed);
        }
        io.execute(() -> {
            File f = file();
            File tmp = new File(f.getPath() + ".tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                for (Map.Entry<String, Integer> e : snapshot.entrySet()) {
                    w.write(e.getKey());
                    w.write('\t');
                    w.write(String.valueOf(e.getValue()));
                    w.write('\n');
                }
            } catch (IOException e) {
                return;
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        });
    }

    /** The personal dictionary words for {@code lang} (and language-neutral ones). Blocking. */
    private Map<String, Integer> readDictionary(String lang) {
        Map<String, Integer> out = new HashMap<>();
        String[] cols = {UserDictionary.Words.WORD, UserDictionary.Words.FREQUENCY, UserDictionary.Words.LOCALE};
        try (Cursor c = app.getContentResolver().query(UserDictionary.Words.CONTENT_URI, cols, null, null, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                String word = c.getString(0);
                String locale = c.getString(2);
                if (word == null || word.isEmpty()) continue;
                if (locale != null && !locale.isEmpty() && !locale.toLowerCase(java.util.Locale.ROOT).startsWith(lang)) continue;
                out.put(word, c.getInt(1));
            }
        } catch (RuntimeException e) {
            // not the current keyboard, or the provider is missing: no dictionary words
        }
        return out;
    }
}
