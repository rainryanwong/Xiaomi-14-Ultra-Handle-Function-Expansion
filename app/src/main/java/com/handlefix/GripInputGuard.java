package com.handlefix;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;

/**
 * 手柄输入身份与锁屏状态的共享门；不解绑 USB，也不修改手柄充电节点。
 * 屏幕亮但 Keyguard 仍显示时继续阻断，直到真正退出锁屏界面。
 */
public final class GripInputGuard {
    private GripInputGuard() {}

    public static boolean isGripEvent(InputEvent event) {
        if (event == null) return false;
        try {
            InputDevice device = event.getDevice();
            if (device == null || device.getVendorId() != 0x2717) return false;
            int product = device.getProductId();
            return product == 0x5083 || product == 0x50b2 || product == 0x50b7;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isRestricted(Context context, int inputType) {
        if (context == null || !ModeConfig.isGripInputGuardEnabled()) return false;
        if ((ModeConfig.getGripInputGuardMask() & inputType) == 0) return false;
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            KeyguardManager keyguard =
                    (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            // 状态读取失败时采取放行，避免在 system_server 误挡其它输入。
            if (power == null || keyguard == null) return false;
            boolean interactive = power.isInteractive();
            boolean locked = keyguard.isKeyguardLocked();
            String scope = ModeConfig.getGripInputGuardScope();
            if (ModeConfig.GRIP_GUARD_SCOPE_KEYGUARD.equals(scope)) {
                // “仅锁屏”不包含息屏：必须已经亮屏且仍停留在 Keyguard。
                return interactive && locked;
            }
            if (ModeConfig.GRIP_GUARD_SCOPE_SCREEN_OFF.equals(scope)) {
                return !interactive;
            }
            return !interactive || locked;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 把已经实机确认的 N1G KeyEvent 划分为四个用户可选类别。 */
    public static int inputType(KeyEvent event) {
        if (event == null) return 0;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_CAMERA: // 27，完整快门
            case KeyEvent.KEYCODE_FOCUS:  // 80，半按快门
                return ModeConfig.GRIP_INPUT_SHUTTER;
            case 168:
            case 169:
                return ModeConfig.GRIP_INPUT_ZOOM;
            case KeyEvent.KEYCODE_UNKNOWN:
                return ModeConfig.GRIP_INPUT_WHEEL;
            case 313:
                return ModeConfig.GRIP_INPUT_RECORD;
            default:
                // 未识别的新按键不因“全按键”标签被猜测屏蔽，避免误伤后续硬件。
                return 0;
        }
    }
}
