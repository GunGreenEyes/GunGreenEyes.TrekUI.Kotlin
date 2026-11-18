package com.gungreeneyes.trekui.keyboard.logic;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

/**
 * What a text field asks for, read from its {@link EditorInfo#inputType} and {@link EditorInfo#imeOptions}:
 * which page to show, which extra key the bottom row needs (@ for e-mail, / for web addresses), what the
 * enter key does and is labelled, and the privacy rules (no learning and no suggestions in password and
 * incognito fields). Pure functions over ints (the Android constants are compile-time values), unit-tested.
 */
public final class FieldRules {
    private FieldRules() {}

    /** Field kinds that change the bottom row. */
    public enum FieldKind { NORMAL, EMAIL, URL }

    /** Enter key behaviours: newline, or one of the editor actions. */
    public enum Enter { NEWLINE, GO, SEARCH, SEND, NEXT, DONE, PREVIOUS }

    /**
     * {@link EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING} (API 26). Repeated here so the rule reads the
     * same on every API level and in JVM tests.
     */
    static final int FLAG_NO_PERSONALIZED_LEARNING = 0x1000000;

    /** The page a field opens on: numbers and dates get the number pad, phone fields the dial pad. */
    public static Mode modeFor(int inputType) {
        switch (inputType & InputType.TYPE_MASK_CLASS) {
            case InputType.TYPE_CLASS_NUMBER:
            case InputType.TYPE_CLASS_DATETIME:
                return Mode.NUMERIC;
            case InputType.TYPE_CLASS_PHONE:
                return Mode.PHONE;
            default:
                return Mode.LETTERS;
        }
    }

    /** E-mail and web-address fields put @ or / next to the space bar instead of the comma. */
    public static FieldKind fieldKind(int inputType) {
        if ((inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return FieldKind.NORMAL;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) return FieldKind.EMAIL;
        if (variation == InputType.TYPE_TEXT_VARIATION_URI) return FieldKind.URL;
        return FieldKind.NORMAL;
    }

    /** True for password fields of any class (text, visible, web, numeric PIN). */
    public static boolean isPassword(int inputType) {
        int cls = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        if (cls == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        }
        return cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    /**
     * The incognito rule: nothing typed in this field may be remembered. True for passwords and whenever
     * the app sets {@code IME_FLAG_NO_PERSONALIZED_LEARNING} (incognito tabs, private chats).
     */
    public static boolean noLearning(int inputType, int imeOptions) {
        return isPassword(inputType) || (imeOptions & FLAG_NO_PERSONALIZED_LEARNING) != 0;
    }

    /**
     * True if the suggestion strip may show word completions: text fields that are not passwords, not
     * incognito, and did not ask for {@code TYPE_TEXT_FLAG_NO_SUGGESTIONS}, and are not e-mail / web
     * addresses (completions there are noise).
     */
    public static boolean suggestionsAllowed(int inputType, int imeOptions) {
        if ((inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false;
        if (noLearning(inputType, imeOptions)) return false;
        if ((inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return false;
        return fieldKind(inputType) == FieldKind.NORMAL;
    }

    /** True if the field wants automatic capitals (sentences, words or all). */
    public static boolean wantsCaps(int inputType) {
        if ((inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false;
        if (isPassword(inputType) || fieldKind(inputType) != FieldKind.NORMAL) return false;
        return (inputType & (InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_CAP_WORDS
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)) != 0;
    }

    /**
     * What the enter key does: the field's editor action, or a newline when the field has none, asked for
     * {@code IME_FLAG_NO_ENTER_ACTION}, or is multi-line without an explicit action.
     */
    public static Enter enterFor(int inputType, int imeOptions) {
        if ((imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return Enter.NEWLINE;
        switch (imeOptions & EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_GO: return Enter.GO;
            case EditorInfo.IME_ACTION_SEARCH: return Enter.SEARCH;
            case EditorInfo.IME_ACTION_SEND: return Enter.SEND;
            case EditorInfo.IME_ACTION_NEXT: return Enter.NEXT;
            case EditorInfo.IME_ACTION_DONE: return Enter.DONE;
            case EditorInfo.IME_ACTION_PREVIOUS: return Enter.PREVIOUS;
            default: return Enter.NEWLINE;
        }
    }

    /** The {@code EditorInfo.IME_ACTION_*} value for {@code e} (NEWLINE: {@code IME_ACTION_NONE}). */
    public static int actionId(Enter e) {
        switch (e) {
            case GO: return EditorInfo.IME_ACTION_GO;
            case SEARCH: return EditorInfo.IME_ACTION_SEARCH;
            case SEND: return EditorInfo.IME_ACTION_SEND;
            case NEXT: return EditorInfo.IME_ACTION_NEXT;
            case DONE: return EditorInfo.IME_ACTION_DONE;
            case PREVIOUS: return EditorInfo.IME_ACTION_PREVIOUS;
            default: return EditorInfo.IME_ACTION_NONE;
        }
    }
}
