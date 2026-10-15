package com.neolauncher.ui;

import android.graphics.Color;

/**
 * Barevna paleta Nea (vybral uzivatel 2026-09-27): tmava "Void Black",
 * neutralni "Titanium Fog" a "Holographic Pearl" nesou vetsinu UI, barvy
 * jsou jen akcenty - "Electric Cobalt" (vyber, pokrok, NOVE), "Toxic Amber"
 * (stredni stav, oblibene) a "Synth Magenta" (upozorneni).
 * Zadne dalsi barvy "od oka" - vse z techto tokenu (+ pruhlednost).
 */
public final class Palette {
    private Palette() {}

    /** Nejtmavsi zaklad: sklo tmaveho stylu, text na perlovem pozadi. */
    public static final int VOID = 0xFF06070A;
    /** Hlavni akcent. */
    public static final int COBALT = 0xFF3D5AFE;
    /** Svetlejsi kobalt pro text a tenke cary na tmavem skle (citelnost). */
    public static final int COBALT_LIGHT = 0xFF8C9DFF;
    /** Upozorneni (slaba baterie). */
    public static final int MAGENTA = 0xFFFF2FA3;
    /** Stredni stav (baterie do 50 %), hvezda oblibenych. */
    public static final int AMBER = 0xFFFF8A1E;
    /** Neutralni: vedlejsi text, svetle sklo. */
    public static final int FOG = 0xFFAEB6C2;
    /** Hlavni text, vybrana pilulka, hlavni tlacitko, vypln posuvniku. */
    public static final int PEARL = 0xFFF5F7FF;
    /** Jemny "holograficky" odlesk perly (do fialova a modra) pro prechody. */
    public static final int PEARL_LILAC = 0xFFEEEBFF;
    public static final int PEARL_BLUE = 0xFFE8F0FF;

    /** Text: hlavni / vedlejsi / popisky sekci (na tmavem skle). */
    public static final int TEXT = PEARL;
    public static final int TEXT_2 = FOG;
    public static final int TEXT_3 = 0xA6AEB6C2;

    /** Vedlejsi text podle stylu skla - na svetlem (sedem) skle by mlhova sed zanikla. */
    public static int text2() {
        return Glass.isLight() ? 0xE6F5F7FF : TEXT_2;
    }

    public static int text3() {
        return Glass.isLight() ? 0xB3F5F7FF : TEXT_3;
    }

    /** Barva s danou pruhlednosti (0..1). */
    public static int alpha(int color, float a) {
        final int al = Math.round(Math.max(0f, Math.min(1f, a)) * Color.alpha(color));
        return (color & 0x00FFFFFF) | (al << 24);
    }

    /** Stav baterie: nad 50 % perla, do 50 % jantar, do 20 % magenta. */
    public static int battery(int level) {
        if (level < 0) return PEARL;
        if (level <= 20) return MAGENTA;
        if (level <= 50) return AMBER;
        return PEARL;
    }
}
