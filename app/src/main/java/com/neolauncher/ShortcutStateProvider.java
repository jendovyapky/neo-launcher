package com.neolauncher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import com.neolauncher.data.ForegroundApps;
import com.neolauncher.data.Platform;
import com.neolauncher.data.Prefs;

/**
 * Stav Nea pro doplnek "Neo - Meta tlacitko" (MetaAddon) a starsi addon
 * RedirectServices z Lightning Launcheru: isOpen / isVisible (je Neo otevrene?),
 * shouldBlur, allowShortcuts a volby Meta tlacitka z nastaveni Nea
 * (metaGameMenu, metaAfterGame, openOnBoot, metaTriple, batteryAlerts), co je opravdu
 * v popredi podle UsageStats (usageKnown, topPkg, vrPkg, recentVrPkg, endedVrPkg, bgVrPkg -
 * viz ForegroundApps) a aplikace v liste bezici aplikace (runningPkg - 3x Meta v Neu = zpet).
 */
public class ShortcutStateProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        final MatrixCursor c = new MatrixCursor(new String[]{"isOpen", "shouldBlur", "allowShortcuts",
                "isVisible", "metaGameMenu", "metaAfterGame", "openOnBoot", "metaTriple",
                "usageKnown", "topPkg", "vrPkg", "endedVrPkg", "recentVrPkg", "bgVrPkg", "runningPkg",
                "batteryAlerts"});
        final NeoApp app = NeoApp.get();
        final Prefs prefs = app != null ? app.prefs() : null;
        final int visible = LauncherActivity.isVisible() ? 1 : 0;
        // Co je opravdu v popredi (UsageStats) - udalosti oken na Questu klamou (menu ve hre).
        final ForegroundApps.Result fg = getContext() != null ? ForegroundApps.query(getContext())
                : new ForegroundApps.Result();
        Log.d("NeoMeta", "Neo pro doplnek: " + ForegroundApps.describe(fg) + ", lista=" + MetaAddon.runningApp());
        final boolean allow = prefs == null || prefs.allowShortcuts();
        final boolean blur = prefs != null && allow && prefs.systemBlur() && Platform.supportsBlendEffects();
        c.addRow(new Object[]{allow && LauncherActivity.isInForeground() ? 1 : 0, blur ? 1 : 0, allow ? 1 : 0,
                visible, prefs == null || prefs.metaGameMenu() ? 1 : 0, prefs == null || prefs.metaAfterGame() ? 1 : 0,
                prefs == null || prefs.openOnBoot() ? 1 : 0, prefs == null || prefs.metaTriple() ? 1 : 0,
                fg.known ? 1 : 0, fg.topPkg, fg.vrPkg, fg.endedVrPkg, fg.recentVrPkg, fg.bgVrPkg,
                MetaAddon.runningApp(), prefs == null || prefs.batteryAlerts() ? 1 : 0});
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
