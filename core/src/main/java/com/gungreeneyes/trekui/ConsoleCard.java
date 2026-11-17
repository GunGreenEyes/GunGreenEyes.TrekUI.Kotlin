package com.gungreeneyes.trekui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The container for every home-screen widget panel ({@link Panel#create()}): a bracket down the left
 * side, a title strip (title, accent rule, status text) and a vertical {@link #body} the panel fills.
 * The bracket is drawn in the current interface's style (console "[", Imperial bevel, Collective trace, the
 * 23rd/25th-century brackets from {@link EraDraw#bracket} or a pipe-material bracket from
 * {@link PipeDraw#cardBracket}).
 */
class ConsoleCard extends LinearLayout {
    /** Width of the side bracket, design dp. */
    static final float BRACKET_DP = 16f;

    final LinearLayout body;
    final TextView titleView, status;
    final Bracket bracket;
    final LinearLayout header;

    ConsoleCard(Context c, String title, int accent, Palette p) {
        super(c);
        setOrientation(HORIZONTAL);
        setClipChildren(false);
        bracket = new Bracket(c, accent);
        addView(bracket, new LayoutParams(Ui.dp(c, BRACKET_DP), LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = Ui.dp(c, Ui.S);
        addView(col, lp);

        header = new LinearLayout(c);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(Ui.dp(c, 30));
        titleView = Ui.oneLine(Ui.bold(c, title, 17, accent));
        header.addView(titleView, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        View bar = new View(c);
        bar.setBackground(Ui.shape(c, Ui.alpha(accent, 0.55f), Ui.PILL, 0));
        LayoutParams bl = new LayoutParams(0, Ui.dp(c, 3), 1);
        bl.leftMargin = Ui.dp(c, Ui.S);
        bl.rightMargin = Ui.dp(c, Ui.S);
        header.addView(bar, bl);
        status = Ui.oneLine(Ui.text(c, "", 13, p.textLo));
        header.addView(status);
        col.addView(header);

        body = new LinearLayout(c);
        body.setOrientation(VERTICAL);
        body.setPadding(0, Ui.dp(c, Ui.XS), 0, Ui.dp(c, Ui.GUTTER));
        col.addView(body);
    }

    /** The side bracket: "[" with rounded outer corners and short stubs reaching toward the content. */
    static class Bracket extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF arc = new RectF();

        Bracket(Context c, int color) {
            super(c);
            paint.setColor(color);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float d = Ui.density(getContext());
            // bar: inner edge of the upright; stub: thickness of the top/bottom arms; reach: how far the arms extend
            float bar = w * 0.5f, stub = Ui.dp(getContext(), 7), r = w, reach = w + 14 * d;
            if (Ui.cloak) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1, 1.2f * d));
            } else {
                paint.setStyle(Paint.Style.FILL);
            }
            path.reset();
            if (Skin.pipe()) {
                PipeDraw.cardBracket(cv, getContext(), w, h, paint.getColor());
                return;
            }
            if (Skin.era()) {
                EraDraw.bracket(cv, d, w, h, paint.getColor(), Ui.cloak);
                return;
            }
            if (Skin.collective()) {
                // a trace down the side of the card: node at the top, via at the bottom,
                // a short branch toward the content
                int col = paint.getColor();
                float cx = w * 0.4f, nr = Math.min(w * 0.42f, 6 * d);
                Path t = CollectiveDraw.tmp();
                t.moveTo(cx, nr * 2 + d);
                t.cubicTo(cx, h * 0.4f, cx + w * 0.2f, h * 0.6f, cx, h - 5 * d);
                CollectiveDraw.trace(cv, t, col, d, 0.85f);
                CollectiveDraw.curveTrace(cv, cx + nr, nr + d, reach, d * 2, -0.1f, col, d, 0.7f);
                CollectiveDraw.node(cv, cx, nr + d, nr, col, d, 1f);
                CollectiveDraw.via(cv, cx, h - 4 * d, 3 * d, col, d, 1f);
                return;
            }
            if (Skin.imperial()) {
                // angular bracket: bevelled corners, inner diagonals
                float bevel = w * 0.8f;
                path.moveTo(reach, 0);
                path.lineTo(bevel, 0);
                path.lineTo(0, bevel);
                path.lineTo(0, h - bevel);
                path.lineTo(bevel, h);
                path.lineTo(reach, h);
                path.lineTo(reach - stub, h - stub);
                path.lineTo(bar + r * 0.35f, h - stub);
                path.lineTo(bar, h - stub - r * 0.35f);
                path.lineTo(bar, stub + r * 0.35f);
                path.lineTo(bar + r * 0.35f, stub);
                path.lineTo(reach - stub, stub);
                path.close();
                cv.drawPath(path, paint);
                return;
            }
            path.moveTo(reach, 0);
            path.lineTo(r, 0);
            arc.set(0, 0, r * 2, r * 2);
            path.arcTo(arc, 270, -90, false);
            path.lineTo(0, h - r);
            arc.set(0, h - r * 2, r * 2, h);
            path.arcTo(arc, 180, -90, false);
            path.lineTo(reach, h);
            path.lineTo(reach, h - stub);
            path.lineTo(bar + r * 0.35f, h - stub);
            path.lineTo(bar, h - stub - r * 0.35f);
            path.lineTo(bar, stub + r * 0.35f);
            path.lineTo(bar + r * 0.35f, stub);
            path.lineTo(reach, stub);
            path.close();
            cv.drawPath(path, paint);
        }
    }
}
