package com.handlefix;

import android.content.Intent;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Phoenix 快捷启动的一次性精确模式路由桥。
 *
 * <p>system_server 先按 Phoenix 已公开的 CAMERA_MODE 进入最接近的原生解析链，再附带
 * HandleFix 私有目标 moduleId。本类核对 launch_source 后，只在同一次 Intent 解析窗口
 * 收敛 IntentParser、RunningData 和 StartControl；225/229 还会覆盖 Phoenix 自己的街拍
 * 选择器。标记超时或 descriptor 不匹配时整组 Hook 回滚，绝不改写普通相机启动。</p>
 */
final class PhoenixQuickLaunchHooks {
    private static final String EXTRA_CAMERA_MODE = "android.intent.extra.CAMERA_MODE";
    private static final long ROUTE_WINDOW_MS = 10_000L;
    private static volatile int sPendingMode;
    private static volatile long sPendingUntil;
    /** 与模式路由分开消费：Fragment 工厂可能在 module.init() 返回之后才创建 UI。 */
    private static volatile boolean sPendingLegendStreetUi;
    private static volatile long sPendingLegendStreetUiUntil;
    private static Method sGetRunningData;
    private static Method sSetRunningMode;
    private static java.lang.reflect.Constructor<?> sPairConstructor;

    private PhoenixQuickLaunchHooks() {
    }

    static void installIfSupported(ClassLoader loader, long versionCode) {
        if (!CameraBuilds.isPhoenixOs4Family(versionCode)) return;
        HandleFixModule.install("phoenix grip quick launch", () -> install(loader));
        // UI 工厂描述符独立预检；失败时只关闭“传奇款”UI 强制，不拖垮已验收的模式路由。
        HandleFixModule.install("phoenix grip quick launch legendary street ui",
                () -> {
                    try {
                        installLegendStreetUi(loader);
                    } catch (ReflectiveOperationException error) {
                        throw new IllegalStateException(
                                "Phoenix legendary street UI preflight failed", error);
                    }
                });
    }

    private static void install(ClassLoader loader) {
        ArrayList<XC_MethodHook.Unhook> installed = new ArrayList<>();
        try {
            Class<?> manager = XposedHelpers.findClass("vr.m", loader);
            Method acceptIntent = exact(manager, "B", void.class, false, Intent.class);
            Class<?> parser = XposedHelpers.findClass("u2.S", loader);
            Method resolveMode = exact(parser, "d", int.class, true,
                    manager, boolean.class, boolean.class, boolean.class);
            Class<?> pair = XposedHelpers.findClass("h0.b", loader);
            Method parseIntent = exact(parser, "g", pair, false,
                    manager, boolean.class, boolean.class, boolean.class);
            Class<?> runningData = XposedHelpers.findClass("u2.Q", loader);
            sGetRunningData = exact(XposedHelpers.findClass("g2.a", loader),
                    "g", runningData, true);
            sSetRunningMode = exact(runningData, "c0", void.class, false, int.class);
            Method readRunningMode = exact(runningData, "E", int.class, false, int.class);
            sPairConstructor = pair.getDeclaredConstructor(Object.class, Object.class);
            sPairConstructor.setAccessible(true);
            Class<?> selector = XposedHelpers.findClass("U4.i", loader);
            Method selectStreet = exact(selector, "Yq", boolean.class, false,
                    int.class, String.class, boolean.class);
            Class<?> startControl = XposedHelpers.findClass(
                    "com.android.camera.module.loader.base.StartControl", loader);
            Method createStartControl = exact(startControl, "create", startControl, true,
                    int.class);
            Class<?> baseModule = XposedHelpers.findClass("com.android.camera.module.r", loader);
            Method initializeModule = exact(baseModule, "init", void.class, false);
            Method getModuleIndex = exact(baseModule, "getModuleIndex", int.class, false);

            installed.add(XposedBridge.hookMethod(acceptIntent, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    capture((Intent) param.args[0]);
                }
            }));
            installed.add(XposedBridge.hookMethod(resolveMode, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    int target = pendingMode();
                    if (target != 0) param.setResult(Integer.valueOf(target));
                }
            }));
            installed.add(XposedBridge.hookMethod(parseIntent, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    int target = pendingMode();
                    Object result = param.getResult();
                    if (target == 0 || result == null) return;
                    Object cameraId = XposedHelpers.getObjectField(result, "a");
                    setRunningMode(target);
                    param.setResult(sPairConstructor.newInstance(cameraId, Integer.valueOf(target)));
                }
            }));
            installed.add(XposedBridge.hookMethod(selectStreet, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    int target = pendingMode();
                    if (target != 225 && target != 229) return;
                    param.args[0] = Integer.valueOf(target);
                    HandleFixModule.debug("phoenix-quick-launch: selector route=" + target);
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    // 相机外启动并不保证经过该选择器。强制事务要保留到目标模块实际创建，
                    // 不能在这里提前清除，否则 Phoenix 后续的数据层路由仍会接管。
                }
            }));

            /*
             * Phoenix-1.2.14 的 StreetUiEntryPoint 会在 IntentParser 之后继续 Hook
             * StartControl.create、Q.c0 与 Q.E，并按持久设置把 225 再改成 229。
             * 这里不写 Phoenix 偏好，只在 HandleFix 私有启动事务存活期间用明确的
             * Xposed 优先级取得“最终一次性路由”：LOWEST before 在 Phoenix 默认
             * before 之后恢复参数；HIGHEST after 在 Phoenix 默认 after 之后恢复结果。
             */
            installed.add(XposedBridge.hookMethod(sSetRunningMode,
                    new XC_MethodHook(XC_MethodHook.PRIORITY_LOWEST) {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            forceModeArgument(param, "running-write");
                        }
                    }));
            installed.add(XposedBridge.hookMethod(readRunningMode,
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            int target = pendingMode();
                            if (target != 0) {
                                param.setResult(Integer.valueOf(target));
                            }
                        }
                    }));
            installed.add(XposedBridge.hookMethod(createStartControl,
                    new XC_MethodHook(XC_MethodHook.PRIORITY_LOWEST) {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            forceModeArgument(param, "start-control");
                        }
                    }));
            installed.add(XposedBridge.hookMethod(initializeModule,
                    consumeAfterModuleInitialized(getModuleIndex)));
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : installed) hook.unhook();
            clearAllPending();
            throw new IllegalStateException("Phoenix quick-launch preflight/install failed", error);
        }
    }

    /**
     * Phoenix-1.2.14 的 229 共用同一模块，仅由主题工厂选择 LC/New 两套街拍 Fragment。
     * 这里不 Hook 全局 Je.c.V()，因为该能力判断还控制大量无关相机 UI；只在受信任的
     * HandleFix 启动事务中替换这一次工厂返回值，并在首个命中后立即消费。
     */
    private static void installLegendStreetUi(ClassLoader loader)
            throws ReflectiveOperationException {
        Class<?> feature = XposedHelpers.findClass("Je.c", loader);
        Method supportsThemedStreetUi = exact(feature, "N", boolean.class, false);
        Class<?> streetBase = XposedHelpers.findClass("q4.i", loader);
        Class<?> streetNew = XposedHelpers.findClass("q4.A", loader);
        final java.lang.reflect.Constructor<?> streetNewConstructor =
                streetNew.getDeclaredConstructor();
        streetNewConstructor.setAccessible(true);
        Method lcFactory = exact(XposedHelpers.findClass("miuix.autodensity.d", loader),
                "k", streetBase, false);
        Method newFactory = exact(XposedHelpers.findClass("sd.z", loader),
                "k", streetBase, false);
        XposedBridge.hookMethod(supportsThemedStreetUi,
                new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (hasPendingLegendStreetUi() && param.getThrowable() == null) {
                            /*
                             * f6.g$a 构造 fragmentId=180 时，N()==false 会直接 new q4.w，
                             * 完全绕过 LC/New 主题工厂。只在受信任的传奇款启动窗口返回
                             * true，让本次构造进入相机原有主题工厂；不修改全局设备特征。
                             */
                            param.setResult(Boolean.TRUE);
                        }
                    }
                });
        XC_MethodHook forceNewUi = new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!hasPendingLegendStreetUi() || param.getThrowable() != null) return;
                try {
                    Object current = param.getResult();
                    if (!streetNew.isInstance(current)) {
                        param.setResult(streetNewConstructor.newInstance());
                    }
                    clearLegendStreetUiPending();
                    HandleFixModule.debug(
                            "phoenix-quick-launch: consumed legendary street ui=FragmentEquipStreetNew");
                } catch (Throwable error) {
                    HandleFixModule.log(
                            "phoenix-quick-launch: legendary street ui factory failed", error);
                }
            }
        };
        XposedBridge.hookMethod(lcFactory, forceNewUi);
        XposedBridge.hookMethod(newFactory, forceNewUi);
    }

    private static void capture(Intent intent) {
        clearAllPending();
        if (intent == null
                || !ModeConfig.GRIP_QUICK_LAUNCH_SOURCE.equals(intent.getStringExtra(
                "com.android.systemui.camera_launch_source"))) {
            return;
        }
        int target = intent.getIntExtra(ModeConfig.EXTRA_HANDLEFIX_QUICK_MODE_TARGET, 0);
        if (target == 0 && "STREET".equals(intent.getStringExtra(EXTRA_CAMERA_MODE))) {
            // 兼容 12.1.49-test1/test2 写入的街拍专用一次性字段。
            target = intent.getIntExtra(ModeConfig.EXTRA_HANDLEFIX_QUICK_STREET_TARGET, 0);
        }
        if (!isSupportedExactMode(target)) return;
        sPendingMode = target;
        sPendingUntil = SystemClock.uptimeMillis() + ROUTE_WINDOW_MS;
        if (target == 229 && intent.getBooleanExtra(
                ModeConfig.EXTRA_HANDLEFIX_QUICK_STREET_LEGEND_UI, false)) {
            sPendingLegendStreetUi = true;
            sPendingLegendStreetUiUntil = sPendingUntil;
        }
        // 一次性标记只参与本次解析，不让相机后续复用旧 Intent 时重复强制路由。
        intent.removeExtra(ModeConfig.EXTRA_HANDLEFIX_QUICK_MODE_TARGET);
        intent.removeExtra(ModeConfig.EXTRA_HANDLEFIX_QUICK_STREET_TARGET);
        intent.removeExtra(ModeConfig.EXTRA_HANDLEFIX_QUICK_STREET_LEGEND_UI);
        setRunningMode(target);
        HandleFixModule.debug("phoenix-quick-launch: captured exact route=" + target);
    }

    private static int pendingMode() {
        if (sPendingMode == 0 || SystemClock.uptimeMillis() > sPendingUntil) {
            clearRoutePending();
            return 0;
        }
        return sPendingMode;
    }

    private static void clearRoutePending() {
        sPendingMode = 0;
        sPendingUntil = 0L;
    }

    private static boolean hasPendingLegendStreetUi() {
        if (!sPendingLegendStreetUi
                || SystemClock.uptimeMillis() > sPendingLegendStreetUiUntil) {
            clearLegendStreetUiPending();
            return false;
        }
        return true;
    }

    private static void clearLegendStreetUiPending() {
        sPendingLegendStreetUi = false;
        sPendingLegendStreetUiUntil = 0L;
    }

    private static void clearAllPending() {
        clearRoutePending();
        clearLegendStreetUiPending();
    }

    private static void forceModeArgument(XC_MethodHook.MethodHookParam param, String stage) {
        int target = pendingMode();
        if (target == 0) return;
        int incoming = ((Integer) param.args[0]).intValue();
        param.args[0] = Integer.valueOf(target);
        if (incoming != target) {
            HandleFixModule.debug("phoenix-quick-launch: " + stage + " route="
                    + incoming + "->" + target);
        }
    }

    private static XC_MethodHook consumeAfterModuleInitialized(final Method getModuleIndex) {
        return new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                int target = pendingMode();
                if (param.getThrowable() != null || target == 0) {
                    return;
                }
                int initialized = ((Integer) getModuleIndex.invoke(param.thisObject)).intValue();
                if (initialized != target) return;
                HandleFixModule.debug("phoenix-quick-launch: consumed route=" + target);
                // UI 事务单独保留到 Fragment 工厂命中，避免 init() 先返回导致标记丢失。
                clearRoutePending();
            }
        };
    }

    /** 与按键配置页默认显示的十个主模式一致，另含街拍特别款 229 精确分支。 */
    private static boolean isSupportedExactMode(int mode) {
        return mode == 163 || mode == 162 || mode == 256 || mode == 167 || mode == 180
                || mode == 173 || mode == 171 || mode == 186 || mode == 225 || mode == 175
                || mode == 229;
    }

    private static void setRunningMode(int target) {
        try {
            if (sGetRunningData != null && sSetRunningMode != null) {
                Object running = sGetRunningData.invoke(null);
                if (running != null) sSetRunningMode.invoke(running, Integer.valueOf(target));
            }
        } catch (Throwable error) {
            HandleFixModule.log("phoenix-quick-launch: cannot set running mode=" + target, error);
        }
    }

    private static Method exact(Class<?> owner, String name, Class<?> result,
            boolean isStatic, Class<?>... parameters) throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(name, parameters);
        if (method.getReturnType() != result
                || Modifier.isStatic(method.getModifiers()) != isStatic) {
            throw new NoSuchMethodException(owner.getName() + "." + name
                    + " return/static mismatch");
        }
        method.setAccessible(true);
        return method;
    }
}
