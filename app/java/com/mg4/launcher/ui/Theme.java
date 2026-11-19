package com.mg4.launcher.ui;

import android.content.Context;

/** Colori del launcher (stessi valori del mockup approvato). */
public final class Theme {
    public final boolean day;
    public final boolean calm;
    public final int bg, panel, text, sub, track, chip, battery, accent, onAccent, overlay, map, road, roadMinor, glow, shadow;

    private Theme(boolean day, boolean calm) {
        this.day = day;
        this.calm = calm;
        if (calm) {
            // Calm Dark (AuDHD): ardesia profonda e desaturata, bianco sporco morbido, poco bagliore
            bg = 0xFF121619; panel = 0xFF1B2024; text = 0xFFD9DEE2; sub = 0xFF8A9298; track = 0xFF2A3036;
            chip = 0xFF222A30; battery = 0xFF5FA87C; overlay = 0xF01B2024; map = 0xFF1A2024; road = 0xFF2C333A;
            roadMinor = 0xFF232A30; glow = 0xFF263038; shadow = 0x66000000;
        } else if (day) {
            bg = 0xFFE6E8EB; panel = 0xFFFFFFFF; text = 0xFF111318; sub = 0xFF555C66; track = 0xFFE1E4E8;
            chip = 0xFFF1F3F5; battery = 0xFF1E9E5A; overlay = 0xEBFFFFFF; map = 0xFFEEF0F2; road = 0xFFFFFFFF;
            roadMinor = 0xFFE2E5E9; glow = 0xFFC9D3E0; shadow = 0x1A000000;
        } else {
            bg = 0xFF0A0B0D; panel = 0xFF15171B; text = 0xFFF2F3F5; sub = 0xFF9BA1A9; track = 0xFF262A30;
            chip = 0xFF1E2126; battery = 0xFF34C77B; overlay = 0xEB15171B; map = 0xFF1A1D22; road = 0xFF2A2E35;
            roadMinor = 0xFF22252B; glow = 0xFF1F2A3A; shadow = 0x8C000000;
        }
        // nessun colore d'accento: pulsanti e icone usano lo stesso colore del testo
        accent = text;
        onAccent = luminance(accent) > 0.6 ? 0xFF111318 : 0xFFFFFFFF;
    }

    /** 1.0.0: ultimo tema applicato e quando è cambiato. */
    private static Boolean lastDay;
    private static String lastChange = "—";

    /**
     * Tema da usare. La scelta manuale Giorno/Notte ha sempre la precedenza; in automatico decide DayNight (1.0.2:
     * sistema se affidabile, poi fari, poi alba/tramonto, con 60 s di conferma prima di ogni cambio).
     */
    public static Theme resolve(Context c, Prefs p) {
        boolean day = wouldBeDay(c, p);
        boolean calm = p.isCalm();
        String mode = p.themeMode();
        if (lastDay == null || lastDay != day) {
            lastChange = new java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.ITALY).format(new java.util.Date());
            lastDay = day;
        }
        com.mg4.launcher.svc.Diag.put("tema.scelta", (Prefs.THEME_DAY.equals(mode) ? "scelta Giorno" : Prefs.THEME_NIGHT.equals(mode)
                ? "scelta Notte" : "automatico") + " · applicato " + (day ? "giorno" : "notte") + " · ricostruito " + lastChange);
        return new Theme(day, calm);
    }

    /** Tema che si applicherebbe adesso (per ricontrollare al ritorno nel launcher). */
    public static boolean wouldBeDay(Context c, Prefs p) {
        String mode = p.themeMode();
        if (Prefs.THEME_DAY.equals(mode)) return true;
        if (Prefs.THEME_NIGHT.equals(mode) || Prefs.THEME_CALM_NIGHT.equals(mode)) return false;
        return com.mg4.launcher.svc.DayNight.get(c).isDay();
    }

    private static double luminance(int c) {
        return (0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF)) / 255.0;
    }
}
