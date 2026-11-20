package com.mg4.launcher.svc;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Calm Launcher: logica pura (niente Android) del client Subsonic, cosi' si puo' testare su una JVM normale.
 * Normalizzazione URL, token di autenticazione (md5) e confronto per la ricerca.
 */
public final class SubsonicParse {
    private SubsonicParse() {}

    /** Ripulisce l'indirizzo del server: aggiunge http://, toglie lo "/" finale e un eventuale "/rest". */
    public static String normBase(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.isEmpty()) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/rest")) u = u.substring(0, u.length() - 5);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    public static String md5(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            byte[] h = d.digest((s == null ? "" : s).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    /** Token Subsonic: md5(password + salt). */
    public static String authToken(String pass, String salt) {
        return md5((pass == null ? "" : pass) + (salt == null ? "" : salt));
    }

    /** Vero se titolo/artista/album contengono la query (minuscole). */
    public static boolean matches(String title, String artist, String album, String q) {
        if (q == null || q.isEmpty()) return false;
        String lq = q.toLowerCase(Locale.ROOT);
        return low(title).contains(lq) || low(artist).contains(lq) || low(album).contains(lq);
    }

    private static String low(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
