package com.neolauncher.ui;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.util.Log;

/**
 * "Tekute sklo" horni bubliny (ornamentu): obsah pod bublinou se u jejiho
 * okraje lomi jako ve vypouklem skle - karty se u hrany natahnou dovnitr
 * a na okraji se lehce rozlozi barvy (cervena/modra). Uprostred zustane jen
 * rozmazani (matne sklo).
 * <p>
 * JEDEN AGSL efekt retezeny za rozmazanim na malou vrstvu pod bublinou
 * (backdropNode), zadny efekt na karty. Android 13+; bez shaderu (nebo kdyz
 * selze - napr. v nahledu v Robolectricu) jen obycejne rozmazani.
 */
final class LiquidGlass {
    private static final String TAG = "NeoLiquidGlass";

    private static final String AGSL =
            "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float band;\n"
            + "uniform float strength;\n"
            + "uniform float chroma;\n"
            + "float sdBox(float2 p, float2 b, float r) {\n"
            + "    float2 q = abs(p) - b + float2(r, r);\n"
            + "    return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;\n"
            + "}\n"
            + "half4 main(float2 xy) {\n"
            + "    float2 c = size * 0.5;\n"
            + "    float2 p = xy - c;\n"
            + "    float d = sdBox(p, c, radius);\n"
            // t: 0 hloubeji nez 'band' od okraje, 1 na samem okraji
            + "    float t = clamp(1.0 + d / band, 0.0, 1.0);\n"
            + "    if (t <= 0.0) {\n"
            + "        return content.eval(xy);\n"
            + "    }\n"
            + "    float2 g = float2(sdBox(p + float2(1.0, 0.0), c, radius) - sdBox(p - float2(1.0, 0.0), c, radius),\n"
            + "                      sdBox(p + float2(0.0, 1.0), c, radius) - sdBox(p - float2(0.0, 1.0), c, radius));\n"
            + "    float2 n = g / max(length(g), 0.0001);\n"
            // Vypukla hrana: cim bliz okraji, tim vic se bere obsah zevnitr (natazeni).
            + "    float2 o = -n * (t * t * strength);\n"
            + "    half4 base = content.eval(xy + o);\n"
            + "    half r = min(content.eval(xy + o * (1.0 + chroma)).r, base.a);\n"
            + "    half b = min(content.eval(xy + o * (1.0 - chroma)).b, base.a);\n"
            + "    return half4(r, base.g, b, base.a);\n"
            + "}\n";

    private Object shader; // RuntimeShader (API 33+), Object kvuli nacitani trid na starsim API
    private boolean failed;
    private RenderEffect lastBlur, last;
    private float lastW = -1, lastH, lastBand, lastStrength;

    /**
     * @param blur     rozmazani, ktere se provede nejdriv (matne sklo)
     * @param w,h      rozmer bubliny (px), zaobleni = h / 2
     * @param band     sirka lomiveho okraje (px)
     * @param strength o kolik se u samotneho okraje posune obraz (px)
     * @return rozmazani + lom, nebo jen rozmazani, kdyz shader nejde
     */
    RenderEffect effect(RenderEffect blur, float w, float h, float band, float strength) {
        if (failed || Build.VERSION.SDK_INT < 33) return blur;
        if (last != null && blur == lastBlur && w == lastW && h == lastH && band == lastBand
                && strength == lastStrength) {
            return last;
        }
        try {
            if (shader == null) shader = Agsl.compile(AGSL);
            final RuntimeShader s = (RuntimeShader) shader;
            s.setFloatUniform("size", w, h);
            s.setFloatUniform("radius", h / 2f);
            s.setFloatUniform("band", Math.max(1f, band));
            s.setFloatUniform("strength", strength);
            s.setFloatUniform("chroma", 0.35f);
            final RenderEffect lens = RenderEffect.createRuntimeShaderEffect(s, "content");
            last = RenderEffect.createChainEffect(lens, blur);
            lastBlur = blur;
            lastW = w;
            lastH = h;
            lastBand = band;
            lastStrength = strength;
            return last;
        } catch (Throwable t) {
            // Chyba kompilace nebo chybejici nativni cast (nahled) - dal jen rozmazani.
            failed = true;
            Log.w(TAG, "Tekute sklo nejde, jen rozmazani: " + t);
            return blur;
        }
    }
}
