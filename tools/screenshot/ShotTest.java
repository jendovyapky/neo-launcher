package neoshot;

import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PixelCopy;

import com.neolauncher.art.ArtworkLoader;
import com.neolauncher.data.AppEntry;
import com.neolauncher.data.Prefs;
import com.neolauncher.ui.CarouselView;
import com.neolauncher.ui.NeoLauncherView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w1176dp-h664dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ShotTest {
    static final String[][] GAMES = {
            {"com.beatgames.beatsaber", "Beat Saber"},
            {"com.cloudheadgames.pistolwhip", "Pistol Whip"},
            {"com.kluge.SynthRiders", "Synth Riders"},
            {"com.MightyCoconut.WalkaboutMiniGolf", "Walkabout Mini Golf"},
            {"com.AnotherAxiom.GorillaTag", "Gorilla Tag"},
            {"com.StressLevelZero.BONELAB", "BONELAB"},
            {"com.Warpfrog.BladeAndSorcery", "Blade & Sorcery: Nomad"},
            {"com.owlchemylabs.jobsimulator", "Job Simulator"},
            {"com.vrchat.oculus.quest", "VRChat"},
            {"com.AgainstGravity.RecRoom", "Rec Room"},
            {"com.fitxr.boxvr", "FitXR"},
            {"com.bigscreenvr.bigscreen", "Bigscreen Beta"},
            {"VirtualDesktop.Android", "Virtual Desktop"},
    };

    private static void pump(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(20);
        }
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void capture(Activity a, android.view.View v, String out) throws Exception {
        Bitmap bmp = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        final int[] res = {-1};
        PixelCopy.request(a.getWindow(), bmp, r -> res[0] = r, new Handler(Looper.getMainLooper()));
        shadowOf(Looper.getMainLooper()).idle();
        System.out.println("PixelCopy result=" + res[0] + " size=" + bmp.getWidth() + "x" + bmp.getHeight());
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
    }

    private static void hover(android.view.View v, float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, x, y, 0);
        e.setSource(InputDevice.SOURCE_MOUSE);
        v.dispatchGenericMotionEvent(e);
        e.recycle();
    }

    @Test
    public void shot() throws Exception {
        System.setProperty("robolectric.pixelCopyRenderMode", "hardware");
        final String banners = System.getProperty("neo.banners");
        final String outDir = System.getProperty("neo.out");

        org.robolectric.android.controller.ActivityController<Activity> ctl =
                Robolectric.buildActivity(Activity.class);
        ctl.get().setTheme(android.R.style.Theme_Material_NoActionBar_Fullscreen);
        Activity a = ctl.setup().get();
        a.getWindow().setBackgroundDrawable(new ColorDrawable(0));

        File cache = new File(a.getFilesDir(), "art-cache");
        cache.mkdirs();
        for (String[] g : GAMES) {
            File src = new File(banners, g[0] + ".img");
            Files.copy(src.toPath(), new File(cache, g[0] + ".webp").toPath());
        }

        Prefs prefs = new Prefs(a);
        ArtworkLoader art = new ArtworkLoader(a);
        art.setOnlineEnabled(false);
        NeoLauncherView v = new NeoLauncherView(a);
        v.bind(prefs, art);
        a.setContentView(v);
        pump(200);
        System.out.println("view size " + v.getWidth() + "x" + v.getHeight());

        List<AppEntry> apps = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (String[] g : GAMES) {
            apps.add(new AppEntry(g[0], g[1], AppEntry.TYPE_VR, false));
            labels.add(g[1]);
        }
        // Oblibena hra (hvezdicka) a nova hra (stitek NOVE) - jen pro nahled.
        prefs.setFavorite("com.beatgames.beatsaber", true);
        final com.neolauncher.data.AppRepository.Signals sig = new com.neolauncher.data.AppRepository.Signals() {
            @Override public long playtimeMs(String pkg) { return 0; }
            @Override public long lastUsed(String pkg) { return 0; }
            @Override public boolean isNew(AppEntry e) { return e.pkg.equals("com.kluge.SynthRiders"); }
        };
        v.setSignals(sig);
        v.setApps(apps, labels, false);
        v.setBattery(92, true, true);
        pump(2500); // obrazky se nacitaji na pozadi
        capture(a, v, outDir + "/warmup.png");
        pump(1500);
        capture(a, v, outDir + "/neo-idle.png");
        // Nastup karet pri otevreni (playIntro) - snimek v prvni tretine animace.
        v.playIntro();
        // Nastup se spusti az prvnim vykreslenym snimkem (+ kratka pauza) - pak tretina animace.
        capture(a, v, outDir + "/warmup.png");
        Thread.sleep(180 + 200);
        shadowOf(Looper.getMainLooper()).idle();
        capture(a, v, outDir + "/neo-intro.png");
        pump(900);

        final float d = a.getResources().getDisplayMetrics().density;
        // Kousek odrolovat (thumbstick), at prvni rada zajede pod matnou listu.
        {
            long t = SystemClock.uptimeMillis();
            MotionEvent.PointerProperties[] pp = {new MotionEvent.PointerProperties()};
            pp[0].id = 0;
            pp[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
            MotionEvent.PointerCoords[] pc = {new MotionEvent.PointerCoords()};
            pc[0].x = 600 * d;
            pc[0].y = 400 * d;
            pc[0].setAxisValue(MotionEvent.AXIS_VSCROLL, -Float.parseFloat(System.getProperty("neo.scroll", "0.9")));
            MotionEvent e = MotionEvent.obtain(t, t, MotionEvent.ACTION_SCROLL, 1, pp, pc, 0, 0,
                    1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
            v.dispatchGenericMotionEvent(e);
            e.recycle();
            for (int i = 0; i < 90; i++) {
                capture(a, v, outDir + "/scrolling.png"); // kazdy snimek posune fyziku rolovani
                Thread.sleep(16);
            }
        }
        System.out.println("scroll done");
        // Poloha karty (sloupec 1, rada 1) ze skutecne geometrie view (px).
        float cardW = (Float) f(v, "cardW"), cardH = (Float) f(v, "cardH"), gp = (Float) f(v, "gap");
        float left = (Float) f(v, "gridLeft") + (cardW + gp);
        float top = (Float) f(v, "gridTop") + (cardH + gp) + (Float) f(v, "scrollCur");
        hover(v, left + cardW * 0.5f, top + cardH * 0.5f);
        pump(150);
        hover(v, left + cardW * 0.80f, top + cardH * 0.28f);
        pump(900);
        dumpState(v);
        System.out.println("glow BONELAB=" + Integer.toHexString(art.glowColor("com.StressLevelZero.BONELAB", 0)));
        capture(a, v, outDir + "/neo-hover.png");
        // Laser u praveho okraje panelu (karta vpravo nahore) - hrana skla chyta svetlo.
        float rightLeft = (Float) f(v, "gridLeft") + 3 * (cardW + gp);
        hover(v, rightLeft + cardW * 0.5f, top - (cardH + gp) + cardH * 0.5f);
        pump(150);
        hover(v, rightLeft + cardW * 0.92f, top - (cardH + gp) + cardH * 0.12f);
        pump(1600);
        capture(a, v, outDir + "/neo-edge.png");
        dumpState(v);

        // Kliknuti na kartu -> animace spusteni "kukatko", 6x zpomalena
        // (jako "Meritko animace" ve vyvojarskych volbach), snimky po fazich.
        java.lang.reflect.Method setScale =
                android.animation.ValueAnimator.class.getDeclaredMethod("setDurationScale", float.class);
        setScale.setAccessible(true);
        setScale.invoke(null, 6f);
        float tx = left + cardW * 0.80f, ty = top + cardH * 0.28f;
        long t0 = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, tx, ty, 0);
        v.dispatchTouchEvent(down);
        MotionEvent up = MotionEvent.obtain(t0, t0 + 80, MotionEvent.ACTION_UP, tx, ty, 0);
        v.dispatchTouchEvent(up);
        long start = System.currentTimeMillis();
        long[] at = {600, 1900, 2700, 3700, 4700, 6000};
        for (int i = 0; i < at.length; i++) {
            long wait = start + at[i] - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            shadowOf(Looper.getMainLooper()).idle();
            capture(a, v, outDir + "/neo-launch-" + (i + 1) + ".png");
            System.out.println("launch frame " + (i + 1) + " at " + (System.currentTimeMillis() - start) + " ms");
        }
        setScale.invoke(null, 1f);

        // --- Karusel (testovaci rezim) ---
        CarouselView car = new CarouselView(a);
        final long day = 24L * 60 * 60 * 1000;
        final long nowMs = System.currentTimeMillis();
        car.bind(prefs, art, new CarouselView.Stats() {
            @Override public boolean hasUsageAccess() { return true; }
            @Override public long playtimeMs(String pkg) { return (42L * 60 + 17) * 60 * 1000 + pkg.length() * 3_600_000L; }
            @Override public long lastUsed(String pkg) { return nowMs - day - 3_600_000L; }
            @Override public int launchCount(String pkg) { return 23; }
            @Override public long installTime(String pkg) { return nowMs - 420 * day; }
        });
        car.setBattery(92, true, true);
        a.setContentView(car);
        pump(300);
        car.setApps(apps, labels, 0);
        car.show();
        pump(2500); // obrazky na vysku se skladaji na pozadi
        for (int i = 0; i < 5; i++) {
            scroll(car, car.getWidth() / 2f, 300 * d, MotionEvent.AXIS_HSCROLL, 1f);
            pump(260);
        }
        pump(1200);
        hover(car, car.getWidth() / 2f + 80 * d, car.getHeight() * 0.43f - 60 * d);
        pump(700);
        capture(a, car, outDir + "/neo-carousel.png");
        scroll(car, car.getWidth() / 2f, 300 * d, MotionEvent.AXIS_HSCROLL, 1f);
        Thread.sleep(120);
        shadowOf(Looper.getMainLooper()).idle();
        capture(a, car, outDir + "/neo-carousel-move.png");
        pump(1200);

        // --- Rychle menu (vyjede z hodin/baterie, launcher ustoupi do hloubky) ---
        android.widget.FrameLayout root = new android.widget.FrameLayout(a);
        NeoLauncherView grid = new NeoLauncherView(a);
        grid.bind(prefs, art);
        root.addView(grid);
        com.neolauncher.ui.OverlayHost overlay = new com.neolauncher.ui.OverlayHost(a);
        overlay.setListener(new com.neolauncher.ui.OverlayHost.Listener() {
            @Override public void onOverlayShown() { grid.setInteractive(false); grid.setModalBlur(true); }
            @Override public void onOverlayClosed() { grid.setModalBlur(false); grid.setInteractive(true); }
        });
        root.addView(overlay);
        a.setContentView(root);
        pump(300);
        grid.setSignals(sig);
        grid.setApps(apps, labels, false);
        grid.setBattery(14, false, false); // slaba baterie - cervena zare
        pump(1500);
        capture(a, root, outDir + "/warmup.png"); // Robolectric kresli jen pri snimku - az pak zna polohu hodin
        // Ziva bublina v ornamentu (instalace APK s krouzkem prubehu).
        grid.showLive(com.neolauncher.ui.Icons.PACKAGE_PLUS, "Instaluji Beat Saber · 64 %",
                com.neolauncher.ui.Palette.COBALT_LIGHT, 0.64f, 0);
        pump(1100);
        capture(a, root, outDir + "/neo-live.png");
        grid.clearLive();
        pump(900);
        // Presouvani: podrzet kartu (sloupec 1, rada 0), zvednout a tahnout doprava dolu -
        // ostatni karty se trasou, uhnou a ukaze se "jamka", kam karta dopadne.
        {
            float cw = (Float) f(grid, "cardW"), ch = (Float) f(grid, "cardH"), g = (Float) f(grid, "gap");
            float sx = (Float) f(grid, "gridLeft") + (cw + g) + cw * 0.5f;
            float sy = (Float) f(grid, "gridTop") + (Float) f(grid, "scrollCur") + ch * 0.5f;
            hover(grid, sx, sy);
            pump(400);
            long dt = SystemClock.uptimeMillis();
            MotionEvent dn = MotionEvent.obtain(dt, dt, MotionEvent.ACTION_DOWN, sx, sy, 0);
            grid.dispatchTouchEvent(dn);
            dn.recycle();
            java.lang.reflect.Method lp = NeoLauncherView.class.getDeclaredMethod("onLongPress");
            lp.setAccessible(true);
            lp.invoke(grid);
            pump(200);
            for (int i = 1; i <= 24; i++) {
                float k = i / 24f;
                MotionEvent mv = MotionEvent.obtain(dt, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                        sx + (cw + g) * 1.15f * k, sy + (ch + g) * 0.72f * k, 0);
                grid.dispatchTouchEvent(mv);
                mv.recycle();
                capture(a, root, outDir + "/warmup.png");
                Thread.sleep(16);
            }
            pump(260);
            capture(a, root, outDir + "/neo-drag.png");
            MotionEvent cn = MotionEvent.obtain(dt, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, sx, sy, 0);
            grid.dispatchTouchEvent(cn);
            cn.recycle();
            pump(1200);
        }
        // Lista bezici aplikace (3x Meta ze hry): Pokracovat / Ukoncit / skryt.
        grid.setRunningApp(apps.get(0), "Beat Saber");
        hover(grid, grid.getWidth() / 2f + 40 * d, grid.getHeight() - 60 * d);
        pump(1100);
        capture(a, root, outDir + "/neo-running.png");
        grid.setRunningApp(null, null);
        pump(900);
        com.neolauncher.ui.QuickMenuView menu = new com.neolauncher.ui.QuickMenuView(a,
                new com.neolauncher.ui.QuickMenuView.Actions() {
                    @Override public void openTarget(int t) { }
                    @Override public void requestBrightnessAccess() { }
                    @Override public void launch(AppEntry app) { }
                });
        menu.setBattery(14, false, false);
        menu.setBatteryInfo("vydrží asi 25 min");
        menu.setLastPlayed(apps.get(5), "BONELAB", "Naposledy hráno · včera", art.get(apps.get(5)));
        // Leva lista po najeti (rozbalena s popisky).
        android.graphics.RectF gear = grid.settingsRect();
        hover(grid, gear.centerX() - gear.width() / 2f + 20 * d, gear.centerY());
        pump(700);
        capture(a, root, outDir + "/neo-rail.png");
        hover(grid, grid.getWidth() / 2f, grid.getHeight() / 2f);
        pump(500);
        overlay.showSide(menu, grid.frameRect(), com.neolauncher.ui.QuickMenuView.sideWidth(a));
        Thread.sleep(90);
        shadowOf(Looper.getMainLooper()).idle();
        capture(a, root, outDir + "/neo-quickmenu-open.png");
        pump(1200);
        hover(menu, 100 * d, 300 * d);
        pump(500);
        capture(a, root, outDir + "/neo-quickmenu.png");

        // --- Nastaveni jako dashboard: vsech 5 stranek v tmavem skle (vychozi) ---
        overlay.close();
        pump(600);
        android.view.View sheet = com.neolauncher.ui.SettingsSheet.build(a, prefs,
                new com.neolauncher.data.AppRepository(a, prefs), art, () -> { }, () -> { }, () -> { },
                overlay::close);
        overlay.show(sheet, null, grid.settingsRect(), grid.frameRect(), Math.round(1000 * d));
        pump(1500);
        capture(a, root, outDir + "/neo-settings.png");
        final String[] pages = {"Aplikace", "Quest", "Aktualizace", "O Neo"};
        for (int i = 0; i < pages.length; i++) {
            android.view.View pill = findText(sheet, pages[i]);
            if (pill != null) pill.performClick();
            pump(1300);
            capture(a, root, outDir + "/neo-settings-" + (i + 2) + ".png");
        }

        // --- Svetly styl skla (panel i dialogy, jako po prepnuti v nastaveni) ---
        overlay.close();
        pump(600);
        prefs.setGlassStyle(com.neolauncher.data.Prefs.GLASS_VISION);
        com.neolauncher.ui.Glass.setStyle(true);
        pump(300);
        capture(a, root, outDir + "/neo-idle-vision.png");
        android.view.View light = com.neolauncher.ui.SettingsSheet.build(a, prefs,
                new com.neolauncher.data.AppRepository(a, prefs), art, () -> { }, () -> { }, () -> { },
                overlay::close);
        overlay.show(light, null, grid.settingsRect(), grid.frameRect(), Math.round(1000 * d));
        pump(1500);
        capture(a, root, outDir + "/neo-settings-light.png");
        overlay.close();
        pump(600);
        prefs.setGlassStyle(com.neolauncher.data.Prefs.GLASS_DARK);
        com.neolauncher.ui.Glass.setStyle(false);

        // --- Hledani ---
        overlay.close();
        pump(500);
        android.view.View search = com.neolauncher.ui.SearchSheet.build(a, new com.neolauncher.ui.SearchSheet.Source() {
            @Override public List<AppEntry> apps() { return apps; }
            @Override public String label(AppEntry e) { return e.systemLabel; }
            @Override public long lastUsed(String pkg) { return pkg.contains("BONELAB") ? 2 : pkg.contains("beat") ? 1 : 0; }
            @Override public android.graphics.Bitmap art(AppEntry e) { return art.get(e); }
        }, e -> { }, overlay::close);
        overlay.show(search, null, grid.settingsRect(), grid.frameRect(), Math.round(640 * d));
        pump(400);
        android.widget.EditText field = findEdit(search);
        if (field != null) field.setText("sab");
        pump(900);
        capture(a, root, outDir + "/neo-search.png");

        // --- Co je noveho ---
        overlay.close();
        pump(500);
        android.view.View news = com.neolauncher.ui.WhatsNewSheet.build(a, "2.0.33",
                new java.io.FileInputStream(System.getProperty("neo.novinky")), overlay::close);
        overlay.show(news, null, null, grid.frameRect(), Math.round(620 * d));
        pump(1200);
        capture(a, root, outDir + "/neo-whatsnew.png");

        // --- Menu karty (podrzet kartu) ---
        overlay.close();
        pump(500);
        final AppEntry first = apps.get(0);
        final android.graphics.RectF cardAt = new android.graphics.RectF(grid.frameRect().left + 24 * d, grid.frameRect().top + 50 * d,
                grid.frameRect().left + 290 * d, grid.frameRect().top + 216 * d);
        android.view.View menuView = com.neolauncher.ui.AppMenu.build(a, first, first.systemLabel, "6,4 GB", false, true,
                new com.neolauncher.ui.AppMenu.Actions() {
                    @Override public void launch() {}
                    @Override public void favorite() {}
                    @Override public void rename() {}
                    @Override public void pickImage() {}
                    @Override public void removeImage() {}
                    @Override public void reloadImage() {}
                    @Override public void hide() {}
                    @Override public void info() {}
                    @Override public void uninstall() {}
                });
        overlay.show(menuView, cardAt, grid.frameRect(), Math.round(300 * d));
        pump(1200);
        capture(a, root, outDir + "/neo-appmenu.png");

        // --- Vypnout Quest (Uspat / Restartovat / Vypnout) ---
        overlay.close();
        pump(500);
        android.view.View power = com.neolauncher.ui.PowerSheet.build(a, new com.neolauncher.ui.PowerSheet.Actions() {
            @Override public void power(int what) {}
            @Override public void cancel() {}
        });
        overlay.show(power, null, null, grid.frameRect(), Math.round(600 * d));
        pump(1200);
        capture(a, root, outDir + "/neo-power.png");

        // --- Nabidka aktualizace doplnku Meta tlacitka ---
        overlay.close();
        pump(500);
        android.view.View prompt = com.neolauncher.ui.ConfirmSheet.build(a, com.neolauncher.ui.Icons.META,
                "Nová verze Meta tlačítka", "Lépe pozná hru na pozadí a menu Questu ve hře, upozorní na slabou "
                        + "baterii i ve hře a umí vypnout a restartovat Quest. Quest se zeptá, jestli doplněk aktualizovat.",
                "Aktualizovat", "Později", () -> { }, () -> { });
        overlay.show(prompt, null, null, grid.frameRect(), Math.round(520 * d));
        pump(1200);
        capture(a, root, outDir + "/neo-addon-prompt.png");
    }

    /** Prvni TextView s presne timto textem (pilulka kategorie v nastaveni). */
    private static android.view.View findText(android.view.View v, String text) {
        if (v instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView) v).getText())) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View e = findText(g.getChildAt(i), text);
                if (e != null) return e;
            }
        }
        return null;
    }

    private static android.widget.EditText findEdit(android.view.View v) {
        if (v instanceof android.widget.EditText) return (android.widget.EditText) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.EditText e = findEdit(g.getChildAt(i));
                if (e != null) return e;
            }
        }
        return null;
    }

    private static void scroll(android.view.View v, float x, float y, int axis, float value) {
        long t = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] pp = {new MotionEvent.PointerProperties()};
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] pc = {new MotionEvent.PointerCoords()};
        pc[0].x = x;
        pc[0].y = y;
        pc[0].setAxisValue(axis, value);
        MotionEvent e = MotionEvent.obtain(t, t, MotionEvent.ACTION_SCROLL, 1, pp, pc, 0, 0,
                1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        v.dispatchGenericMotionEvent(e);
        e.recycle();
    }

    static Object f(Object o, String name) throws Exception {
        java.lang.reflect.Field fl = o.getClass().getDeclaredField(name);
        fl.setAccessible(true);
        return fl.get(o);
    }

    /** Hodnota animace (Eased i Spring maji get(long)). */
    static Object val(Object anim, long now) throws Exception {
        java.lang.reflect.Method get = anim.getClass().getDeclaredMethod("get", long.class);
        get.setAccessible(true);
        return get.invoke(anim, now);
    }

    static void dumpState(NeoLauncherView v) throws Exception {
        Object foc = f(v, "focused");
        System.out.println("scrollCur=" + f(v, "scrollCur") + " squish=" + f(v, "squish")
                + " pivot=" + f(v, "squishPivot") + " scrollActive=" + f(v, "scrollActive")
                + " minScroll=" + f(v, "minScroll") + " pointer=" + f(v, "px") + "," + f(v, "py"));
        if (foc != null) {
            Object app = f(foc, "app");
            long now = System.nanoTime();
            System.out.println("focused=" + f(app, "pkg") + " hover=" + val(f(foc, "hover"), now)
                    + " rotX=" + val(f(foc, "rotX"), now) + " rotY=" + val(f(foc, "rotY"), now)
                    + " y=" + val(f(foc, "y"), now) + " x=" + val(f(foc, "x"), now)
                    + " press=" + val(f(foc, "press"), now) + " flash=" + val(f(foc, "flash"), now));
        } else System.out.println("focused=null");
    }
}
