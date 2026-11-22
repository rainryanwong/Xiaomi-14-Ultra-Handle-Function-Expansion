package com.handlefix;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * “快门键长按徕卡经典/生动轮拍”的相机生命周期适配器。
 *
 * <p>C3 的根因边界来自诊断结果：手柄快门的 ACTION_DOWN 已经沿相机原生链拍出
 * 第一张照片，长按 repeat 只负责进入长按分发；它不是第二次拍摄。因此这里不在
 * {@code onKeyUp} 上盲目补拍，也不使用固定 {@code sleep/postDelayed} 等待 JPEG，
 * 而是以 {@code onPictureTakenFinished(boolean,long,int)} 为唯一的拍摄完成信号，
 * 并在长按阈值成立后先走一次相机原生的“逻辑释放”，不等待用户真正松手：</p>
 *
 * <pre>
 * 原生 DOWN 第一张完成 + 达到长按阈值 + 原生 performKeyClicked(pressed=false) 清理完成
 * -> 切换 CV
 * -> 等待切换后的 notifyFirstFrameArrived(int)
 * -> mCameraAction.onShutterButtonClick(本次原生 triggerMode)
 * -> 相机原生对焦/阻拍门控 -> startNormalCapture(moduleState.S())
 * -> 第二张完成 -> 恢复长按前的 CV
 * </pre>
 *
 * <p>这里不能直接调用 {@code startNormalCapture(170)}：170 是物理键值，不保证
 * 等于 {@code parseKeyCameraTriggerMode()} 产生的拍摄触发模式，而且直接调用会跳过
 * {@code onShutterButtonClick()} 建立的相机状态。第二拍复用相机原生动作入口，不使用
 * 固定延时，也不伪造新的 KeyEvent。</p>
 *
 * <p>Phoenix/官方 6.4 与 6.6/NRV 使用 KeyEvent 新链；V5.1 使用 repeatCount 旧链。
 * 安装时按参数类型精确识别，且只有按键、首拍完成、切换后首帧与原生第二拍入口
 * 全部存在才进入 ready。版本号只是候选门，不会把一个 APK 的混淆入口硬套到另一版。</p>
 */
public final class DualStyleCaptureHooks {
    private static final String CAMERA2_MODULE_CLASS =
            "com.android.camera.module.Camera2Module";
    private static final int KEYCODE_SHUTTER = 27;
    private static final int KEYCODE_SHUTTER_ALT = 170;
    private static final long CAPTURE_TIMEOUT_MS = 12000L;

    /** 回调可能在相机工作线程执行；CV/UI 调用统一回到相机主线程。 */
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Map<Object, CaptureState> STATES =
            new WeakHashMap<Object, CaptureState>();
    private static final Set<String> HOOKED_METHODS = new HashSet<String>();
    private static CaptureState sActiveState;

    private static volatile Field sCameraActionField;
    private static volatile Method sShutterButtonClick;
    private static volatile Method sShutterButtonCancel;
    private static volatile Method sShutterButtonLongClickCancel;
    private static volatile Method sStartNormalCapture;
    private static volatile Method sDirectShutterButtonClick;
    private static volatile ClassLoader sClassLoader;
    private static volatile long sCameraVersionCode;
    private static volatile boolean sPrometheusProfile;
    private static volatile boolean sReady;

    private DualStyleCaptureHooks() {
    }

    /** 只在版本、模式与已确认入口均满足能力表时安装。 */
    public static void install(final ClassLoader classLoader, final long cameraVersionCode) {
        final boolean prometheusProfile = HandleFixModule.promFix();
        if (!CameraCapabilityRegistry.supportsDualStyleCapture(cameraVersionCode,
                prometheusProfile, 163)) {
            return;
        }
        HandleFixModule.install("dual-style-capture", new Runnable() {
            @Override
            public void run() {
                installAdapter(classLoader, cameraVersionCode, prometheusProfile);
            }
        });
    }

    private static void installAdapter(final ClassLoader classLoader,
            final long cameraVersionCode, final boolean prometheusProfile) {
        final Class<?> camera2Module;
        try {
            camera2Module = XposedHelpers.findClass(CAMERA2_MODULE_CLASS, classLoader);
        } catch (Throwable t) {
            HandleFixModule.log("dual-style: Camera2Module missing", t);
            return;
        }

        int keyDown = hookExact(camera2Module, "onKeyDown",
                new Signature() {
                    @Override
                    public boolean matches(Method method) {
                        return method.getReturnType() == boolean.class
                                && parameters(method, int.class, KeyEvent.class);
                    }
                }, new KeyDownHook(classLoader));
        int keyUp = hookExact(camera2Module, "onKeyUp",
                new Signature() {
                    @Override
                    public boolean matches(Method method) {
                        return method.getReturnType() == boolean.class
                                && parameters(method, int.class, KeyEvent.class);
                    }
                }, new KeyUpHook(classLoader));
        int perform = hookExact(camera2Module, "performKeyClicked",
                new Signature() {
                    @Override
                    public boolean matches(Method method) {
                        if (method.getReturnType() != void.class) return false;
                        return parameters(method, int.class, String.class,
                                KeyEvent.class, boolean.class)
                                || parameters(method, int.class, String.class,
                                int.class, boolean.class);
                    }
                }, new PerformKeyHook(classLoader));
        int start = hookExact(camera2Module, "startNormalCapture",
                new Signature() {
                    @Override
                    public boolean matches(Method method) {
                        return method.getReturnType() == boolean.class
                                && parameters(method, int.class);
                    }
                }, new StartCaptureHook(classLoader));
        int finished = hookExact(camera2Module, "onPictureTakenFinished",
                new Signature() {
                    @Override
                    public boolean matches(Method method) {
                        return method.getReturnType() == void.class
                                && parameters(method, boolean.class, long.class, int.class);
                    }
                }, new PictureFinishedHook(classLoader));
        int firstFrame = hookExact(camera2Module, "notifyFirstFrameArrived",
                new Signature() {
                    @Override
                    public boolean matches(Method method) {
                        return method.getReturnType() == void.class
                                && (parameters(method, int.class) || parameters(method));
                    }
                }, new FirstFrameHook(classLoader));

        Field cameraActionField = findField(camera2Module, "mCameraAction");
        Method shutterButtonClick = cameraActionField == null ? null
                : findExact(cameraActionField.getType(), "onShutterButtonClick",
                        boolean.class, int.class);
        Method shutterButtonCancel = cameraActionField == null ? null
                : findExact(cameraActionField.getType(), "onShutterButtonCancel",
                        void.class, boolean.class);
        Method shutterButtonLongClickCancel = cameraActionField == null ? null
                : findExact(cameraActionField.getType(), "onShutterButtonLongClickCancel",
                        void.class, boolean.class);
        Method startNormalCapture = findExact(camera2Module, "startNormalCapture",
                boolean.class, int.class);
        Method directShutterButtonClick = cameraActionField == null
                ? findExact(camera2Module, "onShutterButtonClick", boolean.class, int.class)
                : null;
        sCameraActionField = cameraActionField;
        sShutterButtonClick = shutterButtonClick;
        sShutterButtonCancel = shutterButtonCancel;
        sShutterButtonLongClickCancel = shutterButtonLongClickCancel;
        sStartNormalCapture = startNormalCapture;
        sDirectShutterButtonClick = directShutterButtonClick;
        sClassLoader = classLoader;
        sCameraVersionCode = cameraVersionCode;
        sPrometheusProfile = prometheusProfile;
        boolean needsDirectCancel = CameraBuilds.isCamera68Port(cameraVersionCode)
                || (!prometheusProfile
                && cameraVersionCode == CameraBuilds.OFFICIAL_OS4_6_6_510_VERSION_CODE);
        sReady = keyDown > 0 && keyUp > 0 && perform > 0 && start > 0 && finished > 0
                && firstFrame > 0
                && ((cameraActionField != null && shutterButtonClick != null)
                || directShutterButtonClick != null)
                && (!needsDirectCancel || shutterButtonCancel != null);
        HandleFixModule.log("dual-style: adapter ready=" + sReady
                + " keyDown=" + keyDown + " keyUp=" + keyUp
                + " perform=" + perform + " start=" + start
                + " finished=" + finished + " firstFrame=" + firstFrame
                + " cameraAction=" + (cameraActionField != null)
                + " shutterClick=" + (shutterButtonClick != null)
                + " shutterCancel=" + (shutterButtonCancel != null)
                + " shutterLongCancel=" + (shutterButtonLongClickCancel != null)
                + " directSingle=" + (startNormalCapture != null)
                + " directShutterClick=" + (directShutterButtonClick != null)
                + " profile=" + (CameraBuilds.isPhoenixOs4Family(cameraVersionCode)
                ? "phoenix" : (prometheusProfile ? "prometheus" : "official"))
                + " versionCode=" + cameraVersionCode);
    }

    /** 初始 DOWN 只登记状态；真正的第一张仍由相机原生代码拍摄。 */
    private static final class KeyDownHook extends XC_MethodHook {
        private final ClassLoader classLoader;

        KeyDownHook(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            KeyEvent event = event(param.args);
            if (!isShutter(event) || !sReady || event.isCanceled()) {
                return;
            }
            int mode = currentMode(param.thisObject, classLoader);
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return;
            }
            boolean supported = CameraCapabilityRegistry.supportsDualStyleCapture(
                    sCameraVersionCode, sPrometheusProfile, mode);
            if (event.getRepeatCount() == 0) {
                // 每次物理快门只记一行。若专业模式仍无 armed 日志，可直接
                // 区分“按键未进入 Camera2Module”与“模式/配置/能力门未通过”，无需抓整份日志。
                HandleFixModule.log("dual-style: key down class="
                        + param.thisObject.getClass().getName() + " mode=" + mode
                        + " action=" + ModeConfig.getFuncLong(ModeConfig.KEY_SHUTTER, mode)
                        + " supported=" + supported + " keyCode=" + event.getKeyCode());
            }
            if (!supported) return;
            if (event.getRepeatCount() == 0) {
                begin(param.thisObject, classLoader, mode, event);
            } else {
                qualifyIfLong(param.thisObject, classLoader, event);
            }
        }
    }

    /**
     * 真实松键只负责最终收尾和无 repeat 设备的兼容回退。
     *
     * <p>通常路径会在 repeat 达到阈值时，把当次原生 performKeyClicked 的
     * {@code pressed} 参数改为 false，让目标相机自己清理 button status/downTime，
     * 因而可以在用户仍按住快门时切换并开始第二拍。真实 ACTION_UP 仍然放行，避免
     * 改写设备原生按键生命周期；如果设备不产生 repeat，它也能作为后备释放门。</p>
     */
    private static final class KeyUpHook extends XC_MethodHook {
        private final ClassLoader classLoader;

        KeyUpHook(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            KeyEvent event = event(param.args);
            if (!isShutter(event) || !sReady
                    || event.getAction() != KeyEvent.ACTION_UP) {
                return;
            }
            CaptureState state = stateFor(param.thisObject, event);
            if (state == null) {
                return;
            }
            if (event.isCanceled()) {
                boolean secondInFlight;
                synchronized (state) {
                    state.keyReleased = true;
                    secondInFlight = state.secondRequested;
                }
                if (!secondInFlight) {
                    finishAfterSecond(param.thisObject, classLoader, state, "physical shutter canceled");
                }
                return;
            }
            long duration = duration(event);
            if (duration < ModeConfig.getShutterLongPressMs()) {
                // 短按保持完全原生；若相机回调稍晚到达也不能被下一次按键误认作 C3。
                removeState(param.thisObject, state);
                return;
            }
            synchronized (state) {
                state.keyReleased = true;
            }
            qualifyIfLong(param.thisObject, classLoader, event);
            if (isCamera68Street(state) && state.firstStarted && state.longQualified
                    && !state.nativeReleaseCompleted) {
                // A first shot can finish before the first hardware repeat. Release on the
                // physical UP as well so this path uses the same StreetModule cleanup.
                if (!releaseCamera68Street(param.thisObject)) {
                    removeState(param.thisObject, state);
                    return;
                }
                synchronized (state) {
                    state.nativeReleaseCompleted = true;
                }
            }
            if (shouldConsumeStreetPhysicalRelease(state)) {
                // C3 在阈值处已经调用过普通释放和 StreetModule 长按清理。真实 UP
                // 再进入街拍 CameraAction 会重复执行释放模式分发，因此由 C3 消费。
                param.setResult(Boolean.TRUE);
                if (state.phase == Phase.WAITING_RELEASE) {
                    removeState(param.thisObject, state);
                    HandleFixModule.log("dual-style: street physical release consumed;"
                            + " transaction finished");
                    return;
                }
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (param.hasThrowable()) return;
            KeyEvent event = event(param.args);
            if (!isShutter(event) || !sReady || event.isCanceled()
                    || event.getAction() != KeyEvent.ACTION_UP) {
                return;
            }
            CaptureState state = stateFor(param.thisObject, event);
            if (state == null) {
                return;
            }
            synchronized (state) {
                if (!state.keyReleased || !state.longQualified) {
                    return;
                }
                // 产生 repeat 的正常路径早已完成逻辑释放；没有 repeat 的设备则在
                // 此处确认真实 ACTION_UP 已经走完原生清理链。
                state.nativeReleaseCompleted = true;
            }
            maybeStartSecond(param.thisObject, classLoader, state);
        }
    }

    /** 记录目标相机原生解析出的拍摄 triggerMode，并拦截其 repeat 长按分发。 */
    private static final class PerformKeyHook extends XC_MethodHook {
        private final ClassLoader classLoader;

        PerformKeyHook(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            KeyEvent event = event(param.args);
            if (!sReady) {
                return;
            }
            int repeatCount;
            if (event != null) {
                if (!isShutter(event) || event.getAction() != KeyEvent.ACTION_DOWN) return;
                repeatCount = event.getRepeatCount();
            } else if (param.args != null && param.args.length >= 4
                    && param.args[2] instanceof Integer) {
                // V5.1 旧链不传 KeyEvent，只传 repeatCount；KeyDownHook 已完成物理键校验。
                repeatCount = ((Integer) param.args[2]).intValue();
            } else {
                return;
            }
            CaptureState state = event == null
                    ? stateFor(param.thisObject) : stateFor(param.thisObject, event);
            if (state == null) return;
            if (repeatCount == 0) {
                if (Boolean.TRUE.equals(param.args[3])
                        && param.args[0] instanceof Integer) {
                    synchronized (state) {
                        state.triggerMode = ((Integer) param.args[0]).intValue();
                    }
                    HandleFixModule.debug("dual-style: native triggerMode="
                            + state.triggerMode + " mode=" + state.mode);
                }
                return;
            }
            if (event != null) {
                if (CameraBuilds.isCamera68Port(sCameraVersionCode)
                        && duration(event) < ModeConfig.getShutterLongPressMs()) {
                    // 已选双风格的手势要等到配置阈值，避免原生 repeat 提前触发连拍。
                    param.setResult(null);
                    return;
                }
                qualifyIfLong(param.thisObject, classLoader, event);
            }
            boolean directCancel = false;
            synchronized (state) {
                if (state.longQualified && state.firstStarted
                        && state.triggerMode > 0 && !state.nativeReleaseCompleted
                        && !state.logicalReleaseInFlight) {
                    state.logicalReleaseInFlight = true;
                    if (usesDirectCancelRelease()) {
                        // 6.6.510 和 1770 的 pressed=false 可能重新进入拍摄。在这些版本
                        // 用 ImageActionImpl.onShutterButtonCancel(true) 释放按钮状态。
                        directCancel = true;
                    } else {
                        param.args[3] = Boolean.FALSE;
                        HandleFixModule.log("dual-style: native logical release requested"
                                + " duration=" + stateDuration(state) + "ms mode=" + state.mode);
                    }
                } else if (state.suppressNative) {
                    // 逻辑释放后阻止同一次按压的后续 repeat。
                    param.setResult(null);
                }
            }
            if (directCancel) {
                boolean camera68Street = isCamera68Street(state);
                boolean released = camera68Street ? releaseCamera68Street(param.thisObject)
                        : invokeNativeShutterCancel(param.thisObject);
                if (released && !camera68Street && needsStreetLongPressCleanup(state)) {
                    // 官方 6.6.510 的 StreetModule 额外维护 mMultiCap 长按状态。
                    // onShutterButtonCancel(true) 只结束焦点/按钮按下态，并不会调用
                    // StreetModule.CameraActionImpl.onShutterButtonLongClickCancel(boolean)
                    // 中的 mMultiCap.b()。若不在 C3 阈值处同步清理，切换 CV 后
                    // 第二张会继承原生高速连拍。这里必须传 false；传 true 会让
                    // 原生实现再调用 onShutterButtonClick(10)，反而额外补拍一张。
                    invokeNativeShutterLongClickCancel(param.thisObject);
                }
                synchronized (state) {
                    state.logicalReleaseInFlight = !released;
                    state.nativeReleaseCompleted = released;
                }
                if (released) {
                    param.setResult(null);
                    HandleFixModule.log("dual-style: native cancel release completed mode="
                            + state.mode);
                    maybeStartSecond(param.thisObject, classLoader, state);
                } else if (!CameraBuilds.isCamera68Port(sCameraVersionCode)) {
                    // 运行时入口意外失败时保留旧的可回退释放链，不让快门卡在按下态。
                    param.args[3] = Boolean.FALSE;
                    HandleFixModule.log("dual-style: native cancel release failed;"
                            + " fallback to pressed=false mode=" + state.mode);
                } else {
                    // 1770 的 pressed=false 分支可能主动处理一次快门，不能用来替代释放。
                    removeState(param.thisObject, state);
                }
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            KeyEvent event = event(param.args);
            CaptureState state = event == null
                    ? stateFor(param.thisObject) : stateFor(param.thisObject, event);
            if (state == null) {
                return;
            }
            boolean released = false;
            synchronized (state) {
                if (state.logicalReleaseInFlight) {
                    state.logicalReleaseInFlight = false;
                    state.nativeReleaseCompleted = true;
                    released = true;
                }
            }
            if (released) {
                HandleFixModule.log("dual-style: native logical release completed mode="
                        + state.mode);
                maybeStartSecond(param.thisObject, classLoader, state);
            }
        }
    }

    /** 标记原生首拍/第二拍是否真正进入 startNormalCapture。 */
    private static final class StartCaptureHook extends XC_MethodHook {
        private static final String CLAIMED_CAPTURE = "com.handlefix.dualStyle.claimedCapture";
        private final ClassLoader classLoader;

        StartCaptureHook(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            CaptureState state = stateFor(param.thisObject);
            if (state == null) {
                return;
            }
            synchronized (state) {
                if (state.phase == Phase.WAITING_FIRST) {
                    state.firstStarted = true;
                    state.phase = Phase.CAPTURING_FIRST;
                    param.setObjectExtra(CLAIMED_CAPTURE, state);
                    HandleFixModule.log("dual-style: first capture started mode="
                            + state.mode);
                } else if (state.phase == Phase.SWITCHING && state.secondRequested
                        && !state.secondStarted) {
                    // 第二拍只有真正穿过相机原生 onShutterButtonClick/对焦门控并进入
                    // startNormalCapture 后才算启动；不能把“请求已接受”误当成已拍摄。
                    state.secondStarted = true;
                    state.phase = Phase.CAPTURING_SECOND;
                    param.setObjectExtra(CLAIMED_CAPTURE, state);
                    HandleFixModule.log("dual-style: second capture started trigger="
                            + state.triggerMode + " mode=" + state.mode);
                }
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            CaptureState state = (CaptureState) param.getObjectExtra(CLAIMED_CAPTURE);
            if (state == null || !isCurrent(param.thisObject, state)
                    || !(param.getResult() instanceof Boolean)) {
                return;
            }
            boolean accepted = ((Boolean) param.getResult()).booleanValue();
            if (accepted) {
                return;
            }
            boolean second;
            synchronized (state) {
                second = state.secondRequested || state.secondStarted;
                if (!second) {
                    state.firstFailed = true;
                }
            }
            if (second) {
                finishAfterSecond(param.thisObject, classLoader, state,
                        "startNormalCapture returned false");
            } else {
                removeState(param.thisObject, state);
                HandleFixModule.log("dual-style: first capture rejected; keep native behavior");
            }
        }
    }

    /** 真实 JPEG/相册完成点；只有它能推进第二拍或恢复状态。 */
    private static final class PictureFinishedHook extends XC_MethodHook {
        private final ClassLoader classLoader;

        PictureFinishedHook(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            CaptureState state = stateFor(param.thisObject);
            if (state == null) {
                return;
            }
            boolean success = param.args != null && param.args.length > 0
                    && Boolean.TRUE.equals(param.args[0]);
            boolean second;
            synchronized (state) {
                second = state.secondStarted;
                if (!second) {
                    if (!state.firstStarted || state.firstCompleted || state.firstFailed) return;
                    state.firstCompleted = success;
                    state.firstFailed = !success;
                }
            }
            HandleFixModule.log("dual-style: picture finished phase="
                    + (second ? "second" : "first") + " success=" + success
                    + " mode=" + state.mode);
            if (!success) {
                if (second) {
                    finishAfterSecond(param.thisObject, classLoader, state,
                            "second capture failed");
                } else {
                    removeState(param.thisObject, state);
                }
                return;
            }
            if (second) {
                finishAfterSecond(param.thisObject, classLoader, state,
                        "second capture completed");
            } else {
                maybeStartSecond(param.thisObject, classLoader, state);
            }
        }
    }

    /**
     * CV 切换会令目标相机重新配置并可能重建 Camera2Module。只有新预览首帧到达后，
     * 原生快门动作入口才重新可用；在 onInactive/startPreview
     * 之间调用它只会返回 false。该回调是第二拍的原生就绪门，不是经验延时。
     */
    private static final class FirstFrameHook extends XC_MethodHook {
        private final ClassLoader classLoader;

        FirstFrameHook(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            CaptureState state = stateFor(param.thisObject);
            if (state == null) {
                // CV 切换可能重建 Camera2Module。把新的、同模式的首帧来源绑定到
                // 当前唯一事务，后续第二拍必须使用新实例的原生快门动作入口。
                CaptureState active = activeState();
                if (active != null && active.phase == Phase.WAITING_SECOND_PREVIEW
                        && currentMode(param.thisObject, classLoader) == active.mode) {
                    bindModule(param.thisObject, active);
                    state = active;
                    HandleFixModule.log("dual-style: rebound rebuilt Camera2Module mode="
                            + active.mode);
                }
            }
            if (state == null) {
                return;
            }
            final CaptureState readyState = state;
            synchronized (readyState) {
                if (readyState.phase != Phase.WAITING_SECOND_PREVIEW
                        || !readyState.awaitingSecondPreview || readyState.secondRequested) {
                    return;
                }
                readyState.awaitingSecondPreview = false;
            }
            HandleFixModule.log("dual-style: switched preview ready mode=" + readyState.mode
                    + " trigger=" + readyState.triggerMode);
            final Object module = param.thisObject;
            // 让原生首帧回调完整返回后再发起快门，避免在预览就绪通知内部重入拍摄。
            MAIN_HANDLER.post(new Runnable() {
                @Override
                public void run() {
                    requestSecondCapture(module, classLoader, readyState);
                }
            });
        }
    }

    private enum Phase {
        WAITING_FIRST,
        CAPTURING_FIRST,
        SWITCHING,
        WAITING_SECOND_PREVIEW,
        CAPTURING_SECOND,
        WAITING_RELEASE,
        RESTORING
    }

    /** 一次物理按键的状态；弱键避免持有 Camera2Module 生命周期。 */
    private static final class CaptureState {
        final int mode;
        final int deviceId;
        final long downTime;
        final LeicaStateAdapter.State initialState;
        Phase phase = Phase.WAITING_FIRST;
        int triggerMode = -1;
        boolean firstStarted;
        boolean firstCompleted;
        boolean firstFailed;
        boolean longQualified;
        boolean keyReleased;
        boolean suppressNative;
        boolean logicalReleaseInFlight;
        boolean nativeReleaseCompleted;
        boolean switchScheduled;
        boolean awaitingSecondPreview;
        boolean secondRequested;
        boolean secondStarted;
        boolean cvChangedByTransaction;

        CaptureState(int mode, KeyEvent event, LeicaStateAdapter.State initialState) {
            this.mode = mode;
            this.deviceId = event.getDeviceId();
            this.downTime = event.getDownTime();
            this.initialState = initialState;
        }

        boolean matches(KeyEvent event) {
            return event.getDeviceId() == deviceId && event.getDownTime() == downTime;
        }
    }

    private static void begin(Object module, ClassLoader classLoader, int mode,
            KeyEvent event) {
        closeStaleWaitingRelease(event);
        String action = ModeConfig.getFuncLong(ModeConfig.KEY_SHUTTER, mode);
        if (!ModeConfig.F_DUAL_STYLE_CAPTURE.equals(action)) {
            return;
        }
        if (mode == ModeConfig.MODE_STREET && CameraBuilds.isCamera68Port(sCameraVersionCode)
                && sShutterButtonLongClickCancel == null) return;
        LeicaStateAdapter.State initial = LeicaStateAdapter.read(classLoader, mode);
        if (initial == LeicaStateAdapter.State.UNKNOWN) {
            // 第一张仍由原生相机完成，但不猜测 CV 状态，避免第二张风格或恢复方向错误。
            HandleFixModule.log("dual-style: CV state unknown; keep native single capture");
            return;
        }
        CaptureState state = new CaptureState(mode, event, initial);
        synchronized (STATES) {
            if (sActiveState != null) {
                // 上一次状态尚未由真实完成/超时回调收口时，不把新按键串入旧状态机。
                HandleFixModule.log("dual-style: previous transaction still active;"
                        + " keep new shutter native");
                return;
            }
            STATES.put(module, state);
            sActiveState = state;
        }
        // 这是异常保护超时，不是第二拍延时。正常路径完全由真实完成回调驱动。
        MAIN_HANDLER.postDelayed(new Runnable() {
            @Override
            public void run() {
                expire(module, state);
            }
        }, CAPTURE_TIMEOUT_MS);
        HandleFixModule.log("dual-style: armed mode=" + mode + " initial=" + initial
                + " downTime=" + event.getDownTime());
    }

    /**
     * 官方街拍的 ACTION_UP 可能由 StreetModule 自己的按键链处理，无法到达我们
     * 挂载在 Camera2Module 的 onKeyUp。下一次 repeatCount=0 的 DOWN 必然来自新的
     * 物理按键周期，因此它是比固定超时更准确的收口信号：旧事务已经恢复 CV，
     * 这里只清除 WAITING_RELEASE 状态，不再次调用相机拍摄或释放入口。
     */
    private static void closeStaleWaitingRelease(KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN
                || event.getRepeatCount() != 0) {
            return;
        }
        synchronized (STATES) {
            CaptureState previous = sActiveState;
            if (previous == null) {
                return;
            }
            boolean waiting;
            synchronized (previous) {
                waiting = previous.phase == Phase.WAITING_RELEASE;
            }
            if (waiting && event.getDownTime() > previous.downTime) {
                removeStateLocked(previous);
                HandleFixModule.log("dual-style: new physical down closed stale"
                        + " WAITING_RELEASE mode=" + previous.mode);
            }
        }
    }

    private static void qualifyIfLong(Object module, ClassLoader classLoader, KeyEvent event) {
        CaptureState state = stateFor(module, event);
        if (state == null || duration(event) < ModeConfig.getShutterLongPressMs()) {
            return;
        }
        boolean start;
        synchronized (state) {
            if (state.longQualified || state.firstFailed
                    || state.initialState == LeicaStateAdapter.State.UNKNOWN) {
                start = false;
            } else {
                state.longQualified = true;
                // 达到阈值后，PerformKeyHook 会把第一条 repeat 转换为目标相机自己的
                // 逻辑释放；随后的 repeat 全部阻止，真实 ACTION_UP 仍保持放行。
                state.suppressNative = state.firstStarted;
                start = true;
            }
        }
        if (start) {
            HandleFixModule.log("dual-style: long threshold reached duration="
                    + duration(event) + "ms mode=" + state.mode);
            maybeStartSecond(module, classLoader, state);
        }
    }

    private static void maybeStartSecond(final Object module, final ClassLoader classLoader,
            final CaptureState state) {
        synchronized (state) {
            if (!state.longQualified || !state.firstCompleted || state.firstFailed
                    || !state.nativeReleaseCompleted
                    || state.secondRequested || state.secondStarted || state.switchScheduled) {
                return;
            }
            state.switchScheduled = true;
            state.phase = Phase.SWITCHING;
        }
        // 不使用固定等待；post 让原生逻辑释放/真实 ACTION_UP 先完整返回，随后在
        // 相机主线程切换 CV。实际第二拍仍由预览首帧和拍摄完成回调确认。
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                switchAndCapture(module, classLoader, state);
            }
        });
    }

    private static void switchAndCapture(Object module, ClassLoader classLoader,
            CaptureState state) {
        if (!isCurrent(module, state)) {
            return;
        }
        synchronized (state) {
            if (state.phase != Phase.SWITCHING) return;
        }
        if (state.triggerMode <= 0) {
            finishWithoutCapture(module, classLoader, state,
                    "native trigger mode missing");
            return;
        }
        LeicaStateAdapter.State target = opposite(state.initialState);
        LeicaStateAdapter.State current = LeicaStateAdapter.read(classLoader, state.mode);
        if (current != state.initialState) {
            finishWithoutCapture(module, classLoader, state,
                    "CV changed before second capture");
            return;
        }
        boolean toggled = toggleCvType(classLoader, state.mode);
        LeicaStateAdapter.State after = LeicaStateAdapter.read(classLoader, state.mode);
        synchronized (state) {
            state.cvChangedByTransaction = after == target;
        }
        if (!toggled) {
            finishWithoutCapture(module, classLoader, state, "CV switch rejected");
            return;
        }
        if (after != target) {
            finishWithoutCapture(module, classLoader, state,
                    "CV switch readback mismatch target=" + target + " actual=" + after);
            return;
        }
        synchronized (state) {
            if (state.phase != Phase.SWITCHING) return;
            state.awaitingSecondPreview = true;
            state.phase = Phase.WAITING_SECOND_PREVIEW;
        }
        HandleFixModule.log("dual-style: CV switched; waiting for new preview target="
                + target + " trigger=" + state.triggerMode);
    }

    private static void requestSecondCapture(Object module, ClassLoader classLoader,
            CaptureState state) {
        if (!isCurrent(module, state)) {
            return;
        }
        LeicaStateAdapter.State target = opposite(state.initialState);
        synchronized (state) {
            if (state.phase != Phase.WAITING_SECOND_PREVIEW || state.secondRequested
                    || state.secondStarted) {
                return;
            }
            state.secondRequested = true;
            state.phase = Phase.SWITCHING;
        }
        boolean accepted = needsDirectStreetSingleCapture(state)
                ? invokeStartNormalCapture(module, state.triggerMode)
                : invokeNativeShutterClick(module, state.triggerMode);
        HandleFixModule.log("dual-style: second capture requested target=" + target
                + " trigger=" + state.triggerMode + " accepted=" + accepted);
        if (!accepted) {
            finishWithoutCapture(module, classLoader, state,
                    "native shutter action rejected");
        }
    }

    private static void finishWithoutCapture(Object module, ClassLoader classLoader,
            CaptureState state, String reason) {
        HandleFixModule.log("dual-style: no second capture; " + reason);
        synchronized (state) {
            state.awaitingSecondPreview = false;
            state.secondRequested = false;
            state.secondStarted = false;
        }
        restoreAndFinish(module, classLoader, state, false);
    }

    private static void finishAfterSecond(final Object module, final ClassLoader classLoader,
            final CaptureState state, final String reason) {
        synchronized (state) {
            if (state.phase == Phase.RESTORING) {
                return;
            }
            state.phase = Phase.RESTORING;
        }
        HandleFixModule.log("dual-style: restoring initial CV reason=" + reason);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            restoreAndFinish(module, classLoader, state, false);
        } else {
            MAIN_HANDLER.post(new Runnable() {
                @Override
                public void run() {
                    restoreAndFinish(module, classLoader, state, false);
                }
            });
        }
    }

    /**
     * 恢复长按前的 CV 并收口事务。正常完成时官方街拍需要等真实 ACTION_UP
     * 再移除状态，避免相机自己的释放分支重复执行；forceFinish 只由异常超时
     * 使用，防止缺失 ACTION_UP 时把全局事务永久卡在 WAITING_RELEASE。
     */
    private static void restoreAndFinish(Object module, ClassLoader classLoader,
            CaptureState state, boolean forceFinish) {
        if (!isCurrent(module, state)) {
            return;
        }
        boolean restoreCv;
        synchronized (state) {
            state.phase = Phase.RESTORING;
            restoreCv = state.cvChangedByTransaction;
        }
        LeicaStateAdapter.State current = restoreCv
                ? LeicaStateAdapter.read(classLoader, state.mode) : state.initialState;
        if (current == LeicaStateAdapter.State.UNKNOWN) {
            // UNKNOWN 不能通过“再切一次”猜恢复方向；那可能把相机留在另一种 CV。
            // 这里仅清理状态并记录诊断，让用户仍可用相机原生切换恢复，而不是叠加
            // 一次没有读回依据的 toggle。
            HandleFixModule.log("dual-style: restore skipped; current CV unknown");
        } else if (current != state.initialState) {
            boolean toggled = toggleCvType(classLoader, state.mode);
            LeicaStateAdapter.State restored = LeicaStateAdapter.read(classLoader, state.mode);
            if (!toggled || restored != state.initialState) {
                HandleFixModule.log("dual-style: restore readback failed expected="
                        + state.initialState + " actual=" + restored);
            }
        }
        if (!forceFinish && shouldWaitForStreetPhysicalRelease(state)) {
            synchronized (state) {
                state.phase = Phase.WAITING_RELEASE;
            }
            HandleFixModule.log("dual-style: captures finished;"
                    + " waiting for street physical release initial=" + state.initialState);
        } else {
            removeState(module, state);
            HandleFixModule.log("dual-style: finished initial=" + state.initialState
                    + (forceFinish ? " (timeout force-finish)" : ""));
        }
    }

    private static void expire(Object module, CaptureState state) {
        if (!isCurrent(module, state)) {
            return;
        }
        synchronized (state) {
            // 正常恢复任务可能已经把阶段标记为 RESTORING，但还排在主线程队列中。
            // 超时不能因这个标记直接返回，否则恢复任务若随后进入 WAITING_RELEASE，
            // 就再也没有清理机会；下面的 forceFinish 会在同一主线程上安全收口。
            state.phase = Phase.RESTORING;
        }
        // 物理 ACTION_UP 可能因相机重建、系统取消或设备驱动差异没有回到
        // Camera2Module。超时必须是最后一道收口，而不能再次进入
        // WAITING_RELEASE；否则 sActiveState 永久占用，后续任何模式都会被
        // “previous transaction still active” 拒绝。这里仍先按真实 CV 状态恢复，
        // 再无条件移除事务；这不是延时拍摄，也不会伪造按键事件。
        HandleFixModule.log("dual-style: timeout; restoring/force-finishing state");
        restoreAndFinish(module, sClassLoader, state, true);
    }

    private static boolean invokeNativeShutterClick(Object module, int triggerMode) {
        Field field = sCameraActionField;
        Method method = sShutterButtonClick;
        Method directMethod = sDirectShutterButtonClick;
        if (module == null || triggerMode <= 0) {
            return false;
        }
        try {
            Object result;
            if (field != null && method != null) {
                Object cameraAction = field.get(module);
                if (cameraAction == null) return false;
                result = method.invoke(cameraAction, Integer.valueOf(triggerMode));
            } else if (directMethod != null) {
                // V5.1 的 Camera2Module 自身实现 CameraAction，没有 mCameraAction 字段。
                result = directMethod.invoke(module, Integer.valueOf(triggerMode));
            } else {
                return false;
            }
            return result instanceof Boolean && ((Boolean) result).booleanValue();
        } catch (Throwable t) {
            HandleFixModule.log("dual-style: native shutter invocation failed", t);
            return false;
        }
    }

    /**
     * 官方 6.6.510 的街拍 CameraAction 会根据“快门释放模式”把 click(170)
     * 分发为单张或高速连拍。C3 的第二张语义固定为单张，因此必须直接进入
     * Camera2Module.startNormalCapture(int)；该方法仍经过相机自身拍摄管线和真实
     * 完成回调，并不是伪造快门或按数量截断连拍。
     */
    private static boolean invokeStartNormalCapture(Object module, int triggerMode) {
        Method method = sStartNormalCapture;
        if (module == null || triggerMode <= 0 || method == null) return false;
        try {
            Object result = method.invoke(module, Integer.valueOf(triggerMode));
            return result instanceof Boolean && ((Boolean) result).booleanValue();
        } catch (Throwable t) {
            HandleFixModule.log("dual-style: direct single capture invocation failed", t);
            return false;
        }
    }

    private static boolean usesDirectCancelRelease() {
        return (CameraBuilds.isCamera68Port(sCameraVersionCode)
                || (!sPrometheusProfile
                && sCameraVersionCode == CameraBuilds.OFFICIAL_OS4_6_6_510_VERSION_CODE))
                && sCameraActionField != null && sShutterButtonCancel != null;
    }

    /**
     * 只调用目标相机自身的按键释放入口；不构造 KeyEvent，也不触发拍照分支。
     */
    private static boolean invokeNativeShutterCancel(Object module) {
        Field field = sCameraActionField;
        Method method = sShutterButtonCancel;
        if (module == null || field == null || method == null) return false;
        try {
            Object cameraAction = field.get(module);
            if (cameraAction == null) return false;
            method.invoke(cameraAction, Boolean.TRUE);
            return true;
        } catch (Throwable t) {
            HandleFixModule.log("dual-style: native shutter cancel invocation failed", t);
            return false;
        }
    }

    private static boolean needsStreetLongPressCleanup(CaptureState state) {
        return state != null && state.mode == ModeConfig.MODE_STREET
                && (isCamera68Street(state) || (!sPrometheusProfile
                && sCameraVersionCode == CameraBuilds.OFFICIAL_OS4_6_6_510_VERSION_CODE))
                && sCameraActionField != null && sShutterButtonLongClickCancel != null;
    }

    private static boolean isCamera68Street(CaptureState state) {
        return state != null && state.mode == ModeConfig.MODE_STREET
                && CameraBuilds.isCamera68Port(sCameraVersionCode);
    }

    private static boolean releaseCamera68Street(Object module) {
        // 1770's StreetModule$a calls mMultiCap.b() with false, without the click(10)
        // branch. The base cancel separately closes the capture button status.
        return invokeNativeShutterCancel(module) && invokeNativeShutterLongClickCancel(module);
    }

    private static boolean needsDirectStreetSingleCapture(CaptureState state) {
        // 1770's StreetModule$a inherits the normal click and its focus/camera guards.
        return !isCamera68Street(state) && needsStreetLongPressCleanup(state)
                && sStartNormalCapture != null;
    }

    private static boolean shouldConsumeStreetPhysicalRelease(CaptureState state) {
        return needsStreetLongPressCleanup(state) && state.longQualified
                && state.nativeReleaseCompleted;
    }

    private static boolean shouldWaitForStreetPhysicalRelease(CaptureState state) {
        return shouldConsumeStreetPhysicalRelease(state) && !state.keyReleased;
    }

    /**
     * 结束官方 6.6.510 街拍模块自身的长按/多拍状态。普通 CameraAction 释放入口与
     * StreetModule 的 mMultiCap 清理职责不同，不能用其中一个替代另一个。
     */
    private static boolean invokeNativeShutterLongClickCancel(Object module) {
        Field field = sCameraActionField;
        Method method = sShutterButtonLongClickCancel;
        if (module == null || field == null || method == null) return false;
        try {
            Object cameraAction = field.get(module);
            if (cameraAction == null) return false;
            method.invoke(cameraAction, Boolean.FALSE);
            HandleFixModule.log("dual-style: street long-click state cleared");
            return true;
        } catch (Throwable t) {
            // 普通逻辑释放已经完成；该附加清理失败时只记录，不回退到
            // performKeyClicked(pressed=false)，避免重新进入拍照/连拍分支。
            HandleFixModule.log("dual-style: street long-click cleanup failed", t);
            return false;
        }
    }

    /**
     * C3 与“按住快门高速连拍”共用同一组 DOWN/repeat/UP，无法在阈值到达后再撤销
     * 已经开始的连拍。事务一旦在首个 DOWN 建立，所有单张/连拍决策点都必须让 C3
     * 独占这次手势，先走一张原生单拍，再由真实完成回调推进第二种 CV；否则会出现
     * 街拍连拍抢占首拍、C3 等不到 startNormalCapture 并最终超时。
     */
    public static boolean ownsShutterGesture(int mode) {
        CaptureState state = activeState();
        // 事务只有 removeState 后才真正结束。第二张完成到 CV 恢复之间仍可能收到
        // 50 ms 一次的硬件 repeat；若在 RESTORING 提前放弃所有权，街拍恰好会在
        // 这个窗口重新建立原生连拍。因此恢复阶段和等待物理 UP 阶段都继续独占。
        return state != null && state.mode == mode;
    }

    public static boolean ownsShutterGesture(int mode, KeyEvent event) {
        CaptureState state = activeState();
        return state != null && state.mode == mode && state.matches(event);
    }

    private static CaptureState stateFor(Object module) {
        synchronized (STATES) {
            return STATES.get(module);
        }
    }

    private static CaptureState stateFor(Object module, KeyEvent event) {
        CaptureState state = stateFor(module);
        return state != null && state.matches(event) ? state : null;
    }

    private static boolean isCurrent(Object module, CaptureState state) {
        return state != null && state == activeState() && state == stateFor(module);
    }

    private static void removeState(Object module, CaptureState state) {
        synchronized (STATES) {
            removeStateLocked(state);
        }
    }

    /** 调用方已经持有 STATES 锁时使用，避免重复实现弱映射和全局状态清理。 */
    private static void removeStateLocked(CaptureState state) {
        Iterator<Map.Entry<Object, CaptureState>> iterator = STATES.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() == state) {
                iterator.remove();
            }
        }
        if (sActiveState == state) {
            sActiveState = null;
        }
    }

    private static CaptureState activeState() {
        synchronized (STATES) {
            return sActiveState;
        }
    }

    private static void bindModule(Object module, CaptureState state) {
        synchronized (STATES) {
            if (sActiveState == state) {
                STATES.put(module, state);
            }
        }
    }

    private static int currentMode(Object module, ClassLoader classLoader) {
        try {
            int mode = HandleFixModule.currentModuleIndex(module, classLoader);
            return mode > 0 ? mode : HandleFixModule.CURRENT_MODE;
        } catch (Throwable ignored) {
            return HandleFixModule.CURRENT_MODE;
        }
    }

    private static LeicaStateAdapter.State opposite(LeicaStateAdapter.State state) {
        return state == LeicaStateAdapter.State.VIBRANT
                ? LeicaStateAdapter.State.CLASSIC : LeicaStateAdapter.State.VIBRANT;
    }

    private static KeyEvent event(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof KeyEvent) {
                return (KeyEvent) arg;
            }
        }
        return null;
    }

    private static boolean isShutter(KeyEvent event) {
        if (event == null) {
            return false;
        }
        int code = event.getKeyCode();
        if (code != KEYCODE_SHUTTER && code != KEYCODE_SHUTTER_ALT) return false;
        return !CameraBuilds.isCamera68Port(sCameraVersionCode)
                || (ModeConfig.isCamera68Mapping() && GripInputGuard.isGripEvent(event));
    }

    private static long duration(KeyEvent event) {
        return Math.max(0L, event.getEventTime() - event.getDownTime());
    }

    private static long stateDuration(CaptureState state) {
        return Math.max(0L, SystemClock.uptimeMillis() - state.downTime);
    }

    /**
     * CV 写入必须按相机族调用已验证的原生 UI 入口；不能只 setComponentValue，
     * 否则预览、顶栏和最终 JPEG 可能处于不同状态。
     */
    private static boolean toggleCvType(ClassLoader classLoader, int mode) {
        if (CameraBuilds.isCamera68Port(sCameraVersionCode)) {
            return LeicaStateAdapter.toggleCamera68CvType(classLoader, mode);
        }
        if (CameraBuilds.isPhoenixOs4Family(sCameraVersionCode)) {
            return PhoenixLeicaHooks.toggleCvType(classLoader, mode);
        }
        if (!sPrometheusProfile) {
            return OemHooks.toggleOfficialCvType(classLoader, mode);
        }
        return LeicaHooks.toggleCvType(classLoader);
    }

    private interface Signature {
        boolean matches(Method method);
    }

    private static int hookExact(Class<?> root, String methodName, Signature signature,
            XC_MethodHook callback) {
        for (Class<?> type = root; type != null && type != Object.class;
                type = type.getSuperclass()) {
            Method[] methods;
            try {
                methods = type.getDeclaredMethods();
            } catch (Throwable t) {
                continue;
            }
            for (Method method : methods) {
                if (!methodName.equals(method.getName()) || method.isBridge()
                        || method.isSynthetic() || Modifier.isAbstract(method.getModifiers())
                        || !signature.matches(method)) {
                    continue;
                }
                if (hookOnce(method, callback)) {
                    return 1;
                }
            }
        }
        HandleFixModule.log("dual-style: method missing " + methodName);
        return 0;
    }

    /**
     * Resolve one verified camera action method by its complete JVM signature.
     *
     * <p>Do not assume every CameraAction callback returns {@code boolean}:
     * {@code onShutterButtonClick(int)} does, while the native release callback
     * {@code onShutterButtonCancel(boolean)} returns {@code void}. The old helper
     * hard-coded a boolean return and therefore rejected the valid OS4 6.6.510
     * release method, which made the whole C3 adapter report {@code ready=false}.
     * Including the return type in the lookup keeps this a strict descriptor match
     * without coupling unrelated callbacks to one return convention.</p>
     *
     * <p>An abstract/interface declaration is still invokable reflectively on the
     * concrete {@code mCameraAction} instance and dispatches to its implementation,
     * so it is a valid capability declaration rather than an installation failure.</p>
     */
    private static Method findExact(Class<?> root, String methodName,
            Class<?> returnType, Class<?>... parameters) {
        for (Class<?> type = root; type != null && type != Object.class;
                type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(methodName, parameters);
                if (method.getReturnType() == returnType) {
                    method.setAccessible(true);
                    return method;
                }
            } catch (Throwable ignored) {
                // Try the parent declaration.
            }
        }
        return null;
    }

    private static Field findField(Class<?> root, String fieldName) {
        for (Class<?> type = root; type != null && type != Object.class;
                type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored) {
                // Try the parent declaration. The field name is verified against this APK.
            }
        }
        // V5.1 的 Camera2Module 自己实现 onShutterButtonClick(int)，本来就没有
        // mCameraAction；字段缺失是该版本的正常适配分支，不应伪装成安装异常。
        return null;
    }

    private static boolean hookOnce(Method method, XC_MethodHook callback) {
        String key = method.toGenericString();
        synchronized (HOOKED_METHODS) {
            if (!HOOKED_METHODS.add(key)) {
                return true;
            }
        }
        try {
            method.setAccessible(true);
            XposedBridge.hookMethod(method, callback);
            HandleFixModule.log("dual-style: hooked " + descriptor(method));
            return true;
        } catch (Throwable t) {
            synchronized (HOOKED_METHODS) {
                HOOKED_METHODS.remove(key);
            }
            HandleFixModule.log("dual-style: hook failed " + descriptor(method), t);
            return false;
        }
    }

    private static boolean parameters(Method method, Class<?>... expected) {
        Class<?>[] actual = method.getParameterTypes();
        if (actual.length != expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (actual[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static String descriptor(Method method) {
        StringBuilder result = new StringBuilder(method.getDeclaringClass().getName())
                .append('.').append(method.getName()).append('(');
        for (Class<?> parameter : method.getParameterTypes()) {
            result.append(typeDescriptor(parameter));
        }
        return result.append(')').append(typeDescriptor(method.getReturnType())).toString();
    }

    private static String typeDescriptor(Class<?> type) {
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        if (type.isArray()) return type.getName().replace('.', '/');
        return "L" + type.getName().replace('.', '/') + ";";
    }
}
