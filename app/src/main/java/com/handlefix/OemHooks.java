/**
 * OemHooks：官方相机按键映射与快门决策，以及 Phoenix 使用的 OS4 基线入口。
 * - pref 读取覆盖（oemPrefOverride）+ data.r/s 直接读取覆盖（c=录像键/e=快门键）
 * - 官方快门单张/连拍决策点（i3.e.p4 / g3.c.n4）与 ShotType 责任链（H3）
 * - 录像键 C（attr_shutter）为权威切换：其生效时快门键 C/1 的覆盖/强制全部让位
 *
 * ============ OS4 官方相机（6.6.000460.0 / 6.6.000510.0）混淆类名对照表 ============
 * 说明：以下均为 dex 方法级反查确认的真实描述符。jadx 因大小写冲突显示的包名/类名
 * （如 p563q6/p153q6、InterfaceC1894i0/0533i0、P4.C）与真实名不一致，勿直接照抄 jadx。
 * 460/510 两版本混淆不同，代码里统一用“双候选数组”适配，新版本适配时按表核对。
 *
 * 类名（460 / 510）                         角色 / 原名
 * q6.d1 / q6.g1                             ManuallyValueChangeImpl（log tag 实锤；EV 精确数值入口 Ud）
 * q6.R0 / q6.U0                             KeyEventImpl（log tag 实锤；波轮分发 G1）
 * Q6.i0（两版本一致）                        FeatureUI 接口（jadx 显示 InterfaceC0533i0/1894i0）
 * Q6.U0（两版本一致）                        FragmentManuallyExtra 协议（EV 条面板）
 * com.android.camera.data.data.c             手动组件 ComponentData 基类（R8 短名，无原始名）
 * com.android.camera.ui.EvTipView            顶栏 EV_TIP 数值组件（R8 保留原名，XML 引用）
 * com.android.camera.module.r                BaseModule 基类（setEvValue/onEvChanged/getModuleIndex）
 * Wh.a.m                                     OS4 pref 读取链（S7/I.invoke -> V.m -> Wh.a.m）
 *
 * 旧版本候选（OS4 上不存在，仅 6.4.x 用；安装失败无害）：
 *   6.4.000370.0：A3.Q1（按键分发）、g3.c.n4（快门决策）、data.data.s.c/e（pref）、U9.a.l、H3.a/H3.f
 *   6.4.000530.0：C3.F1（按键分发）、i3.e.p4（快门决策）、data.data.r.c/e（pref）、W9.a.l、H3.a/H3.f
 * =============================================================================
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
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;

import android.view.WindowManager;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.handlefix.HandleFixModule.*;

/**
 * 官方相机（promFix=关）按键映射：U9/W9.a.l pref 覆盖 + data.data.s/r.c/e 直读 + 连拍。
 */
public final class OemHooks {

    private OemHooks() {
    }

    /** OS4 EV 数值条（EvTipView）实例缓存：onAttachedToWindow/setEvValue 时刷新 */
    private static volatile Object EV_TIP_VIEW = null;

    /** EvTipView 候选实例（保留最近若干，避免只缓存到非当前布局/隐藏实例） */
    private static final List<Object> EV_TIP_VIEWS = new CopyOnWriteArrayList<>();

    /** 主线程 Handler：EvTipView.setEvValue 必须在 UI 线程调用（后台线程调用时 Folme 动画不生效、视图不显示） */
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /** 连续拨轮只保留最新 EV 值，避免每个 Ud 调用都向主线程堆积一个 Runnable。 */
    private static volatile float PENDING_EV_VALUE = 0f;
    private static final AtomicBoolean EV_UPDATE_POSTED = new AtomicBoolean(false);

    /** 官方 6.6.510 顶栏实例（真实 dex：V9.i0；入口 s9(View)）。 */
    private static volatile Object OFFICIAL_OS4_CV_TOP_BAR = null;
    private static volatile boolean OFFICIAL_OS4_CV_HOOK_READY = false;

    /** 官方 6.4.x 顶栏实例（真实类名：com.android...mm.top.MainTopBar）。 */
    private static volatile Object OFFICIAL_OS3_CV_TOP_BAR = null;
    private static volatile boolean OFFICIAL_OS3_CV_HOOK_READY = false;

    /**
     * 官方相机 Profile：除按键映射外，还注册 OS4 6.6.510 的 EV 面板展开补偿。
     * 该补偿仅解决官方提取包缺少 EV 小按钮时的波轮可视化问题。
     */
    public static void install(final ClassLoader cl) {
        install(cl, CameraBuilds.UNKNOWN_VERSION_CODE);
    }

    /** 官方相机安装入口；版本号用于启用已反查的原生长按徕卡入口。 */
    public static void install(final ClassLoader cl, final long cameraVersionCode) {
        installProfile(cl, "official", true, cameraVersionCode);
    }

    /**
     * Phoenix-0.3.0 的 OS4 基线 Profile。
     *
     * <p>它沿用 6.6.510 的底层按键映射与快门决策，不能再挂 Prometheus 独有链。EV
     * 显示补偿虽与官方相机共用 OS4 的真实描述符，但只会在 Phoenix 普通夜景
     * （实机确认 moduleId=173）的拨轮路径启用；普通/专业等已有原生面板的路径不重复接管。
     * Phoenix 的街拍特别款 moduleId=169 使用另一套作者面板，不在这里改写。</p>
     */
    public static void installPhoenixOs4(final ClassLoader cl) {
        installPhoenixOs4(cl, CameraBuilds.UNKNOWN_VERSION_CODE);
    }

    /** Phoenix 使用 OS4 底层按键映射，但不复用官方 6.6.510 的徕卡长按入口。 */
    public static void installPhoenixOs4(final ClassLoader cl, final long cameraVersionCode) {
        installProfile(cl, "phoenix-os4", false, cameraVersionCode);
    }

    private static void installProfile(final ClassLoader cl, final String profile,
            final boolean officialCompatibilityProfile, final long cameraVersionCode) {
        HandleFixModule.install("oem-map-" + profile, new Runnable() {
            public void run() { hookOemMapping(cl, officialCompatibilityProfile, cameraVersionCode); }
        });
    }

    public static void updateOemMode(Object thisObj, ClassLoader cl) {
        // 6.4.x 使用 C3.F1.T()/A3.Q1.O()；OS4 6.6.510 的真实分发类 q6.U0
        // 使用 h0()。候选逐个尝试，避免把一个版本的混淆入口强行套到另一个版本。
        for (String mn : new String[]{"h0", "T", "O"}) {
            try {
                Object opt = XposedHelpers.callMethod(thisObj, mn);
                boolean present = ((Boolean) XposedHelpers.callMethod(opt, "isPresent")).booleanValue();
                if (present) {
                    Object module = XposedHelpers.callMethod(opt, "get");
                    int m = ((Integer) XposedHelpers.callMethod(module, "getModuleIndex")).intValue();
                    if (m > 0) {
                        OEM_CURRENT_MODE = m;
                        return;
                    }
                }
            } catch (Throwable t) {
                // try next
            }
        }
        // 兜底：用模式缓存（官方相机同样存在 街拍225 等模式）
        if (CURRENT_MODE > 0) {
            OEM_CURRENT_MODE = CURRENT_MODE;
        }
    }

    public static String oemKeyToConfigKey(String pref) {
        if (pref == null) return null;
        if (pref.endsWith("_button")) return ModeConfig.KEY_RECORD;
        if (pref.endsWith("_wheel")) return ModeConfig.KEY_WHEEL;
        if (pref.endsWith("_snap")) return ModeConfig.KEY_SHUTTER;
        if (pref.endsWith("_zoom")) return ModeConfig.KEY_ZOOM;
        return null;
    }

    private static void hookOemMapping(final ClassLoader cl, final boolean officialCompatibilityProfile,
            final long cameraVersionCode) {
        // 官方按键分发（6.4.x=A3/Q1、C3/F1；OS4 6.6.510=q6.U0），事件进来时刷新当前模式。
        // q6.U0 只在官方 OS4 Profile 加入；Phoenix 同基线由自己的专用 Hook 处理，
        // 不让两套分发器同时消费同一事件。
        final String[] keyHandlerClasses = officialCompatibilityProfile
                && cameraVersionCode == CameraBuilds.OFFICIAL_OS4_6_6_510_VERSION_CODE
                ? new String[]{"q6.U0", "C3.F1", "A3.Q1", "com.android.camera.Camera"}
                : new String[]{"C3.F1", "A3.Q1", "com.android.camera.Camera"};
        for (final String cls : keyHandlerClasses) {
            for (final String m : new String[]{"onKeyDown", "onKeyUp"}) {
                try {
                    XposedHelpers.findAndHookMethod(cls, cl, m, int.class, KeyEvent.class,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    updateOemMode(param.thisObject, cl);
                                    // 官方相机录像键(313)：先处理已核实的 OS3 专业工作区短按；
                                    // 其余事件继续交给既有长按/原生分支，避免改变相册预览行为。
                                    if ("onKeyUp".equals(m) && param.args.length > 0) {
                                        try {
                                            int kc = ((Integer) param.args[0]).intValue();
                                            if (kc == 313 && officialCompatibilityProfile
                                                    && NativeFilterPanelHooks.handleRecordKey(
                                                    param, cl, cameraVersionCode, false,
                                                    OEM_CURRENT_MODE)) {
                                                param.setResult(Boolean.TRUE);
                                                return;
                                            }
                                            if (kc == 313 && officialCompatibilityProfile
                                                    && OfficialParamPanelHooks.isKeyHandlerClass(
                                                    cls, cameraVersionCode)
                                                    && OfficialParamPanelHooks.handleRecordKey(
                                                    param, cl, cameraVersionCode)) {
                                                param.setResult(Boolean.TRUE);
                                                return;
                                            }
                                            if (kc == 313 && OEM_CURRENT_MODE > 0) {
                                                ModeAwareHooks.handleLongPressRecord(param, cl, OEM_CURRENT_MODE);
                                            }
                                        } catch (Throwable error) {
                                            // 这里只在真正异常时记录；正式包不输出逐按键诊断。
                                            log("[filter-panel] " + cls + ".onKeyUp failed", error);
                                        }
                                    }
                                }
                            });
                } catch (Throwable t) {
                    log("oem-map: " + cls + "." + m + " hook failed: " + t);
                }
            }
            try {
                XposedHelpers.findAndHookMethod(cls, cl, "onGenericMotionEvent", android.view.MotionEvent.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                updateOemMode(param.thisObject, cl);
                                if (officialCompatibilityProfile
                                        && NativeFilterPanelHooks.handleWheelEvent(
                                        param, cl, cameraVersionCode, false,
                                        OEM_CURRENT_MODE)) {
                                    param.setResult(Boolean.TRUE);
                                    return;
                                }
                                if (officialCompatibilityProfile
                                        && OfficialParamPanelHooks.isKeyHandlerClass(
                                        cls, cameraVersionCode)
                                        && OfficialParamPanelHooks.handleWheelEvent(
                                        param, cl, cameraVersionCode)) {
                                    param.setResult(Boolean.TRUE);
                                    return;
                                }
                            }
                        });
            } catch (Throwable t) {
                log("oem-map: " + cls + ".onGenericMotionEvent hook failed: " + t);
            }
        }
        // pref 读取基类：旧版 U9/a、6.4.000530.0 为 W9/a 的 l(String,String)；OS4 6.6.000460.0/000510.0 为 Wh/a 的 m(String,String)
        // （OS4 手柄 pref 读取链：S7/I.invoke -> V.m -> DataItem 体系 mParentDataItem.m = Wh.a.m）
        for (final String cls : new String[]{"W9.a", "U9.a", "Wh.a"}) {
            for (final String mn : new String[]{"l", "m"}) {
                try {
                    XposedHelpers.findAndHookMethod(cls, cl, mn, String.class, String.class,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                    oemPrefOverride(param);
                                }
                            });
                    log("oem-map: " + cls + "." + mn + " hooked");
                } catch (Throwable t) {
                    log("oem-map: " + cls + "." + mn + " hook failed: " + t);
                }
            }
        }
        installProfileSpecificExtras(cl, officialCompatibilityProfile, cameraVersionCode);

        // 官方快门单张/连拍真正决策点：
        //   旧版 6.4.000370.0 = g3/c.n4(KeyEvent)；新版 6.4.000530.0 = i3/e.p4(KeyEvent)
        // 实测日志证实：官方相机手柄快门分支不读 pref（oem-map 覆盖对行为无效），
        // 而是设备管理器缓存的 burst 标志；因此必须 hook 该决策方法才能控制连拍/单张：
        //   true  -> doKeyShutterSnap（单张）；false -> doKeyShutterLongPress（连拍）
    for (final String[] cand : new String[][]{
            {"i3.e", "p4"},  // 6.4.000530.0
            {"g3.c", "n4"}}) { // 6.4.000370.0
        try {
            XposedHelpers.findAndHookMethod(cand[0], cl, cand[1], KeyEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                KeyEvent ev = (KeyEvent) param.args[0];
                                if (ev == null) return;
                                int kc = ev.getKeyCode();
                                if (kc != 27 && kc != 170) return;
                                int mode = CURRENT_MODE;
                                String f = ModeConfig.getFunc(ModeConfig.KEY_SHUTTER, mode);
                                if (DualStyleCaptureHooks.ownsShutterGesture(mode)) {
                                    // C3 与高速连拍共用同一按住手势；事务已建立时必须先
                                    // 走单张首拍，否则连拍会抢占 startNormalCapture。
                                    // 旧版决策点 true=单张。
                                    param.setResult(Boolean.TRUE);
                                    debug("oem-burst: C3 owns gesture -> single (mode="
                                            + mode + ")");
                                    return;
                                }
                                // 录像键 C（快门释放模式）生效时让位：官方 attr_shutter 原生切换
                                // 单张/连拍，快门键 C/1 的强制不再干预
                                if (isRecordShutterActive()) {
                                    debug("oem-burst: " + cand[0] + "." + cand[1] + " record=shutter-release, not forced (mode=" + mode + ")");
                                    return;
                                }
                                if (ModeConfig.F_BURST.equals(f)) {
                                    param.setResult(Boolean.FALSE);
                                    debug("oem-burst: " + cand[0] + "." + cand[1] + " forced false -> burst (mode=" + mode + ")");
                                } else if (ModeConfig.F_SHUTTER.equals(f)) {
                                    param.setResult(Boolean.TRUE);
                                    debug("oem-burst: " + cand[0] + "." + cand[1] + " forced true -> single (mode=" + mode + ")");
                                }
                            } catch (Throwable t) { /* never break */ }
                        }
                    });
            log("oem-burst: " + cand[0] + "." + cand[1] + " hooked");
        } catch (Throwable t) {
            log("oem-burst: " + cand[0] + "." + cand[1] + " hook failed: " + t);
        }
    }

    // OS4 6.6.000460.0/000510.0：连拍/单张决策点
    //   isNeedBurst(int,KeyEvent)：true=连拍 false=单张（语义与旧版相反）
    //   lambda$performMiHandlePressed$50(KeyEvent,Q6.L)：true=doKeyShutterLongPress(连拍) false=doKeyShutterSnap(单张)
    try {
        XposedHelpers.findAndHookMethod("com.android.camera.module.Camera2Module", cl, "isNeedBurst", int.class, KeyEvent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Boolean holdBurst = ShutterHoldBurstController.overrideIsNeedBurst(
                                    param.thisObject,
                                    ((Integer) param.args[0]).intValue(),
                                    (KeyEvent) param.args[1],
                                    cl);
                            if (holdBurst != null) {
                                param.setResult(holdBurst);
                                return;
                            }
                            int mode = CURRENT_MODE;
                            String f = ModeConfig.getFunc(ModeConfig.KEY_SHUTTER, mode);
                            if (DualStyleCaptureHooks.ownsShutterGesture(mode)) {
                                param.setResult(Boolean.FALSE); // OS4 true=burst, false=single
                                debug("oem-burst(OS4): C3 owns gesture, isNeedBurst=false"
                                        + " (mode=" + mode + ")");
                                return;
                            }
                            if (isRecordShutterActive()) return; // 录像键 C 权威切换让位
                            if (ModeConfig.F_BURST.equals(f)) {
                                param.setResult(Boolean.TRUE);
                                debug("oem-burst(OS4): isNeedBurst forced true -> burst (mode=" + mode + ")");
                            } else if (ModeConfig.F_SHUTTER.equals(f)) {
                                param.setResult(Boolean.FALSE);
                                debug("oem-burst(OS4): isNeedBurst forced false -> single (mode=" + mode + ")");
                            }
                        } catch (Throwable t) { /* never break */ }
                    }
                });
        log("oem-burst: Camera2Module.isNeedBurst hooked (OS4)");
    } catch (Throwable t) {
        log("oem-burst: Camera2Module.isNeedBurst hook failed: " + t);
    }
    try {
        XposedHelpers.findAndHookMethod("com.android.camera.module.Camera2Module", cl, "lambda$performMiHandlePressed$50", KeyEvent.class,
                XposedHelpers.findClass("Q6.L", cl),
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            // Phoenix OS4 的 F_HOLD_BURST 只在同一物理事务达到阈值后
                            // 接管这个已核实的原生长按路由；其它配置返回 null，继续沿用
                            // 下方既有 C3/静态高速连拍语义。
                            Boolean holdBurst = ShutterHoldBurstController.overrideMiHandle(
                                    param.thisObject,
                                    (KeyEvent) param.args[0],
                                    cl);
                            if (holdBurst != null) {
                                param.setResult(holdBurst);
                                return;
                            }
                            int mode = CURRENT_MODE;
                            String f = ModeConfig.getFunc(ModeConfig.KEY_SHUTTER, mode);
                            if (DualStyleCaptureHooks.ownsShutterGesture(mode)) {
                                param.setResult(Boolean.FALSE); // false -> doKeyShutterSnap(单张)
                                debug("oem-burst(OS4): C3 owns gesture, mihandle=single"
                                        + " (mode=" + mode + ")");
                                return;
                            }
                            if (isRecordShutterActive()) return;
                            if (ModeConfig.F_BURST.equals(f)) {
                                param.setResult(Boolean.TRUE); // true -> doKeyShutterLongPress(连拍)
                                debug("oem-burst(OS4): mihandle lambda forced true -> burst (mode=" + mode + ")");
                            } else if (ModeConfig.F_SHUTTER.equals(f)) {
                                param.setResult(Boolean.FALSE); // false -> doKeyShutterSnap(单张)
                                debug("oem-burst(OS4): mihandle lambda forced false -> single (mode=" + mode + ")");
                            }
                        } catch (Throwable t) { /* never break */ }
                    }
                });
        log("oem-burst: Camera2Module.lambda$performMiHandlePressed$50 hooked (OS4)");
    } catch (Throwable t) {
        log("oem-burst: Camera2Module.lambda$performMiHandlePressed$50 hook failed: " + t);
    }
        // 官方 ShotTypeHandler 责任链 H3/a.d() 日志/兜底：
        // 注意：6.4.000530.0 里 H3/a.d() 变成 abstract，不能 hook（跳过即可，不影响功能）。
        try {
            java.lang.reflect.Method dm = XposedHelpers.findMethodExact("H3.a", cl, "d");
            if (dm != null && !java.lang.reflect.Modifier.isAbstract(dm.getModifiers())) {
                XposedHelpers.findAndHookMethod("H3.a", cl, "d",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                try {
                                    int mode = -1;
                                    Object g = XposedHelpers.getObjectField(param.thisObject, "a"); // H3/f.a -> H3/g
                                    if (g != null) {
                                        mode = ((Integer) XposedHelpers.getIntField(g, "c")).intValue();
                                    }
                                    String f = ModeConfig.getFunc(ModeConfig.KEY_SHUTTER, mode);
                                    boolean orig = ((Boolean) param.getResult()).booleanValue();
                                    if (DualStyleCaptureHooks.ownsShutterGesture(mode)) {
                                        // OS4 街拍还会经过 H3 ShotType 责任链；必须与前面
                                        // 两个单拍决策保持一致，避免最后一层又改回连拍。
                                        param.setResult(Boolean.FALSE);
                                        debug("oem-burst: C3 owns gesture, H3 single (mode="
                                                + mode + ")");
                                    } else if (mode == 225 && ModeConfig.F_BURST.equals(f) && !orig && !isRecordShutterActive()) {
                                        debug("oem-burst: H3/a.d() mode=225 forced true (burst)");
                                        param.setResult(Boolean.TRUE);
                                    } else {
                                        debug("oem-burst: H3/a.d() mode=" + mode + " cfg=" + f + " orig=" + orig);
                                    }
                                } catch (Throwable t) { /* never break */ }
                            }
                        });
                log("oem-burst: H3.a.d hooked");
            } else {
                log("oem-burst: H3.a.d is abstract on this build, skip (harmless)");
            }
        } catch (Throwable t) {
            log("oem-burst: H3.a.d hook failed: " + t);
        }
        // H3/f 与 Camera2Module 长按链路曾仅用于逐次日志诊断；发布版不再挂载，
        // 避免每次拍摄额外进入 Xposed 回调。真实快门决策仍由上述 isNeedBurst、lambda
        // 与 H3.a.d 负责，功能行为不变。
        log("oem-map installed");
    }

    /**
     * 安装按 Profile 分流的兼容补丁。
     *
     * <p>这里故意用一次 Profile 门控，而不是在每个 Hook 的回调里判断版本：Phoenix
     * 不复用官方相机的旧偏好直读（该链曾造成设置页返回值类型崩溃），但可以复用已经
     * 通过 dex 反查确认的 OS4 EV 面板协议；Phoenix 只开放普通夜景 moduleId=173 的窄路径，
     * 因而不会把官方补丁无条件套到作者自己的其它 UI。</p>
     *
     * <p>根因：Phoenix-0.3.0 的 {@code com.android.camera.data.data.r} 虽然保留了旧
     * 混淆类名，但 {@code c()} 实际返回 {@code ArrayList<Integer>}、{@code e()} 返回
     * {@code void}。旧版直读 Hook 把结果当作字符串写回，进入
     * {@code CameraPreferenceActivity.onCreateOptionsMenu()} 时会被 LSPosed 的返回值
     * 类型校验拦截并抛出 {@code ClassCastException}。因此 Phoenix 必须在安装阶段跳过
     * {@link #hookOemDirectRead(ClassLoader)}，而不是捕获异常后继续运行。</p>
     *
     * <p>官方相机仍保留完整的旧直读、EV 面板和已反查的原生徕卡入口；6.4.x 与
     * 6.6.510 使用不同的顶栏/组件链，分别由下方的版本门控安装。Prometheus/NRV
     * 分支本来就不调用 {@link OemHooks}，所以这条门控不会改变它们的功能。</p>
     */
    private static void installProfileSpecificExtras(final ClassLoader cl,
            final boolean officialCompatibilityProfile, final long cameraVersionCode) {
        if (!officialCompatibilityProfile) {
            if (CameraBuilds.isPhoenixOs4Family(cameraVersionCode)) {
                // Phoenix 的 q6.g1/q6.U0/Q6.i0 与官方 6.6.510 共用 OS4 基线协议；只启用
                // 夜景 EV 展开补偿，不启用官方 data.r/s.c/e 直读链（两者返回类型不同）。
                hookOemEvDisplay(cl, true);
                log("oem-map: Phoenix OS4 EV panel workaround enabled (ordinary night mode 173 only)");
            } else {
                log("oem-map: non-official profile skips legacy r/s.c/e direct-read and EV extras");
            }
            return;
        }
        hookOemDirectRead(cl);
        hookOemEvDisplay(cl, false);
        installOfficialLongLeicaHook(cl, cameraVersionCode);
        OfficialParamPanelHooks.installIfSupported(cl, cameraVersionCode);
    }

    /**
     * 按官方版本安装已反查的原生徕卡入口。6.4.x 调用
     * {@code com.android.camera2.compat.theme.custom.mm.top.MainTopBar.onCvClick(View)}；
     * 6.6.000510.0 则调用真实 dex 的 {@code V9.i0.s9(View)}。两条链都只捕获实例
     * 和调用原生方法，不替换任何返回值。
     */
    private static void installOfficialLongLeicaHook(final ClassLoader cl, long cameraVersionCode) {
        if (CameraBuilds.isOfficialOs3LongLeicaSupported(cameraVersionCode)) {
            installOfficialOs3LongLeicaHook(cl, cameraVersionCode);
        } else if (CameraBuilds.isOfficialOs4LongLeicaSupported(cameraVersionCode)) {
            installOfficialOs4LongLeicaHook(cl);
        } else {
            log("oem-leica-long: skip versionCode=" + cameraVersionCode
                    + " (no dex-verified official entry)");
        }
    }

    /**
     * 捕获官方 6.6.000510.0 的真实顶栏实例。该版本 JADX 显示为 V9.i0，真实 dex
     * 入口是 {@code s9(View)}；它负责计算下一档、写入 {@code r2.m}、刷新预览并发出原生提示。
     *
     * <p>JADX 在大小写文件系统上会把真实的 {@code V9.a} 显示成
     * {@code V9.AbstractC2024a}。这里不能按 JADX 文件名指定构造器参数，否则找不到
     * 参数类型时会中止整个安装流程，连后面的 {@code s9(View)} 也不会注册。使用
     * 全构造器监听只捕获实例，不依赖这个不稳定的反编译别名，也不替换任何返回值。</p>
     */
    private static void installOfficialOs4LongLeicaHook(final ClassLoader cl) {
        if (OFFICIAL_OS4_CV_HOOK_READY) {
            return;
        }
        synchronized (OemHooks.class) {
            if (OFFICIAL_OS4_CV_HOOK_READY) {
                return;
            }
            try {
                Class<?> topBar = XposedHelpers.findClass("V9.i0", cl);
                // 真实 dex 的构造器参数是 V9.a；JADX 文件名 AbstractC2024a 只是反编译别名。
                // 不依赖该别名，避免构造器查找失败导致 s9(View) 入口也被跳过。
                XposedBridge.hookAllConstructors(topBar, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        OFFICIAL_OS4_CV_TOP_BAR = param.thisObject;
                    }
                });
                XposedHelpers.findAndHookMethod(topBar, "s9", View.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // 手动点击或模块调用都会刷新缓存，避免复用已销毁的旧 Fragment。
                        OFFICIAL_OS4_CV_TOP_BAR = param.thisObject;
                    }
                });
                OFFICIAL_OS4_CV_HOOK_READY = true;
                log("oem-leica-long: V9.i0.<init>/s9(View) captured");
            } catch (Throwable t) {
                log("oem-leica-long: native 6.6.510 entry unavailable", t);
            }
        }
    }

    /**
     * 官方 6.4.000370/530 的顶栏实现相同，但其徕卡组件的真实 dex 描述符不同。
     * 只捕获 {@code MainTopBar} 并重放它自己的 {@code onCvClick(null)}，不把
     * 6.6.510 的 V9.i0/g2.a/r2.m 链路带进 OS3，避免状态不同步或误调用。
     */
    private static void installOfficialOs3LongLeicaHook(final ClassLoader cl,
            long cameraVersionCode) {
        if (OFFICIAL_OS3_CV_HOOK_READY) {
            return;
        }
        synchronized (OemHooks.class) {
            if (OFFICIAL_OS3_CV_HOOK_READY) {
                return;
            }
            try {
                Class<?> topBar = XposedHelpers.findClass(
                        "com.android.camera2.compat.theme.custom.mm.top.MainTopBar", cl);
                XposedBridge.hookAllConstructors(topBar, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        OFFICIAL_OS3_CV_TOP_BAR = param.thisObject;
                    }
                });
                XposedHelpers.findAndHookMethod(topBar, "onCvClick", View.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                // 原生顶栏点击和模块调用都刷新到最近实例，避免旋转/切模式后
                                // 继续使用已 detach 的旧顶栏对象。
                                OFFICIAL_OS3_CV_TOP_BAR = param.thisObject;
                            }
                        });
                OFFICIAL_OS3_CV_HOOK_READY = true;
                log("oem-leica-long: official 6.4 MainTopBar.<init>/onCvClick captured"
                        + " versionCode=" + cameraVersionCode);
            } catch (Throwable t) {
                log("oem-leica-long: native 6.4 entry unavailable versionCode="
                        + cameraVersionCode, t);
            }
        }
    }

    /**
     * 官方长按 A：调用对应版本的原生顶栏入口，而不是仿写偏好或复制 Toast/UI。
     *
     * <p>6.4.x 的组件仓库/数据类由 {@link #toggleOfficialOs3CvType(ClassLoader, int, long)}
     * 按版本选择；6.6.510 才使用真实 dex 名 {@code g2.a} / {@code r2.m}。JADX
     * 显示的 {@code p213g2.a} / {@code p595r2.C4164m} 只是反编译别名，不能传给
     * {@code findClass}，否则会在按键触发时退化为原生相册行为。</p>
     * 没有真实顶栏实例时返回 false，让相机保留原生按键行为。
     */
    public static boolean toggleOfficialCvType(ClassLoader cl, int mode) {
        long versionCode = HandleFixModule.CAMERA_VERSION_CODE;
        if (!ModeConfig.isLeicaStyleMode(mode)
                || !CameraBuilds.isOfficialLongLeicaSupported(versionCode)) {
            return false;
        }
        if (CameraBuilds.isOfficialOs3LongLeicaSupported(versionCode)) {
            return toggleOfficialOs3CvType(cl, mode, versionCode);
        }
        if (!OFFICIAL_OS4_CV_HOOK_READY) {
            return false;
        }
        Object topBar = OFFICIAL_OS4_CV_TOP_BAR;
        if (topBar == null) {
            log("oem-leica-long: top bar instance is not ready");
            return false;
        }
        try {
            // 真实 dex 描述符是 g2.a；p213g2.a 是 JADX 为避免命名冲突生成的别名。
            Class<?> repoClass = XposedHelpers.findClass("g2.a", cl);
            Object repo = XposedHelpers.callStaticMethod(repoClass, "a");
            // 真实 dex 描述符是 r2.m；p595r2.C4164m 同样只是 JADX 反编译别名。
            Class<?> cvClass = XposedHelpers.findClass("r2.m", cl);
            Object cvData = XposedHelpers.callMethod(repo, "x", cvClass);
            if (cvData == null) {
                return false;
            }
            String before = (String) XposedHelpers.callMethod(cvData, "getComponentValue", mode);
            String next = (String) XposedHelpers.callMethod(cvData, "m", mode);
            if (before == null || next == null || next.equals(before)) {
                return false;
            }
            XposedHelpers.callMethod(topBar, "s9", new Class<?>[]{View.class}, (Object) null);
            String after = (String) XposedHelpers.callMethod(cvData, "getComponentValue", mode);
            boolean changed = after != null && !before.equals(after);
            if (changed) {
                log("oem-leica-long: CV_TYPE mode=" + mode + " " + before + " -> " + after);
            }
            return changed;
        } catch (Throwable t) {
            log("oem-leica-long: native toggle failed", t);
            return false;
        }
    }

    /**
     * 6.4.x 原生 CV 组件只在对应能力存在且确实有下一档时才消费长按事件。
     * 组件类/仓库类按真实 dex 描述符分别选择：370 为 {@code b0.x}/{@code Z.a}，
     * 530 为 {@code d0.z}/{@code b0.a}。调用前后读回值用于确认切换成功，失败时
     * 放行原生录像键行为，不把按键误吞成空操作。
     */
    private static boolean toggleOfficialOs3CvType(ClassLoader cl, int mode,
            long versionCode) {
        Object topBar = OFFICIAL_OS3_CV_TOP_BAR;
        if (!OFFICIAL_OS3_CV_HOOK_READY || topBar == null) {
            log("oem-leica-long: official 6.4 top bar instance is not ready");
            return false;
        }
        try {
            String repoName;
            String componentName;
            if (versionCode == CameraBuilds.OFFICIAL_OS3_6_4_370_VERSION_CODE) {
                repoName = "Z.a";
                componentName = "b0.x";
            } else if (versionCode == CameraBuilds.OFFICIAL_OS3_6_4_530_VERSION_CODE) {
                repoName = "b0.a";
                componentName = "d0.z";
            } else {
                return false;
            }

            Class<?> repoClass = XposedHelpers.findClass(repoName, cl);
            Object repo = XposedHelpers.callStaticMethod(repoClass, "a");
            Class<?> cvClass = XposedHelpers.findClass(componentName, cl);
            Object cvData = XposedHelpers.callMethod(repo, "v", cvClass);
            if (cvData == null) {
                return false;
            }
            boolean disabled = ((Boolean) XposedHelpers.callMethod(cvData, "k", mode))
                    .booleanValue();
            String before = (String) XposedHelpers.callMethod(cvData,
                    "getComponentValue", mode);
            String next = (String) XposedHelpers.callMethod(cvData, "h", mode);
            if (disabled || before == null || next == null || next.equals(before)) {
                return false;
            }

            XposedHelpers.callMethod(topBar, "onCvClick",
                    new Class<?>[]{View.class}, (Object) null);
            String after = (String) XposedHelpers.callMethod(cvData,
                    "getComponentValue", mode);
            boolean changed = after != null && !before.equals(after);
            if (changed) {
                log("oem-leica-long: official 6.4 CV_TYPE mode=" + mode + " "
                        + before + " -> " + after + " versionCode=" + versionCode);
            }
            return changed;
        } catch (Throwable t) {
            log("oem-leica-long: official 6.4 native toggle failed versionCode="
                    + versionCode, t);
            return false;
        }
    }

    /** U9/W9.a.l 覆盖逻辑：pref_camera_handle_* 按当前模式返回模块配置值 */
    public static void oemPrefOverride(de.robv.android.xposed.XC_MethodHook.MethodHookParam param) {
        try {
            String key = (String) param.args[0];
            if (key == null || !key.startsWith("pref_camera_handle_")) return;
            String cfgKey = oemKeyToConfigKey(key);
            if (cfgKey == null) return;
            String f = ModeConfig.getFunc(cfgKey, OEM_CURRENT_MODE);
            if (f == null || ModeConfig.F_DEFAULT.equals(f)) return;
            // 官方相机上录像键 C（快门释放模式）是权威切换：其生效时快门键 C/1 的
            // snap pref 覆盖让位（否则会把官方切换后的值覆盖回去，导致切换失效）
            if (ModeConfig.KEY_SHUTTER.equals(cfgKey) && isRecordShutterActive()) return;
            String attr = ModeConfig.funcToAttrOem(cfgKey, f);
            if (attr != null) {
                debug("oem-map: " + key + " mode=" + OEM_CURRENT_MODE + " orig=" + param.getResult() + " -> " + attr);
                param.setResult(attr);
            }
        } catch (Throwable t) {
            // never break
        }
    }

    /**
     * 官方相机上录像键是否配置为 C（快门释放模式）。
     * 官方原生模型：录像键 C（attr_shutter）是单张/连拍的权威切换，
     * 其生效时快门键 C/1 的模块覆盖/强制全部让位（否则会覆盖官方切换结果）。
     */
    private static boolean isRecordShutterActive() {
        return ModeConfig.F_SHUTTER.equals(ModeConfig.getFunc(ModeConfig.KEY_RECORD, OEM_CURRENT_MODE));
    }

    /**
     * OS4 6.6 基线的 EV 数值反馈与面板展开补偿。
     *
     * 结构（反编译 + dex 方法级反查确认，6.6.000460.0 / 6.6.000510.0 混淆名不同，均双候选）：
     *  - 左侧顶部小 EV 数值 = EvTipView（顶栏 EV_TIP item）。准确值在
     *    ManuallyValueChangeImpl.Ud(String) 的入参里（=实际 EV，如 "0.7"）；
     *    相机自己用 index*0.3 传入导致不准，故这里用 Ud 的字符串直接更新。
     *  - 右侧快门 EV 条 = FragmentManuallyExtra 面板（fragmentId=254），由
     *    FeatureUI（真实类名 Q6.i0，jadx 大小写冲突显示为 InterfaceC1894i0/0533i0）
     *    状态位 (7,254) 控制显隐：g(7,254,1)=展开，d(7,254)=是否已展开。
     *
     * 普通模式（163 等）：波轮 -> ManuallyValueChangeImpl.Ud，直接置位展开面板。
     * 专业/手动模式（164/167/168/180）：波轮 -> KeyEventImpl.G1（510=q6.U0，460=q6.R0）。
     *   注意 G1 在面板未展开且 str==null 时走 P0 分支只改数值、不调用 n1
     *   （因此 hook FragmentManuallyOs4.n1 无效），必须在 G1 执行前先 g(7,254,1)
     *   展开，让原生 G1 走 n1 分支刷新滑块与数值。
     *
     * <p>Phoenix-0.3.0 的普通夜景实机 moduleId 是 173，q6.U0.G1 会在面板未展开时
     * 走同一个 P0 分支，因此需要在 G1 执行前预先展开标准 EV 面板。moduleId=169
     * 是街拍特别款/快速动作的另一套 UI（其原生状态为 (7,16777202)），不能误用
     * 普通夜景的 (7,254)；官方调用方保持 6.6 原有的全部模式行为。</p>
     */
    private static void hookOemEvDisplay(final ClassLoader cl, final boolean phoenixProfile) {
        // EvTipView 实例缓存：构造方法 + setEvValue 必然调用，供 Ud hook 更新数值
        try {
            XposedHelpers.findAndHookConstructor("com.android.camera.ui.EvTipView", cl, android.content.Context.class, android.util.AttributeSet.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            cacheEvTipView(param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod("com.android.camera.ui.EvTipView", cl, "setEvValue", float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            cacheEvTipView(param.thisObject);
                        }
                    });
            log("oem-map: EvTipView hooks installed (instance cache, OS4)");
        } catch (Throwable t) {
            log("oem-map: EvTipView hooks failed: " + t);
        }
        // EV 精确数值入口：510=q6.g1，460=q6.d1。Phoenix-0.3.0 只存在 q6.g1；
        // 这里按 Profile 选择候选，避免在 Phoenix 进程里无意义地尝试官方 460 类名。
        // （jadx 大小写冲突显示为 p563q6/p153q6，真实描述符为 Lq6/g1; / Lq6/d1;。）
        final String[] evValueClasses = phoenixProfile
                ? new String[]{"q6.g1"}
                : new String[]{"q6.g1", "q6.d1"};
        for (final String cls : evValueClasses) {
            try {
                XposedHelpers.findAndHookMethod(cls, cl, "Ud", String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                try {
                                    // Phoenix 只补普通夜景 173。普通/专业模式的原生 EV 组件
                                    // 已经可用；街拍特别款 169 使用作者自己的面板协议，不能重复展开。
                                    if (phoenixProfile && !isPhoenixNightMode()) return;
                                    scheduleEvTipUpdate(Float.parseFloat((String) param.args[0]));
                                    // 普通模式（163 等）不走 G1；官方在此直接展开 EV 条，
                                    // Phoenix 则只对普通夜景 173 进入该分支。
                                    ensureEvPanelShown(cl);
                                } catch (Throwable t) { /* never break */ }
                            }
                        });
                log("oem-map: " + cls + ".Ud hooked (EV value + panel auto-show, OS4)");
            } catch (Throwable t) {
                log("oem-map: " + cls + ".Ud hook failed: " + t);
            }
        }
        // 专业/手动模式波轮分发入口（KeyEventImpl）：510=q6.U0.G1，460=q6.R0.G1。
        // 面板未展开时 G1 直接走 P0 分支（不调用 n1），故在 G1 执行前先展开面板，
        // 随后原生 G1 看到 d(7,254)=true 会走 n1 分支刷新滑块与数值。
        // Phoenix 仅有 q6.U0，且额外包含普通夜景 173；官方保持原有四个专业/手动模式。
        final String[] evKeyHandlerClasses = phoenixProfile
                ? new String[]{"q6.U0"}
                : new String[]{"q6.U0", "q6.R0"};
        for (final String cls : evKeyHandlerClasses) {
            try {
                XposedHelpers.findAndHookMethod(cls, cl,
                        "G1", String.class, XposedHelpers.findClass("com.android.camera.data.data.c", cl), int.class, boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                try {
                                    int mode = ((Integer) param.args[2]).intValue();
                                    if (!isEvPanelMode(mode, phoenixProfile)) return;
                                    Object ui = getFeatureUi(cl);
                                    if (ui == null) {
                                        debug("ev-panel: " + cls + ".G1 FeatureUI null (mode=" + mode + ")");
                                        return;
                                    }
                                    boolean shown = ((Boolean) XposedHelpers.callMethod(ui, "d", 7, 254)).booleanValue();
                                    if (!shown) {
                                        XposedHelpers.callMethod(ui, "g", 7, 254, 1);
                                        debug("ev-panel: " + cls + ".G1 -> pre-show extra (mode=" + mode + ")");
                                    }
                                } catch (Throwable t) { /* never break */ }
                            }
                        });
                log("oem-map: " + cls + ".G1 hooked (EV panel pre-show, OS4)");
            } catch (Throwable t) {
                log("oem-map: " + cls + ".G1 hook failed: " + t);
            }
        }
    }

    /** Phoenix OS4 基线普通夜景的真实 moduleId（由运行时 NightModule 日志确认）。 */
    private static final int PHOENIX_NIGHT_MODE = 173;

    private static boolean isPhoenixNightMode() {
        // 使用 EnvHooks/PhoenixWheelHooks 最近一次真实 moduleIndex；缓存尚未建立时
        // 宁可不展开，也不把普通夜景补丁误带到普通/专业模式或街拍特别款。
        if (CURRENT_MODE > 0) return CURRENT_MODE == PHOENIX_NIGHT_MODE;
        if (OEM_CURRENT_MODE > 0) return OEM_CURRENT_MODE == PHOENIX_NIGHT_MODE;
        return false;
    }

    private static boolean isEvPanelMode(int mode, boolean phoenixProfile) {
        return mode == 164 || mode == 167 || mode == 168 || mode == 180
                || (phoenixProfile && mode == PHOENIX_NIGHT_MODE);
    }

    // 双保险：直接 hook 录像键/快门键 pref 读取点（旧 data.data.s.c()/e()，新 data.data.r.c()/e()）
    public static void hookOemDirectRead(final ClassLoader cl) {
        for (final String cls : new String[]{
                "com.android.camera.data.data.r",  // 6.4.000530.0
                "com.android.camera.data.data.s"}) { // 6.4.000370.0
            try {
                XposedHelpers.findAndHookMethod(cls, cl, "c",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                try {
                                    String f = ModeConfig.getFunc(ModeConfig.KEY_RECORD, OEM_CURRENT_MODE);
                                    if (f == null || ModeConfig.F_DEFAULT.equals(f)) return;
                                    String attr = ModeConfig.funcToAttrOem(ModeConfig.KEY_RECORD, f);
                                    if (attr != null) {
                                        debug("oem-map: " + cls + ".c() button mode=" + OEM_CURRENT_MODE
                                                + " orig=" + param.getResult() + " -> " + attr);
                                        param.setResult(attr);
                                    }
                                } catch (Throwable t) { /* never break */ }
                            }
                        });
                XposedHelpers.findAndHookMethod(cls, cl, "e",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                try {
                                    String f = ModeConfig.getFunc(ModeConfig.KEY_SHUTTER, OEM_CURRENT_MODE);
                                    if (f == null || ModeConfig.F_DEFAULT.equals(f)) return;
                                    // 录像键 C（快门释放模式）权威切换：生效时快门键 snap 覆盖让位，
                                    // 否则会把官方 attr_shutter 切换后的值覆盖回去（导致切换失效）
                                    if (isRecordShutterActive()) return;
                                    String attr = ModeConfig.funcToAttrOem(ModeConfig.KEY_SHUTTER, f);
                                    if (attr != null) {
                                        debug("oem-map: " + cls + ".e() snap mode=" + OEM_CURRENT_MODE
                                                + " orig=" + param.getResult() + " -> " + attr);
                                        param.setResult(attr);
                                    }
                                } catch (Throwable t) { /* never break */ }
                            }
                        });
                log("oem-map: " + cls + ".c/e hooked");
            } catch (Throwable t) {
                log("oem-map: " + cls + ".c/e hook failed: " + t);
            }
        }
    }

    /** FeatureUI 类（Q6.i0）缓存：首次命中后不再重复 findClass（每事件调用，避免反射开销） */
    private static volatile Class<?> FEATURE_UI_CLASS = null;

    /** 获取 OS4 FeatureUI 实现。真实类名 Q6.i0（jadx 大小写冲突显示为
     *  InterfaceC1894i0/InterfaceC0533i0，已用 dex 反查确认 460/510 均为 Q6.i0）。 */
    private static Object getFeatureUi(ClassLoader cl) {
        try {
            if (FEATURE_UI_CLASS == null) {
                FEATURE_UI_CLASS = XposedHelpers.findClass("Q6.i0", cl);
            }
            Object opt = XposedHelpers.callStaticMethod(FEATURE_UI_CLASS, "a");
            if (opt != null && ((Boolean) XposedHelpers.callMethod(opt, "isPresent")).booleanValue()) {
                return XposedHelpers.callMethod(opt, "get");
            }
        } catch (Throwable t) { /* 类未就绪/非 OS4 构建 */ }
        return null;
    }

    /**
     * 将连续 Ud 回调合并为主线程上的一次最新值刷新。若刷新期间来了新值，结束后会
     * 再排一次，既不阻塞相机事件线程，也不会遗漏最后一个 EV 值。
     */
    private static void scheduleEvTipUpdate(float ev) {
        PENDING_EV_VALUE = ev;
        if (!EV_UPDATE_POSTED.compareAndSet(false, true)) return;
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                float displayed = PENDING_EV_VALUE;
                try {
                    Object tip = pickEvTipView();
                    if (tip != null) {
                        XposedHelpers.callMethod(tip, "setEvValue", displayed);
                        debug("ev-bar: ManuallyValueChangeImpl.Ud -> EvTipView ev=" + displayed);
                    }
                } catch (Throwable t) { /* never break */ }
                EV_UPDATE_POSTED.set(false);
                if (PENDING_EV_VALUE != displayed) {
                    scheduleEvTipUpdate(PENDING_EV_VALUE);
                }
            }
        });
    }

    /** 若标准 FragmentManuallyExtra（EV 条面板）未展开，则置位 FeatureUI (7,254) 展开。 */
    private static void ensureEvPanelShown(ClassLoader cl) {
        try {
            Object ui = getFeatureUi(cl);
            if (ui == null) return;
            boolean shown = ((Boolean) XposedHelpers.callMethod(ui, "d", 7, 254)).booleanValue();
            if (!shown) {
                XposedHelpers.callMethod(ui, "g", 7, 254, 1);
                debug("ev-panel: auto show FragmentManuallyExtra");
            }
        } catch (Throwable t) { /* never break */ }
    }

    /** 缓存 EvTipView 候选实例（保留最近 6 个，避免持有已销毁实例） */
    private static void cacheEvTipView(Object v) {
        if (v == null) return;
        EV_TIP_VIEW = v;
        EV_TIP_VIEWS.remove(v);
        EV_TIP_VIEWS.add(v);
        while (EV_TIP_VIEWS.size() > 6) EV_TIP_VIEWS.remove(0);
    }

    /** 选取最可能正在显示的 EvTipView：优先已 attach 到窗口的实例，其次最近一次 setEvValue 的实例 */
    private static Object pickEvTipView() {
        if (!EV_TIP_VIEWS.isEmpty()) {
            for (int i = EV_TIP_VIEWS.size() - 1; i >= 0; i--) {
                Object v = EV_TIP_VIEWS.get(i);
                try {
                    if (((Boolean) XposedHelpers.callMethod(v, "isAttachedToWindow")).booleanValue()) {
                        EV_TIP_VIEW = v;
                        return v;
                    }
                } catch (Throwable t) { /* try next */ }
            }
        }
        return EV_TIP_VIEW;
    }
}
