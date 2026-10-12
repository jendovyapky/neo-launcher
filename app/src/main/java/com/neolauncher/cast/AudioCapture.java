package com.neolauncher.cast;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.projection.MediaProjection;
import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Zachytava zvuk, ktery Quest prehrava (hry, videa) - Android "AudioPlaybackCapture".
 * V headsetu zvuk hraje dal normalne, tohle je jen kopie pro prohlizec.
 *
 * Bezi jen kdyz nekdo posloucha (tlacitko Zvuk na webu je vychozi vypnute).
 * Kazdy kousek (20 ms PCM) ma casove razitko ve stejnych hodinach jako snimky obrazu
 * (CLOCK_MONOTONIC), takze prohlizec muze zvuk presne srovnat s obrazem.
 */
final class AudioCapture {
    private AudioCapture() {}

    static final int RATE = 48_000, CHANNELS = 2, CHUNK_MS = 20;
    static final int CHUNK_FRAMES = RATE * CHUNK_MS / 1000;           // 960
    static final int CHUNK_BYTES = CHUNK_FRAMES * CHANNELS * 2;        // 3840

    static final class Chunk {
        final byte[] pcm; final long ptsUs;
        Chunk(byte[] p, long t) { pcm = p; ptsUs = t; }
    }

    static final class Listener {
        final ArrayBlockingQueue<Chunk> queue = new ArrayBlockingQueue<>(50); // max 1 s ve fronte
        volatile boolean closed;
        Chunk next() throws InterruptedException { return queue.poll(1, TimeUnit.SECONDS); }
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static volatile MediaProjection projection;
    private static volatile Context app;
    private static volatile String startError;
    private static Thread thread;
    static volatile int levelDb = -100;   // hlasitost poslednich ~250 ms (dBFS), -100 = ticho

    static void attach(Context ctx, MediaProjection p) {
        app = ctx.getApplicationContext();
        projection = p;
        startError = null;
    }

    static void detach() {
        projection = null;
        for (Listener l : listeners) l.closed = true;
        listeners.clear();
        stopThread();
    }

    /** null = zvuk jde, jinak duvod cesky. */
    static String unavailableReason() {
        Context c = app;
        if (c != null && c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return "chybí povolení k nahrávání zvuku – povol ho v okně Streamovat v Neu";
        return startError;
    }

    static int count() { return listeners.size(); }

    static synchronized Listener join() {
        Listener l = new Listener();
        listeners.add(l);
        if (thread == null) startThread();
        return l;
    }

    static synchronized void leave(Listener l) {
        l.closed = true;
        listeners.remove(l);
        if (listeners.isEmpty()) stopThread();
    }

    private static synchronized void startThread() {
        thread = new Thread(AudioCapture::loop, "audio-capture");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    private static synchronized void stopThread() {
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private static void loop() {
        MediaProjection p = projection;
        if (p == null) return;
        AudioRecord rec = null;
        try {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(p)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(Math.max(min, CHUNK_BYTES * 4))
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord se nespustil");
            rec.startRecording();
            startError = null;

            AudioTimestamp ts = new AudioTimestamp();
            long framesRead = 0, levelSum = 0, levelN = 0, levelAt = System.nanoTime();
            while (!Thread.currentThread().isInterrupted() && projection == p) {
                byte[] buf = new byte[CHUNK_BYTES];
                int got = 0;
                while (got < CHUNK_BYTES) {
                    int n = rec.read(buf, got, CHUNK_BYTES - got);
                    if (n < 0) throw new IllegalStateException("čtení zvuku selhalo (" + n + ")");
                    got += n;
                }
                // cas prvniho vzorku tohoto kousku ve stejnych hodinach jako obraz
                long startNs;
                if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    startNs = ts.nanoTime + (framesRead - ts.framePosition) * 1_000_000_000L / RATE;
                } else {
                    startNs = System.nanoTime() - (long) CHUNK_MS * 1_000_000L * 2; // odhad: kousek + buffer
                }
                framesRead += CHUNK_FRAMES;

                // hlasitost (pro diagnostiku na webu: ukaze, jestli hra zvuk vubec pousti ven)
                for (int i = 0; i < CHUNK_BYTES; i += 8) { // kazdy 2. stereo vzorek
                    int v = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
                    levelSum += (long) v * v; levelN++;
                }
                long now = System.nanoTime();
                if (now - levelAt > 250_000_000L) {
                    double rms = levelN == 0 ? 0 : Math.sqrt((double) levelSum / levelN) / 32768.0;
                    levelDb = rms < 1e-5 ? -100 : (int) Math.round(20 * Math.log10(rms));
                    levelSum = 0; levelN = 0; levelAt = now;
                }

                Chunk c = new Chunk(buf, startNs / 1000);
                for (Listener l : listeners) {
                    if (!l.queue.offer(c)) { l.queue.poll(); l.queue.offer(c); } // posluchac nestiha -> zahodit nejstarsi
                }
            }
        } catch (Throwable t) {
            Log.e(CaptureService.TAG, "audio", t);
            startError = "zvuk nejde zachytit: " + t.getMessage();
            for (Listener l : listeners) l.closed = true;
        } finally {
            if (rec != null) {
                try { rec.stop(); } catch (Throwable ignored) {}
                rec.release();
            }
            levelDb = -100;
            synchronized (AudioCapture.class) { if (thread == Thread.currentThread()) thread = null; }
        }
    }
}
