package com.neolauncher.ui;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;
import android.text.TextPaint;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import java.util.Calendar;
import java.util.Locale;

/**
 * Rychle menu Nea - vlastni obdoba systemoveho menu Questu (ovladaci
 * centrum): hodiny, baterie, Wi-Fi, posuvniky jasu a hlasitosti, dlazdice
 * hlavnich funkci Questu. Vyjede jako bocni panel vpravo po klepnuti na
 * hodiny/baterii nebo tlacitkem menu na ovladaci.
 * <p>
 * Rozmery jsou z tokenu v Glass (PAD, SECTION, GAP, R_TILE...), at mezery
 * a zaobleni sedi se vsemi ostatnimi okny.
 * <p>
 * Jas = Settings.System.SCREEN_BRIGHTNESS (potrebuje "Upravu systemovych
 * nastaveni", stejne jako QuestDim), hlasitost = AudioManager (bez opravneni).
 * Vse kreslene v jednom View, pohyb na pruzinach, stav jen z casu.
 */
public final class QuickMenuView extends View {

    public static final int T_WIFI = 0;
    public static final int T_BLUETOOTH = 1;
    /** Sdileni Questu: streamovani (Chromecast...), nahravani, snimek. */
    public static final int T_CAST = 2;
    /** Android nastaveni (schovana na Questu - opravneni, pristupnost, aplikace). */
    public static final int T_ANDROID_SETTINGS = 3;
    public static final int T_QUEST_QUICK = 4;
    public static final int T_FILES = 5;
    public static final int T_BROWSER = 6;
    /** Uspat headset / nabidka vypnuti a restartu (pres sluzbu Meta tlacitka). */
    public static final int T_SLEEP = 7;
    public static final int T_POWER = 8;
    public static final int T_NEO = 9;
    /** Dlazdice ve trech sloupcich (ikona nahore, nazev pod ni). */
    private static final int TILE_COLS = 3;

    public interface Actions {
        void openTarget(int target);

        /** Klepnuti na kartu naposledy hrane hry. */
        void launch(com.neolauncher.data.AppEntry app);

        void requestBrightnessAccess();
    }

    private static final String[] TILE_LABELS = {"Wi-Fi", "Bluetooth", "Streamovat", "Android",
            "Menu Questu", "Soubory", "Prohlížeč", "Uspat", "Vypnout", "Neo"};
    private static final int[] TILE_ICONS = {Icons.WIFI, Icons.BLUETOOTH, Icons.CAST, Icons.GEAR,
            Icons.SLIDERS, Icons.FOLDER, Icons.GLOBE, Icons.MOON, Icons.POWER, Icons.NEO};
    private static final float W_DP = 400f;
    private static final float H_DP = 598f;
    /** Karta "Naposledy hrano": vyska a tlacitko uvnitr. */
    private static final float CARD_H = 68f;
    private static final float CARD_BTN_H = 36f;
    private static final float SLIDER_H = 44f;
    /**
     * Vrch cislic hodin: vic nez PAD - velke pismo u rohu s radiusem 40 by jinak
     * pusobilo namackane. Pod datem uz jen GAP ke karte.
     */
    private static final float HEADER_TOP = Glass.TITLE_TOP;
    /** Pilulky baterie a Wi-Fi v hlavicce. */
    private static final float PILL_H = 32f;
    private static final float PILL_PAD = 12f;
    /** Dlazdice rostou do volneho mista, ale jen v rozumnych mezich. */
    private static final float TILE_MIN = 52f;
    private static final float TILE_MAX = 72f;
    /** Postupny nastup prvku (kaskada), ms mezi prvky. */
    private static final float STAGGER_MS = 28f;

    private static final int EL_BRIGHT = 0;
    private static final int EL_VOLUME = 1;
    private static final int EL_TILE0 = 2;
    /** Karta "Naposledy hrano". */
    private static final int EL_LAST = EL_TILE0 + TILE_LABELS.length;
    private static final int EL_COUNT = EL_LAST + 1;

    private final float d;
    private final Actions actions;
    private final AudioManager audio;
    private final RectF[] rects = new RectF[EL_COUNT];
    private final Spring[] hover = new Spring[EL_COUNT];
    private final Spring brightShown = new Spring(0, 0.22f, 1f, 0.001f);
    private final Spring volumeShown = new Spring(0, 0.22f, 1f, 0.001f);
    private float bright, volume;
    private boolean brightOk;
    private int volMax = 15;
    private int batteryLevel = -1;
    private String batteryInfo;
    private boolean charging, fastCharging;
    private boolean wifiOn;
    private int wifiBars;
    private long shownNs;
    /** Zakladni linky hlavicky (spocitane z metriky pisma, aby cislice zacinaly presne na okraji). */
    private float timeTop, digitH, timeBase, dateBase;
    private com.neolauncher.data.AppEntry lastApp;
    private String lastTitle, lastSub, lastButton;
    private android.graphics.BitmapShader lastShader;
    private android.graphics.Bitmap lastArt;
    private final android.graphics.Matrix lastMatrix = new android.graphics.Matrix();
    private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    /** Prave bezi streamovani do prohlizece -> dlazdice Streamovat ukaze "Vysila". */
    private boolean castActive;

    public void setCastActive(boolean on) {
        castActive = on;
        invalidate();
    }

    private int hovered = -1;
    private int pressed = -1;
    private boolean draggingSlider;
    private long lastWheelMs;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timeText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint dateText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint labelText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint tileText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint smallText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Path path = new Path();
    private final Path boltPath = new Path();
    private final RectF tmp = new RectF();
    private final android.graphics.Rect textBounds = new android.graphics.Rect();
    /** Sklo: panel, pilulky v hlavicce a kazdy prvek zvlast (vlastni cache shaderu). */
    private final GlassSurface panelGlass = new GlassSurface();
    private final GlassSurface[] pillGlass = {new GlassSurface(), new GlassSurface()};
    private final HoverGlass[] glass = new HoverGlass[EL_COUNT];
    /** Posledni poloha laseru (pro svetlo pod ukazatelem), -1 = mimo. */
    private float pointerX = -1, pointerY = -1;
    private Shader spot;
    private float spotX, spotY, spotR;

    public QuickMenuView(Context c, Actions actions) {
        super(c);
        this.actions = actions;
        d = getResources().getDisplayMetrics().density;
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        setClickable(true);
        for (int i = 0; i < EL_COUNT; i++) {
            rects[i] = new RectF();
            glass[i] = new HoverGlass();
            hover[i] = new Spring(0, 0.30f, 0.78f, 0.002f);
        }
        Typeface semi = Typeface.create(Typeface.SANS_SERIF, 600, false);
        Typeface bold = Typeface.create(Typeface.SANS_SERIF, 700, false);
        timeText.setTypeface(bold);
        timeText.setTextSize(dp(38));
        timeText.setFontFeatureSettings("tnum");
        timeText.setColor(Color.WHITE);
        dateText.setTypeface(semi);
        dateText.setTextSize(dp(13.5f));
        dateText.setColor(0xB3FFFFFF);
        labelText.setTypeface(semi);
        labelText.setTextSize(dp(14));
        labelText.setFontFeatureSettings("tnum");
        tileText.setTypeface(semi);
        tileText.setTextSize(dp(12.5f));
        tileText.setTextAlign(Paint.Align.CENTER);
        smallText.setTypeface(semi);
        smallText.setTextSize(dp(12.5f));
        smallText.setFontFeatureSettings("tnum");
        stroke.setStyle(Paint.Style.STROKE);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeJoin(Paint.Join.ROUND);
        boltPath.moveTo(11, 2);
        boltPath.lineTo(4, 13);
        boltPath.rLineTo(5, 0);
        boltPath.rLineTo(-1.5f, 9);
        boltPath.lineTo(18, 11);
        boltPath.rLineTo(-5.5f, 0);
        boltPath.lineTo(14, 2);
        boltPath.close();
        refresh();
        final long now = System.nanoTime();
        brightShown.snap(bright);
        volumeShown.snap(volume);
        shownNs = now;
    }

    private float dp(float v) {
        return v * d;
    }

    /** Sirka bocniho panelu vpravo (dlazdice ve dvou sloupcich). */
    public static int sideWidth(Context c) {
        return Glass.dpi(c, W_DP);
    }

    /**
     * Naposledy hrana hra / aplikace - karta s tlacitkem "Hrat".
     * Klepnuti hru spusti znovu (bezi-li jeste na pozadi, Quest se do ni vrati).
     */
    public void setLastPlayed(com.neolauncher.data.AppEntry app, String title, String sub,
                              android.graphics.Bitmap art) {
        lastApp = app;
        lastTitle = title;
        lastSub = sub;
        lastButton = app != null && app.isVr() ? "Hrát" : "Otevřít";
        lastArt = art;
        lastShader = art != null ? new android.graphics.BitmapShader(art,
                Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) : null;
        if (getWidth() > 0) onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
        invalidate();
    }

    /** Odhad vydrze / nabiti (vpravo na radku s datem), null = nic. */
    public void setBatteryInfo(String text) {
        batteryInfo = text;
        invalidate();
    }

    public void setBattery(int level, boolean isCharging, boolean isFast) {
        batteryLevel = level;
        charging = isCharging;
        fastCharging = isFast;
        invalidate();
    }

    /** Nacte aktualni stav systemu (jas, hlasitost, Wi-Fi). */
    public void refresh() {
        final ContentResolver cr = getContext().getContentResolver();
        try {
            brightOk = Settings.System.canWrite(getContext());
        } catch (Exception e) {
            brightOk = false;
        }
        try {
            final int b = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128);
            bright = clamp((b - 10) / 245f, 0f, 1f);
        } catch (Exception e) {
            bright = 0.5f;
        }
        if (audio != null) {
            volMax = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) volMax;
        }
        wifiOn = false;
        wifiBars = 0;
        try {
            ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities nc = n != null ? cm.getNetworkCapabilities(n) : null;
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                wifiOn = true;
                final int rssi = nc.getSignalStrength();
                wifiBars = rssi == Integer.MIN_VALUE ? 3 : rssi >= -55 ? 4 : rssi >= -66 ? 3 : rssi >= -77 ? 2 : 1;
            }
        } catch (Exception ignored) {
        }
        final long now = System.nanoTime();
        if (!draggingSlider) {
            brightShown.set(bright, now);
            volumeShown.set(volume, now);
        }
        invalidate();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        // Navrat ze systemoveho nastaveni (povoleni jasu) nebo zmena hlasitosti tlacitky.
        if (hasWindowFocus) refresh();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int w = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? Math.round(dp(W_DP)) : MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, resolveSize(Math.round(dp(H_DP)), heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layout(w, h);
    }

    /**
     * Svisle: HEADER_TOP | hodiny + datum | GAP | karta | GAP | jas | GAP | hlasitost
     * | GAP | dlazdice (GAP mezi nimi) | GAP | tlacitko | PAD. Vsude stejne mezery,
     * dlazdice si vezmou zbytek vysky.
     */
    private void layout(int w, int h) {
        final float pad = dp(Glass.PAD), gap = dp(Glass.GAP);
        // Hlavicka: hodiny s volnym mistem nad sebou, datum pod nimi, pak GAP ke karte.
        timeText.getTextBounds("0123456789", 0, 10, textBounds);
        timeTop = dp(HEADER_TOP);
        digitH = -textBounds.top;
        timeBase = timeTop + digitH;
        dateText.getTextBounds("0123456789", 0, 10, textBounds);
        dateBase = timeBase + dp(12) - textBounds.top;
        float y = dateBase + dp(4) + dp(Glass.GAP);
        if (lastApp != null) {
            rects[EL_LAST].set(pad, y, w - pad, y + dp(CARD_H));
            y += dp(CARD_H) + gap;
        } else {
            rects[EL_LAST].setEmpty();
        }
        final float sh = dp(SLIDER_H);
        rects[EL_BRIGHT].set(pad, y, w - pad, y + sh);
        y += sh + gap;
        rects[EL_VOLUME].set(pad, y, w - pad, y + sh);
        y += sh + gap;
        // Posledni "dlazdice" (Neo) = tlacitko "Nastaveni Nea" pres celou sirku dole.
        final float btnTop = h - pad - dp(Glass.BUTTON_H);
        rects[EL_TILE0 + T_NEO].set(pad, btnTop, w - pad, h - pad);
        final int rows = (T_NEO + TILE_COLS - 1) / TILE_COLS;
        final float th = clamp((btnTop - gap - y - (rows - 1) * gap) / rows, dp(TILE_MIN), dp(TILE_MAX));
        final float tw = (w - 2 * pad - (TILE_COLS - 1) * gap) / TILE_COLS;
        for (int i = 0; i < T_NEO; i++) {
            final int col = i % TILE_COLS, row = i / TILE_COLS;
            final float l = pad + col * (tw + gap);
            final float t = y + row * (th + gap);
            rects[EL_TILE0 + i].set(l, t, l + tw, t + th);
        }
    }

    // =========================================================================
    // Kresleni
    // =========================================================================

    /** Nastup prvku el: kriticky tlumena pruzina se zpozdenim (kaskada shora dolu). */
    private float appear(int el, long now) {
        final int order = el == EL_LAST ? 0 : el + 1;
        final float t = (now - shownNs) / 1e6f - order * STAGGER_MS;
        if (t <= 0f) return 0f;
        final float w = (float) (2 * Math.PI / 0.42) * t / 1000f;
        return 1f - (1f + w) * (float) Math.exp(-w);
    }

    @Override
    protected void onDraw(Canvas c) {
        final long now = System.nanoTime();
        final float w = getWidth(), h = getHeight();
        // Sklo panelu stejnym receptem jako ostatni okna (GlassSurface).
        panelGlass.draw(c, 0, 0, w, h, dp(Glass.R_PANEL), GlassSurface.panel(), d, 1f);

        drawHeader(c, w);
        drawSlider(c, EL_BRIGHT, now, brightOk ? brightShown.get(now) : 0f,
                brightOk ? "Jas" : "Jas – klepni pro povolení", brightOk);
        drawSlider(c, EL_VOLUME, now, volumeShown.get(now), "Hlasitost", true);
        for (int i = 0; i < TILE_LABELS.length; i++) drawTile(c, i, now);
        if (lastApp != null) drawLastPlayed(c, now);

        boolean anim = brightShown.active(now) || volumeShown.active(now)
                || (now - shownNs) / 1e6f < EL_COUNT * STAGGER_MS + 900f;
        for (Spring s : hover) anim |= s.active(now);
        if (anim) postInvalidateOnAnimation();
    }

    private void drawHeader(Canvas c, float w) {
        final Calendar cal = Calendar.getInstance();
        final String time = String.format(Locale.ROOT, "%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
        final String[] days = {"neděle", "pondělí", "úterý", "středa", "čtvrtek", "pátek", "sobota"};
        final String[] months = {"ledna", "února", "března", "dubna", "května", "června", "července",
                "srpna", "září", "října", "listopadu", "prosince"};
        final String date = days[cal.get(Calendar.DAY_OF_WEEK) - 1] + " " + cal.get(Calendar.DAY_OF_MONTH)
                + ". " + months[cal.get(Calendar.MONTH)];
        final float pad = dp(Glass.PAD);
        timeText.setColor(Palette.TEXT);
        c.drawText(time, pad, timeBase, timeText);
        dateText.setColor(Palette.text2());
        c.drawText(date, pad, dateBase, dateText);
        // Na stejnem radku vpravo: odhad vydrze baterie (nebo za jak dlouho bude nabito).
        if (batteryInfo != null) {
            final float dw = dateText.measureText(date);
            final float max = w - 2 * pad - dw - dp(Glass.GAP);
            if (max > dp(40)) {
                final CharSequence info = android.text.TextUtils.ellipsize(batteryInfo, smallText, max,
                        android.text.TextUtils.TruncateAt.END);
                smallText.setColor(Palette.text2());
                smallText.setTextAlign(Paint.Align.RIGHT);
                c.drawText(info, 0, info.length(), w - pad, dateBase, smallText);
                smallText.setTextAlign(Paint.Align.LEFT);
            }
        }

        // Vpravo nahore v jedne rade: Wi-Fi a baterie jako sklenene pilulky, svisle na stredu cislic hodin.
        final String pct = batteryLevel >= 0 ? batteryLevel + " %" : "–";
        final int bolts = charging ? (fastCharging ? 2 : 1) : 0;
        final float pp = dp(PILL_PAD), dot = dp(8), inner = dp(Glass.GAP_S);
        labelText.setColor(Palette.TEXT);
        final float pw = labelText.measureText(pct);
        final float bw = pp + dot + inner + pw + (bolts > 0 ? dp(6) + bolts * dp(9) - dp(1) : 0) + pp;
        final String wl = wifiOn ? "Wi-Fi" : "Offline";
        final float wifiIcon = dp(18);
        final float ww = pp + wifiIcon + inner + labelText.measureText(wl) + pp;
        final float ph = dp(PILL_H), top = timeTop + digitH / 2f - ph / 2f;
        final Paint.FontMetrics fm = labelText.getFontMetrics();
        final float base = top + ph / 2f - (fm.ascent + fm.descent) / 2f;

        // Baterie: barva stavu je v tecce (perla / jantar / magenta), text zustava svetly.
        tmp.set(w - pad - bw, top, w - pad, top + ph);
        final float batLeft = tmp.left;
        pillGlass[0].draw(c, tmp, ph / 2f, GlassSurface.TILE, d);
        final int bc = Palette.battery(batteryLevel);
        fill.setShader(null);
        fill.setColor(bc);
        fill.setShadowLayer(dp(4), 0, 0, Palette.alpha(bc, 0.8f));
        c.drawCircle(tmp.left + pp + dot / 2f, tmp.centerY(), dot / 2f, fill);
        fill.clearShadowLayer();
        c.drawText(pct, tmp.left + pp + dot + inner, base, labelText);
        float x = tmp.left + pp + dot + inner + pw + dp(6);
        if (bolts > 0) {
            fill.setShader(null);
            fill.setColor(Palette.TEXT);
            for (int i = 0; i < bolts; i++) {
                c.save();
                c.translate(x, tmp.centerY() - dp(6));
                c.scale(dp(8) / 14f, dp(12) / 20f);
                c.translate(-4f, -2f);
                c.drawPath(boltPath, fill);
                c.restore();
                x += dp(9);
            }
        }
        // Wi-Fi: ikona podle sily signalu, vlevo od baterie.
        tmp.set(batLeft - inner - ww, top, batLeft - inner, top + ph);
        pillGlass[1].draw(c, tmp, ph / 2f, GlassSurface.TILE, d);
        final int wc = wifiOn ? Palette.TEXT : Palette.text3();
        Icons.draw(c, Icons.wifi(wifiOn ? wifiBars : 0), tmp.left + pp + wifiIcon / 2f, tmp.centerY(),
                wifiIcon, wc, dp(1.6f), 1f, icon);
        labelText.setColor(wifiOn ? Palette.TEXT : Palette.text2());
        c.drawText(wl, tmp.left + pp + wifiIcon + inner, base, labelText);
    }

    /**
     * Hover jako sklo, ne jako neon: dlazdice se zesvetli, zjasni se okraj
     * a pod laserem je mekke svetlo (sleduje ukazatel). Zadna barevna zare.
     */
    private void drawHoverLight(Canvas c, int el, float l, float t, float r, float b, float radius, float hv,
                                float px, float py) {
        if (hv <= 0.01f) return;
        glass[el].drawHover(c, l, t, r, b, radius, hv, d);
        if (el != hovered || pointerX < 0) return;
        final float lx = clamp(px, l, r), ly = clamp(py, t, b);
        final float rad = Math.max(r - l, b - t) * 0.9f;
        if (spot == null || Math.abs(spotX - lx) > 0.5f || Math.abs(spotY - ly) > 0.5f || Math.abs(spotR - rad) > 0.5f) {
            spot = new RadialGradient(lx, ly, rad, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            spotX = lx;
            spotY = ly;
            spotR = rad;
        }
        fill.setShader(spot);
        fill.setAlpha(Math.round(255 * clamp(hv, 0f, 1f)));
        c.drawRoundRect(l, t, r, b, radius, radius, fill);
        fill.setShader(null);
        fill.setAlpha(255);
    }

    private void drawSlider(Canvas c, int el, long now, float value, String label, boolean enabled) {
        final RectF r = rects[el];
        final float a = appear(el, now);
        if (a <= 0.004f) return;
        final float hv = clamp(hover[el].get(now), 0f, 1.2f);
        final float ac = clamp(a, 0f, 1f);
        c.save();
        c.translate(0, (1f - a) * dp(12));
        final float grow = dp(1.5f) * hv;
        tmp.set(r.left - grow, r.top - grow, r.right + grow, r.bottom + grow);
        final float rr = tmp.height() / 2f;
        final int alpha = Math.round(255 * ac);
        // Stopa = sklenena dlazdice, pri hoveru se zesvetli.
        glass[el].draw(c, tmp.left, tmp.top, tmp.right, tmp.bottom, rr, GlassSurface.TILE, d, ac);
        drawHoverLight(c, el, tmp.left, tmp.top, tmp.right, tmp.bottom, rr, clamp(hv, 0f, 1f) * ac,
                pointerX, pointerY - (1f - a) * dp(12));
        // Perlova vypln - zaoblena, oriznuta stopou.
        final float v = clamp(value, 0f, 1f);
        final float fw = Math.max(v > 0.004f ? tmp.height() : 0f, tmp.width() * v);
        if (fw > 0f) {
            c.save();
            path.reset();
            path.addRoundRect(tmp, rr, rr, Path.Direction.CW);
            c.clipPath(path);
            fill.setShader(pearlShader(tmp.left, tmp.top, tmp.left + fw, tmp.bottom));
            fill.setAlpha(alpha);
            c.drawRoundRect(tmp.left, tmp.top, tmp.left + fw, tmp.bottom, rr, rr, fill);
            fill.setShader(null);
            fill.setAlpha(255);
            c.restore();
        }
        if (!enabled) {
            // Bez povoleni: kobaltovy obrys = "klepni pro povoleni".
            stroke.setShader(null);
            stroke.setStrokeWidth(dp(1.2f));
            stroke.setColor(Palette.alpha(Palette.COBALT_LIGHT, 0.8f * ac));
            c.drawRoundRect(tmp.left + dp(0.6f), tmp.top + dp(0.6f), tmp.right - dp(0.6f), tmp.bottom - dp(0.6f),
                    rr, rr, stroke);
        }

        // Ikona vlevo ve ctverci o strane vysky posuvniku (stejny okraj zleva i shora),
        // tmava, kdyz je pod ni perlova vypln.
        final float ix = tmp.left + tmp.height() / 2f, iy = tmp.centerY();
        final boolean onFill = fw > tmp.height() * 0.8f;
        final int ink = Palette.alpha(Palette.VOID, ac), light = Palette.alpha(Palette.TEXT, ac);
        Icons.draw(c, el == EL_BRIGHT ? Icons.SUN : Icons.SPEAKER, ix, iy, dp(20), onFill ? ink : light,
                dp(1.8f), v, icon);
        final Paint.FontMetrics fm = labelText.getFontMetrics();
        final float base = iy - (fm.ascent + fm.descent) / 2f;
        final float lx = tmp.left + tmp.height();
        final boolean labelOnFill = fw > lx - tmp.left + labelText.measureText(label) + dp(4);
        labelText.setColor(labelOnFill ? ink : light);
        c.drawText(label, lx, base, labelText);
        if (enabled) {
            final String pct = Math.round(v * 100) + " %";
            final float pw = labelText.measureText(pct);
            final float px = tmp.right - dp(16) - pw;
            final boolean pctOnFill = fw > px - tmp.left + pw;
            labelText.setColor(pctOnFill ? ink : Palette.alpha(Palette.text2(), ac));
            c.drawText(pct, px, base, labelText);
        }
        c.restore();
    }

    /** Perlovy prechod (temer bila s nadechem do fialova a modra) pro vyplne a hlavni tlacitka. */
    private Shader pearlShader(float l, float t, float r, float b) {
        return new LinearGradient(l, t, r, b, new int[]{Palette.PEARL, Palette.PEARL_LILAC, Palette.PEARL_BLUE},
                null, Shader.TileMode.CLAMP);
    }

    private void drawTile(Canvas c, int i, long now) {
        final int el = EL_TILE0 + i;
        final RectF r = rects[el];
        final float a = appear(el, now);
        if (a <= 0.004f) return;
        final float hv = hover[el].get(now);
        final float hc = clamp(hv, 0f, 1f);
        final float ac = clamp(a, 0f, 1f);
        final float s = 1f + 0.03f * hv - (pressed == el ? 0.03f : 0f);
        c.save();
        c.translate(r.centerX(), r.centerY() + (1f - a) * dp(14));
        c.scale(s, s);
        final boolean button = i == T_NEO;
        final float w2 = r.width() / 2f, h2 = r.height() / 2f, rr = button ? h2 : Math.min(dp(Glass.R_TILE), h2);
        if (button) {
            // Tlacitko "Nastaveni Nea": perlova pilulka s tmavym textem (hlavni akce), hover = jasnejsi.
            fill.setShader(pearlShader(-w2, -h2, w2, h2));
            fill.setAlpha(Math.round(255 * ac));
            c.drawRoundRect(-w2, -h2, w2, h2, h2, h2, fill);
            fill.setShader(null);
            if (hc > 0.01f) {
                fill.setColor(Palette.alpha(0xFFFFFFFF, 0.55f * hc * ac));
                c.drawRoundRect(-w2, -h2, w2, h2, h2, h2, fill);
            }
            fill.setAlpha(255);
            final int ink = Palette.alpha(Glass.INK, ac);
            final String t = "Nastavení Nea";
            labelText.setColor(ink);
            final float tw = labelText.measureText(t);
            final float total = dp(20) + dp(10) + tw;
            Icons.draw(c, Icons.GEAR, -total / 2f + dp(10), 0, dp(20), ink, dp(1.8f), 1f, icon);
            final Paint.FontMetrics fm = labelText.getFontMetrics();
            c.drawText(t, -total / 2f + dp(30), -(fm.ascent + fm.descent) / 2f, labelText);
        } else {
            glass[el].draw(c, -w2, -h2, w2, h2, rr, GlassSurface.TILE, d, ac);
            // Svetlo pod laserem: souradnice View prepocitane do lokalnich (stred dlazdice).
            drawHoverLight(c, el, -w2, -h2, w2, h2, rr, hc * ac,
                    (pointerX - r.centerX()) / s, (pointerY - r.centerY() - (1f - a) * dp(14)) / s);
            // Dlazdice ve trech sloupcich: ikona nahore, nazev pod ni (cely blok na stredu).
            final int col = Palette.alpha(Palette.TEXT, ac);
            final Paint.FontMetrics fm = tileText.getFontMetrics();
            final float iconS = dp(22), gapY = dp(6), textH = fm.descent - fm.ascent;
            final float top = -(iconS + gapY + textH) / 2f;
            // Bezici streamovani: akcent (Palette.COBALT_LIGHT = pokrok/vyber) a nazev "Vysila".
            final boolean live = i == T_CAST && castActive;
            Icons.draw(c, TILE_ICONS[i], 0, top + iconS / 2f, iconS,
                    live ? Palette.alpha(Palette.COBALT_LIGHT, ac) : col, dp(1.8f), 1f, icon);
            tileText.setTextAlign(Paint.Align.CENTER);
            tileText.setColor(Palette.alpha(Palette.TEXT, 0.94f * ac));
            c.drawText(live ? "Vysílá" : TILE_LABELS[i], 0, top + iconS + gapY - fm.ascent, tileText);
            tileText.setTextAlign(Paint.Align.LEFT);
        }
        c.restore();
    }

    /** Karta "Naposledy hrano": banner, nazev, kdy a tlacitko Hrat. */
    private void drawLastPlayed(Canvas c, long now) {
        final RectF r = rects[EL_LAST];
        final float a = appear(EL_LAST, now);
        if (a <= 0.004f || r.isEmpty()) return;
        final float hv = clamp(hover[EL_LAST].get(now), 0f, 1f);
        final float ac = clamp(a, 0f, 1f);
        c.save();
        c.translate(0, (1f - a) * dp(12));
        final float rr = dp(Glass.R_TILE);
        glass[EL_LAST].draw(c, r.left, r.top, r.right, r.bottom, rr, GlassSurface.TILE, d, ac);
        drawHoverLight(c, EL_LAST, r.left, r.top, r.right, r.bottom, rr, hv * ac,
                pointerX, pointerY - (1f - a) * dp(12));
        // Banner hry (na sirku) vlevo - odsazeny INSET, zaobleni soustredne s kartou.
        final float in = dp(Glass.INSET), ri = dp(Glass.R_INNER);
        final float th = r.height() - 2 * in, tw = th * 1.6f;
        final float tl = r.left + in, tt = r.top + in;
        if (lastShader != null && lastArt != null) {
            lastMatrix.setScale(tw / lastArt.getWidth(), th / lastArt.getHeight());
            lastMatrix.postTranslate(tl, tt);
            lastShader.setLocalMatrix(lastMatrix);
            artPaint.setShader(lastShader);
            artPaint.setAlpha(Math.round(255 * ac));
            c.drawRoundRect(tl, tt, tl + tw, tt + th, ri, ri, artPaint);
            artPaint.setShader(null);
        } else {
            fill.setColor(Palette.alpha(0x33FFFFFF, ac));
            c.drawRoundRect(tl, tt, tl + tw, tt + th, ri, ri, fill);
        }
        // Tlacitko vpravo: perlova pilulka s tmavym textem, od praveho okraje stejne jako shora.
        final float bh = dp(CARD_BTN_H), bm = (r.height() - bh) / 2f;
        final float bw = labelText.measureText(lastButton) + dp(32);
        final float bl = r.right - bm - bw, bt = r.centerY() - bh / 2f;
        fill.setShader(pearlShader(bl, bt, bl + bw, bt + bh));
        fill.setAlpha(Math.round(255 * ac));
        c.drawRoundRect(bl, bt, bl + bw, bt + bh, bh / 2f, bh / 2f, fill);
        fill.setShader(null);
        if (hv > 0.01f) {
            fill.setColor(Palette.alpha(0xFFFFFFFF, 0.55f * hv * ac));
            c.drawRoundRect(bl, bt, bl + bw, bt + bh, bh / 2f, bh / 2f, fill);
        }
        fill.setAlpha(255);
        final Paint.FontMetrics fm = labelText.getFontMetrics();
        labelText.setColor(Palette.alpha(Glass.INK, ac));
        c.drawText(lastButton, bl + dp(16), bt + bh / 2f - (fm.ascent + fm.descent) / 2f, labelText);
        // Texty mezi bannerem a tlacitkem.
        final float tx = tl + tw + dp(Glass.GAP);
        final float maxW = bl - dp(Glass.GAP) - tx;
        smallText.setColor(Palette.alpha(Palette.text2(), ac));
        c.drawText(android.text.TextUtils.ellipsize(lastSub, smallText, maxW,
                android.text.TextUtils.TruncateAt.END).toString(), tx, r.centerY() - dp(6), smallText);
        labelText.setColor(Palette.alpha(Palette.TEXT, ac));
        c.drawText(android.text.TextUtils.ellipsize(lastTitle, labelText, maxW,
                android.text.TextUtils.TruncateAt.END).toString(), tx, r.centerY() + dp(14), labelText);
        c.restore();
    }

    // =========================================================================
    // Vstup
    // =========================================================================

    private int elementAt(float x, float y) {
        for (int i = 0; i < EL_COUNT; i++) {
            if (rects[i].isEmpty()) continue;
            tmp.set(rects[i]);
            tmp.inset(-dp(3), -dp(3));
            if (tmp.contains(x, y)) return i;
        }
        return -1;
    }

    private void setHovered(int el) {
        if (el == hovered) return;
        final long now = System.nanoTime();
        if (hovered >= 0) hover[hovered].set(0f, now);
        hovered = el;
        if (el >= 0) hover[el].set(1f, now);
        invalidate();
    }

    @Override
    public boolean onHoverEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                pointerX = e.getX();
                pointerY = e.getY();
                setHovered(elementAt(pointerX, pointerY));
                if (hovered >= 0) invalidate(); // svetlo pod laserem jde za ukazatelem
                break;
            case MotionEvent.ACTION_HOVER_EXIT:
                pointerX = pointerY = -1;
                setHovered(-1);
                break;
            default:
                break;
        }
        return true;
    }

    /** Joystick nad posuvnikem = jemne pridavani/ubirani (5 % na krok). */
    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_SCROLL
                && (e.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0) {
            final int el = elementAt(e.getX(), e.getY());
            if (el != EL_BRIGHT && el != EL_VOLUME) return true;
            final float hs = e.getAxisValue(MotionEvent.AXIS_HSCROLL);
            final float vs = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            final float v = Math.abs(hs) >= Math.abs(vs) ? hs : vs;
            final long now = System.currentTimeMillis();
            if (Math.abs(v) < 0.2f || now - lastWheelMs < 70) return true;
            lastWheelMs = now;
            if (el == EL_BRIGHT) setBrightness(bright + Math.signum(v) * 0.05f);
            else setVolume(volume + Math.signum(v) / Math.max(volMax, 1));
            return true;
        }
        return super.onGenericMotionEvent(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        final float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = elementAt(x, y);
                setHovered(pressed);
                if ((pressed == EL_BRIGHT && brightOk) || pressed == EL_VOLUME) {
                    draggingSlider = true;
                    sliderTo(pressed, x);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (draggingSlider) sliderTo(pressed, x);
                else setHovered(elementAt(x, y));
                return true;
            case MotionEvent.ACTION_UP: {
                final int el = elementAt(x, y);
                if (draggingSlider) {
                    sliderTo(pressed, x);
                } else if (el == pressed && el >= 0) {
                    if (el == EL_BRIGHT && !brightOk) actions.requestBrightnessAccess();
                    else if (el == EL_LAST) {
                        if (lastApp != null) actions.launch(lastApp);
                    } else if (el >= EL_TILE0) actions.openTarget(el - EL_TILE0);
                }
                draggingSlider = false;
                pressed = -1;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                draggingSlider = false;
                pressed = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void sliderTo(int el, float x) {
        final RectF r = rects[el];
        final float f = clamp((x - r.left) / r.width(), 0f, 1f);
        if (el == EL_BRIGHT) setBrightness(f);
        else setVolume(f);
    }

    private void setBrightness(float f) {
        if (!brightOk) return;
        bright = clamp(f, 0f, 1f);
        try {
            final ContentResolver cr = getContext().getContentResolver();
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, Math.round(10 + bright * 245));
        } catch (Exception e) {
            brightOk = false;
        }
        brightShown.set(bright, System.nanoTime());
        invalidate();
    }

    private void setVolume(float f) {
        if (audio == null) return;
        final int steps = Math.round(clamp(f, 0f, 1f) * volMax);
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, steps, 0);
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) volMax;
        } catch (Exception e) {
            volume = steps / (float) volMax;
        }
        volumeShown.set(volume, System.nanoTime());
        invalidate();
    }

    /** Sklo prvku + zesvetleni pri hoveru (druha vrstva s pruhlednosti podle pruziny). */
    private static final class HoverGlass {
        private final GlassSurface base = new GlassSurface();
        private final GlassSurface lit = new GlassSurface();

        void draw(Canvas c, float l, float t, float r, float b, float radius, GlassSurface.Style s, float d, float a) {
            base.draw(c, l, t, r, b, radius, s, d, a);
        }

        void drawHover(Canvas c, float l, float t, float r, float b, float radius, float hv, float d) {
            lit.draw(c, l, t, r, b, radius, GlassSurface.TILE_HOVER, d, hv);
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
