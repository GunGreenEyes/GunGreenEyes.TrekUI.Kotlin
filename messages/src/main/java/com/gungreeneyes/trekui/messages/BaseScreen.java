package com.gungreeneyes.trekui.messages;

import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.gungreeneyes.trekui.MessagesLook;
import com.gungreeneyes.trekui.ScreenMetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Base of every Messages screen: edge-to-edge window, a root that pads itself for the system bars,
 * cutouts and the keyboard, the interface's console frame (title, footer, sidebar of buttons, content),
 * background loading with main-thread delivery, and the theme follow: every resume re-reads the launcher's
 * interface ({@link MessagesTheme#refresh}) and rebuilds the screen when it changed. Screens handle
 * rotation and resizing themselves (configChanges in the manifest) by rebuilding.
 */
public abstract class BaseScreen extends ComponentActivity {
    /** Sidebar width in design dp (wider on tablets via the frame's zoom). */
    static final float SIDEBAR_DP = 84f;

    private static final ExecutorService LOADER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "messages-load");
        t.setDaemon(true);
        return t;
    });
    protected final Handler main = new Handler(Looper.getMainLooper());
    protected FrameLayout root;
    protected ScreenMetrics metrics;
    private int lastUiMode = -1;

    /** A sidebar button: label, colour slot and action. */
    protected static final class SideButton {
        final String label;
        final int accent;
        final View.OnClickListener action;
        final String tag;

        SideButton(String label, int accent, String tag, View.OnClickListener action) {
            this.label = label;
            this.accent = accent;
            this.tag = tag;
            this.action = action;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        rebuild();
    }

    @Override
    protected void onResume() {
        super.onResume();
        MessagesTheme.refresh(this, this::rebuild);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        rebuild();
    }

    /** Re-reads the look and builds the whole screen again (keeps the screen's own state fields). */
    protected final void rebuild() {
        MessagesLook.load(this);
        metrics = ScreenMetrics.of(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(MessagesLook.bg());
        root.setOnApplyWindowInsetsListener((view, raw) -> {
            WindowInsetsCompat w = WindowInsetsCompat.toWindowInsetsCompat(raw, view);
            Insets bars = w.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = w.getInsets(WindowInsetsCompat.Type.ime());
            int side = metrics.isTwoPane() ? metrics.marginPx() : MessagesLook.dp(this, 8f);
            root.setPadding(bars.left + side, bars.top + MessagesLook.dp(this, 4f), bars.right + side,
                    Math.max(bars.bottom, ime.bottom) + MessagesLook.dp(this, 6f));
            return raw;
        });
        build();
        setContentView(root);
        root.requestApplyInsets();
    }

    /** Builds the screen into {@link #root} (called on create, theme change and configuration change). */
    protected abstract void build();

    /** Puts {@code content} into the console frame with a title, footer and sidebar buttons. */
    protected void frame(String title, String footer, List<SideButton> buttons, View content) {
        TextView t = MessagesLook.oneLine(MessagesLook.bold(this, title, 28f, MessagesLook.accent(0)));
        t.setPadding(MessagesLook.dp(this, 4f), 0, MessagesLook.dp(this, 2f), 0);
        TextView f = MessagesLook.oneLine(MessagesLook.text(this, footer, 14f, MessagesLook.accent(1)));
        f.setPadding(MessagesLook.dp(this, 4f), 0, MessagesLook.dp(this, 2f), 0);
        ViewGroup frame = MessagesLook.frame(this, t, f, sidebar(buttons), content, SIDEBAR_DP);
        root.addView(frame, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** The sidebar: a scrolling column of square console buttons. */
    private View sidebar(List<SideButton> buttons) {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < buttons.size(); i++) {
            SideButton b = buttons.get(i);
            int color = MessagesLook.accent(b.accent);
            TextView v = MessagesLook.button(this, b.label, color, MessagesLook.SQUARE, b.action);
            v.setBackground(MessagesLook.touchCell(this, color, MessagesLook.SQUARE, 0f));
            MessagesLook.setTextSp(v, 14f);
            v.setPadding(MessagesLook.dp(this, 4f), 0, MessagesLook.dp(this, 8f), MessagesLook.dp(this, 4f));
            v.setTag(b.tag);
            v.setContentDescription(b.label);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    MessagesLook.dp(this, 50f));
            if (i > 0) lp.topMargin = MessagesLook.dp(this, MessagesLook.GUTTER);
            column.addView(v, lp);
        }
        scroll.addView(column);
        return scroll;
    }

    /** A new, empty sidebar button list. */
    protected static List<SideButton> buttons() {
        return new ArrayList<>();
    }

    /** Runs {@code work} in the background and hands its result to {@code done} on the main thread. */
    protected <T> void load(Supplier<T> work, Consumer<T> done) {
        LOADER.execute(() -> {
            T result;
            try {
                result = work.get();
            } catch (RuntimeException e) {
                result = null;
            }
            final T r = result;
            main.post(() -> {
                if (!isDestroyed()) done.accept(r);
            });
        });
    }

    /** True if {@code permission} is granted. */
    protected boolean has(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** Version name for the footer. */
    protected String version() {
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
