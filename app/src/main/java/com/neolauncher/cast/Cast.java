package com.neolauncher.cast;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Ovladani streamovani z launcheru (hlavni proces). Samotne nahravani bezi
 * v procesu :cast (CaptureService), takze pad streamovani nikdy neshodi Neo.
 * Stav se zjistuje bez sdilene pameti: bezi sluzba? + /stats z mistniho serveru.
 */
public final class Cast {
    private Cast() {}

    /** Adresa, kterou kamarad otevre v prohlizeci (najde Quest ve stejne Wi-Fi). */
    public static final String SITE = Discovery.HOST;

    /** Stav pro okno Streamovat (null = nevysila). */
    public static final class Status {
        public boolean running;
        public String out = "";
        public float fps;
        public int viewers, listeners;
        public String audio = "ok";
        public String error;
    }

    /** Bezi sluzba nahravani? (rychle, jen vlastni sluzby aplikace) */
    @SuppressWarnings("deprecation")
    public static boolean isRunning(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            for (ActivityManager.RunningServiceInfo s : am.getRunningServices(50)) {
                if (CaptureService.class.getName().equals(s.service.getClassName()) && s.foreground) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** Zepta se na povoleni a spusti nahravani (dialogy ukaze system). */
    public static void start(Context c) {
        Intent i = new Intent(c, CastStartActivity.class);
        if (!(c instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    public static void stop(Context c) {
        try {
            c.startService(new Intent(c, CaptureService.class).setAction(CaptureService.ACTION_STOP));
        } catch (Exception ignored) {}
    }

    /** Adresa streamu primo ve Wi-Fi (zaloha, kdyz web nejde), nebo null. */
    public static String lanUrl() {
        String ip = CaptureService.wifiIp();
        return ip == null ? null : "http://" + ip + ":" + CaptureService.PORT;
    }

    /** Podrobnosti z mistniho serveru. Blokuje (max ~0,6 s) - volat mimo hlavni vlakno. */
    public static Status fetchStatus() {
        Status st = new Status();
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL("http://127.0.0.1:" + CaptureService.PORT + "/stats").openConnection();
            h.setConnectTimeout(300);
            h.setReadTimeout(300);
            try (InputStream in = h.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) bos.write(b, 0, n);
                JSONObject j = new JSONObject(bos.toString("UTF-8"));
                st.running = j.optBoolean("running");
                st.out = j.optString("out");
                st.fps = (float) j.optDouble("fps", 0);
                st.viewers = j.optInt("viewers");
                st.listeners = j.optInt("listeners");
                st.audio = j.optString("audio", "ok");
                st.error = j.isNull("error") ? null : j.optString("error");
            }
        } catch (Exception e) {
            st.running = false;
        } finally {
            if (h != null) h.disconnect();
        }
        return st;
    }
}
