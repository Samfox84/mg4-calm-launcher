package com.mg4.launcher.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.mg4.launcher.svc.Diag;

import java.io.File;

/**
 * Calm Launcher: mappa "dove siamo" dal vivo (AuDHD). NON è un navigatore: mostra solo la posizione dell'auto e il
 * nome della via, e si ricentra mentre si guida.
 * <p>
 * Se sul dispositivo c'è un file mappa offline (.pmtiles in Download/ o in /CalmMap), usa le mappe VETTORIALI offline
 * (MapLibre + pmtiles, stile scuro e calmo, nessun dato internet). Altrimenti usa riquadri online scuri come ripiego.
 * Stato nel log: map.file, map.engine, map.stato.
 */
@SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
public final class LiveMap extends FrameLayout implements LocationListener {

    private final WebView web;
    private final TextView status;
    private final LocationManager lm;
    private TileServer server;
    private boolean pageReady, started, haveFix, mapReady, offline;

    public LiveMap(Context c) {
        super(c);
        setBackgroundColor(0xFF0E1114);
        web = new WebView(c);
        web.setBackgroundColor(0xFF0E1114);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setAllowFileAccessFromFileURLs(true);
        web.getSettings().setAllowUniversalAccessFromFileURLs(true);
        web.addJavascriptInterface(new Bridge(), "AndroidMap");
        web.setWebViewClient(new android.webkit.WebViewClient());
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                if (m != null && m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR)
                    Diag.put("map.console", m.message() + " @" + m.sourceId() + ":" + m.lineNumber());
                return true;
            }
        });
        addView(web, new LayoutParams(-1, -1));

        status = new TextView(c);
        status.setTextColor(0xFFAEB6BF);
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        status.setText("Finding your position…");
        addView(status, new LayoutParams(-2, -2, Gravity.CENTER));

        lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);

        File map = findMapFile(c);
        if (map != null) {
            offline = true;
            try {
                int port = (server = new TileServer(map, getContext().getAssets())).start();
                Diag.put("map.file", "offline: " + map.getName() + " (" + (map.length() / (1024 * 1024)) + " MB)");
                web.loadUrl("http://127.0.0.1:" + port + "/map-vector.html");
            } catch (Throwable e) {
                offline = false;
                Diag.put("map.file", "file trovato ma server KO: " + e.getClass().getSimpleName());
                web.loadUrl("file:///android_asset/map/index.html");
            }
        } else {
            Diag.put("map.file", "nessun file offline (uso riquadri online)");
            web.loadUrl("file:///android_asset/map/index.html");
        }
    }

    /** Cerca un file mappa offline .pmtiles nei posti dove è facile metterlo da USB o file manager. */
    private static File findMapFile(Context c) {
        try {
            File ext = Environment.getExternalStorageDirectory();
            File[] cands = {
                    new File(ext, "CalmMap"),
                    new File(ext, "Download"),
                    c.getExternalFilesDir(null),
            };
            for (File dir : cands) {
                if (dir == null || !dir.isDirectory()) continue;
                File named = new File(dir, "calm-map.pmtiles");
                if (named.isFile() && named.length() > 1024) return named;
                File[] fs = dir.listFiles();
                if (fs != null) for (File f : fs)
                    if (f.isFile() && f.getName().toLowerCase().endsWith(".pmtiles") && f.length() > 1024) return f;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try { web.onResume(); } catch (Throwable ignored) {}
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        try { if (lm != null) lm.removeUpdates(this); } catch (Throwable ignored) {}
        started = false;
        try { web.onPause(); } catch (Throwable ignored) {}
        if (server != null) server.stop();
    }

    @SuppressLint("MissingPermission")
    private void start() {
        if (started || lm == null) return;
        started = true;
        try {
            Location last = null;
            try { last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (Throwable ignored) {}
            if (last == null) try { last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (Throwable ignored) {}
            if (last != null) onLocationChanged(last);
            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 3f, this); } catch (Throwable ignored) {}
            try { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 10f, this); } catch (Throwable ignored) {}
        } catch (Throwable e) {
            Diag.put("map.stato", "GPS non disponibile: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public void onLocationChanged(Location l) {
        if (l == null) return;
        if (!haveFix) { haveFix = true; Diag.put("map.stato", "posizione ok"); }
        final double lat = l.getLatitude(), lon = l.getLongitude();
        if (!pageReady) return;
        post(() -> {
            try { web.evaluateJavascript("window.setPos(" + lat + "," + lon + ")", null); } catch (Throwable ignored) {}
            if (mapReady) status.setVisibility(GONE);
        });
    }

    @Override public void onProviderDisabled(String p) {}
    @Override public void onProviderEnabled(String p) {}
    @Override public void onStatusChanged(String p, int s, Bundle b) {}

    private final class Bridge {
        @JavascriptInterface
        public void status(String s) {
            if ("page-ready".equals(s)) { pageReady = true; }
            else if ("webgl-yes".equals(s)) { Diag.put("map.webgl", "si (mappe vettoriali offline possibili)"); }
            else if ("webgl-no".equals(s)) { Diag.put("map.webgl", "no (serviranno riquadri raster offline)"); }
            else if ("engine-ok".equals(s)) { mapReady = true; Diag.put("map.engine", "MapLibre offline ok"); post(() -> { if (haveFix) status.setVisibility(GONE); }); }
            else if (s != null && s.startsWith("engine-error")) { Diag.put("map.engine", "MapLibre ERRORE: " + s); post(() -> { status.setText("Offline map failed – see log"); status.setVisibility(VISIBLE); }); }
            else if (s != null && s.startsWith("style-error")) { Diag.put("map.engine", "stile ERRORE: " + s); }
            else if ("tiles-ok".equals(s)) { mapReady = true; Diag.put("map.stato", "riquadri ok (internet auto)"); post(() -> { if (haveFix) status.setVisibility(GONE); }); }
            else if ("tiles-error".equals(s)) { Diag.put("map.stato", "riquadri OFFLINE (niente internet)"); post(() -> { status.setText(offline ? "" : "Map offline – no data signal"); status.setVisibility(offline ? GONE : VISIBLE); }); }
            else if (s != null && s.startsWith("size:")) { Diag.put("map.size", s.substring(5)); }
            else if ("fix".equals(s)) { post(() -> { if (mapReady) status.setVisibility(GONE); }); }
        }
    }
}
