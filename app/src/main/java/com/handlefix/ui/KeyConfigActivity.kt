package com.handlefix.ui

import kotlin.math.roundToInt
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.handlefix.ModeConfig
import com.handlefix.CameraBuilds
import com.handlefix.Camera68InputCapabilities
import com.handlefix.GripModeWhitelist
import com.handlefix.NativeFilterPanelHooks
import com.handlefix.activation.LspActivationMonitor
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 按键模式配置页：录像键短按/短长按/深长按；快门键短按/长按。 */
class KeyConfigActivity : ComponentActivity() {

    private val resumeTick = mutableStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick.value++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra("key") ?: ModeConfig.KEY_RECORD
        val title = intent.getStringExtra("title") ?: "按键"
        setContent {
            XiaomiEdgeToEdge()
            HandleFixTheme {
                KeyConfigScreen(key, title, resumeTick.value) { finish() }
            }
        }
    }
}

// 模式列表：更多模式默认隐藏（开发者选项启用后显示）
private const val MODE_LEICA_INSTANT = 256
private val MODES = intArrayOf(163, 162, MODE_LEICA_INSTANT, 167, 180,
    Camera68InputCapabilities.CINEMASTER, Camera68InputCapabilities.AI_CAPTURE, 173, 171, 186, 225,
    Camera68InputCapabilities.EQUIP_STREET, 175,
    172, 169, 204, 227, 187, 166, 184, 188, 183, 205)
// 模式基础名（不含编号）；「显示模式编号」开关打开时在显示处拼接 (163) 等
private val MODE_NAMES = arrayOf(
    "拍照", "录像", "徕卡一瞬", "专业拍照", "专业录像", "导演模式", "AI帮拍",
    "夜景", "人像", "文档", "街拍", "经典／传奇街拍", "超清",
    "慢动作", "延时摄影", "前后双景", "电影", "长曝光",
    "全景", "萌拍", "超级月亮", "短视频", "AI水印",
)
private val MORE_MODES = intArrayOf(172, 169, 204, 227, 187, 166, 184, 188, 183, 205)

private fun isMoreMode(m: Int): Boolean = MORE_MODES.contains(m)

private const val PHOENIX_CLASSIC_STYLE_LSP_PACKAGE = "com.prometheus.camera.rev"
private val PHOENIX_CLASSIC_STYLE_MODES = setOf("163", "167", "173", "171", "225", "preview")
private val PHOENIX_FILTER_PANEL_MODES = setOf(
    "163", "225", "162", "171", "175", "167", "180", "preview",
)

/** 相机进程写入自身私有缓存的目录快照；显示名称只用于设置页。 */
private data class ClassicStyleOption(val id: String, val label: String)
private data class ClassicStyleCatalog(
    val profile: String,
    val cameraVersionCode: Long,
    val lspVersionCode: Long,
    val options: List<ClassicStyleOption>,
)

/**
 * 通过 Root 读取相机进程发布的实时风格目录。读取不到、适配档案不符或相机版本不符时
 * 返回 null，设置页不会猜测风格 ID；用户仍可使用作者当前全部目录的默认循环入口。
 */
private fun readClassicStyleCatalog(cameraVersionCode: Long): ClassicStyleCatalog? {
    val profile = com.handlefix.ClassicStyleCatalogBridge.profileForVersion(cameraVersionCode)
        ?: return null
    return try {
        val raw = UiUtil.suOut(
            "cat ${com.handlefix.ClassicStyleCatalogBridge.CAMERA_CATALOG_PATH} 2>/dev/null",
        ).trim()
        if (raw.isEmpty()) return null
        val result = JSONObject(raw)
        val actualProfile = result.optString(com.handlefix.ClassicStyleCatalogBridge.KEY_PROFILE)
        val actualVersion = result.optLong(
            com.handlefix.ClassicStyleCatalogBridge.KEY_CAMERA_VERSION_CODE,
            -1L,
        )
        if (actualProfile != profile || actualVersion != cameraVersionCode) {
            return null
        }
        val idArray = result.optJSONArray(com.handlefix.ClassicStyleCatalogBridge.KEY_IDS)
            ?: return null
        val ids = (0 until idArray.length()).mapNotNull { index ->
            idArray.optString(index).trim().takeIf(String::isNotEmpty)
        }
        if (ids.isEmpty()) return null
        val labelArray = result.optJSONArray(com.handlefix.ClassicStyleCatalogBridge.KEY_LABELS)
        val options = ids.mapIndexed { index, id ->
            val label = labelArray?.optString(index)?.trim().orEmpty().ifEmpty { id }
            ClassicStyleOption(id, label)
        }
        ClassicStyleCatalog(
            profile = profile,
            cameraVersionCode = cameraVersionCode,
            lspVersionCode = result.optLong(
                com.handlefix.ClassicStyleCatalogBridge.KEY_LSP_VERSION_CODE,
                -1L,
            ),
            options = options,
        )
    } catch (_: Throwable) {
        null
    }
}

// 录像键长按功能：未设置、预览照片，以及在已反查入口的版本上可用的原生二态切换；
// 162/180 整行默认隐藏（同超清等更多模式），开发者选项打开后展示
private val FUNCS_LONG = arrayOf(
    arrayOf(ModeConfig.F_DEFAULT, "未设置"),
    arrayOf(ModeConfig.F_PREVIEW, "预览照片"),
    arrayOf(ModeConfig.F_LEICA, "徕卡经典/生动（新）"),
)

private val FUNCS_RECORD = arrayOf(
    arrayOf(ModeConfig.F_DEFAULT, "跟随全局设置"),
    arrayOf(ModeConfig.F_FILTER, "滤镜选择"),
    arrayOf(ModeConfig.F_LEICA, "徕卡经典/生动（新）"),
    arrayOf(ModeConfig.F_PHOENIX_CLASSIC_STYLE, "徕卡经典风格"),
    arrayOf(ModeConfig.F_PARAM_PANEL, "专业模式参数预设（新）"),
    arrayOf(ModeConfig.F_QUICK, "快速录像"),
    arrayOf(ModeConfig.F_SHUTTER, "快门释放模式"),
    arrayOf(ModeConfig.F_METER, "测光权重"),
    arrayOf(ModeConfig.F_AUTOEXP, "测光模式"),
    arrayOf(ModeConfig.F_AWB, "白平衡"),
    arrayOf(ModeConfig.F_PICSTYLE, "自定义摄影风格"),
    arrayOf(ModeConfig.F_EIS, "超级防抖"),
    arrayOf(ModeConfig.F_EXPFEED, "曝光反馈"),
    arrayOf(ModeConfig.F_PEAK, "峰值对焦"),
    arrayOf(ModeConfig.F_SOUND, "声音设置"),
    arrayOf(ModeConfig.F_AUDIOT, "收音类型"),
    arrayOf(ModeConfig.F_RATION, "画幅比例"),
    arrayOf(ModeConfig.F_50MP, "50MP"),
    arrayOf(ModeConfig.F_FORMAT, "专业模式照片格式（新）"),
    arrayOf(ModeConfig.F_ZOOM, "变焦段（新）"),
)
private val FUNCS_SHUTTER = arrayOf(
    arrayOf(ModeConfig.F_DEFAULT, "跟随全局设置"),
    arrayOf(ModeConfig.F_SHUTTER, "单张拍摄"),
    arrayOf(ModeConfig.F_BURST, "高速连拍"),
)
private val FUNCS_SHUTTER_LONG = arrayOf(
    arrayOf(ModeConfig.F_DEFAULT, "未设置"),
    arrayOf(ModeConfig.F_DUAL_STYLE_CAPTURE, "徕卡经典/生动轮拍"),
    arrayOf(ModeConfig.F_HOLD_BURST, "高速连拍"),
)
private val FUNCS_WHEEL = arrayOf(
    arrayOf(ModeConfig.F_DEFAULT, "跟随全局设置"),
    arrayOf(ModeConfig.F_EV, "EV"),
    arrayOf(ModeConfig.F_APERT, "光圈"),
    arrayOf(ModeConfig.F_ET, "快门时间"),
    arrayOf(ModeConfig.F_ISO, "ISO"),
    arrayOf(ModeConfig.F_AWB, "白平衡"),
    arrayOf(ModeConfig.F_FOCUSP, "对焦"),
    arrayOf(ModeConfig.F_BOKEH, "虚化"),
    arrayOf(ModeConfig.F_WORKSP, "专业模式参数预设（新）"),
)
private val FUNCS_ZOOM = arrayOf(
    arrayOf(ModeConfig.F_DEFAULT, "跟随全局设置"),
    arrayOf(ModeConfig.F_DEFZOOM, "默认变焦"),
    arrayOf(ModeConfig.F_CONTZOOM, "连续变焦"),
    arrayOf(ModeConfig.F_ZOOM, "焦段切换"),
)

/**
 * 快门 C3 冲突提示只面向配置页，功能码归一化本身由 ModeConfig 负责。
 * 这样 UI 文案不会渗入相机进程，也不会把系统设置页的同名选项误当成模块配置。
 */
private fun shutterModeName(mode: Int): String = when (mode) {
    ModeConfig.MODE_CAPTURE -> "拍照"
    ModeConfig.MODE_STREET -> "街拍"
    else -> "模式$mode"
}

private fun shutterFunctionName(letter: String, longAction: Boolean): String = when {
    longAction && letter == ModeConfig.F_DUAL_STYLE_CAPTURE -> "徕卡经典/生动轮拍"
    longAction && letter == ModeConfig.F_HOLD_BURST -> "高速连拍"
    !longAction && letter == ModeConfig.F_BURST -> "高速连拍"
    !longAction && letter == ModeConfig.F_SHUTTER -> "单张拍摄"
    letter == ModeConfig.F_DEFAULT -> "未设置"
    else -> letter
}

private fun shutterConflictMessage(
    mode: Int,
    changedFunction: String,
    otherFunction: String,
    replacement: String,
): String {
    val modeName = shutterModeName(mode)
    val replacementName = if (replacement == ModeConfig.F_DEFAULT) "未设置" else "单张拍摄"
    return "当前【${modeName}-${changedFunction}】与【${modeName}-${otherFunction}】设置存在冲突，" +
        "已将【${modeName}-${changedFunction}】更改为【${replacementName}】。"
}

@Composable
private fun KeyConfigScreen(key: String, title: String, resumeTick: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    var cfg by remember { mutableStateOf(ModeConfig.read()) }
    var keyEnabled by remember { mutableStateOf(ModeConfig.isKeyEnabled(key)) }
    var devMode by remember { mutableStateOf(ModeConfig.isDevMode()) }
    var pickMode by remember { mutableStateOf<Int?>(null) }
    var showPreview by remember { mutableStateOf(false) }
    var showLpDialog by remember { mutableStateOf(false) }
    var showDeepLpDialog by remember { mutableStateOf(false) }
    var showShutterLpDialog by remember { mutableStateOf(false) }
    var shutterConflictDialogText by remember { mutableStateOf<String?>(null) }
    var pressTab by remember { mutableStateOf(0) }
    var showDetail by remember { mutableStateOf(ModeConfig.isShowDetail()) }
    // 长按响应时间 state：保存后立即刷新列表页 summary（避免返回重进才更新）
    var longPressMs by remember { mutableStateOf(ModeConfig.getLongPressMs()) }
    var deepLongPressMs by remember { mutableStateOf(ModeConfig.getDeepLongPressMs()) }
    var shutterLongPressMs by remember { mutableStateOf(ModeConfig.getShutterLongPressMs()) }
    var classicStyleCatalog by remember { mutableStateOf<ClassicStyleCatalog?>(null) }
    var classicStyleSelectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showClassicStyleDialog by remember { mutableStateOf(false) }
    val isRecordKey = key == ModeConfig.KEY_RECORD
    val isShutterKey = key == ModeConfig.KEY_SHUTTER
    val longTab = (isRecordKey && pressTab > 0) || (isShutterKey && pressTab == 1)
    val deepLongTab = isRecordKey && pressTab == 2
    val camera68Mapping = ModeConfig.readMappingScheme(cfg) == ModeConfig.MAPPING_CAMERA_68
    var classicRuntimeModes by remember(resumeTick, camera68Mapping, longTab) {
        mutableStateOf(IntArray(0))
    }

    LaunchedEffect(resumeTick) {
        cfg = ModeConfig.read()
        keyEnabled = ModeConfig.isKeyEnabled(key)
        devMode = ModeConfig.isDevMode()
        showDetail = ModeConfig.isShowDetail()
        longPressMs = ModeConfig.getLongPressMs()
        deepLongPressMs = ModeConfig.getDeepLongPressMs()
        shutterLongPressMs = ModeConfig.getShutterLongPressMs()
    }

    fun funcs(): Array<Array<String>> = when (key) {
        ModeConfig.KEY_RECORD -> FUNCS_RECORD
        ModeConfig.KEY_SHUTTER -> FUNCS_SHUTTER
        ModeConfig.KEY_WHEEL -> FUNCS_WHEEL
        else -> FUNCS_ZOOM
    }

    fun activeFuncs(): Array<Array<String>> = when {
        isRecordKey && longTab -> FUNCS_LONG
        isShutterKey && longTab -> FUNCS_SHUTTER_LONG
        else -> funcs()
    }

    /** 只读取当前安装相机的版本号；版本门控集中在本页，避免每个功能各自猜测。 */
    fun cameraVersionCode(): Long? = try {
        // 已知相机版本号均在 Int 范围内；沿用旧字段读取，
        // 避免 AGP/Lint 在 JDK 17 下解析 longVersionCode 平台 JavaDoc 时崩溃。
        context.packageManager.getPackageInfo("com.android.camera", 0).versionCode.toLong()
    } catch (_: Throwable) {
        null
    }

    fun isPhoenixCameraVersion(): Boolean = cameraVersionCode()?.let {
        CameraBuilds.isPhoenixOs4Family(it)
    } == true

    fun isLeicaInstantModeAvailable(): Boolean = cameraVersionCode()?.let { version ->
        (ModeConfig.isPromFixEnabled() && CameraBuilds.isPhoenixOs4Family(version)) ||
            (ModeConfig.isCamera68Mapping() && CameraBuilds.isCamera68Port(version))
    } == true

    /**
     * “滤镜选择”按已反查的相机族开放：Phoenix OS4 使用 Prometheus 方案，官方
     * 6.6.510 使用系统相机方案。这里只服务普通用户的能力过滤；开发者模式允许看见
     * 并保存本按键的所有选项，但运行时仍由 NativeFilterPanelHooks 安全放行或回退。
     */
    fun isNativeFilterPanelAvailable(modeKey: String): Boolean {
        if (key != ModeConfig.KEY_RECORD || longTab) {
            return false
        }
        val versionCode = cameraVersionCode() ?: return false
        return modeKey in PHOENIX_FILTER_PANEL_MODES &&
            NativeFilterPanelHooks.isSupported(versionCode, ModeConfig.isPromFixEnabled())
    }

    // 自定义风格目录只对 Phoenix OS4 版本族和已实测的 NRV-1.6.1 开放；NRV 旧版
    // 仍隐藏。Phoenix 后续同基线更新不按单个 0.3.0 版本号卡死，运行时控制器还会
    // 检查 ClassicStyleStore 的真实方法签名，避免“显示了但强行 Hook”。
    fun isPhoenixCameraAvailable(): Boolean {
        if (key != ModeConfig.KEY_RECORD || longTab || !ModeConfig.isPromFixEnabled()) {
            return false
        }
        return cameraVersionCode()?.let { CameraBuilds.isClassicStyleSwitchSupported(it) } == true
    }

    /**
     * 长按 A 的能力门：模式白名单复用短按规则，版本入口再单独核对。
     * Phoenix 的多 LUT「徕卡经典风格」与原生经典/生动二态分离；当前 0.3.0
     * 已反查 V9.i0.s9(View) + r2.m 原生链，因此可以开放 A，但不复用短按多 LUT 动作。
     */
    fun isLongLeicaAvailable(modeKey: String): Boolean {
        val mode = modeKey.toIntOrNull() ?: return false
        if (camera68Mapping) {
            return cameraVersionCode()?.let(CameraBuilds::isCamera68Port) == true &&
                Camera68InputCapabilities.supportsAction(
                    ModeConfig.KEY_RECORD, mode, ModeConfig.F_LEICA, true,
                )
        }
        if (!ModeConfig.isLeicaStyleMode(mode)) return false
        if (isPhoenixCameraVersion()) return true
        if (ModeConfig.isPromFixEnabled()) {
            return true
        }
        return cameraVersionCode()?.let { CameraBuilds.isOfficialLongLeicaSupported(it) } == true
    }

    // 参数预设录像键动作与 Phoenix 风格动作不同：Phoenix 使用 q6.U0/T9.A，
    // Prometheus/V5.1/NRV 使用 K3.u1/FragmentManualWorkspaceBottom；官方 6.4.x
    // 使用各自 FeatureUI 协议打开同一个 fragmentId=209 工作区。所有分支都只在
    // 专业拍照 167 生效；官方仅开放真实 dex 已核实的 370/530，避免显示无效选项。
    fun isParamPanelFeatureAvailable(modeKey: String): Boolean {
        if (key != ModeConfig.KEY_RECORD || longTab || modeKey != "167") return false
        if (ModeConfig.isPromFixEnabled()) return true
        return cameraVersionCode()?.let { CameraBuilds.isOfficialParamPanelSupported(it) } == true
    }

    /** 徕卡经典/生动轮拍必须由相机版本适配器提供真实的拍摄完成回调。 */
    fun isDualStyleCaptureAvailable(modeKey: String): Boolean {
        val mode = modeKey.toIntOrNull() ?: return false
        if (!isShutterKey || !longTab || !ModeConfig.isLeicaStyleMode(mode)) return false
        return cameraVersionCode()?.let {
            com.handlefix.CameraCapabilityRegistry.supportsDualStyleCapture(
                it, ModeConfig.isPromFixEnabled(), mode)
        } == true
    }

    /** 只对具备完整原生开始/松手停止链的相机候选开放 163/225。 */
    fun isHoldBurstAvailable(modeKey: String): Boolean {
        if (!isShutterKey || !longTab || deepLongTab) {
            return false
        }
        val mode = modeKey.toIntOrNull() ?: return false
        val version = cameraVersionCode() ?: return false
        return com.handlefix.CameraCapabilityRegistry.supportsHoldBurst(
            version, ModeConfig.isPromFixEnabled(), mode)
    }

    // 经典风格还需要附带作者 LSP；参数工作台则是相机自身原生能力。
    // 该模式集合必须与 共享录像键白名单中的 F_PHOENIX_CLASSIC_STYLE 保持一致：前者负责
    // 能力门，后者负责列表过滤。只改其中一处会出现“配置可写但选项不可见”或反之。
    fun isPhoenixClassicStyleAvailable(modeKey: String): Boolean {
        if (modeKey !in PHOENIX_CLASSIC_STYLE_MODES ||
            !isPhoenixCameraAvailable()) return false
        return try {
            context.packageManager.getPackageInfo(PHOENIX_CLASSIC_STYLE_LSP_PACKAGE, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 读取/保存风格白名单的能力门；仅显示在录像键短按页，避免与长按 A 混淆。 */
    fun isClassicStyleSelectionAvailable(): Boolean {
        if (camera68Mapping) {
            return isRecordKey && !longTab &&
                cameraVersionCode()?.let { CameraBuilds.isCamera68Port(it) } == true &&
                classicRuntimeModes.isNotEmpty()
        }
        if (!isPhoenixCameraAvailable()) return false
        return try {
            context.packageManager.getPackageInfo(PHOENIX_CLASSIC_STYLE_LSP_PACKAGE, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun refreshClassicStyleCatalog() {
        val version = cameraVersionCode()
        if (!isClassicStyleSelectionAvailable() || version == null) {
            classicStyleCatalog = null
            classicStyleSelectedIds = emptySet()
            return
        }
        val catalog = readClassicStyleCatalog(version)
        classicStyleCatalog = catalog
        if (catalog == null) {
            classicStyleSelectedIds = emptySet()
            return
        }
        val available = catalog.options.map { it.id }.toSet()
        val saved = ModeConfig.getClassicStyleSelection(version)
        // 首次使用或旧配置与新目录无交集时默认全选；运行时同样有安全回退。
        val selected = saved.orEmpty().filter { it in available }.toSet()
        classicStyleSelectedIds = if (saved == null || selected.isEmpty()) available else selected
    }

    fun saveClassicStyleSelection(next: Set<String>) {
        val version = cameraVersionCode() ?: return
        if (next.isEmpty()) {
            UiUtil.toast(context, "至少保留一个风格")
            return
        }
        // Set 只负责勾选状态；落盘时恢复作者目录顺序，保证“下一项”与相机原生顺序一致。
        val ordered = classicStyleCatalog?.options
            ?.map { it.id }
            ?.filter { it in next }
            ?: next.toList()
        val ok = ModeConfig.saveClassicStyleSelection(version, ordered)
        if (ok) {
            classicStyleSelectedIds = ordered.toSet()
            // 后续选择其它按键时会基于这份最新 JSON 保存，避免旧 cfg 覆盖白名单。
            cfg = ModeConfig.read()
        } else {
            UiUtil.toast(context, "保存失败：需要 root 权限（KernelSU/Magisk）")
        }
    }

    LaunchedEffect(resumeTick, longTab, key, camera68Mapping) {
        if (isRecordKey && !longTab) {
            if (camera68Mapping) {
                classicRuntimeModes = LspActivationMonitor.camera68ClassicModes(context)
            }
            refreshClassicStyleCatalog()
        } else {
            classicStyleCatalog = null
            classicStyleSelectedIds = emptySet()
        }
    }

    // 普通用户只看到当前版本已核实的能力；开发者模式在调用方直接跳过本层。
    fun filterUnavailableCapabilities(
        source: Array<Array<String>>,
        modeKey: String,
    ): Array<Array<String>> = source.filterNot {
        (it[0] == ModeConfig.F_PHOENIX_CLASSIC_STYLE && !isPhoenixClassicStyleAvailable(modeKey)) ||
            (it[0] == ModeConfig.F_FILTER && !isNativeFilterPanelAvailable(modeKey)) ||
            (it[0] == ModeConfig.F_PARAM_PANEL && !isParamPanelFeatureAvailable(modeKey)) ||
            (isRecordKey && longTab && it[0] == ModeConfig.F_LEICA && !isLongLeicaAvailable(modeKey)) ||
            (isShutterKey && longTab && it[0] == ModeConfig.F_DUAL_STYLE_CAPTURE &&
                !isDualStyleCaptureAvailable(modeKey)) ||
            (isShutterKey && longTab && it[0] == ModeConfig.F_HOLD_BURST &&
                !isHoldBurstAvailable(modeKey))
    }.toTypedArray()

    fun actionLabel(letter: String, label: String): String =
        if (isRecordKey && camera68Mapping && letter == ModeConfig.F_LEICA) {
            "摄影风格切换"
        } else {
            label
        }

    fun funcName(letter: String): String {
        funcs().forEach { if (it[0] == letter) return actionLabel(letter, it[1]) }
        return "跟随全局设置"
    }

    /**
     * 长按 A 的括号字母只是开发者诊断标记，不是另一项功能。
     * 开启开发者选项后才在动作名称旁显示内部码 A，
     * 避免把功能码误认为需要额外选择的模式。
     */
    fun longFuncLabel(letter: String, baseLabel: String): String {
        val label = actionLabel(letter, baseLabel)
        return if (letter == ModeConfig.F_LEICA && ModeConfig.isDevMode()) {
            "$label（A）"
        } else {
            label
        }
    }

    /**
     * 「显示模式编号」会为其它功能追加内部功能码，但 A 是徕卡二态的开发诊断标记，
     * 不能因为用户打开了编号显示就绕过“仅开发者模式可见”的约定。
     */
    fun formatPickerLabel(letter: String, baseLabel: String): String {
        val label = if (longTab) longFuncLabel(letter, baseLabel) else actionLabel(letter, baseLabel)
        return if (showDetail && (letter != ModeConfig.F_LEICA || ModeConfig.isDevMode())) {
            "$label ($letter)"
        } else {
            label
        }
    }

    /**
     * Phoenix 特别街拍 229 会继承普通街拍 225 的按键配置，但底包明确关闭连拍和
     * 画幅比例能力。这里只补充配置页提示，不绕过底包门控，也不扩展运行时控制器。
     */
    fun contextualShutterLabel(
        mode: Int,
        letter: String,
        longAction: Boolean,
        baseLabel: String,
    ): String {
        val isBurst = (!longAction && letter == ModeConfig.F_BURST) ||
            (longAction && letter == ModeConfig.F_HOLD_BURST)
        val isRecordRatio = isRecordKey && !longAction && letter == ModeConfig.F_RATION
        return if (mode == ModeConfig.MODE_STREET &&
            ((isShutterKey && isBurst) || isRecordRatio)) {
            "$baseLabel（仅普通街拍生效）"
        } else {
            baseLabel
        }
    }

    fun funcNameLong(letter: String): String {
        (FUNCS_LONG + FUNCS_SHUTTER_LONG).forEach {
            if (it[0] == letter) return longFuncLabel(it[0], it[1])
        }
        return "未设置"
    }

    fun filterFuncs(modeKey: String): Array<Array<String>> {
        if (camera68Mapping) {
            val version = cameraVersionCode()
            val mode = modeKey.toIntOrNull() ?: 0
            return activeFuncs().filter { (action) ->
                when {
                    version == null || !CameraBuilds.isCamera68Port(version) ->
                        action == ModeConfig.F_DEFAULT
                    !Camera68InputCapabilities.allowsModeAction(key, mode, action, longTab) -> false
                    action == ModeConfig.F_DEFAULT && (longTab ||
                        mode == Camera68InputCapabilities.EQUIP_STREET) -> true
                    else -> when {
                        isShutterKey && longTab ->
                            (action == ModeConfig.F_HOLD_BURST && isHoldBurstAvailable(modeKey)) ||
                                (action == ModeConfig.F_DUAL_STYLE_CAPTURE && isDualStyleCaptureAvailable(modeKey))
                        action == ModeConfig.F_PHOENIX_CLASSIC_STYLE ->
                            isClassicStyleSelectionAvailable() &&
                                (modeKey == "preview" || mode in classicRuntimeModes)
                        mode == Camera68InputCapabilities.EQUIP_STREET -> false
                        else -> Camera68InputCapabilities.supportsAction(key, mode, action, longTab)
                    }
                }
            }.toTypedArray()
        }
        // 开发者选项的唯一语义是“把选择权交还给用户”：四类按键及长按页都先返回
        // 各自完整功能目录，不再经过模式表或版本能力过滤。这里只改变设置页可见性和
        // 可保存性；相机进程的 descriptor、版本、模式能力门仍决定动作是否会被消费。
        if (ModeConfig.isDevMode()) return activeFuncs()
        val base = filterUnavailableCapabilities(activeFuncs(), modeKey)
        if (isShutterKey && longTab) {
            val mode = modeKey.toIntOrNull()
            val allowed = if (mode != null && ModeConfig.isLeicaStyleMode(mode)) {
                setOf(
                    ModeConfig.F_DEFAULT,
                    ModeConfig.F_DUAL_STYLE_CAPTURE,
                    ModeConfig.F_HOLD_BURST,
                )
            } else {
                setOf(ModeConfig.F_DEFAULT)
            }
            return base.filter { it[0] in allowed }.toTypedArray()
        }
        // 长按页的 A 必须沿用短按页的模式白名单；预览照片不受该表限制。
        if (longTab) {
            val shortAllowed = GripModeWhitelist.shortActions(ModeConfig.KEY_RECORD, modeKey.toIntOrNull() ?: 0)
            return if (shortAllowed == null) base else base.filter {
                it[0] != ModeConfig.F_LEICA || shortAllowed.contains(ModeConfig.F_LEICA)
            }.toTypedArray()
        }
        val allowed: Set<String>? = GripModeWhitelist.shortActions(key, modeKey.toIntOrNull() ?: 0)
            ?.let { actions ->
                // 版本区分：跟随 promFix 开关（与导入清洗 sanitizeForCamera 一致）。
                // 官方相机（promFix 关）：专业拍照 167 仅在已反查的 6.4.000370/530、
                // OS4 6.6.000510 保留 J；其它
                // 官方版本仍过滤 J。街拍 225 继续过滤 O（官方不支持）。
                // 夜景 173 EV 已放开（官方原生支持；PrometheusCam NRV1.5.0 起作者已适配）
                if (key == ModeConfig.KEY_WHEEL && !ModeConfig.isPromFixEnabled()) {
                    when (modeKey) {
                        "167" -> if (cameraVersionCode()?.let {
                            CameraBuilds.isOfficialParamPanelSupported(it)
                        } == true) actions else actions - ModeConfig.F_WORKSP
                        "225" -> actions - ModeConfig.F_FOCUSP
                        else -> actions
                    }
                } else {
                    actions
                }
            }
        if (allowed == null) return base
        return base.filter { allowed.contains(it[0]) }.toTypedArray()
    }

    /** 短按当前模式功能：直接读 cfg 状态，选择后立即刷新列表 */
    fun currentFunc(mode: Int): String {
        val c = cfg ?: return ModeConfig.F_DEFAULT
        if (!keyEnabled) return ModeConfig.F_DEFAULT
        return try {
            val keys = c.optJSONObject("keys") ?: return ModeConfig.F_DEFAULT
            val k = keys.optJSONObject(key) ?: return ModeConfig.F_DEFAULT
            val v = k.optString(mode.toString(), "")
            if (camera68Mapping && isRecordKey && mode == Camera68InputCapabilities.EQUIP_STREET) {
                return if (v == ModeConfig.F_PHOENIX_CLASSIC_STYLE) v else ModeConfig.F_DEFAULT
            }
            val action = if (v.isNullOrEmpty()) k.optString("0", ModeConfig.F_DEFAULT) else v
            if (camera68Mapping && !Camera68InputCapabilities.allowsModeAction(key, mode, action, false)) {
                ModeConfig.F_DEFAULT
            } else {
                action
            }
        } catch (t: Throwable) {
            ModeConfig.F_DEFAULT
        }
    }

    /** 长按当前模式功能：keys.<key>.long.<mode>，0=未设置 */
    fun currentFuncLong(mode: Int): String {
        val c = cfg ?: return ModeConfig.F_DEFAULT
        if (!keyEnabled) return ModeConfig.F_DEFAULT
        return try {
            val keys = c.optJSONObject("keys") ?: return ModeConfig.F_DEFAULT
            val k = keys.optJSONObject(key) ?: return ModeConfig.F_DEFAULT
            val lo = k.optJSONObject(ModeConfig.LONG_KEY) ?: return ModeConfig.F_DEFAULT
            val v = lo.optString(mode.toString(), "")
            if (v.isNullOrEmpty()) lo.optString("0", ModeConfig.F_DEFAULT) else v
        } catch (t: Throwable) {
            ModeConfig.F_DEFAULT
        }
    }

    /** 深长按读取 keys.<key>.deepLong.<mode>，没有节点时返回 0。 */
    fun currentFuncDeepLong(mode: Int): String {
        val c = cfg ?: return ModeConfig.F_DEFAULT
        if (!keyEnabled) return ModeConfig.F_DEFAULT
        return try {
            val keys = c.optJSONObject("keys") ?: return ModeConfig.F_DEFAULT
            val k = keys.optJSONObject(key) ?: return ModeConfig.F_DEFAULT
            val deep = k.optJSONObject(ModeConfig.DEEP_LONG_KEY) ?: return ModeConfig.F_DEFAULT
            val v = deep.optString(mode.toString(), "")
            if (v.isNullOrEmpty()) deep.optString("0", ModeConfig.F_DEFAULT) else v
        } catch (_: Throwable) {
            ModeConfig.F_DEFAULT
        }
    }

    fun setFunc(mode: Int, letter: String) {
        if (cfg == null) cfg = JSONObject()
        var conflictReplacement: String? = null
        var conflictLongFunction: String? = null
        try {
            val keys = cfg!!.optJSONObject("keys") ?: JSONObject().also { cfg!!.put("keys", it) }
            val k = keys.optJSONObject(key) ?: JSONObject().also { keys.put(key, it) }
            k.put(mode.toString(), letter)
            // 短按选择是本次变更的权威侧：若旧长按仍为双风格，清掉长按，
            // 使一次物理快门不会同时进入高速连拍和 C3 状态机。
            if (isShutterKey && !longTab) {
                val previousLongFunction = currentFuncLong(mode)
                conflictReplacement = ModeConfig.normalizeShutterConflict(
                    cfg!!, mode, false)
                if (conflictReplacement != null) {
                    conflictLongFunction = previousLongFunction
                }
            }
        } catch (e: Exception) {
        }
        val ok = ModeConfig.writeViaRoot(cfg!!)
        if (!ok) {
            UiUtil.toast(context, "保存失败：需要 root 权限（KernelSU/Magisk）")
        } else if (conflictReplacement != null) {
            // 归一化已经在本次写入中完成；重要的冲突说明改用带确认按钮的
            // 底部弹窗，避免 Toast 在快速连续设置时被覆盖或错过。
            shutterConflictDialogText = shutterConflictMessage(
                mode,
                changedFunction = "长按-${shutterFunctionName(
                    conflictLongFunction ?: ModeConfig.F_DUAL_STYLE_CAPTURE, true)}",
                otherFunction = "短按-${shutterFunctionName(ModeConfig.F_BURST, false)}",
                replacement = conflictReplacement,
            )
        } else {
            UiUtil.toast(context, "已保存")
        }
        cfg = ModeConfig.read() // 触发列表刷新
    }

    fun setFuncLong(mode: Int, letter: String) {
        if (letter == ModeConfig.F_LEICA && !isLongLeicaAvailable(mode.toString())) {
            UiUtil.toast(context, "当前相机版本或模式不支持长按徕卡切换")
            return
        }
        if (letter == ModeConfig.F_HOLD_BURST && !isHoldBurstAvailable(mode.toString())) {
            UiUtil.toast(context, "当前相机版本或模式尚未确认原生高速连拍入口")
            return
        }
        if (letter == ModeConfig.F_DUAL_STYLE_CAPTURE &&
            !isDualStyleCaptureAvailable(mode.toString())) {
            UiUtil.toast(context, "当前相机版本尚未确认徕卡经典/生动轮拍的安全入口")
            return
        }
        if (cfg == null) cfg = JSONObject()
        var conflictReplacement: String? = null
        try {
            val keys = cfg!!.optJSONObject("keys") ?: JSONObject().also { cfg!!.put("keys", it) }
            val k = keys.optJSONObject(key) ?: JSONObject().also { keys.put(key, it) }
            val nodeName = if (deepLongTab) ModeConfig.DEEP_LONG_KEY else ModeConfig.LONG_KEY
            val lo = k.optJSONObject(nodeName) ?: JSONObject().also { k.put(nodeName, it) }
            lo.put(mode.toString(), letter)
            // 只有快门键的“长按”页与短按高速连拍构成独占事务冲突；录像键的
            // 短长按/深长按是松键时长分段，不应复用此归一化规则。
            if (isShutterKey && longTab && !deepLongTab) {
                conflictReplacement = ModeConfig.normalizeShutterConflict(
                    cfg!!, mode, true)
            }
        } catch (e: Exception) {
        }
        val ok = ModeConfig.writeViaRoot(cfg!!)
        if (!ok) {
            UiUtil.toast(context, "保存失败：需要 root 权限（KernelSU/Magisk）")
        } else if (conflictReplacement != null) {
            shutterConflictDialogText = shutterConflictMessage(
                mode,
                changedFunction = "短按-${shutterFunctionName(ModeConfig.F_BURST, false)}",
                otherFunction = "长按-${shutterFunctionName(letter, true)}",
                replacement = conflictReplacement,
            )
        } else {
            UiUtil.toast(context, "已保存")
        }
        cfg = ModeConfig.read() // 触发列表刷新
    }

    fun saveCfg() {
        val ok = ModeConfig.writeViaRoot(cfg ?: JSONObject())
        UiUtil.toast(context, if (ok) "已保存" else "保存失败：需要 root 权限（KernelSU/Magisk）")
    }

    fun rowSummary(mode: Int): String =
        if (isRecordKey && longTab) {
            val func = if (deepLongTab) currentFuncDeepLong(mode) else currentFuncLong(mode)
            if (deepLongTab && func == ModeConfig.F_DEFAULT) {
                // 运行时的兼容语义是“深长按未设置则沿用短长按”，列表必须如实展示，
                // 不能写成普通“未设置”让用户误以为按到深阈值后不会执行任何动作。
                "跟随短长按（${funcNameLong(currentFuncLong(mode))}）"
            } else if (filterFuncs(mode.toString()).none { it[0] == func }) {
                funcNameLong(ModeConfig.F_DEFAULT)
            } else {
                funcNameLong(func)
            }
        } else if (isShutterKey && longTab) {
            val func = currentFuncLong(mode)
            if (filterFuncs(mode.toString()).none { it[0] == func }) {
                funcNameLong(ModeConfig.F_DEFAULT)
            } else {
                contextualShutterLabel(mode, func, true, funcNameLong(func))
            }
        } else {
            val func = currentFunc(mode)
            // 摘要与选择弹窗必须使用同一过滤结果。普通模式下隐藏所有当前不可用的旧
            // 配置，但不删除 JSON；开发者模式的 filterFuncs 返回完整目录，因此能如实
            // 显示并继续编辑未适配组合。
            if (filterFuncs(mode.toString()).none { it[0] == func }) {
                funcName(ModeConfig.F_DEFAULT)
            } else {
                contextualShutterLabel(mode, func, false, funcName(func))
            }
        }

    val camera68ModesAvailable = camera68Mapping &&
        cameraVersionCode()?.let(CameraBuilds::isCamera68Port) == true
    val supportedModes = MODES.filter { mode ->
        when (mode) {
            MODE_LEICA_INSTANT -> isLeicaInstantModeAvailable()
            Camera68InputCapabilities.CINEMASTER, Camera68InputCapabilities.AI_CAPTURE ->
                camera68ModesAvailable
            Camera68InputCapabilities.EQUIP_STREET ->
                camera68ModesAvailable && isRecordKey && !longTab && mode in classicRuntimeModes
            else -> true
        }
    }
    val baseModes = if (devMode) supportedModes
        else supportedModes.filter { !isMoreMode(it) && !(longTab && (it == 162 || it == 180)) }
    val visibleModes = if (longTab) baseModes.filter { it != 0 } else baseModes

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "$title · 模式配置",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (isRecordKey) {
                TabRow(
                    tabs = listOf("短按", "短长按", "深长按"),
                    selectedTabIndex = pressTab,
                    onTabSelected = {
                        UiUtil.hapticClick(view)
                        pressTab = it
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    // miuix TabRow 默认样式（页面底色 + 选中项浅色容器底），对齐 miuix/官方风格
                )
            } else if (isShutterKey) {
                TabRow(
                    // C3 通过能力表打开第二页；不支持的相机版本只会看到“未设置”，
                    // 运行时也不会安装快门状态机。Tab 本身不按版本隐藏，便于同一配置页
                    // 在切换相机后保持结构稳定。
                    tabs = listOf("短按", "长按"),
                    selectedTabIndex = pressTab,
                    onTabSelected = {
                        UiUtil.hapticClick(view)
                        pressTab = it
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            val modeListState = rememberLazyListState()
            LazyListEdgeHaptic(modeListState, view)
            LazyColumn(
                state = modeListState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Card {
                        SwitchPreference(
                            title = "启用按键功能自定义",
                            summary = "关闭按键功能自定义，将恢复摄影手柄该按键的原定设置",
                            checked = keyEnabled,
                            onCheckedChange = { c ->
                                keyEnabled = c
                                if (cfg == null) cfg = JSONObject()
                                try {
                                    val keys = cfg!!.optJSONObject("keys") ?: JSONObject().also { cfg!!.put("keys", it) }
                                    val k = keys.optJSONObject(key) ?: JSONObject().also { keys.put(key, it) }
                                    k.put("enabled", c)
                                } catch (e: Exception) {
                                }
                                saveCfg()
                                UiUtil.hapticClick(view)
                            },
                        )
                        ArrowPreference(
                            title = "预览支持功能",
                            summary = if (longTab) "查看本按键长按可选功能"
                            else if (isRecordKey) "查看本按键短按可选功能"
                            else "查看本按键全部可选功能",
                            onClick = { showPreview = true },
                        )
                        if (isRecordKey && !longTab && isClassicStyleSelectionAvailable()) {
                            val catalog = classicStyleCatalog
                            ArrowPreference(
                                title = "徕卡经典风格切换-自定义",
                                summary = if (catalog == null) {
                                    "点击读取当前版本的可用风格"
                                } else {
                                    "已选择 ${classicStyleSelectedIds.size}/${catalog.options.size} 项"
                                },
                                onClick = {
                                    refreshClassicStyleCatalog()
                                    showClassicStyleDialog = true
                                },
                            )
                        }
                    }
                }
                if (isRecordKey && longTab) {
                    item {
                        Card {
                            ArrowPreference(
                                title = "短长按起始阈值",
                                summary = "当前：${longPressMs}ms",
                                onClick = { showLpDialog = true },
                            )
                            ArrowPreference(
                                title = "深长按起始阈值",
                                summary = "当前：${deepLongPressMs}ms",
                                onClick = { showDeepLpDialog = true },
                            )
                        }
                    }
                }
                if (isShutterKey && longTab) {
                    item {
                        Card {
                            ArrowPreference(
                                title = "快门键长按响应时间",
                                summary = "当前：${shutterLongPressMs}ms",
                                onClick = { showShutterLpDialog = true },
                            )
                        }
                    }
                }
                item {
                    SmallTitle(
                        text = (if (longTab) "长按模式列表" else "模式列表")
                                + if (ModeConfig.isDevMode()) " · 开发者模式：开（显示全部功能）" else " · 开发者模式：关",
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                item {
                    Card {
                        visibleModes.forEach { mode ->
                            val name = MODE_NAMES[MODES.indexOf(mode)]
                            ArrowPreference(
                                // 显示模式编号开关：打开时显示 (163)，关闭只显示名称
                                title = if (showDetail && mode != 0) "$name ($mode)" else name,
                                summary = if (mode == Camera68InputCapabilities.EQUIP_STREET &&
                                    currentFunc(mode) == ModeConfig.F_DEFAULT
                                ) "跟随街拍（225）设置" else rowSummary(mode),
                                onClick = { pickMode = mode },
                            )
                        }
                    }
                }
            }
        }

        // ===== 弹窗（左对齐；必须在 Scaffold 内部） =====

        // 选择功能弹窗（√ 在右）
        pickMode?.let { mode ->
            val modeName = MODE_NAMES[MODES.indexOf(mode)] +
                    if (showDetail && mode != 0) " (${mode})" else ""
            val funcs = filterFuncs(mode.toString()).map { option ->
                if (deepLongTab && option[0] == ModeConfig.F_DEFAULT) {
                    arrayOf(option[0], "跟随短长按")
                } else if (mode == Camera68InputCapabilities.EQUIP_STREET &&
                    option[0] == ModeConfig.F_DEFAULT
                ) {
                    arrayOf(option[0], "跟随街拍（225）设置")
                } else {
                    arrayOf(
                        option[0],
                        contextualShutterLabel(mode, option[0], longTab, option[1]),
                    )
                }
            }.toTypedArray()
            val cur = when {
                deepLongTab -> currentFuncDeepLong(mode)
                longTab -> currentFuncLong(mode)
                else -> currentFunc(mode)
            }
            LeftDialog(
                show = true,
                title = (when {
                    deepLongTab -> "深长按·"
                    longTab -> "长按·"
                    else -> ""
                }) + "选择${modeName}的功能",
                onDismissRequest = { pickMode = null },
            ) {
                Column {
                    // 列表可滚动：功能较多时（如开发者模式全列表）超出屏幕部分不再被裁剪
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        funcs.forEach { f ->
                            RadioButtonPreference(
                                title = formatPickerLabel(f[0], f[1]),
                                selected = f[0] == cur,
                                radioButtonLocation = RadioButtonLocation.End,
                                onClick = {
                                    pickMode = null
                                    if (longTab) setFuncLong(mode, f[0]) else setFunc(mode, f[0])
                                },
                            )
                        }
                    }
                    DialogButtons(cancel = "取消", onCancel = { pickMode = null })
                }
            }
        }

        // 录像键短长按阈值（松键分段；不在 500ms 时提前执行，避免一次按键触发两个动作）
        if (showLpDialog) {
            var lpMs by remember { mutableStateOf(longPressMs) }
            LeftDialog(
                show = true,
                title = "短长按起始阈值",
                text = "录像键松开后达到此时长才执行短长按动作：",
                onDismissRequest = { showLpDialog = false },
            ) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Slider(
                        value = lpMs.toFloat(),
                        onValueChange = { lpMs = it.roundToInt() },
                        valueRange = 500f..800f,
                        // steps=中间步数，总档位=steps+2：2 -> 500/600/700/800。
                        steps = 2,
                        showKeyPoints = true,
                        keyPoints = listOf(500f, 600f, 700f, 800f),
                        hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                    )
                    Text(
                        text = "当前：${lpMs}ms",
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    DialogButtons(
                        cancel = "取消",
                        onCancel = { showLpDialog = false },
                        confirm = "确定",
                        onConfirm = {
                            if (cfg == null) cfg = JSONObject()
                            cfg!!.put("longPressMs", lpMs)
                            val ok = ModeConfig.writeViaRoot(cfg!!)
                            longPressMs = lpMs // 立即刷新列表页 summary
                            showLpDialog = false
                            UiUtil.toast(context, if (ok) "已保存" else "保存失败：需要 root 权限", true)
                        },
                    )
                }
            }
        }

        // 作者 LSP 风格目录选择：保存稳定 ID，切换时由相机进程实时求交集。
        if (showClassicStyleDialog) {
            val catalog = classicStyleCatalog
            LeftDialog(
                show = true,
                title = "徕卡经典风格切换-自定义",
                text = if (catalog == null) {
                    "暂未读取到当前版本的徕卡经典风格目录，请先打开一次相机并手动切换风格后再试。"
                } else {
                    "${catalog.profile} · 可选 ${catalog.options.size} 项"
                },
                onDismissRequest = { showClassicStyleDialog = false },
            ) {
                Column {
                    if (catalog != null) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            catalog.options.forEach { option ->
                                SwitchPreference(
                                    title = option.label,
                                    checked = option.id in classicStyleSelectedIds,
                                    onCheckedChange = { checked ->
                                        val next = if (checked) {
                                            classicStyleSelectedIds + option.id
                                        } else {
                                            classicStyleSelectedIds - option.id
                                        }
                                        saveClassicStyleSelection(next)
                                    },
                                )
                            }
                        }
                    }
                    DialogButtons(
                        confirm = "完成",
                        onConfirm = { showClassicStyleDialog = false },
                    )
                }
            }
        }

        shutterConflictDialogText?.let { message ->
            // 与“预览支持功能”共用同一种左对齐 OverlayDialog + 底部确认按钮，
            // 避免冲突说明使用另一套 BottomSheet 版式造成视觉不一致。
            LeftDialog(
                show = true,
                title = "功能设置冲突",
                text = message,
                onDismissRequest = { shutterConflictDialogText = null },
            ) {
                DialogButtons(
                    confirm = "确定",
                    onConfirm = { shutterConflictDialogText = null },
                )
            }
        }

        // 两段范围在 UI 上天然分离：短长按最高 800ms，深长按最低 1000ms。
        if (showDeepLpDialog) {
            var deepMs by remember { mutableStateOf(deepLongPressMs) }
            LeftDialog(
                show = true,
                title = "深长按起始阈值",
                text = "达到此时长执行深长按动作；同一次松键不会同时执行短长按：",
                onDismissRequest = { showDeepLpDialog = false },
            ) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Slider(
                        value = deepMs.toFloat(),
                        onValueChange = { deepMs = it.roundToInt() },
                        valueRange = 1000f..1500f,
                        steps = 4,
                        showKeyPoints = true,
                        keyPoints = (1000..1500 step 100).map { it.toFloat() },
                        hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                    )
                    Text(
                        text = "当前：${deepMs}ms",
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    DialogButtons(
                        cancel = "取消",
                        onCancel = { showDeepLpDialog = false },
                        confirm = "确定",
                        onConfirm = {
                            if (cfg == null) cfg = JSONObject()
                            cfg!!.put("deepLongPressMs", deepMs)
                            val ok = ModeConfig.writeViaRoot(cfg!!)
                            deepLongPressMs = deepMs
                            showDeepLpDialog = false
                            UiUtil.toast(context, if (ok) "已保存" else "保存失败：需要 root 权限", true)
                        },
                    )
                }
            }
        }

        // 快门键独立长按阈值，避免修改录像键分段阈值时意外改变快门行为。
        if (showShutterLpDialog) {
            var shutterMs by remember { mutableStateOf(shutterLongPressMs) }
            LeftDialog(
                show = true,
                title = "快门键长按响应时间",
                onDismissRequest = { showShutterLpDialog = false },
            ) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Slider(
                        value = shutterMs.toFloat(),
                        onValueChange = { shutterMs = it.roundToInt() },
                        valueRange = 500f..1000f,
                        steps = 4,
                        showKeyPoints = true,
                        keyPoints = (500..1000 step 100).map { it.toFloat() },
                        hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                    )
                    Text(
                        text = "当前：${shutterMs}ms",
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    DialogButtons(
                        cancel = "取消",
                        onCancel = { showShutterLpDialog = false },
                        confirm = "确定",
                        onConfirm = {
                            if (cfg == null) cfg = JSONObject()
                            shutterMs = shutterMs.coerceIn(500, 1000)
                            cfg!!.put("shutterLongPressMs", shutterMs)
                            val ok = ModeConfig.writeViaRoot(cfg!!)
                            shutterLongPressMs = shutterMs
                            showShutterLpDialog = false
                            UiUtil.toast(context, if (ok) "已保存" else "保存失败：需要 root 权限", true)
                        },
                    )
                }
            }
        }

        // 预览功能弹窗（内容较长，文本区可滚动）
        if (showPreview) {
            val sb = StringBuilder()
            // 预览与实际选择页使用同一开发者语义：开发者可查看完整目录，普通用户只看
            // 当前版本已核实能力。运行时安全门不会因为预览或保存动作而被绕过。
            (when {
                camera68Mapping && !(isShutterKey && longTab) -> filterFuncs("preview")
                ModeConfig.isDevMode() -> activeFuncs()
                else -> filterUnavailableCapabilities(activeFuncs(), "preview")
            })
                .forEach {
                    // A 是内部诊断标记：普通用户的预览也不能看到它；开发者模式下
                    // 保留功能码，便于反查 JSON 与相机 dex 入口。
                    val code = if (it[0] != ModeConfig.F_LEICA || ModeConfig.isDevMode()) {
                        "${it[0]}  "
                    } else {
                        ""
                    }
                    sb.append(code)
                        .append(if (longTab) longFuncLabel(it[0], it[1]) else actionLabel(it[0], it[1]))
                        .append("\n")
                }
            LeftDialog(
                show = true,
                title = (if (longTab) "长按 · " else "") + "预览「$title」支持功能",
                onDismissRequest = { showPreview = false },
            ) {
                Column {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = sb.toString().trim(),
                            modifier = Modifier.padding(horizontal = 4.dp),
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                    DialogButtons(confirm = "确定", onConfirm = { showPreview = false })
                }
            }
        }
    }
}
