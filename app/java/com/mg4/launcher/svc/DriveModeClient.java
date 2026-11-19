package com.mg4.launcher.svc;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;

/**
 * Calm Launcher: legge/imposta la modalita' di guida tramite MG4 Control (se installato e con
 * "API esterna" attiva nelle sue impostazioni). Lettura via ContentProvider, scrittura via broadcast.
 * Degrada senza errori se MG4 Control manca o l'API e' spenta: read() torna null, set() non fa nulla.
 */
public final class DriveModeClient {
    private static final Uri STATE = Uri.parse("content://com.mg4.control.state/state");
    private static final String PKG = "com.mg4.control";

    private DriveModeClient() {}

    /** "ECO", "NORMAL", "SPORT", "SNOW", "CUSTOM" oppure null se non leggibile. */
    public static String read(Context c) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(STATE, null, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                int i = cur.getColumnIndex("drive_mode");
                return i >= 0 ? cur.getString(i) : null;
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) try { cur.close(); } catch (Exception ignored) {}
        }
        return null;
    }

    /** Imposta la modalita' (ECO/NORMAL/SPORT/SNOW/CUSTOM) via MG4 Control. L'auto rifiuta in marcia. */
    public static void set(Context c, String mode) {
        try {
            Intent i = new Intent("com.mg4.control.action.SET").setPackage(PKG);
            i.putExtra("key", "drive_mode");
            i.putExtra("value", mode);
            c.sendBroadcast(i);
        } catch (Exception ignored) {
        }
    }
}
