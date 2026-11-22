package com.mg4.launcher.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Piccoli costruttori di viste: tutta l'interfaccia è creata da codice. */
public final class Ui {
    private Ui() {}

    public static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    public static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    public static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    /** Dimensione dei comandi toccabili: 1 Normale, 1,15 Grande, 1,3 Molto grande. */
    public static float K = 1f;

    // ---- animazioni calme (1.0.5) ----------------------------------------------------------------
    // Interruttore unico, aggiornato dal vivo dall'impostazione "Animazioni morbide". Quando è spento,
    // ogni helper non fa nulla (le viste appaiono subito), così è anche una "riduci movimento".
    public static boolean animations = true;

    /** Dissolvenza in entrata (pannelli, copertina al cambio brano). */
    public static void fadeIn(View v) {
        if (!animations || v == null) return;
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(220).start();
    }

    /** Comparsa morbida di una finestra: velo in dissolvenza, scheda che sale appena di scala. */
    public static void popIn(View card, View dim) {
        fadeIn(dim);
        if (!animations || card == null) return;
        card.setAlpha(0f);
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start();
    }

    /** Micro-rimbalzo al tocco di un pulsante (abbinato al suono di tap). */
    public static void tapPulse(final View v) {
        if (!animations || v == null) return;
        v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(130).start()).start();
    }

    /** Misura di un comando toccabile (dp × K). */
    public static int tp(Context c, float v) {
        return dp(c, v * K);
    }

    /** Testo scritto su un comando toccabile (sp × K). */
    public static float ts(float sp) {
        return sp * K;
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** Sfondo con effetto pressione (ripple) dentro una forma arrotondata. */
    public static Drawable pressable(Context c, int color, float radiusDp, Theme t) {
        int r = dp(c, radiusDp);
        int ripple = t.day ? 0x22000000 : 0x33FFFFFF;
        return new RippleDrawable(ColorStateList.valueOf(ripple), Color.alpha(color) == 0 ? null : round(color, r), round(Color.WHITE, r));
    }

    public static TextView text(Context c, String s, float sizeSp, int color, Typeface tf) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(color);
        tv.setTypeface(tf);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    public static TextView oneLine(TextView tv) {
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        return tv;
    }

    public static ImageView icon(Context c, int res, int color, int sizeDp) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return iv;
    }

    /** Pulsante-icona tondo o arrotondato, area di tocco ≥ 56 px. */
    public static FrameLayout iconButton(Context c, Theme t, int res, int iconColor, int bgColor, int sizeDp, int iconDp,
                                         float radiusDp, String description, View.OnClickListener click) {
        sizeDp = Math.round(sizeDp * K);
        iconDp = Math.round(iconDp * K);
        radiusDp = radiusDp * K;
        FrameLayout f = new FrameLayout(c);
        f.setBackground(pressable(c, bgColor, radiusDp, t));
        f.setContentDescription(description);
        ImageView iv = icon(c, res, iconColor, iconDp);
        f.addView(iv, new FrameLayout.LayoutParams(dp(c, iconDp), dp(c, iconDp), Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        f.setClickable(true);
        final View.OnClickListener cl = click;
        f.setOnClickListener(cl == null ? null : v -> { com.mg4.launcher.svc.Sfx.click(); tapPulse(v); cl.onClick(v); });
        return f;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpWeight(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.weight = weight;
        return p;
    }

    public static <T extends ViewGroup.MarginLayoutParams> T margins(T p, int l, int t, int r, int b) {
        p.setMargins(l, t, r, b);
        return p;
    }

    public static View spacer(Context c) {
        View v = new View(c);
        v.setLayoutParams(lpWeight(0, 1, 1f));
        return v;
    }

    public static View divider(Context c, int color, int heightDp) {
        View v = new View(c);
        v.setBackgroundColor(color);
        v.setLayoutParams(margins(new LinearLayout.LayoutParams(dp(c, 1), dp(c, heightDp)), dp(c, 6), 0, dp(c, 6), 0));
        return v;
    }
}
