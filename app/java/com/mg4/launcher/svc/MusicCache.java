package com.mg4.launcher.svc;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;

/**
 * Calm Launcher: cache locale dei brani ascoltati (fino a ~500 MB, scarto i piu' vecchi).
 * Quando un brano e' in cache si riproduce dal file locale (niente streaming, niente dati).
 * La prima volta si riproduce in streaming e intanto si scarica di nascosto per le volte successive.
 */
public final class MusicCache {

    private static final long CAP = 500L * 1024 * 1024;     // 500 MB
    private static final long MAX_SONG = 80L * 1024 * 1024;  // guardia: max 80 MB per brano
    private final File dir;

    public MusicCache(Context c) {
        dir = new File(c.getApplicationContext().getFilesDir(), "musiccache");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    private File fileFor(String id) {
        return new File(dir, id.replaceAll("[^A-Za-z0-9_]", "_"));
    }

    /** File locale se il brano e' in cache (e lo "tocca" per l'LRU), altrimenti null. */
    public File cached(String id) {
        File f = fileFor(id);
        if (f.isFile() && f.length() > 10 * 1024) {   // calm.121: ignora file minuscoli rotti (vecchi 0 KB) → si ri-streamma
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
            return f;
        }
        if (f.isFile() && f.length() <= 10 * 1024) { //noinspection ResultOfMethodCallIgnored
            f.delete(); }   // pulisci i rotti
        return null;
    }

    /** calm.121: true se il brano è in cache offline (per il pallino di stato nella lista), SENZA toccare l'LRU. */
    public boolean isCached(String id) {
        if (id == null) return false;
        File f = fileFor(id);
        return f.isFile() && f.length() > 10 * 1024;   // almeno 10 KB: un file minuscolo è un errore, non un brano
    }

    /** Scarica il brano in cache in background (se non c'e' gia'). */
    public void fetch(final String id, final String url) {
        if (id == null || url == null || cached(id) != null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                File tmp = new File(dir, "." + System.nanoTime() + ".part");
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(12000);
                    c.setReadTimeout(30000);
                    c.setInstanceFollowRedirects(true);
                    int code = c.getResponseCode();
                    String ct = c.getContentType();
                    if (code != 200) { Diag.put("musica.cache.file", "stream HTTP " + code + " ct=" + ct); return; }
                    // calm.121: se il server risponde JSON/testo (errore) invece di audio, NON salvare un file rotto
                    if (ct != null && (ct.contains("json") || ct.contains("xml") || ct.startsWith("text"))) {
                        Diag.put("musica.cache.file", "stream NON audio (ct=" + ct + ") → " + errBody(c)); return;   // calm.125: logga il testo dell'errore del server
                    }
                    InputStream in = c.getInputStream();
                    FileOutputStream out = new FileOutputStream(tmp);
                    byte[] b = new byte[65536];
                    int r;
                    long tot = 0;
                    boolean overflow = false;
                    while ((r = in.read(b)) > 0) {
                        out.write(b, 0, r);
                        tot += r;
                        if (tot > MAX_SONG) { overflow = true; break; }
                    }
                    out.close();
                    in.close();
                    if (overflow || tot < 10 * 1024) { tmp.delete(); if (tot > 0) Diag.put("musica.cache.file", "risposta troppo piccola (" + tot + " byte) → non è un brano"); return; }
                    File dst = fileFor(id);
                    if (tmp.renameTo(dst)) {
                        evict();
                        Diag.put("musica.cache.file", "salvato " + (dst.length() / 1024) + " KB");
                    } else {
                        tmp.delete();
                    }
                } catch (Exception e) {
                    try { tmp.delete(); } catch (Exception ignored) {}
                } finally {
                    if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
                }
            }
        }, "music-cache").start();
    }

    /** calm.122: scarica SUBITO (bloccante, da chiamare in un thread) e restituisce il file locale, o null se fallisce.
     *  Ripiego per quando MediaPlayer non riesce a fare streaming diretto ma la cache (HttpURLConnection) sì. */
    public File fetchBlocking(final String id, final String url) {
        if (id == null || url == null) return null;
        File have = cached(id);
        if (have != null) return have;
        File tmp = new File(dir, "." + System.nanoTime() + ".part");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            String ct = c.getContentType();
            if (code != 200) { Diag.put("musica.cache.file", "ripiego HTTP " + code + " ct=" + ct); return null; }
            if (ct != null && (ct.contains("json") || ct.contains("xml") || ct.startsWith("text"))) {
                Diag.put("musica.cache.file", "ripiego NON audio (ct=" + ct + ") → " + errBody(c)); return null;   // calm.125: testo errore server
            }
            InputStream in = c.getInputStream();
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] b = new byte[65536];
            int r;
            long tot = 0;
            boolean overflow = false;
            while ((r = in.read(b)) > 0) { out.write(b, 0, r); tot += r; if (tot > MAX_SONG) { overflow = true; break; } }
            out.close();
            in.close();
            if (overflow || tot < 10 * 1024) { tmp.delete(); return null; }
            File dst = fileFor(id);
            if (tmp.renameTo(dst)) { evict(); Diag.put("musica.cache.file", "ripiego salvato " + (dst.length() / 1024) + " KB"); return dst; }
            tmp.delete();
        } catch (Exception e) {
            try { tmp.delete(); } catch (Exception ignored) {}
            Diag.put("musica.cache.file", "ripiego errore: " + e.getMessage());
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
        return null;
    }

    /** calm.125: legge i primi ~300 byte della risposta del server (il testo dell'errore Subsonic, es.
     *  &lt;error code="70"…&gt;) così la diagnostica dice ESATTAMENTE perché lo stream fallisce. */
    private static String errBody(HttpURLConnection c) {
        InputStream in = null;
        try {
            try { in = c.getInputStream(); } catch (Exception e) { in = c.getErrorStream(); }
            if (in == null) return "(nessun corpo)";
            byte[] b = new byte[300];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, "UTF-8").replaceAll("\\s+", " ").trim() : "(vuoto)";
        } catch (Exception e) {
            return "(corpo illeggibile: " + e.getClass().getSimpleName() + ")";
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    /** Se si supera il tetto, cancella i file meno usati di recente (LRU). */
    private synchronized void evict() {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        long tot = 0;
        for (File f : fs) tot += f.length();
        if (tot <= CAP) return;
        Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));   // piu' vecchi prima
        for (File f : fs) {
            if (tot <= CAP) break;
            long sz = f.length();
            if (f.getName().endsWith(".part")) { f.delete(); continue; }
            if (f.delete()) tot -= sz;
        }
    }
}
