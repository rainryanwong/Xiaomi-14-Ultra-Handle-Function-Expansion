package com.handlefix;

import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.ref.WeakReference;
import java.util.Optional;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 官方相机“专业模式参数预设（新）”适配。
 *
 * <p>6.4.000370/530 没有 Prometheus/V5.1 的 {@code K3.u1.i0(...,
 * "attr_workspace")} 入口，但保留了原生 {@code FragmentManualWorkspaceBottom}
 * （fragmentId=209）；370 通过真实 dex 类 {@code V3.d0.X3(7,209,2)} 打开，530
 * 通过 {@code X3.d0.Y3(7,209,2)} 打开。6.6.000510 则与 Phoenix 使用同一条 OS4
 * 基线链：按键实现为 {@code q6.U0}，工作区为 {@code T9.A}，由
 * {@code q6.U0.S0(MotionEvent,boolean,"attr_workspace")} 负责打开和步进。JADX
 * 为避免 Windows 大小写文件名冲突会生成 InterfaceC* 或 p563q6 等别名，运行时反射
 * 必须使用真实 dex descriptor 的二进制类名。</p>
 *
 * <p>录像键短按只负责打开/关闭原生工作区；OS3 波轮由 Fragment 的
 * {@code onCustomWheelScroll(...)} 接管，OS4 波轮直接调用 q6.U0 的 S0 并把 attr
 * 固定为 {@code attr_workspace}。不能把事件继续放给
 * {@code pref_camera_handle_wheel}，否则会同时切换参数预设和 EV；也不能绕行
 * A3.Q1.a0/C3.F1.g0，因为实机证实该中间入口依赖的协议在 209 工作区没有注册。</p>
 *
 * <p>原生进场动画约 300ms，而一次实体拨轮会在 20~40ms 内产生多条滚动事件。
 * {@code workspaceOpening} 使打开请求幂等：Fragment 尚未 added/visible 时只消费后续
 * 脉冲，不重复提交 209 容器事务。这是“参数预设与 ISO/EV/WB 界面偶发重叠”的根因
 * 修复；超时只用于异常恢复，不代替 Fragment 生命周期判断。</p>
 */
public final class OfficialParamPanelHooks {
    private static final int KEYCODE_RECORD = 313;
    private static final int MODULE_MANUAL = 167;
    private static final int CONTAINER_MAIN = 7;
    private static final int WORKSPACE_FRAGMENT_ID = 209;
    private static final int OPEN = 2;
    private static final long WORKSPACE_OPEN_TIMEOUT_MS = 1500L;

    private static final String LEGACY_WORKSPACE_FRAGMENT_CLASS =
            "com.android.camera2.compat.theme.custom.mm.manually.FragmentManualWorkspaceBottom";
    /** 官方 6.6.510 与 Phoenix 共享的 OS4 参数预设 Fragment。 */
    private static final String OS4_WORKSPACE_FRAGMENT_CLASS = "T9.A";
    private static final String COMPONENT_DATA_CLASS = "com.android.camera.data.data.c";

    private static final Object WORKSPACE_STATE_LOCK = new Object();
    private static volatile WeakReference<Object> workspaceFragment = new WeakReference<>(null);
    private static volatile Class<?> componentDataClass;
    private static volatile boolean workspaceOpening;
    private static volatile long workspaceOpenRequestedAtMs;

    private OfficialParamPanelHooks() {
    }

    /** 仅在已用真实 dex 描述符核实的官方版本注册。 */
    public static void installIfSupported(final ClassLoader classLoader, final long versionCode) {
        if (!CameraBuilds.isOfficialParamPanelSupported(versionCode)) {
            return;
        }
        HandleFixModule.install("official-param-panel-" + versionCode, new Runnable() {
            @Override
            public void run() {
                hookWorkspaceLifecycle(classLoader, versionCode);
            }
        });
    }

    /**
     * 由官方按键分发类的 onKeyUp 调用。只消费专业拍照模式下配置为动作 4 的录像键
     * 短按；长按徕卡 A、相册预览和所有失败路径都交还原生逻辑。
     */
    public static boolean handleRecordKey(XC_MethodHook.MethodHookParam param,
            ClassLoader classLoader, long versionCode) {
        if (!CameraBuilds.isOfficialParamPanelSupported(versionCode)
                || HandleFixModule.promFix()
                || HandleFixModule.OEM_CURRENT_MODE != MODULE_MANUAL
                || !isRecordShortPress(param)
                || !ModeConfig.F_PARAM_PANEL.equals(
                        ModeConfig.getFunc(ModeConfig.KEY_RECORD, MODULE_MANUAL))) {
            return false;
        }
        return closeWorkspace()
                || requestWorkspaceOpen(param.thisObject, classLoader, versionCode);
    }

    /** 由各版本按键分发类的 onGenericMotionEvent 调用。 */
    public static boolean handleWheelEvent(XC_MethodHook.MethodHookParam param,
            ClassLoader classLoader, long versionCode) {
        if (!CameraBuilds.isOfficialParamPanelSupported(versionCode)
                || HandleFixModule.promFix()
                || HandleFixModule.OEM_CURRENT_MODE != MODULE_MANUAL
                || param.args == null
                || param.args.length == 0
                || !(param.args[0] instanceof MotionEvent)) {
            return false;
        }

        MotionEvent event = (MotionEvent) param.args[0];
        if (event.getActionMasked() != MotionEvent.ACTION_SCROLL
                || !ModeConfig.F_WORKSP.equals(
                        ModeConfig.getFunc(ModeConfig.KEY_WHEEL, MODULE_MANUAL))) {
            return false;
        }
        /*
         * OS4 6.6 的 onGenericMotionEvent 原生就是 S0(event, positive, attr)。
         * 在这里直接调用同一入口，既能让第一次滚动打开工作区，也能让工作区显示后
         * 继续步进预设；若反射失败则返回 false，让相机保留原生事件，不吞掉波轮。
         */
        if (versionCode == CameraBuilds.OFFICIAL_OS4_6_6_510_VERSION_CODE) {
            return dispatchOs4WorkspaceWheel(param.thisObject, event);
        }
        if (isWorkspaceActive()) {
            dispatchWorkspaceWheel(event.getAxisValue(MotionEvent.AXIS_SCROLL) > 0.0f);
            return true;
        }
        // 第一次脉冲发起打开；OPENING 期间的后续脉冲只消费，不重复提交 Fragment。
        return requestWorkspaceOpen(param.thisObject, classLoader, versionCode);
    }

    /** 精确限制到各版本已验证的官方按键分发类，避免 Camera 外层回调重复处理。 */
    public static boolean isKeyHandlerClass(String className, long versionCode) {
        if (versionCode == CameraBuilds.OFFICIAL_OS3_6_4_370_VERSION_CODE) {
            return "A3.Q1".equals(className);
        }
        if (versionCode == CameraBuilds.OFFICIAL_OS3_6_4_530_VERSION_CODE) {
            return "C3.F1".equals(className);
        }
        if (versionCode == CameraBuilds.OFFICIAL_OS4_6_6_510_VERSION_CODE) {
            return "q6.U0".equals(className);
        }
        return false;
    }

    private static boolean isRecordShortPress(XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length < 2
                || !(param.args[0] instanceof Integer)
                || ((Integer) param.args[0]).intValue() != KEYCODE_RECORD
                || !(param.args[1] instanceof KeyEvent)) {
            return false;
        }
        return RecordLongPressDispatcher.isShortRelease(param);
    }

    /**
     * 直接调用已显示工作区的原生相邻预设算法。两版实现只读取方向，并自行使用
     * activeItem、getStartIndex() 和 Adapter；其余参数在真实 dex 中均未读取。
     */
    private static boolean dispatchWorkspaceWheel(boolean positive) {
        Object fragment = workspaceFragment.get();
        Class<?> componentClass = componentDataClass;
        if (fragment == null || componentClass == null) {
            return false;
        }
        try {
            XposedHelpers.callMethod(fragment, "onCustomWheelScroll",
                    new Class<?>[]{componentClass, int.class, boolean.class,
                            int.class, int.class},
                    null, Integer.valueOf(0), Boolean.valueOf(positive),
                    Integer.valueOf(MODULE_MANUAL), Integer.valueOf(8));
            return true;
        } catch (Throwable t) {
            HandleFixModule.log("official param-panel: workspace wheel failed", t);
            return false;
        }
    }

    /** OS4 6.6 的原生波轮实现；不要复制 Fragment 的预设步进状态机。 */
    private static boolean dispatchOs4WorkspaceWheel(Object keyEventImpl, MotionEvent event) {
        if (keyEventImpl == null || event == null) {
            return false;
        }
        try {
            boolean positive = event.getAxisValue(MotionEvent.AXIS_SCROLL) > 0.0f;
            XposedHelpers.callMethod(keyEventImpl, "S0", event,
                    Boolean.valueOf(positive), "attr_workspace");
            return true;
        } catch (Throwable t) {
            HandleFixModule.log("official param-panel: q6.U0.S0 wheel failed", t);
            return false;
        }
    }

    private static void hookWorkspaceLifecycle(final ClassLoader classLoader,
            final long versionCode) {
        componentDataClass = XposedHelpers.findClass(COMPONENT_DATA_CLASS, classLoader);
        String fragmentName = versionCode == CameraBuilds.OFFICIAL_OS4_6_6_510_VERSION_CODE
                ? OS4_WORKSPACE_FRAGMENT_CLASS : LEGACY_WORKSPACE_FRAGMENT_CLASS;
        Class<?> fragmentClass = XposedHelpers.findClass(fragmentName, classLoader);
        XposedBridge.hookAllConstructors(fragmentClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                workspaceFragment = new WeakReference<>(param.thisObject);
            }
        });
        // 两个样本均继承 onDetach；若某个厂商变体不暴露该方法，弱引用仍可兜底。
        try {
            XposedHelpers.findAndHookMethod(fragmentClass, "onDetach", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    clearWorkspace(param.thisObject);
                }
            });
        } catch (Throwable ignored) {
            // Constructor capture + WeakReference remains a safe fallback.
        }
    }

    /** 使用各版本真实的原生入口打开 209 工作区。 */
    private static boolean openWorkspace(Object keyEventImpl, ClassLoader classLoader,
            long versionCode) {
        if (versionCode == CameraBuilds.OFFICIAL_OS4_6_6_510_VERSION_CODE) {
            return openOs4Workspace(keyEventImpl);
        }
        try {
            String protocolClassName;
            String openMethodName;
            if (versionCode == CameraBuilds.OFFICIAL_OS3_6_4_370_VERSION_CODE) {
                // classes2.dex descriptor: LV3/d0; (not JADX's InterfaceC1161d0 alias).
                protocolClassName = "V3.d0";
                openMethodName = "X3";
            } else if (versionCode == CameraBuilds.OFFICIAL_OS3_6_4_530_VERSION_CODE) {
                // classes2.dex descriptor: LX3/d0; (not JADX's InterfaceC1139d0 alias).
                protocolClassName = "X3.d0";
                openMethodName = "Y3";
            } else {
                return false;
            }

            Class<?> protocolClass = XposedHelpers.findClass(protocolClassName, classLoader);
            Object optionalObject = XposedHelpers.callStaticMethod(protocolClass, "impl");
            if (!(optionalObject instanceof Optional)) {
                return false;
            }
            Optional<?> optional = (Optional<?>) optionalObject;
            if (!optional.isPresent()) {
                return false;
            }
            // lc/rc 只查询“是否已显示”，不能作为首次打开的能力门；原生入口本身负责
            // 创建 Fragment、隐藏专业参数栏及执行进场动画。
            XposedHelpers.callMethod(optional.get(), openMethodName,
                    CONTAINER_MAIN, WORKSPACE_FRAGMENT_ID, OPEN);
            return true;
        } catch (Throwable t) {
            HandleFixModule.log("official param-panel: native open failed versionCode="
                    + versionCode, t);
            return false;
        }
    }

    /**
     * 官方 6.6.510 与 Phoenix 共用的 OS4 工作区入口。
     * 录像键没有真实 MotionEvent，使用短生命周期探针满足 S0 签名；该调用仍由
     * q6.U0 自己创建/显示 T9.A，并负责后续参数步进和状态清理。
     */
    private static boolean openOs4Workspace(Object keyEventImpl) {
        if (keyEventImpl == null) {
            return false;
        }
        MotionEvent probe = null;
        try {
            long now = SystemClock.uptimeMillis();
            probe = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 0.0f, 0.0f, 0);
            XposedHelpers.callMethod(keyEventImpl, "S0", probe,
                    Boolean.FALSE, "attr_workspace");
            HandleFixModule.log("official param-panel: q6.U0.S0 attr_workspace dispatched");
            return true;
        } catch (Throwable t) {
            HandleFixModule.log("official param-panel: q6.U0.S0 open failed", t);
            return false;
        } finally {
            if (probe != null) {
                probe.recycle();
            }
        }
    }

    /** 幂等请求 209 工作区；返回 true 表示本次事件已请求或已被 OPENING 状态接管。 */
    private static boolean requestWorkspaceOpen(Object keyEventImpl, ClassLoader classLoader,
            long versionCode) {
        long now = SystemClock.uptimeMillis();
        synchronized (WORKSPACE_STATE_LOCK) {
            long elapsed = now - workspaceOpenRequestedAtMs;
            if (workspaceOpening && elapsed >= 0L && elapsed < WORKSPACE_OPEN_TIMEOUT_MS) {
                return true;
            }
            // 超时后允许恢复性重试，防止反射返回成功但原生生命周期意外中断时锁死。
            workspaceOpening = true;
            workspaceOpenRequestedAtMs = now;
        }
        if (openWorkspace(keyEventImpl, classLoader, versionCode)) {
            return true;
        }
        resetWorkspaceOpening();
        return false;
    }

    private static boolean closeWorkspace() {
        Object fragment = workspaceFragment.get();
        if (fragment == null) {
            return false;
        }
        try {
            Object result = XposedHelpers.callMethod(fragment, "onBackEvent", 4);
            boolean closed = Boolean.TRUE.equals(result);
            if (closed) {
                resetWorkspaceOpening();
            }
            return closed;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isWorkspaceActive() {
        Object fragment = workspaceFragment.get();
        if (fragment == null) {
            return false;
        }
        try {
            boolean active = Boolean.TRUE.equals(
                    XposedHelpers.callMethod(fragment, "isAdded"))
                    && !Boolean.TRUE.equals(XposedHelpers.callMethod(fragment, "isHidden"));
            if (active && workspaceOpening) {
                resetWorkspaceOpening();
            }
            return active;
        } catch (Throwable ignored) {
            // 已构造的工作区应优先接管事件，避免反射异常时重新落入 EV 路径。
            resetWorkspaceOpening();
            return true;
        }
    }

    private static void clearWorkspace(Object candidate) {
        if (workspaceFragment.get() == candidate) {
            workspaceFragment = new WeakReference<>(null);
            resetWorkspaceOpening();
        }
    }

    private static void resetWorkspaceOpening() {
        synchronized (WORKSPACE_STATE_LOCK) {
            workspaceOpening = false;
            workspaceOpenRequestedAtMs = 0L;
        }
    }
}
