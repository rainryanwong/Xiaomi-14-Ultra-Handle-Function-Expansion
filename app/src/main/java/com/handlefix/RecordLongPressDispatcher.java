package com.handlefix;

import android.view.KeyEvent;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 录像键松键时长分段的唯一分发入口。
 *
 * <p>以前 K3、q6、官方适配器各自判断一次“长按”，导致阈值不同或同一次释放被
 * 连续消费。现在所有相机入口都只把事件交给这里：短长按阈值（默认 500ms）到
 * 深长按阈值（默认 1000ms）之间使用 long 配置，达到深长按阈值使用 deepLong
 * 配置；deepLong 缺省时回退旧 long 配置。动作失败时不设置 result，让相机继续
 * 走原生逻辑。</p>
 */
public final class RecordLongPressDispatcher {
    public interface CvToggle {
        boolean toggle();
    }

    private RecordLongPressDispatcher() {
    }

    public static boolean dispatch(XC_MethodHook.MethodHookParam param,
            final ClassLoader classLoader, final int mode, final CvToggle toggle) {
        KeyEvent event = eventFor(param);
        if (event == null || event.getAction() != KeyEvent.ACTION_UP || event.isCanceled()) {
            return false;
        }
        long duration = Math.max(0L, event.getEventTime() - event.getDownTime());
        String action = ModeConfig.getRecordLongAction(mode, duration);
        if (ModeConfig.F_DEFAULT.equals(action)) {
            return false;
        }
        if (ModeConfig.F_LEICA.equals(action)) {
            if (!ModeConfig.isLeicaStyleMode(mode) || toggle == null) {
                return false;
            }
            LeicaStateAdapter.State before = LeicaStateAdapter.read(classLoader, mode);
            if (!toggle.toggle()) {
                return false;
            }
            showClassicStyleAfterNativeToggle(classLoader, before, mode);
            param.setResult(Boolean.TRUE);
            HandleFixModule.log("record-long: native Leica toggle mode=" + mode
                    + " duration=" + duration + "ms");
            return true;
        }
        if (ModeConfig.F_PREVIEW.equals(action)) {
            if (!ModeAwareHooks.openLatestPhoto()) {
                return false;
            }
            param.setResult(Boolean.TRUE);
            HandleFixModule.log("record-long: preview latest photo mode=" + mode
                    + " duration=" + duration + "ms");
            return true;
        }
        // 其它动作属于短按或未来独立分发器；不能在这里吞掉，避免长按误触发。
        return false;
    }

    public static boolean isShortRelease(XC_MethodHook.MethodHookParam param) {
        KeyEvent event = eventFor(param);
        return event != null && !event.isCanceled()
                && event.getAction() == KeyEvent.ACTION_UP
                && event.getEventTime() - event.getDownTime() < ModeConfig.getLongPressMs();
    }

    public static boolean isRecordRelease(XC_MethodHook.MethodHookParam param) {
        KeyEvent event = eventFor(param);
        return event != null && event.getAction() == KeyEvent.ACTION_UP;
    }

    private static KeyEvent eventFor(XC_MethodHook.MethodHookParam param) {
        if (param == null || param.args == null || param.args.length < 2
                || !(param.args[0] instanceof Integer)
                || ((Integer) param.args[0]).intValue() != 313
                || !(param.args[1] instanceof KeyEvent)) {
            return null;
        }
        return (KeyEvent) param.args[1];
    }

    private static void showClassicStyleAfterNativeToggle(final ClassLoader classLoader,
            LeicaStateAdapter.State before, final int mode) {
        if (before != LeicaStateAdapter.State.VIBRANT
                || LeicaStateAdapter.read(classLoader, mode) != LeicaStateAdapter.State.CLASSIC) {
            return;
        }
        /*
         * v12.0.76 曾延迟 1.8s 等待原生“徕卡经典”提示结束，但这会牺牲状态提示的
         * 时效性。自定义 Overlay 现已在横屏方向下移到另一显示区域，因此切换成功并
         * 读回 CLASSIC 后立即展示当前 LUT；Overlay 自己仍会用新提示替换旧提示。
         */
        String label = LeicaStateAdapter.currentClassicStyleLabel(
                classLoader, HandleFixModule.CAMERA_VERSION_CODE);
        if (label != null && label.length() > 0) {
            PhoenixClassicStyleHooks.showStyleFeedback(label, mode);
        }
    }
}
