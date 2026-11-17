package com.gungreeneyes.trekui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * App groups for the OPEN GROUP function (gap A6): named sets of up to {@link #MAX_APPS} apps that a
 * tile, slot, dock icon or gesture unfolds as a {@link GroupPanel}.
 *
 * <p>Groups are stored as one JSON array in the {@code "trekui"} preferences under {@link #KEY}. Every
 * key this feature writes starts with {@link #KEY_PREFIX} so a layout export (which copies the whole
 * preference file) carries the groups along. A tile points at a group by its stable {@link Group#id},
 * so renaming or reordering a group never breaks an assignment.
 *
 * <p>The encoding and the list edits are pure static functions (no Android types) so they are tested
 * on the JVM in {@code AppGroupsTest}.
 */
final class AppGroups {

    /** Prefix shared by every preference key of the app-group feature. */
    static final String KEY_PREFIX = "group.";
    /** Preference key holding the JSON array of groups. */
    static final String KEY = KEY_PREFIX + "list";
    /** Most apps one group holds (the panels lay out at most twelve keys). */
    static final int MAX_APPS = 12;
    /** Most groups the user can create. */
    static final int MAX_GROUPS = 24;
    /** Longest group name, in characters. */
    static final int MAX_NAME = 20;
    /** Name given to a group created without one. */
    static final String DEFAULT_NAME = "GROUP";

    private AppGroups() {}

    /** One app in a group: a flattened component name plus the profile's user serial (-1 = main profile). */
    static final class Member {
        final String comp;
        final long user;

        Member(String comp, long user) {
            this.comp = comp;
            this.user = user;
        }

        /** True when both point at the same app in the same profile. */
        boolean same(Member other) {
            return other != null && comp.equals(other.comp) && user == other.user;
        }
    }

    /** A named, ordered set of apps. */
    static final class Group {
        final String id;
        String name;
        final List<Member> apps = new ArrayList<>();

        Group(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    // ---- encoding ------------------------------------------------------------------------------

    /**
     * Decodes the stored JSON. Corrupt input gives an empty list; groups without an id, duplicate ids,
     * members without a component and duplicate members are dropped, and each group is capped at
     * {@link #MAX_APPS} apps, so a hand-edited or imported layout can never crash the home screen.
     */
    static List<Group> parse(String raw) {
        List<Group> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length() && out.size() < MAX_GROUPS; i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                if (id.isEmpty() || find(out, id) != null) continue;
                Group g = new Group(id, cleanName(o.optString("name", DEFAULT_NAME)));
                JSONArray apps = o.optJSONArray("apps");
                if (apps != null) {
                    for (int k = 0; k < apps.length(); k++) {
                        JSONObject a = apps.optJSONObject(k);
                        if (a == null) continue;
                        String comp = a.optString("comp", "");
                        if (comp.isEmpty()) continue;
                        addApp(g, new Member(comp, a.optLong("user", -1)));
                    }
                }
                out.add(g);
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return out;
    }

    /** Encodes groups as the JSON array {@link #parse} reads. */
    static String encode(List<Group> groups) {
        JSONArray array = new JSONArray();
        try {
            for (Group g : groups) {
                JSONObject o = new JSONObject();
                o.put("id", g.id);
                o.put("name", g.name);
                JSONArray apps = new JSONArray();
                for (Member m : g.apps) {
                    JSONObject a = new JSONObject();
                    a.put("comp", m.comp);
                    a.put("user", m.user);
                    apps.put(a);
                }
                o.put("apps", apps);
                array.put(o);
            }
        } catch (Exception ignored) {
            // JSONObject.put only throws for non-finite numbers, which never occur here
        }
        return array.toString();
    }

    // ---- persistence ---------------------------------------------------------------------------

    /** The stored groups (a fresh, editable copy). */
    static List<Group> load(Prefs prefs) {
        return parse(prefs.s(KEY, null));
    }

    /** Stores the groups and marks the home layout stale so tiles showing a group name refresh. */
    static void save(Prefs prefs, List<Group> groups) {
        prefs.put(KEY, encode(groups));
        prefs.bump();
    }

    // ---- pure edits ----------------------------------------------------------------------------

    /** The group with {@code id}, or null. */
    static Group find(List<Group> groups, String id) {
        if (id == null) return null;
        for (Group g : groups) if (g.id.equals(id)) return g;
        return null;
    }

    /** A new id not used by any group: "g1", "g2", … */
    static String newId(List<Group> groups) {
        int n = 1;
        while (find(groups, "g" + n) != null) n++;
        return "g" + n;
    }

    /** Upper-cases, trims and caps a group name; blank names become {@link #DEFAULT_NAME}. */
    static String cleanName(String name) {
        String n = name == null ? "" : name.trim().toUpperCase(Locale.getDefault());
        if (n.isEmpty()) return DEFAULT_NAME;
        return n.length() > MAX_NAME ? n.substring(0, MAX_NAME).trim() : n;
    }

    /**
     * Appends a new group named {@code name} and returns it, or null when {@link #MAX_GROUPS} exist.
     */
    static Group create(List<Group> groups, String name) {
        if (groups.size() >= MAX_GROUPS) return null;
        Group g = new Group(newId(groups), cleanName(name));
        groups.add(g);
        return g;
    }

    /** Index of {@code member} in the group, or -1. */
    static int indexOf(Group g, Member member) {
        for (int i = 0; i < g.apps.size(); i++) if (g.apps.get(i).same(member)) return i;
        return -1;
    }

    /** Adds an app at the end; false when it is already there or the group is full. */
    static boolean addApp(Group g, Member member) {
        if (indexOf(g, member) >= 0 || g.apps.size() >= MAX_APPS) return false;
        g.apps.add(member);
        return true;
    }

    /** Removes an app; false when it was not in the group. */
    static boolean removeApp(Group g, Member member) {
        int i = indexOf(g, member);
        if (i < 0) return false;
        g.apps.remove(i);
        return true;
    }

    /**
     * Moves the item at {@code from} one step ({@code delta} -1 = earlier, +1 = later). Returns false
     * (and changes nothing) at either end or for an invalid index.
     */
    static <T> boolean move(List<T> list, int from, int delta) {
        int to = from + delta;
        if (from < 0 || from >= list.size() || to < 0 || to >= list.size()) return false;
        Collections.swap(list, from, to);
        return true;
    }

    /** Removes the group with {@code id}; false when there was none. */
    static boolean delete(List<Group> groups, String id) {
        Group g = find(groups, id);
        return g != null && groups.remove(g);
    }
}
