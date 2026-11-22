package com.mg4.launcher.svc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Calm Launcher 1.0.5 — dashcam incidenti con l'app 360 (com.drivehub.kamera).
 *
 * Due usi:
 *  - pulsante manuale "Invia clip": fa salvare un evento all'app e carica le clip risultanti;
 *  - automatico: se l'allarme di collisione (FCW) del veicolo scatta, fa salvare un evento e lo carica.
 *
 * L'app 360 fa già il buffer pre+post: noi inviamo solo il broadcast di trigger (ricevitore esportato
 * DashcamEventTriggerReceiver), poi sorvegliamo la cartella dashcam e carichiamo i nuovi file .mp4.
 * Il caricamento usa lo stesso meccanismo dei log: PUT su GitHub (contents API, base64, token di sola lettura).
 */
public final class DashcamClient {
    private static final String CAM_PKG = "com.drivehub.kamera";
    private static final String TRIGGER_RECEIVER = "com.drivehub.kamera.dashcam.DashcamEventTriggerReceiver";
    private static final String ACTION_TRIGGER = "com.drivehub.kamera.action.TRIGGER_DASHCAM_EVENT";
    private static final String REPO = "Samfox84/mg4-launcher-logs";   // stesso repo dei log
    private static final int FCW_PROP = 557883745;                      // AAD_FCW_WARNING_LEVEL_SIGNAL
    private static final long WATCH_MS = 210_000;                       // sorveglia la cartella ~3,5 min dopo un trigger
    private static final long POLL_MS = 4000;

    private final Context app;
    private final HandlerThread thread = new HandlerThread("mg4-dashcam");
    private final Handler bg;
    private final Set<String> uploaded = new HashSet<>();   // nomi file già inviati, per non duplicare
    private volatile long watchUntil;
    private volatile boolean fcwArmed = true;
    private Object carPropertyManager;   // android.car CarPropertyManager via reflection (può restare null)

    private static DashcamClient sInst;
    public static DashcamClient get(Context c) {
        if (sInst == null) sInst = new DashcamClient(c);
        return sInst;
    }

    private DashcamClient(Context c) {
        this.app = c.getApplicationContext();
        thread.start();
        bg = new Handler(thread.getLooper());
    }

    public static boolean camInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(CAM_PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Pulsante manuale: salva un evento nell'app 360 e carica le clip risultanti. */
    public void sendClipNow() {
        trigger("manuale");
    }

    /** true se l'app 360 c'è E la cartella dashcam esiste (chiavetta USB inserita). Altrimenti non si fa nulla. */
    public boolean ready() {
        return camInstalled(app) && dashcamDir().isDirectory();
    }

    private void trigger(String why) {
        if (!ready()) {
            Diag.put("dashcam.trigger", why + " ignorato: chiavetta/cartella dashcam assente");
            return;
        }
        try {
            Intent i = new Intent(ACTION_TRIGGER);
            i.setComponent(new ComponentName(CAM_PKG, TRIGGER_RECEIVER));
            app.sendBroadcast(i);
            Diag.put("dashcam.trigger", why + " → evento richiesto all'app 360");
        } catch (Exception e) {
            Diag.error("dashcam.trigger", e.toString());
            return;
        }
        // sorveglia la cartella e carica i nuovi file per qualche minuto (l'app scrive pre+post)
        watchUntil = System.currentTimeMillis() + WATCH_MS;
        bg.removeCallbacks(watchRun);
        bg.post(watchRun);
    }

    private final Runnable watchRun = new Runnable() {
        @Override public void run() {
            try {
                scanAndUpload();
            } catch (Throwable t) {
                Diag.error("dashcam.scan", t.toString());
            }
            if (System.currentTimeMillis() < watchUntil) bg.postDelayed(this, POLL_MS);
        }
    };

    /**
     * Cartella dashcam. Nell'ordine: percorso impostato dall'utente → una "dashcam" su una chiavetta USB
     * (/storage o /mnt/media_rw) → Download/dashcam (default dell'app 360). Se nulla esiste, la chiavetta non
     * c'è e ready() sarà false, quindi non si fa nulla.
     */
    private File dashcamDir() {
        String custom = com.mg4.launcher.ui.Prefs.get(app).dashcamFolder();
        if (custom != null && !custom.isEmpty()) {
            File f = new File(custom);
            if (f.isDirectory()) return f;
        }
        for (File base : new File[]{new File("/storage"), new File("/mnt/media_rw")}) {
            File[] vols = base.listFiles();
            if (vols == null) continue;
            for (File v : vols) {
                if ("emulated".equals(v.getName()) || "self".equals(v.getName())) continue;
                File d = new File(v, "dashcam");
                if (d.isDirectory()) return d;
            }
        }
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "dashcam");
    }

    private void scanAndUpload() {
        File root = dashcamDir();
        if (!root.isDirectory()) {
            Diag.put("dashcam.cartella", "non trovata: " + root.getAbsolutePath());
            return;
        }
        long since = watchUntil - WATCH_MS - 90_000;   // includi anche le clip appena precedenti al trigger
        collect(root, since, 0);
    }

    private void collect(File dir, long since, int depth) {
        if (depth > 3) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) { collect(f, since, depth + 1); continue; }
            String n = f.getName();
            if (!n.toLowerCase(Locale.US).endsWith(".mp4")) continue;
            if (f.lastModified() < since) continue;
            if (f.length() <= 0 || n.endsWith(".part")) continue;
            String key = f.getAbsolutePath() + ":" + f.length();
            synchronized (uploaded) {
                if (uploaded.contains(key)) continue;
                uploaded.add(key);
            }
            uploadClip(f);
        }
    }

    private void uploadClip(File f) {
        if (!hasToken()) { Diag.put("dashcam.upload", "nessun token: clip non inviata (" + f.getName() + ")"); return; }
        try {
            byte[] data = readAll(new FileInputStream(f));
            // GitHub contents API: limite ~100 MB per file (il base64 aumenta del 33%). Le clip 720x240 sono piccole.
            if (data.length > 90L * 1024 * 1024) {
                Diag.put("dashcam.upload", "clip troppo grande per GitHub (" + (data.length / (1024 * 1024)) + " MB): " + f.getName());
                return;
            }
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(f.lastModified());
            String path = "dashcam/" + day + "/" + f.getName();
            int code = put(path, data);
            Diag.put("dashcam.upload", f.getName() + " → " + (code == 422 ? "già presente" : "inviata (" + (data.length / 1024) + " KB)"));
        } catch (Exception e) {
            synchronized (uploaded) { uploaded.remove(f.getAbsolutePath() + ":" + f.length()); }   // riprova al prossimo giro
            Diag.error("dashcam.upload", f.getName() + ": " + e);
        }
    }

    private boolean hasToken() {
        try { return LogConfig.token().length() > 10; } catch (Throwable e) { return false; }
    }

    private int put(String path, byte[] data) throws Exception {
        JSONObject body = new JSONObject();
        body.put("message", "dashcam " + path);
        body.put("content", Base64.encodeToString(data, Base64.NO_WRAP));
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/contents/" + path).openConnection();
        c.setRequestMethod("PUT");
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + LogConfig.token());
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "MG4Launcher");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream o = c.getOutputStream()) {
            o.write(body.toString().getBytes("UTF-8"));
        }
        int code = c.getResponseCode();
        c.disconnect();
        if (code != 200 && code != 201 && code != 422) throw new Exception("HTTP " + code);
        return code;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int r;
            while ((r = i.read(buf)) != -1) bo.write(buf, 0, r);
            return bo.toByteArray();
        }
    }

    // ---- automatico: sorveglia l'allarme di collisione (FCW) del veicolo -----------------------

    /** Avvia il controllo automatico FCW (se l'utente l'ha attivato). Difensivo: se non leggibile, resta inerte. */
    public void startAutoWatch() {
        bg.post(() -> {
            try {
                Class<?> carClass = Class.forName("android.car.Car");
                java.lang.reflect.Method createCar = carClass.getMethod("createCar", Context.class);
                Object car = createCar.invoke(null, app);
                if (car == null) throw new IllegalStateException("createCar null");
                try { carClass.getMethod("connect").invoke(car); } catch (Throwable ignore) { /* alcune versioni sono già connesse */ }
                java.lang.reflect.Method getMgr = car.getClass().getMethod("getCarManager", String.class);
                carPropertyManager = getMgr.invoke(car, "property");
                Diag.put("dashcam.auto", carPropertyManager != null ? "controllo FCW avviato" : "property manager nullo");
            } catch (Throwable t) {
                carPropertyManager = null;
                Diag.put("dashcam.auto", "CarPropertyManager non disponibile: " + t);
            }
            bg.post(fcwPoll);
        });
    }

    private final Runnable fcwPoll = new Runnable() {
        @Override public void run() {
            if (carPropertyManager == null) return;   // API auto non disponibile: niente poll (il pulsante manuale resta)
            try {
                if (com.mg4.launcher.ui.Prefs.get(app).dashcamAutoUpload()) {
                    int level = readFcwLevel();
                    if (level > 0 && fcwArmed) {
                        fcwArmed = false;
                        Diag.put("dashcam.auto", "FCW livello " + level + " → salvataggio evento");
                        trigger("collisione FCW " + level);
                    } else if (level <= 0) {
                        fcwArmed = true;   // riarmato quando l'allarme rientra
                    }
                }
            } catch (Throwable t) {
                Diag.error("dashcam.auto", t.toString());
            }
            bg.postDelayed(this, 700);
        }
    };

    private int readFcwLevel() {
        try {
            java.lang.reflect.Method getProperty = carPropertyManager.getClass().getMethod(
                    "getProperty", Class.class, int.class, int.class);
            Object v = getProperty.invoke(carPropertyManager, Integer.class, FCW_PROP, 0);
            if (v == null) return -1;
            Object val = v.getClass().getMethod("getValue").invoke(v);
            return val instanceof Integer ? (Integer) val : -1;
        } catch (Throwable t) {
            return -1;
        }
    }
}
