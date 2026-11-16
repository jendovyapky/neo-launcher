package com.neolauncher.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.neolauncher.cast.Cast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Okno "Streamovat" (dlazdice v rychlem menu): obraz a zvuk Questu do prohlizece
 * na pocitaci ve stejne Wi-Fi. Kamarad otevre quest.jendovyapky.eu a Quest se
 * najde sam. Puvodni systemove sdileni Questu (Chromecast, nahravani, snimek)
 * zustava pod tlacitkem dole.
 * <p>
 * Stav se jednou za sekundu docte z mistniho serveru streamu (bezi v procesu
 * :cast) - okno nic nedrzi v pameti, zavreni nic nezastavi.
 */
public final class CastSheet {
    private CastSheet() {}

    public interface Actions {
        void start();

        void stop();

        /** Puvodni sdileni Questu (Fotoaparat / systemux://sharing). */
        void systemSharing();

        void close();
    }

    public static View build(Context c, Actions a) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Glass.panel(c));
        root.setClickable(true);
        final int pad = Glass.dpi(c, Glass.PAD);
        root.setPadding(pad, pad, pad, pad);

        root.addView(Glass.title(c, "Streamovat"));
        TextView sub = Glass.text(c, "Obraz a zvuk Questu v prohlížeči na počítači ve stejné Wi-Fi",
                12.5f, Palette.text3(), false);
        sub.setPadding(0, Glass.dpi(c, 4), 0, Glass.dpi(c, Glass.SECTION));
        root.addView(sub);

        // Stav: ikona v kulatem skle + nadpis a podrobnosti
        LinearLayout state = new LinearLayout(c);
        state.setOrientation(LinearLayout.HORIZONTAL);
        state.setGravity(Gravity.CENTER_VERTICAL);
        state.setBackground(Glass.tile(c));
        final int tp = Glass.dpi(c, Glass.TILE_PAD);
        state.setPadding(tp, tp, tp, tp);
        state.addView(new GlassWidgets.IconView(c, Icons.CAST, 52, Color.WHITE));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Glass.dpi(c, Glass.TILE_PAD), 0, 0, 0);
        final TextView stTitle = Glass.text(c, "Nevysílá", 16, Color.WHITE, true);
        final TextView stInfo = Glass.text(c, "Spusť, a obraz půjde do prohlížeče", 12.5f, Palette.text2(), false);
        stInfo.setPadding(0, Glass.dpi(c, 3), 0, 0);
        texts.addView(stTitle);
        texts.addView(stInfo);
        state.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(state);

        // Adresa pro kamarady
        LinearLayout addr = new LinearLayout(c);
        addr.setOrientation(LinearLayout.VERTICAL);
        addr.setBackground(Glass.tile(c));
        addr.setPadding(tp, tp, tp, tp);
        addr.addView(Glass.text(c, "Na počítači otevři", 12.5f, Palette.text2(), false));
        TextView site = Glass.text(c, Cast.SITE, 22, Palette.TEXT, true);
        site.setPadding(0, Glass.dpi(c, 4), 0, 0);
        addr.addView(site);
        final TextView lan = Glass.text(c, "", 12.5f, Palette.text3(), false);
        lan.setPadding(0, Glass.dpi(c, 6), 0, 0);
        addr.addView(lan);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Glass.dpi(c, Glass.GAP);
        root.addView(addr, lp);

        // Tlacitka: vlevo systemove sdileni, vpravo Zavrit + hlavni akce
        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, Glass.dpi(c, Glass.SECTION), 0, 0);
        buttons.addView(Glass.button(c, "Sdílení Questu", false, v -> a.systemSharing()));
        buttons.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        buttons.addView(Glass.button(c, "Zavřít", false, v -> a.close()));
        final boolean[] running = {Cast.isRunning(c)};
        final TextView main = Glass.button(c, running[0] ? "Zastavit" : "Spustit vysílání", true, null);
        main.setOnClickListener(v -> {
            if (running[0]) a.stop();
            else a.start();
        });
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        mp.leftMargin = Glass.dpi(c, Glass.GAP_S);
        buttons.addView(main, mp);
        root.addView(buttons);

        // Obnova stavu kazdou sekundu, dokud je okno videt
        final ExecutorService io = Executors.newSingleThreadExecutor();
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (!root.isAttachedToWindow()) return;
            final boolean svc = Cast.isRunning(c);
            final String lanUrl = Cast.lanUrl();
            io.execute(() -> {
                final Cast.Status st = svc ? Cast.fetchStatus() : null;
                root.post(() -> {
                    running[0] = svc;
                    main.setText(svc ? "Zastavit" : "Spustit vysílání");
                    lan.setText(lanUrl == null ? "Quest není připojený k Wi-Fi"
                            : "nebo napřímo " + lanUrl.replace("http://", ""));
                    if (!svc) {
                        stTitle.setText("Nevysílá");
                        stInfo.setText("Spusť, a obraz půjde do prohlížeče");
                    } else if (st == null || !st.running) {
                        stTitle.setText("Spouštím…");
                        stInfo.setText(st != null && st.error != null ? st.error : "Chvilku strpení");
                    } else {
                        stTitle.setText("Vysílá");
                        stInfo.setText(info(st));
                    }
                    root.postDelayed(tick[0], 1000);
                });
            });
        };
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                root.post(tick[0]);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                root.removeCallbacks(tick[0]);
                io.shutdown();
            }
        });
        return root;
    }

    /** "60 fps · 2 diváci · zvuk se posílá" */
    private static String info(Cast.Status st) {
        StringBuilder sb = new StringBuilder();
        sb.append(Math.round(st.fps)).append(" fps · ");
        int n = st.viewers;
        sb.append(n == 0 ? "zatím se nikdo nedívá" : n == 1 ? "1 divák" : n < 5 ? n + " diváci" : n + " diváků");
        if (!"ok".equals(st.audio)) sb.append(" · bez zvuku");
        else if (st.listeners > 0) sb.append(" · zvuk se posílá");
        return sb.toString();
    }
}
