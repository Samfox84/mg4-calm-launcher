package com.mg4.launcher.svc;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * Calm Launcher: lettore diretto per i brani del server Subsonic/Navidrome.
 *
 * Trasmette direttamente dall'URL del server (nessuna dipendenza da Ultrasonic). Pubblica una MediaSession cosi' che
 * l'auto mostri titolo/artista e i tasti al volante (play/pausa/avanti/indietro) funzionino.
 */
public final class MusicPlayer {

    public interface Listener {
        void onChanged(Subsonic.Song current, boolean playing);
    }

    private final Context app;
    private final Subsonic server;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AudioManager am;
    private final MusicCache cache;

    private MediaPlayer mp;
    private MediaSession session;
    private final List<Subsonic.Song> queue = new ArrayList<>();
    private int index = -1;
    private int errorStreak = 0;   // errori di riproduzione consecutivi (azzerato al primo brano che parte)
    private boolean playing, prepared;
    private Listener listener;

    private final AudioManager.OnAudioFocusChangeListener focus = new AudioManager.OnAudioFocusChangeListener() {
        @Override public void onAudioFocusChange(int change) {
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause();
            else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) { if (mp != null) mp.setVolume(0.3f, 0.3f); }
            else if (change == AudioManager.AUDIOFOCUS_GAIN) { if (mp != null) mp.setVolume(1f, 1f); }
        }
    };

    public MusicPlayer(Context c, Subsonic server) {
        this.app = c.getApplicationContext();
        this.server = server;
        this.am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        this.cache = new MusicCache(app);
    }

    public void setListener(Listener l) { this.listener = l; }

    public Subsonic.Song current() { return (index >= 0 && index < queue.size()) ? queue.get(index) : null; }
    public boolean isPlaying() { return playing; }

    private void ensureSession() {
        if (session != null) return;
        try {
            session = new MediaSession(app, "CalmMusic");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { resume(); }
                @Override public void onPause() { pause(); }
                @Override public void onSkipToNext() { next(); }
                @Override public void onSkipToPrevious() { prev(); }
                @Override public void onStop() { stop(); }
            });
            session.setActive(true);
        } catch (Exception e) { Diag.put("musica.player", "sessione: " + e.getMessage()); }
    }

    /** Imposta la coda e parte dal brano scelto. */
    public void play(List<Subsonic.Song> list, int startAt) {
        queue.clear();
        queue.addAll(list);
        index = Math.max(0, Math.min(startAt, queue.size() - 1));
        startCurrent();
    }

    private void startCurrent() {
        Subsonic.Song s = current();
        if (s == null) return;
        ensureSession();
        if (am != null) {
            try {
                am.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            } catch (Exception ignored) {}
        }
        releasePlayer();
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            prepared = false;
            java.io.File local = cache.cached(s.id);
            String streamUrl = server.streamUrl(s.id);
            if (local != null) {
                mp.setDataSource(local.getAbsolutePath());
                Diag.put("musica.player", "da cache: " + s.title);
            } else {
                mp.setDataSource(streamUrl);
                cache.fetch(s.id, streamUrl);   // scarica in background per la prossima volta
            }
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer m) { prepared = true; errorStreak = 0; m.start(); setPlaying(true); }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) { ui.post(() -> next()); }   // fuori dal callback: evita il reset del player dentro il suo stesso onCompletion
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) {
                    Diag.put("musica.player", "errore riproduzione what=" + what + " extra=" + extra);
                    // Un brano che non parte non deve fermare l'album: salta al successivo, ma con un tetto per non
                    // ciclare all'infinito se la rete e' giu' (si riprende al primo brano che parte).
                    if (!queue.isEmpty() && ++errorStreak <= Math.min(queue.size(), 5)) {
                        ui.postDelayed(() -> next(), 1200);
                    } else {
                        Diag.put("musica.player", "troppi errori di fila (" + errorStreak + "): fermo");
                    }
                    return true;
                }
            });
            mp.prepareAsync();
            pushMetadata(s);
            Diag.put("musica.player", "avvio: " + s.title + " - " + s.artist);
        } catch (Exception e) {
            Diag.put("musica.player", "setDataSource fallito: " + e.getMessage());
        }
    }

    public void resume() {
        if (mp != null && prepared) { mp.start(); setPlaying(true); }
        else if (current() != null) startCurrent();
    }

    public void pause() {
        if (mp != null && prepared && mp.isPlaying()) { mp.pause(); setPlaying(false); }
    }

    public void toggle() { if (playing) pause(); else resume(); }

    public void next() {
        if (queue.isEmpty()) return;
        index = (index + 1) % queue.size();
        Diag.put("musica.player", "avanti: brano " + (index + 1) + "/" + queue.size());
        startCurrent();
    }

    public void prev() {
        if (queue.isEmpty()) return;
        // se oltre 3s, ricomincia il brano
        if (mp != null && prepared && mp.getCurrentPosition() > 3000) { mp.seekTo(0); return; }
        index = (index - 1 + queue.size()) % queue.size();
        startCurrent();
    }

    public void stop() {
        releasePlayer();
        setPlaying(false);
        if (am != null) try { am.abandonAudioFocus(focus); } catch (Exception ignored) {}
        if (session != null) { try { session.setActive(false); session.release(); } catch (Exception ignored) {} session = null; }
    }

    private void releasePlayer() {
        if (mp != null) {
            try { mp.reset(); mp.release(); } catch (Exception ignored) {}
            mp = null;
        }
        prepared = false;
    }

    private void setPlaying(boolean p) {
        playing = p;
        pushState();
        final Subsonic.Song s = current();
        ui.post(new Runnable() { @Override public void run() { if (listener != null) listener.onChanged(s, playing); } });
    }

    private void pushMetadata(Subsonic.Song s) {
        if (session == null) return;
        try {
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, s.duration * 1000L).build());
        } catch (Exception ignored) {}
    }

    private void pushState() {
        if (session == null) return;
        try {
            long pos = (mp != null && prepared) ? mp.getCurrentPosition() : 0;
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                            | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, 1f)
                    .build());
        } catch (Exception ignored) {}
    }
}
