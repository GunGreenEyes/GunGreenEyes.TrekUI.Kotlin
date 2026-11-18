package com.gungreeneyes.trekui.keyboard.logic

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * What a text field asks for, read from its [EditorInfo.inputType] and [EditorInfo.imeOptions]:
 * which page to show, which extra key the bottom row needs (@ for e-mail, / for web addresses), what the
 * enter key does and is labelled, and the privacy rules (no learning and no suggestions in password and
 * incognito fields). Pure functions over ints (the Android constants are compile-time values), unit-tested.
 */
internal object FieldRules {
    /** Field kinds that change the bottom row. */
    enum class FieldKind { NORMAL, EMAIL, URL }

    /** Enter key behaviours: newline, or one of the editor actions. */
    enum class Enter { NEWLINE, GO, SEARCH, SEND, NEXT, DONE, PREVIOUS }

    /**
     * [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING] (API 26). Repeated here so the rule reads the
     * same on every API level and in JVM tests.
     */
    const val FLAG_NO_PERSONALIZED_LEARNING = 0x1000000

    /** The page a field opens on: numbers and dates get the number pad, phone fields the dial pad. */
    fun modeFor(inputType: Int): Mode = when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> Mode.NUMERIC
        InputType.TYPE_CLASS_PHONE -> Mode.PHONE
        else -> Mode.LETTERS
    }

    /** E-mail and web-address fields put @ or / next to the space bar instead of the comma. */
    fun fieldKind(inputType: Int): FieldKind {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return FieldKind.NORMAL
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.EMAIL
            InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URL
            else -> FieldKind.NORMAL
        }
    }

    /** True for password fields of any class (text, visible, web, numeric PIN). */
    fun isPassword(inputType: Int): Boolean {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (cls) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /**
     * The incognito rule: nothing typed in this field may be remembered. True for passwords and whenever
     * the app sets `IME_FLAG_NO_PERSONALIZED_LEARNING` (incognito tabs, private chats).
     */
    fun noLearning(inputType: Int, imeOptions: Int): Boolean =
        isPassword(inputType) || imeOptions and FLAG_NO_PERSONALIZED_LEARNING != 0

    /**
     * True if the suggestion strip may show word completions: text fields that are not passwords, not
     * incognito, and did not ask for `TYPE_TEXT_FLAG_NO_SUGGESTIONS`, and are not e-mail / web
     * addresses (completions there are noise).
     */
    fun suggestionsAllowed(inputType: Int, imeOptions: Int): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        if (noLearning(inputType, imeOptions)) return false
        if (inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return false
        return fieldKind(inputType) == FieldKind.NORMAL
    }

    /** True if the field wants automatic capitals (sentences, words or all). */
    fun wantsCaps(inputType: Int): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        if (isPassword(inputType) || fieldKind(inputType) != FieldKind.NORMAL) return false
        val capsFlags = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS or
            InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        return inputType and capsFlags != 0
    }

    /**
     * What the enter key does: the field's editor action, or a newline when the field has none, asked for
     * `IME_FLAG_NO_ENTER_ACTION`, or is multi-line without an explicit action.
     */
    fun enterFor(inputType: Int, imeOptions: Int): Enter {
        if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return Enter.NEWLINE
        return when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> Enter.GO
            EditorInfo.IME_ACTION_SEARCH -> Enter.SEARCH
            EditorInfo.IME_ACTION_SEND -> Enter.SEND
            EditorInfo.IME_ACTION_NEXT -> Enter.NEXT
            EditorInfo.IME_ACTION_DONE -> Enter.DONE
            EditorInfo.IME_ACTION_PREVIOUS -> Enter.PREVIOUS
            else -> Enter.NEWLINE
        }
    }

    /** The `EditorInfo.IME_ACTION_*` value for [e] (NEWLINE: `IME_ACTION_NONE`). */
    fun actionId(e: Enter): Int = when (e) {
        Enter.GO -> EditorInfo.IME_ACTION_GO
        Enter.SEARCH -> EditorInfo.IME_ACTION_SEARCH
        Enter.SEND -> EditorInfo.IME_ACTION_SEND
        Enter.NEXT -> EditorInfo.IME_ACTION_NEXT
        Enter.DONE -> EditorInfo.IME_ACTION_DONE
        Enter.PREVIOUS -> EditorInfo.IME_ACTION_PREVIOUS
        Enter.NEWLINE -> EditorInfo.IME_ACTION_NONE
    }
}
