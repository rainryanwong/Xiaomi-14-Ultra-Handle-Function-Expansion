package com.handlefix;

import android.content.ContentResolver;
import android.provider.Settings;
import java.util.ArrayList;
import java.lang.reflect.Method;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Camera-local counterpart of the enhancer's Global flag, not a persistent system write.
 * Phoenix 0.3.0/0.8.0 p6.b.g creates W5.b and reads the Bluetooth flag, then native
 * detector/229 routing runs. Only that construction window sees the override;
 * USB IMiCharge, real connection broadcasts and all other Global keys are untouched.
 * Changing the option restarts the camera: there is no global setting/file to clean up.
 */
final class StreetSpecialWithoutGripHooks {
    private static final String CONNECTED = "is_bluetooth_photography_handle";
    private static final String COLOR = "bluetooth_photography_handle_color";
    private StreetSpecialWithoutGripHooks() { }

    static void install(ClassLoader loader) {
        HandleFixModule.install("street-special camera-local no-grip", () -> {
            ArrayList<XC_MethodHook.Unhook> installed = new ArrayList<>();
            try {
                Method factory = StreetSpecialProfile.method(loader, "p6.b", "g", void.class,
                        false, ArrayList.class, Class[].class);
                StreetSpecialProfile.method(loader, "W5.b", "Mg", boolean.class, false);
                Method select = StreetSpecialProfile.method(loader, "U4.i", "Yq",
                        boolean.class, false, int.class, String.class, boolean.class);
                Method nativeEnabled = StreetSpecialProfile.method(loader,
                        "com.android.camera.data.data.w", "d", boolean.class, true);
                Method guideCompleted = StreetSpecialProfile.method(loader, "Q5.J", "d", boolean.class, true);
                Method getInt = Settings.Global.class.getDeclaredMethod("getInt",
                        ContentResolver.class, String.class, int.class);
                Method getString = Settings.Global.class.getDeclaredMethod("getString",
                        ContentResolver.class, String.class);
                ThreadLocal<Integer> depth = new ThreadLocal<>();
                installed.add(XposedBridge.hookMethod(factory, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Integer previous = depth.get();
                        depth.set(previous == null ? 1 : previous + 1);
                    }
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Integer current = depth.get();
                        if (current == null || current <= 1) depth.remove();
                        else depth.set(current - 1);
                    }
                }));
                installed.add(XposedBridge.hookMethod(getInt, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (depth.get() != null && CONNECTED.equals(param.args[1]) && !param.hasThrowable()) {
                            param.setResult(1);
                        }
                    }
                }));
                installed.add(XposedBridge.hookMethod(getString, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (depth.get() != null && COLOR.equals(param.args[1]) && !param.hasThrowable()
                                && param.getResult() == null) param.setResult("");
                    }
                }));
                // Normal selector requests 225; quick-launch routing already supports 229.
                // Change the selector input BEFORE its native q.c0(mode) + StartControl.create.
                // Changing only the latter leaves stored mode=225 but active module=229,
                // breaking native menu/exit decisions. Do not write the stored mode ourselves.
                // Scope to the selector, not every StartControl factory call: special UI exit
                // must remain able to request ordinary street natively without being redirected.
                // This hook exists only when both module switches were enabled at camera load;
                // the native preference and first-run guide remain authoritative at each entry.
                installed.add(XposedBridge.hookMethod(select, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!Integer.valueOf(225).equals(param.args[0])) return;
                        try {
                            if (Boolean.TRUE.equals(nativeEnabled.invoke(null))
                                    && Boolean.TRUE.equals(guideCompleted.invoke(null))) {
                                param.args[0] = 229;
                                HandleFixModule.log("StreetSpecial: native street selection 225 -> 229 (no-grip)");
                            }
                        } catch (ReflectiveOperationException error) {
                            HandleFixModule.log("StreetSpecial: native entry check failed; retain 225: " + error);
                        }
                    }
                }));
            } catch (Throwable error) {
                // Roll back partial installation instead of leaving a global-read override alive.
                for (XC_MethodHook.Unhook hook : installed) hook.unhook();
                throw new IllegalStateException("No-grip preflight/install failed", error);
            }
        });
    }
}
