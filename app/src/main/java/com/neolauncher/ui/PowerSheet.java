package com.neolauncher.ui;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Okno "Vypnout Quest" ve skle Nea misto systemove nabidky Androidu: Uspat /
 * Restartovat / Vypnout jako tri velke dlazdice (jako menu Questu). Vypnout
 * a restartovat obycejna aplikace nesmi - udela to doplnek Meta tlacitka.
 */
public final class PowerSheet {
    private PowerSheet() {}

    public static final int SLEEP = 0;
    public static final int RESTART = 1;
    public static final int OFF = 2;

    public interface Actions {
        void power(int what);

        void cancel();
    }

    public static View build(Context c, Actions a) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Glass.panel(c));
        final int pad = Glass.dpi(c, Glass.PAD);
        root.setPadding(pad, pad, pad, pad);

        root.addView(Glass.title(c, "Vypnout Quest"));
        TextView sub = Glass.text(c, "Uspaný Quest se probudí tlačítkem napájení", 12.5f, Palette.text3(), false);
        sub.setPadding(0, Glass.dpi(c, 4), 0, Glass.dpi(c, Glass.SECTION));
        root.addView(sub);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(tile(c, Icons.MOON, "Uspat", "Displej zhasne, hry počkají", v -> a.power(SLEEP)),
                weight(c, false));
        row.addView(tile(c, Icons.REFRESH, "Restartovat", "Vypne se a zase zapne", v -> a.power(RESTART)),
                weight(c, true));
        row.addView(tile(c, Icons.POWER, "Vypnout", "Úplně vypnout headset", v -> a.power(OFF)),
                weight(c, true));
        root.addView(row);

        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, Glass.dpi(c, Glass.SECTION), 0, 0);
        buttons.addView(Glass.button(c, "Zrušit", false, v -> a.cancel()));
        root.addView(buttons);
        return root;
    }

    private static LinearLayout.LayoutParams weight(Context c, boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (gap) lp.leftMargin = Glass.dpi(c, Glass.GAP);
        return lp;
    }

    /** Dlazdice: ikona v kulatem skle, nazev a popis (cela dlazdice je tlacitko). */
    private static View tile(Context c, int icon, String title, String desc, View.OnClickListener l) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setBackground(Glass.tile(c));
        final int p = Glass.dpi(c, Glass.TILE_PAD);
        t.setPadding(p, p, p, p);
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(l);
        t.addView(new GlassWidgets.IconView(c, icon, 52, Color.WHITE));
        TextView tt = Glass.text(c, title, 15.5f, Color.WHITE, true);
        tt.setGravity(Gravity.CENTER);
        tt.setPadding(0, Glass.dpi(c, Glass.GAP), 0, 0);
        tt.setSingleLine(true);
        t.addView(tt);
        TextView s = Glass.text(c, desc, 12.5f, Palette.text2(), false);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, Glass.dpi(c, 3), 0, 0);
        s.setMaxLines(2);
        s.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(s);
        return t;
    }
}
