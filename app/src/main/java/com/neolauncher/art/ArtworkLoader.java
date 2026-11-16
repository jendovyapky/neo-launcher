package com.neolauncher.art;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import com.neolauncher.data.AppEntry;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Obrazky karet (bannery her). Poradi zdroju:
 * <ol>
 *   <li>vlastni obrazek od uzivatele</li>
 *   <li>stazeny banner v cache na disku</li>
 *   <li>TV banner aplikace (android:banner)</li>
 *   <li>docasny nahradni obrazek (ikona na rozmazanem pozadi) a na pozadi
 *       pokus o stazeni banneru z online repozitaru</li>
 * </ol>
 * Online zdroje jsou stejne jako v Lightning Launcheru (threethan, GPL-3.0).
 * Hotove bitmapy jsou uz oriznute na presny pomer karty, takze kresleni
 * je jen jedno drawBitmap bez dalsiho pocitani.
 */
public final class ArtworkLoader {
    private static final String TAG = "NeoArt";

    /** Pomer stran karty (sirka / vyska) - dle preview. */
    public static final float CARD_ASPECT = 1.6f;

    private static final String[] BANNER_URLS_FIRST = {
            "https://raw.githubusercontent.com/threethan/QuestLauncherImages/main/banner/%s.jpg",
    };
    private static final String META_METADATA_URL =
            "https://raw.githubusercontent.com/threethan/MetaMetadata/main/data/common/%s.json";
    private static final String[] BANNER_URLS_FALLBACK = {
            "https://raw.githubusercontent.com/veticia/binaries/main/banners/%s.png",
    };

    /** Po neuspesnem stazeni zkusit znovu az za 3 dny. */
    private static final long MISS_RETRY_MS = 3L * 24 * 60 * 60 * 1000;
    /** Pri chybe site (offline) zkusit znovu za 10 minut. */
    private static final long NETWORK_RETRY_MS = 10L * 60 * 1000;
    private static final int SAVED_MAX_HEIGHT = 480;

    public interface Listener {
        void onArtworkChanged(String pkg);
    }

    private final Context ctx;
    private final File cacheDir;
    private final File customDir;
    private final SharedPreferences misses;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService disk = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "neo-art-disk");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final ExecutorService net = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "neo-art-net");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final LruCache<String, Bitmap> memory;
    /** Balicky, ktere maji v pameti jen docasny nahradni obrazek. */
    private final Set<String> fallbacks = ConcurrentHashMap.newKeySet();
    /** "Ambientni" barva obrazku (pro zari hovernute karty v barve hry). */
    private final Map<String, Integer> glowColors = new ConcurrentHashMap<>();
    private final Set<String> loading = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> networkRetryAt = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean onlineEnabled = true;
    private volatile int targetW = 400;
    private volatile int targetH = 250;
    /** Pozadovane velikosti obrazku od jednotlivych pohledu (mrizka, karusel) - plati nejvetsi. */
    private final Map<String, Integer> sizeRequests = new java.util.HashMap<>();
    /** Zvysuje se pri zmene velikosti - zahodi vysledky rozpracovanych nacteni. */
    private volatile int generation = 0;

    public ArtworkLoader(Context c) {
        ctx = c.getApplicationContext();
        cacheDir = new File(ctx.getFilesDir(), "art-cache");
        customDir = new File(ctx.getFilesDir(), "art-custom");
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        customDir.mkdirs();
        misses = ctx.getSharedPreferences("neo_art_miss", Context.MODE_PRIVATE);
        int maxKb = (int) Math.min(Runtime.getRuntime().maxMemory() / 1024 / 5, 128 * 1024);
        memory = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getAllocationByteCount() / 1024;
            }
        };
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /**
     * Velikost karty v pixelech (uz vcetne rezervy na zvetseni pri hoveru).
     * Zaokrouhluje se, aby zmena velikosti okna nenacitala obrazky porad dokola.
     */
    public void requestTargetSize(String client, int w) {
        sizeRequests.put(client, w);
        int max = 0;
        for (int v : sizeRequests.values()) max = Math.max(max, v);
        setTargetSize(max);
    }

    private void setTargetSize(int w) {
        int qw = Math.max(128, Math.min(960, ((w + 63) / 64) * 64));
        int qh = Math.round(qw / CARD_ASPECT);
        if (qw == targetW && qh == targetH) return;
        targetW = qw;
        targetH = qh;
        generation++;
        memory.evictAll();
        fallbacks.clear();
        loading.clear();
        notifyAllChanged();
    }

    /** Vypnuti stahovani z internetu (nastaveni). Uz stazene obrazky zustanou. */
    public void setOnlineEnabled(boolean enabled) {
        if (enabled == onlineEnabled) return;
        onlineEnabled = enabled;
        if (enabled) {
            // Karty s docasnym obrazkem zkusit stahnout znovu.
            networkRetryAt.clear();
            for (String pkg : fallbacks) memory.remove(pkg);
            fallbacks.clear();
            notifyAllChanged();
        }
    }

    public int targetWidth() {
        return targetW;
    }

    /** Neblokujici - vrati obrazek z pameti, nebo null a zacne ho nacitat. */
    public Bitmap get(AppEntry e) {
        Bitmap b = memory.get(e.pkg);
        if (b == null) schedule(e);
        return b;
    }

    /** Nacte obrazky vsech aplikaci predem, at je rolovani hned plynule. */
    public void prefetch(List<AppEntry> list) {
        for (AppEntry e : list) {
            if (memory.get(e.pkg) == null) schedule(e);
        }
    }

    /** Smaze stazeny obrazek a zkusi ho stahnout znovu. */
    public void reload(AppEntry e) {
        //noinspection ResultOfMethodCallIgnored
        cacheFile(e.pkg).delete();
        misses.edit().remove(e.pkg).apply();
        networkRetryAt.remove(e.pkg);
        memory.remove(e.pkg);
        fallbacks.remove(e.pkg);
        notifyChanged(e.pkg);
    }

    /** Smaze vsechny stazene obrazky (vlastni obrazky zustanou). */
    public void clearDownloaded() {
        File[] files = cacheDir.listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
        misses.edit().clear().apply();
        networkRetryAt.clear();
        generation++;
        memory.evictAll();
        fallbacks.clear();
        loading.clear();
        notifyAllChanged();
    }

    public boolean hasCustomImage(String pkg) {
        return customFile(pkg).exists();
    }

    /** Ulozi obrazek vybrany uzivatelem jako vlastni obrazek karty. */
    public void setCustomImage(AppEntry e, Uri uri, Runnable onDone) {
        disk.execute(() -> {
            boolean ok = false;
            try {
                Bitmap b = decodeUri(uri, 1600);
                if (b != null) {
                    saveScaled(b, customFile(e.pkg), 900);
                    ok = true;
                }
            } catch (Exception ex) {
                Log.w(TAG, "Vlastni obrazek nejde ulozit", ex);
            }
            final boolean success = ok;
            main.post(() -> {
                if (success) {
                    memory.remove(e.pkg);
                    fallbacks.remove(e.pkg);
                    notifyChanged(e.pkg);
                }
                if (onDone != null) onDone.run();
            });
        });
    }

    public void removeCustomImage(AppEntry e) {
        //noinspection ResultOfMethodCallIgnored
        customFile(e.pkg).delete();
        memory.remove(e.pkg);
        fallbacks.remove(e.pkg);
        notifyChanged(e.pkg);
    }

    // --- Nacitani ---------------------------------------------------------------

    private void schedule(AppEntry e) {
        if (!loading.add(e.pkg)) return;
        final int gen = generation;
        disk.execute(() -> {
            try {
                loadLocal(e, gen);
            } catch (Throwable t) {
                Log.w(TAG, "Nacteni obrazku selhalo: " + e.pkg, t);
                loading.remove(e.pkg);
            }
        });
    }

    private void loadLocal(AppEntry e, int gen) {
        final int w = targetW, h = targetH;

        File custom = customFile(e.pkg);
        if (custom.exists()) {
            Bitmap b = decodeFile(custom, w, h);
            if (b != null) {
                publish(e.pkg, coverCrop(b, w, h), false, gen);
                return;
            }
        }

        File cached = cacheFile(e.pkg);
        if (cached.exists()) {
            Bitmap b = decodeFile(cached, w, h);
            if (b != null) {
                publish(e.pkg, coverCrop(b, w, h), false, gen);
                return;
            }
        }

        if (e.hasBanner) {
            Bitmap b = pmBanner(e.pkg);
            if (b != null) {
                publish(e.pkg, coverCrop(b, w, h), false, gen);
                return;
            }
        }

        // Docasny nahradni obrazek hned, stazeni banneru na pozadi.
        Bitmap fb = composeFallback(e, w, h);
        final boolean tryDownload = shouldDownload(e);
        publish(e.pkg, fb, true, gen);
        if (tryDownload) {
            loading.add(e.pkg);
            net.execute(() -> {
                try {
                    downloadAndPublish(e, gen);
                } finally {
                    loading.remove(e.pkg);
                }
            });
        }
    }

    /** Barva zare v barve obrazku hry, nebo fallback, kdyz jeste neni spocitana. */
    public int glowColor(String pkg, int fallback) {
        Integer c = glowColors.get(pkg);
        return c != null ? c : fallback;
    }

    /**
     * Prumerna barva obrazku, vytazena do syta a svetla - zare ma "svitit"
     * barvou hry (jako ambientni svetlo na PS5/Apple TV). Pocita se na pozadi.
     */
    static int ambientColor(Bitmap b) {
        try {
            Bitmap s = Bitmap.createScaledBitmap(b, 8, 5, true);
            float r = 0, g = 0, bl = 0, wsum = 0;
            float[] hsv = new float[3];
            for (int y = 0; y < s.getHeight(); y++) {
                for (int x = 0; x < s.getWidth(); x++) {
                    int p = s.getPixel(x, y);
                    Color.colorToHSV(p, hsv);
                    // Syte a svetle pixely vazi vic nez sede/tmave.
                    float w = 0.15f + hsv[1] * hsv[2];
                    r += Color.red(p) * w;
                    g += Color.green(p) * w;
                    bl += Color.blue(p) * w;
                    wsum += w;
                }
            }
            int avg = Color.rgb(Math.round(r / wsum), Math.round(g / wsum), Math.round(bl / wsum));
            Color.colorToHSV(avg, hsv);
            if (hsv[1] < 0.12f) return 0xFFBFE6FF; // skoro sede -> jemne studene bila
            hsv[1] = Math.min(1f, Math.max(0.45f, hsv[1] * 1.4f));
            hsv[2] = Math.max(0.9f, hsv[2]);
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return 0xFFBFE6FF;
        }
    }

    private void publish(String pkg, Bitmap b, boolean isFallback, int gen) {
        final int glow = b != null ? ambientColor(b) : 0;
        main.post(() -> {
            loading.remove(pkg);
            if (gen != generation || b == null) return;
            // Nikdy neprepsat skutecny obrazek docasnym.
            if (isFallback && memory.get(pkg) != null && !fallbacks.contains(pkg)) return;
            memory.put(pkg, b);
            glowColors.put(pkg, glow);
            if (isFallback) fallbacks.add(pkg);
            else fallbacks.remove(pkg);
            notifyChanged(pkg);
        });
    }

    /** Po obnove ze zalohy (jine vlastni obrazky): zahodit obrazky v pameti. */
    public void clearMemory() {
        memory.evictAll();
        fallbacks.clear();
        glowColors.clear();
        notifyChanged(null);
    }

    private void notifyChanged(String pkg) {
        for (Listener l : listeners) l.onArtworkChanged(pkg);
    }

    private void notifyAllChanged() {
        for (Listener l : listeners) l.onArtworkChanged(null);
    }

    // --- Stahovani --------------------------------------------------------------

    private boolean shouldDownload(AppEntry e) {
        if (!onlineEnabled || e.isSystemPanel()) return false;
        if (e.pkg.startsWith("com.android.") || e.pkg.startsWith("com.google.android."))
            return false;
        long now = System.currentTimeMillis();
        Long retry = networkRetryAt.get(e.pkg);
        if (retry != null && now < retry) return false;
        long missAt = misses.getLong(e.pkg, 0);
        return now - missAt > MISS_RETRY_MS;
    }

    private void downloadAndPublish(AppEntry e, int gen) {
        final String id = e.pkg.replace(".mrf.", ".");
        boolean networkError = false;
        Bitmap found = null;
        try {
            for (String url : BANNER_URLS_FIRST) {
                found = downloadBitmap(String.format(url, id));
                if (found != null) break;
            }
            if (found == null) {
                byte[] json = httpGet(String.format(META_METADATA_URL, id), 256 * 1024);
                if (json != null) {
                    JSONObject o = new JSONObject(new String(json, StandardCharsets.UTF_8));
                    String landscape = o.optString("landscape", "");
                    if (!landscape.isEmpty()) found = downloadBitmap(landscape);
                }
            }
            if (found == null) {
                for (String url : BANNER_URLS_FALLBACK) {
                    found = downloadBitmap(String.format(url, id));
                    if (found != null) break;
                }
            }
        } catch (IOException ex) {
            networkError = true;
        } catch (Exception ex) {
            Log.w(TAG, "Chyba pri stahovani " + e.pkg, ex);
        }

        if (found == null) {
            if (networkError) {
                networkRetryAt.put(e.pkg, System.currentTimeMillis() + NETWORK_RETRY_MS);
            } else {
                misses.edit().putLong(e.pkg, System.currentTimeMillis()).apply();
            }
            return;
        }
        try {
            saveScaled(found, cacheFile(e.pkg), SAVED_MAX_HEIGHT);
        } catch (IOException ex) {
            Log.w(TAG, "Banner nejde ulozit", ex);
        }
        if (gen != generation) return;
        publish(e.pkg, coverCrop(found, targetW, targetH), false, gen);
    }

    /** @return bitmapa, nebo null kdyz soubor neexistuje (404). IOException = chyba site. */
    private Bitmap downloadBitmap(String url) throws IOException {
        byte[] data = httpGet(url, 12 * 1024 * 1024);
        if (data == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth < 40 || o.outHeight < 40) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, 1280, 800);
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    private static byte[] httpGet(String url, int maxBytes) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(4000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "NeoLauncher/2.0 (Meta Quest)");
            int code = c.getResponseCode();
            if (code == 404 || code == 410 || code == 400) return null;
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
                byte[] buf = new byte[16 * 1024];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > maxBytes) return null;
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            }
        } catch (FileNotFoundException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // --- Bitmapy ----------------------------------------------------------------

    private File cacheFile(String pkg) {
        return new File(cacheDir, safeName(pkg) + ".webp");
    }

    private File customFile(String pkg) {
        return new File(customDir, safeName(pkg) + ".webp");
    }

    private static String safeName(String pkg) {
        return pkg.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void saveScaled(Bitmap b, File f, int maxHeight) throws IOException {
        Bitmap s = b;
        if (b.getHeight() > maxHeight) {
            int w = Math.round(b.getWidth() * (maxHeight / (float) b.getHeight()));
            s = Bitmap.createScaledBitmap(b, Math.max(1, w), maxHeight, true);
        }
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            s.compress(Bitmap.CompressFormat.WEBP_LOSSY, 88, fos);
        }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
    }

    private static Bitmap decodeFile(File f, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, w, h);
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private Bitmap decodeUri(Uri uri, int max) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        if (o.outWidth <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, max, max);
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, o);
        }
    }

    private static int sampleSize(int w, int h, int reqW, int reqH) {
        int s = 1;
        while (w / (s * 2) >= reqW && h / (s * 2) >= reqH) s *= 2;
        return s;
    }

    /** Orizne obrazek na stred do presne velikosti w x h (jako CSS background-size: cover). */
    static Bitmap coverCrop(Bitmap src, int w, int h) {
        if (src == null) return null;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((w - src.getWidth() * scale) / 2f, (h - src.getHeight() * scale) / 2f);
        c.drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return out;
    }

    private Bitmap pmBanner(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            Drawable d = pm.getApplicationBanner(ai);
            return d == null ? null : drawableToBitmap(d, 640, 360);
        } catch (Exception e) {
            return null;
        }
    }

    private Drawable appIcon(AppEntry e) {
        PackageManager pm = ctx.getPackageManager();
        String[] candidates = e.isSystemPanel()
                ? new String[]{"com.oculus.panelapp.settings", "com.android.settings"}
                : new String[]{e.pkg};
        for (String p : candidates) {
            try {
                return pm.getApplicationIcon(p);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Bitmap drawableToBitmap(Drawable d, int maxW, int maxH) {
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            return ((BitmapDrawable) d).getBitmap();
        }
        int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : maxW;
        int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : maxH;
        float s = Math.min(1f, Math.min(maxW / (float) w, maxH / (float) h));
        w = Math.max(1, Math.round(w * s));
        h = Math.max(1, Math.round(h * s));
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }

    /**
     * Nahradni obrazek: ikona aplikace uprostred, pod ni rozmazane barvy
     * te same ikony. Pusobi to jako sklo podsvicene barvou aplikace.
     */
    private Bitmap composeFallback(AppEntry e, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        Drawable icon = appIcon(e);
        Bitmap iconBmp = icon != null ? drawableToBitmap(icon, 256, 256) : null;

        if (iconBmp != null) {
            // Barevne pozadi: ikona zmensena na par pixelu a zpet = hladky prechod barev.
            Bitmap tiny = Bitmap.createScaledBitmap(iconBmp, 4, 3, true);
            c.drawBitmap(tiny, new Rect(0, 0, 4, 3), new RectF(-w * 0.15f, -h * 0.15f,
                    w * 1.15f, h * 1.15f), p);
            p.setColor(0x73000000);
            c.drawRect(0, 0, w, h, p);
        } else {
            int[] pair = Placeholders.colorsFor(e.pkg);
            p.setShader(new LinearGradient(0, 0, w, h, pair[0], pair[1], Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
        }

        // Jemne svetlo shora = sklenena hloubka.
        p.setShader(new RadialGradient(w * 0.5f, h * 0.18f, w * 0.75f,
                0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        final float size = h * 0.46f;
        final float cx = w / 2f, cy = h * 0.43f;
        if (iconBmp != null) {
            // Mekky stin pod ikonou.
            Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
            sp.setColor(0x80000000);
            sp.setMaskFilter(new BlurMaskFilter(size * 0.12f, BlurMaskFilter.Blur.NORMAL));
            float r = size * 0.22f;
            c.drawRoundRect(cx - size / 2f, cy - size / 2f + size * 0.06f,
                    cx + size / 2f, cy + size / 2f + size * 0.06f, r, r, sp);
            c.drawBitmap(iconBmp, null, new RectF(cx - size / 2f, cy - size / 2f,
                    cx + size / 2f, cy + size / 2f), p);
        } else {
            p.setColor(Color.WHITE);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, 700, false));
            p.setTextSize(size * 0.9f);
            String letter = e.systemLabel.isEmpty() ? "?"
                    : e.systemLabel.substring(0, 1).toUpperCase();
            c.drawText(letter, cx, cy - (p.descent() + p.ascent()) / 2f, p);
        }
        return out;
    }
}
