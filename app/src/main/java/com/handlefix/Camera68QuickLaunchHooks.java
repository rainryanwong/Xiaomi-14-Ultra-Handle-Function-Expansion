package com.handlefix;

import android.app.Activity;
import android.app.ComponentCaller;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** 6.8 的外部模式快捷启动接入目标 APK 的 IntentParser。 */
public final class Camera68QuickLaunchHooks {
    private static final String CAMERA_CLASS = "com.android.camera.Camera";
    private static final String CAMERA_MODE = "android.intent.extra.CAMERA_MODE";
    private static final String LAUNCH_SOURCE = "com.android.systemui.camera_launch_source";
    private static final String STREET_STYLE_BRIDGE =
            "com.aurora.camera.compat.handlefix.streetUi.1770.v1";
    private static final String STREET_STYLE_FINISH = "handlefix.streetUi.finish";
    private static final String STREET_STYLE_PREVIOUS = "handlefix.streetUi.previous";
    private static final ThreadLocal<Route> RESOLVING_STREET_STYLE = new ThreadLocal<>();
    private static final int SYSTEM_UID = 1000;
    private static final long ROUTE_WINDOW_MS = 10_000L;
    private static final WeakHashMap<Intent, Route> MODE_REQUESTS = new WeakHashMap<>();
    private static final WeakHashMap<Intent, RecordRequest> RECORD_REQUESTS = new WeakHashMap<>();
    private static final ThreadLocal<Route> STREET_SELECTION = new ThreadLocal<>();
    private static volatile boolean hooksReady;

    private Camera68QuickLaunchHooks() {}

    private static final class Route {
        final int module;
        final int streetStyle;
        final WeakReference<Activity> owner;
        final long deadline = SystemClock.uptimeMillis() + ROUTE_WINDOW_MS;

        Route(int module, int streetStyle, Activity owner) {
            this.module = module;
            this.streetStyle = streetStyle;
            this.owner = new WeakReference<>(owner);
        }
    }

    private static final class RecordRequest {
        final long deadline = SystemClock.uptimeMillis() + ROUTE_WINDOW_MS;
        boolean dispatching;
    }

    static boolean isTrustedRecordRequest(Intent intent) {
        synchronized (RECORD_REQUESTS) {
            RecordRequest request = RECORD_REQUESTS.get(intent);
            if (request == null) return false;
            if (SystemClock.uptimeMillis() <= request.deadline) return true;
            RECORD_REQUESTS.remove(intent);
            return false;
        }
    }

    static boolean beginRecordRequest(Intent intent) {
        synchronized (RECORD_REQUESTS) {
            RecordRequest request = RECORD_REQUESTS.get(intent);
            if (request == null || request.dispatching
                    || SystemClock.uptimeMillis() > request.deadline) return false;
            request.dispatching = true;
            return true;
        }
    }

    static void deferRecordRequest(Intent intent) {
        synchronized (RECORD_REQUESTS) {
            RecordRequest request = RECORD_REQUESTS.get(intent);
            if (request != null) request.dispatching = false;
        }
    }

    static void consumeRecordRequest(Intent intent) {
        synchronized (RECORD_REQUESTS) {
            RECORD_REQUESTS.remove(intent);
        }
    }

    public static void installIfSupported(ClassLoader loader, long versionCode) {
        if (Build.VERSION.SDK_INT < 35 || !CameraBuilds.isCamera68Port(versionCode)) return;
        HandleFixModule.install("camera68 grip quick launch", () -> install(loader));
    }

    @android.annotation.TargetApi(35)
    private static void install(ClassLoader loader) {
        ArrayList<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            Class<?> baseActivity = XposedHelpers.findClass("com.android.camera.a", loader);
            Class<?> camera = XposedHelpers.findClass(CAMERA_CLASS, loader);
            Class<?> manager = XposedHelpers.findClass("Lr.n", loader);
            Class<?> managerResult = XposedHelpers.findClass("Lr.n$b", loader);
            Class<?> parser = XposedHelpers.findClass("u2.Z", loader);
            Class<?> pair = XposedHelpers.findClass("g0.b", loader);
            Class<?> moduleEntry = XposedHelpers.findClass("com.android.camera.module.entry.a", loader);
            Class<?> catalog = XposedHelpers.findClass("t3.a", loader);
            Class<?> startControl = XposedHelpers.findClass(
                    "com.android.camera.module.loader.base.StartControl", loader);

            Method create = exact(baseActivity, "onCreate", void.class, Bundle.class);
            Method incoming = exact(camera, "onNewIntent", void.class, Intent.class);
            Method classify = exact(manager, "y", managerResult, boolean.class, Boolean.class);
            Method resolve = exact(parser, "d", int.class,
                    manager, boolean.class, boolean.class, boolean.class);
            Method parse = exact(parser, "g", pair,
                    manager, boolean.class, boolean.class, boolean.class);
            Method lookup = exact(catalog, "c", moduleEntry, int.class);
            Method startMode = exact(startControl, "create", startControl, int.class);
            Field managerIntent = field(manager, "a", Intent.class);
            Field explicitMode = field(managerResult, "b", boolean.class);
            Field parsedMode = field(pair, "b", Object.class);

            hooks.add(XposedBridge.hookMethod(create, new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!camera.isInstance(param.thisObject)) return;
                    Activity activity = (Activity) param.thisObject;
                    accept(activity, activity.getIntent(), activity.getInitialCaller().getUid());
                }
            }));
            hooks.add(XposedBridge.hookMethod(incoming, new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    // onNewIntent 的发送方必须独立验证，不能沿用首次启动时的 UID。
                    Activity activity = (Activity) param.thisObject;
                    Intent previous = activity.getIntent();
                    synchronized (MODE_REQUESTS) {
                        MODE_REQUESTS.remove(previous);
                    }
                    consumeRecordRequest(previous);
                    STREET_SELECTION.remove();
                    ComponentCaller caller = activity.getCurrentCaller();
                    applyStreetStyle(loader, activity, 0);
                    accept(activity, (Intent) param.args[0], caller.getUid());
                }
            }));
            hooks.add(XposedBridge.hookMethod(classify, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param)
                        throws IllegalAccessException {
                    if (param.hasThrowable()) return;
                    Route request = route(managerIntent.get(param.thisObject));
                    if (request != null
                            && (request.streetStyle == 0 || RESOLVING_STREET_STYLE.get() == request)) {
                        // y() 的 b 字段选择 g() 内原生显式模式分支。仅调整已验证的这一封
                        // Intent 分类，不改变 CameraIntentManager 的调用者白名单。
                        explicitMode.setBoolean(param.getResult(), true);
                    }
                }
            }));
            hooks.add(XposedBridge.hookMethod(resolve, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.hasThrowable()) return;
                    Route request = route(managerIntent.get(param.args[0]));
                    if (request == null || request.module <= 0) return;
                    // 229 只能在本次解析已经建立 CameraCompat 样式事务时使用。
                    if (request.streetStyle != 0 && RESOLVING_STREET_STYLE.get() != request) return;
                    // 精确模块目标和正常解析共用相机的入口注册校验。
                    if (lookup.invoke(null, request.module) != null) {
                        param.setResult(Integer.valueOf(request.module));
                    }
                }
            }));
            hooks.add(XposedBridge.hookMethod(parse, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param)
                        throws IllegalAccessException {
                    if ((Boolean) param.args[1]) return; // justFetch
                    Route request = route(managerIntent.get(param.args[0]));
                    if (request == null || request.streetStyle == 0) return;
                    Activity owner = request.owner.get();
                    if (owner == null) return;
                    Consumer<Boolean> finish = applyStreetStyle(loader, owner, request.streetStyle);
                    if (finish == null) {
                        HandleFixModule.log("camera68 quick launch: CameraCompat street style unavailable");
                        return;
                    }
                    param.setObjectExtra(STREET_STYLE_FINISH, finish);
                    param.setObjectExtra(STREET_STYLE_PREVIOUS, RESOLVING_STREET_STYLE.get());
                    RESOLVING_STREET_STYLE.set(request);
                }

                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if ((Boolean) param.args[1]) return; // justFetch
                    @SuppressWarnings("unchecked")
                    Consumer<Boolean> finish = (Consumer<Boolean>) param.getObjectExtra(STREET_STYLE_FINISH);
                    if (finish != null) {
                        Route previous = (Route) param.getObjectExtra(STREET_STYLE_PREVIOUS);
                        if (previous == null) RESOLVING_STREET_STYLE.remove();
                        else RESOLVING_STREET_STYLE.set(previous);
                        boolean selected = false;
                        try {
                            selected = !param.hasThrowable() && param.getResult() != null
                                    && Integer.valueOf(229).equals(parsedMode.get(param.getResult()));
                        } finally {
                            try {
                                finish.accept(selected);
                            } catch (RuntimeException | LinkageError error) {
                                HandleFixModule.log("camera68 quick launch: CameraCompat could not finish street style", error);
                            }
                        }
                        if (!selected) {
                            HandleFixModule.log("camera68 quick launch: parser did not select street module 229");
                        }
                    }
                    Intent intent = (Intent) managerIntent.get(param.args[0]);
                    Route request;
                    synchronized (MODE_REQUESTS) {
                        request = MODE_REQUESTS.remove(intent);
                    }
                    if (param.hasThrowable()) return;
                    if (request != null && request.module == 225
                            && SystemClock.uptimeMillis() <= request.deadline
                            && lookup.invoke(null, 225) != null) {
                        STREET_SELECTION.set(request);
                    } else {
                        STREET_SELECTION.remove();
                    }
                }
            }));
            hooks.add(XposedBridge.hookMethod(startMode, new XC_MethodHook(XC_MethodHook.PRIORITY_LOWEST) {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Route pending = STREET_SELECTION.get();
                    if (pending == null) return;
                    STREET_SELECTION.remove();
                    int mode = (Integer) param.args[0];
                    // 相机配套模块可能在 StartControl.create 前按样式把 225 改到 229。
                    // 仅消费一次该启动对应的原生街拍选择，不改写持久的街拍样式设置。
                    if (SystemClock.uptimeMillis() <= pending.deadline
                            && (mode == 225 || mode == 229)) {
                        param.args[0] = Integer.valueOf(225);
                    }
                }
            }));
            hooksReady = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            throw new IllegalStateException("Camera 6.8 quick launch descriptors", error);
        }
    }

    private static void accept(Activity activity, Intent intent, int uid) {
        if (intent == null || uid != SYSTEM_UID || !ModeConfig.isCamera68Mapping()) return;
        ComponentName component = intent.getComponent();
        if (component == null || !"com.android.camera".equals(component.getPackageName())
                || !CAMERA_CLASS.equals(component.getClassName())) return;
        String source = intent.getStringExtra(LAUNCH_SOURCE);
        if ("android.media.action.VIDEO_CAMERA".equals(intent.getAction())
                && Camera68LaunchTargets.RECORD_LAUNCH_SOURCE.equals(source)
                && intent.getBooleanExtra(ModeConfig.EXTRA_HANDLEFIX_START_RECORDING, false)) {
            intent.removeExtra(ModeConfig.EXTRA_HANDLEFIX_START_RECORDING);
            synchronized (RECORD_REQUESTS) {
                RECORD_REQUESTS.put(intent, new RecordRequest());
            }
            return;
        }
        if (!"android.media.action.STILL_IMAGE_CAMERA".equals(intent.getAction())
                || !ModeConfig.GRIP_QUICK_LAUNCH_SOURCE.equals(source)) return;
        String mode = intent.getStringExtra(CAMERA_MODE);
        int exact = intent.getIntExtra(ModeConfig.EXTRA_HANDLEFIX_QUICK_MODE_TARGET, 0);
        if (!Camera68LaunchTargets.isSupportedRequest(mode, exact)) return;
        boolean legend = intent.getBooleanExtra(ModeConfig.EXTRA_HANDLEFIX_QUICK_STREET_LEGEND_UI, false);
        if (legend && exact != 229) return;
        intent.removeExtra(ModeConfig.EXTRA_HANDLEFIX_QUICK_MODE_TARGET);
        intent.removeExtra(ModeConfig.EXTRA_HANDLEFIX_QUICK_STREET_LEGEND_UI);
        synchronized (MODE_REQUESTS) {
            MODE_REQUESTS.put(intent, new Route(exact, exact == 229 ? (legend ? 2 : 1) : 0, activity));
        }
    }

    private static Consumer<Boolean> applyStreetStyle(ClassLoader loader, Activity owner, int style) {
        Object bridge = XposedHelpers.getAdditionalInstanceField(loader, STREET_STYLE_BRIDGE);
        if (!(bridge instanceof BiFunction)) return null;
        @SuppressWarnings("unchecked")
        BiFunction<Activity, Integer, Consumer<Boolean>> apply =
                (BiFunction<Activity, Integer, Consumer<Boolean>>) bridge;
        try {
            return apply.apply(owner, style);
        } catch (RuntimeException | LinkageError error) {
            HandleFixModule.log("camera68 quick launch: CameraCompat street style callback failed", error);
            return null;
        }
    }

    public static boolean hasStreetStyleBridge(ClassLoader loader) {
        return hooksReady && ModeConfig.isCamera68Mapping()
                && CameraBuilds.isCamera68Port(HandleFixModule.CAMERA_VERSION_CODE)
                && XposedHelpers.getAdditionalInstanceField(loader, STREET_STYLE_BRIDGE) instanceof BiFunction;
    }

    private static Route route(Object intent) {
        synchronized (MODE_REQUESTS) {
            Route request = MODE_REQUESTS.get(intent);
            if (request != null && SystemClock.uptimeMillis() > request.deadline) {
                MODE_REQUESTS.remove(intent);
                return null;
            }
            return request;
        }
    }

    private static Method exact(Class<?> owner, String name, Class<?> result, Class<?>... args)
            throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(name, args);
        if (method.getReturnType() != result) {
            throw new NoSuchMethodException(owner.getName() + "." + name);
        }
        method.setAccessible(true);
        return method;
    }

    private static Field field(Class<?> owner, String name, Class<?> type)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != type) throw new NoSuchFieldException(owner.getName() + "." + name);
        field.setAccessible(true);
        return field;
    }
}
