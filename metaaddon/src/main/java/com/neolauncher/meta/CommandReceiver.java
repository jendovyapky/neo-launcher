package com.neolauncher.meta;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Prikazy z Nea (jen se stejnym podpisem - opravneni com.neolauncher.permission.CONTROL):
 * uspat, vypnout / restartovat, ukoncit aplikaci, docasne ignorovat Meta tlacitko
 * a zapomenout bezici aplikaci. Provede je bezici sluzba pristupnosti.
 */
public class CommandReceiver extends BroadcastReceiver {
    static final String ACTION_SLEEP = "com.neolauncher.meta.SLEEP";
    static final String ACTION_POWER = "com.neolauncher.meta.POWER";
    static final String ACTION_FORCE_STOP = "com.neolauncher.meta.FORCE_STOP";
    static final String ACTION_SUPPRESS = "com.neolauncher.meta.SUPPRESS";
    static final String ACTION_CLEAR_RUNNING = "com.neolauncher.meta.CLEAR_RUNNING";
    static final String EXTRA_PKG = "pkg";
    static final String EXTRA_MS = "ms";
    /** POWER: "off" / "restart" = rovnou klepnout v systemove nabidce, jinak ji jen otevrit. */
    static final String EXTRA_WHAT = "what";

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        final MetaService s = MetaService.get();
        if (action == null) return;
        if (s == null) {
            Log.w(MetaService.TAG, "Prikaz " + action + ", ale sluzba neni zapnuta");
            return;
        }
        switch (action) {
            case ACTION_SLEEP:
                s.sleep();
                break;
            case ACTION_POWER:
                s.powerMenu(intent.getStringExtra(EXTRA_WHAT));
                break;
            case ACTION_FORCE_STOP:
                s.startForceStop(intent.getStringExtra(EXTRA_PKG));
                break;
            case ACTION_SUPPRESS:
                s.suppress(intent.getLongExtra(EXTRA_MS, 4000));
                break;
            case ACTION_CLEAR_RUNNING:
                s.clearRunning();
                break;
            default:
                break;
        }
    }
}
