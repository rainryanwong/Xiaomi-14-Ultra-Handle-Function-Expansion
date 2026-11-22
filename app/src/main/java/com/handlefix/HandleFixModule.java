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

public class HandleFixModule implements IXposedHookLoadPackage {
    public static final String CAM_PKG = "com.android.camera";
    public static final long LONG_PRESS_MS = 800;
    /** 正式版本关闭逐事件日志；Hook 安装结果与异常仍通过 {@link #log(String)} 保留。 */
    public static final boolean VERBOSE_HOOK_LOG = false;
    public static final ThreadLocal<Boolean> IN_HANDLE_KEYUP = new ThreadLocal<>();
    public static final ThreadLocal<Boolean> S0_FORCE_ASD = new ThreadLocal<>();
    public static final ThreadLocal<String> KEY_FUNC_OVERRIDE = new ThreadLocal<>();
    public static final ThreadLocal<Integer> WHEEL_MODE_SPOOF = new ThreadLocal<>();
    public static volatile Object CURRENT_TOP_BAR = null;
    public static volatile boolean TOP_BAR_HOOK_READY = false;
    public static final Object TOP_BAR_LOCK = new Object();
    public static volatile int OEM_CURRENT_MODE = -1;
    public static volatile int CURRENT_MODE = -1;
    /** 当前相机进程的精确 versionCode，供长按能力门避免把 Phoenix 多 LUT 当成原生 A。 */
    public static volatile long CAMERA_VERSION_CODE = CameraBuilds.UNKNOWN_VERSION_CODE;
    public static final List<String> PROBE_LINES = new ArrayList();
    public static int INSTALL_OK = 0;
    public static int INSTALL_FAIL = 0;
    public static String ENV_LOAD_LINE = "";
    public static String ENV_PROBE_TEXT = "";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (CAM_PKG.equals(loadPackageParam.packageName)) {
            com.handlefix.activation.ActivationProbeHooks.install(loadPackageParam.classLoader, CAM_PKG);
            final ClassLoader cl = loadPackageParam.classLoader;
            String ver = "?";
            int vcode = -1;
            try {
                Object app = XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.app.ActivityThread", (ClassLoader) null), "currentApplication", new Object[0]);
                if ((app instanceof Context) && ((android.content.pm.PackageInfo) ((Context) app).getPackageManager().getPackageInfo(loadPackageParam.packageName, 0)) != null) {
                    android.content.pm.PackageInfo pi = ((Context) app).getPackageManager().getPackageInfo(loadPackageParam.packageName, 0);
                    ver = pi.versionName;
                    vcode = pi.versionCode;
                }
            } catch (Throwable unused) {
            }
            final boolean fixOn = promFix();
            // “旧版 Prometheus Cam 手柄适配”开关只对已核实的模块版 V5.1 相机生效。
            // 配置值仍保留以便切回该版本时恢复，但不能因为 promFix 或版本号族相同
            // 就把该版本专用的基础补丁挂载到其它 Prometheus 相机。
            final boolean streetSpecial = ModeConfig.isStreetSpecialForceEnabled();
            final long cameraVersionCode = CameraBuilds.readVersionCode(loadPackageParam.appInfo);
            CAMERA_VERSION_CODE = cameraVersionCode;
            final boolean camera68 = CameraBuilds.isCamera68Port(cameraVersionCode);
            final boolean camera68Mapping = ModeConfig.isCamera68Mapping();
            final boolean v51 = ModeConfig.isV51Enabled()
                    && cameraVersionCode == CameraBuilds.PROMETHEUS_MODULE_V51_VERSION_CODE;
            final boolean phoenixOs4Family = CameraBuilds.isPhoenixOs4Family(cameraVersionCode);
            final String mappingProfile = camera68Mapping ? "camera68"
                    : phoenixOs4Family ? "phoenix-os4" : (fixOn ? "prometheus" : "official");
            ENV_LOAD_LINE = "pkg=" + loadPackageParam.packageName + " ver=" + ver + "(" + vcode + ")"
                    + " appInfoVcode=" + cameraVersionCode + " profile=" + mappingProfile
                    + " promFix=" + fixOn + " v51=" + v51 + " streetSpecial=" + streetSpecial;
            log("== HandleFixModule loaded: " + ENV_LOAD_LINE + " ==");

            // The Aurora camera port supplies N1G recognition and basic input routing.
            if (camera68 || camera68Mapping) {
                if (camera68 && camera68Mapping && CAM_PKG.equals(loadPackageParam.processName)) {
                    EnvHooks.install(cl);
                    GripMotionGuardHooks.install();
                    FocusPolicyHooks.install(cl);
                    Camera68QuickLaunchHooks.installIfSupported(cl, cameraVersionCode);
                    QuickVideoLaunchHooks.install(cl);
                    Camera68InputHooks.install(cl, cameraVersionCode);
                    DualStyleCaptureHooks.install(cl, cameraVersionCode);
                    ShutterHoldBurstController.installIfSupported(
                            loadPackageParam.packageName, cl, cameraVersionCode, mappingProfile, false);
                    logInstallSummary();
                }
                return;
            }

            // ================= 版本适配对照表 =================
            // Phoenix OS4 版本组（当前实测 0.3.0，基线 6.6.510）：无论界面选择都先识别，
            //   使用专用 OEM Profile，避免 Prometheus 独有链与 OS4 6.6.510 链重复挂载；
            //   仅对 Phoenix 普通夜景 173 启用已反查的 OS4 EV 面板展开补偿；
            //   街拍特别款 169 使用作者自己的面板协议，其他模式保留原生 UI。
            // promFix=true（其他 PrometheusCam 移植包）：
            //   V5.1 开关 开 = 老包（V5.1/1.x，未内置修复）→ PromHooks + LeicaHooks 共有补丁
            //   V5.1 开关 关 = 新包（NRV-1.3.2 / NRV-1.5.0 等，已内置/作者 LSP 提供）→ 仅独有功能 + NRV 门槛
            //   NRV-1.3.2 夜景缺原生 EV 条目，模块按列表缺项补入；NRV-1.5.0 已自带，判重后跳过
            // promFix=false（官方相机 6.4.000370.0 / 6.4.000530.0 / OS4 6.6）→ OemHooks
            //   长按录像键 A 已按真实 dex 分流：6.4.x 重放 MainTopBar.onCvClick(null)，
            //   6.6.510 重放 V9.i0.s9(null)；两条入口互不复用，失败时保留原生按键。
            //   OS3 370/530 的专业拍照 J/录像键短按另走 FeatureUI(7,209,2)，只在
            //   真实版本与 fragmentId=209 同时核实后挂载；OS4/其它官方版本仍隐藏 J。
            // 各版本关键混淆类名（作者重打包可能变化，新包适配时核对）：
            //   K3.u1 按键分发 / com.android.camera.data.data.v 功能值 / i0.J 设置管理器 /
            //   o3.b/o3.c 手柄设备 / A2.w 设置页选项 / G7.b S0门 / f4.b0 HandleDetector /
            //   f0.w0 EV组件 / TopEditorHelperKt supportedConfigItem 门槛
            // ==================================================
            EnvHooks.install(cl); // 版本探测 + 环境记录 + 模式缓存（始终）
            // C6a 只在稳定的 Camera Activity 入口识别独立 Focus 事件。
            // 安装 Hook 不代表改变行为：默认策略为 native，只有用户
            // 选择 disable 且 VID/PID + scanCode 全部匹配时才消费半按。
            FocusPolicyHooks.install(cl);
            // 只针对相机进程的真实手柄 MotionEvent；锁屏唤醒键由 system_server 拦截。
            GripMotionGuardHooks.install();
            // C2 自动开录只补 VIDEO_CAMERA 外部启动路径缺失的一次性状态桥和原生 gate
            // 重试；没有 HandleFix 私有标记时该 Hook 不改变任何相机启动。
            QuickVideoLaunchHooks.install(cl);
            /*
             * C2 已通过 QuickVideoLaunchHooks 与 system_server 的唯一入口接回相机
             * 原生录像 gate。C3 不复用录像键或拨轮分发器：它只在下方 Phoenix 专用
             * 分支接入 Camera2Module 的快门生命周期，并由真实完成回调推进第二张。
             * 运行时能力门不满足时不会安装该 Hook，避免影响已验收行为。
             */
            if (phoenixOs4Family) {
                // 相机外变焦拨杆长拨默认只使用 Phoenix 原生 CAMERA_MODE。用户明确
                // 选择强制 225/229 时，再由一次性私有标记进入窄路由桥；描述符不符
                // 则整组 fail closed，不影响普通启动和相机前台拨杆映射。
                PhoenixQuickLaunchHooks.installIfSupported(cl, cameraVersionCode);
                log("Phoenix OS4 family detected (versionCode=" + cameraVersionCode
                        + "): installing OS4-base mapping profile; Phoenix night EV panel workaround enabled");
                OemHooks.installPhoenixOs4(cl, cameraVersionCode);
                // Phoenix 与官方 6.6.510 共用 V9.i0.s9(View)，但色彩组件不同；
                // 原生长按 A 单独走 PhoenixLeicaHooks，不复用官方 C4164m 链。
                PhoenixLeicaHooks.installIfSupported(cl, cameraVersionCode);
                // Phoenix 附带 LSP 注入了一套独立的多 LUT「徕卡经典风格」，和官方的
                // attr_leica_style（经典/生动两态）不是同一功能。仅在用户选择 Prometheus
                // 映射方案时注册专属按键消费 hook；其内部还会二次读取 promFix，避免切换
                // 方案后旧相机进程误触发。
                if (fixOn) {
                    // Phoenix 的 q6.U0.S0 是旧 K3.u1.i0 的真实等价入口；只注册
                    // J(专业模式参数预设)这一条窄适配，不叠加 Prometheus 的整套链。
                    // 录像键短按打开参数预设面板复用 q6.U0.S0；再次按键时由
                    // PhoenixClassicStyleHooks 对已捕获的 T9.A 调用原生 onBackEvent(4)。
                    // 不能把 Phoenix 入口直接套给 NRV/V5.1
                    // 或官方相机，否则可能误消费录像键、关闭错误的面板，甚至退出相机主界面。
                    PhoenixWheelHooks.installIfSupported(cl, cameraVersionCode);
                    PhoenixClassicStyleHooks.installIfSupported(cl, cameraVersionCode);
                } else {
                    log("Phoenix classic style: skip because Prometheus Cam mapping is disabled");
                }
            } else if (fixOn) {
                // 某些 Prometheus 组合直接搭配官方 6.6.510 的 q6/Q6 相机本体。
                // 只按实际相机 versionCode 补滤镜短按入口，不挂整套官方 OemHooks。
                NativeFilterPanelHooks.installPrometheusOs4BridgeIfSupported(
                        cl, cameraVersionCode);
                if (v51) {
                    PromHooks.install(cl);   // fix1-5 + S0（老包共有补丁）
                    LeicaHooks.install(cl);  // 徕卡切换 + 设置页注入（老包共有补丁）
                } else {
                    log("v51 OFF: 共有手柄补丁(fix1-5/S0/leica)跳过，由 PrometheusCam 新版内置/作者LSP提供");
                }
                // MainTopBar 实例捕获预热（无论 v51）：模式感知 A 切换（toggleCvType）复用
                // MainTopBar 实例刷新 UI，必须尽早捕获，否则首次按键只写值不刷新（延迟/不响应）
                LeicaHooks.installTopBarCaptureAsync(cl);
                // 旧版 Prometheus/NRV 的专业参数预设：录像键与波轮 J 复用 K3.u1.i0
                // 的完整 attr_workspace 链（f4.f0 加载工作区，最后才由 h1 发送埋点）。
                // Phoenix 走 q6.U0/T9.A 专属链；官方相机的分发类不同，不能混挂。
                LegacyParamPanelHooks.installIfSupported(cl);
                ModeAwareHooks.install(cl);      // 模式感知（独有，始终）
                ZoomHooks.install(cl);           // 变焦拨杆/快门连拍/焦段（独有，始终）
                // 作者 LSP 多 LUT 风格的候选门由 Phoenix 族/NRV-1.6.1 分开处理：
                // Phoenix 在专用 q6.U0 链中安装，NRV 只安装 K3.u1 提示层生命周期；
                // ClassicStyleController 仍会按真实 Store 方法签名二次核验。
                PhoenixClassicStyleHooks.installOverlayLifecycleIfSupported(cl,
                        cameraVersionCode);
                if (v51) {
                    // 5.0 相机的人像变焦与夜景 EV 仍有原生缺口；只在旧包开关启用时补齐。
                    ModeAwareHooks.installLegacyV51Gate(cl);
                } else {
                    ModeAwareHooks.installNrvGate(cl); // NRV 门槛 + 1.3.2 夜景 EV 缺项修复（1.5.0 判重跳过）
                }
            } else {
                log("promFix OFF: PrometheusCam handle-fix hooks skipped, installing OEM (官方相机) mapping");
                OemHooks.install(cl, cameraVersionCode);
            }

            /*
             * C3 是 Camera2Module 的独立快门事务，必须在各 Profile 的原生 CV 顶栏
             * 捕获 Hook 安装后注册。适配器按各相机已核实的按键与拍摄生命周期精确匹配；
             * 任一关键入口缺失就保持 ready=false，不消费快门。
             */
            DualStyleCaptureHooks.install(cl, cameraVersionCode);

            final boolean phoenixPrometheusConfigRecognized = phoenixOs4Family
                    && "phoenix-os4".equals(mappingProfile);
            ShutterHoldBurstController.installIfSupported(
                    loadPackageParam.packageName,
                    cl,
                    cameraVersionCode,
                    mappingProfile,
                    phoenixPrometheusConfigRecognized);

            // Phoenix 新版可能已经自行开放 EquipStreetModule(229)。即使用户未开启旧版
            // “强制解锁街拍特别款”开关，也必须让 229 继承普通街拍 225 的手柄配置。
            // 此处仅做完整描述符预检并开启配置查询别名，不改 Je.c.M()，也不绕过手柄接入条件。
            if (phoenixPrometheusConfigRecognized) {
                StreetSpecialHooks.enableConfigAliasIfSupported(cl, cameraVersionCode);
            }

            // 街拍特别款只跟随 Prometheus Cam 方案；切换到官方相机方案时 UI 会清除该配置，
            // 这里再次门控以防用户手工保留了旧 JSON。仅相机进程重新创建后才会注册新 hook 集。
            if (streetSpecial && fixOn) {
                StreetSpecialHooks.installIfSupported(cl, cameraVersionCode);
            } else if (streetSpecial) {
                log("StreetSpecial: skip because Prometheus Cam mapping is disabled");
            }
            logInstallSummary();
            log("== HandleFixModule: install finished ==");
        }
    }
    public static String buildEnvRecord() {
        StringBuilder sb = new StringBuilder("load=");
        sb.append(ENV_LOAD_LINE).append("\nhook_ok=");
        sb.append(INSTALL_OK).append("\nhook_fail=");
        sb.append(INSTALL_FAIL).append("\nprobe:\n");
        sb.append(ENV_PROBE_TEXT);
        return sb.toString();
    }
    /**
     * 读取当前相机模式（moduleIndex）并缓存到 CURRENT_MODE。
     * @param obj 按键分发/模块实例（如 K3.u1、o3.c 的 thisObject；传 null 时走兜底读取）
     * @param classLoader 相机进程 ClassLoader
     */
    public static int currentModuleIndex(Object obj, ClassLoader classLoader) {
        int iIntValue;
        if (obj != null) {
            try {
                int iIntValue2 = ((Integer) XposedHelpers.callMethod(obj, "getModuleIndex", new Object[0])).intValue();
                if (iIntValue2 > 0) {
                    CURRENT_MODE = iIntValue2;
                    return iIntValue2;
                }
            } catch (Throwable unused) {
            }
            try {
                Object objCallMethod = XposedHelpers.callMethod(obj, ModeConfig.F_ET, new Object[0]);
                if (((Boolean) XposedHelpers.callMethod(objCallMethod, "isPresent", new Object[0])).booleanValue() && (iIntValue = ((Integer) XposedHelpers.callMethod(XposedHelpers.callMethod(objCallMethod, "get", new Object[0]), "getModuleIndex", new Object[0])).intValue()) > 0) {
                    CURRENT_MODE = iIntValue;
                    return iIntValue;
                }
            } catch (Throwable unused2) {
            }
        }
        if (CURRENT_MODE > 0) {
            return CURRENT_MODE;
        }
        try {
            int iIntValue3 = Integer.valueOf(XposedHelpers.getIntField(XposedHelpers.callMethod(XposedHelpers.callStaticMethod(XposedHelpers.findClass("d0.a", classLoader), "a", new Object[0]), "v", new Object[]{XposedHelpers.findClass("f0.l", classLoader)}), "mCurrentMode")).intValue();
            if (iIntValue3 <= 0) {
                return -1;
            }
            CURRENT_MODE = iIntValue3;
            return iIntValue3;
        } catch (Throwable unused3) {
            return -1;
        }
    }

    /**
     * 注册一个 hook 安装任务（立即执行）。
     * @param str 注册名（如 "shutter-burst"），用于安装日志与失败定位
     * @param runnable 实际执行 Xposed hook 安装的代码
     */
    public static void install(String str, Runnable runnable) {
        try {
            runnable.run();
            INSTALL_OK++;
            log("installed: " + str);
        } catch (Throwable th) {
            INSTALL_FAIL++;
            log("install FAILED: " + str + " -> " + th);
        }
    }

    public static void logInstallSummary() {
        log("== hook summary: ok=" + INSTALL_OK + " fail=" + INSTALL_FAIL + " ==");
    }
    public static boolean promFix() {
        return ModeConfig.isPromFixEnabled();
    }

    /**
     * 运行时模块版 V5.1 Prometheus 修复门。
     *
     * <p>v51 配置可以跨设备/相机版本保留，但只有当前相机确认为模块版 V5.1
     * 内嵌的 V5.0（精确 versionCode）且仍选择 Prometheus 映射时，旧链才可消费。
     * 这样配置读取不会把同一版本号段的其它移植包误当成旧版相机。</p>
     */
    public static boolean isV51RuntimeEnabled() {
        return promFix()
                && ModeConfig.isV51Enabled()
                && CAMERA_VERSION_CODE == CameraBuilds.PROMETHEUS_MODULE_V51_VERSION_CODE;
    }

    /** 写入 XposedBridge 日志（前缀 [HandleFix]），供 LSPosed 日志/模块诊断查看 */
    public static void log(String str) {
        XposedBridge.log("[HandleFix] " + str);
    }

    public static void log(String str, Throwable th) {
        XposedBridge.log("[HandleFix] " + str + " -> " + th);
    }

    /** 仅用于手动排障的逐事件日志；安装结果和错误仍始终记录。 */
    public static void debug(String str) {
        if (VERBOSE_HOOK_LOG) {
            log(str);
        }
    }
}
