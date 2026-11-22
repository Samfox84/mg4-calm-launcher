package com.mg4.launcher.svc;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import com.mg4.launcher.R;

import java.util.EnumMap;
import java.util.Map;

/**
 * Calm Launcher: suoni soft (pacchetto Pacely completo) sulle interazioni. Canale "sonification":
 * niente focus audio, la musica continua. Tutti i 20 suoni del pacchetto sono caricati; quelli con
 * un gesto corrispondente nel launcher vengono usati (avvio, tocco, toggle on/off, apri/chiudi,
 * conferma, cambio pagina, aggiungi/rimuovi, errore), gli altri restano disponibili.
 */
public final class Sfx {
    public enum S {
        TAP, TOGGLE_ON, TOGGLE_OFF, OPEN, CLOSE, DONE, STARTUP, SWIPE,
        ADD, DELETE, ERROR, UNDO, PAUSE, STEP, NEXT, FOCUS, PICKUP, DROP, HOVER, TIMER
    }

    private static Sfx I;
    private static boolean enabled;

    private final SoundPool pool;
    private final Map<S, Integer> ids = new EnumMap<>(S.class);

    private Sfx(Context c) {
        pool = new SoundPool.Builder().setMaxStreams(6)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .build();
        load(c, S.TAP, R.raw.tab);
        load(c, S.TOGGLE_ON, R.raw.toggle_on);
        load(c, S.TOGGLE_OFF, R.raw.toggle_off);
        load(c, S.OPEN, R.raw.open);
        load(c, S.CLOSE, R.raw.close);
        load(c, S.DONE, R.raw.done);
        load(c, S.STARTUP, R.raw.welcome);
        load(c, S.SWIPE, R.raw.swipe);
        load(c, S.ADD, R.raw.add);
        load(c, S.DELETE, R.raw.delete);
        load(c, S.ERROR, R.raw.gentle_error);
        load(c, S.UNDO, R.raw.undo);
        load(c, S.PAUSE, R.raw.pause);
        load(c, S.STEP, R.raw.step);
        load(c, S.NEXT, R.raw.next_step);
        load(c, S.FOCUS, R.raw.focus_start);
        load(c, S.PICKUP, R.raw.pickup);
        load(c, S.DROP, R.raw.drop);
        load(c, S.HOVER, R.raw.hover);
        load(c, S.TIMER, R.raw.timer);
    }

    private void load(Context c, S s, int res) {
        try { ids.put(s, pool.load(c, res, 1)); } catch (Exception ignored) {}
    }

    public static void init(Context c) {
        if (I == null) I = new Sfx(c.getApplicationContext());
    }

    public static void setEnabled(boolean e) { enabled = e; }

    public static void click() { play(S.TAP); }

    /** Toggle: suono diverso per acceso e spento. */
    public static void toggle(boolean on) { play(on ? S.TOGGLE_ON : S.TOGGLE_OFF); }

    public static void play(S s) {
        if (!enabled || I == null) return;
        Integer id = I.ids.get(s);
        if (id != null && id != 0) {
            // l'avvio un filo più presente, il resto discreto
            float vol = s == S.STARTUP ? 0.7f : 0.45f;
            try { I.pool.play(id, vol, vol, 1, 0, 1f); } catch (Exception ignored) {}
        }
    }
}
