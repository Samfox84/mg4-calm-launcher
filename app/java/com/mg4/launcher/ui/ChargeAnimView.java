package com.mg4.launcher.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import com.mg4.launcher.svc.ChargeFrames;
import com.mg4.launcher.svc.Diag;

import java.io.File;

/**
 * 1.0.4 (punto F): animazione di ricarica sopra l'auto disegnata.
 * <p>
 * Quattro fasi: aggancio (una volta) → loop "in carica" (ripetuto) → finita (una volta, l'ultimo fotogramma resta
 * fermo) → sgancio (al distacco del cavo, poi torna l'immagine ferma). Se il cavo è già inserito quando si accende lo
 * schermo si parte dal loop (o dalla fase finita). Mai in READY o con l'auto in movimento: si torna subito all'immagine
 * ferma. Foto → clip e clip → foto con dissolvenza di 0,3 s.
 * <p>
 * Riproduzione (correzione 1.0.4, prova in auto del 15/11): niente più MediaPlayer su TextureView, che sull'auto
 * restava fermo sul primo fotogramma con il riquadro nero. Le clip vengono trasformate una volta sola in fotogrammi
 * JPEG ({@link ChargeFrames}); un thread li decodifica a 30 al secondo in tre bitmap che si alternano e questa vista
 * normale li disegna. Il fotogramma finale di una fase è uguale al primo della successiva, quindi il passaggio non si vede.
 * <p>
 * Posizione: la clip (1312×400) è il doppio del riquadro dell'auto nel layout di ricarica (circa 656×200); i centri
 * ruota della clip (x 358 e 973, y 288) si allineano a quelli della foto disegnata da CarView.
 */
public final class ChargeAnimView extends android.widget.FrameLayout {
    public static final int IDLE = 0, AGGANCIO = 1, LOOP = 2, FINITA = 3, SGANCIO = 4;
    private static final String[] NAMES = {"ferma", "aggancio", "loop", "finita", "sgancio"};
    private static final float CLIP_W = 1312, CLIP_H = 400, CLIP_WHEEL_X1 = 358, CLIP_WHEEL_X2 = 973, CLIP_WHEEL_Y = 288;
    private static final long FRAME_MS = 1000 / 30;

    /** Dove prendere le clip: pacchetto del colore scelto oppure cartella di prova (chiavetta). */
    public interface Source {
        File clip(boolean day, String phase);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    /** Thread di disegno con le sue tre bitmap: a ogni rilascio se ne crea uno nuovo (niente bitmap condivise). */
    private static final class Player {
        final HandlerThread thread = new HandlerThread("mg4-animazione-disegno", android.os.Process.THREAD_PRIORITY_DISPLAY);
        final Handler h;
        final Bitmap[] buf = new Bitmap[3];

        Player() {
            thread.start();
            h = new Handler(thread.getLooper());
        }
    }

    private Player player;
    private final android.widget.ImageView iv;
    private final Rect dst = new Rect();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private android.graphics.drawable.AnimatedImageDrawable current;
    /** Cresce a ogni nuova sequenza: il thread smette di disegnare quelle vecchie. */
    private volatile int gen;

    private int state = IDLE;
    private boolean day = true;
    private boolean fresh = true;            // prima valutazione dopo l'accensione dello schermo
    private volatile boolean finishAfterLoop;
    private Source source;
    private String reason = "";
    private Runnable afterTest;
    private boolean lastPlugged, lastDone, failedThisPlug;
    private boolean preparing;

    public ChargeAnimView(Context c) {
        super(c);
        setClickable(false);
        iv = new android.widget.ImageView(c);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        addView(iv, new android.widget.FrameLayout.LayoutParams(0, 0));
        setVisibility(GONE);
        setAlpha(0f);
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
        if (w > 0 && h > 0) {
            float s = CarView.photoScale(w, h);
            float[] wc = CarView.wheelCenter(w, h);   // centro della ruota anteriore della foto, in px della vista
            float k = s * CarView.wheelDistance() / (CLIP_WHEEL_X2 - CLIP_WHEEL_X1);
            int cw = Math.round(CLIP_W * k), ch = Math.round(CLIP_H * k);
            int cx = Math.round(wc[0] - CLIP_WHEEL_X1 * k), cy = Math.round(wc[1] - CLIP_WHEEL_Y * k);
            dst.set(cx, cy, cx + cw, cy + ch);
            android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) iv.getLayoutParams();
            lp.width = cw; lp.height = ch; lp.leftMargin = cx; lp.topMargin = cy;
            iv.setLayoutParams(lp);
        }
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
            // con il cavo già inserito, alla ripartenza si va dritti al loop; l'aggancio solo per un inserimento nuovo
            fresh = plugged;
            if (!plugged) failedThisPlug = false;
            if (state != IDLE) toPhoto();
            else writeLog();
            return;
        }
        // dopo un errore l'animazione resta ferma fino al distacco del cavo (niente tentativi a ripetizione)
        if (!plugged) failedThisPlug = false;
        if (failedThisPlug) {
            if (state != IDLE) toPhoto();
            writeLog();
            return;
        }
        if (preparing) return;   // fotogrammi in preparazione: si decide quando sono pronti
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

    /** Fine di una fase (dal thread di disegno, riportata sul principale). */
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
            case LOOP:   // solo con il loop non più ripetuto: arriva la fase "finita"
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

    private int testLoops;

    /** Prova: aggancio → loop (3 giri) → finita → sgancio, poi di nuovo la foto. */
    public void playTest(Source src, boolean dayTheme, Runnable done) {
        release();
        source = src;
        day = dayTheme;
        afterTest = done;
        testLoops = 0;
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

    private Player ensurePlayer() {
        if (player == null) player = new Player();
        return player;
    }

    /** Fa partire una fase. Se i fotogrammi non sono ancora pronti li prepara (una volta sola) e poi parte. */
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
        ensurePlayer().h.post(() -> {
            android.graphics.drawable.Drawable d;
            String err = null;
            try {
                // allocazione software: su questo chip vecchio la GIF hardware a volte non si decodifica
                d = android.graphics.ImageDecoder.decodeDrawable(
                        android.graphics.ImageDecoder.createSource(clip),
                        (decoder, info, isrc) -> decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE));
            } catch (Throwable e) {
                d = null;
                err = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            }
            final android.graphics.drawable.Drawable dd = d;
            final String fe = err;
            main.post(() -> {
                if (g != gen) return;
                if (dd instanceof android.graphics.drawable.AnimatedImageDrawable) {
                    showDrawable((android.graphics.drawable.AnimatedImageDrawable) dd, ph, g);
                } else {
                    fail("gif " + NAMES[ph] + " non leggibile ("
                            + (fe != null ? fe : (dd == null ? "null" : "tipo " + dd.getClass().getSimpleName())) + ")");
                }
            });
        });
    }

    /**
     * Mostra una GIF con riproduzione nativa (AnimatedImageDrawable): nessuna decodifica fotogramma per fotogramma,
     * quindi niente scatti. Solo la fase LOOP si ripete all'infinito; le altre (aggancio, finita, sgancio) una volta
     * sola, poi onPhaseEnd decide il seguito.
     */
    private void showDrawable(final android.graphics.drawable.AnimatedImageDrawable aid, final int phase, final int g) {
        boolean fromPhoto = getVisibility() != VISIBLE || getAlpha() < 1f;
        clearDrawable();
        current = aid;
        int repeat;
        if (phase == LOOP) repeat = (afterTest != null) ? 2 : android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE;
        else repeat = 0;   // una volta sola; sovrascrive il loop scritto nella GIF
        try { aid.setRepeatCount(repeat); } catch (Throwable ignored) {}
        aid.registerAnimationCallback(new android.graphics.drawable.Animatable2.AnimationCallback() {
            @Override public void onAnimationEnd(android.graphics.drawable.Drawable drawable) {
                if (g != gen) return;
                main.post(() -> { if (g == gen) onPhaseEnd(phase); });
            }
        });
        iv.setImageDrawable(aid);          // la ImageView gestisce invalidazione e avanzamento fotogrammi
        setVisibility(VISIBLE);
        iv.setVisibility(VISIBLE);
        aid.setVisible(true, true);
        aid.start();
        com.mg4.launcher.svc.Diag.put("ricarica.gif", NAMES[phase] + ": dst " + dst.width() + "x" + dst.height()
                + " view " + getWidth() + "x" + getHeight() + " running=" + aid.isRunning());
        if (fromPhoto) { animate().cancel(); animate().alpha(1f).setDuration(300).start(); }
        else setAlpha(1f);
    }

    private void clearDrawable() {
        android.graphics.drawable.AnimatedImageDrawable d = current;
        current = null;
        if (d != null) {
            try { d.clearAnimationCallbacks(); d.stop(); } catch (Throwable ignored) {}
        }
        iv.setImageDrawable(null);
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

    /** Torna all'immagine ferma (dissolvenza 0,3 s) e libera i fotogrammi. */
    private void toPhoto() {
        state = IDLE;
        finishAfterLoop = false;
        gen++;
        writeLog();
        if (getVisibility() != VISIBLE) {
            release();
            return;
        }
        final int g = gen;
        animate().cancel();
        animate().alpha(0f).setDuration(300).withEndAction(() -> {
            if (g == gen && state == IDLE) release();
        }).start();
    }

    /** Libera tutto (clip non visibile). */
    public void release() {
        gen++;
        animate().cancel();
        setAlpha(0f);
        setVisibility(GONE);
        clearDrawable();
        Player p = player;
        player = null;
        if (p != null) {
            // le bitmap si liberano sul loro thread, dopo l'eventuale fotogramma in corso; il thread poi si chiude
            p.h.post(() -> {
                for (int i = 0; i < p.buf.length; i++) {
                    if (p.buf[i] != null) p.buf[i].recycle();
                    p.buf[i] = null;
                }
                p.thread.quitSafely();
            });
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        afterTest = null;
        state = IDLE;
        release();
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
