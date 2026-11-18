package com.gungreeneyes.trekui.keyboard;

import android.content.Context;

import com.gungreeneyes.trekui.TrekPrefs;
import com.gungreeneyes.trekui.keyboard.logic.Language;
import com.gungreeneyes.trekui.keyboard.logic.Sizing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The keyboard's own settings, stored next to the shared look settings in this app's {@code trekui}
 * prefs file under {@code kb.} keys: size presets, one-handed mode, digit row, haptics, suggestions,
 * learning and the enabled / current languages. Key sounds and their volume live in {@code KeyboardLook}
 * (they are the :core Sfx switches).
 */
final class KeyboardPrefs {
    static final String SIZE = "kb.size", SIZE_LAND = "kb.sizeLand", ONE_HANDED = "kb.oneHanded",
            DIGIT_ROW = "kb.digitRow", HAPTICS = "kb.haptics", SUGGEST = "kb.suggest", LEARN = "kb.learn",
            LANGS = "kb.langs", LANG = "kb.lang";

    private final TrekPrefs p;

    private KeyboardPrefs(Context c) {
        p = TrekPrefs.get(c);
    }

    static KeyboardPrefs get(Context c) {
        return new KeyboardPrefs(c);
    }

    int size() { return Sizing.clampPreset(p.i(SIZE, Sizing.M)); }
    void setSize(int v) { p.put(SIZE, Sizing.clampPreset(v)); }
    int sizeLandscape() { return Sizing.clampPreset(p.i(SIZE_LAND, Sizing.M)); }
    void setSizeLandscape(int v) { p.put(SIZE_LAND, Sizing.clampPreset(v)); }
    int oneHanded() {
        int v = p.i(ONE_HANDED, Sizing.ONE_HANDED_OFF);
        return v < Sizing.ONE_HANDED_OFF || v > Sizing.ONE_HANDED_RIGHT ? Sizing.ONE_HANDED_OFF : v;
    }
    void setOneHanded(int v) { p.put(ONE_HANDED, v); }
    boolean digitRow() { return p.b(DIGIT_ROW, false); }
    void setDigitRow(boolean v) { p.put(DIGIT_ROW, v); }
    boolean haptics() { return p.b(HAPTICS, true); }
    void setHaptics(boolean v) { p.put(HAPTICS, v); }
    boolean suggestions() { return p.b(SUGGEST, true); }
    void setSuggestions(boolean v) { p.put(SUGGEST, v); }
    boolean learn() { return p.b(LEARN, true); }
    void setLearn(boolean v) { p.put(LEARN, v); }

    /** The languages switched on, in table order; defaults to the device language (English otherwise). */
    List<Language> languages() {
        String stored = p.s(LANGS, null);
        List<Language> out = new ArrayList<>();
        if (stored != null) {
            for (String code : stored.split(",")) {
                Language l = Language.forCode(code.trim(), null);
                if (l != null && !out.contains(l)) out.add(l);
            }
        }
        if (out.isEmpty()) out.add(Language.forLocale(Locale.getDefault().toString()));
        List<Language> ordered = new ArrayList<>();
        for (Language l : Language.values()) if (out.contains(l)) ordered.add(l);
        return ordered;
    }

    /** Switches {@code l} on or off (the last language can't be switched off). */
    void setLanguage(Language l, boolean on) {
        List<Language> now = languages();
        if (on && !now.contains(l)) now.add(l);
        if (!on && now.size() > 1) now.remove(l);
        StringBuilder sb = new StringBuilder();
        for (Language x : now) {
            if (sb.length() > 0) sb.append(',');
            sb.append(x.code);
        }
        p.put(LANGS, sb.toString());
    }

    /** The language in use (one of {@link #languages()}). */
    Language current() {
        List<Language> on = languages();
        Language l = Language.forCode(p.s(LANG, null), on.get(0));
        return on.contains(l) ? l : on.get(0);
    }

    void setCurrent(Language l) { p.put(LANG, l.code); }
}
