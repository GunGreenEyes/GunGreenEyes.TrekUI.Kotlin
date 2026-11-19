package com.gungreeneyes.trekui.keyboard

import android.content.Context
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import com.gungreeneyes.trekui.KeyboardLook
import com.gungreeneyes.trekui.core.SuiteClient
import com.gungreeneyes.trekui.keyboard.logic.FieldRules
import com.gungreeneyes.trekui.keyboard.logic.Key
import com.gungreeneyes.trekui.keyboard.logic.Language
import com.gungreeneyes.trekui.keyboard.logic.Layouts
import com.gungreeneyes.trekui.keyboard.logic.Mode
import com.gungreeneyes.trekui.keyboard.logic.ShiftState
import com.gungreeneyes.trekui.keyboard.logic.Sizing
import com.gungreeneyes.trekui.keyboard.logic.Suggestions
import com.gungreeneyes.trekui.keyboard.logic.WordEdit
import com.gungreeneyes.trekui.licensing.Licensing
import java.text.BreakIterator
import java.util.Locale

/**
 * TrekUI Keyboard's input method service: owns the [KeyboardView], picks the page for each field
 * ([FieldRules]), types into the field through its [InputConnection], runs shift / caps lock
 * ([ShiftState]), the suggestion strip ([WordStore] + [Suggestions]) and language switching.
 *
 * Privacy: nothing is learned (and no completions are shown) in password fields or when the app asks
 * for `IME_FLAG_NO_PERSONALIZED_LEARNING`; the app has no network permission at all.
 *
 * Licensing never blocks typing: when the trial session is over the strip shows a slim UNLOCK chip that
 * opens :core's purchase screen. The settings activity runs the session itself.
 */
class TrekKeyboardService : InputMethodService() {
    private var view: KeyboardView? = null
    private lateinit var prefs: KeyboardPrefs
    private lateinit var words: WordStore
    private val shift = ShiftState()
    private val keys = Keys()

    private var language = Language.EN
    private var mode = Mode.LETTERS
    private var fieldMode = Mode.LETTERS
    private var fieldKind = FieldRules.FieldKind.NORMAL
    private var enter = FieldRules.Enter.NEWLINE
    private var inputType = 0
    private var noLearning = false
    private var suggest = false
    private var caps = false
    /** The voice keyboard to hand over to, or null when none is installed and enabled. */
    private var voiceIme: InputMethodInfo? = null
    private var voiceSubtype: InputMethodSubtype? = null

    override fun onCreate() {
        super.onCreate()
        prefs = KeyboardPrefs.get(this)
        words = WordStore.get(this)
        language = prefs.current
    }

    override fun onCreateInputView(): View {
        val v = KeyboardView(this, keys)
        view = v
        v.setShift(shift)
        applySizes()
        rebuildLayout()
        return v
    }

    /** The compact keyboard never switches to Android's full-screen extract editor. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (view != null) {
            applySizes()
            rebuildLayout()
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputType = info.inputType
        fieldMode = FieldRules.modeFor(inputType)
        fieldKind = FieldRules.fieldKind(inputType)
        enter = FieldRules.enterFor(inputType, info.imeOptions)
        noLearning = FieldRules.noLearning(inputType, info.imeOptions)
        suggest = prefs.suggestions && FieldRules.suggestionsAllowed(inputType, info.imeOptions)
        caps = FieldRules.wantsCaps(inputType)
        if (!restarting || !mode.textual || !fieldMode.textual) mode = fieldMode
        if (!restarting) shift.reset()
        language = prefs.current
        findVoiceIme()
        val v = view ?: return
        KeyboardTheme.refreshThrottled(this, ::onThemeChanged)
        KeyboardLook.load(this)
        v.themeChanged()
        applySizes()
        rebuildLayout()
        updateAutoCaps()
        if (suggest) words.refreshDictionary(language.code)
        if (!siblingsChecked) {
            siblingsChecked = true
            SuiteClient.checkSiblingsAsync(this, null)
        }
        updateStrip()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        view?.cancelTouch()
        words.flush()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateAutoCaps()
        updateStrip()
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        if (newSubtype == null) return
        val l = Language.forLocale(subtypeLocale(newSubtype))
        prefs.setLanguage(l, true)
        setLanguage(l)
    }

    // ---- building the page ---------------------------------------------------------------------------

    private fun applySizes() {
        val v = view ?: return
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val preset = if (land) prefs.sizeLandscape else prefs.size
        val metrics = resources.displayMetrics
        val rows = 4 + if (prefs.digitRow) 1 else 0
        val rowPx = Sizing.rowPx(preset, land, metrics.density, rows, metrics.heightPixels)
        v.setSizes(rowPx, prefs.oneHanded)
    }

    private fun rebuildLayout() {
        val v = view ?: return
        val o = Layouts.Options(
            globe = prefs.languages().size > 1 || otherImeAvailable(),
            digitRow = prefs.digitRow && mode == Mode.LETTERS,
            field = fieldKind,
        )
        v.setLayout(Layouts.build(mode, language, o), Locale.forLanguageTag(language.code))
        v.setEnterLabel(enterLabel())
        v.setSpaceLabel(spaceLabel())
        applySizes()
    }

    /** The enter key's label for the field's action, or null for the return-arrow glyph. */
    private fun enterLabel(): String? = when (enter) {
        FieldRules.Enter.GO -> getString(R.string.enter_go)
        FieldRules.Enter.SEARCH -> getString(R.string.enter_search)
        FieldRules.Enter.SEND -> getString(R.string.enter_send)
        FieldRules.Enter.NEXT -> getString(R.string.enter_next)
        FieldRules.Enter.DONE -> getString(R.string.enter_done)
        FieldRules.Enter.PREVIOUS -> getString(R.string.enter_previous)
        FieldRules.Enter.NEWLINE -> null
    }

    private fun spaceLabel(): String {
        if (mode != Mode.LETTERS || prefs.languages().size < 2) return ""
        return getString(
            when (language) {
                Language.DE -> R.string.lang_short_de
                Language.FR -> R.string.lang_short_fr
                Language.ES -> R.string.lang_short_es
                Language.EN -> R.string.lang_short_en
            },
        )
    }

    private fun onThemeChanged() {
        KeyboardLook.reloadSounds(this)
        view?.themeChanged()
    }

    private fun setMode(m: Mode) {
        if (m == mode) return
        mode = m
        if (m != Mode.LETTERS) shift.reset()
        rebuildLayout()
        updateAutoCaps()
    }

    private fun setLanguage(l: Language) {
        if (l == language) return
        language = l
        prefs.current = l
        if (suggest) words.refreshDictionary(l.code)
        rebuildLayout()
        updateStrip()
    }

    // ---- shift and strip --------------------------------------------------------------------------------

    private fun updateAutoCaps() {
        val v = view ?: return
        var want = false
        val ic = currentInputConnection
        if (caps && mode == Mode.LETTERS && ic != null) {
            val ei = currentInputEditorInfo
            want = ei != null && ic.getCursorCapsMode(ei.inputType) != 0
        }
        shift.setAuto(want)
        v.setShift(shift)
    }

    /** The word before the cursor, or "" (also when text is selected). */
    private fun currentWord(): String {
        val ic = currentInputConnection ?: return ""
        if (!ic.getSelectedText(0).isNullOrEmpty()) return ""
        return WordEdit.currentWord(ic.getTextBeforeCursor(48, 0))
    }

    private fun updateStrip() {
        val v = view ?: return
        var list = emptyList<String>()
        if (suggest && mode == Mode.LETTERS) {
            val w = currentWord()
            if (w.isNotEmpty()) {
                list = Suggestions.rank(w, if (prefs.learn) words.typed() else null, words.dictionary,
                    SUGGESTIONS, Locale.forLanguageTag(language.code))
            }
        }
        v.setStrip(list, unlockChip(), getString(R.string.unlock_chip), voiceIme != null)
    }

    /** The UNLOCK chip shows once the trial session is over (typing is never blocked). */
    private fun unlockChip(): Boolean = try {
        !Licensing.isUnlocked(this) && Licensing.trialRemainingMillis(this) <= 0
    } catch (e: RuntimeException) {
        false
    }

    /** Counts [word] as typed unless this field forbids learning. */
    private fun learn(word: String) {
        if (noLearning || !prefs.learn || !suggest) return
        if (Suggestions.learnable(word)) words.learn(word)
    }

    // ---- KeyboardView.Listener ----------------------------------------------------------------------------

    /** The view's touches, acted on by the service. */
    private inner class Keys : KeyboardView.Listener {
        override fun onPress(key: Key) {
            KeyboardLook.click(this@TrekKeyboardService)
            if (prefs.haptics) view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        override fun onText(text: String) {
            val ic = currentInputConnection ?: return
            val cp = text.codePointAt(0)
            if (text.codePointCount(0, text.length) == 1 && !WordEdit.wordChar(cp)) learn(currentWord())
            ic.commitText(text, 1)
            shift.typed()
            if (mode == Mode.SYMBOLS && text == "'") setMode(Mode.LETTERS)
            updateAutoCaps()
            updateStrip()
        }

        override fun onDelete() {
            val ic = currentInputConnection ?: return
            if (inputType == InputType.TYPE_NULL) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                return
            }
            if (ic.getSelectedText(0).isNullOrEmpty()) deleteLastGrapheme(ic) else ic.commitText("", 1)
        }

        /**
         * Deletes the whole last grapheme (a surrogate-pair emoji, or a multi-code-point one like ❤️),
         * not one UTF-16 unit or one code point.
         */
        private fun deleteLastGrapheme(ic: InputConnection) {
            val before = ic.getTextBeforeCursor(GRAPHEME_LOOKBACK, 0)?.toString().orEmpty()
            val grapheme = if (before.isEmpty()) 0 else {
                val chars = BreakIterator.getCharacterInstance()
                chars.setText(before)
                val start = chars.preceding(before.length)
                if (start == BreakIterator.DONE) 0 else before.length - start
            }
            if (grapheme > 0) {
                ic.deleteSurroundingText(grapheme, 0)
            } else if (Build.VERSION.SDK_INT >= 24) {
                ic.deleteSurroundingTextInCodePoints(1, 0)
            } else {
                ic.deleteSurroundingText(1, 0)
            }
        }

        override fun onDeleteWord() {
            val ic = currentInputConnection ?: return
            if (!ic.getSelectedText(0).isNullOrEmpty()) {
                ic.commitText("", 1)
                return
            }
            val n = WordEdit.deleteWordLength(ic.getTextBeforeCursor(64, 0))
            if (n > 0) ic.deleteSurroundingText(n, 0)
        }

        override fun onFunction(kind: Int) {
            when (kind) {
                Key.SHIFT -> {
                    shift.tap(SystemClock.uptimeMillis())
                    view?.setShift(shift)
                }
                Key.SPACE -> {
                    learn(currentWord())
                    commit(" ")
                }
                Key.ENTER -> {
                    learn(currentWord())
                    doEnter()
                }
                Key.TO_SYMBOLS -> setMode(Mode.SYMBOLS)
                Key.TO_SYMBOLS_2 -> setMode(Mode.SYMBOLS_2)
                Key.TO_LETTERS -> setMode(if (fieldMode.textual) Mode.LETTERS else fieldMode)
                Key.EMOJI -> setMode(Mode.EMOJI)
                Key.GLOBE -> nextLanguageOrKeyboard()
            }
        }

        override fun onLongFunction(kind: Int) {
            when (kind) {
                Key.SPACE -> {
                    val on = prefs.languages()
                    if (on.size > 1) setLanguage(Sizing.nextLanguage(language, on)) else showPicker()
                }
                Key.GLOBE -> showPicker()
                Key.EMOJI -> switchToOtherKeyboard()
            }
        }

        override fun onCursor(direction: Int) {
            sendDownUpKeyEvents(if (direction < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
        }

        override fun onSuggestion(word: String) {
            val ic = currentInputConnection ?: return
            val typed = currentWord()
            ic.beginBatchEdit()
            if (typed.isNotEmpty()) ic.deleteSurroundingText(typed.length, 0)
            ic.commitText("$word ", 1)
            ic.endBatchEdit()
            learn(word)
            shift.typed()
            updateAutoCaps()
            updateStrip()
        }

        override fun onUnlock() {
            requestHideSelf(0)
            Licensing.openPurchaseScreen(this@TrekKeyboardService)
        }

        override fun onVoice() {
            val ime = voiceIme ?: return
            switchTo(ime.id, voiceSubtype)
        }

        override fun onSwapHand() {
            prefs.oneHanded = if (prefs.oneHanded == Sizing.ONE_HANDED_LEFT) {
                Sizing.ONE_HANDED_RIGHT
            } else {
                Sizing.ONE_HANDED_LEFT
            }
            applySizes()
        }
    }

    // ---- actions -------------------------------------------------------------------------------------------

    private fun commit(s: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(s, 1)
        shift.typed()
        if (mode == Mode.SYMBOLS || mode == Mode.SYMBOLS_2) setMode(Mode.LETTERS)
        updateAutoCaps()
        updateStrip()
    }

    private fun doEnter() {
        val ic = currentInputConnection ?: return
        if (enter != FieldRules.Enter.NEWLINE) {
            ic.performEditorAction(FieldRules.actionId(enter))
            return
        }
        if (inputType == InputType.TYPE_NULL) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER) else ic.commitText("\n", 1)
        updateAutoCaps()
        updateStrip()
    }

    /** Globe: the next language switched on, or the next keyboard app when only one language is on. */
    private fun nextLanguageOrKeyboard() {
        val on = prefs.languages()
        if (on.size > 1) {
            setLanguage(Sizing.nextLanguage(language, on))
            return
        }
        if (Build.VERSION.SDK_INT >= 28) {
            if (!switchToNextInputMethod(false)) showPicker()
        } else {
            val imm = imm()
            val token = token()
            if (imm == null || token == null || !legacyNext(imm, token)) showPicker()
        }
    }

    private fun showPicker() {
        imm()?.showInputMethodPicker()
    }

    /** Emoji long press: hand over to another enabled keyboard (for its full emoji picker). */
    private fun switchToOtherKeyboard() {
        val other = otherIme()
        if (other == null) showPicker() else switchTo(other.id, null)
    }

    @Suppress("DEPRECATION")
    private fun switchTo(id: String, subtype: InputMethodSubtype?) {
        if (Build.VERSION.SDK_INT >= 28) {
            if (subtype != null) switchInputMethod(id, subtype) else switchInputMethod(id)
            return
        }
        val imm = imm() ?: return
        val token = token() ?: return
        if (subtype != null) imm.setInputMethodAndSubtype(token, id, subtype) else imm.setInputMethod(token, id)
    }

    private fun imm(): InputMethodManager? = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?

    private fun token(): IBinder? = window?.window?.attributes?.token

    private fun otherImeAvailable(): Boolean = otherIme() != null

    /** The first enabled keyboard app that is not this one (a full keyboard, not a voice-only one). */
    private fun otherIme(): InputMethodInfo? {
        val imm = imm() ?: return null
        return try {
            imm.enabledInputMethodList.firstOrNull { info ->
                packageName != info.packageName && (hasMode(imm, info, "keyboard") || info.subtypeCount == 0)
            }
        } catch (ignored: RuntimeException) {
            null // IMM unavailable
        }
    }

    /** Looks for an enabled voice keyboard (a subtype in "voice" mode): the strip's voice key uses it. */
    private fun findVoiceIme() {
        voiceIme = null
        voiceSubtype = null
        val imm = imm() ?: return
        try {
            for (info in imm.enabledInputMethodList) {
                if (packageName == info.packageName) continue
                val voice = imm.getEnabledInputMethodSubtypeList(info, true).firstOrNull { it.mode == "voice" }
                if (voice != null) {
                    voiceIme = info
                    voiceSubtype = voice
                    return
                }
            }
        } catch (ignored: RuntimeException) {
            // IMM unavailable: no voice key
        }
    }

    private companion object {
        /** Completions shown in the strip. */
        const val SUGGESTIONS = 3

        /** Chars read before the cursor to find the last grapheme (longer than any emoji sequence). */
        const val GRAPHEME_LOOKBACK = 32

        /** A sibling-unlock check from the keyboard runs at most once per process. */
        var siblingsChecked = false

        @Suppress("DEPRECATION")
        fun subtypeLocale(s: InputMethodSubtype): String =
            if (Build.VERSION.SDK_INT >= 24 && !s.languageTag.isNullOrEmpty()) s.languageTag else s.locale

        @Suppress("DEPRECATION")
        fun legacyNext(imm: InputMethodManager, token: IBinder): Boolean = imm.switchToNextInputMethod(token, false)

        fun hasMode(imm: InputMethodManager, info: InputMethodInfo, mode: String): Boolean =
            imm.getEnabledInputMethodSubtypeList(info, true).any { it.mode == mode }
    }
}
