package com.handlefix;

import android.view.InputDevice;
import android.view.KeyEvent;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 摄影手柄半按对焦策略。
 *
 * <p>实机输入日志已确认：小米手柄半按是独立的
 * {@link KeyEvent#KEYCODE_FOCUS} (80) / scanCode 528，完整快门则在此后另外发送
 * keyCode 27/170。相机会在 {@code Camera.onKeyDown(80)} 中调用
 * {@code handleLockFocus()}；因此“禁用半按”的最小根因修复是在 Activity 入口
 * 同时消费 Focus DOWN/UP，让相机继续自身的连续自动对焦。</p>
 *
 * <p>“禁用半按”不 Hook 混淆后的按键分发类；“中心单次 AF”也只 Hook 语义稳定、
 * 已逐版本核实过的 BaseModule/r，不触碰 27/170，不模拟触摸坐标。中心策略只替换
 * {@code handleLockFocus()}，并调用各相机
 * FocusManager 已核实的“建立中心 AF 区域 + 启动一次 AF”方法。这样仍由相机自己的
 * FocusTask、UI、完成回调和约 2 秒超时恢复状态机管理，不裸写 Camera2 请求。</p>
 *
 * <p>设备信息不完整、FocusManager 类名/方法签名不匹配或当前处于 MF/EDOF 时，
 * 一律放行原生半按；不能因为实验策略未适配就让半按变成无响应。</p>
 */
public final class FocusPolicyHooks {
    private static final int HANDLE_VENDOR_ID = 0x2717;
    private static final int HANDLE_PRODUCT_PRO = 0x5083;
    private static final int HANDLE_PRODUCT_LITE_1 = 0x50b2;
    private static final int HANDLE_PRODUCT_LITE_2 = 0x50b7;
    private static final int HANDLE_FOCUS_SCAN_CODE = 528;

    private static volatile boolean sLoggedSuppression;
    private static volatile boolean sLoggedCenterRoute;
    private static volatile boolean sLoggedCenterFailure;
    /**
     * 首次中心 AF 适配失败的结构化原因。只在进程生命周期内记录一次，
     * 避免每个半按事件都把同一条反射失败刷进 LSPosed 日志。
     */
    private static volatile String sCenterFailureDetail;
    /** 仅表示本次 Focus DOWN 已成功改走中心 AF；新一轮 DOWN 或对应 UP 后清零。 */
    private static volatile boolean sCenterFocusActive;
    /**
     * Camera.onKeyUp(80) 调用 Module.cancelFocus(true) 的同线程窗口。
     * 只在该窗口跳过取消 AF；Module 其余状态清理和 FocusManager 自己的超时恢复均保留。
     */
    private static final ThreadLocal<Boolean> CENTER_FOCUS_KEY_UP = new ThreadLocal<>();

    private FocusPolicyHooks() {
    }

    public static void install(final ClassLoader classLoader) {
        HandleFixModule.install("focus-policy", new Runnable() {
            @Override
            public void run() {
                if (CameraBuilds.isCamera68Port(HandleFixModule.CAMERA_VERSION_CODE)) {
                    hookCamera68Focus(classLoader);
                    return;
                }
                hookCameraKeyEntry(classLoader);
                hookCenterFocusEntries(classLoader);
            }
        });
    }

    private static final ThreadLocal<KeyEvent> CAMERA68_FOCUS_KEY = new ThreadLocal<>();
    private static final String CAMERA68_PREVIOUS_KEY = "handlefix.68.focus.previous";
    private static final String CAMERA68_CENTER_PRESS = "handlefix.68.focus.press";

    /** 6.8 相机模块已提供原生半按修复。只在用户选择中心/禁用时接管对应事件。 */
    private static void hookCamera68Focus(ClassLoader loader) {
        Class<?> camera = XposedHelpers.findClass("com.android.camera.Camera", loader);
        Class<?> module = XposedHelpers.findClass("com.android.camera.module.u", loader);
        Class<?> manager = XposedHelpers.findClass("k6.k", loader);
        Class<?> focus = XposedHelpers.findClass("v6.q", loader);
        Method keyDown = XposedHelpers.findMethodExact(camera, "onKeyDown", int.class, KeyEvent.class);
        Method keyUp = XposedHelpers.findMethodExact(camera, "onKeyUp", int.class, KeyEvent.class);
        Method lock = XposedHelpers.findMethodExact(module, "handleLockFocus");
        Method getManager = XposedHelpers.findMethodExact(module, "getCameraManager");
        Method notify = XposedHelpers.findMethodExact(module, "notifyFocusAreaUpdate", boolean.class);
        Method alive = XposedHelpers.findMethodExact(module, "isDeviceAndModuleAlive");
        Method canFocus = XposedHelpers.findMethodExact(manager, "a1");
        Method getFocus = XposedHelpers.findMethodExact(manager, "p0");
        Method focusMode = XposedHelpers.findMethodExact(focus, "getFocusMode");
        Method setCenter = XposedHelpers.findMethodExact(focus, "j0");
        Method startFocus = XposedHelpers.findMethodExact(focus, "l0", int.class);

        XC_MethodHook keyScope = new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setObjectExtra(CAMERA68_PREVIOUS_KEY, CAMERA68_FOCUS_KEY.get());
                CAMERA68_FOCUS_KEY.remove();
                KeyEvent event = findKeyEvent(param);
                if (!ModeConfig.isCamera68Mapping() || !isPhotographyHandleFocusEvent(event)) {
                    return;
                }
                String policy = ModeConfig.getHalfFocusPolicy();
                if (ModeConfig.HALF_FOCUS_DISABLE.equals(policy)) {
                    param.setResult(true);
                } else if (ModeConfig.HALF_FOCUS_CENTER.equals(policy)) {
                    CAMERA68_FOCUS_KEY.set(event);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                KeyEvent previous = (KeyEvent) param.getObjectExtra(CAMERA68_PREVIOUS_KEY);
                if (previous == null) CAMERA68_FOCUS_KEY.remove();
                else CAMERA68_FOCUS_KEY.set(previous);
            }
        };
        XposedBridge.hookMethod(keyDown, keyScope);
        XposedBridge.hookMethod(keyUp, keyScope);

        XposedBridge.hookMethod(lock, new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                KeyEvent event = CAMERA68_FOCUS_KEY.get();
                if (event == null || event.getAction() != KeyEvent.ACTION_DOWN
                        || event.getRepeatCount() != 0) return;
                Object owner = param.thisObject;
                XposedHelpers.removeAdditionalInstanceField(owner, CAMERA68_CENTER_PRESS);
                if (!Boolean.TRUE.equals(alive.invoke(owner))) return;
                Object cameraManager = getManager.invoke(owner);
                if (!Boolean.TRUE.equals(canFocus.invoke(cameraManager))) return;
                Object focusManager = getFocus.invoke(cameraManager);
                if (!focus.isInstance(focusManager)) return;
                int afMode = (Integer) focusMode.invoke(focusManager);
                if (afMode == 0 || afMode == 5) return;

                // j0 建立中心区域并更新原生框，l0 负责发起 AF 和管理完成/超时。
                setCenter.invoke(focusManager);
                notify.invoke(owner, false);
                startFocus.invoke(focusManager, 0);
                XposedHelpers.setAdditionalInstanceField(owner, CAMERA68_CENTER_PRESS,
                        new Camera68Press(event));
                param.setResult(null);
            }
        });
        XC_MethodHook release = new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                KeyEvent event = CAMERA68_FOCUS_KEY.get();
                if (event == null || event.getAction() != KeyEvent.ACTION_UP || event.isCanceled()
                        || !Boolean.TRUE.equals(param.args[0])) return;
                Object press = XposedHelpers.getAdditionalInstanceField(
                        param.thisObject, CAMERA68_CENTER_PRESS);
                if (press instanceof Camera68Press && ((Camera68Press) press).matches(event)) {
                    // 只移除本次松键同步的立即 AF_CANCEL。相机仍清除按钮状态，AF 自行完成。
                    XposedHelpers.removeAdditionalInstanceField(param.thisObject, CAMERA68_CENTER_PRESS);
                    param.setResult(null);
                }
            }
        };
        XposedBridge.hookMethod(XposedHelpers.findMethodExact(module, "cancelFocus", boolean.class),
                release);
        for (String owner : new String[]{
                "com.android.camera.module.VideoBase",
                "com.android.camera.features.mode.street.StreetModule",
                "com.xiaomi.camera.module.PhotoBase",
                "com.xiaomi.microfilm.dualcam.mode.DualVideoModuleBase"
        }) {
            Method cancel = XposedHelpers.findMethodExact(
                    XposedHelpers.findClass(owner, loader), "cancelFocus", boolean.class);
            XposedBridge.hookMethod(cancel, release);
        }
    }

    private static final class Camera68Press {
        private final int device;
        private final long downTime;

        Camera68Press(KeyEvent event) {
            device = event.getDeviceId();
            downTime = event.getDownTime();
        }

        boolean matches(KeyEvent event) {
            return device == event.getDeviceId() && downTime == event.getDownTime();
        }
    }

    private static void hookCameraKeyEntry(ClassLoader classLoader) {
        Class<?> cameraClass = XposedHelpers.findClass("com.android.camera.Camera", classLoader);
        Method keyDown = XposedHelpers.findMethodExact(cameraClass, "onKeyDown",
                int.class, KeyEvent.class);
        Method keyUp = XposedHelpers.findMethodExact(cameraClass, "onKeyUp",
                int.class, KeyEvent.class);
        if (keyDown.getReturnType() != boolean.class || keyUp.getReturnType() != boolean.class) {
            throw new IllegalStateException("Camera focus entry must return boolean");
        }

        XC_MethodHook policyHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                KeyEvent event = findKeyEvent(param);
                if (!isPhotographyHandleFocusEvent(event)) {
                    return;
                }
                String policy = ModeConfig.getHalfFocusPolicy();
                if (ModeConfig.HALF_FOCUS_CENTER.equals(policy)) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN
                            && event.getRepeatCount() == 0) {
                        // 防止上一次异常丢失 UP 后，把新一轮按键误判成旧的中心 AF。
                        sCenterFocusActive = false;
                    } else if (event.getAction() == KeyEvent.ACTION_UP
                            && sCenterFocusActive) {
                        // 让 Module.onKeyUp 继续清理“半按中”状态和原生 UI，但在其内部
                        // cancelFocus(true) 处跳过立即取消，保证轻按松手后 AF 能完成。
                        CENTER_FOCUS_KEY_UP.set(Boolean.TRUE);
                    }
                    return;
                }
                if (!ModeConfig.HALF_FOCUS_DISABLE.equals(policy)) {
                    return;
                }

                // Activity.onKeyDown/onKeyUp 均返回 true，表示 Focus 事件已处理。
                // 必须成对消费：只拦 DOWN 会让相机收到无头的 UP，
                // 只拦 UP 则可能把 handleLockFocus() 留在锁定状态。
                param.setResult(true);
                if (!sLoggedSuppression) {
                    sLoggedSuppression = true;
                    HandleFixModule.log("focus-policy: Xiaomi handle half-press disabled; "
                            + "full shutter 27/170 remains native");
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                KeyEvent event = findKeyEvent(param);
                if (event == null || event.getAction() != KeyEvent.ACTION_UP
                        || !Boolean.TRUE.equals(CENTER_FOCUS_KEY_UP.get())) {
                    return;
                }
                CENTER_FOCUS_KEY_UP.remove();
                sCenterFocusActive = false;
            }
        };

        XposedBridge.hookMethod(keyDown, policyHook);
        XposedBridge.hookMethod(keyUp, policyHook);
        HandleFixModule.log("focus-policy: Camera.onKeyDown/onKeyUp installed");
    }

    /** 在当前相机族的真实 Module 基类上挂载，避免探测另一族产生无害的安装噪声。 */
    private static void hookCenterFocusEntries(ClassLoader classLoader) {
        long versionCode = HandleFixModule.CAMERA_VERSION_CODE;
        boolean installed = false;
        if (CameraBuilds.isPhoenixOs4Family(versionCode)
                || versionCode == CameraBuilds.OFFICIAL_OS4_6_6_510_VERSION_CODE) {
            installed = hookCenterFocusClass(classLoader, "com.android.camera.module.r", true);
        } else if (CameraBuilds.isOfficialOs3LongLeicaSupported(versionCode)
                || versionCode == CameraBuilds.NRV_161_VERSION_CODE) {
            installed = hookCenterFocusClass(classLoader,
                    "com.android.camera.module.BaseModule", true);
        } else {
            // 未建档版本只能做能力探测；单条缺失属于正常版本差异，不污染常规日志。
            installed |= hookCenterFocusClass(classLoader,
                    "com.android.camera.module.BaseModule", false);
            installed |= hookCenterFocusClass(classLoader, "com.android.camera.module.r", false);
        }
        if (!installed) {
            throw new IllegalStateException("No supported camera module focus entry");
        }
    }

    private static boolean hookCenterFocusClass(ClassLoader classLoader, String className,
            boolean logFailure) {
        Class<?> moduleClass = XposedHelpers.findClassIfExists(className, classLoader);
        if (moduleClass == null) {
            return false;
        }
        try {
            Method handleLockFocus = XposedHelpers.findMethodExact(moduleClass,
                    "handleLockFocus");
            Method cancelFocus = XposedHelpers.findMethodExact(moduleClass,
                    "cancelFocus", boolean.class);
            if (handleLockFocus.getReturnType() != void.class
                    || cancelFocus.getReturnType() != void.class) {
                throw new IllegalStateException(className + " focus methods must return void");
            }

            XposedBridge.hookMethod(handleLockFocus, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ModeConfig.HALF_FOCUS_CENTER.equals(
                            ModeConfig.getHalfFocusPolicy())) {
                        return;
                    }
                    if (!triggerNativeCenterFocus(param.thisObject)) {
                        return; // 能力未匹配：执行原生 handleLockFocus，不能吞掉半按。
                    }
                    sCenterFocusActive = true;
                    // handleLockFocus 是 void；中心 AF 已由 FocusManager 启动，跳过原生的
                    // AE_LOCK + AF_MODE_AUTO 重复链，避免两个对焦状态机互相覆盖。
                    param.setResult(null);
                }
            });

            XposedBridge.hookMethod(cancelFocus, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (ModeConfig.HALF_FOCUS_CENTER.equals(
                            ModeConfig.getHalfFocusPolicy())
                            && Boolean.TRUE.equals(CENTER_FOCUS_KEY_UP.get())) {
                        // 只跳过 Focus UP 同步调用的立即取消；相机自己的 AF 完成/超时
                        // 回调不在此 ThreadLocal 窗口内，仍会正常恢复连续 AF。
                        param.setResult(null);
                    }
                }
            });
            HandleFixModule.log("focus-policy: center AF entry installed on " + className);
            return true;
        } catch (Throwable t) {
            if (logFailure) {
                HandleFixModule.log("focus-policy: center AF entry unavailable on "
                        + className + " -> " + t);
            }
            return false;
        }
    }

    /**
     * 按已反查 APK 的真实描述符调用 FocusManager，不按版本号猜混淆名：
     *
     * <ul>
     *   <li>OS4 6.6.510 / Phoenix：p704u6.p，j0() + l0(0)</li>
     *   <li>OS3 6.4.530：H3.q，r() + u(0)</li>
     *   <li>OS3 6.4.370：F3.p，r() + u(0)</li>
     *   <li>NRV 1.6.1：P3.q，r() + t(0)</li>
     * </ul>
     *
     * <p>先验证 FocusManager 精确类名、两个 void 方法及当前 AF_MODE，再执行；
     * AUTO/MACRO/CONTINUOUS_* 可用，OFF/MF(0) 与 EDOF(5) 放回原生逻辑。</p>
     */
    private static boolean triggerNativeCenterFocus(Object module) {
        if (module == null) {
            return false;
        }
        try {
            Object cameraManager = XposedHelpers.callMethod(module, "getCameraManager");
            if (cameraManager == null) {
                return false;
            }
            long versionCode = HandleFixModule.CAMERA_VERSION_CODE;
            String route = tryKnownCenterFocusAdapter(cameraManager, versionCode);
            if (route == null) {
                if (!sLoggedCenterFailure) {
                    sLoggedCenterFailure = true;
                    String detail = sCenterFailureDetail;
                    HandleFixModule.log("focus-policy: no verified center AF adapter for "
                            + cameraManager.getClass().getName() + "; keep native half-press"
                            + (detail == null ? "" : "; reason=" + detail));
                }
                return false;
            }
            if (!sLoggedCenterRoute) {
                sLoggedCenterRoute = true;
                HandleFixModule.log("focus-policy: native center AF started via " + route);
            }
            return true;
        } catch (Throwable t) {
            if (!sLoggedCenterFailure) {
                sLoggedCenterFailure = true;
                HandleFixModule.log("focus-policy: native center AF failed; keep native -> " + t);
            }
            return false;
        }
    }

    /**
     * 已知版本直接走各自真实 dex 描述符；未知候选才做无副作用的能力探测。
     * 这样 NRV 不会先调用 Phoenix 的 q0() 并制造 NoSuchMethodError 噪声，也避免未来
     * 某个版本恰好复用了同名但不同语义的方法。
     */
    private static String tryKnownCenterFocusAdapter(Object cameraManager, long versionCode) {
        if (CameraBuilds.isPhoenixOs4Family(versionCode)
                || versionCode == CameraBuilds.OFFICIAL_OS4_6_6_510_VERSION_CODE) {
            return tryCenterFocusAdapter(cameraManager, "q0", "p704u6.p", "j0", "l0");
        }
        if (versionCode == CameraBuilds.OFFICIAL_OS3_6_4_530_VERSION_CODE) {
            return tryCenterFocusAdapter(cameraManager, "I0", "H3.q", "r", "u");
        }
        if (versionCode == CameraBuilds.OFFICIAL_OS3_6_4_370_VERSION_CODE) {
            return tryCenterFocusAdapter(cameraManager, "J0", "F3.p", "r", "u");
        }
        if (versionCode == CameraBuilds.NRV_161_VERSION_CODE) {
            return tryCenterFocusAdapter(cameraManager, "w1", "P3.q", "r", "t");
        }

        String route = tryCenterFocusAdapter(cameraManager,
                "q0", "p704u6.p", "j0", "l0");
        if (route == null) route = tryCenterFocusAdapter(cameraManager, "I0", "H3.q", "r", "u");
        if (route == null) route = tryCenterFocusAdapter(cameraManager, "J0", "F3.p", "r", "u");
        if (route == null) route = tryCenterFocusAdapter(cameraManager, "w1", "P3.q", "r", "t");
        return route;
    }

    private static String tryCenterFocusAdapter(Object cameraManager, String managerGetter,
            String expectedFocusClass, String centerMethodName, String startMethodName) {
        Object focusManager;
        try {
            focusManager = XposedHelpers.callMethod(cameraManager, managerGetter);
        } catch (Throwable t) {
            recordCenterFailure(managerGetter + " unavailable: " + describeFailure(t));
            return null;
        }
        if (focusManager == null) {
            recordCenterFailure(managerGetter + " returned null");
            return null;
        }
        String actualFocusClass = focusManager.getClass().getName();
        if (!matchesExpectedFocusClass(focusManager.getClass(), expectedFocusClass)) {
            recordCenterFailure(managerGetter + " returned " + actualFocusClass
                    + ", expected " + expectedFocusClass);
            return null;
        }

        try {
            Method getFocusMode = focusManager.getClass().getMethod("getFocusMode");
            Method centerMethod = focusManager.getClass().getMethod(centerMethodName);
            Method startMethod = focusManager.getClass().getMethod(startMethodName, int.class);
            if (getFocusMode.getReturnType() != int.class
                    || centerMethod.getReturnType() != void.class
                    || startMethod.getReturnType() != void.class) {
                recordCenterFailure("adapter " + expectedFocusClass + " return types: "
                        + getFocusMode.getReturnType().getName() + ", "
                        + centerMethod.getReturnType().getName() + ", "
                        + startMethod.getReturnType().getName());
                return null;
            }
            int focusMode = ((Number) getFocusMode.invoke(focusManager)).intValue();
            if (focusMode == 0 || focusMode == 5) {
                recordCenterFailure("adapter " + expectedFocusClass
                        + " rejected focusMode=" + focusMode);
                return null;
            }
            centerMethod.invoke(focusManager);
            startMethod.invoke(focusManager, 0);
            return expectedFocusClass + "." + centerMethodName + "+" + startMethodName;
        } catch (NoSuchMethodException e) {
            recordCenterFailure("adapter " + expectedFocusClass + " missing method: "
                    + e.getMessage());
            return null; // 正常版本差异，继续尝试下一条已核实适配器。
        } catch (Throwable t) {
            recordCenterFailure("adapter " + expectedFocusClass + " invocation failed: "
                    + describeFailure(t));
            return null;
        }
    }

    /**
     * 判断 FocusManager 的真实类型。
     *
     * <p>Phoenix-0.3.0 的移植工具会把 {@code p704u6.p} 重命名成运行时的
     * {@code u6.p}，但方法和继承关系保持不变。这里只兼容“单字母 + 数字前缀”
     * 被移除这一种已确认的包名变化；真正决定是否可调用的仍是下面的精确方法名、
     * 参数和返回值检查，不能因为类名相似就误把其他对象当作 FocusManager。</p>
     */
    private static boolean matchesExpectedFocusClass(Class<?> actualClass,
            String expectedFocusClass) {
        String actualName = actualClass.getName();
        if (expectedFocusClass.equals(actualName)) {
            return true;
        }

        int expectedDot = expectedFocusClass.lastIndexOf('.');
        int actualDot = actualName.lastIndexOf('.');
        if (expectedDot <= 0 || actualDot <= 0) {
            return false;
        }
        String expectedPackage = expectedFocusClass.substring(0, expectedDot);
        String actualPackage = actualName.substring(0, actualDot);
        String expectedSimpleName = expectedFocusClass.substring(expectedDot + 1);
        String actualSimpleName = actualName.substring(actualDot + 1);
        return expectedSimpleName.equals(actualSimpleName)
                && stripRenamedPackagePrefix(expectedPackage)
                .equals(stripRenamedPackagePrefix(actualPackage));
    }

    /** 仅去掉已知的“字母 + 数字 + 原包名”移植前缀，例如 p704u6 -> u6。 */
    private static String stripRenamedPackagePrefix(String packageName) {
        if (packageName == null || packageName.length() < 3
                || !Character.isLetter(packageName.charAt(0))
                || !Character.isDigit(packageName.charAt(1))) {
            return packageName;
        }
        int index = 1;
        while (index < packageName.length() && Character.isDigit(packageName.charAt(index))) {
            index++;
        }
        return index < packageName.length() ? packageName.substring(index) : packageName;
    }

    /**
     * 记录首次失败原因而不直接输出日志。最终由 triggerNativeCenterFocus() 统一输出一次，
     * 这样可以同时看到“实际 cameraManager 类型”和“q0/方法检查失败点”。
     */
    private static void recordCenterFailure(String detail) {
        if (detail != null && sCenterFailureDetail == null) {
            sCenterFailureDetail = detail;
        }
    }

    private static String describeFailure(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        Throwable cause = throwable;
        if (throwable instanceof java.lang.reflect.InvocationTargetException
                && ((java.lang.reflect.InvocationTargetException) throwable).getCause() != null) {
            cause = ((java.lang.reflect.InvocationTargetException) throwable).getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.length() == 0) {
            return cause.getClass().getName();
        }
        // 反射异常文本可能包含很长的 dex/路径信息，诊断日志只保留首行。
        int lineBreak = message.indexOf('\n');
        if (lineBreak >= 0) {
            message = message.substring(0, lineBreak);
        }
        if (message.length() > 180) {
            message = message.substring(0, 180) + "...";
        }
        return cause.getClass().getName() + ": " + message;
    }

    private static KeyEvent findKeyEvent(XC_MethodHook.MethodHookParam param) {
        if (param == null || param.args == null || param.args.length < 2
                || !(param.args[1] instanceof KeyEvent)) {
            return null;
        }
        if (!(param.args[0] instanceof Number)
                || ((Number) param.args[0]).intValue() != KeyEvent.KEYCODE_FOCUS) {
            return null;
        }
        return (KeyEvent) param.args[1];
    }

    private static boolean isPhotographyHandleFocusEvent(KeyEvent event) {
        if (event == null || event.getKeyCode() != KeyEvent.KEYCODE_FOCUS
                || event.getScanCode() != HANDLE_FOCUS_SCAN_CODE) {
            return false;
        }
        InputDevice device = event.getDevice();
        if (device == null && event.getDeviceId() >= 0) {
            device = InputDevice.getDevice(event.getDeviceId());
        }
        if (device == null || device.getVendorId() != HANDLE_VENDOR_ID) {
            return false;
        }
        int productId = device.getProductId();
        return productId == HANDLE_PRODUCT_PRO
                || productId == HANDLE_PRODUCT_LITE_1
                || productId == HANDLE_PRODUCT_LITE_2;
    }
}
