package com.neolauncher.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.neolauncher.art.ArtworkLoader;
import com.neolauncher.LauncherActivity;
import com.neolauncher.MetaAddon;
import com.neolauncher.update.ApkInstaller;
import com.neolauncher.data.AppEntry;
import com.neolauncher.data.AppRepository;
import com.neolauncher.data.Platform;
import com.neolauncher.data.Prefs;
import com.neolauncher.data.UsageInfo;
import com.neolauncher.launch.AppLauncher;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Nastaveni jako dashboard z matneho skla (predlohy od uzivatele):
 * vlevo nadpis, kategorie jako pilulky a dlazdice s ikonou v kolecku,
 * prepinacem nebo volbami; vpravo widgety (datum, cas, Neo, system).
 * Otevira se klepnutim na logo Neo a "vyroste" z nej (OverlayHost).
 */
public final class SettingsSheet {
    private SettingsSheet() {}

    private static final String[] CATEGORIES = {"Vzhled", "Aplikace", "Quest", "Aktualizace", "O Neo"};

    public static View build(Context c, Prefs prefs, AppRepository repo, ArtworkLoader art,
                             Runnable onAppsChanged, Runnable onCheckUpdates, Runnable onWhatsNew,
                             Runnable onClose) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackground(Glass.panel(c));
        final int pad = Glass.dpi(c, Glass.PAD);
        root.setPadding(pad, pad, pad, pad);
        root.setMinimumHeight(Glass.dpi(c, 560));

        // --- Levy sloupec: nadpis, kategorie, dlazdice -----------------------------
        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        root.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        // Velky nadpis potrebuje vzduch: nahore vic nez PAD (u rohu R 40 jinak pusobi
        // namackane), pod podnadpisem SECTION k pilulkam.
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Glass.dpi(c, Glass.TITLE_INSET), Glass.dpi(c, Glass.TITLE_TOP - Glass.PAD), 0, 0);
        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Glass.text(c, "Nastavení", 30, Palette.TEXT, true));
        TextView sub = Glass.text(c, "Vzhled, aplikace i Quest na jednom místě", 13.5f, Palette.text2(), false);
        sub.setPadding(0, Glass.dpi(c, 4), 0, 0);
        titles.addView(sub);
        header.addView(titles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        View close = roundButton(c, Icons.CLOSE, v -> onClose.run());
        header.addView(close);
        left.addView(header);

        LinearLayout pills = new LinearLayout(c);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.topMargin = Glass.dpi(c, Glass.SECTION);
        left.addView(pills, plp);

        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        Glass.fadeEdges(scroll);
        final LinearLayout page = new LinearLayout(c);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, 0, 0, Glass.dpi(c, 4));
        scroll.addView(page);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = Glass.dpi(c, Glass.SECTION);
        left.addView(scroll, slp);

        final Ctx x = new Ctx(c, prefs, repo, art, onAppsChanged, onCheckUpdates, onWhatsNew);
        final TextView[] pillViews = new TextView[CATEGORIES.length];
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int idx = i;
            pillViews[i] = Glass.pill(c, CATEGORIES[i], i == 0, v -> {
                for (int j = 0; j < pillViews.length; j++) Glass.setPillSelected(c, pillViews[j], j == idx);
                showPage(x, page, idx);
                scroll.scrollTo(0, 0);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = Glass.dpi(c, Glass.GAP_S);
            pills.addView(pillViews[i], lp);
        }
        showPage(x, page, 0);

        // --- Pravy sloupec: widgety ------------------------------------------------
        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(Glass.dpi(c, 236),
                LinearLayout.LayoutParams.MATCH_PARENT);
        rlp.leftMargin = Glass.dpi(c, Glass.SECTION);
        root.addView(right, rlp);
        buildWidgets(x, right, onClose);
        return root;
    }

    /** Spolecne veci pro stavbu stranek. */
    private static final class Ctx {
        final Context c;
        final Prefs prefs;
        final AppRepository repo;
        final ArtworkLoader art;
        final Runnable onAppsChanged, onCheckUpdates, onWhatsNew;

        Ctx(Context c, Prefs prefs, AppRepository repo, ArtworkLoader art, Runnable onAppsChanged,
            Runnable onCheckUpdates, Runnable onWhatsNew) {
            this.c = c;
            this.prefs = prefs;
            this.repo = repo;
            this.art = art;
            this.onAppsChanged = onAppsChanged;
            this.onCheckUpdates = onCheckUpdates;
            this.onWhatsNew = onWhatsNew;
        }
    }

    private static void showPage(Ctx x, LinearLayout page, int idx) {
        page.removeAllViews();
        switch (idx) {
            case 0:
                pageLook(x, page);
                break;
            case 1:
                pageApps(x, page);
                break;
            case 2:
                pageQuest(x, page);
                break;
            case 3:
                pageUpdates(x, page);
                break;
            default:
                pageAbout(x, page);
                break;
        }
        // Dlazdice nastoupi postupne (kaskada).
        List<View> tiles = new ArrayList<>();
        for (int i = 0; i < page.getChildCount(); i++) {
            View rowView = page.getChildAt(i);
            if (rowView instanceof LinearLayout && ((LinearLayout) rowView).getOrientation() == LinearLayout.HORIZONTAL) {
                LinearLayout row = (LinearLayout) rowView;
                for (int j = 0; j < row.getChildCount(); j++) tiles.add(row.getChildAt(j));
            } else {
                tiles.add(rowView);
            }
        }
        Cascade.play(page, tiles, Glass.dp(x.c, 14));
    }

    // =========================================================================
    // Stranky
    // =========================================================================

    private static void pageLook(Ctx x, LinearLayout page) {
        final Context c = x.c;
        final Prefs prefs = x.prefs;
        grid(c, page,
                wide(c, Icons.APERTURE, "Hloubka ostrosti", "Rozmazání okolních karet, když na hru míříš",
                        Glass.segmented(c, new String[]{"Vypnuto", "Jemná", "Silná"}, prefs.dofMode(),
                                prefs::setDofMode)),
                wide(c, Icons.PALETTE, "Styl skla", "Tmavé matné sklo, nebo světlejší šedé",
                        Glass.segmented(c, new String[]{"Tmavé", "Světlé"}, prefs.glassStyle(),
                                prefs::setGlassStyle)));

        final int[] colOptions = {3, 4, 5, 6};
        int colSel = 1;
        for (int i = 0; i < colOptions.length; i++) if (colOptions[i] == prefs.columns()) colSel = i;
        grid(c, page,
                wide(c, Icons.GLASS, "Krytí skla", "Jak moc je panel průhledný",
                        new GlassWidgets.Slider(c, "Krytí", 5, 100, prefs.glassAlpha(), " %", prefs::setGlassAlpha)),
                wide(c, Icons.COLUMNS, "Počet sloupců", "Kolik karet je vedle sebe",
                        Glass.segmented(c, new String[]{"3", "4", "5", "6"}, colSel,
                                i -> prefs.setColumns(colOptions[i]))));

        grid(c, page,
                toggleTile(c, Icons.SPARKLE, "Kukátko", "Animace spuštění – hrou projdeš jako kukátkem",
                        prefs.launchAnimation(), prefs::setLaunchAnimation),
                toggleTile(c, Icons.EXIT, "Zavřít po spuštění", "Launcher se zavře, jakmile hra naběhne",
                        prefs.closeAfterLaunch(), prefs::setCloseAfterLaunch),
                toggleTile(c, Icons.LAYERS, "Karty vyskakují", "Zvětšená karta může přesáhnout panel",
                        prefs.popoutMargin(), prefs::setPopoutMargin));
        grid(c, page,
                toggleTile(c, Icons.CAROUSEL, "Karusel (test)", "Karty ve vějíři. Taky 5× klepnout na logo",
                        prefs.carouselMode(), prefs::setCarouselMode),
                toggleTile(c, Icons.INFO, "Nápověda v karuselu", "Dole v karuselu ukázat, jak se ovládá",
                        prefs.carouselHint(), prefs::setCarouselHint),
                null);
    }

    private static void pageApps(Ctx x, LinearLayout page) {
        final Context c = x.c;
        final Prefs prefs = x.prefs;
        final int[] holdOptions = {700, 1000, 1500};
        int holdSel = 1;
        for (int i = 0; i < holdOptions.length; i++) if (holdOptions[i] == prefs.menuHoldMs()) holdSel = i;
        // Razeni pres celou sirku (6 moznosti). Oblibene jsou vzdy nahore.
        grid(c, page,
                wide(c, Icons.SORT, "Řazení", "Chytré = nové aplikace první, pak podle herního času. "
                                + "Velikost = největší první. Oblíbené (★ v menu karty) jsou vždy nahoře.",
                        Glass.segmented(c, new String[]{"Vlastní", "Abecedně", "Naposledy", "Nejhranější", "Chytré",
                                        "Velikost"},
                                prefs.sortMode(), m -> {
                                    prefs.setSortMode(m);
                                    x.onAppsChanged.run();
                                })));
        grid(c, page,
                wide(c, Icons.HOLD, "Podržení pro menu", "Jak dlouho držet kartu bez pohybu",
                        Glass.segmented(c, new String[]{"0,7 s", "1 s", "1,5 s"}, holdSel,
                                i -> prefs.setMenuHoldMs(holdOptions[i]))),
                infoTile(c, Icons.CHART, "Nejhranější a Chytré", "Herní čas bere z Questu – "
                        + "potřebuje „Přístup k využití“ (dlaždice Herní čas níže)."));

        final boolean usageOk = new UsageInfo(c).hasPermission();
        grid(c, page,
                toggleTile(c, Icons.CLOUD, "Obrázky z internetu", "Chybějící bannery her se stáhnou z veřejných databází",
                        prefs.onlineArt(), prefs::setOnlineArt),
                actionTile(c, Icons.REFRESH, "Obrázky znovu", "Smaže stažené bannery a stáhne je znovu",
                        "Stáhnout", v -> {
                            x.art.clearDownloaded();
                            Toast.makeText(c, "Obrázky se stahují znovu", Toast.LENGTH_SHORT).show();
                        }),
                actionTile(c, Icons.CHART, "Herní čas", usageOk ? "Povoleno – karusel ukazuje odehraný čas"
                                : "Potřebuje „Přístup k využití“", usageOk ? "Nastavení" : "Povolit",
                        v -> {
                            if (!(c instanceof Activity) || !UsageInfo.requestPermission((Activity) c)) {
                                Toast.makeText(c, "Z PC: adb shell appops set " + c.getPackageName()
                                        + " GET_USAGE_STATS allow", Toast.LENGTH_LONG).show();
                            }
                        }));

        // Sideload bez PC: APK stazene v prohlizeci Questu.
        grid(c, page,
                actionTile(c, Icons.PACKAGE_PLUS, "Nainstalovat APK", "Soubor stažený třeba v prohlížeči Questu – bez PC",
                        "Vybrat", v -> startForResult(c, ApkInstaller.pickIntent(), LauncherActivity.REQ_PICK_APK)),
                null, null);

        // Skryte aplikace pres celou sirku.
        LinearLayout hiddenTile = tileBase(c);
        hiddenTile.addView(tileHeader(c, Icons.EYE_OFF, "Skryté aplikace", "Skrýt jde v menu karty (podržet kartu)", null));
        final LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        fillHidden(x, list);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        llp.topMargin = Glass.dpi(c, 8);
        hiddenTile.addView(list, llp);
        addRow(c, page, hiddenTile);
    }

    private static void pageQuest(Ctx x, LinearLayout page) {
        final Context c = x.c;
        final Prefs prefs = x.prefs;
        final boolean blendOk = Platform.supportsBlendEffects();
        grid(c, page,
                toggleTile(c, Icons.BLUR, "Rozmazat prostředí", blendOk
                                ? "Systémové matné sklo za panelem (projeví se po novém otevření)"
                                : "Toto zařízení to nepodporuje (Quest 3/3S)",
                        prefs.systemBlur(), prefs::setSystemBlur),
                actionTile(c, Icons.HEADSET, "Nastavení Questu", "Systémová nastavení headsetu",
                        "Otevřít", v -> {
                            if (!(c instanceof Activity) || !AppLauncher.launch((Activity) c,
                                    new AppEntry("systemux://settings", "Nastavení Questu", AppEntry.TYPE_PANEL, false))) {
                                Toast.makeText(c, "Na tomto zařízení není k dispozici", Toast.LENGTH_SHORT).show();
                            }
                        }),
                actionTile(c, Icons.GEAR, "Android nastavení", "Schovaná nastavení Androidu (oprávnění, přístupnost, aplikace)",
                        "Otevřít", v -> {
                            if (!(c instanceof Activity) || !AppLauncher.openAndroidSettings((Activity) c)) {
                                Toast.makeText(c, "Na tomto zařízení není k dispozici", Toast.LENGTH_SHORT).show();
                            }
                        }));

        // Meta tlacitko: doplnek "Neo - Meta tlacitko" (jako u Lightning Launcheru) - Neo ho
        // samo nainstaluje (jinak by ho Android kvuli "Omezenemu nastaveni" nepustil zapnout)
        // a pak se zapne v Pristupnosti Androidu.
        final boolean installed = MetaAddon.isInstalled(c);
        final boolean update = installed && MetaAddon.needsUpdate(c);
        final boolean metaOn = installed && MetaAddon.isEnabled(c);
        // Hru v popredi pozna doplnek z UsageStats v Neu (udalosti oken ve hre klamou).
        final boolean usageOk = com.neolauncher.data.ForegroundApps.query(c).known;
        final String metaDesc;
        final String metaButton;
        if (!installed) {
            metaDesc = "Nainstaluj malý doplněk „Neo – Meta tlačítko“ (jako u Lightning Launcheru)";
            metaButton = "Nainstalovat";
        } else if (update) {
            metaDesc = "Je nová verze doplňku Meta tlačítka";
            metaButton = "Aktualizovat";
        } else if (!metaOn) {
            metaDesc = "V Přístupnosti Androidu zapni „Neo – Meta tlačítko“";
            metaButton = "Zapnout";
        } else if (!usageOk) {
            metaDesc = "Zapnuto. Ať ve hře pozná hru, povol Neu herní čas (Přístup k využití)";
            metaButton = "Povolit";
        } else {
            metaDesc = "Zapnuto. Když je Neo otevřené, další zmáčknutí ukáže menu Questu";
            metaButton = "Nastavení";
        }
        grid(c, page,
                actionTile(c, Icons.META, "Meta tlačítko otevře Neo", metaDesc, metaButton, v -> {
                    if (!(c instanceof LauncherActivity)) return;
                    final LauncherActivity a = (LauncherActivity) c;
                    if (!installed || update) a.installMetaAddon();
                    else if (!metaOn) a.enableMetaAddon();
                    else if (!usageOk) UsageInfo.requestPermission(a);
                    else if (!MetaAddon.openSettings(a)) {
                        Toast.makeText(c, "Přístupnost nejde otevřít", Toast.LENGTH_SHORT).show();
                    }
                }),
                toggleTile(c, Icons.PLAY, "Ve hře menu Questu", "Meta ve hře ukáže menu Questu (Pokračovat / Ukončit), ne Neo",
                        prefs.metaGameMenu(), prefs::setMetaGameMenu),
                toggleTile(c, Icons.EXIT, "Po hře otevřít Neo", "Když hru ukončíš, rovnou se ukáže Neo",
                        prefs.metaAfterGame(), prefs::setMetaAfterGame));
        grid(c, page,
                toggleTile(c, Icons.LAYERS, "3× Meta = zpět do Nea",
                        "Ve hře třikrát rychle zmáčkni Meta: otevře se Neo a dole Pokračovat / Ukončit",
                        prefs.metaTriple(), prefs::setMetaTriple),
                toggleTile(c, Icons.POWER, "Po zapnutí Questu otevřít Neo",
                        metaOn ? "Po startu headsetu se rovnou ukáže Neo"
                                : "Potřebuje doplněk Meta tlačítka (vlevo)",
                        prefs.openOnBoot(), prefs::setOpenOnBoot),
                actionTile(c, Icons.MOON, "Uspat, restartovat, vypnout",
                        metaOn ? "Taky v rychlém menu (dlaždice Vypnout)" : "Potřebuje doplněk Meta tlačítka (vlevo)",
                        "Otevřít", v -> {
                            if (c instanceof LauncherActivity) ((LauncherActivity) c).showPowerSheet();
                        }));
        boolean canWrite;
        try {
            canWrite = Settings.System.canWrite(c);
        } catch (Exception e) {
            canWrite = false;
        }
        grid(c, page,
                actionTile(c, Icons.SUN, "Ovládání jasu", canWrite ? "Povoleno – jas jde měnit v rychlém menu"
                                : "Posuvník jasu potřebuje „Úpravu systémových nastavení“",
                        canWrite ? "Nastavení" : "Povolit", v -> {
                            try {
                                Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                        Uri.parse("package:" + c.getPackageName()));
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                c.startActivity(AppLauncher.inAndroidSettings(c, i));
                            } catch (Exception e) {
                                Toast.makeText(c, "Z PC: adb shell appops set " + c.getPackageName()
                                        + " WRITE_SETTINGS allow", Toast.LENGTH_LONG).show();
                            }
                        }),
                infoTile(c, Icons.SLIDERS, "Rychlé menu", "Klepni na hodiny v horní liště – jas, hlasitost, Wi-Fi a funkce Questu"),
                toggleTile(c, Icons.ALERT, "Upozornění na baterii",
                        metaOn ? "Slabá baterie (20 a 10 %) a nabito na 100 % – i ve hře"
                                : "Potřebuje doplněk Meta tlačítka",
                        prefs.batteryAlerts(), on -> {
                            prefs.setBatteryAlerts(on);
                            // Android 13+: doplnek se musi zeptat na povoleni oznameni.
                            if (on && metaOn && c instanceof Activity && !MetaAddon.canNotify(c)) {
                                MetaAddon.requestNotifications((Activity) c);
                            }
                        }));
    }

    private static void pageUpdates(Ctx x, LinearLayout page) {
        final Context c = x.c;
        final Prefs prefs = x.prefs;
        grid(c, page,
                actionTile(c, Icons.UPDATE, "Neo " + version(c), "Nové verze se stahují z GitHubu (kontrola i při otevření)",
                        "Zkontrolovat", v -> x.onCheckUpdates.run()),
                toggleTile(c, Icons.FLASK, "Testovací verze", "Nabízet i buildy z vývojových větví",
                        prefs.updateTestBuilds(), prefs::setUpdateTestBuilds),
                actionTile(c, Icons.SPARKLE, "Co je nového", "Novinky v téhle verzi Nea",
                        "Zobrazit", v -> x.onWhatsNew.run()));
        grid(c, page,
                infoTile(c, Icons.INFO, "Jak to funguje", "Každá změna na GitHubu vytvoří novou verzi. "
                        + "Launcher ji najde, ukáže, co je nového, a po potvrzení nainstaluje – nastavení zůstane."));
    }

    private static void pageAbout(Ctx x, LinearLayout page) {
        final Context c = x.c;
        // Zaloha: vsechna nastaveni, poradi, oblibene, prejmenovani a vlastni obrazky v jednom souboru.
        grid(c, page,
                actionTile(c, Icons.ARCHIVE, "Zálohovat nastavení", "Pořadí, oblíbené, jména i vlastní obrázky do jednoho souboru",
                        "Uložit", v -> startForResult(c, LauncherActivity.backupExportIntent(), LauncherActivity.REQ_EXPORT)),
                actionTile(c, Icons.ARCHIVE_RESTORE, "Obnovit ze zálohy", "Po přeinstalaci, resetu Questu nebo od kamaráda",
                        "Vybrat", v -> startForResult(c, LauncherActivity.backupImportIntent(), LauncherActivity.REQ_IMPORT)),
                null);
        grid(c, page,
                infoTile(c, Icons.NEO, "Neo Launcher " + version(c), "Vlastní launcher pro Meta Quest 3S – "
                        + "sklo, karty vyskakující do prostoru, kukátko, karusel a rychlé menu."));
        grid(c, page,
                infoTile(c, Icons.HEADSET, "Lightning Launcher", "Načítání obrázků, rozpoznání her a spouštění "
                        + "vychází z Lightning Launcheru (threethan)."),
                infoTile(c, Icons.INFO, "Licence", "GPL-3.0 – zdrojový kód je veřejný na GitHubu "
                        + "(lichicks/neo-launcher). Ikony: Lucide (licence ISC)."));
    }

    private static void fillHidden(Ctx x, LinearLayout list) {
        final Context c = x.c;
        list.removeAllViews();
        List<String> hidden = new ArrayList<>(x.prefs.hidden());
        Collections.sort(hidden);
        if (hidden.isEmpty()) {
            list.addView(Glass.text(c, "Žádné skryté aplikace", 13, Palette.text3(), false));
            return;
        }
        for (String pkg : hidden) {
            AppEntry e = x.repo.find(pkg);
            String label = e != null ? x.prefs.labelFor(e) : pkg;
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Glass.dpi(c, 4), 0, Glass.dpi(c, 4));
            TextView t = Glass.text(c, label, 14, Color.WHITE, true);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(Glass.button(c, "Zobrazit", false, v -> {
                x.prefs.setHidden(pkg, false);
                x.onAppsChanged.run();
                fillHidden(x, list);
            }));
            list.addView(row);
        }
    }

    // =========================================================================
    // Widgety vpravo
    // =========================================================================

    private static void buildWidgets(Ctx x, LinearLayout right, Runnable onClose) {
        final Context c = x.c;
        final Calendar cal = Calendar.getInstance();
        final String[] days = {"neděle", "pondělí", "úterý", "středa", "čtvrtek", "pátek", "sobota"};
        final String[] months = {"ledna", "února", "března", "dubna", "května", "června", "července",
                "srpna", "září", "října", "listopadu", "prosince"};
        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        // Datum = den a mesic ("27 / zari"), cas = s nazvem dne ("14:59 / nedele").
        top.addView(bigWidget(c, String.valueOf(cal.get(Calendar.DAY_OF_MONTH)), months[cal.get(Calendar.MONTH)]),
                new LinearLayout.LayoutParams(0, Glass.dpi(c, 112), 1f));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, Glass.dpi(c, 112), 1f);
        tlp.leftMargin = Glass.dpi(c, Glass.GAP);
        top.addView(bigWidget(c, String.format(Locale.ROOT, "%d:%02d", cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE)), days[cal.get(Calendar.DAY_OF_WEEK) - 1]), tlp);
        right.addView(top);

        // Neo + aktualizace.
        LinearLayout neo = new LinearLayout(c);
        neo.setOrientation(LinearLayout.VERTICAL);
        neo.setBackground(Glass.widget(c));
        final int p = Glass.dpi(c, Glass.TILE_PAD);
        neo.setPadding(p, p, p, p);
        neo.addView(new GlassWidgets.IconView(c, Icons.NEO, 40, Color.WHITE));
        TextView nt = Glass.text(c, "Neo " + version(c), 17, Color.WHITE, true);
        nt.setPadding(0, Glass.dpi(c, 10), 0, 0);
        neo.addView(nt);
        neo.addView(Glass.text(c, "Launcher pro Meta Quest 3S", 12.5f, Palette.text2(), false));
        TextView upd = Glass.button(c, "Aktualizace", false, v -> x.onCheckUpdates.run());
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ulp.topMargin = Glass.dpi(c, Glass.GAP);
        ulp.gravity = Gravity.END;
        neo.addView(upd, ulp);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = Glass.dpi(c, Glass.GAP);
        right.addView(neo, nlp);

        // System: aplikace a uloziste.
        int games = 0, apps = 0;
        for (AppEntry e : x.repo.apps()) {
            if (e.isVr()) games++;
            else apps++;
        }
        long free = 0, total = 0;
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            free = fs.getAvailableBytes();
            total = fs.getTotalBytes();
        } catch (Exception ignored) {
        }
        LinearLayout sys = new LinearLayout(c);
        sys.setOrientation(LinearLayout.VERTICAL);
        sys.setBackground(Glass.widget(c));
        sys.setPadding(p, p, p, p);
        sys.addView(Glass.text(c, games + " her · " + apps + " aplikací", 15, Color.WHITE, true));
        if (total > 0) {
            TextView st = Glass.text(c, "Volné místo " + Math.round(free / 1e9) + " z " + Math.round(total / 1e9) + " GB",
                    12.5f, Palette.text2(), false);
            st.setPadding(0, Glass.dpi(c, 4), 0, Glass.dpi(c, 8));
            sys.addView(st);
            final float used = 1f - free / (float) total;
            sys.addView(new Bar(c, used), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    Glass.dpi(c, 6)));
        }
        LinearLayout.LayoutParams sylp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sylp.topMargin = Glass.dpi(c, Glass.GAP);
        right.addView(sys, sylp);

        right.addView(new View(c), new LinearLayout.LayoutParams(1, 0, 1f));
        TextView done = Glass.button(c, "Hotovo", true, v -> onClose.run());
        done.setPadding(0, 0, 0, 0);
        right.addView(done, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Glass.dpi(c, Glass.BUTTON_H)));
    }

    private static View bigWidget(Context c, String big, String small) {
        LinearLayout w = new LinearLayout(c);
        w.setOrientation(LinearLayout.VERTICAL);
        w.setGravity(Gravity.CENTER);
        w.setBackground(Glass.widget(c));
        TextView b = Glass.text(c, big, 30, Color.WHITE, true);
        b.setFontFeatureSettings("tnum");
        b.setGravity(Gravity.CENTER);
        w.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView s = Glass.text(c, small, 13, Palette.text2(), false);
        s.setGravity(Gravity.CENTER);
        w.addView(s, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return w;
    }

    /** Tenky ukazatel zaplneni uloziste. */
    private static final class Bar extends View {
        private final float value;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Bar(Context c, float value) {
            super(c);
            this.value = Math.max(0.02f, Math.min(1f, value));
        }

        @Override
        protected void onDraw(Canvas cv) {
            final float h = getHeight(), r = h / 2f;
            paint.setColor(0x33FFFFFF);
            cv.drawRoundRect(0, 0, getWidth(), h, r, r, paint);
            paint.setColor(value > 0.9f ? Palette.AMBER : Palette.PEARL);
            cv.drawRoundRect(0, 0, getWidth() * value, h, r, r, paint);
        }
    }

    // =========================================================================
    // Dlazdice
    // =========================================================================

    private static LinearLayout tileBase(Context c) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackground(Glass.tile(c));
        final int p = Glass.dpi(c, Glass.TILE_PAD);
        t.setPadding(p, p, p, p);
        return t;
    }

    /** Horni cast dlazdice: ikona v kolecku, vpravo volitelny prvek; pod tim nazev a popis. */
    private static View tileHeader(Context c, int icon, String title, String sub, View corner) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(new GlassWidgets.IconView(c, icon, 40, Color.WHITE));
        top.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        if (corner != null) top.addView(corner);
        box.addView(top);
        TextView t = Glass.text(c, title, 15.5f, Color.WHITE, true);
        t.setPadding(0, Glass.dpi(c, 12), 0, 0);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(t);
        if (sub != null) {
            TextView s = Glass.text(c, sub, 12.5f, Palette.text2(), false);
            s.setPadding(0, Glass.dpi(c, 3), 0, 0);
            s.setMaxLines(3);
            s.setEllipsize(TextUtils.TruncateAt.END);
            box.addView(s);
        }
        return box;
    }

    /** Dlazdice s prepinacem - klepnuti kamkoliv na dlazdici prepne (velky cil pro laser). */
    private static View toggleTile(Context c, int icon, String title, String sub, boolean on,
                                   java.util.function.Consumer<Boolean> onChange) {
        LinearLayout t = tileBase(c);
        final GlassWidgets.Toggle tg = Glass.toggle(c, on, onChange);
        t.addView(tileHeader(c, icon, title, sub, tg));
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(v -> tg.toggle());
        return t;
    }

    /** Dlazdice s tlacitkem v rohu (klepnuti na dlazdici = tlacitko). */
    private static View actionTile(Context c, int icon, String title, String sub, String button,
                                   View.OnClickListener l) {
        LinearLayout t = tileBase(c);
        TextView b = Glass.button(c, button, false, l);
        b.setPadding(Glass.dpi(c, 14), Glass.dpi(c, 6), Glass.dpi(c, 14), Glass.dpi(c, 6));
        b.setTextSize(13);
        t.addView(tileHeader(c, icon, title, sub, b));
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(l);
        return t;
    }

    private static View infoTile(Context c, int icon, String title, String sub) {
        LinearLayout t = tileBase(c);
        t.setBackground(Glass.widget(c));
        t.addView(tileHeader(c, icon, title, sub, null));
        return t;
    }

    /** Siroka dlazdice: ikona + texty, pod nimi ovladaci prvek (volby, posuvnik). */
    private static View wide(Context c, int icon, String title, String sub, View control) {
        LinearLayout t = tileBase(c);
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(new GlassWidgets.IconView(c, icon, 40, Color.WHITE));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Glass.dpi(c, 12), 0, 0, 0);
        TextView tt = Glass.text(c, title, 15.5f, Color.WHITE, true);
        texts.addView(tt);
        TextView s = Glass.text(c, sub, 12.5f, Palette.text2(), false);
        s.setMaxLines(2);
        s.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(s);
        head.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        t.addView(head);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                control instanceof GlassWidgets.Slider ? LinearLayout.LayoutParams.MATCH_PARENT
                        : LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Glass.dpi(c, Glass.GAP);
        t.addView(control, lp);
        return t;
    }

    /** Rada dlazdic stejne sirky (null = prazdne misto, at maji dlazdice stale stejnou sirku). */
    private static void grid(Context c, LinearLayout page, View... tiles) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final int gap = Glass.dpi(c, Glass.GAP);
        for (int i = 0; i < tiles.length; i++) {
            View t = tiles[i] != null ? tiles[i] : new View(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) lp.leftMargin = gap;
            row.addView(t, lp);
        }
        addRow(c, page, row);
    }

    private static void addRow(Context c, LinearLayout page, View row) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        if (page.getChildCount() > 0) lp.topMargin = Glass.dpi(c, Glass.GAP);
        page.addView(row, lp);
    }

    /** Systemovy vyber souboru; vysledek zpracuje LauncherActivity.onActivityResult. */
    @SuppressWarnings("deprecation")
    private static void startForResult(Context c, Intent i, int request) {
        if (!(c instanceof Activity)) return;
        try {
            ((Activity) c).startActivityForResult(i, request);
        } catch (Exception e) {
            Toast.makeText(c, "Výběr souborů tu není k dispozici", Toast.LENGTH_LONG).show();
        }
    }

    /** Kulate sklenene tlacitko s ikonou (zavrit). */
    private static View roundButton(Context c, int icon, View.OnClickListener l) {
        GlassWidgets.IconView v = new GlassWidgets.IconView(c, icon, 40, Color.WHITE);
        v.setClickable(true);
        v.setFocusable(true);
        v.setOnClickListener(l);
        return v;
    }

    private static String version(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "2.0";
        } catch (Exception e) {
            return "?";
        }
    }
}
