package com.handlefix;

/**
 * 相机能力注册表的最小实现。
 *
 * <p>这里集中维护“可以尝试”与“已经确认可以执行”的边界。版本号只能筛选候选
 * 相机，真实 Hook 仍必须在各适配器内完成方法签名和读回校验；未确认的能力返回
 * false，让 UI 隐藏选项、运行时放行原生行为，避免把一个版本的混淆入口硬套到另一版。</p>
 */
public final class CameraCapabilityRegistry {
    private CameraCapabilityRegistry() {
    }

    public static boolean supportsNativeLeica(long versionCode, boolean prometheusProfile) {
        if (prometheusProfile) {
            // Phoenix/OS4 使用 PhoenixLeicaHooks；NRV1.6.1 使用已验证的作者存储链。
            // 其他 Prometheus/NRV 版本仍可能拥有同一入口，但在反查 descriptor 和
            // 运行时读回之前不能把它们标为“已确认”，否则 UI 会保存不可用动作。
            return CameraBuilds.isPhoenixOs4Family(versionCode)
                    || versionCode == CameraBuilds.NRV_161_VERSION_CODE;
        }
        return CameraBuilds.isOfficialLongLeicaSupported(versionCode);
    }

    public static boolean supportsParamPanel(long versionCode, boolean prometheusProfile) {
        return prometheusProfile || CameraBuilds.isOfficialParamPanelSupported(versionCode);
    }

    /**
     * C3 能力门。
     *
     * <p>模式范围复用已实测的经典/生动二态白名单。版本号只决定“允许探测”：
     * Phoenix/官方 370、510/NRV 1.6.1/V5.1 的 Camera2Module 已逐包静态核对；
     * NRV 的 750005xxx 版本组作为候选，但运行时仍必须精确匹配按键、完成、首帧和
     * 原生快门入口，缺少任一项都会保持 ready=false 并放行原生行为。</p>
     */
    public static boolean supportsDualStyleCapture(long versionCode,
            boolean prometheusProfile, int mode) {
        if (CameraBuilds.isCamera68Port(versionCode)) {
            return ModeConfig.isCamera68Mapping()
                    && (mode == ModeConfig.MODE_CAPTURE || mode == ModeConfig.MODE_STREET);
        }
        if (!ModeConfig.isLeicaStyleMode(mode)) {
            return false;
        }
        if (CameraBuilds.isPhoenixOs4Family(versionCode)) {
            return true;
        }
        if (prometheusProfile) {
            return CameraBuilds.isPrometheusDualStyleCandidate(versionCode);
        }
        return CameraBuilds.isOfficialLongLeicaSupported(versionCode);
    }

    /** 兼容旧调用；按当前映射方案补入 Profile。 */
    public static boolean supportsDualStyleCapture(long versionCode, int mode) {
        return supportsDualStyleCapture(versionCode, ModeConfig.isPromFixEnabled(), mode);
    }

    /**
     * 目标相机族均保留 {@code com.android.camera.Camera.onKeyDown/onKeyUp}；
     * 真正消费前还会校验 Focus keyCode/scanCode 与小米手柄 VID/PID。
     */
    public static boolean supportsHalfFocusPolicy(long versionCode) {
        return versionCode != CameraBuilds.UNKNOWN_VERSION_CODE && versionCode > 0;
    }

    /**
     * 原生长按高速连拍能力门。只有普通拍照 163 与街拍 225 具备原生连拍闭环；
     * 夜景、人像、专业拍照明确排除，避免通过 Hook 制造不存在的能力。
     */
    public static boolean supportsHoldBurst(long versionCode,
            boolean prometheusProfile, int mode) {
        if (CameraBuilds.isCamera68Port(versionCode)) {
            return ModeConfig.isCamera68Mapping()
                    && (mode == ModeConfig.MODE_CAPTURE || mode == ModeConfig.MODE_STREET);
        }
        return (mode == ModeConfig.MODE_CAPTURE || mode == ModeConfig.MODE_STREET)
                && CameraBuilds.isModernHoldBurstCandidate(versionCode, prometheusProfile);
    }
}
