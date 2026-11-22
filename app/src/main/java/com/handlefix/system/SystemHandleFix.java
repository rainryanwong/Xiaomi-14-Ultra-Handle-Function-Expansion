package com.handlefix.system;

import android.content.Context;
import android.os.IBinder;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.handlefix.GripInputGuard;
import com.handlefix.ModeConfig;

import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Method;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * System-server hook: un-swallow the photography-handle record button
 * (KEYCODE 313) so it reaches the foreground camera app.
 *
 * Root cause (from device logs): when the photography handle is connected,
 * MIUI's PhoneWindowManagerStub.interceptKeyBeforeQueueing() consumes keyCode
 * 313 (it is never published to any window), because the MIUI input system is
 * not tracking the handle device (mPhotoHandleDeviceIds is empty on the
 * ported camera setup). The AOSP PhoneWindowManager.interceptKeyBeforeQueueing
 * returns immediately when the MIUI stub says "consumed".
 *
 * Fix: in system_server, after PhoneWindowManager.interceptKeyBeforeQueueing
 * returns, force ACTION_PASS_TO_USER (0x1) into the result for keyCode 313
 * coming from the Xiaomi photography handle (vendor 0x2717), and make
 * interceptKeyBeforeDispatching never consume it. The event then flows to the
 * focused window (com.android.camera), where the camera's KeyEventImpl handles
 * it as the record-button key.
 */
public class SystemHandleFix implements IXposedHookLoadPackage {

    private static final int KEY_RECORD = 313; // 0x139
    private static final int HANDLE_VENDOR = 0x2717;
    private static final int ACTION_PASS_TO_USER = 0x1;
    /** 正式配置不写逐按键/拨轮日志；保留安装结果与异常供故障定位。 */
    private static final boolean VERBOSE_INPUT_LOG = false;
    private static final String BLOCKED_EXTRA = "handlefix.gripInputBlocked";
    private static final String QUICK_LAUNCH_EXTRA = "handlefix.gripQuickLaunchClaimed";
    /** 每个设备/键只保留本次被挡的 downTime，解锁期间的 repeat/UP 也不能漏出。 */
    private static final ConcurrentHashMap<Long, Long> BLOCKED_PRESSES =
            new ConcurrentHashMap<Long, Long>();
    // OEM 的 non-interactive motion policy 没有 deviceId。仅以已核验手柄的
    // KEYCODE_UNKNOWN 拨轮伴生事件时间戳关联滚动，避免按 source 广泛屏蔽鼠标/触摸。
    private static volatile long lastBlockedWheelEventTime = -1L;
    private static final long WHEEL_MOTION_WINDOW_MS = 16L;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) {
            return;
        }
        try {
            hookPhoneWindowManager(lpparam.classLoader);
            com.handlefix.activation.ActivationProbeHooks.install(lpparam.classLoader, "android");
            log("SystemHandleFix: installed in system_server");
        } catch (Throwable t) {
            log("SystemHandleFix: install failed: " + t);
        }
    }

    private void hookPhoneWindowManager(final ClassLoader cl) {
        // ---- interceptKeyBeforeQueueing: force pass-to-user for handle 313 ----
        XposedHelpers.findAndHookMethod("com.android.server.policy.PhoneWindowManager", cl,
                "interceptKeyBeforeQueueing", KeyEvent.class, int.class,
                new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            KeyEvent ev = (KeyEvent) param.args[0];
                            if (shouldSuppress(param.thisObject, ev)) {
                                param.setObjectExtra(BLOCKED_EXTRA, Boolean.TRUE);
                                // 不调用原来的 MIUI/AOSP 入队策略：实机唤醒发生在该策略中，
                                // dispatch 阶段才吞按键已经太晚。0 不含 PASS_TO_USER。
                                param.setResult(Integer.valueOf(0));
                                if (ev.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN
                                        && ev.getAction() == KeyEvent.ACTION_DOWN
                                        && ev.getRepeatCount() == 0) {
                                    lastBlockedWheelEventTime = ev.getEventTime();
                                }
                                if (ev.getKeyCode() == KEY_RECORD) {
                                    GlobalRecordVideoHook.forgetPress(ev.getDeviceId());
                                }
                                if (VERBOSE_INPUT_LOG && ev.getAction() == KeyEvent.ACTION_DOWN
                                        && ev.getRepeatCount() == 0) {
                                    log("grip-input-guard: pre-queue blocked key="
                                            + ev.getKeyCode() + " deviceId=" + ev.getDeviceId());
                                }
                                return;
                            }
                            // 相机外快捷启动在入队阶段接管配置方向，避免原生策略先唤醒或
                            // 分发半段按键。锁屏/息屏由上方 GripInputGuard 优先裁决；保护
                            // 未命中时快捷启动可以沿用相机自己的锁屏启动链。未配置、非
                            // Phoenix 或相机前台时返回 null，原有 168/169 行为完全不变。
                            Integer quickDecision = GripQuickLaunchController.onQueue(
                                    param.thisObject, ev);
                            if (quickDecision != null) {
                                param.setObjectExtra(QUICK_LAUNCH_EXTRA, Boolean.TRUE);
                                param.setResult(quickDecision);
                            }
                        } catch (Throwable t) {
                            log("grip-input-guard: queue gate failed: " + t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (Boolean.TRUE.equals(param.getObjectExtra(BLOCKED_EXTRA))
                                    || Boolean.TRUE.equals(param.getObjectExtra(QUICK_LAUNCH_EXTRA))) return;
                            KeyEvent ev = (KeyEvent) param.args[0];
                            if (shouldSuppress(param.thisObject, ev)) {
                                if (ev.getKeyCode() == KEY_RECORD) {
                                    GlobalRecordVideoHook.forgetPress(ev.getDeviceId());
                                }
                                // 该方法返回 int；0 表示不向应用分发，也不再补触发按键唤醒。
                                param.setResult(Integer.valueOf(0));
                                return;
                            }
                            if (ev.getKeyCode() == KEY_RECORD && isHandleDevice(ev.getDeviceId())) {
                                int r = ((Integer) param.getResult()).intValue();
                                if ((r & ACTION_PASS_TO_USER) == 0) {
                                    param.setResult(Integer.valueOf(r | ACTION_PASS_TO_USER));
                                    if (VERBOSE_INPUT_LOG) {
                                        log("force pass-to-user keyCode=313 deviceId=" + ev.getDeviceId());
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            log("interceptKeyBeforeQueueing hook error: " + t);
                        }
                    }
                });

        // ---- interceptKeyBeforeDispatching: never consume handle 313 ----
        XposedHelpers.findAndHookMethod("com.android.server.policy.PhoneWindowManager", cl,
                "interceptKeyBeforeDispatching", IBinder.class, KeyEvent.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            KeyEvent ev = (KeyEvent) param.args[1];
                            if (ev.getKeyCode() == KEY_RECORD && isHandleDevice(ev.getDeviceId())) {
                                // 相机在前台或 C2 开关关闭时，onDispatch() 返回 null，
                                // 继续把 313 原样交给当前窗口；只有“非相机前台 + 深长按
                                // 达阈值 + 原生快捷录像 Intent 已成功启动”才返回 -1 消费 UP。
                                // 这样不会覆盖相机内部按模式配置的短按/短长按/深长按。
                                Long decision = GlobalRecordVideoHook.onDispatch(
                                        param.thisObject, ev);
                                param.setResult(decision != null
                                        ? decision : Long.valueOf(0L));
                            }
                        } catch (Throwable t) {
                            log("interceptKeyBeforeDispatching hook error: " + t);
                        }
                    }
                });

        hookWheelWakeMotion(cl);
    }

    /** 唤醒前的非交互运动入口；按键入队拦截无法挡住拨轮的独立 MOTION 唤醒。 */
    private void hookWheelWakeMotion(ClassLoader cl) {
        try {
            Class<?> pwm = XposedHelpers.findClass(
                    "com.android.server.policy.PhoneWindowManager", cl);
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(pwm,
                    "interceptMotionBeforeQueueingNonInteractive",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Method method = (Method) param.method;
                                Class<?>[] types = method.getParameterTypes();
                                long whenNanos = -1L;
                                for (int i = 0; i < types.length; i++) {
                                    if (types[i] == long.class) {
                                        whenNanos = ((Long) param.args[i]).longValue();
                                        break;
                                    }
                                }
                                if (whenNanos < 0 || types.length < 2) return;
                                // 实测五参数策略回调：N1G 拨轮是 ROTARY_ENCODER/SCROLL，
                                // 非鼠标。未知签名缺少来源/动作证据时放行，不猜测触摸来源。
                                if (types.length != 5 || types[1] != int.class
                                        || types[2] != int.class) return;
                                int source = ((Integer) param.args[1]).intValue();
                                int action = ((Integer) param.args[2]).intValue();
                                if (source != InputDevice.SOURCE_ROTARY_ENCODER
                                        || action != MotionEvent.ACTION_SCROLL) return;
                                long wheelTime = lastBlockedWheelEventTime;
                                long delta = whenNanos / 1000000L - wheelTime;
                                if (wheelTime < 0 || delta < -WHEEL_MOTION_WINDOW_MS
                                        || delta > WHEEL_MOTION_WINDOW_MS) return;
                                if (!GripInputGuard.isRestricted(contextOf(param.thisObject),
                                        ModeConfig.GRIP_INPUT_WHEEL)) return;
                                // 0 不含 PASS_TO_USER，且跳过原策略的 MOTION wakeUp。
                                param.setResult(Integer.valueOf(0));
                                // 一个拨轮刻度可能连续产生多个运动帧，保留时间窗至自然过期。
                                if (VERBOSE_INPUT_LOG) {
                                    log("grip-input-guard: pre-queue blocked wheel motion deltaMs="
                                            + delta);
                                }
                            } catch (Throwable t) {
                                log("grip-input-guard: motion gate failed: " + t);
                            }
                        }
                    });
            log("grip-input-guard: non-interactive motion hooks=" + hooks.size());
        } catch (Throwable t) {
            log("grip-input-guard: motion hook unavailable: " + t);
        }
    }

    private static boolean shouldSuppress(Object phoneWindowManager, KeyEvent event) {
        if (event == null) return false;
        long key = (((long) event.getDeviceId()) << 32)
                | (event.getKeyCode() & 0xffffffffL);
        Long held = BLOCKED_PRESSES.get(key);
        if (held != null && held.longValue() == event.getDownTime()) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                BLOCKED_PRESSES.remove(key, held);
            }
            return true;
        }
        if (held != null) {
            BLOCKED_PRESSES.remove(key, held);
        }
        if (!GripInputGuard.isGripEvent(event)) return false;
        int inputType = GripInputGuard.inputType(event);
        if (inputType == 0) return false;
        Context context = contextOf(phoneWindowManager);
        if (!GripInputGuard.isRestricted(context, inputType)) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            BLOCKED_PRESSES.put(key, Long.valueOf(event.getDownTime()));
        }
        return true;
    }

    private static Context contextOf(Object phoneWindowManager) {
        try {
            Object value = XposedHelpers.getObjectField(phoneWindowManager, "mContext");
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Returns true when deviceId belongs to the Xiaomi photography handle. */
    private static boolean isHandleDevice(int deviceId) {
        try {
            Class<?> imCls = Class.forName("android.hardware.input.InputManager");
            Object im = imCls.getMethod("getInstance").invoke(null);
            if (im == null) {
                return false;
            }
            Object dev = imCls.getMethod("getInputDevice", int.class).invoke(im, deviceId);
            if (dev == null) {
                return false;
            }
            int vendor = ((Integer) dev.getClass().getMethod("getVendorId").invoke(dev)).intValue();
            if (vendor != HANDLE_VENDOR) {
                return false;
            }
            int product = ((Integer) dev.getClass().getMethod("getProductId").invoke(dev)).intValue();
            return product == 0x5083 || product == 0x50b2 || product == 0x50b7;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void log(String msg) {
        XposedBridge.log("[HandleFixSys] " + msg);
    }
}
