package com.gungreeneyes.trekui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The OPEN GROUP overlay (gap A6): an app group unfolding over the home screen in the style of the
 * active interface.
 *
 * <ul>
 *   <li><b>Command</b>: an console cascade like {@link CommandCascade}: the group's name in a pill key,
 *       a segmented spine growing down from it, and app keys sliding out of the spine left and right.</li>
 *   <li><b>Imperial</b>: a tactical deploy: chamfered plates fly out of the name plate into a grid,
 *       under a band of warning chevrons.</li>
 *   <li><b>Collective</b>: an orbit: the name in a hub node, apps on an ellipse joined to it by traces that
 *       stretch outward as it opens.</li>
 * </ul>
 *
 * <p>All sizes come from {@link ScreenMetrics} (margins, columns, orientation) and {@link Ui#dp}
 * (zoom-aware dp), inside the window insets, so the panel fits phones, tablets, landscape and split
 * screen. Tapping an app launches it; tapping outside, Back or Home folds the panel; long-pressing
 * the title edits the group ({@link GroupEditor}). Test hooks: view id {@code group_panel}, tag
 * {@code "group_panel"}; app keys are tagged {@code "group_key_<n>"}.
 */
final class GroupPanel extends FrameLayout {

    /** Unfold / fold durations, in ms. */
    private static final long UNFOLD_MS = 460L, FOLD_MS = 220L;
    /** Scrim opacity over the home screen. */
    private static final float SCRIM_ALPHA = 0.82f;
    /** Widest the panel content gets, in dp. */
    private static final float MAX_CONTENT_DP = 620f;
    /** Command: title key height and max width, spine width, row height range, widest key (dp). */
    private static final float COMMAND_TITLE_H = 50, COMMAND_TITLE_MAX_W = 300, COMMAND_SPINE = 20, COMMAND_ROW_MIN = 36, COMMAND_ROW_MAX = 62, COMMAND_KEY_MAX_W = 260;
    /** Command key corner radius and icon size (dp). */
    private static final float COMMAND_RADIUS = 30, ICON_DP = 26;
    /** Grid key height range for the Imperial layout (dp). */
    private static final float GRID_ROW_MIN = 44, GRID_ROW_MAX = 64;
    /** Collective hub diameter and key size range (dp). */
    private static final float COLLECTIVE_HUB = 104, COLLECTIVE_KEY_MIN = 58, COLLECTIVE_KEY_MAX = 88;

    private static final int STYLE_COMMAND = 0, STYLE_IMPERIAL = 1, STYLE_COLLECTIVE = 2;

    private final MainActivity activity;
    private final AppGroups.Group group;
    private final Palette pal;
    private final ScreenMetrics metrics;
    private final int style;
    private final TextView title;
    private final List<View> keys = new ArrayList<>();
    private final List<Integer> keyColors = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    /** Laid-out key positions (left, top, right, bottom) and the title's rectangle. */
    private float[][] keyRects = new float[0][];
    private final RectF titleRect = new RectF();
    /** Command spine / grid extent; Collective hub centre and orbit radii; Imperial band width. */
    private float spineTop, spineBottom, rowH, gap, hubX, hubY, orbitX, orbitY, bandW;
    private float progress;
    private ValueAnimator animator;
    private boolean folding;

    private GroupPanel(MainActivity a, AppGroups.Group group) {
        super(a);
        this.activity = a;
        this.group = group;
        this.pal = a.pal != null ? a.pal : Prefs.get(a).palette();
        this.metrics = ScreenMetrics.of(a);
        this.style = Skin.imperial() ? STYLE_IMPERIAL : Skin.collective() ? STYLE_COLLECTIVE : STYLE_COMMAND;
        setId(R.id.group_panel);
        setTag("group_panel");
        setWillNotDraw(false);
        setClickable(true);
        setOnClickListener(v -> fold());
        setContentDescription(a.getString(R.string.cd_group_panel, group.name));

        title = Ui.oneLine(Ui.bold(a, group.name, style == STYLE_COLLECTIVE ? 15 : 20,
                style == STYLE_COMMAND ? Ui.ink() : ink(pal.c(0))));
        title.setGravity(style == STYLE_COMMAND ? Gravity.END | Gravity.CENTER_VERTICAL : Gravity.CENTER);
        if (style == STYLE_COLLECTIVE) {
            title.setSingleLine(false);
            title.setMaxLines(2);
        }
        int pad = Ui.dp(a, Ui.L);
        title.setPadding(pad, 0, pad, 0);
        title.setBackground(Ui.touch(style == STYLE_COMMAND ? Ui.shape(a, pal.c(0), Ui.PILL, COMMAND_RADIUS)
                : Skin.cell(a, pal.c(0), Ui.PILL, COMMAND_RADIUS)));
        title.setOnClickListener(v -> fold());
        title.setOnLongClickListener(v -> {
            Ui.longPress(v);
            editGroup();
            return true;
        });
        title.setTag("group_title");
        addView(title);

        AppRepo repo = AppRepo.get(a);
        int position = 0;
        for (AppGroups.Member m : group.apps) {
            AppInfo app = repo.find(m.comp, m.user);
            if (app == null) continue;
            addKey(app, position++);
        }
        if (keys.isEmpty()) addEmptyKey();
    }

    // ---- opening and closing -------------------------------------------------------------------

    /**
     * Unfolds the group with {@code groupId} over the home screen (replacing any open group).
     *
     * @return false (after an error chirp and toast) when the group no longer exists
     */
    static boolean open(MainActivity a, String groupId) {
        AppGroups.Group g = AppGroups.find(AppGroups.load(Prefs.get(a)), groupId);
        if (g == null) {
            Sfx.play(a, Sfx.ERROR);
            a.toast(a.getString(R.string.toast_group_missing));
            return false;
        }
        View rootView = a.findViewById(R.id.home_root);
        if (!(rootView instanceof ViewGroup)) return false;
        removeNow(a);
        GroupPanel panel = new GroupPanel(a, g);
        ((ViewGroup) rootView).addView(panel, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Sfx.play(a, Sfx.OPEN);
        panel.unfold();
        return true;
    }

    /** Folds the open group panel, if any. Used by Back and Home; true when one was open. */
    static boolean close(MainActivity a) {
        View v = a.findViewById(R.id.group_panel);
        if (!(v instanceof GroupPanel)) return false;
        ((GroupPanel) v).fold();
        return true;
    }

    /** True while a group panel is on screen. */
    static boolean isOpen(MainActivity a) {
        return a.findViewById(R.id.group_panel) instanceof GroupPanel;
    }

    /** Removes an open panel without animation. */
    private static void removeNow(MainActivity a) {
        View v = a.findViewById(R.id.group_panel);
        if (v instanceof GroupPanel) ((GroupPanel) v).detach();
    }

    private void unfold() {
        animateTo(1f, UNFOLD_MS, null);
    }

    /** Folds the panel away and removes it. */
    void fold() {
        if (folding) return;
        folding = true;
        Sfx.play(getContext(), Sfx.CLOSE);
        animateTo(0f, FOLD_MS, this::detach);
    }

    private void detach() {
        if (animator != null) animator.cancel();
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) parent.removeView(this);
    }

    private void animateTo(float target, long ms, final Runnable after) {
        if (animator != null) animator.cancel();
        if (!Ui.animate(getContext())) {
            setProgress(target);
            if (after != null) after.run();
            return;
        }
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(ms);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(va -> setProgress((Float) va.getAnimatedValue()));
        if (after != null) {
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    after.run();
                }
            });
        }
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    // ---- keys ----------------------------------------------------------------------------------

    /** Key ink: Command keys use the dark console ink; Collective keys the matrix green; others a glow. */
    private int ink(int color) {
        if (style == STYLE_COMMAND) return Ui.ink();
        if (style == STYLE_COLLECTIVE) return Ui.cloak ? Skin.glow(color) : Skin.tileInk();
        return Skin.glow(color);
    }

    private boolean leftKey(int i) {
        return i % 2 == 0;
    }

    private Drawable keyBackground(int color, int i) {
        if (style == STYLE_COMMAND) {
            return Ui.touch(Ui.shape(getContext(), color, leftKey(i) ? Ui.LEFT : Ui.RIGHT, COMMAND_RADIUS));
        }
        return Ui.touch(Skin.cell(getContext(), color, style == STYLE_COLLECTIVE ? Ui.PILL : Ui.SQUARE, 18));
    }

    private void addKey(final AppInfo app, int i) {
        Context c = getContext();
        int color = pal.c(HomeLogic.accentIndex(i + 1));
        LinearLayout key = new LinearLayout(c);
        boolean commandStyle = style == STYLE_COMMAND;
        key.setOrientation(commandStyle ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        key.setGravity(Gravity.CENTER);
        key.setBackground(keyBackground(color, i));
        int inset = Ui.dp(c, commandStyle ? 16 : 6);
        key.setPadding(inset, Ui.dp(c, 4), inset, Ui.dp(c, 4));

        ImageView icon = new ImageView(c);
        Prefs prefs = Prefs.get(c);
        icon.setImageDrawable(IconFactory.style(c, app, pal, prefs.iconStyle(), prefs.iconTone()));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int is = Ui.dp(c, ICON_DP);
        TextView label = Ui.oneLine(Ui.bold(c, Ui.up(c, HomeLogic.shortLabel(app.label)), commandStyle ? 15 : 12, ink(color)));
        if (commandStyle) {
            label.setGravity(leftKey(i) ? Gravity.END | Gravity.BOTTOM : Gravity.START | Gravity.BOTTOM);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.leftMargin = lp.rightMargin = Ui.dp(c, Ui.S);
            label.setPadding(0, 0, 0, Ui.dp(c, 4));
            if (leftKey(i)) {
                key.addView(icon, new LinearLayout.LayoutParams(is, is));
                key.addView(label, lp);
            } else {
                key.addView(label, lp);
                key.addView(icon, new LinearLayout.LayoutParams(is, is));
            }
        } else {
            label.setGravity(Gravity.CENTER);
            key.addView(icon, new LinearLayout.LayoutParams(is, is));
            key.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        key.setContentDescription(app.label);
        key.setTag("group_key_" + i);
        key.setOnClickListener(v -> {
            activity.launchApp(app, v);
            post(this::detach);
        });
        key.setOnLongClickListener(v -> {
            Ui.longPress(v);
            editGroup();
            return true;
        });
        keys.add(key);
        keyColors.add(color);
        addView(key);
    }

    /** An empty group shows one key that opens the app picker. */
    private void addEmptyKey() {
        Context c = getContext();
        int color = pal.c(2);
        TextView key = Ui.oneLine(Ui.bold(c, c.getString(R.string.group_add_apps), 14, ink(color)));
        key.setGravity(Gravity.CENTER);
        key.setBackground(keyBackground(color, 0));
        key.setTag("group_key_add");
        key.setOnClickListener(v -> {
            detach();
            GroupEditor.pickApps(activity, group.id, null);
        });
        keys.add(key);
        keyColors.add(color);
        addView(key);
    }

    private void editGroup() {
        detach();
        GroupEditor.manage(activity, group.id, null);
    }

    // ---- layout --------------------------------------------------------------------------------

    private float dp(float v) {
        return Ui.dp(getContext(), v);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        computeLayout(w, h);
        for (int i = 0; i < keys.size(); i++) {
            float[] k = keyRects[i];
            keys.get(i).measure(MeasureSpec.makeMeasureSpec(Math.round(k[2] - k[0]), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(k[3] - k[1]), MeasureSpec.EXACTLY));
        }
        title.measure(MeasureSpec.makeMeasureSpec(Math.round(titleRect.width()), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(titleRect.height()), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int rr, int b) {
        for (int i = 0; i < keys.size(); i++) {
            float[] k = keyRects[i];
            keys.get(i).layout(Math.round(k[0]), Math.round(k[1]), Math.round(k[2]), Math.round(k[3]));
        }
        title.layout(Math.round(titleRect.left), Math.round(titleRect.top), Math.round(titleRect.right), Math.round(titleRect.bottom));
        apply();
    }

    /** Works out every rectangle for the current style inside the insets and ScreenMetrics margins. */
    private void computeLayout(int w, int h) {
        float margin = metrics.marginPx();
        float left = activity.insetLeft + margin, right = w - activity.insetRight - margin;
        float top = activity.insetTop + margin, bottom = h - activity.insetBottom - margin;
        float cw = Math.min(right - left, dp(MAX_CONTENT_DP));
        float cx = (left + right) / 2f;
        RectF area = new RectF(cx - cw / 2f, top, cx + cw / 2f, Math.max(top + dp(120), bottom));
        gap = dp(Ui.GUTTER);
        int n = keys.size();
        keyRects = new float[n][];
        switch (style) {
            case STYLE_COMMAND: layoutCascade(area, n); break;
            case STYLE_COLLECTIVE: layoutOrbit(area, n); break;
            default: layoutGrid(area, n); break;
        }
    }

    /** Command: title pill on top, two key columns either side of a centred spine. */
    private void layoutCascade(RectF a, int n) {
        float titleH = dp(COMMAND_TITLE_H), spine = dp(COMMAND_SPINE);
        int rows = Math.max(1, (n + 1) / 2);
        float avail = a.height() - titleH - gap * 2;
        rowH = clamp(avail / rows - gap, dp(COMMAND_ROW_MIN), dp(COMMAND_ROW_MAX));
        if (rows * (rowH + gap) > avail) rowH = Math.max(dp(24), avail / rows - gap);
        float half = Math.min((a.width() - spine) / 2f - gap, dp(COMMAND_KEY_MAX_W));
        float total = titleH + gap * 2 + rows * (rowH + gap) - gap;
        float y0 = a.top + Math.max(0, (a.height() - total) / 2f);
        float cx = a.centerX();
        float titleW = Math.min(a.width() * 0.7f, dp(COMMAND_TITLE_MAX_W));
        titleRect.set(cx - titleW / 2f, y0, cx + titleW / 2f, y0 + titleH);
        float keysTop = y0 + titleH + gap * 2;
        for (int i = 0; i < n; i++) {
            float y = keysTop + (i / 2) * (rowH + gap);
            float x = leftKey(i) ? cx - spine / 2f - gap - half : cx + spine / 2f + gap;
            keyRects[i] = new float[]{x, y, x + half, y + rowH};
        }
        spineTop = y0 + titleH + gap * 0.5f;
        spineBottom = keysTop + rows * (rowH + gap) - gap;
    }

    /** Imperial: title plate on top, keys in a grid whose column count follows the window width. */
    private void layoutGrid(RectF a, int n) {
        int cols = Math.max(1, Math.min(n, metrics.columns(120f, 2, metrics.isLandscape() ? 5 : 3)));
        float titleH = dp(46);
        int rows = Math.max(1, (n + cols - 1) / cols);
        float pad = 0;
        float gridW = Math.min(a.width() - pad * 2, cols * dp(190));
        float keyW = (gridW - gap * (cols - 1)) / cols;
        float avail = a.height() - titleH - gap * 3 - pad * 2;
        rowH = clamp(avail / rows - gap, dp(GRID_ROW_MIN), dp(GRID_ROW_MAX));
        if (rows * (rowH + gap) > avail) rowH = Math.max(dp(28), avail / rows - gap);
        float total = titleH + gap * 3 + rows * (rowH + gap) - gap + pad * 2;
        float y0 = a.top + Math.max(0, (a.height() - total) / 2f) + pad;
        float cx = a.centerX();
        float titleW = Math.min(gridW, Math.max(gridW * 0.8f, dp(200)));
        titleRect.set(cx - titleW / 2f, y0, cx + titleW / 2f, y0 + titleH);
        float keysTop = y0 + titleH + gap * 3;
        float x0 = cx - gridW / 2f;
        for (int i = 0; i < n; i++) {
            int row = i / cols, col = i % cols;
            // centre a partial last row
            int inRow = row == rows - 1 ? n - row * cols : cols;
            float rowX = x0 + (gridW - (inRow * keyW + (inRow - 1) * gap)) / 2f;
            float x = rowX + col * (keyW + gap);
            float y = keysTop + row * (rowH + gap);
            keyRects[i] = new float[]{x, y, x + keyW, y + rowH};
        }
        spineTop = y0 + titleH + gap;
        spineBottom = keysTop + rows * (rowH + gap) - gap;
        bandW = gridW;
    }

    /** Collective: the title is the hub; keys sit on an ellipse around it. */
    private void layoutOrbit(RectF a, int n) {
        float hub = Math.min(dp(COLLECTIVE_HUB), Math.min(a.width(), a.height()) * 0.34f);
        hubX = a.centerX();
        hubY = a.centerY();
        titleRect.set(hubX - hub / 2f, hubY - hub / 2f, hubX + hub / 2f, hubY + hub / 2f);
        float ry0 = a.height() / 2f, rx0 = Math.min(a.width() / 2f, ry0 * 1.6f);
        float circumference = (float) (Math.PI * (rx0 + ry0));
        float kw = clamp(circumference / Math.max(1, n) * 0.8f, dp(COLLECTIVE_KEY_MIN), dp(COLLECTIVE_KEY_MAX));
        float kh = kw * 0.86f;
        orbitX = Math.max(hub / 2f + kw / 2f, rx0 - kw / 2f);
        orbitY = Math.max(hub / 2f + kh / 2f, ry0 - kh / 2f);
        for (int i = 0; i < n; i++) {
            double angle = -Math.PI / 2 + i * 2 * Math.PI / Math.max(1, n);
            float x = hubX + (float) Math.cos(angle) * orbitX, y = hubY + (float) Math.sin(angle) * orbitY;
            keyRects[i] = new float[]{x - kw / 2f, y - kh / 2f, x + kw / 2f, y + kh / 2f};
        }
    }

    // ---- animation and drawing -----------------------------------------------------------------

    private void setProgress(float p) {
        progress = p;
        apply();
    }

    private static float ease(float x) {
        float c = clamp(x, 0f, 1f);
        float inv = 1f - c;
        return 1f - inv * inv * inv;
    }

    /** How far key {@code i} has deployed (keys follow one another). */
    private float keyProgress(int i) {
        float stagger = keys.size() > 1 ? 0.3f / keys.size() : 0f;
        return ease((progress - 0.25f - i * stagger) / 0.45f);
    }

    /** Applies the current progress to the title and keys (per-style deploy motion). */
    private void apply() {
        float head = ease(progress / 0.35f);
        title.setAlpha(head);
        title.setScaleX(0.6f + 0.4f * head);
        title.setScaleY(0.6f + 0.4f * head);
        for (int i = 0; i < keys.size() && i < keyRects.length; i++) {
            View v = keys.get(i);
            float e = keyProgress(i);
            float[] k = keyRects[i];
            float kx = (k[0] + k[2]) / 2f, ky = (k[1] + k[3]) / 2f;
            switch (style) {
                case STYLE_COMMAND:
                    v.setPivotX(leftKey(i) ? v.getWidth() : 0);
                    v.setPivotY(v.getHeight() / 2f);
                    v.setScaleX(Math.max(0.01f, e));
                    break;
                case STYLE_IMPERIAL:
                    v.setTranslationX((1 - e) * (titleRect.centerX() - kx));
                    v.setTranslationY((1 - e) * (titleRect.centerY() - ky));
                    break;
                case STYLE_COLLECTIVE:
                    v.setTranslationX((1 - e) * (hubX - kx));
                    v.setTranslationY((1 - e) * (hubY - ky));
                    v.setScaleX(0.4f + 0.6f * e);
                    v.setScaleY(0.4f + 0.6f * e);
                    break;
            }
            v.setAlpha(0.15f + 0.85f * e);
            v.setVisibility(e <= 0.001f ? INVISIBLE : VISIBLE);
        }
        invalidate();
    }

    /** Scrim, then the style's decorations (spine, chevron band, orbit traces), then the keys. */
    @Override
    protected void dispatchDraw(Canvas cv) {
        float fade = clamp(progress / 0.3f, 0f, 1f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.alpha(Skin.bg(), SCRIM_ALPHA * fade));
        cv.drawRect(0, 0, getWidth(), getHeight(), paint);
        float d = Ui.density(getContext());
        switch (style) {
            case STYLE_COMMAND: drawSpine(cv); break;
            case STYLE_IMPERIAL: drawChevrons(cv, fade); break;
            case STYLE_COLLECTIVE: drawTraces(cv, d); break;
        }
        super.dispatchDraw(cv);
    }

    /** Command: one console segment per row, growing down from the title key. */
    private void drawSpine(Canvas cv) {
        float grow = ease((progress - 0.12f) / 0.45f);
        if (grow <= 0) return;
        float cx = titleRect.centerX(), sw = dp(COMMAND_SPINE) / 2f;
        float reach = spineTop + (spineBottom - spineTop) * grow;
        int[] seg = {pal.c(1), pal.c(3), pal.c(5), pal.c(2)};
        paint.setStyle(Ui.cloak ? Paint.Style.STROKE : Paint.Style.FILL);
        paint.setStrokeWidth(dp(1.5f));
        float y = spineTop;
        int k = 0;
        while (y < reach) {
            float end = Math.min(y + rowH, reach);
            paint.setColor(seg[k++ % seg.length]);
            cv.drawRect(cx - sw, y, cx + sw, end, paint);
            y = end + gap;
        }
        paint.setStyle(Paint.Style.FILL);
    }

    /** Imperial: a band of chevrons and warning triangles under the title plate. */
    private void drawChevrons(Canvas cv, float alpha) {
        float w = bandW * ease(progress / 0.5f);
        if (w <= 0) return;
        float cx = titleRect.centerX(), y = spineTop, h = gap * 1.2f;
        ImperialDraw.row(cv, cx - w / 2f, y, w, h, new int[]{ImperialDraw.RED, ImperialDraw.ORANGE, ImperialDraw.YELLOW}, alpha);
        float s = dp(10);
        ImperialDraw.tri(cv, titleRect.left - s * 1.4f, titleRect.centerY(), s, s, false, pal.c(1), alpha);
        ImperialDraw.tri(cv, titleRect.right + s * 1.4f, titleRect.centerY(), s, s, false, pal.c(1), alpha);
    }

    /** Collective: curved traces from the hub to every node (following the nodes as they fly out). */
    private void drawTraces(Canvas cv, float d) {
        for (int i = 0; i < keys.size() && i < keyRects.length; i++) {
            View v = keys.get(i);
            if (v.getVisibility() != VISIBLE) continue;
            float[] k = keyRects[i];
            float x = (k[0] + k[2]) / 2f + v.getTranslationX(), y = (k[1] + k[3]) / 2f + v.getTranslationY();
            CollectiveDraw.curveTrace(cv, hubX, hubY, x, y, (i % 2 == 0 ? 0.18f : -0.18f), keyColors.get(i), d, keyProgress(i));
        }
        CollectiveDraw.ringNode(cv, hubX, hubY, titleRect.width() / 2f + dp(6), pal.c(1), d, ease(progress / 0.35f));
    }
}
