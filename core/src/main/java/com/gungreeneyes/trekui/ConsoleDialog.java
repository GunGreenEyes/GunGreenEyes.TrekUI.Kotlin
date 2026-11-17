package com.gungreeneyes.trekui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Modal panels in the current interface's style: option lists, text prompts and messages.
 *
 * <p>Every dialog is built by {@link #base}: a framed panel (console outline, faction tray or pipe
 * material) with a title strip and a scrolling body. Sizing is responsive: the panel is
 * {@link #MAX_WIDTH_DP} wide at most (times the window's {@link Ui#zoom}), never wider than the
 * window minus {@link #SIDE_MARGIN_DP}, so dialogs stay a readable column on tablets and desktop
 * windows instead of stretching across a 1280dp screen; the body scrolls once it would exceed
 * {@link #MAX_BODY_HEIGHT_FRACTION} of the window height (landscape phones included).
 */
final class ConsoleDialog {
    /** Callback with the index of the chosen option. */
    interface OnPick { void pick(int which); }
    /** Callback with the trimmed text the user accepted. */
    interface OnText { void text(String value); }

    /** Widest a dialog may be, in design dp (before zoom). */
    static final float MAX_WIDTH_DP = 460f;
    /** Space kept between the dialog and the window edges, in total (both sides), dp. */
    static final float SIDE_MARGIN_DP = 32f;
    /** Largest share of the window height the scrolling body may take. */
    static final float MAX_BODY_HEIGHT_FRACTION = 0.62f;
    /** Dim behind the dialog. */
    static final float DIM_AMOUNT = 0.7f;

    private ConsoleDialog() {}

    /** A dialog under construction: add views to {@link #body}, then show {@link #dialog}. */
    static final class Built {
        final Dialog dialog;
        final LinearLayout body;
        Built(Dialog d, LinearLayout b) { dialog = d; body = b; }
    }

    /**
     * Dialog width in dp for a window {@code windowWidthDp} wide: the design width scaled by
     * {@code zoom}, but always leaving {@link #SIDE_MARGIN_DP} free (and never below 1dp).
     */
    static float widthDpFor(float windowWidthDp, float zoom) {
        return Math.max(1f, Math.min(windowWidthDp - SIDE_MARGIN_DP * zoom, MAX_WIDTH_DP * zoom));
    }

    /** Maximum body height in px for a window {@code windowHeightPx} tall. */
    static int maxBodyHeightFor(int windowHeightPx) {
        return (int) (windowHeightPx * MAX_BODY_HEIGHT_FRACTION);
    }

    /** Builds the panel, title strip and scrolling body; the caller fills the body and shows it. */
    static Built base(Context c, String title) {
        Palette p = TrekPrefs.get(c).palette();
        final Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        Drawable frame;
        if (Skin.pipe()) frame = PipeDraw.dialog(c, p);
        else if (Skin.command()) frame = Ui.outline(c, Color.BLACK, p.c(1), 26, 2);
        else frame = Ui.outline(c, Skin.surface(), p.c(Skin.imperial() ? 0 : 1), Skin.trayRadius(26), 1.5f);
        root.setBackground(frame);
        int pad = Ui.dp(c, 14);
        root.setPadding(pad, pad, pad, pad);

        // title strip: left cap, rule, title
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View cap = new View(c);
        cap.setBackground(Ui.shape(c, p.c(0), Ui.LEFT, 16));
        head.addView(cap, new LinearLayout.LayoutParams(Ui.dp(c, 34), Ui.dp(c, 30)));
        View bar = new View(c);
        bar.setBackground(Skin.rule(c, p.c(0)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, Ui.dp(c, 30), 1);
        blp.leftMargin = Ui.dp(c, Ui.XS);
        head.addView(bar, blp);
        TextView t = Ui.oneLine(Ui.bold(c, title, 22, p.c(0)));
        t.setPadding(Ui.dp(c, Ui.M), 0, Ui.dp(c, Ui.XS), 0);
        head.addView(t);
        root.addView(head);

        ScreenMetrics m = ScreenMetrics.of(c);
        MaxHeightScrollView sv = new MaxHeightScrollView(c, maxBodyHeightFor(Math.round(m.heightDp * m.density)));
        sv.setVerticalScrollBarEnabled(true);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, Ui.dp(c, Ui.M), 0, 0);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int widthPx = Math.round(widthDpFor(m.widthDp, Ui.zoom(c)) * m.density);
            w.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = DIM_AMOUNT;
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        return new Built(d, body);
    }

    /** A full-width pill option button in {@code color}. */
    static TextView option(Context c, String label, int color, View.OnClickListener l) {
        TextView b = Ui.button(c, label, color, Ui.PILL, l);
        b.setMinHeight(Ui.dp(c, 52));
        Ui.setTextSp(b, 17);
        return b;
    }

    /** Appends an option with the standard spacing below it. */
    static void addOption(Built b, TextView v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(v.getContext(), Ui.S);
        b.body.addView(v, lp);
    }

    /** Shows a list of options; tapping one dismisses the dialog and reports its index. */
    static Dialog options(final Context c, String title, String[] items, final OnPick cb) {
        Palette p = TrekPrefs.get(c).palette();
        final Built b = base(c, title);
        for (int i = 0; i < items.length; i++) {
            final int which = i;
            addOption(b, option(c, items[i], p.c(i + 1), new View.OnClickListener() {
                @Override public void onClick(View v) {
                    b.dialog.dismiss();
                    cb.pick(which);
                }
            }));
        }
        b.dialog.show();
        return b.dialog;
    }

    /** Shows a message with buttons (the first in the primary colour); {@code cb} may be null. */
    static Dialog message(final Context c, String title, String msg, String[] buttons, final OnPick cb) {
        Palette p = TrekPrefs.get(c).palette();
        final Built b = base(c, title);
        TextView m = Ui.text(c, msg, 16, p.textHi);
        m.setLineSpacing(0, 1.15f);
        m.setPadding(Ui.dp(c, Ui.XS), 0, Ui.dp(c, Ui.XS), Ui.dp(c, Ui.L));
        b.body.addView(m);
        for (int i = 0; i < buttons.length; i++) {
            final int which = i;
            addOption(b, option(c, buttons[i], p.c(i == 0 ? 0 : i + 1), new View.OnClickListener() {
                @Override public void onClick(View v) {
                    b.dialog.dismiss();
                    if (cb != null) cb.pick(which);
                }
            }));
        }
        b.dialog.show();
        return b.dialog;
    }

    /** Asks for one line of text (upper-case keyboard); ACCEPT or the IME action reports it trimmed. */
    static Dialog prompt(final Context c, String title, String initial, String hint, final OnText cb) {
        Palette p = TrekPrefs.get(c).palette();
        final Built b = base(c, title);
        final EditText e = new EditText(c);
        e.setText(initial);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setTextColor(p.textHi);
        e.setHintTextColor(Ui.alpha(p.textLo, 0.6f));
        e.setTypeface(Ui.font(c, false));
        Ui.setTextSp(e, 19);
        e.setBackground(Ui.outline(c, p.panel, p.c(2), 18, 2));
        e.setPadding(Ui.dp(c, Ui.L), Ui.dp(c, Ui.M), Ui.dp(c, Ui.L), Ui.dp(c, Ui.M));
        e.setSelection(e.getText().length());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(c, Ui.M);
        b.body.addView(e, lp);
        final Runnable ok = new Runnable() {
            @Override public void run() {
                b.dialog.dismiss();
                cb.text(e.getText().toString().trim());
            }
        };
        e.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent ev) {
                ok.run();
                return true;
            }
        });
        LinearLayout row = new LinearLayout(c);
        TextView cancel = option(c, "CANCEL", p.c(3), new View.OnClickListener() {
            @Override public void onClick(View v) { Sfx.play(c, Sfx.CANCEL); b.dialog.dismiss(); }
        });
        TextView accept = option(c, "ACCEPT", p.c(0), new View.OnClickListener() {
            @Override public void onClick(View v) { Sfx.play(c, Sfx.CONFIRM); ok.run(); }
        });
        LinearLayout.LayoutParams h = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        h.rightMargin = Ui.dp(c, Ui.S);
        row.addView(cancel, h);
        row.addView(accept, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        b.body.addView(row);
        Window w = b.dialog.getWindow();
        if (w != null) {
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        b.dialog.show();
        e.requestFocus();
        return b.dialog;
    }

    /**
     * A ScrollView that wraps its content up to a maximum height, then scrolls. Replaces the old
     * global-layout listener, which only ever shrank the view and stayed registered for the dialog's life.
     */
    static final class MaxHeightScrollView extends ScrollView {
        private final int maxHeightPx;

        MaxHeightScrollView(Context c, int maxHeightPx) {
            super(c);
            this.maxHeightPx = maxHeightPx;
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int mode = MeasureSpec.getMode(heightSpec);
            int size = MeasureSpec.getSize(heightSpec);
            int capped = mode == MeasureSpec.UNSPECIFIED ? maxHeightPx : Math.min(size, maxHeightPx);
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(capped, MeasureSpec.AT_MOST));
        }
    }
}
