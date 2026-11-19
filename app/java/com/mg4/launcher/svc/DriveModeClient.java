package com.mg4.launcher.svc;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;

/**
 * Calm Launcher: legge/imposta la modalita' di guida.
 * Via PREFERITA: proprieta' veicolo diretta 0x2140a17c (come l'app MG originale) -> funziona anche in marcia,
 * senza bisogno di MG4 Control. Se il launcher non ha il permesso, RIPIEGA su MG4 Control (lettura via
 * ContentProvider, scrittura via broadcast), che pero' blocca la scrittura in movimento.
 */
public final class DriveModeClient {
    private static final int PROP_DRIVE_MODE = 0x2140a17c;   // CarAccTja / drive mode (SliDeeN/MG4Control)
    private static final Uri STATE = Uri.parse("content://com.mg4.control.state/state");
    private static final String PKG = "com.mg4.control";

    private DriveModeClient() {}

    private static String nameOf(int v) {
        switch (v) {
            case 2: return "ECO";
            case 3: return "NORMAL";
            case 4: return "SPORT";
            case 6: return "SNOW";
            case 7: return "CUSTOM";
            default: return null;
        }
    }

    private static int valueOf(String m) {
        if (m == null) return -1;
        switch (m) {
            case "ECO": return 2;
            case "NORMAL": return 3;
            case "SPORT": return 4;
            case "SNOW": return 6;
            case "CUSTOM": return 7;
            default: return -1;
        }
    }

    /** "ECO"/"NORMAL"/"SPORT"/"SNOW"/"CUSTOM" o null. Prima prova diretta, poi MG4 Control. */
    public static String read(Context c) {
        try {
            CarProps p = VehicleClient.carProps();
            if (p != null) {
                String n = nameOf(p.getInt(PROP_DRIVE_MODE));
                if (n != null) return n;
            }
        } catch (Throwable ignored) {
        }
        return readViaMg4Control(c);
    }

    /** Imposta la modalita'. Prima scrittura diretta (vale anche in marcia), poi MG4 Control come ripiego. */
    public static void set(Context c, String mode) {
        int v = valueOf(mode);
        try {
            CarProps p = VehicleClient.carProps();
            if (v >= 0 && p != null && p.setInt(PROP_DRIVE_MODE, v)) return;
        } catch (Throwable ignored) {
        }
        send(c, "drive_mode", mode);
    }

    /** Rigenerazione: OFF|LOW|MEDIUM|HIGH|ADAPTIVE|ONE_PEDAL. Solo via MG4 Control (nessuna proprieta' diretta nota). */
    public static void setRegen(Context c, String level) {
        send(c, "regen", level);
    }

    private static String readViaMg4Control(Context c) {
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

    private static void send(Context c, String key, String value) {
        try {
            Intent i = new Intent("com.mg4.control.action.SET").setPackage(PKG);
            i.putExtra("key", key);
            i.putExtra("value", value);
            c.sendBroadcast(i);
        } catch (Exception ignored) {
        }
    }
}
