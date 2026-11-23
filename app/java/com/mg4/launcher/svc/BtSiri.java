package com.mg4.launcher.svc;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Avvia Siri sull'iPhone tramite Bluetooth (profilo Hands-Free Client, HFP), così Siri usa il MICROFONO
 * DELL'AUTO invece di quello (muto) del CarPlay wireless.
 *
 * Perché funziona: il microfono dell'auto è raggiungibile sul percorso Bluetooth hands-free (le telefonate
 * BT funzionano), che è gestito dallo stack Bluetooth dell'auto, non dal motore CarPlay nativo chiuso. La
 * chiamata standard BluetoothHeadsetClient.startVoiceRecognition(device) invia il comando AT+BVRA all'iPhone,
 * che avvia Siri su HFP: microfono dell'auto in ingresso, audio dell'auto in uscita.
 *
 * Il launcher è un'app di sistema (android.uid.system, firmata con la chiave di piattaforma), quindi può
 * ottenere il proxy HEADSET_CLIENT e chiamare startVoiceRecognition (API nascosta) via reflection.
 */
public final class BtSiri {

    private static final int HEADSET_CLIENT = 16;   // BluetoothProfile.HEADSET_CLIENT
    private static volatile BluetoothProfile proxy;
    private static volatile String lastResult = "";

    private BtSiri() {}

    /** Da chiamare una volta all'avvio: ottiene il proxy HFP client (asincrono). */
    public static void init(Context c) {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null) { lastResult = "nessun adattatore Bluetooth"; return; }
            a.getProfileProxy(c.getApplicationContext(), new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile p) {
                    if (profile == HEADSET_CLIENT) { proxy = p; Diag.put("bt.siri", "HFP client pronto"); }
                }
                @Override public void onServiceDisconnected(int profile) {
                    if (profile == HEADSET_CLIENT) proxy = null;
                }
            }, HEADSET_CLIENT);
        } catch (Throwable t) {
            lastResult = "init: " + t;
            Diag.error("bt.siri", t.toString());
        }
    }

    /** L'ultimo esito leggibile, per mostrarlo nel pulsante di test. */
    public static String lastResult() { return lastResult; }

    /**
     * Avvia Siri via Bluetooth sull'iPhone connesso in hands-free. Ritorna un messaggio leggibile sull'esito.
     */
    public static String triggerSiri(Context c) {
        try {
            BluetoothProfile p = proxy;
            if (p == null) { init(c); lastResult = "HFP non ancora pronto, riprova tra un attimo"; return lastResult; }
            List<BluetoothDevice> devices = p.getConnectedDevices();   // pubblico su BluetoothProfile
            if (devices == null || devices.isEmpty()) { lastResult = "nessun telefono connesso in hands-free"; return lastResult; }
            BluetoothDevice phone = devices.get(0);
            Method m = p.getClass().getMethod("startVoiceRecognition", BluetoothDevice.class);
            Object ok = m.invoke(p, phone);
            lastResult = "Siri via Bluetooth avviata su " + safeName(phone) + " (esito " + ok + ")";
            Diag.put("bt.siri", lastResult);
            return lastResult;
        } catch (Throwable t) {
            lastResult = "errore: " + t.getClass().getSimpleName() + " " + t.getMessage();
            Diag.error("bt.siri", t.toString());
            return lastResult;
        }
    }

    private static String safeName(BluetoothDevice d) {
        try { String n = d.getName(); return n != null ? n : d.getAddress(); }
        catch (Throwable t) { return "telefono"; }
    }
}
