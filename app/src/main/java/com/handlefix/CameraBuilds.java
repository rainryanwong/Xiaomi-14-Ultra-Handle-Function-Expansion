package com.handlefix;

import android.content.pm.ApplicationInfo;

import de.robv.android.xposed.XposedHelpers;

/**
 * 已反查相机安装包的版本标识。
 *
 * <p>LSPosed 的 {@code handleLoadPackage} 发生在 {@code Application} 创建之前，不能依赖
 * {@code currentApplication()} 或 {@code PackageManager} 取得稳定版本。因此统一从加载参数的
 * {@link ApplicationInfo} 读取版本号，避免各功能各自实现不一致的回退逻辑。</p>
 */
public final class CameraBuilds {
    public static final long UNKNOWN_VERSION_CODE = -1L;

    /** 6.8.001770.0 used by the Aurora camera port. */
    public static final long CAMERA_68_1770_VERSION_CODE = 680017700L;

    public static boolean isCamera68Port(long versionCode) {
        return versionCode == CAMERA_68_1770_VERSION_CODE;
    }

    /** Phoenix-0.3.0（6.6.000510.0 基线）实机 APK 的 versionCode。 */
    public static final long PHOENIX_030_VERSION_CODE = 760000300L;

    /** Phoenix-0.7.23（同一 OS4 6.6.000510.0 基线）APK 的精确 versionCode。 */
    public static final long PHOENIX_0723_VERSION_CODE = 760000723L;

    /**
     * Phoenix 同一条 OS4 版本组沿用相同的版本编码前缀；后续仍以 OS4 6.6.510 为底包的
     * Phoenix 重打包通常只递增末段。该族标记只代表“可以尝试对应 OS4 适配器”，不等于所有
     * 功能都能直接复用：街拍特别款由 StreetSpecialProfile 核验 229 入口和能力门，依赖混淆
     * 描述符的功能还必须在相机进程内完成运行时核对。
     */
    private static final long PHOENIX_VERSION_FAMILY_DIVISOR = 10_000_000L;
    private static final long PHOENIX_VERSION_FAMILY =
            PHOENIX_030_VERSION_CODE / PHOENIX_VERSION_FAMILY_DIVISOR;

    /** 官方澎湃 OS3 相机 6.4.000370.0 的 versionCode。 */
    public static final long OFFICIAL_OS3_6_4_370_VERSION_CODE = 640003700L;

    /** 官方澎湃 OS3 相机 6.4.000530.0 的 versionCode。 */
    public static final long OFFICIAL_OS3_6_4_530_VERSION_CODE = 640005300L;

    /** 官方 HyperOS 4 相机 6.6.000510.0 的 versionCode。 */
    public static final long OFFICIAL_OS4_6_6_510_VERSION_CODE = 660005100L;

    /** Prometheus NRV-1.6.1（AllInOne 样本）的相机 versionCode。 */
    public static final long NRV_161_VERSION_CODE = 750005601L;

    /** Prometheus NRV-1.5.0 实机日志确认的相机 versionCode。 */
    public static final long NRV_150_VERSION_CODE = 750005500L;

    /** Prometheus OS1 5.1.001130.2 提取样本的旧相机 versionCode；旧 repeatCount 链不支持现代长按连拍。 */
    public static final long PROMETHEUS_V51_VERSION_CODE = 510011302L;

    /** Prometheus Camera V5.1 AllInOne 模块样本的相机 versionCode；使用现代 KeyEvent 链。 */
    public static final long PROMETHEUS_MODULE_V51_VERSION_CODE = 750005100L;

    /** NRV/V5.0 重打包使用的 750005xxx 版本号段；具体入口仍由运行时签名二次确认。 */
    private static final long PROMETHEUS_75_FAMILY_MIN = 750005000L;
    private static final long PROMETHEUS_75_FAMILY_MAX = 750005999L;

    private CameraBuilds() {
    }

    /**
     * 优先读取 API 28+ 的 {@code longVersionCode}；字段不可用时回退旧的 int
     * {@code versionCode}。当前已验证版本在 int 范围内，回退不损失精确性。
     */
    public static long readVersionCode(ApplicationInfo appInfo) {
        if (appInfo == null) {
            return UNKNOWN_VERSION_CODE;
        }
        try {
            Object value = XposedHelpers.getObjectField(appInfo, "longVersionCode");
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        } catch (Throwable ignored) {
            // Fall through: early process load may expose only the legacy int field.
        }
        try {
            return ((long) XposedHelpers.getIntField(appInfo, "versionCode")) & 0xffffffffL;
        } catch (Throwable t) {
            HandleFixModule.log("CameraBuilds: cannot read appInfo versionCode -> " + t);
            return UNKNOWN_VERSION_CODE;
        }
    }

    /** 只识别需要独立 15U 街拍映射的 Phoenix-0.3.0。 */
    public static boolean isPhoenix030(long versionCode) {
        return versionCode == PHOENIX_030_VERSION_CODE;
    }

    /**
     * Phoenix OS4 基线的长按连拍候选门。
     *
     * <p>0.3.0 与 0.7.23 已证明使用同一条 {@code $50} decision/lifecycle 链，
     * 所以后续仍以 6.6.000510.0 为底包、保持同一 OS4 版本组编码的 Phoenix 包可以
     * 自动进入候选。真正挂载前，{@link ShutterHoldBurstController} 还会在相机进程
     * 内核对 {@code onKeyDown/onKeyUp}、{@code performKeyClicked}、
     * {@code startNormalCapture}、{@code isNeedBurst} 与对应 synthetic lambda 的精确
     * descriptor；任一入口缺失或歧义时都会 fail closed。</p>
     */
    public static boolean isPhoenixHoldBurstSupported(long versionCode) {
        return isPhoenixOs4Family(versionCode);
    }

    /**
     * 识别仍沿用 Phoenix OS4 基线的版本号族。
     *
     * <p>这是给作者 LSP 多 LUT 风格动作使用的候选门，不代表其它 Phoenix 专属入口
     * 可以直接复用；需要精确 dex/映射的功能仍使用各自的版本门。若新包改变了
     * versionCode 编码族，应先反查样本再更新这里。</p>
     */
    public static boolean isPhoenixOs4Family(long versionCode) {
        return versionCode > 0
                && versionCode / PHOENIX_VERSION_FAMILY_DIVISOR == PHOENIX_VERSION_FAMILY;
    }

    /** 官方 6.4 两个 OS3 样本是否已反查出独立的原生徕卡入口。 */
    public static boolean isOfficialOs3LongLeicaSupported(long versionCode) {
        return versionCode == OFFICIAL_OS3_6_4_370_VERSION_CODE
                || versionCode == OFFICIAL_OS3_6_4_530_VERSION_CODE;
    }

    /** 官方 OS4 6.6.000510.0 是否已反查出原生徕卡入口。 */
    public static boolean isOfficialOs4LongLeicaSupported(long versionCode) {
        return versionCode == OFFICIAL_OS4_6_6_510_VERSION_CODE;
    }

    /**
     * 官方相机已反查出 fragmentId=209 专业参数工作区的版本。
     *
     * <p>6.6.000510 虽然与 OS3 的 FeatureUI 协议不同，但官方 dex 仍保留
     * q6.U0.S0(MotionEvent,boolean,String) 与 T9.A 工作区；它与 Phoenix 使用的
     * 是同一套 OS4 基线实现，因此由 OfficialParamPanelHooks 走独立的 OS4 分支，
     * 不复用 6.4.x 的 V3/X3 协议。</p>
     */
    public static boolean isOfficialParamPanelSupported(long versionCode) {
        return versionCode == OFFICIAL_OS3_6_4_370_VERSION_CODE
                || versionCode == OFFICIAL_OS3_6_4_530_VERSION_CODE
                || versionCode == OFFICIAL_OS4_6_6_510_VERSION_CODE;
    }

    /**
     * 是否已反查出官方相机长按徕卡 A 的安全入口。
     *
     * <p>6.4.000370.0/530.0 调用官方 {@code MainTopBar.onCvClick(View)}；
     * 6.6.000510.0 使用独立的 {@code V9.i0.s9(View)}。这些入口虽然最终都是
     * 原生经典/生动切换，但混淆类和组件实现不同，不能跨版本复用。</p>
     */
    public static boolean isOfficialLongLeicaSupported(long versionCode) {
        return isOfficialOs3LongLeicaSupported(versionCode)
                || isOfficialOs4LongLeicaSupported(versionCode);
    }

    /**
     * 作者 LSP 多 LUT 风格的版本候选门：Phoenix 放开同一 OS4 版本组，NRV 仍只
     * 保留已实测的 1.6.1；NRV 旧版本不能因为同属 Prometheus 就显示该功能。
     * ClassicStyleController 还会在相机进程检查真实 Store 方法签名，候选门不是能力证明。
     */
    public static boolean isClassicStyleSwitchSupported(long versionCode) {
        return isPhoenixOs4Family(versionCode) || versionCode == NRV_161_VERSION_CODE;
    }

    /**
     * 17U OS3 衍生的 V5.1/NRV 滤镜面板候选版本组。
     *
     * <p>版本号只缩小候选范围；运行时仍必须同时存在 K3.u1 按键入口、
     * f4.B 顶栏配置协议、f4.j 面板宿主和 FragmentBeauty。当前实机
     * NRV-1.3.2 为 750005302，同样属于该 K3 组。</p>
     */
    public static boolean isPrometheusLegacyFilterCandidate(long versionCode) {
        return versionCode >= PROMETHEUS_75_FAMILY_MIN
                && versionCode <= PROMETHEUS_75_FAMILY_MAX;
    }

    /**
     * C3 候选的 Prometheus 相机族。
     *
     * <p>旧 OS1 V5.1 提取样本使用 {@code performKeyClicked(int,String,int,boolean)}；
     * 模块版 V5.1/750005100 与 NRV 使用 {@code KeyEvent} 新式签名。这里只负责版本
     * 候选，DualStyleCaptureHooks 仍要求七个真实生命周期入口全部匹配后才会进入 ready
     * 状态。</p>
     */
    public static boolean isPrometheusDualStyleCandidate(long versionCode) {
        return versionCode == PROMETHEUS_V51_VERSION_CODE
                || versionCode == PROMETHEUS_MODULE_V51_VERSION_CODE
                || (versionCode >= PROMETHEUS_75_FAMILY_MIN
                && versionCode <= PROMETHEUS_75_FAMILY_MAX);
    }

    /**
     * 长按原生连拍的现代 KeyEvent 候选版本。
     *
     * <p>Phoenix 的 OS4 版本组只表示“可能沿用 OS4 6.6.000510.0 基线”；
     * controller 仍会在相机进程中核对真实 decision/lifecycle descriptor。NRV-1.3.2
     * 保留官方 OS4 的 versionCode=660005100，因此必须结合 Prometheus Profile 判断；
     * 版本号本身不能区分它与官方 6.6.510。Prometheus 模块版 V5.1 的
     * versionCode=750005100 使用现代 KeyEvent 长按链，但仍须在 Controller 内通过
     * 按键、首拍与连拍决策 descriptor 预检；旧 OS1 提取样本 versionCode=510011302 使用 repeatCount
     * 旧链，明确不在本轮现代事务适配范围内。</p>
     */
    public static boolean isModernHoldBurstCandidate(long versionCode,
            boolean prometheusProfile) {
        if (isPhoenixHoldBurstSupported(versionCode)) {
            return true;
        }
        if (prometheusProfile) {
            return versionCode == OFFICIAL_OS4_6_6_510_VERSION_CODE
                    || versionCode == PROMETHEUS_MODULE_V51_VERSION_CODE
                    || (versionCode >= PROMETHEUS_75_FAMILY_MIN
                    && versionCode <= PROMETHEUS_75_FAMILY_MAX);
        }
        return versionCode == OFFICIAL_OS3_6_4_370_VERSION_CODE
                || versionCode == OFFICIAL_OS3_6_4_530_VERSION_CODE
                || versionCode == OFFICIAL_OS4_6_6_510_VERSION_CODE;
    }
}
