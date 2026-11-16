package com.neolauncher.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import com.neolauncher.art.ArtworkLoader;
import com.neolauncher.art.Placeholders;
import com.neolauncher.data.AppEntry;
import com.neolauncher.data.Prefs;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Testovaci karuselovy rezim (5x klepnout na logo Neo). Zadne sklo, jen
 * karty na sirku v prostorovem "vejiri": prostredni velka a ostra, do stran
 * mensi, natocene k divakovi a cim dal, tim vic rozmazane (hloubka ostrosti).
 * Pod prostredni kartou nazev a stitky se statistikami.
 * <p>
 * Pravidla jako mrizka (CLAUDE.md): jeden View, stav jen z casu (pruziny
 * {@link Spring}), hover z pozice ukazatele. Rozmazani je JEDEN efekt na
 * celou vrstvu bocnich karet (DepthBlur), zadny blur na jednotlive karty.
 * Ovladani: joystick doleva/doprava (ACTION_SCROLL), klepnuti na bocni kartu
 * ji prinese doprostred, klepnuti na prostredni spusti, tazeni laserem roluje
 * (po pusteni pokracuje setrvacnosti), podrzeni prostredni otevre menu karty.
 */
public final class CarouselView extends View implements ArtworkLoader.Listener {

    public interface Host {
        void onLaunch(AppEntry app);

        void onLaunchSequenceDone();

        void onAppMenu(AppEntry app, RectF cardRect);

        void onOpenSettings();

        void onOpenQuickMenu(RectF origin);

        void onExitCarousel();

        void onTabSelected(int tab);

        void onRequestUsageAccess();
    }

    /** Statistiky pro stitky pod kartou (dodava aktivita). */
    public interface Stats {
        boolean hasUsageAccess();

        long playtimeMs(String pkg);

        long lastUsed(String pkg);

        int launchCount(String pkg);

        long installTime(String pkg);
    }

    private static final float CARD_RADIUS = 20f;
    /** Zmenseni karet podle vzdalenosti od stredu (0 = prostredni). */
    private static final float[] SCALE = {1f, 0.74f, 0.56f, 0.44f};
    /** Vodorovny odstup stredu karet (v nasobcich sirky prostredni) - karty se prekryvaji. */
    private static final float[] OFFSET = {0f, 0.64f, 1.06f, 1.38f};
    /** Natoceni bocnich karet k divakovi (vejir kolem hrace jako ve visionOS). */
    private static final float SIDE_ROT_DEG = 18f;
    private static final long HOVER_EXIT_GRACE_MS = 90;
    private static final float TILT_DEG = 5f;
    /** Joystick: prvni krok hned, pri drzeni opakovani po teto prodleve / intervalu. */
    private static final long STICK_FIRST_REPEAT_MS = 380;
    private static final long STICK_REPEAT_MS = 170;
    private static final long STICK_RELEASE_MS = 180;
    private static final float STICK_DEADZONE = 0.2f;

    private static final int ZONE_NONE = 0;
    private static final int ZONE_BACK = 1;
    private static final int ZONE_SETTINGS = 2;
    private static final int ZONE_USAGE = 3;
    private static final int ZONE_STATUS = 4;
    private static final int ZONE_TAB0 = 10;

    private static final String[] TAB_NAMES = {"Hry", "Aplikace", "Vše"};

    private static final int ICON_CLOCK = 0;
    private static final int ICON_CAL = 1;
    private static final int ICON_PLAY = 2;
    private static final int ICON_DOWNLOAD = 3;

    private static final class Item {
        AppEntry app;
        String label;
        Bitmap art;
        BitmapShader shader;
        float shaderFor = -1;
        Shader placeholder;
        float placeholderFor = -1;
        final Spring hl = new Spring(0, 0.30f, 0.80f, 0.002f);
        /** Vlastni RenderNode karty (3D natoceni). Kazda karta je ve snimku nejvys jednou. */
        RenderNode node;
    }

    private static final class Chip {
        int icon;
        String text;
        boolean action;
        final RectF rect = new RectF();
    }

    private final float d;
    private final GlassSurface tabGlass = new GlassSurface();
    private final GlassSurface backGlass = new GlassSurface();
    private final GlassSurface settingsGlass = new GlassSurface();
    private final float touchSlop;
    private Host host;
    private Prefs prefs;
    private ArtworkLoader artwork;
    private Stats stats;
    private com.neolauncher.data.AppRepository.Signals signals;

    private final List<Item> items = new ArrayList<>();
    private final Map<String, Item> itemByPkg = new HashMap<>();
    private int tab;
    private String selectedPkg;
    private boolean appsLoaded;

    // --- Poloha karuselu (v jednotkach "karet", neomezena kvuli dokola) ---------------
    private final Spring pos = new Spring(0, 0.50f, 0.86f, 0.0005f);
    private final Spring enter = new Spring(1, 0.55f, 0.80f, 0.001f);
    private final Eased chipFade = new Eased(1, 320, Eased.EASE_OUT);
    private final Spring press = new Spring(0, 0.22f, 0.9f, 0.002f);
    private final Spring rotX = new Spring(0, 0.38f, 0.85f, 0.01f);
    private final Spring rotY = new Spring(0, 0.38f, 0.85f, 0.01f);
    private final Eased[] zoneHover = new Eased[ZONE_TAB0 + TAB_NAMES.length];
    private final Spring tabPos = new Spring(0, 0.42f, 0.78f, 0.001f);

    // --- Geometrie (px) -------------------------------------------------------------
    private float cw, ch, cx, cy;
    private final float[] offX = new float[OFFSET.length];
    private final Path cardPath = new Path();
    private float notchW, notchH;
    private ShadowSprite shadow, glow;
    private Shader bottomShade;
    private final RectF backRect = new RectF();
    private final RectF settingsRect = new RectF();
    private final RectF tabsRect = new RectF();
    private final RectF[] tabRects = {new RectF(), new RectF(), new RectF()};
    private final RectF statusRect = new RectF();

    // --- Ukazatel / dotyk -----------------------------------------------------------
    private boolean interactive = true;
    private boolean pointerIn;
    private float px, py;
    private int hoverZone = ZONE_NONE;
    /** Absolutni index karty pod ukazatelem, nebo Integer.MIN_VALUE. */
    private int hoverK = Integer.MIN_VALUE;
    private boolean touching, dragging;
    private float downX, downY, dragStartX, dragStartPos;
    private int downZone = ZONE_NONE;
    private int pressedK = Integer.MIN_VALUE;
    private VelocityTracker velocity;
    private long lastStickEvtMs, lastStickStepMs;
    private int stickRepeat;

    // --- Stitky ---------------------------------------------------------------------
    private final List<Chip> chips = new ArrayList<>();
    private String chipsFor;
    private int statsVersion, chipsVersion = -1;
    private String shownTitle = "";
    private String shownTitleFor;

    // --- Stav horni listy -----------------------------------------------------------
    private int batteryLevel = -1;
    private boolean charging, fastCharging;
    private long clockMinute = -1;
    private String clockTime = "";

    // --- Spusteni -------------------------------------------------------------------
    private final PeepholeAnimation peephole;
    private int launchK = Integer.MIN_VALUE;

    // --- Kresleni -------------------------------------------------------------------
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint spritePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titleText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint subText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint badgeText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint chipText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint tabText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint headText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint hintText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Path boltPath = new Path();
    private final Matrix shaderMatrix = new Matrix();
    private final RectF tmp = new RectF();
    /** Vrstva bocnich karet s jedinym efektem hloubky ostrosti. */
    private final RenderNode sideNode = new RenderNode("neo-carousel-side");
    private final DepthBlur depthBlur = new DepthBlur();
    private ModalDepth modalDepth;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hoverExitRunnable = () -> {
        pointerIn = false;
        updateHover(System.nanoTime());
        invalidate();
    };
    private final Runnable longPressRunnable = this::onLongPress;

    public CarouselView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        touchSlop = Math.max(ViewConfiguration.get(c).getScaledTouchSlop(), dp(10));
        setClickable(true);
        for (int i = 0; i < zoneHover.length; i++) zoneHover[i] = new Eased(0, 180, Eased.EASE_OUT);

        Typeface semi = Typeface.create(Typeface.SANS_SERIF, 600, false);
        Typeface bold = Typeface.create(Typeface.SANS_SERIF, 700, false);
        titleText.setTypeface(bold);
        titleText.setTextSize(dp(26));
        titleText.setTextAlign(Paint.Align.CENTER);
        titleText.setColor(Color.WHITE);
        titleText.setShadowLayer(dp(8), 0, dp(1), 0x99000000);
        subText.setTypeface(semi);
        subText.setTextSize(dp(13));
        subText.setTextAlign(Paint.Align.CENTER);
        subText.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        badgeText.setTypeface(semi);
        badgeText.setTextSize(dp(11.5f));
        badgeText.setTextAlign(Paint.Align.CENTER);
        badgeText.setFontFeatureSettings("tnum");
        badgeText.setColor(0xD9FFFFFF);
        chipText.setTypeface(semi);
        chipText.setTextSize(dp(13));
        chipText.setFontFeatureSettings("tnum");
        tabText.setTypeface(semi);
        tabText.setTextSize(dp(13.5f));
        tabText.setTextAlign(Paint.Align.CENTER);
        headText.setTypeface(semi);
        headText.setTextSize(dp(12.5f));
        headText.setTextAlign(Paint.Align.CENTER);
        headText.setColor(0xB3FFFFFF);
        headText.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        hintText.setTypeface(semi);
        hintText.setTextSize(dp(12));
        hintText.setTextAlign(Paint.Align.CENTER);
        hintText.setColor(0x80FFFFFF);
        hintText.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        stroke.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);

        peephole = new PeepholeAnimation(d);

        // Blesk z preview (viewBox 24x24).
        boltPath.moveTo(11, 2);
        boltPath.lineTo(4, 13);
        boltPath.rLineTo(5, 0);
        boltPath.rLineTo(-1.5f, 9);
        boltPath.lineTo(18, 11);
        boltPath.rLineTo(-5.5f, 0);
        boltPath.lineTo(14, 2);
        boltPath.close();
    }

    private float dp(float v) {
        return v * d;
    }

    // =========================================================================
    // Verejne API
    // =========================================================================

    public void setHost(Host h) {
        host = h;
    }

    /** Odkud se bere "nova aplikace" (v popisku pod kartou). */
    public void setSignals(com.neolauncher.data.AppRepository.Signals s) {
        signals = s;
    }

    public void bind(Prefs p, ArtworkLoader a, Stats s) {
        prefs = p;
        artwork = a;
        stats = s;
        tab = p.tab();
        tabPos.snap(tab);
    }

    /** Karusel se prave ukazal: karty na pruzine "vyjedou" ze stredu do stran. */
    public void show() {
        final long now = System.nanoTime();
        enter.snap(0f);
        enter.set(1f, now);
        chipFade.snap(0f);
        chipFade.set(1f, now);
        statsChanged();
        prefetch();
        invalidate();
    }

    public void setApps(List<AppEntry> list, List<String> labels, int newTab) {
        final long now = System.nanoTime();
        final boolean tabChanged = newTab != tab;
        if (tabChanged) saveState();
        tab = newTab;
        tabPos.set(newTab, now);
        appsLoaded = true;
        final int oldIdx = items.isEmpty() ? 0 : Math.floorMod(Math.round(pos.target()), items.size());
        Map<String, Item> old = new HashMap<>(itemByPkg);
        items.clear();
        itemByPkg.clear();
        for (int i = 0; i < list.size(); i++) {
            AppEntry e = list.get(i);
            Item it = old.remove(e.pkg);
            if (it == null) it = new Item();
            it.app = e;
            it.label = labels.get(i);
            items.add(it);
            itemByPkg.put(e.pkg, it);
        }
        for (Item gone : old.values()) if (gone.node != null) gone.node.discardDisplayList();
        // Vybrana karta zustane vybrana (i po obnove seznamu). Kdyz zmizela
        // (skryta/odinstalovana), vybere se ta, ktera je ted na jejim miste.
        int sel = 0;
        // Po otevreni / prepnuti zalozky vybrat kartu, na ktere se naposledy skoncilo.
        final String remembered = (selectedPkg == null || tabChanged) && prefs != null
                ? prefs.carouselSelection(tab) : null;
        if (remembered != null) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).app.pkg.equals(remembered)) {
                    sel = i;
                    break;
                }
            }
        }
        if (!tabChanged && selectedPkg != null) {
            sel = -1;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).app.pkg.equals(selectedPkg)) {
                    sel = i;
                    break;
                }
            }
            if (sel < 0) sel = Math.max(0, Math.min(oldIdx, items.size() - 1));
        }
        final int n = items.size();
        final int curK = Math.round(pos.target());
        if (!(n > 0 && Math.floorMod(curK, n) == sel && !tabChanged)) pos.snap(sel);
        selectedPkg = n > 0 ? items.get(Math.floorMod(Math.round(pos.target()), n)).app.pkg : null;
        if (tabChanged) {
            chipFade.snap(0f);
            chipFade.set(1f, now);
            enter.snap(0.3f);
            enter.set(1f, now);
        }
        chipsFor = null;
        shownTitleFor = null;
        prefetch();
        invalidate();
    }

    public void setBattery(int level, boolean isCharging, boolean isFast) {
        if (level == batteryLevel && isCharging == charging && isFast == fastCharging) return;
        batteryLevel = level;
        charging = isCharging;
        fastCharging = isFast;
        invalidate();
    }

    public void onClockTick() {
        clockMinute = -1;
        invalidate();
    }

    /** Nova data herniho casu (UsageStats) - prepocitat stitky. */
    public void statsChanged() {
        statsVersion++;
        invalidate();
    }

    public void setInteractive(boolean b) {
        interactive = b;
        if (!b) {
            cancelTouch();
            clearHover();
        }
        invalidate();
    }

    /** Otevreny dialog: karusel plynule ustoupi do hloubky. */
    public void setModalBlur(boolean b) {
        if (modalDepth == null) modalDepth = new ModalDepth(this, d);
        modalDepth.set(b);
    }

    /** Tlacitko nastaveni (odtud "vyroste" panel nastaveni). */
    public RectF settingsRect() {
        return new RectF(settingsRect);
    }

    /** Ulozi vybranou kartu (pri odchodu z launcheru). */
    public void saveState() {
        if (prefs != null && selectedPkg != null) prefs.setCarouselSelection(tab, selectedPkg);
    }

    /** Obdelnik hodin/baterie (odtud "vyjede" rychle menu). */
    public RectF statusRect() {
        return new RectF(statusRect);
    }

    public void clearPointer() {
        handler.removeCallbacks(hoverExitRunnable);
        pointerIn = false;
        cancelTouch();
        clearHover();
        invalidate();
    }

    @Override
    public void onArtworkChanged(String pkg) {
        invalidate();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        clearPointer();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (artwork != null) artwork.addListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (artwork != null) artwork.removeListener(this);
        handler.removeCallbacksAndMessages(null);
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
        sideNode.discardDisplayList();
        for (Item it : items) if (it.node != null) it.node.discardDisplayList();
        peephole.discard();
    }

    // =========================================================================
    // Rozlozeni
    // =========================================================================

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        cw = Math.min(w * 0.40f, h * 0.42f * ArtworkLoader.CARD_ASPECT);
        ch = cw / ArtworkLoader.CARD_ASPECT;
        cx = w / 2f;
        cy = h * 0.43f;
        for (int i = 0; i < OFFSET.length; i++) offX[i] = OFFSET[i] * cw;
        final float r = dp(CARD_RADIUS);
        notchW = dp(62);
        notchH = dp(24);
        buildCardPath(cw, ch, r);
        shadow = ShadowSprite.create(cw, ch, r, dp(14));
        glow = ShadowSprite.create(cw, ch, r, dp(36));
        bottomShade = new LinearGradient(0, ch / 2f - ch * 0.40f, 0, ch / 2f,
                0x00000000, 0x80000000, Shader.TileMode.CLAMP);
        requestArtSize(getVisibility() == VISIBLE);
        for (Item it : items) {
            it.shaderFor = -1;
            it.placeholderFor = -1;
        }
    }

    /**
     * Obrazky ostre i pro velkou kartu. Sdilene s mrizkou (loader vezme vetsi
     * pozadavek) - jen kdyz je karusel videt, v rezimu mrizky nic navic.
     */
    private void requestArtSize(boolean visible) {
        if (artwork != null && cw > 0) {
            artwork.requestTargetSize("carousel", visible ? Math.round(cw * 1.05f) : 0);
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // Jen prepnuti rezimu (ne skryti okna) - jinak by se obrazky nacitaly znovu po kazdem navratu.
        if (changedView == this) requestArtSize(visibility == VISIBLE);
    }

    /** Obrys karty se "zarezem" vpravo nahore, ve kterem je poradi (jako ve visionOS). */
    private void buildCardPath(float w, float h, float r) {
        final float L = -w / 2f, T = -h / 2f, R = w / 2f, B = h / 2f;
        final float rn = dp(9);
        final float nx = R - notchW;
        final float ny = T + notchH;
        cardPath.reset();
        cardPath.moveTo(L + r, T);
        cardPath.lineTo(nx - rn, T);
        cardPath.quadTo(nx, T, nx, T + rn);
        cardPath.lineTo(nx, ny - rn);
        cardPath.quadTo(nx, ny, nx + rn, ny);
        cardPath.lineTo(R - rn, ny);
        cardPath.quadTo(R, ny, R, ny + rn);
        cardPath.lineTo(R, B - r);
        cardPath.arcTo(R - 2 * r, B - 2 * r, R, B, 0, 90, false);
        cardPath.lineTo(L + r, B);
        cardPath.arcTo(L, B - 2 * r, L + 2 * r, B, 90, 90, false);
        cardPath.lineTo(L, T + r);
        cardPath.arcTo(L, T, L + 2 * r, T + 2 * r, 180, 90, false);
        cardPath.close();
    }

    private boolean wraps() {
        return items.size() >= 5;
    }

    /** Kolik karet na kazdou stranu je videt (pri dokola bez opakovani stejne karty). */
    private int reach() {
        return wraps() ? Math.min(3, (items.size() - 1) / 2) : 3;
    }

    private Item itemAt(int k) {
        final int n = items.size();
        if (n == 0) return null;
        if (!wraps() && (k < 0 || k >= n)) return null;
        return items.get(Math.floorMod(k, n));
    }

    private static float interp(float[] arr, float a) {
        if (a <= 0f) return arr[0];
        final int last = arr.length - 1;
        if (a >= last) return arr[last] + (a - last) * (arr[last] - arr[last - 1]);
        final int i = (int) a;
        final float f = a - i;
        return arr[i] + (arr[i + 1] - arr[i]) * f;
    }

    private float scaleFor(float a) {
        return Math.max(0.2f, interp(SCALE, a));
    }

    private float xFor(float o, float spread) {
        return cx + Math.signum(o) * interp(offX, Math.abs(o)) * spread;
    }

    private float alphaFor(float a) {
        return clamp((reach() + 0.5f - a) / 0.6f, 0f, 1f);
    }

    private int centerK() {
        return Math.round(pos.target());
    }

    // =========================================================================
    // Kresleni
    // =========================================================================

    @Override
    protected void onDraw(Canvas canvas) {
        if (prefs == null || cw <= 0) return;
        final long now = System.nanoTime();
        final float e = enter.get(now);

        drawHeader(canvas, now);
        if (items.isEmpty()) {
            final String msg = !appsLoaded ? "Načítám aplikace…"
                    : tab == Prefs.TAB_GAMES ? "Žádné VR hry" : "Žádné aplikace";
            hintText.setTextSize(dp(16));
            canvas.drawText(msg, cx, cy, hintText);
            hintText.setTextSize(dp(12));
        } else {
            drawCards(canvas, now, e);
            drawCaption(canvas, now);
            drawChips(canvas, now);
            if (prefs != null && prefs.carouselHint()) {
                canvas.drawText("Joystick ← → přepíná   ·   klepnutí spustí   ·   podržení otevře menu",
                        cx, getHeight() - dp(18), hintText);
            }
        }

        if (launchK != Integer.MIN_VALUE) {
            if (!peephole.isRunning(now)) launchK = Integer.MIN_VALUE;
            else peephole.draw(canvas, now, getWidth(), getHeight());
        }
        if (isAnimating(now) || launchK != Integer.MIN_VALUE) postInvalidateOnAnimation();
    }

    private boolean isAnimating(long now) {
        if (pos.active(now) || enter.active(now) || chipFade.active(now) || press.active(now)
                || rotX.active(now) || rotY.active(now) || tabPos.active(now)) return true;
        for (Eased z : zoneHover) if (z.active(now)) return true;
        for (Item it : items) if (it.hl.active(now)) return true;
        return false;
    }

    /** Jedna viditelna karta v aktualnim snimku. */
    private static final class Slot {
        int k;
        float a, x, s, ry, alpha;
        Item it;
    }

    private void drawCards(Canvas c, long now, float e) {
        final float p = pos.get(now);
        final float spread = 0.4f + 0.6f * e;
        final float eScale = 0.85f + 0.15f * Math.min(e, 1.05f);
        final float eAlpha = clamp(e * 1.6f, 0f, 1f);
        final int base = Math.round(p);
        final int reach = reach();
        final int n = items.size();
        final int ck = centerK();

        List<Slot> slots = new ArrayList<>();
        for (int k = base - reach - 1; k <= base + reach + 1; k++) {
            if (!wraps() && (k < 0 || k >= n)) continue;
            final float a = Math.abs(k - p);
            final float alpha = alphaFor(a) * eAlpha;
            if (alpha <= 0.004f) continue;
            Slot sl = new Slot();
            sl.k = k;
            sl.a = a;
            sl.it = itemAt(k);
            sl.alpha = alpha;
            sl.x = xFor(k - p, spread);
            sl.s = scaleFor(a) * eScale;
            // Vejir kolem hrace: vnejsi hrana bocni karty jde k divakovi.
            sl.ry = -Math.signum(k - p) * Math.min(a, 1f) * SIDE_ROT_DEG;
            slots.add(sl);
        }
        // Od nejvzdalenejsi (kresli se prvni) po prostredni.
        slots.sort((x, y) -> Float.compare(y.a, x.a));

        // 1) Zare v barve hry pod kartami u stredu - aditivne (svetlo).
        spritePaint.setBlendMode(BlendMode.PLUS);
        for (Slot sl : slots) {
            if (sl.a >= 1f || sl.k == launchK) continue;
            final int col = artwork != null ? artwork.glowColor(sl.it.app.pkg, 0xFFBFE6FF) : 0xFFBFE6FF;
            final float ga = (1f - sl.a) * (1f - sl.a) * sl.alpha;
            c.save();
            c.translate(sl.x, cy);
            c.scale(sl.s, sl.s);
            spritePaint.setColor((col & 0x00FFFFFF) | (Math.round(120 * ga) << 24));
            glow.draw(c, -cw / 2f, -ch / 2f, cw / 2f, ch / 2f, dp(8), spritePaint);
            c.restore();
        }
        spritePaint.setBlendMode(null);

        // 2) Bocni karty do jedne vrstvy s hloubkou ostrosti, prostredni zvlast (ostra).
        Slot center = null;
        final boolean hw = c.isHardwareAccelerated();
        Canvas side = c;
        RecordingCanvas rc = null;
        if (hw) {
            sideNode.setPosition(0, 0, getWidth(), getHeight());
            rc = sideNode.beginRecording(getWidth(), getHeight());
            side = rc;
        }
        try {
            for (Slot sl : slots) {
                if (sl.k == launchK) continue;
                if (sl.k == ck && sl.a < 0.5f) {
                    center = sl;
                    continue;
                }
                final float hl = clamp(sl.it.hl.get(now), 0f, 1f);
                final float dim = Math.min(0.45f, 0.18f * sl.a) * (1f - 0.6f * hl);
                drawCard(side, sl, sl.s * (1f + 0.04f * sl.it.hl.get(now)), 0f, sl.ry, dim, hl, now);
            }
        } finally {
            if (rc != null) sideNode.endRecording();
        }
        if (hw) {
            final float amt = prefs.dofMode() == Prefs.DOF_OFF ? 0f
                    : prefs.dofMode() == Prefs.DOF_STRONG ? 1.6f : 1f;
            sideNode.setRenderEffect(amt > 0f ? depthBlur.effect(cx, cy, cw * 0.35f,
                    offX[2] + cw * 0.35f, dp(0.8f) * amt, dp(9f) * amt) : null);
            c.drawRenderNode(sideNode);
        }

        // 3) Prostredni karta: ostra, naklon za ukazatelem, lehke "stisknuti".
        if (center != null) {
            final float hl = clamp(center.it.hl.get(now), 0f, 1f);
            final float s = center.s * (1f + 0.03f * center.it.hl.get(now) - 0.05f * press.get(now));
            drawCard(c, center, s, rotX.get(now), center.ry + rotY.get(now), 0f, hl, now);
        }
    }

    /** Karta pres jeji vlastni RenderNode (poloha, meritko, 3D natoceni, pruhlednost). */
    private void drawCard(Canvas c, Slot sl, float s, float rx, float ry, float dim, float hl, long now) {
        final Item it = sl.it;
        if (!c.isHardwareAccelerated()) {
            c.save();
            c.translate(sl.x, cy);
            c.scale(s, s);
            drawBody(c, it, sl.k, dim, hl, sl.a);
            c.restore();
            return;
        }
        if (it.node == null) it.node = new RenderNode("neo-carousel-card");
        final float m = shadow.margin + dp(24);
        final int nw = (int) Math.ceil(cw + 2 * m);
        final int nh = (int) Math.ceil(ch + 2 * m);
        final RenderNode n = it.node;
        RecordingCanvas rc = n.beginRecording(nw, nh);
        try {
            rc.translate(nw / 2f, nh / 2f);
            drawBody(rc, it, sl.k, dim, hl, sl.a);
        } finally {
            n.endRecording();
        }
        n.setPosition(0, 0, nw, nh);
        n.setClipToBounds(false);
        n.setTranslationX(sl.x - nw / 2f);
        n.setTranslationY(cy - nh / 2f);
        n.setPivotX(nw / 2f);
        n.setPivotY(nh / 2f);
        n.setScaleX(s);
        n.setScaleY(s);
        n.setRotationX(rx);
        n.setRotationY(ry);
        n.setCameraDistance(dp(900) / 72f);
        n.setAlpha(sl.alpha);
        c.drawRenderNode(n);
    }

    /** Karta v lokalnich souradnicich se stredem v (0,0), velikost cw x ch (vcetne stinu). */
    private void drawBody(Canvas c, Item it, int k, float dim, float hl, float a) {
        final float w2 = cw / 2f, h2 = ch / 2f;
        // Stin: mekky (predpocitany) + plochy ostry pod kartou.
        spritePaint.setColor(Color.argb(Math.round(110 + 70 * (1f - Math.min(a, 1f))), 0, 0, 0));
        shadow.draw(c, -w2, -h2, w2, h2, dp(12), spritePaint);
        fill.setShader(null);
        fill.setColor(0x38000000);
        c.save();
        c.translate(0, dp(4));
        c.drawPath(cardPath, fill);
        c.restore();

        Bitmap art = artwork != null ? artwork.get(it.app) : null;
        if (art != it.art) {
            it.art = art;
            it.shader = art != null ? new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) : null;
            it.shaderFor = -1;
        }
        if (it.shader != null) {
            if (it.shaderFor != cw) {
                shaderMatrix.setScale(cw / it.art.getWidth(), ch / it.art.getHeight());
                shaderMatrix.postTranslate(-w2, -h2);
                it.shader.setLocalMatrix(shaderMatrix);
                it.shaderFor = cw;
            }
            artPaint.setShader(it.shader);
        } else {
            if (it.placeholder == null || it.placeholderFor != cw) {
                int[] pair = Placeholders.colorsFor(it.app.pkg);
                it.placeholder = new LinearGradient(-w2, -h2, w2, h2, pair[0], pair[1], Shader.TileMode.CLAMP);
                it.placeholderFor = cw;
            }
            artPaint.setShader(it.placeholder);
        }
        c.drawPath(cardPath, artPaint);
        artPaint.setShader(null);

        fill.setShader(bottomShade);
        fill.setColor(Color.WHITE);
        c.drawPath(cardPath, fill);
        fill.setShader(null);
        if (dim > 0.004f) {
            fill.setColor(Color.argb(Math.round(255 * dim), 4, 6, 10));
            c.drawPath(cardPath, fill);
        }

        // Ramecek (pri hoveru zari).
        stroke.setShader(null);
        stroke.setStrokeWidth(dp(1));
        stroke.setColor(Color.argb(Math.round(255 * lerp(a < 0.5f ? 0.30f : 0.16f, 0.85f, hl)), 255, 255, 255));
        c.drawPath(cardPath, stroke);

        // Poradi v zarezu vpravo nahore.
        final int n = items.size();
        final String badge = (Math.floorMod(k, n) + 1) + " / " + n;
        final Paint.FontMetrics fm = badgeText.getFontMetrics();
        c.drawText(badge, w2 - notchW / 2f + dp(3), -h2 + notchH / 2f - (fm.ascent + fm.descent) / 2f - dp(1),
                badgeText);
    }

    /** Nazev a typ prostredni karty pod ni. */
    private void drawCaption(Canvas c, long now) {
        final Item it = itemAt(Math.round(pos.get(now)));
        if (it == null) return;
        if (!it.app.pkg.equals(shownTitleFor)) {
            shownTitleFor = it.app.pkg;
            shownTitle = TextUtils.ellipsize(it.label, titleText, getWidth() * 0.6f,
                    TextUtils.TruncateAt.END).toString();
        }
        final float f = chipFade.get(now) * clamp(enter.get(now) * 1.6f, 0f, 1f);
        if (f <= 0.004f) return;
        final float y = cy + ch / 2f + dp(46) + (1f - f) * dp(6);
        titleText.setAlpha(Math.round(255 * f));
        c.drawText(shownTitle, cx, y, titleText);
        titleText.setAlpha(255);
        subText.setColor(Color.argb(Math.round(178 * f), 255, 255, 255));
        String sub = typeLabel(it.app);
        if (signals != null && signals.isNew(it.app)) sub += "  ·  Nové";
        if (prefs.isFavorite(it.app.pkg)) sub += "  ·  ★ Oblíbená";
        c.drawText(sub, cx, y + dp(22), subText);
    }

    private static String typeLabel(AppEntry e) {
        if (e.isSystemPanel() || e.type == AppEntry.TYPE_PANEL) return "Systém Questu";
        return e.isVr() ? "VR hra" : "2D aplikace";
    }

    // --- Horni lista --------------------------------------------------------------

    private void drawHeader(Canvas c, long now) {
        final float midY = dp(44);
        final float r = dp(20);
        // Zpet (vlevo) a nastaveni (vpravo) - kulata skla jako ve visionOS.
        backRect.set(dp(24), midY - r, dp(24) + 2 * r, midY + r);
        settingsRect.set(getWidth() - dp(24) - 2 * r, midY - r, getWidth() - dp(24), midY + r);
        drawRoundButton(c, backRect, zoneHover[ZONE_BACK].get(now));
        drawRoundButton(c, settingsRect, zoneHover[ZONE_SETTINGS].get(now));
        Icons.draw(c, Icons.CHEVRON_LEFT, backRect.centerX(), midY, dp(22), Palette.TEXT, dp(2f), 1f, iconPaint);
        Icons.draw(c, Icons.SLIDERS, settingsRect.centerX(), midY, dp(20), Palette.TEXT, dp(1.8f), 1f, iconPaint);

        drawTabs(c, now, midY);
        drawStatus(c, now, midY);

        // Pocet polozek pod zalozkami.
        final int n = items.size();
        if (n > 0) c.drawText(countLabel(n), cx, midY + dp(40), headText);
    }

    private void drawRoundButton(Canvas c, RectF r, float hv) {
        final float rad = r.height() / 2f;
        fill.setShader(null);
        fill.setColor(0x33000000);
        c.drawCircle(r.centerX(), r.centerY() + dp(2), rad, fill);
        // Kulate sklo stejnym receptem jako zbytek (tmave pod, pak sklo, hover zesvetli).
        fill.setColor(Palette.alpha(Palette.VOID, 0.55f));
        c.drawCircle(r.centerX(), r.centerY(), rad, fill);
        (r == backRect ? backGlass : settingsGlass).draw(c, r, rad,
                hv > 0.004f ? GlassSurface.TILE_HOVER : GlassSurface.TILE, d);
    }

    private void drawTabs(Canvas c, long now, float midY) {
        final float padX = dp(14);
        final float itemH = dp(30);
        final float inner = dp(4);
        float total = 0;
        final float[] widths = new float[TAB_NAMES.length];
        for (int i = 0; i < TAB_NAMES.length; i++) {
            widths[i] = tabText.measureText(TAB_NAMES[i]) + 2 * padX;
            total += widths[i];
        }
        final float left = cx - total / 2f - inner;
        tabsRect.set(left, midY - itemH / 2f - inner, left + total + 2 * inner, midY + itemH / 2f + inner);
        final float rr = tabsRect.height() / 2f;
        fill.setShader(null);
        fill.setColor(0x33000000);
        c.drawRoundRect(tabsRect.left, tabsRect.top + dp(2), tabsRect.right, tabsRect.bottom + dp(2), rr, rr, fill);
        fill.setColor(0x4D12161F);
        c.drawRoundRect(tabsRect, rr, rr, fill);
        stroke.setStrokeWidth(dp(1));
        stroke.setColor(0x2EFFFFFF);
        c.drawRoundRect(tabsRect, rr, rr, stroke);
        float x = left + inner;
        for (int i = 0; i < TAB_NAMES.length; i++) {
            tabRects[i].set(x, midY - itemH / 2f, x + widths[i], midY + itemH / 2f);
            x += widths[i];
        }
        final float tp = clamp(tabPos.get(now), -0.3f, TAB_NAMES.length - 0.7f);
        final int i0 = (int) clamp((float) Math.floor(tp), 0f, TAB_NAMES.length - 2);
        final int i1 = i0 + 1;
        final float f = tp - i0;
        final float il = lerp(tabRects[i0].left, tabRects[i1].left, f);
        final float ir = lerp(tabRects[i0].right, tabRects[i1].right, f);
        // Vybrana zalozka = svetlejsi sklenena kapsle (bez barevne zare), jako v mrizce.
        tabGlass.draw(c, il, midY - itemH / 2f, ir, midY + itemH / 2f, itemH / 2f, GlassSurface.TILE_HOVER, d, 1f);
        final Paint.FontMetrics fm = tabText.getFontMetrics();
        final float baseY = midY - (fm.ascent + fm.descent) / 2f;
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final float hv = zoneHover[ZONE_TAB0 + i].get(now);
            if (hv > 0.004f && i != tab) {
                fill.setColor(Color.argb(Math.round(20 * hv), 255, 255, 255));
                c.drawRoundRect(tabRects[i], itemH / 2f, itemH / 2f, fill);
            }
            final float al = i == tab ? 1f : lerp(0.62f, 0.95f, hv);
            tabText.setColor(Color.argb(Math.round(255 * al), 255, 255, 255));
            c.drawText(TAB_NAMES[i], tabRects[i].centerX(), baseY, tabText);
        }
    }

    /** Hodiny + baterie vlevo od tlacitka nastaveni (klepnuti = rychle menu). */
    private void drawStatus(Canvas c, long now, float midY) {
        final long minute = System.currentTimeMillis() / 60000L;
        if (minute != clockMinute) {
            clockMinute = minute;
            Calendar cal = Calendar.getInstance();
            clockTime = String.format(Locale.ROOT, "%02d:%02d",
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
        }
        final String pct = batteryLevel >= 0 ? batteryLevel + "%" : "–";
        final int bolts = charging ? (fastCharging ? 2 : 1) : 0;
        final float boltW = dp(7.5f), boltH = dp(11f);
        chipText.setColor(0xF2FFFFFF);
        final float timeW = chipText.measureText(clockTime);
        final float pctW = chipText.measureText(pct);
        final float w = dp(14) + timeW + dp(12) + pctW + (bolts > 0 ? dp(4) + bolts * (boltW + dp(1)) : 0) + dp(14);
        final float h = dp(32);
        final float right = settingsRect.left - dp(10);
        statusRect.set(right - w, midY - h / 2f, right, midY + h / 2f);
        final float rr = h / 2f;
        final float hv = zoneHover[ZONE_STATUS].get(now);
        fill.setShader(null);
        fill.setColor(0x33000000);
        c.drawRoundRect(statusRect.left, statusRect.top + dp(2), statusRect.right, statusRect.bottom + dp(2), rr, rr, fill);
        fill.setColor(Color.argb(Math.round(lerp(0x4D, 0x80, hv)), 18, 22, 32));
        c.drawRoundRect(statusRect, rr, rr, fill);
        stroke.setStrokeWidth(dp(1));
        stroke.setColor(Color.argb(Math.round(lerp(0x2E, 0x80, hv)), 255, 255, 255));
        c.drawRoundRect(statusRect, rr, rr, stroke);
        final Paint.FontMetrics fm = chipText.getFontMetrics();
        final float base = midY - (fm.ascent + fm.descent) / 2f;
        float x = statusRect.left + dp(14);
        c.drawText(clockTime, x, base, chipText);
        x += timeW + dp(12);
        chipText.setColor(batteryColor());
        c.drawText(pct, x, base, chipText);
        x += pctW + dp(4);
        if (bolts > 0) {
            fill.setColor(Color.WHITE);
            for (int i = 0; i < bolts; i++) {
                c.save();
                c.translate(x, midY - boltH / 2f);
                c.scale(boltW / 14f, boltH / 20f);
                c.translate(-4f, -2f);
                c.drawPath(boltPath, fill);
                c.restore();
                x += boltW + dp(1);
            }
        }
    }

    private int batteryColor() {
        return Palette.battery(batteryLevel);
    }

    private String countLabel(int n) {
        if (tab == Prefs.TAB_GAMES) return n + (n == 1 ? " hra" : n <= 4 ? " hry" : " her");
        if (tab == Prefs.TAB_APPS) return n + (n <= 4 ? " aplikace" : " aplikací");
        return n + (n == 1 ? " položka" : n <= 4 ? " položky" : " položek");
    }

    // --- Stitky se statistikami --------------------------------------------------

    private void rebuildChips(Item it) {
        chips.clear();
        final String pkg = it.app.pkg;
        Chip play = new Chip();
        play.icon = ICON_CLOCK;
        if (stats == null || !stats.hasUsageAccess()) {
            play.text = "Herní čas – povolit";
            play.action = true;
        } else {
            play.text = (it.app.isVr() ? "Hráno " : "Používáno ") + formatDuration(stats.playtimeMs(pkg));
        }
        chips.add(play);

        Chip last = new Chip();
        last.icon = ICON_CAL;
        last.text = formatLast(stats != null ? stats.lastUsed(pkg) : 0L);
        chips.add(last);

        // Pocet spusteni a datum instalace uz ne: pocet znal jen spusteni z Nea (u her
        // spoustenych z knihovny Questu ukazoval 0x vedle hodin hrani - zmatek).
    }

    private void drawChips(Canvas c, long now) {
        final Item it = itemAt(centerK());
        if (it == null) return;
        if (!it.app.pkg.equals(chipsFor) || chipsVersion != statsVersion) {
            chipsFor = it.app.pkg;
            chipsVersion = statsVersion;
            rebuildChips(it);
        }
        final float f = chipFade.get(now) * clamp(enter.get(now) * 1.6f, 0f, 1f);
        if (f <= 0.004f) return;
        final float h = dp(32);
        final float gap = dp(10);
        final float icon = dp(14);
        float total = -gap;
        for (Chip ch0 : chips) total += dp(12) + icon + dp(7) + chipText.measureText(ch0.text) + dp(14) + gap;
        final float y = cy + ch / 2f + dp(108) + (1f - f) * dp(8);
        float x = cx - total / 2f;
        final Paint.FontMetrics fm = chipText.getFontMetrics();
        final int al = Math.round(255 * f);
        for (Chip chip : chips) {
            final float tw = chipText.measureText(chip.text);
            final float w = dp(12) + icon + dp(7) + tw + dp(14);
            chip.rect.set(x, y - h / 2f, x + w, y + h / 2f);
            final float rr = h / 2f;
            final float hv = chip.action ? zoneHover[ZONE_USAGE].get(now) : 0f;
            fill.setShader(null);
            fill.setColor(Color.argb(Math.round(0x33 * f), 0, 0, 0));
            c.drawRoundRect(chip.rect.left, chip.rect.top + dp(2), chip.rect.right, chip.rect.bottom + dp(2), rr, rr, fill);
            fill.setColor(Color.argb(Math.round(lerp(0x59, 0x80, hv) * f), 18, 22, 32));
            c.drawRoundRect(chip.rect, rr, rr, fill);
            stroke.setStrokeWidth(dp(1));
            stroke.setColor(chip.action ? Palette.alpha(Palette.COBALT_LIGHT, lerp(0.5f, 0.9f, hv) * f)
                    : Color.argb(Math.round(0x2E * f), 255, 255, 255));
            c.drawRoundRect(chip.rect, rr, rr, stroke);
            final int col = chip.action ? Palette.COBALT_LIGHT : Palette.TEXT;
            drawIcon(c, chip.icon, x + dp(12) + icon / 2f, y, icon, (col & 0x00FFFFFF) | (Math.round(al * 0.9f) << 24));
            chipText.setColor((col & 0x00FFFFFF) | (Math.round(al * 0.95f) << 24));
            c.drawText(chip.text, x + dp(12) + icon + dp(7), y - (fm.ascent + fm.descent) / 2f, chipText);
            x += w + gap;
        }
    }

    /** Ikona stitku (sada Lucide jako vsude jinde). */
    private void drawIcon(Canvas c, int type, float x, float y, float size, int color) {
        final int id = type == ICON_CLOCK ? Icons.CLOCK : type == ICON_CAL ? Icons.CALENDAR
                : type == ICON_PLAY ? Icons.PLAY : Icons.DOWNLOAD;
        Icons.draw(c, id, x, y, size, color, dp(1.6f), 1f, iconPaint);
    }

    static String formatDuration(long ms) {
        if (ms <= 0) return "0 min";
        final long min = ms / 60000L;
        if (min < 1) return "< 1 min";
        if (min < 60) return min + " min";
        final long h = min / 60, m = min % 60;
        if (h >= 100 || m == 0) return h + " h";
        return h + " h " + m + " min";
    }

    public static String formatLast(long t) {
        if (t <= 0) return "Zatím nespuštěno";
        final Calendar now = Calendar.getInstance();
        final Calendar then = Calendar.getInstance();
        then.setTimeInMillis(t);
        final long days = dayIndex(now) - dayIndex(then);
        if (days <= 0) {
            return String.format(Locale.ROOT, "Naposledy dnes %d:%02d",
                    then.get(Calendar.HOUR_OF_DAY), then.get(Calendar.MINUTE));
        }
        if (days == 1) return "Naposledy včera";
        if (days < 7) return "Naposledy před " + days + " dny";
        return "Naposledy " + formatDate(t, then.get(Calendar.YEAR) != now.get(Calendar.YEAR));
    }

    private static long dayIndex(Calendar c) {
        return c.get(Calendar.YEAR) * 400L + c.get(Calendar.DAY_OF_YEAR);
    }

    static String formatDate(long t, boolean withYear) {
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        final String s = c.get(Calendar.DAY_OF_MONTH) + ". " + (c.get(Calendar.MONTH) + 1) + ".";
        return withYear ? s + " " + c.get(Calendar.YEAR) : s;
    }

    // =========================================================================
    // Hover
    // =========================================================================

    /** Karta pod bodem (od nejblizsi ke stredu), nebo Integer.MIN_VALUE. */
    private int cardAt(float x, float y, long now) {
        if (items.isEmpty()) return Integer.MIN_VALUE;
        final float p = pos.get(now);
        final int base = Math.round(p);
        int best = Integer.MIN_VALUE;
        float bestA = Float.MAX_VALUE;
        for (int k = base - reach(); k <= base + reach(); k++) {
            if (itemAt(k) == null) continue;
            final float a = Math.abs(k - p);
            if (alphaFor(a) < 0.5f) continue;
            final float s = scaleFor(a);
            final float xx = xFor(k - p, 1f);
            if (Math.abs(x - xx) <= cw * s / 2f && Math.abs(y - cy) <= ch * s / 2f && a < bestA) {
                best = k;
                bestA = a;
            }
        }
        return best;
    }

    private int zoneAt(float x, float y) {
        tmp.set(backRect);
        tmp.inset(-dp(6), -dp(6));
        if (tmp.contains(x, y)) return ZONE_BACK;
        tmp.set(settingsRect);
        tmp.inset(-dp(6), -dp(6));
        if (tmp.contains(x, y)) return ZONE_SETTINGS;
        if (statusRect.contains(x, y)) return ZONE_STATUS;
        for (int i = 0; i < tabRects.length; i++) if (tabRects[i].contains(x, y)) return ZONE_TAB0 + i;
        for (Chip chip : chips) if (chip.action && chip.rect.contains(x, y)) return ZONE_USAGE;
        return ZONE_NONE;
    }

    private void updateHover(long now) {
        int zone = ZONE_NONE;
        int k = Integer.MIN_VALUE;
        if (pointerIn && interactive && !dragging) {
            zone = zoneAt(px, py);
            if (zone == ZONE_NONE) k = cardAt(px, py, now);
        }
        if (zone != hoverZone) {
            hoverZone = zone;
            for (int i = 0; i < zoneHover.length; i++) zoneHover[i].set(i == zone ? 1f : 0f, now);
        }
        if (k != hoverK) {
            Item old = hoverK != Integer.MIN_VALUE ? itemAt(hoverK) : null;
            if (old != null) old.hl.set(0f, now);
            hoverK = k;
            Item it = k != Integer.MIN_VALUE ? itemAt(k) : null;
            if (it != null) it.hl.set(1f, now);
        }
        // Naklon prostredni karty za ukazatelem (jako v mrizce).
        if (k != Integer.MIN_VALUE && k == centerK()) {
            final float s = scaleFor(Math.abs(k - pos.get(now)));
            final float nx = clamp((px - cx) / (cw * s / 2f), -1f, 1f);
            final float ny = clamp((py - cy) / (ch * s / 2f), -1f, 1f);
            final float ry = nx * TILT_DEG;
            final float rx = -ny * TILT_DEG;
            if (Math.abs(ry - rotY.target()) > 0.05f) rotY.set(ry, now);
            if (Math.abs(rx - rotX.target()) > 0.05f) rotX.set(rx, now);
        } else {
            rotX.set(0f, now);
            rotY.set(0f, now);
        }
    }

    private void clearHover() {
        final long now = System.nanoTime();
        for (Item it : items) it.hl.set(0f, now);
        hoverK = Integer.MIN_VALUE;
        hoverZone = ZONE_NONE;
        for (Eased z : zoneHover) z.set(0f, now);
        rotX.set(0f, now);
        rotY.set(0f, now);
        press.set(0f, now);
    }

    // =========================================================================
    // Vstup
    // =========================================================================

    private void pointer(float x, float y) {
        px = x;
        py = y;
        pointerIn = true;
        handler.removeCallbacks(hoverExitRunnable);
    }

    @Override
    public boolean onHoverEvent(MotionEvent e) {
        if (!interactive || launchK != Integer.MIN_VALUE) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                pointer(e.getX(), e.getY());
                updateHover(System.nanoTime());
                invalidate();
                break;
            case MotionEvent.ACTION_HOVER_EXIT:
                if (!touching) {
                    handler.removeCallbacks(hoverExitRunnable);
                    handler.postDelayed(hoverExitRunnable, HOVER_EXIT_GRACE_MS);
                }
                break;
            default:
                break;
        }
        return true;
    }

    /**
     * Joystick (thumbstick) na Questu posila ACTION_SCROLL. Doprava/dolu = dalsi karta.
     * Prvni krok hned, pri drzeni se opakuje (nejdriv po 380 ms, pak po 170 ms).
     * Pruzina si pri rychlem opakovani drzi rychlost - karusel plynule "jede".
     */
    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_SCROLL
                && (e.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0) {
            if (!interactive || dragging || launchK != Integer.MIN_VALUE || items.isEmpty()) return true;
            final float hs = e.getAxisValue(MotionEvent.AXIS_HSCROLL);
            final float vs = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            final float v = Math.abs(hs) >= Math.abs(vs) ? hs : -vs;
            final long now = System.currentTimeMillis();
            if (now - lastStickEvtMs > STICK_RELEASE_MS) stickRepeat = 0;
            lastStickEvtMs = now;
            if (Math.abs(v) < STICK_DEADZONE) return true;
            final long wait = stickRepeat == 0 ? 0 : stickRepeat == 1 ? STICK_FIRST_REPEAT_MS : STICK_REPEAT_MS;
            if (now - lastStickStepMs >= wait) {
                lastStickStepMs = now;
                stickRepeat++;
                step(v > 0 ? 1 : -1);
            }
            return true;
        }
        return super.onGenericMotionEvent(e);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (interactive && launchK == Integer.MIN_VALUE && !items.isEmpty()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                step(1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                step(-1);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void step(int dir) {
        final long now = System.nanoTime();
        final float t = Math.round(pos.target()) + dir;
        if (!wraps() && (t < 0 || t > items.size() - 1)) {
            // Na kraji fyzikalni "naraz": pruzina dostane rychlost a vrati se zpet.
            pos.setWithVelocity(pos.target(), dir * 2.2f, now);
            invalidate();
            return;
        }
        select(t, Float.NaN);
    }

    /** @param velocity pocatecni rychlost pruziny (karet/s), NaN = zachovat aktualni */
    private void select(float t, float velocity) {
        final long now = System.nanoTime();
        final int n = items.size();
        if (n == 0) return;
        if (!wraps()) t = clamp(t, 0, n - 1);
        final boolean changed = Math.round(t) != Math.round(pos.target());
        if (Float.isNaN(velocity)) pos.set(t, now);
        else pos.setWithVelocity(t, velocity, now);
        final String pkg = items.get(Math.floorMod(Math.round(t), n)).app.pkg;
        if (changed || !pkg.equals(selectedPkg)) {
            selectedPkg = pkg;
            chipFade.snap(0f);
            chipFade.set(1f, now);
            prefetch();
        }
        updateHover(now);
        invalidate();
    }

    private void prefetch() {
        // Skryty karusel (rezim mrizky) nic nenacita.
        if (artwork == null || items.isEmpty() || getVisibility() != VISIBLE) return;
        final int k = Math.round(pos.target());
        for (int i = k - 4; i <= k + 4; i++) {
            Item it = itemAt(i);
            if (it != null) artwork.get(it.app);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!interactive) return false;
        if (launchK != Integer.MIN_VALUE) return true;
        final float x = e.getX(), y = e.getY();
        final long now = System.nanoTime();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointer(x, y);
                touching = true;
                dragging = false;
                downX = x;
                downY = y;
                if (velocity == null) velocity = VelocityTracker.obtain();
                velocity.clear();
                velocity.addMovement(e);
                updateHover(now);
                downZone = zoneAt(x, y);
                pressedK = downZone == ZONE_NONE ? cardAt(x, y, now) : Integer.MIN_VALUE;
                if (pressedK != Integer.MIN_VALUE && pressedK == centerK()) {
                    press.set(1f, now);
                    handler.postDelayed(longPressRunnable, prefs != null ? prefs.menuHoldMs() : 1000);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                pointer(x, y);
                if (velocity != null) velocity.addMovement(e);
                if (!dragging && (Math.abs(x - downX) > touchSlop || Math.abs(y - downY) > touchSlop)) {
                    dragging = true;
                    handler.removeCallbacks(longPressRunnable);
                    press.set(0f, now);
                    dragStartX = x;
                    dragStartPos = pos.get(now);
                    pressedK = Integer.MIN_VALUE;
                    clearHover();
                }
                if (dragging && !items.isEmpty()) {
                    float p = dragStartPos - (x - dragStartX) / Math.max(1f, offX[1]);
                    if (!wraps()) {
                        final float max = items.size() - 1;
                        if (p < 0f) p *= 0.35f;
                        else if (p > max) p = max + (p - max) * 0.35f;
                    }
                    pos.snap(p);
                }
                if (!dragging) updateHover(now);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPressRunnable);
                press.set(0f, now);
                if (velocity != null) velocity.addMovement(e);
                if (dragging) {
                    float vx = 0f;
                    if (velocity != null) {
                        velocity.computeCurrentVelocity(1000);
                        vx = velocity.getXVelocity();
                    }
                    // Setrvacnost: rychlost laseru se preda pruzine, cil = kam by dojela.
                    final float v = -vx / Math.max(1f, offX[1]);
                    final float cur = pos.get(now);
                    float t = Math.round(cur + v * 0.25f);
                    t = clamp(t, Math.round(cur) - 4, Math.round(cur) + 4);
                    dragging = false;
                    select(t, v);
                } else {
                    click(x, y, now);
                }
                touching = false;
                pressedK = Integer.MIN_VALUE;
                updateHover(now);
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelTouch();
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void cancelTouch() {
        final long now = System.nanoTime();
        handler.removeCallbacks(longPressRunnable);
        press.set(0f, now);
        if (dragging) {
            dragging = false;
            select(Math.round(pos.get(now)), Float.NaN);
        }
        touching = false;
        pressedK = Integer.MIN_VALUE;
    }

    private void click(float x, float y, long now) {
        final int zone = zoneAt(x, y);
        if (zone != ZONE_NONE && zone == downZone) {
            if (host == null) return;
            if (zone == ZONE_BACK) host.onExitCarousel();
            else if (zone == ZONE_SETTINGS) host.onOpenSettings();
            else if (zone == ZONE_STATUS) host.onOpenQuickMenu(new RectF(statusRect));
            else if (zone == ZONE_USAGE) host.onRequestUsageAccess();
            else if (zone >= ZONE_TAB0 && zone < ZONE_TAB0 + TAB_NAMES.length) {
                final int t = zone - ZONE_TAB0;
                if (t != tab) host.onTabSelected(t);
            }
            return;
        }
        final int k = cardAt(x, y, now);
        if (k == Integer.MIN_VALUE || k != pressedK) return;
        if (k == centerK()) launch(k, now);
        else select(k, Float.NaN);
    }

    private void onLongPress() {
        if (!touching || dragging || pressedK == Integer.MIN_VALUE || pressedK != centerK()) return;
        final Item it = itemAt(pressedK);
        if (it == null) return;
        final long now = System.nanoTime();
        touching = false;
        pressedK = Integer.MIN_VALUE;
        press.set(0f, now);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (host != null) {
            host.onAppMenu(it.app, new RectF(cx - cw / 2f, cy - ch / 2f, cx + cw / 2f, cy + ch / 2f));
        }
        invalidate();
    }

    /** Spusteni prostredni karty - stejne "kukatko" jako v mrizce. */
    private void launch(int k, long now) {
        final Item it = itemAt(k);
        if (it == null) return;
        final AppEntry app = it.app;
        final float animScale = ValueAnimator.getDurationScale();
        if (prefs == null || !prefs.launchAnimation() || animScale <= 0f || !isHardwareAccelerated()) {
            if (host != null) host.onLaunch(app);
            return;
        }
        final boolean closes = prefs.closeAfterLaunch();
        final Shader shader;
        if (it.art != null) {
            BitmapShader bs = new BitmapShader(it.art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(cw / it.art.getWidth(), ch / it.art.getHeight());
            m.postTranslate(-cw / 2f, -ch / 2f);
            bs.setLocalMatrix(m);
            shader = bs;
        } else {
            int[] pair = Placeholders.colorsFor(app.pkg);
            shader = new LinearGradient(-cw / 2f, -ch / 2f, cw / 2f, ch / 2f, pair[0], pair[1],
                    Shader.TileMode.CLAMP);
        }
        final float z0 = 1f + 0.03f * clamp(it.hl.get(now), 0f, 1f);
        launchK = k;
        clearHover();
        peephole.start(now, animScale, cx, cy, cw, ch, z0, dp(CARD_RADIUS), shader, it.label, closes);
        final long dur = (long) (PeepholeAnimation.DURATION_MS * animScale);
        handler.postDelayed(() -> {
            if (host != null) host.onLaunch(app);
        }, (long) (dur * PeepholeAnimation.FIRE_AT));
        if (closes) {
            handler.postDelayed(() -> {
                if (host != null) host.onLaunchSequenceDone();
            }, dur);
        }
        invalidate();
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
