package com.mg4.launcher.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.Gravity;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.mg4.launcher.svc.Diag;

/**
 * Calm Launcher: mappa "dove siamo" dal vivo (AuDHD). NON è un navigatore: mostra solo la posizione dell'auto su una
 * mappa scura e calma con i nomi delle vie, che si ricentra mentre si guida. Leaflet è incluso negli asset; solo i
 * riquadri (tiles) arrivano da internet (dati dell'auto). Lo stato finisce nel log come map.stato per capire, dalla
 * prossima diagnostica, se i riquadri si scaricano in auto.
 */
@SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
public final class LiveMap extends FrameLayout implements LocationListener {

    private final WebView web;
    private final TextView status;
    private final LocationManager lm;
    private boolean pageReady, started, haveFix, tilesOk;

    public LiveMap(Context c) {
        super(c);
        setBackgroundColor(0xFF0E1114);
        web = new WebView(c);
        web.setBackgroundColor(0xFF0E1114);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
        web.addJavascriptInterface(new Bridge(), "AndroidMap");
        web.setWebViewClient(new android.webkit.WebViewClient());
        addView(web, new LayoutParams(-1, -1));

        status = new TextView(c);
        status.setTextColor(0xFFAEB6BF);
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        status.setText("Finding your position…");
        addView(status, new LayoutParams(-2, -2, Gravity.CENTER));

        lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        web.loadUrl("file:///android_asset/map/index.html");
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
            try {
                web.evaluateJavascript("window.setPos(" + lat + "," + lon + ")", null);
            } catch (Throwable ignored) {}
            if (tilesOk) status.setVisibility(GONE);
        });
    }

    @Override public void onProviderDisabled(String p) {}
    @Override public void onProviderEnabled(String p) {}
    @Override public void onStatusChanged(String p, int s, Bundle b) {}

    private final class Bridge {
        @JavascriptInterface
        public void status(String s) {
            if ("page-ready".equals(s)) { pageReady = true; }
            else if ("tiles-ok".equals(s)) { tilesOk = true; Diag.put("map.stato", "riquadri ok (internet auto)"); post(() -> { if (haveFix) status.setVisibility(GONE); }); }
            else if ("tiles-error".equals(s)) { Diag.put("map.stato", "riquadri OFFLINE (niente internet per la mappa)"); post(() -> { status.setText("Map offline – no data signal"); status.setVisibility(VISIBLE); }); }
            else if ("fix".equals(s)) { post(() -> { if (tilesOk) status.setVisibility(GONE); }); }
        }
    }
}
