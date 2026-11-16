package com.neolauncher.ui;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Jeden recept na sklo pro vsechno (panel, ornament, lista, rychle menu,
 * dialogy, dlazdice). Co dela sklo "sklem":
 * <ol>
 * <li>vypln je pruhledna a tonovana (nahore o chlup svetlejsi) - za ni je
 *     rozmazane pozadi, takze prosvita barva;</li>
 * <li>odlesk: mekke svetlo vlevo nahore, jako by svetlo dopadalo shora;</li>
 * <li>okraj neni jednobarevny - nahore a vlevo jasny, po stranach slabne,
 *     dole zase jemne svetly (odraz). Jednobarevny okraj pusobi jako plast.</li>
 * </ol>
 * Shadery se drzi v cache a prepocitaji jen pri zmene velikosti nebo stylu
 * (zadne alokace v kazdem snimku). Zadny blur ani vrstva - jen gradienty.
 */
final class GlassSurface {

    /** Vzhled jedne plochy (barvy s pruhlednosti). */
    static final class Style {
        final int fillTop, fillBottom;
        /** Alfa odlesku vlevo nahore (0 = bez odlesku). */
        final int sheen;
        final int rimTop, rimMid, rimBottom;
        final float rimDp;

        Style(int fillTop, int fillBottom, int sheen, int rimTop, int rimMid, int rimBottom, float rimDp) {
            this.fillTop = fillTop;
            this.fillBottom = fillBottom;
            this.sheen = sheen;
            this.rimTop = rimTop;
            this.rimMid = rimMid;
            this.rimBottom = rimBottom;
            this.rimDp = rimDp;
        }

        /** Stejny styl s jinou pruhlednosti vyplne (kryti skla z nastaveni). */
        Style withFillAlpha(float a) {
            return new Style(Palette.alpha(fillTop, a), Palette.alpha(fillBottom, a), sheen,
                    rimTop, rimMid, rimBottom, rimDp);
        }
    }

    /* Tmave sklo ("Void"): chladny temer cerny ton. */
    static final Style PANEL_DARK = new Style(0xC4171B24, 0xD00A0C11, 0x1C, 0x99FFFFFF, 0x1AFFFFFF, 0x3DFFFFFF, 1.2f);
    /* Svetle sklo ("Titanium Fog"): sedomodre, krycejsi nez tmave - bily text musi zustat citelny. */
    static final Style PANEL_LIGHT = new Style(0xC7868F9C, 0xD1646C79, 0x2E, 0xB3FFFFFF, 0x2EFFFFFF, 0x52FFFFFF, 1.2f);
    /* Plovouci prvky nad obsahem (ornament, lista) - krycejsi, at jsou citelne nad kartami. */
    static final Style CHROME_DARK = new Style(0xDE171B24, 0xE60A0C11, 0x1C, 0x99FFFFFF, 0x1AFFFFFF, 0x3DFFFFFF, 1f);
    static final Style CHROME_LIGHT = new Style(0xD28A93A0, 0xDC6B7380, 0x2E, 0xB3FFFFFF, 0x2EFFFFFF, 0x52FFFFFF, 1f);
    /* Horni bublina (ornament): pruhlednejsi - pod ni je rozmazany a u okraje lomeny obsah (LiquidGlass). */
    static final Style LIQUID_DARK = new Style(0xA8171B24, 0xB80A0C11, 0x1C, 0xB3FFFFFF, 0x1FFFFFFF, 0x4DFFFFFF, 1f);
    static final Style LIQUID_LIGHT = new Style(0xB88A93A0, 0xC46B7380, 0x2E, 0xC7FFFFFF, 0x33FFFFFF, 0x5CFFFFFF, 1f);
    /* Dlazdice uvnitr skla (svetlejsi vrstva). */
    static final Style TILE = new Style(0x1AFFFFFF, 0x0AFFFFFF, 0, 0x47FFFFFF, 0x12FFFFFF, 0x1FFFFFFF, 1f);
    static final Style TILE_HOVER = new Style(0x33FFFFFF, 0x1AFFFFFF, 0, 0x8CFFFFFF, 0x2EFFFFFF, 0x40FFFFFF, 1f);
    static final Style TILE_PRESSED = new Style(0x40FFFFFF, 0x29FFFFFF, 0, 0x99FFFFFF, 0x3DFFFFFF, 0x4DFFFFFF, 1f);

    static Style panel() {
        return Glass.isLight() ? PANEL_LIGHT : PANEL_DARK;
    }

    static Style chrome() {
        return Glass.isLight() ? CHROME_LIGHT : CHROME_DARK;
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Style style;
    private float w = -1, h = -1;
    private Shader fillShader, sheenShader, rimShader;

    GlassSurface() {
        rim.setStyle(Paint.Style.STROKE);
    }

    /** Nakresli sklo do obdelniku r se zaoblenim radius (px). */
    void draw(Canvas c, RectF r, float radius, Style s, float density) {
        draw(c, r.left, r.top, r.right, r.bottom, radius, s, density, 1f);
    }

    /** @param alpha celkova pruhlednost (nastup/zmizeni) */
    void draw(Canvas c, float l, float t, float rr, float b, float radius, Style s, float density, float alpha) {
        final float ww = rr - l, hh = b - t;
        if (ww <= 0 || hh <= 0 || alpha <= 0.004f) return;
        if (s != style || Math.abs(ww - w) > 0.5f || Math.abs(hh - h) > 0.5f) {
            style = s;
            w = ww;
            h = hh;
            fillShader = new LinearGradient(0, 0, 0, hh, s.fillTop, s.fillBottom, Shader.TileMode.CLAMP);
            sheenShader = s.sheen > 0 ? new RadialGradient(ww * 0.18f, 0, Math.max(ww, hh) * 0.75f,
                    (s.sheen << 24) | 0xFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP) : null;
            // Okraj: diagonalne z leveho horniho rohu - jasny, pak slabne, dole jemny odraz.
            rimShader = new LinearGradient(0, 0, ww * 0.35f, hh,
                    new int[]{s.rimTop, s.rimMid, s.rimMid, s.rimBottom},
                    new float[]{0f, 0.38f, 0.72f, 1f}, Shader.TileMode.CLAMP);
        }
        final int a = Math.round(255 * Math.min(1f, alpha));
        c.save();
        c.translate(l, t);
        fill.setShader(fillShader);
        fill.setAlpha(a);
        c.drawRoundRect(0, 0, ww, hh, radius, radius, fill);
        if (sheenShader != null) {
            fill.setShader(sheenShader);
            c.drawRoundRect(0, 0, ww, hh, radius, radius, fill);
        }
        fill.setShader(null);
        final float sw = s.rimDp * density;
        rim.setStrokeWidth(sw);
        rim.setShader(rimShader);
        rim.setAlpha(a);
        c.drawRoundRect(sw / 2f, sw / 2f, ww - sw / 2f, hh - sw / 2f,
                Math.max(0f, radius - sw / 2f), Math.max(0f, radius - sw / 2f), rim);
        c.restore();
    }
}
