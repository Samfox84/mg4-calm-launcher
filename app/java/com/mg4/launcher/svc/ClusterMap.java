package com.mg4.launcher.svc;

import android.app.Presentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import com.mg4.launcher.ui.LiveMap;
import com.mg4.launcher.ui.Prefs;

/**
 * Calm Launcher (calm.109, TEST): draws the launcher's own live "where are we" map on the instrument
 * CLUSTER, as a second-display {@link Presentation} hosting the existing {@link LiveMap}.
 *
 * <p>Position marker only — NOT turn-by-turn. OFF by default (Settings → toggle). Independent of
 * CarPlay/DiPlay: it only needs GPS and the offline map the launcher already carries. If DiPlay is
 * running its own cluster, the user turns this off and nothing here touches it.
 *
 * <p>Everything is best-effort and wrapped in try/catch: if the cluster display can't be found or
 * won't accept a Presentation, it logs to diagnostics and does nothing, so it can NEVER stop the
 * launcher from starting. Every step is reported through {@link Diag} (the channel uploaded to the
 * private log repo) so a run can be bug-tested afterwards — keys: cluster.map.*
 */
public final class ClusterMap {

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static Presentation presentation;
    private static int shownOn = -1;

    private ClusterMap() {}

    /** Show the cluster map if the setting is on, hide it if off. Reads the setting itself. */
    public static void refresh(Context c) {
        if (c == null) return;
        boolean on = false;
        try { on = Prefs.get(c).clusterMap(); } catch (Throwable t) { Diag.error("cluster.map.pref", t.toString()); }
        apply(c, on);
    }

    /** Turn the cluster map on/off. Safe to call repeatedly and from any thread. */
    public static void apply(Context c, boolean on) {
        if (c == null) return;
        final Context app = c.getApplicationContext();
        Diag.put("cluster.map.toggle", on ? "ON richiesto" : "OFF richiesto");
        UI.post(() -> {
            try {
                if (on) show(app); else hide("spento dall'utente");
            } catch (Throwable t) {
                Diag.error("cluster.map", "apply fallito: " + t);
                hide("errore in apply");
            }
        });
    }

    public static void stop() { UI.post(() -> hide("stop")); }

    /** Re-show with the latest position setting (used when the user nudges the cluster position sliders). */
    public static void restart(Context c) {
        if (c == null) return;
        final Context app = c.getApplicationContext();
        boolean on = false;
        try { on = Prefs.get(app).clusterMap(); } catch (Throwable ignored) {}
        if (!on) return;
        UI.post(() -> { try { hide("riposiziono"); show(app); } catch (Throwable t) { Diag.error("cluster.map", "restart: " + t); } });
    }

    private static void show(Context app) {
        logDisplays(app);
        Display cluster = findClusterDisplay(app);
        if (cluster == null) {
            Diag.put("cluster.map", "NESSUN display cluster trovato (solo lo schermo principale) — niente da mostrare");
            hide("nessun display");
            return;
        }
        if (presentation != null && presentation.isShowing() && shownOn == cluster.getDisplayId()) {
            Diag.put("cluster.map", "gia' attiva sul display " + shownOn);
            return;
        }
        hide("ricreo");

        LiveMap map;
        try {
            Prefs p = Prefs.get(app);
            map = new LiveMap(app, true, p.clusterPosX() / 100f, p.clusterPosY() / 100f);   // nightDim + ancora del pallino
            Diag.put("cluster.map", "posizione pallino X=" + p.clusterPosX() + "% Y=" + p.clusterPosY() + "%");
        } catch (Throwable t) {
            Diag.error("cluster.map", "LiveMap non creata: " + t);
            return;
        }

        // Due tentativi: prima il tipo Presentation predefinito, poi un tipo di finestra di sistema
        // (serve quando si mostra da un contesto non-Activity; il launcher e' app di sistema).
        int[] types = { -1, WindowManager.LayoutParams.TYPE_SYSTEM_ALERT };
        for (int i = 0; i < types.length; i++) {
            Presentation p = null;
            try {
                p = new Presentation(app, cluster);
                p.setTitle("Calm cluster map");
                p.setCancelable(false);
                android.view.Window w = p.getWindow();
                if (w != null) {
                    if (types[i] != -1) w.setType(types[i]);
                    // Riempi il display del quadro bordo a bordo: così la mappa copre tutto e il pallino
                    // finisce da solo al centro, senza regolazioni manuali.
                    try {
                        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF0E1114));
                        w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT);
                        w.setGravity(android.view.Gravity.FILL);
                        w.getDecorView().setPadding(0, 0, 0, 0);
                        android.view.WindowManager.LayoutParams lp = w.getAttributes();
                        lp.horizontalMargin = 0f; lp.verticalMargin = 0f;
                        w.setAttributes(lp);
                    } catch (Throwable ignored) {}
                }
                p.setContentView(map);
                p.show();
                presentation = p;
                shownOn = cluster.getDisplayId();
                Diag.put("cluster.map", "MOSTRATA sul display " + shownOn + " ('" + cluster.getName() + "') tentativo "
                        + (i + 1) + (types[i] == -1 ? " (tipo predefinito)" : " (tipo system-alert)"));
                return;
            } catch (Throwable t) {
                Diag.error("cluster.map", "tentativo " + (i + 1) + " fallito: " + t);
                try { if (p != null) p.dismiss(); } catch (Throwable ignored) {}
                // alla prossima iterazione la stessa LiveMap viene riusata: va staccata dalla finestra morta
                try {
                    android.view.ViewParent vp = map.getParent();
                    if (vp instanceof android.view.ViewGroup) ((android.view.ViewGroup) vp).removeView(map);
                } catch (Throwable ignored) {}
            }
        }
        Diag.put("cluster.map", "impossibile aprire la Presentation sul quadro — resta tutto com'era");
        presentation = null;
        shownOn = -1;
    }

    private static void hide(String why) {
        if (presentation != null) {
            try { presentation.dismiss(); Diag.put("cluster.map", "chiusa (" + why + ")"); }
            catch (Throwable t) { Diag.error("cluster.map", "chiusura: " + t); }
            presentation = null;
            shownOn = -1;
        }
    }

    /** Preferisce un display di categoria "presentation"; altrimenti il primo display diverso dal principale. */
    private static Display findClusterDisplay(Context app) {
        try {
            DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) { Diag.put("cluster.map", "DisplayManager assente"); return null; }
            Display[] pres = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
            if (pres != null && pres.length > 0) {
                Diag.put("cluster.map", "scelto display 'presentation' id=" + pres[0].getDisplayId());
                return pres[0];
            }
            for (Display d : dm.getDisplays()) {
                if (d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                    Diag.put("cluster.map", "nessun display 'presentation'; uso il secondario id=" + d.getDisplayId());
                    return d;
                }
            }
            Diag.put("cluster.map", "solo il display principale presente");
        } catch (Throwable t) {
            Diag.error("cluster.map", "ricerca display: " + t);
        }
        return null;
    }

    /** Elenca tutti i display (id, nome, dimensioni, stato) nel log, per capire cosa vede l'auto. */
    private static void logDisplays(Context app) {
        try {
            DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return;
            Display[] all = dm.getDisplays();
            StringBuilder sb = new StringBuilder("n=").append(all.length);
            for (Display d : all) {
                DisplayMetrics m = new DisplayMetrics();
                try { d.getRealMetrics(m); } catch (Throwable ignored) {}
                sb.append(" | id=").append(d.getDisplayId())
                  .append(" '").append(d.getName()).append("'")
                  .append(" ").append(m.widthPixels).append("x").append(m.heightPixels)
                  .append(" state=").append(d.getState());
            }
            Diag.put("cluster.map.displays", sb.toString());
        } catch (Throwable t) {
            Diag.error("cluster.map.displays", t.toString());
        }
    }
}
