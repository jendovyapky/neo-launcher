package com.neolauncher.cast;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Rozesila zakodovane snimky vsem divakum. Kazdy divak ma malou frontu;
 * kdyz nestiha (pomala Wi-Fi), fronta se zahodi a ceka se na dalsi klicovy snimek,
 * takze zpozdeni nikdy nenaroste.
 */
final class Hub {
    private Hub() {}

    static final class Frame {
        final byte[] data;
        final boolean key;
        final int durationMs;
        final long ptsUs;   // cas zachyceni (CLOCK_MONOTONIC, μs) - stejne hodiny jako zvuk
        Frame(byte[] d, boolean k, int dur, long pts) { data = d; key = k; durationMs = dur; ptsUs = pts; }
    }

    static final class Viewer {
        final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(8);
        volatile boolean needKey = true;
        volatile boolean closed = false;

        Frame next() throws InterruptedException { return queue.poll(1, TimeUnit.SECONDS); }
    }

    private static final CopyOnWriteArrayList<Viewer> viewers = new CopyOnWriteArrayList<>();

    static Viewer join() {
        Viewer v = new Viewer();
        viewers.add(v);
        CaptureService s = CaptureService.instance;
        if (s != null) s.requestKeyFrame();
        return v;
    }

    static void leave(Viewer v) {
        v.closed = true;
        viewers.remove(v);
    }

    static int count() { return viewers.size(); }

    static void publish(byte[] data, boolean key, int durationMs, long ptsUs) {
        Frame f = new Frame(data, key, durationMs, ptsUs);
        boolean wantKey = false;
        for (Viewer v : viewers) {
            if (v.needKey) {
                if (!key) { wantKey = true; continue; }
                v.needKey = false;
            }
            if (!v.queue.offer(f)) {
                // divak nestiha -> zahodit stare snimky a zacit od dalsiho klicoveho
                v.queue.clear();
                v.needKey = true;
                wantKey = true;
            }
        }
        if (wantKey) {
            CaptureService s = CaptureService.instance;
            if (s != null) s.requestKeyFrame();
        }
    }

    static void closeAll() {
        for (Viewer v : viewers) v.closed = true;
        viewers.clear();
    }
}
