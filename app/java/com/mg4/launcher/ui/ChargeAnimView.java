package com.mg4.launcher.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import com.mg4.launcher.svc.Diag;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * 1.0.4 (punto F): animazione di ricarica sopra l'auto disegnata.
 * <p>
 * Quattro fasi: aggancio (una volta) → loop "in carica" (ripetuto) → finita (una volta, l'ultimo fotogramma resta
 * fermo) → sgancio (al distacco del cavo, poi torna l'immagine ferma). Se il cavo è già inserito quando si accende lo
 * schermo si parte dal loop (o dalla fase finita). Mai in READY o con l'auto in movimento: si torna subito all'immagine
 * ferma.
 * <p>
 * Riproduzione (1.0.5-calm.70+): la GIF viene disegnata a mano su Canvas in {@link #onDraw}, come fa la schermata
 * d'avvio e le ruote (che sull'auto funzionano). Si usa {@link Movie} su un layer software: su questo head unit
 * (Android 9, GPU SAIC) l'AnimatedImageDrawable "girava" ma non mandava i fotogrammi a schermo. Movie + onDraw li
 * disegna direttamente, un fotogramma a ogni vsync, senza affidarsi al lettore GIF di sistema.
 * <p>
 * Posizione: la clip (riferimento 1312×400) copre il riquadro dell'auto; i centri ruota della clip (x 358 e 973,
 * y 288) si allineano a quelli della foto disegnata da CarView.
 */
public final class ChargeAnimView extends View {
    public static final int IDLE = 0, AGGANCIO = 1, LOOP = 2, FINITA = 3, SGANCIO = 4;
    private static final String[] NAMES = {"ferma", "aggancio", "loop", "finita", "sgancio"};
    private static final float CLIP_W = 1312, CLIP_H = 400, CLIP_WHEEL_X1 = 358, CLIP_WHEEL_X2 = 973, CLIP_WHEEL_Y = 288;

    /** Dove prendere le clip: pacchetto del colore scelto oppure cartella di prova (chiavetta). */
    public interface Source {
        File clip(boolean day, String phase);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread decodeThread;
    private Handler decodeHandler;

    private final Rect dst = new Rect();
    /** Cresce a ogni nuova sequenza: i decode in corso delle vecchie vengono scartati. */
    private volatile int gen;

    // --- riproduzione corrente (Movie disegnato in onDraw) ---
    private Movie movie;
    private int movieDur = 1;        // durata di un giro, ms
    private long phaseStart;         // uptime di inizio fase
    private int loopsTarget;         // 0 = loop infinito; >0 = numero di giri poi fine fase
    private int curPhase = IDLE;
    private boolean endFired;
    private volatile boolean drewOnce;

    private int state = IDLE;
    private boolean day = true;
    private boolean fresh = true;            // prima valutazione dopo l'accensione dello schermo
    private boolean finishAfterLoop;
    private Source source;
    private String reason = "";
    private Runnable afterTest;
    private boolean lastPlugged, lastDone, failedThisPlug;

    public ChargeAnimView(Context c) {
        super(c);
        setClickable(false);
        setWillNotDraw(false);
        // Movie.draw() funziona solo su canvas software: tengo la vista su un layer software.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        // INVISIBLE (non GONE): viene misurata e dimensionata, ma non disegnata finché non parte un'animazione.
        setVisibility(INVISIBLE);
    }

    public int state() {
        return state;
    }

    public static String name(int st) {
        return NAMES[st];
    }

    /** Lo schermo si è riacceso (o la home è nuova): se il cavo è già inserito si parte dal loop. */
    public void markFresh() {
        fresh = true;
    }

    // ---- geometria: stessa foto e stessi centri ruota di CarView ------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutClip(w, h);
    }

    /** Calcola il riquadro della clip (dst), usando gli stessi centri ruota di CarView. */
    private void layoutClip(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float s = CarView.photoScale(w, h);
        float[] wc = CarView.wheelCenter(w, h);   // centro della ruota anteriore della foto, in px della vista
        float k = s * CarView.wheelDistance() / (CLIP_WHEEL_X2 - CLIP_WHEEL_X1);
        int cw = Math.round(CLIP_W * k), ch = Math.round(CLIP_H * k);
        int cx = Math.round(wc[0] - CLIP_WHEEL_X1 * k), cy = Math.round(wc[1] - CLIP_WHEEL_Y * k);
        dst.set(cx, cy, cx + cw, cy + ch);
    }

    // ---- logica delle fasi ---------------------------------------------------------------------

    /**
     * Stato dell'auto (dal thread principale, a ogni lettura del veicolo).
     *
     * @param stopWhy motivo per cui l'animazione non deve girare ("" = può girare): spenta, pacchetto assente, READY...
     */
    public void update(Source src, boolean dayTheme, boolean plugged, boolean done, String stopWhy) {
        if (afterTest != null) return;   // durante la prova si ignorano i segnali dell'auto
        boolean themeChanged = dayTheme != day;
        day = dayTheme;
        source = src;
        lastPlugged = plugged;
        lastDone = done;
        if (!stopWhy.isEmpty()) {
            reason = stopWhy;
            fresh = plugged;
            if (!plugged) failedThisPlug = false;
            if (state != IDLE) toPhoto();
            else writeLog();
            return;
        }
        if (!plugged) failedThisPlug = false;
        if (failedThisPlug) {
            if (state != IDLE) toPhoto();
            writeLog();
            return;
        }
        reason = "";
        boolean wasFresh = fresh;
        fresh = false;
        switch (state) {
            case IDLE:
                if (!plugged) break;
                if (wasFresh) play(done ? FINITA : LOOP);
                else play(AGGANCIO);
                break;
            case AGGANCIO:
                break;   // si decide alla fine dell'aggancio
            case LOOP:
                if (!plugged) play(SGANCIO);
                else if (done) play(FINITA);
                else if (themeChanged) play(LOOP);
                break;
            case FINITA:
                if (!plugged) play(SGANCIO);
                else if (!done) play(LOOP);
                else if (themeChanged) play(FINITA);
                break;
            case SGANCIO:
                break;   // alla fine torna la foto
        }
    }

    /** Schermo spento o launcher in secondo piano: si torna alla foto e si liberano i fotogrammi. */
    public void pause() {
        afterTest = null;
        fresh = true;
        if (state != IDLE) {
            state = IDLE;
            finishAfterLoop = false;
            writeLog();
        }
        release();
    }

    /** Fine di una fase (riportata dal ciclo di disegno sul thread principale). */
    private void onPhaseEnd(int phase) {
        if (afterTest != null) {
            testNext(phase);
            return;
        }
        switch (phase) {
            case AGGANCIO:
                if (!lastPlugged) play(SGANCIO);
                else play(lastDone ? FINITA : LOOP);
                break;
            case LOOP:
                finishAfterLoop = false;
                if (!lastPlugged) play(SGANCIO);
                else play(FINITA);
                break;
            case FINITA:
                break;   // l'ultimo fotogramma resta fermo
            case SGANCIO:
                toPhoto();
                break;
        }
    }

    // ---- prova dalle impostazioni --------------------------------------------------------------

    /** Prova: aggancio → loop (3 giri) → finita → sgancio, poi di nuovo la foto. */
    public void playTest(Source src, boolean dayTheme, Runnable done) {
        release();
        source = src;
        day = dayTheme;
        afterTest = done;
        failedThisPlug = false;
        play(AGGANCIO);
    }

    private void testNext(int phase) {
        switch (phase) {
            case AGGANCIO:
                play(LOOP);
                break;
            case LOOP:
                play(FINITA);   // dopo i 3 giri del loop
                break;
            case FINITA:
                main.postDelayed(() -> {
                    if (afterTest != null) play(SGANCIO);
                }, 2000);
                break;
            case SGANCIO:
                Runnable r = afterTest;
                afterTest = null;
                toPhoto();
                if (r != null) r.run();
                break;
        }
    }

    // ---- riproduzione --------------------------------------------------------------------------

    /** Errori di riproduzione dall'avvio (per il Controllo funzioni). */
    public static int errors;

    private Handler decoder() {
        if (decodeThread == null) {
            decodeThread = new HandlerThread("mg4-animazione-decode");
            decodeThread.start();
            decodeHandler = new Handler(decodeThread.getLooper());
        }
        return decodeHandler;
    }

    /** Fa partire una fase: decodifica la clip in un Movie (fuori dal thread UI) e poi la disegna in onDraw. */
    private void play(int phase) {
        state = phase;
        writeLog();
        Source src = source;
        final File clip = src == null ? null : src.clip(day, NAMES[phase]);
        if (clip == null || !clip.isFile() || clip.length() == 0) {
            fail("clip " + NAMES[phase] + " mancante");
            return;
        }
        final int g = ++gen;
        final int ph = phase;
        decoder().post(() -> {
            Movie mv = null;
            String err = null;
            try {
                byte[] bytes = readAll(clip);
                mv = Movie.decodeByteArray(bytes, 0, bytes.length);
                if (mv == null) err = "Movie null";
            } catch (Throwable e) {
                err = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            }
            final Movie fmv = mv;
            final String fe = err;
            main.post(() -> {
                if (g != gen) return;
                if (fmv == null) {
                    fail("gif " + NAMES[ph] + " non leggibile (" + (fe != null ? fe : "null") + ")");
                    return;
                }
                startMovie(fmv, ph);
            });
        });
    }

    private void startMovie(Movie mv, int phase) {
        movie = mv;
        int dur = mv.duration();
        movieDur = dur > 0 ? dur : 1000;   // alcune GIF riportano durata 0
        curPhase = phase;
        loopsTarget = (phase == LOOP) ? (afterTest != null ? 3 : 0) : 1;   // loop reale = infinito; le altre un giro
        endFired = false;
        drewOnce = false;
        phaseStart = SystemClock.uptimeMillis();
        if (dst.isEmpty() && getWidth() > 0 && getHeight() > 0) layoutClip(getWidth(), getHeight());
        if (getWidth() == 0 || getHeight() == 0) requestLayout();
        setVisibility(VISIBLE);
        setAlpha(1f);
        Diag.put("ricarica.gif", NAMES[phase] + ": movie " + mv.width() + "x" + mv.height() + " dur " + movieDur
                + " · dst " + dst.width() + "x" + dst.height() + "@" + dst.left + "," + dst.top
                + " · vista " + getWidth() + "x" + getHeight());
        final int g = gen;
        main.postDelayed(() -> {
            if (g == gen) Diag.put("ricarica.gif2", "assestata: disegnata=" + drewOnce + " · vista alpha " + getAlpha()
                    + " vis " + getVisibility() + " · dst " + dst.width() + "x" + dst.height());
        }, 600);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Movie mv = movie;
        if (mv == null || dst.isEmpty()) return;
        int dur = movieDur > 0 ? movieDur : 1;
        int elapsed = (int) (SystemClock.uptimeMillis() - phaseStart);
        boolean ended = false;
        int t;
        if (loopsTarget == 0) {
            t = elapsed % dur;                 // loop infinito
        } else {
            int total = dur * loopsTarget;
            if (elapsed >= total) { t = dur - 1; ended = true; }   // ultimo fotogramma, poi fine fase
            else t = elapsed % dur;
        }
        mv.setTime(Math.max(0, Math.min(dur - 1, t)));
        int mw = mv.width() > 0 ? mv.width() : 1, mh = mv.height() > 0 ? mv.height() : 1;
        canvas.save();
        canvas.translate(dst.left, dst.top);
        canvas.scale(dst.width() / (float) mw, dst.height() / (float) mh);
        try { mv.draw(canvas, 0, 0); drewOnce = true; } catch (Throwable ignored) {}
        canvas.restore();
        if (ended) {
            if (!endFired) {
                endFired = true;
                final int ph = curPhase;
                final int g = gen;
                main.post(() -> { if (g == gen) onPhaseEnd(ph); });
            }
        } else {
            postInvalidateOnAnimation();
        }
    }

    private void fail(String why) {
        reason = why;
        errors++;
        Diag.put("ricarica.animazione", "errore: " + why);
        if (afterTest == null) failedThisPlug = true;
        Runnable r = afterTest;
        afterTest = null;
        toPhoto();
        if (r != null) r.run();
    }

    /** Torna all'immagine ferma e libera il Movie. */
    private void toPhoto() {
        state = IDLE;
        finishAfterLoop = false;
        writeLog();
        release();
    }

    /** Libera tutto (clip non visibile). */
    public void release() {
        gen++;
        movie = null;
        endFired = false;
        setVisibility(INVISIBLE);
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        afterTest = null;
        state = IDLE;
        release();
        HandlerThread dt = decodeThread;
        decodeThread = null;
        decodeHandler = null;
        if (dt != null) dt.quitSafely();
    }

    // ---- lettura file --------------------------------------------------------------------------

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return b;
        }
    }

    private String lastLog = "";

    private void writeLog() {
        String txt = "fase " + NAMES[state] + (reason.isEmpty() ? "" : " · " + reason) + (afterTest != null ? " · prova" : "");
        if (!txt.equals(lastLog)) {
            lastLog = txt;
            phaseTxt = txt;
        }
    }

    /** Testo della fase per il log (lo compone HomeView con il resto). */
    public static volatile String phaseTxt = "fase ferma";
}
