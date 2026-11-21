package com.mg4.launcher.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.View;

import com.mg4.launcher.R;

/**
 * Calm Launcher: animazione d'avvio. Logo che pulsa con un anello di colori che ruota attorno,
 * poi dissolvenza verso la schermata principale. Mostrata una volta all'avvio a freddo.
 */
public final class StartupSplash extends View {

    private final Bitmap logo;
    private final int bg, c1, c2;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long start;
    private boolean running;
    private static final long SPIN_MS = 1500, FADE_MS = 520;

    public StartupSplash(Context c, int bg, int accent1, int accent2) {
        super(c);
        this.bg = bg;
        this.c1 = accent1;
        this.c2 = accent2;
        Bitmap b = null;
        try { b = BitmapFactory.decodeResource(getResources(), R.drawable.calm_logo); } catch (Exception ignored) {}
        logo = b;
        setBackgroundColor(bg);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Avvia l'animazione; onDone viene chiamato quando la dissolvenza finisce. */
    public void play(final Runnable onDone) {
        start = SystemClock.elapsedRealtime();
        running = true;
        postInvalidateOnAnimation();
        // dissolvenza finale, poi rimozione
        postDelayed(() -> animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            running = false;
            if (onDone != null) onDone.run();
        }).start(), SPIN_MS);
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float cx = w / 2f, cy = h / 2f;
        long el = SystemClock.elapsedRealtime() - start;
        float t = el / 1000f;

        float R = Math.min(w, h) * 0.17f;
        float appear = Math.min(1f, el / 420f);              // entrata morbida
        float pulse = 1f + 0.05f * (float) Math.sin(t * 3.0f);
        float rot = (el / 1300f) * 360f;                      // anello che ruota

        // anello di colori rotante (gradiente a spazzata, due archi)
        ring.setStrokeWidth(R * 0.085f);
        int clear = c1 & 0x00FFFFFF;
        SweepGradient sg = new SweepGradient(cx, cy, new int[]{clear, c1, c2, c1, clear},
                new float[]{0f, 0.25f, 0.5f, 0.75f, 1f});
        Matrix m = new Matrix();
        m.setRotate(rot, cx, cy);
        sg.setLocalMatrix(m);
        ring.setShader(sg);
        ring.setAlpha((int) (235 * appear));
        float rr = R * (0.92f + 0.08f * appear);
        cv.drawArc(new RectF(cx - rr, cy - rr, cx + rr, cy + rr), 0, 300, false, ring);
        ring.setShader(null);

        // logo che pulsa
        if (logo != null) {
            float lw = R * 1.05f * pulse * (0.85f + 0.15f * appear);
            cv.drawBitmap(logo, null, new RectF(cx - lw, cy - lw, cx + lw, cy + lw), null);
        }

        if (running) postInvalidateOnAnimation();
    }
}
