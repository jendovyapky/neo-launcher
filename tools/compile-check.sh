#!/usr/bin/env bash
# Rychla kontrola kompilace Javy BEZ Android SDK (napr. v cloudu, kde neni
# pristup na dl.google.com). Prelozi zdrojaky proti android-all.jar
# z Robolectricu (Maven Central) a proti vygenerovane tride R.
# Neni to plnohodnotny build (neresi resources, manifest, dex) - skutecne
# APK stavi GitHub Actions (.github/workflows/build.yml).
#
# Pouziti: tools/compile-check.sh [cesta/k/android-all.jar]
set -euo pipefail
cd "$(dirname "$0")/.."

WORK="${TMPDIR:-/tmp}/neo-compile-check"
JAR="${1:-$WORK/android-all.jar}"
mkdir -p "$WORK"
if [ ! -f "$JAR" ]; then
  echo "Stahuji android-all.jar (Android 15, ~180 MB)..."
  curl -sSL -o "$JAR" \
    https://repo.maven.apache.org/maven2/org/robolectric/android-all/15-robolectric-13954326/android-all-15-robolectric-13954326.jar
fi

GEN="$WORK/gen/com/neolauncher"
mkdir -p "$GEN"
python3 - "$GEN/R.java" <<'PY'
import os, re, sys, xml.etree.ElementTree as ET
res = "app/src/main/res"
types = {}
def add(t, n):
    types.setdefault(t, set()).add(re.sub(r"[.:-]", "_", n))
for d in sorted(os.listdir(res)):
    base = d.split("-")[0]
    p = os.path.join(res, d)
    if base == "values":
        for f in os.listdir(p):
            root = ET.parse(os.path.join(p, f)).getroot()
            for el in root:
                tag = el.tag
                if tag in ("string", "color", "dimen", "bool", "integer", "style", "array"):
                    add({"array": "array"}.get(tag, tag), el.get("name"))
                elif tag in ("string-array", "integer-array"):
                    add("array", el.get("name"))
    else:
        for f in os.listdir(p):
            add(base, f.split(".")[0])
out = ["package com.neolauncher;", "public final class R {"]
i = 0x7f000000
for t, names in sorted(types.items()):
    out.append("  public static final class %s {" % t)
    for n in sorted(names):
        i += 1
        out.append("    public static final int %s = 0x%x;" % (n, i))
    out.append("  }")
out.append("}")
open(sys.argv[1], "w").write("\n".join(out) + "\n")
PY

rm -rf "$WORK/classes" && mkdir -p "$WORK/classes"
find app/src/main/java -name '*.java' > "$WORK/sources.txt"
echo "$GEN/R.java" >> "$WORK/sources.txt"
javac -encoding UTF-8 --release 17 -Xlint:unchecked -Xmaxerrs 200 \
  -cp "$JAR" -d "$WORK/classes" @"$WORK/sources.txt"
echo "OK: $(wc -l < "$WORK/sources.txt") souboru se prelozilo bez chyb."

# Doplnek "Neo - Meta tlacitko" (samostatne APK, nepouziva R).
rm -rf "$WORK/addon-classes" && mkdir -p "$WORK/addon-classes"
find metaaddon/src/main/java -name '*.java' > "$WORK/addon-sources.txt"
javac -encoding UTF-8 --release 17 -Xmaxerrs 200 \
  -cp "$JAR" -d "$WORK/addon-classes" @"$WORK/addon-sources.txt"
echo "OK: doplnek Meta tlacitka ($(wc -l < "$WORK/addon-sources.txt") souboru)."
