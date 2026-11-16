#!/usr/bin/env bash
# Skutecny snimek NeoLauncherView bez headsetu a bez Android SDK:
# Robolectric (emulace Androidu na JVM) s nativni grafikou a HW vykreslovanim
# (RenderNode, RenderEffect i AGSL shader jedou pres skutecnou Skia/HWUI).
# Vysledek: out/neo-idle.png, out/neo-hover.png, out/neo-carousel*.png (pruhledne pozadi)
#           out/neo-quest-*.png (slozeno s ilustracnim pozadim "Quest domov").
# Pozadi a systemove rozmazani prostredi jsou jen ilustrace, launcher sam je skutecny.
#
# Pouziti: tools/screenshot/run.sh [pracovni-slozka]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
W="${1:-${TMPDIR:-/tmp}/neo-screenshot}"
mkdir -p "$W"/{lib,deps,banners,out,stubs/androidx/annotation,stubs/androidx/tracing}
cd "$W"
MAVEN=https://repo.maven.apache.org/maven2

# 1) Robolectric + JUnit z Maven Central
if [ ! -f lib/robolectric-4.17.jar ]; then
  cp "$HERE/pom.xml" pom.xml
  mvn -q -B dependency:copy-dependencies -DoutputDirectory=lib
fi

# 2) Android framework jary (kompilace = android-all, beh = instrumentovana verze)
[ -f deps/android-all.jar ] || curl -sSL -o deps/android-all.jar \
  $MAVEN/org/robolectric/android-all/15-robolectric-13954326/android-all-15-robolectric-13954326.jar
I=android-all-instrumented-14-robolectric-10818077-i7
[ -f deps/$I.jar ] || curl -sSL --retry 5 -o deps/$I.jar \
  $MAVEN/org/robolectric/android-all-instrumented/14-robolectric-10818077-i7/$I.jar

# 3) androidx.test (monitor + idling) ze zdrojaku - binarky jsou jen na Google Maven
if [ ! -f lib/androidx-test-monitor-local.jar ]; then
  [ -d android-test ] || { git clone -q --depth 1 --filter=blob:none --sparse \
      https://github.com/android/android-test.git android-test
    (cd android-test && git sparse-checkout set runner/monitor/java espresso/idling_resource/java); }
  for a in NonNull Nullable; do
    printf 'package androidx.annotation;\npublic @interface %s {}\n' $a > stubs/androidx/annotation/$a.java
  done
  printf 'package androidx.annotation;\npublic @interface VisibleForTesting { int otherwise() default 0; int PRIVATE = 2; int PACKAGE_PRIVATE = 3; int PROTECTED = 4; int NONE = 5; }\n' \
    > stubs/androidx/annotation/VisibleForTesting.java
  printf 'package androidx.annotation;\npublic @interface RestrictTo { Scope[] value(); enum Scope { LIBRARY, LIBRARY_GROUP, LIBRARY_GROUP_PREFIX, GROUP_ID, TESTS, SUBCLASSES } }\n' \
    > stubs/androidx/annotation/RestrictTo.java
  printf 'package androidx.tracing;\npublic final class Trace { public static void beginSection(String s) {} public static void endSection() {} public static void forceEnableAppTracing() {} public static boolean isEnabled() { return false; } }\n' \
    > stubs/androidx/tracing/Trace.java
  M=android-test/runner/monitor/java; E=android-test/espresso/idling_resource/java
  : > need.txt
  for n in espresso/IdlingRegistry espresso/IdlingResource internal/platform/ThreadChecker \
      internal/platform/app/ActivityInvoker internal/platform/content/PermissionGranter \
      internal/platform/os/ControlledLooper internal/platform/util/TestOutputEmitter \
      internal/runner/intent/IntentMonitorImpl internal/runner/lifecycle/ActivityLifecycleMonitorImpl \
      internal/runner/lifecycle/ApplicationLifecycleMonitorImpl platform/app/InstrumentationRegistry \
      platform/ui/InjectEventSecurityException platform/ui/UiController runner/intent/IntentMonitor \
      runner/intent/IntentMonitorRegistry runner/intent/IntentStubber runner/intent/IntentStubberRegistry \
      runner/lifecycle/ActivityLifecycleCallback runner/lifecycle/ActivityLifecycleMonitor \
      runner/lifecycle/ActivityLifecycleMonitorRegistry runner/lifecycle/ApplicationLifecycleMonitor \
      runner/lifecycle/ApplicationLifecycleMonitorRegistry runner/lifecycle/ApplicationStage runner/lifecycle/Stage; do
    for f in $M/androidx/test/$n.java $E/androidx/test/$n.java; do [ -f "$f" ] && echo "$f" >> need.txt; done
  done
  find stubs -name '*.java' >> need.txt
  rm -rf at-classes && mkdir at-classes
  javac -nowarn -encoding UTF-8 --release 17 -implicit:class -sourcepath $M:$E:stubs \
    -cp "deps/android-all.jar:lib/*" -d at-classes @need.txt
  (cd at-classes && jar cf ../lib/androidx-test-monitor-local.jar androidx)
fi

# 4) Opravdove bannery her (zalozni zdroj launcheru: veticia/binaries na GitHubu)
for p in com.beatgames.beatsaber com.cloudheadgames.pistolwhip com.kluge.SynthRiders \
    com.MightyCoconut.WalkaboutMiniGolf com.AnotherAxiom.GorillaTag com.StressLevelZero.BONELAB \
    com.Warpfrog.BladeAndSorcery com.owlchemylabs.jobsimulator com.vrchat.oculus.quest \
    com.AgainstGravity.RecRoom com.fitxr.boxvr com.bigscreenvr.bigscreen VirtualDesktop.Android; do
  [ -f banners/$p.img ] || curl -sSL -o banners/$p.img \
    "https://raw.githubusercontent.com/veticia/binaries/main/banners/$p.png"
done

# 5) Prelozit launcher + test a spustit
rm -rf classes && mkdir classes
find "$REPO/app/src/main/java" -name '*.java' > src.txt
echo "$HERE/ShotTest.java" >> src.txt
javac -nowarn -encoding UTF-8 --release 17 -cp "deps/android-all.jar:lib/*" -d classes @src.txt
java -Xmx4g -Dneo.banners="$W/banners" -Dneo.out="$W/out" -Dneo.scroll=0.9 -Dneo.scrollDp=57.6 \
  -Dneo.novinky="$REPO/app/src/main/assets/novinky.txt" \
  -Drobolectric.offline=true -Drobolectric.dependency.dir="$W/deps" -Drobolectric.logging=stdout \
  -cp "classes:lib/*:deps/android-all.jar" org.junit.runner.JUnitCore neoshot.ShotTest

# 6) Slozit s ilustracnim pozadim
javac -nowarn -d classes "$HERE/Compose.java"
for n in idle intro hover live drag running launch-1 launch-2 launch-3 launch-4 launch-5 launch-6 carousel carousel-move rail quickmenu-open quickmenu idle-vision settings settings-2 settings-3 settings-4 settings-5 settings-light search whatsnew appmenu power addon-prompt; do
  java -Djava.awt.headless=true -cp classes Compose out/neo-$n.png out/neo-quest-$n.png
done
echo "Hotovo: $W/out"
