package com.neolauncher.meta;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

/**
 * Pruhledna aktivita bez obsahu: Android 13+ chce povoleni k oznamenim (upozorneni
 * na baterii). Doplnek jinak zadnou obrazovku nema, takze ji otevira Neo.
 */
public class PermissionActivity extends Activity {
    private static final String POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{POST_NOTIFICATIONS}, 1);
        } else {
            finish();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        finish();
    }
}
