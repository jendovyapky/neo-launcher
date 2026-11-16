package com.neolauncher.cast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * Zachytava obraz headsetu pres MediaProjection a koduje ho hardwarovym H.264 enkoderem.
 * Virtualni displej kresli rovnou do vstupni plochy enkoderu -> zadne kopirovani pres procesor.
 * Hotove snimky rozesila {@link Hub} vsem pripojenym prohlizecum.
 *
 * Android 14: createVirtualDisplay se smi zavolat jen JEDNOU na jednu MediaProjection.
 * Zmena kvality proto dela jen resize() + setSurface() na existujicim displeji.
 */
public class CaptureService extends Service {

    static final String TAG = "QuestCast";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    static final String ACTION_STOP = "stop";
    static final int PORT = 8080;

    // --- sdileny stav pro web a okno Streamovat (vse v procesu :cast) ---
    static volatile boolean running = false;
    static volatile int srcWidth, srcHeight, outWidth, outHeight;
    static volatile float outFps;
    static volatile int kbps;
    static volatile String lastError;
    static volatile long startedAt;
    static volatile CaptureService instance;

    // nastaveni (meni se z webu pres /set)
    static volatile int maxSide = 1920;
    static volatile int targetFps = 60;
    static volatile int bitrateMbps = 14;

    private MediaProjection projection;
    private VirtualDisplay display;
    private int densityDpi;
    private HandlerThread ctrlThread;
    private Handler ctrl;
    private HttpServer server;
    private WifiManager.WifiLock wifiLock;

    private final Object codecLock = new Object();
    private MediaCodec encoder;
    private Surface encoderSurface;
    private Thread drainThread;
    private byte[] codecConfig;            // SPS + PPS, posila se pred kazdym klicovym snimkem
    private long lastPtsUs = -1;
    private int fpsCount, byteCount;
    private long fpsWindow;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running || intent == null) return START_NOT_STICKY;

        startAsForeground();
        try {
            int code = intent.getIntExtra(EXTRA_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
            if (projection == null) throw new IllegalStateException("Systém nevydal povolení k nahrávání");

            ctrlThread = new HandlerThread("capture-ctrl");
            ctrlThread.start();
            ctrl = new Handler(ctrlThread.getLooper());

            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    Log.i(TAG, "projection stopped");
                    if (running) lastError = "Systém ukončil nahrávání";
                    stopSelf();
                }
            }, ctrl);

            AudioCapture.attach(this, projection);
            running = true;
            startedAt = SystemClock.elapsedRealtime();
            lastError = null;

            startEncoderAndDisplay();
            startDrain();

            server = new HttpServer(this, PORT);
            server.start();
            acquireWifiLock();
            Discovery.start(this);
        } catch (Throwable t) {
            Log.e(TAG, "start failed", t);
            lastError = "Start selhal: " + t;
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("cast", "Streamování", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "cast")
                .setContentTitle("Neo streamuje")
                .setContentText("Obraz jde na quest.jendovyapky.eu – zastavíš v rychlém menu Nea")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    // ---------------------------------------------------------------- enkoder

    private int[] targetSize() {
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        srcWidth = dm.widthPixels;
        srcHeight = dm.heightPixels;
        densityDpi = dm.densityDpi;
        float scale = Math.min(1f, (float) maxSide / Math.max(srcWidth, srcHeight));
        // enkodery chteji nasobky 16
        int w = Math.max(16, Math.round(srcWidth * scale / 16f) * 16);
        int h = Math.max(16, Math.round(srcHeight * scale / 16f) * 16);
        return new int[]{w, h};
    }

    private MediaCodec createEncoder(int w, int h) throws Exception {
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrateMbps * 1_000_000);
        f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, targetFps);
        f.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, targetFps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000); // i staticky obraz posila snimky
        f.setInteger(MediaFormat.KEY_PRIORITY, 0);                        // realtime
        f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);                    // nizsi zpozdeni
        if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LATENCY, 1);
        f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        f.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel52);

        MediaCodec c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Exception e) {
            // nektere enkodery nemaji rady profil/level -> zkusime bez nich
            Log.w(TAG, "configure High failed, retry default profile", e);
            f.removeKey(MediaFormat.KEY_PROFILE);
            f.removeKey(MediaFormat.KEY_LEVEL);
            c.reset();
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        }
        return c;
    }

    /** Vytvori enkoder a napoji na nej virtualni displej (prvni start i zmena kvality). */
    private void startEncoderAndDisplay() throws Exception {
        int[] size = targetSize();
        MediaCodec enc;
        try {
            enc = createEncoder(size[0], size[1]);
        } catch (Exception e) {
            // rozliseni enkoder nezvladl -> zkusime 1280 na delsi strane
            Log.w(TAG, "encoder " + size[0] + "x" + size[1] + " failed", e);
            maxSide = 1280;
            size = targetSize();
            enc = createEncoder(size[0], size[1]);
        }
        Surface surf = enc.createInputSurface();
        enc.start();

        synchronized (codecLock) {
            encoder = enc;
            encoderSurface = surf;
            codecConfig = null;
            lastPtsUs = -1;
        }
        outWidth = size[0];
        outHeight = size[1];

        if (display == null) {
            // JEDINE volani createVirtualDisplay za celou dobu nahravani
            display = projection.createVirtualDisplay("QuestCast", size[0], size[1], densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surf, null, ctrl);
        } else {
            display.resize(size[0], size[1], densityDpi);
            display.setSurface(surf);
        }
        Log.i(TAG, "encoding " + size[0] + "x" + size[1] + " @" + targetFps + " " + bitrateMbps + "Mbps");
    }

    private void stopEncoder() {
        MediaCodec old;
        Surface oldSurf;
        synchronized (codecLock) {
            old = encoder;
            oldSurf = encoderSurface;
            encoder = null;
            encoderSurface = null;
        }
        if (display != null) display.setSurface(null);
        if (old != null) {
            try { old.stop(); } catch (Throwable ignored) {}
            try { old.release(); } catch (Throwable ignored) {}
        }
        if (oldSurf != null) oldSurf.release();
    }

    /** Zmena z webu. Bitrate jde menit za behu, rozliseni a fps vyzaduji novy enkoder. */
    void applySettings(int side, int fps, int mbps) {
        if (ctrl == null) return;
        ctrl.post(() -> {
            try {
                boolean restart = side != maxSide || fps != targetFps;
                bitrateMbps = mbps;
                if (restart) {
                    maxSide = side;
                    targetFps = fps;
                    stopEncoder();
                    startEncoderAndDisplay();
                } else {
                    synchronized (codecLock) {
                        if (encoder != null) {
                            Bundle b = new Bundle();
                            b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, mbps * 1_000_000);
                            encoder.setParameters(b);
                        }
                    }
                }
                lastError = null;
            } catch (Throwable t) {
                Log.e(TAG, "apply settings failed", t);
                lastError = "Změna kvality selhala: " + t.getMessage();
            }
        });
    }

    /** Novy divak potrebuje klicovy snimek, jinak by cekal az 2 s. */
    private volatile long lastKeyRequest;

    void requestKeyFrame() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastKeyRequest < 300) return;   // nespamovat enkoder
        lastKeyRequest = now;
        synchronized (codecLock) {
            if (encoder == null) return;
            try {
                Bundle b = new Bundle();
                b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                encoder.setParameters(b);
            } catch (Throwable ignored) {}
        }
    }

    // ---------------------------------------------------------------- vystup enkoderu

    private void startDrain() {
        fpsWindow = SystemClock.elapsedRealtime();
        drainThread = new Thread(this::drainLoop, "encoder-drain");
        drainThread.setPriority(Thread.MAX_PRIORITY);
        drainThread.start();
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            byte[] frame = null;
            boolean key = false;
            int durationMs = 0;
            long ptsUs = 0;
            synchronized (codecLock) {
                MediaCodec enc = encoder;
                if (enc != null) {
                    try {
                        int idx = enc.dequeueOutputBuffer(info, 20_000);
                        if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            MediaFormat of = enc.getOutputFormat();
                            codecConfig = concat(bytes(of.getByteBuffer("csd-0")), bytes(of.getByteBuffer("csd-1")));
                        } else if (idx >= 0) {
                            ByteBuffer buf = enc.getOutputBuffer(idx);
                            if (buf != null && info.size > 0) {
                                buf.position(info.offset);
                                buf.limit(info.offset + info.size);
                                byte[] data = new byte[info.size];
                                buf.get(data);
                                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                    codecConfig = data;
                                } else {
                                    key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                                    frame = key && codecConfig != null ? concat(codecConfig, data) : data;
                                    // trvani z absolutnich ms -> soucet trvani presne sedi na pts (zadny drift,
                                    // dulezite pro synchronizaci zvuku v prohlizeci)
                                    long ptsMs = info.presentationTimeUs / 1000;
                                    durationMs = lastPtsUs < 0 ? 1000 / Math.max(1, targetFps)
                                            : (int) Math.max(1, Math.min(250, ptsMs - lastPtsUs / 1000));
                                    lastPtsUs = info.presentationTimeUs;
                                    ptsUs = info.presentationTimeUs;
                                }
                            }
                            enc.releaseOutputBuffer(idx, false);
                        }
                    } catch (IllegalStateException e) {
                        // enkoder se prave vymenuje - nevadi
                    }
                }
            }
            if (frame != null) {
                Hub.publish(frame, key, durationMs, ptsUs);
                countFps(frame.length);
            } else if (encoder == null) {
                SystemClock.sleep(10);
            }
        }
    }

    private void countFps(int len) {
        fpsCount++;
        byteCount += len;
        long now = SystemClock.elapsedRealtime();
        long dt = now - fpsWindow;
        if (dt >= 1000) {
            outFps = fpsCount * 1000f / dt;
            kbps = (int) (byteCount * 8L / dt);
            fpsCount = 0;
            byteCount = 0;
            fpsWindow = now;
        }
    }

    private static byte[] bytes(ByteBuffer b) {
        if (b == null) return new byte[0];
        ByteBuffer d = b.duplicate();
        d.rewind();
        byte[] out = new byte[d.remaining()];
        d.get(out);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ---------------------------------------------------------------- uklid

    private void acquireWifiLock() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "QuestCast");
            wifiLock.acquire();
        } catch (Throwable t) {
            Log.w(TAG, "wifi lock failed", t);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        Discovery.stop(this);
        AudioCapture.detach();
        Hub.closeAll();
        try { if (server != null) server.stopServer(); } catch (Throwable ignored) {}
        try { if (drainThread != null) drainThread.join(300); } catch (Throwable ignored) {}
        try { stopEncoder(); } catch (Throwable ignored) {}
        try { if (display != null) display.release(); } catch (Throwable ignored) {}
        try { if (projection != null) projection.stop(); } catch (Throwable ignored) {}
        try { if (ctrlThread != null) ctrlThread.quitSafely(); } catch (Throwable ignored) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Throwable ignored) {}
        display = null;
        super.onDestroy();
    }

    /** IPv4 adresa Questu ve Wi-Fi (napr. 192.168.0.44), nebo null. */
    static String wifiIp() {
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
