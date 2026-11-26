package com.mg4.launcher.svc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * calm.118: riceve il feed di navigazione CarPlay (Apple/Google Maps) direttamente dal servizio Allgo.
 *
 * Perché serve: il servizio Allgo (com.allgo.carplay.service.CarPlayService) NON manda la navigazione
 * attraverso la sessione media che ascoltavamo prima — la manda solo a chi si REGISTRA come
 * ISessionStatusCallback tramite ICarPlayService.register(...). I log lo mostravano: onNavRouteGuidanceUpdate
 * scattava ~1 volta al secondo durante la guida, ma noi non eravamo registrati su quel canale.
 *
 * Questa classe ricrea a mano quel piccolo "binder" (interfacce AIDL del servizio) e si registra, così i
 * dati — distanza alla svolta, strada dopo, icona, orario d'arrivo — arrivano a noi e guidano il percorso
 * sul quadro. I valori arrivano divisi su due callback (uno con la distanza, uno con la strada): li uniamo.
 *
 * Nomi/codici presi dal servizio installato (AllgoCarplay EH32), così la comunicazione combacia.
 */
public final class CarPlayNavClient {

    // calm.120: ci leghiamo a RemoteUIService (com.allgo.rui), NON a CarPlayService: quest'ultimo richiede il
    // permesso BIND_TELECOM_CONNECTION_SERVICE (solo il sistema può legarlo → SecurityException nei log).
    // RemoteUIService è esportato senza permesso e inoltra gli stessi eventi (onExtrasChanged) ai client.
    private static final String RUI_DESC     = "com.allgo.rui.IRemoteUIService";
    private static final String SESSION_DESC = "com.allgo.rui.IRemoteSessionCallback";
    private static final String DEVICE_DESC  = "com.allgo.rui.IRemoteDeviceCallback";
    private static final String ERROR_DESC   = "com.allgo.rui.IErrorCallback";
    private static final int TRANSACTION_register = 1;        // IRemoteUIService.register
    private static final int TRANSACTION_onExtrasChanged = 9; // IRemoteSessionCallback.onExtrasChanged

    private final Context ctx;
    private boolean bound;
    private IBinder service;
    private int extrasCount;

    // stato unito della navigazione (i campi arrivano su callback diverse)
    private String navRoad = "";
    private int navMeters = -1;
    private String navEta = "";
    private int navIcon = -1;
    private String navDest = "";      // calm.128: nome destinazione (dal feed)
    private int navTotalM = -1;       // calm.128: distanza totale rimanente (m)
    private String lastDestKey = "";  // per non ri-spingere/ri-cercare uguale
    private long lastUpdate;

    public CarPlayNavClient(Context c) { this.ctx = c.getApplicationContext(); }

    public void start() {
        try {
            Intent it = new Intent();
            it.setComponent(new ComponentName("com.allgo.rui", "com.allgo.rui.RemoteUIService"));
            boolean ok = ctx.bindService(it, conn, Context.BIND_AUTO_CREATE);
            Diag.put("carplay.navbind", ok ? "richiesto bind a RemoteUIService" : "bindService ha risposto false (servizio non trovato?)");
            if (!ok) { try { ctx.unbindService(conn); } catch (Throwable ignored) {} }
        } catch (Throwable t) {
            Diag.error("carplay.navbind", "bind fallito: " + t);
        }
    }

    public void stop() {
        if (bound) { try { ctx.unbindService(conn); } catch (Throwable ignored) {} bound = false; }
        service = null;
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            bound = true; service = binder;
            Diag.put("carplay.navbind", "servizio Allgo collegato, registro il callback nav…");
            registerCallback(binder);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            Diag.put("carplay.navbind", "servizio Allgo disconnesso");
            ClusterMap.pushTurn(false, "", "", "");
            ClusterMap.pushRoute(false, -1, "", "");
        }
    };

    /** Chiama IRemoteUIService.register(deviceCb, sessionCb, errorCb) via transazione 1. */
    private void registerCallback(IBinder svc) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(RUI_DESC);
            data.writeStrongBinder(deviceCb);
            data.writeStrongBinder(sessionCb);
            data.writeStrongBinder(errorCb);
            svc.transact(TRANSACTION_register, data, reply, 0);
            reply.readException();
            Diag.put("carplay.navbind", "registrato: ora ricevo la navigazione CarPlay (Apple/Google)");
        } catch (Throwable t) {
            Diag.error("carplay.navbind", "register fallito: " + t);
        } finally {
            reply.recycle(); data.recycle();
        }
    }

    // ---- callback che RICEVE la navigazione (ISessionStatusCallback) ----
    private final Binder sessionCb = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code >= 1 && code <= 9) {
                data.enforceInterface(SESSION_DESC);
                if (code == TRANSACTION_onExtrasChanged) {
                    String action = data.readString();
                    Bundle b = null;
                    if (data.readInt() != 0) {
                        b = Bundle.CREATOR.createFromParcel(data);
                        try { b.setClassLoader(getClass().getClassLoader()); } catch (Throwable ignored) {}
                    }
                    try { onExtras(action, b); } catch (Throwable t) { Diag.error("carplay.navfeed", t.toString()); }
                }
                if (reply != null) reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };
    // callback richiesti da register() ma che non ci servono: rispondiamo e basta
    private final Binder deviceCb = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code >= 1 && code <= 4) { data.enforceInterface(DEVICE_DESC); if (reply != null) reply.writeNoException(); return true; }
            return super.onTransact(code, data, reply, flags);
        }
    };
    private final Binder errorCb = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 1) { data.enforceInterface(ERROR_DESC); if (reply != null) reply.writeNoException(); return true; }
            return super.onTransact(code, data, reply, flags);
        }
    };

    /** calm.126: elenca TUTTI i campi del Bundle del feed CarPlay con tipo e valore, così si vede esattamente cosa
     *  mandano Apple/Google (per capire se c'è la destinazione o la rotta completa, non solo la prossima svolta). */
    private static String dumpBundle(Bundle b) {
        try {
            java.util.Set<String> keys = b.keySet();
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (String k : keys) {
                Object v = b.get(k);
                String vs;
                if (v == null) vs = "null";
                else if (v instanceof byte[]) vs = "byte[" + ((byte[]) v).length + "]";
                else { vs = String.valueOf(v); if (vs.length() > 80) vs = vs.substring(0, 80) + "…"; }
                if (!first) sb.append(", ");
                first = false;
                sb.append(k).append("=").append(vs);
            }
            return sb.append("}").toString();
        } catch (Throwable t) {
            return b.toString() + " (dump fallito: " + t.getClass().getSimpleName() + ")";
        }
    }

    /** Un aggiornamento del feed: unisce i campi presenti e spinge manovra + percorso sul quadro. */
    private void onExtras(String action, Bundle b) {
        if (b == null || !"ACTION_CARPLAY_NAVI_METADATA".equals(action)) return;
        extrasCount++;
        if (extrasCount <= 5 || extrasCount % 20 == 0)
            Diag.put("carplay.navfeed", "#" + extrasCount + " " + dumpBundle(b));   // calm.126: TUTTI i campi (per capire se c'è destinazione/rotta nascoste)

        boolean changed = false;
        if (b.containsKey("DIST_TO_NEXT_JUNCTION")) { navMeters = b.getInt("DIST_TO_NEXT_JUNCTION", navMeters); changed = true; }
        if (b.containsKey("ROAD_AFTER_JUNCTION"))   { String r = b.getString("ROAD_AFTER_JUNCTION"); if (r != null) { navRoad = r; changed = true; } }
        if (b.containsKey("ICON_ID"))               { navIcon = b.getInt("ICON_ID", navIcon); changed = true; }
        if (b.containsKey("EST_TIME_ARRIVAL")) {
            try {
                java.util.ArrayList<Integer> a = b.getIntegerArrayList("EST_TIME_ARRIVAL");
                // calm.122: EST_TIME_ARRIVAL[0] è l'orario d'arrivo in SECONDI dal 1970 (epoch), non ore:minuti.
                if (a != null && !a.isEmpty() && a.get(0) != null && a.get(0) > 1000000) {
                    long ms = (long) a.get(0).intValue() * 1000L;
                    navEta = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK).format(new java.util.Date(ms));
                    changed = true;
                } else if (a != null && a.size() >= 2) {   // fallback: [ore, minuti]
                    navEta = String.format(java.util.Locale.UK, "%02d:%02d", a.get(0), a.get(1)); changed = true;
                }
            } catch (Throwable ignored) {}
        }
        // calm.128: nome destinazione + distanza totale rimanente. I nomi delle chiavi variano, quindi li cerchiamo
        // per contenuto: una chiave String che contiene "DEST" = nome; un intero con "TOTAL"+"DIST" = distanza totale.
        try {
            for (String k : b.keySet()) {
                String ku = k.toUpperCase(java.util.Locale.ROOT);
                Object v = b.get(k);
                if (v instanceof String && ku.contains("DEST") && !ku.contains("DIST")) {
                    String s = ((String) v).trim();
                    if (!s.isEmpty() && !s.equals(navDest)) { navDest = s; changed = true; }
                } else if (v instanceof Integer && ku.contains("TOTAL") && ku.contains("DIST")) {
                    int tv = (Integer) v;
                    if (tv > 0) navTotalM = tv;
                }
            }
        } catch (Throwable ignored) {}

        if (!changed) return;
        lastUpdate = android.os.SystemClock.uptimeMillis();

        String distText = navMeters < 0 ? "" : (navMeters < 1000 ? navMeters + " m" : String.format(java.util.Locale.UK, "%.1f km", navMeters / 1000f));
        String eta = navEta.isEmpty() ? "" : "ETA " + navEta;
        boolean active = navMeters > 0 || !navRoad.isEmpty();

        Diag.putQuiet("carplay.manovra", (active ? "attiva" : "ferma") + " · " + distText + " · " + navRoad + " · " + eta + " · icon " + navIcon);

        boolean banner = true;
        try { banner = com.mg4.launcher.ui.Prefs.get(ctx).clusterTurn(); } catch (Throwable ignored) {}
        if (banner) ClusterMap.pushTurn(active, distText, navRoad, eta);
        ClusterMap.pushRoute(active, navMeters, navRoad, "");

        // calm.128: spingi la destinazione (nome + distanza totale) per la bandierina, solo quando cambia
        if (!navDest.isEmpty() && navTotalM > 0) {
            String key = navDest + "@" + (navTotalM / 200);
            if (!key.equals(lastDestKey)) { lastDestKey = key; ClusterMap.pushDest(navDest, navTotalM); }
        }
    }
}
