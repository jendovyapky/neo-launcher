package com.neolauncher.ui;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.util.Log;

/**
 * Cocka "kukatka" pro animaci spusteni: JEDEN AGSL efekt na celou vrstvu
 * animace (ne na karty). Dela najednou:
 * <ul>
 *   <li>rybi oko (vyduti uprostred jako u kukatka ve dverich),</li>
 *   <li>radialni "zoom blur" - rozmazani do stran pri pruletu,</li>
 *   <li>mekky okraj kruhu a ztmaveni u obruby,</li>
 *   <li>tmu mimo kruh.</li>
 * </ul>
 * Na Androidu pod 13 nebo pri chybe kompilace vrati null a animace se
 * nakresli bez cocky (NeoLauncherView ma zalozni cestu).
 */
final class LaunchLens {
    private static final String TAG = "NeoLaunchLens";

    private static final String AGSL =
            "uniform shader content;\n"
            + "uniform float2 center;\n"
            + "uniform float radius;\n"
            + "uniform float feather;\n"
            + "uniform float fisheye;\n"
            + "uniform float zoomBlur;\n"
            + "uniform float edgeBlur;\n"
            + "uniform float darkA;\n"
            + "half4 main(float2 p) {\n"
            + "    float2 d = p - center;\n"
            + "    float r = length(d);\n"
            + "    float nr = r / max(radius, 1.0);\n"
            + "    float k = 0.38 * fisheye;\n"
            + "    float2 src = center + d * mix(1.0 - k, 1.0, min(nr * nr, 1.0));\n"
            + "    float amount = 0.14 * zoomBlur + 0.06 * edgeBlur * min(nr * nr, 1.0);\n"
            + "    float4 acc = float4(0.0);\n"
            + "    for (int i = 0; i < 12; i++) {\n"
            + "        float f = 1.0 - amount * (float(i) / 11.0);\n"
            + "        acc += float4(content.eval(center + (src - center) * f));\n"
            + "    }\n"
            + "    float4 col = acc / 12.0;\n"
            + "    float inside = 1.0 - smoothstep(radius - feather, radius, r);\n"
            + "    float vig = 1.0 - 0.45 * fisheye * smoothstep(0.5, 1.0, nr);\n"
            + "    col.rgb = col.rgb * vig;\n"
            + "    float4 lens = col * inside;\n"
            + "    float4 dark = float4(0.012, 0.02, 0.035, 1.0) * darkA * (1.0 - inside);\n"
            + "    return half4(lens + dark);\n"
            + "}\n";

    private Object shader; // RuntimeShader (API 33+)
    private boolean failed;

    /** @return false, kdyz cocka neni k dispozici (pak se kresli bez ni). */
    boolean available() {
        if (failed || Build.VERSION.SDK_INT < 33) return false;
        if (shader == null) {
            try {
                shader = Agsl.compile(AGSL);
            } catch (Throwable t) {
                Log.e(TAG, "AGSL cocka selhala, animace pojede bez ni", t);
                failed = true;
                return false;
            }
        }
        return true;
    }

    RenderEffect effect(float cx, float cy, float radius, float feather, float fisheye,
                        float zoomBlur, float edgeBlur, float darkA) {
        if (!available()) return null;
        try {
            RuntimeShader s = (RuntimeShader) shader;
            s.setFloatUniform("center", cx, cy);
            s.setFloatUniform("radius", radius);
            s.setFloatUniform("feather", Math.max(1f, feather));
            s.setFloatUniform("fisheye", fisheye);
            s.setFloatUniform("zoomBlur", zoomBlur);
            s.setFloatUniform("edgeBlur", edgeBlur);
            s.setFloatUniform("darkA", darkA);
            return RenderEffect.createRuntimeShaderEffect(s, "content");
        } catch (Throwable t) {
            Log.e(TAG, "AGSL cocka selhala, animace pojede bez ni", t);
            failed = true;
            return null;
        }
    }
}
