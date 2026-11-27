package com.mg4.launcher.svc;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import com.mg4.launcher.ui.Prefs;
import com.mg4.launcher.ui.Units;

/**
 * calm.130: mostra le svolte di CarPlay sul cruscotto NATIVO dell'auto, senza reflash né patch.
 *
 * <p>Differenza da {@link ClusterMap}: ClusterMap disegna una NOSTRA Presentation sul display del
 * quadro (mappa ricostruita o specchio). ClusterFeed invece ALIMENTA il renderer NATIVO del quadro:
 * manda i dati-svolta al servizio adattatore SAIC (com.saicmotor.adapterservice / MapService), che è
 * ciò che la app cluster (com.saicmotor.hmi.clusterprojection) disegna da sola. Così il quadro mostra
 * la sua pagina navigazione vera, mentre CarPlay cablato tiene schermo, navigazione e MICROFONO.
 *
 * <p>I dati arrivano già uniti da {@link CarPlayNavClient#onExtras} (distanza, strada, icona): qui li
 * traduciamo nel vocabolario Telenav/MG (docs/navigation/native-maneuver-codes.csv) e li spingiamo in
 * DUE modi, entrambi attivi per il test:
 *   1) AIDL su IMapService (esportato, senza permesso): requestProjection() + isMapNavigating(true) +
 *      guideStatusChange(1) + guideInfosChange(icona,distanza,via) + roadInfoChange(via).
 *   2) Broadcast com.saicmotor.navimaps_auto.GUIDE_INFO / GUIDE_STATUS (stessi che ascolta il
 *      BroadcastReceiver non protetto di MapService), col marcatore mg4src così NavClient li ignora.
 *
 * <p>Esclusivo con ClusterMap: se ClusterFeed è attivo, la NOSTRA Presentation sul quadro va spenta
 * (altrimenti litigano per lo stesso display). Il toggle nelle impostazioni lo garantisce.
 *
 * <p>Tutto best-effort e loggato (chiavi cluster.feed.*) per il test di una sola uscita in auto.
 */
public final class ClusterFeed {

    private static final String PKG = "com.saicmotor.adapterservice";
    private static final String MAP_CLS = "com.saicmotor.adapterservice.services.MapService";
    private static final String MAP = "com.saicmotor.adapterservice.IMapService";

    // Codici transazione di IMapService (verificati in SaicAdapterService EH32).
    private static final int T_getClusterIsReady = 16;
    private static final int T_isMapNavigating = 22;
    private static final int T_requestProjection = 27;
    private static final int T_guideStatusChange = 29;
    private static final int T_guideInfosChange = 30;
    private static final int T_roadInfoChange = 31;
    private static final int T_getClusterIsOnNaviTab = 55;

    private static final String BC_INFO = "com.saicmotor.navimaps_auto.GUIDE_INFO";
    private static final String BC_STATUS = "com.saicmotor.navimaps_auto.GUIDE_STATUS";
    private static final String BC_INFO_TT = "com.tomtom.navigation.GUIDE_INFO";
    private static final String BC_STATUS_TT = "com.tomtom.navigation.GUIDE_STATUS";
    /** Marcatore: i NOSTRI broadcast, così NavClient non li riscambia per il navigatore nativo. */
    public static final String SRC_EXTRA = "mg4src";
    public static final String SRC_VALUE = "clusterfeed";

    private static final int GUIDE_START = 1, GUIDE_STOP = 0;   // NavigationMapper.GUIDE_STATUS_START = 1
    private static final long STALE_MS = 12_000;
    private static final long TICK_MS = 1_000;
    private static final long REPROJECT_MS = 5_000;

    private static Context app;
    private static ServiceLink link;
    private static HandlerThread thread;
    private static Handler bg;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile boolean running;
    private static boolean wasActive;
    private static long lastReproject, lastReadback;

    // ultima svolta ricevuta da CarPlayNavClient (già unita)
    private static volatile boolean curActive;
    private static volatile int curCode = -1, curDist = -1, curRawIcon = -1, curMeters = -1;
    private static volatile String curRoad = "";
    private static volatile long lastPush;

    private ClusterFeed() {}

    /** Avvia l'alimentazione del cruscotto nativo (se attiva nelle impostazioni). Idempotente. */
    public static synchronized void start(Context c) {
        if (c == null) return;
        app = c.getApplicationContext();
        boolean on;
        try { on = Prefs.get(app).clusterFeed(); } catch (Throwable t) { on = true; }
        if (!on) { Diag.put("cluster.feed", "disattivato nelle impostazioni"); return; }
        if (running) return;
        running = true;
        if (link == null) link = new ServiceLink(app, "cluster-adapter", ServiceLink.byComponent(PKG, MAP_CLS, null));
        try { link.start(); } catch (Throwable t) { Diag.error("cluster.feed.bind", t.toString()); }
        if (thread == null) { thread = new HandlerThread("mg4-cluster-feed"); thread.start(); bg = new Handler(thread.getLooper()); }
        Diag.put("cluster.feed", "avviato (alimenta il cruscotto nativo dalle svolte CarPlay)");
        bg.removeCallbacks(loop);
        bg.post(loop);
    }

    /** Ferma e porta a riposo la pagina navigazione del cruscotto. */
    public static synchronized void stop() {
        if (!running && !wasActive) { if (link != null) safeUnbind(); return; }
        running = false;
        if (bg != null) bg.removeCallbacks(loop);
        if (wasActive) { sendStatus(GUIDE_STOP); wasActive = false; }
        safeUnbind();
        curActive = false;
        Diag.put("cluster.feed", "fermato");
    }

    private static void safeUnbind() {
        try { if (link != null) link.stop(); } catch (Throwable ignored) {}
    }

    /**
     * Chiamata da CarPlayNavClient a ogni aggiornamento del feed (già unito). Non manda nulla da sola:
     * aggiorna lo stato e il timer periodico fa gli invii (così abbiamo un heartbeat anche se CarPlay
     * smette di aggiornare).
     */
    public static void push(boolean active, int carplayIcon, int meters, String road) {
        if (!running) return;
        if (!active) { curActive = false; return; }
        curActive = true;
        curRawIcon = carplayIcon;
        curMeters = meters;
        curCode = mapManeuver(carplayIcon, -1);
        curDist = toClusterDistance(meters);
        curRoad = road == null ? "" : road.trim();
        lastPush = SystemClock.uptimeMillis();
    }

    private static final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try { tick(); } catch (Throwable t) { Diag.error("cluster.feed", t.toString()); }
            if (running && bg != null) bg.postDelayed(this, TICK_MS);
        }
    };

    private static void tick() {
        boolean fresh = curActive && (SystemClock.uptimeMillis() - lastPush) < STALE_MS;
        if (!fresh) {
            if (wasActive) {
                Diag.put("cluster.feed", "navigazione CarPlay ferma → cruscotto a riposo");
                sendStatus(GUIDE_STOP);
                wasActive = false;
            }
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (!wasActive) {
            wasActive = true;
            Diag.put("cluster.feed", "navigazione CarPlay attiva → alimento il cruscotto nativo");
            sendStatus(GUIDE_START);
            requestProjection();
            lastReproject = now;
        } else {
            sendStatus(GUIDE_START);   // heartbeat: tiene aperta la pagina navigazione
            if (now - lastReproject >= REPROJECT_MS) { requestProjection(); lastReproject = now; }
        }

        sendInfo(curCode, curDist, curRoad);

        Diag.put("cluster.feed.svolta",
                "CarPlay icon=" + curRawIcon + " dist=" + curMeters + "m → codiceMG=" + curCode
                        + " dist=" + curDist + (Units.navUnit == 2 ? " ft" : " m")
                        + " via=" + ManeuverReport.instructionType(curRoad));

        if (now - lastReadback >= REPROJECT_MS) { readbackClusterState(); lastReadback = now; }
    }

    // ---------- invio ----------

    private static void sendStatus(int status) {
        IBinder b = link == null ? null : link.binder();
        if (b != null) {
            try {
                final int st = status;
                Rpc.call(b, MAP, T_guideStatusChange, p -> p.writeInt(st));
                // isMapNavigating(true) all'avvio; (false) in chiusura → il MapService manda un nav-end pulito al quadro
                Rpc.call(b, MAP, T_isMapNavigating, p -> p.writeInt(st == GUIDE_START ? 1 : 0));
            } catch (Throwable e) {
                Diag.error("cluster.feed.statoAIDL", e.toString());
            }
        } else {
            Diag.put("cluster.feed.aidl", "MapService non ancora collegato (per ora solo broadcast)");
        }
        broadcastStatus(BC_STATUS, status);
        broadcastStatus(BC_STATUS_TT, status);
    }

    private static void sendInfo(int code, int dist, String road) {
        IBinder b = link == null ? null : link.binder();
        if (b != null) {
            try {
                final int c = code, d = dist;
                final String r = road;
                Rpc.call(b, MAP, T_guideInfosChange, p -> { p.writeInt(c); p.writeInt(d); p.writeString(r); });
                Rpc.call(b, MAP, T_roadInfoChange, p -> p.writeString(r));
            } catch (Throwable e) {
                Diag.error("cluster.feed.infoAIDL", e.toString());
            }
        }
        broadcastInfo(BC_INFO, code, dist, road);
        broadcastInfo(BC_INFO_TT, code, dist, road);
    }

    private static void requestProjection() {
        IBinder b = link == null ? null : link.binder();
        if (b == null) return;
        try {
            Rpc.call(b, MAP, T_requestProjection, Rpc.NONE);
            Diag.put("cluster.feed.proiezione", "requestProjection inviata");
        } catch (Throwable e) {
            Diag.error("cluster.feed.proiezione", e.toString());
        }
    }

    private static void readbackClusterState() {
        IBinder b = link == null ? null : link.binder();
        if (b == null) return;
        try {
            boolean ready = Rpc.callBool(b, MAP, T_getClusterIsReady);
            boolean onNavi = Rpc.callBool(b, MAP, T_getClusterIsOnNaviTab);
            Diag.put("cluster.feed.statoCruscotto", "pronto=" + ready + " paginaNavi=" + onNavi);
        } catch (Throwable e) {
            Diag.error("cluster.feed.statoCruscotto", e.toString());
        }
    }

    private static void broadcastStatus(String action, int status) {
        if (app == null) return;
        try {
            Intent i = new Intent(action);
            i.putExtra("STATUS", status);
            i.putExtra(SRC_EXTRA, SRC_VALUE);
            app.sendBroadcast(i);
        } catch (Throwable e) {
            Diag.error("cluster.feed.bcStato", e.toString());
        }
    }

    private static void broadcastInfo(String action, int code, int dist, String road) {
        if (app == null) return;
        try {
            Intent i = new Intent(action);
            i.putExtra("turnIcon", code);
            i.putExtra("turnDist", dist);
            i.putExtra("dirName", road);
            i.putExtra(SRC_EXTRA, SRC_VALUE);
            app.sendBroadcast(i);
        } catch (Throwable e) {
            Diag.error("cluster.feed.bcInfo", e.toString());
        }
    }

    // ---------- traduzione ----------

    /** Manovra CarPlay (NavRouteManeuverUpdateInfo.mManeuverType) → codice icona MG/Telenav 0..39. UK: guida a sinistra. */
    static int mapManeuver(int carplay, int exitAngle) {
        switch (carplay) {
            case 0:  return 0;   // NO_TURN
            case 1:  return 1;   // LEFT_TURN
            case 2:  return 2;   // RIGHT_TURN
            case 3:  return 0;   // STRAIGHT_AHEAD
            case 4:  return 6;   // U_TURN → inversione a destra (UK)
            case 5:  return 0;   // FOLLOW_ROAD
            case 6:  return roundabout(exitAngle);   // ENTER_ROUNDABOUT
            case 7:  return 0;   // EXIT_ROUNDABOUT
            case 8:  return 36;  // OFF_RAMP → uscita sinistra
            case 9:  return 34;  // ON_RAMP → entra a sinistra
            case 10: return 20;  // ARRIVE_END_OF_NAVIGATION
            case 11: return 0;   // START_ROUTE
            case 12: return 20;  // ARRIVE_AT_DESTINATION
            case 13: return 17;  // KEEP_LEFT
            case 14: return 18;  // KEEP_RIGHT
            case 15: return 19;  // ENTER_FERRY
            case 16: return 19;  // EXIT_FERRY
            case 17: return 19;  // CHANGE_FERRY
            case 18: return 6;   // START_ROUTE_WITH_U_TURN
            case 19: return 12;  // U_TURN_AT_ROUNDABOUT
            case 20: return 1;   // LEFT_TURN_AT_END
            case 21: return 2;   // RIGHT_TURN_AT_END
            case 22: return 36;  // HIGHWAY_OFF_RAMP_LEFT
            case 23: return 37;  // HIGHWAY_OFF_RAMP_RIGHT
            case 24: return 21;  // ARRIVE_AT_DESTINATION_LEFT
            case 25: return 22;  // ARRIVE_AT_DESTINATION_RIGHT
            case 26: return 6;   // U_TURN_WHEN_POSSIBLE
            case 27: return 20;  // ARRIVE_END_OF_DIRECTIONS
            case 47: return 8;   // SHARP_LEFT_TURN
            case 48: return 7;   // SHARP_RIGHT_TURN
            case 49: return 3;   // SLIGHT_LEFT_TURN
            case 50: return 4;   // SLIGHT_RIGHT_TURN
            case 51: return 0;   // CHANGE_HIGHWAY
            case 52: return 34;  // CHANGE_HIGHWAY_LEFT
            case 53: return 35;  // CHANGE_HIGHWAY_RIGHT
            default: return 0;   // sconosciuto → dritto (sicuro); l'icona grezza è nei log
        }
    }

    /** Rotonda: senza convenzione certa sull'angolo, icona "rotonda dritto" + log dell'angolo per affinare. */
    private static int roundabout(int exitAngle) {
        if (exitAngle < 0) return 9;
        if (exitAngle <= 30 || exitAngle >= 330) return 9;
        if (exitAngle > 150 && exitAngle < 210) return 12;
        if (exitAngle < 180) return 11;
        return 10;
    }

    /** Distanza (metri, come la fornisce CarPlayNavClient) → unità del cruscotto: metri o piedi (imperiale). */
    static int toClusterDistance(int meters) {
        if (meters < 0) return 0;
        if (Units.navUnit == 2) return (int) Math.round(meters / 0.3048); // cruscotto imperiale → piedi
        return meters;                                                    // cruscotto metrico → metri
    }
}
