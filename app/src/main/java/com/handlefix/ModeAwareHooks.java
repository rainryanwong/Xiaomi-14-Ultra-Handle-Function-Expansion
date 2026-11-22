/**
 * ModeAwareHooks：模式感知按键映射（移植包 promFix=开 时挂载，新旧包均生效）。
 * 按相机模式（moduleIndex）读取配置的功能字母，驱动：
 * - 录像键（K3.u1.onKeyUp）：徕卡切换 / 原生快门释放切换 / 功能值覆盖（KEY_FUNC_OVERRIDE）
 * - 波轮（K3.u1.i0）：替换波轮功能值，夜景光圈伪装模式号
 * - 快门键（F3.i）：快门键徕卡切换
 * 另含 NRV 门槛放行（installNrvGate，新包专用）。
 */
package com.handlefix;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
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
 * 模式感知按键映射（独有功能，始终挂载）：录像键/波轮/快门按当前模式分发，
 * 含 C 快门释放自实现（SP 落盘）、夜景伪装、NRV 门槛放行（installNrvGate）。
 */
public final class ModeAwareHooks {

    /** Prometheus V5 / NRV 共用的夜景 moduleId。 */
    private static final int MODE_NIGHT = 173;

    /** BottomPopupTips 原生 EV 自定义条目编号（日志显示为 customItem: 5）。 */
    private static final int CUSTOM_ITEM_EV = 5;

    private ModeAwareHooks() {
    }

    /** 主类 install 注册表按版本条件调用（模式感知始终挂载） */
    public static void install(final ClassLoader cl) {
        HandleFixModule.install("mode-aware", new Runnable() {
            public void run() { hookModeAware(cl); }
        });
    }

    /** NRV 专用：门槛放行 + 1.3.2 夜景 EV 缺项补齐（1.5.0 已有条目时自动跳过）。 */
    public static void installNrvGate(final ClassLoader cl) {
        HandleFixModule.install("config-item-gate", new Runnable() {
            public void run() { hookConfigItemGate(cl); }
        });
        HandleFixModule.install("nrv-night-ev-gate", new Runnable() {
            public void run() { hookNightEvGate(cl, false); }
        });
        HandleFixModule.install("nrv-night-ev-entry", new Runnable() {
            public void run() { hookNativeNightEvEntry(cl, false); }
        });
    }

    /**
     * V5.0 专用夜景 EV 修复。旧包的 f0.w0.m(173) 固定返回 false，先放行 EV
     * 数据能力，再由 hookNativeNightEvEntry 补原生常驻入口；仅在旧版开关开启时挂载。
     */
    public static void installLegacyV51Gate(final ClassLoader cl) {
        HandleFixModule.install("legacy-v51-night-ev-gate", new Runnable() {
            public void run() { hookNightEvGate(cl, true); }
        });
        HandleFixModule.install("legacy-v51-night-ev-entry", new Runnable() {
            public void run() { hookNativeNightEvEntry(cl, true); }
        });
    }
    public static void hookModeAware(final ClassLoader classLoader) {
        if (!promFix()) {
            log("mode-aware: promFix OFF, hooks skipped");
            return;
        }
        /*
         * K3.u1.onKeyUp 会根据相机的 lite 路径选择两个不同的读取器：
         *   普通拍照/手柄路径 -> v.c() -> pref_camera_handle_button
         *   街拍及部分特殊布局 -> v.g() -> pref_camera_handle_button_lite
         *
         * 旧实现只覆盖 c()。因此 NRV1.5.0 的街拍模式虽然进入了原生
         * attr_shutter 分支并弹出 Toast，实际读取的却仍是作者默认功能，表现为
         * 单张/高速连拍始终停在同一档。两个读取器必须使用同一份短生命周期覆盖，
         * 但不应把值写回两个 pref：原生 onKeyUp 会根据 isLite 自己更新正确的
         * pref_camera_handle_snap(_lite)，写回两份反而会让下次切换互相覆盖。
         */
        hookButtonAttributeAccessor(classLoader, "c");
        hookButtonAttributeAccessor(classLoader, "g");
        XposedHelpers.findAndHookMethod("K3.u1", classLoader, "onKeyUp", new Object[]{Integer.TYPE, KeyEvent.class, new XC_MethodHook() {
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                try {
                    if (HandleFixModule.promFix() && ((Integer) methodHookParam.args[0]).intValue() == 313) {
                        int iCurrentModuleIndex = currentModuleIndex(methodHookParam.thisObject, classLoader);
                        if (handleLongPressRecord(methodHookParam, classLoader, iCurrentModuleIndex)) {
                            return;
                        }
                        // 下面这些是录像键“短按”动作。长按/深长按由统一分发器决定；
                        // 若这里不再检查松键时长，旧配置会在长按释放时先执行一次短按
                        // 映射，再执行长按动作，表现为徕卡切换两次、相册与参数面板抢事件。
                        if (!isShortRecordRelease(methodHookParam)) {
                            return;
                        }
                        String func = ModeConfig.getFunc(ModeConfig.KEY_RECORD, iCurrentModuleIndex);
                        if (ModeConfig.F_FILTER.equals(func)) {
                            // V5.1/NRV 1.5/1.6 使用 K3.u1.i0 的原生 attr_filter 打开链；
                            // 第二次短按由共享控制器读取 f4.j 当前宿主并执行完整退出。
                            // Q 是 HandleFix 私有码，绝不能落入下面的 KEY_FUNC_OVERRIDE。
                            if (NativeFilterPanelHooks.handleRecordKey(methodHookParam,
                                    classLoader, HandleFixModule.CAMERA_VERSION_CODE, true,
                                    iCurrentModuleIndex)) {
                                methodHookParam.setResult(Boolean.TRUE);
                            }
                        } else if (ModeConfig.F_LEICA.equals(func)) {
                            if (LeicaHooks.toggleCvType(classLoader)) {
                                methodHookParam.setResult(Boolean.TRUE);
                            }
                        } else if (ModeConfig.F_PHOENIX_CLASSIC_STYLE.equals(func)) {
                            // 作者 LSP 的多 LUT 风格不是 attr_* 值，不能写入
                            // KEY_FUNC_OVERRIDE。状态判断、真实 setter、读回校验和提示统一
                            // 由共享入口处理，失败时不吞键。
                            if (PhoenixClassicStyleHooks.handleClassicStylePress(
                                    classLoader, iCurrentModuleIndex)) {
                                methodHookParam.setResult(Boolean.TRUE);
                            }
                        } else if (ModeConfig.F_SHUTTER.equals(func) && (iCurrentModuleIndex == 163 || iCurrentModuleIndex == 225)) {
                            // 试行原生 attr_shutter 路径：保留原方法继续执行，由相机自行切换
                            // 单张/高速连拍并展示“高速连拍：开/单张拍摄：开”提示。
                            // 不直写两份 pref：相机会根据当前 lite 路径更新正确的原生状态。
                            HandleFixModule.KEY_FUNC_OVERRIDE.set(func);
                        } else {
                            if (ModeConfig.F_DEFAULT.equals(func)) {
                                return;
                            }
                            HandleFixModule.KEY_FUNC_OVERRIDE.set(func);
                        }
                    }
                } catch (Throwable unused) {
                }
            }

            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                HandleFixModule.KEY_FUNC_OVERRIDE.remove();
            }
        }});
        /*
         * V5.1/NRV 的 K3 原生 onGenericMotionEvent 在前摄滤镜面板已显示时，会在
         * i0(attr_filter) 的实际分发日志之前被自身 UI/输入协议门禁拦截。只在当前
         * 真实滤镜 Fragment 可见且共享控制器完成相邻项切换时消费事件；其他场景
         * 继续交给相机原生拨轮逻辑。
         */
        XposedHelpers.findAndHookMethod("K3.u1", classLoader, "onGenericMotionEvent",
                new Object[]{MotionEvent.class, new XC_MethodHook() {
                    protected void beforeHookedMethod(
                            XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                        try {
                            if (!HandleFixModule.promFix()) return;
                            int mode = currentModuleIndex(methodHookParam.thisObject, classLoader);
                            if (NativeFilterPanelHooks.handleWheelEvent(methodHookParam,
                                    classLoader, HandleFixModule.CAMERA_VERSION_CODE, true,
                                    mode)) {
                                methodHookParam.setResult(Boolean.TRUE);
                            }
                        } catch (Throwable error) {
                            HandleFixModule.log("[filter-panel] K3 wheel hook failed", error);
                        }
                    }
                }});
        XposedHelpers.findAndHookMethod("K3.u1", classLoader, "i0", new Object[]{MotionEvent.class, Boolean.TYPE, String.class, new XC_MethodHook() {
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                try {
                    if (HandleFixModule.promFix()) {
                        int iCurrentModuleIndex = currentModuleIndex(methodHookParam.thisObject, classLoader);
                        String func = ModeConfig.getFunc(ModeConfig.KEY_WHEEL, iCurrentModuleIndex);
                        if (ModeConfig.F_DEFAULT.equals(func)) {
                            return;
                        }
                        // 用 funcToAttr（移植包映射）而非 funcToAttrOem：J 工作区(attr_workspace)
                        // 在官方表里为 null，导致 NRV 新版上波轮 167 设 J 失效；其余波轮功能两表一致
                        String wheelAttr = ModeConfig.funcToAttr(func);
                        if (wheelAttr != null) {
                            methodHookParam.args[2] = wheelAttr;
                        }
                        // 夜景 173 光圈：伪装 getModuleIndex=163 绕过 i0 模式限制。
                        // 老包 i0 光圈白名单不含 173（仅 162/163/167/169/180/186/225/164），
                        // NRV 同样需要伪装（v10.60 误将门控从 NRV 反转到老包导致 NRV 失效，
                        // 现去掉门控两版统一）。
                        // EV(K) 不伪装：官方相机原生支持；PrometheusCam NRV1.5.0 起作者已适配原生
                        // 夜景 EV（无需伪装）。V5.1/NRV-1.3.2 若选择 EV 无效属作者版本差异
                        if (iCurrentModuleIndex == 173 && ModeConfig.F_APERT.equals(func)) {
                            HandleFixModule.WHEEL_MODE_SPOOF.set(163);
                        }
                    }
                } catch (Throwable unused) {
                }
            }

            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                HandleFixModule.WHEEL_MODE_SPOOF.remove();
            }
        }});
        try {
            XposedHelpers.findAndHookMethod("F3.i", classLoader, "onShutterButtonClick", new Object[]{Integer.TYPE, new XC_MethodHook() {
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    try {
                        if (HandleFixModule.promFix() && ModeConfig.F_LEICA.equals(ModeConfig.getFunc(ModeConfig.KEY_SHUTTER, currentModuleIndex(null, classLoader))) && LeicaHooks.toggleCvType(classLoader)) {
                            methodHookParam.setResult(Boolean.TRUE);
                        }
                    } catch (Throwable unused) {
                    }
                }
            }});
        } catch (Throwable th) {
            log("mode-aware: shutter hook failed (F3/i may differ on this build): " + th);
        }
        log("mode-aware: config-driven key mapping installed");
    }

    /**
     * 在录像键 onKeyUp 的同一调用栈内覆盖手柄功能读取。
     *
     * <p>c/g 是 NRV、V5.1 与 Phoenix OS4 基线中稳定保留的两个静态读取器；
     * 某个早期构建若缺少其中一个，只记录该读取器失败，另一个仍可独立工作。
     * ThreadLocal 由 onKeyUp 的 after 回调清理，普通配置读取和相机生命周期不受影响。</p>
     */
    private static void hookButtonAttributeAccessor(final ClassLoader classLoader,
            final String methodName) {
        try {
            XposedHelpers.findAndHookMethod("com.android.camera.data.data.v", classLoader,
                    methodName, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(
                                XC_MethodHook.MethodHookParam methodHookParam) {
                            try {
                                if (!HandleFixModule.promFix()) {
                                    return;
                                }
                                String func = HandleFixModule.KEY_FUNC_OVERRIDE.get();
                                String attr = ModeConfig.funcToAttr(func);
                                if (attr != null) {
                                    methodHookParam.setResult(attr);
                                }
                            } catch (Throwable ignored) {
                                // 读取器失败时保留原生返回值，不能吞掉手柄事件。
                            }
                        }
                    });
            log("mode-aware: data.data.v." + methodName + " override hooked");
        } catch (Throwable t) {
            // c/g 在不同早期移植包中可能只保留其一；分别降级比中止整组 Hook 安全。
            log("mode-aware: data.data.v." + methodName + " hook failed: " + t);
        }
    }
    public static void hookConfigItemGate(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("com.android.camera2.compat.theme.custom.mm.top.editor.TopEditorHelperKt", classLoader, "supportedConfigItem", new Object[]{Integer.TYPE, Integer.TYPE, new XC_MethodHook() {
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    try {
                        if (HandleFixModule.KEY_FUNC_OVERRIDE.get() == null) {
                            return;
                        }
                        int iIntValue = ((Integer) methodHookParam.args[1]).intValue();
                        if (iIntValue == 218 || iIntValue == 165) {
                            methodHookParam.setResult(Boolean.TRUE);
                        }
                    } catch (Throwable unused) {
                    }
                }
            }});
            log("config-item-gate: super_eis supportedConfigItem gate installed");
        } catch (Throwable th) {
            log("config-item-gate hook failed: " + th);
        }
    }

    /** 共用夜景 EV 支持判断；legacyOnly 防止旧包修复泄漏到 NRV/官方相机。 */
    private static void hookNightEvGate(final ClassLoader classLoader, final boolean legacyOnly) {
        try {
            XposedHelpers.findAndHookMethod("f0.w0", classLoader, "m", new Object[]{Integer.TYPE, new XC_MethodHook() {
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    try {
                        if (legacyOnly && !HandleFixModule.isV51RuntimeEnabled()) {
                            return;
                        }
                        if (((Integer) methodHookParam.args[0]).intValue() == MODE_NIGHT
                                && !Boolean.TRUE.equals(methodHookParam.getResult())) {
                            methodHookParam.setResult(Boolean.TRUE);
                        }
                    } catch (Throwable unused) {
                    }
                }
            }});
            log((legacyOnly ? "legacy-v51" : "nrv") + ": f0.w0.m night EV gate installed");
        } catch (Throwable th) {
            log((legacyOnly ? "legacy-v51" : "nrv") + ": f0.w0.m night EV gate failed: " + th);
        }
    }

    /**
     * Prometheus V5.0 / NRV-1.3.2 夜景主界面的原生 EV 常驻入口补齐。
     *
     * 已实机验证的定位依据（以后同源相机出现“能调 EV、但没有 EV 0 按钮”时可复用）：
     * 1. 拍照模式点击日志为 BottomPopupTips customItem=5，随后加载
     *    FragmentManuallyExtra(fragmentId=254)；夜景同一位置只触发预览对焦，说明缺的是
     *    ModeUI 条目，而不是 EV 数据组件或 Fragment 显示开关。
     * 2. 真实 dex 描述符为 LJ1/a;->h()Ljava/util/List;（夜景 ModeUI，moduleId=173）。
     *    其基类 Lk1/c; 字段 f:Lv2/i; 是底部条目工厂，Lv2/i;->a()Lv2/c;
     *    会完整创建 customItem=5：原生布局、数值 updater、点击监听和文案都在其中。
     * 3. NRV-1.5.0 的 LJ1/a;.h() 已原生执行 list.add(this.f.a())；V5.0 与
     *    NRV-1.3.2 缺少这一步。故修复应补列表项，不能再改 f0.w0.n(173)，也不应
     *    直接强制 FeatureUI (7,254) 展开，否则只能得到临时 EV 条而没有常驻按钮。
     *
     * 版本策略不依赖模块 zip 的 NRV 版本字符串（相机进程未必能稳定读取）：直接检查
     * h() 返回列表是否已有字段 e=5。NRV-1.5.0 及后续已修版本会原样跳过；只有缺项的
     * V5.0 / NRV-1.3.2 才复用原生工厂补入，因此不会产生重复按钮。
     * f0.w0.m(173) 的独立 Hook 仍负责 EV 能力放行，两部分缺一不可。
     */
    private static void hookNativeNightEvEntry(final ClassLoader classLoader, final boolean legacyOnly) {
        try {
            XposedHelpers.findAndHookMethod("J1.a", classLoader, "h", new Object[]{new XC_MethodHook() {
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    try {
                        boolean legacyEnabled = HandleFixModule.isV51RuntimeEnabled();
                        if (!HandleFixModule.promFix() || legacyOnly != legacyEnabled) {
                            return;
                        }
                        Object result = methodHookParam.getResult();
                        if (!(result instanceof List) || hasCustomItem((List) result, CUSTOM_ITEM_EV)) {
                            return; // NRV-1.5.0 已原生包含 EV，禁止重复注入。
                        }
                        Object itemFactory = XposedHelpers.getObjectField(methodHookParam.thisObject, "f");
                        Object evItem = XposedHelpers.callMethod(itemFactory, "a");
                        if (evItem != null) {
                            // 与 NRV dex 顺序一致：EV 位于夜景现有美颜/滤镜条目之前。
                            ((List) result).add(0, evItem);
                            debug((legacyOnly ? "legacy-v51" : "nrv")
                                    + ": native night EV customItem=5 injected");
                        }
                    } catch (Throwable th) {
                        log((legacyOnly ? "legacy-v51" : "nrv")
                                + ": native night EV item inject failed: " + th);
                    }
                }
            }});
            log((legacyOnly ? "legacy-v51" : "nrv")
                    + ": J1.a.h native night EV item hook installed (duplicate-safe)");
        } catch (Throwable th) {
            log((legacyOnly ? "legacy-v51" : "nrv")
                    + ": J1.a.h native night EV item hook failed: " + th);
        }
    }

    /** v2.a 的真实 dex 字段 e 是自定义条目编号；反射失败时继续检查其它项。 */
    private static boolean hasCustomItem(List items, int itemId) {
        for (Object item : items) {
            if (item == null) continue;
            try {
                if (XposedHelpers.getIntField(item, "e") == itemId) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
    public static boolean handleLongPressRecord(XC_MethodHook.MethodHookParam methodHookParam, ClassLoader classLoader, int i) {
        final boolean phoenix = HandleFixModule.promFix()
                && CameraBuilds.isPhoenixOs4Family(HandleFixModule.CAMERA_VERSION_CODE);
        if (phoenix) {
            // Phoenix 的 q6.U0 由 PhoenixLeicaHooks 负责原生 CV_TYPE；该分支只会在
            // 非 Phoenix 的 K3/OEM 链被调用，防止同一释放事件被切换两次。
            return false;
        }
        return RecordLongPressDispatcher.dispatch(methodHookParam, classLoader, i,
                new RecordLongPressDispatcher.CvToggle() {
                    @Override
                    public boolean toggle() {
                        return HandleFixModule.promFix()
                                ? LeicaHooks.toggleCvType(classLoader)
                                : OemHooks.toggleOfficialCvType(classLoader, i);
                    }
                });
    }

    /** K3.u1 的风格动作只处理短按，避免长按同时落入预览照片/原生按键链。 */
    private static boolean isShortRecordRelease(XC_MethodHook.MethodHookParam param) {
        return RecordLongPressDispatcher.isShortRelease(param);
    }

    /**
     * 打开相册查看最新照片：查询 MediaStore 最新 _id -> REVIEW intent（com.miui.gallery），
     * 查询失败退化为打开相册首页。仅供 handleLongPressRecord 调用。
     */
    public static boolean openLatestPhoto() {
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", (ClassLoader) null),
                    "currentApplication", new Object[0]);
            if (!(app instanceof Context)) {
                return false;
            }
            Context context = (Context) app;
            Uri uri = null;
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, new String[]{"_id"},
                        null, null, "date_added DESC");
                if (cursor != null && cursor.moveToFirst()) {
                    uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0));
                }
            } catch (Throwable t) {
                // 查询失败不阻塞：退化为打开相册首页
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            Intent intent;
            if (uri != null) {
                intent = new Intent("com.android.camera.action.REVIEW", uri);
                intent.setPackage("com.miui.gallery");
                intent.putExtra("from_MiuiCamera", true);
            } else {
                intent = new Intent("android.intent.action.VIEW");
                intent.setPackage("com.miui.gallery");
            }
            intent.addFlags(268435456);
            context.startActivity(intent);
            return true;
        } catch (Throwable t) {
            log("long-press: open gallery failed: " + t);
            return false;
        }
    }
}
