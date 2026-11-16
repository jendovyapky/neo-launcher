package com.neolauncher.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.neolauncher.update.Updater;

/** Dialog "Je dostupna nova verze" s poznamkami k verzi a prubehem stahovani. */
public final class UpdateSheet {
    private UpdateSheet() {}

    public interface Actions {
        /** Uzivatel chce aktualizovat; status = TextView pro zobrazeni prubehu. */
        void update(TextView status, View buttons);

        void later();
    }

    public static View build(Context c, String currentVersion, Updater.Release r, Actions a) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Glass.panel(c));
        int pad = Glass.dpi(c, Glass.PAD);
        root.setPadding(pad, pad, pad, pad);

        root.addView(Glass.title(c, "Nová verze Neo " + r.versionName));
        TextView sub = Glass.text(c, "Nainstalováno: " + currentVersion
                + (r.prerelease ? " · testovací build" : ""), 12.5f, Palette.text3(), false);
        sub.setPadding(0, Glass.dpi(c, 4), 0, Glass.dpi(c, Glass.SECTION));
        root.addView(sub);

        if (!r.notes.isEmpty()) {
            ScrollView sv = new ScrollView(c);
            sv.setVerticalScrollBarEnabled(false);
            sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
            Glass.fadeEdges(sv);
            TextView notes = Glass.text(c, r.notes, 13.5f, 0xE6FFFFFF, false);
            notes.setLineSpacing(0, 1.15f);
            sv.addView(notes);
            root.addView(sv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Glass.dpi(c, 180)));
        }

        final TextView status = Glass.text(c, "Po instalaci se launcher zavře – stačí ho znovu otevřít.",
                12.5f, Palette.text3(), false);
        status.setPadding(0, Glass.dpi(c, Glass.GAP), 0, 0);
        root.addView(status);

        final LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, Glass.dpi(c, Glass.SECTION), 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Glass.dpi(c, Glass.GAP_S);
        buttons.addView(Glass.button(c, "Později", false, v -> a.later()), lp);
        buttons.addView(Glass.button(c, "Aktualizovat", true, v -> {
            status.setTextColor(Color.WHITE);
            a.update(status, buttons);
        }), new LinearLayout.LayoutParams(lp));
        root.addView(buttons);
        return root;
    }
}
