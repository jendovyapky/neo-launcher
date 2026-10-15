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
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import com.neolauncher.art.ArtworkLoader;
import com.neolauncher.art.Placeholders;
import com.neolauncher.data.AppEntry;
import com.neolauncher.data.AppRepository;
import com.neolauncher.data.Prefs;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cely launcher v JEDNOM View. Vsechno (sklo, horni lista, mrizka, hover,
 * rozmazani, rolovani, presouvani) se kresli a ridi tady.
 * <p>
 * Proc ne RecyclerView jako ve forku: hover efekty tam zavisely na
 * ACTION_HOVER_ENTER/EXIT jednotlivych karet, ktere Quest pri presunu
 * laseru mezi sousednimi kartami neposila spolehlive (CLAUDE.md, bug 1b).
 * Tady se hover pocita z pozice ukazatele pri KAZDEM pohybu i snimku:
 * jeden zdroj pravdy ({@link #focused}), zadne callbacky, zadna recyklace.
 * <p>
 * Rozmery jsou v dp a odpovidaji CSS pixelum z docs/preview_neo.html.
 */
public final class NeoLauncherView extends View implements ArtworkLoader.Listener, Prefs.Listener {

    /** Akce, ktere View nechava na aktivite. */
    public interface Host {
        void onLaunch(AppEntry app);

        void onOpenSettings();

        void onAppMenu(AppEntry app, RectF cardRect);

        void onTabSelected(int tab);

        void onOrderChanged(int tab, List<String> pkgs);

        /** Konec animace spusteni - launcher se muze zavrit. */
        void onLaunchSequenceDone();

        /** Logo Neo klepnuto 5x rychle za sebou - prepnout testovaci karusel. */
        void onToggleCarousel();

        /** Klepnuti na hodiny/baterii - rychle menu (vyjede z tohoto obdelniku). */
        void onOpenQuickMenu(RectF origin);

        /** Hledani (ikona lupy v leve liste). */
        void onOpenSearch(RectF origin);

        /** Lista bezici aplikace: RUN_RESUME / RUN_QUIT / RUN_HIDE. */
        void onRunningAction(int action);
    }

    // --- Rozmery z preview (dp) ----------------------------------------------
    private static final float FRAME_RADIUS = 28f;
    /**
     * Plovouci horni lista ("ornament" jako ve visionOS) se zalozkami, casem
     * a baterii - napul zanorena do horni hrany panelu (stred na frame.top).
     */
    private static final float ORN_H = 52f;
    /** Svetly okraj skla u laseru: dosah svetla (dp), rychlost dojezdu (s), tloustka hrany (dp). */
    private static final float RIM_RADIUS = 230f;
    private static final float RIM_RESPONSE = 0.24f;
    private static final float RIM_WIDTH = 1.6f;
    /** Nastup karet pri otevreni: delka, zpozdeni okraju proti stredu, rozestup a priblizeni na zacatku. */
    private static final float INTRO_MS = 900f;
    private static final float INTRO_STAGGER_MS = 300f;
    private static final float INTRO_SPREAD = 0.34f;
    private static final float INTRO_ZOOM = 0.22f;
    /**
     * Nastup zacne az od prvniho opravdu vykresleneho snimku a jeste o chvili
     * pozdeji - Quest okno ukazuje s vlastni animaci, jinak by nastup probehl
     * "naslepo" (na headsetu nebyl videt). Prolnuti obsahu (ms).
     */
    private static final long INTRO_DELAY_MS = 180;
    private static final float INTRO_FADE_MS = 450f;
    /** Polozky ornamentu (zalozky, stav) - soustredne s ornamentem: (52 - 40) / 2 = 6. */
    private static final float ORN_ITEM_H = 40f;
    private static final float ORN_INSET = 6f;
    /** Vodorovny okraj textu v polozce ornamentu (zalozky i stav). */
    private static final float ORN_PAD_X = 18f;
    /** Mezera mezi zalozkami a oddelovacem (z obou stran stejna). */
    private static final float ORN_DIVIDER = 10f;
    /** Mezi casem, baterii a ikonou. */
    private static final float ORN_STATUS_GAP = 12f;
    private static final float ORN_ICON = 17f;
    /** Ziva bublina v ornamentu: krouzek s ikonou (dp) a mezera k textu. */
    private static final float LIVE_RING = 26f;
    private static final float LIVE_GAP = 10f;
    /** Prubeh bez dalsi zpravy nejdele takhle dlouho. */
    private static final long LIVE_MAX_MS = 120_000L;
    /** Delsi text se zkrati trojteckou. */
    private static final float LIVE_TEXT_MAX = 280f;
    /**
     * Lista bezici aplikace dole na panelu (3x Meta): vyska, okraj uvnitr
     * (soustredne: radius 32 - 8 = 24 u nahledu a tlacitek), odsazeni od spodni hrany.
     */
    private static final float RUN_H = 64f;
    private static final float RUN_INSET = 8f;
    private static final float RUN_BOTTOM = 16f;
    private static final float RUN_BTN_PAD = 18f;
    private static final float RUN_TITLE_MAX = 200f;
    private static final String[] RUN_LABELS = {"Pokračovat", "Ukončit"};
    /** Tekute sklo ornamentu: sirka lomiveho okraje a posun obrazu na hrane (dp). */
    private static final float LIQUID_BAND = 16f;
    private static final float LIQUID_STRENGTH = 11f;
    private static final float FRAME_TOP = 36f;
    /** Mrizka zacina pod ornamentem (i zvetsena karta v prvni rade ho nezakryje). */
    private static final float GRID_TOP = 50f;
    /** Leva lista s ikonami (Knihovna, Karusel, Rychle menu, Nastaveni); po najeti se rozbali. */
    private static final float RAIL_LEFT = 16f;
    private static final float RAIL_W = 56f;
    private static final float RAIL_GAP = 16f;
    private static final float RAIL_EXPAND = 132f;
    private static final float RAIL_ITEM = 44f;
    private static final float GRID_SIDE = 24f;
    private static final float GRID_BOTTOM = 24f;
    private static final float GAP = 15f;
    private static final float CARD_RADIUS = 16f;
    private static final float POPOUT_MARGIN_X = 24f;
    private static final float POPOUT_MARGIN_Y = 30f;

    // --- Hover ----------------------------------------------------------------
    private static final float HOVER_SCALE = 1.18f;
    private static final float HOVER_Z = 32f;
    private static final float PERSPECTIVE = 900f;
    private static final float TILT_DEG = 6f;
    /**
     * Smer naklonu. +1 = strana pod ukazatelem se "zamackne" dozadu
     * (jako CSS rotateX/rotateY v preview). Kdyby to na Questu pusobilo
     * obracene, staci zmenit na -1.
     */
    private static final float TILT_SIGN = 1f;
    /** Fyzika (response s, tlumeni) - jako Apple spring: hover lehce "pruzne" dojede. */
    private static final float[] HOVER_SPRING = {0.42f, 0.75f};
    private static final float[] TILT_SPRING = {0.38f, 0.85f};
    private static final float[] REORDER_SPRING = {0.40f, 0.82f};
    private static final long BLUR_MS = 350;
    /**
     * Presouvani: ostatni karty se lehce trasou (jako ikony pri upravach plochy),
     * o chlup se zmensi a uhnou tazene karte. Uhel (st.), frekvence (Hz),
     * zmenseni, o kolik uhnou (dp) a dosah (nasobek sirky karty).
     */
    private static final float WIGGLE_DEG = 0.9f;
    private static final float WIGGLE_HZ = 2.6f;
    private static final float WIGGLE_SHRINK = 0.035f;
    private static final float WIGGLE_PUSH = 12f;
    private static final float WIGGLE_REACH = 0.9f;
    /** Po tak dlouhem podrzeni se karta zvedne a jde tahat. */
    private static final long LONG_PRESS_MS = 450;
    private static final long HOVER_EXIT_GRACE_MS = 90;
    /** Logo: dalsi klepnuti do teto doby se pocita do "5x klepnout" (karusel). */
    private static final long BRAND_TAP_MS = 380;
    private static final int BRAND_TAPS_CAROUSEL = 5;
    /** Animace spusteni "kukatko": celkova delka a okamzik, kdy se opravdu spusti aplikace. */
    private static final long LAUNCH_MS = 1150;
    private static final float LAUNCH_FIRE_AT = 0.48f;
    /** Pojistka: bez jakekoliv udalosti ukazatele tak dlouho = ukazatel je pryc. */
    private static final long POINTER_STALE_MS = 6000;

    // --- Rolovani (konstanty z preview, prepoctene na 60 snimku/s) -------------
    private static final float SCROLL_LERP = 0.16f;
    private static final float OVERSCROLL_RESIST = 0.38f;
    private static final float SQUISH_MAX = 0.045f;
    private static final float SQUISH_PER_DP = 0.0028f;
    private static final float WHEEL_GAIN = 1.0f;

    private static final int ZONE_NONE = 0;
    private static final int ZONE_BRAND = 1;
    private static final int ZONE_TAB0 = 2; // +index zalozky
    /** Cela pilulka s hodinami a baterii (klepnuti = rychle menu). */
    private static final int ZONE_BATTERY = 10;
    private static final int ZONE_RAIL0 = 20; // +index polozky leve listy
    private static final int ZONE_RAIL_BG = 30;
    private static final int ZONE_ORN_BG = 31;
    /** Lista bezici aplikace: tlacitka (+index) a zbytek listy. */
    private static final int ZONE_RUN0 = 40;
    private static final int ZONE_RUN_BG = 43;

    private static final String[] RAIL_LABELS = {"Knihovna", "Hledat", "Karusel", "Rychlé menu", "Nastavení"};
    private static final int[] RAIL_ICONS = {Icons.COLUMNS, Icons.SEARCH, Icons.CAROUSEL, Icons.SLIDERS, Icons.GEAR};
    private static final int RAIL_LIBRARY = 0;
    private static final int RAIL_SEARCH = 1;
    private static final int RAIL_CAROUSEL = 2;
    private static final int RAIL_QUICK = 3;
    private static final int RAIL_SETTINGS = 4;

    private static final String[] TAB_NAMES = {"Hry", "Aplikace", "Vše"};

    /** Stav jedne karty. Pozice jsou v "obsahovych" souradnicich mrizky. */
    private static final class Card {
        AppEntry app;
        String label;
        String shownLabel;
        float shownLabelWidth;
        float shownLabelFor = -1;
        int index;
        // Pruziny (Spring): pri zmene cile se zachova rychlost, hover lehce prekmitne.
        final Spring hover = new Spring(0, HOVER_SPRING);
        final Spring rotX = new Spring(0, TILT_SPRING, 0.01f);
        final Spring rotY = new Spring(0, TILT_SPRING, 0.01f);
        final Spring press = new Spring(0, 0.22f, 0.9f, 0.002f);
        final Spring x = new Spring(0, REORDER_SPRING, 0.3f);
        final Spring y = new Spring(0, REORDER_SPRING, 0.3f);
        final Eased flash = new Eased(0, 420, Eased.EASE_OUT);
        Bitmap art;
        BitmapShader artShader;
        float artShaderW = -1;
        Shader placeholder;
        float placeholderW = -1;
        /** Vlastni RenderNode jen pro kartu, ktera je prave naklonena (3D rotace). */
        RenderNode node;
        /** Oblibena (hvezdicka) a nova (jeste nespustena, cerstve nainstalovana). */
        boolean favorite, fresh;
    }

    private final float d; // density
    private Host host;
    private Prefs prefs;
    private ArtworkLoader artwork;

    private final List<Card> cards = new ArrayList<>();
    private final Map<String, Card> cardByPkg = new HashMap<>();
    private int tab = Prefs.TAB_GAMES;
    private boolean appsLoaded;

    // --- Geometrie (px) ---------------------------------------------------------
    private final RectF frame = new RectF();
    private float topBarBottom, gridLeft, gridTop, gridRight;
    private float cardW, cardH, gap;
    private int cols = 4;
    private float minScroll;
    private float hoverScaleEff;

    // --- Ukazatel a fokus ---------------------------------------------------------
    private boolean pointerIn;
    private float px, py;
    private long lastPointerEventMs;
    private boolean interactive = true;
    /** JEDINY zdroj pravdy o tom, ktera karta je pod ukazatelem. */
    private Card focused;
    private int hoverZone = ZONE_NONE;
    private final Eased blurAmount = new Eased(0, BLUR_MS);

    // --- Animace spusteni ("kukatko") - samotna animace je v PeepholeAnimation --------
    private Card launchCard;
    private boolean launchCloses;
    private PeepholeAnimation peephole;
    private float blurCx, blurCy;

    // --- Dotyk / rolovani / presouvani --------------------------------------------
    private final float touchSlop;
    private boolean touching, touchScrolling;
    private float downX, downY, lastTouchY;
    private int downZone = ZONE_NONE;
    private Card pressedCard;
    private VelocityTracker velocity;
    private float scrollCur, scrollTarget, flingV, squish = 1f, squishPivot;
    private long lastStepNs;
    private boolean scrollActive;
    /** Po otevreni / prepnuti zalozky obnovit posledni pozici rolovani (ulozena v Prefs). */
    private boolean restoreScroll = true;

    private boolean dragging;
    private Card dragCard;
    private float dragDX, dragDY, liftX, liftY;
    private boolean dragMoved, dragOrderChanged;
    private final Spring dragLift = new Spring(0, 0.34f, 0.68f, 0.002f);
    private final Spring dragTilt = new Spring(0, 0.30f, 0.75f, 0.02f);
    private float lastDragX;
    private long lastDragStepNs;
    /** Trasani ostatnich karet (0..1) - zacne az kartou pohnes, po pusteni dobehne. */
    private final Spring wiggle = new Spring(0, 0.35f, 1f, 0.002f);
    /** Stred tazene karty (obrazovka) - drzi se i po pusteni, at karty plynule dosednou. */
    private float dragCx, dragCy;
    /** Misto, kam karta dopadne (sklenena "jamka"), jede za zmenou poradi. */
    private final Spring ghostX = new Spring(0, REORDER_SPRING, 0.3f);
    private final Spring ghostY = new Spring(0, REORDER_SPRING, 0.3f);

    // --- Horni lista ---------------------------------------------------------------
    private final Spring tabPos = new Spring(0, 0.42f, 0.78f, 0.001f);
    private final Eased brandHover = new Eased(0, 180, Eased.EASE_OUT);
    private final Eased brandTap = new Eased(0, 420, Eased.EASE_OUT);
    private int brandTaps;
    private final Eased batteryHover = new Eased(0, 180, Eased.EASE_OUT);
    private final Eased[] tabHover = {new Eased(0, 180, Eased.EASE_OUT),
            new Eased(0, 180, Eased.EASE_OUT), new Eased(0, 180, Eased.EASE_OUT)};
    private final Eased contentFade = new Eased(1, 280, Eased.EASE_OUT);
    /** Nastup karet pri otevreni (playIntro): zacatek, meritko animaci systemu. */
    private long introNs;
    private float introScale = 1f;
    /** playIntro zavolano, ale jeste nebyl vykresleny snimek - hodiny nastupu se spusti v onDraw. */
    private boolean introArmed;
    /** Logo Neo nahore v leve liste. */
    private final RectF brandRect = new RectF();
    private final RectF ornRect = new RectF();
    private final RectF railRect = new RectF();
    private final RectF[] railRects = {new RectF(), new RectF(), new RectF(), new RectF(), new RectF()};
    private final Eased[] railHover = {new Eased(0, 180, Eased.EASE_OUT), new Eased(0, 180, Eased.EASE_OUT),
            new Eased(0, 180, Eased.EASE_OUT), new Eased(0, 180, Eased.EASE_OUT), new Eased(0, 180, Eased.EASE_OUT)};
    private final Spring railExpand = new Spring(0, 0.36f, 0.82f, 0.002f);
    /** Svetly okraj skla u laseru: poloha (px) a sila. */
    private final Spring rimX = new Spring(0, RIM_RESPONSE, 1f, 0.3f);
    private final Spring rimY = new Spring(0, RIM_RESPONSE, 1f, 0.3f);
    private final Spring rimAmt = new Spring(0, 0.45f, 1f, 0.002f);
    private Shader rimShader;
    private final Matrix rimMatrix = new Matrix();
    private final Path chromePath = new Path();
    /** Tvar ornamentu (vcetne paralaxy) - pod nim se panel a ostre karty nekresli, jen sklo. */
    private final Path ornHole = new Path();
    private float ornDividerX;
    private final RectF tabsRect = new RectF();
    private final RectF[] tabRects = {new RectF(), new RectF(), new RectF()};
    private final RectF statusRect = new RectF();
    private final RectF batteryRect = new RectF();
    private int batteryLevel = -1;
    private boolean charging, fastCharging;
    /** Slaba baterie (<= 20 %, nenabiji se): kdy zacala - prvnich par sekund pilulka pulzuje. */
    private long lowBatterySinceNs;
    private static final long LOW_BATTERY_PULSE_NS = 8_000_000_000L;
    private long clockMinute = -1;
    private String clockTime = "";

    // --- Ziva bublina (instalace, nabijeni, aktualizace...) ---------------------------
    /** Pro showLive: bez krouzku (jen ikona) / tocici se krouzek. */
    public static final float LIVE_ICON_ONLY = Float.NaN;
    public static final float LIVE_SPINNER = -1f;
    private String liveText;
    private int liveIcon;
    private int liveColor;
    private float liveProgress = LIVE_ICON_ONLY;
    /** Do kdy je bublina rozbalena (0 = dokud neprijde dalsi / clearLive). Stav jen z casu. */
    private long liveUntilNs;
    private float liveTextW;
    /** Rozbaleni bubliny - lehce prekmitne, jako by se nafoukla. */
    private final Spring liveAmt = new Spring(0, 0.5f, 0.74f, 0.001f);
    private final Spring liveProg = new Spring(0, 0.45f, 1f, 0.001f);
    /** Sirka textu na pruzine (zmena textu = plynule roztazeni, ne skok). */
    private final Spring liveWidth = new Spring(0, 0.4f, 0.9f, 0.3f);
    private final Runnable liveExpire = this::invalidate;

    // --- Lista bezici aplikace (Pokracovat / Ukoncit) --------------------------------
    public static final int RUN_RESUME = 0;
    public static final int RUN_QUIT = 1;
    public static final int RUN_HIDE = 2;
    private AppEntry runApp;
    private String runLabel;
    private boolean runOn;
    private final Spring runAmt = new Spring(0, 0.46f, 0.78f, 0.001f);
    private final RectF runRect = new RectF();
    private final RectF runThumb = new RectF();
    private final RectF[] runBtn = {new RectF(), new RectF(), new RectF()};
    private final Eased[] runHover = {new Eased(0, 180, Eased.EASE_OUT), new Eased(0, 180, Eased.EASE_OUT),
            new Eased(0, 180, Eased.EASE_OUT)};
    private final GlassSurface runGlass = new GlassSurface();
    private final GlassSurface[] runBtnGlass = {new GlassSurface(), new GlassSurface()};
    private Bitmap runArt;
    private BitmapShader runShader;
    private final Matrix runMatrix = new Matrix();
    private final TextPaint runTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint runSub = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private String runShownTitle;

    // --- Kresleni ------------------------------------------------------------------
    private final Matrix shaderMatrix = new Matrix();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint spritePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint pillText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint brandText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint tabText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint clockText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint batteryText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint emptyText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint badgeText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private AppRepository.Signals signals;
    private final Path framePath = new Path();
    private final Path boltPath = new Path();
    private final RectF tmp = new RectF();
    private Shader bottomShade;
    /** Sklo panelu, ornamentu a listy (GlassSurface drzi shadery v cache). */
    private final GlassSurface panelGlass = new GlassSurface();
    private final GlassSurface ornGlass = new GlassSurface();
    private final GlassSurface railGlass = new GlassSurface();
    private final GlassSurface tabGlass = new GlassSurface();
    private GlassSurface.Style panelStyle;
    /** Svetlo hry na skle (drawGameLight). */
    private Shader lightShader;
    private int lightColor;
    private final android.graphics.Matrix lightMatrix = new android.graphics.Matrix();
    private float panelStyleAlpha = -1f;
    private boolean panelStyleLight;
    /** Svetle sklo ve stylu visionOS (volba v nastaveni), cache podle kryti. */
    private ShadowSprite restShadow, hoverShadow, glowSprite;
    private float spriteForW = -1;

    private final RenderNode contentNode = new RenderNode("neo-content");
    /** Rozmazana kopie karet pod ornamentem = matne sklo horni listy. */
    private final RenderNode backdropNode = new RenderNode("neo-ornament-backdrop");
    private final DepthBlur depthBlur = new DepthBlur();
    private RenderEffect backdropEffect;
    /** Lom svetla u okraje horni bubliny (tekute sklo), retezeny za rozmazanim. */
    private final LiquidGlass liquidGlass = new LiquidGlass();
    /** Totez pro listu bezici aplikace (vlastni instance - jina velikost, vlastni cache efektu). */
    private final RenderNode runBackdropNode = new RenderNode("neo-run-backdrop");
    private final LiquidGlass runLiquid = new LiquidGlass();
    private ModalDepth modalDepth;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hoverExitRunnable = () -> {
        pointerIn = false;
        updateFocus(System.nanoTime());
        invalidate();
    };
    private boolean stalePosted;
    private final Runnable staleRunnable = new Runnable() {
        @Override
        public void run() {
            stalePosted = false;
            if (!pointerIn) return;
            long idle = System.currentTimeMillis() - lastPointerEventMs;
            if (idle >= POINTER_STALE_MS && !touching && !dragging) {
                clearPointer();
            } else {
                stalePosted = true;
                handler.postDelayed(this, Math.max(200, POINTER_STALE_MS - idle + 50));
            }
        }
    };
    private final Runnable longPressRunnable = this::onLongPress;
    /** Po poslednim klepnuti na logo: mene nez 5 klepnuti = otevrit nastaveni. */
    private final Runnable brandTapRunnable = () -> {
        final int taps = brandTaps;
        brandTaps = 0;
        if (taps > 0 && taps < BRAND_TAPS_CAROUSEL && host != null) host.onOpenSettings();
    };
    /** Karta zvednuta a porad drzena bez pohybu -> po prodleve z nastaveni otevrit menu. */
    private final Runnable menuHoldRunnable = this::onMenuHold;
    private long liftNs;

    public NeoLauncherView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        touchSlop = Math.max(ViewConfiguration.get(c).getScaledTouchSlop(), dp(10));
        setFocusable(false);
        setClickable(true);

        Typeface semi = Typeface.create(Typeface.SANS_SERIF, 600, false);
        Typeface bold = Typeface.create(Typeface.SANS_SERIF, 700, false);

        pillText.setTypeface(semi);
        pillText.setTextSize(dp(11.5f));
        pillText.setColor(Color.WHITE);

        brandText.setTypeface(bold);
        brandText.setTextSize(dp(19));
        brandText.setLetterSpacing(0.5f / 19f);
        brandText.setColor(Color.WHITE);

        tabText.setTypeface(semi);
        tabText.setTextSize(dp(14));
        tabText.setTextAlign(Paint.Align.CENTER);

        clockText.setTypeface(semi);
        clockText.setTextSize(dp(14));
        clockText.setFontFeatureSettings("tnum");
        clockText.setLetterSpacing(0.5f / 14f);
        clockText.setColor(0xF2FFFFFF);

        batteryText.setTypeface(bold);
        batteryText.setTextSize(dp(12.5f));
        batteryText.setFontFeatureSettings("tnum");

        emptyText.setTypeface(semi);
        emptyText.setTextSize(dp(16));
        emptyText.setTextAlign(Paint.Align.CENTER);
        emptyText.setColor(0x99FFFFFF);

        peephole = new PeepholeAnimation(d);

        badgeText.setTypeface(bold);
        badgeText.setTextSize(dp(10));
        badgeText.setLetterSpacing(0.08f);
        badgeText.setTextAlign(Paint.Align.CENTER);

        stroke.setStyle(Paint.Style.STROKE);

        runTitle.setTypeface(semi);
        runTitle.setTextSize(dp(15));
        runSub.setTypeface(Typeface.create(Typeface.SANS_SERIF, 400, false));
        runSub.setTextSize(dp(12.5f));

        // Blesk z preview: SVG path "M11 2L4 13h5l-1.5 9L18 11h-5.5L14 2Z" (viewBox 24x24)
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

    /** Odkud se bere "nova aplikace" (stitek NOVE). */
    public void setSignals(AppRepository.Signals s) {
        signals = s;
    }

    public void bind(Prefs p, ArtworkLoader a) {
        prefs = p;
        artwork = a;
        tab = p.tab();
        tabPos.snap(tab);
        restoreScroll = true;
    }

    /** Ulozi pozici rolovani aktualni zalozky (pri odchodu z launcheru / prepnuti zalozky). */
    public void saveState() {
        if (prefs != null && appsLoaded && !cards.isEmpty()) prefs.setScrollDp(tab, scrollTarget / d);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (artwork != null) artwork.addListener(this);
        if (prefs != null) prefs.addListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (artwork != null) artwork.removeListener(this);
        if (prefs != null) prefs.removeListener(this);
        handler.removeCallbacksAndMessages(null);
        stalePosted = false;
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
        contentNode.discardDisplayList();
        backdropNode.discardDisplayList();
        runBackdropNode.discardDisplayList();
        for (Card k : cards) if (k.node != null) k.node.discardDisplayList();
        peephole.discard();
    }

    public int tab() {
        return tab;
    }

    /**
     * Nastavi aplikace zobrazene v mrizce. Existujici karty si drzi svuj
     * stav (obrazek, animace), jen se plynule presunou na nove misto.
     */
    public void setApps(List<AppEntry> list, List<String> labels, boolean animate) {
        final long now = System.nanoTime();
        appsLoaded = true;
        Map<String, Card> old = new HashMap<>(cardByPkg);
        cards.clear();
        cardByPkg.clear();
        for (int i = 0; i < list.size(); i++) {
            AppEntry e = list.get(i);
            Card k = old.remove(e.pkg);
            boolean fresh = k == null;
            if (fresh) k = new Card();
            k.app = e;
            String label = labels.get(i);
            if (!label.equals(k.label)) {
                k.label = label;
                k.shownLabelFor = -1;
            }
            k.index = i;
            k.favorite = prefs != null && prefs.isFavorite(e.pkg);
            k.fresh = signals != null && signals.isNew(e);
            cards.add(k);
            cardByPkg.put(e.pkg, k);
            float sx = slotX(i), sy = slotY(i);
            if (animate && !fresh) {
                k.x.set(sx, now);
                k.y.set(sy, now);
            } else {
                k.x.snap(sx);
                k.y.snap(sy);
            }
        }
        // Karta, ktera zmizela, nesmi zustat jako fokus/tazena.
        if (focused != null && !cardByPkg.containsKey(focused.app.pkg)) focused = null;
        if (pressedCard != null && !cardByPkg.containsKey(pressedCard.app.pkg)) pressedCard = null;
        if (dragging && (dragCard == null || !cardByPkg.containsKey(dragCard.app.pkg))) {
            dragging = false;
            dragCard = null;
            dragLift.set(0f, now);
            wiggle.set(0f, now);
        }
        updateScrollBounds();
        tryRestoreScroll();
        if (artwork != null) {
            List<AppEntry> pre = new ArrayList<>(list);
            artwork.prefetch(pre);
        }
        updateFocus(now);
        invalidate();
    }

    /** Prepnuti zalozky: obsah se kratce prolne a odroluje nahoru. */
    public void showTab(int newTab, List<AppEntry> list, List<String> labels) {
        final long now = System.nanoTime();
        saveState();
        tab = newTab;
        tabPos.set(newTab, now);
        resetHoverState();
        flingV = 0;
        scrollCur = scrollTarget = 0;
        restoreScroll = true;
        setApps(list, labels, false);
        contentFade.snap(0f);
        contentFade.set(1f, now);
        invalidate();
    }

    public void setBattery(int level, boolean isCharging, boolean isFast) {
        if (level == batteryLevel && isCharging == charging && isFast == fastCharging) return;
        final boolean wasLow = isLowBattery();
        batteryLevel = level;
        charging = isCharging;
        fastCharging = isFast;
        if (isLowBattery() && !wasLow) lowBatterySinceNs = System.nanoTime();
        invalidate();
    }

    private boolean isLowBattery() {
        return batteryLevel >= 0 && batteryLevel <= 20 && !charging;
    }

    /** Volat pri tiknuti minuty (hodiny) - prekresli horni listu. */
    public void onClockTick() {
        clockMinute = -1;
        invalidate();
    }

    /**
     * Ziva bublina: cas a baterie v horni liste se na chvili promeni v ikonu
     * (s krouzkem prubehu) a kratky text - instalace, nabijeni, aktualizace.
     *
     * @param progress 0..1 = krouzek prubehu, {@link #LIVE_SPINNER} = toci se,
     *                 {@link #LIVE_ICON_ONLY} = jen ikona v barve
     * @param ms       jak dlouho zustane rozbalena (0 = dokud neprijde dalsi / clearLive, nejdele 2 min)
     */
    public void showLive(int icon, String text, int color, float progress, long ms) {
        final long now = System.nanoTime();
        final boolean wasOn = liveOn(now);
        liveIcon = icon;
        liveText = TextUtils.ellipsize(text, clockText, dp(LIVE_TEXT_MAX), TextUtils.TruncateAt.END).toString();
        liveColor = color;
        liveTextW = clockText.measureText(liveText);
        if (!wasOn && liveAmt.get(now) < 0.05f) liveWidth.snap(liveTextW);
        else liveWidth.set(liveTextW, now);
        if (progress >= 0f) {
            if (!wasOn || !(liveProgress >= 0f)) liveProg.snap(0f);
            liveProg.set(clamp(progress, 0f, 1f), now);
        }
        liveProgress = progress;
        // Pojistka: prubeh bez dalsi zpravy (napr. nepotvrzena instalace) sam zmizi.
        if (ms <= 0) ms = LIVE_MAX_MS;
        liveUntilNs = now + ms * 1_000_000L;
        liveAmt.set(1f, now);
        handler.removeCallbacks(liveExpire);
        handler.postDelayed(liveExpire, ms + 30);
        invalidate();
    }

    /** Bublina se zase smrskne na cas a baterii. */
    public void clearLive() {
        liveUntilNs = 1L; // v minulosti
        liveAmt.set(0f, System.nanoTime());
        handler.removeCallbacks(liveExpire);
        invalidate();
    }

    /**
     * Lista bezici aplikace (3x Meta ze hry): dole na panelu nahled, nazev
     * a tlacitka Pokracovat / Ukoncit / skryt. null = schovat.
     */
    public void setRunningApp(AppEntry app, String label) {
        final long now = System.nanoTime();
        final boolean on = app != null;
        if (on) {
            runApp = app;
            runLabel = label;
            runShownTitle = null;
        }
        if (on != runOn) {
            runOn = on;
            runAmt.set(on ? 1f : 0f, now);
            updateScrollBounds();
            if (!on && hoverZone >= ZONE_RUN0 && hoverZone <= ZONE_RUN_BG) {
                hoverZone = ZONE_NONE;
                updateZoneHover(now);
            }
        }
        invalidate();
    }

    private boolean liveOn(long now) {
        return liveText != null && (liveUntilNs == 0L || now < liveUntilNs);
    }

    /** Pri otevrenem nastaveni/menu: karty nereaguji a cely launcher se rozmaze. */
    public void setInteractive(boolean b) {
        interactive = b;
        if (!b) {
            // Rozpracovane gesto uz nedobehne (UP pujde do prazdna) - uklidit hned.
            cancelTouch();
            resetHoverState();
        }
        invalidate();
    }

    /** Otevreny dialog: launcher plynule ustoupi do hloubky (rozmazani + zmenseni). */
    public void setModalBlur(boolean b) {
        if (modalDepth == null) modalDepth = new ModalDepth(this, d);
        modalDepth.set(b);
    }

    /** Logo (odtud "vyroste" nastaveni). */
    public RectF brandRect() {
        return new RectF(brandRect);
    }

    /** Tlacitko nastaveni v leve liste. */
    public RectF settingsRect() {
        return new RectF(railRects[RAIL_SETTINGS]);
    }

    /** Hodiny + baterie (odtud "vyjede" rychle menu). */
    public RectF statusRect() {
        return new RectF(statusRect);
    }

    /**
     * Nastup pri otevreni launcheru: karty se "poskladaji" na sva mista - cela
     * mrizka se z lehkeho priblizeni stahne dovnitr, stred prvni, okraje
     * o chlup pozdeji (bez odskoku). Stav jen z casu (introNs).
     */
    public void playIntro() {
        final float scale = ValueAnimator.getDurationScale();
        if (scale <= 0f) return;
        final long now = System.nanoTime();
        introArmed = true;
        introScale = scale;
        // Do spusteni v onDraw karty nekreslit (nastup zacina z pruhledna).
        introNs = now + 60_000_000_000L;
        invalidate();
    }

    private boolean introActive(long now) {
        return introArmed
                || (introNs != 0 && (now - introNs) / 1e6f < (INTRO_MS + INTRO_STAGGER_MS) * introScale);
    }

    /** Pruhlednost obsahu behem nastupu (0 pred zacatkem, pak ease-out do 1). */
    private float introAlpha(long now) {
        if (!introActive(now)) return 1f;
        final float t = clamp((now - introNs) / 1e6f / introScale / INTRO_FADE_MS, 0f, 1f);
        return 1f - (1f - t) * (1f - t);
    }

    /** Navrat z karuselu: obsah se plynule objevi. */
    public void playEnter() {
        final long now = System.nanoTime();
        contentFade.snap(0f);
        contentFade.set(1f, now);
        invalidate();
    }

    /** Skleneny panel v souradnicich View (pro umisteni dialogu). */
    public RectF frameRect() {
        return new RectF(frame);
    }

    /** Tvrdy reset hoveru - napr. kdyz Quest vezme oknu fokus (CLAUDE.md, oprava #1). */
    public void clearPointer() {
        handler.removeCallbacks(hoverExitRunnable);
        pointerIn = false;
        cancelTouch();
        resetHoverState();
        invalidate();
    }

    private void resetHoverState() {
        final long now = System.nanoTime();
        for (Card k : cards) {
            k.hover.set(0, now);
            k.rotX.set(0, now);
            k.rotY.set(0, now);
            k.press.set(0, now);
        }
        focused = null;
        hoverZone = ZONE_NONE;
        blurAmount.set(0, now);
        updateZoneHover(now);
    }

    @Override
    public void onArtworkChanged(String pkg) {
        invalidate();
    }

    @Override
    public void onPrefsChanged() {
        relayout();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        // Quest umi panelu docasne sebrat fokus a pak neposila zadne hover
        // udalosti (ani HOVER_EXIT). Proto pri kazde zmene tvrdy reset.
        clearPointer();
    }

    // =========================================================================
    // Rozlozeni
    // =========================================================================

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        relayout();
    }

    private void relayout() {
        final float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || prefs == null) return;
        final boolean popout = prefs.popoutMargin();
        final float mx = popout ? dp(POPOUT_MARGIN_X) : dp(6);
        final float my = popout ? dp(POPOUT_MARGIN_Y) : dp(6);
        // Vlevo misto na listu s ikonami, nahore na polovinu ornamentu.
        frame.set(dp(RAIL_LEFT + RAIL_W + RAIL_GAP), dp(FRAME_TOP), w - mx, h - my);
        // Horni lista uz neni soucasti panelu - obsah zacina hned u horni hrany.
        topBarBottom = frame.top;
        gridLeft = frame.left + dp(GRID_SIDE);
        gridRight = frame.right - dp(GRID_SIDE);
        gridTop = frame.top + dp(GRID_TOP);
        final int oldCols = cols;
        final float oldCardW = cardW;
        cols = prefs.columns();
        gap = dp(GAP);
        cardW = Math.max(dp(60), (gridRight - gridLeft - gap * (cols - 1)) / cols);
        cardH = cardW / ArtworkLoader.CARD_ASPECT;
        final boolean geometryChanged = oldCols != cols || Math.abs(oldCardW - cardW) > 0.01f;

        final float persp = dp(PERSPECTIVE);
        // translateZ(32px) v perspective(900px) karte opticky prida ~3.7 % velikosti.
        hoverScaleEff = HOVER_SCALE * (persp / (persp - dp(HOVER_Z)));

        if (Math.abs(spriteForW - cardW) > 0.5f) {
            spriteForW = cardW;
            final float r = dp(CARD_RADIUS);
            restShadow = ShadowSprite.create(cardW, cardH, r, dp(10));   // 0 6px 20px
            hoverShadow = ShadowSprite.create(cardW, cardH, r, dp(27));  // 0 26px 55px
            glowSprite = ShadowSprite.create(cardW, cardH, r, dp(16));   // 0 0 32px
            bottomShade = new LinearGradient(0, cardH / 2f - dp(44), 0, cardH / 2f,
                    0x00000000, 0x8C000000, Shader.TileMode.CLAMP);
        }
        if (artwork != null) artwork.requestTargetSize("grid", Math.round(cardW * hoverScaleEff));

        final float fr = dp(FRAME_RADIUS);
        framePath.reset();
        framePath.addRoundRect(frame, fr, fr, Path.Direction.CW);

        // Vrstva karet: cely panel, vsechny rohy zaoblene.
        contentNode.setPosition(Math.round(frame.left), Math.round(topBarBottom),
                Math.round(frame.right), Math.round(frame.bottom));
        Outline o = new Outline();
        o.setRoundRect(0, 0, Math.round(frame.width()),
                Math.round(frame.bottom) - Math.round(topBarBottom), fr);
        contentNode.setOutline(o);
        contentNode.setClipToOutline(true);

        // Kopie karet pod ornamentem, rozmazana = "matne sklo" (poloha se urci pri kresleni,
        // u okraje se k rozmazani pridava lom - LiquidGlass).
        final float sigma = dp(14);
        final float radius = (sigma - 0.5f) / 0.57735f;
        backdropEffect = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP);

        final long now = System.nanoTime();
        // Jen pri zmene mrizky - jinak by zmena nastaveni zarizla rozbehnute animace.
        if (geometryChanged) {
            for (Card k : cards) {
                k.x.snap(slotX(k.index));
                k.y.snap(slotY(k.index));
                k.shownLabelFor = -1;
            }
        }
        updateScrollBounds();
        tryRestoreScroll();
        updateFocus(now);
        invalidate();
    }

    /**
     * Obnovi posledni pozici rolovani zalozky - az je znamy seznam i rozmery okna
     * (vola se z setApps i relayout, probehne jen jednou).
     */
    private void tryRestoreScroll() {
        if (!restoreScroll || cards.isEmpty() || frame.height() <= 0 || prefs == null) return;
        restoreScroll = false;
        flingV = 0;
        scrollCur = scrollTarget = clamp(prefs.scrollDp(tab) * d, minScroll, 0f);
    }

    private float slotX(int i) {
        return (i % cols) * (cardW + gap);
    }

    private float slotY(int i) {
        return (i / cols) * (cardH + gap);
    }

    private void updateScrollBounds() {
        final int rows = (cards.size() + cols - 1) / cols;
        final float contentH = rows * cardH + Math.max(0, rows - 1) * gap;
        // Lista bezici aplikace prekryva spodek mrizky - posledni rada musi jit odrolovat nad ni.
        final float visibleBottom = frame.bottom - dp(GRID_BOTTOM) - (runOn ? dp(RUN_H + RUN_BOTTOM) : 0f);
        minScroll = Math.min(0f, visibleBottom - (gridTop + contentH));
        if (!touching) kickScroll();
    }

    // =========================================================================
    // Transformace souradnic
    // =========================================================================
    // "scrolled" prostor = obsah posunuty o scrollCur. Na obrazovce se na nej
    // navic aplikuje gumove "zmacknuti" (squish) kolem squishPivot.

    private float screenToScrolledY(float sy) {
        return squishPivot + (sy - squishPivot) / squish;
    }

    private float scrolledToScreenY(float y) {
        return squishPivot + (y - squishPivot) * squish;
    }

    private float cardLeft(Card k, long now) {
        return gridLeft + k.x.get(now);
    }

    private float cardTop(Card k, long now) {
        return gridTop + k.y.get(now) + scrollCur;
    }

    private float visualScale(Card k, long now) {
        float h = k.hover.get(now);
        float p = k.press.get(now);
        return 1f + h * (hoverScaleEff - 1f) - p * 0.045f;
    }

    private Card cardAt(float x, float y, long now) {
        final float yy = screenToScrolledY(y);
        for (Card k : cards) {
            if (k == dragCard && dragging) continue;
            float l = cardLeft(k, now), t = cardTop(k, now);
            if (x >= l && x < l + cardW && yy >= t && yy < t + cardH) return k;
        }
        return null;
    }

    /** Obdelnik karty na obrazovce vcetne aktualniho zvetseni. */
    private void poppedRect(Card k, long now, RectF out) {
        float s = visualScale(k, now);
        float cx = cardLeft(k, now) + cardW / 2f;
        float cy = scrolledToScreenY(cardTop(k, now) + cardH / 2f);
        out.set(cx - cardW * s / 2f, cy - cardH * s * squish / 2f,
                cx + cardW * s / 2f, cy + cardH * s * squish / 2f);
    }

    // =========================================================================
    // Fokus (hover) - jediny zdroj pravdy
    // =========================================================================

    private void updateFocus(long now) {
        Card target = null;
        int zone = ZONE_NONE;
        if (pointerIn && interactive && !dragging && !cards.isEmpty()) {
            zone = zoneAt(px, py);
            if (focused != null) {
                poppedRect(focused, now, tmp);
                if (tmp.contains(px, py)) target = focused;
            }
            if (target == null && zone == ZONE_NONE && py >= topBarBottom
                    && frame.contains(px, py)) {
                target = cardAt(px, py, now);
            }
        } else if (pointerIn && interactive && !dragging) {
            zone = zoneAt(px, py);
        }
        if (target != null) zone = ZONE_NONE;

        // Svetly okraj skla jede za laserem (objevi se rovnou u nej, nepriletava).
        final boolean rimOn = pointerIn && interactive;
        if (rimOn) {
            if (rimAmt.get(now) < 0.02f) {
                rimX.snap(px);
                rimY.snap(py);
            } else {
                if (Math.abs(px - rimX.target()) > 0.5f) rimX.set(px, now);
                if (Math.abs(py - rimY.target()) > 0.5f) rimY.set(py, now);
            }
        }
        rimAmt.set(rimOn ? 1f : 0f, now);

        if (target != focused) {
            if (focused != null) {
                focused.hover.set(0, now);
                focused.rotX.set(0, now);
                focused.rotY.set(0, now);
                focused.press.set(0, now);
            }
            focused = target;
            if (target != null) target.hover.set(1, now);
            blurAmount.set(target != null ? 1f : 0f, now);
        }
        if (focused != null) {
            poppedRect(focused, now, tmp);
            float nx = clamp((px - tmp.centerX()) / (tmp.width() / 2f), -1f, 1f);
            float ny = clamp((py - tmp.centerY()) / (tmp.height() / 2f), -1f, 1f);
            float ry = TILT_SIGN * nx * TILT_DEG;
            float rx = -TILT_SIGN * ny * TILT_DEG;
            if (Math.abs(ry - focused.rotY.target()) > 0.05f) focused.rotY.set(ry, now);
            if (Math.abs(rx - focused.rotX.target()) > 0.05f) focused.rotX.set(rx, now);
            blurCx = cardLeft(focused, now) + cardW / 2f;
            blurCy = scrolledToScreenY(cardTop(focused, now) + cardH / 2f);
        }
        if (zone != hoverZone) {
            hoverZone = zone;
            updateZoneHover(now);
        }
    }

    private void updateZoneHover(long now) {
        brandHover.set(hoverZone == ZONE_BRAND ? 1f : 0f, now);
        batteryHover.set(hoverZone == ZONE_BATTERY ? 1f : 0f, now);
        for (int i = 0; i < tabHover.length; i++) {
            tabHover[i].set(hoverZone == ZONE_TAB0 + i ? 1f : 0f, now);
        }
        for (int i = 0; i < railHover.length; i++) {
            railHover[i].set(hoverZone == ZONE_RAIL0 + i ? 1f : 0f, now);
        }
        for (int i = 0; i < runHover.length; i++) {
            runHover[i].set(hoverZone == ZONE_RUN0 + i ? 1f : 0f, now);
        }
        // Leva lista se po najeti rozbali a ukaze popisky (jako lista ve visionOS).
        railExpand.set(isRailZone(hoverZone) ? 1f : 0f, now);
    }

    private int zoneAt(float x, float y) {
        if (runOn && runRect.contains(x, y)) {
            for (int i = 0; i < runBtn.length; i++) if (runBtn[i].contains(x, y)) return ZONE_RUN0 + i;
            return ZONE_RUN_BG;
        }
        if (railRect.contains(x, y)) {
            if (brandRect.contains(x, y)) return ZONE_BRAND;
            for (int i = 0; i < railRects.length; i++) if (railRects[i].contains(x, y)) return ZONE_RAIL0 + i;
            return ZONE_RAIL_BG;
        }
        if (ornRect.contains(x, y)) {
            for (int i = 0; i < tabRects.length; i++) if (tabRects[i].contains(x, y)) return ZONE_TAB0 + i;
            if (statusRect.contains(x, y)) return ZONE_BATTERY;
            return ZONE_ORN_BG;
        }
        return ZONE_NONE;
    }

    private boolean isRailZone(int zone) {
        return zone == ZONE_BRAND || zone == ZONE_RAIL_BG
                || (zone >= ZONE_RAIL0 && zone < ZONE_RAIL0 + RAIL_LABELS.length);
    }

    // =========================================================================
    // Vstup
    // =========================================================================

    private void touchPointer(float x, float y) {
        px = x;
        py = y;
        pointerIn = true;
        lastPointerEventMs = System.currentTimeMillis();
        handler.removeCallbacks(hoverExitRunnable);
        if (!stalePosted) {
            stalePosted = true;
            handler.postDelayed(staleRunnable, POINTER_STALE_MS);
        }
    }

    @Override
    public boolean onHoverEvent(MotionEvent e) {
        if (!interactive || launchCard != null) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                touchPointer(e.getX(), e.getY());
                updateFocus(System.nanoTime());
                invalidate();
                break;
            case MotionEvent.ACTION_HOVER_EXIT:
                // HOVER_EXIT chodi i tesne pred stiskem spouste - s kratkym
                // odkladem, aby karta pri kliknuti neblikla.
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

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_SCROLL
                && (e.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0) {
            if (!interactive || dragging || launchCard != null) return true;
            touchPointer(e.getX(), e.getY());
            float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (v != 0f) {
                float factor = ViewConfiguration.get(getContext()).getScaledVerticalScrollFactor();
                float delta = v * factor * WHEEL_GAIN;
                flingV = 0;
                scrollTarget += delta;
                if (scrollTarget > 0f) scrollTarget = scrollTarget * OVERSCROLL_RESIST;
                else if (scrollTarget < minScroll)
                    scrollTarget = minScroll + (scrollTarget - minScroll) * OVERSCROLL_RESIST;
                kickScroll();
            }
            return true;
        }
        return super.onGenericMotionEvent(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!interactive) return false;
        if (launchCard != null) return true; // behem animace spusteni se nic nedeje
        final float x = e.getX(), y = e.getY();
        final long now = System.nanoTime();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                touchPointer(x, y);
                touching = true;
                touchScrolling = false;
                downX = x;
                downY = y;
                lastTouchY = y;
                if (velocity == null) velocity = VelocityTracker.obtain();
                velocity.clear();
                velocity.addMovement(e);
                // Klepnuti behem setrvacneho rolovani ho zastavi.
                flingV = 0;
                if (scrollCur <= 0f && scrollCur >= minScroll) scrollTarget = scrollCur;
                updateFocus(now);
                downZone = zoneAt(x, y);
                pressedCard = downZone == ZONE_NONE ? focused : null;
                if (pressedCard != null) {
                    pressedCard.press.set(1f, now);
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                touchPointer(x, y);
                if (velocity != null) velocity.addMovement(e);
                if (dragging) {
                    if (!dragMoved && Math.hypot(x - liftX, y - liftY) > touchSlop) {
                        dragMoved = true;
                        handler.removeCallbacks(menuHoldRunnable);
                        wiggle.set(1f, now);
                    }
                    dragTo(x, y, now);
                    invalidate();
                    return true;
                }
                if (!touchScrolling && (Math.abs(y - downY) > touchSlop
                        || Math.abs(x - downX) > touchSlop)) {
                    touchScrolling = true;
                    lastTouchY = y;
                    handler.removeCallbacks(longPressRunnable);
                    if (pressedCard != null) pressedCard.press.set(0f, now);
                    pressedCard = null;
                }
                if (touchScrolling) {
                    float dy = y - lastTouchY;
                    lastTouchY = y;
                    float next = scrollCur + dy;
                    if (next > 0f || next < minScroll) next = scrollCur + dy * OVERSCROLL_RESIST;
                    scrollCur = scrollTarget = next;
                    kickScroll();
                }
                updateFocus(now);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                handler.removeCallbacks(longPressRunnable);
                handler.removeCallbacks(menuHoldRunnable);
                if (velocity != null) velocity.addMovement(e);
                if (dragging) {
                    endDrag(now, false);
                } else if (touchScrolling) {
                    if (velocity != null) {
                        velocity.computeCurrentVelocity(1000);
                        flingV = velocity.getYVelocity();
                        if (Math.abs(flingV) < dp(80)) flingV = 0;
                    }
                    kickScroll();
                } else {
                    click(x, y, now);
                }
                if (pressedCard != null) pressedCard.press.set(0f, now);
                pressedCard = null;
                touching = false;
                touchScrolling = false;
                updateFocus(now);
                invalidate();
                return true;
            }
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
        handler.removeCallbacks(menuHoldRunnable);
        if (dragging) endDrag(now, true);
        if (pressedCard != null) pressedCard.press.set(0f, now);
        pressedCard = null;
        if (touching && touchScrolling) kickScroll();
        touching = false;
        touchScrolling = false;
    }

    private void click(float x, float y, long now) {
        final int zone = zoneAt(x, y);
        if (zone != ZONE_NONE && zone == downZone) {
            if (zone == ZONE_BRAND) {
                // Jedno klepnuti = nastaveni (s kratkou prodlevou), 5x rychle = karusel.
                handler.removeCallbacks(brandTapRunnable);
                brandTaps++;
                brandTap.snap(1f);
                brandTap.set(0f, now);
                if (brandTaps >= BRAND_TAPS_CAROUSEL) {
                    brandTaps = 0;
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    if (host != null) host.onToggleCarousel();
                } else {
                    handler.postDelayed(brandTapRunnable, BRAND_TAP_MS);
                }
            } else if (zone >= ZONE_TAB0 && zone < ZONE_TAB0 + TAB_NAMES.length) {
                int t = zone - ZONE_TAB0;
                if (t != tab && host != null) host.onTabSelected(t);
            } else if (zone == ZONE_BATTERY) {
                if (host != null) host.onOpenQuickMenu(new RectF(statusRect));
            } else if (zone == ZONE_RAIL0 + RAIL_LIBRARY) {
                // Uz jsme v knihovne - klepnuti odroluje nahoru.
                flingV = 0;
                scrollTarget = 0f;
                kickScroll();
            } else if (zone == ZONE_RAIL0 + RAIL_SEARCH) {
                if (host != null) host.onOpenSearch(new RectF(railRects[RAIL_SEARCH]));
            } else if (zone == ZONE_RAIL0 + RAIL_CAROUSEL) {
                if (host != null) host.onToggleCarousel();
            } else if (zone == ZONE_RAIL0 + RAIL_QUICK) {
                if (host != null) host.onOpenQuickMenu(new RectF(railRects[RAIL_QUICK]));
            } else if (zone == ZONE_RAIL0 + RAIL_SETTINGS) {
                if (host != null) host.onOpenSettings();
            } else if (zone >= ZONE_RUN0 && zone < ZONE_RUN0 + runBtn.length) {
                if (host != null && runOn) host.onRunningAction(zone - ZONE_RUN0);
            }
            return;
        }
        Card k = pressedCard;
        if (k != null && k == focused) {
            final float animScale = ValueAnimator.getDurationScale();
            if (prefs.launchAnimation() && animScale > 0f && isHardwareAccelerated()) {
                startLaunch(k, now, animScale);
                return;
            }
            k.flash.snap(1f);
            k.flash.set(0f, now);
            final AppEntry app = k.app;
            // Kratka prodleva, aby byl videt zablesk karty.
            handler.postDelayed(() -> {
                if (host != null) host.onLaunch(app);
            }, 90);
        }
    }

    /**
     * "Kukatko" (viz PeepholeAnimation). Aplikace se spusti v polovine animace,
     * na konci se launcher zavre (pokud je to zapnute v nastaveni). Oboji ridi
     * casovace, ne vykreslovani - pri spusteni VR hry se okno prestane kreslit.
     */
    private void startLaunch(Card k, long now, float animScale) {
        launchCard = k;
        launchCloses = prefs.closeAfterLaunch();
        poppedRect(k, now, tmp);
        final Shader shader;
        if (k.art != null) {
            BitmapShader bs = new BitmapShader(k.art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(cardW / k.art.getWidth(), cardH / k.art.getHeight());
            m.postTranslate(-cardW / 2f, -cardH / 2f);
            bs.setLocalMatrix(m);
            shader = bs;
        } else {
            int[] pair = Placeholders.colorsFor(k.app.pkg);
            shader = new LinearGradient(-cardW / 2f, -cardH / 2f, cardW / 2f, cardH / 2f,
                    pair[0], pair[1], Shader.TileMode.CLAMP);
        }
        peephole.start(now, animScale, tmp.centerX(), tmp.centerY(), cardW, cardH,
                visualScale(k, now), dp(CARD_RADIUS), shader, k.label, launchCloses);
        final AppEntry app = k.app;
        final long dur = (long) (PeepholeAnimation.DURATION_MS * animScale);
        handler.postDelayed(() -> {
            if (host != null) host.onLaunch(app);
        }, (long) (dur * PeepholeAnimation.FIRE_AT));
        if (launchCloses) {
            handler.postDelayed(() -> {
                if (host != null) host.onLaunchSequenceDone();
            }, dur);
        }
        invalidate();
    }

    private void drawLaunch(Canvas c, long now) {
        if (launchCard == null) return;
        if (!peephole.isRunning(now)) {
            launchCard = null; // konec - odvozeno z casu
            return;
        }
        peephole.draw(c, now, getWidth(), getHeight());
    }


    // =========================================================================
    // Presouvani karet (dlouhe podrzeni)
    // =========================================================================

    private void onLongPress() {
        if (!touching || touchScrolling || pressedCard == null || pressedCard != focused) return;
        final long now = System.nanoTime();
        dragCard = pressedCard;
        pressedCard = null;
        dragging = true;
        dragMoved = false;
        dragOrderChanged = false;
        liftX = px;
        liftY = py;
        lastDragX = px;
        lastDragStepNs = now;
        final float cx = cardLeft(dragCard, now) + cardW / 2f;
        final float cy = scrolledToScreenY(cardTop(dragCard, now) + cardH / 2f);
        dragDX = px - cx;
        dragDY = py - cy;
        dragCx = cx;
        dragCy = cy;
        ghostX.snap(slotX(dragCard.index));
        ghostY.snap(slotY(dragCard.index));
        dragCard.press.snap(0f);
        dragLift.snap(0f);
        dragLift.set(1f, now);
        dragTilt.snap(0f);
        // Behem presouvani bez fokusu a bez rozmazani - at je videt, kam pustit.
        for (Card k : cards) {
            k.hover.set(0, now);
            k.rotX.set(0, now);
            k.rotY.set(0, now);
        }
        dragCard.hover.snap(0f);
        focused = null;
        blurAmount.set(0f, now);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        liftNs = now;
        handler.removeCallbacks(menuHoldRunnable);
        handler.postDelayed(menuHoldRunnable, Math.max(100, menuHoldMs() - LONG_PRESS_MS));
        kickScroll();
        invalidate();
    }

    private long menuHoldMs() {
        return prefs != null ? prefs.menuHoldMs() : 1000;
    }

    /** Karta drzena bez pohybu celou prodlevu -> menu karty. */
    private void onMenuHold() {
        if (!dragging || dragCard == null || dragMoved || dragOrderChanged) return;
        final long now = System.nanoTime();
        final Card k = dragCard;
        endDrag(now, true);
        if (host != null) {
            poppedRect(k, now, tmp);
            host.onAppMenu(k.app, new RectF(tmp));
        }
        invalidate();
    }

    private void dragTo(float x, float y, long now) {
        if (dragCard == null) return;
        // Stred tazene karty v obsahovych souradnicich mrizky.
        final float cxc = (x - dragDX) - gridLeft;
        final float cyc = screenToScrolledY(y - dragDY) - gridTop - scrollCur;
        final int rows = Math.max(1, (cards.size() + cols - 1) / cols);
        int col = Math.round((cxc - cardW / 2f) / (cardW + gap));
        int row = Math.round((cyc - cardH / 2f) / (cardH + gap));
        col = Math.max(0, Math.min(cols - 1, col));
        row = Math.max(0, Math.min(rows - 1, row));
        int slot = Math.max(0, Math.min(cards.size() - 1, row * cols + col));
        if (slot != dragCard.index) {
            cards.remove(dragCard);
            cards.add(slot, dragCard);
            for (int i = 0; i < cards.size(); i++) {
                Card k = cards.get(i);
                k.index = i;
                if (k != dragCard) {
                    k.x.set(slotX(i), now);
                    k.y.set(slotY(i), now);
                }
            }
            ghostX.set(slotX(slot), now);
            ghostY.set(slotY(slot), now);
            dragOrderChanged = true;
        }
        // Lehky naklon podle rychlosti tazeni do strany - pusobi fyzicky.
        float dt = Math.max(0.001f, (now - lastDragStepNs) / 1e9f);
        float vx = (x - lastDragX) / dt / d;
        lastDragX = x;
        lastDragStepNs = now;
        dragTilt.set(clamp(vx * 0.012f, -9f, 9f), now);
    }

    /** Automaticke rolovani, kdyz se tazena karta priblizi k hornimu/dolnimu okraji. */
    private boolean stepDragAutoScroll(float dt) {
        if (!dragging) return false;
        final float zone = dp(64);
        float speed = 0f;
        if (py < topBarBottom + zone) speed = (topBarBottom + zone - py) / zone;
        else if (py > frame.bottom - zone) speed = -(py - (frame.bottom - zone)) / zone;
        if (speed == 0f) return false;
        speed = clamp(speed, -1.2f, 1.2f) * dp(900);
        float next = clamp(scrollCur + speed * dt, minScroll, 0f);
        if (next == scrollCur) return false;
        scrollCur = scrollTarget = next;
        dragTo(px, py, System.nanoTime());
        return true;
    }

    private void endDrag(long now, boolean cancelled) {
        if (!dragging || dragCard == null) {
            dragging = false;
            return;
        }
        final Card k = dragCard;
        // Karta doleti z mista, kde byla pustena, na svuj slot.
        final float curLeft = (px - dragDX) - cardW / 2f - gridLeft;
        final float curTop = screenToScrolledY(py - dragDY) - cardH / 2f - gridTop - scrollCur;
        k.x.snap(curLeft);
        k.y.snap(curTop);
        k.x.set(slotX(k.index), now);
        k.y.set(slotY(k.index), now);
        dragging = false;
        dragCard = null;
        dragLift.set(0f, now);
        wiggle.set(0f, now);
        if (cancelled) return;
        // Pusteni bez pohybu pred uplynutim prodlevy menu = nic (karta jen dosedne).
        if (dragOrderChanged && host != null) {
            List<String> order = new ArrayList<>(cards.size());
            for (Card c : cards) order.add(c.app.pkg);
            host.onOrderChanged(tab, order);
        }
    }

    // =========================================================================
    // Fyzika rolovani
    // =========================================================================

    private void kickScroll() {
        if (!scrollActive) {
            scrollActive = true;
            lastStepNs = System.nanoTime() - 16_000_000L;
        }
        invalidate();
    }

    /** @return true, pokud se jeste bude hybat (potreba dalsi snimek). */
    private boolean stepScroll(long now) {
        if (!scrollActive) return false;
        final float dt = clamp((now - lastStepNs) / 1e9f, 0f, 0.05f);
        lastStepNs = now;
        final float frames = Math.max(dt * 60f, 0.0001f);
        final float lerp = 1f - (float) Math.pow(1f - SCROLL_LERP, frames);
        final float prev = scrollCur;

        stepDragAutoScroll(dt);

        if (touching && touchScrolling) {
            // Primo za prstem/laserem (resi onTouchEvent).
        } else if (flingV != 0f) {
            scrollCur += flingV * dt;
            final boolean out = scrollCur > 0f || scrollCur < minScroll;
            flingV *= (float) Math.pow(out ? 0.55f : 0.955f, frames);
            if (Math.abs(flingV) < dp(30)) flingV = 0f;
            scrollTarget = scrollCur;
        } else if (!dragging) {
            // Gumovy navrat, kdyz je obsah pretazeny za okraj.
            if (scrollTarget > 0f) scrollTarget += (0f - scrollTarget) * lerp;
            else if (scrollTarget < minScroll) scrollTarget += (minScroll - scrollTarget) * lerp;
            scrollCur += (scrollTarget - scrollCur) * lerp;
        }

        final float v60 = (scrollCur - prev) / frames / d; // dp za 1/60 s
        squish = 1f - Math.min(SQUISH_MAX, Math.abs(v60) * SQUISH_PER_DP);
        // Obsah jede nahoru -> stlaci se k hornimu okraji, jinak ke spodnimu.
        squishPivot = v60 < 0 ? topBarBottom : frame.bottom;

        final boolean settled = !(touching && touchScrolling) && !dragging && flingV == 0f
                && Math.abs(scrollTarget - scrollCur) < 0.08f * d && Math.abs(v60) < 0.08f
                && scrollTarget <= 0f && scrollTarget >= minScroll;
        if (settled) {
            scrollCur = scrollTarget = clamp(scrollCur, minScroll, 0f);
            squish = 1f;
            scrollActive = false;
            return false;
        }
        return true;
    }

    // =========================================================================
    // Kresleni
    // =========================================================================

    @Override
    protected void onDraw(Canvas canvas) {
        if (prefs == null || frame.isEmpty()) return;
        final long now = System.nanoTime();
        if (introArmed) {
            // Prvni skutecny snimek po otevreni -> ted (s kratkou pauzou) nastup karet.
            introArmed = false;
            introNs = now + INTRO_DELAY_MS * 1_000_000L;
        }
        final boolean again = stepScroll(now);
        // Behem rolovani jede karta pod stojicim ukazatelem - fokus prepocitat kazdy snimek.
        if (again) updateFocus(now);

        layoutChrome(now);
        final boolean hw = canvas.isHardwareAccelerated();
        // Pod ornamentem jen jeho sklo: panel a karty tam kresli rozmazane backdrop (s lomem).
        if (hw) {
            updateOrnHole(now);
            canvas.save();
            canvas.clipOutPath(ornHole);
        }
        drawGlass(canvas, now);
        if (hw) canvas.restore();
        drawGameLight(canvas, now);
        if (hw) {
            canvas.save();
            canvas.clipOutPath(ornHole);
            drawContentLayer(canvas, now);
            canvas.restore();
            drawOrnamentBackdrop(canvas, now);
        } else {
            canvas.save();
            canvas.clipRect(frame.left, topBarBottom, frame.right, frame.bottom);
            drawCardsInto(canvas, now, frame.top, frame.bottom, 0f, false);
            canvas.restore();
        }
        drawEmptyState(canvas);
        drawRail(canvas, now);
        drawOrnament(canvas, now);
        drawRunBar(canvas, now);
        drawFocusedCard(canvas, now);
        drawDraggedCard(canvas, now);
        drawLaunch(canvas, now);

        if (again || dragging || launchCard != null || isAnimating(now)) postInvalidateOnAnimation();
    }

    private boolean isAnimating(long now) {
        if (blurAmount.active(now) || tabPos.active(now) || contentFade.active(now) || introActive(now)
                || rimX.active(now) || rimY.active(now) || rimAmt.active(now)
                || liveAmt.active(now) || liveProg.active(now) || liveWidth.active(now)
                || (liveProgress == LIVE_SPINNER && liveOn(now))
                || brandHover.active(now) || brandTap.active(now) || batteryHover.active(now)
                || dragLift.active(now) || railExpand.active(now) || wiggle.active(now)
                || ghostX.active(now) || ghostY.active(now) || runAmt.active(now)
                || (isLowBattery() && now - lowBatterySinceNs < LOW_BATTERY_PULSE_NS)
                || dragTilt.active(now)) return true;
        for (Eased e : tabHover) if (e.active(now)) return true;
        for (Eased e : railHover) if (e.active(now)) return true;
        for (Eased e : runHover) if (e.active(now)) return true;
        for (Card k : cards) {
            if (k.hover.active(now) || k.rotX.active(now) || k.rotY.active(now)
                    || k.press.active(now) || k.x.active(now) || k.y.active(now)
                    || k.flash.active(now)) return true;
        }
        return false;
    }

    private void drawGlass(Canvas c, long now) {
        final float r = dp(FRAME_RADIUS);
        final float alpha = prefs.glassAlpha() / 100f;
        if (prefs.popoutMargin()) {
            // Jemna kobaltova zare okolo skla (jen v pruhlednem okraji - uvnitr oriznuta).
            // Stin tvaru pocita GPU analyticky, zadna bitmapa ani vrstva navic.
            c.save();
            c.clipOutPath(framePath);
            fill.setShader(null);
            fill.setColor(Palette.VOID);
            fill.setShadowLayer(dp(22), 0, dp(4), Palette.alpha(Palette.COBALT, 0.26f));
            c.drawRoundRect(frame, r, r, fill);
            fill.clearShadowLayer();
            c.restore();
        }
        // Sklo panelu stejnym receptem jako dialogy (GlassSurface), kryti z nastaveni.
        final boolean light = prefs.glassStyle() == Prefs.GLASS_VISION;
        if (panelStyle == null || panelStyleAlpha != alpha || panelStyleLight != light) {
            panelStyle = light
                    ? new GlassSurface.Style(Palette.alpha(0xFF8A93A0, alpha), Palette.alpha(0xFF646C79, alpha),
                    0x24, 0xA6FFFFFF, 0x24FFFFFF, 0x4DFFFFFF, 1.5f)
                    : new GlassSurface.Style(Palette.alpha(0xFF141821, alpha), Palette.alpha(0xFF08090D, alpha),
                    0x14, 0x80FFFFFF, 0x17FFFFFF, 0x33FFFFFF, 1.5f);
            panelStyleAlpha = alpha;
            panelStyleLight = light;
        }
        panelGlass.draw(c, frame, r, panelStyle, getResources().getDisplayMetrics().density);
        drawRimLight(c, now, frame, r, true);
    }

    /**
     * Svetlo laseru na hrane skla: kde je laser blizko okraje, hrana se rozsviti
     * (jako kdyz sklo chyta svetlo). Jeden radialni prechod posouvany matici,
     * u panelu navic mekka zare na vnitrni strane hrany.
     */
    private void drawRimLight(Canvas c, long now, RectF r, float radius, boolean innerGlow) {
        final float a = clamp(rimAmt.get(now), 0f, 1f);
        if (a < 0.01f) return;
        if (rimShader == null) {
            rimShader = new RadialGradient(0, 0, 1f, new int[]{0xFFFFFFFF, 0x59FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP);
        }
        final float rad = dp(RIM_RADIUS);
        rimMatrix.setScale(rad, rad);
        rimMatrix.postTranslate(rimX.get(now), rimY.get(now));
        rimShader.setLocalMatrix(rimMatrix);
        stroke.setShader(rimShader);
        stroke.setColor(Color.WHITE);
        if (innerGlow) {
            chromePath.reset();
            chromePath.addRoundRect(r, radius, radius, Path.Direction.CW);
            c.save();
            c.clipPath(chromePath);
            stroke.setAlpha(Math.round(56 * a));
            stroke.setStrokeWidth(dp(12));
            c.drawRoundRect(r, radius, radius, stroke);
            c.restore();
        }
        final float w = dp(RIM_WIDTH);
        stroke.setAlpha(Math.round((innerGlow ? 215 : 170) * a));
        stroke.setStrokeWidth(w);
        c.drawRoundRect(r.left + w / 2f, r.top + w / 2f, r.right - w / 2f, r.bottom - w / 2f,
                radius - w / 2f, radius - w / 2f, stroke);
        stroke.setShader(null);
        stroke.setAlpha(255);
    }

    /**
     * Barevne svetlo hry na skle: kolem karty pod laserem se sklo panelu jemne
     * rozsviti barvou jejiho obrazku (pricita se - jako svetlo, ne jako barva).
     * Jeden radialni prechod pod kartami, shader se jen posouva matici.
     */
    private void drawGameLight(Canvas c, long now) {
        final Card k = focused;
        if (k == null || k == launchCard || artwork == null) return;
        final float h = clamp(k.hover.get(now), 0f, 1f);
        if (h <= 0.01f) return;
        final int color = artwork.glowColor(k.app.pkg, 0xFFBFE6FF);
        if (lightShader == null || lightColor != color) {
            lightColor = color;
            lightShader = new RadialGradient(0, 0, 1f, new int[]{(color & 0x00FFFFFF) | 0x66000000,
                    (color & 0x00FFFFFF) | 0x1F000000, color & 0x00FFFFFF}, new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP);
        }
        final float cx = cardLeft(k, now) + cardW / 2f;
        final float cy = scrolledToScreenY(cardTop(k, now) + cardH / 2f);
        final float rad = cardW * 1.5f;
        lightMatrix.setScale(rad, rad * 0.8f);
        lightMatrix.postTranslate(cx, cy);
        lightShader.setLocalMatrix(lightMatrix);
        c.save();
        c.clipPath(framePath);
        fill.setShader(lightShader);
        fill.setColor(Color.WHITE);
        fill.setAlpha(Math.round(255 * h));
        fill.setBlendMode(BlendMode.PLUS);
        c.drawRect(cx - rad, cy - rad, cx + rad, cy + rad, fill);
        fill.setBlendMode(null);
        fill.setShader(null);
        fill.setAlpha(255);
        c.restore();
    }

    /** Karty pod listou, ve vlastni vrstve s jedinym efektem hloubky ostrosti. */
    private void drawContentLayer(Canvas canvas, long now) {
        final int w = Math.round(frame.width());
        final int h = Math.round(frame.bottom) - Math.round(topBarBottom);
        if (w <= 0 || h <= 0) return;
        RecordingCanvas rc = contentNode.beginRecording(w, h);
        try {
            rc.translate(-Math.round(frame.left), -Math.round(topBarBottom));
            drawCardsInto(rc, now, topBarBottom, frame.bottom, dimStrength(), true);
        } finally {
            contentNode.endRecording();
        }

        final float amt = blurAmount.get(now);
        final int dof = prefs.dofMode();
        if (dof != Prefs.DOF_OFF && amt > 0.01f && !dragging) {
            final float k = (dof == Prefs.DOF_STRONG ? 2f : 1f) * amt;
            contentNode.setRenderEffect(depthBlur.effect(
                    blurCx - Math.round(frame.left), blurCy - Math.round(topBarBottom),
                    cardW * 0.55f, dp(850), dp(2.2f) * k, dp(4.4f) * k));
        } else {
            contentNode.setRenderEffect(null);
        }
        contentNode.setAlpha(contentFade.get(now) * introAlpha(now));
        canvas.drawRenderNode(contentNode);
    }

    private void updateOrnHole(long now) {
        final float r = ornRect.height() / 2f;
        ornHole.reset();
        ornHole.addRoundRect(ornRect, r, r, Path.Direction.CW);
        // Usazena lista bezici aplikace ma pod sebou taky jen sve (rozmazane) sklo.
        if (runOn && runAmt.get(now) >= 0.99f && !runRect.isEmpty()) {
            final float rr = runRect.height() / 2f;
            ornHole.addRoundRect(runRect, rr, rr, Path.Direction.CW);
        }
    }

    /**
     * Matne sklo ornamentu: rozmazana kopie panelu a karet pod nim (spodni pulka
     * je nad panelem), u okraje navic lom svetla (LiquidGlass).
     */
    private void drawOrnamentBackdrop(Canvas canvas, long now) {
        final float bottom = Math.min(ornRect.bottom, frame.bottom);
        if (bottom <= frame.top) return;
        final int l = Math.round(ornRect.left), t = Math.round(ornRect.top);
        final int w = Math.round(ornRect.width()), h = Math.round(ornRect.height());
        backdropNode.setPosition(l, t, l + w, t + h);
        Outline ob = new Outline();
        ob.setRoundRect(0, 0, w, h, h / 2f);
        backdropNode.setOutline(ob);
        backdropNode.setClipToOutline(true);
        RecordingCanvas rc = backdropNode.beginRecording(w, h);
        try {
            rc.translate(-l, -t);
            // Jen cast uvnitr panelu (nad panelem je prostredi). Karty naplocho -
            // RenderNode nakloneni karty nesmi byt ve dvou rodicich naraz.
            rc.clipRect(frame.left, frame.top, frame.right, frame.bottom);
            if (panelStyle != null) {
                panelGlass.draw(rc, frame, dp(FRAME_RADIUS), panelStyle, getResources().getDisplayMetrics().density);
            }
            drawCardsInto(rc, now, frame.top, bottom, 0f, false);
        } finally {
            backdropNode.endRecording();
        }
        backdropNode.setRenderEffect(liquidGlass.effect(backdropEffect, w, h, dp(LIQUID_BAND), dp(LIQUID_STRENGTH)));
        canvas.drawRenderNode(backdropNode);
    }

    private float dimStrength() {
        return prefs.dofMode() == Prefs.DOF_OFF ? 0.38f : 0.10f;
    }

    /**
     * Nakresli vsechny karty krome hovernute a tazene, jen ty, ktere zasahuji
     * do svisleho rozsahu [top, bottom] obrazovky. Karty, ktere jeste
     * dojizdeji z hoveru, se kresli az nakonec (jsou "nad" ostatnimi).
     */
    private void drawCardsInto(Canvas c, long now, float top, float bottom, float dim,
                               boolean allow3d) {
        c.save();
        c.translate(0, squishPivot);
        c.scale(1f, squish);
        c.translate(0, -squishPivot);
        drawDropSlot(c, now);
        final float amt = blurAmount.get(now);
        final float maxDist = dp(850);
        List<Card> late = null;
        for (Card k : cards) {
            if (k == focused || k == launchCard || (dragging && k == dragCard)) continue;
            final float t = cardTop(k, now);
            final float st = scrolledToScreenY(t - cardH * 0.3f);
            final float sb = scrolledToScreenY(t + cardH * 1.3f);
            if (sb < top || st > bottom) continue;
            if (Math.abs(k.hover.get(now)) > 0.001f || k.hover.active(now)
                    || k.x.active(now) || k.y.active(now)) {
                if (late == null) late = new ArrayList<>();
                late.add(k);
                continue;
            }
            drawCard(c, k, now, dimFor(k, now, amt, dim, maxDist), allow3d);
        }
        if (late != null) {
            late.sort((a, b) -> Float.compare(a.hover.get(now), b.hover.get(now)));
            for (Card k : late) drawCard(c, k, now, dimFor(k, now, amt, dim, maxDist), allow3d);
        }
        c.restore();
    }

    private float dimFor(Card k, long now, float amt, float dim, float maxDist) {
        if (dim <= 0f || amt <= 0f) return 0f;
        float cx = cardLeft(k, now) + cardW / 2f;
        float cy = scrolledToScreenY(cardTop(k, now) + cardH / 2f);
        float dist = (float) Math.hypot(cx - blurCx, cy - blurCy);
        float t = clamp(dist / maxDist, 0f, 1f);
        return amt * dim * (0.55f + 0.45f * t);
    }

    private void drawFocusedCard(Canvas c, long now) {
        if (focused == null || focused == launchCard) return;
        c.save();
        c.translate(0, squishPivot);
        c.scale(1f, squish);
        c.translate(0, -squishPivot);
        drawCard(c, focused, now, 0f, true);
        c.restore();
    }

    private void drawDraggedCard(Canvas c, long now) {
        if (!dragging || dragCard == null) return;
        final float lift = dragLift.get(now);
        final float cx = px - dragDX;
        final float cy = py - dragDY;
        dragCx = cx;
        dragCy = cy;
        final float scale = 1f + 0.12f * lift;
        drawCardAt(c, dragCard, now, cx, cy, scale, 0f,
                dragTilt.get(now), clamp(lift, 0.6f, 1f), 0f, true);
        if (!dragMoved) {
            // Tenky ukazatel pod kartou: az se naplni, otevre se menu karty.
            final float total = Math.max(1f, menuHoldMs() - LONG_PRESS_MS);
            final float p = clamp((now - liftNs) / 1e6f / total, 0f, 1f);
            final float w = cardW * 0.42f * scale;
            final float top = cy + cardH * scale / 2f + dp(10);
            final float bh = dp(4);
            fill.setShader(null);
            fill.setColor(0x40FFFFFF);
            c.drawRoundRect(cx - w / 2f, top, cx + w / 2f, top + bh, bh / 2f, bh / 2f, fill);
            fill.setColor(Palette.COBALT_LIGHT);
            c.drawRoundRect(cx - w / 2f, top, cx - w / 2f + w * p, top + bh, bh / 2f, bh / 2f, fill);
        }
    }

    private void drawCard(Canvas c, Card k, long now, float dim, boolean allow3d) {
        float cx = cardLeft(k, now) + cardW / 2f;
        float cy = cardTop(k, now) + cardH / 2f;
        float scale = visualScale(k, now);
        if (introActive(now) && frame.width() > 0 && frame.height() > 0) {
            // Nastup: karta jde z lehce vzdalenejsi a vetsi pozice na sve misto,
            // cim dal od stredu panelu, tim o chlup pozdeji (ease-out, zadny odskok).
            final float gx = frame.centerX(), gy = frame.centerY();
            final float dx = cx - gx, dy = cy - gy;
            final float dn = clamp((float) Math.hypot(dx, dy)
                    / (float) Math.hypot(frame.width() / 2f, frame.height() / 2f), 0f, 1f);
            final float ms = (now - introNs) / 1e6f / introScale - dn * INTRO_STAGGER_MS; // < 0 = jeste ceka
            final float p = clamp(ms / INTRO_MS, 0f, 1f);
            // Kubicky ease-out: pohyb je videt celou dobu (kvarticky skoncil moc brzy).
            final float e = 1f - (float) Math.pow(1f - p, 3);
            final float spread = 1f + INTRO_SPREAD * (1f - e);
            cx = gx + dx * spread;
            cy = gy + dy * spread;
            scale *= 1f + INTRO_ZOOM * (1f - e);
        }
        // Pruzina muze lehce prekmitnout - na meritko ano, na pruhlednosti ne.
        final float h = clamp(k.hover.get(now), 0f, 1f);
        final float wg = clamp(wiggle.get(now), 0f, 1f);
        if (wg > 0.001f && k != dragCard) {
            // Presouvani: karta se trese (kazda v jine fazi), zmensi a uhne tazene karte.
            final float phase = (k.app.pkg.hashCode() & 1023) / 1023f * 6.2832f;
            final float rz = (float) Math.sin(now / 1e9 * 6.2832 * WIGGLE_HZ + phase) * WIGGLE_DEG * wg;
            scale *= 1f - WIGGLE_SHRINK * wg;
            final float vx = cx - dragCx, vy = cy - screenToScrolledY(dragCy);
            final float dist = (float) Math.hypot(vx, vy);
            if (dist > 1f) {
                final float f = dist / (cardW * WIGGLE_REACH);
                final float push = dp(WIGGLE_PUSH) * wg * (float) Math.exp(-f * f);
                cx += vx / dist * push;
                cy += vy / dist * push;
            }
            c.save();
            c.rotate(rz, cx, cy);
            drawCardAt(c, k, now, cx, cy, scale, k.rotX.get(now), k.rotY.get(now), h, dim, allow3d);
            c.restore();
            return;
        }
        drawCardAt(c, k, now, cx, cy, scale, k.rotX.get(now), k.rotY.get(now),
                h, dim, allow3d);
    }

    /** Sklenena "jamka" na miste, kam tazena karta dopadne. */
    private void drawDropSlot(Canvas c, long now) {
        if (!dragging || dragCard == null) return;
        final float a = clamp(dragLift.get(now), 0f, 1f);
        if (a < 0.01f) return;
        final float l = gridLeft + ghostX.get(now);
        final float t = gridTop + ghostY.get(now) + scrollCur;
        final float r = dp(CARD_RADIUS);
        final float in = dp(3);
        fill.setShader(null);
        fill.setColor(Color.argb(Math.round(20 * a), 255, 255, 255));
        c.drawRoundRect(l + in, t + in, l + cardW - in, t + cardH - in, r - in, r - in, fill);
        stroke.setStrokeWidth(dp(1.5f));
        stroke.setColor(Color.argb(Math.round(90 * a), 255, 255, 255));
        c.drawRoundRect(l + in, t + in, l + cardW - in, t + cardH - in, r - in, r - in, stroke);
    }

    /**
     * Jedna karta se stredem v (cx, cy). Naklonena karta (rx/ry) jde pres vlastni
     * RenderNode s rotaci - stejna cesta, jakou Android pouziva pro View.setRotationX/Y.
     * (Vlastni perspektivni matice pres android.graphics.Camera se v nekterych
     * stavech vubec nevykreslila - odhaleno v Robolectric snimku.)
     */
    private void drawCardAt(Canvas c, Card k, long now, float cx, float cy, float scale,
                            float rx, float ry, float h, float dim, boolean allow3d) {
        if (allow3d && c.isHardwareAccelerated() && (Math.abs(rx) > 0.01f || Math.abs(ry) > 0.01f)) {
            drawCardNode(c, k, now, cx, cy, scale, rx, ry, h, dim);
            return;
        }
        c.save();
        c.translate(cx, cy);
        if (scale != 1f) c.scale(scale, scale);
        drawCardBody(c, k, now, h, dim);
        c.restore();
    }

    private void drawCardNode(Canvas c, Card k, long now, float cx, float cy, float scale,
                              float rx, float ry, float h, float dim) {
        if (k.node == null) k.node = new RenderNode("neo-card");
        // Rezerva kolem karty, aby se nezarizl velky stin pod kartou.
        final float m = (hoverShadow != null ? hoverShadow.margin : dp(90)) + dp(30);
        final int nw = (int) Math.ceil(cardW + 2 * m);
        final int nh = (int) Math.ceil(cardH + 2 * m);
        final RenderNode n = k.node;
        RecordingCanvas rc = n.beginRecording(nw, nh);
        try {
            rc.translate(nw / 2f, nh / 2f);
            drawCardBody(rc, k, now, h, dim);
        } finally {
            n.endRecording();
        }
        n.setPosition(0, 0, nw, nh);
        n.setClipToBounds(false);
        n.setTranslationX(cx - nw / 2f);
        n.setTranslationY(cy - nh / 2f);
        n.setPivotX(nw / 2f);
        n.setPivotY(nh / 2f);
        n.setScaleX(scale);
        n.setScaleY(scale);
        n.setRotationX(rx);
        n.setRotationY(ry);
        n.setCameraDistance(dp(PERSPECTIVE) / 72f);
        c.drawRenderNode(n);
    }

    /** Obsah karty v lokalnich souradnicich se stredem v (0, 0). */
    private void drawCardBody(Canvas c, Card k, long now, float h, float dim) {
        final float w2 = cardW / 2f, h2 = cardH / 2f;
        final float r = dp(CARD_RADIUS);

        // Stiny: klidovy 0 6px 20px rgba(0,0,0,.4) -> hover 0 26px 55px rgba(0,0,0,.85)
        // + bila zare 0 0 32px rgba(255,255,255,.3).
        if (restShadow != null) {
            if (h < 0.999f) {
                spritePaint.setColor(Color.argb(Math.round(102 * (1f - h)), 0, 0, 0));
                restShadow.draw(c, -w2, -h2, w2, h2, dp(6), spritePaint);
                // Plochy (ostry) stin pod kartou - "flat shadow" styl.
                fill.setShader(null);
                fill.setColor(Color.argb(Math.round(64 * (1f - h)), 0, 0, 0));
                c.drawRoundRect(-w2 + dp(2), -h2 + dp(5), w2 - dp(2), h2 + dp(5), r, r, fill);
            }
            if (h > 0.001f) {
                // Tmavy stin slabsi nez v preview (.85 -> .6), jinak by barevnou zari prebil.
                spritePaint.setColor(Color.argb(Math.round(153 * h), 0, 0, 0));
                hoverShadow.draw(c, -w2, -h2, w2, h2, dp(26) * h, spritePaint);
                // Zare v barve obrazku hry (ambientni svetlo jako u PS5) + jemna bila.
                // Siroka (sprite velkeho stinu), aby presahla tmavy stin i okoli karty.
                final int glow = artwork != null ? artwork.glowColor(k.app.pkg, 0xFFBFE6FF) : 0xFFBFE6FF;
                // Aditivne (PLUS) - svetlo se pricte k okoli, misto aby ho zasedilo.
                spritePaint.setColor((glow & 0x00FFFFFF) | (Math.round(235 * h) << 24));
                spritePaint.setBlendMode(BlendMode.PLUS);
                hoverShadow.draw(c, -w2, -h2, w2, h2, dp(5) * h, spritePaint);
                spritePaint.setBlendMode(null);
                spritePaint.setColor(Color.argb(Math.round(46 * h), 255, 255, 255));
                glowSprite.draw(c, -w2, -h2, w2, h2, 0f, spritePaint);
            }
        }

        // Obrazek (cover) oriznuty na zaoblene rohy pres BitmapShader - bez clipPath.
        Bitmap art = artwork != null ? artwork.get(k.app) : null;
        if (art != k.art) {
            k.art = art;
            k.artShader = art != null
                    ? new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) : null;
            k.artShaderW = -1;
        }
        if (k.artShader != null) {
            if (k.artShaderW != cardW) {
                shaderMatrix.setScale(cardW / k.art.getWidth(), cardH / k.art.getHeight());
                shaderMatrix.postTranslate(-w2, -h2);
                k.artShader.setLocalMatrix(shaderMatrix);
                k.artShaderW = cardW;
            }
            artPaint.setShader(k.artShader);
        } else {
            if (k.placeholder == null || k.placeholderW != cardW) {
                int[] pair = Placeholders.colorsFor(k.app.pkg);
                k.placeholder = new LinearGradient(-w2, -h2, w2, h2, pair[0], pair[1],
                        Shader.TileMode.CLAMP);
                k.placeholderW = cardW;
            }
            artPaint.setShader(k.placeholder);
        }
        c.drawRoundRect(-w2, -h2, w2, h2, r, r, artPaint);
        artPaint.setShader(null);

        // Tmavy prechod dole (::after v preview), pod nazvem.
        shadePaint.setShader(bottomShade);
        c.drawRoundRect(-w2, -h2, w2, h2, r, r, shadePaint);
        shadePaint.setShader(null);

        if (dim > 0.004f) {
            fill.setShader(null);
            fill.setColor(Color.argb(Math.round(255 * dim), 4, 6, 10));
            c.drawRoundRect(-w2, -h2, w2, h2, r, r, fill);
        }
        final float flash = k.flash.get(now);
        if (flash > 0.004f) {
            fill.setShader(null);
            fill.setColor(Color.argb(Math.round(110 * flash), 255, 255, 255));
            c.drawRoundRect(-w2, -h2, w2, h2, r, r, fill);
        }

        // Ramecek 1px: rgba(255,255,255,.12) -> .85 pri hoveru.
        final float bw = dp(1f);
        stroke.setShader(null);
        stroke.setStrokeWidth(bw);
        stroke.setColor(Color.argb(Math.round(255 * lerp(0.12f, 0.85f, h)), 255, 255, 255));
        c.drawRoundRect(-w2 + bw / 2f, -h2 + bw / 2f, w2 - bw / 2f, h2 - bw / 2f,
                r - bw / 2f, r - bw / 2f, stroke);

        drawBadges(c, k, w2, h2);
        drawTitlePill(c, k, h, w2, h2);
    }

    /** Stitek NOVE vlevo nahore a hvezdicka oblibenych vpravo nahore. */
    private void drawBadges(Canvas c, Card k, float w2, float h2) {
        if (k.fresh) {
            final String t = "NOVÉ";
            final float bw = badgeText.measureText(t) + dp(16), bh = dp(20);
            final float l = -w2 + dp(10), tp = -h2 + dp(10);
            fill.setShader(null);
            fill.setColor(Palette.COBALT);
            fill.setShadowLayer(dp(8), 0, 0, Palette.alpha(Palette.COBALT, 0.7f));
            c.drawRoundRect(l, tp, l + bw, tp + bh, bh / 2f, bh / 2f, fill);
            fill.clearShadowLayer();
            badgeText.setColor(Palette.PEARL);
            final Paint.FontMetrics fm = badgeText.getFontMetrics();
            c.drawText(t, l + bw / 2f, tp + bh / 2f - (fm.ascent + fm.descent) / 2f, badgeText);
        }
        if (k.favorite) {
            final float r = dp(13);
            final float cx = w2 - dp(10) - r, cy = -h2 + dp(10) + r;
            fill.setShader(null);
            fill.setColor(0xB3000000);
            c.drawCircle(cx, cy, r, fill);
            stroke.setShader(null);
            stroke.setStrokeWidth(dp(1));
            stroke.setColor(0x4DFFFFFF);
            c.drawCircle(cx, cy, r - dp(0.5f), stroke);
            Icons.fill(c, Icons.STAR, cx, cy, dp(13), Palette.AMBER, fill);
        }
    }

    /** Plovouci "pilulka" s nazvem: rgba(10,14,22,.9), radius 999, 11.5px, ellipsis. */
    private void drawTitlePill(Canvas c, Card k, float h, float w2, float h2) {
        drawTitlePill(c, k, h, w2, h2, 1f);
    }

    private void drawTitlePill(Canvas c, Card k, float h, float w2, float h2, float alpha) {
        final float padH = dp(12), padV = dp(3);
        final float maxPillW = cardW * 0.86f;
        if (k.shownLabelFor != cardW) {
            final float maxText = Math.max(dp(10), maxPillW - 2 * padH);
            k.shownLabel = TextUtils.ellipsize(k.label, pillText, maxText,
                    TextUtils.TruncateAt.END).toString();
            k.shownLabelWidth = pillText.measureText(k.shownLabel);
            k.shownLabelFor = cardW;
        }
        final Paint.FontMetrics fm = pillText.getFontMetrics();
        final float textH = fm.descent - fm.ascent;
        final float pillH = textH + 2 * padV;
        final float pillW = Math.min(maxPillW, k.shownLabelWidth + 2 * padH);
        final float bottom = h2 - dp(8);
        final float top = bottom - pillH;
        final float left = -pillW / 2f, right = pillW / 2f;
        final float rr = pillH / 2f;

        fill.setShader(null);
        // Plochy stin pod pilulkou.
        fill.setColor(Color.argb(Math.round(90 * alpha), 0, 0, 0));
        c.drawRoundRect(left, top + dp(2), right, bottom + dp(2), rr, rr, fill);
        fill.setColor(Color.argb(Math.round(255 * lerp(0.90f, 0.96f, h) * alpha),
                Math.round(lerp(10, 6, h)), Math.round(lerp(14, 9, h)), Math.round(lerp(22, 15, h))));
        c.drawRoundRect(left, top, right, bottom, rr, rr, fill);
        final float bw = dp(1f);
        stroke.setStrokeWidth(bw);
        stroke.setColor(Color.argb(Math.round(255 * lerp(0.28f, 0.65f, h) * alpha), 255, 255, 255));
        c.drawRoundRect(left + bw / 2f, top + bw / 2f, right - bw / 2f, bottom - bw / 2f,
                rr, rr, stroke);
        final float baseline = top + padV - fm.ascent;
        pillText.setAlpha(Math.round(255 * alpha));
        c.drawText(k.shownLabel, -k.shownLabelWidth / 2f, baseline, pillText);
        pillText.setAlpha(255);
    }

    // --- Ornament (horni lista) a leva lista ---------------------------------------

    /** Rozmery ornamentu a leve listy (zavisi na sirce textu a rozbaleni listy). */
    private void layoutChrome(long now) {
        final long minute = System.currentTimeMillis() / 60000L;
        if (minute != clockMinute) {
            clockMinute = minute;
            Calendar cal = Calendar.getInstance();
            clockTime = String.format(Locale.ROOT, "%02d:%02d",
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
        }
        // Ornament: [zalozky] | cas [baterie] ikona, stred na horni hrane panelu.
        // Soumerne: text prvni zalozky i ikona na konci maji od okraje ORN_INSET + ORN_PAD_X,
        // oddelovac ma z obou stran ORN_DIVIDER, polozky stavu mezi sebou ORN_STATUS_GAP.
        // Datum je az v rychlem menu (tady by jen zabiralo misto).
        final float midY = frame.top;
        final float padX = dp(ORN_PAD_X), itemH = dp(ORN_ITEM_H), inner = dp(ORN_INSET);
        float tabsW = 0;
        final float[] widths = new float[TAB_NAMES.length];
        for (int i = 0; i < TAB_NAMES.length; i++) {
            widths[i] = tabText.measureText(TAB_NAMES[i]) + 2 * padX;
            tabsW += widths[i];
        }
        // Na konci ikonka posuvniku = napoveda, ze klepnuti otevre rychle menu.
        float statusW = padX + clockText.measureText(clockTime) + dp(ORN_STATUS_GAP)
                + batteryWidth() + dp(ORN_STATUS_GAP) + dp(ORN_ICON) + padX;
        // Ziva bublina: stav se na pruzine roztahne na sirku ikony a textu.
        liveAmt.set(liveOn(now) ? 1f : 0f, now);
        final float la = Math.max(0f, liveAmt.get(now));
        if (liveText != null && la > 0.0005f) {
            final float liveW = padX + dp(LIVE_RING) + dp(LIVE_GAP) + liveWidth.get(now) + padX;
            statusW = Math.max(dp(40), lerp(statusW, liveW, la));
        }
        final float w = inner + tabsW + 2 * dp(ORN_DIVIDER) + statusW + inner;
        ornRect.set(frame.centerX() - w / 2f, midY - dp(ORN_H) / 2f, frame.centerX() + w / 2f, midY + dp(ORN_H) / 2f);
        float x = ornRect.left + inner;
        for (int i = 0; i < TAB_NAMES.length; i++) {
            tabRects[i].set(x, midY - itemH / 2f, x + widths[i], midY + itemH / 2f);
            x += widths[i];
        }
        tabsRect.set(tabRects[0].left, tabRects[0].top, x, tabRects[0].bottom);
        ornDividerX = x + dp(ORN_DIVIDER);
        statusRect.set(ornDividerX + dp(ORN_DIVIDER), midY - itemH / 2f, ornRect.right - inner, midY + itemH / 2f);

        // Leva lista: logo, oddelovac, 4 polozky. Rozbaleni = sirsi (popisky).
        final float e = Math.max(0f, railExpand.get(now));
        final float it = dp(RAIL_ITEM), gapY = dp(6), pad = dp(6);
        final float h = pad + it + dp(12) + RAIL_LABELS.length * it + (RAIL_LABELS.length - 1) * gapY + pad;
        final float left = dp(RAIL_LEFT);
        final float rw = dp(RAIL_W) + dp(RAIL_EXPAND) * e;
        final float top = frame.centerY() - h / 2f;
        railRect.set(left, top, left + rw, top + h);
        float y = top + pad;
        brandRect.set(left + pad, y, railRect.right - pad, y + it);
        y += it + dp(12);
        for (int i = 0; i < RAIL_LABELS.length; i++) {
            railRects[i].set(left + pad, y, railRect.right - pad, y + it);
            y += it + gapY;
        }
        layoutRunBar();
    }

    /** Lista bezici aplikace: [nahled] nazev | Pokracovat | Ukoncit | x, na stredu dole v panelu. */
    private void layoutRunBar() {
        if (runLabel == null) return;
        if (runShownTitle == null) {
            runShownTitle = TextUtils.ellipsize(runLabel, runTitle, dp(RUN_TITLE_MAX),
                    TextUtils.TruncateAt.END).toString();
        }
        final float h = dp(RUN_H), in = dp(RUN_INSET), bh = h - 2 * in;
        final float thumbW = bh * ArtworkLoader.CARD_ASPECT;
        final float textW = Math.max(runTitle.measureText(runShownTitle), runSub.measureText("Běží na pozadí"));
        final float icon = dp(18), gapI = dp(Glass.GAP_S);
        final float b0 = 2 * dp(RUN_BTN_PAD) + icon + gapI + tabText.measureText(RUN_LABELS[0]);
        final float b1 = 2 * dp(RUN_BTN_PAD) + icon + gapI + tabText.measureText(RUN_LABELS[1]);
        final float w = in + thumbW + dp(14) + textW + dp(Glass.PAD) + b0 + dp(Glass.GAP_S) + b1
                + dp(Glass.GAP_S) + bh + in;
        final float bottom = frame.bottom - dp(RUN_BOTTOM);
        runRect.set(frame.centerX() - w / 2f, bottom - h, frame.centerX() + w / 2f, bottom);
        runThumb.set(runRect.left + in, runRect.top + in, runRect.left + in + thumbW, runRect.bottom - in);
        float x = runRect.right - in;
        runBtn[RUN_HIDE].set(x - bh, runRect.top + in, x, runRect.bottom - in);
        x -= bh + dp(Glass.GAP_S);
        runBtn[RUN_QUIT].set(x - b1, runRect.top + in, x, runRect.bottom - in);
        x -= b1 + dp(Glass.GAP_S);
        runBtn[RUN_RESUME].set(x - b0, runRect.top + in, x, runRect.bottom - in);
    }

    private float batteryWidth() {
        final String pct = batteryLevel >= 0 ? batteryLevel + "%" : "–";
        final int bolts = charging ? (fastCharging ? 2 : 1) : 0;
        return dp(10) + batteryText.measureText(pct)
                + (bolts > 0 ? dp(5) + bolts * dp(8.5f) + (bolts - 1) * dp(1.5f) : 0) + dp(10);
    }

    /**
     * Sklo plovouciho prvku (ornament, lista) - stejny recept jako panel, s mekkym stinem.
     * Stin jen VNE tvaru (clipOut). Ornament a lista bezici aplikace (liquid) jsou
     * pruhledne - pod nimi je rozmazany a lomeny obsah (backdrop); lista vlevo ma
     * kryci telo (pod ni zadny rozmazany podklad neni).
     */
    private void drawChromeGlass(Canvas c, GlassSurface g, RectF r, float radius, boolean liquid) {
        final boolean light = prefs.glassStyle() == Prefs.GLASS_VISION;
        chromePath.reset();
        chromePath.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.save();
        c.clipOutPath(chromePath);
        fill.setShader(null);
        fill.setColor(Palette.VOID);
        fill.setShadowLayer(dp(18), 0, dp(6), 0x73000000);
        c.drawRoundRect(r.left + dp(2), r.top + dp(2), r.right - dp(2), r.bottom - dp(2), radius, radius, fill);
        fill.clearShadowLayer();
        c.restore();
        if (!liquid) {
            // Bez rozmazaneho podkladu (lista) kryci telo - karty pod rozbalenou listou
            // by jinak prosvitaly i s napisy.
            fill.setColor(light ? 0xFF6B7380 : Palette.VOID);
            c.drawRoundRect(r.left + dp(1), r.top + dp(1), r.right - dp(1), r.bottom - dp(1), radius, radius, fill);
        }
        final GlassSurface.Style st = liquid
                ? (light ? GlassSurface.LIQUID_LIGHT : GlassSurface.LIQUID_DARK)
                : (light ? GlassSurface.CHROME_LIGHT : GlassSurface.CHROME_DARK);
        g.draw(c, r, radius, st, getResources().getDisplayMetrics().density);
    }

    /** Horni "ornament": zalozky s posuvnym indikatorem, oddelovac, hodiny a baterie. */
    private void drawOrnament(Canvas c, long now) {
        final float midY = ornRect.centerY();
        final float itemH = tabRects[0].height();
        drawChromeGlass(c, ornGlass, ornRect, ornRect.height() / 2f, true);
        drawRimLight(c, now, ornRect, ornRect.height() / 2f, false);

        // Indikator vybrane zalozky jede na pruzine (muze lehce prejet a vratit se).
        final float pos = clamp(tabPos.get(now), -0.3f, TAB_NAMES.length - 0.7f);
        final int i0 = (int) clamp((float) Math.floor(pos), 0f, TAB_NAMES.length - 2);
        final float f = pos - i0;
        final float il = lerp(tabRects[i0].left, tabRects[i0 + 1].left, f);
        final float ir = lerp(tabRects[i0].right, tabRects[i0 + 1].right, f);
        // Vybrana zalozka = svetlejsi sklenena kapsle (bez barevne zare).
        tabGlass.draw(c, il, midY - itemH / 2f, ir, midY + itemH / 2f, itemH / 2f, GlassSurface.TILE_HOVER,
                getResources().getDisplayMetrics().density, 1f);
        final Paint.FontMetrics fm = tabText.getFontMetrics();
        final float base = midY - (fm.ascent + fm.descent) / 2f;
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final float hv = tabHover[i].get(now);
            if (hv > 0.004f && i != tab) {
                fill.setColor(Color.argb(Math.round(22 * hv), 255, 255, 255));
                c.drawRoundRect(tabRects[i], itemH / 2f, itemH / 2f, fill);
            }
            final float a = i == tab ? 1f : lerp(0.66f, 0.95f, hv);
            tabText.setColor(Palette.alpha(Palette.TEXT, a));
            c.drawText(TAB_NAMES[i], tabRects[i].centerX(), base, tabText);
        }

        // Oddelovac.
        fill.setColor(0x33FFFFFF);
        c.drawRect(ornDividerX - dp(0.5f), midY - dp(12), ornDividerX + dp(0.5f), midY + dp(12), fill);

        // Stav: hover rozsviti (klepnuti = rychle menu).
        final float sh = batteryHover.get(now);
        if (sh > 0.004f) {
            fill.setColor(Color.argb(Math.round(26 * sh), 255, 255, 255));
            c.drawRoundRect(statusRect, itemH / 2f, itemH / 2f, fill);
        }
        final Paint.FontMetrics cfm = clockText.getFontMetrics();
        final float cbase = midY - (cfm.ascent + cfm.descent) / 2f;
        // Ziva bublina: normalni stav rychle zmizi, novy obsah naskoci o chlup pozdeji.
        final float la = clamp(liveAmt.get(now), 0f, 1f);
        final float normalA = liveText == null ? 1f : clamp(1f - la * 1.7f, 0f, 1f);
        final float liveA = liveText == null ? 0f : clamp((la - 0.35f) / 0.65f, 0f, 1f);
        final boolean morphing = normalA < 1f;
        if (morphing) {
            c.save();
            c.clipRect(statusRect);
        }
        if (normalA > 0.004f) {
            if (normalA < 0.999f) c.saveLayerAlpha(statusRect, Math.round(255 * normalA));
            float x = statusRect.left + dp(ORN_PAD_X);
            clockText.setColor(Palette.TEXT);
            c.drawText(clockTime, x, cbase, clockText);
            x += clockText.measureText(clockTime) + dp(ORN_STATUS_GAP);
            drawBattery(c, x, midY);
            Icons.draw(c, Icons.SLIDERS, batteryRect.right + dp(ORN_STATUS_GAP) + dp(ORN_ICON) / 2f, midY,
                    dp(ORN_ICON), Color.argb(Math.round(lerp(0xB3, 0xFF, sh)), 255, 255, 255), dp(1.7f), 1f, fill);
            if (normalA < 0.999f) c.restore();
        }
        if (liveA > 0.004f) drawLive(c, now, midY, cbase, liveA);
        if (morphing) c.restore();
    }

    /** Obsah zive bubliny: krouzek prubehu (nebo ikona) a text, naskoci s lehkym zvetsenim. */
    private void drawLive(Canvas c, long now, float midY, float base, float a) {
        final float ringR = dp(LIVE_RING) / 2f;
        final float cx = statusRect.left + dp(ORN_PAD_X) + ringR;
        final float s = 0.86f + 0.14f * a;
        c.save();
        c.scale(s, s, statusRect.centerX(), midY);
        final int col = Palette.alpha(liveColor, a);
        if (Float.isNaN(liveProgress)) {
            Icons.draw(c, liveIcon, cx, midY, dp(20), col, dp(1.8f), 1f, fill);
        } else {
            final float sw = dp(2.5f);
            final float rr = ringR - sw / 2f;
            stroke.setShader(null);
            stroke.setStrokeWidth(sw);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setColor(Color.argb(Math.round(56 * a), 255, 255, 255));
            c.drawCircle(cx, midY, rr, stroke);
            stroke.setColor(col);
            tmp.set(cx - rr, midY - rr, cx + rr, midY + rr);
            if (liveProgress == LIVE_SPINNER) {
                final float turn = (now / 1_000_000L % 900L) / 900f * 360f;
                c.drawArc(tmp, turn - 90f, 100f, false, stroke);
            } else {
                final float p = clamp(liveProg.get(now), 0f, 1f);
                if (p > 0.004f) c.drawArc(tmp, -90f, 360f * p, false, stroke);
            }
            stroke.setStrokeCap(Paint.Cap.BUTT);
            Icons.draw(c, liveIcon, cx, midY, dp(13), Palette.alpha(Palette.TEXT, a), dp(1.6f), 1f, fill);
        }
        clockText.setColor(Palette.alpha(Palette.TEXT, a));
        c.drawText(liveText, cx + ringR + dp(LIVE_GAP), base, clockText);
        clockText.setColor(Palette.TEXT);
        c.restore();
    }

    /**
     * Lista bezici aplikace (3x Meta ze hry): vyjede zdola na pruzine. Nahled hry,
     * nazev, "Bezi na pozadi" a tlacitka Pokracovat (perlove) / Ukoncit / skryt.
     */
    private void drawRunBar(Canvas c, long now) {
        final float a = runAmt.get(now);
        if (a <= 0.004f || runLabel == null || runRect.isEmpty()) return;
        final float ac = clamp(a, 0f, 1f);
        final float h2 = runRect.height() / 2f;
        c.save();
        c.translate(0, (1f - a) * dp(28));
        final float s = 0.94f + 0.06f * ac;
        c.scale(s, s, runRect.centerX(), runRect.centerY());
        final boolean fading = ac < 0.999f;
        if (fading) {
            tmp.set(runRect);
            tmp.inset(-dp(40), -dp(40));
            c.saveLayerAlpha(tmp, Math.round(255 * ac));
        }
        if (c.isHardwareAccelerated()) drawRunBackdrop(c, now);
        drawChromeGlass(c, runGlass, runRect, h2, true);
        drawRimLight(c, now, runRect, h2, false);

        // Nahled hry (banner), soustredne zaobleny.
        final float tr = runThumb.height() / 2f;
        final Bitmap art = artwork != null && runApp != null ? artwork.get(runApp) : null;
        if (art != runArt) {
            runArt = art;
            runShader = art != null ? new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) : null;
        }
        if (runShader != null) {
            final float k = Math.max(runThumb.width() / runArt.getWidth(), runThumb.height() / runArt.getHeight());
            runMatrix.setScale(k, k);
            runMatrix.postTranslate(runThumb.centerX() - runArt.getWidth() * k / 2f,
                    runThumb.centerY() - runArt.getHeight() * k / 2f);
            runShader.setLocalMatrix(runMatrix);
            fill.setColor(Color.WHITE);
            fill.setShader(runShader);
            c.drawRoundRect(runThumb, tr, tr, fill);
            fill.setShader(null);
        } else {
            fill.setShader(null);
            fill.setColor(0x26FFFFFF);
            c.drawRoundRect(runThumb, tr, tr, fill);
        }
        stroke.setShader(null);
        stroke.setStrokeWidth(dp(1));
        stroke.setColor(0x33FFFFFF);
        c.drawRoundRect(runThumb.left + dp(0.5f), runThumb.top + dp(0.5f), runThumb.right - dp(0.5f),
                runThumb.bottom - dp(0.5f), tr, tr, stroke);

        // Nazev a stav.
        final float tx = runThumb.right + dp(14);
        final Paint.FontMetrics tfm = runTitle.getFontMetrics();
        final Paint.FontMetrics sfm = runSub.getFontMetrics();
        final float th = (tfm.descent - tfm.ascent) + dp(2) + (sfm.descent - sfm.ascent);
        final float ty = runRect.centerY() - th / 2f;
        runTitle.setColor(Palette.TEXT);
        c.drawText(runShownTitle, tx, ty - tfm.ascent, runTitle);
        runSub.setColor(Palette.text2());
        c.drawText("Běží na pozadí", tx, ty + (tfm.descent - tfm.ascent) + dp(2) - sfm.ascent, runSub);

        // Tlacitka.
        final Paint.FontMetrics bfm = tabText.getFontMetrics();
        final float icon = dp(18), gapI = dp(Glass.GAP_S);
        for (int i = 0; i < runBtn.length; i++) {
            final RectF r = runBtn[i];
            final float hv = runHover[i].get(now);
            final float rr = r.height() / 2f;
            final float cy = r.centerY();
            if (i == RUN_RESUME) {
                // Hlavni akce: perlova pilulka s tmavym textem.
                fill.setColor(Color.WHITE);
                fill.setShader(pearlShader(r.left, r.top, r.right, r.bottom));
                c.drawRoundRect(r, rr, rr, fill);
                fill.setShader(null);
                if (hv > 0.004f) {
                    fill.setColor(Color.argb(Math.round(110 * hv), 255, 255, 255));
                    c.drawRoundRect(r, rr, rr, fill);
                }
            } else {
                final GlassSurface g = runBtnGlass[i == RUN_QUIT ? 0 : 1];
                g.draw(c, r.left, r.top, r.right, r.bottom, rr, hv > 0.5f ? GlassSurface.TILE_HOVER : GlassSurface.TILE,
                        d, 1f);
                if (hv > 0.004f) {
                    fill.setShader(null);
                    fill.setColor(Color.argb(Math.round(26 * hv), 255, 255, 255));
                    c.drawRoundRect(r, rr, rr, fill);
                }
            }
            if (i == RUN_HIDE) {
                Icons.draw(c, Icons.CLOSE, r.centerX(), cy, dp(18), Palette.TEXT, dp(1.8f), 1f, fill);
                continue;
            }
            final int ink = i == RUN_RESUME ? Glass.INK : Palette.TEXT;
            float x = r.left + dp(RUN_BTN_PAD);
            if (i == RUN_RESUME) Icons.fill(c, Icons.PLAY, x + icon / 2f, cy, icon * 0.85f, ink, fill);
            else Icons.draw(c, Icons.POWER, x + icon / 2f, cy, icon, Palette.MAGENTA, dp(1.8f), 1f, fill);
            tabText.setTextAlign(Paint.Align.LEFT);
            tabText.setColor(ink);
            c.drawText(RUN_LABELS[i], x + icon + gapI, cy - (bfm.ascent + bfm.descent) / 2f, tabText);
            tabText.setTextAlign(Paint.Align.CENTER);
        }
        if (fading) c.restore();
        c.restore();
    }

    /** Tekute sklo listy: rozmazana kopie panelu a karet pod ni (jako u ornamentu). */
    private void drawRunBackdrop(Canvas canvas, long now) {
        if (backdropEffect == null) return;
        final int l = Math.round(runRect.left), t = Math.round(runRect.top);
        final int w = Math.round(runRect.width()), h = Math.round(runRect.height());
        if (w <= 0 || h <= 0) return;
        runBackdropNode.setPosition(l, t, l + w, t + h);
        final Outline o = new Outline();
        o.setRoundRect(0, 0, w, h, h / 2f);
        runBackdropNode.setOutline(o);
        runBackdropNode.setClipToOutline(true);
        final RecordingCanvas rc = runBackdropNode.beginRecording(w, h);
        try {
            rc.translate(-l, -t);
            rc.clipRect(frame.left, frame.top, frame.right, frame.bottom);
            if (panelStyle != null) {
                panelGlass.draw(rc, frame, dp(FRAME_RADIUS), panelStyle, getResources().getDisplayMetrics().density);
            }
            drawCardsInto(rc, now, runRect.top, runRect.bottom, 0f, false);
        } finally {
            runBackdropNode.endRecording();
        }
        runBackdropNode.setRenderEffect(runLiquid.effect(backdropEffect, w, h, dp(LIQUID_BAND), dp(LIQUID_STRENGTH)));
        canvas.drawRenderNode(runBackdropNode);
    }

    /** Pilulka baterie: procenta v barve nabiti, bile blesky pri nabijeni. */
    private void drawBattery(Canvas c, float x, float midY) {
        final String pct = batteryLevel >= 0 ? batteryLevel + "%" : "–";
        final int bolts = charging ? (fastCharging ? 2 : 1) : 0;
        final float boltW = dp(8.5f), boltH = dp(12f), boltGap = dp(1.5f);
        final float batH = dp(26);
        batteryRect.set(x, midY - batH / 2f, x + batteryWidth(), midY + batH / 2f);
        final int color = batteryColor();
        fill.setShader(null);
        if (isLowBattery()) {
            // Slaba baterie: cervena zare (prvnich 8 s pulzuje, pak zustane klidna).
            final long since = System.nanoTime() - lowBatterySinceNs;
            float glow = 0.7f;
            if (since < LOW_BATTERY_PULSE_NS) glow = 0.45f + 0.55f * (0.5f + 0.5f * (float) Math.sin(since / 1e9 * Math.PI * 2 / 1.2));
            fill.setColor(Palette.alpha(Palette.MAGENTA, 0.35f));
            fill.setShadowLayer(dp(12) * glow, 0, 0, Palette.alpha(Palette.MAGENTA, 0.9f * glow));
            c.drawRoundRect(batteryRect, batH / 2f, batH / 2f, fill);
            fill.clearShadowLayer();
        }
        fill.setColor(0x59000000);
        c.drawRoundRect(batteryRect, batH / 2f, batH / 2f, fill);
        stroke.setStrokeWidth(dp(1));
        stroke.setColor((color & 0x00FFFFFF) | 0x66000000);
        c.drawRoundRect(batteryRect.left + dp(0.5f), batteryRect.top + dp(0.5f),
                batteryRect.right - dp(0.5f), batteryRect.bottom - dp(0.5f), batH / 2f, batH / 2f, stroke);
        final Paint.FontMetrics bfm = batteryText.getFontMetrics();
        batteryText.setColor(color);
        float bx = batteryRect.left + dp(10);
        c.drawText(pct, bx, midY - (bfm.ascent + bfm.descent) / 2f, batteryText);
        bx += batteryText.measureText(pct) + dp(5);
        if (bolts > 0) {
            fill.setColor(Color.WHITE);
            fill.setShadowLayer(dp(2), 0, 0, 0xCCFFFFFF);
            for (int i = 0; i < bolts; i++) {
                c.save();
                c.translate(bx, midY - boltH / 2f);
                c.scale(boltW / 14f, boltH / 20f); // path zabira x 4..18, y 2..22
                c.translate(-4f, -2f);
                c.drawPath(boltPath, fill);
                c.restore();
                bx += boltW + boltGap;
            }
            fill.clearShadowLayer();
        }
    }

    /**
     * Leva lista (jako tab bar ve visionOS): logo Neo a ikony Knihovna, Karusel,
     * Rychle menu, Nastaveni. Po najeti se na pruzine rozbali a ukaze popisky.
     */
    private void drawRail(Canvas c, long now) {
        final float e = clamp(railExpand.get(now), 0f, 1f);
        final float rr = dp(RAIL_W) / 2f;
        drawChromeGlass(c, railGlass, railRect, rr, false);
        drawRimLight(c, now, railRect, rr, false);
        final float ic = dp(RAIL_ITEM) / 2f; // polomer kolecka ikony
        final float iconX = railRect.left + dp(6) + ic;
        c.save();
        chromePath.reset();
        chromePath.addRoundRect(railRect, rr, rr, Path.Direction.CW);
        c.clipPath(chromePath);

        // Logo Neo (5x klepnout = karusel, 1x = nastaveni).
        final float ly = brandRect.centerY();
        final float hv = brandHover.get(now);
        if (hv > 0.004f) {
            fill.setShader(null);
            fill.setColor(Color.argb(Math.round(26 * hv), 255, 255, 255));
            c.drawRoundRect(brandRect, ic, ic, fill);
        }
        final float icon = dp(32);
        fill.setShader(null);
        fill.setColor(0xFF000000);
        c.drawRoundRect(iconX - icon / 2f, ly - icon / 2f, iconX + icon / 2f, ly + icon / 2f, dp(8), dp(8), fill);
        stroke.setStrokeWidth(dp(1));
        stroke.setColor(0x4DFFFFFF);
        c.drawRoundRect(iconX - icon / 2f + dp(0.5f), ly - icon / 2f + dp(0.5f), iconX + icon / 2f - dp(0.5f),
                ly + icon / 2f - dp(0.5f), dp(7.5f), dp(7.5f), stroke);
        final float tap = brandTap.get(now);
        fill.setColor(Color.WHITE);
        if (tap > 0.004f) fill.setShadowLayer(dp(10) * tap, 0, 0, Palette.alpha(Palette.COBALT_LIGHT, 0.9f * tap));
        c.drawCircle(iconX, ly, dp(7) * (1f + 0.25f * tap), fill);
        fill.clearShadowLayer();
        if (e > 0.01f) {
            final Paint.FontMetrics bfm = brandText.getFontMetrics();
            brandText.setAlpha(Math.round(255 * e));
            c.drawText("Neo", iconX + ic + dp(10), ly - (bfm.ascent + bfm.descent) / 2f, brandText);
            brandText.setAlpha(255);
        }
        // Oddelovac pod logem.
        fill.setColor(0x26FFFFFF);
        final float dy = brandRect.bottom + dp(6);
        c.drawRect(railRect.left + dp(12), dy - dp(0.5f), railRect.right - dp(12), dy + dp(0.5f), fill);

        final Paint.FontMetrics fm = tabText.getFontMetrics();
        for (int i = 0; i < RAIL_LABELS.length; i++) {
            final RectF r = railRects[i];
            final float cy = r.centerY();
            final float h = railHover[i].get(now);
            final boolean selected = i == RAIL_LIBRARY;
            // Vybrana polozka = perlove kolecko s tmavou ikonou, hover = svetle pozadi radku.
            if (h > 0.004f) {
                fill.setColor(Color.argb(Math.round(30 * h), 255, 255, 255));
                c.drawRoundRect(r, ic, ic, fill);
            }
            if (selected) {
                fill.setColor(Color.WHITE); // alfa barvy se nasobi i se shaderem
                fill.setShader(pearlShader(iconX - ic, cy - ic, iconX + ic, cy + ic));
                c.drawCircle(iconX, cy, ic - dp(2), fill);
                fill.setShader(null);
            }
            Icons.draw(c, RAIL_ICONS[i], iconX, cy, dp(20), selected ? Glass.INK : Palette.TEXT,
                    dp(1.8f), 1f, fill);
            if (e > 0.01f) {
                tabText.setTextAlign(Paint.Align.LEFT);
                tabText.setColor(Color.argb(Math.round(255 * e * (0.85f + 0.15f * h)), 255, 255, 255));
                c.drawText(RAIL_LABELS[i], iconX + ic + dp(10), cy - (fm.ascent + fm.descent) / 2f, tabText);
                tabText.setTextAlign(Paint.Align.CENTER);
            }
        }
        c.restore();
    }

    private int batteryColor() {
        return Palette.battery(batteryLevel);
    }

    /** Perlovy prechod (temer bila s nadechem do fialova a modra). */
    private static Shader pearlShader(float l, float t, float r, float b) {
        return new LinearGradient(l, t, r, b, new int[]{Palette.PEARL, Palette.PEARL_LILAC, Palette.PEARL_BLUE},
                null, Shader.TileMode.CLAMP);
    }

    private void drawEmptyState(Canvas c) {
        if (!cards.isEmpty()) return;
        final String msg = !appsLoaded ? "Načítám aplikace…"
                : tab == Prefs.TAB_GAMES ? "Žádné VR hry" : "Žádné aplikace";
        final float cy = (topBarBottom + frame.bottom) / 2f;
        c.drawText(msg, frame.centerX(), cy, emptyText);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
