package com.neolauncher.cast;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Ohlasuje Quest na quest.jendovyapky.eu, aby ho prohlizec ze stejne Wi-Fi nasel sam.
 * Server paruje podle verejne IP, a protoze prohlizec muze jit pres IPv4 i IPv6,
 * posilame ohlaseni zvlast pres obe rodiny adres (rucne otevreny TLS socket).
 */
final class Discovery {
    private Discovery() {}

    static final String HOST = "quest.jendovyapky.eu";
    static final String VERSION = "neo";
    private static final long EVERY_MS = 25_000;

    private static Thread thread;
    private static volatile boolean on;
    static volatile String status = "–";

    static synchronized void start(Context ctx) {
        if (on) return;
        on = true;
        Context app = ctx.getApplicationContext();
        thread = new Thread(() -> {
            String id = deviceId(app);
            while (on) {
                String ip = CaptureService.wifiIp();
                if (ip != null) {
                    String body = "{\"id\":\"" + id + "\",\"name\":\"" + json(deviceName()) + "\",\"lan\":\"http://"
                            + ip + ":" + CaptureService.PORT + "\",\"running\":" + CaptureService.running
                            + ",\"v\":\"" + VERSION + "\"}";
                    status = sendBoth("/api/hello", body);
                } else {
                    status = "bez Wi-Fi";
                }
                try { Thread.sleep(EVERY_MS); } catch (InterruptedException e) { break; }
            }
        }, "discovery");
        thread.setDaemon(true);
        thread.start();
    }

    static synchronized void stop(Context ctx) {
        if (!on) return;
        on = false;
        if (thread != null) thread.interrupt();
        String id = deviceId(ctx.getApplicationContext());
        Thread bye = new Thread(() -> sendBoth("/api/bye", "{\"id\":\"" + id + "\"}"), "discovery-bye");
        bye.setDaemon(true);
        bye.start();
        status = "–";
    }

    /** Posle pozadavek pres IPv4 i IPv6 (co jde). Vraci kratky stav pro appku. */
    private static String sendBoth(String path, String body) {
        boolean v4 = false, v6 = false;
        try {
            InetAddress[] all = InetAddress.getAllByName(HOST);
            InetAddress a4 = null, a6 = null;
            for (InetAddress a : all) {
                if (a4 == null && a instanceof Inet4Address) a4 = a;
                if (a6 == null && a instanceof Inet6Address) a6 = a;
            }
            if (a4 != null) v4 = post(a4, path, body);
            if (a6 != null) v6 = post(a6, path, body);
        } catch (Throwable t) {
            Log.w(CaptureService.TAG, "discovery dns", t);
            return "server nedostupný";
        }
        if (v4 && v6) return "ohlášeno (IPv4 + IPv6)";
        if (v4) return "ohlášeno (IPv4)";
        if (v6) return "ohlášeno (IPv6)";
        return "server nedostupný";
    }

    private static boolean post(InetAddress addr, String path, String body) {
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(addr, 443), 5000);
            raw.setSoTimeout(6000);
            SSLSocket s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, HOST, 443, true);
            s.startHandshake();
            // overit, ze certifikat opravdu patri quest.jendovyapky.eu
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(HOST, s.getSession())) {
                s.close();
                return false;
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = s.getOutputStream();
            out.write(("POST " + path + " HTTP/1.1\r\nHost: " + HOST + "\r\nUser-Agent: NeoLauncher/" + VERSION
                    + "\r\nContent-Type: application/json\r\nContent-Length: " + b.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(b);
            out.flush();
            String line = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            s.close();
            return line != null && line.contains(" 200");
        } catch (Throwable t) {
            try { raw.close(); } catch (Throwable ignored) {}
            return false;
        }
    }

    private static String deviceId(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("questcast", Context.MODE_PRIVATE);
        String id = p.getString("id", null);
        if (id == null) {
            id = "quest-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            p.edit().putString("id", id).apply();
        }
        return id;
    }

    private static String deviceName() {
        String m = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (m.isEmpty()) return "Quest";
        return m.toLowerCase().contains("quest") ? m : "Quest (" + m + ")";
    }

    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "'");
    }
}
