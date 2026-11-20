package com.mg4.launcher.ui;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import com.mg4.launcher.svc.Diag;

/** Mini server locale (solo 127.0.0.1) che serve il file mappa .pmtiles con supporto Range, per MapLibre/pmtiles. */
final class TileServer {
    private final File file;
    private ServerSocket ss;
    private volatile boolean run;

    TileServer(File f) { this.file = f; }

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
            try { Socket s = ss.accept(); handle(s); }
            catch (Exception e) { if (run) Diag.put("map.server", "errore: " + e.getClass().getSimpleName()); }
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            InputStream is = sock.getInputStream();
            StringBuilder head = new StringBuilder();
            int b, prevN = 0;
            // leggi fino a \r\n\r\n
            while ((b = is.read()) != -1) {
                head.append((char) b);
                if (b == '\n') { if (prevN == 1) break; prevN = 1; }
                else if (b != '\r') prevN = 0;
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
            long len = file.length();
            long start = 0, end = len - 1;
            boolean partial = false;
            int r = indexOfIgnoreCase(req, "range:");
            if (r >= 0) {
                String line = req.substring(r + 6);
                int nl = line.indexOf('\n'); if (nl >= 0) line = line.substring(0, nl);
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
        } catch (Exception ignored) {}
    }

    private static int indexOfIgnoreCase(String hay, String needle) {
        return hay.toLowerCase(java.util.Locale.ROOT).indexOf(needle);
    }
}
