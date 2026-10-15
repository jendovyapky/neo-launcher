package com.neolauncher.ui;

import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.view.View;

/**
 * Kdyz se otevre dialog, launcher za nim "ustoupi do hloubky" (jako ve
 * visionOS): plynule se rozmaze a o kousek zmensi. Jeden efekt na cely View
 * (ne na karty), rizeny pruzinou - stav jen z casu.
 */
final class ModalDepth {
    private final View view;
    private final float d;
    private final Spring amount = new Spring(0, 0.40f, 1f, 0.002f);
    private final Runnable frame = this::apply;
    private boolean posted;

    ModalDepth(View view, float density) {
        this.view = view;
        this.d = density;
    }

    void set(boolean on) {
        amount.set(on ? 1f : 0f, System.nanoTime());
        apply();
    }

    private void apply() {
        posted = false;
        final long now = System.nanoTime();
        final float m = Math.max(0f, Math.min(1f, amount.get(now)));
        // Rozmazani + zivejsi barvy (sytost): za sklem dialogu pak prosvita barva her,
        // misto "zakaleneho" sedeho zavoje. Scena se neztmavuje, jen ustoupi.
        final float r = 10f * d * m;
        if (r > 0.5f) {
            final android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
            cm.setSaturation(1f + 0.3f * m);
            view.setRenderEffect(RenderEffect.createColorFilterEffect(
                    new android.graphics.ColorMatrixColorFilter(cm),
                    RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)));
        } else {
            view.setRenderEffect(null);
        }
        final float s = 1f - 0.035f * m;
        view.setScaleX(s);
        view.setScaleY(s);
        view.setAlpha(1f - 0.04f * m);
        if (amount.active(now) && !posted) {
            posted = true;
            view.postOnAnimation(frame);
        }
    }
}
