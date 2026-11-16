package com.neolauncher.ui;

/**
 * Fyzikalni pruzina (tlumeny harmonicky oscilator) jako u Apple (SwiftUI
 * {@code .spring(response:dampingFraction:)}, visionOS). Oproti {@link Eased}
 * si pri zmene cile zachova RYCHLOST - pohyb plynule navaze, i kdyz se cil
 * meni rychle za sebou (joystick, laser), a s tlumenim < 1 lehce prekmitne.
 * <p>
 * Stejna pravidla jako Eased (CLAUDE.md #2): hodnota se pocita v uzavrenem
 * tvaru jen z casu - zadne kroky po snimcich, zadne callbacky, nic nemuze
 * "zustat viset".
 */
final class Spring {
    /** Response (s) a tlumeni presetu - odpovidaji Apple .smooth/.snappy/.bouncy. */
    static final float[] SMOOTH = {0.50f, 1.00f};
    static final float[] SNAPPY = {0.40f, 0.85f};
    static final float[] BOUNCY = {0.45f, 0.70f};

    private final float omega;   // vlastni uhlova frekvence
    private final float zeta;    // pomerny utlum (0..1)
    private final float wd;      // tlumena frekvence
    private final float eps;     // presnost, pod kterou je pruzina "v klidu"
    private float target;
    private float x0, v0;        // vychylka od cile a rychlost (jednotek/s) v case start
    private long start;
    private long settleNs;

    Spring(float value, float[] preset) {
        this(value, preset[0], preset[1], 0.001f);
    }

    Spring(float value, float[] preset, float eps) {
        this(value, preset[0], preset[1], eps);
    }

    /**
     * @param response doba jednoho kmitu v sekundach (mensi = rychlejsi)
     * @param damping  1 = bez prekmitu, 0.7 = vyrazny "pruzny" dojezd
     * @param eps      presnost v jednotkach hodnoty (napr. 0.3 pro px)
     */
    Spring(float value, float response, float damping, float eps) {
        this.target = value;
        this.omega = (float) (2 * Math.PI / Math.max(0.05f, response));
        this.zeta = Math.max(0.05f, Math.min(1f, damping));
        this.wd = omega * (float) Math.sqrt(Math.max(0f, 1f - zeta * zeta));
        this.eps = Math.max(1e-5f, eps);
    }

    float get(long now) {
        if (!active(now)) return target;
        return target + disp((now - start) / 1e9f);
    }

    /** Rychlost v jednotkach za sekundu. */
    float velocity(long now) {
        if (!active(now)) return 0f;
        return vel((now - start) / 1e9f);
    }

    /** Novy cil; pokracuje z aktualni polohy i rychlosti. Stejny cil nic nemeni. */
    void set(float value, long now) {
        if (value == target) return;
        restart(value, get(now), velocity(now), now);
    }

    /** Novy cil s danou pocatecni rychlosti (napr. rychlost laseru po pusteni). */
    void setWithVelocity(float value, float velocity, long now) {
        restart(value, get(now), velocity, now);
    }

    void snap(float value) {
        target = value;
        x0 = 0f;
        v0 = 0f;
        settleNs = 0;
    }

    float target() {
        return target;
    }

    boolean active(long now) {
        return settleNs > 0 && now - start < settleNs;
    }

    private void restart(float value, float x, float v, long now) {
        target = value;
        x0 = x - value;
        v0 = v;
        start = now;
        // Doba, za kterou obalka kmitu klesne pod eps (+ rezerva pro kriticky utlum).
        final float a = zeta * omega;
        final float amp = Math.abs(x0) + Math.abs(v0 + a * x0) / Math.max(wd, omega * 0.2f);
        if (amp <= eps) {
            snap(value);
            return;
        }
        float t = (float) Math.log(amp / eps) / a;
        if (zeta > 0.97f) t += 2.5f / omega;
        settleNs = (long) (Math.min(4f, t) * 1e9f);
    }

    private float disp(float t) {
        final float a = zeta * omega;
        final float e = (float) Math.exp(-a * t);
        if (wd < 1e-3f) {
            return (x0 + (v0 + omega * x0) * t) * e;
        }
        final float b = (v0 + a * x0) / wd;
        return e * (x0 * (float) Math.cos(wd * t) + b * (float) Math.sin(wd * t));
    }

    private float vel(float t) {
        final float a = zeta * omega;
        final float e = (float) Math.exp(-a * t);
        if (wd < 1e-3f) {
            return e * (v0 - omega * (v0 + omega * x0) * t);
        }
        return e * (v0 * (float) Math.cos(wd * t)
                - ((a * v0 + omega * omega * x0) / wd) * (float) Math.sin(wd * t));
    }
}
