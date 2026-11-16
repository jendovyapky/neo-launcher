package com.neolauncher.ui;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Predpocitany rozmazany stin karty (jako CSS box-shadow). Vyrobi se jednou
 * pri zmene velikosti karet (softwarove, mimo GPU) a pak se jen kresli
 * jako obycejna bitmapa - zadny blur za behu, zadne offscreen vrstvy.
 */
final class ShadowSprite {
    final Bitmap mask;
    final float margin;
    private final RectF dst = new RectF();

    private ShadowSprite(Bitmap mask, float margin) {
        this.mask = mask;
        this.margin = margin;
    }

    /**
     * @param w     sirka karty v px
     * @param h     vyska karty v px
     * @param r     radius rohu v px
     * @param sigma rozmazani v px (CSS box-shadow blur / 2)
     */
    static ShadowSprite create(float w, float h, float r, float sigma) {
        float m = (float) Math.ceil(sigma * 3f);
        int bw = Math.max(1, Math.round(w + 2 * m));
        int bh = Math.max(1, Math.round(h + 2 * m));
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000);
        float radius = Math.max(0.5f, (sigma - 0.5f) / 0.57735f);
        p.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL));
        c.drawRoundRect(m, m, m + w, m + h, r, r, p);
        return new ShadowSprite(b, m);
    }

    /** Nakresli stin pod kartou s lokalnimi souradnicemi [l,t,r,b], posunuty o dy. */
    void draw(Canvas c, float l, float t, float r, float b, float dy, Paint paint) {
        dst.set(l - margin, t - margin + dy, r + margin, b + margin + dy);
        c.drawBitmap(mask, null, dst, paint);
    }
}
