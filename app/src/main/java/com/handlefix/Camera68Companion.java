package com.handlefix;

import android.content.Context;
import android.content.pm.PackageManager;

/** Package presence for the system-side forced street launch. Camera scope verifies the interface. */
public final class Camera68Companion {
    private static final String PACKAGE = "com.aurora.camera.compat";

    private Camera68Companion() {}

    public static boolean isCameraCompatInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
