package com.gungreeneyes.trekui

import android.content.Intent
import android.view.View

/**
 * A live home-screen widget panel (weather, calendar, media, power, systems, ...). MainActivity creates
 * it with [create], which wraps [build]'s content in an [ConsoleCard], and drives
 * its lifecycle: work (receivers, polling, animation) happens only between [resume] and
 * [pause].
 *
 * Java interop: the fields are [JvmField] so the Java panels keep reading `a`, `pal`, `accent` and
 * `card`; their overrides are `public` (Kotlin has no package-private members to override).
 */
internal abstract class Panel(
    @JvmField val a: MainActivity,
    @JvmField val accent: Int,
) {
    @JvmField
    val pal: Palette = Prefs.get(a).palette()

    /** The card made by [create]; null until then. */
    @JvmField
    var card: ConsoleCard? = null

    /** Stable id (one of Prefs.ALL_WIDGETS or a quick-widget id). */
    abstract fun id(): String

    /** Card title. */
    abstract fun title(): String

    /** Fill card.body. */
    abstract fun build()

    /** The home became visible: start listening/polling. */
    open fun resume() {}

    /** The home was hidden: stop all work. */
    open fun pause() {}

    /** Called about once a minute while visible. */
    open fun tick() {}

    /** Sticky battery broadcast, forwarded while resumed. */
    open fun onBattery(battery: Intent?) {}

    /** Result of a runtime permission request this panel made. */
    open fun onPermissionResult(requestCode: Int, granted: Boolean) {}

    /** Extra long-press menu entries. */
    open fun extraMenu(): Array<String> = emptyArray()

    /** One of [extraMenu]'s entries was chosen. */
    open fun onExtraMenu(which: Int) {}

    /** Creates the card and fills it. */
    fun create(): View {
        val c = ConsoleCard(a, title(), accent, pal)
        card = c
        build()
        return c
    }
}
