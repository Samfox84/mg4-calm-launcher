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
        if (f.isFile() && f.length() > 0) {
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
            return f;
        }
        return null;
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
                    if (c.getResponseCode() != 200) return;
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
                    if (overflow || tot == 0) { tmp.delete(); return; }
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
