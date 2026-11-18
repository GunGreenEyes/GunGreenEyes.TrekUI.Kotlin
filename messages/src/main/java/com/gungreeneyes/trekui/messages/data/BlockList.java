package com.gungreeneyes.trekui.messages.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Pure block-list matching (unit-tested). Entries come from the system's BlockedNumberContract (exact
 * numbers, shared with the dialer) plus this app's own prefix rules, written with a trailing {@code *}
 * ("+1900*" blocks every number starting +1900). Alphanumeric senders and e-mail addresses match by
 * their normalised text; a {@code *} rule on text matches its prefix.
 */
public final class BlockList {
    private final List<String> exact = new ArrayList<>();
    private final List<String> prefixes = new ArrayList<>();

    public BlockList(Collection<String> entries) {
        if (entries == null) return;
        for (String e : entries) {
            if (e == null) continue;
            String t = e.trim();
            if (t.endsWith("*")) {
                String p = PhoneNumbers.normalize(t.substring(0, t.length() - 1));
                if (!p.isEmpty()) prefixes.add(p);
            } else {
                String k = PhoneNumbers.key(t);
                if (!k.isEmpty()) exact.add(k);
            }
        }
    }

    /** True if {@code address} is blocked by an exact entry or a prefix rule. */
    public boolean matches(String address) {
        String key = PhoneNumbers.key(address);
        if (key.isEmpty()) return false;
        if (exact.contains(key)) return true;
        String n = PhoneNumbers.normalize(address);
        for (String p : prefixes) {
            if (n.startsWith(p)) return true;
            // "+1900*" also catches the same number delivered without the country prefix's plus
            if (p.startsWith("+") && n.startsWith(p.substring(1))) return true;
        }
        return false;
    }

    /** True if {@code rule} is a valid prefix rule (non-empty text before a trailing {@code *}). */
    public static boolean isPrefixRule(String rule) {
        return rule != null && rule.trim().endsWith("*")
                && !PhoneNumbers.normalize(rule.trim().substring(0, rule.trim().length() - 1)).isEmpty();
    }

    /** Number of rules (exact + prefix). */
    public int size() {
        return exact.size() + prefixes.size();
    }

    /** The prefix rules, normalised (for display). */
    public List<String> prefixRules() {
        return Collections.unmodifiableList(prefixes);
    }
}
