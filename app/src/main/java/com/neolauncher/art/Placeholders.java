package com.neolauncher.art;

/** Barevne prechody pro karty bez obrazku - stejna paleta jako v preview_neo.html. */
public final class Placeholders {
    private Placeholders() {}

    private static final int[][] PAIRS = {
            {0xFFE11D48, 0xFF9F1239},
            {0xFF0284C7, 0xFF075985},
            {0xFF7C3AED, 0xFF5B21B6},
            {0xFF10B981, 0xFF065F46},
            {0xFFF59E0B, 0xFFB45309},
            {0xFF6366F1, 0xFF4338CA},
            {0xFFEC4899, 0xFFBE185D},
            {0xFF14B8A6, 0xFF115E59},
            {0xFF881337, 0xFF4C0519},
            {0xFF065F46, 0xFF022C22},
            {0xFFEA580C, 0xFF7C2D12},
            {0xFF4F46E5, 0xFF312E81},
    };

    public static int[] colorsFor(String key) {
        int h = key.hashCode();
        return PAIRS[Math.floorMod(h, PAIRS.length)];
    }
}
