package com.neolauncher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.content.pm.PackageInstaller;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.neolauncher.art.ArtworkLoader;
import com.neolauncher.data.AppEntry;
import com.neolauncher.data.AppSizes;
import com.neolauncher.data.BatteryEstimate;
import com.neolauncher.data.ForegroundApps;
import com.neolauncher.data.Backup;
import com.neolauncher.data.AppRepository;
import com.neolauncher.data.Prefs;
import com.neolauncher.data.UsageInfo;
import com.neolauncher.launch.AppLauncher;
import com.neolauncher.update.ApkInstaller;
import com.neolauncher.update.InstallReceiver;
import com.neolauncher.update.Updater;
import com.neolauncher.ui.AppMenu;
import com.neolauncher.ui.CarouselView;
import com.neolauncher.ui.ConfirmSheet;
import com.neolauncher.ui.Glass;
import com.neolauncher.ui.Icons;
import com.neolauncher.ui.Palette;
import com.neolauncher.ui.NeoLauncherView;
import com.neolauncher.ui.OverlayHost;
import com.neolauncher.ui.CastSheet;
import com.neolauncher.ui.PowerSheet;
import com.neolauncher.ui.QuickMenuView;
import com.neolauncher.ui.SearchSheet;
import com.neolauncher.ui.WhatsNewSheet;
import com.neolauncher.ui.SettingsSheet;
import com.neolauncher.ui.UpdateSheet;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Jedina aktivita launcheru. Drzi NeoLauncherView a vrstvu dialogu. */
public class LauncherActivity extends Activity
        implements NeoLauncherView.Host, AppRepository.Listener, OverlayHost.Listener, Prefs.Listener {

    private static final int REQ_PICK_IMAGE = 41;
    /** Vyber APK k instalaci (Nastaveni -> Aplikace). */
    public static final int REQ_PICK_APK = 42;
    /** Zaloha nastaveni: kam ulozit / odkud obnovit. */
    public static final int REQ_EXPORT = 43;
    public static final int REQ_IMPORT = 44;
    /** Automaticka kontrola aktualizaci nejvys jednou za 6 hodin. */
    private static final long UPDATE_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000;
    private static volatile boolean sForeground;
    private static volatile boolean sVisible;

    private Prefs prefs;
    private AppRepository repo;
    private ArtworkLoader artwork;
    private NeoLauncherView launcher;
    /** Testovaci karuselovy rezim (5x logo Neo); lezi nad mrizkou, ukazan je vzdy jen jeden. */
    private CarouselView carousel;
    private boolean carouselShown;
    private UsageInfo usage;
    /** Zabrane misto her (menu karty, razeni podle velikosti). */
    private AppSizes sizes;
    private BatteryEstimate battery;
    private int batteryPct = -1;
    private boolean batteryCharging, batteryFast;
    private OverlayHost overlay;
    private String pendingImagePkg;
    private boolean receiversRegistered;
    private long lastRefreshMs;
    private boolean lastLaunchOk;

    /** Pro addon Meta tlacitka: je launcher prave v popredi? */
    public static boolean isInForeground() {
        return sForeground;
    }

    /** Je okno launcheru videt (i kdyz je nad nim menu Questu)? Pro sluzbu Meta tlacitka. */
    public static boolean isVisible() {
        return sVisible;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Okno skoro uplne pruhledne (alfa 1/255 - Quest s alfou 0 muze
        // delat problemy s kompozici, viz Lightning Launcher). Sklo si kresli View.
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.argb(1, 0, 0, 0)));

        NeoApp app = (NeoApp) getApplication();
        prefs = app.prefs();
        Glass.setStyle(prefs.glassStyle() == Prefs.GLASS_VISION);
        repo = app.apps();
        artwork = app.artwork();

        FrameLayout root = new FrameLayout(this);
        launcher = new NeoLauncherView(this);
        launcher.bind(prefs, artwork);
        launcher.setHost(this);
        root.addView(launcher, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        usage = new UsageInfo(this);
        sizes = new AppSizes(this);
        battery = new BatteryEstimate(this);
        repo.setSignals(signals);
        launcher.setSignals(signals);
        carousel = new CarouselView(this);
        carousel.bind(prefs, artwork, carouselStats);
        carousel.setHost(carouselHost);
        carousel.setSignals(signals);
        usage.setListener(() -> {
            carousel.statsChanged();
            // Razeni podle herniho casu se po nacteni UsageStats preradi.
            if (sortNeedsUsage()) showApps(true);
        });
        root.addView(carousel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = new OverlayHost(this);
        overlay.setListener(this);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        repo.addListener(this);
        prefs.addListener(this);
        InstallReceiver.setListener(installListener);
        applyMode(false);
        repo.loadCache();
        if (!repo.apps().isEmpty()) showApps(false);
        repo.refreshAsync();
        lastRefreshMs = System.currentTimeMillis();
        handleServiceIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Neo otevrene znovu (Meta tlacitko, knihovna), i kdyz okno nebylo zastavene -> nastup karet.
        if (!carouselShown && !intent.getBooleanExtra(MetaAddon.EXTRA_RESUME, false)) launcher.playIntro();
        handleServiceIntent(intent);
    }

    /** Neo otevrela sluzba Meta tlacitka: po ukonceni aplikace, nebo 3x Meta v Neu = zpet do hry. */
    private void handleServiceIntent(Intent i) {
        if (i == null) return;
        final String running = i.getStringExtra(MetaAddon.EXTRA_RUNNING);
        if (running != null) {
            // Doplnek otevrel Neo ze hry / aplikace (Meta nebo 3x Meta) -> lista Pokracovat / Ukoncit.
            i.removeExtra(MetaAddon.EXTRA_RUNNING);
            MetaAddon.setRunning(running);
            syncRunningApp();
        }
        final String stopped = i.getStringExtra(MetaAddon.EXTRA_STOPPED);
        if (stopped != null) {
            i.removeExtra(MetaAddon.EXTRA_STOPPED);
            launcher.clearLive();
            final String label = appLabel(stopped);
            launcher.post(() -> notice(Icons.CHECK, "Ukončeno: " + label, Palette.COBALT_LIGHT, 3500));
        }
        final String ended = i.getStringExtra(MetaAddon.EXTRA_ENDED);
        if (ended != null) {
            // Neo otevrene po skonceni hry -> listu "bezi na pozadi" pro ni neukazovat.
            i.removeExtra(MetaAddon.EXTRA_ENDED);
            prefs.dismissBackground(ended, System.currentTimeMillis());
            if (ended.equals(MetaAddon.runningApp())) {
                MetaAddon.setRunning(null);
                syncRunningApp();
            }
        }
        if (i.getBooleanExtra(MetaAddon.EXTRA_RESUME, false)) {
            i.removeExtra(MetaAddon.EXTRA_RESUME);
            onRunningAction(NeoLauncherView.RUN_RESUME);
        }
    }

    /**
     * Neo si hru na pozadi hlida i samo (nejen kdyz ho otevrel doplnek ze hry): posledni
     * VR hra, ktera neni v popredi a nebyla ukoncena (UsageStats, viz ForegroundApps) ->
     * lista Pokracovat / Ukoncit. Bez toho hra (Beat Saber) bezela nepozorovane na pozadi.
     */
    private void checkBackgroundGame() {
        ForegroundApps.queryAsync(this, r -> {
            if (isFinishing() || !r.known) return;
            final String pkg = r.bgVrPkg != null ? r.bgVrPkg : r.vrPkg;
            final long since = r.bgVrPkg != null ? r.bgSince : System.currentTimeMillis();
            final String cur = MetaAddon.runningApp();
            if (cur != null) {
                // Lista uz neco ukazuje. VR hra, ktera na pozadi uz neni (ukoncena, nahradila
                // ji jina VR hra), ale pryc - jinak nechat (2D aplikace z 3x Meta).
                final AppEntry e = repo.find(cur);
                if (e == null || !e.isVr() || cur.equals(pkg)) return;
                MetaAddon.setRunning(null);
                syncRunningApp();
            }
            if (pkg == null || prefs.isBackgroundDismissed(pkg, since)) return;
            Log.i("NeoMeta", "Na pozadi bezi " + pkg + " -> lista");
            MetaAddon.setRunning(pkg);
            syncRunningApp();
        });
    }

    /** Nazev aplikace (prejmenovani z Nea, jinak z Androidu). */
    private String appLabel(String pkg) {
        final AppEntry e = repo.find(pkg);
        if (e != null) return prefs.labelFor(e);
        try {
            return String.valueOf(getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception ex) {
            return pkg;
        }
    }

    /** Lista bezici aplikace podle sluzby Meta tlacitka (3x Meta ze hry). */
    private void syncRunningApp() {
        final String pkg = MetaAddon.runningApp();
        if (pkg == null || pkg.equals(getPackageName())) {
            launcher.setRunningApp(null, null);
            return;
        }
        AppEntry e = repo.find(pkg);
        final String label = appLabel(pkg);
        if (e == null) e = new AppEntry(pkg, label, AppEntry.TYPE_2D, false);
        launcher.setRunningApp(e, label);
    }

    @Override
    public void onRunningAction(int action) {
        final String pkg = MetaAddon.runningApp();
        MetaAddon.clearRunning(this);
        launcher.setRunningApp(null, null);
        if (pkg == null) return;
        // Schovana / ukoncena hra: listu uz neukazovat, dokud znovu neodejde do pozadi.
        if (action != NeoLauncherView.RUN_RESUME) prefs.dismissBackground(pkg, System.currentTimeMillis());
        if (action == NeoLauncherView.RUN_HIDE) return;
        final String label = appLabel(pkg);
        if (action == NeoLauncherView.RUN_RESUME) {
            AppEntry e = repo.find(pkg);
            if (e == null) e = new AppEntry(pkg, label, AppEntry.TYPE_2D, false);
            launchFromMenu(e);
            return;
        }
        // Ukoncit: sluzba klepne v Informacich o aplikaci na Vynutit ukonceni a vrati se sem.
        if (MetaAddon.forceStop(this, pkg)) {
            showLive(Icons.POWER, "Ukončuji " + label + "…", NeoLauncherView.LIVE_SPINNER, 0);
        } else {
            AppLauncher.openAppInfo(this, pkg);
            Toast.makeText(this, "Klepni na „Vynutit ukončení“", Toast.LENGTH_LONG).show();
        }
    }

    /** Po onStart prehrat nastup karet (jednou pri kazdem ukazani launcheru). */
    private boolean introPending;

    @Override
    protected void onStart() {
        super.onStart();
        registerReceivers();
        // Navrat (napr. ze hry): kolik baterie ubylo = skutecna spotreba pro odhad vydrze.
        battery.onReturn(batteryPct, batteryCharging);
        sVisible = true;
        // Launcher se prave ukazal (otevreni, navrat ze hry) -> v onResume nastup karet.
        introPending = true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        sVisible = false;
        battery.onLeave(batteryPct, batteryCharging);
        unregisterReceivers();
    }

    @Override
    protected void onResume() {
        super.onResume();
        sForeground = true;
        if (introPending) {
            introPending = false;
            if (carouselShown) carousel.show();
            else launcher.playIntro();
        }
        launcher.onClockTick();
        carousel.onClockTick();
        syncRunningApp();
        checkBackgroundGame();
        // Pri navratu do launcheru (napr. po odinstalaci ve Store) prekontrolovat aplikace.
        long now = System.currentTimeMillis();
        if (now - lastRefreshMs > 1500) {
            lastRefreshMs = now;
            repo.refreshAsync();
        }
        if (prefs.sortMode() == Prefs.SORT_RECENT) showApps(true);
        if (carouselShown || sortNeedsUsage()) usage.refresh(false);
        if (carouselShown) carousel.statsChanged();
        refreshSizes(false);
        // Az je okno rozlozene (dialog potrebuje rozmery panelu).
        launcher.post(() -> {
            maybeShowWhatsNew();
            maybePromptAddon();
        });
        checkForUpdates(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sForeground = false;
        launcher.saveState();
        carousel.saveState();
        launcher.clearPointer();
        carousel.clearPointer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        repo.removeListener(this);
        prefs.removeListener(this);
        InstallReceiver.clearListener(installListener);
    }

    @Override
    public void onBackPressed() {
        if (overlay.isOpen()) overlay.close();
        // Jinak nic - launcher se tlacitkem zpet nezavira.
    }

    // --- Seznam aplikaci -------------------------------------------------------

    /** Velikosti her na pozadi; po nacteni preradit, kdyz se radi podle velikosti. */
    private void refreshSizes(boolean force) {
        if (!usage.hasPermission()) return;
        sizes.refresh(repo.apps(), force, () -> {
            if (prefs.sortMode() == Prefs.SORT_SIZE) showApps(true);
        });
    }

    @Override
    public void onAppsChanged(List<AppEntry> apps) {
        showApps(true);
        refreshSizes(false);
    }

    private void showApps(boolean animate) {
        List<AppEntry> list = repo.forTab(launcher.tab());
        List<String> labels = labelsFor(list);
        launcher.setApps(list, labels, animate);
        carousel.setApps(list, labels, launcher.tab());
    }

    // --- Karusel (testovaci rezim) ------------------------------------------------

    @Override
    public void onPrefsChanged() {
        Glass.setStyle(prefs.glassStyle() == Prefs.GLASS_VISION);
        if (prefs.carouselMode() != carouselShown) applyMode(true);
    }

    /** Ukaze mrizku, nebo karusel (podle nastaveni). Druhy pohled je INVISIBLE, ale rozlozeny. */
    private void applyMode(boolean animate) {
        carouselShown = prefs.carouselMode();
        launcher.setVisibility(carouselShown ? View.INVISIBLE : View.VISIBLE);
        carousel.setVisibility(carouselShown ? View.VISIBLE : View.INVISIBLE);
        if (carouselShown) {
            launcher.clearPointer();
            usage.refresh(false);
            if (animate) carousel.show();
        } else {
            carousel.clearPointer();
            if (animate) launcher.playEnter();
        }
    }

    @Override
    public void onToggleCarousel() {
        final boolean on = !prefs.carouselMode();
        prefs.setCarouselMode(on);
        if (on) toast("Karusel (testovací) – zpět šipkou vlevo nahoře");
    }

    /** Naposledy spustena aplikace (z Nea nebo podle Questu), mimo skryte a systemove panely. */
    private AppEntry lastPlayed() {
        final java.util.Set<String> hidden = prefs.hidden();
        AppEntry best = null;
        long bestT = 0;
        for (AppEntry e : repo.apps()) {
            if (e.isSystemPanel() || hidden.contains(e.pkg)) continue;
            final long t = signals.lastUsed(e.pkg);
            if (t > bestT) {
                bestT = t;
                best = e;
            }
        }
        return best;
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private boolean sortNeedsUsage() {
        final int m = prefs.sortMode();
        return m == Prefs.SORT_PLAYTIME || m == Prefs.SORT_SMART || m == Prefs.SORT_RECENT;
    }

    /** Aplikace nainstalovana v poslednich 14 dnech a jeste nespustena = stitek "NOVE". */
    private static final long NEW_WINDOW_MS = 14L * 24 * 60 * 60 * 1000;

    private final AppRepository.Signals signals = new AppRepository.Signals() {
        @Override
        public long playtimeMs(String pkg) {
            return usage.playtimeMs(pkg);
        }

        @Override
        public long lastUsed(String pkg) {
            return Math.max(prefs.lastLaunch(pkg), usage.lastUsed(pkg));
        }

        @Override
        public long sizeBytes(String pkg) {
            return sizes.get(pkg);
        }

        @Override
        public boolean isNew(AppEntry e) {
            if (e.installTime <= 0 || e.isSystemPanel()) return false;
            if (System.currentTimeMillis() - e.installTime > NEW_WINDOW_MS) return false;
            if (prefs.lastLaunch(e.pkg) > 0 || prefs.launchCount(e.pkg) > 0) return false;
            // Spustena odjinud (knihovna Questu) po instalaci = uz neni nova.
            return usage.lastUsed(e.pkg) <= e.installTime + 60_000L;
        }
    };

    /** Spusteni z menu / hledani / rychleho menu (bez animace kukatka). */
    private void launchFromMenu(AppEntry app) {
        overlay.close();
        onLaunch(app);
        if (lastLaunchOk && prefs.closeAfterLaunch()) {
            launcher.postDelayed(() -> {
                if (!isFinishing()) finish();
            }, 450);
        }
    }

    private void requestUsageAccess() {
        if (UsageInfo.requestPermission(this)) {
            toast("Najdi Neo Launcher a povol mu přístup k využití");
        } else {
            toast("Nastavení není dostupné. Z PC: adb shell appops set "
                    + getPackageName() + " GET_USAGE_STATS allow");
        }
    }

    private final CarouselView.Stats carouselStats = new CarouselView.Stats() {
        @Override
        public boolean hasUsageAccess() {
            return usage.hasPermission();
        }

        @Override
        public long playtimeMs(String pkg) {
            return usage.playtimeMs(pkg);
        }

        @Override
        public long lastUsed(String pkg) {
            return Math.max(prefs.lastLaunch(pkg), usage.lastUsed(pkg));
        }

        @Override
        public int launchCount(String pkg) {
            return prefs.launchCount(pkg);
        }

        @Override
        public long installTime(String pkg) {
            return usage.installTime(pkg);
        }
    };

    private final CarouselView.Host carouselHost = new CarouselView.Host() {
        @Override
        public void onLaunch(AppEntry app) {
            LauncherActivity.this.onLaunch(app);
        }

        @Override
        public void onLaunchSequenceDone() {
            LauncherActivity.this.onLaunchSequenceDone();
        }

        @Override
        public void onAppMenu(AppEntry app, RectF cardRect) {
            LauncherActivity.this.onAppMenu(app, cardRect);
        }

        @Override
        public void onOpenSettings() {
            LauncherActivity.this.onOpenSettings();
        }

        @Override
        public void onOpenQuickMenu(RectF origin) {
            LauncherActivity.this.onOpenQuickMenu(origin);
        }

        @Override
        public void onExitCarousel() {
            prefs.setCarouselMode(false);
        }

        @Override
        public void onTabSelected(int tab) {
            LauncherActivity.this.onTabSelected(tab);
        }

        @Override
        public void onRequestUsageAccess() {
            requestUsageAccess();
        }
    };

    private List<String> labelsFor(List<AppEntry> list) {
        List<String> labels = new ArrayList<>(list.size());
        for (AppEntry e : list) labels.add(prefs.labelFor(e));
        return labels;
    }

    // --- NeoLauncherView.Host ---------------------------------------------------

    @Override
    public void onLaunch(AppEntry app) {
        prefs.markLaunched(app.pkg);
        carousel.statsChanged();
        lastLaunchOk = AppLauncher.launch(this, app);
    }

    @Override
    public void onLaunchSequenceDone() {
        // Launcher se zavre jen kdyz se aplikace opravdu spustila.
        if (lastLaunchOk && prefs.closeAfterLaunch() && !isFinishing()) finish();
    }

    @Override
    public void onOpenSettings() {
        // Panel "vyroste" z loga (mrizka) nebo z tlacitka nastaveni (karusel).
        final RectF from = carouselShown ? carousel.settingsRect() : launcher.settingsRect();
        overlay.show(SettingsSheet.build(this, prefs, repo, artwork,
                        () -> showApps(true), () -> checkForUpdates(true), this::showWhatsNew, overlay::close),
                null, from, launcher.frameRect(), Glass.dpi(this, 1000));
    }

    // --- Rychle menu (jas, hlasitost, funkce Questu) ----------------------------------

    @Override
    public void onOpenQuickMenu(RectF origin) {
        QuickMenuView menu = new QuickMenuView(this, new QuickMenuView.Actions() {
            @Override
            public void openTarget(int target) {
                openQuickTarget(target);
            }

            @Override
            public void requestBrightnessAccess() {
                requestWriteSettings();
            }

            @Override
            public void launch(AppEntry app) {
                launchFromMenu(app);
            }
        });
        menu.setBattery(batteryPct, batteryCharging, batteryFast);
        menu.setCastActive(com.neolauncher.cast.Cast.isRunning(this));
        menu.setBatteryInfo(batteryInfoText());
        final AppEntry last = lastPlayed();
        if (last != null) {
            final long t = signals.lastUsed(last.pkg);
            menu.setLastPlayed(last, prefs.labelFor(last),
                    (last.isVr() ? "Naposledy hráno · " : "Naposledy otevřeno · ")
                            + lowerFirst(CarouselView.formatLast(t).replace("Naposledy ", "")),
                    artwork.get(last));
        }
        // Rychle menu vyjede jako bocni panel vpravo (visionOS), pres celou vysku panelu.
        overlay.showSide(menu, launcher.frameRect(), QuickMenuView.sideWidth(this));
    }

    // --- Co je noveho --------------------------------------------------------------

    /** Po aktualizaci jednou ukaze novinky (ne pri ciste instalaci). */
    private void maybeShowWhatsNew() {
        if (isFinishing()) return;
        final long code = versionCode();
        final long seen = prefs.lastSeenVersion();
        if (code <= 0 || seen == code) return;
        // Starsi verze si "videnou" verzi neukladaly - kdo uz launcher mel (kontroloval
        // aktualizace), dostane novinky taky; cista instalace ne.
        final boolean updated = seen != 0 || prefs.lastUpdateCheck() > 0;
        prefs.setLastSeenVersion(code);
        if (updated && !overlay.isOpen()) showWhatsNew();
    }

    private void showWhatsNew() {
        String name = "2.0";
        try {
            name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        View v = WhatsNewSheet.build(this, name, () -> {
            overlay.close();
            // Po novinkach pripadne nabidnout aktualizaci doplnku (az dialog zmizi).
            launcher.postDelayed(this::maybePromptAddon, 500);
        });
        if (v != null) overlay.show(v, null, null, launcher.frameRect(), Glass.dpi(this, 620));
    }

    private long versionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    // --- Doplnek Meta tlacitka: aktualizace a povoleni ---------------------------------

    private static final int PROMPT_UPDATE = 1, PROMPT_USAGE = 2, PROMPT_NOTIFY = 4;

    /**
     * Jednou za verzi Nea nabidne, co doplnek potrebuje, aby novinky opravdu fungovaly:
     * novejsi verzi doplnku, Pristup k vyuziti (hra na pozadi) a oznameni (baterie).
     * Bez toho se opravy z Nea k uzivateli vubec nedostanou (doplnek se sam neaktualizuje).
     */
    private void maybePromptAddon() {
        if (isFinishing() || overlay.isOpen() || carouselShown || !MetaAddon.isInstalled(this)) return;
        final long code = versionCode();
        if (code <= 0) return;
        final int kind;
        final View v;
        if (MetaAddon.needsUpdate(this)) {
            kind = PROMPT_UPDATE;
            v = ConfirmSheet.build(this, Icons.META, "Nová verze Meta tlačítka",
                    "Lépe pozná hru na pozadí a menu Questu ve hře, upozorní na slabou baterii i ve hře "
                            + "a umí vypnout a restartovat Quest. Quest se zeptá, jestli doplněk aktualizovat.",
                    "Aktualizovat", "Později", this::installMetaAddon, overlay::close);
        } else if (!MetaAddon.isEnabled(this)) {
            return;
        } else if (!usage.hasPermission()) {
            kind = PROMPT_USAGE;
            v = ConfirmSheet.build(this, Icons.CHART, "Povol Neu přístup k využití",
                    "Bez něj Meta tlačítko nepozná, že běží hra, a Neo neukáže hru, která běží na pozadí. "
                            + "V seznamu klepni na Neo Launcher a zapni přístup.",
                    "Povolit", "Později", () -> {
                        overlay.close();
                        UsageInfo.requestPermission(this);
                    }, overlay::close);
        } else if (prefs.batteryAlerts() && !MetaAddon.canNotify(this)) {
            kind = PROMPT_NOTIFY;
            v = ConfirmSheet.build(this, Icons.ALERT, "Upozornění na baterii",
                    "Aby Neo mohlo upozornit na slabou baterii i ve hře, povol Meta tlačítku oznámení.",
                    "Povolit", "Později", () -> {
                        overlay.close();
                        MetaAddon.requestNotifications(this);
                    }, overlay::close);
        } else {
            return;
        }
        if (prefs.addonPrompted(code, kind)) return;
        prefs.setAddonPrompted(code, kind);
        overlay.show(v, null, null, launcher.frameRect(), Glass.dpi(this, 520));
    }

    // --- Hledani -----------------------------------------------------------------

    @Override
    public void onOpenSearch(RectF origin) {
        overlay.show(SearchSheet.build(this, new SearchSheet.Source() {
            @Override
            public List<AppEntry> apps() {
                final java.util.Set<String> hidden = prefs.hidden();
                List<AppEntry> out = new ArrayList<>();
                for (AppEntry e : repo.apps()) if (!hidden.contains(e.pkg)) out.add(e);
                return out;
            }

            @Override
            public String label(AppEntry e) {
                return prefs.labelFor(e);
            }

            @Override
            public long lastUsed(String pkg) {
                return signals.lastUsed(pkg);
            }

            @Override
            public android.graphics.Bitmap art(AppEntry e) {
                return artwork.get(e);
            }
        }, this::launchFromMenu, overlay::close), null, origin, launcher.frameRect(), Glass.dpi(this, 640));
    }

    private void toggleQuickMenu() {
        if (overlay.isOpen() && overlay.panel() instanceof QuickMenuView) {
            overlay.close();
        } else {
            onOpenQuickMenu(carouselShown ? carousel.statusRect() : launcher.statusRect());
        }
    }

    /** Tlacitko menu na ovladaci (pokud ho Quest do aplikace posila) = rychle menu. */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            toggleQuickMenu();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void openQuickTarget(int target) {
        // Otevreni systemu Questu z rychleho menu neni zmacknuti Meta tlacitka.
        MetaAddon.suppress(this, 4000);
        boolean ok;
        switch (target) {
            case QuickMenuView.T_WIFI:
                ok = startSettings(android.provider.Settings.ACTION_WIFI_SETTINGS)
                        || openPanel("systemux://settings");
                break;
            case QuickMenuView.T_BLUETOOTH:
                ok = startSettings(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                        || openPanel("systemux://settings");
                break;
            case QuickMenuView.T_ANDROID_SETTINGS:
                ok = AppLauncher.openAndroidSettings(this);
                break;
            case QuickMenuView.T_QUEST_QUICK:
                ok = openPanel("systemux://quick_settings");
                break;
            case QuickMenuView.T_FILES:
                ok = openPackage("com.oculus.systemutilities", "Soubory")
                        || openPanel("systemux://file-manager");
                break;
            case QuickMenuView.T_BROWSER:
                ok = openPackage("com.oculus.browser", "Prohlížeč");
                break;
            case QuickMenuView.T_CAST:
                // Streamovani do prohlizece (quest.jendovyapky.eu) - vlastni okno Nea.
                // Puvodni sdileni Questu je v nem pod tlacitkem "Sdileni Questu".
                showCastSheet();
                return;
            case QuickMenuView.T_SLEEP:
                overlay.close();
                if (!MetaAddon.sleep(this)) needMetaService();
                return;
            case QuickMenuView.T_POWER:
                showPowerSheet();
                return;
            default:
                onOpenSettings();
                return;
        }
        if (ok) overlay.close();
        else Toast.makeText(this, "Na tomto zařízení není k dispozici", Toast.LENGTH_SHORT).show();
    }

    /**
     * Okno Streamovat: obraz a zvuk Questu do prohlizece ve stejne Wi-Fi. Nahravani
     * bezi v procesu :cast (com.neolauncher.cast), takze pad streamovani Neo neshodi.
     */
    public void showCastSheet() {
        overlay.show(CastSheet.build(this, new CastSheet.Actions() {
            @Override
            public void start() {
                com.neolauncher.cast.Cast.start(LauncherActivity.this);
            }

            @Override
            public void stop() {
                com.neolauncher.cast.Cast.stop(LauncherActivity.this);
                showLive(Icons.CAST, "Streamování zastaveno", NeoLauncherView.LIVE_ICON_ONLY, 2500);
            }

            @Override
            public void systemSharing() {
                overlay.close();
                openSystemSharing();
            }

            @Override
            public void close() {
                overlay.close();
            }
        }), null, null, launcher.frameRect(), Glass.dpi(this, 560));
    }

    /**
     * Puvodni sdileni Questu (streamovani do TV / telefonu, nahravani, snimek): od Horizon OS v81
     * je to aplikace Fotoaparat (com.oculus.metacam), driv panel systemux://sharing
     * (Lightning Launcher to deli stejne). Jinak aspon rychle nastaveni Questu.
     */
    private void openSystemSharing() {
        MetaAddon.suppress(this, 4000);
        boolean ok = openPackage("com.oculus.metacam", "Fotoaparát")
                || (com.neolauncher.data.Platform.vrOsVersion() < 81 && openPanel("systemux://sharing"))
                || openPanel("systemux://quick_settings");
        if (!ok) Toast.makeText(this, "Na tomto zařízení není k dispozici", Toast.LENGTH_SHORT).show();
    }

    /**
     * Vlastni skleneny dialog Uspat / Restartovat / Vypnout (systemova nabidka Androidu
     * na Questu vypada spatne). Restart a vypnuti provede doplnek Meta tlacitka.
     */
    public void showPowerSheet() {
        if (!MetaAddon.isEnabled(this)) {
            overlay.close();
            needMetaService();
            return;
        }
        overlay.show(PowerSheet.build(this, new PowerSheet.Actions() {
            @Override
            public void power(int what) {
                overlay.close();
                final boolean ok;
                if (what == PowerSheet.SLEEP) {
                    ok = MetaAddon.sleep(LauncherActivity.this);
                } else {
                    ok = MetaAddon.power(LauncherActivity.this,
                            what == PowerSheet.RESTART ? MetaAddon.POWER_RESTART : MetaAddon.POWER_OFF);
                    if (ok) showLive(Icons.POWER, what == PowerSheet.RESTART ? "Restartuji…" : "Vypínám…",
                            NeoLauncherView.LIVE_SPINNER, 0);
                }
                if (!ok) needMetaService();
            }

            @Override
            public void cancel() {
                overlay.close();
            }
        }), null, null, launcher.frameRect(), Glass.dpi(this, 600));
    }

    /** Uspani / vypnuti umi jen sluzba Meta tlacitka (globalni akce pristupnosti). */
    private void needMetaService() {
        notice(Icons.ALERT, "Nejdřív nainstaluj a zapni Meta tlačítko (Nastavení → Quest)", Palette.MAGENTA, 5000);
    }

    /**
     * Nainstaluje pribaleny doplnek "Neo - Meta tlacitko" (Nastaveni -> Quest). Po instalaci
     * se otevre Pristupnost v Android nastaveni, kde ho uzivatel zapne.
     */
    public void installMetaAddon() {
        overlay.close();
        final boolean started = MetaAddon.install(this, new ApkInstaller.Listener() {
            @Override
            public void onProgress(float p) {
                showLive(Icons.META, "Připravuji Meta tlačítko…", NeoLauncherView.LIVE_SPINNER, 0);
            }

            @Override
            public void onCommitted() {
                showLive(Icons.META, "Potvrď instalaci Meta tlačítka", NeoLauncherView.LIVE_SPINNER, 0);
            }

            @Override
            public void onError(String message) {
                notice(Icons.ALERT, "Meta tlačítko se nepodařilo nainstalovat", Palette.MAGENTA, 5000);
            }
        });
        if (!started) notice(Icons.ALERT, "Doplněk v téhle verzi Nea chybí", Palette.MAGENTA, 5000);
    }

    /** Zapnout doplnek: s povolenim z PC rovnou, jinak Pristupnost v Android nastaveni. */
    public void enableMetaAddon() {
        overlay.close();
        if (MetaAddon.selfEnable(this)) {
            notice(Icons.CHECK, "Meta tlačítko zapnuto", Palette.COBALT_LIGHT, 3000);
            return;
        }
        if (MetaAddon.openSettings(this)) {
            Toast.makeText(this, "Klepni na „Neo – Meta tlačítko“ a zapni ho", Toast.LENGTH_LONG).show();
        } else {
            notice(Icons.ALERT, "Přístupnost nejde otevřít", Palette.MAGENTA, 5000);
        }
    }

    private boolean startSettings(String action) {
        try {
            Intent i = new Intent(action);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(getPackageManager()) == null) return false;
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean openPanel(String uri) {
        return AppLauncher.launch(this, new AppEntry(uri, uri, AppEntry.TYPE_PANEL, false));
    }

    private boolean openPackage(String pkg, String label) {
        if (getPackageManager().getLaunchIntentForPackage(pkg) == null) return false;
        return AppLauncher.launch(this, new AppEntry(pkg, label, AppEntry.TYPE_2D, false));
    }

    /** Povoleni "Uprava systemovych nastaveni" pro posuvnik jasu (jako QuestDim). */
    private void requestWriteSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(AppLauncher.inAndroidSettings(this, i));
            toast("Povol Neo Launcheru úpravu systémových nastavení");
        } catch (Exception e) {
            toast("Nastavení není dostupné. Z PC: adb shell appops set "
                    + getPackageName() + " WRITE_SETTINGS allow");
        }
    }

    @Override
    public void onAppMenu(AppEntry app, RectF cardRect) {
        final String label = prefs.labelFor(app);
        final boolean fav = prefs.isFavorite(app.pkg);
        overlay.show(AppMenu.build(this, app, label, AppSizes.format(sizes.get(app.pkg)),
                artwork.hasCustomImage(app.pkg), fav,
                new AppMenu.Actions() {
                    @Override
                    public void launch() {
                        launchFromMenu(app);
                    }

                    @Override
                    public void favorite() {
                        overlay.close();
                        prefs.setFavorite(app.pkg, !fav);
                        showApps(true);
                        notice(Icons.STAR, fav ? "Odebráno z oblíbených" : "Přidáno do oblíbených – drží se nahoře",
                                Palette.AMBER, 2800);
                    }

                    @Override
                    public void rename() {
                        showRename(app);
                    }

                    @Override
                    public void pickImage() {
                        overlay.close();
                        pendingImagePkg = app.pkg;
                        try {
                            //noinspection deprecation
                            startActivityForResult(AppMenu.pickImageIntent(), REQ_PICK_IMAGE);
                        } catch (Exception e) {
                            Toast.makeText(LauncherActivity.this,
                                    "Výběr souboru není k dispozici", Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void removeImage() {
                        overlay.close();
                        artwork.removeCustomImage(app);
                    }

                    @Override
                    public void reloadImage() {
                        overlay.close();
                        artwork.reload(app);
                        notice(Icons.REFRESH, "Obrázek se stahuje znovu", Palette.PEARL, 2500);
                    }

                    @Override
                    public void hide() {
                        overlay.close();
                        prefs.setHidden(app.pkg, true);
                        showApps(true);
                        notice(Icons.EYE_OFF, "Skryto · zpět v Nastavení → Skryté aplikace", Palette.PEARL, 4000);
                    }

                    @Override
                    public void info() {
                        overlay.close();
                        AppLauncher.openAppInfo(LauncherActivity.this, app.pkg);
                    }

                    @Override
                    public void uninstall() {
                        overlay.close();
                        AppLauncher.uninstall(LauncherActivity.this, app.pkg);
                    }
                }), cardRect, launcher.frameRect(), Glass.dpi(this, 300));
    }

    private void showRename(AppEntry app) {
        overlay.show(AppMenu.rename(this, prefs.labelFor(app), app.systemLabel, name -> {
            hideKeyboard();
            prefs.setCustomLabel(app.pkg, name);
            overlay.close();
            showApps(true);
        }, () -> {
            hideKeyboard();
            overlay.close();
        }), null, launcher.frameRect(), Glass.dpi(this, 440));
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
    }

    @Override
    public void onTabSelected(int tab) {
        prefs.setTab(tab);
        List<AppEntry> list = repo.forTab(tab);
        List<String> labels = labelsFor(list);
        launcher.showTab(tab, list, labels);
        carousel.setApps(list, labels, tab);
    }

    @Override
    public void onOrderChanged(int tab, List<String> pkgs) {
        prefs.setManualOrder(tab, pkgs);
        if (prefs.sortMode() != Prefs.SORT_MANUAL) {
            prefs.setSortMode(Prefs.SORT_MANUAL);
            notice(Icons.SORT, "Řazení přepnuto na vlastní pořadí", Palette.PEARL, 3000);
        }
    }

    // --- Aktualizace z GitHubu ----------------------------------------------------

    private void checkForUpdates(boolean manual) {
        final long now = System.currentTimeMillis();
        if (!manual && now - prefs.lastUpdateCheck() < UPDATE_CHECK_INTERVAL_MS) return;
        prefs.setLastUpdateCheck(now);
        if (manual) Toast.makeText(this, "Hledám aktualizace…", Toast.LENGTH_SHORT).show();
        Updater.check(this, prefs.updateTestBuilds(), (status, release) -> {
            if (isFinishing() || isDestroyed()) return;
            switch (status) {
                case AVAILABLE:
                    if (manual || release.versionCode != prefs.dismissedUpdate()) showUpdate(release);
                    break;
                case UP_TO_DATE:
                    if (manual) toast("Máš nejnovější verzi");
                    break;
                case NOT_PUBLIC:
                    if (manual) toast("Aktualizace nejsou dostupné – repozitář na GitHubu je soukromý");
                    break;
                default:
                    if (manual) toast("GitHub není dostupný – zkus to později");
                    break;
            }
        });
    }

    private void showUpdate(Updater.Release release) {
        String current = "?";
        try {
            current = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        overlay.show(UpdateSheet.build(this, current, release, new UpdateSheet.Actions() {
            @Override
            public void update(TextView status, View buttons) {
                buttons.setVisibility(View.GONE);
                status.setText("Stahuji…");
                Updater.download(LauncherActivity.this, release, new Updater.DownloadCallback() {
                    @Override
                    public void onProgress(int percent) {
                        status.setText("Stahuji… " + percent + " %");
                        showLive(Icons.DOWNLOAD, "Stahuji Neo · " + percent + " %", percent / 100f, 0);
                    }

                    @Override
                    public void onDone(File apk) {
                        status.setText("Instaluji – potvrď prosím systémový dialog.");
                        showLive(Icons.DOWNLOAD, "Potvrď instalaci Nea", NeoLauncherView.LIVE_SPINNER, 0);
                        try {
                            Updater.install(LauncherActivity.this, apk);
                        } catch (Exception e) {
                            status.setText("Instalace se nepodařila spustit.");
                            buttons.setVisibility(View.VISIBLE);
                        }
                    }

                    @Override
                    public void onError(String message) {
                        status.setText(message);
                        buttons.setVisibility(View.VISIBLE);
                        launcher.clearLive();
                    }
                });
            }

            @Override
            public void later() {
                prefs.setDismissedUpdate(release.versionCode);
                overlay.close();
            }
        }), null, launcher.frameRect(), Glass.dpi(this, 520));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    // --- Ziva bublina v horni liste ------------------------------------------------

    /**
     * Prubeh (instalace, stahovani...) v zive bubline horni listy.
     * @return false, kdyz ji ted neni videt (karusel, Neo neni na ocich)
     */
    private boolean showLive(int icon, String text, float progress, long ms) {
        return showLive(icon, text, Palette.COBALT_LIGHT, progress, ms);
    }

    private boolean showLive(int icon, String text, int color, float progress, long ms) {
        if (carouselShown) return false;
        launcher.showLive(icon, text, color, progress, ms);
        return sVisible;
    }

    /** Kratke oznameni: v bubline, a kdyz neni videt, obycejna hlaska. */
    private void notice(int icon, String text, int color, long ms) {
        if (!showLive(icon, text, color, NeoLauncherView.LIVE_ICON_ONLY, ms)) {
            Toast.makeText(this, text, ms > 3000 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
        }
    }

    /** Vysledek instalace (APK vybrane v Neu i aktualizace Nea) do bubliny. */
    private final InstallReceiver.Listener installListener = (kind, status, label, message) -> {
        final boolean addon = ApkInstaller.KIND_ADDON.equals(kind);
        final boolean apk = kind != null && !addon;
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                showLive(apk ? Icons.PACKAGE_PLUS : addon ? Icons.META : Icons.DOWNLOAD,
                        apk ? "Potvrď instalaci" : addon ? "Potvrď instalaci Meta tlačítka" : "Potvrď instalaci Nea",
                        NeoLauncherView.LIVE_SPINNER, 0);
                return true;
            case PackageInstaller.STATUS_SUCCESS:
                if (addon) {
                    if (MetaAddon.isEnabled(this)) {
                        // Aktualizace - sluzba zustava zapnuta. Pripadne jeste povoleni oznameni.
                        showLive(Icons.CHECK, "Meta tlačítko je aktuální", Palette.COBALT_LIGHT,
                                NeoLauncherView.LIVE_ICON_ONLY, 3500);
                        if (prefs.batteryAlerts() && !MetaAddon.canNotify(this)) {
                            launcher.postDelayed(() -> MetaAddon.requestNotifications(this), 1200);
                        }
                        return true;
                    }
                    // Doplnek je nainstalovany -> rovnou ho zapnout (Pristupnost).
                    showLive(Icons.CHECK, "Meta tlačítko nainstalováno – teď ho zapni", Palette.COBALT_LIGHT,
                            NeoLauncherView.LIVE_ICON_ONLY, 4000);
                    launcher.postDelayed(this::enableMetaAddon, 1200);
                    return true;
                }
                return showLive(Icons.CHECK, "Nainstalováno: " + label, Palette.COBALT_LIGHT,
                        NeoLauncherView.LIVE_ICON_ONLY, 4500);
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                // Uzivatel instalaci zrusil - jen schovat bublinu.
                launcher.clearLive();
                return true;
            default:
                return showLive(Icons.ALERT, addon ? "Meta tlačítko se nenainstalovalo"
                                : apk ? "Aplikace se nenainstalovala" : "Aktualizace se nenainstalovala",
                        Palette.MAGENTA, NeoLauncherView.LIVE_ICON_ONLY, 5000);
        }
    };

    // --- OverlayHost.Listener ---------------------------------------------------

    @Override
    public void onOverlayShown() {
        launcher.setInteractive(false);
        launcher.setModalBlur(true);
        carousel.setInteractive(false);
        carousel.setModalBlur(true);
    }

    @Override
    public void onOverlayClosed() {
        // Klavesnice (hledani, prejmenovani) po zavreni dialogu pryc.
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
        launcher.setModalBlur(false);
        launcher.setInteractive(true);
        carousel.setModalBlur(false);
        carousel.setInteractive(true);
    }

    // --- Vlastni obrazek ----------------------------------------------------------

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        final Uri picked = resultCode == RESULT_OK && data != null ? data.getData() : null;
        if (requestCode == REQ_PICK_APK) {
            if (picked == null) return;
            if (!showLive(Icons.PACKAGE_PLUS, "Připravuji instalaci…", 0f, 0)) {
                Toast.makeText(this, "Připravuji instalaci…", Toast.LENGTH_SHORT).show();
            }
            ApkInstaller.install(this, picked, new ApkInstaller.Listener() {
                @Override
                public void onProgress(float p) {
                    showLive(Icons.PACKAGE_PLUS, p >= 0f ? "Připravuji instalaci · " + Math.round(p * 100) + " %"
                            : "Připravuji instalaci…", p >= 0f ? p : NeoLauncherView.LIVE_SPINNER, 0);
                }

                @Override
                public void onCommitted() {
                    showLive(Icons.PACKAGE_PLUS, "Potvrď instalaci", NeoLauncherView.LIVE_SPINNER, 0);
                }

                @Override
                public void onError(String message) {
                    notice(Icons.ALERT, message, Palette.MAGENTA, 5000);
                }
            });
            return;
        }
        if (requestCode == REQ_EXPORT || requestCode == REQ_IMPORT) {
            if (picked != null) onBackupFile(requestCode == REQ_EXPORT, picked);
            return;
        }
        if (requestCode != REQ_PICK_IMAGE) return;
        final String pkg = pendingImagePkg;
        pendingImagePkg = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || pkg == null) return;
        AppEntry e = repo.find(pkg);
        if (e == null) return;
        Uri uri = data.getData();
        artwork.setCustomImage(e, uri, () -> notice(Icons.CHECK, "Obrázek nastaven", Palette.COBALT_LIGHT, 2500));
    }

    // --- Zaloha nastaveni ---------------------------------------------------------

    /** Vyber, kam ulozit zalohu (Nastaveni -> O Neo). */
    public static Intent backupExportIntent() {
        return new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip").putExtra(Intent.EXTRA_TITLE, Backup.fileName());
    }

    /** Vyber zalohy k obnoveni. */
    public static Intent backupImportIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[]{"application/zip", "application/octet-stream"});
    }

    private void onBackupFile(boolean export, Uri uri) {
        if (export) {
            showLive(Icons.ARCHIVE, "Ukládám zálohu…", NeoLauncherView.LIVE_SPINNER, 0);
            Backup.export(this, uri, err -> {
                if (err != null) notice(Icons.ALERT, err, Palette.MAGENTA, 5000);
                else notice(Icons.CHECK, "Záloha uložena", Palette.COBALT_LIGHT, 3500);
            });
            return;
        }
        showLive(Icons.ARCHIVE_RESTORE, "Obnovuji zálohu…", NeoLauncherView.LIVE_SPINNER, 0);
        Backup.restore(this, uri, err -> {
            if (err != null) {
                notice(Icons.ALERT, err, Palette.MAGENTA, 5000);
                return;
            }
            // Vse znovu nacist: nastaveni, obrazky, poradi.
            prefs.reloadAfterRestore();
            artwork.clearMemory();
            overlay.close();
            showApps(true);
            notice(Icons.CHECK, "Nastavení obnoveno ze zálohy", Palette.COBALT_LIGHT, 3500);
        });
    }

    // --- Baterie, hodiny, zmeny balicku ---------------------------------------------

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            onBattery(i);
        }
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            launcher.onClockTick();
            carousel.onClockTick();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            repo.refreshAsync();
        }
    };

    private void registerReceivers() {
        if (receiversRegistered) return;
        receiversRegistered = true;
        Intent sticky = register(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) onBattery(sticky);

        IntentFilter time = new IntentFilter();
        time.addAction(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        register(timeReceiver, time);

        IntentFilter pkg = new IntentFilter();
        pkg.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkg.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkg.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkg.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pkg.addDataScheme("package");
        register(packageReceiver, pkg);
    }

    private Intent register(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) {
            return registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        }
        return registerReceiver(r, f);
    }

    private void unregisterReceivers() {
        if (!receiversRegistered) return;
        receiversRegistered = false;
        for (BroadcastReceiver r : new BroadcastReceiver[]{batteryReceiver, timeReceiver, packageReceiver}) {
            try {
                unregisterReceiver(r);
            } catch (Exception ignored) {
            }
        }
    }

    private void onBattery(Intent i) {
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int pct = level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        boolean charging = plugged != 0 && (status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
        final boolean fast = charging && isFastCharging(i);
        announceBattery(pct, charging, fast);
        batteryPct = pct;
        batteryCharging = charging;
        batteryFast = fast;
        battery.sample(charging);
        launcher.setBattery(pct, charging, fast);
        carousel.setBattery(pct, charging, fast);
        if (overlay.panel() instanceof QuickMenuView) {
            ((QuickMenuView) overlay.panel()).setBattery(pct, charging, fast);
            ((QuickMenuView) overlay.panel()).setBatteryInfo(batteryInfoText());
        }
    }

    /** Zmena nabijeni do zive bubliny: zacalo nabijeni, nabito, slaba baterie. */
    private void announceBattery(int pct, boolean charging, boolean fast) {
        final int prev = batteryPct;
        if (prev < 0 || pct < 0) return; // prvni hodnota po startu - nic se nezmenilo
        if (charging && !batteryCharging) {
            String t = (fast ? "Rychle nabíjí · " : "Nabíjí se · ") + pct + " %";
            final String full = chargeTimeText();
            if (full != null) t += " · plně za " + full;
            showLive(Icons.BATTERY_CHARGING, t, Palette.PEARL, NeoLauncherView.LIVE_ICON_ONLY, 4000);
        } else if (charging && pct >= 100 && prev < 100) {
            showLive(Icons.CHECK, "Nabito na 100 %", Palette.COBALT_LIGHT, NeoLauncherView.LIVE_ICON_ONLY, 4000);
        } else if (!charging && ((pct <= 20 && prev > 20) || (pct <= 10 && prev > 10))) {
            final String left = BatteryEstimate.format(battery.minutesLeft(pct, false));
            showLive(Icons.ALERT, "Slabá baterie · " + pct + " %" + (left != null ? " · asi " + left : ""),
                    Palette.MAGENTA, NeoLauncherView.LIVE_ICON_ONLY, 5000);
        }
    }

    /** Pod hodinami rychleho menu: jak dlouho baterie vydrzi / za jak dlouho bude nabito. */
    private String batteryInfoText() {
        if (batteryCharging) {
            final String full = chargeTimeText();
            return batteryPct >= 100 ? "nabito" : full != null ? "nabito za " + full : null;
        }
        final String left = BatteryEstimate.format(battery.minutesLeft(batteryPct, false));
        return left != null ? "vydrží asi " + left : null;
    }

    /** Odhad systemu, za jak dlouho bude nabito ("1 h 5 min"), nebo null. */
    private String chargeTimeText() {
        try {
            final BatteryManager bm = getSystemService(BatteryManager.class);
            final long ms = bm != null ? bm.computeChargeTimeRemaining() : -1;
            if (ms < 60_000L) return null;
            final long min = (ms + 59_999L) / 60_000L;
            return min >= 60 ? (min / 60) + " h " + (min % 60) + " min" : min + " min";
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Rychlonabijeni stejne jako v Android SystemUI: maximalni vykon nabijecky
     * nad 7.5 W. Zaloha: okamzity proud nad 1.5 A.
     */
    private boolean isFastCharging(Intent i) {
        int microAmp = i.getIntExtra("max_charging_current", -1);
        int microVolt = i.getIntExtra("max_charging_voltage", -1);
        if (microVolt <= 0) microVolt = 5_000_000;
        if (microAmp > 0) {
            long microWatt = (long) (microAmp / 1000) * (microVolt / 1000);
            if (microWatt > 7_500_000L) return true;
        }
        try {
            BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                int now = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                return Math.abs(now) > 1_500_000 && now != Integer.MIN_VALUE;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
