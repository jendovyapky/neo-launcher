package com.neolauncher.ui;

import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Postupny nastup prvku (kaskada jako v iOS/visionOS): kazdy prvek o chvilku
 * pozdeji vyjede zdola a objevi se. Jen vizual (alfa + posun), zadny stav -
 * prubeh se pocita z casu kriticky tlumenou pruzinou.
 */
final class Cascade {
    private static final float STAGGER_MS = 28f;
    private static final float RESPONSE_S = 0.42f;

    private final View host;
    private final List<View> views = new ArrayList<>();
    private final float rise;
    private long startNs;
    private final Runnable frame = this::step;

    private Cascade(View host, float risePx) {
        this.host = host;
        this.rise = risePx;
    }

    /** Spusti kaskadu na prvcich (v poradi, v jakem maji nastoupit). */
    static void play(View host, List<View> views, float risePx) {
        Cascade c = new Cascade(host, risePx);
        c.views.addAll(views);
        c.startNs = System.nanoTime();
        for (View v : views) {
            v.setAlpha(0f);
            v.setTranslationY(risePx);
        }
        host.postOnAnimation(c.frame);
    }

    private void step() {
        final float t = (System.nanoTime() - startNs) / 1e6f;
        boolean running = false;
        for (int i = 0; i < views.size(); i++) {
            final float ti = t - i * STAGGER_MS;
            float p = 0f;
            if (ti > 0f) {
                final float w = (float) (2 * Math.PI / RESPONSE_S) * ti / 1000f;
                p = 1f - (1f + w) * (float) Math.exp(-w);
            }
            if (p < 0.999f) running = true;
            else p = 1f;
            final View v = views.get(i);
            v.setAlpha(p);
            v.setTranslationY((1f - p) * rise);
        }
        if (running && host.isAttachedToWindow()) host.postOnAnimation(frame);
    }
}
