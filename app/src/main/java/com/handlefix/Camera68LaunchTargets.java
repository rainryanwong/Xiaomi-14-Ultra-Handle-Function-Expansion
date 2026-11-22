package com.handlefix;

/** 6.8 快捷模式的界面配置、system_server 发起值与相机端白名单。 */
public final class Camera68LaunchTargets {
    public static final String RECORD_LAUNCH_SOURCE = "handlefix_start_video_68";

    private Camera68LaunchTargets() {}

    public static String cameraModeForTarget(String target) {
        if (ModeConfig.GRIP_QUICK_LEGEND.equals(target)) return "LEGEND";
        if (ModeConfig.GRIP_QUICK_CAPTURE.equals(target)) return "CAPTURE";
        if (ModeConfig.GRIP_QUICK_VIDEO.equals(target)) return "VIDEO";
        if (ModeConfig.GRIP_QUICK_PRO_PHOTO.equals(target)) return "MANUAL";
        if (ModeConfig.GRIP_QUICK_PRO_VIDEO.equals(target)) return "VIDEO";
        if (ModeConfig.GRIP_QUICK_NIGHT.equals(target)) return "SUPER_NIGHT";
        if (ModeConfig.GRIP_QUICK_PORTRAIT.equals(target)) return "PORTRAIT";
        if (ModeConfig.GRIP_QUICK_DOCUMENT.equals(target)) return "DOC";
        if (ModeConfig.GRIP_QUICK_ULTRA_PIXEL.equals(target)) return "ULTRA_PIXEL";
        if (ModeConfig.GRIP_QUICK_STREET_FOLLOW.equals(target)
                || ModeConfig.GRIP_QUICK_STREET_225.equals(target)
                || requiresStreetStyleBridge(target)) return "STREET";
        return null;
    }

    public static boolean supportsTarget(String target) {
        return ModeConfig.GRIP_QUICK_NATIVE.equals(target) || cameraModeForTarget(target) != null;
    }

    public static boolean requiresStreetStyleBridge(String target) {
        return ModeConfig.GRIP_QUICK_STREET_229.equals(target)
                || ModeConfig.GRIP_QUICK_STREET_229_LEGEND.equals(target);
    }

    /** -1 表示采用相机自身的街拍样式；0 保留 CAMERA_MODE 的原生解析结果。 */
    public static int routeForTarget(String target) {
        if (ModeConfig.GRIP_QUICK_STREET_FOLLOW.equals(target)) return -1;
        if (ModeConfig.GRIP_QUICK_STREET_225.equals(target)) return 225;
        if (requiresStreetStyleBridge(target)) return 229;
        if (ModeConfig.GRIP_QUICK_PRO_VIDEO.equals(target)) return 180;
        return 0;
    }

    static boolean isSupportedRequest(String mode, int exact) {
        if (exact == 229 || exact == 225 || exact == -1) return "STREET".equals(mode);
        if (exact == 180) return "VIDEO".equals(mode);
        if (exact != 0 || mode == null) return false;
        switch (mode) {
            case "LEGEND": case "CAPTURE": case "VIDEO": case "MANUAL":
            case "SUPER_NIGHT": case "PORTRAIT": case "DOC": case "ULTRA_PIXEL":
                return true;
            default:
                return false;
        }
    }
}
