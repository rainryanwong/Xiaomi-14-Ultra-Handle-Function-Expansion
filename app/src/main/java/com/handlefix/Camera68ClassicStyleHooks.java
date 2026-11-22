package com.handlefix;

import android.content.Context;
import android.os.Looper;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import de.robv.android.xposed.XposedHelpers;

/** Shares CameraCompat's 1770 Leica Classic chooser inside the camera process. */
public final class Camera68ClassicStyleHooks {
    private static final String BRIDGE = "com.aurora.camera.compat.handlefix.classic.v1";
    private static final int[] LEGACY_MODES = {ModeConfig.MODE_CAPTURE};
    private static boolean catalogPublished;

    private Camera68ClassicStyleHooks() {}

    static void installCatalogProbe(ClassLoader loader, long versionCode) {
        HandleFixModule.install("camera68-classic-catalog", () ->
                PhoenixClassicStyleHooks.hookCameraLifecycleOnly(loader,
                        activity -> publishCatalog(activity, loader, versionCode)));
        HandleFixModule.install("camera68-classic-overlay-rotation",
                () -> PhoenixClassicStyleHooks.hookCamera68OverlayRotation(loader));
    }

    public static int[] supportedModes(ClassLoader loader) {
        Map<?, ?> service = bridge(loader);
        return service == null ? new int[0] : supportedModes(service).clone();
    }

    private static int[] supportedModes(Map<?, ?> service) {
        Object modes = service.get("modes");
        return modes instanceof int[] ? (int[]) modes : LEGACY_MODES;
    }

    private static Map<?, ?> bridge(ClassLoader loader) {
        Object value = XposedHelpers.getAdditionalInstanceField(loader, BRIDGE);
        if (!(value instanceof Map<?, ?>)) return null;
        Map<?, ?> service = (Map<?, ?>) value;
        Object ids = service.get("ids");
        Object labels = service.get("labels");
        if (!(ids instanceof String[]) || !(labels instanceof String[])
                || ((String[]) ids).length == 0
                || ((String[]) ids).length != ((String[]) labels).length
                || !(service.get("version") instanceof Long)
                || !(service.get("cycle") instanceof Function<?, ?>)) {
            return null;
        }
        return service;
    }

    private static void publishCatalog(Context context, ClassLoader loader, long versionCode) {
        if (catalogPublished) return;
        Map<?, ?> service = bridge(loader);
        if (service == null) return;
        catalogPublished = ClassicStyleCatalogBridge.publish(context,
                ClassicStyleCatalogBridge.profileForVersion(versionCode),
                versionCode, (Long) service.get("version"),
                (String[]) service.get("ids"), (String[]) service.get("labels"));
        if (catalogPublished) HandleFixModule.log("camera68 classic LUT catalog published");
    }

    @SuppressWarnings("unchecked") // The v1 registration supplies this bootstrap interface.
    static boolean handlePress(ClassLoader loader, int mode) {
        Map<?, ?> service = bridge(loader);
        if (service == null) {
            // A saved binding owns this press even if the CameraCompat hook did not load.
            // Falling through to the native record-key action can start an unrelated video.
            PhoenixClassicStyleHooks.showStatusFeedback("相机进程未加载徕卡经典风格组件", mode);
            return true;
        }
        boolean supported = false;
        for (int supportedMode : supportedModes(service)) {
            if (supportedMode == mode) {
                supported = true;
                break;
            }
        }
        if (!supported) return false;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            HandleFixModule.log("camera68 classic LUT: unexpected record-key thread");
            return true;
        }

        List<String> candidates = ClassicStyleController.configuredCandidates(
                CameraBuilds.CAMERA_68_1770_VERSION_CODE, (String[]) service.get("ids"));
        Function<List<String>, String> cycle =
                (Function<List<String>, String>) service.get("cycle");
        String label;
        try {
            // Compat resolves the effective style, including AI capture and mode-specific overrides.
            Object current = service.get("current");
            boolean classicActive = current instanceof Supplier<?>
                    ? ((Supplier<?>) current).get() != null
                    : LeicaStateAdapter.read(loader, mode) == LeicaStateAdapter.State.CLASSIC;
            if (!classicActive) {
                PhoenixClassicStyleHooks.showStatusFeedback("请切换徕卡经典", mode);
                return true;
            }
            label = cycle.apply(candidates);
        } catch (RuntimeException | LinkageError failure) {
            HandleFixModule.log("camera68 classic LUT refresh failed", failure);
            PhoenixClassicStyleHooks.showStatusFeedback("徕卡经典风格切换失败", mode);
            return true;
        }
        if (label == null) {
            PhoenixClassicStyleHooks.showStatusFeedback("徕卡经典风格切换未生效", mode);
            return true;
        }
        PhoenixClassicStyleHooks.showStyleFeedback(label, mode);
        return true;
    }
}
