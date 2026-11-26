package com.mg4.launcher.svc;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.mg4.launcher.ui.Prefs;

import java.lang.reflect.Method;

/**
 * calm.129 (SPERIMENTALE): specchia il VIDEO CarPlay reale (dallo schermo principale) sul QUADRO.
 *
 * <p>Idea: con CarPlay NATIVO CABLATO in corso (microfono funzionante), il launcher — app di sistema
 * firmata con la chiave di piattaforma — cattura lo schermo principale e ridisegna il ritaglio della
 * mappa sul display del quadro. Niente ricostruzione: è la mappa vera di Apple/Google/Waze.
 *
 * <p>Incognita decisiva: se la superficie video CarPlay è PROTETTA (secure), la cattura esce nera.
 * Lo diciamo nel log (chiave cluster.mirror). Se è nera, l'unica strada resta modificare il sistema.
 */
public final class ClusterMirror {

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static Presentation pres;
    private static Thread loop;
    private static volatile boolean running;

    private ClusterMirror() {}

    public static void start(final Context app, final Display cluster) {
        stop();
        UI.post(() -> {
            try {
                final SurfaceView sv = new SurfaceView(app);
                Presentation p = new Presentation(app, cluster);
                p.setTitle("Calm cluster mirror");
                p.setCancelable(false);
                android.view.Window w = p.getWindow();
                if (w != null) {
                    try { w.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT); } catch (Throwable ignored) {}
                    w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                }
                p.setContentView(sv);
                p.show();
                pres = p;
                startLoop(app, sv);
                Diag.put("cluster.mirror", "avviato sul display " + cluster.getDisplayId() + " ('" + cluster.getName() + "')");
            } catch (Throwable t) {
                Diag.error("cluster.mirror", "start: " + t);
            }
        });
    }

    private static void startLoop(final Context app, final SurfaceView sv) {
        running = true;
        loop = new Thread(new Runnable() {
            @Override public void run() {
                int sw = 0, sh = 0;
                try {
                    DisplayMetrics dm = new DisplayMetrics();
                    ((WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
                    sw = dm.widthPixels; sh = dm.heightPixels;
                } catch (Throwable ignored) {}
                Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
                boolean loggedOk = false, loggedNull = false;
                while (running) {
                    try {
                        Bitmap full = capture(sw, sh);
                        if (full != null) {
                            if (!loggedOk) { loggedOk = true;
                                Diag.put("cluster.mirror", "cattura OK " + full.getWidth() + "x" + full.getHeight() + " (se è tutto nero = superficie protetta)"); }
                            SurfaceHolder h = sv.getHolder();
                            Canvas c = h.lockCanvas();
                            if (c != null) {
                                Rect src = cropRect(app, full.getWidth(), full.getHeight());
                                Rect dst = new Rect(0, 0, c.getWidth(), c.getHeight());
                                c.drawColor(0xFF000000);
                                c.drawBitmap(full, src, dst, paint);
                                h.unlockCanvasAndPost(c);
                            }
                            full.recycle();
                        } else if (!loggedNull) { loggedNull = true;
                            Diag.put("cluster.mirror", "cattura NULL — permesso cattura negato o API non disponibile"); }
                    } catch (Throwable t) {
                        Diag.error("cluster.mirror", "loop: " + t);
                    }
                    try { Thread.sleep(66); } catch (InterruptedException e) { break; }   // ~15 fps
                }
            }
        }, "cluster-mirror");
        loop.start();
    }

    /** Ritaglio della zona mappa dello schermo principale (percentuali regolabili in Impostazioni). */
    private static Rect cropRect(Context app, int w, int h) {
        try {
            Prefs p = Prefs.get(app);
            int l = clamp(p.mirrorL()), t = clamp(p.mirrorT()), r = clamp(p.mirrorR()), b = clamp(p.mirrorB());
            Rect rc = new Rect(l * w / 100, t * h / 100, r * w / 100, b * h / 100);
            if (rc.width() < 10 || rc.height() < 10) return new Rect(0, 0, w, h);
            return rc;
        } catch (Throwable t) { return new Rect(0, 0, w, h); }
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 100 ? 100 : v); }

    /** Cattura lo schermo principale via SurfaceControl (API nascoste; consentito a un'app firmata piattaforma). */
    private static Bitmap capture(int w, int h) {
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            // API 28: screenshot(Rect sourceCrop, int width, int height, int rotation)
            try {
                Method m = sc.getMethod("screenshot", Rect.class, int.class, int.class, int.class);
                Object b = m.invoke(null, new Rect(), w, h, 0);
                if (b instanceof Bitmap) return (Bitmap) b;
            } catch (NoSuchMethodException ignored) {}
            // variante: screenshot(Rect, int, int, boolean, int)
            try {
                Method m = sc.getMethod("screenshot", Rect.class, int.class, int.class, boolean.class, int.class);
                Object b = m.invoke(null, new Rect(), w, h, false, 0);
                if (b instanceof Bitmap) return (Bitmap) b;
            } catch (NoSuchMethodException ignored) {}
            // vecchia: screenshot(int width, int height)
            try {
                Method m = sc.getMethod("screenshot", int.class, int.class);
                Object b = m.invoke(null, w, h);
                if (b instanceof Bitmap) return (Bitmap) b;
            } catch (NoSuchMethodException ignored) {}
            Diag.put("cluster.mirror", "nessuna firma screenshot nota su questa versione Android");
        } catch (Throwable t) {
            Diag.error("cluster.mirror", "capture refl: " + t);
        }
        return null;
    }

    public static void stop() {
        running = false;
        Thread l = loop; loop = null;
        if (l != null) l.interrupt();
        final Presentation p = pres; pres = null;
        if (p != null) UI.post(new Runnable() { @Override public void run() { try { p.dismiss(); } catch (Throwable ignored) {} } });
    }
}
