package com.handlefix;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.Consumer;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Phoenix OS4 版本族 / NRV-1.6.1 的录像键专属原生动作适配。
 *
 * <p>Phoenix AllInOne 的作者 LSP 会将
 * {@code com.prometheus.camera.filters.ClassicStyleStore} 注入相机 ClassLoader。其
 * {@code selectModel(Context, String)} 不只写入 {@code selected_model}，还会由作者的
 * ColorDev hook 清理 MIVI LUT 缓存、重发 LUT 并请求预览重绘。因此这里必须调用该真实 setter，
 * 不能直接写 SharedPreferences，否则可能只改变设置页而不刷新预览或成片。</p>
 *
 * <p>Phoenix 分支还复用 {@code q6.U0.S0} 打开相机原生参数预设和滤镜面板；这里只
 * 传递相机已有的 {@code attr_workspace}/{@code attr_filter}，不复制面板或选择状态。</p>
 *
 * <p>本类仍只在 Phoenix OS4 版本族负责 q6.U0/T9.A 的参数工作台；NRV-1.6.1 的按键分发
 * 由 {@link ModeAwareHooks} 处理。本类共享的只是相机 Activity 生命周期与提示层，风格
 * 目录/切换逻辑统一位于 {@link ClassicStyleController}，避免两个版本各维护一套 setter。
 * 任一前置条件、反射或读回校验失败时均放行原生按键，避免把无效配置变成吞键。</p>
 *
 * <p>参数工作台的打开链路是单向的：{@code q6.U0.S0(..., "attr_workspace")}
 * 经 {@code q6.U0.y2()} 延迟投递 {@code key_common}，再由相机工作台 Fragment 创建/显示
 * 面板；相同的 {@code S0} 调用不会切换状态。当前的二次按键关闭因此不重复发送 S0，
 * 而是保存工作台 Fragment 的弱引用，并调用其原生 {@code onBackEvent(4)}。相机自身会
 * 负责收起动画、卸载 Fragment、清理 {@code attr_workspace} 状态。若 Fragment 尚未显示、
 * 引用失效或原生回调拒绝关闭，则继续走当前打开链路，不吞掉按键，也不直接操作 View
 * 的可见性。</p>
 *
 * <p>这条关闭方案只对已反查出同一工作台生命周期的 Phoenix OS4 版本族开放。旧版
 * Prometheus/V5.1/NRV 已由 {@link LegacyParamPanelHooks} 使用各自的
 * {@code K3.u1/FragmentManualWorkspaceBottom} 链处理；官方相机的分发类和工作区
 * 实现不同，仍不套用。</p>
 */
public final class PhoenixClassicStyleHooks {
    private static final long PHOENIX_030_VERSION_CODE = CameraBuilds.PHOENIX_030_VERSION_CODE;
    private static final int KEYCODE_RECORD = 313;
    private static final int MODULE_PHOTO = 163;
    private static final int MODULE_MANUAL = 167;
    private static final int MODULE_NIGHT = 173;
    private static final int MODULE_PORTRAIT = 171;
    private static final int MODULE_STREET = 225;
    private static final int MODULE_VIDEO = 162;
    private static final int MODULE_ULTRA_HD = 175;
    private static final int MODULE_PRO_VIDEO = 180;

    /*
     * Android 12+ 的普通文本 Toast 由 SystemUI 绘制：会强制附带相机图标、排队显示，
     * 且无法按 Phoenix 相机 UI 旋转/定位。因此改为直接挂入相机 camera_app_root 的短时
     * 非交互 Overlay；每次切换先移除旧 View，不让快速切换滞后显示过期风格。
     */
    private static final int STYLE_OVERLAY_BACKGROUND_ALPHA = 112;
    private static final int STYLE_OVERLAY_TEXT_SIZE_SP = 11;
    private static final int STYLE_OVERLAY_MAX_WIDTH_DP = 200;
    // C5 实测 1.3s 阅读时间略短；延长 0.5s，不改变快速切换时“新提示替换旧提示”的语义。
    private static final int STYLE_OVERLAY_DURATION_MS = 1800;
    /** 正向竖屏系统 Toast 位于取景框上沿；缩小负偏移后只把本模块提示下移。 */
    private static final float STYLE_OVERLAY_PORTRAIT_UPRIGHT_OFFSET_RATIO = 0.22f;
    /** 反向竖屏已由实机确认位置合适，必须与正向竖屏分开，避免“顺手修复”造成回归。 */
    private static final float STYLE_OVERLAY_PORTRAIT_INVERTED_OFFSET_RATIO = 0.31f;
    /**
     * 横屏相机仍使用竖屏 Activity 坐标：把偏移由外侧 44% 收到 30%，视觉上即沿用户
     * 握持方向明显下移到取景框内侧，避开系统徕卡 Toast；270° 方向自动镜像。
     */
    private static final float STYLE_OVERLAY_LANDSCAPE_OFFSET_RATIO = 0.30f;
    /** Phoenix 固定竖屏坐标下，绿色参考框中心位于全屏中心上方约 5.3%。 */
    private static final float STYLE_OVERLAY_LANDSCAPE_VERTICAL_RATIO = -0.053f;

    /** 相机生命周期缓存，不强持有 Activity，避免相机重建时泄漏旧 Window。 */
    private static volatile WeakReference<Activity> sCameraActivity = new WeakReference<>(null);
    /**
     * Phoenix 参数工作台 Fragment 的弱引用。工作台由相机异步创建/销毁，不能用模块自己的
     * boolean 记录“已打开”：用户也可能通过界面返回、切换模式或系统生命周期关闭它。
     * 弱引用只用于在下一次录像键短按时请求原生关闭，不持有 Fragment 或其 Activity。
     */
    private static volatile WeakReference<Object> sWorkspaceFragment = new WeakReference<>(null);
    /** 当前风格提示 Overlay；每次新按键都会立即替换它。 */
    private static volatile FrameLayout sStyleOverlay;
    /** 反射入口失败时只记录一次，避免在作者 LSP 未启用时刷屏。 */
    private static volatile boolean sStyleFailureLogged;
    /** 根布局短暂重建时只记录一次提示层异常，避免快速切换污染 LSPosed 日志。 */
    private static volatile boolean sOverlayFailureLogged;
    /** 仅在手机方向变化时记录一次定位信息，便于实机校准而不刷日志。 */
    private static volatile int sLastOverlayRotation = -1;
    /** 229 会切换到 DecorView；宿主变化时也需留一条诊断证据。 */
    private static volatile boolean sLastOverlayTopLevel;
    private static volatile boolean sOverlayHostLogged;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable REMOVE_STYLE_OVERLAY = new Runnable() {
        @Override
        public void run() {
            removeStyleOverlay(false);
        }
    };

    private PhoenixClassicStyleHooks() {
    }

    /**
     * Phoenix 同一 OS4 版本族复用 q6.U0/T9.A；每个 Hook 自身仍捕获失败即放行，
     * 新包如果改变真实描述符不会因为版本号匹配而强行替换返回值。
     */
    public static void installIfSupported(final ClassLoader classLoader, long cameraVersionCode) {
        if (!CameraBuilds.isPhoenixOs4Family(cameraVersionCode)) {
            return;
        }
        HandleFixModule.install("phoenix-classic-style", new Runnable() {
            @Override
            public void run() {
                hookRecordButton(classLoader);
                hookFrontFilterWheel(classLoader);
                hookCameraLifecycle(classLoader);
                ClassicStyleController.installCatalogProbe(classLoader, cameraVersionCode);
            }
        });
    }

    /**
     * NRV 与 Phoenix 共用提示层生命周期，但没有 Phoenix 的 T9.A 工作台。
     * 单独安装 Activity 生命周期 Hook，避免为了显示 Toast 把 Phoenix 参数面板链误挂到
     * NRV。这里仍只接受 Phoenix 版本族或 NRV-1.6.1；未知/旧 NRV 不会误命中。
     */
    public static void installOverlayLifecycleIfSupported(final ClassLoader classLoader,
            final long cameraVersionCode) {
        if (!ClassicStyleController.isSupportedVersion(cameraVersionCode)
                || cameraVersionCode == PHOENIX_030_VERSION_CODE) {
            return;
        }
        HandleFixModule.install("classic-style-overlay-lifecycle-" + cameraVersionCode,
                new Runnable() {
                    @Override
                    public void run() {
                        hookCameraLifecycleOnly(classLoader);
                        ClassicStyleController.installCatalogProbe(classLoader, cameraVersionCode);
                    }
                });
    }

    /** 监听真实 Camera 继承链的生命周期以获取当前 Window，不使用悬浮窗权限。 */
    private static void hookCameraLifecycle(final ClassLoader classLoader) {
        hookCameraLifecycleOnly(classLoader);
        hookWorkspaceFragment(classLoader);
    }

    /**
     * 只捕获相机 Activity；Phoenix 专属的 T9.A 工作台 Hook 不在这里复用。
     *
     * <p>当前 Phoenix-0.3.0 的生命周期声明类反编译为 {@code com.android.camera.a}，而
     * NRV-1.6.1 的真实声明类是 {@code com.android.camera.ActivityBase}。两者都由
     * {@code com.android.camera.Camera} 继承，但混淆基类名并不稳定。这里从具体 Camera
     * 类向上寻找实际声明的无参生命周期方法，再用 Method 精确挂载；不能继续把 Phoenix
     * 的基类描述符硬套给 NRV，否则风格虽然切换成功，提示层会因拿不到 Activity 而消失。</p>
     */
    private static void hookCameraLifecycleOnly(final ClassLoader classLoader) {
        hookCameraLifecycleOnly(classLoader, null);
    }

    static void hookCameraLifecycleOnly(ClassLoader classLoader, Consumer<Activity> onCameraResume) {
        try {
            final Class<?> cameraClass = XposedHelpers.findClass(
                    "com.android.camera.Camera", classLoader);
            Method onResume = findCameraLifecycleMethod(cameraClass, "onResume");
            Method onPause = findCameraLifecycleMethod(cameraClass, "onPause");
            Method onDestroy = findCameraLifecycleMethod(cameraClass, "onDestroy");

            XposedBridge.hookMethod(onResume,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // 生命周期声明基类还可能被设置页继承；只接受真正的 Camera 实例。
                            if (cameraClass.isInstance(param.thisObject)
                                    && param.thisObject instanceof Activity) {
                                Activity activity = (Activity) param.thisObject;
                                sCameraActivity = new WeakReference<>(activity);
                                if (onCameraResume != null && !param.hasThrowable()) {
                                    onCameraResume.accept(activity);
                                }
                            }
                        }
                    });
            XposedBridge.hookMethod(onPause,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (cameraClass.isInstance(param.thisObject)) {
                                clearCameraActivity(param.thisObject);
                            }
                        }
                    });
            XposedBridge.hookMethod(onDestroy,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (cameraClass.isInstance(param.thisObject)) {
                                clearCameraActivity(param.thisObject);
                            }
                        }
                    });
            HandleFixModule.log("classic-style overlay lifecycle hooked: resume="
                    + onResume.getDeclaringClass().getName()
                    + " pause=" + onPause.getDeclaringClass().getName()
                    + " destroy=" + onDestroy.getDeclaringClass().getName());
        } catch (Throwable t) {
            // 提示层获取失败只影响视觉反馈，不能影响已确认成功的 LUT 切换。
            HandleFixModule.log("classic-style overlay: camera lifecycle hook failed", t);
        }
    }

    /**
     * 从具体 Camera 向上寻找相机自身声明的生命周期方法。不会退到 android.app.Activity：
     * 若作者以后把方法完全移回系统父类，应视为尚未适配并安全禁用提示，而不是在整个相机
     * 进程全局 Hook 框架 Activity。
     */
    private static Method findCameraLifecycleMethod(Class<?> cameraClass, String methodName)
            throws NoSuchMethodException {
        Class<?> current = cameraClass;
        while (current != null && current != Activity.class
                && Activity.class.isAssignableFrom(current)) {
            try {
                Method method = current.getDeclaredMethod(methodName);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(cameraClass.getName() + "#" + methodName + "[]");
    }

    /**
     * 捕获 Phoenix 真实工作台 Fragment 的生命周期。T9.A 在 onAttach 时绑定
     * {@code attr_workspace}，在 onDetach 时释放；这比扫描 View 层级可靠，也能覆盖相机
     * 自己通过下拉菜单打开的工作台。关闭时不直接调用 FragmentManager 或隐藏 View，统一
     * 交给该 Fragment 的 {@code onBackEvent(4)} 做状态清理。
     */
    private static void hookWorkspaceFragment(final ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("T9.A", classLoader, "onAttach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sWorkspaceFragment = new WeakReference<>(param.thisObject);
                            HandleFixModule.debug("Phoenix workspace: T9.A attached");
                        }
                    });
            XposedHelpers.findAndHookMethod("T9.A", classLoader, "onDetach",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            clearWorkspaceFragment(param.thisObject);
                        }
                    });
            HandleFixModule.log("Phoenix workspace: T9.A lifecycle hooked");
        } catch (Throwable t) {
            // 捕获失败只禁用二次关闭；首次打开仍可走 q6.U0.S0，不能影响其它手柄功能。
            HandleFixModule.log("Phoenix workspace: T9.A lifecycle hook failed", t);
        }
    }

    private static void clearCameraActivity(Object candidate) {
        Activity current = sCameraActivity.get();
        if (candidate instanceof Activity && current == candidate) {
            removeStyleOverlay(true);
            sCameraActivity = new WeakReference<>(null);
            sWorkspaceFragment = new WeakReference<>(null);
        }
    }

    private static void clearWorkspaceFragment(Object candidate) {
        Object current = sWorkspaceFragment.get();
        if (current == candidate) {
            sWorkspaceFragment = new WeakReference<>(null);
            HandleFixModule.debug("Phoenix workspace: T9.A detached");
        }
    }

    /**
     * Phoenix OS4 相机的真实 dex 入口：Lq6/U0;->onKeyUp(ILandroid/view/KeyEvent;)Z。
     * JADX 会把类显示为 p1400q6.C17141U0，不能把该显示名用于 Xposed hook。
     */
    private static void hookRecordButton(final ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod("q6.U0", classLoader, "onKeyUp", int.class, KeyEvent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (tryToggleParamPresetPanel(param)) {
                                return;
                            }
                            int filterMode = resolveCurrentMode(param.thisObject);
                            if (NativeFilterPanelHooks.handleRecordKey(param, classLoader,
                                    HandleFixModule.CAMERA_VERSION_CODE, true, filterMode)) {
                                param.setResult(Boolean.TRUE);
                                return;
                            }
                            if (!shouldHandleShortRecordPress(param)) {
                                return;
                            }
                            int mode = resolveCurrentMode(param.thisObject);
                            if (!handleClassicStylePress(classLoader, mode)) {
                                return; // 作者 LSP 未加载、写入失败或无法确认时保留原生行为。
                            }
                            param.setResult(Boolean.TRUE);
                        } catch (Throwable t) {
                            logFailure("record handler failed", t);
                        }
                    }
        });
    }

    /**
     * Phoenix 6.6.510 的 q6.U0 在前摄拍照/录像收到真实 ACTION_SCROLL 后，会在
     * S0(attr_filter) 主分发前返回。只对这两个已实测故障模式接管；后摄及其他模式
     * 继续使用相机原生拨轮链。
     */
    private static void hookFrontFilterWheel(final ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod("q6.U0", classLoader, "onGenericMotionEvent",
                MotionEvent.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!HandleFixModule.promFix()) {
                                return;
                            }
                            int mode = resolveCurrentMode(param.thisObject);
                            if ((mode != MODULE_PHOTO && mode != MODULE_VIDEO)
                                    || !isCurrentFrontCamera(param.thisObject)) {
                                return;
                            }
                            if (NativeFilterPanelHooks.handleWheelEvent(param, classLoader,
                                    HandleFixModule.CAMERA_VERSION_CODE, true, mode)) {
                                param.setResult(Boolean.TRUE);
                            }
                        } catch (Throwable t) {
                            logFailure("front filter wheel failed", t);
                        }
                    }
                });
    }

    /**
     * 小米相机 module 的公开 getActualCameraId() 在 14 Ultra 上以 1 表示前摄。
     * 当前 module 缺失或返回未知值时一律放行，避免跨模式缓存造成后摄误接管。
     */
    private static boolean isCurrentFrontCamera(Object keyEventImpl) {
        Object module = resolveCurrentModule(keyEventImpl);
        if (module == null) {
            return false;
        }
        Object cameraId = XposedHelpers.callMethod(module, "getActualCameraId");
        return cameraId instanceof Number && ((Number) cameraId).intValue() == 1;
    }

    /**
     * Phoenix 专业拍照的录像键短按动作。模块只负责把原生工作区属性送回真实 module，
     * 面板创建、参数预设选择和后续波轮接管全部交给相机自身，避免复制一套 UI 状态机。
     * 若工作台 Fragment 当前仍可提供返回事件，则先调用其原生 {@code onBackEvent(4)}；
     * 只有没有已附着工作台或原生回调拒绝关闭时，才发送 {@code attr_workspace} 打开。
     * 这样用户从界面手动关闭后，下一次按键仍能重新打开，而不会依赖失真的模块状态。
     */
    private static boolean tryToggleParamPresetPanel(XC_MethodHook.MethodHookParam param) {
        if (!HandleFixModule.promFix() || !RecordLongPressDispatcher.isShortRelease(param)) {
            return false;
        }
        int mode = resolveCurrentMode(param.thisObject);
        if (mode != MODULE_MANUAL || !ModeConfig.F_PARAM_PANEL.equals(
                ModeConfig.getFunc(ModeConfig.KEY_RECORD, mode))) {
            return false;
        }
        if (closeParamPresetPanel()) {
            param.setResult(Boolean.TRUE);
            return true;
        }
        if (!dispatchNativePanel(param.thisObject, "attr_workspace", "param-panel")) {
            return false; // 反射失败时放行，让相机保留原生录像键行为。
        }
        param.setResult(Boolean.TRUE);
        return true;
    }

    /**
     * 请求相机原生工作台关闭。onBackEvent 的返回值是相机自己的可见性判断：true 表示
     * 本次按键已被工作台消费，false 表示 Fragment 虽存在但当前不可关闭，此时必须回退
     * 到打开入口。这里不提前清除弱引用，等 onDetach 再清理，避免打断相机的收起动画。
     */
    private static boolean closeParamPresetPanel() {
        Object fragment = sWorkspaceFragment.get();
        if (fragment == null) {
            return false;
        }
        try {
            Object result = XposedHelpers.callMethod(fragment, "onBackEvent", 4);
            boolean consumed = result instanceof Boolean && ((Boolean) result).booleanValue();
            if (consumed) {
                HandleFixModule.log("Phoenix param-panel: T9.A.onBackEvent(4) closed workspace");
            }
            return consumed;
        } catch (Throwable t) {
            // Fragment 可能正在随 Activity 重建；让当前按键继续尝试原生打开，不吞事件。
            HandleFixModule.debug("Phoenix param-panel: native close unavailable");
            return false;
        }
    }

    /**
     * 直接调用 Phoenix q6.U0 自身的真实 S0(MotionEvent, boolean, String) 入口。
     * 旧测试版误从 h0() 取出 module 后调用 i0；h0() 返回的是相机 module，而 S0 属于
     * q6.U0 按键实现本身，导致事件没有进入 Phoenix 的波轮工作区链。第一个参数只用于
     * 满足原生签名；使用短生命周期的 ACTION_DOWN 事件，不注入系统，调用完成立即回收。
     */
    private static boolean dispatchNativePanel(Object keyEventImpl, String attribute,
            String logName) {
        MotionEvent probe = null;
        try {
            long now = SystemClock.uptimeMillis();
            probe = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 0f, 0f, 0);
            XposedHelpers.callMethod(keyEventImpl, "S0", probe, Boolean.FALSE, attribute);
            HandleFixModule.log("Phoenix " + logName + ": q6.U0.S0 " + attribute
                    + " dispatched");
            return true;
        } catch (Throwable t) {
            HandleFixModule.log("Phoenix " + logName + ": q6.U0.S0 failed", t);
            return false;
        } finally {
            if (probe != null) {
                probe.recycle();
            }
        }
    }

    /**
     * 判断本次 313 是否是用户配置的 Phoenix 专属短按动作。
     *
     * <p>这里故意使用显式静态模式白名单，而不是泛化为“所有非录像模式”：作者的
     * ClassicStyleStore 虽然是全局 LUT 状态，但录像/更多模式没有经过实机确认。白名单
     * 必须与 KeyConfigActivity 的 MODE_FILTER 和 isPhoenixClassicStyleAvailable 同步，
     * 否则可能出现 UI 能保存但运行时吞键，或运行时能切换但界面不可选。</p>
     */
    private static boolean shouldHandleShortRecordPress(XC_MethodHook.MethodHookParam param) {
        // 切换到官方相机方案后，旧相机进程仍可能保留已安装的 hook；热路径必须再门控。
        if (!HandleFixModule.promFix()) {
            return false;
        }
        if (param.args == null || param.args.length < 2 || !(param.args[0] instanceof Integer)
                || !(param.args[1] instanceof KeyEvent)) {
            return false;
        }
        if (((Integer) param.args[0]).intValue() != KEYCODE_RECORD) {
            return false;
        }
        if (!RecordLongPressDispatcher.isShortRelease(param)) {
            return false;
        }
        int mode = resolveCurrentMode(param.thisObject);
        // q6.U0 是 Phoenix 的真实按键入口。长按/深长按由 PhoenixLeicaHooks 统一交给
        // RecordLongPressDispatcher；本类只判断短按多 LUT/工作区动作，避免同一入口重复
        // 计算阈值或把多 LUT 风格误当成二态切换。
        return isPhoenixClassicStyleMode(mode)
                && ModeConfig.F_PHOENIX_CLASSIC_STYLE.equals(
                        ModeConfig.getFunc(ModeConfig.KEY_RECORD, mode));
    }

    /**
     * Phoenix 作者 LSP 已确认可用的静态预览模式；不包含录像及未确认的更多模式。
     * 229 只在 StreetSpecialHooks 已通过精确版本门并启用 225 配置别名后放行，避免
     * 其他相机碰巧复用同一 moduleId 时误吞录像键。
     */
    private static boolean isPhoenixClassicStyleMode(int mode) {
        return mode == MODULE_PHOTO || mode == MODULE_MANUAL || mode == MODULE_NIGHT
                || mode == MODULE_PORTRAIT || mode == MODULE_STREET
                || ModeConfig.isStreetSpecialConfigAliasMode(mode);
    }

    /**
     * 供 Phoenix 各按键 Hook 复用当前模式解析链。q6.U0 本身没有 getModuleIndex，
     * 真实链为 q6.U0.h0() -> Optional.get() -> module.getModuleIndex()；失败时回退
     * OemHooks 已维护的缓存。
     */
    static int resolveCurrentMode(Object keyEventImpl) {
        try {
            Object module = resolveCurrentModule(keyEventImpl);
            if (module != null) {
                Object value = XposedHelpers.callMethod(module, "getModuleIndex");
                if (value instanceof Number && ((Number) value).intValue() > 0) {
                    int mode = ((Number) value).intValue();
                    HandleFixModule.OEM_CURRENT_MODE = mode;
                    HandleFixModule.CURRENT_MODE = mode;
                    return mode;
                }
            }
        } catch (Throwable ignored) {
            // Fall through to OemHooks' recent event cache.
        }
        if (HandleFixModule.OEM_CURRENT_MODE > 0) {
            return HandleFixModule.OEM_CURRENT_MODE;
        }
        return HandleFixModule.CURRENT_MODE;
    }

    /** Phoenix 按键实现通过 h0() 暴露当前模块；统一取值避免参数面板和模式解析分叉。 */
    private static Object resolveCurrentModule(Object keyEventImpl) {
        try {
            Object optional = XposedHelpers.callMethod(keyEventImpl, "h0");
            if (optional instanceof Optional && ((Optional<?>) optional).isPresent()) {
                return ((Optional<?>) optional).get();
            }
        } catch (Throwable ignored) {
            // 当前模块尚未就绪时由调用方放行原生事件。
        }
        return null;
    }

    /**
     * Phoenix 与 NRV 共用的“作者多 LUT 风格”唯一按键入口。
     *
     * <p>这不是相机原生的经典/生动二态：{@link LeicaStateAdapter} 只负责先确认当前
     * CV_TYPE。若处于生动模式，模块只提示用户切回经典，绝不写作者的 selectedModel；
     * 若处于经典或无法可靠判定二态，则交给 {@link ClassicStyleController} 调用作者
     * Store 的真实 setter，并且只在 setter 后读回一致时消费按键。这样 Phoenix 的
     * q6.U0 与 NRV 的 K3.u1 不再各复制一套状态判断、切换和提示逻辑。</p>
     *
     * @return true 表示本次按键已经提示或成功切换，应由调用方消费；false 表示能力未
     *         就绪或切换失败，调用方必须放行相机原生行为。
     */
    public static boolean handleClassicStylePress(ClassLoader classLoader, int mode) {
        if (!ModeConfig.isLeicaStyleMode(mode)
                || !ClassicStyleController.isRuntimeSupported(classLoader,
                HandleFixModule.CAMERA_VERSION_CODE)) {
            return false;
        }
        if (LeicaStateAdapter.isVibrant(classLoader, mode)) {
            showStatusFeedback("请切换徕卡经典", mode);
            return true;
        }
        // 按键发生时才查找作者注入类，避免 HandleFix 早于作者 LSP 加载而永久缓存失败。
        String selectedLabel = ClassicStyleController.selectNextStyle(classLoader,
                HandleFixModule.CAMERA_VERSION_CODE);
        if (selectedLabel == null) {
            return false;
        }
        showStyleFeedback(selectedLabel, mode);
        return true;
    }

    /**
     * 按实际运行模式选择 Overlay 挂载层。229 的 EquipStreet 布局会覆盖普通
     * camera_app_root 子层，因此提升到 Activity DecorView 的最后一层；文字样式、
     * 方向变换和“新提示替换旧提示”的行为与其它模式保持一致，不退回系统 Toast。
     */
    static void showStyleFeedback(String label, int mode) {
        showStatusFeedback("徕卡经典风格：" + label, mode);
    }

    static void showStatusFeedback(String message, int mode) {
        showStatusOverlay(message, ModeConfig.isStreetSpecialConfigAliasMode(mode));
    }

    static void hookCamera68OverlayRotation(ClassLoader loader) {
        XposedHelpers.findAndHookMethod("com.android.camera.Camera", loader, "Rs",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Activity activity = sCameraActivity.get();
                        if (param.hasThrowable() || activity != param.thisObject
                                || sStyleOverlay == null) return;
                        if (Looper.myLooper() == Looper.getMainLooper()) {
                            rotateCamera68Overlay(activity);
                        } else {
                            MAIN_HANDLER.post(() -> rotateCamera68Overlay(activity));
                        }
                    }
                });
    }

    private static void rotateCamera68Overlay(Activity activity) {
        FrameLayout overlay = sStyleOverlay;
        if (activity != sCameraActivity.get() || overlay == null
                || !(overlay.getParent() instanceof ViewGroup)) return;
        View text = overlay.getChildAt(0);
        int rotation = (360 - getCameraUiOrientation(activity)) % 360;
        if (text.getRotation() != rotation) {
            positionStyleOverlay(text, (ViewGroup) overlay.getParent(), rotation);
        }
    }

    /**
     * 在相机根视图显示轻量提示。与系统 Toast 不同，它没有应用图标、不会排队，且位置和文字
     * 都按 Phoenix 相机自己的 UI 方向变换，因此横屏拍摄时无需歪头读取。
     */
    static void showStyleOverlay(String label) {
        showStatusOverlay("徕卡经典风格：" + label);
    }

    /** 显示不带风格前缀的状态提示（例如“请切换徕卡经典”）。 */
    public static void showStatusOverlay(String message) {
        showStatusOverlay(message, false);
    }

    /**
     * @param topLevel true 时挂到 DecorView，供 229 避开 EquipStreet 后加的同级遮挡层。
     */
    private static void showStatusOverlay(String message, boolean topLevel) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                return;
            }
            Activity activity = sCameraActivity.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                logOverlayFailure("camera Activity is unavailable", null);
                return;
            }
            ViewGroup root = topLevel ? findDecorRoot(activity) : findCameraRoot(activity);
            if (root == null || !root.isAttachedToWindow() || root.getWidth() <= 0
                    || root.getHeight() <= 0) {
                logOverlayFailure("camera root is not ready", null);
                return;
            }

            // 新风格立即取代旧提示：快速连按时永远只看见当前一档，不产生 Toast 队列延迟。
            removeStyleOverlay(true);
            int cameraOrientation = getCameraUiOrientation(activity);
            // Phoenix 的 Rotatable 控件使用 -orientation 绘制；提示层采用同一变换，才能与
            // AUTO、LEICA 等相机原生控件保持相同阅读方向。
            int contentRotation = (360 - cameraOrientation) % 360;

            FrameLayout overlay = new FrameLayout(activity);
            overlay.setClipChildren(false);
            overlay.setClipToPadding(false);
            overlay.setClickable(false);
            overlay.setFocusable(false);
            overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

            TextView text = buildStyleOverlayText(activity, message);
            OverlayPosition position = positionStyleOverlay(text, root, contentRotation);
            overlay.addView(text, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));

            root.addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            if (topLevel) {
                // DecorView 可能在模式切换时保留多个相机子层；最后加入并显式置顶，
                // 但 Overlay 自身不接收触摸，不改变相机手势和系统栏行为。
                overlay.bringToFront();
                root.invalidate();
            }
            sStyleOverlay = overlay;
            sOverlayFailureLogged = false;
            MAIN_HANDLER.postDelayed(REMOVE_STYLE_OVERLAY, STYLE_OVERLAY_DURATION_MS);

            if (!sOverlayHostLogged || sLastOverlayRotation != contentRotation
                    || sLastOverlayTopLevel != topLevel) {
                sOverlayHostLogged = true;
                sLastOverlayRotation = contentRotation;
                sLastOverlayTopLevel = topLevel;
                HandleFixModule.debug("Phoenix classic style overlay: cameraOrientation="
                        + cameraOrientation
                        + " contentRotation=" + contentRotation
                        + " host=" + (topLevel ? "decor" : "camera-root")
                        + " offset=(" + position.xOffset + "," + position.yOffset + ")");
            }
        } catch (Throwable t) {
            // 风格已由 readback 确认；提示异常不能让原生录像键在一次成功切换后继续执行。
            logOverlayFailure("show failed", t);
        }
    }

    /** 创建不带 SystemUI 相机图标、不会遮挡取景的半透明文字提示。 */
    private static TextView buildStyleOverlayText(Context context, String label) {
        TextView text = new TextView(context);
        text.setText(label);
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, STYLE_OVERLAY_TEXT_SIZE_SP);
        text.setGravity(Gravity.CENTER);
        text.setIncludeFontPadding(false);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(dp(context, STYLE_OVERLAY_MAX_WIDTH_DP));
        int horizontalPadding = dp(context, 8);
        int verticalPadding = dp(context, 5);
        text.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
        text.setShadowLayer(dp(context, 1), 0f, dp(context, 1), Color.argb(160, 0, 0, 0));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(STYLE_OVERLAY_BACKGROUND_ALPHA, 0, 0, 0));
        background.setCornerRadius(dp(context, 9));
        text.setBackground(background);
        return text;
    }

    private static OverlayPosition positionStyleOverlay(View text, View root, int rotation) {
        OverlayPosition position = getOverlayPosition(root, rotation);
        text.setRotation(rotation);
        text.setTranslationX(position.xOffset);
        text.setTranslationY(position.yOffset);
        return position;
    }

    /**
     * 把“取景框视觉上方”转换成 camera_app_root 坐标。竖屏提示靠近取景框上沿；横屏时
     * Activity 仍保持竖屏坐标，因此视觉上方会落在屏幕左右侧。Phoenix 的
     * {@code preview_surface_layout} 实际覆盖范围包含全屏布局，不能代表截图中肉眼可见的
     * 取景框；直接使用它会让 v12.0.49 的提示下移。因此横屏改用实机绿色参考框换算出的
     * 根布局比例。C5 后续实测发现系统徕卡 Toast 也占用外侧位置，因此只把横屏提示
     * 向取景框内侧移动；两个横屏方向保持镜像。后续竖屏实测只发现 0°（摄像头朝上）
     * 与系统 Toast 重叠，因此只下移 0°，180°（摄像头朝下）保持原位置。
     */
    private static OverlayPosition getOverlayPosition(View root, int contentRotation) {
        if (contentRotation == 90 || contentRotation == 270) {
            int side = contentRotation == 90 ? 1 : -1;
            return new OverlayPosition(
                    side * Math.round(root.getWidth() * STYLE_OVERLAY_LANDSCAPE_OFFSET_RATIO),
                    Math.round(root.getHeight() * STYLE_OVERLAY_LANDSCAPE_VERTICAL_RATIO));
        }
        switch (contentRotation) {
            case 180:
                return new OverlayPosition(0, Math.round(
                        root.getHeight() * STYLE_OVERLAY_PORTRAIT_INVERTED_OFFSET_RATIO));
            case 0:
            default:
                return new OverlayPosition(0, -Math.round(
                        root.getHeight() * STYLE_OVERLAY_PORTRAIT_UPRIGHT_OFFSET_RATIO));
        }
    }

    /**
     * 读取相机已经完成防抖取整的 UI 方向。
     *
     * <p>优先调用相机基类的 {@code getOrientation()}：NRV-1.6.1 的真实实现返回
     * {@code ActivityBase.f10644n}，这是 OrientationEvent 链传给 Rotatable 控件的方向。
     * Phoenix 旧版本若没有该方法，再回退到已反查的 {@code d0:I} 字段。不能把 NRV 的
     * {@code d0} 当作方向——NRV 中它是预览 Surface 对象；两种反射都失败时才使用窗口
     * 旋转。相机 Activity 被锁定为竖屏时，窗口旋转可能始终为 0，因此它只能作为最后
     * 的安全回退。</p>
     */
    private static int getCameraUiOrientation(Activity activity) {
        if (CameraBuilds.isCamera68Port(HandleFixModule.CAMERA_VERSION_CODE)) {
            // Camera.Rs passes this sensor-plus-display angle to the native rotation dispatcher.
            int orientation = XposedHelpers.getIntField(activity, "j0");
            return orientation >= 0 && orientation < 360 && orientation % 90 == 0
                    ? orientation : 0;
        }
        try {
            Object value = XposedHelpers.callMethod(activity, "getOrientation");
            if (value instanceof Integer) {
                int orientation = ((Integer) value).intValue();
                if (orientation >= 0 && orientation < 360 && orientation % 90 == 0) {
                    return orientation;
                }
            }
        } catch (Throwable ignored) {
            // Phoenix 旧版可能没有 getOrientation()，继续尝试其已核实的字段。
        }
        try {
            int orientation = XposedHelpers.getIntField(activity, "d0");
            if (orientation >= 0 && orientation < 360 && orientation % 90 == 0) {
                return orientation;
            }
        } catch (Throwable ignored) {
            // 继续走公开 Display 回退；不能让提示异常影响已完成的风格切换。
        }
        try {
            int rotation = activity.getDisplay() != null ? activity.getDisplay().getRotation() : 0;
            switch (rotation) {
                case 1:
                    return 90;
                case 2:
                    return 180;
                case 3:
                    return 270;
                default:
                    return 0;
            }
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 优先使用 Phoenix 相机已稳定存在的根布局；完整主题/预览重建时才回退 DecorView。 */
    private static ViewGroup findCameraRoot(Activity activity) {
        try {
            int rootId = activity.getResources().getIdentifier("camera_app_root", "id",
                    activity.getPackageName());
            View root = rootId != 0 ? activity.findViewById(rootId) : null;
            if (root instanceof ViewGroup) {
                return (ViewGroup) root;
            }
        } catch (Throwable ignored) {
            // Fall through to the activity decor root.
        }
        return findDecorRoot(activity);
    }

    /** 229 专用顶层宿主；不扫描或操作 EquipStreet 自身 View，只使用 Activity 公共根。 */
    private static ViewGroup findDecorRoot(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            return decor instanceof ViewGroup ? (ViewGroup) decor : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void removeStyleOverlay(boolean cancelPendingRemoval) {
        if (cancelPendingRemoval) {
            MAIN_HANDLER.removeCallbacks(REMOVE_STYLE_OVERLAY);
        }
        View overlay = sStyleOverlay;
        sStyleOverlay = null;
        if (overlay == null) {
            return;
        }
        try {
            ViewParent parent = overlay.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(overlay);
            }
        } catch (Throwable ignored) {
            // 相机重建时父布局可能已销毁；清空静态引用即可。
        }
    }

    private static void logOverlayFailure(String message, Throwable t) {
        if (sOverlayFailureLogged) {
            return;
        }
        sOverlayFailureLogged = true;
        if (t == null) {
            HandleFixModule.log("Phoenix classic style overlay: " + message);
        } else {
            HandleFixModule.log("Phoenix classic style overlay: " + message, t);
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class OverlayPosition {
        final int xOffset;
        final int yOffset;

        OverlayPosition(int xOffset, int yOffset) {
            this.xOffset = xOffset;
            this.yOffset = yOffset;
        }
    }

    private static void logFailure(String message, Throwable t) {
        if (sStyleFailureLogged) {
            return;
        }
        sStyleFailureLogged = true;
        if (t == null) {
            HandleFixModule.log("Phoenix classic style: " + message);
        } else {
            HandleFixModule.log("Phoenix classic style: " + message, t);
        }
    }
}
