package com.gungreeneyes.trekui;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Choosing a sound in two steps: first a group (LAUNCHER, COMPUTER, CONSOLE, COMMS, ALERTS,
 * WEAPONS, WARP, TRANSPORTER, SCANNER, …, IMPORTED, each with its count), then the sound. The
 * group holding the current choice is marked ●, and so is the choice itself. Fixed options a caller
 * needs (DEFAULT, SILENT, IMPORT…) sit above the groups.
 */
final class SoundPicker {
    interface Picked {
        /** value: a clip id, or one of the caller's own top-option values. */
        void picked(String value);
    }

    static final class Group {
        final String name;
        final List<SoundLibrary.Entry> items = new ArrayList<>();

        Group(String name) { this.name = name; }
    }

    private SoundPicker() {}

    /** True for ids whose label carries a "GROUP: " prefix (source-library and synthesized clips). */
    private static boolean grouped(String id) { return id.startsWith("tk_") || Sfx.isSynth(id); }

    /** "WEAPONS: BEAM 1" → group WEAPONS, name BEAM 1. Older bundled clips are LAUNCHER; imports are IMPORTED. */
    static String groupOf(SoundLibrary.Entry e) {
        if (e.imported) return "IMPORTED";
        if (grouped(e.id)) {
            int k = e.label.indexOf(": ");
            if (k > 0) return e.label.substring(0, k);
        }
        return "LAUNCHER";
    }

    /** The sound's name inside its group (the group prefix removed). */
    static String nameOf(SoundLibrary.Entry e) {
        if (grouped(e.id)) {
            int k = e.label.indexOf(": ");
            if (k > 0) return e.label.substring(k + 2);
        }
        return e.label;
    }

    static List<Group> groups(Context c) {
        Map<String, Group> m = new LinkedHashMap<>();
        m.put("LAUNCHER", new Group("LAUNCHER"));
        for (SoundLibrary.Entry e : SoundLibrary.all(c)) {
            String g = groupOf(e);
            Group gr = m.get(g);
            if (gr == null) { gr = new Group(g); m.put(g, gr); }
            gr.items.add(e);
        }
        Group imp = m.remove("IMPORTED");
        List<Group> out = new ArrayList<>();
        for (Group g : m.values()) if (!g.items.isEmpty()) out.add(g);
        if (imp != null && !imp.items.isEmpty()) out.add(imp);
        return out;
    }

    /**
     * @param topLabels  fixed options shown above the groups (may be empty)
     * @param topValues  what each fixed option returns
     * @param current    the current clip id (marked ●), or null
     * @param defaultId  a clip to tag "(DEFAULT)", or null
     */
    static void pick(final Context c, final String title, final String[] topLabels, final String[] topValues,
                     final String current, final String defaultId, final Picked cb) {
        final List<Group> groups = groups(c);
        List<String> labels = new ArrayList<>();
        for (String s : topLabels) labels.add(s);
        String curGroup = null;
        if (current != null) {
            for (Group g : groups) for (SoundLibrary.Entry e : g.items) if (e.id.equals(current)) curGroup = g.name;
        }
        for (Group g : groups) {
            labels.add((g.name.equals(curGroup) ? "● " : "") + g.name + " (" + g.items.size() + ")  ›");
        }
        ConsoleDialog.options(c, title, labels.toArray(new String[0]), new ConsoleDialog.OnPick() {
            @Override public void pick(int k) {
                if (k < topLabels.length) { cb.picked(topValues[k]); return; }
                showGroup(c, title, groups.get(k - topLabels.length), topLabels, topValues, current, defaultId, cb);
            }
        });
    }

    private static void showGroup(final Context c, final String title, final Group g, final String[] topLabels, final String[] topValues,
                                  final String current, final String defaultId, final Picked cb) {
        List<String> labels = new ArrayList<>();
        labels.add("‹ ALL GROUPS");
        for (SoundLibrary.Entry e : g.items) {
            labels.add((e.id.equals(current) ? "● " : "") + nameOf(e) + (e.id.equals(defaultId) ? " (DEFAULT)" : ""));
        }
        ConsoleDialog.options(c, title + " • " + g.name, labels.toArray(new String[0]), new ConsoleDialog.OnPick() {
            @Override public void pick(int k) {
                if (k == 0) { SoundPicker.pick(c, title, topLabels, topValues, current, defaultId, cb); return; }
                cb.picked(g.items.get(k - 1).id);
            }
        });
    }
}
