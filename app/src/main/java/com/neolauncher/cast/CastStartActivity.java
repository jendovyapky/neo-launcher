package com.neolauncher.cast;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/**
 * Neviditelna aktivita (proces :cast): rekne si o povoleni ke zvuku her (jen
 * poprve, bez nej jde jen obraz), pak o systemove povoleni nahravat obrazovku
 * a spusti CaptureService. Hned se zavre - zadne vlastni okno.
 */
public class CastStartActivity extends Activity {

    private static final int REQ_AUDIO = 1;
    private static final int REQ_CAPTURE = 2;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null) return; // po otoceni / obnove uz dialog bezi
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                && !getSharedPreferences("neo_cast", MODE_PRIVATE).getBoolean("askedAudio", false)) {
            getSharedPreferences("neo_cast", MODE_PRIVATE).edit().putBoolean("askedAudio", true).apply();
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        askCapture();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_AUDIO) askCapture();
    }

    private void askCapture() {
        CaptureService.lastError = null;
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (Exception e) {
            CaptureService.lastError = "Nahrávání obrazovky tu nejde spustit";
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CAPTURE && res == RESULT_OK && data != null) {
            // povoleni (data) jde pouzit jen jednou -> vzdy cerstve z tohoto dialogu
            startForegroundService(new Intent(this, CaptureService.class)
                    .putExtra(CaptureService.EXTRA_CODE, res)
                    .putExtra(CaptureService.EXTRA_DATA, data));
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
