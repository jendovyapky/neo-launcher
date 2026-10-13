package com.neolauncher.cast;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Maly HTTP server bez knihoven:
 *   /           stranka s prehravacem
 *   /jmuxer.js  prehravac H.264 (MSE) z assets
 *   /quba.js    maskot
 *   /video      nekonecny stream snimku H.264 (Annex-B), hlavicka viz writePacket
 *   /audio      nekonecny stream zvuku: PCM s16le, 48 kHz, stereo, po 20 ms, stejna hlavicka
 *   /stats      JSON se stavem
 *   /set?side=&fps=&mbps=   zmena kvality
 */
class HttpServer extends Thread {

    private final int port;
    private final byte[] jmuxer, page, quba;
    private ServerSocket socket;

    HttpServer(Context ctx, int port) throws IOException {
        super("http");
        this.port = port;
        setDaemon(true);
        jmuxer = asset(ctx, "cast/jmuxer.min.js");
        page = asset(ctx, "cast/index.html");
        quba = asset(ctx, "cast/quba.js");
    }

    private static byte[] asset(Context ctx, String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) bos.write(b, 0, n);
            return bos.toByteArray();
        }
    }

    void stopServer() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    @Override
    public void run() {
        try {
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            while (!socket.isClosed()) {
                Socket c = socket.accept();
                Thread t = new Thread(() -> handle(c), "client");
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            if (CaptureService.running) {
                Log.e(CaptureService.TAG, "server", e);
                CaptureService.lastError = "HTTP server: " + e.getMessage();
            }
        }
    }

    private void handle(Socket c) {
        try (Socket s = c) {
            s.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            String line = in.readLine();
            if (line == null) return;
            String[] parts = line.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            while ((line = in.readLine()) != null && !line.isEmpty()) { /* hlavicky nepotrebujeme */ }

            OutputStream out = s.getOutputStream();
            if (path.equals("/") || path.startsWith("/?")) {
                send(out, "200 OK", "text/html; charset=utf-8", page);
            } else if (path.startsWith("/quba.js")) {
                send(out, "200 OK", "text/javascript; charset=utf-8", quba);
            } else if (path.startsWith("/jmuxer.js")) {
                send(out, "200 OK", "text/javascript; charset=utf-8", jmuxer);
            } else if (path.startsWith("/video")) {
                video(s, out);
            } else if (path.startsWith("/audio")) {
                audio(s, out);
            } else if (path.startsWith("/stats")) {
                send(out, "200 OK", "application/json", stats().getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/set")) {
                applySettings(path);
                send(out, "200 OK", "application/json", stats().getBytes(StandardCharsets.UTF_8));
            } else {
                send(out, "404 Not Found", "text/plain", "404".getBytes(StandardCharsets.US_ASCII));
            }
        } catch (Throwable ignored) {
            // klient zavrel spojeni
        }
    }

    private static void send(OutputStream out, String status, String type, byte[] body) throws IOException {
        String h = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static void streamHeaders(OutputStream out) throws IOException {
        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** Hlavicka 16 B: [delka 4][trvani ms 2][priznaky 1 (bit0 = klicovy)][typ 1 (1 obraz, 2 zvuk)][pts μs 8] */
    static void writePacket(OutputStream out, byte[] header, int type, byte[] data, int durationMs, boolean key, long ptsUs)
            throws IOException {
        int len = data.length;
        header[0] = (byte) (len >>> 24); header[1] = (byte) (len >>> 16); header[2] = (byte) (len >>> 8); header[3] = (byte) len;
        header[4] = (byte) (durationMs >>> 8); header[5] = (byte) durationMs;
        header[6] = (byte) (key ? 1 : 0); header[7] = (byte) type;
        for (int i = 0; i < 8; i++) header[8 + i] = (byte) (ptsUs >>> (56 - 8 * i));
        out.write(header);
        out.write(data);
        out.flush();
    }

    private static void video(Socket s, OutputStream out) throws IOException {
        if (!CaptureService.running) {
            send(out, "503 Service Unavailable", "text/plain", "not running".getBytes(StandardCharsets.US_ASCII));
            return;
        }
        s.setSendBufferSize(256 * 1024);
        streamHeaders(out);
        Hub.Viewer v = Hub.join();
        byte[] header = new byte[16];
        try {
            while (CaptureService.running && !v.closed) {
                Hub.Frame f = v.next();
                if (f == null) continue;
                writePacket(out, header, 1, f.data, f.durationMs, f.key, f.ptsUs);
            }
        } catch (InterruptedException ignored) {
        } finally {
            Hub.leave(v);
        }
    }

    private static void audio(Socket s, OutputStream out) throws IOException {
        String why = AudioCapture.unavailableReason();
        if (!CaptureService.running || why != null) {
            send(out, "503 Service Unavailable", "text/plain; charset=utf-8",
                    (why != null ? why : "not running").getBytes(StandardCharsets.UTF_8));
            return;
        }
        streamHeaders(out);
        AudioCapture.Listener l = AudioCapture.join();
        byte[] header = new byte[16];
        try {
            while (CaptureService.running && !l.closed) {
                AudioCapture.Chunk c = l.next();
                if (c == null) continue;
                writePacket(out, header, 2, c.pcm, AudioCapture.CHUNK_MS, false, c.ptsUs);
            }
        } catch (InterruptedException ignored) {
        } finally {
            AudioCapture.leave(l);
        }
    }

    private static void applySettings(String path) {
        int q = path.indexOf('?');
        if (q < 0) return;
        int side = CaptureService.maxSide, fps = CaptureService.targetFps, mbps = CaptureService.bitrateMbps;
        for (String kv : path.substring(q + 1).split("&")) {
            String[] p = kv.split("=", 2);
            if (p.length != 2) continue;
            try {
                int v = Integer.parseInt(p[1]);
                switch (p[0]) {
                    case "side": side = clamp(v, 640, 4096); break;
                    case "fps": fps = clamp(v, 15, 90); break;
                    case "mbps": mbps = clamp(v, 2, 60); break;
                }
            } catch (NumberFormatException ignored) {}
        }
        CaptureService svc = CaptureService.instance;
        if (svc != null) svc.applySettings(side, fps, mbps);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** [procenta (-1 = nezname), nabiji 0/1] - z "sticky" broadcastu, cte se max 1x za 5 s. */
    private static final int[] battery = {-1, 0};
    private static long batteryAt;

    private static void readBattery() {
        long now = SystemClock.elapsedRealtime();
        if (now - batteryAt < 5000) return;
        batteryAt = now;
        CaptureService svc = CaptureService.instance;
        if (svc == null) return;
        try {
            android.content.Intent b = svc.registerReceiver(null,
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return;
            int level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
            int status = b.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            battery[0] = level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
            battery[1] = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                    || status == android.os.BatteryManager.BATTERY_STATUS_FULL ? 1 : 0;
        } catch (Exception ignored) {}
    }

    static String stats() {
        readBattery();
        long up = CaptureService.running ? (SystemClock.elapsedRealtime() - CaptureService.startedAt) / 1000 : 0;
        String err = CaptureService.lastError == null ? "null"
                : "\"" + CaptureService.lastError.replace("\\", "\\\\").replace("\"", "'").replace("\n", " ") + "\"";
        return String.format(Locale.US,
                "{\"running\":%b,\"src\":\"%dx%d\",\"out\":\"%dx%d\",\"fps\":%.1f,\"kbps\":%d,"
                        + "\"side\":%d,\"targetFps\":%d,\"mbps\":%d,\"viewers\":%d,\"uptime\":%d,\"error\":%s,"
                        + "\"audio\":%s,\"audioLevel\":%d,\"listeners\":%d,"
                        + "\"battery\":%d,\"charging\":%b}",
                CaptureService.running, CaptureService.srcWidth, CaptureService.srcHeight,
                CaptureService.outWidth, CaptureService.outHeight, CaptureService.outFps, CaptureService.kbps,
                CaptureService.maxSide, CaptureService.targetFps, CaptureService.bitrateMbps,
                Hub.count(), up, err,
                AudioCapture.unavailableReason() == null ? "\"ok\"" : "\"" + AudioCapture.unavailableReason() + "\"",
                AudioCapture.levelDb, AudioCapture.count(), battery[0], battery[1] == 1);
    }
}
