package com.neolauncher.launch;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import com.neolauncher.data.AppEntry;
import com.neolauncher.data.Platform;

import java.util.UUID;

/**
 * Spousteni aplikaci na Questu. Logika prevzata z Lightning Launcheru
 * (Launch.java / LaunchExt.java, threethan, GPL-3.0) a zjednodusena na to,
 * co potrebuje Quest 3S s aktualnim Horizon OS.
 */
public final class AppLauncher {
    private static final String TAG = "NeoLaunch";

    private AppLauncher() {}

    /** @return true, kdyz se spusteni podarilo odeslat. */
    public static boolean launch(Activity a, AppEntry app) {
        try {
            return launchInternal(a, app);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Nejde spustit " + app.pkg, e);
            Toast.makeText(a, "Aplikaci nejde spustit", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private static boolean launchInternal(Activity a, AppEntry app) {
        final PackageManager pm = a.getPackageManager();
        final boolean quest = Platform.isQuest(a);

        // Systemove panely Questu (systemux://...) otevira primo vrshell.
        if (quest && app.type == AppEntry.TYPE_PANEL) {
            // Nastaveni / menu Questu otevrene z Nea neni zmacknuti Meta tlacitka.
            com.neolauncher.MetaAddon.suppress(a, 4000);
            Intent i = pm.getLaunchIntentForPackage("com.oculus.vrshell");
            if (i == null) return false;
            i.setData(Uri.parse(app.pkg));
            a.startActivity(i);
            return true;
        }

        // Chaty na novejsich verzich maji vlastni aktivitu.
        if (quest && "com.oculus.socialplatform".equals(app.pkg)) {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(new ComponentName("com.oculus.socialplatform",
                    "com.oculus.panelapp.people.BlendedPeopleActivity"));
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
            return true;
        }

        Intent standard = pm.getLaunchIntentForPackage(app.pkg);
        if (standard == null) standard = pm.getLeanbackLaunchIntentForPackage(app.pkg);

        // Horizon OS v71+: aplikaci spusti shell stejne, jako kdyby se klikla
        // v oficialni knihovne (VR hry immersivne, 2D aplikace v novem panelu).
        if (quest && Platform.supportsVrOsChainLaunch()
                && (standard != null || app.type == AppEntry.TYPE_VR)) {
            vrOsLaunch(a, app.pkg);
            return true;
        }

        if (standard == null) return false;
        standard.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (quest) standard.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        a.startActivity(standard);
        return true;
    }

    private static void vrOsLaunch(Activity a, String pkg) {
        if (Platform.vrOsVersion() < 202) {
            Intent i = new Intent("com.oculus.vrshell.intent.action.LAUNCH");
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setData(Uri.parse("apk://" + pkg));
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            i.setComponent(new ComponentName("com.oculus.vrshell",
                    "com.oculus.vrshell.MainActivity"));
            a.startActivity(i);
        } else {
            // Horizon OS v2.0.4+ zmenil zpusob - broadcast do shellu.
            Intent i = new Intent("com.oculus.vrshell.intent.action.LAUNCH");
            i.setComponent(new ComponentName("com.oculus.vrshell",
                    "com.oculus.vrshell.ShellControlBroadcastReceiver"));
            i.setFlags(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES);
            i.putExtra("intent_pkg", "com.oculus.panelapp.library");
            i.putExtra("launchId", UUID.randomUUID().toString());
            i.putExtra("intent_data", pkg);
            i.putExtra("uri", "null");
            i.putExtra("timestamp", System.currentTimeMillis());
            a.sendBroadcast(i);
        }
    }

    /** Android nastaveni (na Questu schovane) - Quest jinak kazdy odkaz na nastaveni presmeruje do svych. */
    public static final String ANDROID_SETTINGS = "com.android.settings";

    /**
     * Informace o aplikaci v ANDROID nastaveni (Vynutit ukonceni, uloziste, tri tecky - Povolit
     * omezena nastaveni). Bez setPackage by Quest otevrel sve Nastaveni Questu,
     * kde nic z toho neni (jako Lightning Launcher).
     */
    public static Intent appInfoIntent(Context c, String pkg) {
        final Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + pkg.replaceFirst("systemux://", "")));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Intent android = new Intent(i).setPackage(ANDROID_SETTINGS);
        return android.resolveActivity(c.getPackageManager()) != null ? android : i;
    }

    /** Obecne: systemova obrazovka v Android nastaveni, kdyz ji umi (jinak puvodni intent). */
    public static Intent inAndroidSettings(Context c, Intent i) {
        final Intent android = new Intent(i).setPackage(ANDROID_SETTINGS);
        return android.resolveActivity(c.getPackageManager()) != null ? android : i;
    }

    public static void openAppInfo(Activity a, String pkg) {
        try {
            a.startActivity(appInfoIntent(a, pkg));
        } catch (Exception e) {
            Toast.makeText(a, "Informace o aplikaci nejdou otevřít", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Android nastaveni. Kdyz hlavni obrazovka nejde spustit primo (na Questu byva
     * zamcena), otevrou se Informace o aplikaci Nastaveni s tlacitkem Otevrit - stejne
     * jako "Android nastaveni" v Lightning Launcheru.
     */
    public static boolean openAndroidSettings(Activity a) {
        final Intent main = new Intent(android.provider.Settings.ACTION_SETTINGS).setPackage(ANDROID_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (main.resolveActivity(a.getPackageManager()) != null) {
                a.startActivity(main);
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Android nastaveni nejdou spustit primo", e);
        }
        try {
            final Intent info = appInfoIntent(a, ANDROID_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            a.startActivity(info);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void uninstall(Activity a, String pkg) {
        Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            a.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(a, "Odinstalace se nepodařila spustit", Toast.LENGTH_SHORT).show();
        }
    }
}
