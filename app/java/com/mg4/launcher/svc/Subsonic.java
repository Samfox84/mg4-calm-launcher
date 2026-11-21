package com.mg4.launcher.svc;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Calm Launcher: client diretto Navidrome/Subsonic.
 *
 * Scarica UNA volta l'intero catalogo (tutti gli album -> tutti i brani) via API REST, lo salva su disco come JSON e
 * poi cerca in locale (istantaneo, offline). Solo lo streaming e le copertine usano la rete al momento del bisogno.
 * Aggira il limite dell'albero MediaBrowser che Ultrasonic espone ad Android Auto.
 */
public final class Subsonic {

    /** Un brano del catalogo. */
    public static final class Song {
        public final String id, title, artist, album, albumId, coverArt, suffix;
        public final int duration, track, year;
        public Song(String id, String title, String artist, String album, String albumId,
                    String coverArt, String suffix, int duration, int track, int year) {
            this.id = id; this.title = title; this.artist = artist; this.album = album;
            this.albumId = albumId; this.coverArt = coverArt; this.suffix = suffix;
            this.duration = duration; this.track = track; this.year = year;
        }
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id); o.put("t", title); o.put("ar", artist); o.put("al", album);
                o.put("alId", albumId); o.put("c", coverArt); o.put("sx", suffix);
                o.put("d", duration); o.put("tr", track); o.put("y", year);
            } catch (Exception ignored) {}
            return o;
        }
        static Song fromJson(JSONObject o) {
            return new Song(o.optString("id"), o.optString("t"), o.optString("ar"), o.optString("al"),
                    o.optString("alId"), o.optString("c"), o.optString("sx"),
                    o.optInt("d"), o.optInt("tr"), o.optInt("y"));
        }
    }

    /** Un album (derivato dai brani in cache). */
    public static final class Album {
        public final String id, name, artist, coverArt;
        public int count;
        public Album(String id, String name, String artist, String coverArt) {
            this.id = id; this.name = name; this.artist = artist; this.coverArt = coverArt;
        }
    }

    /** Una playlist del server. */
    public static final class Playlist {
        public final String id, name; public final int count;
        public Playlist(String id, String name, int count) { this.id = id; this.name = name; this.count = count; }
    }

    public interface SyncCb {
        void progress(int albumsDone, int albumsTotal, int songs);
        void done(int songs, String error);
    }

    private static final String CLIENT = "CalmLauncher", API = "1.16.1";
    private final Context app;
    private final String base, user, pass;
    private final List<Song> catalogue = new ArrayList<>();
    private volatile boolean loaded;

    public Subsonic(Context c, String url, String user, String pass) {
        this.app = c.getApplicationContext();
        this.base = normBase(url);
        this.user = user == null ? "" : user.trim();
        this.pass = pass == null ? "" : pass;
    }

    private static String normBase(String url) { return SubsonicParse.normBase(url); }

    public boolean configured() { return !base.isEmpty() && !user.isEmpty(); }

    // ---- autenticazione (salt + token md5) ----
    private String auth() {
        String salt = Long.toHexString(Double.doubleToLongBits(Math.random())) + Long.toHexString(System.nanoTime());
        String token = SubsonicParse.authToken(pass, salt);
        return "u=" + Uri.encode(user) + "&t=" + token + "&s=" + salt
                + "&v=" + API + "&c=" + CLIENT + "&f=json";
    }

    private String endpoint(String method, String extra) {
        return base + "/rest/" + method + ".view?" + auth() + (extra == null || extra.isEmpty() ? "" : "&" + extra);
    }

    /** URL diretto per lo streaming di un brano (usato dal player). */
    public String streamUrl(String id) {
        return endpoint("stream", "id=" + Uri.encode(id) + "&maxBitRate=0");
    }

    /** URL della copertina. */
    public String coverUrl(String coverArt, int size) {
        if (coverArt == null || coverArt.isEmpty()) return null;
        return endpoint("getCoverArt", "id=" + Uri.encode(coverArt) + "&size=" + size);
    }

    // ---- HTTP GET -> JSON "subsonic-response" ----
    private JSONObject get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String ln;
            while ((ln = r.readLine()) != null) sb.append(ln);
            r.close();
            JSONObject root = new JSONObject(sb.toString()).optJSONObject("subsonic-response");
            if (root == null) throw new Exception("risposta non valida");
            if (!"ok".equals(root.optString("status"))) {
                JSONObject err = root.optJSONObject("error");
                throw new Exception(err != null ? err.optString("message", "errore server") : "errore server");
            }
            return root;
        } finally {
            c.disconnect();
        }
    }

    /** Prova la connessione. Restituisce null se ok, altrimenti il messaggio d'errore. */
    public String ping() {
        try { get(endpoint("ping", null)); return null; }
        catch (Exception e) { return e.getMessage() == null ? "connessione fallita" : e.getMessage(); }
    }

    // ---- cache su disco ----
    private File cacheFile() { return new File(app.getFilesDir(), "music-catalogue.json"); }

    public synchronized boolean loadCache() {
        if (loaded && !catalogue.isEmpty()) return true;
        File f = cacheFile();
        if (!f.exists()) return false;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String ln;
            while ((ln = r.readLine()) != null) sb.append(ln);
            r.close();
            JSONArray a = new JSONArray(sb.toString());
            catalogue.clear();
            for (int i = 0; i < a.length(); i++) catalogue.add(Song.fromJson(a.getJSONObject(i)));
            loaded = true; albumCache = null;
            Diag.put("musica.cache", "catalogo caricato: " + catalogue.size() + " brani");
            return !catalogue.isEmpty();
        } catch (Exception e) {
            Diag.put("musica.cache", "lettura fallita: " + e.getMessage());
            return false;
        }
    }

    private void saveCache() {
        try {
            JSONArray a = new JSONArray();
            synchronized (this) { for (Song s : catalogue) a.put(s.toJson()); }
            FileOutputStream o = new FileOutputStream(cacheFile());
            o.write(a.toString().getBytes("UTF-8"));
            o.close();
        } catch (Exception e) {
            Diag.put("musica.cache", "salvataggio fallito: " + e.getMessage());
        }
    }

    public synchronized int size() { return catalogue.size(); }

    public synchronized List<Song> snapshot() { return new ArrayList<>(catalogue); }

    /** Cerca nel catalogo in cache (titolo, artista, album). */
    public synchronized List<Song> search(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        List<Song> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        for (Song s : catalogue) {
            if (SubsonicParse.matches(s.title, s.artist, s.album, q)) {
                out.add(s);
                if (out.size() >= 2000) break;
            }
        }
        return out;
    }

    // ---- sincronizzazione completa (tutti gli album -> tutti i brani) ----
    private volatile boolean syncing;

    public boolean isSyncing() { return syncing; }

    /** Scarica l'intero catalogo in background. I callback arrivano su un thread di lavoro. */
    public void syncAll(final SyncCb cb) {
        if (syncing) return;
        syncing = true;
        new Thread(new Runnable() { @Override public void run() { doSync(cb); } }, "subsonic-sync").start();
    }

    private void doSync(SyncCb cb) {
        try {
            String err = ping();
            if (err != null) { syncing = false; cb.done(0, err); return; }

            // 1) enumera tutti gli album (pagine da 500)
            List<String> albumIds = new ArrayList<>();
            int offset = 0;
            while (true) {
                JSONObject root = get(endpoint("getAlbumList2", "type=alphabeticalByName&size=500&offset=" + offset));
                JSONObject list = root.optJSONObject("albumList2");
                JSONArray albums = list == null ? null : list.optJSONArray("album");
                int n = albums == null ? 0 : albums.length();
                for (int i = 0; i < n; i++) {
                    String id = albums.getJSONObject(i).optString("id", null);
                    if (id != null) albumIds.add(id);
                }
                if (n < 500) break;
                offset += 500;
                if (offset > 100000) break; // guardia
            }
            Diag.put("musica.sync", "album trovati: " + albumIds.size());

            // 2) per ogni album, prendi i brani (pool limitato)
            final List<Song> collected = java.util.Collections.synchronizedList(new ArrayList<Song>());
            final AtomicInteger done = new AtomicInteger(0);
            final int total = albumIds.size();
            ExecutorService pool = Executors.newFixedThreadPool(4);
            final int[] lastReport = { -1 };
            for (final String albumId : albumIds) {
                pool.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            JSONObject root = get(endpoint("getAlbum", "id=" + Uri.encode(albumId)));
                            JSONObject al = root.optJSONObject("album");
                            JSONArray songs = al == null ? null : al.optJSONArray("song");
                            if (songs != null) {
                                for (int i = 0; i < songs.length(); i++) {
                                    JSONObject s = songs.getJSONObject(i);
                                    String id = s.optString("id", null);
                                    if (id == null) continue;
                                    collected.add(new Song(id,
                                            s.optString("title", "?"),
                                            s.optString("artist", ""),
                                            s.optString("album", ""),
                                            s.optString("albumId", albumId),
                                            s.optString("coverArt", ""),
                                            s.optString("suffix", ""),
                                            s.optInt("duration", 0),
                                            s.optInt("track", 0),
                                            s.optInt("year", 0)));
                                }
                            }
                        } catch (Exception ignored) {
                        } finally {
                            int d = done.incrementAndGet();
                            if (d - lastReport[0] >= 10 || d == total) {
                                lastReport[0] = d;
                                cb.progress(d, total, collected.size());
                            }
                        }
                    }
                });
            }
            pool.shutdown();
            pool.awaitTermination(20, java.util.concurrent.TimeUnit.MINUTES);

            // 3) salva e pubblica
            synchronized (this) {
                catalogue.clear();
                catalogue.addAll(collected);
                loaded = true; albumCache = null;
            }
            saveCache();
            Diag.put("musica.sync", "completato: " + collected.size() + " brani da " + total + " album");
            syncing = false;
            cb.done(collected.size(), null);
        } catch (Exception e) {
            syncing = false;
            Diag.put("musica.sync", "errore: " + e.getMessage());
            cb.done(0, e.getMessage() == null ? "errore" : e.getMessage());
        }
    }

    // ---- album derivati dal catalogo (nessuna rete) ----
    private List<Album> albumCache;
    public synchronized List<Album> albums() {
        if (albumCache != null) return albumCache;
        java.util.LinkedHashMap<String, Album> map = new java.util.LinkedHashMap<>();
        for (Song s : catalogue) {
            String key = (s.albumId == null || s.albumId.isEmpty()) ? (s.album + "\u0001" + s.artist) : s.albumId;
            Album a = map.get(key);
            if (a == null) { a = new Album(s.albumId, s.album.isEmpty() ? "?" : s.album, s.artist, s.coverArt); map.put(key, a); }
            a.count++;
        }
        List<Album> out = new ArrayList<>(map.values());
        java.util.Collections.sort(out, new java.util.Comparator<Album>() {
            public int compare(Album x, Album y) { return x.name.compareToIgnoreCase(y.name); }
        });
        albumCache = out;
        return out;
    }

    public synchronized List<Song> songsOfAlbum(String albumId, String albumName) {
        List<Song> out = new ArrayList<>();
        for (Song s : catalogue) {
            boolean hit = (albumId != null && !albumId.isEmpty()) ? albumId.equals(s.albumId) : (albumName != null && albumName.equals(s.album));
            if (hit) out.add(s);
        }
        java.util.Collections.sort(out, new java.util.Comparator<Song>() {
            public int compare(Song x, Song y) { return Integer.compare(x.track, y.track); }
        });
        return out;
    }

    /** Album che corrispondono alla query (per metterli in cima alla ricerca). */
    public synchronized List<Album> searchAlbums(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        List<Album> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        for (Album a : albums()) {
            if (a.name.toLowerCase(Locale.ROOT).contains(q) || a.artist.toLowerCase(Locale.ROOT).contains(q)) {
                out.add(a);
                if (out.size() >= 60) break;
            }
        }
        return out;
    }

    // ---- playlist dal server (rete, poche) ----
    public List<Playlist> playlists() {
        List<Playlist> out = new ArrayList<>();
        try {
            JSONObject root = get(endpoint("getPlaylists", null));
            JSONObject pl = root.optJSONObject("playlists");
            JSONArray arr = pl == null ? null : pl.optJSONArray("playlist");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String id = o.optString("id", null);
                if (id != null) out.add(new Playlist(id, o.optString("name", "?"), o.optInt("songCount", 0)));
            }
        } catch (Exception e) { Diag.put("musica.playlist", "errore: " + e.getMessage()); }
        return out;
    }

    public List<Song> playlistSongs(String id) {
        List<Song> out = new ArrayList<>();
        try {
            JSONObject root = get(endpoint("getPlaylist", "id=" + Uri.encode(id)));
            JSONObject pl = root.optJSONObject("playlist");
            JSONArray arr = pl == null ? null : pl.optJSONArray("entry");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String sid = o.optString("id", null);
                if (sid == null) continue;
                out.add(new Song(sid, o.optString("title", "?"), o.optString("artist", ""), o.optString("album", ""),
                        o.optString("albumId", ""), o.optString("coverArt", ""), o.optString("suffix", ""),
                        o.optInt("duration", 0), o.optInt("track", 0), o.optInt("year", 0)));
            }
        } catch (Exception e) { Diag.put("musica.playlist", "brani errore: " + e.getMessage()); }
        return out;
    }

    // ---- ascoltati di recente (id persistiti) ----
    private File recentFile() { return new File(app.getFilesDir(), "music-recent.json"); }

    public synchronized void notePlayed(String id) {
        if (id == null || id.isEmpty()) return;
        try {
            List<String> ids = recentIds();
            ids.remove(id);
            ids.add(0, id);
            while (ids.size() > 40) ids.remove(ids.size() - 1);
            JSONArray a = new JSONArray();
            for (String s : ids) a.put(s);
            FileOutputStream o = new FileOutputStream(recentFile());
            o.write(a.toString().getBytes("UTF-8"));
            o.close();
        } catch (Exception ignored) {}
    }

    private List<String> recentIds() {
        List<String> ids = new ArrayList<>();
        File f = recentFile();
        if (!f.exists()) return ids;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder(); String ln;
            while ((ln = r.readLine()) != null) sb.append(ln);
            r.close();
            JSONArray a = new JSONArray(sb.toString());
            for (int i = 0; i < a.length(); i++) ids.add(a.optString(i));
        } catch (Exception ignored) {}
        return ids;
    }

    public synchronized List<Song> recent(int max) {
        java.util.HashMap<String, Song> byId = new java.util.HashMap<>();
        for (Song s : catalogue) byId.put(s.id, s);
        List<Song> out = new ArrayList<>();
        for (String id : recentIds()) {
            Song s = byId.get(id);
            if (s != null) { out.add(s); if (out.size() >= max) break; }
        }
        return out;
    }

}
