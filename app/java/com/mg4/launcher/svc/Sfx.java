package com.mg4.launcher.svc;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import com.mg4.launcher.R;

import java.util.EnumMap;
import java.util.Map;

/**
 * Calm Launcher: suoni soft (pacchetto Pacely) per il feedback dell'interfaccia.
 * Non chiede il focus audio, quindi la musica continua; usa il canale "sonification" a volume basso.
 */
public final class Sfx {
    public enum S { TAP, TOGGLE_ON, TOGGLE_OFF, OPEN, CLOSE, ERROR, DONE, SWIPE }

    private static Sfx I;
    private final SoundPool pool;
    private final Map<S, Integer> ids = new EnumMap<>(S.class);
    private final float volume = 0.45f;

    private Sfx(Context c) {
        pool = new SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        load(c, S.TAP, R.raw.tab);
        load(c, S.TOGGLE_ON, R.raw.toggle_on);
        load(c, S.TOGGLE_OFF, R.raw.toggle_off);
        load(c, S.OPEN, R.raw.open);
        load(c, S.CLOSE, R.raw.close);
        load(c, S.ERROR, R.raw.gentle_error);
        load(c, S.DONE, R.raw.done);
        load(c, S.SWIPE, R.raw.swipe);
    }

    private void load(Context c, S s, int res) {
        try {
            ids.put(s, pool.load(c, res, 1));
        } catch (Exception ignored) {
        }
    }

    public static Sfx get(Context c) {
        if (I == null) I = new Sfx(c.getApplicationContext());
        return I;
    }

    public void play(S s) {
        Integer id = ids.get(s);
        if (id != null && id != 0) {
            try {
                pool.play(id, volume, volume, 1, 0, 1f);
            } catch (Exception ignored) {
            }
        }
    }
}
