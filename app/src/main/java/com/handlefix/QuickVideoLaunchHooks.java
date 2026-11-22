package com.handlefix;

import android.app.Activity;
import android.content.Intent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 把 system_server 的“进入录像并开始录制”请求接回小米相机原生 QuickVideo 状态机。
 *
 * <p>仅靠 {@code VIDEO_CAMERA} Intent 能可靠切到录像页，但不会把启动来源写入
 * VideoModule 私有的 {@code mQuickVideo}，也不会自动走相机内部的模式切换回调。真实
 * APK 中 {@code startVideoRecordingIfNeeded()} 只在该字段为 true 时调用
 * {@code directRecordingWhenModeChanged()}，由相机自行等待 Window 获得焦点并在主线程
 * 启动录像。上一版只在 {@code setParameter(...)} 返回后补状态，外部启动路径随后又
 * 可能跳过 {@code onPreviewStart()}，所以仍然只有预览。本类在预览会话成功后，把请求
 * 交回相机自己的私有 gate；相机仍负责 500 ms 调度和真正的 shutter 调用。</p>
 *
 * <p>HandleFix 私有 extra 是一次性令牌：只有 system_server 的 START 策略会写入；
 * OPEN 策略和普通外部 VIDEO_CAMERA 启动完全不受影响。字段或生命周期入口不匹配时
 * 保留“已进入录像页”的安全降级，不强制调用混淆后的录制方法。</p>
 */
public final class QuickVideoLaunchHooks {
    /**
     * Activity 重用时系统走 onNewIntent()，不能假设一定会再次调用 setParameter()。
     * 用弱键保存一次性请求，避免把令牌绑死在某一个生命周期回调上。
     */
    private static final Map<Activity, Long> PENDING_REQUESTS =
            new WeakHashMap<Activity, Long>();
    /** 已经把 mQuickVideo 写入某个 VideoModule 实例的请求，供 gate 返回后消费。 */
    private static final Map<Object, Boolean> ARMED_MODULES =
            new WeakHashMap<Object, Boolean>();
    /** 录像预览会话已经建立；只有到这个点才允许主动重试原生 gate。 */
    private static final Map<Object, Boolean> READY_MODULES =
            new WeakHashMap<Object, Boolean>();
    private static final long REQUEST_TTL_MS = 15000L;

    private QuickVideoLaunchHooks() {
    }

    public static void install(final ClassLoader classLoader) {
        HandleFixModule.install("quick-video-native-state", new Runnable() {
            @Override
            public void run() {
                Class<?> videoModule = XposedHelpers.findClass(
                        "com.android.camera.module.VideoModule", classLoader);
                int setParameterCount = 0;
                for (Method method : videoModule.getDeclaredMethods()) {
                    if (!"setParameter".equals(method.getName())
                            || method.getParameterTypes().length != 1
                            || method.getReturnType() != void.class
                            || Modifier.isStatic(method.getModifiers())) {
                        continue;
                    }
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            armNativeQuickVideo(param.thisObject, "setParameter");
                            // 外部 VIDEO_CAMERA 启动可能没有再调用 onPreviewStart()。
                            // 如果预览会话已经先于 setParameter 完成，这里补一次原生 gate；
                            // 未准备好时保持 pending，等待 onPreviewSessionSuccess。
                            tryInvokeNativeGate(param.thisObject, "setParameter");
                        }
                    });
                    setParameterCount++;
                }

                int previewReadyCount = 0;
                for (Method method : videoModule.getDeclaredMethods()) {
                    if ("onPreviewSessionSuccess".equals(method.getName())
                            && method.getParameterTypes().length == 1
                            && method.getReturnType() == void.class
                            && !Modifier.isStatic(method.getModifiers())) {
                        method.setAccessible(true);
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                markPreviewReady(param.thisObject);
                                // 这是外部启动路径最可靠的共同就绪点：相机预览会话已经
                                // 建立，再调用私有 gate 不会提前触碰 MediaRecorder。
                                tryInvokeNativeGate(param.thisObject,
                                        "onPreviewSessionSuccess");
                            }
                        });
                        previewReadyCount++;
                    }
                }

                int focusCount = 0;
                for (Method method : videoModule.getDeclaredMethods()) {
                    if ("onWindowFocusChanged".equals(method.getName())
                            && method.getParameterTypes().length == 1
                            && method.getParameterTypes()[0] == boolean.class
                            && method.getReturnType() == void.class
                            && !Modifier.isStatic(method.getModifiers())) {
                        method.setAccessible(true);
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (param.args.length == 1
                                        && Boolean.TRUE.equals(param.args[0])) {
                                    // 某些锁屏/Activity 重用时 session 先成功、Window 后
                                    // 获得焦点；此处只重试已确认 ready 的实例。
                                    tryInvokeNativeGate(param.thisObject,
                                            "onWindowFocusChanged");
                                }
                            }
                        });
                        focusCount++;
                    }
                }
                if (setParameterCount == 0) {
                    throw new IllegalStateException("VideoModule.setParameter entry not found");
                }

                int gateCount = 0;
                for (Method method : videoModule.getDeclaredMethods()) {
                    if ("startVideoRecordingIfNeeded".equals(method.getName())
                            && method.getParameterTypes().length == 0
                            && method.getReturnType() == void.class
                            && !Modifier.isStatic(method.getModifiers())) {
                        method.setAccessible(true);
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                armNativeQuickVideo(param.thisObject,
                                        "startVideoRecordingIfNeeded");
                            }
                        });
                        gateCount++;
                    } else if ("directRecordingWhenModeChanged".equals(method.getName())
                            && method.getParameterTypes().length == 0
                            && method.getReturnType() == boolean.class
                            && !Modifier.isStatic(method.getModifiers())) {
                        method.setAccessible(true);
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                armNativeQuickVideo(param.thisObject,
                                        "directRecordingWhenModeChanged");
                            }

                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (!(param.getResult() instanceof Boolean)
                                        || !((Boolean) param.getResult()).booleanValue()) {
                                    return;
                                }
                                Activity activity = activityOf(param.thisObject);
                                if (activity != null && isArmed(param.thisObject)
                                        && isPending(activity)) {
                                    consumeRequest(activity, param.thisObject,
                                            "directRecordingWhenModeChanged");
                                }
                            }
                        });
                        gateCount++;
                    }
                }

                // Camera 是 singleTop Activity。已有实例收到下一次快捷录像时，系统不会
                // 重建 VideoModule，只会调用 onNewIntent；必须在这里重新登记令牌。
                Class<?> camera = XposedHelpers.findClass(
                        "com.android.camera.Camera", classLoader);
                XposedHelpers.findAndHookMethod(camera, "onNewIntent", Intent.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.thisObject instanceof Activity
                                        && isStartRequest((Intent) param.args[0])) {
                                    markPending((Activity) param.thisObject,
                                            (Intent) param.args[0], true);
                                }
                            }
                        });
                HandleFixModule.log("quick-video: native gates hooked setParameter="
                        + setParameterCount + " gate=" + gateCount
                        + " previewReady=" + previewReadyCount + " focus=" + focusCount);
            }
        });
    }

    private static void markPreviewReady(Object videoModule) {
        if (videoModule == null) {
            return;
        }
        Activity activity = activityOf(videoModule);
        if (activity == null) {
            return;
        }
        // 普通录像也会经过 onPreviewSessionSuccess；没有本模块的一次性令牌时，
        // 不写状态、不打日志，避免把 C2 诊断逻辑带入所有录像启动。
        Intent intent = activity.getIntent();
        if (!isPending(activity) && !isStartRequest(intent)) {
            return;
        }
        boolean newlyReady;
        synchronized (READY_MODULES) {
            newlyReady = !Boolean.TRUE.equals(READY_MODULES.get(videoModule));
            READY_MODULES.put(videoModule, Boolean.TRUE);
        }
        if (newlyReady) {
            HandleFixModule.log("quick-video: preview session ready");
        }
    }

    private static boolean isPreviewReady(Object videoModule) {
        synchronized (READY_MODULES) {
            return Boolean.TRUE.equals(READY_MODULES.get(videoModule));
        }
    }

    /**
     * 只调用相机自己的 gate，不伪造 KeyEvent/触摸，也不在模块中另设延时。
     * gate 返回 false 时保留 pending，后续焦点回调仍可重试；返回 true 后由原生
     * gate 的 afterHook 消费一次性令牌，避免同一请求重复开录。
     */
    private static void tryInvokeNativeGate(Object videoModule, String source) {
        Activity activity = activityOf(videoModule);
        if (activity == null || !isPending(activity) || !isArmed(videoModule)
                || !isPreviewReady(videoModule)) {
            return;
        }
        try {
            if (!activity.hasWindowFocus()) {
                return;
            }
            Object result = XposedHelpers.callMethod(
                    videoModule, "directRecordingWhenModeChanged");
            if (result instanceof Boolean && ((Boolean) result).booleanValue()) {
                HandleFixModule.log("quick-video: native recording gate accepted from "
                        + source);
            } else {
                HandleFixModule.log("quick-video: native recording gate deferred from "
                        + source);
            }
        } catch (Throwable t) {
            // 版本没有可调用的准确 gate 时保持“只进入录像页”的安全降级。
            HandleFixModule.log("quick-video: native recording gate call failed from "
                    + source + "; keep pending -> " + t);
        }
    }

    private static void armNativeQuickVideo(Object videoModule, String source) {
        try {
            Activity activity = activityOf(videoModule);
            if (activity == null) {
                return;
            }
            Intent intent = activity.getIntent();
            if (!isPending(activity)
                    && !isStartRequest(intent)) {
                return;
            }
            if (isStartRequest(intent)) {
                markPending(activity, intent, false);
            }
            XposedHelpers.setBooleanField(videoModule, "mQuickVideo", true);
            synchronized (ARMED_MODULES) {
                if (!Boolean.TRUE.equals(ARMED_MODULES.get(videoModule))) {
                    ARMED_MODULES.put(videoModule, Boolean.TRUE);
                    HandleFixModule.log("quick-video: native state armed at " + source);
                }
            }
        } catch (Throwable t) {
            HandleFixModule.log("quick-video: cannot arm native state; keep video UI -> " + t);
        }
    }

    private static Activity activityOf(Object videoModule) {
        try {
            Object activityObject = XposedHelpers.callMethod(videoModule, "getActivity");
            return activityObject instanceof Activity ? (Activity) activityObject : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isStartRequest(Intent intent) {
        return intent != null && intent.getBooleanExtra(
                ModeConfig.EXTRA_HANDLEFIX_START_RECORDING, false);
    }

    private static void markPending(Activity activity, Intent intent, boolean forceRefresh) {
        if (activity == null || !isStartRequest(intent)) {
            return;
        }
        synchronized (PENDING_REQUESTS) {
            if (forceRefresh || !PENDING_REQUESTS.containsKey(activity)) {
                PENDING_REQUESTS.put(activity, Long.valueOf(System.currentTimeMillis()));
                HandleFixModule.log("quick-video: start marker received"
                        + (forceRefresh ? " via onNewIntent" : ""));
            }
        }
    }

    private static boolean isPending(Activity activity) {
        if (activity == null) {
            return false;
        }
        synchronized (PENDING_REQUESTS) {
            Long receivedAt = PENDING_REQUESTS.get(activity);
            if (receivedAt == null) {
                return false;
            }
            if (System.currentTimeMillis() - receivedAt.longValue() > REQUEST_TTL_MS) {
                PENDING_REQUESTS.remove(activity);
                return false;
            }
            return true;
        }
    }

    private static boolean isArmed(Object videoModule) {
        synchronized (ARMED_MODULES) {
            return Boolean.TRUE.equals(ARMED_MODULES.get(videoModule));
        }
    }

    private static void consumeRequest(Activity activity, Object videoModule, String source) {
        synchronized (PENDING_REQUESTS) {
            PENDING_REQUESTS.remove(activity);
        }
        try {
            Intent intent = activity.getIntent();
            if (intent != null) {
                intent.removeExtra(ModeConfig.EXTRA_HANDLEFIX_START_RECORDING);
            }
        } catch (Throwable ignored) {
            // 令牌消费失败不影响已经由原生 gate 安排的录像。
        }
        synchronized (ARMED_MODULES) {
            ARMED_MODULES.remove(videoModule);
        }
        synchronized (READY_MODULES) {
            READY_MODULES.remove(videoModule);
        }
        HandleFixModule.log("quick-video: native recording scheduled via " + source);
    }
}
