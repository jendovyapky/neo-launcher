package com.neolauncher.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/**
 * Sklo (GlassSurface) jako Drawable pro obycejne Android Views: dialogy,
 * dlazdice nastaveni, widgety. Stav "hovered" (laser) / "pressed" jen
 * zesvetli - zadna barevna zare.
 */
final class GlassDrawable extends Drawable {
    private final GlassSurface surface = new GlassSurface();
    private final float radius, density;
    private final GlassSurface.Style normal, hover, pressed;
    private GlassSurface.Style current;
    private int alpha = 255;

    GlassDrawable(float radiusPx, float density, GlassSurface.Style normal,
                  GlassSurface.Style hover, GlassSurface.Style pressed) {
        this.radius = radiusPx;
        this.density = density;
        this.normal = normal;
        this.hover = hover;
        this.pressed = pressed;
        this.current = normal;
    }

    @Override
    public void draw(Canvas c) {
        final android.graphics.Rect b = getBounds();
        surface.draw(c, b.left, b.top, b.right, b.bottom, Math.min(radius, b.height() / 2f), current,
                density, alpha / 255f);
    }

    @Override
    public boolean isStateful() {
        return hover != null || pressed != null;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean isPressed = false, isHovered = false;
        for (int s : state) {
            if (s == android.R.attr.state_pressed) isPressed = true;
            else if (s == android.R.attr.state_hovered) isHovered = true;
        }
        final GlassSurface.Style next = isPressed && pressed != null ? pressed
                : isHovered && hover != null ? hover : normal;
        if (next == current) return false;
        current = next;
        invalidateSelf();
        return true;
    }

    @Override
    public void getOutline(Outline outline) {
        outline.setRoundRect(getBounds(), Math.min(radius, getBounds().height() / 2f));
    }

    @Override
    public void setAlpha(int a) {
        alpha = a;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
