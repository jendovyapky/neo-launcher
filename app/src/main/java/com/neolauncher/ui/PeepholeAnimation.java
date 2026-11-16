package com.neolauncher.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;

/**
 * Animace spusteni "kukatko" - sdilena mrizkou i karuselem.
 * <p>
 * Faze (ms pri meritku animaci 1):
 * <ol>
 *   <li>0-130: stisk - karta se zmensi (jako tlacitko pod prstem)</li>
 *   <li>130-450: kukatko - pruzny navrat, tma se stahne do kruhu, mekky okraj, rybi oko</li>
 *   <li>450-850: prulet - kruh se roztahne, banner se priblizi pres cele okno,
 *       "zoom blur" do stran (vrstvene zvetsene kopie - funguje i bez shaderu)</li>
 *   <li>850-1150: bud setmeni do cerna a launcher se zavre (vychozi), nebo
 *       rozplynuti zpet do launcheru</li>
 * </ol>
 * Stav je odvozeny jen z casu; spusteni aplikace a zavreni launcheru ridi
 * casovace ve View (ne vykreslovani - to se pri skryti okna zastavi).
 */
final class PeepholeAnimation {
    static final long DURATION_MS = 1150;
    static final float FIRE_AT = 0.48f;
    /** Kdyz se launcher po spusteni nezavrel (napr. chyba), po teto dobe se zase ukaze. */
    private static final long HOLD_DARK_MAX_MS = 2500;
    private static final int BLUR_COPIES = 7;

    private final float d;
    private final LaunchLens lens = new LaunchLens();
    private final RenderNode node = new RenderNode("neo-launch");
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint sub = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Path hole = new Path();
    private final Path clip = new Path();

    private boolean running;
    private long startNs, durNs;
    private float cx0, cy0, cardW, cardH, z0, corner;
    private Shader artShader, shade;
    private String label = "";
    private boolean closeAfter;

    PeepholeAnimation(float density) {
        d = density;
        stroke.setStyle(Paint.Style.STROKE);
        rim.setStyle(Paint.Style.STROKE);
        title.setTypeface(Typeface.create(Typeface.SANS_SERIF, 700, false));
        title.setTextSize(23 * d);
        title.setTextAlign(Paint.Align.CENTER);
        sub.setTypeface(Typeface.create(Typeface.SANS_SERIF, 600, false));
        sub.setTextSize(15 * d);
        sub.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * @param artShader obrazek v lokalnich souradnicich karty se stredem v (0,0) a velikosti cardW x cardH
     * @param z0        aktualni zvetseni karty na obrazovce
     */
    void start(long now, float animScale, float cx, float cy, float cardW, float cardH, float z0,
               float corner, Shader artShader, String label, boolean closeAfter) {
        running = true;
        startNs = now;
        durNs = (long) (DURATION_MS * animScale * 1_000_000L);
        this.cx0 = cx;
        this.cy0 = cy;
        this.cardW = cardW;
        this.cardH = cardH;
        this.z0 = z0;
        this.corner = corner;
        this.artShader = artShader;
        this.label = label != null ? label : "";
        this.closeAfter = closeAfter;
        shade = new LinearGradient(0, cardH / 2f - 44 * d, 0, cardH / 2f,
                0x00000000, 0x8C000000, Shader.TileMode.CLAMP);
    }

    boolean isRunning(long now) {
        if (!running) return false;
        final float t = durNs <= 0 ? 1f : (now - startNs) / (float) durNs;
        if (t < 1f) return true;
        if (closeAfter && (now - startNs - durNs) < HOLD_DARK_MAX_MS * 1_000_000L) return true;
        reset();
        return false;
    }

    void reset() {
        running = false;
        artShader = null;
        node.discardDisplayList();
    }

    void discard() {
        node.discardDisplayList();
    }

    void draw(Canvas c, long now, float w, float h) {
        if (!isRunning(now)) return;
        final float t = Math.min(1f, durNs <= 0 ? 1f : (now - startNs) / (float) durNs);
        final float ms = t * DURATION_MS;
        final float diag = (float) Math.hypot(w, h);
        final float zPress = z0 * 0.9f;
        final float zPeep = z0;
        // Kruh kukatka se vejde do kratsi strany karty (vyska v mrizce, sirka
        // v karuselu) -> obrazek ho od zacatku cely pokryva.
        final float side = Math.min(cardW, cardH);
        final float peepR = 0.49f * side * zPeep;
        final float holeEnd = diag * 0.52f;

        float holeR, darkA, z, cx, cy, chrome, fish, zoomBlur, edgeA, rimA, textA;
        float globalA = 1f, blackA = 0f;
        if (ms < 130f) {
            final float p = easeOutCubic(ms / 130f);
            z = lerp(z0, zPress, p);
            holeR = diag;
            darkA = 0.25f * p;
            cx = cx0;
            cy = cy0;
            chrome = 1f;
            fish = 0f;
            zoomBlur = 0f;
            edgeA = 0f;
            rimA = 0f;
            textA = 0f;
        } else if (ms < 450f) {
            final float u = (ms - 130f) / 320f;
            final float e = easeInOutCubic(u);
            z = lerp(zPress, zPeep, easeOutBack(u));
            holeR = lerp(diag, peepR, easeOutCubic(u));
            darkA = lerp(0.25f, 0.92f, e);
            cx = cx0;
            cy = cy0;
            chrome = 1f - e;
            fish = e;
            zoomBlur = 0f;
            edgeA = e;
            rimA = e;
            textA = e;
        } else if (ms < 850f) {
            final float v = (ms - 450f) / 400f;
            final float e = easeInOutCubic(v);
            holeR = lerp(peepR, holeEnd, e);
            z = zPeep;
            darkA = 0.92f;
            cx = lerp(cx0, w / 2f, e);
            cy = lerp(cy0, h / 2f, e);
            chrome = 0f;
            fish = 1f - e;
            zoomBlur = (float) Math.sin(Math.PI * v);
            edgeA = 1f - e;
            rimA = 1f - e;
            textA = Math.max(0f, 1f - v * 3f);
        } else {
            final float u = (ms - 850f) / 300f;
            holeR = holeEnd;
            z = zPeep;
            darkA = 0.92f;
            cx = w / 2f;
            cy = h / 2f;
            chrome = 0f;
            fish = 0f;
            zoomBlur = 0f;
            edgeA = 0f;
            rimA = 0f;
            textA = 0f;
            if (closeAfter) {
                // Setmit do cerna - launcher se hned potom zavre, pod animaci uz neprosvita.
                blackA = easeInOutCubic(u);
            } else {
                globalA = 1f - easeInOutCubic(u);
            }
        }
        // Pri pruletu musi obrazek kruh vzdy cely pokryt (na zacatku 2.04*peepR/side == zPeep).
        if (ms >= 450f) z = Math.max(z, 2.04f * holeR / side);

        // 1) Tma mimo kruh.
        if (darkA > 0.004f && holeR < diag) {
            hole.reset();
            hole.setFillType(Path.FillType.EVEN_ODD);
            hole.addRect(0, 0, w, h, Path.Direction.CW);
            hole.addCircle(cx, cy, holeR, Path.Direction.CW);
            fill.setShader(null);
            fill.setColor(Color.argb(Math.round(255 * darkA * globalA), 3, 5, 9));
            c.drawPath(hole, fill);
        }

        // 2) Banner v kruhu (+ zoom blur). S cockou (Android 13+) navic rybi oko.
        final float r = Math.min(holeR, diag);
        if (lens.available() && c.isHardwareAccelerated()) {
            final int iw = Math.max(1, (int) w), ih = Math.max(1, (int) h);
            node.setPosition(0, 0, iw, ih);
            RecordingCanvas rc = node.beginRecording(iw, ih);
            try {
                drawContent(rc, cx, cy, r, z, chrome, zoomBlur);
            } finally {
                node.endRecording();
            }
            node.setRenderEffect(lens.effect(cx, cy, r, 1f, fish, 0f, edgeA * 0.6f, 0f));
            node.setAlpha(globalA);
            c.drawRenderNode(node);
        } else if (globalA > 0.004f) {
            if (globalA < 0.999f) c.saveLayerAlpha(0, 0, w, h, Math.round(255 * globalA));
            drawContent(c, cx, cy, r, z, chrome, zoomBlur);
            if (globalA < 0.999f) c.restore();
        }

        // 3) Mekky okraj: uvnitr kruhu smerem k obrube postupne tmavne.
        if (edgeA > 0.004f && holeR < diag) {
            final int a = Math.round(255 * darkA * edgeA * globalA);
            edge.setShader(new RadialGradient(cx, cy, Math.max(1f, r),
                    new int[]{0x00000000, 0x00000000, Color.argb(a, 3, 5, 9)},
                    new float[]{0f, 0.72f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, edge);
            edge.setShader(null);
        }

        // 4) Obruba kukatka: jemny bily okraj s kobaltovou zari.
        if (rimA > 0.004f) {
            rim.setStrokeWidth(2 * d);
            rim.setColor(Color.argb(Math.round(90 * rimA * globalA), 255, 255, 255));
            rim.setShadowLayer(24 * d, 0, 0, Palette.alpha(Palette.COBALT, 0.6f * rimA * globalA));
            c.drawCircle(cx, cy, holeR, rim);
            rim.clearShadowLayer();
        }

        // 5) Nazev hry pod kukatkem.
        if (textA > 0.004f) {
            final float ty = cy + (ms < 450f ? peepR : holeR) + 46 * d;
            title.setColor(Color.argb(Math.round(255 * textA * globalA), 255, 255, 255));
            c.drawText(label, cx, ty, title);
            sub.setColor(Palette.alpha(Palette.COBALT_LIGHT, textA * globalA));
            c.drawText("Spouštím…", cx, ty + 24 * d, sub);
        }

        // 6) Setmeni pred zavrenim launcheru.
        if (blackA > 0.004f) {
            fill.setShader(null);
            fill.setColor(Color.argb(Math.round(255 * blackA), 0, 0, 0));
            c.drawRect(0, 0, w, h, fill);
        }
    }

    /** Karta/banner oriznuty do kruhu, pri pruletu s vrstvenymi zvetsenymi kopiemi (zoom blur). */
    private void drawContent(Canvas c, float cx, float cy, float r, float z, float chrome, float zoomBlur) {
        if (artShader == null) return;
        c.save();
        clip.reset();
        clip.addCircle(cx, cy, r, Path.Direction.CW);
        c.clipPath(clip);
        drawCard(c, cx, cy, z, chrome, 255);
        if (zoomBlur > 0.01f) {
            // Kazda dalsi kopie je vetsi a pruhlednejsi -> smer od stredu se rozmaze
            // (u stredu skoro nic, u okraju nejvic) = "fast blur" pri pruletu.
            for (int i = 1; i <= BLUR_COPIES; i++) {
                final float f = i / (float) BLUR_COPIES;
                final float s = 1f + 0.22f * zoomBlur * f;
                final int a = Math.round(255 * 0.42f * zoomBlur * (1f - f * 0.85f));
                drawCard(c, cx, cy, z * s, 0f, a);
            }
        }
        c.restore();
    }

    private void drawCard(Canvas c, float cx, float cy, float z, float chrome, int alpha) {
        final float w2 = cardW / 2f, h2 = cardH / 2f;
        final float rr = corner * chrome;
        c.save();
        c.translate(cx, cy);
        c.scale(z, z);
        art.setShader(artShader);
        art.setAlpha(alpha);
        c.drawRoundRect(-w2, -h2, w2, h2, rr, rr, art);
        art.setShader(null);
        if (chrome > 0.004f) {
            fill.setShader(shade);
            fill.setAlpha(Math.round(255 * chrome));
            c.drawRoundRect(-w2, -h2, w2, h2, rr, rr, fill);
            fill.setShader(null);
            final float bw = d;
            stroke.setStrokeWidth(bw);
            stroke.setColor(Color.argb(Math.round(217 * chrome), 255, 255, 255));
            c.drawRoundRect(-w2 + bw / 2f, -h2 + bw / 2f, w2 - bw / 2f, h2 - bw / 2f,
                    Math.max(0f, rr - bw / 2f), Math.max(0f, rr - bw / 2f), stroke);
        }
        c.restore();
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static float easeOutCubic(float x) {
        float f = 1f - clamp(x, 0f, 1f);
        return 1f - f * f * f;
    }

    static float easeInOutCubic(float x) {
        float f = clamp(x, 0f, 1f);
        return f < 0.5f ? 4f * f * f * f : 1f - (float) Math.pow(-2f * f + 2f, 3) / 2f;
    }

    /** Mirny "pruzny" prekmit na konci (jako gumove tlacitko). */
    static float easeOutBack(float x) {
        final float f = clamp(x, 0f, 1f) - 1f;
        final float c1 = 1.4f;
        return 1f + (c1 + 1f) * f * f * f + c1 * f * f;
    }
}
