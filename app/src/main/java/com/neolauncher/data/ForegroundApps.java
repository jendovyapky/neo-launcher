package com.neolauncher.data;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Co je ted opravdu v popredi a co bezi na pozadi - pro doplnek Meta tlacitka
 * (pta se pres ShortcutStateProvider) i pro listu bezici hry v Neu. Udalosti
 * oken na Questu nestaci: kdyz se ve hre zmackne Meta, Quest posle udalost
 * domovskeho prostredi, jako by hra skoncila.
 * <p>
 * Zdroj pravdy = UsageStats (stejne opravneni "Pristup k vyuziti" jako herni
 * cas): posledni udalost kazde aktivity RESUMED / PAUSED / STOPPED. Hra
 * "bezi", kdyz jeji aktivita je RESUMED, nebo byla PAUSED pred chvilkou
 * (menu Questu pres hru). Quest ale hru pri otevreni menu muze rovnou
 * zastavit (STOPPED) - proto i "prave odesla z popredi" (recentVrPkg).
 * <p>
 * Ukoncenou a jen odlozenou hru UsageStats neodlisi (Android po STOPPED uz
 * zadnou udalost neposle), takze "na pozadi" (bgVrPkg) = posledni VR hra od
 * zapnuti Questu, ktera neni v popredi, neni vynucene ukoncena a po ni se
 * nespustila jina VR hra (Quest drzi jen jednu).
 */
public final class ForegroundApps {
    private static final String TAG = "NeoMeta";
    /** Kolik historie projit (udalosti jsou po startu headsetu, hra delsi nez tohle je vzacnost). */
    private static final long WINDOW_MS = 8L * 60 * 60 * 1000;
    /** PAUSED pred mene nez tolika ms = hra porad bezi pod menu Questu. */
    private static final long PAUSED_GRACE_MS = 5000;
    /** Hra odesla z popredi pred mene nez tolika ms = prave se zmacklo Meta (menu pres hru). */
    private static final long RECENT_MS = 6000;
    private static final long CACHE_MS = 400;
    /** Hra skoncena pred mene nez tolika ms a hrala aspon MIN_PLAY_MS = "po hre otevrit Neo". */
    private static final long ENDED_WINDOW_MS = 15_000;
    private static final long MIN_PLAY_MS = 4000;
    private static final Set<String> SYSTEM = new HashSet<>(java.util.Arrays.asList(
            "com.oculus.vrshell", "com.oculus.shellenv", "com.oculus.systemux", "com.oculus.panelapp.library",
            "com.android.systemui", "android", "com.neolauncher.meta"));

    /** Vysledek dotazu. */
    public static final class Result {
        /** Mame opravneni a dotaz se povedl (jinak se doplnek ridi udalostmi oken). */
        public boolean known;
        /** Posledni aplikace v popredi (RESUMED), krome Nea a systemu, nebo null. */
        public String topPkg;
        /** VR hra, ktera ted bezi (RESUMED, nebo PAUSED pred chvilkou = pod menu Questu), nebo null. */
        public String vrPkg;
        /** VR hra, ktera odesla z popredi (PAUSED / STOPPED) pred par vterinami, nebo null. */
        public String recentVrPkg;
        /** VR hra, ktera prave skoncila (STOPPED pred chvilkou po aspon par vterinach hrani), nebo null. */
        public String endedVrPkg;
        /** Posledni VR hra, ktera neni v popredi a nejspis bezi na pozadi (viz popis tridy), nebo null. */
        public String bgVrPkg;
        /** Kdy bgVrPkg odesla do pozadi (System.currentTimeMillis). */
        public long bgSince;
    }

    private static Result cached;
    private static long cachedAt;
    private static final java.util.concurrent.ExecutorService IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r, "neo-foreground");
                t.setDaemon(true);
                return t;
            });
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    private ForegroundApps() {}

    public static synchronized Result query(Context c) {
        final long now = SystemClock.uptimeMillis();
        if (cached != null && now - cachedAt < CACHE_MS) return cached;
        final Result r = new Result();
        try {
            final AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            final boolean allowed = ops != null && ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
            final UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
            if (allowed && usm != null) {
                compute(c, usm, r);
                r.known = true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Popredi z UsageStats nejde zjistit", e);
        }
        cached = r;
        cachedAt = now;
        return r;
    }

    /** Dotaz na pozadi (UsageStats za 8 h muze chvili trvat), vysledek na hlavnim vlakne. */
    public static void queryAsync(Context c, java.util.function.Consumer<Result> onMain) {
        final Context app = c.getApplicationContext();
        IO.execute(() -> {
            final Result r = query(app);
            MAIN.post(() -> onMain.accept(r));
        });
    }

    private static void compute(Context c, UsageStatsManager usm, Result r) {
        final long now = System.currentTimeMillis();
        final UsageEvents events = usm.queryEvents(now - WINDOW_MS, now);
        // Posledni stav kazde aktivity (balicek/trida) a kdy.
        final Map<String, Integer> state = new HashMap<>();
        final Map<String, Long> when = new HashMap<>();
        final Map<String, Long> resumedAt = new HashMap<>();
        // Posledni RESUMED kazdeho balicku (kdo byl naposledy v popredi).
        final Map<String, Long> pkgResumed = new HashMap<>();
        final UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            final int t = e.getEventType();
            if (t == UsageEvents.Event.DEVICE_STARTUP || t == UsageEvents.Event.DEVICE_SHUTDOWN) {
                // Po restartu Questu nic z predtim nebezi.
                state.clear();
                when.clear();
                resumedAt.clear();
                pkgResumed.clear();
                continue;
            }
            if (t != UsageEvents.Event.ACTIVITY_RESUMED && t != UsageEvents.Event.ACTIVITY_PAUSED
                    && t != UsageEvents.Event.ACTIVITY_STOPPED) continue;
            final String key = e.getPackageName() + "/" + e.getClassName();
            final Integer prev = state.get(key);
            if (t == UsageEvents.Event.ACTIVITY_RESUMED) {
                if (prev == null || prev == UsageEvents.Event.ACTIVITY_STOPPED) {
                    resumedAt.put(key, e.getTimeStamp()); // zacatek "sezeni" aktivity
                }
                pkgResumed.put(e.getPackageName(), e.getTimeStamp());
            }
            state.put(key, t);
            when.put(key, e.getTimeStamp());
        }
        final String self = c.getPackageName();
        final PackageManager pm = c.getPackageManager();
        long topAt = 0, vrAt = 0, endedAt = 0, recentAt = 0;
        final Set<String> runningPkgs = new HashSet<>();
        // Posledni stav a cas kazdeho VR balicku (pres vsechny jeho aktivity).
        final Map<String, Long> vrLast = new HashMap<>();
        final Map<String, Boolean> vrCache = new HashMap<>();
        for (Map.Entry<String, Integer> en : state.entrySet()) {
            final String pkg = en.getKey().substring(0, en.getKey().indexOf('/'));
            if (pkg.equals(self) || SYSTEM.contains(pkg) || AppRepository.isExcluded(pkg)) continue;
            final int t = en.getValue();
            final long at = when.get(en.getKey());
            Boolean vrBox = vrCache.get(pkg);
            if (vrBox == null) {
                vrBox = AppRepository.isVrPackage(pm, pkg);
                vrCache.put(pkg, vrBox);
            }
            final boolean vr = vrBox;
            if (vr) {
                final Long last = vrLast.get(pkg);
                if (last == null || at > last) vrLast.put(pkg, at);
            }
            if (t == UsageEvents.Event.ACTIVITY_STOPPED && now - at < ENDED_WINDOW_MS && at > endedAt && vr) {
                final Long start = resumedAt.get(en.getKey());
                if (start != null && at - start >= MIN_PLAY_MS) {
                    endedAt = at;
                    r.endedVrPkg = pkg;
                }
            }
            if (vr && t != UsageEvents.Event.ACTIVITY_RESUMED && now - at < RECENT_MS && at > recentAt) {
                recentAt = at;
                r.recentVrPkg = pkg;
            }
            final boolean resumed = t == UsageEvents.Event.ACTIVITY_RESUMED;
            final boolean justPaused = t == UsageEvents.Event.ACTIVITY_PAUSED && now - at < PAUSED_GRACE_MS;
            if (!resumed && !justPaused) continue;
            if (!vr && pm.getLaunchIntentForPackage(pkg) == null) continue;
            if (resumed && at > topAt) {
                topAt = at;
                r.topPkg = pkg;
            }
            runningPkgs.add(pkg);
            if (at > vrAt && vr) {
                vrAt = at;
                r.vrPkg = pkg;
            }
        }
        // Hra, ktera porad bezi (jina aktivita), neskoncila.
        if (r.endedVrPkg != null && runningPkgs.contains(r.endedVrPkg)) r.endedVrPkg = null;
        if (r.recentVrPkg != null && runningPkgs.contains(r.recentVrPkg)) r.recentVrPkg = null;

        // Na pozadi: VR hra, ktera byla naposledy v popredi (novejsi VR hra by ji na Questu
        // ukoncila), ted v popredi neni a nebyla vynucene ukoncena.
        String bg = null;
        long bgResumed = 0;
        for (String pkg : vrLast.keySet()) {
            final Long res = pkgResumed.get(pkg);
            if (res != null && res > bgResumed) {
                bgResumed = res;
                bg = pkg;
            }
        }
        if (bg != null && !runningPkgs.contains(bg) && !isForceStopped(pm, bg)) {
            r.bgVrPkg = bg;
            r.bgSince = vrLast.get(bg);
        }
    }

    /** Vynucene ukoncena aplikace (Ukoncit v Neu, Vynutit ukonceni) - urcite nebezi. */
    private static boolean isForceStopped(PackageManager pm, String pkg) {
        try {
            return (pm.getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_STOPPED) != 0;
        } catch (Exception e) {
            return true; // odinstalovana
        }
    }

    /** Pro logcat doplnku (NeoMeta). */
    public static String describe(Result r) {
        if (!r.known) return "bez UsageStats";
        return "top=" + r.topPkg + " vr=" + r.vrPkg + " recent=" + r.recentVrPkg + " ended=" + r.endedVrPkg
                + " bg=" + r.bgVrPkg + (r.bgVrPkg != null
                ? " (" + (System.currentTimeMillis() - r.bgSince) / 1000 + " s)" : "");
    }
}
