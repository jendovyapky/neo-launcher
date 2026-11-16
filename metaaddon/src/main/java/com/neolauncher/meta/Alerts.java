package com.neolauncher.meta;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/**
 * Upozorneni i kdyz Neo neni otevrene (doplnek bezi porad, i ve hre): slaba
 * baterie a nabito. Oznameni Androidu (Quest ho ukaze v oznamenich) a navic
 * kratka hlaska - co z toho Quest ve hre opravdu ukaze, je potreba overit.
 */
final class Alerts {
    static final int LOW_20 = 1;
    static final int LOW_10 = 2;
    static final int FULL = 3;
    private static final String CHANNEL = "battery";
    private static final int ID_BATTERY = 1;

    private Alerts() {}

    static void battery(Context c, int kind, int pct) {
        final String title = kind == FULL ? "Nabito na 100 %"
                : kind == LOW_10 ? "Baterie skoro vybitá · " + pct + " %" : "Slabá baterie · " + pct + " %";
        final String text = kind == FULL ? "Nabíječku můžeš odpojit" : "Připoj nabíječku";
        boolean notified = false;
        try {
            final NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null && nm.areNotificationsEnabled()) {
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    final NotificationChannel ch = new NotificationChannel(CHANNEL, "Baterie",
                            NotificationManager.IMPORTANCE_HIGH);
                    ch.setDescription("Slabá baterie a nabito na 100 %");
                    nm.createNotificationChannel(ch);
                }
                // Klepnuti na oznameni otevre Neo.
                final Intent open = new Intent(Intent.ACTION_MAIN)
                        .setComponent(new ComponentName(Neo.PKG, Neo.ACTIVITY))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                final PendingIntent pi = PendingIntent.getActivity(c, 0, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                final Notification n = new Notification.Builder(c, CHANNEL)
                        .setSmallIcon(kind == FULL ? android.R.drawable.ic_lock_idle_charging
                                : android.R.drawable.ic_lock_idle_low_battery)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setCategory(Notification.CATEGORY_STATUS)
                        .setAutoCancel(true)
                        .setContentIntent(pi)
                        .setTimeoutAfter(kind == FULL ? 30 * 60_000L : 15 * 60_000L)
                        .build();
                nm.notify(ID_BATTERY, n);
                notified = true;
            }
        } catch (Exception e) {
            Log.w(MetaService.TAG, "Oznameni nejde ukazat", e);
        }
        Toast.makeText(c, title + " – " + text, Toast.LENGTH_LONG).show();
        Log.i(MetaService.TAG, "Upozorneni: " + title + (notified ? " (oznameni + hlaska)" : " (jen hlaska)"));
    }
}
