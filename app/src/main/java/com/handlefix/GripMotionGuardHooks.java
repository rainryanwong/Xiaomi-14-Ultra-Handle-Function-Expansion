package com.handlefix;

import android.app.Activity;
import android.view.MotionEvent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** 锁屏相机仍在前台时，挡住手柄拨轮滚动；系统层另挡伴生唤醒 KeyEvent。 */
public final class GripMotionGuardHooks {
    private GripMotionGuardHooks() {}

    public static void install() {
        HandleFixModule.install("grip-motion-guard", new Runnable() {
            @Override public void run() {
                XposedBridge.hookMethod(XposedHelpers.findMethodExact(Activity.class,
                        "dispatchGenericMotionEvent", MotionEvent.class), new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        MotionEvent event = (MotionEvent) param.args[0];
                        if (GripInputGuard.isGripEvent(event)
                                && GripInputGuard.isRestricted((Activity) param.thisObject,
                                ModeConfig.GRIP_INPUT_WHEEL)) {
                            param.setResult(Boolean.TRUE);
                        }
                    }
                });
            }
        });
    }
}
