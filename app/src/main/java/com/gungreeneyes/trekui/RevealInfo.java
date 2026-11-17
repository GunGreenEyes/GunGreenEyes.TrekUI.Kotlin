package com.gungreeneyes.trekui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The info-panel row under the Command and Imperial deploy layouts: one small key per home
 * panel (console pills / Imperial pointed plates). The open panel's key is lit; the others are dim.
 */
final class RevealInfo extends LinearLayout implements RevealHost.InfoBar {
    /** Key height, the corner radius of the pill keys, and the label size, at the design scale. */
    private static final float KEY_HEIGHT_DP = 34, KEY_RADIUS_DP = 17, LABEL_SP = 12;

    private final TextView[] keys;
    private final int[] colors;

    /**
     * @param names  one key label per home panel
     * @param colors the panel accent colours, same order
     * @param cb     told which key was tapped
     */
    RevealInfo(Context c, String[] names, int[] colors, final CollectiveInfo.OnSelect cb) {
        super(c);
        this.colors = colors;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        keys = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            TextView t = Ui.oneLine(Ui.bold(c, names[i], LABEL_SP, Ui.ink()));
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { Ui.tap(v); cb.select(k); }
            });
            t.setContentDescription(names[i] + " panel");
            LayoutParams lp = new LayoutParams(0, Math.max(Ui.dp(c, KEY_HEIGHT_DP), ScreenMetrics.of(c).scaledPx(KEY_HEIGHT_DP)), 1);
            if (i > 0) lp.leftMargin = Ui.dp(c, Ui.XS);
            addView(t, lp);
            keys[i] = t;
        }
        setActive(-1);
    }

    @Override public void setActive(int index) {
        for (int i = 0; i < keys.length; i++) {
            boolean on = i == index;
            int col = on ? colors[i] : Skin.command() ? Ui.darken(colors[i], 0.45f) : Ui.alpha(colors[i], 0.45f);
            keys[i].setBackground(Ui.touch(Ui.shape(getContext(), col, Ui.PILL, KEY_RADIUS_DP)));
            keys[i].setTextColor(Skin.command() ? Ui.ink() : on ? Skin.ink() : Ui.alpha(Skin.ink(), 0.7f));
        }
    }
}
