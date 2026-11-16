package com.neolauncher.data;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.lang.reflect.Method;

/** Informace o zarizeni - Quest, verze Horizon OS, podpora efektu. */
public final class Platform {
    private Platform() {}

    private static Boolean sIsQuest;
    private static int sVrOsVersion = Integer.MIN_VALUE;

    public static boolean isQuest(Context c) {
        if (sIsQuest == null) {
            boolean q;
            try {
                c.getPackageManager().getApplicationInfo("com.oculus.vrshell", 0);
                q = true;
            } catch (PackageManager.NameNotFoundException e) {
                q = false;
            }
            sIsQuest = q;
        }
        return sIsQuest;
    }

    /** Verze Horizon OS (vlastnost ro.vros.build.version), -1 kdyz neni Quest. */
    public static int vrOsVersion() {
        if (sVrOsVersion == Integer.MIN_VALUE) {
            int v;
            try {
                v = Integer.parseInt(systemProperty("ro.vros.build.version", "-1"));
            } catch (Exception e) {
                v = -1;
            }
            sVrOsVersion = v;
        }
        return sVrOsVersion;
    }

    /** Quest 3 ("eureka") nebo Quest 3S ("panther"). */
    public static boolean isQuestGen3() {
        return "eureka".equalsIgnoreCase(Build.HARDWARE)
                || "panther".equalsIgnoreCase(Build.HARDWARE);
    }

    /** Systemove rozmazani prostredi za panelem (blend effects) - Quest 3/3S, v77+. */
    public static boolean supportsBlendEffects() {
        return isQuestGen3() && vrOsVersion() >= 77;
    }

    /** Spousteni aplikaci pres vrshell (jako z oficialni knihovny) - v71+. */
    public static boolean supportsVrOsChainLaunch() {
        return vrOsVersion() >= 71;
    }

    @SuppressLint("PrivateApi")
    private static String systemProperty(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, def);
        } catch (Exception e) {
            return def;
        }
    }
}
