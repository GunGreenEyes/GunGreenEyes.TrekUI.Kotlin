package com.gungreeneyes.trekui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * The signature console frame: a vertical sidebar joined to horizontal bars by rounded "elbows"
 * (outer convex corner, inner concave corner). Children: sidebar (fills the column between the
 * elbows), content, a title at the top-right end of the top bar and an optional footer at the
 * bottom-right end of the bottom bar. Coloured blocks sit at the outer end of each bar.
 *
 * <p>Each faction draws the same structure in its own geometry: Command elbows, Imperial angular
 * brackets with triangle blocks, Collective conduit traces with nodes, the 23rd-century console's square
 * blocks with blinking lamps and the 25th century's thin glass elbow ({@link EraDraw}).
 *
 * <p>The drawn parts are touch targets: a {@link PartListener} receives taps and long-presses on
 * "elbowT", "elbowB", "blockT0".., "blockB0".. so the home screen can customise them.
 *
 * <p>All sizes come from {@link Ui#dp} and therefore follow the window's zoom; the frame itself
 * is orientation-agnostic (content gets whatever is left of the width after the sidebar).
 */
class ConsoleFrame extends ViewGroup {
    /** Design sizes in dp (before zoom). */
    static final float SIDEBAR_DP = 64, BAR_DP = 18, OUTER_RADIUS_DP = 42, INNER_RADIUS_DP = 18,
            ELBOW_EXTENSION_DP = 22, BLOCK_DP = 26;
    /** Room kept free next to the title/footer label. */
    private static final float LABEL_CLEARANCE_DP = 48;
    /** Touch slop around drawn parts. */
    private static final float PART_PAD_DP = 10;
    /** How much a pressed part brightens (per RGB channel). */
    static final int PRESS_BRIGHTEN = 70;
    private static final long REVEAL_MS = 420;

    /** Sidebar width, bar heights, outer/inner elbow radii, gap between pieces, elbow extension below the bar. */
    int sidebarW, topH, botH, rOut, rIn, gap, ext;
    /** Elbow colours. */
    int colorTop, colorBot;
    /** Colours of the blocks at the outer end of each bar (right to left). */
    int[] topBlocks = new int[0], botBlocks = new int[0];
    /** Width of one bar block. */
    int blockW;

    private View sidebar, content, title, footer;
    /** Reveal animation progress, 0 (bars retracted) .. 1. */
    private float progress = 1f;

    /** Taps / long-presses on the drawn parts: "elbowT", "elbowB", "blockT0".., "blockB0".. */
    interface PartListener { void onPart(String part, View frame, boolean longPress); }

    private PartListener partListener;
    private final List<RectF> topRects = new ArrayList<>(), botRects = new ArrayList<>();
    private float topBarEnd, botBarEnd;
    private String pressed;
    private boolean longFired;
    private float downX, downY;
    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (pressed != null && partListener != null) {
                longFired = true;
                String p = pressed;
                pressed = null;
                invalidate();
                partListener.onPart(p, ConsoleFrame.this, true);
            }
        }
    };
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();

    ConsoleFrame(Context c) {
        super(c);
        setWillNotDraw(false);
        sidebarW = Ui.dp(c, SIDEBAR_DP);
        topH = Ui.dp(c, BAR_DP);
        botH = Ui.dp(c, BAR_DP);
        rOut = Ui.dp(c, OUTER_RADIUS_DP);
        rIn = Ui.dp(c, INNER_RADIUS_DP);
        gap = Ui.dp(c, Ui.GUTTER);
        ext = Ui.dp(c, ELBOW_EXTENSION_DP);
        blockW = Ui.dp(c, BLOCK_DP);
    }

    void setPartListener(PartListener l) { partListener = l; }

    private View swap(View old, View v) {
        if (old != null) removeView(old);
        if (v != null) addView(v);
        return v;
    }

    void setSidebar(View v) { sidebar = swap(sidebar, v); }
    void setContent(View v) { content = swap(content, v); }
    void setTitle(View v) { title = swap(title, v); }
    void setFooter(View v) { footer = swap(footer, v); }

    // ---- pure geometry (unit-tested) -----------------------------------------------------------

    /** Vertical extent of an elbow: bar + inner radius + extension (0 when there is no bar). */
    static int elbowSpanFor(int barH, int rIn, int ext) { return barH > 0 ? barH + rIn + ext : 0; }

    /** Where a bar currently ends while sweeping out from {@code minEnd} to {@code fullEnd}. */
    static float sweptBarEnd(float minEnd, float fullEnd, float progress) {
        return Math.max(minEnd, minEnd + (fullEnd - minEnd) * progress);
    }

    /** A pressed part's colour: each RGB channel brightened by {@link #PRESS_BRIGHTEN}, opaque. */
    static int pressedTint(int color) {
        int r = Math.min(255, ((color >> 16) & 0xFF) + PRESS_BRIGHTEN);
        int g = Math.min(255, ((color >> 8) & 0xFF) + PRESS_BRIGHTEN);
        int b = Math.min(255, (color & 0xFF) + PRESS_BRIGHTEN);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ---- layout ----------------------------------------------------------------------------------

    private int topRegion() { return Math.max(topH, title != null ? title.getMeasuredHeight() : 0); }
    private int botRegion() { return Math.max(botH, footer != null ? footer.getMeasuredHeight() : 0); }
    private int contentLeft() { return sidebarW + gap * 2; }
    private int contentTop() { int t = topRegion(); return t > 0 ? t + gap * 2 : 0; }
    private int contentBottomInset() { int b = botRegion(); return b > 0 ? b + gap * 2 : 0; }
    private int elbowSpan(int barH) { return elbowSpanFor(barH, rIn, ext); }
    private int sideTop() { return topH > 0 ? elbowSpan(topH) + gap : 0; }
    private int sideBottom(int h) { return botH > 0 ? h - elbowSpan(botH) - gap : h; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int hMode = MeasureSpec.getMode(hSpec), hSize = MeasureSpec.getSize(hSpec);
        int unspec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        int labelMax = MeasureSpec.makeMeasureSpec(Math.max(0, w - sidebarW - rIn - Ui.dp(getContext(), LABEL_CLEARANCE_DP)), MeasureSpec.AT_MOST);
        if (title != null) title.measure(labelMax, unspec);
        if (footer != null) footer.measure(labelMax, unspec);

        int cw = Math.max(0, w - contentLeft());
        int vert = contentTop() + contentBottomInset();
        int h;
        if (hMode == MeasureSpec.EXACTLY) {
            h = hSize;
            if (content != null) content.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(0, h - vert), MeasureSpec.EXACTLY));
        } else {
            int ch = 0;
            if (content != null) {
                int spec = hMode == MeasureSpec.AT_MOST
                        ? MeasureSpec.makeMeasureSpec(Math.max(0, hSize - vert), MeasureSpec.AT_MOST) : unspec;
                content.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), spec);
                ch = content.getMeasuredHeight();
            }
            h = vert + ch;
            h = Math.max(h, elbowSpan(topH) + elbowSpan(botH) + gap * 2);
            if (hMode == MeasureSpec.AT_MOST) h = Math.min(h, hSize);
        }
        if (sidebar != null) {
            int sh = Math.max(0, sideBottom(h) - sideTop());
            sidebar.measure(MeasureSpec.makeMeasureSpec(sidebarW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(sh, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        if (title != null) title.layout(w - title.getMeasuredWidth(), 0, w, title.getMeasuredHeight());
        if (footer != null) footer.layout(w - footer.getMeasuredWidth(), h - footer.getMeasuredHeight(), w, h);
        if (sidebar != null) sidebar.layout(0, sideTop(), sidebarW, sideTop() + sidebar.getMeasuredHeight());
        if (content != null) {
            int ct = contentTop();
            content.layout(contentLeft(), ct, contentLeft() + content.getMeasuredWidth(), ct + content.getMeasuredHeight());
        }
    }

    // ---- drawing ---------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (Ui.cloak) {
            // cloaked: the frame becomes a dim outline
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1, Ui.dp(getContext(), 1.5f)));
        } else {
            paint.setStyle(Paint.Style.FILL);
        }
        if (topH > 0) {
            int end = title != null ? title.getLeft() - gap * 2 : w;
            end = drawBlocks(canvas, topBlocks, end, 0, topH, topRects, "blockT");
            topBarEnd = end;
            paint.setColor(lit("elbowT", colorTop));
            drawCorner(canvas, topH, end);
        }
        if (botH > 0) {
            int end = footer != null ? footer.getLeft() - gap * 2 : w;
            end = drawBlocks(canvas, botBlocks, end, h - botH, h, botRects, "blockB");
            botBarEnd = end;
            paint.setColor(lit("elbowB", colorBot));
            // the bottom elbow is the top one mirrored vertically
            canvas.save();
            canvas.translate(0, h);
            canvas.scale(1, -1);
            drawCorner(canvas, botH, end);
            canvas.restore();
        }
    }

    /** The corner piece joining sidebar and bar, in the current faction's geometry. */
    private void drawCorner(Canvas canvas, int barH, int end) {
        if (Skin.imperial()) {
            // hollow, double-lined bracket in a heavy red line, ▲▼ row near its end
            int col = paint.getColor();
            float dd = Ui.density(getContext());
            buildBracket(barH, end);
            ImperialDraw.fill(canvas, path, Ui.cloak ? Color.BLACK : Skin.mix(Skin.bg(), col, 0.10f));
            ImperialDraw.frame(canvas, path, null, col, dd, 1f);
            triRow(canvas, barH, end, dd);
            paint.setColor(col);
        } else if (Skin.collective()) {
            conduit(canvas, barH, end);
        } else if (Skin.era()) {
            eraCorner(canvas, barH, end);
        } else {
            buildElbow(barH, end);
            canvas.drawPath(path, paint);
        }
    }

    /**
     * 23rd / 25th century corner ({@link EraDraw#frameCorner23}, {@link EraDraw#frameCorner25}). The
     * console's lamps blink: the frame redraws itself for the next toggle while animations are on.
     */
    private void eraCorner(Canvas canvas, int barH, int end) {
        int col = paint.getColor();
        float d = Ui.density(getContext());
        float depth = elbowSpan(barH);
        float barEnd = sweptBarEnd(sidebarW, end, progress);
        if (Skin.era23()) {
            long now = Ui.animate(getContext()) ? android.os.SystemClock.uptimeMillis() : -1;
            long next = EraDraw.frameCorner23(canvas, d, col, sidebarW, barH, depth, barEnd, gap, Ui.cloak, barH * 31 + sidebarW, now);
            if (next > 0) postInvalidateDelayed(next + 16);
        } else {
            EraDraw.frameCorner25(canvas, d, col, sidebarW, barH, depth, barEnd, rIn, rOut, Ui.cloak);
        }
        paint.setColor(col);
    }

    /**
     * Imperial: an angular bracket. Bevelled outer corner, a straight diagonal
     * where console has its concave curve, a narrower rail down the side and an oblique cut at the end
     * of the bar.
     */
    private void buildBracket(int barH, int barEndFull) {
        float W = sidebarW, H = barH;
        float ri = Math.min(rIn, W);
        float depth = H + ri + ext;
        float bevel = Math.min(rOut * 0.75f, Math.min(W * 0.6f, depth * 0.7f));
        float rail = W * 0.62f;
        float barEnd = sweptBarEnd(W + ri, barEndFull, progress);
        float cut = H * 0.9f;
        path.reset();
        path.moveTo(0, depth);
        path.lineTo(0, bevel);
        path.lineTo(bevel, 0);
        path.lineTo(barEnd, 0);
        path.lineTo(barEnd - cut, H);
        path.lineTo(rail + ri, H);
        path.lineTo(rail, H + ri);
        path.lineTo(rail, depth);
        path.close();
    }

    /** A short row of alternating triangles inside the bar, just before its oblique end. */
    private void triRow(Canvas canvas, int barH, int barEnd, float dd) {
        if (progress < 0.95f || barEnd - sidebarW < blockW * 3 || barH < 8 * dd) return;
        float H = barH, cut = H * 0.9f;
        float rw = Math.min(64 * dd, (barEnd - sidebarW) * 0.3f);
        float x = barEnd - cut - rw - gap;
        ImperialDraw.row(canvas, x, H * 0.22f, rw, H * 0.56f,
                new int[]{ImperialDraw.YELLOW, ImperialDraw.ORANGE, ImperialDraw.RED}, 1f);
    }

    /**
     * Collective: no corner piece. A hub node sits where console has its elbow; a trace runs from it along
     * the bar line through vias and small nodes to the bar's end, and another drops from the hub
     * down the sidebar.
     */
    private void conduit(Canvas canvas, int barH, int barEndFull) {
        float W = sidebarW, H = barH;
        float ri = Math.min(rIn, W);
        float depth = H + ri + ext;
        float barEnd = sweptBarEnd(W, barEndFull, progress);
        int col = paint.getColor();
        float d = Ui.density(getContext());
        float hx = W * 0.5f, hr = Math.min(W * 0.3f, 15 * d), hy = Math.max(hr + 2 * d, H * 0.5f + 2 * d);
        // trace along the bar line, bowing gently, with vias and small nodes
        float ly = H * 0.5f;
        float x = hx + hr;
        int i = 0;
        float[] runs = {0.22f, 0.18f, 0.27f, 0.14f, 0.19f};
        float span = barEnd - x;
        while (x < barEnd - 6 * d && span > 0) {
            float seg = Math.min(barEnd - x, span * runs[i % runs.length]);
            float bow = (i % 2 == 0 ? 0.06f : -0.05f);
            CollectiveDraw.curveTrace(canvas, x, ly, x + seg, ly, bow, col, d, 0.95f);
            x += seg;
            if (x < barEnd - 6 * d) {
                if (i % 2 == 0) CollectiveDraw.via(canvas, x, ly, 3 * d, col, d, 1f);
                else CollectiveDraw.node(canvas, x, ly, Math.min(H * 0.42f, 6 * d), col, d, 1f);
            }
            i++;
        }
        CollectiveDraw.via(canvas, barEnd - 4 * d, ly, 3.5f * d, col, d, 1f);
        CollectiveDraw.dots(canvas, hx + hr + 10 * d, ly + Math.max(5 * d, H * 0.5f + 3 * d), 5, col, d, barH * 13, 0.9f);
        // drop down the sidebar
        CollectiveDraw.curveTrace(canvas, hx, hy + hr, hx - W * 0.12f, depth, 0.08f, col, d, 0.9f);
        CollectiveDraw.via(canvas, hx - W * 0.12f, depth - 3 * d, 3 * d, col, d, 1f);
        // the hub itself
        CollectiveDraw.node(canvas, hx, hy, hr, col, d, 1f);
        paint.setColor(col);
    }

    /**
     * Draws the blocks at a bar's outer end, right to left starting at {@code right}, stopping before
     * they would run into the elbow. Records their hit rectangles and returns where the bar proper
     * now ends.
     */
    private int drawBlocks(Canvas canvas, int[] colors, int right, int top, int bottom, List<RectF> rects, String prefix) {
        int x = right;
        rects.clear();
        float d = Ui.density(getContext());
        for (int i = 0; i < colors.length; i++) {
            if (x - blockW < sidebarW + rIn + gap) break;
            paint.setColor(lit(prefix + i, colors[i]));
            float p = progress;
            if (Skin.imperial()) {
                // blocks become solid triangles, alternately pointing up and down
                boolean up = (x / Math.max(1, blockW + gap)) % 2 == 0;
                ImperialDraw.tri(canvas, x - blockW * p / 2f, (top + bottom) / 2f, blockW * p, (bottom - top) * 1.0f, up, paint.getColor(), 1f);
            } else if (Skin.era()) {
                EraDraw.frameBlock(canvas, x - blockW * p, top, x, bottom, paint.getColor(), d, Ui.cloak);
            } else if (Skin.collective()) {
                float cy = (top + bottom) / 2f, cx = x - blockW * 0.5f;
                CollectiveDraw.curveTrace(canvas, x - blockW * p, cy, x, cy, 0.0f, paint.getColor(), d, 0.7f);
                CollectiveDraw.node(canvas, cx, cy, Math.min((bottom - top) * 0.55f, 8 * d) * p, paint.getColor(), d, 1f);
            } else {
                canvas.drawRect(x - blockW * p, top, x, bottom, paint);
            }
            rects.add(new RectF(x - blockW, top, x, bottom));
            x -= blockW + gap;
        }
        return x;
    }

    /** Pressed parts glow briefly (console buttons brighten when touched); cloaked parts are dimmed. */
    private int lit(String part, int color) {
        if (Ui.cloak) color = Ui.alpha(color, part.equals(pressed) ? 1f : 0.7f);
        if (!part.equals(pressed)) return color;
        return pressedTint(color);
    }

    // ---- touch -----------------------------------------------------------------------------------

    /** The drawn part under a touch point, or null. */
    String partAt(float x, float y) { // package-private: HomeGestureDetector skips double-taps on frame parts
        int pad = Ui.dp(getContext(), PART_PAD_DP);
        int h = getHeight();
        for (int i = 0; i < topRects.size(); i++) {
            RectF r = topRects.get(i);
            if (x >= r.left - pad / 2f && x <= r.right + pad / 2f && y <= r.bottom + pad) return "blockT" + i;
        }
        for (int i = 0; i < botRects.size(); i++) {
            RectF r = botRects.get(i);
            if (x >= r.left - pad / 2f && x <= r.right + pad / 2f && y >= r.top - pad) return "blockB" + i;
        }
        if (topH > 0) {
            boolean side = x <= sidebarW && y <= topH + rIn + ext;
            boolean bar = y <= topH + pad && x <= topBarEnd;
            if (side || bar) return "elbowT";
        }
        if (botH > 0) {
            boolean side = x <= sidebarW && y >= h - (botH + rIn + ext);
            boolean bar = y >= h - botH - pad && x <= botBarEnd;
            if (side || bar) return "elbowB";
        }
        return null;
    }

    /** Tap / long-press handling for the drawn parts; touches elsewhere fall through to children. */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (partListener == null) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = partAt(e.getX(), e.getY());
                if (pressed == null) return false;
                longFired = false;
                downX = e.getX();
                downY = e.getY();
                invalidate();
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pressed != null && Math.hypot(e.getX() - downX, e.getY() - downY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                    removeCallbacks(longPress);
                    pressed = null;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (pressed != null && !longFired) {
                    String p = pressed;
                    pressed = null;
                    invalidate();
                    partListener.onPart(p, this, false);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                pressed = null;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    /** Command elbow: convex outer arc, bar, concave inner arc, sidebar edge. */
    private void buildElbow(int barH, int barEndFull) {
        float W = sidebarW, H = barH;
        float ri = Math.min(rIn, W);
        float depth = H + ri + ext;
        float ro = Math.min(rOut, Math.min(W, depth));
        float barEnd = sweptBarEnd(W + ri, barEndFull, progress);
        path.reset();
        path.moveTo(0, depth);
        path.lineTo(0, ro);
        oval.set(0, 0, ro * 2, ro * 2);
        path.arcTo(oval, 180, 90, false);
        path.lineTo(barEnd, 0);
        path.lineTo(barEnd, H);
        path.lineTo(W + ri, H);
        oval.set(W, H, W + ri * 2, H + ri * 2);
        path.arcTo(oval, 270, -90, false);
        path.lineTo(W, depth);
        path.close();
    }

    /** Bars sweep out from the elbows (skipped when animations are off). */
    void reveal() {
        if (!Ui.animate(getContext())) { progress = 1f; invalidate(); return; }
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(REVEAL_MS);
        a.setInterpolator(new DecelerateInterpolator(1.6f));
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator va) {
                progress = (Float) va.getAnimatedValue();
                invalidate();
            }
        });
        a.start();
    }
}
