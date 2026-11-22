package com.handlefix.system;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.KeyEvent;

import com.handlefix.Camera68Companion;
import com.handlefix.CameraBuilds;
import com.handlefix.Camera68LaunchTargets;
import com.handlefix.GripInputGuard;
import com.handlefix.HandleFixModule;
import com.handlefix.ModeConfig;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 相机外变焦拨杆长拨快捷启动。
 *
 * <p>状态机运行在 system_server 的按键入队阶段：配置方向的首个 DOWN 在确认相机
 * 不在前台且当前安装包与快捷启动方案匹配后才被接管；达到阈值时发送相机原生
 * CAMERA_MODE Intent，UP 只结束本次事务。锁屏/息屏是否允许触发完全交给
 * {@link GripInputGuard} 的用户配置决定，本类不再叠加第二套屏幕状态门。这样既不需要
 * 模拟触摸，也不会改动相机前台的变焦拨杆映射。</p>
 */
final class GripQuickLaunchController {
    private static final int KEY_ZOOM_IN = KeyEvent.KEYCODE_ZOOM_IN;   // 168
    private static final int KEY_ZOOM_OUT = KeyEvent.KEYCODE_ZOOM_OUT; // 169
    private static final String CAMERA_PACKAGE = "com.android.camera";
    private static final String CAMERA_CLASS = "com.android.camera.Camera";
    private static final String EXTRA_CAMERA_MODE = "android.intent.extra.CAMERA_MODE";
    private static final String EXTRA_REFERRER_NAME = "android.intent.extra.REFERRER_NAME";
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final ConcurrentHashMap<Integer, Press> PRESSES =
            new ConcurrentHashMap<Integer, Press>();
    private static volatile long sLastTriggerAt;

    private GripQuickLaunchController() {
    }

    private static final class Press {
        final int deviceId;
        final int keyCode;
        final long downTime;
        final String target;
        final Context context;
        final boolean camera68;
        final Runnable trigger;
        volatile boolean cancelled;
        volatile boolean triggered;

        Press(int deviceId, int keyCode, long downTime, String target, Context context,
                boolean camera68) {
            this.deviceId = deviceId;
            this.keyCode = keyCode;
            this.downTime = downTime;
            this.target = target;
            this.context = context;
            this.camera68 = camera68;
            this.trigger = new Runnable() {
                @Override public void run() {
                    fire(Press.this);
                }
            };
        }
    }

    /**
     * @return null 表示完全保留原生策略；0 表示本次拨杆事务由快捷启动状态机接管。
     */
    static Integer onQueue(Object phoneWindowManager, KeyEvent event) {
        if (event == null || (event.getKeyCode() != KEY_ZOOM_IN
                && event.getKeyCode() != KEY_ZOOM_OUT)
                || !GripInputGuard.isGripEvent(event)) {
            return null;
        }
        int deviceId = event.getDeviceId();
        Press active = PRESSES.get(deviceId);
        if (event.getAction() == KeyEvent.ACTION_UP || event.isCanceled()) {
            if (active == null) return null;
            if (active.downTime != event.getDownTime() && active.keyCode != event.getKeyCode()) {
                return Integer.valueOf(0);
            }
            cancel(active);
            PRESSES.remove(deviceId, active);
            return Integer.valueOf(0);
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return active == null ? null : Integer.valueOf(0);

        if (active != null) {
            if (active.keyCode == event.getKeyCode() && active.downTime == event.getDownTime()) {
                return Integer.valueOf(0);
            }
            // 同一物理事务内换向时取消旧计时，并吞到新方向松开；不能让两侧分别触发。
            cancel(active);
            Press blocked = new Press(deviceId, event.getKeyCode(), event.getDownTime(),
                    ModeConfig.GRIP_QUICK_NATIVE, active.context, active.camera68);
            blocked.cancelled = true;
            PRESSES.put(deviceId, blocked);
            return Integer.valueOf(0);
        }

        String target = ModeConfig.getGripQuickTarget(event.getKeyCode() == KEY_ZOOM_IN);
        if (ModeConfig.GRIP_QUICK_NATIVE.equals(target)) return null;
        Context context = contextOf(phoneWindowManager);
        boolean camera68 = ModeConfig.isCamera68Mapping();
        if (!canArm(context) || !matchesCamera(context, camera68, target)
                || GripInputGuard.isRestricted(context, ModeConfig.GRIP_INPUT_ZOOM)) {
            return null;
        }
        Press press = new Press(deviceId, event.getKeyCode(), event.getDownTime(), target,
                context, camera68);
        PRESSES.put(deviceId, press);
        HANDLER.postDelayed(press.trigger, ModeConfig.getGripQuickLongPressMs());
        return Integer.valueOf(0);
    }

    private static void fire(Press press) {
        if (press.cancelled || press.triggered || PRESSES.get(press.deviceId) != press) return;
        if (!canArm(press.context) || press.camera68 != ModeConfig.isCamera68Mapping()
                || !matchesCamera(press.context, press.camera68, press.target)
                || GripInputGuard.isRestricted(press.context, ModeConfig.GRIP_INPUT_ZOOM)
                || !press.target.equals(ModeConfig.getGripQuickTarget(
                press.keyCode == KEY_ZOOM_IN))) {
            press.cancelled = true;
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - sLastTriggerAt < 1500L) {
            press.cancelled = true;
            return;
        }
        if (!startCamera(press.context, press.target, press.camera68)) {
            press.cancelled = true;
            HandleFixModule.log("grip-quick-launch: start failed target=" + press.target);
            return;
        }
        press.triggered = true;
        sLastTriggerAt = now;
        if (ModeConfig.isGripQuickHapticEnabled()) vibrate(press.context);
        // 正式版只保留失败日志；逐事务成功信息走统一 verbose 门，避免长期污染
        // system_server 日志。需要适配新相机族时可临时打开 VERBOSE_HOOK_LOG。
        HandleFixModule.debug("grip-quick-launch: started target=" + press.target
                + " key=" + press.keyCode + " threshold="
                + ModeConfig.getGripQuickLongPressMs() + "ms");
    }

    private static void cancel(Press press) {
        press.cancelled = true;
        HANDLER.removeCallbacks(press.trigger);
    }

    private static boolean startCamera(Context context, String target, boolean camera68) {
        boolean forceStreetStyle = camera68 && Camera68LaunchTargets.requiresStreetStyleBridge(target);
        String cameraMode;
        if (camera68) {
            if (forceStreetStyle && !Camera68Companion.isCameraCompatInstalled(context)) {
                HandleFixModule.log("grip-quick-launch: CameraCompat street style unavailable");
                return false;
            }
            cameraMode = Camera68LaunchTargets.cameraModeForTarget(target);
            if (cameraMode == null) return false;
        } else if (ModeConfig.GRIP_QUICK_LEGEND.equals(target)) {
            cameraMode = "LEGEND";
        } else if (ModeConfig.GRIP_QUICK_CAPTURE.equals(target)) {
            cameraMode = "CAPTURE";
        } else if (ModeConfig.GRIP_QUICK_VIDEO.equals(target)) {
            cameraMode = "VIDEO";
        } else if (ModeConfig.GRIP_QUICK_PRO_PHOTO.equals(target)) {
            cameraMode = "MANUAL";
        } else if (ModeConfig.GRIP_QUICK_PRO_VIDEO.equals(target)) {
            // Phoenix 没有公开的专业录像 CAMERA_MODE；先走最接近的 VIDEO 解析链，
            // 相机进程再凭一次性私有目标把 StartControl 精确收敛到 module 180。
            cameraMode = "VIDEO";
        } else if (ModeConfig.GRIP_QUICK_NIGHT.equals(target)) {
            cameraMode = "SUPER_NIGHT";
        } else if (ModeConfig.GRIP_QUICK_PORTRAIT.equals(target)) {
            cameraMode = "PORTRAIT";
        } else if (ModeConfig.GRIP_QUICK_DOCUMENT.equals(target)) {
            cameraMode = "DOC";
        } else if (ModeConfig.GRIP_QUICK_ULTRA_PIXEL.equals(target)) {
            cameraMode = "ULTRA_PIXEL";
        } else if (ModeConfig.GRIP_QUICK_STREET_FOLLOW.equals(target)
                || ModeConfig.GRIP_QUICK_STREET_225.equals(target)
                || ModeConfig.GRIP_QUICK_STREET_229.equals(target)
                || ModeConfig.GRIP_QUICK_STREET_229_LEGEND.equals(target)) {
            cameraMode = "STREET";
        } else {
            return false;
        }
        // A cold start cannot prove whether the in-camera bridge will be available. Submit the
        // forced-style request without explicitly destroying the existing camera task.
        if (!forceStreetStyle && !removeExistingCameraTasks(context, target)) {
            // Phoenix 的全部快捷模式跳转都必须从干净任务进入。尤其在息屏/锁屏场景，
            // 直接复用旧 Camera Activity 会出现“首次只振动，第二次才显示”的竞态；
            // 任务移除失败时宁可不启动、不振动，也不能给出虚假的成功反馈。
            return false;
        }
        Intent intent = new Intent("android.media.action.STILL_IMAGE_CAMERA");
        intent.setClassName(CAMERA_PACKAGE, CAMERA_CLASS);
        intent.putExtra(EXTRA_CAMERA_MODE, cameraMode);
        // Phoenix 只允许白名单来源使用自定义 CAMERA_MODE；该 referrer 经过 1.2.14
        // 冷/热启动实机验证。模块私有 launch_source 仍用于相机侧识别本功能。
        if (!camera68) intent.putExtra(EXTRA_REFERRER_NAME, "android-app://com.miui.home");
        intent.putExtra("com.android.systemui.camera_launch_source",
                ModeConfig.GRIP_QUICK_LAUNCH_SOURCE);
        // 与系统快捷录像复用相机自己的锁屏入口语义：允许相机显示在 Keyguard 上并
        // 按自身策略点亮屏幕，但不绕过解锁。输入保护若已启用，会在到达这里前拦截。
        intent.putExtra("StartActivityWhenLocked", true);
        intent.putExtra("ShowCameraWhenLocked", true);
        int exactMode = camera68 ? Camera68LaunchTargets.routeForTarget(target)
                : exactModuleForTarget(target);
        if (exactMode != 0) {
            intent.putExtra(ModeConfig.EXTRA_HANDLEFIX_QUICK_MODE_TARGET, exactMode);
        }
        if (ModeConfig.GRIP_QUICK_STREET_229_LEGEND.equals(target)) {
            // 229 的两套 UI 共用同一 moduleId；使用单次私有标记交给相机进程的
            // 精确 Fragment 工厂 Hook，不能改 Phoenix 的持久主题或街拍偏好。
            intent.putExtra(ModeConfig.EXTRA_HANDLEFIX_QUICK_STREET_LEGEND_UI, true);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!forceStreetStyle) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (camera68) return startCamera68Activity(context, intent);
        try {
            Method method = Context.class.getMethod(
                    "startActivityAsUser", Intent.class, UserHandle.class);
            method.invoke(context, intent, currentUserHandle());
            return true;
        } catch (Throwable first) {
            try {
                context.startActivity(intent);
                return true;
            } catch (Throwable second) {
                return false;
            }
        }
    }

    /**
     * 全部快捷目标先通过 system_server 可用的任务接口同步移除相机旧任务，再单独发起
     * 一次普通启动。不能把 FLAG_ACTIVITY_CLEAR_TASK 叠在启动 Intent 上：息屏时它可能
     * 只完成清栈，却没有留下可见的新 Camera Activity，表现为需要第二次长拨。这里不
     * 使用固定延时、双 Intent 或模拟按键；没有旧任务时直接放行，检查/移除失败时
     * fail closed。229 传奇款的一次性 UI 标记仍由新 Activity 的原生构造流程消费。
     */
    static boolean removeExistingCameraTasks(Context context, String target) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(
                    Context.ACTIVITY_SERVICE);
            if (am == null) {
                HandleFixModule.log("grip-quick-launch: no ActivityManager target=" + target);
                return false;
            }
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(64);
            if (tasks == null) {
                HandleFixModule.log("grip-quick-launch: task query failed target=" + target);
                return false;
            }
            // 新版 Android 已把任务操作从 ActivityManager 移到隐藏的
            // ActivityTaskManager；这里按设备 framework.jar 的真实公开签名反射，
            // 避免调用不存在的 ActivityManager.removeTask(int)。
            Class<?> taskManagerClass = Class.forName("android.app.ActivityTaskManager");
            Method getInstance = taskManagerClass.getMethod("getInstance");
            Object taskManager = getInstance.invoke(null);
            if (taskManager == null) {
                HandleFixModule.log("grip-quick-launch: no ActivityTaskManager target="
                        + target);
                return false;
            }
            Method removeTask = taskManagerClass.getMethod("removeTask", int.class);
            for (ActivityManager.RunningTaskInfo task : tasks) {
                if (task == null || !isCameraTask(task)) continue;
                Object result = removeTask.invoke(taskManager, Integer.valueOf(task.taskId));
                if (result instanceof Boolean && !((Boolean) result).booleanValue()) {
                    HandleFixModule.log("grip-quick-launch: remove camera task rejected id="
                            + task.taskId + " target=" + target);
                    return false;
                }
                HandleFixModule.debug("grip-quick-launch: removed camera task id="
                        + task.taskId + " target=" + target);
            }
            return true;
        } catch (Throwable error) {
            HandleFixModule.log("grip-quick-launch: remove camera task failed target="
                    + target + " error=" + error.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean isCameraTask(ActivityManager.RunningTaskInfo task) {
        if (isCameraComponent(task.baseActivity) || isCameraComponent(task.topActivity)
                || isCameraComponent(task.origActivity)) {
            return true;
        }
        Intent baseIntent = task.baseIntent;
        return baseIntent != null && (isCameraComponent(baseIntent.getComponent())
                || CAMERA_PACKAGE.equals(baseIntent.getPackage()));
    }

    private static boolean isCameraComponent(ComponentName component) {
        return component != null && CAMERA_PACKAGE.equals(component.getPackageName());
    }

    private static boolean canArm(Context context) {
        return context != null && !isCameraForeground(context);
    }

    private static boolean matchesCamera(Context context, boolean camera68, String target) {
        return camera68 ? isCamera68Camera(context)
                && Camera68LaunchTargets.supportsTarget(target)
                : ModeConfig.isPromFixEnabled()
                        && CameraBuilds.isPhoenixOs4Family(installedCameraVersion(context));
    }

    static boolean isCamera68Camera(Context context) {
        return Build.VERSION.SDK_INT >= 35
                && CameraBuilds.isCamera68Port(installedCameraVersion(context));
    }

    static long installedCameraVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(CAMERA_PACKAGE, 0);
            return info.getLongVersionCode();
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            return -1L;
        }
    }

    @android.annotation.TargetApi(35)
    static boolean startCamera68Activity(Context context, Intent intent) {
        try {
            // 由 ActivityTaskManager 向相机传递 system_server 真实 UID，用于验证启动请求。
            Bundle options = ActivityOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
            Method method = Context.class.getMethod("startActivityAsUser",
                    Intent.class, Bundle.class, UserHandle.class);
            method.invoke(context, intent, options, currentUserHandle());
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            HandleFixModule.log("camera68: explicit camera launch failed", error);
            return false;
        }
    }

    private static boolean isCameraForeground(Context context) {
        ActivityManager am = null;
        boolean taskReadSucceeded = false;
        try {
            am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> tasks = am == null ? null : am.getRunningTasks(1);
            if (tasks != null && !tasks.isEmpty() && tasks.get(0).topActivity != null) {
                taskReadSucceeded = true;
                if (CAMERA_PACKAGE.contentEquals(tasks.get(0).topActivity.getPackageName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // Phoenix 的热启动任务栈偶尔不会及时更新 topActivity；继续检查 UID 重要级。
        }
        try {
            if (am == null) {
                am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            }
            List<ActivityManager.RunningAppProcessInfo> processes =
                    am == null ? null : am.getRunningAppProcesses();
            if (processes != null) {
                for (ActivityManager.RunningAppProcessInfo process : processes) {
                    if (process != null && process.processName != null
                            && (CAMERA_PACKAGE.equals(process.processName)
                            || process.processName.startsWith(CAMERA_PACKAGE + ":"))
                            && process.importance
                            == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 两条系统证据都不可读时 fail closed，不能误吞相机前台的变焦拨杆。
        }
        return !taskReadSucceeded;
    }

    /** 返回需要相机进程精确收敛的 moduleId；跟随街拍故意返回 0。 */
    private static int exactModuleForTarget(String target) {
        if (ModeConfig.GRIP_QUICK_CAPTURE.equals(target)) return 163;
        if (ModeConfig.GRIP_QUICK_VIDEO.equals(target)) return 162;
        if (ModeConfig.GRIP_QUICK_LEGEND.equals(target)) return 256;
        if (ModeConfig.GRIP_QUICK_PRO_PHOTO.equals(target)) return 167;
        if (ModeConfig.GRIP_QUICK_PRO_VIDEO.equals(target)) return 180;
        if (ModeConfig.GRIP_QUICK_NIGHT.equals(target)) return 173;
        if (ModeConfig.GRIP_QUICK_PORTRAIT.equals(target)) return 171;
        if (ModeConfig.GRIP_QUICK_DOCUMENT.equals(target)) return 186;
        if (ModeConfig.GRIP_QUICK_STREET_225.equals(target)) return 225;
        if (ModeConfig.GRIP_QUICK_STREET_229.equals(target)) return 229;
        if (ModeConfig.GRIP_QUICK_STREET_229_LEGEND.equals(target)) return 229;
        if (ModeConfig.GRIP_QUICK_ULTRA_PIXEL.equals(target)) return 175;
        return 0;
    }

    private static void vibrate(Context context) {
        try {
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(
                        28L, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Throwable ignored) {
            // 振动只是成功反馈；不可用时不能影响已经提交的相机启动。
        }
    }

    private static Context contextOf(Object pwm) {
        try {
            Object value = de.robv.android.xposed.XposedHelpers.getObjectField(pwm, "mContext");
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static UserHandle currentUserHandle() {
        try {
            Method current = ActivityManager.class.getDeclaredMethod("getCurrentUser");
            current.setAccessible(true);
            int userId = ((Integer) current.invoke(null)).intValue();
            Method of = UserHandle.class.getDeclaredMethod("of", int.class);
            of.setAccessible(true);
            return (UserHandle) of.invoke(null, Integer.valueOf(userId));
        } catch (Throwable ignored) {
            return UserHandle.getUserHandleForUid(android.os.Process.myUid());
        }
    }
}
