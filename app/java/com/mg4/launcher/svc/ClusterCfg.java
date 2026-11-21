package com.mg4.launcher.svc;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileOutputStream;

/** Scrive il file di configurazione letto dalla DiPlay "grey" per il filtro del cruscotto. */
public final class ClusterCfg {
    public static final File FILE = new File("/sdcard/mg4cplay-grey.cfg");

    /** colour/dim sono 0..100. Scrive sat= e dim=. */
    public static void write(Context c, int colour, int dim) {
        ensureStorage(c);
        String body = "sat=" + clamp(colour) + "\ndim=" + clamp(dim) + "\n";
        try (FileOutputStream o = new FileOutputStream(FILE)) {
            o.write(body.getBytes("UTF-8"));
            Diag.put("cluster.cfg", "scritto sat=" + clamp(colour) + " dim=" + clamp(dim));
        } catch (Exception e) {
            Diag.error("cluster.cfg", e.toString());
        }
    }

    private static int clamp(int v) { return v < 0 ? 0 : v > 100 ? 100 : v; }

    private static void ensureStorage(Context c) {
        try {
            if (c.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                    == PackageManager.PERMISSION_GRANTED) return;
            PackageManager pm = c.getPackageManager();
            pm.getClass().getMethod("grantRuntimePermission", String.class, String.class, android.os.UserHandle.class)
                    .invoke(pm, c.getPackageName(), "android.permission.WRITE_EXTERNAL_STORAGE",
                            android.os.Process.myUserHandle());
        } catch (Throwable ignored) {}
    }
}
