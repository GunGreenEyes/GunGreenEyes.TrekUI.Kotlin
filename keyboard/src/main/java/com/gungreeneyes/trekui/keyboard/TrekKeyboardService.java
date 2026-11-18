package com.gungreeneyes.trekui.keyboard;

import android.content.Context;
import android.content.res.Configuration;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.IBinder;
import android.text.InputType;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import com.gungreeneyes.trekui.KeyboardLook;
import com.gungreeneyes.trekui.core.SuiteClient;
import com.gungreeneyes.trekui.keyboard.logic.FieldRules;
import com.gungreeneyes.trekui.keyboard.logic.Key;
import com.gungreeneyes.trekui.keyboard.logic.KeyLayout;
import com.gungreeneyes.trekui.keyboard.logic.Language;
import com.gungreeneyes.trekui.keyboard.logic.Layouts;
import com.gungreeneyes.trekui.keyboard.logic.Mode;
import com.gungreeneyes.trekui.keyboard.logic.ShiftState;
import com.gungreeneyes.trekui.keyboard.logic.Sizing;
import com.gungreeneyes.trekui.keyboard.logic.Suggestions;
import com.gungreeneyes.trekui.keyboard.logic.WordEdit;
import com.gungreeneyes.trekui.licensing.Licensing;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * TrekUI Keyboard's input method service: owns the {@link KeyboardView}, picks the page for each field
 * ({@link FieldRules}), types into the field through its {@link InputConnection}, runs shift / caps lock
 * ({@link ShiftState}), the suggestion strip ({@link WordStore} + {@link Suggestions}) and language
 * switching.
 *
 * <p>Privacy: nothing is learned (and no completions are shown) in password fields or when the app asks
 * for {@code IME_FLAG_NO_PERSONALIZED_LEARNING}; the app has no network permission at all.
 *
 * <p>Licensing never blocks typing: when the trial session is over the strip shows a slim UNLOCK chip that
 * opens :core's purchase screen. The settings activity runs the session itself.
 */
public final class TrekKeyboardService extends InputMethodService implements KeyboardView.Listener {
    /** Completions shown in the strip. */
    private static final int SUGGESTIONS = 3;
    /** A sibling-unlock check from the keyboard runs at most once per process. */
    private static boolean siblingsChecked;

    private KeyboardView view;
    private KeyboardPrefs prefs;
    private WordStore words;
    private final ShiftState shift = new ShiftState();

    private Language language = Language.EN;
    private Mode mode = Mode.LETTERS;
    private Mode fieldMode = Mode.LETTERS;
    private FieldRules.FieldKind fieldKind = FieldRules.FieldKind.NORMAL;
    private FieldRules.Enter enter = FieldRules.Enter.NEWLINE;
    private int inputType;
    private boolean noLearning, suggest, caps;
    /** The voice keyboard to hand over to, or null when none is installed and enabled. */
    private InputMethodInfo voiceIme;
    private InputMethodSubtype voiceSubtype;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = KeyboardPrefs.get(this);
        words = WordStore.get(this);
        language = prefs.current();
    }

    @Override
    public View onCreateInputView() {
        view = new KeyboardView(this, this);
        view.setShift(shift);
        applySizes();
        rebuildLayout();
        return view;
    }

    /** The compact keyboard never switches to Android's full-screen extract editor. */
    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (view != null) {
            applySizes();
            rebuildLayout();
        }
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        inputType = info.inputType;
        fieldMode = FieldRules.modeFor(inputType);
        fieldKind = FieldRules.fieldKind(inputType);
        enter = FieldRules.enterFor(inputType, info.imeOptions);
        noLearning = FieldRules.noLearning(inputType, info.imeOptions);
        suggest = prefs.suggestions() && FieldRules.suggestionsAllowed(inputType, info.imeOptions);
        caps = FieldRules.wantsCaps(inputType);
        if (!restarting || !mode.textual() || !fieldMode.textual()) mode = fieldMode;
        if (!restarting) shift.reset();
        language = prefs.current();
        findVoiceIme();
        if (view == null) return;
        KeyboardTheme.refreshThrottled(this, this::onThemeChanged);
        KeyboardLook.load(this);
        view.themeChanged();
        applySizes();
        rebuildLayout();
        updateAutoCaps();
        if (suggest) words.refreshDictionary(language.code);
        if (!siblingsChecked) {
            siblingsChecked = true;
            SuiteClient.checkSiblingsAsync(this, null);
        }
        updateStrip();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        if (view != null) view.cancelTouch();
        words.flush();
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        updateAutoCaps();
        updateStrip();
    }

    @Override
    protected void onCurrentInputMethodSubtypeChanged(InputMethodSubtype subtype) {
        super.onCurrentInputMethodSubtypeChanged(subtype);
        if (subtype == null) return;
        Language l = Language.forLocale(subtypeLocale(subtype));
        prefs.setLanguage(l, true);
        setLanguage(l);
    }

    @SuppressWarnings("deprecation")
    private static String subtypeLocale(InputMethodSubtype s) {
        if (Build.VERSION.SDK_INT >= 24 && !TextUtils.isEmpty(s.getLanguageTag())) return s.getLanguageTag();
        return s.getLocale();
    }

    // ---- building the page ---------------------------------------------------------------------------

    private void applySizes() {
        if (view == null) return;
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int preset = land ? prefs.sizeLandscape() : prefs.size();
        float density = getResources().getDisplayMetrics().density;
        int rows = 4 + (prefs.digitRow() ? 1 : 0);
        int rowPx = Sizing.rowPx(preset, land, density, rows, getResources().getDisplayMetrics().heightPixels);
        view.setSizes(rowPx, prefs.oneHanded());
    }

    private void rebuildLayout() {
        if (view == null) return;
        Layouts.Options o = new Layouts.Options();
        o.globe = prefs.languages().size() > 1 || otherImeAvailable();
        o.digitRow = prefs.digitRow() && mode == Mode.LETTERS;
        o.field = fieldKind;
        KeyLayout layout = Layouts.build(mode, language, o);
        view.setLayout(layout, Locale.forLanguageTag(language.code));
        view.setEnterLabel(enterLabel());
        view.setSpaceLabel(spaceLabel());
        applySizes();
    }

    private String enterLabel() {
        switch (enter) {
            case GO: return getString(R.string.enter_go);
            case SEARCH: return getString(R.string.enter_search);
            case SEND: return getString(R.string.enter_send);
            case NEXT: return getString(R.string.enter_next);
            case DONE: return getString(R.string.enter_done);
            case PREVIOUS: return getString(R.string.enter_previous);
            default: return null; // return-arrow glyph
        }
    }

    private String spaceLabel() {
        if (mode != Mode.LETTERS || prefs.languages().size() < 2) return "";
        switch (language) {
            case DE: return getString(R.string.lang_short_de);
            case FR: return getString(R.string.lang_short_fr);
            case ES: return getString(R.string.lang_short_es);
            default: return getString(R.string.lang_short_en);
        }
    }

    private void onThemeChanged() {
        KeyboardLook.reloadSounds(this);
        if (view != null) view.themeChanged();
    }

    private void setMode(Mode m) {
        if (m == mode) return;
        mode = m;
        if (m != Mode.LETTERS) shift.reset();
        rebuildLayout();
        updateAutoCaps();
    }

    private void setLanguage(Language l) {
        if (l == language) return;
        language = l;
        prefs.setCurrent(l);
        if (suggest) words.refreshDictionary(l.code);
        rebuildLayout();
        updateStrip();
    }

    // ---- shift and strip --------------------------------------------------------------------------------

    private void updateAutoCaps() {
        if (view == null) return;
        boolean want = false;
        InputConnection ic = getCurrentInputConnection();
        if (caps && mode == Mode.LETTERS && ic != null) {
            EditorInfo ei = getCurrentInputEditorInfo();
            want = ei != null && ic.getCursorCapsMode(ei.inputType) != 0;
        }
        shift.setAuto(want);
        view.setShift(shift);
    }

    /** The word before the cursor, or "" (also when text is selected). */
    private String currentWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return "";
        CharSequence sel = ic.getSelectedText(0);
        if (!TextUtils.isEmpty(sel)) return "";
        return WordEdit.currentWord(ic.getTextBeforeCursor(48, 0));
    }

    private void updateStrip() {
        if (view == null) return;
        List<String> list = Collections.emptyList();
        if (suggest && mode == Mode.LETTERS) {
            String w = currentWord();
            if (!w.isEmpty()) {
                list = Suggestions.rank(w, prefs.learn() ? words.typed() : null, words.dictionary(),
                        SUGGESTIONS, Locale.forLanguageTag(language.code));
            }
        }
        view.setStrip(list, unlockChip(), getString(R.string.unlock_chip), voiceIme != null);
    }

    /** The UNLOCK chip shows once the trial session is over (typing is never blocked). */
    private boolean unlockChip() {
        try {
            return !Licensing.isUnlocked(this) && Licensing.trialRemainingMillis(this) <= 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Counts {@code word} as typed unless this field forbids learning. */
    private void learn(String word) {
        if (noLearning || !prefs.learn() || !suggest) return;
        if (Suggestions.learnable(word)) words.learn(word);
    }

    // ---- KeyboardView.Listener ----------------------------------------------------------------------------

    @Override
    public void onPress(Key key) {
        KeyboardLook.click(this);
        if (prefs.haptics() && view != null) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    @Override
    public void onText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        int cp = text.codePointAt(0);
        if (text.codePointCount(0, text.length()) == 1 && !WordEdit.wordChar(cp)) learn(currentWord());
        ic.commitText(text, 1);
        shift.typed();
        if (mode == Mode.SYMBOLS && "'".equals(text)) setMode(Mode.LETTERS);
        updateAutoCaps();
        updateStrip();
    }

    @Override
    public void onDelete() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (inputType == InputType.TYPE_NULL) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            return;
        }
        CharSequence sel = ic.getSelectedText(0);
        if (!TextUtils.isEmpty(sel)) ic.commitText("", 1);
        else if (Build.VERSION.SDK_INT >= 24) ic.deleteSurroundingTextInCodePoints(1, 0);
        else ic.deleteSurroundingText(1, 0);
    }

    @Override
    public void onDeleteWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence sel = ic.getSelectedText(0);
        if (!TextUtils.isEmpty(sel)) {
            ic.commitText("", 1);
            return;
        }
        int n = WordEdit.deleteWordLength(ic.getTextBeforeCursor(64, 0));
        if (n > 0) ic.deleteSurroundingText(n, 0);
    }

    @Override
    public void onFunction(int kind) {
        switch (kind) {
            case Key.SHIFT:
                shift.tap(android.os.SystemClock.uptimeMillis());
                if (view != null) view.setShift(shift);
                break;
            case Key.SPACE:
                learn(currentWord());
                commit(" ");
                break;
            case Key.ENTER:
                learn(currentWord());
                doEnter();
                break;
            case Key.TO_SYMBOLS: setMode(Mode.SYMBOLS); break;
            case Key.TO_SYMBOLS_2: setMode(Mode.SYMBOLS_2); break;
            case Key.TO_LETTERS: setMode(fieldMode.textual() ? Mode.LETTERS : fieldMode); break;
            case Key.EMOJI: setMode(Mode.EMOJI); break;
            case Key.GLOBE: nextLanguageOrKeyboard(); break;
            default: break;
        }
    }

    @Override
    public void onLongFunction(int kind) {
        switch (kind) {
            case Key.SPACE: {
                List<Language> on = prefs.languages();
                if (on.size() > 1) setLanguage(Sizing.nextLanguage(language, on));
                else showPicker();
                break;
            }
            case Key.GLOBE: showPicker(); break;
            case Key.EMOJI: switchToOtherKeyboard(); break;
            default: break;
        }
    }

    @Override
    public void onCursor(int direction) {
        sendDownUpKeyEvents(direction < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT);
    }

    @Override
    public void onSuggestion(String word) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String typed = currentWord();
        ic.beginBatchEdit();
        if (!typed.isEmpty()) ic.deleteSurroundingText(typed.length(), 0);
        ic.commitText(word + " ", 1);
        ic.endBatchEdit();
        learn(word);
        shift.typed();
        updateAutoCaps();
        updateStrip();
    }

    @Override
    public void onUnlock() {
        requestHideSelf(0);
        Licensing.openPurchaseScreen(this);
    }

    @Override
    public void onVoice() {
        if (voiceIme == null) return;
        switchTo(voiceIme.getId(), voiceSubtype);
    }

    @Override
    public void onSwapHand() {
        int now = prefs.oneHanded();
        prefs.setOneHanded(now == Sizing.ONE_HANDED_LEFT ? Sizing.ONE_HANDED_RIGHT : Sizing.ONE_HANDED_LEFT);
        applySizes();
    }

    // ---- actions -------------------------------------------------------------------------------------------

    private void commit(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(s, 1);
        shift.typed();
        if (mode == Mode.SYMBOLS || mode == Mode.SYMBOLS_2) setMode(Mode.LETTERS);
        updateAutoCaps();
        updateStrip();
    }

    private void doEnter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (enter != FieldRules.Enter.NEWLINE) {
            ic.performEditorAction(FieldRules.actionId(enter));
            return;
        }
        if (inputType == InputType.TYPE_NULL) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
        else ic.commitText("\n", 1);
        updateAutoCaps();
        updateStrip();
    }

    /** Globe: the next language switched on, or the next keyboard app when only one language is on. */
    private void nextLanguageOrKeyboard() {
        List<Language> on = prefs.languages();
        if (on.size() > 1) {
            setLanguage(Sizing.nextLanguage(language, on));
            return;
        }
        if (Build.VERSION.SDK_INT >= 28) {
            if (!switchToNextInputMethod(false)) showPicker();
        } else {
            InputMethodManager imm = imm();
            IBinder token = token();
            if (imm == null || token == null || !legacyNext(imm, token)) showPicker();
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean legacyNext(InputMethodManager imm, IBinder token) {
        return imm.switchToNextInputMethod(token, false);
    }

    private void showPicker() {
        InputMethodManager imm = imm();
        if (imm != null) imm.showInputMethodPicker();
    }

    /** Emoji long press: hand over to another enabled keyboard (for its full emoji picker). */
    private void switchToOtherKeyboard() {
        InputMethodInfo other = otherIme();
        if (other == null) {
            showPicker();
            return;
        }
        switchTo(other.getId(), null);
    }

    @SuppressWarnings("deprecation")
    private void switchTo(String id, InputMethodSubtype subtype) {
        if (Build.VERSION.SDK_INT >= 28) {
            if (subtype != null) switchInputMethod(id, subtype);
            else switchInputMethod(id);
            return;
        }
        InputMethodManager imm = imm();
        IBinder token = token();
        if (imm == null || token == null) return;
        if (subtype != null) imm.setInputMethodAndSubtype(token, id, subtype);
        else imm.setInputMethod(token, id);
    }

    private InputMethodManager imm() {
        return (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    private IBinder token() {
        android.app.Dialog d = getWindow();
        return d == null || d.getWindow() == null ? null : d.getWindow().getAttributes().token;
    }

    private boolean otherImeAvailable() {
        return otherIme() != null;
    }

    /** The first enabled keyboard app that is not this one (a full keyboard, not a voice-only one). */
    private InputMethodInfo otherIme() {
        InputMethodManager imm = imm();
        if (imm == null) return null;
        try {
            for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
                if (getPackageName().equals(info.getPackageName())) continue;
                if (hasMode(imm, info, "keyboard") || info.getSubtypeCount() == 0) return info;
            }
        } catch (RuntimeException ignored) {
            // IMM unavailable
        }
        return null;
    }

    /** Looks for an enabled voice keyboard (a subtype in "voice" mode): the strip's voice key uses it. */
    private void findVoiceIme() {
        voiceIme = null;
        voiceSubtype = null;
        InputMethodManager imm = imm();
        if (imm == null) return;
        try {
            for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
                if (getPackageName().equals(info.getPackageName())) continue;
                for (InputMethodSubtype s : imm.getEnabledInputMethodSubtypeList(info, true)) {
                    if ("voice".equals(s.getMode())) {
                        voiceIme = info;
                        voiceSubtype = s;
                        return;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // IMM unavailable: no voice key
        }
    }

    private static boolean hasMode(InputMethodManager imm, InputMethodInfo info, String mode) {
        for (InputMethodSubtype s : imm.getEnabledInputMethodSubtypeList(info, true)) {
            if (mode.equals(s.getMode())) return true;
        }
        return false;
    }
}
