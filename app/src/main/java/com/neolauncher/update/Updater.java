package com.neolauncher.update;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aktualizace launcheru primo z GitHubu. CI (.github/workflows/build.yml) po
 * kazdem buildu zverejni APK jako GitHub Release; tady se najde nejnovejsi
 * a nainstaluje pres system (PackageInstaller - uzivatel potvrdi dialog).
 * <p>
 * Funguje jen kdyz jsou releasy verejne (verejny repozitar). U soukromeho
 * repozitare GitHub bez prihlaseni vraci 404 -> {@link Status#NOT_PUBLIC}.
 */
public final class Updater {
    private static final String TAG = "NeoUpdater";

    /**
     * Repozitare, kde se hledaji releasy (prvni, ktery odpovi). Druhy je pro
     * pripad, ze kod zustane soukromy a APK se budou zverejnovat zvlast.
     */
    private static final String[] FEEDS = {
            // Repo se 2026-10-12 presunulo pod jendovyapky (GitHub stare jmeno presmeruje, ale radsi primo).
            "jendovyapky/neo-launcher",
            "lichicks/neo-launcher",
            "lichicks/neo-launcher-releases",
    };
    private static final Pattern VERSION_CODE = Pattern.compile("versionCode:\\s*(\\d+)");
    private static final Pattern TAG_BUILD = Pattern.compile("^v?2\\.0\\.(\\d+)$");

    public enum Status {UP_TO_DATE, AVAILABLE, NOT_PUBLIC, OFFLINE}

    public static final class Release {
        public final long versionCode;
        public final String versionName;
        public final String notes;
        public final String apkUrl;
        public final boolean prerelease;

        Release(long versionCode, String versionName, String notes, String apkUrl, boolean prerelease) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.notes = notes;
            this.apkUrl = apkUrl;
            this.prerelease = prerelease;
        }
    }

    public interface CheckCallback {
        void onResult(Status status, Release release);
    }

    public interface DownloadCallback {
        void onProgress(int percent);

        void onDone(File apk);

        void onError(String message);
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "neo-updater");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Updater() {}

    public static long installedVersionCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Zjisti, jestli je na GitHubu novejsi verze. Vysledek prijde na hlavnim vlakne. */
    public static void check(Context c, boolean includeTestBuilds, CheckCallback cb) {
        final long current = installedVersionCode(c);
        IO.execute(() -> {
            Status status = Status.NOT_PUBLIC;
            Release best = null;
            for (String repo : FEEDS) {
                try {
                    best = newest(repo, includeTestBuilds);
                    status = best != null && best.versionCode > current
                            ? Status.AVAILABLE : Status.UP_TO_DATE;
                    break;
                } catch (FileNotFoundException e) {
                    // 404 = repozitar neexistuje nebo je soukromy; zkusit dalsi.
                } catch (Exception e) {
                    Log.w(TAG, "Kontrola aktualizaci selhala (" + repo + ")", e);
                    status = Status.OFFLINE;
                }
            }
            final Status s = status;
            final Release r = best;
            MAIN.post(() -> cb.onResult(s, r));
        });
    }

    private static Release newest(String repo, boolean includeTestBuilds) throws Exception {
        byte[] body = get("https://api.github.com/repos/" + repo + "/releases?per_page=30",
                512 * 1024, null);
        JSONArray arr = new JSONArray(new String(body, StandardCharsets.UTF_8));
        Release best = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (o.optBoolean("draft", false)) continue;
            boolean pre = o.optBoolean("prerelease", false);
            if (pre && !includeTestBuilds) continue;
            String tag = o.optString("tag_name", "");
            String notes = o.optString("body", "");
            long code = versionCodeOf(tag, notes);
            if (code <= 0) continue;
            String apk = null;
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                // Release ma i doplnek Meta tlacitka - brat APK Nea (NeoLauncher-*.apk).
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject a = assets.getJSONObject(j);
                    final String name = a.optString("name", "");
                    if (!name.endsWith(".apk") || name.contains("Meta")) continue;
                    if (apk == null || name.startsWith("NeoLauncher")) {
                        apk = a.optString("browser_download_url", null);
                        if (name.startsWith("NeoLauncher")) break;
                    }
                }
            }
            if (apk == null) continue;
            if (best == null || code > best.versionCode) {
                String name = tag.startsWith("v") ? tag.substring(1) : tag;
                best = new Release(code, name, cleanNotes(notes), apk, pre);
            }
        }
        return best;
    }

    private static long versionCodeOf(String tag, String notes) {
        Matcher m = VERSION_CODE.matcher(notes);
        if (m.find()) return Long.parseLong(m.group(1));
        Matcher t = TAG_BUILD.matcher(tag);
        if (t.find()) return 2000 + Long.parseLong(t.group(1));
        return -1;
    }

    /** Z poznamek k releasu vyhodi technicke radky (versionCode, vetev, podpisy commitu). */
    private static String cleanNotes(String notes) {
        StringBuilder sb = new StringBuilder();
        for (String line : notes.split("\n")) {
            String l = line.trim();
            if (l.startsWith("versionCode:") || l.startsWith("vetev:")
                    || l.startsWith("Co-Authored-By:") || l.startsWith("Claude-Session:")) continue;
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    /** Stahne APK do cache aplikace. Callbacky prijdou na hlavnim vlakne. */
    public static void download(Context c, Release r, DownloadCallback cb) {
        final File out = new File(c.getCacheDir(), "neo-update.apk");
        IO.execute(() -> {
            try {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                get(r.apkUrl, 200 * 1024 * 1024, out, pct -> MAIN.post(() -> cb.onProgress(pct)));
                MAIN.post(() -> cb.onDone(out));
            } catch (Exception e) {
                Log.w(TAG, "Stazeni aktualizace selhalo", e);
                MAIN.post(() -> cb.onError("Stažení se nepovedlo"));
            }
        });
    }

    /**
     * Preda APK systemu k instalaci. Android/Quest zobrazi potvrzovaci dialog;
     * po instalaci se launcher sam zavre a staci ho znovu otevrit.
     */
    public static void install(Context c, File apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        int id = pi.createSession(params);
        PackageInstaller.Session session = pi.openSession(id);
        try {
            try (OutputStream os = session.openWrite("neo.apk", 0, apk.length());
                 InputStream is = new FileInputStream(apk)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                session.fsync(os);
            }
            Intent i = new Intent(c, InstallReceiver.class);
            PendingIntent p = PendingIntent.getBroadcast(c, id, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(p.getIntentSender());
        } catch (IOException | RuntimeException e) {
            session.abandon();
            throw e;
        } finally {
            session.close();
        }
    }

    private interface Progress {
        void onPercent(int pct);
    }

    private static byte[] get(String url, int maxBytes, File saveTo) throws IOException {
        return get(url, maxBytes, saveTo, null);
    }

    /** HTTP GET; 404 -> FileNotFoundException. Kdyz saveTo != null, uklada do souboru. */
    private static byte[] get(String url, int maxBytes, File saveTo, Progress progress)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "NeoLauncher-Updater");
            c.setRequestProperty("Accept", url.contains("api.github.com")
                    ? "application/vnd.github+json" : "application/octet-stream");
            int code = c.getResponseCode();
            if (code == 404) throw new FileNotFoundException(url);
            if (code != 200) throw new IOException("HTTP " + code + " " + url);
            long total = c.getContentLengthLong();
            try (InputStream in = c.getInputStream();
                 OutputStream os = saveTo != null ? new FileOutputStream(saveTo)
                         : new ByteArrayOutputStream(64 * 1024)) {
                byte[] buf = new byte[32 * 1024];
                long read = 0;
                int n, lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    read += n;
                    if (read > maxBytes) throw new IOException("Soubor je prilis velky");
                    os.write(buf, 0, n);
                    if (progress != null && total > 0) {
                        int pct = (int) (read * 100 / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            progress.onPercent(pct);
                        }
                    }
                }
                return os instanceof ByteArrayOutputStream
                        ? ((ByteArrayOutputStream) os).toByteArray() : null;
            }
        } finally {
            c.disconnect();
        }
    }
}
