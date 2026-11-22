package com.handlefix;

import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * v4.0-config 模式感知规则表
 * 配置为 JSON：{"enabled":true,"keys":{"record":{"0":"0","163":"A",...},...}}
 * 功能用单字母编号，模式用 moduleIndex 数字，0 = 跟随设置页。
 * 跨进程：模块 App（com.handlefix.all）用 root 写入，相机进程 hook 直接读。
 */
public final class ModeConfig {

    public static final String CONF_DIR = "/sdcard/Download/HandleFixConfig";
    public static final String BACKUP_DIR = CONF_DIR + "/backups";
    // 生效配置文件放到隐藏目录 .config 里（避免用户在导入时误选）
    public static final String CONF_HIDDEN_DIR = CONF_DIR + "/.config";
    public static final String CONF_PATH_1 = "/data/local/tmp/handlefix-mode.conf";
    public static final String CONF_PATH_2 = CONF_HIDDEN_DIR + "/handlefix-mode.conf";
    /**
     * system_server 专用配置镜像。
     *
     * <p>{@code /data/local/tmp} 在量产系统上标记为 {@code shell_data_file}，即使文件
     * chmod 0666，SELinux 仍会拒绝 system_server 打开它。系统级录像键 Hook 因而会把
     * “任意界面快捷录像”误读为关闭。这里使用 {@code /data/system} 下、由 system
     * 持有且标记为 {@code system_data_file} 的只读镜像；相机/App 无权读取时会自然回退
     * 到原有路径，不扩大任一进程的权限。</p>
     */
    public static final String CONF_SYSTEM_DIR = "/data/system/handlefix";
    public static final String CONF_PATH_SYSTEM = CONF_SYSTEM_DIR + "/handlefix-mode.conf";
    // 兼容旧位置（只读迁移，不再写入）
    public static final String CONF_PATH_V76 = CONF_DIR + "/handlefix-mode.conf";
    public static final String CONF_PATH_OLD = "/sdcard/Download/handlefix-mode.conf";

    /** Phoenix 街拍特别款的顶层选项键；与按键 rules 分离，供相机进程加载时读取一次。 */
    public static final String OPTION_STREET_SPECIAL = "streetSpecial";
    /** Optional camera-local virtual grip, backed up in the same JSON object. */
    public static final String OPTION_STREET_SPECIAL_WITHOUT_GRIP = "streetSpecialWithoutGrip";

    /**
     * 徕卡经典风格轮换的用户白名单。值按相机 versionCode 分桶，保存稳定的风格 ID，
     * 不保存会随语言/作者 LSP 变化的显示名称。未出现的版本键表示“尚未自定义”，
     * 运行时应默认使用作者当前返回的全部目录。
     */
    public static final String OPTION_CLASSIC_STYLE_SELECTIONS = "classicStyleSelections";

    /** 系统级录像键深长按策略；兼容旧版 boolean，默认保持原生。 */
    public static final String OPTION_GLOBAL_RECORD_VIDEO = "globalRecordLongVideo";
    /** 系统级录像键长按响应时间；与相机前台的短长按/深长按阈值相互独立。 */
    public static final String OPTION_GLOBAL_RECORD_LONG_PRESS_MS = "globalRecordLongPressMs";
    /** 手柄锁屏/息屏输入保护总开关；缺失或旧备份默认关闭。 */
    public static final String OPTION_GRIP_INPUT_GUARD = "gripInputGuard";
    /** 输入保护生效场景；旧配置缺失时保持原来的“锁屏和息屏”行为。 */
    public static final String OPTION_GRIP_INPUT_GUARD_SCOPE = "gripInputGuardScope";
    /** 需要屏蔽的手柄输入位掩码；旧配置缺失时保持原来的“全按键”行为。 */
    public static final String OPTION_GRIP_INPUT_GUARD_MASK = "gripInputGuardMask";
    /** 相机外长拨变焦拨杆的两个方向；native 表示完全保留系统原生行为。 */
    public static final String OPTION_GRIP_QUICK_ZOOM_IN = "gripQuickZoomIn";
    public static final String OPTION_GRIP_QUICK_ZOOM_OUT = "gripQuickZoomOut";
    /** 相机外长拨的独立响应时间和成功振动，不复用录像键或相机内长按阈值。 */
    public static final String OPTION_GRIP_QUICK_LONG_PRESS_MS = "gripQuickLongPressMs";
    public static final String OPTION_GRIP_QUICK_HAPTIC = "gripQuickHaptic";
    public static final String GRIP_GUARD_SCOPE_BOTH = "both";
    public static final String GRIP_GUARD_SCOPE_KEYGUARD = "keyguard";
    public static final String GRIP_GUARD_SCOPE_SCREEN_OFF = "screenOff";
    public static final int GRIP_INPUT_SHUTTER = 1;
    public static final int GRIP_INPUT_ZOOM = 1 << 1;
    public static final int GRIP_INPUT_WHEEL = 1 << 2;
    public static final int GRIP_INPUT_RECORD = 1 << 3;
    public static final int GRIP_INPUT_ALL = GRIP_INPUT_SHUTTER | GRIP_INPUT_ZOOM
            | GRIP_INPUT_WHEEL | GRIP_INPUT_RECORD;
    public static final String GRIP_QUICK_NATIVE = "native";
    public static final String GRIP_QUICK_LEGEND = "legend";
    public static final String GRIP_QUICK_STREET_FOLLOW = "streetFollow";
    public static final String GRIP_QUICK_CAPTURE = "capture";
    public static final String GRIP_QUICK_STREET_225 = "street225";
    public static final String GRIP_QUICK_STREET_229 = "street229";
    /** 仍进入 229，但本次启动强制使用另一套 FragmentEquipStreetNew 街拍 UI。 */
    public static final String GRIP_QUICK_STREET_229_LEGEND = "street229Legend";
    public static final String GRIP_QUICK_VIDEO = "video";
    public static final String GRIP_QUICK_PRO_PHOTO = "proPhoto";
    public static final String GRIP_QUICK_PRO_VIDEO = "proVideo";
    public static final String GRIP_QUICK_NIGHT = "night";
    public static final String GRIP_QUICK_PORTRAIT = "portrait";
    public static final String GRIP_QUICK_DOCUMENT = "document";
    public static final String GRIP_QUICK_ULTRA_PIXEL = "ultraPixel";
    /**
     * system_server 与 Phoenix 相机进程之间的一次性精确模式路由标记。
     *
     * <p>保留早期街拍专用键用于旧测试配置兼容；新入口统一写 QUICK_MODE_TARGET。
     * 只有带有 HandleFix 私有 launch source 的 Phoenix 启动事务才会读取这些字段。</p>
     */
    public static final String EXTRA_HANDLEFIX_QUICK_MODE_TARGET =
            "com.handlefix.extra.QUICK_MODE_TARGET";
    public static final String EXTRA_HANDLEFIX_QUICK_STREET_TARGET =
            "com.handlefix.extra.QUICK_STREET_TARGET";
    /** 仅本次 229 快捷启动使用另一套街拍 UI；相机接收后立即移除并消费。 */
    public static final String EXTRA_HANDLEFIX_QUICK_STREET_LEGEND_UI =
            "com.handlefix.extra.QUICK_STREET_LEGEND_UI";
    public static final String GRIP_QUICK_LAUNCH_SOURCE = "handlefix_grip_quick_launch";
    public static final String GLOBAL_RECORD_NATIVE = "native";
    public static final String GLOBAL_RECORD_OPEN = "open";
    public static final String GLOBAL_RECORD_START = "start";
    /** 仅由 HandleFix system_server 启动链写入，供相机进程接回原生 QuickVideo 状态机。 */
    public static final String EXTRA_HANDLEFIX_START_RECORDING =
            "com.handlefix.extra.START_VIDEO_RECORDING";
    /** C6 半按对焦策略：原生 / 禁用半按锁焦 / 相机原生 FocusManager 中心单次 AF。 */
    public static final String OPTION_HALF_FOCUS_POLICY = "halfFocusPolicy";
    public static final String HALF_FOCUS_NATIVE = "native";
    public static final String HALF_FOCUS_DISABLE = "disable";
    public static final String HALF_FOCUS_CENTER = "center";

    /** 普通拍照/街拍的配置键；快门短按与 C3 长按只在这两个模式有冲突语义。 */
    public static final int MODE_CAPTURE = 163;
    public static final int MODE_STREET = 225;
    /** Phoenix-0.3.0 街拍特别款的隐藏 moduleId，不会出现在模块自己的模式配置页。 */
    private static final int MODE_STREET_SPECIAL = 229;

    /**
     * 仅由通过版本门的 StreetSpecialHooks 在相机进程启动时置为 true。
     *
     * <p>特别款 229 是普通街拍 225 的手柄专属 UI 变体，而不是另一套应由用户单独配置的
     * 拍摄模式。若按原始 229 查 JSON，会落到全局 {@code "0"}，使四个按键重新跟随相机
     * 设置页。将别名集中在配置读取层，可让所有现有按键回调（含录像键长按）统一继承
     * 225 的规则，避免在各 Hook 中重复补丁。</p>
     */
    private static volatile boolean STREET_SPECIAL_CONFIG_ALIAS_ENABLED = false;

    /**
     * 相机进程的按键/拨轮回调可能在很短时间内连续触发。配置跨进程保存在文件中，
     * 因此不能在每个回调里重复打开文件并解析 JSON。此缓存最多延迟 200ms；保存后
     * 本进程会主动失效，其他进程通过文件元数据自动刷新。
     */
    private static final long CONFIG_CACHE_TTL_MS = 200L;
    private static final Object CONFIG_CACHE_LOCK = new Object();
    private static volatile ConfigSnapshot CONFIG_CACHE = null;

    /** 已解析配置及其来源文件元数据；JSONObject 只在内部作为只读快照使用。 */
    private static final class ConfigSnapshot {
        final JSONObject config;
        final String path;
        final long lastModified;
        final long length;
        final long checkedAt;

        ConfigSnapshot(JSONObject config, String path, long lastModified, long length, long checkedAt) {
            this.config = config;
            this.path = path;
            this.lastModified = lastModified;
            this.length = length;
            this.checkedAt = checkedAt;
        }

        boolean matches(File file) {
            return file != null
                    && path != null
                    && path.equals(file.getPath())
                    && lastModified == file.lastModified()
                    && length == file.length();
        }

        ConfigSnapshot withCheckedAt(long now) {
            return new ConfigSnapshot(config, path, lastModified, length, now);
        }
    }

    // 按键标识
    public static final String KEY_RECORD  = "record";
    public static final String KEY_SHUTTER = "shutter";
    public static final String KEY_WHEEL   = "wheel";
    public static final String KEY_ZOOM    = "zoom";

    // 功能字母编号
    public static final String F_DEFAULT = "0"; // 跟随设置页
    public static final String F_LEICA   = "A";
    public static final String F_QUICK   = "B";
    public static final String F_SHUTTER = "C";
    public static final String F_AWB     = "D";
    public static final String F_ZOOM    = "E";
    public static final String F_EIS     = "F";
    public static final String F_METER   = "G";
    public static final String F_PEAK    = "H";
    public static final String F_FORMAT  = "I";
    public static final String F_WORKSP  = "J";
    public static final String F_EV      = "K";
    public static final String F_ISO     = "L";
    public static final String F_APERT   = "M";
    public static final String F_ET      = "N";
    public static final String F_FOCUSP  = "O";
    public static final String F_BOKEH   = "P";
    public static final String F_FILTER  = "Q";
    public static final String F_AUTOEXP = "R"; // 测光模式(自动曝光)
    public static final String F_PICSTYLE = "S"; // 自定义摄影风格
    public static final String F_SOUND    = "T"; // 声音设置
    public static final String F_AUDIOT   = "U"; // 收音类型
    public static final String F_RATION   = "V"; // 画幅比例
    public static final String F_50MP     = "W"; // 50MP
    public static final String F_EXPFEED  = "X"; // 曝光反馈
    public static final String F_DEFZOOM  = "Y"; // 默认变焦(变焦拨杆)
    public static final String F_CONTZOOM = "Z"; // 连续变焦(变焦拨杆)
    public static final String F_BURST    = "1"; // 高速连拍(快门按键)
    public static final String F_PREVIEW  = "2"; // 预览照片(录像键长按)
    // Phoenix OS4 版本族/NRV-1.6.1 附带 LSP 的「徕卡经典风格」多 LUT 循环。
    // 它不是 F_LEICA 的经典/生动两态，也不是相机原生 attr_* 值；仅由
    // ClassicStyleController 在运行时确认 Store API 的作者相机进程消费。独立数字码让 OEM 映射在
    // 不支持的相机上自然回退原来的摄影手柄设置。
    public static final String F_PHOENIX_CLASSIC_STYLE = "3";
    /**
     * 专业拍照：录像键短按打开相机原生参数预设工作台。
     *
     * <p>这是录像键的独立动作码，不等同于 F_WORKSP(J) 的波轮映射：J 只负责在
     * q6.U0.S0(MotionEvent, boolean, String) 上把波轮事件改成 attr_workspace；录像键
     * 则由 PhoenixClassicStyleHooks（q6.U0）或 LegacyParamPanelHooks（K3.u1）触发
     * 对应版本的同一原生工作区入口。面板本身的创建、参数选择、波轮接管和关闭都必须
     * 交给相机 Fragment，不在模块里复制一套 UI 状态。</p>
     *
     * <p>Phoenix 当前按 q6.U0/T9.A 实现二次关闭；Prometheus/V5.1/NRV 旧版按
     * K3.u1/FragmentManualWorkspaceBottom 复用同一原生工作区生命周期；官方 OS3
     * 6.4.000370/530 则由 OfficialParamPanelHooks 使用各自 FeatureUI 协议触发同一
     * fragmentId=209。OS4/其它官方版本仍由 UI 过滤，不会误套这些移植包链。</p>
     */
    /** 录像键/波轮共用的专业参数预设动作码；JSON 仍保存为历史值 4。 */
    public static final String F_PARAM_PANEL = "4";
    /** 兼容旧源码引用；功能已不再只属于 Phoenix。 */
    @Deprecated
    public static final String F_PHOENIX_PARAM_PANEL = F_PARAM_PANEL;
    public static final String LONG_KEY   = "long"; // 录像键长按配置子节点
    /** 深长按配置子节点；旧配置没有该节点时由分发器回退到 LONG_KEY。 */
    public static final String DEEP_LONG_KEY = "deepLong";
    /** 快门键长按徕卡经典/生动轮拍；没有真实相机能力门的版本不会消费该动作。 */
    public static final String F_DUAL_STYLE_CAPTURE = "5";
    /** 快门键长按原生高速连拍；仅在精确能力门和 163/225 模式消费。 */
    public static final String F_HOLD_BURST = "6";

    /**
     * 原生徕卡经典/生动二态已确认可用的拍照模式。
     *
     * <p>长按录像键 A 的过滤表与短按 A 共用这一白名单；不在这里的模式即使
     * JSON 中残留 A，也不会被长按分支消费。Phoenix 的多 LUT 风格仍使用独立
     * {@link #F_PHOENIX_CLASSIC_STYLE}，不能因为同样含有“徕卡经典”字样而混入。</p>
     */
    public static boolean isLeicaStyleMode(int mode) {
        return mode == 163 || mode == 167 || mode == 173 || mode == 171 || mode == 225
                || isStreetSpecialConfigAliasMode(mode);
    }

    // 字母 -> 相机原生功能值（attr 字符串）；A(leica) 由模块自己处理，返回 null 走模块
    public static String funcToAttr(String f) {
        if (f == null) return null;
        switch (f) {
            case F_QUICK:  return "quick_recording";
            case F_SHUTTER: return "attr_shutter";
            case F_AWB:    return "attr_awb";
            case F_ZOOM:   return "attr_zoom_segment";
            case F_EIS:    return "attr_super_eis";
            case F_METER:  return "attr_metering_weight";
            case F_PEAK:   return "attr_focus_peak";
            case F_FORMAT: return "attr_format";
            case F_WORKSP: return "attr_workspace";
            case F_EV:     return "attr_ev";
            case F_ISO:    return "attr_iso";
            case F_APERT:  return "attr_variable_aperture";
            case F_ET:     return "attr_et";
            case F_FOCUSP: return "attr_focus_position";
            case F_BOKEH:  return "attr_bokeh_ratio";
            case F_FILTER: return "attr_filter";
            case F_AUTOEXP: return "attr_auto_exposure";
            case F_PICSTYLE: return "attr_custom_picturestyle_new";
            case F_SOUND: return "attr_sound_setting_click";
            case F_AUDIOT: return "attr_ai_audio_pickup_type";
            case F_RATION: return "attr_picture_ration";
            case F_50MP: return "attr_ultra_pixel";
            case F_EXPFEED: return "attr_exposure_feedback";
            case F_DEFZOOM: return "attr_zoom"; // 默认变焦(待确认)
            case F_CONTZOOM: return "attr_continuous_zoom";
            case F_BURST: return "attr_burst"; // 高速连拍(待确认)
            default: return null; // 0=跟随设置页, A=leica(模块处理)
        }
    }

    // 字母 -> 官方相机（14U ROM 自带 MiuiCamera）原生功能值。
    // 官方四个手柄按键的合法值（反编译 resources 确认）：
    //   录像键(button): quick_recording / attr_shutter / attr_metering_weight / attr_auto_exposure /
    //                   attr_awb / attr_custom_picturestyle_new / attr_super_eis / attr_exposure_feedback /
    //                   attr_focus_peak / attr_sound_setting_click / attr_ai_audio_pickup_type /
    //                   attr_picture_ration / attr_ultra_pixel / attr_leica_style / attr_format
    //   快门键(snap):   capture(单张) / burst_shot(连拍) —— 官方 h3/a.a() 只比较这两个字符串
    //   波轮(wheel):    attr_ev / attr_variable_aperture / attr_et / attr_iso / attr_awb /
    //                   attr_focus_position / attr_bokeh_ratio
    //   拨杆(zoom):     attr_default_zoom / attr_continuous_zoom / attr_zoom_segment
    public static String funcToAttrOem(String f) {
        if (f == null) return null;
        switch (f) {
            case F_LEICA:  return "attr_leica_style";          // 官方录像键原生支持徕卡切换
            case F_QUICK:  return "quick_recording";
            case F_SHUTTER: return "capture";                  // 官方快门=单张拍摄
            case F_AWB:    return "attr_awb";
            case F_ZOOM:   return "attr_zoom_segment";
            case F_EIS:    return "attr_super_eis";
            case F_METER:  return "attr_metering_weight";
            case F_PEAK:   return "attr_focus_peak";
            case F_FORMAT: return "attr_format";
            case F_WORKSP: return null;                        // 官方录像键无工作区
            case F_EV:     return "attr_ev";
            case F_ISO:    return "attr_iso";
            case F_APERT:  return "attr_variable_aperture";
            case F_ET:     return "attr_et";
            case F_FOCUSP: return "attr_focus_position";
            case F_BOKEH:  return "attr_bokeh_ratio";
            // 官方摄影手柄映射表没有 attr_filter；已适配版本由
            // NativeFilterPanelHooks 调用相机自己的面板入口，不能在这里伪造原生映射值。
            case F_FILTER: return null;
            case F_AUTOEXP: return "attr_auto_exposure";
            case F_PICSTYLE: return "attr_custom_picturestyle_new";
            case F_SOUND: return "attr_sound_setting_click";
            case F_AUDIOT: return "attr_ai_audio_pickup_type";
            case F_RATION: return "attr_picture_ration";
            case F_50MP: return "attr_ultra_pixel";
            case F_EXPFEED: return "attr_exposure_feedback";
            case F_DEFZOOM: return "attr_default_zoom";        // 官方叫 default_zoom
            case F_CONTZOOM: return "attr_continuous_zoom";
            case F_BURST: return "burst_shot";                 // 官方快门=高速连拍
            default: return null;
        }
    }

    /**
     * 官方相机按键功能值映射（区分按键）：
     * 录像键 C = 官方「快门释放模式」（attr_shutter，按录像键切换单张/连拍）；
     * 快门键 C = 官方「单张拍摄」（capture，官方快门键只有 单张/高速连拍 两个选项）。
     * 其余功能与 funcToAttrOem(f) 一致。
     */
    public static String funcToAttrOem(String key, String f) {
        if (f == null) return null;
        if (KEY_RECORD.equals(key) && F_SHUTTER.equals(f)) return "attr_shutter";
        return funcToAttrOem(f);
    }

    /** 检测模块是否已获取 Root 权限（su 可执行且 uid=0） */
    public static boolean hasRoot() {
        try {
            Process p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            p.waitFor();
            return line != null && line.contains("uid=0");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 总开关：启用“Prometheus Cam 手柄修复方案”（默认开；切回系统自带相机时关闭） */
    public static boolean isPromFixEnabled() {
        try {
            JSONObject cfg = cachedConfig();
            return cfg == null || cfg.optBoolean("promFix", true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** V5.1 开关：相机是否已内置手柄修复（共有补丁：fix1-5/S0/徕卡切换/设置页注入）。
     *  开 = 老版 PrometheusCam（V5.1 等）未内置，模块挂载全部补丁；
     *  关 = 新版（NRV-1.3.2 等）已内置/由作者 LSP 提供，跳过共有补丁避免双重挂载（默认关）。 */
    public static boolean isV51Enabled() {
        try {
            JSONObject cfg = cachedConfig();
            return cfg != null && cfg.optBoolean("v51", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Phoenix OS4 版本组的「街拍特别款」开关（默认关闭）。
     *
     * 该开关只在相机进程加载时读取，并且只随 Prometheus Cam 映射方案生效。开启后由
     * StreetSpecialHooks 在共同原生入口通过校验后放行机型能力门；相机原生的“街拍特别款”
     * 偏好仍由相机处理，无手柄状态由独立子选项控制。
     */
    public static boolean isStreetSpecialForceEnabled() {
        try {
            JSONObject cfg = cachedConfig();
            return cfg != null && cfg.optBoolean(OPTION_STREET_SPECIAL, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isStreetSpecialWithoutGripEnabled() {
        JSONObject cfg = cachedConfig();
        return cfg != null && cfg.optBoolean("enabled", true) && cfg.optBoolean("promFix", true)
                && cfg.optBoolean(OPTION_STREET_SPECIAL, false)
                && cfg.optBoolean(OPTION_STREET_SPECIAL_WITHOUT_GRIP, false);
    }

    /**
     * 返回某个相机版本是否曾由用户保存过风格轮换白名单。
     *
     * <p>{@code false} 与“已保存但列表为空”必须区分：前者代表首次使用，应默认全选
     * 作者当前目录；后者是损坏/旧配置，运行时会安全回退到全部有效项。</p>
     */
    public static boolean hasClassicStyleSelection(long cameraVersionCode) {
        try {
            JSONObject cfg = cachedConfig();
            JSONObject selections = cfg == null
                    ? null : cfg.optJSONObject(OPTION_CLASSIC_STYLE_SELECTIONS);
            return selections != null && selections.has(Long.toString(cameraVersionCode));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 读取某版本的稳定风格 ID。返回 {@code null} 表示从未配置，返回空列表表示曾配置
     * 但没有有效项；调用方必须再与相机进程实时目录求交集，不能直接信任旧配置。
     */
    public static List<String> getClassicStyleSelection(long cameraVersionCode) {
        try {
            JSONObject cfg = cachedConfig();
            JSONObject selections = cfg == null
                    ? null : cfg.optJSONObject(OPTION_CLASSIC_STYLE_SELECTIONS);
            JSONArray array = selections == null
                    ? null : selections.optJSONArray(Long.toString(cameraVersionCode));
            if (array == null) {
                return null;
            }
            Set<String> unique = new LinkedHashSet<String>();
            for (int i = 0; i < array.length(); i++) {
                String id = array.optString(i, null);
                if (id != null && id.trim().length() > 0) {
                    unique.add(id);
                }
            }
            return new ArrayList<String>(unique);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 保存某版本的稳定风格 ID。只由模块设置页调用；运行时仍会把它与作者 LSP 当前
     * 返回的 {@code modelIds()} 求交集，因此作者删改目录不会让旧配置变成未知值。
     */
    public static boolean saveClassicStyleSelection(long cameraVersionCode,
            Collection<String> styleIds) {
        if (cameraVersionCode < 0L || styleIds == null) {
            return false;
        }
        try {
            JSONObject cfg = read();
            if (cfg == null) {
                cfg = new JSONObject();
                cfg.put("enabled", true);
                cfg.put("promFix", true);
                cfg.put("keys", new JSONObject());
            }
            JSONObject selections = cfg.optJSONObject(OPTION_CLASSIC_STYLE_SELECTIONS);
            if (selections == null) {
                selections = new JSONObject();
                cfg.put(OPTION_CLASSIC_STYLE_SELECTIONS, selections);
            }
            JSONArray array = new JSONArray();
            Set<String> unique = new LinkedHashSet<String>();
            for (String id : styleIds) {
                if (id != null && id.trim().length() > 0) {
                    unique.add(id.trim());
                }
            }
            for (String id : unique) {
                array.put(id);
            }
            selections.put(Long.toString(cameraVersionCode), array);
            return writeViaRoot(cfg);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 开启 Phoenix 街拍特别款对普通街拍配置的继承。
     *
     * <p>此方法刻意不是由 JSON 读取直接决定：只有 StreetSpecialHooks 已完成精确版本门控的
     * 相机进程才可调用，避免其他相机恰好使用 moduleId 229 时被误映射。</p>
     */
    static void enableStreetSpecialConfigAlias() {
        STREET_SPECIAL_CONFIG_ALIAS_ENABLED = true;
    }

    /**
     * 当前相机进程是否已把街拍特别款 229 安全映射到普通街拍 225 的配置。
     *
     * <p>供需要先做运行模式白名单、再读取配置的控制器使用。不能仅凭 229 放行：
     * 只有 {@link StreetSpecialHooks} 完成 Phoenix 能力预检后，该别名才成立。</p>
     */
    public static boolean isStreetSpecialConfigAliasMode(int mode) {
        return STREET_SPECIAL_CONFIG_ALIAS_ENABLED && mode == MODE_STREET_SPECIAL;
    }

    /** 某按键的“启用该按键规则”开关（默认开；关闭后该按键所有模式配置失效=跟随设置页） */
    public static boolean isKeyEnabled(String key) {
        return isKeyEnabled(cachedConfig(), key);
    }

    /** 同一配置快照内判断按键规则是否启用，供 getFunc/getFuncLong 避免二次读盘。 */
    private static boolean isKeyEnabled(JSONObject cfg, String key) {
        try {
            if (cfg == null) return true;
            JSONObject keys = cfg.optJSONObject("keys");
            if (keys == null) return true;
            JSONObject k = keys.optJSONObject(key);
            if (k == null) return true;
            return k.optBoolean("enabled", true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 是否显示模式编号/按键码等详细信息（默认关，仅 UI 显示用） */
    public static boolean isShowDetail() {
        try {
            JSONObject cfg = cachedConfig();
            return cfg != null && cfg.optBoolean("showDetail", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 开发者选项：启用后 UI 过滤表失效（全部功能可选） */
    public static boolean isDevMode() {
        try {
            JSONObject cfg = cachedConfig();
            return cfg != null && cfg.optBoolean("devMode", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 读取配置副本（供 UI/备份等可能修改 JSONObject 的调用方使用）。
     * Hook 热路径请使用内部 cachedConfig()，避免序列化副本。
     */
    public static JSONObject read() {
        JSONObject cfg = cachedConfig();
        if (cfg == null) return null;
        try {
            return new JSONObject(cfg.toString());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 返回只读缓存快照；配置文件变化最多在 200ms 内被相机进程感知。 */
    private static JSONObject cachedConfig() {
        long now = SystemClock.uptimeMillis();
        ConfigSnapshot snapshot = CONFIG_CACHE;
        if (snapshot != null && now - snapshot.checkedAt < CONFIG_CACHE_TTL_MS) {
            return snapshot.config;
        }
        synchronized (CONFIG_CACHE_LOCK) {
            snapshot = CONFIG_CACHE;
            if (snapshot != null && now - snapshot.checkedAt < CONFIG_CACHE_TTL_MS) {
                return snapshot.config;
            }
            File preferred = firstReadableConfigFile();
            if (snapshot != null && snapshot.matches(preferred)) {
                CONFIG_CACHE = snapshot.withCheckedAt(now);
                return snapshot.config;
            }
            CONFIG_CACHE = loadConfigSnapshot(now);
            return CONFIG_CACHE.config;
        }
    }

    /** 找到优先级最高的可读配置文件，用于不解析 JSON 的快速元数据检查。 */
    private static File firstReadableConfigFile() {
        for (String p : configReadPaths()) {
            try {
                File f = new File(p);
                if (f.exists() && f.canRead()) {
                    return f;
                }
            } catch (Throwable t) {
                // try next
            }
        }
        return null;
    }

    /** 首次读取或文件元数据变化时解析 JSON；损坏的新文件会继续尝试旧兼容路径。 */
    private static ConfigSnapshot loadConfigSnapshot(long now) {
        for (String p : configReadPaths()) {
            try {
                File f = new File(p);
                if (f.exists() && f.canRead()) {
                    String s = readAll(f);
                    if (s != null && s.trim().length() > 0) {
                        return new ConfigSnapshot(new JSONObject(s), f.getPath(),
                                f.lastModified(), f.length(), now);
                    }
                }
            } catch (Throwable t) {
                // try next compatible path
            }
        }
        return new ConfigSnapshot(null, null, -1L, -1L, now);
    }

    /** 写入/迁移成功后让当前进程下一次读取立即刷新；其他进程走文件元数据检测。 */
    private static void invalidateCache() {
        CONFIG_CACHE = null;
    }

    /** 取某按键在当前模式下的功能字母；未配置则回退 0（跟随设置页） */
    public static String getFunc(String key, int mode) {
        try {
            JSONObject cfg = cachedConfig();
            if (cfg == null) return F_DEFAULT;
            if (!cfg.optBoolean("enabled", true)) return F_DEFAULT;
            if (!isKeyEnabled(cfg, key)) return F_DEFAULT; // 该按键规则未启用
            JSONObject keys = cfg.optJSONObject("keys");
            if (keys == null) return F_DEFAULT;
            JSONObject keyObj = keys.optJSONObject(key);
            if (keyObj == null) return F_DEFAULT;
            String v = keyObj.optString(String.valueOf(configModeFor(mode)), null);
            if (v == null || v.length() == 0) {
                v = keyObj.optString("0", F_DEFAULT);
            }
            return v;
        } catch (Throwable t) {
            return F_DEFAULT;
        }
    }

    /**
     * 录像键/快门键长按配置：keys.&lt;key&gt;.long.&lt;mode&gt;；录像键支持预览和徕卡二态，
     * 快门键的 C3 动作由独立能力门读取。未配置时返回 0（跟随原生行为）。
     */
    public static String getFuncLong(String key, int mode) {
        try {
            JSONObject cfg = cachedConfig();
            if (cfg == null) return F_DEFAULT;
            if (!cfg.optBoolean("enabled", true)) return F_DEFAULT;
            if (!isKeyEnabled(cfg, key)) return F_DEFAULT;
            JSONObject keys = cfg.optJSONObject("keys");
            if (keys == null) return F_DEFAULT;
            JSONObject keyObj = keys.optJSONObject(key);
            if (keyObj == null) return F_DEFAULT;
            JSONObject longObj = keyObj.optJSONObject(LONG_KEY);
            if (longObj == null) return F_DEFAULT;
            String v = longObj.optString(String.valueOf(configModeFor(mode)), null);
            if (v == null || v.length() == 0) {
                v = longObj.optString("0", F_DEFAULT);
            }
            return v;
        } catch (Throwable t) {
            return F_DEFAULT;
        }
    }

    /**
     * 读取深长按动作：keys.<key>.deepLong.<mode>。
     *
     * <p>返回 0 表示用户没有配置深长按。不要在这里回退到 long，调用方需要知道
     * 是否存在独立配置，才能在 >= deepLongPressMs 时保持旧配置兼容。</p>
     */
    public static String getFuncDeepLong(String key, int mode) {
        try {
            JSONObject cfg = cachedConfig();
            if (cfg == null) return F_DEFAULT;
            if (!cfg.optBoolean("enabled", true)) return F_DEFAULT;
            if (!isKeyEnabled(cfg, key)) return F_DEFAULT;
            JSONObject keys = cfg.optJSONObject("keys");
            if (keys == null) return F_DEFAULT;
            JSONObject keyObj = keys.optJSONObject(key);
            if (keyObj == null) return F_DEFAULT;
            JSONObject deepObj = keyObj.optJSONObject(DEEP_LONG_KEY);
            if (deepObj == null) return F_DEFAULT;
            String v = deepObj.optString(String.valueOf(configModeFor(mode)), null);
            if (v == null || v.length() == 0) {
                v = deepObj.optString("0", F_DEFAULT);
            }
            return v;
        } catch (Throwable t) {
            return F_DEFAULT;
        }
    }

    /**
     * 归一化快门键短按/长按的互斥组合。
     *
     * <p>普通拍照和普通街拍共用同一实体快门手势：短按的高速连拍会在第一次
     * {@code ACTION_DOWN}/repeat 时建立相机连拍状态，而独占长按动作又需要接管同一
     * 手势。因此「短按高速连拍 + 任一独占长按动作」不是两个可以同时成立的独立动作。
     * 把这个约束放在配置写入层，能在运行时之前消除状态源冲突；它不读取、不修改
     * 系统相机设置页中的释放模式。</p>
     *
     * <p>{@code preferLongAction} 表示哪一侧是本次用户刚刚设置的动作：设置短按时
     * 保留高速连拍并清空长按；设置任一独占长按时保留长按并把短按改为单张。导入
     * 旧备份时也可以传 {@code true}，以保留更特殊的独占长按动作。返回被自动改写的
     * 目标值，没有冲突或配置结构不完整时返回 {@code null}。</p>
     */
    public static String normalizeShutterConflict(JSONObject cfg, int mode,
            boolean preferLongAction) {
        if (cfg == null || (mode != MODE_CAPTURE && mode != MODE_STREET)) {
            return null;
        }
        try {
            JSONObject keys = cfg.optJSONObject("keys");
            JSONObject shutter = keys == null ? null : keys.optJSONObject(KEY_SHUTTER);
            if (shutter == null) return null;
            JSONObject longObj = shutter.optJSONObject(LONG_KEY);
            if (longObj == null) return null;

            String modeKey = String.valueOf(mode);
            String shortValue = readConfiguredValue(shutter, modeKey);
            String longValue = readConfiguredValue(longObj, modeKey);
            if (!F_BURST.equals(shortValue) || !isExclusiveShutterLongAction(longValue)) {
                return null;
            }

            if (preferLongAction) {
                // 独占长按是本次用户刚设置的动作，短按让位为单张，确保首拍和
                // 后续长按路由不会再次进入短按 MultiCapture。系统设置页的全局释放模式不变。
                shutter.put(modeKey, F_SHUTTER);
                return F_SHUTTER;
            }

            // 短按高速连拍是本次用户刚设置的动作，独占长按回到未设置；未设置的
            // 运行时语义是不接管该长按事务，不会留下不可见的冲突动作。
            longObj.put(modeKey, F_DEFAULT);
            return F_DEFAULT;
        } catch (Throwable ignored) {
            // 配置清洗失败不能阻断用户保存原本选择；运行时仍有 C3 手势保护。
            return null;
        }
    }

    /** C3 与 Phoenix 快门长按连拍都独占同一物理长按事务。 */
    private static boolean isExclusiveShutterLongAction(String value) {
        return F_DUAL_STYLE_CAPTURE.equals(value) || F_HOLD_BURST.equals(value);
    }

    /** 读取模式专属值；缺失时遵循配置页已有的 0（跟随全局设置）回退语义。 */
    private static String readConfiguredValue(JSONObject node, String modeKey) {
        String value = node.optString(modeKey, "");
        if (value == null || value.length() == 0) {
            value = node.optString("0", F_DEFAULT);
        }
        return value;
    }

    /**
     * 按松键时长返回录像键动作。一次按键只返回一个动作：短长按和深长按互斥。
     * 深长按没有独立配置时回退到旧的 long 配置，从而不改变已有用户行为。
     */
    public static String getRecordLongAction(int mode, long durationMs) {
        if (durationMs < getLongPressMs()) {
            return F_DEFAULT;
        }
        String normal = getFuncLong(KEY_RECORD, mode);
        if (durationMs >= getDeepLongPressMs()) {
            String deep = getFuncDeepLong(KEY_RECORD, mode);
            return F_DEFAULT.equals(deep) ? normal : deep;
        }
        return normal;
    }

    /** 将运行时 moduleId 转为用户配置所对应的规则键；其他相机和模式保持原样。 */
    private static int configModeFor(int mode) {
        if (STREET_SPECIAL_CONFIG_ALIAS_ENABLED && mode == MODE_STREET_SPECIAL) {
            HandleFixModule.debug("StreetSpecial config alias: runtimeMode=229 configMode=225");
            return MODE_STREET;
        }
        return mode;
    }

    private static String readAll(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), 65536)];
            int n = in.read(buf);
            in.close();
            return n > 0 ? new String(buf, 0, n, "UTF-8") : "";
        } catch (Throwable t) {
            return null;
        }
    }

    /** 不同进程只会命中自己有 SELinux 读取权限的第一个路径。 */
    private static String[] configReadPaths() {
        return new String[]{
                CONF_PATH_SYSTEM,
                CONF_PATH_1,
                CONF_PATH_2,
                CONF_PATH_V76,
                CONF_PATH_OLD
        };
    }

    /** 模块 App 用 root 写入相机/App 配置和 system_server 专用镜像。 */
    public static boolean writeViaRoot(JSONObject cfg) {
        String json = cfg.toString();
        boolean prepared = suExec("mkdir -p " + CONF_HIDDEN_DIR + " " + BACKUP_DIR);
        boolean appConfigWritten = false;
        for (String p : new String[]{CONF_PATH_1, CONF_PATH_2}) {
            appConfigWritten |= suWrite(p, json);
        }
        boolean systemConfigWritten = writeSystemConfigViaRoot(json);
        boolean ok = prepared && appConfigWritten && systemConfigWritten;
        if (ok) invalidateCache();
        return ok;
    }

    // ---------------- 备份 / 导入 ----------------

    /**
     * 备份当前设置到 /sdcard/Download/HandleFixConfig/backups/。
     *
     * <p>配置采用单一根 JSON，直接备份整个对象可覆盖按键映射、录像键两段长按、
     * 快门键短按/长按功能码、{@code shutterLongPressMs}、徕卡风格白名单、街拍特别款、
     * 系统快捷录像、半按对焦及界面选项。新增顶层字段也会自动进入备份，不需要维护
     * 一份容易漏项的字段复制表；本轮仅移动页面位置但键名未变的选项同样会随根对象保存。</p>
     *
     * @return 备份文件完整路径；失败返回 null
     */
    public static String backup() {
        try {
            JSONObject cfg = read();
            if (cfg == null) {
                cfg = new JSONObject();
                cfg.put("enabled", true);
                cfg.put("promFix", true);
                cfg.put("keys", new JSONObject());
            }
            String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            String path = BACKUP_DIR + "/handlefix-backup-" + ts + ".conf";
            boolean ok = suExec("mkdir -p " + BACKUP_DIR);
            ok &= suWrite(path, cfg.toString());
            return ok ? path : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 列出备份目录下所有备份文件（App 无存储权限，用 root ls 读取） */
    public static java.util.List<String> listBackups() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        try {
            Process p = new ProcessBuilder("su", "-c",
                    "ls -1 " + BACKUP_DIR + " 2>/dev/null").redirectErrorStream(true).start();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() > 0 && line.endsWith(".conf")) {
                    out.add(BACKUP_DIR + "/" + line);
                }
            }
            p.waitFor();
        } catch (Throwable t) {
            // ignore
        }
        return out;
    }

    /** 确保隐藏目录下有 config（迁移旧路径配置）：返回是否已就绪 */
    public static boolean migrateConfigToNewDir() {
        try {
            Process p = new ProcessBuilder("su", "-c",
                    "mkdir -p " + CONF_HIDDEN_DIR + " " + BACKUP_DIR
                    + " && if [ ! -f " + CONF_PATH_2 + " ]; then "
                    + "  if [ -f " + CONF_PATH_1 + " ]; then cp " + CONF_PATH_1 + " " + CONF_PATH_2 + "; "
                    + "  elif [ -f " + CONF_PATH_V76 + " ]; then cp " + CONF_PATH_V76 + " " + CONF_PATH_2 + "; "
                    + "  elif [ -f " + CONF_PATH_OLD + " ]; then cp " + CONF_PATH_OLD + " " + CONF_PATH_2 + "; fi; fi")
                    .redirectErrorStream(true).start();
            p.getInputStream().read();
            p.waitFor();
            boolean ok = p.exitValue() == 0;
            if (!ok) return false;

            // 升级旧版本后用户不必重新切换一次开关：首次打开模块就把现有有效 JSON
            // 同步到 system_server 镜像。先解析再写，避免把 su/cat 的错误文本当配置。
            String migrated = readFileViaRoot(CONF_PATH_2);
            if (migrated == null || migrated.trim().length() == 0) {
                migrated = readFileViaRoot(CONF_PATH_1);
            }
            if (migrated == null || migrated.trim().length() == 0) return false;
            String json = migrated.trim();
            new JSONObject(json);
            boolean systemOk = writeSystemConfigViaRoot(json);
            if (systemOk) invalidateCache();
            return systemOk;
        } catch (Throwable t) {
            return false;
        }
    }

    /** root 读取文件内容（App 无存储权限，走 su cat） */
    public static String readFileViaRoot(String path) {
        try {
            Process p = new ProcessBuilder("su", "-c", "cat " + path).redirectErrorStream(true).start();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按当前相机方案清洗配置。这里只处理已经确认不具备安全入口的组合；夜景 EV、
     * 专业参数预设等版本能力由 UI 与运行时的精确 versionCode 门处理，不能仅凭
     * Prometheus/官方二选一删除用户配置，否则切换回原相机时会丢失仍有效的规则。
     * @param cfg  待清洗的配置（原地修改）
     * @param isProm 是否按 PrometheusCam 移植包规则清洗（promFix 开关）
     */
    public static JSONObject sanitizeForCamera(JSONObject cfg, boolean isProm) {
        if (cfg == null) return null;
        try {
            JSONObject keys = cfg.optJSONObject("keys");
            if (keys == null) return cfg;
            JSONObject wheel = keys.optJSONObject(KEY_WHEEL);
            if (wheel == null) return cfg;
            if (!isProm) {
                // 官方相机的具体版本由设置页/运行时 Hook 决定，不能在无 Context 的
                // 配置层把 6.4.000370/530 的 J 一律清掉；不支持版本会在 UI 和 Hook
                // 能力门中隐藏/放行原生行为。街拍 225 的 O 仍无已核实官方入口，继续清理。
                if (F_FOCUSP.equals(wheel.optString("225", null))) wheel.put("225", "0");
            }
            // 夜景 173 EV(K)：官方相机原生支持，PrometheusCam NRV1.5.0 起作者已适配，无需清洗
        } catch (Throwable t) {
            // 清洗失败不影响导入/切换
        }
        return cfg;
    }

    public static boolean importBackupContent(String json) {
        try {
            if (json == null || json.trim().length() == 0) return false;
            JSONObject cfg = prepareImportedConfig(new JSONObject(json));
            return writeViaRoot(cfg);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 导入备份（root 读取备份文件内容并写回生效配置）；成功返回 true */
    public static boolean importBackup(String backupPath) {
        try {
            String s = readFileViaRoot(backupPath);
            if (s == null || s.trim().length() == 0) return false;
            JSONObject cfg = prepareImportedConfig(new JSONObject(s));
            return writeViaRoot(cfg);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 导入前统一迁移与清洗。
     *
     * <p>旧实现按“当前正在使用的 promFix”清洗导入文件；当备份本身要切换相机族时，
     * 会用错误的过滤表删除仍有效的规则。这里改为以导入 JSON 自己的 promFix 为准，
     * 并只规范化有边界要求的字段。快门长按阈值、长按功能码，以及本轮移动到模块
     * 设置页但键名未变的 streetSpecial/halfFocus 等选项都属于同一根 JSON，未知字段
     * 也原样保留，保证旧备份和未来版本前向兼容。</p>
     */
    private static JSONObject prepareImportedConfig(JSONObject cfg) {
        if (cfg == null) return null;
        try {
            if (cfg.has("longPressMs")) {
                cfg.put("longPressMs", clampThreshold(cfg.optInt("longPressMs", 500), 500, 800));
            }
            if (cfg.has("deepLongPressMs")) {
                cfg.put("deepLongPressMs",
                        clampThreshold(cfg.optInt("deepLongPressMs", 1000), 1000, 1500));
            }
            if (cfg.has(OPTION_GLOBAL_RECORD_LONG_PRESS_MS)) {
                cfg.put(OPTION_GLOBAL_RECORD_LONG_PRESS_MS,
                        clampThreshold(cfg.optInt(OPTION_GLOBAL_RECORD_LONG_PRESS_MS, 800),
                                500, 1000));
            }
            if (cfg.has(OPTION_GRIP_QUICK_LONG_PRESS_MS)) {
                cfg.put(OPTION_GRIP_QUICK_LONG_PRESS_MS,
                        clampThreshold(cfg.optInt(OPTION_GRIP_QUICK_LONG_PRESS_MS, 800),
                                700, 1000));
            }
            if (cfg.has("shutterLongPressMs")) {
                // 旧备份可能带有 100ms/1500ms 等越界值；导入时归一化到新 UI 的
                // 500–1000ms 范围，避免非法阈值重新进入相机进程。
                cfg.put("shutterLongPressMs",
                        clampThreshold(cfg.optInt("shutterLongPressMs", 800), 500, 1000));
            }
            cfg.put(OPTION_GLOBAL_RECORD_VIDEO, readGlobalRecordVideoPolicy(cfg));
            if (!cfg.optBoolean("promFix", true) || !cfg.optBoolean(OPTION_STREET_SPECIAL, false)) {
                cfg.put(OPTION_STREET_SPECIAL_WITHOUT_GRIP, false);
            }
            String focus = cfg.optString(OPTION_HALF_FOCUS_POLICY, HALF_FOCUS_NATIVE);
            if (!HALF_FOCUS_DISABLE.equals(focus) && !HALF_FOCUS_CENTER.equals(focus)) {
                cfg.put(OPTION_HALF_FOCUS_POLICY, HALF_FOCUS_NATIVE);
            }
            // 旧备份可能保存过互相冲突的快门短/长按组合。导入时保留较特殊的
            // 独占长按，并把短按规范为单张，避免恢复配置后再次触发同一冲突。
            normalizeShutterConflict(cfg, MODE_CAPTURE, true);
            normalizeShutterConflict(cfg, MODE_STREET, true);
        } catch (Throwable ignored) {
            // 单个可选字段损坏不应阻止其它有效规则恢复；运行时读取器仍有安全默认值。
        }
        return sanitizeForCamera(cfg, cfg.optBoolean("promFix", true));
    }

    /**
     * 录像键短长按起始阈值（ms）。新配置默认 500；没有显式阈值、但已有旧 long
     * 配置的 JSON 继续使用历史 800ms，避免仅升级模块就改变旧用户的按键手感。
     */
    public static int getLongPressMs() {
        try {
            JSONObject cfg = cachedConfig();
            if (cfg == null) return 500;
            if (cfg.has("longPressMs")) {
                // 旧版本可能写入 900/1000ms；只在读取时安全夹取，
                // 不后台改写用户 JSON，用户在新界面确认后才持久化。
                return clampThreshold(cfg.optInt("longPressMs", 500), 500, 800);
            }
            // 新 UI 首次写入 deepLongPressMs 后即进入新分段配置；之前的旧 JSON
            // 没有这两个字段，只要存在 record.long 就保留 v12.0.50 以前的 800ms。
            if (!cfg.has("deepLongPressMs") && hasLegacyRecordLongConfig(cfg)) {
                return 800;
            }
            return 500;
        } catch (Throwable t) {
            return 800;
        }
    }

    private static boolean hasLegacyRecordLongConfig(JSONObject cfg) {
        try {
            JSONObject keys = cfg.optJSONObject("keys");
            JSONObject record = keys == null ? null : keys.optJSONObject(KEY_RECORD);
            return record != null && record.optJSONObject(LONG_KEY) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 深长按起始阈值（ms），默认 1000；必须严格大于短长按阈值。 */
    public static int getDeepLongPressMs() {
        try {
            JSONObject cfg = cachedConfig();
            int normal = getLongPressMs();
            int value = cfg == null ? 1000 : cfg.optInt("deepLongPressMs", 1000);
            return Math.max(normal + 1, clampThreshold(value, 1000, 1500));
        } catch (Throwable t) {
            return Math.max(getLongPressMs() + 1, 1000);
        }
    }

    /** 快门键独立长按阈值（ms），范围 500–1000，默认 800，不复用录像键阈值。 */
    public static int getShutterLongPressMs() {
        try {
            JSONObject cfg = cachedConfig();
            return clampThreshold(cfg == null ? 800 : cfg.optInt("shutterLongPressMs", 800),
                    500, 1000);
        } catch (Throwable t) {
            return 800;
        }
    }

    /**
     * 读取系统级录像键深长按策略。
     *
     * <p>12.0.77 及以前保存的是 boolean：true 已实测语义是“只进入录像界面”，
     * 因此迁移时必须映射到 {@link #GLOBAL_RECORD_OPEN}，不能擅自升级成自动录制。</p>
     */
    public static String getGlobalRecordVideoPolicy() {
        try {
            JSONObject cfg = cachedConfig();
            if (cfg == null || !cfg.optBoolean("enabled", true)) {
                return GLOBAL_RECORD_NATIVE;
            }
            return readGlobalRecordVideoPolicy(cfg);
        } catch (Throwable t) {
            return GLOBAL_RECORD_NATIVE;
        }
    }

    /** UI 与运行时共用同一兼容转换，避免两端对旧 boolean 配置解释不一致。 */
    public static String readGlobalRecordVideoPolicy(JSONObject cfg) {
        if (cfg == null || !cfg.has(OPTION_GLOBAL_RECORD_VIDEO)) {
            return GLOBAL_RECORD_NATIVE;
        }
        Object raw = cfg.opt(OPTION_GLOBAL_RECORD_VIDEO);
        if (raw instanceof Boolean) {
            return ((Boolean) raw).booleanValue() ? GLOBAL_RECORD_OPEN : GLOBAL_RECORD_NATIVE;
        }
        String value = raw == null ? GLOBAL_RECORD_NATIVE : String.valueOf(raw);
        if (GLOBAL_RECORD_OPEN.equals(value) || GLOBAL_RECORD_START.equals(value)) {
            return value;
        }
        return GLOBAL_RECORD_NATIVE;
    }

    public static boolean isGlobalRecordVideoEnabled() {
        return !GLOBAL_RECORD_NATIVE.equals(getGlobalRecordVideoPolicy());
    }

    /** system_server 与相机进程共用同一配置镜像，独立于按键映射总开关。 */
    public static boolean isGripInputGuardEnabled() {
        try {
            JSONObject cfg = cachedConfig();
            return cfg != null && cfg.optBoolean("enabled", true)
                    && cfg.optBoolean(OPTION_GRIP_INPUT_GUARD, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** UI 与 system_server 共用的场景读取；未知值安全回退到旧版完整保护。 */
    public static String getGripInputGuardScope() {
        try {
            return readGripInputGuardScope(cachedConfig());
        } catch (Throwable ignored) {
            return GRIP_GUARD_SCOPE_BOTH;
        }
    }

    public static String readGripInputGuardScope(JSONObject cfg) {
        String value = cfg == null ? GRIP_GUARD_SCOPE_BOTH
                : cfg.optString(OPTION_GRIP_INPUT_GUARD_SCOPE, GRIP_GUARD_SCOPE_BOTH);
        if (GRIP_GUARD_SCOPE_KEYGUARD.equals(value)
                || GRIP_GUARD_SCOPE_SCREEN_OFF.equals(value)) {
            return value;
        }
        return GRIP_GUARD_SCOPE_BOTH;
    }

    /** 缺失掩码表示从旧版布尔开关迁移，必须继续屏蔽全部四类输入。 */
    public static int getGripInputGuardMask() {
        try {
            return readGripInputGuardMask(cachedConfig());
        } catch (Throwable ignored) {
            return GRIP_INPUT_ALL;
        }
    }

    public static int readGripInputGuardMask(JSONObject cfg) {
        int value = cfg == null ? GRIP_INPUT_ALL
                : cfg.optInt(OPTION_GRIP_INPUT_GUARD_MASK, GRIP_INPUT_ALL);
        return value & GRIP_INPUT_ALL;
    }

    /** 读取某一拨杆方向的相机外快捷目标；未知值必须回退原生，不能误启动相机。 */
    public static String getGripQuickTarget(boolean zoomIn) {
        try {
            JSONObject cfg = cachedConfig();
            if (cfg == null || !cfg.optBoolean("enabled", true)) return GRIP_QUICK_NATIVE;
            return readGripQuickTarget(cfg, zoomIn);
        } catch (Throwable ignored) {
            return GRIP_QUICK_NATIVE;
        }
    }

    public static String readGripQuickTarget(JSONObject cfg, boolean zoomIn) {
        String key = zoomIn ? OPTION_GRIP_QUICK_ZOOM_IN : OPTION_GRIP_QUICK_ZOOM_OUT;
        String value = cfg == null ? GRIP_QUICK_NATIVE
                : cfg.optString(key, GRIP_QUICK_NATIVE);
        if (GRIP_QUICK_LEGEND.equals(value)
                || GRIP_QUICK_STREET_FOLLOW.equals(value)
                || GRIP_QUICK_CAPTURE.equals(value)
                || GRIP_QUICK_STREET_225.equals(value)
                || GRIP_QUICK_STREET_229.equals(value)
                || GRIP_QUICK_STREET_229_LEGEND.equals(value)
                || GRIP_QUICK_VIDEO.equals(value)
                || GRIP_QUICK_PRO_PHOTO.equals(value)
                || GRIP_QUICK_PRO_VIDEO.equals(value)
                || GRIP_QUICK_NIGHT.equals(value)
                || GRIP_QUICK_PORTRAIT.equals(value)
                || GRIP_QUICK_DOCUMENT.equals(value)
                || GRIP_QUICK_ULTRA_PIXEL.equals(value)) {
            return value;
        }
        return GRIP_QUICK_NATIVE;
    }

    /** 长拨阈值固定为 700-1000ms；UI 仅提供 100ms 步进。 */
    public static int getGripQuickLongPressMs() {
        try {
            return readGripQuickLongPressMs(cachedConfig());
        } catch (Throwable ignored) {
            return 800;
        }
    }

    public static int readGripQuickLongPressMs(JSONObject cfg) {
        return clampThreshold(cfg == null ? 800
                : cfg.optInt(OPTION_GRIP_QUICK_LONG_PRESS_MS, 800), 700, 1000);
    }

    public static boolean isGripQuickHapticEnabled() {
        try {
            JSONObject cfg = cachedConfig();
            return cfg == null || cfg.optBoolean(OPTION_GRIP_QUICK_HAPTIC, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * 系统级快捷录像的独立长按阈值，500–1000ms，默认 800ms。
     * 相机前台仍由录像键短长按/深长按配置决定，两套手势不会互相改变。
     */
    public static int getGlobalRecordLongPressMs() {
        try {
            JSONObject cfg = cachedConfig();
            return readGlobalRecordLongPressMs(cfg);
        } catch (Throwable ignored) {
            return 800;
        }
    }

    /** UI 与 system_server 共用同一范围处理。 */
    public static int readGlobalRecordLongPressMs(JSONObject cfg) {
        return clampThreshold(cfg == null ? 800
                : cfg.optInt(OPTION_GLOBAL_RECORD_LONG_PRESS_MS, 800), 500, 1000);
    }

    /** 读取半按对焦策略；未知值一律回到原生，避免损坏配置改变快门语义。 */
    public static String getHalfFocusPolicy() {
        try {
            JSONObject cfg = cachedConfig();
            String value = cfg == null ? HALF_FOCUS_NATIVE
                    : cfg.optString(OPTION_HALF_FOCUS_POLICY, HALF_FOCUS_NATIVE);
            if (!HALF_FOCUS_DISABLE.equals(value) && !HALF_FOCUS_CENTER.equals(value)) {
                return HALF_FOCUS_NATIVE;
            }
            // 只在相机进程识别到有效 versionCode 时允许非原生策略；
            // 最终事件还必须通过 FocusPolicyHooks 的 VID/PID + scanCode 校验。
            return CameraCapabilityRegistry.supportsHalfFocusPolicy(
                    HandleFixModule.CAMERA_VERSION_CODE)
                    ? value : HALF_FOCUS_NATIVE;
        } catch (Throwable t) {
            return HALF_FOCUS_NATIVE;
        }
    }

    private static int clampThreshold(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 删除备份文件（root），并清理对应备注 */
    public static boolean deleteBackup(String backupPath) {
        try {
            boolean ok = suExec("rm -f " + shellQuote(backupPath));
            if (ok) {
                JSONObject notes = readNotes();
                notes.remove(new File(backupPath).getName());
                ok = writeNotes(notes);
            }
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- 备份备注 ----------------
    private static JSONObject readNotes() {
        String s = readFileViaRoot(BACKUP_DIR + "/notes.json");
        if (s == null || s.trim().length() == 0) return new JSONObject();
        try {
            return new JSONObject(s);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static boolean writeNotes(JSONObject notes) {
        try {
            return suExec("mkdir -p " + BACKUP_DIR) && suWrite(BACKUP_DIR + "/notes.json", notes.toString());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读取备份文件备注（无则返回空串） */
    public static String getBackupNote(String backupPath) {
        try {
            JSONObject notes = readNotes();
            String fn = new File(backupPath).getName();
            return notes.optString(fn, "");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 设置/清除备份文件备注（传空串清除） */
    public static boolean setBackupNote(String backupPath, String note) {
        try {
            JSONObject notes = readNotes();
            String fn = new File(backupPath).getName();
            if (note == null || note.trim().length() == 0) {
                notes.remove(fn);
            } else {
                notes.put(fn, note.trim());
            }
            return writeNotes(notes);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean suExec(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            p.getInputStream().read();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean suWrite(String path, String content) {
        try {
            String parent = new File(path).getParent();
            String tempPath = path + ".tmp-" + System.currentTimeMillis();
            // 先写同目录临时文件再 mv，避免相机进程读到 root 写入中的半截 JSON。
            String cmd = "mkdir -p " + shellQuote(parent)
                    + " && printf '%s' " + shellQuote(content) + " > " + shellQuote(tempPath)
                    + " && chmod 666 " + shellQuote(tempPath)
                    + " && mv -f " + shellQuote(tempPath) + " " + shellQuote(path);
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            p.getInputStream().read(); // drain a bit
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 原子写入 system_server 镜像并恢复正确 SELinux 标签。
     *
     * <p>不能只对 /data/local/tmp 文件 chmod，也不能在 system_server 中借用 su；前者
     * 绕不过 SELinux，后者会把系统按键热路径变成提权 I/O。配置仅在 App 保存/启动迁移
     * 时由 root 同步，system_server 运行时保持普通只读文件访问。</p>
     */
    private static boolean writeSystemConfigViaRoot(String content) {
        boolean prepared = suExec("mkdir -p " + shellQuote(CONF_SYSTEM_DIR)
                + " && chown system:system " + shellQuote(CONF_SYSTEM_DIR)
                + " && chmod 750 " + shellQuote(CONF_SYSTEM_DIR));
        if (!prepared || !suWrite(CONF_PATH_SYSTEM, content)) {
            return false;
        }
        return suExec("chown system:system " + shellQuote(CONF_PATH_SYSTEM)
                + " && chmod 640 " + shellQuote(CONF_PATH_SYSTEM)
                + " && (restorecon -RF " + shellQuote(CONF_SYSTEM_DIR)
                + " >/dev/null 2>&1 || chcon -R u:object_r:system_data_file:s0 "
                + shellQuote(CONF_SYSTEM_DIR) + ")");
    }

    private static String shellQuote(String s) {
        // 单引号转义
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
