package com.neolauncher.data;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Seznam nainstalovanych aplikaci. Pri startu se okamzite nacte z cache
 * (apps.json), aby launcher ukazal mrizku hned, a na pozadi se pak
 * prekontroluje proti PackageManageru.
 * <p>
 * Rozpoznani typu aplikaci (VR / 2D / systemovy panel) a seznam vyloucenych
 * balicku vychazi z Lightning Launcheru (threethan, GPL-3.0).
 */
public final class AppRepository {
    private static final String TAG = "NeoApps";
    private static final String CACHE_FILE = "apps.json";

    public interface Listener {
        void onAppsChanged(List<AppEntry> apps);
    }

    /** Systemovy balicek Questu/Androidu (neni to hra ani aplikace pro uzivatele). */
    public static boolean isExcluded(String pkg) {
        return EXCLUDED.contains(pkg) || pkg.startsWith("com.threethan.launcher.service");
    }

    /** Systemove balicky Questu/Androidu, ktere v launcheru nemaji co delat. */
    private static final Set<String> EXCLUDED = new HashSet<>(List.of(
            "android",
            "com.oculus.panelapp.library",
            "com.oculus.panelapp.devicepairing",
            "com.oculus.cvp",
            "com.oculus.vrshell",
            "com.oculus.shellenv",
            "com.oculus.integrity",
            "com.oculus.systemactivities",
            "com.oculus.systempermissions",
            "com.oculus.systemsearch",
            "com.oculus.systemresource",
            "com.oculus.extrapermissions",
            "com.oculus.mobile_mrc_setup",
            "com.oculus.os.chargecontrol",
            "com.oculus.os.clearactivity",
            "com.oculus.os.voidactivity",
            "com.oculus.os.qrcodereader",
            "com.oculus.AccountsCenter.pwa",
            "com.oculus.identitymanage",
            "com.oculus.voidactivity",
            "com.oculus.xrstreamingclient",
            "com.oculus.vrprivacycheckup",
            "com.oculus.panelapp.settings",
            "com.oculus.panelapp.kiosk",
            "com.meta.handseducationmodule",
            "com.oculus.avatareditor",
            "com.oculus.accountscenter",
            "com.oculus.identitymanagement.service",
            "com.meta.AccountsCenter.pwa",
            "com.oculus.firsttimenux",
            "com.oculus.guidebook",
            "com.oculus.vrshell.desktop",
            "com.oculus.systemux",
            "com.android.healthconnect.controller",
            "com.android.metacam",
            "com.oculus.horizonmediaplayer",
            "com.oculus.guardiansetup",
            "com.oculus.globalsearch",
            "com.oculus.pclinkservice.server",
            "com.meta.pclinkservice.server",
            "com.meta.surfacetypingnux",
            "com.meta.spatial.samples.startersample",
            "com.google.android.inputmethod.latin",
            // Puvodni Lightning Launcher a jeho addon - zalozni launcher,
            // nechceme ho mit mezi hrami.
            "com.threethan.launcher.service"
    ));

    /** Hezci nazvy pro systemove aplikace. */
    private static final Map<String, String> LABELS = new HashMap<>();

    static {
        LABELS.put("systemux://settings", "Nastavení Questu");
        LABELS.put("com.android.settings", "Nastavení Androidu");
        LABELS.put("com.oculus.browser", "Prohlížeč");
        LABELS.put("com.oculus.socialplatform", "Chaty");
        LABELS.put("com.oculus.systemutilities", "Soubory");
        LABELS.put("com.oculus.metacam", "Fotoaparát");
    }

    private final Context ctx;
    private final Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "neo-apps");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean scanQueued = new AtomicBoolean(false);
    private volatile List<AppEntry> apps = Collections.emptyList();
    private volatile boolean scannedOnce;

    /**
     * Signaly pro chytre razeni (herni cas z UsageStats, "nove" aplikace) -
     * dodava aktivita, repozitar sam UsageStats nepouziva.
     */
    public interface Signals {
        long playtimeMs(String pkg);

        long lastUsed(String pkg);

        boolean isNew(AppEntry e);

        /** Zabrane misto v bajtech, -1 = nezname. */
        default long sizeBytes(String pkg) {
            return -1L;
        }
    }

    private volatile Signals signals;

    public void setSignals(Signals s) {
        signals = s;
    }

    public AppRepository(Context c, Prefs prefs) {
        this.ctx = c.getApplicationContext();
        this.prefs = prefs;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public List<AppEntry> apps() {
        return apps;
    }

    public AppEntry find(String pkg) {
        for (AppEntry e : apps) if (e.pkg.equals(pkg)) return e;
        return null;
    }

    /** Nacte posledni znamy seznam z disku (rychle, pouziva se pri studenem startu). */
    public void loadCache() {
        if (!apps.isEmpty()) return;
        try {
            File f = new File(ctx.getFilesDir(), CACHE_FILE);
            if (!f.exists()) return;
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(json);
            List<AppEntry> list = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new AppEntry(o.getString("p"), o.getString("l"),
                        o.getInt("t"), o.optBoolean("b", false), o.optLong("i", 0L)));
            }
            apps = Collections.unmodifiableList(list);
        } catch (Exception e) {
            Log.w(TAG, "Cache aplikaci nejde nacist", e);
        }
    }

    /** Prekontroluje nainstalovane aplikace na pozadi. Vice volani za sebou se slouci. */
    public void refreshAsync() {
        if (!scanQueued.compareAndSet(false, true)) return;
        io.execute(() -> {
            scanQueued.set(false);
            List<AppEntry> fresh;
            try {
                fresh = scan();
            } catch (Exception e) {
                Log.e(TAG, "Sken aplikaci selhal", e);
                return;
            }
            // Prvni sken ohlasit vzdy (i kdyz se shoduje s cache), at UI vi, ze je nacteno.
            if (fresh.equals(apps) && scannedOnce) return;
            scannedOnce = true;
            apps = Collections.unmodifiableList(fresh);
            saveCache(fresh);
            main.post(() -> {
                for (Listener l : listeners) l.onAppsChanged(apps);
            });
        });
    }

    private List<AppEntry> scan() {
        PackageManager pm = ctx.getPackageManager();
        final boolean quest = Platform.isQuest(ctx);

        // Par hromadnych dotazu misto dotazu pro kazdou aplikaci zvlast.
        Set<String> vrActivities = packagesForActivities(pm,
                new Intent(Intent.ACTION_MAIN).addCategory("com.oculus.intent.category.VR"));
        Set<String> launcherActivities = packagesForActivities(pm,
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER));
        Set<String> leanbackActivities = packagesForActivities(pm,
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER));
        Set<String> infoActivities = packagesForActivities(pm,
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_INFO));
        Set<String> shellPanels = new HashSet<>();
        try {
            for (ResolveInfo ri : pm.queryIntentServices(
                    new Intent("com.oculus.vrshell.SHELL_MAIN"), 0)) {
                if (ri.serviceInfo != null) shellPanels.add(ri.serviceInfo.packageName);
            }
        } catch (Exception ignored) {
        }

        List<ApplicationInfo> infos;
        try {
            infos = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        } catch (Exception e) {
            // BadParcelableException pri obrovskem mnozstvi aplikaci - zkusit znovu bez metadat
            infos = pm.getInstalledApplications(0);
        }

        final String self = ctx.getPackageName();
        List<AppEntry> out = new ArrayList<>();
        for (ApplicationInfo ai : infos) {
            final String pkg = ai.packageName;
            if (pkg == null || !ai.enabled) continue;
            if (pkg.equals(self) || pkg.startsWith(self + ".")) continue;
            if (EXCLUDED.contains(pkg) || pkg.startsWith("com.threethan.launcher.service"))
                continue;

            int type;
            if (shellPanels.contains(pkg)
                    || (ai.metaData != null && ai.metaData.containsKey("com.oculus.pwa.START_URL"))) {
                type = AppEntry.TYPE_PANEL;
            } else if (isVr(ai, vrActivities)) {
                type = AppEntry.TYPE_VR;
            } else {
                type = AppEntry.TYPE_2D;
            }

            final boolean launchable = launcherActivities.contains(pkg)
                    || leanbackActivities.contains(pkg)
                    || infoActivities.contains(pkg);
            if (!launchable && type == AppEntry.TYPE_2D) continue;
            if (!launchable && type == AppEntry.TYPE_VR && !vrActivities.contains(pkg)) continue;

            String label = LABELS.get(pkg);
            if (label == null) {
                try {
                    label = pm.getApplicationLabel(ai).toString().trim();
                } catch (Exception e) {
                    continue; // poskozeny zaznam (napr. prave odinstalovano)
                }
            }
            if (label.isEmpty()) label = pkg;
            long installed = 0L;
            try {
                installed = pm.getPackageInfo(pkg, 0).firstInstallTime;
            } catch (Exception ignored) {
            }
            out.add(new AppEntry(pkg, label, type, ai.banner != 0, installed));
        }

        if (quest) {
            out.add(new AppEntry("systemux://settings", LABELS.get("systemux://settings"),
                    AppEntry.TYPE_PANEL, false));
        }

        out.sort(Comparator.comparing(e -> e.pkg));
        return out;
    }

    /** Je balicek VR hra/aplikace? (pro sluzbu Meta tlacitka, kdyz jeste neni seznam nacteny) */
    public static boolean isVrPackage(PackageManager pm, String pkg) {
        try {
            final ApplicationInfo ai = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA);
            final Set<String> vr = new HashSet<>();
            final Intent i = new Intent(Intent.ACTION_MAIN).addCategory("com.oculus.intent.category.VR")
                    .setPackage(pkg);
            if (!pm.queryIntentActivities(i, 0).isEmpty()) vr.add(pkg);
            return isVr(ai, vr);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isVr(ApplicationInfo ai, Set<String> vrActivities) {
        if ("com.android.settings".equals(ai.packageName)) return false;
        if (ai.metaData != null) {
            if (ai.metaData.containsKey("com.oculus.ossplash")
                    || ai.metaData.containsKey("com.samsung.android.vr.application.mode")
                    || ai.metaData.containsKey("com.oculus.intent.category.VR"))
                return true;
        }
        return vrActivities.contains(ai.packageName);
    }

    private static Set<String> packagesForActivities(PackageManager pm, Intent intent) {
        Set<String> out = new HashSet<>();
        try {
            for (ResolveInfo ri : pm.queryIntentActivities(intent, 0)) {
                if (ri.activityInfo != null) out.add(ri.activityInfo.packageName);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void saveCache(List<AppEntry> list) {
        try {
            JSONArray arr = new JSONArray();
            for (AppEntry e : list) {
                JSONObject o = new JSONObject();
                o.put("p", e.pkg);
                o.put("l", e.systemLabel);
                o.put("t", e.type);
                o.put("b", e.hasBanner);
                o.put("i", e.installTime);
                arr.put(o);
            }
            File tmp = new File(ctx.getFilesDir(), CACHE_FILE + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(new File(ctx.getFilesDir(), CACHE_FILE));
        } catch (Exception e) {
            Log.w(TAG, "Cache aplikaci nejde ulozit", e);
        }
    }

    // --- Filtrovani a razeni pro zalozky --------------------------------------

    public static boolean inTab(AppEntry e, int tab) {
        switch (tab) {
            case Prefs.TAB_GAMES:
                return e.type == AppEntry.TYPE_VR;
            case Prefs.TAB_APPS:
                return e.type != AppEntry.TYPE_VR;
            default:
                return true;
        }
    }

    /** Viditelne aplikace v zalozce, serazene podle aktualniho rezimu razeni. */
    public List<AppEntry> forTab(int tab) {
        final Set<String> hidden = prefs.hidden();
        List<AppEntry> list = new ArrayList<>();
        for (AppEntry e : apps) {
            if (!hidden.contains(e.pkg) && inTab(e, tab)) list.add(e);
        }
        final Collator collator = Collator.getInstance(new Locale("cs", "CZ"));
        collator.setStrength(Collator.PRIMARY);
        final Map<String, String> labels = new HashMap<>();
        for (AppEntry e : list) labels.put(e.pkg, prefs.labelFor(e));
        final Comparator<AppEntry> byLabel =
                (a, b) -> collator.compare(labels.get(a.pkg), labels.get(b.pkg));

        final Signals sig = signals;
        switch (prefs.sortMode()) {
            case Prefs.SORT_ALPHA:
                list.sort(byLabel);
                break;
            case Prefs.SORT_RECENT: {
                final Map<String, Long> last = new HashMap<>();
                for (AppEntry e : list) {
                    last.put(e.pkg, Math.max(prefs.lastLaunch(e.pkg), sig != null ? sig.lastUsed(e.pkg) : 0L));
                }
                list.sort((a, b) -> {
                    int c = Long.compare(last.get(b.pkg), last.get(a.pkg));
                    return c != 0 ? c : byLabel.compare(a, b);
                });
                break;
            }
            case Prefs.SORT_PLAYTIME:
            case Prefs.SORT_SMART: {
                // Nejhranejsi: podle herniho casu. Chytre: nove (jeste nespustene) aplikace
                // prvni, pak podle herniho casu, pak naposledy spustene, pak abecedne.
                final boolean smart = prefs.sortMode() == Prefs.SORT_SMART;
                final Map<String, Long> play = new HashMap<>();
                final Map<String, Long> last = new HashMap<>();
                final Map<String, Long> fresh = new HashMap<>();
                for (AppEntry e : list) {
                    play.put(e.pkg, sig != null ? sig.playtimeMs(e.pkg) : 0L);
                    last.put(e.pkg, Math.max(prefs.lastLaunch(e.pkg), sig != null ? sig.lastUsed(e.pkg) : 0L));
                    fresh.put(e.pkg, smart && sig != null && sig.isNew(e) ? Math.max(1L, e.installTime) : 0L);
                }
                list.sort((a, b) -> {
                    int c = Long.compare(fresh.get(b.pkg), fresh.get(a.pkg));
                    if (c != 0) return c;
                    c = Long.compare(play.get(b.pkg), play.get(a.pkg));
                    if (c != 0) return c;
                    c = Long.compare(last.get(b.pkg), last.get(a.pkg));
                    return c != 0 ? c : byLabel.compare(a, b);
                });
                break;
            }
            case Prefs.SORT_SIZE: {
                // Nejvetsi prvni (uklid mista); nezname velikosti na konec, abecedne.
                final Map<String, Long> size = new HashMap<>();
                for (AppEntry e : list) size.put(e.pkg, sig != null ? sig.sizeBytes(e.pkg) : -1L);
                list.sort((a, b) -> {
                    int c = Long.compare(size.get(b.pkg), size.get(a.pkg));
                    return c != 0 ? c : byLabel.compare(a, b);
                });
                break;
            }
            default: {
                // Rucni poradi; nove aplikace (jeste nezarazene) jdou abecedne na konec.
                final List<String> order = prefs.manualOrder(tab);
                final Map<String, Integer> index = new HashMap<>();
                for (int i = 0; i < order.size(); i++) index.put(order.get(i), i);
                list.sort((a, b) -> {
                    Integer ia = index.get(a.pkg), ib = index.get(b.pkg);
                    if (ia != null && ib != null) return Integer.compare(ia, ib);
                    if (ia != null) return -1;
                    if (ib != null) return 1;
                    return byLabel.compare(a, b);
                });
                break;
            }
        }
        // Oblibene (hvezdicka) jsou vzdy nahore - razeni uvnitr zustane (stabilni razeni).
        final Set<String> fav = prefs.favorites();
        if (!fav.isEmpty()) {
            List<AppEntry> pinned = new ArrayList<>();
            List<AppEntry> rest = new ArrayList<>();
            for (AppEntry e : list) (fav.contains(e.pkg) ? pinned : rest).add(e);
            pinned.addAll(rest);
            return pinned;
        }
        return list;
    }
}
