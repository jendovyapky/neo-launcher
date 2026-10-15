package com.neolauncher.ui;

import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * Animovana hodnota s chovanim jako CSS transition: pri zmene cile se
 * plynule pokracuje z AKTUALNI hodnoty (zadne skoky, zadne callbacky).
 * <p>
 * Zamerne bez withEndAction()/listeneru - stav se vzdy dopocita z casu,
 * takze nic nemuze "zustat viset", kdyz se animace prerusi (viz CLAUDE.md,
 * omezeni #2).
 */
final class Eased {
    /** cubic-bezier(0.16, 1, 0.3, 1) - hlavni krivka z preview. */
    static final Interpolator NEO = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    static final Interpolator EASE_OUT = new PathInterpolator(0.25f, 0.1f, 0.25f, 1f);

    private final long durNs;
    private final Interpolator interp;
    private float from, to;
    private long start;

    Eased(float value, long durMs) {
        this(value, durMs, NEO);
    }

    Eased(float value, long durMs, Interpolator interp) {
        this.from = value;
        this.to = value;
        this.durNs = durMs * 1_000_000L;
        this.interp = interp;
    }

    float get(long now) {
        if (from == to) return to;
        long dt = now - start;
        if (dt >= durNs) {
            from = to;
            return to;
        }
        if (dt <= 0) return from;
        return from + (to - from) * interp.getInterpolation(dt / (float) durNs);
    }

    /** Novy cil; animuje se z aktualni hodnoty. Stejny cil animaci nerestartuje. */
    void set(float target, long now) {
        if (target == to) return;
        from = get(now);
        to = target;
        start = now;
    }

    void snap(float value) {
        from = value;
        to = value;
    }

    float target() {
        return to;
    }

    boolean active(long now) {
        return from != to && now - start < durNs;
    }
}
