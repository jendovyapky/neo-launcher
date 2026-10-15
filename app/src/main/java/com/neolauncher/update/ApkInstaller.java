package com.neolauncher.update;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Instalace APK primo z Nea (sideload bez PC): uzivatel vybere soubor stazeny
 * treba v prohlizeci Questu, Neo ho preda systemu (PackageInstaller) a Quest
 * se zepta na potvrzeni. Stejna cesta jako aktualizace Nea.
 */
public final class ApkInstaller {
    private static final String TAG = "NeoApk";
    /** Extra pro InstallReceiver: co se instaluje (bez extra = aktualizace Nea). */
    static final String EXTRA_KIND = "neo.install.kind";
    /** APK vybrane uzivatelem. */
    public static final String KIND_APK = "apk";
    /** Doplnek "Neo - Meta tlacitko" pribaleny v Neu. */
    public static final String KIND_ADDON = "addon";

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ApkInstaller() {}

    /** Prubeh instalace (vse na hlavnim vlakne) - Neo ho ukazuje v zive bubline. */
    public interface Listener {
        /** Kopirovani APK do instalacni relace, 0..1 (nebo -1, kdyz velikost neni znama). */
        void onProgress(float p);

        /** Predano systemu - Quest se zepta na potvrzeni, vysledek prijde do InstallReceiver. */
        void onCommitted();

        void onError(String message);
    }

    /** Vyber souboru (systemovy vyber dokumentu). APK nemivaji vzdy spravny typ, proto i obecne soubory. */
    public static Intent pickIntent() {
        final Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive", "application/octet-stream"});
        return i;
    }

    /** Zdroj dat APK (soubor / dokument). */
    private interface Source {
        InputStream open() throws Exception;
    }

    /** Zkopiruje APK vybrane uzivatelem do instalacni relace a odesle ke schvaleni. */
    public static void install(Context c, Uri uri, Listener listener) {
        final Context app = c.getApplicationContext();
        final ContentResolver cr = app.getContentResolver();
        EXEC.execute(() -> {
            final String name = displayName(cr, uri);
            if (name != null && !name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                if (listener != null) MAIN.post(() -> listener.onError("Tohle není soubor APK"));
                return;
            }
            run(app, () -> cr.openInputStream(uri), fileSize(cr, uri), KIND_APK, listener);
        });
    }

    /** Instalace APK ze souboru (pribaleny doplnek). */
    public static void installFile(Context c, java.io.File f, String kind, Listener listener) {
        final Context app = c.getApplicationContext();
        EXEC.execute(() -> run(app, () -> new java.io.FileInputStream(f), f.length(), kind, listener));
    }

    /**
     * Instalace pres PackageInstaller (session) - stejne jako Lightning Launcher instaluje
     * svuj doplnek. Takto nainstalovanou aplikaci Android nebere jako "rucne nainstalovanou"
     * (zadne Omezene nastaveni u pristupnosti).
     */
    private static void run(Context app, Source source, long size, String kind, Listener listener) {
        String error = null;
        PackageInstaller.Session session = null;
        try {
            final PackageInstaller pi = app.getPackageManager().getPackageInstaller();
            final PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            if (size > 0) params.setSize(size);
            final int id = pi.createSession(params);
            session = pi.openSession(id);
            try (InputStream is = source.open();
                 OutputStream os = session.openWrite("app.apk", 0, size > 0 ? size : -1)) {
                if (is == null) throw new java.io.FileNotFoundException("APK");
                final byte[] buf = new byte[256 * 1024];
                long done = 0;
                int lastPct = -1;
                int n;
                while ((n = is.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    done += n;
                    final int pct = size > 0 ? (int) (done * 100 / size) : -1;
                    if (pct != lastPct && listener != null) {
                        lastPct = pct;
                        final float p = pct >= 0 ? Math.min(1f, pct / 100f) : -1f;
                        MAIN.post(() -> listener.onProgress(p));
                    }
                }
                session.fsync(os);
            }
            final Intent result = new Intent(app, InstallReceiver.class).putExtra(EXTRA_KIND, kind);
            final PendingIntent p = PendingIntent.getBroadcast(app, id, result,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(p.getIntentSender());
            session.close();
            session = null;
            if (listener != null) MAIN.post(listener::onCommitted);
        } catch (Exception e) {
            Log.w(TAG, "Instalace APK selhala", e);
            error = "APK se nepodařilo načíst";
            if (session != null) {
                try {
                    session.abandon();
                } catch (Exception ignored) {
                }
            }
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {
                }
            }
        }
        final String err = error;
        if (err != null && listener != null) MAIN.post(() -> listener.onError(err));
    }

    private static String displayName(ContentResolver cr, Uri uri) {
        try (Cursor cur = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getString(0);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static long fileSize(ContentResolver cr, Uri uri) {
        try (Cursor cur = cr.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getLong(0);
        } catch (Exception ignored) {
        }
        return -1;
    }
}
