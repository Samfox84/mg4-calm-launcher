package com.mg4.launcher.svc;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import com.mg4.launcher.R;

import java.util.EnumMap;
import java.util.Map;

/** Calm Launcher: suoni soft (pacchetto Pacely) su tutti i tasti. Canale "sonification": niente focus audio, la musica continua. */
public final class Sfx {
    public enum S { TAP, TOGGLE_ON, OPEN, DONE }

    private static Sfx I;
    private static boolean enabled;

    private final SoundPool pool;
    private final Map<S, Integer> ids = new EnumMap<>(S.class);

    private Sfx(Context c) {
        pool = new SoundPool.Builder().setMaxStreams(4)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .build();
        load(c, S.TAP, R.raw.tab);
        load(c, S.TOGGLE_ON, R.raw.toggle_on);
        load(c, S.OPEN, R.raw.open);
        load(c, S.DONE, R.raw.done);
    }

    private void load(Context c, S s, int res) {
        try { ids.put(s, pool.load(c, res, 1)); } catch (Exception ignored) {}
    }

    public static void init(Context c) {
        if (I == null) I = new Sfx(c.getApplicationContext());
    }

    public static void setEnabled(boolean e) { enabled = e; }

    public static void click() { play(S.TAP); }

    public static void play(S s) {
        if (!enabled || I == null) return;
        Integer id = I.ids.get(s);
        if (id != null && id != 0) {
            try { I.pool.play(id, 0.45f, 0.45f, 1, 0, 1f); } catch (Exception ignored) {}
        }
    }
}
