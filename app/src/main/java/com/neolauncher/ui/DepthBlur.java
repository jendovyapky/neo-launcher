package com.neolauncher.ui;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.util.Log;

/**
 * Radialni "hloubka ostrosti": JEDEN efekt na celou vrstvu karet (ne na
 * kazdou kartu zvlast - to uz jednou shodilo headset, viz CLAUDE.md #1).
 * <p>
 * Android 13+ (Quest 3S): vlastni AGSL shader - cim dal od hovernute karty,
 * tim vetsi rozmazani, presne jako v preview. Starsi Android nebo chyba
 * pri kompilaci shaderu: obycejny rovnomerny blur.
 */
final class DepthBlur {
    private static final String TAG = "NeoDepthBlur";

    private static final String AGSL =
            "uniform shader content;\n"
            + "uniform float2 center;\n"
            + "uniform float innerR;\n"
            + "uniform float outerR;\n"
            + "uniform float rNear;\n"
            + "uniform float rFar;\n"
            + "half4 main(float2 p) {\n"
            + "    float d = distance(p, center);\n"
            + "    float t = smoothstep(innerR, outerR, d);\n"
            + "    float r = mix(rNear, rFar, t);\n"
            + "    if (r < 0.35) {\n"
            + "        return content.eval(p);\n"
            + "    }\n"
            + "    float4 acc = float4(content.eval(p));\n"
            + "    for (int i = 0; i < 16; i++) {\n"
            + "        float fi = float(i) + 0.5;\n"
            + "        float rr = r * sqrt(fi / 16.0);\n"
            + "        float a = fi * 2.39996323;\n"
            + "        acc += float4(content.eval(p + float2(cos(a), sin(a)) * rr));\n"
            + "    }\n"
            + "    return half4(acc / 17.0);\n"
            + "}\n";

    private Object shader; // RuntimeShader (API 33+), Object kvuli nacitani trid na API 31
    private boolean shaderFailed;

    private float lastCx = Float.NaN, lastCy, lastInner, lastOuter, lastNear, lastFar;
    private RenderEffect last;

    /**
     * @param cx,cy  stred ostre oblasti v souradnicich vrstvy
     * @param inner  do teto vzdalenosti plati rNear
     * @param outer  od teto vzdalenosti plati rFar
     * @param rNear  polomer rozmazani u hovernute karty (px)
     * @param rFar   polomer rozmazani nejdal (px)
     */
    RenderEffect effect(float cx, float cy, float inner, float outer, float rNear, float rFar) {
        if (last != null && cx == lastCx && cy == lastCy && inner == lastInner
                && outer == lastOuter && rNear == lastNear && rFar == lastFar) {
            return last;
        }
        lastCx = cx;
        lastCy = cy;
        lastInner = inner;
        lastOuter = outer;
        lastNear = rNear;
        lastFar = rFar;
        last = build(cx, cy, inner, outer, rNear, rFar);
        return last;
    }

    private RenderEffect build(float cx, float cy, float inner, float outer, float rNear, float rFar) {
        if (Build.VERSION.SDK_INT >= 33 && !shaderFailed) {
            try {
                if (shader == null) shader = Agsl.compile(AGSL);
                RuntimeShader s = (RuntimeShader) shader;
                s.setFloatUniform("center", cx, cy);
                s.setFloatUniform("innerR", inner);
                s.setFloatUniform("outerR", Math.max(inner + 1f, outer));
                s.setFloatUniform("rNear", rNear);
                s.setFloatUniform("rFar", rFar);
                return RenderEffect.createRuntimeShaderEffect(s, "content");
            } catch (Throwable t) {
                Log.e(TAG, "AGSL shader selhal, prechazim na obycejny blur", t);
                shaderFailed = true;
                shader = null;
            }
        }
        // Rovnomerny blur: prumer blizkeho a vzdaleneho polomeru (polomer disku ~ 2 sigma).
        float sigma = Math.max(0.5f, (rNear + rFar) * 0.25f);
        float radius = Math.max(0.5f, (sigma - 0.5f) / 0.57735f);
        return RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL);
    }
}
