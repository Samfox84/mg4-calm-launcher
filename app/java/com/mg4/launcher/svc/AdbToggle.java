package com.mg4.launcher.svc;

import android.content.Context;
import android.provider.Settings;

/**
 * 1.0.5: interruttore ADB (debug USB) direttamente dal launcher. La modalità tecnica dell'auto lo accende
 * scrivendo Settings.Global.adb_enabled = 1; il launcher ha WRITE_SECURE_SETTINGS, quindi può farlo da solo,
 * senza entrare nella modalità tecnica né inserire il PIN. Serve un cavo verso un PC per usarlo davvero.
 */
public final class AdbToggle {
    private AdbToggle() {}

    public static boolean enabled(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
        } catch (Throwable e) {
            return false;
        }
    }

    /** @return true se la scrittura è andata a buon fine. */
    public static boolean set(Context c, boolean on) {
        try {
            Settings.Global.putInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, on ? 1 : 0);
            Diag.put("adb", on ? "attivato (adb_enabled = 1)" : "disattivato");
            return true;
        } catch (Throwable e) {
            Diag.error("adb", e.toString());
            return false;
        }
    }
}
