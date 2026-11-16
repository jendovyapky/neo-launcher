package com.neolauncher.meta;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Meta tlacitko otevre Neo - sluzba pristupnosti v samostatnem doplnku (jako
 * RedirectServices u Lightning Launcheru). Neo ho instaluje samo, takze ho
 * Android bere jako normalne nainstalovanou aplikaci a jde zapnout v Pristupnosti
 * bez "Omezeneho nastaveni".
 * <p>
 * Meta tlacitko samo do aplikaci nechodi. Pozname ho jako Lightning Launcher:
 * otevre se okno Knihovny Questu (com.oculus.panelapp.library) s titulkem
 * "Knihovna" / "Navigator" (lokalizovane). Navic: systemux (starsi Quest)
 * a Navigator na jine zalozce podle tlacitka Knihovna uvnitr okna.
 * <p>
 * Pravidla (prani uzivatele):
 * <ul>
 * <li>Doma: Meta = Neo.</li>
 * <li>Kdyz uz je Neo otevrene: Meta = opravdove menu Questu (k nastaveni Questu se da vzdy dostat).</li>
 * <li>Ve VR hre: menu Questu (Pokracovat / Ukoncit), ne Neo pres hru (volba v Neu).</li>
 * <li>Po skonceni hry se otevre Neo (volba).</li>
 * <li>Po zapnuti Questu se otevre Neo (volba) - sluzbu system pripoji hned po startu.</li>
 * <li>3x Meta ve hre/aplikaci: otevre Neo a dole nabidne Pokracovat / Ukoncit (volba).
 *     Znovu 3x Meta v Neu = zpet do hry.</li>
 * </ul>
 * Navic umi globalni akce, ktere obycejna aplikace nesmi (na prikaz z Nea, viz
 * CommandReceiver): uspat headset, vypnout / restartovat (otevre systemovou nabidku
 * vypnuti a sama v ni klepne) a "Ukoncit" bezici aplikaci (klepne v Informacich
 * o aplikaci na Vynutit ukonceni). A protoze bezi porad (i ve hre), hlida baterii
 * a upozorni na slabou baterii / nabito (Alerts).
 * Vse jen heuristika z udalosti oken - loguje se pod tagem "NeoMeta"
 * (adb -P 5038 logcat -s NeoMeta), at jde na headsetu doladit.
 */
public class MetaService extends AccessibilityService {
    static final String TAG = "NeoMeta";
    private static final String SYSTEMUX = "com.oculus.systemux";
    /** Okno Knihovny / Navigatoru na novejsim Questu (tady ho hleda Lightning Launcher). */
    private static final String LIBRARY_PANEL = "com.oculus.panelapp.library";
    private static final String ANDROID_SETTINGS = "com.android.settings";
    /** Domovske prostredi Questu - kdyz se objevi po hre, hra skoncila. */
    private static final Set<String> HOME = new HashSet<>(Arrays.asList(
            "com.oculus.vrshell", "com.oculus.shellenv"));
    /** Titulky Navigatoru / Knihovny (jako v RedirectServices z Lightning Launcheru, ruzne jazyky). */
    private static final Set<String> NAV_TITLES = lower(
            "Library", "Navigator", "App Library", "Knihovna", "Knihovna aplikací", "Navigátor",
            "Bibliothek", "App-Bibliothek", "Bibliothèque", "Navigateur", "Biblioteca", "Navegador",
            "Explorador", "Libreria", "Navigatore", "Biblioteka", "Nawigator", "Библиотека", "Навигатор",
            "ライブラリ", "ナビゲーター", "라이브러리", "내비게이터", "资源库", "导航工具", "資料庫",
            "導覽小幫手", "Bibliotek", "Navigatorn", "Kirjasto");
    /** Popisky tlacitka Knihovna uvnitr Navigatoru (Navigator otevreny na jine zalozce). */
    private static final Set<String> LIBRARY_LABELS = lower(
            "Library", "App Library", "Apps Library", "Knihovna", "Knihovna aplikací", "Bibliothek",
            "Bibliothèque", "Biblioteca", "Libreria", "Biblioteka", "Библиотека", "ライブラリ",
            "라이브러리", "资源库", "資料庫", "Bibliotek");
    private static final int NODE_BUDGET = 400;
    /** Po otevreni Nea chvili nereagovat (Navigator muze poslat vic udalosti). */
    private static final long LAUNCH_COOLDOWN_MS = 1500;
    /** Hra musi byt v popredi aspon takhle dlouho, aby se "po hre" otevrelo Neo. */
    private static final long MIN_GAME_MS = 4000;
    /**
     * Udalost domova neni jista: menu Questu ve hre posila taky udalost vrshell.
     * Proto se "hra skoncila" rozhodne az za chvili podle UsageStats z Nea.
     */
    private static final long AFTER_GAME_CHECK_MS = 1500;
    /** Sluzba pripojena do takove doby od zapnuti = start Questu (ne zapnuti sluzby / aktualizace). */
    private static final long BOOT_WINDOW_MS = 10 * 60 * 1000L;
    /** Po prvnim domovskem okne chvili pockat, az se prostredi Questu nacte. */
    private static final long BOOT_DELAY_MS = 2500;
    /** Kdyby po startu zadne domovske okno neprislo, otevrit Neo i tak. */
    private static final long BOOT_FALLBACK_MS = 25_000;
    /**
     * 3x Meta: kazde zmacknuti, ktere otevre menu Questu, posle davku udalosti
     * (vic udalosti do BURST_MS = jedno otevreni). Otevrit - zavrit - otevrit
     * = dve davky do TRIPLE_WINDOW_MS.
     */
    private static final long BURST_MS = 350;
    private static final long TRIPLE_WINDOW_MS = 2200;
    /**
     * Navigator otevreny kolem udalosti domova = menu Questu pres hru (Quest pri nem
     * posila i udalost domova a hru muze zastavit), ne konec hry.
     */
    private static final long NAV_NEAR_HOME_MS = 1500;
    /** Vynutit ukonceni: jak dlouho zkouset klepat v Informacich o aplikaci. */
    private static final long STOP_TIMEOUT_MS = 8000;
    private static final long STOP_POLL_MS = 150;
    /** Nabidka vypnuti: jak dlouho hledat polozku Vypnout / Restartovat. */
    private static final long POWER_TIMEOUT_MS = 4000;
    static final String POWER_OFF = "off";
    static final String POWER_RESTART = "restart";
    private static final Set<String> POWER_OFF_LABELS = lower(
            "Power off", "Shut down", "Shutdown", "Turn off", "Vypnout", "Ausschalten", "Herunterfahren",
            "Éteindre", "Apagar", "Spegni", "Wyłącz", "Выключить", "電源を切る", "전원 끄기", "关机",
            "關機", "Desligar", "Stäng av", "Sammuta", "Slå av", "Sluk");
    private static final Set<String> RESTART_LABELS = lower(
            "Restart", "Reboot", "Restartovat", "Neu starten", "Neustart", "Redémarrer", "Reiniciar",
            "Riavvia", "Uruchom ponownie", "Перезагрузить", "Перезапустить", "再起動", "다시 시작",
            "重新启动", "重新啟動", "重启", "Starta om", "Käynnistä uudelleen", "Start på nytt", "Genstart");
    /** Tlacitko "Vynutit ukonceni" v ruznych jazycich (Informace o aplikaci v Androidu). */
    private static final Set<String> FORCE_STOP_LABELS = lower(
            "Force stop", "Force Stop", "Vynutit ukončení", "Beenden erzwingen", "Stoppen erzwingen",
            "Forcer l'arrêt", "Forzar detención", "Forzar cierre", "Forza interruzione",
            "Wymuś zatrzymanie", "Остановить", "Принудительно остановить", "強制停止", "강제 중지",
            "强行停止", "Forçar parada", "Forçar paragem", "Tvinga stopp", "Pakota lopetus");

    private static volatile MetaService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Boolean> vrCache = new HashMap<>();
    private final Map<String, Boolean> launchCache = new HashMap<>();
    private long suppressUntil;
    /** Aplikace, ze ktere se odeslo do Nea (bezi na pozadi) - lista Pokracovat / Ukoncit v Neu. */
    private String runningPkg;
    private String fgPkg;
    private boolean fgVr;
    private long fgSince;
    private long lastLaunch;
    private Runnable pendingAfterGame;
    private boolean bootPending;
    private final Runnable bootLaunch = this::onBootLaunch;
    private long lastMenuEvent;
    /** Kdy prisla posledni udalost domova (vrshell) a posledni otevreni Navigatoru. */
    private long homeAt, lastNavigatorAt;
    /** Uz obslouzena skoncena hra (at se "po hre" neotevre dvakrat). */
    private String endedHandled;
    private long firstOpen;
    private int opens;
    private String stopPkg;
    private long stopDeadline;
    private boolean stopConfirming;
    private final Runnable stopPoll = this::pollForceStop;
    private Set<String> powerLabels;
    private long powerStart;
    private final Runnable powerPoll = this::pollPower;
    /** Baterie pro upozorneni (i kdyz Neo neni otevrene). */
    private int battPct = -1;
    private boolean batteryRegistered;
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            onBattery(i);
        }
    };

    /** Bezici instance (null = sluzba neni zapnuta). Pro CommandReceiver. */
    static MetaService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "Sluzba Meta tlacitka bezi");
        registerBattery();
        final Neo.State s = Neo.state(this);
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS && (s == null || s.openOnBoot)) {
            // Quest se prave zapnul -> az se ukaze domov, otevrit Neo.
            bootPending = true;
            handler.postDelayed(bootLaunch, BOOT_FALLBACK_MS);
            Log.i(TAG, "Start Questu, po nacteni domova otevru Neo");
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        unregisterBattery();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        handler.removeCallbacksAndMessages(null);
        unregisterBattery();
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        final CharSequence p = e.getPackageName();
        if (p == null) return;
        final String pkg = p.toString();
        final long now = SystemClock.uptimeMillis();
        Log.d(TAG, "Okno: " + pkg + " " + e.getClassName() + " " + e.getText());
        if (bootPending) onBootEvent(pkg);
        if (SYSTEMUX.equals(pkg) || LIBRARY_PANEL.equals(pkg)) {
            onMenuWindow(e, pkg, now);
        } else if (HOME.contains(pkg)) {
            onHome(now);
        } else if (!isTransient(pkg)) {
            onForeground(pkg, now);
        }
    }

    // --- Prikazy z Nea (CommandReceiver) -------------------------------------------

    /** Neo samo otevira system Questu (Nastaveni, Menu Questu...) - chvili to nebrat jako Meta tlacitko. */
    void suppress(long ms) {
        suppressUntil = SystemClock.uptimeMillis() + ms;
    }

    void clearRunning() {
        runningPkg = null;
    }

    boolean sleep() {
        return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    /**
     * Systemova nabidka vypnuti. what = POWER_OFF / POWER_RESTART: v nabidce sama
     * klepne na Vypnout / Restartovat (uzivatel vybiral uz v okne Nea). Kdyby se
     * polozka nenasla, nabidka zustane otevrena.
     */
    boolean powerMenu(String what) {
        final boolean ok = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
        Log.i(TAG, "Nabidka vypnuti" + (what != null ? " -> " + what : "") + (ok ? "" : " se neotevrela"));
        if (!ok || what == null) return ok;
        powerLabels = POWER_RESTART.equals(what) ? RESTART_LABELS : POWER_OFF_LABELS;
        powerStart = SystemClock.uptimeMillis();
        handler.removeCallbacks(powerPoll);
        handler.postDelayed(powerPoll, 300);
        return true;
    }

    private void pollPower() {
        if (powerLabels == null) return;
        final long t = SystemClock.uptimeMillis() - powerStart;
        if (t > POWER_TIMEOUT_MS) {
            Log.w(TAG, "V nabidce vypnuti se polozka nenasla - necham ji otevrenou");
            powerLabels = null;
            return;
        }
        try {
            // Nejdriv jen okna systemu (nabidka je v SystemUI), po chvili vsechna.
            final AccessibilityNodeInfo hit = findInWindows(powerLabels, t > POWER_TIMEOUT_MS / 2);
            if (hit != null && hit.isEnabled() && click(hit)) {
                Log.i(TAG, "Nabidka vypnuti: klepnuto na " + hit.getText());
                powerLabels = null;
                return;
            }
        } catch (Exception ex) {
            Log.w(TAG, "Chyba v nabidce vypnuti", ex);
        }
        handler.postDelayed(powerPoll, STOP_POLL_MS);
    }

    /** Uzel s popiskem ve vsech oknech na obrazovce (flagRetrieveInteractiveWindows). */
    private AccessibilityNodeInfo findInWindows(Set<String> labels, boolean anyWindow) {
        final List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                final AccessibilityNodeInfo r = w.getRoot();
                if (r != null) roots.add(r);
            }
        } catch (Exception ignored) {
        }
        final AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) roots.add(active);
        for (AccessibilityNodeInfo r : roots) {
            final CharSequence p = r.getPackageName();
            final boolean system = p != null && (p.toString().contains("systemui") || "android".contentEquals(p));
            if (!system && !anyWindow) continue;
            final AccessibilityNodeInfo hit = findLabel(r, labels, new int[]{NODE_BUDGET}, 0);
            if (hit != null) return hit;
        }
        return null;
    }

    // --- Baterie (upozorneni i ve hre) --------------------------------------------

    private void registerBattery() {
        if (batteryRegistered) return;
        try {
            final IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(batteryReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(batteryReceiver, f);
            batteryRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "Baterii nejde hlidat", e);
        }
    }

    private void unregisterBattery() {
        if (!batteryRegistered) return;
        batteryRegistered = false;
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception ignored) {
        }
    }

    /** Slaba baterie (20 %, 10 %) pri vybijeni a nabito na 100 % - jako Quest Game Optimizer. */
    private void onBattery(Intent i) {
        final int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        final int pct = level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
        final int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        final boolean charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                && (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
        final int prev = battPct;
        battPct = pct;
        if (prev < 0 || pct < 0 || pct == prev) return; // prvni hodnota po pripojeni sluzby
        final int alert;
        if (charging) {
            alert = pct >= 100 && prev < 100 ? Alerts.FULL : 0;
        } else if (pct <= 10 && prev > 10) {
            alert = Alerts.LOW_10;
        } else if (pct <= 20 && prev > 20) {
            alert = Alerts.LOW_20;
        } else {
            alert = 0;
        }
        if (alert == 0) return;
        final Neo.State s = Neo.state(this);
        if (s != null && !s.batteryAlerts) return;
        if (s != null && s.visible) {
            Log.i(TAG, "Baterie " + pct + " %, Neo je otevrene - ukaze to samo");
            return;
        }
        Alerts.battery(this, alert, pct);
    }

    // --- Start Questu -----------------------------------------------------------

    private void onBootEvent(String pkg) {
        if (HOME.contains(pkg) || SYSTEMUX.equals(pkg) || LIBRARY_PANEL.equals(pkg)) {
            // Domov je nacteny -> za chvilku Neo.
            bootPending = false;
            handler.removeCallbacks(bootLaunch);
            handler.postDelayed(bootLaunch, BOOT_DELAY_MS);
        } else if (pkg.equals(Neo.PKG) || isLaunchable(pkg)) {
            // Uzivatel uz neco spustil (nebo je Neo otevrene) - nerusit.
            // Systemove veci po startu (Guardian, zamykaci obrazovka...) se nepocitaji.
            bootPending = false;
            handler.removeCallbacks(bootLaunch);
            Log.i(TAG, "Po startu uz bezi " + pkg + ", Neo neotviram");
        }
    }

    private void onBootLaunch() {
        bootPending = false;
        final Neo.State s = Neo.state(this);
        if (s == null || !s.openOnBoot || s.visible) return;
        if (s.usageKnown ? s.topPkg != null : fgPkg != null && isLaunchable(fgPkg)) return;
        launchNeo("po zapnuti Questu", null);
    }

    /** Aplikace, kterou jde spustit (hra, aplikace) - ne systemovy prekryv Questu. */
    private boolean isLaunchable(String pkg) {
        final Boolean cached = launchCache.get(pkg);
        if (cached != null) return cached;
        boolean ok;
        try {
            ok = getPackageManager().getLaunchIntentForPackage(pkg) != null;
        } catch (Exception e) {
            ok = false;
        }
        launchCache.put(pkg, ok);
        return ok;
    }

    // --- Aplikace v popredi ---------------------------------------------------

    private void onForeground(String pkg, long now) {
        if (pkg.equals(fgPkg)) return;
        if (!pkg.equals(Neo.PKG) && !isLaunchable(pkg) && !isVrApp(pkg)) {
            // Systemovy prekryv Questu (Guardian, oznameni, panely...) - hra porad bezi.
            Log.d(TAG, "Prekryv " + pkg + ", popredi zustava " + fgPkg);
            return;
        }
        // Hra se vratila (napr. Pokracovat v menu) - "po hre" uz neplati.
        cancelAfterGame();
        if (runningPkg != null && !pkg.equals(Neo.PKG) && isLaunchable(pkg)) {
            // Bezici aplikace je zase v popredi (Pokracovat), nebo se spustila jina VR hra
            // (Quest drzi jen jednu) -> lista v Neu uz neplati.
            if (pkg.equals(runningPkg) || (isVrApp(pkg) && isVrApp(runningPkg))) runningPkg = null;
        }
        fgPkg = pkg;
        fgSince = now;
        fgVr = !pkg.equals(Neo.PKG) && isVrApp(pkg);
        Log.d(TAG, "Popredi: " + pkg + (fgVr ? " (VR hra)" : ""));
    }

    private void onHome(long now) {
        Log.d(TAG, "Domovske prostredi, predtim " + fgPkg);
        homeAt = now;
        // Nemazat hned popredi: menu Questu ve hre posila taky udalost domova. Za chvili
        // se overi, jestli hra opravdu skoncila (UsageStats v Neu), a pak pripadne Neo.
        if (pendingAfterGame == null) {
            final boolean wasGame = fgVr && now - fgSince > MIN_GAME_MS;
            pendingAfterGame = () -> {
                pendingAfterGame = null;
                checkAfterGame(wasGame);
            };
            handler.postDelayed(pendingAfterGame, AFTER_GAME_CHECK_MS);
        }
    }

    /** Skoncila hra (a ne jen menu Questu pres hru)? Pak "po hre otevrit Neo". */
    private void checkAfterGame(boolean wasGame) {
        final Neo.State s = Neo.state(this);
        if (s == null) return;
        // Navigator kolem udalosti domova = Meta ve hre (menu Questu pres hru), ne konec hry.
        // Quest pri tom hru muze i zastavit, takze to UsageStats samy neodlisi.
        final boolean menu = lastNavigatorAt >= homeAt - NAV_NEAR_HOME_MS;
        final boolean ended;
        final String game;
        if (s.usageKnown) {
            // Hra je STOPPED pred chvilkou a zadna VR hra nebezi.
            ended = !menu && s.vrPkg == null && s.endedVrPkg != null && !s.endedVrPkg.equals(endedHandled);
            game = s.endedVrPkg;
        } else {
            // Bez UsageStats: kdyz se kolem udalosti domova neotevrel Navigator, hra skoncila.
            ended = !menu && wasGame;
            game = fgPkg;
        }
        Log.i(TAG, "Kontrola po udalosti domova: " + (ended ? "hra " + game + " skoncila"
                : menu ? "menu Questu pres hru" : "hra bezi / nic") + ", " + s.describe());
        if (!ended) return;
        endedHandled = game;
        if (game != null && game.equals(runningPkg)) runningPkg = null;
        fgPkg = null;
        fgVr = false;
        if (s.afterGame && !s.visible) {
            launchNeo("po skonceni hry", game != null ? new Intent().putExtra(Neo.EXTRA_ENDED, game) : null);
        }
    }

    private void cancelAfterGame() {
        if (pendingAfterGame != null) {
            handler.removeCallbacks(pendingAfterGame);
            pendingAfterGame = null;
        }
    }

    /** Klavesnice a systemove prekryvy nejsou "aplikace v popredi". */
    private static boolean isTransient(String pkg) {
        return pkg.equals("android") || pkg.equals("com.android.systemui")
                || pkg.contains("inputmethod") || pkg.contains("keyboard");
    }

    /** VR hra (stejne pravidlo jako Neo / Lightning Launcher). */
    private boolean isVrApp(String pkg) {
        final Boolean cached = vrCache.get(pkg);
        if (cached != null) return cached;
        boolean vr = false;
        try {
            final PackageManager pm = getPackageManager();
            final ApplicationInfo ai = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA);
            if (!ANDROID_SETTINGS.equals(pkg)) {
                if (ai.metaData != null && (ai.metaData.containsKey("com.oculus.ossplash")
                        || ai.metaData.containsKey("com.samsung.android.vr.application.mode")
                        || ai.metaData.containsKey("com.oculus.intent.category.VR"))) {
                    vr = true;
                } else {
                    final Intent i = new Intent(Intent.ACTION_MAIN).addCategory("com.oculus.intent.category.VR")
                            .setPackage(pkg);
                    vr = !pm.queryIntentActivities(i, 0).isEmpty();
                }
            }
        } catch (Exception ignored) {
        }
        vrCache.put(pkg, vr);
        return vr;
    }

    // --- Navigator / Knihovna (Meta tlacitko) ----------------------------------

    private void onMenuWindow(AccessibilityEvent e, String pkg, long now) {
        // 3x Meta (kazda davka udalosti = jedno otevreni menu Questu).
        if (now - lastMenuEvent > BURST_MS && onMenuOpened(now)) {
            lastMenuEvent = now;
            return;
        }
        lastMenuEvent = now;
        if (!isNavigator(e, pkg)) {
            Log.v(TAG, pkg + " okno: " + e.getText() + " / " + e.getClassName());
            return;
        }
        lastNavigatorAt = now;
        if (now < suppressUntil) return;                  // Neo samo otevrelo system Questu
        if (now - lastLaunch < LAUNCH_COOLDOWN_MS) return;
        final Neo.State s = Neo.state(this);
        if (s == null) {
            Log.w(TAG, "Neo neni nainstalovane");
            return;
        }
        final String game = game(s);
        Log.i(TAG, "Navigator (" + pkg + "): " + e.getText() + ", hra=" + game + ", popredi=" + fgPkg
                + (fgVr ? " (VR)" : "") + ", " + s.describe());
        if (s.visible) {
            // Neo uz je otevrene -> druhe zmacknuti = opravdove menu Questu.
            Log.i(TAG, "Neo je otevrene, necham menu Questu");
            return;
        }
        if (!s.allowShortcuts) return;
        if (game != null && s.gameMenu) {
            // Ve hre menu Questu (Pokracovat / Ukoncit). Neni to konec hry.
            cancelAfterGame();
            Log.i(TAG, "Bezi VR hra, necham menu Questu");
            return;
        }
        // Ze hry rovnou do Nea (volba vypnuta) -> v Neu lista Pokracovat / Ukoncit.
        Intent extras = null;
        if (game != null) {
            runningPkg = game;
            extras = new Intent().putExtra(Neo.EXTRA_RUNNING, game);
        }
        launchNeo("Meta tlacitko", extras);
    }

    /**
     * VR hra, ktera ted bezi (i pod menu Questu), nebo null. Quest hru pri otevreni
     * menu muze rovnou zastavit - proto i hra, ktera odesla z popredi pred par vterinami.
     */
    private String game(Neo.State s) {
        if (s.usageKnown) return s.vrPkg != null ? s.vrPkg : s.recentVrPkg;
        return fgVr && fgPkg != null ? fgPkg : null;
    }

    private boolean isNavigator(AccessibilityEvent e, String pkg) {
        // 1) Jako Lightning Launcher: titulek okna je "Knihovna" / "Navigator" (lokalizovany).
        final List<CharSequence> texts = e.getText();
        if (texts != null) {
            for (CharSequence t : texts) if (matches(t, NAV_TITLES)) return true;
        }
        if (matches(e.getContentDescription(), NAV_TITLES)) return true;
        // 2) Navigator otevreny na jine zalozce: uvnitr okna je tlacitko Knihovna.
        AccessibilityNodeInfo root = null;
        try {
            root = e.getSource();
            if (root == null) return false;
            return containsLabel(root, LIBRARY_LABELS, new int[]{NODE_BUDGET}, 0);
        } catch (Exception ex) {
            return false;
        } finally {
            if (root != null) {
                try {
                    root.recycle();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static boolean containsLabel(AccessibilityNodeInfo n, Set<String> labels, int[] budget, int depth) {
        if (n == null || budget[0]-- <= 0 || depth > 14) return false;
        if (matches(n.getText(), labels) || matches(n.getContentDescription(), labels)) return true;
        for (int i = 0; i < n.getChildCount(); i++) {
            final AccessibilityNodeInfo ch = n.getChild(i);
            if (ch == null) continue;
            final boolean hit = containsLabel(ch, labels, budget, depth + 1);
            try {
                ch.recycle();
            } catch (Exception ignored) {
            }
            if (hit) return true;
        }
        return false;
    }

    private static boolean matches(CharSequence s, Set<String> set) {
        return s != null && set.contains(s.toString().trim().toLowerCase(Locale.ROOT));
    }

    private static Set<String> lower(String... items) {
        final Set<String> out = new HashSet<>();
        for (String s : items) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }

    // --- 3x Meta ---------------------------------------------------------------

    /** @return true = bylo to 3x Meta a uz je vyrizene */
    private boolean onMenuOpened(long now) {
        if (opens > 0 && now - firstOpen <= TRIPLE_WINDOW_MS) {
            opens++;
        } else {
            opens = 1;
            firstOpen = now;
        }
        Log.i(TAG, "Menu Questu otevreno (" + opens + ". za " + (now - firstOpen) + " ms), popredi=" + fgPkg);
        if (opens < 2) return false;
        opens = 0;
        if (now < suppressUntil) return false;
        final Neo.State s = Neo.state(this);
        if (s == null || !s.triple) return false;
        Log.i(TAG, "3x Meta: " + s.describe());
        if (s.visible) {
            // V Neu: 3x Meta = zpet do aplikace, ktera bezi na pozadi (lista v Neu,
            // i kdyz si ji Neo nasel samo).
            final String back = runningPkg != null ? runningPkg : s.runningPkg;
            if (back == null) return false;
            Log.i(TAG, "3x Meta v Neu -> zpet do " + back);
            Neo.open(this, new Intent().putExtra(Neo.EXTRA_RESUME, true));
            return true;
        }
        // Hra (i pod menu Questu), jinak posledni aplikace v popredi.
        String target = game(s);
        if (target == null) {
            target = s.usageKnown ? s.topPkg
                    : fgPkg != null && !Neo.PKG.equals(fgPkg) && isLaunchable(fgPkg) ? fgPkg : null;
        }
        if (target == null) {
            Log.i(TAG, "3x Meta, ale nic nebezi");
            return false;
        }
        Log.i(TAG, "3x Meta ve " + target + " -> Neo s nabidkou Pokracovat / Ukoncit");
        runningPkg = target;
        cancelAfterGame();
        lastLaunch = 0;
        launchNeo("3x Meta", new Intent().putExtra(Neo.EXTRA_RUNNING, target));
        return true;
    }

    // --- Vynutit ukonceni (Ukoncit v liste bezici aplikace) ----------------------

    void startForceStop(String pkg) {
        if (pkg == null) return;
        stopPkg = pkg;
        stopConfirming = false;
        stopDeadline = SystemClock.uptimeMillis() + STOP_TIMEOUT_MS;
        if (pkg.equals(runningPkg)) runningPkg = null;
        Log.i(TAG, "Ukoncuji " + pkg);
        try {
            // Android Informace o aplikaci (s tlacitkem Vynutit ukonceni). Bez setPackage by
            // Quest otevrel sve Nastaveni Questu, kde nic takoveho neni.
            final Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_HISTORY
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            final Intent android = new Intent(i).setPackage(ANDROID_SETTINGS);
            startActivity(android.resolveActivity(getPackageManager()) != null ? android : i);
        } catch (Exception ex) {
            Log.w(TAG, "Informace o aplikaci nejdou otevrit", ex);
            stopPkg = null;
            return;
        }
        handler.removeCallbacks(stopPoll);
        handler.postDelayed(stopPoll, 400);
    }

    private void pollForceStop() {
        if (stopPkg == null) return;
        if (SystemClock.uptimeMillis() > stopDeadline) {
            // Nepovedlo se klepnout samo - Informace o aplikaci zustanou otevrene.
            Log.w(TAG, "Vynutit ukonceni se nenaslo (" + stopPkg + ")");
            stopPkg = null;
            Toast.makeText(this, "Klepni na „Vynutit ukončení“", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            final AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                if (!stopConfirming) {
                    final AccessibilityNodeInfo btn = findLabel(root, FORCE_STOP_LABELS, new int[]{NODE_BUDGET}, 0);
                    if (btn != null) {
                        if (!btn.isEnabled()) {
                            finishForceStop("uz nebezi");
                            return;
                        }
                        if (click(btn)) stopConfirming = true;
                    }
                } else {
                    // Potvrzovaci dialog "Vynutit ukonceni?" -> OK.
                    final List<AccessibilityNodeInfo> ok = root.findAccessibilityNodeInfosByViewId("android:id/button1");
                    if (ok != null && !ok.isEmpty() && click(ok.get(0))) {
                        finishForceStop("ukonceno");
                        return;
                    }
                    final AccessibilityNodeInfo btn = findLabel(root, FORCE_STOP_LABELS, new int[]{NODE_BUDGET}, 0);
                    if (btn != null && !btn.isEnabled()) {
                        finishForceStop("ukonceno bez dialogu");
                        return;
                    }
                }
            }
        } catch (Exception ex) {
            Log.w(TAG, "Chyba pri ukoncovani", ex);
        }
        handler.postDelayed(stopPoll, STOP_POLL_MS);
    }

    private void finishForceStop(String why) {
        Log.i(TAG, "Vynutit ukonceni " + stopPkg + ": " + why);
        final String pkg = stopPkg;
        stopPkg = null;
        // Zavrit Informace o aplikaci a vratit se do Nea s hlaskou "Ukonceno".
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 250);
        handler.postDelayed(() -> Neo.open(this, new Intent().putExtra(Neo.EXTRA_STOPPED, pkg)), 600);
    }

    /** Uzel s jednim z popisku. */
    private static AccessibilityNodeInfo findLabel(AccessibilityNodeInfo n, Set<String> labels, int[] budget, int depth) {
        if (n == null || budget[0]-- <= 0 || depth > 20) return null;
        if (matches(n.getText(), labels) || matches(n.getContentDescription(), labels)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            final AccessibilityNodeInfo hit = findLabel(n.getChild(i), labels, budget, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    /** Klepne na uzel nebo na nejblizsiho rodice, na ktereho jde klepnout. */
    private static boolean click(AccessibilityNodeInfo n) {
        for (AccessibilityNodeInfo p = n; p != null; p = p.getParent()) {
            if (p.isClickable()) return p.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        return false;
    }

    // --- Otevreni Nea ------------------------------------------------------------

    private void launchNeo(String why, Intent extras) {
        final long now = SystemClock.uptimeMillis();
        if (now - lastLaunch < LAUNCH_COOLDOWN_MS) return;
        lastLaunch = now;
        Log.i(TAG, "Oteviram Neo (" + why + ")");
        Neo.open(this, extras);
    }
}
