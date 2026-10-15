package com.neolauncher.data;

/** Jedna aplikace v mrizce. Nemenny objekt - pri zmene se vytvori novy. */
public final class AppEntry {
    /** VR hra / immersivni aplikace. */
    public static final int TYPE_VR = 0;
    /** Klasicka 2D Android aplikace (otevira se v panelu). */
    public static final int TYPE_2D = 1;
    /** Systemovy panel Questu (napr. systemux://settings). */
    public static final int TYPE_PANEL = 2;

    public final String pkg;
    /** Nazev ze systemu (bez prejmenovani uzivatelem). */
    public final String systemLabel;
    public final int type;
    /** Ma aplikace vlastni TV banner (android:banner)? */
    public final boolean hasBanner;
    /** Kdy byla aplikace poprve nainstalovana (ms), 0 = nezname. Pro stitek "Nove". */
    public final long installTime;

    public AppEntry(String pkg, String systemLabel, int type, boolean hasBanner) {
        this(pkg, systemLabel, type, hasBanner, 0L);
    }

    public AppEntry(String pkg, String systemLabel, int type, boolean hasBanner, long installTime) {
        this.pkg = pkg;
        this.systemLabel = systemLabel;
        this.type = type;
        this.hasBanner = hasBanner;
        this.installTime = installTime;
    }

    public boolean isVr() {
        return type == TYPE_VR;
    }

    public boolean isSystemPanel() {
        return pkg.startsWith("systemux://");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppEntry)) return false;
        AppEntry e = (AppEntry) o;
        return pkg.equals(e.pkg) && systemLabel.equals(e.systemLabel)
                && type == e.type && hasBanner == e.hasBanner && installTime == e.installTime;
    }

    @Override
    public int hashCode() {
        return pkg.hashCode();
    }

    @Override
    public String toString() {
        return pkg + " (" + systemLabel + ")";
    }
}
