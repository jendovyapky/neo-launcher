#!/usr/bin/env python3
"""
Vygeneruje app/src/main/java/com/neolauncher/ui/IconPaths.java z ikon Lucide
(https://lucide.dev, licence ISC; cast odvozena z Feather, MIT).

Kazda ikona = jeden retezec SVG path v prostoru 24x24 (kruhy, obdelniky,
cary a lomene cary se prevedou na path). Kresli se tahem 2 jednotky se
zaoblenymi konci (Icons.draw), stejne jako v Lucide.

Pouziti: python3 tools/icons/gen_icons.py <cesta k lucide/icons>
  git clone --depth 1 --filter=blob:none --sparse https://github.com/lucide-icons/lucide.git
  cd lucide && git sparse-checkout set icons
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# id v Icons.java -> nazev ikony v Lucide (None = kreslena kodem, napr. logo Neo)
ICONS = [
    ("SUN", "sun"),
    ("SPEAKER", "volume"),
    ("WIFI", "wifi"),
    ("BLUETOOTH", "bluetooth"),
    ("GEAR", "settings"),
    ("SLIDERS", "sliders-horizontal"),
    ("FOLDER", "folder"),
    ("GLOBE", "globe"),
    ("CAMERA", "camera"),
    ("NEO", None),
    ("APERTURE", "aperture"),
    ("GLASS", "blend"),
    ("COLUMNS", "layout-grid"),
    ("SPARKLE", "sparkle"),
    ("EXIT", "log-out"),
    ("LAYERS", "layers"),
    ("CAROUSEL", "gallery-horizontal"),
    ("SORT", "arrow-down-wide-narrow"),
    ("HOLD", "timer"),
    ("CLOUD", "cloud-download"),
    ("REFRESH", "refresh-cw"),
    ("EYE_OFF", "eye-off"),
    ("CHART", "chart-no-axes-column"),
    ("HEADSET", "rectangle-goggles"),
    ("META", "circle-dot"),
    ("UPDATE", "circle-arrow-up"),
    ("INFO", "info"),
    ("FLASK", "flask-conical"),
    ("CLOSE", "x"),
    ("BLUR", "circle-dashed"),
    ("PALETTE", "palette"),
    ("SEARCH", "search"),
    ("WIFI_OFF", "wifi-off"),
    ("WIFI_LOW", "wifi-low"),
    ("WIFI_HIGH", "wifi-high"),
    ("VOLUME_X", "volume-x"),
    ("VOLUME_1", "volume-1"),
    ("VOLUME_2", "volume-2"),
    ("STAR", "star"),
    ("ZAP", "zap"),
    ("PLAY", "play"),
    ("COLUMNS_3", "columns-3"),
    ("CLOCK", "clock"),
    ("CALENDAR", "calendar"),
    ("DOWNLOAD", "download"),
    ("CHEVRON_LEFT", "chevron-left"),
    ("PACKAGE_PLUS", "package-plus"),
    ("ARCHIVE", "archive"),
    ("ARCHIVE_RESTORE", "archive-restore"),
    ("DEPTH", "move-3d"),
    ("POWER", "power"),
    ("CHECK", "circle-check"),
    ("ALERT", "circle-alert"),
    ("BATTERY_CHARGING", "battery-charging"),
    ("CAST", "cast"),
    ("MOON", "moon"),
]


def num(v):
    f = float(v)
    s = ("%.3f" % f).rstrip("0").rstrip(".")
    return s if s not in ("-0", "") else "0"


NUM = re.compile(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")


def absolute_start(d):
    """Relativni 'm' na zacatku samostatne cesty je v SVG absolutni. Po spojeni
    vice cest do jedne by se ale bral relativne k predchozi - prevest na 'M x y'
    (dalsi dvojice po 'm' jsou relativni 'l')."""
    d = d.strip()
    if not d.startswith("m"):
        return d
    m = re.match(r"m([^a-zA-Z]*)", d)
    nums = NUM.findall(m.group(1))
    rest = d[m.end():]
    out = "M%s %s" % (nums[0], nums[1])
    if len(nums) > 2:
        out += "l" + " ".join(nums[2:])
    return out + rest


def element_to_path(el):
    tag = el.tag.split("}")[-1]
    a = el.attrib
    if tag == "path":
        return absolute_start(a["d"])
    if tag == "circle":
        cx, cy, r = float(a["cx"]), float(a["cy"]), float(a["r"])
        return "M%s %sa%s %s 0 1 0 %s 0a%s %s 0 1 0 %s 0" % (
            num(cx - r), num(cy), num(r), num(r), num(2 * r), num(r), num(r), num(-2 * r))
    if tag == "ellipse":
        cx, cy, rx, ry = float(a["cx"]), float(a["cy"]), float(a["rx"]), float(a["ry"])
        return "M%s %sa%s %s 0 1 0 %s 0a%s %s 0 1 0 %s 0" % (
            num(cx - rx), num(cy), num(rx), num(ry), num(2 * rx), num(rx), num(ry), num(-2 * rx))
    if tag == "rect":
        x, y = float(a.get("x", 0)), float(a.get("y", 0))
        w, h = float(a["width"]), float(a["height"])
        rx = float(a.get("rx", a.get("ry", 0)))
        ry = float(a.get("ry", rx))
        rx, ry = min(rx, w / 2), min(ry, h / 2)
        if rx <= 0:
            return "M%s %sh%sv%sh%sz" % (num(x), num(y), num(w), num(h), num(-w))
        return ("M%s %sh%sa%s %s 0 0 1 %s %sv%sa%s %s 0 0 1 %s %sh%sa%s %s 0 0 1 %s %sv%sa%s %s 0 0 1 %s %sz" % (
            num(x + rx), num(y), num(w - 2 * rx), num(rx), num(ry), num(rx), num(ry),
            num(h - 2 * ry), num(rx), num(ry), num(-rx), num(ry),
            num(-(w - 2 * rx)), num(rx), num(ry), num(-rx), num(-ry),
            num(-(h - 2 * ry)), num(rx), num(ry), num(rx), num(-ry)))
    if tag == "line":
        return "M%s %sL%s %s" % (num(a["x1"]), num(a["y1"]), num(a["x2"]), num(a["y2"]))
    if tag in ("polyline", "polygon"):
        pts = [p for p in re.split(r"[\s,]+", a["points"].strip()) if p]
        pairs = ["%s %s" % (num(pts[i]), num(pts[i + 1])) for i in range(0, len(pts) - 1, 2)]
        d = "M" + pairs[0] + "".join("L" + p for p in pairs[1:])
        return d + ("z" if tag == "polygon" else "")
    raise ValueError("neznamy prvek " + tag)


def icon_path(folder, name):
    root = ET.parse(folder / (name + ".svg")).getroot()
    parts = [element_to_path(el) for el in root.iter() if el is not root]
    return "".join(parts)


def main():
    folder = Path(sys.argv[1])
    repo = Path(__file__).resolve().parents[2]
    out = repo / "app/src/main/java/com/neolauncher/ui/IconPaths.java"
    lines = []
    for const, name in ICONS:
        if name is None:
            lines.append("            null, // %s (kresleno kodem)" % const)
        else:
            lines.append('            "%s", // %s = %s' % (icon_path(folder, name), const, name))
    consts = "\n".join("    static final int %s = %d;" % (c, i) for i, (c, _) in enumerate(ICONS))
    java = '''package com.neolauncher.ui;

/*
 * VYGENEROVANO tools/icons/gen_icons.py - neupravovat rucne.
 *
 * Ikony Lucide (https://lucide.dev), ISC License,
 * Copyright (c) Lucide Icons and Contributors.
 * Cast ikon je odvozena z Feather (https://feathericons.com), MIT License,
 * Copyright (c) 2013-present Cole Bemis.
 * Plne zneni licenci: THIRD_PARTY_NOTICES.md v koreni repozitare.
 */

/** SVG path kazde ikony v prostoru 24x24 (tah 2, zaoblene konce). Index = id v Icons. */
final class IconPaths {
    private IconPaths() {}

%s

    static final String[] DATA = {
%s
    };
}
''' % (consts, "\n".join(lines))
    out.write_text(java)
    print("zapsano", out, len(ICONS), "ikon")


if __name__ == "__main__":
    main()
