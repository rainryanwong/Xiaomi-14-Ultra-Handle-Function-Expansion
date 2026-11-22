/**
 * ZoomHooks：变焦拨杆 / 快门高速连拍 / 焦段修复（移植包 promFix=开 时挂载）。
 * - 变焦拨杆三档：默认变焦(Y)/连续变焦(Z)/焦段切换(E)，以及变焦段高速连拍(1)
 * - 快门高速连拍：o3.c.V1/r4 决策点强制（尊重录像键 C 切换标志）
 * - 焦段修复：data.i 主按钮档位与完整档位列表
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
 * 变焦拨杆模式感知 + 快门高速连拍 + 焦段修复（独有功能，始终挂载）。
 */
public final class ZoomHooks {

    private ZoomHooks() {
    }

    /** 主类 install 注册表按版本条件调用 */
    public static void install(final ClassLoader cl) {
        HandleFixModule.install("zoom-aware", new Runnable() {
            public void run() { hookZoomAware(cl); }
        });
        // v12.0.37 起不再注册 hookZoomLeverModeAware：该旧实现直接调用 o1()/n() 并
        // setResult(true)，会绕开相机原生的场景支持判断、连续变焦提示和松杆收尾。
        // 方法本体暂时保留为兼容回退，若个别旧包的原生分发链不完整可快速恢复。
        HandleFixModule.install("shutter-burst", new Runnable() {
            public void run() { hookShutterBurst(cl); }
        });
        HandleFixModule.install("focal-fix", new Runnable() {
            public void run() { hookFocalFull(cl); }
        });
        HandleFixModule.install("focal-inner", new Runnable() {
            public void run() { hookFocalInner(cl); }
        });
        HandleFixModule.install("legacy-v51-portrait-zoom-limit", new Runnable() {
            public void run() { hookLegacyV51PortraitZoomLimit(cl); }
        });
    }

    public static void hookZoomAware(final ClassLoader cl) {
        hookNativeZoomPolicy(cl, "m9", true);
        hookNativeZoomPolicy(cl, "Wb", false);
        log("zoom-aware: native zoom policy routing installed");
    }

    /**
     * 按当前相机模式覆写手柄适配器的两项原生变焦策略，不接管按键事件。
     *
     * V5.0 实际 dex 链路：K3.u1.s() 不直接读取设置，而是通过 f4.K 协议查询
     * o3.c.m9(KeyEvent) 和 o3.c.Wb(KeyEvent)。o3.c 再转给当前 InputDevice 对应的
     * 手柄适配器（14 Ultra 为 C0477）：
     * - m9 -> adapter.f()：焦段切换或默认变焦时为 true；
     * - Wb -> adapter.g()：连续变焦或默认变焦时为 true。
     *
     * v12.0.37 曾在 onKeyDown/onKeyUp 期间临时覆写 pref_camera_handle_zoom，但实机
     * 仍采用手柄设置页的全局值，说明在该移植包上从设置层注入并不可靠。这里改在
     * 相机已经归一化后的两个策略决策点覆写，既能准确表达三种模式，又继续让
     * s()/C()/n()、ZoomManager.y2()、不支持场景提示和松杆收尾全部走原生实现。
     */
    private static void hookNativeZoomPolicy(final ClassLoader cl, final String methodName,
                                             final boolean segmentPolicy) {
        try {
            XposedHelpers.findAndHookMethod("o3.c", cl, methodName, KeyEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                if (!promFix()) return;
                                KeyEvent event = (KeyEvent) param.args[0];
                                if (event == null) return;
                                int keyCode = event.getKeyCode();
                                if (keyCode != 168 && keyCode != 169) return;

                                int mode = currentModuleIndex(null, cl);
                                String func = ModeConfig.getFunc(ModeConfig.KEY_ZOOM, mode);
                                if (func == null || ModeConfig.F_DEFAULT.equals(func)) return;

                                boolean enabled = ModeConfig.F_DEFZOOM.equals(func)
                                        || (segmentPolicy
                                        ? ModeConfig.F_ZOOM.equals(func)
                                        : ModeConfig.F_CONTZOOM.equals(func));
                                param.setResult(Boolean.valueOf(enabled));
                                debug("zoom-policy: " + methodName + "=" + enabled
                                        + " kc=" + keyCode + " mode=" + mode + " cfg=" + func);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            log("zoom-aware: o3.c." + methodName + " hook failed: " + t);
        }
    }



    /**
     * 旧版强制变焦拨杆实现（v12.0.37 起不再注册，仅保留作兼容回退）：
     * hook K3.u1.onKeyDown/onKeyUp(168/169)，按当前模式配置 KEY_ZOOM 分发
     * E(焦段切换)/Y(默认变焦)/Z(连续变焦)；直接调用 o1()/n() 并吃掉原事件。
     * 不要与 hookZoomAware 的原生路由同时启用，否则会重复处理一次按键。
     */
    public static void hookZoomLeverModeAware(final ClassLoader classLoader) {
        if (!promFix()) {
            log("zoom-lever-mode: promFix OFF, hooks skipped");
            return;
        }
        XposedHelpers.findAndHookMethod("K3.u1", classLoader, "onKeyDown", new Object[]{Integer.TYPE, KeyEvent.class, new XC_MethodHook() {
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                Object objCallMethod;
                try {
                    int iIntValue = ((Integer) methodHookParam.args[0]).intValue();
                    if ((iIntValue == 168 || iIntValue == 169) && HandleFixModule.promFix()) {
                        int iCurrentModuleIndex = currentModuleIndex(methodHookParam.thisObject, classLoader);
                        String func = ModeConfig.getFunc(ModeConfig.KEY_ZOOM, iCurrentModuleIndex);
                        HandleFixModule.debug("zoom-lever: down kc=" + iIntValue + " mode=" + iCurrentModuleIndex + " cfg=" + func);
                        if (func != null && !ModeConfig.F_DEFAULT.equals(func)) {
                            // obj = 变焦相关对象（thisObject），用于后续读取模式/功能值
                            Object obj = methodHookParam.thisObject;
                            KeyEvent keyEvent = (KeyEvent) methodHookParam.args[1];
                            Object objCallMethod2 = XposedHelpers.callMethod(obj, ModeConfig.F_ET, new Object[0]);
                            if (((Boolean) XposedHelpers.callMethod(objCallMethod2, "isPresent", new Object[0])).booleanValue()) {
                                Object objCallMethod3 = XposedHelpers.callMethod(objCallMethod2, "get", new Object[0]);
                                int i = iIntValue == 168 ? 1 : -1;
                                int repeatCount = keyEvent.getRepeatCount();
                                try {
                                    XposedHelpers.callStaticMethod(XposedHelpers.findClass("K3.u1", classLoader), "D1", new Object[]{Integer.valueOf(iIntValue), keyEvent});
                                } catch (Throwable unused) {
                                }
                                if (ModeConfig.F_CONTZOOM.equals(func)) {
                                    Object objCallMethod4 = XposedHelpers.callMethod(objCallMethod3, "getZoomManager", new Object[0]);
                                    if (objCallMethod4 != null) {
                                        float f = i * 0.1f;
                                        XposedHelpers.callMethod(objCallMethod4, "o1", new Object[]{Float.valueOf(f), 1});
                                        HandleFixModule.debug("zoom-lever: continuous o1(" + f + ")");
                                    }
                                } else if (ModeConfig.F_DEFZOOM.equals(func) || ModeConfig.F_ZOOM.equals(func)) {
                                    if (repeatCount == 0) {
                                        XposedHelpers.callMethod(obj, "n", new Object[]{Integer.valueOf(iIntValue), objCallMethod3, Boolean.FALSE, Boolean.TRUE});
                                        HandleFixModule.debug("zoom-lever: segment jump via n()");
                                    } else if (ModeConfig.F_DEFZOOM.equals(func) && (objCallMethod = XposedHelpers.callMethod(objCallMethod3, "getZoomManager", new Object[0])) != null) {
                                        XposedHelpers.callMethod(objCallMethod, "o1", new Object[]{Float.valueOf(i * 0.1f), 1});
                                    }
                                }
                                methodHookParam.setResult(Boolean.TRUE);
                            }
                        }
                    }
                } catch (Throwable unused2) {
                }
            }
        }});
        XposedHelpers.findAndHookMethod("K3.u1", classLoader, "onKeyUp", new Object[]{Integer.TYPE, KeyEvent.class, new XC_MethodHook() {
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                int iCurrentModuleIndex;
                String func;
                try {
                    int iIntValue = ((Integer) methodHookParam.args[0]).intValue();
                    if ((iIntValue == 168 || iIntValue == 169) && HandleFixModule.promFix() && (func = ModeConfig.getFunc(ModeConfig.KEY_ZOOM, (iCurrentModuleIndex = currentModuleIndex(methodHookParam.thisObject, classLoader)))) != null && !ModeConfig.F_DEFAULT.equals(func)) {
                        HandleFixModule.debug("zoom-lever: up kc=" + iIntValue + " mode=" + iCurrentModuleIndex + " cfg=" + func + " intercepted");
                        methodHookParam.setResult(Boolean.TRUE);
                    }
                } catch (Throwable unused) {
                }
            }
        }});
        log("zoom-lever-mode installed");
    }



    /**
     * 快门键高速连拍（模式感知 F_BURST，独有功能，始终挂载）：
     * V1 控制是否进 performMiHandlePressed（原生连拍入口），r4 控制连拍/单拍，
     * 两个决策点缺一不可；50 张上限/松手停止由原生 MultiCapture 自带。
     * v10.38 已删除早期 isNeedBurst/performKeyClicked 空壳 hook。
     */
    public static void hookShutterBurst(final ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("o3.c", classLoader, "V1", new Object[]{InputEvent.class, new XC_MethodHook() {
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    try {
                        if (HandleFixModule.promFix()) {
                            // obj = 按键事件（InputEvent/KeyEvent），快门连拍决策用
                            Object obj = methodHookParam.args[0];
                            if (obj instanceof KeyEvent) {
                                int keyCode = ((KeyEvent) obj).getKeyCode();
                                if (keyCode == 27 || keyCode == 170) {
                                    int iCurrentModuleIndex = currentModuleIndex(null, classLoader);
                                    if (DualStyleCaptureHooks.ownsShutterGesture(
                                            iCurrentModuleIndex)) {
                                        // V1=true 保持进入小米手柄原生快门入口；实际
                                        // 单张/连拍由下方 r4=true 精确选为单张。
                                        methodHookParam.setResult(Boolean.TRUE);
                                        HandleFixModule.debug("shutter-burst: C3 owns gesture,"
                                                + " V1 routed to handle path (mode="
                                                + iCurrentModuleIndex + ")");
                                        return;
                                    }
                                    String shutterFunc = ModeConfig.getFunc(ModeConfig.KEY_SHUTTER, iCurrentModuleIndex);
                                    if (ModeConfig.F_BURST.equals(shutterFunc)) {
                                        if (shouldUseNativeStreetReleaseState(iCurrentModuleIndex)) {
                                            return;
                                        }
                                        if (!isSnapBurst(classLoader)) {
                                            HandleFixModule.debug("shutter-burst: snap=single override, V1 not forced (mode=" + iCurrentModuleIndex + ")");
                                        } else {
                                            methodHookParam.setResult(Boolean.TRUE);
                                            HandleFixModule.debug("shutter-burst: V1 forced true -> native multicapture (mode=" + iCurrentModuleIndex + ")");
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable unused) {
                    }
                }
            }});
        } catch (Throwable th) {
            log("shutter-burst: o3.c.V1 hook failed: " + th);
        }
        try {
            XposedHelpers.findAndHookMethod("o3.c", classLoader, "r4", new Object[]{KeyEvent.class, new XC_MethodHook() {
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    try {
                        if (HandleFixModule.promFix()) {
                            int keyCode = ((KeyEvent) methodHookParam.args[0]).getKeyCode();
                            if (keyCode == 27 || keyCode == 170) {
                                int iCurrentModuleIndex = currentModuleIndex(null, classLoader);
                                    if (DualStyleCaptureHooks.ownsShutterGesture(
                                            iCurrentModuleIndex)) {
                                        // r4=false 是连拍，true 是单张。C3 事务建立后
                                        // 必须让首拍进入 startNormalCapture，不能先启动
                                        // 无法回滚的 MultiCapture。
                                        methodHookParam.setResult(Boolean.TRUE);
                                        HandleFixModule.debug("shutter-burst: C3 owns gesture,"
                                                + " r4 forced single (mode="
                                                + iCurrentModuleIndex + ")");
                                        return;
                                    }
                                    String shutterFunc = ModeConfig.getFunc(ModeConfig.KEY_SHUTTER, iCurrentModuleIndex);
                                    if (ModeConfig.F_BURST.equals(shutterFunc)) {
                                        if (shouldUseNativeStreetReleaseState(iCurrentModuleIndex)) {
                                            return;
                                        }
                                        if (!isSnapBurst(classLoader)) {
                                            HandleFixModule.debug("shutter-burst: snap=single override, r4 not forced (mode=" + iCurrentModuleIndex + ")");
                                        } else {
                                            methodHookParam.setResult(Boolean.FALSE);
                                            HandleFixModule.debug("shutter-burst: r4 forced false -> native burst (mode=" + iCurrentModuleIndex + ")");
                                    }
                                }
                            }
                        }
                    } catch (Throwable unused) {
                    }
                }
            }});
        } catch (Throwable th2) {
            log("shutter-burst: o3.c.r4 hook failed: " + th2);
        }
        log("shutter-burst installed");
    }

    /**
     * 读取模块自己的切换标志 handlefix_snap_mode（录像键 C 写入）：
     * 返回 true = 应保持强制连拍；false = 录像键 C 已切到单张，快门键 1 让位。
     * 标志不存在（从未按过录像键 C）时返回 true —— 保持“快门键设 1 = 高速连拍”的默认行为，
     * 不受相机 pref 默认值（老包可能是 single）影响。
     */
    private static boolean isSnapBurst(ClassLoader classLoader) {
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", (ClassLoader) null),
                    "currentApplication", new Object[0]);
            if (app instanceof android.content.Context) {
                String mode = ((android.content.Context) app)
                        .getSharedPreferences("com.android.camera_preferences", 0)
                        .getString("handlefix_snap_mode", null);
                return !"single".equals(mode);
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * NRV/Phoenix 街拍模式的录像键 C 通过相机原生 {@code attr_shutter} 切换单张/连拍。
     * 此组合下若实体快门 Hook 再读取旧的 {@code handlefix_snap_mode} 标志并强制 V1/r4，
     * 就会形成两个状态源：Toast 已切到单张，实际拍摄仍被强制连拍。这里仅对已经确认
     * 存在争用的「街拍 225 + 录像键 C」让位给相机原生决策；其它模式、其它录像键配置
     * 以及用户明确给快门键配置的高速连拍仍沿用模块原有逻辑。
     */
    private static boolean shouldUseNativeStreetReleaseState(int moduleIndex) {
        return moduleIndex == 225
                && ModeConfig.F_SHUTTER.equals(
                        ModeConfig.getFunc(ModeConfig.KEY_RECORD, moduleIndex));
    }



    /**
     * 焦段修复（独有功能，始终挂载）：hook data.i.P(int,boolean)/O(int)，
     * 163 模式返回主按钮列表 [0.5,1,2,3.2,5] 与完整档位列表（含衍生 1.2/1.5/10/30/60/120）。
     * v10.37 起移除每次调用日志（曾造成滤镜滑动刷屏）。
     */
    public static void hookFocalFull(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("com.android.camera.data.data.i", classLoader, ModeConfig.F_BOKEH, new Object[]{Integer.TYPE, Boolean.TYPE, new XC_MethodHook() {
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    try {
                        if (HandleFixModule.promFix() && ((Integer) methodHookParam.args[0]).intValue() == 163) {
                            methodHookParam.setResult(new float[]{0.5f, 1.0f, 2.0f, 3.2f, 5.0f});
                        }
                    } catch (Throwable unused) {
                    }
                }
            }});
            XposedHelpers.findAndHookMethod("com.android.camera.data.data.i", classLoader, ModeConfig.F_FOCUSP, new Object[]{Integer.TYPE, new XC_MethodHook() {
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    try {
                        if (HandleFixModule.promFix() && ((Integer) methodHookParam.args[0]).intValue() == 163) {
                            methodHookParam.setResult(new float[]{0.5f, 1.0f, 1.2f, 1.5f, 2.0f, 3.2f, 5.0f, 10.0f, 30.0f, 60.0f, 120.0f});
                        }
                    } catch (Throwable unused) {
                    }
                }
            }});
            log("focal-fix installed");
        } catch (Throwable th) {
            log("focal-fix hook failed: " + th);
        }
    }



    /**
     * 焦段修复-内层档位表（独有功能，始终挂载）：hook vc.h.l(boolean,int,boolean,Float[])，
     * 补全 163 内层表缺失的 1.2/1.5/60（拨杆“焦段切换”段位表与滑杆隐藏档位都从这里取数）。
     */
    public static void hookFocalInner(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("vc.h", classLoader, "l", new Object[]{Boolean.TYPE, Integer.TYPE, Boolean.TYPE, Float[].class, new XC_MethodHook() {
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    try {
                        if (!HandleFixModule.promFix() || ((Boolean) methodHookParam.args[0]).booleanValue()) {
                            return;
                        }
                        Float[] fArr = (Float[]) methodHookParam.getResult();
                        Float[] fArrInjectMissingInnerZooms = injectMissingInnerZooms(fArr);
                        if (fArrInjectMissingInnerZooms != fArr) {
                            methodHookParam.setResult(fArrInjectMissingInnerZooms);
                        }
                    } catch (Throwable unused) {
                    }
                }
            }});
            log("focal-inner installed");
        } catch (Throwable th) {
            log("focal-inner hook failed: " + th);
        }
    }

    /**
     * 旧版 Prometheus V5.0 人像变焦修复。
     *
     * V5.0 的手柄拨杆会把人像的段位索引继续推进到 10/30/60/120x，但该模式的
     * CameraCapabilities 最高只有 5x，最终不会切换镜头，因而造成 UI 与实际倍率脱节。
     * 在 ZoomManager 的两条最终提交路径统一钳制，既覆盖段位切换也覆盖连续变焦，
     * 不篡改其它模式的完整焦段表，也不引入 NRV LSP 中与 V5.0 ABI 不一致的档位表 Hook。
     */
    public static void hookLegacyV51PortraitZoomLimit(final ClassLoader classLoader) {
        final XC_MethodHook clampPortraitZoom = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    if (!isLegacyV51Portrait(classLoader)) {
                        return;
                    }
                    float requested = ((Float) param.args[0]).floatValue();
                    if (requested > 5.0f) {
                        // setZoomRatio 会同步保存当前倍率并驱动倍率 UI；此处截断可同步修正两者。
                        param.args[0] = Float.valueOf(5.0f);
                    }
                } catch (Throwable ignored) {
                    // 旧相机 ABI 不可用时保持原生行为，不影响其它版本。
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod("j6.c", classLoader, "setZoomRatio",
                    new Object[]{Float.TYPE, clampPortraitZoom});
            XposedHelpers.findAndHookMethod("j6.c", classLoader, "b0",
                    new Object[]{Float.TYPE, Integer.TYPE, clampPortraitZoom});
            log("legacy-v51 portrait zoom limit installed");
        } catch (Throwable th) {
            log("legacy-v51 portrait zoom limit hook failed: " + th);
        }
    }

    private static boolean isLegacyV51Portrait(ClassLoader classLoader) {
        return HandleFixModule.isV51RuntimeEnabled()
                && currentModuleIndex(null, classLoader) == 171;
    }

    public static Float[] injectMissingInnerZooms(Float[] in) {
        if (in == null || in.length == 0) {
            return in;
        }
        java.util.List<Float> list = new java.util.ArrayList<Float>();
        boolean has10 = false, has20 = false, has12 = false, has15 = false;
        boolean has30 = false, has60 = false, has120 = false;
        for (Float f : in) {
            if (f == null) {
                continue;
            }
            float v = f.floatValue();
            if (Math.abs(v - 1.0f) < 0.001f) has10 = true;
            else if (Math.abs(v - 2.0f) < 0.001f) has20 = true;
            else if (Math.abs(v - 1.2f) < 0.001f) has12 = true;
            else if (Math.abs(v - 1.5f) < 0.001f) has15 = true;
            else if (Math.abs(v - 30.0f) < 0.001f) has30 = true;
            else if (Math.abs(v - 60.0f) < 0.001f) has60 = true;
            else if (Math.abs(v - 120.0f) < 0.001f) has120 = true;
            list.add(f);
        }
        boolean changed = false;
        if (has10 && has20 && !has12) {
            int idx = indexOfFloat(list, 1.0f);
            if (idx >= 0) {
                list.add(idx + 1, Float.valueOf(1.2f));
                changed = true;
            }
        }
        if (has10 && has20 && !has15) {
            int idx = indexOfFloat(list, 1.2f);
            if (idx < 0) {
                idx = indexOfFloat(list, 1.0f);
            }
            if (idx >= 0) {
                list.add(idx + 1, Float.valueOf(1.5f));
                changed = true;
            }
        }
        if (has30 && has120 && !has60) {
            int idx = indexOfFloat(list, 120.0f);
            if (idx >= 0) {
                list.add(idx, Float.valueOf(60.0f));
                changed = true;
            }
        }
        if (!changed) {
            return in;
        }
        return list.toArray(new Float[list.size()]);
    }

    public static int indexOfFloat(java.util.List<Float> list, float v) {
        for (int i = 0; i < list.size(); i++) {
            Float f = list.get(i);
            if (f != null && Math.abs(f.floatValue() - v) < 0.001f) {
                return i;
            }
        }
        return -1;
    }
}
