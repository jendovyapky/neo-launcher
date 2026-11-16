package com.neolauncher.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Kratka nabidka: ikona, nadpis, text a dve tlacitka (napr. aktualizovat doplnek). */
public final class ConfirmSheet {
    private ConfirmSheet() {}

    public static LinearLayout build(Context c, int icon, String title, String text, String primary,
                                     String secondary, Runnable onPrimary, Runnable onSecondary) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Glass.panel(c));
        final int pad = Glass.dpi(c, Glass.PAD);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(new GlassWidgets.IconView(c, icon, 40, Color.WHITE));
        TextView t = Glass.title(c, title);
        t.setPadding(Glass.dpi(c, Glass.GAP), 0, 0, 0);
        head.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(head);

        TextView body = Glass.text(c, text, 14, Palette.text2(), false);
        body.setLineSpacing(0, 1.15f);
        body.setPadding(0, Glass.dpi(c, Glass.SECTION), 0, 0);
        root.addView(body);

        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, Glass.dpi(c, Glass.SECTION), 0, 0);
        if (secondary != null) {
            buttons.addView(Glass.button(c, secondary, false, v -> onSecondary.run()));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Glass.dpi(c, Glass.GAP_S);
        buttons.addView(Glass.button(c, primary, true, v -> onPrimary.run()), lp);
        root.addView(buttons);
        return root;
    }
}
