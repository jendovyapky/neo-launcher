package com.neolauncher.ui;

import android.graphics.RuntimeShader;

/**
 * Kompilace AGSL shaderu. Android 13+ (Quest 3S) chce zapis {@code content.eval(p)};
 * starsi Skia (napr. v Robolectricu pro nahledy) zna jen {@code sample(content, p)}.
 * Zkusi se moderni zapis a pri chybe starsi - logika shaderu je v obou stejna,
 * takze nahled v tools/screenshot overi i shader, ktery pojede na headsetu.
 */
final class Agsl {
    private Agsl() {}

    static RuntimeShader compile(String agsl) {
        try {
            return new RuntimeShader(agsl);
        } catch (IllegalArgumentException modern) {
            try {
                return new RuntimeShader(agsl.replace("content.eval(", "sample(content, "));
            } catch (IllegalArgumentException legacy) {
                throw modern;
            }
        }
    }
}
