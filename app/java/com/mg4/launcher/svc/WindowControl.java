package com.mg4.launcher.svc;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

/**
 * Calm Launcher: alza/abbassa tutti i vetri. Proprieta' float per vetro (da MG4Control / winclose):
 * conducente ha l'auto nativa; gli altri si "tengono premuti" ~5s (nessun anti-pizzicamento).
 * Scrittura diretta via CarProps; se il launcher non ha il permesso, non succede nulla.
 */
public final class WindowControl {
    private static final int DRIVER = 0x11603801, FRONT_R = 0x11603802, REAR_L = 0x11603803, REAR_R = 0x11603804;
    private static final int[] OTHERS = {FRONT_R, REAR_L, REAR_R};
    private static final int AREA = 0x1000000;
    private static final float STOP = 0, MANUAL_UP = 1, MANUAL_DOWN = 2, AUTO_UP = 3, AUTO_DOWN = 4;
    private static final long COURSE_MS = 5_000L;

    private static Handler h;

    private WindowControl() {}

    private static synchronized Handler handler() {
        if (h == null) {
            HandlerThread t = new HandlerThread("calm-windows");
            t.start();
            h = new Handler(t.getLooper());
        }
        return h;
    }

    public static void closeAll(Context c) { handler().post(() -> run(true)); }

    public static void openAll(Context c) { handler().post(() -> run(false)); }

    private static void run(boolean close) {
        CarProps p = VehicleClient.carProps();
        if (p == null) return;
        p.setFloat(DRIVER, AREA, close ? AUTO_UP : AUTO_DOWN);
        float manual = close ? MANUAL_UP : MANUAL_DOWN;
        for (int w : OTHERS) p.setFloat(w, AREA, manual);
        handler().postDelayed(() -> {
            CarProps q = VehicleClient.carProps();
            if (q != null) for (int w : OTHERS) q.setFloat(w, AREA, STOP);
        }, COURSE_MS);
    }
}
