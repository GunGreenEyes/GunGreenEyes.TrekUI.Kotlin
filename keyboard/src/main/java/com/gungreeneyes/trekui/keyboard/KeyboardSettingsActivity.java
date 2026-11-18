package com.gungreeneyes.trekui.keyboard;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.gungreeneyes.trekui.KeyboardLook;
import com.gungreeneyes.trekui.TrekPrefs;
import com.gungreeneyes.trekui.keyboard.logic.Language;
import com.gungreeneyes.trekui.keyboard.logic.Sizing;
import com.gungreeneyes.trekui.licensing.Licensing;

import java.util.ArrayList;
import java.util.List;

/**
 * TrekUI Keyboard's setup and settings screen, drawn in the active interface's console frame like the
 * launcher's CONFIG: a two-step setup (enable the keyboard in Android's list, then pick it as the current
 * keyboard) with a field to try it, the interface choice (read-only while following the launcher),
 * languages, size presets, one-handed mode, sound and haptics, suggestions and learning, license and a
 * privacy summary. The app's launcher entry; licensing ({@link Licensing#init} / onAppResumed) runs here
 * and never in the keyboard itself.
 */
public final class KeyboardSettingsActivity extends Activity {
    private interface Toggle { void set(boolean on); }
    private interface Choice { void chose(int index); }

    /** A sidebar shortcut to a section. */
    private static final class Anchor {
        final String label;
        final View view;
        Anchor(String label, View view) { this.label = label; this.view = view; }
    }

    private KeyboardPrefs prefs;
    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout list;
    private final List<Anchor> anchors = new ArrayList<>();
    private boolean lastEnabled, lastSelected;
    private int scrollY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        prefs = KeyboardPrefs.get(this);
        Licensing.init(this);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Licensing.onAppResumed(this);
        KeyboardTheme.refresh(this, this::rebuild);
        rebuild();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // the system keyboard picker is a dialog over this screen: re-check when it closes
        if (hasFocus && (isEnabled() != lastEnabled || isSelected() != lastSelected)) rebuild();
    }

    private void rebuild() {
        if (scroll != null) scrollY = scroll.getScrollY();
        build();
    }

    // ---- setup state -------------------------------------------------------------------------------------

    /** True if TrekUI Keyboard is switched on in Android's keyboard list. */
    private boolean isEnabled() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return false;
        for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
            if (getPackageName().equals(info.getPackageName())) return true;
        }
        return false;
    }

    /** True if TrekUI Keyboard is the current keyboard. */
    private boolean isSelected() {
        String id = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        ComponentName cn = id == null ? null : ComponentName.unflattenFromString(id);
        return cn != null && getPackageName().equals(cn.getPackageName());
    }

    // ---- screen ------------------------------------------------------------------------------------------

    private void build() {
        KeyboardLook.load(this);
        anchors.clear();
        root = new FrameLayout(this);
        root.setTag("keyboard_settings_root");
        root.setBackgroundColor(KeyboardLook.bg());

        TextView title = KeyboardLook.bold(this, getString(R.string.settings_title), 28f, KeyboardLook.accent(0));
        title.setPadding(KeyboardLook.dp(this, 4f), 0, KeyboardLook.dp(this, 2f), 0);
        title.setSingleLine(true);
        TextView footer = KeyboardLook.text(this, getString(R.string.settings_footer, version()), 14f, KeyboardLook.accent(1));
        footer.setPadding(KeyboardLook.dp(this, 4f), 0, KeyboardLook.dp(this, 2f), 0);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setTag("keyboard_settings_list");
        list.setPadding(0, 0, 0, KeyboardLook.dp(this, 24f));
        scroll.addView(list);

        setupSection();
        lookSection();
        languageSection();
        sizeSection();
        soundSection();
        wordsSection();
        licenseSection();
        privacySection();

        ViewGroup frame = KeyboardLook.frame(this, title, footer, sidebar(), scroll, 80f);
        root.addView(frame, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener((view, raw) -> {
            Insets bars = WindowInsetsCompat.toWindowInsetsCompat(raw, view).getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                            | WindowInsetsCompat.Type.ime());
            int side = KeyboardLook.dp(this, 8f);
            root.setPadding(bars.left + side, bars.top + KeyboardLook.dp(this, 4f), bars.right + side,
                    bars.bottom + KeyboardLook.dp(this, 6f));
            return raw;
        });
        setContentView(root);
        final int y = scrollY;
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private View sidebar() {
        ScrollView sideScroll = new ScrollView(this);
        sideScroll.setVerticalScrollBarEnabled(false);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < anchors.size(); i++) {
            final Anchor a = anchors.get(i);
            int color = KeyboardLook.accent(i % 2 == 0 ? 2 : 4);
            TextView b = KeyboardLook.button(this, a.label, color, KeyboardLook.SQUARE,
                    v -> scroll.smoothScrollTo(0, a.view.getTop()));
            b.setBackground(KeyboardLook.touchCell(this, color, KeyboardLook.SQUARE, 0f));
            KeyboardLook.setTextSp(b, 14f);
            b.setPadding(KeyboardLook.dp(this, 4f), 0, KeyboardLook.dp(this, 8f), KeyboardLook.dp(this, 4f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, KeyboardLook.dp(this, 50f));
            if (i > 0) lp.topMargin = KeyboardLook.dp(this, 6f);
            column.addView(b, lp);
        }
        sideScroll.addView(column);
        return sideScroll;
    }

    // ---- sections ----------------------------------------------------------------------------------------

    private void setupSection() {
        section(getString(R.string.section_setup), KeyboardLook.accent(0), getString(R.string.side_setup));
        lastEnabled = isEnabled();
        lastSelected = isSelected();
        step(getString(R.string.setup_step1), getString(R.string.setup_step1_note), lastEnabled,
                getString(R.string.setup_enable), v -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
                    } catch (RuntimeException e) {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    }
                });
        step(getString(R.string.setup_step2), getString(R.string.setup_step2_note), lastSelected,
                getString(R.string.setup_select), v -> {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showInputMethodPicker();
                });
        if (lastEnabled && lastSelected) note(getString(R.string.setup_all_done));
        EditText field = new EditText(this);
        field.setTag("keyboard_try_field");
        field.setHint(R.string.setup_try_hint);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setTextColor(KeyboardLook.textHi());
        field.setHintTextColor(KeyboardLook.textLo());
        field.setTypeface(KeyboardLook.font(this, false));
        KeyboardLook.setTextSp(field, 16f);
        field.setBackgroundTintList(ColorStateList.valueOf(KeyboardLook.accent(1)));
        field.setMinHeight(KeyboardLook.dp(this, 52f));
        list.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void lookSection() {
        section(getString(R.string.section_look), KeyboardLook.accent(3), getString(R.string.side_look));
        if (KeyboardTheme.followingLauncher()) {
            note(getString(R.string.look_following));
            return;
        }
        TrekPrefs tp = TrekPrefs.get(this);
        final int faction = tp.faction();
        final String[] names = KeyboardLook.interfaceNames();
        valueRow(getString(R.string.look_interface), names[faction], v -> KeyboardLook.options(this,
                getString(R.string.look_interface), names, which -> {
                    tp.setFaction(which);
                    KeyboardLook.reloadSounds(this);
                    rebuild();
                }).show());
        if (KeyboardLook.hasSubThemes(faction)) {
            final String[] subs = KeyboardLook.subThemeNames(faction);
            int sub = tp.subTheme(faction);
            valueRow(getString(R.string.look_subtheme), subs[Math.max(0, Math.min(subs.length - 1, sub))],
                    v -> KeyboardLook.options(this, getString(R.string.look_subtheme), subs, which -> {
                        tp.setSubTheme(faction, which);
                        rebuild();
                    }).show());
        }
    }

    private void languageSection() {
        section(getString(R.string.section_languages), KeyboardLook.accent(2), getString(R.string.side_lang));
        note(getString(R.string.languages_note));
        List<Language> on = prefs.languages();
        int[] labels = {R.string.lang_en, R.string.lang_de, R.string.lang_fr, R.string.lang_es};
        Language[] all = Language.values();
        for (int i = 0; i < all.length; i++) {
            final Language l = all[i];
            toggle(getString(labels[i]), null, on.contains(l), v -> {
                prefs.setLanguage(l, v);
                rebuild();
            });
        }
    }

    private void sizeSection() {
        section(getString(R.string.section_layout), KeyboardLook.accent(4), getString(R.string.side_size));
        String[] sizes = {getString(R.string.size_s), getString(R.string.size_m), getString(R.string.size_l), getString(R.string.size_xl)};
        choice(getString(R.string.size_portrait), sizes, prefs.size(), i -> { prefs.setSize(i); rebuild(); });
        choice(getString(R.string.size_landscape), sizes, prefs.sizeLandscape(), i -> { prefs.setSizeLandscape(i); rebuild(); });
        String[] hands = {getString(R.string.one_handed_off), getString(R.string.one_handed_left), getString(R.string.one_handed_right)};
        choice(getString(R.string.one_handed), hands, prefs.oneHanded(), i -> { prefs.setOneHanded(i); rebuild(); });
        toggle(getString(R.string.digit_row), getString(R.string.digit_row_note), prefs.digitRow(), v -> {
            prefs.setDigitRow(v);
            rebuild();
        });
    }

    private void soundSection() {
        section(getString(R.string.section_feedback), KeyboardLook.accent(1), getString(R.string.side_sound));
        toggle(getString(R.string.sound_keys), getString(R.string.sound_keys_note), KeyboardLook.soundOn(this), v -> {
            KeyboardLook.setSoundOn(this, v);
            rebuild();
        });
        label(getString(R.string.sound_volume));
        SeekBar bar = new SeekBar(this);
        bar.setTag("keyboard_volume");
        bar.setMax(100);
        bar.setProgress(KeyboardLook.volume(this));
        bar.setProgressTintList(ColorStateList.valueOf(KeyboardLook.accent(0)));
        bar.setThumbTintList(ColorStateList.valueOf(KeyboardLook.accent(0)));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(KeyboardLook.grid()));
        bar.setContentDescription(getString(R.string.sound_volume));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) KeyboardLook.setVolume(KeyboardSettingsActivity.this, progress);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) {
                KeyboardLook.click(KeyboardSettingsActivity.this); // preview at the new volume
            }
        });
        list.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, KeyboardLook.dp(this, 44f)));
        toggle(getString(R.string.haptics), getString(R.string.haptics_note), prefs.haptics(), v -> {
            prefs.setHaptics(v);
            rebuild();
        });
    }

    private void wordsSection() {
        section(getString(R.string.section_text), KeyboardLook.accent(5), getString(R.string.side_words));
        toggle(getString(R.string.suggestions), getString(R.string.suggestions_note), prefs.suggestions(), v -> {
            prefs.setSuggestions(v);
            rebuild();
        });
        toggle(getString(R.string.learn_words), getString(R.string.learn_words_note), prefs.learn(), v -> {
            prefs.setLearn(v);
            rebuild();
        });
        action(getString(R.string.clear_words), KeyboardLook.darken(KeyboardLook.alert(), 0.1f), v -> {
            WordStore.get(this).clear();
            Toast.makeText(this, R.string.clear_words_done, Toast.LENGTH_SHORT).show();
        });
    }

    private void licenseSection() {
        section(getString(R.string.section_license), KeyboardLook.accent(0), getString(R.string.side_license));
        boolean unlocked = Licensing.isUnlocked(this);
        note(getString(unlocked ? R.string.license_unlocked : R.string.license_trial));
        if (!unlocked) action(getString(R.string.license_unlock), KeyboardLook.accent(0), v -> Licensing.openPurchaseScreen(this));
    }

    private void privacySection() {
        section(getString(R.string.section_privacy), KeyboardLook.accent(2), null);
        note(getString(R.string.privacy_note));
    }

    // ---- row builders (the launcher CONFIG's vocabulary) --------------------------------------------------

    private void section(String title, int color, String sidebarLabel) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, KeyboardLook.dp(this, list.getChildCount() == 0 ? 2f : 26f), 0, KeyboardLook.dp(this, 8f));
        View pill = new View(this);
        pill.setBackground(KeyboardLook.cell(this, color, KeyboardLook.LEFT, 12f));
        row.addView(pill, new LinearLayout.LayoutParams(KeyboardLook.dp(this, 22f), KeyboardLook.dp(this, 24f)));
        TextView label = KeyboardLook.bold(this, title, 22f, color);
        label.setPadding(KeyboardLook.dp(this, 8f), 0, KeyboardLook.dp(this, 8f), 0);
        row.addView(label);
        View rule = new View(this);
        rule.setBackground(KeyboardLook.rule(this, color));
        row.addView(rule, new LinearLayout.LayoutParams(0, KeyboardLook.dp(this, 6f), 1.0f));
        list.addView(row);
        if (sidebarLabel != null) anchors.add(new Anchor(sidebarLabel, row));
    }

    private void note(String s) {
        TextView v = KeyboardLook.text(this, s, 13f, KeyboardLook.textLo());
        v.setLineSpacing(0f, 1.1f);
        v.setPadding(0, KeyboardLook.dp(this, 2f), 0, KeyboardLook.dp(this, 8f));
        list.addView(v);
    }

    private void label(String s) {
        TextView v = KeyboardLook.bold(this, s, 16f, KeyboardLook.textHi());
        v.setPadding(0, KeyboardLook.dp(this, 12f), 0, KeyboardLook.dp(this, 6f));
        list.addView(v);
    }

    /** A setup step: title, note and a button that turns into READY once the step is done. */
    private void step(String title, String desc, boolean done, String button, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, KeyboardLook.dp(this, 6f), 0, KeyboardLook.dp(this, 6f));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(KeyboardLook.bold(this, title, 16f, KeyboardLook.textHi()));
        TextView d = KeyboardLook.text(this, desc, 12f, KeyboardLook.textLo());
        d.setPadding(0, KeyboardLook.dp(this, 2f), KeyboardLook.dp(this, 8f), 0);
        text.addView(d);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        int color = done ? KeyboardLook.darken(KeyboardLook.accent(2), 0.3f) : KeyboardLook.accent(0);
        TextView b = KeyboardLook.button(this, done ? getString(R.string.setup_done) : button, color, KeyboardLook.PILL, onClick);
        b.setGravity(Gravity.CENTER);
        KeyboardLook.setTextSp(b, 14f);
        b.setContentDescription(title);
        row.addView(b, new LinearLayout.LayoutParams(KeyboardLook.dp(this, 104f), KeyboardLook.dp(this, 44f)));
        list.addView(row);
    }

    /** A labelled value with a CHOOSE button. */
    private void valueRow(String title, String value, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, KeyboardLook.dp(this, 6f), 0, KeyboardLook.dp(this, 6f));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(KeyboardLook.bold(this, title, 16f, KeyboardLook.textHi()));
        text.addView(KeyboardLook.text(this, value, 13f, KeyboardLook.accent(0)));
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        TextView b = KeyboardLook.button(this, getString(R.string.look_choose), KeyboardLook.accent(1), KeyboardLook.PILL, onClick);
        b.setGravity(Gravity.CENTER);
        KeyboardLook.setTextSp(b, 14f);
        b.setContentDescription(title);
        row.addView(b, new LinearLayout.LayoutParams(KeyboardLook.dp(this, 104f), KeyboardLook.dp(this, 44f)));
        list.addView(row);
    }

    /** A setting with an ONLINE / OFFLINE switch. */
    private void toggle(String title, String description, boolean on, Toggle onToggle) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, KeyboardLook.dp(this, 6f), 0, KeyboardLook.dp(this, 6f));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(KeyboardLook.bold(this, title, 16f, KeyboardLook.textHi()));
        if (description != null) {
            TextView d = KeyboardLook.text(this, description, 12f, KeyboardLook.textLo());
            d.setPadding(0, KeyboardLook.dp(this, 2f), KeyboardLook.dp(this, 8f), 0);
            text.addView(d);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        TextView b = KeyboardLook.button(this, getString(on ? R.string.toggle_on : R.string.toggle_off),
                on ? KeyboardLook.accent(0) : KeyboardLook.darken(KeyboardLook.accent(3), 0.4f), KeyboardLook.PILL, v -> {
                    onToggle.set(!on);
                    KeyboardLook.toggleSound(this);
                });
        b.setGravity(Gravity.CENTER);
        KeyboardLook.setTextSp(b, 14f);
        b.setContentDescription(title + (on ? ": on" : ": off"));
        row.addView(b, new LinearLayout.LayoutParams(KeyboardLook.dp(this, 96f), KeyboardLook.dp(this, 44f)));
        list.addView(row);
    }

    /** A labelled group of segmented option buttons; {@code selected} is highlighted. */
    private void choice(String title, String[] options, int selected, Choice onChoice) {
        label(title);
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            int color = i == selected ? KeyboardLook.accent(0) : KeyboardLook.darken(KeyboardLook.accent(1), 0.4f);
            int corner = i == 0 ? KeyboardLook.LEFT : i == options.length - 1 ? KeyboardLook.RIGHT : KeyboardLook.SQUARE;
            TextView b = KeyboardLook.button(this, options[i], color, corner, v -> onChoice.chose(index));
            KeyboardLook.setTextSp(b, 15f);
            b.setGravity(Gravity.CENTER);
            b.setContentDescription(title + ": " + options[i] + (i == selected ? ", selected" : ""));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
            if (i > 0) lp.leftMargin = KeyboardLook.dp(this, 4f);
            row.addView(b, lp);
        }
        list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, KeyboardLook.dp(this, 48f)));
    }

    /** A full-width action button. */
    private void action(String text, int color, View.OnClickListener onClick) {
        TextView b = KeyboardLook.button(this, text, color, KeyboardLook.PILL, onClick);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, KeyboardLook.dp(this, 52f));
        lp.topMargin = KeyboardLook.dp(this, 6f);
        list.addView(b, lp);
    }

    private String version() {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return getPackageManager().getPackageInfo(getPackageName(), PackageManager.PackageInfoFlags.of(0)).versionName;
            }
            return legacyVersion();
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    @SuppressWarnings("deprecation")
    private String legacyVersion() throws PackageManager.NameNotFoundException {
        return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    }
}
