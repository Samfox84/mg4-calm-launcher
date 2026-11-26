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

    private static final String ICARPLAY_DESC = "com.allgo.carplay.service.ICarPlayService";
    private static final String SESSION_DESC  = "com.allgo.carplay.service.ISessionStatusCallback";
    private static final String CONTROL_DESC  = "com.allgo.carplay.service.IControlClientCallback";
    private static final String ERROR_DESC    = "com.allgo.rui.IErrorCallback";
    private static final int TRANSACTION_register = 1;      // ICarPlayService.register
    private static final int TRANSACTION_onExtrasChanged = 15; // ISessionStatusCallback.onExtrasChanged

    private final Context ctx;
    private boolean bound;
    private IBinder service;
    private int extrasCount;

    // stato unito della navigazione (i campi arrivano su callback diverse)
    private String navRoad = "";
    private int navMeters = -1;
    private String navEta = "";
    private int navIcon = -1;
    private long lastUpdate;

    public CarPlayNavClient(Context c) { this.ctx = c.getApplicationContext(); }

    public void start() {
        try {
            Intent it = new Intent();
            it.setComponent(new ComponentName("com.allgo.carplay.service", "com.allgo.carplay.service.CarPlayService"));
            boolean ok = ctx.bindService(it, conn, Context.BIND_AUTO_CREATE);
            Diag.put("carplay.navbind", ok ? "richiesto bind al servizio Allgo" : "bindService ha risposto false (servizio non trovato?)");
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

    /** Chiama ICarPlayService.register(controlCb, sessionCb, errorCb) via transazione 1. */
    private void registerCallback(IBinder svc) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ICARPLAY_DESC);
            data.writeStrongBinder(controlCb);
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
            if (code >= 1 && code <= 16) {
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
    private final Binder controlCb = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code >= 1 && code <= 3) { data.enforceInterface(CONTROL_DESC); if (reply != null) reply.writeNoException(); return true; }
            return super.onTransact(code, data, reply, flags);
        }
    };
    private final Binder errorCb = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 1) { data.enforceInterface(ERROR_DESC); if (reply != null) reply.writeNoException(); return true; }
            return super.onTransact(code, data, reply, flags);
        }
    };

    /** Un aggiornamento del feed: unisce i campi presenti e spinge manovra + percorso sul quadro. */
    private void onExtras(String action, Bundle b) {
        if (b == null || !"ACTION_CARPLAY_NAVI_METADATA".equals(action)) return;
        extrasCount++;
        if (extrasCount <= 3 || extrasCount % 20 == 0)
            Diag.put("carplay.navfeed", "#" + extrasCount + " " + b.toString());   // bundle completo (taratura)

        boolean changed = false;
        if (b.containsKey("DIST_TO_NEXT_JUNCTION")) { navMeters = b.getInt("DIST_TO_NEXT_JUNCTION", navMeters); changed = true; }
        if (b.containsKey("ROAD_AFTER_JUNCTION"))   { String r = b.getString("ROAD_AFTER_JUNCTION"); if (r != null) { navRoad = r; changed = true; } }
        if (b.containsKey("ICON_ID"))               { navIcon = b.getInt("ICON_ID", navIcon); changed = true; }
        if (b.containsKey("EST_TIME_ARRIVAL")) {
            try {
                java.util.ArrayList<Integer> a = b.getIntegerArrayList("EST_TIME_ARRIVAL");
                if (a != null && a.size() >= 2) { navEta = String.format(java.util.Locale.UK, "%02d:%02d", a.get(0), a.get(1)); changed = true; }
            } catch (Throwable ignored) {}
        }
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
    }
}
