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
    private android.location.Location lastLoc;
    private String lastNP = "";   // 1.0.5: ultimo brano spinto sulla mappa ("Via | Brano")
    // calm.113: ultima manovra dal feed CarPlay (Apple/Google), per ripristinarla se la pagina si ricarica
    private boolean turnActive; private String turnDist = "", turnRoad = "", turnEta = "";
    // calm.114: percorso in avanti (distanza alla svolta in metri, strada dopo, direzione)
    private boolean routeActive; private int routeDistM = -1; private String routeRoad = "", routeTurn = "";
    private final boolean nightDim;
    private final float anchorX, anchorY;   // calm.110: dove tenere il pallino sullo schermo (0..1), 0.5 = centro
    private final boolean headingUp;         // calm.113: mappa orientata verso la marcia (solo quadro)

    public LiveMap(Context c) { this(c, false); }
    public LiveMap(Context c, boolean nightDim) { this(c, nightDim, 0.5f, 0.5f); }
    public LiveMap(Context c, boolean nightDim, float ax, float ay) { this(c, nightDim, ax, ay, false); }
    public LiveMap(Context c, boolean nightDim, float ax, float ay, boolean headingUp) {
        super(c);
        this.nightDim = nightDim;
        this.anchorX = ax;
        this.anchorY = ay;
        this.headingUp = headingUp;
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
                if (m != null) {
                    String lvl = m.messageLevel() != null ? m.messageLevel().name() : "LOG";
                    Diag.put("map.console", lvl + ": " + m.message() + " @" + m.sourceId() + ":" + m.lineNumber());
                }
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
                web.loadUrl("http://127.0.0.1:" + port + "/map-vector.html?ax=" + anchorX + "&ay=" + anchorY + (nightDim ? "&dim=1" : "") + (headingUp ? "&rot=1" : ""));
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
        if (server != null) { server.stop(); server = null; }
        // libera la WebView e il server locale (la mappa viene ricreata quando serve)
        try { web.onPause(); } catch (Throwable ignored) {}
        try { web.removeJavascriptInterface("AndroidMap"); } catch (Throwable ignored) {}
        try { ((android.view.ViewGroup) web.getParent()).removeView(web); } catch (Throwable ignored) {}
        try { web.destroy(); } catch (Throwable ignored) {}
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
            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this); } catch (Throwable ignored) {}
            try { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, this); } catch (Throwable ignored) {}
        } catch (Throwable e) {
            Diag.put("map.stato", "GPS non disponibile: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public void onLocationChanged(Location l) {
        if (l == null) return;
        lastLoc = l;
        if (!haveFix) { haveFix = true; Diag.put("map.stato", "posizione ok"); }
        final double lat = l.getLatitude(), lon = l.getLongitude();
        final float spd = l.hasSpeed() ? l.getSpeed() : -1f;   // m/s dal GPS
        final float brg = l.hasBearing() ? l.getBearing() : -1f;   // calm.113: direzione di marcia per heading-up
        if (!pageReady) return;
        post(() -> {
            try { web.evaluateJavascript("window.setPos(" + lat + "," + lon + "," + spd + "," + brg + ")", null); } catch (Throwable ignored) {}
            if (mapReady) status.setVisibility(GONE);
        });
    }

    /** 1.0.5: mostra il brano in riproduzione in basso a sinistra, accanto al nome della via ("Via | Brano"). */
    public void setNowPlaying(String text) {
        final String t = text == null ? "" : text;
        if (t.equals(lastNP)) return;   // niente aggiornamenti inutili alla WebView
        lastNP = t;
        if (!pageReady) return;
        final String js = "window.setNowPlaying(" + jsStr(t) + ")";
        post(() -> { try { web.evaluateJavascript(js, null); } catch (Throwable ignored) {} });
    }

    /**
     * calm.113: mostra la "prossima manovra" dal feed CarPlay (Apple/Google Maps) come fascia in alto sul
     * quadro. App-agnostica: Apple e Google riempiono gli stessi campi; con Waze resta nascosta.
     */
    public void setTurn(boolean active, String dist, String road, String eta) {
        this.turnActive = active;
        this.turnDist = dist == null ? "" : dist;
        this.turnRoad = road == null ? "" : road;
        this.turnEta = eta == null ? "" : eta;
        if (!pageReady) return;
        final String js = "window.setTurn(" + active + "," + jsStr(turnDist) + "," + jsStr(turnRoad) + "," + jsStr(turnEta) + ")";
        post(() -> { try { web.evaluateJavascript(js, null); } catch (Throwable ignored) {} });
    }

    /**
     * calm.114: percorso IN AVANTI — distanza (m) alla prossima svolta, strada dopo la svolta e un eventuale
     * suggerimento di direzione, dal feed CarPlay. La pagina traccia la strada davanti sulla mappa offline.
     */
    public void setRoute(boolean active, int distM, String road, String turnHint) {
        this.routeActive = active;
        this.routeDistM = distM;
        this.routeRoad = road == null ? "" : road;
        this.routeTurn = turnHint == null ? "" : turnHint;
        if (!pageReady) return;
        final String js = "window.setRoute(" + active + "," + distM + "," + jsStr(routeRoad) + "," + jsStr(routeTurn) + ")";
        post(() -> { try { web.evaluateJavascript(js, null); } catch (Throwable ignored) {} });
    }

    /** Racchiude una stringa come letterale JS sicuro (apici, backslash, a capo). */
    private static String jsStr(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch == '\n' || ch == '\r' || ch == '\t') b.append(' ');
            else b.append(ch);
        }
        return b.append('"').toString();
    }

    @Override public void onProviderDisabled(String p) {}
    @Override public void onProviderEnabled(String p) {}
    @Override public void onStatusChanged(String p, int s, Bundle b) {}

    private final class Bridge {
        @JavascriptInterface
        public void status(String s) {
            if ("page-ready".equals(s)) {
                pageReady = true;
                if (lastLoc != null) post(() -> onLocationChanged(lastLoc));
                if (!lastNP.isEmpty()) {   // 1.0.5: ripristina il brano se la pagina si è (ri)caricata dopo l'avvio musica
                    final String js = "window.setNowPlaying(" + jsStr(lastNP) + ")";
                    post(() -> { try { web.evaluateJavascript(js, null); } catch (Throwable ignored) {} });
                }
                if (turnActive) setTurn(true, turnDist, turnRoad, turnEta);   // calm.113: ripristina la fascia manovra
                if (routeActive) setRoute(true, routeDistM, routeRoad, routeTurn);   // calm.114: ripristina il percorso
            }
            else if ("webgl-yes".equals(s)) { Diag.put("map.webgl", "si (mappe vettoriali offline possibili)"); }
            else if ("webgl-no".equals(s)) { Diag.put("map.webgl", "no (serviranno riquadri raster offline)"); }
            else if ("engine-ok".equals(s)) { mapReady = true; Diag.put("map.engine", "mappa offline ok"); post(() -> status.setVisibility(GONE)); }
            else if (s != null && s.startsWith("engine-error")) { Diag.put("map.engine", "MapLibre ERRORE: " + s); post(() -> { status.setText("Offline map failed – see log"); status.setVisibility(VISIBLE); }); }
            else if (s != null && s.startsWith("style-error")) { Diag.put("map.engine", "stile ERRORE: " + s); }
            else if ("tiles-ok".equals(s)) { mapReady = true; Diag.put("map.stato", "riquadri ok (internet auto)"); post(() -> status.setVisibility(GONE)); }
            else if ("tiles-error".equals(s)) { Diag.put("map.stato", "riquadri OFFLINE (niente internet)"); post(() -> { status.setText(offline ? "" : "Map offline – no data signal"); status.setVisibility(offline ? GONE : VISIBLE); }); }
            else if (s != null && s.startsWith("size:")) { Diag.put("map.size", s.substring(5)); }
            else if (s != null && s.startsWith("query:")) { Diag.put("map.query", s.substring(6)); }
            else if (s != null && s.startsWith("calib:")) { Diag.calib(s.substring(6)); }   // calm.113: taratura percorso
            else if ("fix".equals(s)) { post(() -> { if (mapReady) status.setVisibility(GONE); }); }
        }
    }
}
