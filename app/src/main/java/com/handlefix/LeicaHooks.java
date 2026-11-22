/**
 * LeicaHooks：徕卡经典/生动切换 + 官方手柄设置页选项注入 + MainTopBar 实例捕获预热。
 * - toggleCvType() 被模式感知录像键 A（徕卡切换）复用（新旧包都需要）
 * - installTopBarCaptureAsync()：MainTopBar 实例后台捕获，保证首次按键 A 切换可用
 * - hookHandleButtonOption()：仅老包（V5.1 开关=开）注入设置页选项（徕卡/格式/变焦段/工作区）
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
 * V5.1 开关控制的 V3.2 内容：录像键徕卡经典/生动切换 + 官方手柄设置页选项注入。
 * 新版已内置时主类跳过；toggleCvType 同时被 ModeAwareHooks 复用（模式感知 A 功能）。
 */
public final class LeicaHooks {

    private LeicaHooks() {
    }

    /** 主类 install 注册表按版本条件调用 */
    public static void install(final ClassLoader cl) {
        HandleFixModule.install("leica-toggle", new Runnable() {
            public void run() { hookLeicaToggle(cl); }
        });
        HandleFixModule.install("leica-option-in-settings", new Runnable() {
            public void run() { hookHandleButtonOption(cl); }
        });
        // MainTopBar 实例捕获预热由主类在公共路径统一调用（v51 开关之外），
        // 保证 NRV 新版（v51=关）下模式感知 A 切换首次按键也能拿到实例
    }

    /**
     * 手柄官方设置页选项注入（V3.2 内容，V5.1 开关=开时挂载）：
     * hook A2.w.b(String) 给按钮选项注入 徕卡经典/生动(attr_leica_style，默认显示)、
     * 专业模式照片格式(attr_format，devMode 显示)、变焦段(attr_zoom_segment，devMode 显示)；
     * 给波轮选项注入 专业模式参数预设(attr_workspace，devMode 显示)。
     * 老版 PrometheusCam（V5.1/1.x）无这些选项；NRV 新版已内置 -> V5.1 关时不挂载。
     */
    public static void hookHandleButtonOption(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod("A2.w", classLoader, "b", new Object[]{String.class, new XC_MethodHook() {
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                try {
                    Object result = methodHookParam.getResult();
                    if (result == null) {
                        return;
                    }
                    String[] strArrInsertAt = (String[]) XposedHelpers.getObjectField(result, "b");
                    String[] strArrInsertAt2 = (String[]) XposedHelpers.getObjectField(result, "c");
                    if (strArrInsertAt != null && strArrInsertAt2 != null) {
                        if (contains(strArrInsertAt2, "quick_recording")) {
                            int iIndexOf = indexOf(strArrInsertAt2, "quick_recording");
                            if (iIndexOf < 0) {
                                return;
                            }
                            int i = iIndexOf + 1;
                            if (!contains(strArrInsertAt2, "attr_leica_style")) {
                                strArrInsertAt = insertAt(strArrInsertAt, i, "徕卡经典/生动（新）");
                                strArrInsertAt2 = insertAt(strArrInsertAt2, i, "attr_leica_style");
                                i = iIndexOf + 2;
                            }
                            if (ModeConfig.isDevMode() && !contains(strArrInsertAt2, "attr_format")) {
                                strArrInsertAt = insertAt(strArrInsertAt, i, "专业模式照片格式（新）");
                                strArrInsertAt2 = insertAt(strArrInsertAt2, i, "attr_format");
                                i++;
                            }
                            if (ModeConfig.isDevMode() && !contains(strArrInsertAt2, "attr_zoom_segment")) {
                                strArrInsertAt = insertAt(strArrInsertAt, i, "变焦段（新）");
                                strArrInsertAt2 = insertAt(strArrInsertAt2, i, "attr_zoom_segment");
                            }
                            XposedHelpers.setObjectField(result, "b", strArrInsertAt);
                            XposedHelpers.setObjectField(result, "c", strArrInsertAt2);
                            HandleFixModule.log("handle-option: injected leica/format/zoom_segment into button options");
                            return;
                        }
                        if ((contains(strArrInsertAt2, "attr_ev") || contains(strArrInsertAt2, "attr_variable_aperture")) && ModeConfig.isDevMode() && !contains(strArrInsertAt2, "attr_workspace")) {
                            int length = strArrInsertAt2.length;
                            String[] strArrInsertAt3 = insertAt(strArrInsertAt, length, "专业模式参数预设（新）");
                            String[] strArrInsertAt4 = insertAt(strArrInsertAt2, length, "attr_workspace");
                            XposedHelpers.setObjectField(result, "b", strArrInsertAt3);
                            XposedHelpers.setObjectField(result, "c", strArrInsertAt4);
                            HandleFixModule.log("handle-option: injected attr_workspace into wheel options (devMode)");
                        }
                    }
                } catch (Throwable th) {
                    HandleFixModule.log("handle-option hook error: " + th);
                }
            }
        }});
        log("handle-option: handle settings options injected (via A2/w.b)");
    }


    public static int indexOf(String[] arr, String s) {
        for (int i = 0; i < arr.length; i++) {
            if (s.equals(arr[i])) {
                return i;
            }
        }
        return -1;
    }

    public static String[] insertAt(String[] arr, int pos, String s) {
        String[] out = new String[arr.length + 1];
        System.arraycopy(arr, 0, out, 0, pos);
        out[pos] = s;
        System.arraycopy(arr, pos, out, pos + 1, arr.length - pos);
        return out;
    }

    public static boolean contains(String[] arr, String s) {
        for (String a : arr) {
            if (s.equals(a)) {
                return true;
            }
        }
        return false;
    }

    public static void hookLeicaToggle(final ClassLoader cl) {
        XposedHelpers.findAndHookMethod("K3.u1", cl, "onKeyUp", int.class, KeyEvent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            // Hook 可能在用户切换映射方案前已经安装；运行时再次门控，
                            // 防止官方相机方案被旧的 Prometheus hook 抢走录像键事件。
                            if (!HandleFixModule.promFix()) {
                                return;
                            }
                            int kc = (Integer) param.args[0];
                            if (kc != 313) {
                                return;
                            }
                            // 该 Hook 只负责短按原生 attr_leica_style。长按/深长按已经
                            // 由 RecordLongPressDispatcher 统一判定；短按门同时覆盖所有
                            // 长按动作，避免一次释放被二次消费。
                            if (!RecordLongPressDispatcher.isShortRelease(param)) {
                                return;
                            }
                            String func = currentHandleButtonFunction(cl);
                            if (!"attr_leica_style".equals(func)) {
                                return;
                            }
                            if (toggleCvType(cl)) {
                                log("leica-toggle: record button toggled Leica style");
                                param.setResult(Boolean.TRUE);
                            }
                        } catch (Throwable t) {
                            log("leica-toggle error: " + t);
                        }
                    }
                });
        log("leica-toggle: record button -> CV_TYPE (Leica Classic/Vibrant) installed");
    }

    public static String currentHandleButtonFunction(ClassLoader cl) {
        try {
            return (String) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.camera.data.data.v", cl), "c");
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean toggleCvType(ClassLoader cl) {
        try {
            Class<?> repoCls = XposedHelpers.findClass("d0.a", cl);
            Object repo = XposedHelpers.callStaticMethod(repoCls, "a");
            if (repo == null) {
                return false;
            }
            Class<?> cvCls = XposedHelpers.findClass("f0.l", cl);
            Object cvData = XposedHelpers.callMethod(repo, "v", cvCls);
            if (cvData == null) {
                return false;
            }
            int mode = ((Integer) XposedHelpers.getIntField(cvData, "mCurrentMode")).intValue();
            if (mode <= 0) {
                log("leica-toggle: mCurrentMode=" + mode + ", skip");
                return false;
            }
            String target = (String) XposedHelpers.callMethod(cvData, "h", mode);
            String current = (String) XposedHelpers.callMethod(cvData, "getComponentValue", mode);
            if (target == null || target.equals(current)) {
                return false;
            }
            // Preferred: replay the REAL MainTopBar.onCvClick(null) so the top-bar
            // text AND the live preview LUT both refresh, exactly like a manual tap.
            ensureTopBarHook(cl);
            Object topBar = CURRENT_TOP_BAR;
            if (topBar != null) {
                XposedHelpers.callMethod(topBar, "onCvClick", new Class<?>[]{android.view.View.class}, (Object) null);
                log("leica-toggle: CV_TYPE mode=" + mode + " " + current + " -> " + target + " (via onCvClick)");
                return true;
            }
            // Fallback: persist the value only (UI may lag until next mode switch).
            XposedHelpers.callMethod(cvData, "setComponentValue", mode, target);
            log("leica-toggle: CV_TYPE mode=" + mode + " " + current + " -> " + target + " (pref only)");
            return true;
        } catch (Throwable t) {
            log("leica-toggle toggleCvType error: " + t);
            return false;
        }
    }
    public static void ensureTopBarHook(ClassLoader classLoader) {
        if (TOP_BAR_HOOK_READY) {
            return;
        }
        synchronized (TOP_BAR_LOCK) {
            if (TOP_BAR_HOOK_READY) {
                return;
            }
            try {
                Class clsFindClass = XposedHelpers.findClass("com.android.camera2.compat.theme.custom.mm.top.MainTopBar", classLoader);
                try {
                    XposedHelpers.findAndHookConstructor(clsFindClass, new Object[]{XposedHelpers.findClass("com.android.camera2.compat.theme.custom.mm.top.BaseFragmentTopBar", classLoader), new XC_MethodHook() {
                        protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                            HandleFixModule.CURRENT_TOP_BAR = methodHookParam.thisObject;
                        }
                    }});
                    log("leica-toggle: MainTopBar <init> hook installed");
                } catch (Throwable th) {
                    log("leica-toggle: MainTopBar <init> hook failed: " + th);
                }
                try {
                    XposedHelpers.findAndHookMethod(clsFindClass, "updateConfigItem", new Object[]{int[].class, new XC_MethodHook() {
                        protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                            HandleFixModule.CURRENT_TOP_BAR = methodHookParam.thisObject;
                        }
                    }});
                    log("leica-toggle: MainTopBar.updateConfigItem hook installed");
                } catch (Throwable th2) {
                    log("leica-toggle: MainTopBar.updateConfigItem hook failed: " + th2);
                }
                try {
                    XposedHelpers.findAndHookMethod(clsFindClass, "provideAnimateElement", new Object[]{Integer.TYPE, List.class, Integer.TYPE, new XC_MethodHook() {
                        protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                            HandleFixModule.CURRENT_TOP_BAR = methodHookParam.thisObject;
                        }
                    }});
                    log("leica-toggle: MainTopBar.provideAnimateElement hook installed");
                } catch (Throwable th3) {
                    log("leica-toggle: MainTopBar.provideAnimateElement hook failed: " + th3);
                }
                TOP_BAR_HOOK_READY = true;
                log("leica-toggle: MainTopBar instance hooks ready");
            } catch (Throwable unused) {
            }
        }
    }

    // 相机 UI 类可能晚于 LSPosed 入口加载。启动期做有限退避重试以尽早捕获实例；
    // 超出窗口后，toggleCvType() 仍会在真正需要时调用 ensureTopBarHook() 兜底，
    // 因此不再为一次失败持续唤醒 150 秒。
    public static void installTopBarCaptureAsync(final ClassLoader cl) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final long[] retryDelaysMs = new long[]{250L, 500L, 1000L, 2000L, 4000L, 8000L, 8000L};
                for (int i = 0; i <= retryDelaysMs.length; i++) {
                    if (TOP_BAR_HOOK_READY) {
                        return;
                    }
                    try {
                        ensureTopBarHook(cl);
                        if (TOP_BAR_HOOK_READY) {
                            log("leica-toggle: MainTopBar hooks installed (async, attempt " + i + ")");
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                    if (i < retryDelaysMs.length) {
                        try {
                            Thread.sleep(retryDelaysMs[i]);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                    }
                }
            }
        }, "HandleFix-TopBarHook");
        t.setDaemon(true);
        t.start();
    }
}
