package com.neolauncher.data;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Herni cas a posledni spusteni z Android UsageStats - stejne jako plugin
 * "Playtime" v Lightning Launcheru (PlaytimeHelper). Vyzaduje zvlastni
 * opravneni "Pristup k vyuziti" (Settings -> Usage access, nebo pres adb:
 * {@code adb shell appops set com.neolauncher.v1 GET_USAGE_STATS allow}).
 * <p>
 * Dotaz na 10 let historie neni zadarmo -> jede na pozadi, vysledek se drzi
 * v pameti a obnovuje nejvys jednou za 30 s.
 */
public final class UsageInfo {
    private static final String TAG = "NeoUsage";
    private static final long REFRESH_MS = 30_000;

    public interface Listener {
        void onUsageChanged();
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "neo-usage");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private volatile Map<String, long[]> stats = Collections.emptyMap();
    private final Map<String, Long> installTimes = new HashMap<>();
    private long loadedAt;
    private boolean loading;
    private Listener listener;

    public UsageInfo(Context c) {
        ctx = c.getApplicationContext();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean hasPermission() {
        try {
            AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            return ops != null && ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), ctx.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Otevre systemove nastaveni "Pristup k vyuziti" (jako Lightning Launcher). */
    public static boolean requestPermission(Activity a) {
        Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        i.setPackage("com.android.settings");
        try {
            a.startActivity(i);
            return true;
        } catch (Exception e) {
            try {
                a.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    /** Obnovi data na pozadi (kdyz jsou starsi nez 30 s nebo force). */
    public void refresh(boolean force) {
        final long now = System.currentTimeMillis();
        if (loading || (!force && now - loadedAt < REFRESH_MS)) return;
        if (!hasPermission()) return;
        loading = true;
        exec.execute(() -> {
            Map<String, long[]> out = new HashMap<>();
            try {
                UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
                if (usm != null) {
                    Calendar cal = Calendar.getInstance();
                    cal.add(Calendar.YEAR, -10);
                    long start = cal.getTimeInMillis();
                    cal.add(Calendar.YEAR, 11);
                    Map<String, UsageStats> m = usm.queryAndAggregateUsageStats(start, cal.getTimeInMillis());
                    if (m != null) {
                        for (Map.Entry<String, UsageStats> e : m.entrySet()) {
                            UsageStats s = e.getValue();
                            out.put(e.getKey(), new long[]{s.getTotalTimeInForeground(), s.getLastTimeUsed()});
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "UsageStats nejdou nacist", e);
            }
            main.post(() -> {
                stats = out;
                loadedAt = System.currentTimeMillis();
                loading = false;
                if (listener != null) listener.onUsageChanged();
            });
        });
    }

    /** Celkovy cas v popredi (ms), 0 kdyz neni znamy. */
    public long playtimeMs(String pkg) {
        long[] s = stats.get(pkg);
        return s != null ? s[0] : 0L;
    }

    /** Posledni pouziti podle systemu (ms od 1970), 0 kdyz neni znamo. */
    public long lastUsed(String pkg) {
        long[] s = stats.get(pkg);
        return s != null ? s[1] : 0L;
    }

    /** Datum prvni instalace (ms), 0 kdyz neni znamo. */
    public long installTime(String pkg) {
        Long t = installTimes.get(pkg);
        if (t != null) return t;
        long v = 0L;
        try {
            v = ctx.getPackageManager().getPackageInfo(pkg, 0).firstInstallTime;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        installTimes.put(pkg, v);
        return v;
    }
}
