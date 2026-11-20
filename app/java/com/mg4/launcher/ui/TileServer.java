package com.mg4.launcher.ui;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import com.mg4.launcher.svc.Diag;

/**
 * Mini server locale (solo 127.0.0.1): serve il file mappa .pmtiles (con Range) e le pagine/asset della mappa
 * (map-vector.html, leaflet, protomaps-leaflet...) dagli assets. Così la pagina e i dati sono sullo stesso
 * "origine" http e il vecchio WebView non blocca il fetch da file:// verso http://.
 */
final class TileServer {
    private final File file;
    private final AssetManager assets;
    private ServerSocket ss;
    private volatile boolean run;

    TileServer(File pmtiles, AssetManager am) { this.file = pmtiles; this.assets = am; }

    int start() throws Exception {
        ss = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        run = true;
        Thread t = new Thread(this::loop, "calm-tiles");
        t.setDaemon(true);
        t.start();
        return ss.getLocalPort();
    }

    void stop() {
        run = false;
        try { if (ss != null) ss.close(); } catch (Exception ignored) {}
    }

    private void loop() {
        while (run) {
            try { Socket s = ss.accept(); new Thread(() -> handle(s), "calm-tiles-c").start(); }
            catch (Exception e) { if (run) Diag.put("map.server", "errore: " + e.getClass().getSimpleName()); }
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            InputStream is = sock.getInputStream();
            StringBuilder head = new StringBuilder();
            int b, nl = 0;
            while ((b = is.read()) != -1) {
                head.append((char) b);
                if (b == '\n') { if (nl == 1) break; nl = 1; } else if (b != '\r') nl = 0;
            }
            String req = head.toString();
            String method = req.startsWith("OPTIONS") ? "OPTIONS" : (req.startsWith("HEAD") ? "HEAD" : "GET");
            OutputStream out = sock.getOutputStream();

            if ("OPTIONS".equals(method)) {
                out.write(("HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\n"
                        + "Access-Control-Allow-Headers: range\r\nAccess-Control-Allow-Methods: GET,HEAD,OPTIONS\r\n\r\n")
                        .getBytes("US-ASCII"));
                out.flush();
                return;
            }

            // percorso richiesto
            int sp1 = req.indexOf(' ');
            int sp2 = sp1 >= 0 ? req.indexOf(' ', sp1 + 1) : -1;
            String path = (sp1 >= 0 && sp2 > sp1) ? req.substring(sp1 + 1, sp2) : "/";
            int qmark = path.indexOf('?'); if (qmark >= 0) path = path.substring(0, qmark);
            if (path.isEmpty() || "/".equals(path)) path = "/map-vector.html";

            if (path.endsWith("/map.pmtiles")) { serveFile(out, method, req); return; }
            serveAsset(out, method, path);
        } catch (Exception ignored) {}
    }

    /** Il file mappa, con supporto Range (per pmtiles). */
    private void serveFile(OutputStream out, String method, String req) throws Exception {
        long len = file.length();
        long start = 0, end = len - 1;
        boolean partial = false;
        int r = req.toLowerCase(java.util.Locale.ROOT).indexOf("range:");
        if (r >= 0) {
            String line = req.substring(r + 6);
            int e = line.indexOf('\n'); if (e >= 0) line = line.substring(0, e);
            line = line.trim();
            int eq = line.indexOf('='); if (eq >= 0) line = line.substring(eq + 1);
            String[] p = line.split("-", 2);
            try { start = Long.parseLong(p[0].trim()); partial = true; } catch (Exception ignored) {}
            if (p.length > 1 && !p[1].trim().isEmpty()) { try { end = Long.parseLong(p[1].trim()); } catch (Exception ignored) {} }
        }
        if (end >= len) end = len - 1;
        if (start < 0 || start > end) { start = 0; end = len - 1; partial = false; }
        long count = end - start + 1;
        StringBuilder h = new StringBuilder();
        h.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: application/octet-stream\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        if (partial) h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(len).append("\r\n");
        h.append("Content-Length: ").append(count).append("\r\n\r\n");
        out.write(h.toString().getBytes("US-ASCII"));
        if (!"HEAD".equals(method)) {
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                raf.seek(start);
                byte[] buf = new byte[65536];
                long left = count;
                while (left > 0) {
                    int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    left -= n;
                }
            } finally { raf.close(); }
        }
        out.flush();
    }

    /** Una pagina o un asset dalla cartella assets/map. */
    private void serveAsset(OutputStream out, String method, String path) throws Exception {
        String assetPath = "map" + (path.startsWith("/") ? path : "/" + path);
        byte[] data = null;
        try {
            InputStream in = assets.open(assetPath);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            in.close();
            data = bos.toByteArray();
        } catch (Exception notFound) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nAccess-Control-Allow-Origin: *\r\n\r\n".getBytes("US-ASCII"));
            out.flush();
            return;
        }
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: ").append(contentType(path)).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Content-Length: ").append(data.length).append("\r\n\r\n");
        out.write(h.toString().getBytes("US-ASCII"));
        if (!"HEAD".equals(method)) out.write(data);
        out.flush();
    }

    private static String contentType(String path) {
        String p = path.toLowerCase(java.util.Locale.ROOT);
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".pbf")) return "application/x-protobuf";
        return "application/octet-stream";
    }
}
