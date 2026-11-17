package com.gungreeneyes.trekui;

import android.app.Notification;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Unread-notification counts drawn as badges on home tiles, reveal-hub nodes and dock keys, in
 * every interface (console pill, Imperial blade, Collective block, pipe wax seal).
 *
 * <p>Counts come from {@link MediaListener} (the notification listener the owner grants in
 * Android's "notification access"); without that grant there are simply no badges. They are held
 * in memory only, never written to disk, and are recomputed only while the home screen is resumed.
 * A notification counts when it is from another app, clearable (not an ongoing / foreground
 * service notification), allowed to show a badge (Android's per-app / per-channel "notification
 * dot" setting) and not a group summary whose children are also listed; it counts its
 * {@link Notification#number} when the app sets one, else 1. Counting is pure ({@link #count}).
 *
 * <p>Badges are a {@link Drawable} on the view's overlay, so they decorate any tile view without
 * changing its layout; {@link #attach} is the one hook each home builder calls, and
 * {@link #refresh} re-reads the counts for every decorated view under a root.
 */
final class NotificationBadges {
    /** Key for the total over all apps (shown on tiles assigned to the console shade). */
    static final String ALL = "*";
    /** Preference: show badges (default on). */
    static final String PREF = "notifBadges";
    /** Where the badge sits: the view's top-end corner, bottom-start corner, or the top-end of a centred icon. */
    static final int TOP_END = 0, BOTTOM_START = 1, ICON = 2;
    /** Largest number drawn; more shows as "99+". */
    static final int MAX_SHOWN = 99;

    private static final float BADGE_H_DP = 18, TEXT_SP = 11, INSET_DP = 5, PAD_DP = 5;

    /** The current counts by package (and {@link #ALL}); replaced wholesale, never mutated. */
    private static volatile Map<String, Integer> counts = Collections.emptyMap();

    /** One active notification, reduced to what counting needs. */
    static final class Entry {
        final String pkg, group;
        final boolean summary, ongoing, canBadge;
        final int number;

        Entry(String pkg, String group, boolean summary, boolean ongoing, boolean canBadge, int number) {
            this.pkg = pkg;
            this.group = group;
            this.summary = summary;
            this.ongoing = ongoing;
            this.canBadge = canBadge;
            this.number = number;
        }
    }

    private NotificationBadges() {}

    // ---- counting (pure) ----------------------------------------------------------------------------

    /**
     * Unread counts by package, plus the total under {@link #ALL} (see the class comment for the
     * rules). Packages with nothing to count are absent.
     */
    static Map<String, Integer> count(List<Entry> entries, String ownPackage) {
        Set<String> groupsWithChildren = new HashSet<>();
        for (Entry e : entries) if (!e.summary && e.group != null) groupsWithChildren.add(e.pkg + "|" + e.group);
        Map<String, Integer> out = new HashMap<>();
        int total = 0;
        for (Entry e : entries) {
            if (e.pkg == null || e.pkg.equals(ownPackage) || !e.canBadge || e.ongoing) continue;
            if (e.summary && e.group != null && groupsWithChildren.contains(e.pkg + "|" + e.group)) continue;
            int n = e.number > 0 ? e.number : 1;
            Integer had = out.get(e.pkg);
            out.put(e.pkg, (had == null ? 0 : had) + n);
            total += n;
        }
        if (total > 0) out.put(ALL, total);
        return out;
    }

    /** Text for a count: "" for none, the number up to {@link #MAX_SHOWN}, then "99+". */
    static String label(int count) {
        if (count <= 0) return "";
        return count > MAX_SHOWN ? MAX_SHOWN + "+" : String.valueOf(count);
    }

    /** The package of a flattened ComponentName ("pkg/.Activity" → "pkg"), or null. */
    static String packageOf(String flatComponent) {
        if (flatComponent == null) return null;
        int slash = flatComponent.indexOf('/');
        return slash <= 0 ? null : flatComponent.substring(0, slash);
    }

    /** What a tile's badge counts: its app's package, {@link #ALL} for the console shade, else null. */
    static String keyFor(Tile tile) {
        if (tile == null) return null;
        if (tile.comp != null) return packageOf(tile.comp);
        return Tile.A_SHADE.equals(tile.action) ? ALL : null;
    }

    /** The current count for a key (package or {@link #ALL}). */
    static int countFor(String key) {
        Integer n = key == null ? null : counts.get(key);
        return n == null ? 0 : n;
    }

    // ---- live counts ------------------------------------------------------------------------------

    /** Forgets every count (notification access lost). */
    static void clear() { counts = Collections.emptyMap(); }

    /**
     * Recomputes the counts from the listener's active notifications (main thread, after a
     * debounced change). Clears them when badges are switched off in CONFIG.
     */
    static void update(NotificationListenerService listener, Context c) {
        if (listener == null || !Prefs.get(c).b(PREF, true)) {
            clear();
            return;
        }
        StatusBarNotification[] active;
        try {
            active = listener.getActiveNotifications();
        } catch (RuntimeException e) {
            return; // listener not yet (or no longer) connected
        }
        NotificationListenerService.RankingMap map = null;
        try {
            map = listener.getCurrentRanking();
        } catch (RuntimeException ignored) {
            // no ranking: assume badges allowed
        }
        NotificationListenerService.Ranking ranking = new NotificationListenerService.Ranking();
        List<Entry> entries = new ArrayList<>();
        if (active != null) {
            for (StatusBarNotification s : active) {
                Notification n = s.getNotification();
                if (n == null) continue;
                boolean canBadge = map == null || !map.getRanking(s.getKey(), ranking) || ranking.canShowBadge();
                entries.add(new Entry(s.getPackageName(), n.getGroup(), (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0,
                        !s.isClearable(), canBadge, n.number));
            }
        }
        counts = Collections.unmodifiableMap(count(entries, c.getPackageName()));
    }

    // ---- decoration ---------------------------------------------------------------------------------

    /**
     * Decorates a tile view with its badge (or removes it when the tile has nothing to count).
     *
     * @param anchor     {@link #TOP_END}, {@link #BOTTOM_START} or {@link #ICON}
     * @param iconHalfPx for {@link #ICON}: half the centred icon's size, in px
     */
    static void attach(View view, Tile tile, int anchor, int iconHalfPx) {
        attach(view, keyFor(tile), anchor, iconHalfPx);
    }

    /** Decorates a view with the badge for {@code key} (a package or {@link #ALL}); null removes it. */
    static void attach(final View view, String key, int anchor, int iconHalfPx) {
        if (view == null) return;
        Object old = view.getTag(R.id.trekui_notification_badge);
        if (old instanceof Badge) {
            Badge b = (Badge) old;
            view.getOverlay().remove(b);
            view.removeOnLayoutChangeListener(b);
            if (b.baseDescription != null) view.setContentDescription(b.baseDescription);
            view.setTag(R.id.trekui_notification_badge, null);
        }
        if (key == null) return;
        final Badge badge = new Badge(view.getContext(), key, anchor, iconHalfPx, view.getContentDescription());
        view.setTag(R.id.trekui_notification_badge, badge);
        badge.setBounds(0, 0, view.getWidth(), view.getHeight());
        view.addOnLayoutChangeListener(badge);
        view.getOverlay().add(badge);
        badge.sync(view);
    }

    /** Re-reads the counts for every decorated view under {@code root}. */
    static void refresh(View root) {
        if (root == null) return;
        Object tag = root.getTag(R.id.trekui_notification_badge);
        if (tag instanceof Badge) ((Badge) tag).sync(root);
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) refresh(g.getChildAt(i));
        }
    }

    /** The badge itself: a count in the current interface's shape, drawn on the view's overlay. */
    private static final class Badge extends Drawable implements View.OnLayoutChangeListener {
        private final String key;
        private final int anchor, iconHalfPx;
        private final CharSequence baseDescription;
        private final String descriptionFormat;
        private final float density;
        private final int fill, ink, edge;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();
        private String text = "";

        Badge(Context c, String key, int anchor, int iconHalfPx, CharSequence baseDescription) {
            this.key = key;
            this.anchor = anchor;
            this.iconHalfPx = iconHalfPx;
            this.baseDescription = baseDescription;
            this.descriptionFormat = c.getString(R.string.badge_cd);
            this.density = c.getResources().getDisplayMetrics().density * Ui.zoom(c);
            Palette pal = Prefs.get(c).palette();
            if (Skin.pipe()) {
                fill = PipeDraw.RED_INK;
                ink = 0xFFF6E7C8;
            } else if (Skin.collective()) {
                fill = pal.c(0);
                ink = Color.BLACK;
            } else {
                fill = pal.alert;
                ink = Color.BLACK;
            }
            edge = Skin.pipe() ? Ui.darken(PipeDraw.RED_INK, 0.6f) : Color.BLACK;
            paint.setTypeface(Ui.font(c, true));
            paint.setTextSize(TEXT_SP * density * Skin.textScale());
            paint.setTextAlign(Paint.Align.CENTER);
        }

        /** Updates the text and the view's spoken description from the current count. */
        void sync(View view) {
            int n = countFor(key);
            String now = label(n);
            if (!now.equals(text)) {
                text = now;
                invalidateSelf();
                view.invalidate();
            }
            if (baseDescription != null) {
                view.setContentDescription(n > 0 ? String.format(Locale.getDefault(), descriptionFormat, baseDescription, n) : baseDescription);
            }
        }

        @Override public void onLayoutChange(View v, int l, int t, int right, int bottom, int ol, int ot, int or, int ob) {
            setBounds(0, 0, right - l, bottom - t);
            v.invalidate();
        }

        @Override public void draw(Canvas canvas) {
            if (text.isEmpty()) return;
            android.graphics.Rect b = getBounds();
            float h = BADGE_H_DP * density;
            float w = Math.max(h, paint.measureText(text) + 2 * PAD_DP * density);
            float inset = INSET_DP * density;
            float left, top;
            if (anchor == BOTTOM_START) {
                left = b.left + inset;
                top = b.bottom - inset - h;
            } else if (anchor == ICON) {
                left = b.exactCenterX() + iconHalfPx - w * 0.45f;
                top = b.exactCenterY() - iconHalfPx - h * 0.35f;
            } else {
                left = b.right - inset - w;
                top = b.top + inset;
            }
            left = Math.max(b.left, Math.min(left, b.right - w));
            top = Math.max(b.top, Math.min(top, b.bottom - h));
            r.set(left, top, left + w, top + h);
            shape();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.cloak ? Ui.alpha(fill, 0.7f) : fill);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * density);
            paint.setColor(edge);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ink);
            Paint.FontMetrics fm = paint.getFontMetrics();
            canvas.drawText(text, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, paint);
        }

        /** The badge outline for the current interface. */
        private void shape() {
            path.reset();
            float h = r.height();
            if (Skin.imperial()) {
                float cut = h * 0.35f; // blade: clipped corners, pointed ends
                path.moveTo(r.left + cut, r.top);
                path.lineTo(r.right, r.top);
                path.lineTo(r.right - cut, r.bottom);
                path.lineTo(r.left, r.bottom);
                path.close();
            } else if (Skin.collective()) {
                path.addRect(r, Path.Direction.CW);
            } else if (Skin.pipe()) {
                path.addRoundRect(r, h * 0.5f, h * 0.5f, Path.Direction.CW);
            } else {
                path.addRoundRect(r, h / 2, h / 2, Path.Direction.CW); // console pill
            }
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
