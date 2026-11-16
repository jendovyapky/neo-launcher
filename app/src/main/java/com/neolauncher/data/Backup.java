package com.neolauncher.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Zaloha nastaveni Nea do jednoho souboru .zip (a obnova): vsechna nastaveni,
 * rucni poradi, oblibene, prejmenovani, skryte aplikace, naposledy spustene
 * a vlastni obrazky her. Soubor jde poslat i kamaradovi - poradi a jmena se
 * pridrzi balicku, ktere ma nainstalovane.
 */
public final class Backup {
    private static final String TAG = "NeoBackup";
    /** Soubory SharedPreferences, ktere se zalohuji. */
    private static final String[] PREFS = {"neo", "neo_recents", "neo_launch_counts"};
    private static final String CUSTOM_DIR = "art-custom";
    private static final String JSON = "neo-zaloha.json";
    private static final int VERSION = 1;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Backup() {}

    /** Navrzeny nazev souboru zalohy. */
    public static String fileName() {
        return "neo-zaloha-" + new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
                .format(new java.util.Date()) + ".zip";
    }

    /** @param done null = OK, jinak text chyby (na hlavnim vlakne) */
    public static void export(Context c, Uri out, Consumer<String> done) {
        final Context app = c.getApplicationContext();
        Executors.newSingleThreadExecutor().execute(() -> {
            String err = null;
            try (OutputStream os = app.getContentResolver().openOutputStream(out);
                 ZipOutputStream zip = new ZipOutputStream(os)) {
                final JSONObject root = new JSONObject();
                root.put("version", VERSION);
                final JSONObject prefs = new JSONObject();
                for (String name : PREFS) prefs.put(name, toJson(app.getSharedPreferences(name, Context.MODE_PRIVATE)));
                root.put("prefs", prefs);
                zip.putNextEntry(new ZipEntry(JSON));
                zip.write(root.toString(1).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                final File[] images = new File(app.getFilesDir(), CUSTOM_DIR).listFiles();
                if (images != null) {
                    for (File f : images) {
                        if (!f.isFile()) continue;
                        zip.putNextEntry(new ZipEntry(CUSTOM_DIR + "/" + f.getName()));
                        try (InputStream is = new FileInputStream(f)) {
                            copy(is, zip);
                        }
                        zip.closeEntry();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Zaloha selhala", e);
                err = "Zálohu se nepodařilo uložit";
            }
            final String e = err;
            MAIN.post(() -> done.accept(e));
        });
    }

    /** Obnovi nastaveni ze zalohy. Po dokonceni je potreba launcher znovu nacist. */
    public static void restore(Context c, Uri in, Consumer<String> done) {
        final Context app = c.getApplicationContext();
        Executors.newSingleThreadExecutor().execute(() -> {
            String err = null;
            try (InputStream is = app.getContentResolver().openInputStream(in);
                 ZipInputStream zip = new ZipInputStream(is)) {
                JSONObject root = null;
                final File customDir = new File(app.getFilesDir(), CUSTOM_DIR);
                boolean imagesCleared = false;
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    final String name = e.getName();
                    if (JSON.equals(name)) {
                        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
                        copy(zip, buf);
                        root = new JSONObject(buf.toString("UTF-8"));
                    } else if (name.startsWith(CUSTOM_DIR + "/") && !e.isDirectory()) {
                        // Jen obycejne jmeno souboru (zadne "../"), at zaloha nemuze psat jinam.
                        final String file = name.substring(CUSTOM_DIR.length() + 1);
                        if (file.isEmpty() || file.contains("/") || file.contains("\\") || file.startsWith(".")) continue;
                        if (!imagesCleared) {
                            imagesCleared = true;
                            customDir.mkdirs();
                            final File[] old = customDir.listFiles();
                            if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                                f.delete();
                        }
                        try (OutputStream os = new FileOutputStream(new File(customDir, file))) {
                            copy(zip, os);
                        }
                    }
                    zip.closeEntry();
                }
                if (root == null) {
                    err = "Tohle není záloha Nea";
                } else {
                    final JSONObject prefs = root.getJSONObject("prefs");
                    for (String name : PREFS) {
                        if (prefs.has(name)) fromJson(app.getSharedPreferences(name, Context.MODE_PRIVATE),
                                prefs.getJSONObject(name));
                    }
                }
            } catch (Exception ex) {
                Log.w(TAG, "Obnova selhala", ex);
                err = "Zálohu se nepodařilo načíst";
            }
            final String result = err;
            MAIN.post(() -> done.accept(result));
        });
    }

    // --- SharedPreferences <-> JSON (s typem, at se vrati presne) -------------------

    private static JSONObject toJson(SharedPreferences sp) throws Exception {
        final JSONObject o = new JSONObject();
        for (Map.Entry<String, ?> en : sp.getAll().entrySet()) {
            final Object v = en.getValue();
            final JSONObject item = new JSONObject();
            if (v instanceof Boolean) item.put("t", "b").put("v", v);
            else if (v instanceof Integer) item.put("t", "i").put("v", v);
            else if (v instanceof Long) item.put("t", "l").put("v", v);
            else if (v instanceof Float) item.put("t", "f").put("v", ((Float) v).doubleValue());
            else if (v instanceof String) item.put("t", "s").put("v", v);
            else if (v instanceof Set) {
                final JSONArray arr = new JSONArray();
                for (Object s : (Set<?>) v) arr.put(String.valueOf(s));
                item.put("t", "ss").put("v", arr);
            } else continue;
            o.put(en.getKey(), item);
        }
        return o;
    }

    private static void fromJson(SharedPreferences sp, JSONObject o) throws Exception {
        final SharedPreferences.Editor ed = sp.edit().clear();
        final Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            final String k = keys.next();
            final JSONObject item = o.getJSONObject(k);
            switch (item.getString("t")) {
                case "b":
                    ed.putBoolean(k, item.getBoolean("v"));
                    break;
                case "i":
                    ed.putInt(k, item.getInt("v"));
                    break;
                case "l":
                    ed.putLong(k, item.getLong("v"));
                    break;
                case "f":
                    ed.putFloat(k, (float) item.getDouble("v"));
                    break;
                case "s":
                    ed.putString(k, item.getString("v"));
                    break;
                case "ss": {
                    final JSONArray arr = item.getJSONArray("v");
                    final Set<String> set = new HashSet<>();
                    for (int i = 0; i < arr.length(); i++) set.add(arr.getString(i));
                    ed.putStringSet(k, set);
                    break;
                }
                default:
                    break;
            }
        }
        ed.commit();
    }

    private static void copy(InputStream is, OutputStream os) throws java.io.IOException {
        final byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
    }
}
