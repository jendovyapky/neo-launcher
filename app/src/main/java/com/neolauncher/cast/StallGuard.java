package com.neolauncher.cast;

import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hlidac zaseknutych spojeni (obraz i zvuk). Kdyz pocitac zmizi "necisto" (usne, zavre
 * se viko, vypadne Wi-Fi), zapis do socketu se jen zasekne a vlakno by v nem viselo
 * desitky minut - a porad by se pocitalo jako divak (videt bylo treba 25 divaku).
 * Kdyz se do spojeni nepodari nic poslat STALL_MS, hlidac ho zavre: zaseknuty zapis
 * spadne s vyjimkou a obsluha divaka se normalne ukonci (Hub.leave / AudioCapture.leave).
 */
final class StallGuard {
    private StallGuard() {}

    /** Obraz i zvuk tecou porad (enkoder opakuje snimek kazdych 100 ms), 6 s ticha = mrtve spojeni. */
    static final long STALL_MS = 6000;

    static final class Entry {
        final Socket socket;
        volatile long lastOkMs = System.currentTimeMillis();
        volatile boolean done;

        Entry(Socket s) { socket = s; }

        /** Volat po kazdem uspesnem zapisu. */
        void ok() { lastOkMs = System.currentTimeMillis(); }
    }

    private static final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();
    private static Thread thread;

    static Entry watch(Socket s) {
        Entry e = new Entry(s);
        entries.add(e);
        ensureThread();
        return e;
    }

    static void done(Entry e) {
        e.done = true;
        entries.remove(e);
    }

    private static synchronized void ensureThread() {
        if (thread != null && thread.isAlive()) return;
        thread = new Thread(StallGuard::loop, "stall-guard");
        thread.setDaemon(true);
        thread.start();
    }

    private static void loop() {
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            for (Entry e : entries) {
                if (e.done || now - e.lastOkMs < STALL_MS) continue;
                entries.remove(e);
                try { e.socket.close(); } catch (Exception ignored) {}
            }
        }
    }
}
