package com.gungreeneyes.trekui.keyboard.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A built keyboard page: rows of {@link Key}s plus the mode and language it was built for. */
public final class KeyLayout {
    public final Mode mode;
    public final Language language;
    public final List<List<Key>> rows;

    public KeyLayout(Mode mode, Language language, List<List<Key>> rows) {
        this.mode = mode;
        this.language = language;
        List<List<Key>> copy = new ArrayList<>();
        for (List<Key> r : rows) copy.add(Collections.unmodifiableList(new ArrayList<>(r)));
        this.rows = Collections.unmodifiableList(copy);
    }

    /** Sum of the key weights of row {@code r}. */
    public float rowWeight(int r) {
        float w = 0f;
        for (Key k : rows.get(r)) w += k.weight;
        return w;
    }

    /** The widest row's weight (every row is laid out against this, so keys keep one width). */
    public float maxRowWeight() {
        float max = 0f;
        for (int r = 0; r < rows.size(); r++) max = Math.max(max, rowWeight(r));
        return max;
    }

    /** The first key of {@code kind}, or null. */
    public Key find(int kind) {
        for (List<Key> r : rows) for (Key k : r) if (k.kind == kind) return k;
        return null;
    }

    /** The first character key typing {@code text}, or null. */
    public Key findChar(String text) {
        for (List<Key> r : rows) for (Key k : r) if (k.kind == Key.CHAR && text.equals(k.text)) return k;
        return null;
    }

    /** Row {@code r}'s characters joined (function keys as '#'), for tests and debugging. */
    public String rowString(int r) {
        StringBuilder sb = new StringBuilder();
        for (Key k : rows.get(r)) sb.append(k.kind == Key.CHAR ? k.text : "#");
        return sb.toString();
    }
}
