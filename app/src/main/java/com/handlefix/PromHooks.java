/**
 * PromHooks：老包共有手柄补丁（V5.1 开关=开 时挂载）。
 * 内容：fix1-5（手柄基础功能修复）+ S0 门（强制半按对焦等）。
 * 仅 PrometheusCam 老版本（V5.1/1.x，作者未内置修复）需要；
 * NRV-1.3.2 起已内置/由作者 LSP 提供 -> 主类跳过本类。
 */
package com.handlefix;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import static com.handlefix.HandleFixModule.*;

/**
 * V5.1 开关控制的共有补丁：fix1-5（HandleDetector 注册/分发/重启扫描/即插即用）+ fix-S0 定点门。
 * 新版 PrometheusCam（NRV 等）已内置/由作者 LSP 提供时，主类跳过本类 install。
 */
public final class PromHooks {

    private PromHooks() {
    }

    /** 主类 install 注册表按版本条件调用 */
    public static void install(final ClassLoader cl) {
        HandleFixModule.install("fix1 Camera.registerProtocol", new Runnable() {
            public void run() { hookCameraRegisterProtocol(cl); }
        });
        HandleFixModule.install("fix2 record-button customize", new Runnable() {
            public void run() { hookKeyEventCustomize(cl); }
        });
        HandleFixModule.install("fix3 o3.b.sf()", new Runnable() {
            public void run() { hookHandleDetectorSf(cl); }
        });
        HandleFixModule.install("fix4a o3.b.registerProtocol", new Runnable() {
            public void run() { hookHandleDetectorRegisterProtocol(cl); }
        });
        HandleFixModule.install("fix4b o3.c.registerProtocol", new Runnable() {
            public void run() { hookInputDeviceManagerRegisterProtocol(cl); }
        });
        HandleFixModule.install("fix-S0-device-capability", new Runnable() {
            public void run() { hookS0(cl); }
        });
    }

    // ---------------------------------------------------------------
    // Fix 1: force HandleDetector (f4/b0 -> o3/b) registration inside
    //        Camera.registerProtocol(), bypassing the S0() device gate
    //        ONLY at this single call site.
    // ---------------------------------------------------------------
    public static void hookCameraRegisterProtocol(final ClassLoader cl) {
        XposedHelpers.findAndHookMethod("com.android.camera.Camera", cl,
                "registerProtocol", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Object camera = param.thisObject;
                            Object j3b = XposedHelpers.getObjectField(camera, "i1");
                            if (j3b == null) {
                                log("fix1: i1 (J3/b) is null, skip");
                                return;
                            }
                            Class<?> b0 = XposedHelpers.findClass("f4.b0", cl);
                            XposedHelpers.callMethod(j3b, "e", (Object) new Class<?>[]{b0});
                            log("fix1: HandleDetector(f4/b0) registration forced");
                        } catch (Throwable t) {
                            log("fix1: error", t);
                        }
                    }
                });
    }

    // ---------------------------------------------------------------
    // Fix 2: record-button custom function. The original onKeyUp(313)
    //        pre-check only lets attr_awb / attr_zoom_segment /
    //        quick_recording (or a feature flag) reach the dispatch
    //        (cond_13); every other configured function silently falls
    //        back to quick recording. The smali patch turns the first
    //        `if-nez` into an unconditional goto. Here we reproduce that
    //        by making the three pre-check String.equals calls return
    //        true while onKeyUp(313) is executing on this thread.
    // ---------------------------------------------------------------
    public static void hookKeyEventCustomize(final ClassLoader cl) {
        XposedHelpers.findAndHookMethod(String.class, "equals", Object.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Boolean.TRUE.equals(IN_HANDLE_KEYUP.get())) {
                            return;
                        }
                        Object self = param.thisObject;
                        if (!(self instanceof String)) {
                            return;
                        }
                        String s = (String) self;
                        if (isGateString(s)) {
                            param.setResult(true);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod("K3.u1", cl, "onKeyUp",
                int.class, KeyEvent.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            int code = (Integer) param.args[0];
                            if (code == 313) {
                                IN_HANDLE_KEYUP.set(Boolean.TRUE);
                            }
                        } catch (Throwable t) {
                            // never let the gate break key handling
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        IN_HANDLE_KEYUP.remove();
                    }
                });
        log("fix2: record-button custom function dispatch enabled");
    }

    // NOTE: must NOT call String.equals() in here while the gate is armed
    // (the hook would intercept those very calls). contentEquals() is a
    // different method and is not hooked.
    public static boolean isGateString(String s) {
        int len = s.length();
        if (len == 8) {
            return "attr_awb".contentEquals(s);
        }
        if (len == 17) {
            return "attr_zoom_segment".contentEquals(s);
        }
        if (len == 15) {
            return "quick_recording".contentEquals(s);
        }
        return false;
    }

    // ---------------------------------------------------------------
    // Fix 3: o3.b.sf() — dynamic device scan. The author version is a
    //        pure flag check (return a || b); after a background restart
    //        the flags are stale, so the handle is reported disconnected.
    //        Mirror the V4 patch: scan InputManager for the Xiaomi handle
    //        (vendor 0x2717, product 0x5083 Pro / 0x50b2 0x50b7 Lite),
    //        refresh the flags and return true.
    // ---------------------------------------------------------------
    public static void hookHandleDetectorSf(final ClassLoader cl) {
        XposedHelpers.findAndHookMethod("o3.b", cl, "sf", new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                Object self = param.thisObject;
                try {
                    boolean a = XposedHelpers.getBooleanField(self, "a");
                    boolean b = XposedHelpers.getBooleanField(self, "b");
                    if (a || b) {
                        return true;
                    }
                    Object actBase = XposedHelpers.getObjectField(self, "f");
                    if (actBase == null) {
                        return false;
                    }
                    Object im = XposedHelpers.callMethod(actBase, "getSystemService", "input");
                    if (!(im instanceof InputManager)) {
                        return false;
                    }
                    InputManager inputManager = (InputManager) im;
                    int[] ids = inputManager.getInputDeviceIds();
                    for (int id : ids) {
                        InputDevice dev = inputManager.getInputDevice(id);
                        if (dev == null) {
                            continue;
                        }
                        if (dev.getVendorId() != 0x2717) {
                            continue;
                        }
                        int pid = dev.getProductId();
                        if (pid == 0x5083) {
                            XposedHelpers.setBooleanField(self, "a", true);
                            return true;
                        }
                        if (pid == 0x50b2 || pid == 0x50b7) {
                            XposedHelpers.setBooleanField(self, "b", true);
                            return true;
                        }
                    }
                } catch (Throwable t) {
                    log("fix3: sf() scan error", t);
                }
                return false;
            }
        });
        log("fix3: o3.b.sf() dynamic device scan installed");
    }

    // ---------------------------------------------------------------
    // Fix 4a: o3.b.registerProtocol() — the original queues the
    //         receiver-registration runnable (nd/b) on Schedulers.single(),
    //         which delays handle init ~10s at camera start. Run it
    //         synchronously instead (nd/b.run() has an e-flag guard).
    // ---------------------------------------------------------------
    public static void hookHandleDetectorRegisterProtocol(final ClassLoader cl) {
        XposedHelpers.findAndHookMethod("o3.b", cl, "registerProtocol", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    Class<?> ndb = XposedHelpers.findClass("nd.b", cl);
                    Object runnable = XposedHelpers.newInstance(ndb, param.thisObject, 1);
                    XposedHelpers.callMethod(runnable, "run");
                    log("fix4a: handle receiver registered synchronously");
                } catch (Throwable t) {
                    log("fix4a: error", t);
                }
            }
        });
    }

    // ---------------------------------------------------------------
    // Fix 4b: o3.c.registerProtocol() — the original only registers an
    //         InputDeviceListener and never scans devices already present
    //         (handle plugged before camera start). Mirror the V4 patch:
    //         scan all input devices, call m(InputDevice) + n().
    // ---------------------------------------------------------------
    public static void hookInputDeviceManagerRegisterProtocol(final ClassLoader cl) {
        XposedHelpers.findAndHookMethod("o3.c", cl, "registerProtocol", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    Object self = param.thisObject;
                    Object imObj = XposedHelpers.getObjectField(self, "e");
                    if (!(imObj instanceof InputManager)) {
                        return;
                    }
                    InputManager im = (InputManager) imObj;
                    int[] ids = im.getInputDeviceIds();
                    for (int id : ids) {
                        InputDevice dev = im.getInputDevice(id);
                        if (dev != null) {
                            XposedHelpers.callMethod(self, "m", dev);
                        }
                    }
                    XposedHelpers.callMethod(self, "n");
                    log("fix4b: existing input devices scanned (" + ids.length + ")");
                } catch (Throwable t) {
                    log("fix4b: error", t);
                }
            }
        });
    }
    public static void hookS0(final ClassLoader cl) {
        XposedHelpers.findAndHookMethod("G7.b", cl, "S0", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (Boolean.TRUE.equals(S0_FORCE_ASD.get())) {
                    param.setResult(Boolean.TRUE);
                }
            }
        });
        hookAppendAsd("com.android.camera.module.BaseModule", cl);
        hookAppendAsd("com.android.camera.features.mode.polaroid.PolaroidModule", cl);
        log("fix-S0: targeted (S0()=true only during appendModuleExternalASD)");
    }

    public static void hookAppendAsd(String className, ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(className, cl,
                    "appendModuleExternalASD", "L3.e", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            S0_FORCE_ASD.set(Boolean.TRUE);
                        }
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            S0_FORCE_ASD.remove();
                        }
                    });
            log("fix-S0: gated appendModuleExternalASD on " + className);
        } catch (Throwable t) {
            log("fix-S0: hook appendModuleExternalASD failed on " + className + ": " + t);
        }
    }
}