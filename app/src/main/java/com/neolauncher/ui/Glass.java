package com.neolauncher.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntConsumer;

/**
 * Stavebnice pro dialogy ve stylu matneho skla (nastaveni, menu aplikace,
 * aktualizace): sklo s bilym okrajem a svetlou horni hranou, dlazdice,
 * pilulky (vybrana = bila s tmavym textem). Vse programove, bez XML.
 * Prvky maji stav "hovered", ktery Quest nastavi, kdyz na ne mirite laserem.
 */
public final class Glass {
    /** Hlavni akcent (Electric Cobalt z palety). */
    public static final int ACCENT = Palette.COBALT;
    private static final int[] HOVERED = {android.R.attr.state_hovered};
    private static final int[] PRESSED = {android.R.attr.state_pressed};
    private static final int[] SELECTED = {android.R.attr.state_selected};
    private static final int[] EMPTY = {};

    private Glass() {}

    /*
     * Jednotny rytmus mezer a zaobleni (dp) pro vsechna okna i rychle menu.
     * Rohy jsou soustredne: vnitrni radius = vnejsi radius - odsazeni, takze
     * okraj dlazdice bezi s okrajem panelu rovnobezne (jinak roh "nesedi").
     */
    /** Vnitrni okraj panelu - vsude a ze vsech stran stejny. */
    public static final float PAD = 20f;
    /** Mezi nadpisem panelu a obsahem (a mezi velkymi celky). */
    public static final float SECTION = 20f;
    /** Mezi dlazdicemi a prvky v mrizce - vodorovne i svisle stejne. */
    public static final float GAP = 12f;
    /**
     * Velky nadpis (Nastaveni, hodiny v rychlem menu): nahore 30 misto PAD -
     * u rohu s radiusem 40 by jinak pusobil namackane. Vodorovne o kousek
     * odsazeny od obsahu pod nim (opticky zarovnany s textem v dlazdicich).
     */
    public static final float TITLE_TOP = 30f;
    public static final float TITLE_INSET = 4f;
    /** Hlavni tlacitko pres celou sirku (Hotovo, Nastaveni Nea...). */
    public static final float BUTTON_H = 48f;
    /** Delka rozplynuti obsahu u okraje rolovaci plochy. */
    public static final float FADE = 32f;
    /** Mezi malymi prvky vedle sebe (pilulky, tlacitka). */
    public static final float GAP_S = 8f;
    /** Dlazdice, karty, posuvniky. */
    public static final float R_TILE = 20f;
    /** Panel = dlazdice + okraj (soustredne). */
    public static final float R_PANEL = R_TILE + PAD;
    /** Vnitrni okraj dlazdice s textem (dlazdice nastaveni, widgety). */
    public static final float TILE_PAD = 16f;
    /** Odsazeni obsahu uvnitr dlazdice (nahled hry v karte). */
    public static final float INSET = 8f;
    /** Nahled uvnitr dlazdice (soustredne s dlazdici). */
    public static final float R_INNER = R_TILE - INSET;

    public static float dp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static int dpi(Context c, float v) {
        return Math.round(dp(c, v));
    }

    /*
     * Styl skla (volba v nastaveni): tmave "Void" sklo (vychozi) nebo svetle
     * "Titanium Fog". Kresli se jednim receptem (GlassSurface) - panel,
     * ornament, lista, rychle menu, dialogy i dlazdice.
     */
    private static boolean light;

    /** @param vision true = svetle sklo (Titanium Fog), false = tmave sklo (Void) */
    public static void setStyle(boolean vision) {
        light = vision;
    }

    public static boolean isLight() {
        return light;
    }

    /** Tmavy text na perlovem pozadi (vybrana pilulka, hlavni tlacitko). */
    public static final int INK = Palette.VOID;

    /** Sklo dialogu, radius R_PANEL. */
    public static Drawable panel(Context c) {
        return panel(c, R_PANEL);
    }

    public static Drawable panel(Context c, float radiusDp) {
        return new GlassDrawable(dp(c, radiusDp), c.getResources().getDisplayMetrics().density,
                GlassSurface.panel(), null, null);
    }

    /** Dlazdice (karta nastaveni) - pri mireni laserem se rozsviti (bez barevne zare). */
    public static Drawable tile(Context c) {
        return new GlassDrawable(dp(c, R_TILE), c.getResources().getDisplayMetrics().density,
                GlassSurface.TILE, GlassSurface.TILE_HOVER, GlassSurface.TILE_PRESSED);
    }

    /** Staticka dlazdice bez hoveru (widgety). */
    public static Drawable widget(Context c) {
        return new GlassDrawable(dp(c, R_TILE), c.getResources().getDisplayMetrics().density,
                GlassSurface.TILE, null, null);
    }

    /**
     * Perlova plocha (vybrana pilulka, hlavni tlacitko, zapnuty prepinac):
     * temer bila s jemnym "holografickym" nadechem do fialova a modra.
     */
    public static GradientDrawable pearl(Context c, float radiusDp, boolean bright) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, bright
                ? new int[]{0xFFFFFFFF, 0xFFF7F5FF, 0xFFF2F7FF}
                : new int[]{Palette.PEARL, Palette.PEARL_LILAC, Palette.PEARL_BLUE});
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    private static Drawable pearlStates(Context c, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable pressed = pearl(c, radiusDp, false);
        pressed.setAlpha(0xD9);
        s.addState(PRESSED, pressed);
        s.addState(HOVERED, pearl(c, radiusDp, true));
        s.addState(EMPTY, pearl(c, radiusDp, false));
        return s;
    }

    /** Pilulka (kategorie, volba): vybrana = bila s tmavym textem, jinak obrys. */
    public static TextView pill(Context c, String label, boolean selected, View.OnClickListener l) {
        TextView t = text(c, label, 13.5f, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dpi(c, 16), dpi(c, 8), dpi(c, 16), dpi(c, 8));
        t.setClickable(true);
        t.setFocusable(true);
        setPillSelected(c, t, selected);
        t.setOnClickListener(l);
        return t;
    }

    public static void setPillSelected(Context c, TextView t, boolean selected) {
        t.setSelected(selected);
        t.setTextColor(selected ? INK : Palette.TEXT);
        t.setBackground(selected
                ? pearlStates(c, 999)
                : states(c, 999, 0x0DFFFFFF, 0x26FFFFFF, 0x40FFFFFF, 0, 0x40FFFFFF));
    }

    private static GradientDrawable round(Context c, int color, float radius, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radius));
        if (strokeColor != 0) g.setStroke(dpi(c, 1), strokeColor);
        return g;
    }

    private static Drawable states(Context c, float radius, int normal, int hovered,
                                   int pressed, int selected, int stroke) {
        StateListDrawable s = new StateListDrawable();
        s.addState(PRESSED, round(c, pressed, radius, stroke));
        if (selected != 0) s.addState(SELECTED, round(c, selected, radius, 0x55FFFFFF));
        s.addState(HOVERED, round(c, hovered, radius, 0x40FFFFFF));
        s.addState(EMPTY, round(c, normal, radius, stroke));
        return s;
    }

    /**
     * Obsah rolovaci plochy se u horniho/dolniho okraje jemne rozplyne do
     * pruhledna (misto ostre "zakousnute" hrany). Okraj se ukaze jen na strane,
     * kam jde jeste rolovat.
     */
    public static void fadeEdges(android.widget.ScrollView sv) {
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(dpi(sv.getContext(), FADE));
    }

    public static TextView text(Context c, String s, float sizeDp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(Typeface.SANS_SERIF, bold ? 700 : 400, false));
        return t;
    }

    public static TextView title(Context c, String s) {
        return text(c, s, 20, Color.WHITE, true);
    }

    public static TextView section(Context c, String s) {
        TextView t = text(c, s.toUpperCase(), 12, Palette.text3(), true);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, dpi(c, 18), 0, dpi(c, 6));
        return t;
    }

    /** Tlacitko-pilulka. accent = hlavni akce (bila s tmavym textem), jinak obrys. */
    public static TextView button(Context c, String label, boolean accent, View.OnClickListener l) {
        TextView b = text(c, label, 14, accent ? INK : Palette.TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dpi(c, 18), dpi(c, 9), dpi(c, 18), dpi(c, 9));
        if (accent) {
            b.setBackground(pearlStates(c, 999));
        } else {
            b.setBackground(states(c, 999, 0x14FFFFFF, 0x2EFFFFFF, 0x47FFFFFF, 0, 0x4DFFFFFF));
        }
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(l);
        return b;
    }

    /** Polozka menu pres celou sirku, zarovnana vlevo. */
    public static TextView menuItem(Context c, String label, boolean danger, View.OnClickListener l) {
        TextView b = text(c, label, 15, danger ? Palette.MAGENTA : Palette.TEXT, false);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dpi(c, 14), dpi(c, 11), dpi(c, 14), dpi(c, 11));
        b.setBackground(states(c, R_TILE, 0x00FFFFFF, 0x2EFFFFFF, 0x47FFFFFF, 0, 0));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(l);
        return b;
    }

    /** Prepinac z nekolika moznosti - rada pilulek (vybrana je bila). */
    public static LinearLayout segmented(Context c, String[] items, int selected, IntConsumer onSelect) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] views = new TextView[items.length];
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView t = pill(c, items[i], i == selected, v -> {
                for (int j = 0; j < views.length; j++) setPillSelected(c, views[j], j == idx);
                onSelect.accept(idx);
            });
            t.setPadding(dpi(c, 14), dpi(c, 7), dpi(c, 14), dpi(c, 7));
            views[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dpi(c, 6);
            row.addView(t, lp);
        }
        return row;
    }

    public static GlassWidgets.Toggle toggle(Context c, boolean on, java.util.function.Consumer<Boolean> onChange) {
        return new GlassWidgets.Toggle(c, on, onChange);
    }

    /** Radek nastaveni: nazev + popis vlevo, ovladaci prvek vpravo nebo pod nim. */
    public static LinearLayout row(Context c, String title, String subtitle, View control, boolean below) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(below ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setGravity(below ? Gravity.START : Gravity.CENTER_VERTICAL);
        row.setPadding(0, dpi(c, 8), 0, dpi(c, 8));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(c, title, 15, Color.WHITE, true));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = text(c, subtitle, 12.5f, Palette.text2(), false);
            s.setPadding(0, dpi(c, 2), 0, 0);
            texts.addView(s);
        }
        if (below) {
            row.addView(texts);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dpi(c, 8);
            row.addView(control, lp);
        } else {
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dpi(c, 16);
            row.addView(control, lp);
        }
        return row;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(0x26FFFFFF);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, dpi(c, 1))));
        return v;
    }
}
