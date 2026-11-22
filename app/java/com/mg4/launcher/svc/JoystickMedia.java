package com.mg4.launcher.svc;

import android.content.Context;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Tasti media del joystick al volante (SWI68, modalità media: 4 indietro, 5 avanti, 3 muto).
 * Il servizio media SAIC li inoltra solo alle SUE sorgenti (USB 1/2, Bluetooth, radio); CarPlay, DiPlay
 * e qualunque altra app non li ricevono mai. Qui il launcher li manda come veri tasti media Android alla
 * sessione attiva, così controllano ciò che sta davvero suonando.
 *
 * Per non far saltare due volte, si interviene solo quando la sessione in primo piano NON è una sorgente
 * SAIC (quella la gestisce già il sistema) e non è la libreria del launcher (quella la gestisce l'altro
 * ascoltatore). Rimangono così coperte proprio CarPlay/DiPlay e le app di terze parti, che prima erano morte.
 */
public final class JoystickMedia {
    private JoystickMedia() {}

    private static boolean isSaic(String pkg) {
        if (pkg == null) return false;
        return pkg.startsWith("com.saicmotor.hmi.music") || pkg.startsWith("com.saicmotor.hmi.radio")
                || pkg.startsWith("com.saicmotor.hmi.btcall") || pkg.startsWith("com.saicmotor.hmi.video")
                || pkg.contains("mediaservice");
    }

    /**
     * code: 4 indietro, 5 avanti, 3 (pressione) con LONG_PRESS = play/pausa.
     * @return true se il launcher ha inoltrato il tasto (così WheelKeys non chiama anche l'altro ascoltatore).
     */
    public static boolean handle(Context c, int code, boolean down, boolean longPress) {
        if (!down) return false;
        int key;
        if (code == 5) key = KeyEvent.KEYCODE_MEDIA_NEXT;
        else if (code == 4) key = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        else if (code == 3 && longPress) key = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        else return false;

        // Via principale: leggo la sessione attiva (serve MEDIA_CONTENT_CONTROL) e la piloto direttamente.
        // Così so se è SAIC (già gestita) o la libreria del launcher (altro ascoltatore) e le salto.
        try {
            MediaSessionManager msm = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> list = msm.getActiveSessions(null);
            if (list != null && !list.isEmpty()) {
                MediaController top = list.get(0);
                String pkg = top.getPackageName();
                if (isSaic(pkg) || c.getPackageName().equals(pkg)) return false;
                long t = SystemClock.uptimeMillis();
                top.dispatchMediaButtonEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, key, 0));
                top.dispatchMediaButtonEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, key, 0));
                Diag.put("media.joystick", name(key) + " → " + pkg);
                return true;
            }
        } catch (SecurityException se) {
            // niente MEDIA_CONTENT_CONTROL: si ripiega su AudioManager (sotto)
        } catch (Throwable e) {
            Diag.error("media.joystick", e.toString());
        }

        // Ripiego senza permessi speciali: AudioManager instrada il tasto media alla sessione attiva.
        // Non posso sapere quale pacchetto sia, quindi salto le sorgenti native SAIC (già gestite dal sistema)
        // per non far saltare due volte; CarPlay/Android Auto invece vengono coperte.
        if (com.mg4.launcher.svc.MediaClient.isNativeSaicSource()) {
            Diag.put("media.joystick", "sorgente SAIC nativa: la gestisce il sistema");
            return false;
        }
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            Method m = AudioManager.class.getMethod("dispatchMediaKeyEvent", KeyEvent.class);
            long t = SystemClock.uptimeMillis();
            m.invoke(am, new KeyEvent(t, t, KeyEvent.ACTION_DOWN, key, 0));
            m.invoke(am, new KeyEvent(t, t, KeyEvent.ACTION_UP, key, 0));
            Diag.put("media.joystick", name(key) + " → sessione attiva (AudioManager)");
            return true;
        } catch (Throwable e) {
            Diag.put("media.joystick", "inoltro non riuscito: " + e);
            return false;
        }
    }

    private static String name(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_MEDIA_NEXT: return "avanti";
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return "indietro";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return "play/pausa";
            default: return "tasto " + key;
        }
    }
}
