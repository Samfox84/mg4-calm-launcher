package com.mg4.launcher.svc;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Calm Launcher: accende l'hotspot dell'auto a ogni avvio, così DiPlay/CarPlay wireless si collega al telefono senza
 * doverlo attivare a mano.
 * <p>
 * Usa lo stesso metodo di DiPlay su Android 9 (API 28): IConnectivityManager.startTethering via reflection, che qui è
 * autorizzato dai permessi di sistema del launcher (firmato con la chiave platform), senza bisogno di ADB. Se
 * l'hotspot è già acceso non tocca niente. Ogni tentativo finisce nel log con chiave "hotspot".
 */
public final class CarHotspot {
    private static final int TETHERING_WIFI = 0;
    private static final int WIFI_AP_STATE_ENABLING = 12, WIFI_AP_STATE_ENABLED = 13;
    private static final long MIN_GAP_MS = 20_000L;   // non ritentare più spesso di così
    private static volatile long lastAttempt;

    private CarHotspot() {}

    /** Da chiamare quando il launcher va in primo piano. Lavora in background e salta se ha appena tentato. */
    public static void ensureOn(Context ctx) {
        long now = SystemClock.elapsedRealtime();
        if (lastAttempt != 0 && now - lastAttempt < MIN_GAP_MS) return;
        lastAttempt = now;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                int st = apState(app);
                if (st == WIFI_AP_STATE_ENABLED || st == WIFI_AP_STATE_ENABLING) {
                    Diag.put("hotspot", "già acceso (stato " + st + ")");
                    return;
                }
                exemptHiddenApis();
                start(app);
            } catch (Throwable t) {
                Diag.put("hotspot", "errore: " + t.getClass().getSimpleName()
                        + (t.getMessage() != null ? " " + t.getMessage() : ""));
            }
        }, "mg4-hotspot").start();
    }

    /** Stato attuale dell'access point dal broadcast sticky; -1 se sconosciuto. */
    private static int apState(Context ctx) {
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"));
            return i == null ? -1 : i.getIntExtra("wifi_state", -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void start(Context ctx) {
        Object service = connectivityService(ctx);
        if (service == null) {
            Diag.put("hotspot", "servizio connettività non raggiungibile");
            return;
        }
        ResultReceiver receiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                // code 0 = TETHER_ERROR_NO_ERROR
                Diag.put("hotspot", "esito tethering code=" + code + (code == 0 ? " (acceso)" : ""));
            }
        };
        Class<?>[][] sigs = {
            {int.class, ResultReceiver.class, boolean.class, String.class},
            {int.class, ResultReceiver.class, boolean.class},
        };
        Object[][] args = {
            {TETHERING_WIFI, receiver, false, ctx.getPackageName()},
            {TETHERING_WIFI, receiver, false},
        };
        Class<?>[] owners = {forNameOrNull("android.net.IConnectivityManager"), service.getClass()};
        StringBuilder fail = new StringBuilder();
        for (Class<?> owner : owners) {
            if (owner == null) continue;
            for (int i = 0; i < sigs.length; i++) {
                Method m;
                try {
                    m = owner.getMethod("startTethering", sigs[i]);
                } catch (Throwable t) {
                    fail.append(owner.getSimpleName()).append("/").append(sigs[i].length)
                        .append(": ").append(t.getClass().getSimpleName()).append("; ");
                    continue;
                }
                try { m.setAccessible(true); } catch (Throwable ignored) {}
                try {
                    m.invoke(service, args[i]);
                    Diag.put("hotspot", "startTethering chiamato (" + owner.getSimpleName() + "/" + sigs[i].length + ")");
                    return;
                } catch (InvocationTargetException e) {
                    Throwable c = e.getTargetException();
                    Diag.put("hotspot", "startTethering rifiutato dal sistema: "
                            + (c != null ? c.getClass().getSimpleName() + " " + c.getMessage() : "?"));
                    return;
                } catch (Throwable t) {
                    fail.append(owner.getSimpleName()).append("/").append(sigs[i].length)
                        .append(": ").append(t.getClass().getSimpleName()).append("; ");
                }
            }
        }
        Diag.put("hotspot", "startTethering non disponibile: " + fail);
    }

    private static Object connectivityService(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            Field f = ConnectivityManager.class.getDeclaredField("mService");
            f.setAccessible(true);
            Object s = f.get(cm);
            if (s != null) return s;
        } catch (Throwable ignored) {}
        try {
            IBinder b = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, Context.CONNECTIVITY_SERVICE);
            return Class.forName("android.net.IConnectivityManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, b);
        } catch (Throwable ignored) {}
        return null;
    }

    private static Class<?> forNameOrNull(String n) {
        try { return Class.forName(n); } catch (Throwable t) { return null; }
    }

    /** Su Android 9/10 le API nascoste vanno esentate o la reflection su mService/IConnectivityManager è bloccata. */
    private static void exemptHiddenApis() {
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);
            Class<?> vm = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(vm, "getRuntime", new Class[0]);
            Method setExemptions = (Method) getDeclaredMethod.invoke(
                    vm, "setHiddenApiExemptions", new Class[]{String[].class});
            Object runtime = getRuntime.invoke(null);
            setExemptions.invoke(runtime, (Object) new String[]{
                "Landroid/net/IConnectivityManager",
                "Landroid/net/ConnectivityManager;->mService",
                "Landroid/os/ServiceManager;",
            });
        } catch (Throwable ignored) {}
    }
}
