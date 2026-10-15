package com.neolauncher.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * Jednotne linkove ikony (sada Lucide, licence ISC - viz IconPaths): zaoblene
 * konce i rohy, tah 2 z 24. Ostre v jakekoliv velikosti, bez obrazku
 * v resources. Sdili je lista, rychle menu, nastaveni i dialogy.
 * Logo Neo je kreslene zvlast.
 */
public final class Icons {
    public static final int SUN = IconPaths.SUN;
    public static final int SPEAKER = IconPaths.SPEAKER;
    public static final int WIFI = IconPaths.WIFI;
    public static final int BLUETOOTH = IconPaths.BLUETOOTH;
    public static final int GEAR = IconPaths.GEAR;
    public static final int SLIDERS = IconPaths.SLIDERS;
    public static final int FOLDER = IconPaths.FOLDER;
    public static final int GLOBE = IconPaths.GLOBE;
    public static final int CAMERA = IconPaths.CAMERA;
    public static final int NEO = IconPaths.NEO;
    public static final int APERTURE = IconPaths.APERTURE;
    public static final int GLASS = IconPaths.GLASS;
    public static final int COLUMNS = IconPaths.COLUMNS;
    public static final int SPARKLE = IconPaths.SPARKLE;
    public static final int EXIT = IconPaths.EXIT;
    public static final int LAYERS = IconPaths.LAYERS;
    public static final int CAROUSEL = IconPaths.CAROUSEL;
    public static final int SORT = IconPaths.SORT;
    public static final int HOLD = IconPaths.HOLD;
    public static final int CLOUD = IconPaths.CLOUD;
    public static final int REFRESH = IconPaths.REFRESH;
    public static final int EYE_OFF = IconPaths.EYE_OFF;
    public static final int CHART = IconPaths.CHART;
    public static final int HEADSET = IconPaths.HEADSET;
    public static final int META = IconPaths.META;
    public static final int UPDATE = IconPaths.UPDATE;
    public static final int INFO = IconPaths.INFO;
    public static final int FLASK = IconPaths.FLASK;
    public static final int CLOSE = IconPaths.CLOSE;
    public static final int BLUR = IconPaths.BLUR;
    public static final int PALETTE = IconPaths.PALETTE;
    public static final int SEARCH = IconPaths.SEARCH;
    public static final int WIFI_OFF = IconPaths.WIFI_OFF;
    public static final int WIFI_LOW = IconPaths.WIFI_LOW;
    public static final int WIFI_HIGH = IconPaths.WIFI_HIGH;
    public static final int VOLUME_X = IconPaths.VOLUME_X;
    public static final int VOLUME_1 = IconPaths.VOLUME_1;
    public static final int VOLUME_2 = IconPaths.VOLUME_2;
    public static final int STAR = IconPaths.STAR;
    public static final int ZAP = IconPaths.ZAP;
    public static final int PLAY = IconPaths.PLAY;
    public static final int COLUMNS_3 = IconPaths.COLUMNS_3;
    public static final int CLOCK = IconPaths.CLOCK;
    public static final int CALENDAR = IconPaths.CALENDAR;
    public static final int DOWNLOAD = IconPaths.DOWNLOAD;
    public static final int CHEVRON_LEFT = IconPaths.CHEVRON_LEFT;
    public static final int PACKAGE_PLUS = IconPaths.PACKAGE_PLUS;
    public static final int ARCHIVE = IconPaths.ARCHIVE;
    public static final int ARCHIVE_RESTORE = IconPaths.ARCHIVE_RESTORE;
    public static final int DEPTH = IconPaths.DEPTH;
    public static final int POWER = IconPaths.POWER;
    public static final int CHECK = IconPaths.CHECK;
    public static final int ALERT = IconPaths.ALERT;
    public static final int BATTERY_CHARGING = IconPaths.BATTERY_CHARGING;
    public static final int CAST = IconPaths.CAST;
    public static final int MOON = IconPaths.MOON;

    /** Cesty v prostoru 24x24, rozparsovane pri prvnim pouziti. */
    private static final Path[] CACHE = new Path[IconPaths.DATA.length];

    private Icons() {}

    private static Path path(int id) {
        Path p = CACHE[id];
        if (p == null) {
            p = new Path();
            SvgPath.parse(IconPaths.DATA[id], SvgPath.of(p));
            CACHE[id] = p;
        }
        return p;
    }

    /** Ikona Wi-Fi podle sily signalu (0 = odpojeno, 1-4 = carky). */
    public static int wifi(int bars) {
        if (bars <= 0) return WIFI_OFF;
        if (bars == 1) return WIFI_LOW;
        if (bars == 2) return WIFI_HIGH;
        return WIFI;
    }

    /**
     * @param size   velikost ikony (px), ikona se vejde do ctverce size x size
     * @param stroke tloustka tahu (px) - Lucide ma 2 z 24, tj. size / 12
     * @param level  jen pro SPEAKER (0..1): ztlumeno / jedna / dve vlny
     */
    public static void draw(Canvas c, int id, float x, float y, float size, int color, float stroke,
                            float level, Paint p) {
        p.setShader(null);
        p.setColor(color);
        if (id == NEO) {
            drawNeo(c, x, y, size / 2f, color, p);
            return;
        }
        if (id == SPEAKER) id = level <= 0.01f ? VOLUME_X : level <= 0.5f ? VOLUME_1 : VOLUME_2;
        final float k = size / 24f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(stroke / k);
        c.save();
        c.translate(x - size / 2f, y - size / 2f);
        c.scale(k, k);
        c.drawPath(path(id), p);
        c.restore();
        // Sdileny Paint vratit do vyplne - jinak by se dalsi plochy (pilulky, stiny)
        // kreslily jen jako obrys.
        p.setStyle(Paint.Style.FILL);
    }

    /** Vyplnena ikona (napr. hvezda oblibene na karte). */
    public static void fill(Canvas c, int id, float x, float y, float size, int color, Paint p) {
        final float k = size / 24f;
        p.setShader(null);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL_AND_STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(2f);
        c.save();
        c.translate(x - size / 2f, y - size / 2f);
        c.scale(k, k);
        c.drawPath(path(id), p);
        c.restore();
        p.setStyle(Paint.Style.FILL);
    }

    /** Logo Neo: zaobleny tmavy ctverec s teckou (jako ikona aplikace). */
    private static void drawNeo(Canvas c, float x, float y, float s, int color, Paint p) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF000000);
        c.drawRoundRect(x - s * 0.85f, y - s * 0.85f, x + s * 0.85f, y + s * 0.85f, s * 0.42f, s * 0.42f, p);
        p.setColor(color);
        c.drawCircle(x, y, s * 0.36f, p);
    }
}
