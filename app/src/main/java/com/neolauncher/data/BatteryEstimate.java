package com.neolauncher.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.SystemClock;

/**
 * Odhad, jak dlouho jeste baterie vydrzi.
 * <p>
 * Launcher sam skoro nic nezere, takze okamzity odber v Neu by hodne precenoval.
 * Proto se hlavne uci ze skutecneho hrani: pri odchodu z Nea (spusteni hry)
 * si zapamatuje stav baterie a pri navratu spocita, kolik procent za hodinu
 * ubylo (jen cas, kdy headset bezel - ne spanek). Prumeruje se pres vic her.
 * Dokud zadna hra neprobehla, pouzije okamzity odber (proud / naboj z Androidu).
 */
public final class BatteryEstimate {
    private static final String SP = "neo_battery";
    private static final String K_LEVEL = "leave_level";
    private static final String K_UPTIME = "leave_uptime";
    private static final String K_ELAPSED = "leave_elapsed";
    private static final String K_CHARGING = "leave_charging";
    private static final String K_RATE = "drain_rate";
    /** Kratsi pobyt mimo Neo / mensi pokles se nepocita (moc nepresne). */
    private static final long MIN_AWAY_MS = 10 * 60 * 1000L;
    private static final int MIN_DROP = 3;

    private final SharedPreferences sp;
    private final BatteryManager bm;
    /** Prumerny odber pri otevrenem Neu (uA), -1 = zatim nezname. */
    private float currentEma = -1f;

    public BatteryEstimate(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
        bm = c.getSystemService(BatteryManager.class);
    }

    /** Okamzity odber (vola se pri kazde zmene baterie, kdyz je Neo videt). */
    public void sample(boolean charging) {
        if (charging || bm == null) return;
        long ua;
        try {
            ua = Math.abs((long) bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW));
        } catch (Exception e) {
            return;
        }
        if (ua == 0 || ua == Integer.MIN_VALUE) return;
        // Nektera zarizeni hlasi mA misto uA.
        if (ua < 20_000L) ua *= 1000L;
        currentEma = currentEma < 0 ? ua : currentEma * 0.7f + ua * 0.3f;
    }

    /** Odchod z Nea (typicky spusteni hry). */
    public void onLeave(int level, boolean charging) {
        if (level < 0) return;
        sp.edit().putInt(K_LEVEL, level).putLong(K_UPTIME, SystemClock.uptimeMillis())
                .putLong(K_ELAPSED, SystemClock.elapsedRealtime()).putBoolean(K_CHARGING, charging).apply();
    }

    /** Navrat do Nea: z poklesu baterie za dobu hrani se uci skutecna spotreba. */
    public void onReturn(int level, boolean charging) {
        if (level < 0 || !sp.contains(K_LEVEL)) return;
        final int l0 = sp.getInt(K_LEVEL, -1);
        final long du = SystemClock.uptimeMillis() - sp.getLong(K_UPTIME, 0);
        final long de = SystemClock.elapsedRealtime() - sp.getLong(K_ELAPSED, 0);
        final boolean c0 = sp.getBoolean(K_CHARGING, true);
        sp.edit().remove(K_LEVEL).apply();
        // Restart mezitim (casy od startu jsou mensi) nebo nabijeni = nepouzitelne.
        if (du <= 0 || de <= 0 || c0 || charging) return;
        final int drop = l0 - level;
        if (du < MIN_AWAY_MS || drop < MIN_DROP) return;
        final float rate = drop / (du / 3_600_000f); // % za hodinu provozu
        if (rate < 3f || rate > 120f) return;
        final float old = sp.getFloat(K_RATE, -1f);
        sp.edit().putFloat(K_RATE, old < 0 ? rate : old * 0.6f + rate * 0.4f).apply();
    }

    /** Naucena spotreba pri hrani (% za hodinu), nebo -1. */
    public float playRate() {
        return sp.getFloat(K_RATE, -1f);
    }

    /**
     * @return zbyvajici minuty (zaokrouhlene na 5 min), nebo -1 kdyz se neda odhadnout / nabiji se
     */
    public long minutesLeft(int level, boolean charging) {
        if (charging || level <= 0) return -1;
        float rate = playRate();
        if (rate <= 0f) rate = instantRate(level);
        if (rate <= 0f) return -1;
        final long min = Math.round(level / rate * 60f);
        return min < 15 ? Math.max(1, min) : Math.round(min / 5f) * 5L;
    }

    /** Je odhad z hrani (true), nebo jen z okamziteho odberu v Neu? */
    public boolean fromPlay() {
        return playRate() > 0f;
    }

    /** % za hodinu podle okamziteho odberu a naboje baterie. */
    private float instantRate(int level) {
        if (currentEma <= 0 || bm == null) return -1f;
        long uah;
        try {
            uah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        } catch (Exception e) {
            return -1f;
        }
        if (uah <= 0 || uah == Integer.MIN_VALUE) return -1f;
        if (uah < 20_000L) uah *= 1000L; // mAh misto uAh
        final float full = uah / (level / 100f);
        final float rate = currentEma / full * 100f;
        return rate >= 1f && rate <= 120f ? rate : -1f;
    }

    /** "2 h 10 min" / "45 min". */
    public static String format(long minutes) {
        if (minutes < 0) return null;
        if (minutes < 60) return minutes + " min";
        final long m = minutes % 60;
        return (minutes / 60) + " h" + (m > 0 ? " " + m + " min" : "");
    }
}
