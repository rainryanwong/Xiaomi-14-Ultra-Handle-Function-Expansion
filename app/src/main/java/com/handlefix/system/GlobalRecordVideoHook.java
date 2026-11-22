package com.handlefix.system;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.view.KeyEvent;

import com.handlefix.Camera68LaunchTargets;
import com.handlefix.CameraBuilds;
import com.handlefix.HandleFixModule;
import com.handlefix.ModeConfig;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedHelpers;

/**
 * 系统层“任意界面长按录像键”适配器。
 *
 * <p>{@link SystemHandleFix} 默认仍把 313 原样透传给前台窗口；只有用户主动选择
 * {@link ModeConfig#GLOBAL_RECORD_OPEN} 或 {@link ModeConfig#GLOBAL_RECORD_START}、
 * 相机不在前台且达到系统快捷录像独立阈值时，
 * 才把事件交给本类。按键在松开时一次性判定，一次按键最多启动一次小米相机原生
 * “手柄快捷录像” Intent。启动失败不返回消费结果，原生事件流仍然保留；相机在
 * 前台时永远让相机自己的模式配置优先。</p>
 *
 * <p>官方 6.4.370/530、6.6.510、Prometheus/NRV 与 Phoenix 的真实代码均确认了
 * 同一基础协议：{@code android.media.action.VIDEO_CAMERA} 选择录像模块。只进入录像页时
 * 只发标准 Intent；自动录制时额外携带 HandleFix 私有标记，由相机进程
 * {@code QuickVideoLaunchHooks} 把标记接入 VideoModule 原生 {@code mQuickVideo}
 * 状态机。因此这里不模拟屏幕点击，也不向相机进程补发伪造快门。</p>
 */
final class GlobalRecordVideoHook {
    private static final int KEY_RECORD = 313;
    private static final String CAMERA_PACKAGE = "com.android.camera";
    private static final ConcurrentHashMap<Integer, Long> DOWN_TIMES =
            new ConcurrentHashMap<Integer, Long>();
    private static volatile long sLastTriggerAt;

    private GlobalRecordVideoHook() {
    }

    /** 锁屏门控拦掉 313 时撤销系统快捷录像的旧 DOWN，避免解锁后误算长按。 */
    static void forgetPress(int deviceId) {
        DOWN_TIMES.remove(deviceId);
    }

    /** 返回 null 表示不改变分发；返回 -1 表示已成功启动相机并消费本次按键。 */
    static Long onDispatch(Object phoneWindowManager, KeyEvent event) {
        if (event == null || event.getKeyCode() != KEY_RECORD
                || !isHandleDevice(event.getDeviceId())) {
            return null;
        }
        int deviceId = event.getDeviceId();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // 重复 ACTION_DOWN（repeatCount>0）不是新的按下。若每次 repeat 都覆盖
            // 起点，系统层长按超过阈值时反而可能永远测不出足够时长。
            if (event.getRepeatCount() == 0 || !DOWN_TIMES.containsKey(deviceId)) {
                DOWN_TIMES.putIfAbsent(deviceId, event.getDownTime());
            }
            return null;
        }
        if (event.getAction() != KeyEvent.ACTION_UP || event.isCanceled()) {
            return null;
        }
        Long down = DOWN_TIMES.remove(deviceId);
        String policy = ModeConfig.getGlobalRecordVideoPolicy();
        if (down == null || ModeConfig.GLOBAL_RECORD_NATIVE.equals(policy)) {
            return null;
        }
        long duration = Math.max(0L, event.getEventTime() - down.longValue());
        if (duration < ModeConfig.getGlobalRecordLongPressMs()) {
            return null;
        }
        Context context = contextOf(phoneWindowManager);
        if (context == null || !isInteractive(context) || isCameraForeground(context)) {
            return null;
        }
        long now = SystemClock.uptimeMillis();
        if (now - sLastTriggerAt < 1500L) {
            return null;
        }
        boolean camera68 = ModeConfig.isCamera68Mapping();
        if (camera68 != CameraBuilds.isCamera68Port(
                GripQuickLaunchController.installedCameraVersion(context))
                || (camera68 && Build.VERSION.SDK_INT < 35)) return null;
        if (!startVideo(context, policy, camera68)) {
            HandleFixModule.log("global-record-video: VIDEO_CAMERA start failed; keep native event");
            return null;
        }
        sLastTriggerAt = now;
        HandleFixModule.log("global-record-video: started VIDEO_CAMERA policy=" + policy
                + " duration=" + duration + "ms keyguard=" + isKeyguardLocked(context));
        return Long.valueOf(-1L);
    }

    private static boolean startVideo(Context context, String policy, boolean camera68) {
        if (camera68) {
            // 每次系统长按录制都有独立的原生预览会话与真实调用方身份。
            if (!GripQuickLaunchController.removeExistingCameraTasks(context, policy)) return false;
            Intent intent = quickVideoIntent(policy, true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            return GripQuickLaunchController.startCamera68Activity(context, intent);
        }
        try {
            Intent intent = quickVideoIntent(policy, false);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            Method method = Context.class.getMethod("startActivityAsUser", Intent.class, UserHandle.class);
            method.invoke(context, intent, currentUserHandle());
            return true;
        } catch (Throwable first) {
            try {
                // 仅回退到同一个 VIDEO_CAMERA Intent。不能在录像入口失败时改启动
                // 普通相机主页，否则系统层会误报“快捷录像已启动”并吞掉原生按键。
                Intent fallback = quickVideoIntent(policy, false);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(fallback);
                return true;
            } catch (Throwable second) {
                return false;
            }
        }
    }

    /**
     * system_server 自身固定属于 user 0，不能用它的 uid 推断当前交互用户。
     * 优先读取 ActivityManager 的真实当前用户；反射入口因系统差异不可用时，才安全
     * 回退到 system_server 所属用户。Ryan 当前设备为主用户，因此回退路径仍然有效。
     */
    private static UserHandle currentUserHandle() {
        try {
            Method method = ActivityManager.class.getDeclaredMethod("getCurrentUser");
            method.setAccessible(true);
            int userId = ((Integer) method.invoke(null)).intValue();
            // UserHandle.of(int) 在目标 Android 版本运行时存在，但部分 compileSdk
            // stub 未公开该方法；这里反射调用，避免为了编译而错误回退到 system uid。
            Method of = UserHandle.class.getDeclaredMethod("of", int.class);
            of.setAccessible(true);
            return (UserHandle) of.invoke(null, Integer.valueOf(userId));
        } catch (Throwable ignored) {
            return UserHandle.getUserHandleForUid(android.os.Process.myUid());
        }
    }

    /**
     * 构造小米相机自己使用的手柄快捷录像入口。
     *
     * <p>VIDEO_CAMERA 负责选择录像模块；锁屏 extras 只允许相机按自身策略显示在
     * Keyguard 上，不绕过解锁。自动录制策略再携带模块私有标记，不能把“只进入界面”
     * 也伪装成 quick-video，否则旧配置升级后会无意自动开录。</p>
     */
    private static Intent quickVideoIntent(String policy, boolean camera68) {
        Intent intent = new Intent("android.media.action.VIDEO_CAMERA");
        intent.setClassName(CAMERA_PACKAGE, "com.android.camera.Camera");
        if (ModeConfig.GLOBAL_RECORD_START.equals(policy)) {
            intent.putExtra(ModeConfig.EXTRA_HANDLEFIX_START_RECORDING, true);
            if (camera68) {
                intent.putExtra("com.android.systemui.camera_launch_source",
                        Camera68LaunchTargets.RECORD_LAUNCH_SOURCE);
            } else {
                intent.putExtra("quick_video_handle_key", true);
                intent.putExtra("com.android.systemui.camera_launch_source",
                        "quick_video_handle_key");
            }
        } else {
            intent.putExtra("com.android.systemui.camera_launch_source",
                    "handlefix_open_video");
        }
        // 这些键均来自相机自己的 CameraIntentManager/锁屏启动链；不会绕过
        // Keyguard 解锁，只允许相机按自身策略在锁屏上显示录像界面。
        intent.putExtra("StartActivityWhenLocked", true);
        intent.putExtra("ShowCameraWhenLocked", true);
        return intent;
    }

    private static Context contextOf(Object pwm) {
        try {
            Object value = XposedHelpers.getObjectField(pwm, "mContext");
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isCameraForeground(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> tasks = am == null ? null : am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty() || tasks.get(0).topActivity == null) {
                return false;
            }
            return CAMERA_PACKAGE.contentEquals(tasks.get(0).topActivity.getPackageName());
        } catch (Throwable ignored) {
            // 无法确认前台应用时宁可不触发系统快捷录像，避免覆盖相机自己的配置。
            return true;
        }
    }

    private static boolean isInteractive(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            // 无法确认屏幕状态时按安全失败处理，不在未知状态下绕过系统按键策略。
            return pm != null && pm.isInteractive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isKeyguardLocked(Context context) {
        try {
            Object km = context.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && (Boolean) km.getClass().getMethod("isKeyguardLocked").invoke(km);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isHandleDevice(int deviceId) {
        try {
            Class<?> imCls = Class.forName("android.hardware.input.InputManager");
            Object im = imCls.getMethod("getInstance").invoke(null);
            Object dev = im == null ? null : imCls.getMethod("getInputDevice", int.class)
                    .invoke(im, deviceId);
            if (dev == null) return false;
            int vendor = ((Integer) dev.getClass().getMethod("getVendorId").invoke(dev)).intValue();
            if (vendor != 0x2717) return false;
            int product = ((Integer) dev.getClass().getMethod("getProductId").invoke(dev)).intValue();
            return product == 0x5083 || product == 0x50b2 || product == 0x50b7;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
