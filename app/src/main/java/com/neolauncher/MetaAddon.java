package com.neolauncher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

import com.neolauncher.launch.AppLauncher;
import com.neolauncher.update.ApkInstaller;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Doplnek "Neo - Meta tlacitko" (modul metaaddon): samostatna mala aplikace se
 * sluzbou pristupnosti, jako RedirectServices u Lightning Launcheru.
 * <p>
 * Proc zvlast: Android 13+ nedovoli zapnout sluzbu pristupnosti aplikaci
 * nainstalovane rucne ze souboru ("Omezene nastaveni"). Doplnek ale instaluje
 * Neo samo pres PackageInstaller (jako LL svuj doplnek) a takovou aplikaci
 * Android za rucne nainstalovanou nepovazuje.
 * <p>
 * Neo ho ma pribaleny v assets (meta-addon.apk, pridava ho CI). Komunikace:
 * doplnek cte stav Nea z ShortcutStateProvider a otevira LauncherActivity
 * s extra (EXTRA_*); Neo posila prikazy broadcastem na CommandReceiver doplnku
 * (jen se stejnym podpisem - opravneni com.neolauncher.permission.CONTROL).
 */
public final class MetaAddon {
    private static final String TAG = "NeoMeta";
    public static final String PKG = "com.neolauncher.meta";
    private static final String SERVICE = PKG + ".MetaService";
    private static final String RECEIVER = PKG + ".CommandReceiver";
    private static final String ASSET = "meta-addon.apk";

    /** Extra od doplnku (Neo otevrene ze hry / po ukonceni aplikace / 3x Meta v Neu). */
    public static final String EXTRA_RUNNING = "neo.running";
    public static final String EXTRA_STOPPED = "neo.stopped";
    public static final String EXTRA_RESUME = "neo.resume";
    /** Doplnek otevrel Neo po skonceni teto hry - lista "bezi na pozadi" pro ni neplati. */
    public static final String EXTRA_ENDED = "neo.ended";

    /** Co udelat s headsetem (PowerSheet): doplnek klepne v systemove nabidce vypnuti. */
    public static final String POWER_OFF = "off";
    public static final String POWER_RESTART = "restart";
    private static final String PERMISSION_ACTIVITY = PKG + ".PermissionActivity";
    private static final String POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";

    private static final String ACTION_SLEEP = "com.neolauncher.meta.SLEEP";
    private static final String ACTION_POWER = "com.neolauncher.meta.POWER";
    private static final String ACTION_FORCE_STOP = "com.neolauncher.meta.FORCE_STOP";
    private static final String ACTION_SUPPRESS = "com.neolauncher.meta.SUPPRESS";
    private static final String ACTION_CLEAR_RUNNING = "com.neolauncher.meta.CLEAR_RUNNING";

    /** Aplikace, ze ktere se odeslo do Nea (bezi na pozadi) - lista Pokracovat / Ukoncit. */
    private static volatile String runningPkg;

    private MetaAddon() {}

    // --- Stav -----------------------------------------------------------------------

    public static boolean isInstalled(Context c) {
        return installedVersion(c) >= 0;
    }

    public static long installedVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(PKG, 0).getLongVersionCode();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Je doplnek pribaleny v teto verzi Nea? (lokalni buildy bez CI ho mit nemusi) */
    public static boolean isBundled(Context c) {
        return bundledFile(c) != null;
    }

    /** Pribaleny doplnek je novejsi nez nainstalovany. */
    public static boolean needsUpdate(Context c) {
        final long installed = installedVersion(c);
        if (installed < 0) return false;
        final File f = bundledFile(c);
        if (f == null) return false;
        final PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
        return pi != null && pi.getLongVersionCode() > installed;
    }

    /** Je sluzba doplnku zapnuta v Pristupnosti? */
    public static boolean isEnabled(Context c) {
        try {
            final String s = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(PKG + "/");
        } catch (Exception e) {
            return false;
        }
    }

    // --- Instalace a zapnuti ----------------------------------------------------------

    /** Nainstaluje pribaleny doplnek (Quest se zepta na potvrzeni). */
    public static boolean install(Context c, ApkInstaller.Listener listener) {
        final File f = bundledFile(c);
        if (f == null) return false;
        ApkInstaller.installFile(c, f, ApkInstaller.KIND_ADDON, listener);
        return true;
    }

    /**
     * Pristupnost v ANDROID nastaveni (stejny intent jako Lightning Launcher).
     * Bez setPackage Quest otevre sve Nastaveni Questu, kde sluzby nejsou.
     */
    public static boolean openSettings(Activity a) {
        final Intent[] tries = {
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).setPackage(AppLauncher.ANDROID_SETTINGS),
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        };
        for (Intent i : tries) {
            try {
                a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception e) {
                Log.w(TAG, "Pristupnost nejde otevrit: " + i, e);
            }
        }
        return false;
    }

    /** Povolil uzivatel z PC WRITE_SECURE_SETTINGS? Pak Neo doplnek zapne samo. */
    public static boolean canSelfEnable(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    /** Zapne sluzbu doplnku primo v systemovem nastaveni (jen s WRITE_SECURE_SETTINGS). */
    public static boolean selfEnable(Context c) {
        if (!canSelfEnable(c) || !isInstalled(c)) return false;
        try {
            final ContentResolver cr = c.getContentResolver();
            final String me = new ComponentName(PKG, SERVICE).flattenToString();
            String cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null || cur.trim().isEmpty()) cur = me;
            else if (!cur.contains(me)) cur = cur + ":" + me;
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, cur);
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i(TAG, "Doplnek zapnut pres WRITE_SECURE_SETTINGS");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Doplnek nejde zapnout", e);
            return false;
        }
    }

    // --- Prikazy doplnku -------------------------------------------------------------

    /** Uspat headset. @return false = doplnek neni zapnuty */
    public static boolean sleep(Context c) {
        return send(c, new Intent(ACTION_SLEEP));
    }

    /**
     * Vypnout / restartovat (POWER_OFF / POWER_RESTART): doplnek otevre systemovou
     * nabidku vypnuti a sam v ni klepne na spravnou polozku. Obycejna aplikace
     * headset vypnout nesmi.
     */
    public static boolean power(Context c, String what) {
        return send(c, new Intent(ACTION_POWER).putExtra("what", what));
    }

    /** Smi doplnek ukazovat oznameni (Android 13+ se na to musi zeptat)? */
    public static boolean canNotify(Context c) {
        if (android.os.Build.VERSION.SDK_INT < 33) return true;
        try {
            return c.getPackageManager().checkPermission(POST_NOTIFICATIONS, PKG) == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Doplnek se zepta na povoleni oznameni (vlastni pruhledna aktivita). */
    public static boolean requestNotifications(Activity a) {
        try {
            a.startActivity(new Intent().setComponent(new ComponentName(PKG, PERMISSION_ACTIVITY))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Doplnek neumi pozadat o oznameni (starsi verze?)", e);
            return false;
        }
    }

    /** Ukonci aplikaci (doplnek klepne v Informacich o aplikaci na Vynutit ukonceni). */
    public static boolean forceStop(Context c, String pkg) {
        return send(c, new Intent(ACTION_FORCE_STOP).putExtra("pkg", pkg));
    }

    /** Neo samo otevira system Questu - chvili to nebrat jako Meta tlacitko. */
    public static void suppress(Context c, long ms) {
        send(c, new Intent(ACTION_SUPPRESS).putExtra("ms", ms));
    }

    public static String runningApp() {
        return runningPkg;
    }

    public static void setRunning(String pkg) {
        runningPkg = pkg;
    }

    /** Bezici aplikaci zapomenout (Pokracovat / Ukoncit / skryt listu). */
    public static void clearRunning(Context c) {
        runningPkg = null;
        send(c, new Intent(ACTION_CLEAR_RUNNING));
    }

    private static boolean send(Context c, Intent i) {
        if (!isEnabled(c)) return false;
        i.setComponent(new ComponentName(PKG, RECEIVER));
        i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        try {
            c.sendBroadcast(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Prikaz pro doplnek selhal", e);
            return false;
        }
    }

    // --- Pribaleny APK -----------------------------------------------------------------

    /** Pribaleny doplnek rozbaleny do cache (jednou pro kazdou verzi Nea), nebo null. */
    private static File bundledFile(Context c) {
        long neo;
        try {
            neo = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            neo = 0;
        }
        final File f = new File(c.getCacheDir(), "meta-addon-" + neo + ".apk");
        if (f.isFile() && f.length() > 0) return f;
        try (InputStream is = c.getAssets().open(ASSET); OutputStream os = new FileOutputStream(f)) {
            final byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            Log.w(TAG, "Doplnek neni v assets (lokalni build bez CI?)");
            return null;
        }
        return f;
    }
}
