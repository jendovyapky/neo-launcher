package com.neolauncher.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;
import android.widget.Toast;

/** Vysledek instalace od systemu (PackageInstaller): aktualizace Nea nebo APK vybrane v Neu. */
public class InstallReceiver extends BroadcastReceiver {
    /** Nazev nainstalovane aplikace (nebo balicek, kdyz nazev nejde zjistit). */
    private static String label(Context c, String pkg) {
        if (pkg == null) return "aplikace";
        try {
            return String.valueOf(c.getPackageManager().getApplicationLabel(
                    c.getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    private static final String TAG = "NeoUpdater";

    /** Neo ukaze vysledek samo (ziva bublina v horni liste). */
    public interface Listener {
        /**
         * @param kind   ApkInstaller.KIND_APK / KIND_ADDON, null = aktualizace Nea
         * @param status PackageInstaller.STATUS_*
         * @return true = ukazano v Neu, hlasku (Toast) uz neni potreba
         */
        boolean onInstallStatus(String kind, int status, String label, String message);
    }

    private static volatile Listener listener;

    public static void setListener(Listener l) {
        listener = l;
    }

    public static void clearListener(Listener l) {
        if (listener == l) listener = null;
    }

    private static boolean notifyListener(String kind, int status, String label, String message) {
        final Listener l = listener;
        if (l == null) return false;
        try {
            return l.onInstallStatus(kind, status, label, message);
        } catch (Exception e) {
            Log.w(TAG, "Zobrazeni vysledku instalace selhalo", e);
            return false;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        final String kind = intent.getStringExtra(ApkInstaller.EXTRA_KIND);
        final boolean apk = kind != null;
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                // System chce potvrzeni od uzivatele - otevrit jeho dialog.
                @SuppressWarnings("deprecation")
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        context.startActivity(confirm);
                        notifyListener(kind, status, null, null);
                    } catch (Exception e) {
                        Log.w(TAG, "Potvrzovaci dialog instalace nejde otevrit", e);
                        Toast.makeText(context, "Instalaci nejde potvrdit", Toast.LENGTH_LONG).show();
                    }
                }
                break;
            }
            case PackageInstaller.STATUS_SUCCESS:
                // Po aktualizaci sebe sama nas system ukonci - neni co delat.
                if (apk) {
                    final String label = label(context, intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME));
                    if (!notifyListener(kind, status, label, null)) {
                        Toast.makeText(context, "Nainstalováno: " + label, Toast.LENGTH_LONG).show();
                    }
                }
                break;
            default: {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Log.w(TAG, "Instalace selhala: " + status + " " + msg);
                if (notifyListener(kind, status, null, msg)) break;
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Toast.makeText(context, (apk ? "Aplikace se nenainstalovala" : "Aktualizace se nenainstalovala")
                            + (msg != null ? ": " + msg : ""), Toast.LENGTH_LONG).show();
                }
                break;
            }
        }
    }
}
