package com.gungreeneyes.trekui.licensing;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.gungreeneyes.trekui.ScreenMetrics;
import com.gungreeneyes.trekui.core.CoreConfig;
import com.gungreeneyes.trekui.core.SuiteClient;
import java.util.Locale;

/**
 * Full-screen console panel that explains the trial and sells the one-time unlock.
 *
 * <p>Opened in two modes:
 * <ul>
 *   <li><b>expired</b> &ndash; by {@link Licensing#onHomeResumed(Activity)} when the trial session
 *       is over. Offers BUY, RESTORE PURCHASE, NEW SESSION (enabled after a short countdown) and, in
 *       the launcher ({@link CoreConfig#homeApp}), USE ANOTHER HOME APP, so the device is never stuck:
 *       the system home picker and Settings are always one tap away, and Recents / the notification
 *       shade keep working. Companion apps offer CLOSE instead.</li>
 *   <li><b>voluntary</b> &ndash; from {@link Licensing#openPurchaseScreen(Context)}, to buy early.</li>
 * </ul>
 * Drawn with plain views plus a small Canvas elbow header in the classic console palette and the
 * launcher's Antonio typeface, sized with {@link ScreenMetrics} so it fits phones and tablets in
 * either orientation, edge-to-edge with system-bar/cutout insets applied as padding.
 *
 * <p>Shared by every TrekUI app: the wording uses {@link CoreConfig#appName}, and every variant says
 * that one purchase unlocks every TrekUI app. RESTORE PURCHASE checks this app's own Play purchase and
 * every installed sibling app ({@link SuiteClient}).
 *
 * <p>develop flavor only: long-pressing the header simulates a purchase (and long-pressing it
 * again while simulated clears it), for tests and screenshots.
 */
public final class PurchaseActivity extends Activity implements BillingManager.Listener {
    static final String EXTRA_EXPIRED = "com.gungreeneyes.trekui.licensing.EXPIRED";

    /** Seconds before NEW SESSION becomes available on the expiry screen. */
    static final int RELAUNCH_DELAY_SECONDS = 10;
    /** Price shown until Play has returned the localized one. */
    private static final String FALLBACK_PRICE = "$0.99";

    private static final int BLACK = Color.BLACK;
    private static final int ORANGE = Color.parseColor("#FF9C00");
    private static final int LAVENDER = Color.parseColor("#B4A0F0");
    private static final int BLUE = Color.parseColor("#99B8FF");
    private static final int PINK = Color.parseColor("#DD88AA");
    private static final int GOLD = Color.parseColor("#FFCC66");
    private static final int TEXT_HI = Color.parseColor("#E6DEFF");
    private static final int ALERT = Color.parseColor("#FF4433");
    private static final int RIPPLE = 0x70FFFFFF;

    private boolean expired;
    private Typeface regular;
    private Typeface heavy;
    private ScreenMetrics metrics;
    private TextView status;
    private TextView buyButton;
    private TextView relaunchButton;
    private int countdown;
    private boolean closing;
    private CoreConfig config;

    /** Opens the screen; {@code expired} selects the session-over wording and buttons. */
    static void start(Context context, boolean expired) {
        Intent intent = new Intent(context, PurchaseActivity.class).putExtra(EXTRA_EXPIRED, expired);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        expired = getIntent().getBooleanExtra(EXTRA_EXPIRED, false);
        config = CoreConfig.get(this);
        metrics = ScreenMetrics.of(this);
        loadFonts();
        // API 35+ is always edge-to-edge; on 30-34 opt in so the black panel runs under the bars.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        setContentView(buildContent());
        if (expired) startCountdown();
    }

    @Override
    protected void onStart() {
        super.onStart();
        BillingManager billing = Licensing.billing();
        billing.addListener(this);
        billing.refreshPurchases(); // "restore on open": also finishes pending purchases
    }

    @Override
    protected void onStop() {
        Licensing.billing().removeListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (relaunchButton != null) relaunchButton.removeCallbacks(tick);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- layout

    private View buildContent() {
        int margin = Math.max(metrics.marginPx(), dp(16));
        int gutter = Math.max(metrics.gutterPx(), dp(8));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(margin, margin, margin, margin);

        HeaderView header = new HeaderView(this, expired ? "SESSION COMPLETE" : "UNLOCK " + config.appName);
        header.setOnLongClickListener(v -> simulatePurchase());
        column.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int elbowInset = dp(HeaderView.SIDEBAR_DP) + gutter * 2;
        body.setPadding(elbowInset, gutter * 2, 0, 0);
        column.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        String lead = expired
                ? "YOUR " + describeSessionAdjective(config.trialSessionMinutes) + " TRIAL SESSION HAS ENDED."
                : config.appName + " IS FREE TO TRY IN SESSIONS OF " + describeSession(config.trialSessionMinutes) + ".";
        body.addView(text(lead, 24, ORANGE, true));
        body.addView(spacer(gutter));
        body.addView(text(pitch(config.appName, config.homeApp), 16, TEXT_HI, false));
        body.addView(spacer(gutter));
        body.addView(text(SUITE_LINE, 16, GOLD, true));
        body.addView(spacer(gutter));
        String note;
        if (config.homeApp) {
            note = expired
                    ? "Until then you can start a new trial session below, or switch to another home "
                    + "app at any time. Nothing on your device has been changed."
                    : "You can switch to another home app at any time.";
        } else {
            note = expired ? "Until then you can start a new trial session below. Nothing on your device "
                    + "has been changed." : "";
        }
        if (!note.isEmpty()) body.addView(text(note, 16, LAVENDER, false));

        status = text("", 15, GOLD, true);
        status.setPadding(0, gutter * 2, 0, gutter);
        body.addView(status);

        buyButton = button("BUY " + FALLBACK_PRICE, ORANGE, v -> buy());
        TextView restore = button("RESTORE PURCHASE", BLUE, v -> restore());
        TextView other = config.homeApp
                ? button("USE ANOTHER HOME APP", LAVENDER, v -> openHomeSettings())
                : (expired ? button("CLOSE", LAVENDER, v -> finishAffinity()) : null);
        if (expired) {
            relaunchButton = button("NEW SESSION", PINK, v -> relaunch());
            relaunchButton.setEnabled(false);
        }
        TextView close = expired ? null : button("NOT NOW", PINK, v -> finish());
        body.addView(buttonGrid(gutter, buyButton, restore, expired ? relaunchButton : close, other));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setBackgroundColor(BLACK);
        scroll.setClipToPadding(false);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            applyInsets(v, insets);
            return insets;
        });
        return scroll;
    }

    /** Pads the scroll view by the system bars and display cutout so nothing sits under them. */
    @SuppressWarnings("deprecation")
    private static void applyInsets(View v, WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
        } else {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
        }
    }

    /** Buttons in one column on compact portrait screens, two columns when there is room. */
    private View buttonGrid(int gutter, TextView... buttons) {
        int columns = (metrics.isLandscape() || metrics.isTwoPane()) ? 2 : 1;
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        int inRow = 0;
        for (TextView b : buttons) {
            if (b == null) continue;
            if (row == null || inRow == columns) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.topMargin = gutter;
                grid.addView(row, rp);
                inRow = 0;
            }
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (inRow > 0) bp.leftMargin = gutter;
            row.addView(b, bp);
            inRow++;
        }
        if (row != null) {
            while (inRow < columns) { // keep the last row's buttons the same width as the others
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, 1, 1f);
                bp.leftMargin = gutter;
                row.addView(new View(this), bp);
                inRow++;
            }
        }
        return grid;
    }

    private TextView text(CharSequence value, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * Math.min(metrics.uiScale, 1.3f));
        t.setTypeface(bold ? heavy : regular);
        t.setLineSpacing(0, 1.1f);
        return t;
    }

    /**
     * console pill button: label bottom-right, black text on a coloured pill, min 48dp tall. The label
     * shrinks to fit the pill (long labels like RESTORE PURCHASE were clipped on narrow phones), and
     * TalkBack announces it as a button whose description follows the live label (price, countdown).
     */
    private TextView button(String label, int color, View.OnClickListener onClick) {
        TextView b = text(label.toUpperCase(Locale.ROOT), 17, BLACK, true);
        b.setGravity(Gravity.BOTTOM | Gravity.END);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setMinHeight(dp(56));
        b.setPadding(dp(16), dp(6), dp(18), dp(8));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(new ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{Color.argb(255, 60, 56, 70), color}));
        pill.setCornerRadius(dp(999));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), pill, null));
        b.setOnClickListener(onClick);
        b.setContentDescription(label);
        b.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        b.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        float baseSize = b.getTextSize();
        Runnable fit = () -> fitLabel(b, baseSize);
        b.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            if (r - l != or - ol) v.post(fit);
        });
        b.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                b.setContentDescription(s.toString());
                b.post(fit);
            }
        });
        return b;
    }

    /** Shrinks a single-line label (never below 60% of {@code baseSize}) so it fits its button. */
    private static void fitLabel(TextView b, float baseSize) {
        float avail = b.getWidth() - b.getTotalPaddingLeft() - b.getTotalPaddingRight();
        if (avail <= 0) return;
        TextPaint p = new TextPaint(b.getPaint());
        p.setTextSize(baseSize);
        float w = p.measureText(b.getText().toString());
        float size = w <= avail ? baseSize : Math.max(baseSize * 0.6f, baseSize * avail / w * 0.98f);
        if (Math.abs(size - b.getTextSize()) > 0.5f) b.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
    }

    private View spacer(int height) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, height));
        return v;
    }

    private void loadFonts() {
        try {
            regular = new Typeface.Builder(getAssets(), "fonts/Antonio.ttf").setFontVariationSettings("'wght' 500").build();
            heavy = new Typeface.Builder(getAssets(), "fonts/Antonio.ttf").setFontVariationSettings("'wght' 700").build();
        } catch (RuntimeException e) {
            regular = null;
            heavy = null;
        }
        if (regular == null) regular = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
        if (heavy == null) heavy = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    /** Shown on every purchase screen of every TrekUI app (owner rule: one purchase, every app). */
    static final String SUITE_LINE = "One purchase unlocks every TrekUI app.";

    /**
     * The main explanation. The launcher keeps its home-screen wording; companions say "keep using".
     * {@code appName} is the upper-case display name (e.g. "TREKUI MESSAGES").
     */
    static String pitch(String appName, boolean homeApp) {
        String name = displayName(appName);
        String restart = homeApp ? "the launcher" : name;
        String goal = homeApp ? "To use " + name + " as your permanent home screen"
                : "To keep using " + name + " without trial sessions";
        return name + " is free to download and has no ads. Every feature works during a "
                + "trial session; a new session starts after you restart " + restart + " or your device. "
                + goal + ", unlock it once with a single purchase "
                + "of " + FALLBACK_PRICE + " (US price; Google Play shows your local price). There is "
                + "no subscription, and the purchase covers every device signed in to your Google account.";
    }

    /** "TREKUI MESSAGES" becomes "TrekUI Messages" (the brand keeps its capitals). */
    static String displayName(String appName) {
        if (appName == null || appName.trim().isEmpty()) return "TrekUI";
        StringBuilder sb = new StringBuilder();
        for (String w : appName.trim().split(" +")) {
            if (sb.length() > 0) sb.append(' ');
            if (w.equalsIgnoreCase("TREKUI")) sb.append("TrekUI");
            else sb.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /** Noun form: "24 HOURS", "5 MINUTES", "90 MINUTES" ("in sessions of 5 MINUTES"). */
    static String describeSession(long minutes) {
        if (minutes >= 60 && minutes % 60 == 0) {
            long h = minutes / 60;
            return h + (h == 1 ? " HOUR" : " HOURS");
        }
        return minutes + (minutes == 1 ? " MINUTE" : " MINUTES");
    }

    /** Adjective form, always singular and hyphenated: "24-HOUR", "5-MINUTE" ("your 5-MINUTE trial"). */
    static String describeSessionAdjective(long minutes) {
        if (minutes >= 60 && minutes % 60 == 0) return (minutes / 60) + "-HOUR";
        return minutes + "-MINUTE";
    }

    // ---------------------------------------------------------------- actions

    private void buy() {
        BillingManager.LaunchResult r = Licensing.billing().launchPurchase(this);
        if (r == BillingManager.LaunchResult.ALREADY_UNLOCKED) onUnlocked();
    }

    private void restore() {
        status.setText("CHECKING GOOGLE PLAY FOR YOUR PURCHASE…");
        Licensing.billing().refreshPurchases();
        // A purchase made in any other TrekUI app counts too; a grant notifies the billing listeners.
        SuiteClient.checkSiblingsAsync(this, null);
    }

    /** Opens the system "Default home app" screen; falls back to Settings if a ROM lacks it. */
    private void openHomeSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (ActivityNotFoundException ignored) {
                Toast.makeText(this, "Open Settings > Apps > Default apps > Home app", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Explicit relaunch: a fresh trial session starts and the home screen returns. */
    private void relaunch() {
        Licensing.startNewSession();
        finish();
    }

    /** develop flavor only: toggles a simulated purchase. Returns false (not consumed) otherwise. */
    private boolean simulatePurchase() {
        if (!Licensing.isDevelopFlavor()) return false;
        Entitlement ent = Licensing.entitlement();
        boolean nowSimulated = ent.source() != Entitlement.Source.SIMULATED;
        ent.setSimulatedPurchase(nowSimulated);
        Toast.makeText(this, nowSimulated ? "DEV: purchase simulated" : "DEV: simulated purchase cleared",
                Toast.LENGTH_SHORT).show();
        Licensing.billing().notifyListeners();
        return true;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (relaunchButton == null) return;
            if (countdown <= 0) {
                relaunchButton.setText("NEW SESSION");
                relaunchButton.setEnabled(true);
                return;
            }
            relaunchButton.setText("NEW SESSION IN " + countdown);
            countdown--;
            relaunchButton.postDelayed(this, 1000L);
        }
    };

    private void startCountdown() {
        countdown = RELAUNCH_DELAY_SECONDS;
        tick.run();
    }

    private void onUnlocked() {
        if (closing) return;
        closing = true;
        status.setTextColor(GOLD);
        status.setText(config.homeApp ? "UNLOCKED. THANK YOU — TREKUI IS NOW YOUR PERMANENT LAUNCHER."
                : "UNLOCKED. THANK YOU — EVERY TREKUI APP IS NOW UNLOCKED.");
        buyButton.setEnabled(false);
        status.postDelayed(this::finish, 1800L);
    }

    @Override
    public void onBillingStateChanged(BillingManager.State state) {
        if (state.unlocked) {
            onUnlocked();
            return;
        }
        buyButton.setText("BUY " + (state.formattedPrice != null ? state.formattedPrice : FALLBACK_PRICE));
        if (state.message != null) {
            status.setTextColor(state.pending ? GOLD : ALERT);
            status.setText(state.message);
        } else if (state.pending) {
            status.setTextColor(GOLD);
            status.setText("PURCHASE PENDING — IT UNLOCKS AS SOON AS PAYMENT COMPLETES");
        } else {
            status.setText("");
        }
    }

    // ---------------------------------------------------------------- header

    /**
     * console elbow: a vertical sidebar joined to a top bar by a rounded outer corner and a smaller
     * inner curve, with the title in the bar's cut-out at the right, plus a few accent segments.
     */
    private final class HeaderView extends View {
        static final int SIDEBAR_DP = 72;
        private final String title;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path elbow = new Path();
        private final RectF arc = new RectF();

        HeaderView(Context context, String title) {
            super(context);
            this.title = title;
            label.setTypeface(heavy);
            label.setColor(ORANGE);
            label.setTextAlign(Paint.Align.RIGHT);
            setContentDescription(title);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float side = dp(SIDEBAR_DP);
            float bar = h * 0.32f;
            float outer = Math.min(h * 0.9f, side);
            float inner = bar * 0.9f;
            float gap = dp(6);

            label.setTextSize(bar * 1.15f);
            float titleWidth = label.measureText(title);
            float barEnd = Math.max(side + outer, w - titleWidth - gap * 3);

            // Elbow: sidebar down the left, bar across the top, rounded outside, curved inside.
            elbow.reset();
            elbow.moveTo(0, h);
            elbow.lineTo(0, outer);
            arc.set(0, 0, outer * 2, outer * 2);
            elbow.arcTo(arc, 180, 90, false);
            elbow.lineTo(barEnd, 0);
            elbow.lineTo(barEnd, bar);
            elbow.lineTo(side + inner, bar);
            arc.set(side, bar, side + inner * 2, bar + inner * 2);
            elbow.arcTo(arc, 270, -90, false);
            elbow.lineTo(side, h);
            elbow.close();
            fill.setColor(ORANGE);
            canvas.drawPath(elbow, fill);

            // Title in the cut-out, baseline aligned with the bottom of the bar.
            canvas.drawText(title, w, bar - label.descent() * 0.2f, label);

            // Accent segments under the title.
            float segTop = bar + gap * 2;
            float segH = Math.max(dp(10), bar * 0.35f);
            int[] colors = {LAVENDER, BLUE, PINK};
            float start = side + inner * 2 + gap;
            float segW = (w - start - gap * (colors.length - 1)) / colors.length;
            for (int i = 0; i < colors.length; i++) {
                fill.setColor(colors[i]);
                float l = start + i * (segW + gap);
                canvas.drawRect(l, segTop, l + segW, segTop + segH, fill);
            }
        }
    }
}
