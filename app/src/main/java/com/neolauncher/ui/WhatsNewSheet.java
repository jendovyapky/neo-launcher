package com.neolauncher.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * "Co je noveho" - ukaze se jednou po kazde aktualizaci (a jde otevrit
 * z nastaveni). Text je v assets/novinky.txt: sekce zacina "# Nadpis",
 * pod ni body "• ...". Nejnovejsi sekce je nahore; stejny text dava CI
 * do poznamek k releasu (okno aktualizace).
 */
public final class WhatsNewSheet {
    private WhatsNewSheet() {}

    private static final class Section {
        String title;
        final List<String> points = new ArrayList<>();
    }

    /** @return null, kdyz soubor s novinkami chybi nebo je prazdny */
    public static View build(Context c, String version, Runnable onClose) {
        try {
            return build(c, version, c.getAssets().open("novinky.txt"), onClose);
        } catch (Exception e) {
            return null;
        }
    }

    /** Varianta s vlastnim zdrojem textu (nahled v tools/screenshot). */
    public static View build(Context c, String version, java.io.InputStream in, Runnable onClose) {
        final List<Section> sections = load(in);
        if (sections.isEmpty()) return null;

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Glass.panel(c));
        final int pad = Glass.dpi(c, Glass.PAD);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(new GlassWidgets.IconView(c, Icons.SPARKLE, 40, Color.WHITE));
        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Glass.dpi(c, Glass.GAP), 0, 0, 0);
        titles.addView(Glass.text(c, "Co je nového", 24, Color.WHITE, true));
        titles.addView(Glass.text(c, "Neo " + version, 13, Palette.text2(), false));
        header.addView(titles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        Glass.fadeEdges(scroll);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = Glass.dpi(c, Glass.SECTION);
        root.addView(scroll, slp);

        for (int i = 0; i < sections.size(); i++) {
            final Section s = sections.get(i);
            final boolean latest = i == 0;
            if (i == 1) {
                TextView older = Glass.text(c, "DŘÍVE", 12, Palette.text3(), true);
                older.setLetterSpacing(0.08f);
                older.setPadding(0, Glass.dpi(c, Glass.SECTION), 0, 0);
                body.addView(older);
            }
            TextView t = Glass.text(c, s.title, latest ? 18 : 15, latest ? Palette.TEXT : Palette.text2(), true);
            t.setPadding(0, latest ? 0 : Glass.dpi(c, Glass.GAP), 0, Glass.dpi(c, Glass.GAP_S));
            body.addView(t);
            for (String p : s.points) {
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, Glass.dpi(c, 3), 0, Glass.dpi(c, 3));
                TextView dot = Glass.text(c, "•", latest ? 15 : 13.5f, latest ? Palette.COBALT_LIGHT : Palette.text3(), true);
                dot.setPadding(0, 0, Glass.dpi(c, 10), 0);
                row.addView(dot);
                TextView pt = Glass.text(c, p, latest ? 15 : 13.5f, latest ? Palette.TEXT : Palette.text2(), false);
                pt.setLineSpacing(0, 1.12f);
                row.addView(pt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                body.addView(row);
            }
        }

        TextView ok = Glass.button(c, "Super", true, v -> onClose.run());
        ok.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Glass.dpi(c, Glass.BUTTON_H));
        olp.topMargin = Glass.dpi(c, Glass.SECTION);
        root.addView(ok, olp);
        return root;
    }

    private static List<Section> load(java.io.InputStream in) {
        List<Section> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            Section cur = null;
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#")) {
                    cur = new Section();
                    cur.title = line.replaceFirst("^#+\\s*", "");
                    out.add(cur);
                } else if (!line.isEmpty() && cur != null) {
                    cur.points.add(line.replaceFirst("^[•\\-*]\\s*", ""));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
