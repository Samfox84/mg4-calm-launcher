package com.mg4.launcher.svc;

import android.content.Context;
import android.media.AudioManager;

/**
 * Calm Launcher: abilita il microfono di DiPlay sulla MG4.
 *
 * Il CarPlay/projection originale della MG (com.allgo.audiorecorder.AapAudioRecorder nel firmware) apre il
 * microfono SOLO dopo aver impostato un parametro audio del costruttore, "SSE_AA_VR_Wifi=true" per una sessione
 * wireless. Senza quel parametro la chip audio non instrada il microfono verso l'app che registra, quindi DiPlay
 * (wireless) registra silenzio e Siri/i messaggi non sentono la voce — mentre il CarPlay cablato originale, che
 * imposta il parametro, funziona.
 *
 * Il launcher è un'app di sistema (android.uid.system) con i permessi audio, e il parametro è globale alla chip:
 * impostandolo il launcher instrada il microfono per la cattura che fa DiPlay, senza toccare DiPlay stessa.
 * Una volta impostato resta attivo, quindi basta riasserirlo quando DiPlay è in uso.
 */
public final class DiPlayMic {

    private static final String PARAM = "SSE_AA_VR_Wifi=true";
    private static volatile boolean logged;

    private DiPlayMic() {}

    /** Imposta il parametro VR del microfono (best-effort). Da chiamare quando DiPlay è (o sta per essere) attiva. */
    public static void enable(Context c) {
        if (c == null) return;
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            am.setParameters(PARAM);
            if (!logged) { logged = true; Diag.put("diplay.mic", "instradamento microfono VR attivato (" + PARAM + ")"); }
        } catch (Throwable t) {
            Diag.error("diplay.mic", t.toString());
        }
    }
}
