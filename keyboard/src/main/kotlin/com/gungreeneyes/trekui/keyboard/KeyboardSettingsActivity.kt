package com.gungreeneyes.trekui.keyboard

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.gungreeneyes.trekui.KeyboardLook
import com.gungreeneyes.trekui.TrekPrefs
import com.gungreeneyes.trekui.keyboard.logic.Language
import com.gungreeneyes.trekui.licensing.Licensing

/**
 * TrekUI Keyboard's setup and settings screen, drawn in the active interface's console frame like the
 * launcher's CONFIG: a two-step setup (enable the keyboard in Android's list, then pick it as the current
 * keyboard) with a field to try it, the interface choice (read-only while following the launcher),
 * languages, size presets, one-handed mode, sound and haptics, suggestions and learning, license and a
 * privacy summary. The app's launcher entry; licensing ([Licensing.init] / onAppResumed) runs here
 * and never in the keyboard itself.
 */
class KeyboardSettingsActivity : Activity() {
    /** A sidebar shortcut to a section. */
    private data class Anchor(val label: String, val view: View)

    private lateinit var prefs: KeyboardPrefs
    private var scroll: ScrollView? = null
    private lateinit var list: LinearLayout
    private val anchors = mutableListOf<Anchor>()
    private var lastEnabled = false
    private var lastSelected = false
    private var scrollY = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = KeyboardPrefs.get(this)
        Licensing.init(this)
        build()
    }

    override fun onResume() {
        super.onResume()
        Licensing.onAppResumed(this)
        KeyboardTheme.refresh(this, ::rebuild)
        rebuild()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // the system keyboard picker is a dialog over this screen: re-check when it closes
        if (hasFocus && (keyboardEnabled() != lastEnabled || keyboardSelected() != lastSelected)) rebuild()
    }

    private fun rebuild() {
        scroll?.let { scrollY = it.scrollY }
        build()
    }

    // ---- setup state -------------------------------------------------------------------------------------

    private fun imm(): InputMethodManager? = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?

    /** True if TrekUI Keyboard is switched on in Android's keyboard list. */
    private fun keyboardEnabled(): Boolean =
        imm()?.enabledInputMethodList?.any { it.packageName == packageName } == true

    /** True if TrekUI Keyboard is the current keyboard. */
    private fun keyboardSelected(): Boolean {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val cn = id?.let { ComponentName.unflattenFromString(it) }
        return cn != null && cn.packageName == packageName
    }

    // ---- screen ------------------------------------------------------------------------------------------

    private fun build() {
        KeyboardLook.load(this)
        anchors.clear()
        val root = FrameLayout(this)
        root.tag = "keyboard_settings_root"
        root.setBackgroundColor(KeyboardLook.bg())

        val titleView = KeyboardLook.bold(this, getString(R.string.settings_title), 28f, KeyboardLook.accent(0))
        titleView.setPadding(KeyboardLook.dp(this, 4f), 0, KeyboardLook.dp(this, 2f), 0)
        titleView.isSingleLine = true
        val footer = KeyboardLook.text(this, getString(R.string.settings_footer, version()), 14f, KeyboardLook.accent(1))
        footer.setPadding(KeyboardLook.dp(this, 4f), 0, KeyboardLook.dp(this, 2f), 0)

        val scroll = ScrollView(this)
        this.scroll = scroll
        scroll.isVerticalScrollBarEnabled = false
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "keyboard_settings_list"
            setPadding(0, 0, 0, KeyboardLook.dp(this@KeyboardSettingsActivity, 24f))
        }
        scroll.addView(list)

        setupSection()
        lookSection()
        languageSection()
        sizeSection()
        soundSection()
        wordsSection()
        licenseSection()
        privacySection()

        val frame = KeyboardLook.frame(this, titleView, footer, sidebar(scroll), scroll, 80f)
        root.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { view, raw ->
            val bars = WindowInsetsCompat.toWindowInsetsCompat(raw, view).getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            val side = KeyboardLook.dp(this, 8f)
            root.setPadding(bars.left + side, bars.top + KeyboardLook.dp(this, 4f), bars.right + side,
                bars.bottom + KeyboardLook.dp(this, 6f))
            raw
        }
        setContentView(root)
        val y = scrollY
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun sidebar(scroll: ScrollView): View {
        val sideScroll = ScrollView(this)
        sideScroll.isVerticalScrollBarEnabled = false
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        anchors.forEachIndexed { i, a ->
            val color = KeyboardLook.accent(if (i % 2 == 0) 2 else 4)
            val b = KeyboardLook.button(this, a.label, color, KeyboardLook.SQUARE) { scroll.smoothScrollTo(0, a.view.top) }
            b.background = KeyboardLook.touchCell(this, color, KeyboardLook.SQUARE, 0f)
            KeyboardLook.setTextSp(b, 14f)
            b.setPadding(KeyboardLook.dp(this, 4f), 0, KeyboardLook.dp(this, 8f), KeyboardLook.dp(this, 4f))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, KeyboardLook.dp(this, 50f))
            if (i > 0) lp.topMargin = KeyboardLook.dp(this, 6f)
            column.addView(b, lp)
        }
        sideScroll.addView(column)
        return sideScroll
    }

    // ---- sections ----------------------------------------------------------------------------------------

    private fun setupSection() {
        section(getString(R.string.section_setup), KeyboardLook.accent(0), getString(R.string.side_setup))
        lastEnabled = keyboardEnabled()
        lastSelected = keyboardSelected()
        step(getString(R.string.setup_step1), getString(R.string.setup_step1_note), lastEnabled,
            getString(R.string.setup_enable)) {
            try {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            } catch (e: RuntimeException) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        step(getString(R.string.setup_step2), getString(R.string.setup_step2_note), lastSelected,
            getString(R.string.setup_select)) { imm()?.showInputMethodPicker() }
        if (lastEnabled && lastSelected) note(getString(R.string.setup_all_done))
        val field = EditText(this).apply {
            tag = "keyboard_try_field"
            setHint(R.string.setup_try_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(KeyboardLook.textHi())
            setHintTextColor(KeyboardLook.textLo())
            typeface = KeyboardLook.font(this@KeyboardSettingsActivity, false)
            KeyboardLook.setTextSp(this, 16f)
            backgroundTintList = ColorStateList.valueOf(KeyboardLook.accent(1))
            minHeight = KeyboardLook.dp(this@KeyboardSettingsActivity, 52f)
        }
        list.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun lookSection() {
        section(getString(R.string.section_look), KeyboardLook.accent(3), getString(R.string.side_look))
        if (KeyboardTheme.followingLauncher()) {
            note(getString(R.string.look_following))
            return
        }
        val tp = TrekPrefs.get(this)
        val faction = tp.faction()
        val names = KeyboardLook.interfaceNames()
        valueRow(getString(R.string.look_interface), names[KeyboardLook.interfaceIndex(faction)]) {
            KeyboardLook.options(this, getString(R.string.look_interface), names) { which ->
                tp.setFaction(KeyboardLook.interfaceAt(which))
                KeyboardLook.reloadSounds(this)
                rebuild()
            }.show()
        }
        if (KeyboardLook.hasSubThemes(faction)) {
            val subs = KeyboardLook.subThemeNames(faction)
            val sub = tp.subTheme(faction)
            valueRow(getString(R.string.look_subtheme), subs[sub.coerceIn(0, subs.size - 1)]) {
                KeyboardLook.options(this, getString(R.string.look_subtheme), subs) { which ->
                    tp.setSubTheme(faction, which)
                    rebuild()
                }.show()
            }
        }
    }

    private fun languageSection() {
        section(getString(R.string.section_languages), KeyboardLook.accent(2), getString(R.string.side_lang))
        note(getString(R.string.languages_note))
        val on = prefs.languages()
        for (l in Language.entries) {
            val label = when (l) {
                Language.EN -> R.string.lang_en
                Language.DE -> R.string.lang_de
                Language.FR -> R.string.lang_fr
                Language.ES -> R.string.lang_es
            }
            toggle(getString(label), null, l in on) { v ->
                prefs.setLanguage(l, v)
                rebuild()
            }
        }
    }

    private fun sizeSection() {
        section(getString(R.string.section_layout), KeyboardLook.accent(4), getString(R.string.side_size))
        val sizes = arrayOf(getString(R.string.size_s), getString(R.string.size_m), getString(R.string.size_l),
            getString(R.string.size_xl))
        choice(getString(R.string.size_portrait), sizes, prefs.size) { prefs.size = it; rebuild() }
        choice(getString(R.string.size_landscape), sizes, prefs.sizeLandscape) { prefs.sizeLandscape = it; rebuild() }
        val hands = arrayOf(getString(R.string.one_handed_off), getString(R.string.one_handed_left),
            getString(R.string.one_handed_right))
        choice(getString(R.string.one_handed), hands, prefs.oneHanded) { prefs.oneHanded = it; rebuild() }
        toggle(getString(R.string.digit_row), getString(R.string.digit_row_note), prefs.digitRow) { v ->
            prefs.digitRow = v
            rebuild()
        }
    }

    private fun soundSection() {
        section(getString(R.string.section_feedback), KeyboardLook.accent(1), getString(R.string.side_sound))
        toggle(getString(R.string.sound_keys), getString(R.string.sound_keys_note), KeyboardLook.soundOn(this)) { v ->
            KeyboardLook.setSoundOn(this, v)
            rebuild()
        }
        label(getString(R.string.sound_volume))
        val bar = SeekBar(this).apply {
            tag = "keyboard_volume"
            max = 100
            progress = KeyboardLook.volume(this@KeyboardSettingsActivity)
            progressTintList = ColorStateList.valueOf(KeyboardLook.accent(0))
            thumbTintList = ColorStateList.valueOf(KeyboardLook.accent(0))
            progressBackgroundTintList = ColorStateList.valueOf(KeyboardLook.grid())
            contentDescription = getString(R.string.sound_volume)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) KeyboardLook.setVolume(this@KeyboardSettingsActivity, progress)
                }

                override fun onStartTrackingTouch(s: SeekBar) = Unit

                override fun onStopTrackingTouch(s: SeekBar) {
                    KeyboardLook.click(this@KeyboardSettingsActivity) // preview at the new volume
                }
            })
        }
        list.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, KeyboardLook.dp(this, 44f)))
        toggle(getString(R.string.haptics), getString(R.string.haptics_note), prefs.haptics) { v ->
            prefs.haptics = v
            rebuild()
        }
    }

    private fun wordsSection() {
        section(getString(R.string.section_text), KeyboardLook.accent(5), getString(R.string.side_words))
        toggle(getString(R.string.suggestions), getString(R.string.suggestions_note), prefs.suggestions) { v ->
            prefs.suggestions = v
            rebuild()
        }
        toggle(getString(R.string.learn_words), getString(R.string.learn_words_note), prefs.learn) { v ->
            prefs.learn = v
            rebuild()
        }
        action(getString(R.string.clear_words), KeyboardLook.darken(KeyboardLook.alert(), 0.1f)) {
            WordStore.get(this).clear()
            Toast.makeText(this, R.string.clear_words_done, Toast.LENGTH_SHORT).show()
        }
    }

    private fun licenseSection() {
        section(getString(R.string.section_license), KeyboardLook.accent(0), getString(R.string.side_license))
        val unlocked = Licensing.isUnlocked(this)
        note(getString(if (unlocked) R.string.license_unlocked else R.string.license_trial))
        if (!unlocked) {
            action(getString(R.string.license_unlock), KeyboardLook.accent(0)) { Licensing.openPurchaseScreen(this) }
        }
    }

    private fun privacySection() {
        section(getString(R.string.section_privacy), KeyboardLook.accent(2), null)
        note(getString(R.string.privacy_note))
    }

    // ---- row builders (the launcher CONFIG's vocabulary) --------------------------------------------------

    private fun section(title: String, color: Int, sidebarLabel: String?) {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, KeyboardLook.dp(this, if (list.childCount == 0) 2f else 26f), 0, KeyboardLook.dp(this, 8f))
        val pill = View(this)
        pill.background = KeyboardLook.cell(this, color, KeyboardLook.LEFT, 12f)
        row.addView(pill, LinearLayout.LayoutParams(KeyboardLook.dp(this, 22f), KeyboardLook.dp(this, 24f)))
        val label = KeyboardLook.bold(this, title, 22f, color)
        label.setPadding(KeyboardLook.dp(this, 8f), 0, KeyboardLook.dp(this, 8f), 0)
        row.addView(label)
        val rule = View(this)
        rule.background = KeyboardLook.rule(this, color)
        row.addView(rule, LinearLayout.LayoutParams(0, KeyboardLook.dp(this, 6f), 1.0f))
        list.addView(row)
        if (sidebarLabel != null) anchors += Anchor(sidebarLabel, row)
    }

    private fun note(s: String) {
        val v = KeyboardLook.text(this, s, 13f, KeyboardLook.textLo())
        v.setLineSpacing(0f, 1.1f)
        v.setPadding(0, KeyboardLook.dp(this, 2f), 0, KeyboardLook.dp(this, 8f))
        list.addView(v)
    }

    private fun label(s: String) {
        val v = KeyboardLook.bold(this, s, 16f, KeyboardLook.textHi())
        v.setPadding(0, KeyboardLook.dp(this, 12f), 0, KeyboardLook.dp(this, 6f))
        list.addView(v)
    }

    /** A row of a title (and optional description) beside a fixed-width button. */
    private fun buttonRow(title: String, description: View?, button: TextView, buttonWidthDp: Float) {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, KeyboardLook.dp(this, 6f), 0, KeyboardLook.dp(this, 6f))
        val text = LinearLayout(this)
        text.orientation = LinearLayout.VERTICAL
        text.addView(KeyboardLook.bold(this, title, 16f, KeyboardLook.textHi()))
        if (description != null) text.addView(description)
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f))
        button.gravity = Gravity.CENTER
        KeyboardLook.setTextSp(button, 14f)
        row.addView(button, LinearLayout.LayoutParams(KeyboardLook.dp(this, buttonWidthDp), KeyboardLook.dp(this, 44f)))
        list.addView(row)
    }

    /** A small grey description under a row title. */
    private fun description(s: String): TextView {
        val d = KeyboardLook.text(this, s, 12f, KeyboardLook.textLo())
        d.setPadding(0, KeyboardLook.dp(this, 2f), KeyboardLook.dp(this, 8f), 0)
        return d
    }

    /** A setup step: title, note and a button that turns into READY once the step is done. */
    private fun step(title: String, desc: String, done: Boolean, button: String, onClick: View.OnClickListener) {
        val color = if (done) KeyboardLook.darken(KeyboardLook.accent(2), 0.3f) else KeyboardLook.accent(0)
        val b = KeyboardLook.button(this, if (done) getString(R.string.setup_done) else button, color, KeyboardLook.PILL, onClick)
        b.contentDescription = title
        buttonRow(title, description(desc), b, 104f)
    }

    /** A labelled value with a CHOOSE button. */
    private fun valueRow(title: String, value: String, onClick: View.OnClickListener) {
        val b = KeyboardLook.button(this, getString(R.string.look_choose), KeyboardLook.accent(1), KeyboardLook.PILL, onClick)
        b.contentDescription = title
        buttonRow(title, KeyboardLook.text(this, value, 13f, KeyboardLook.accent(0)), b, 104f)
    }

    /** A setting with an ONLINE / OFFLINE switch. */
    private fun toggle(title: String, description: String?, on: Boolean, onToggle: (Boolean) -> Unit) {
        val color = if (on) KeyboardLook.accent(0) else KeyboardLook.darken(KeyboardLook.accent(3), 0.4f)
        val b = KeyboardLook.button(this, getString(if (on) R.string.toggle_on else R.string.toggle_off), color,
            KeyboardLook.PILL) {
            onToggle(!on)
            KeyboardLook.toggleSound(this)
        }
        b.contentDescription = title + if (on) ": on" else ": off"
        buttonRow(title, description?.let { description(it) }, b, 96f)
    }

    /** A labelled group of segmented option buttons; [selected] is highlighted. */
    private fun choice(title: String, options: Array<String>, selected: Int, onChoice: (Int) -> Unit) {
        label(title)
        val row = LinearLayout(this)
        options.forEachIndexed { i, option ->
            val color = if (i == selected) KeyboardLook.accent(0) else KeyboardLook.darken(KeyboardLook.accent(1), 0.4f)
            val corner = when (i) {
                0 -> KeyboardLook.LEFT
                options.size - 1 -> KeyboardLook.RIGHT
                else -> KeyboardLook.SQUARE
            }
            val b = KeyboardLook.button(this, option, color, corner) { onChoice(i) }
            KeyboardLook.setTextSp(b, 15f)
            b.gravity = Gravity.CENTER
            b.contentDescription = "$title: $option" + if (i == selected) ", selected" else ""
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f)
            if (i > 0) lp.leftMargin = KeyboardLook.dp(this, 4f)
            row.addView(b, lp)
        }
        list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, KeyboardLook.dp(this, 48f)))
    }

    /** A full-width action button. */
    private fun action(text: String, color: Int, onClick: View.OnClickListener) {
        val b = KeyboardLook.button(this, text, color, KeyboardLook.PILL, onClick)
        b.gravity = Gravity.CENTER
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, KeyboardLook.dp(this, 52f))
        lp.topMargin = KeyboardLook.dp(this, 6f)
        list.addView(b, lp)
    }

    private fun version(): String? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
        } else {
            legacyVersion()
        }
    } catch (e: PackageManager.NameNotFoundException) {
        ""
    }

    @Suppress("DEPRECATION")
    private fun legacyVersion(): String? = packageManager.getPackageInfo(packageName, 0).versionName
}
