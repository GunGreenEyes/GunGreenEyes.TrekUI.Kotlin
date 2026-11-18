package com.gungreeneyes.trekui

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The info-panel row under the Command and Imperial deploy layouts: one small key per home
 * panel (console pills / Imperial pointed plates). The open panel's key is lit; the others are dim.
 *
 * @param names  one key label per home panel
 * @param colors the panel accent colours, same order
 * @param cb     told which key was tapped
 */
internal class RevealInfo(
    c: Context,
    names: Array<String>,
    private val colors: IntArray,
    cb: CollectiveInfo.OnSelect,
) : LinearLayout(c), RevealHost.InfoBar {

    private val keys: Array<TextView>

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        keys = Array(names.size) { i ->
            val t = Ui.oneLine(Ui.bold(c, names[i], LABEL_SP, Ui.ink()))
            t.gravity = Gravity.CENTER
            t.setOnClickListener { v ->
                Ui.tap(v)
                cb.select(i)
            }
            t.contentDescription = names[i] + " panel"
            val lp = LayoutParams(0, maxOf(Ui.dp(c, KEY_HEIGHT_DP), ScreenMetrics.of(c).scaledPx(KEY_HEIGHT_DP)), 1f)
            if (i > 0) lp.leftMargin = Ui.dp(c, Ui.XS.toFloat())
            addView(t, lp)
            t
        }
        setActive(-1)
    }

    override fun setActive(index: Int) {
        for (i in keys.indices) {
            val on = i == index
            val col = when {
                on -> colors[i]
                Skin.command() -> Ui.darken(colors[i], 0.45f)
                else -> Ui.alpha(colors[i], 0.45f)
            }
            keys[i].background = Ui.touch(Ui.shape(context, col, Ui.PILL, KEY_RADIUS_DP))
            keys[i].setTextColor(
                when {
                    Skin.command() -> Ui.ink()
                    on -> Skin.ink()
                    else -> Ui.alpha(Skin.ink(), 0.7f)
                },
            )
        }
    }

    private companion object {
        /** Key height, the corner radius of the pill keys, and the label size, at the design scale. */
        const val KEY_HEIGHT_DP = 34f
        const val KEY_RADIUS_DP = 17f
        const val LABEL_SP = 12f
    }
}
