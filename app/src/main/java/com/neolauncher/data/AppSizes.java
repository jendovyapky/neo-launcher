package com.neolauncher.data;

import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kolik ktera hra/aplikace zabira (aplikace + data + OBB). Pres
 * StorageStatsManager - potrebuje stejne povoleni jako herni cas
 * ("Pristup k vyuziti"). Pocita se na pozadi a drzi v pameti; obnova
 * nejdriv za 10 minut (nebo po instalaci / odinstalaci).
 */
public final class AppSizes {
    private static final String TAG = "NeoSizes";
    private static final long REFRESH_MS = 10 * 60 * 1000L;

    private final Context ctx;
    private final Map<String, Long> sizes = new ConcurrentHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "neo-sizes");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private long lastRefresh;
    private boolean running;

    public AppSizes(Context c) {
        ctx = c.getApplicationContext();
    }

    /** @return velikost v bajtech, nebo -1 kdyz neni znama (chybi povoleni / jeste se pocita) */
    public long get(String pkg) {
        final Long v = sizes.get(pkg);
        return v != null ? v : -1L;
    }

    /**
     * Prepocita velikosti na pozadi (nejdriv za 10 min, force = hned).
     * @param onDone na hlavnim vlakne, kdyz se neco zmenilo
     */
    public void refresh(List<AppEntry> apps, boolean force, Runnable onDone) {
        final long now = SystemClock.elapsedRealtime();
        if (running || (!force && lastRefresh != 0 && now - lastRefresh < REFRESH_MS)) return;
        running = true;
        lastRefresh = now;
        final List<String> pkgs = new ArrayList<>();
        for (AppEntry e : apps) if (!e.isSystemPanel()) pkgs.add(e.pkg);
        exec.execute(() -> {
            boolean changed = false;
            try {
                final StorageStatsManager ssm = ctx.getSystemService(StorageStatsManager.class);
                if (ssm != null) {
                    for (String pkg : pkgs) {
                        try {
                            final StorageStats st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT,
                                    pkg, Process.myUserHandle());
                            final long total = st.getAppBytes() + st.getDataBytes();
                            final Long old = sizes.put(pkg, total);
                            if (old == null || old != total) changed = true;
                        } catch (SecurityException e) {
                            // Bez povoleni "Pristup k vyuziti" - nema smysl zkouset dalsi.
                            break;
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Velikosti aplikaci nejde zjistit", e);
            }
            final boolean notify = changed;
            main.post(() -> {
                running = false;
                if (notify && onDone != null) onDone.run();
            });
        });
    }

    /** "14,2 GB" / "850 MB" (desitkove jednotky jako v nastaveni Questu). */
    public static String format(long bytes) {
        if (bytes < 0) return null;
        if (bytes >= 1_000_000_000L) {
            return String.format(new Locale("cs", "CZ"), "%.1f GB", bytes / 1e9);
        }
        return Math.max(1, Math.round(bytes / 1e6)) + " MB";
    }
}
