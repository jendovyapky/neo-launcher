package com.neolauncher;

import android.app.Application;

import com.neolauncher.art.ArtworkLoader;
import com.neolauncher.data.AppRepository;
import com.neolauncher.data.Prefs;

/** Drzi jedine instance sdilenych sluzeb (nastaveni, aplikace, obrazky). */
public class NeoApp extends Application {
    private static NeoApp sInstance;

    private Prefs prefs;
    private AppRepository apps;
    private ArtworkLoader artwork;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        // Proces :cast (streamovani, com.neolauncher.cast) launcher nepotrebuje - nic nenacitat.
        if (Application.getProcessName().endsWith(":cast")) return;
        prefs = new Prefs(this);
        apps = new AppRepository(this, prefs);
        artwork = new ArtworkLoader(this);
        artwork.setOnlineEnabled(prefs.onlineArt());
        prefs.addListener(() -> artwork.setOnlineEnabled(prefs.onlineArt()));
    }

    public static NeoApp get() {
        return sInstance;
    }

    public Prefs prefs() {
        return prefs;
    }

    public AppRepository apps() {
        return apps;
    }

    public ArtworkLoader artwork() {
        return artwork;
    }
}
