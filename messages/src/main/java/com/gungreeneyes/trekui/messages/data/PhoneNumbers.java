package com.gungreeneyes.trekui.messages.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure address handling for SMS/MMS (no Android dependencies, unit-tested): normalising what users type
 * or carriers deliver into one canonical form, a loose comparison key that treats "+1 (555) 010-2345",
 * "15550102345" and "555-010-2345" as the same person, recipient-list splitting for {@code smsto:} URIs
 * and a stable key for a set of recipients (drafts, grouping).
 *
 * <p>Three kinds of address exist: phone numbers (digits, optional leading {@code +}), e-mail
 * addresses (some carriers deliver e-mail as SMS) and alphanumeric sender ids ("BANK", "AMAZON").
 * Only phone numbers are reduced to digits; the others keep their text, case-folded.
 */
public final class PhoneNumbers {
    /** Trailing digits compared by {@link #key}: national numbers match regardless of country code. */
    public static final int KEY_DIGITS = 10;
    /** Numbers at most this long are short codes and are compared whole. */
    public static final int SHORT_CODE_MAX = 6;

    private PhoneNumbers() {}

    /**
     * Canonical form: whitespace and dialling punctuation removed, an MMS {@code /TYPE=PLMN} suffix and
     * a {@code tel:} prefix dropped, a leading international {@code 00} turned into {@code +}.
     * E-mail addresses are lower-cased, alphanumeric sender ids upper-cased. Null or blank gives "".
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int type = s.toUpperCase(Locale.ROOT).indexOf("/TYPE=");
        if (type >= 0) s = s.substring(0, type);
        if (s.regionMatches(true, 0, "tel:", 0, 4)) s = s.substring(4);
        if (s.isEmpty()) return "";
        if (s.indexOf('@') >= 0) return s.toLowerCase(Locale.ROOT);
        if (!isPhoneLike(s)) return s.toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') out.append(ch);
            else if (ch == '+' && out.length() == 0) out.append('+');
            else if (ch == '*' || ch == '#') out.append(ch); // USSD-like short codes keep their marks
        }
        String n = out.toString();
        if (n.startsWith("00") && n.length() > 4) n = "+" + n.substring(2);
        return n;
    }

    /**
     * True if {@code s} looks like a dialable number: only digits, spaces and {@code + - . ( ) / * #},
     * with at least one digit.
     */
    public static boolean isPhoneLike(String s) {
        if (s == null) return false;
        boolean digit = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') digit = true;
            else if (" +-.()/*# ".indexOf(ch) < 0) return false;
        }
        return digit;
    }

    /**
     * Loose comparison key: the last {@link #KEY_DIGITS} digits of a phone number (short codes whole),
     * or the normalised text of an e-mail / alphanumeric sender.
     */
    public static String key(String raw) {
        String n = normalize(raw);
        if (n.isEmpty() || !isPhoneLike(n)) return n;
        StringBuilder digits = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            if (ch >= '0' && ch <= '9') digits.append(ch);
        }
        String d = digits.toString();
        if (d.length() <= SHORT_CODE_MAX) return d;
        return d.length() > KEY_DIGITS ? d.substring(d.length() - KEY_DIGITS) : d;
    }

    /** True if both addresses reach the same person ({@link #key} equal and not empty). */
    public static boolean same(String a, String b) {
        String ka = key(a);
        return !ka.isEmpty() && ka.equals(key(b));
    }

    /**
     * Splits a recipient list ("555 1234, 555 9876; bob@example.com") into normalised, de-duplicated
     * addresses in their original order. Separators are comma and semicolon.
     */
    public static List<String> splitRecipients(String list) {
        List<String> out = new ArrayList<>();
        if (list == null) return out;
        Set<String> seen = new TreeSet<>();
        for (String part : list.split("[,;]")) {
            String n = normalize(part);
            if (!n.isEmpty() && seen.add(key(n))) out.add(n);
        }
        return out;
    }

    /**
     * The recipients of an {@code sms:}, {@code smsto:}, {@code mms:} or {@code mmsto:} URI's scheme-specific
     * part (already URL-decoded by the caller), without any {@code ?body=} query.
     */
    public static List<String> fromSchemePart(String schemeSpecificPart) {
        if (schemeSpecificPart == null) return new ArrayList<>();
        String s = schemeSpecificPart;
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        if (s.startsWith("//")) s = s.substring(2);
        return splitRecipients(s);
    }

    /**
     * A stable key for a set of recipients, independent of order and formatting: the sorted
     * {@link #key}s joined by {@code |}. Used for drafts and to match a typed recipient list to an
     * existing conversation.
     */
    public static String setKey(Collection<String> addresses) {
        Set<String> keys = new TreeSet<>();
        if (addresses != null) for (String a : addresses) {
            String k = key(a);
            if (!k.isEmpty()) keys.add(k);
        }
        return String.join("|", keys);
    }

    /** Initials for an avatar block: first letters of up to two words, or the last two digits. */
    public static String initials(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) return "#";
        String s = displayName.trim();
        if (isPhoneLike(s)) {
            String k = key(s);
            return k.length() <= 2 ? k : k.substring(k.length() - 2);
        }
        StringBuilder out = new StringBuilder(2);
        for (String w : s.split("\\s+")) {
            if (w.isEmpty()) continue;
            out.appendCodePoint(Character.toUpperCase(w.codePointAt(0)));
            if (out.length() >= 2) break;
        }
        return out.length() == 0 ? "#" : out.toString();
    }
}
