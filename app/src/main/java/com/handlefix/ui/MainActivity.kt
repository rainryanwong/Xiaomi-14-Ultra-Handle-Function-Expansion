package com.handlefix.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.view.InputDevice
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.handlefix.ModeConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.blendColors
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import com.handlefix.CameraBuilds

/**
 * v10.3 主界面（沉浸式 + 弹窗按钮官方样式 + 左对齐 + 振动反馈）
 */
class MainActivity : ComponentActivity() {

    private val resumeTick = mutableStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick.value++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XiaomiEdgeToEdge()
            HandleFixTheme {
                DeviceEligibilityGate(this) {
                    LspActivationGate(this) { MainScreen(resumeTick.value) }
                }
            }
        }
    }
}

private data class KeyItem(val id: String, val name: String, val desc: String)

private val KEYS = listOf(
    KeyItem(ModeConfig.KEY_SHUTTER, "快门按键", "keyCode 27"),
    KeyItem(ModeConfig.KEY_ZOOM, "变焦拨杆", "keyCode 168/169"),
    KeyItem(ModeConfig.KEY_WHEEL, "自定义拨轮", "波轮滚动"),
    KeyItem(ModeConfig.KEY_RECORD, "录像按键", "keyCode 313"),
)

private fun gripInputGuardSummary(cfg: JSONObject?): String {
    if (cfg?.optBoolean(ModeConfig.OPTION_GRIP_INPUT_GUARD, false) != true) {
        return "已关闭"
    }
    val scope = when (ModeConfig.readGripInputGuardScope(cfg)) {
        ModeConfig.GRIP_GUARD_SCOPE_KEYGUARD -> "仅锁屏"
        ModeConfig.GRIP_GUARD_SCOPE_SCREEN_OFF -> "仅息屏"
        else -> "锁屏和息屏"
    }
    val mask = ModeConfig.readGripInputGuardMask(cfg)
    val inputs = if (mask == ModeConfig.GRIP_INPUT_ALL) {
        "全按键不响应"
    } else {
        buildList {
            if (mask and ModeConfig.GRIP_INPUT_SHUTTER != 0) add("快门")
            if (mask and ModeConfig.GRIP_INPUT_ZOOM != 0) add("变焦拨杆")
            if (mask and ModeConfig.GRIP_INPUT_WHEEL != 0) add("拨轮")
            if (mask and ModeConfig.GRIP_INPUT_RECORD != 0) add("录像键")
        }.joinToString("、").ifEmpty { "未选择按键" }
    }
    return "$scope · $inputs"
}

@Composable
private fun MainScreen(resumeTick: Int) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 3 })

    var cfg by remember { mutableStateOf(ModeConfig.read()) }
    var master by remember { mutableStateOf(cfg?.optBoolean("enabled", true) ?: true) }
    var mappingScheme by remember { mutableStateOf(ModeConfig.readMappingScheme(cfg)) }
    val promFix = mappingScheme == ModeConfig.MAPPING_PROMETHEUS
    var v51 by remember { mutableStateOf(cfg?.optBoolean("v51", false) ?: false) }
    var cameraVersionCode by remember { mutableStateOf(readInstalledCameraVersionCode(context)) }
    var dev by remember { mutableStateOf(cfg?.optBoolean("devMode", false) ?: false) }
    var streetSpecial by remember {
        mutableStateOf(cfg?.optBoolean(ModeConfig.OPTION_STREET_SPECIAL, false) ?: false)
    }
    var streetWithoutGrip by remember {
        mutableStateOf(cfg?.optBoolean(ModeConfig.OPTION_STREET_SPECIAL_WITHOUT_GRIP, false) ?: false)
    }
    var halfFocusPolicy by remember {
        mutableStateOf(
            cfg?.optString(ModeConfig.OPTION_HALF_FOCUS_POLICY, ModeConfig.HALF_FOCUS_NATIVE)
                ?.takeIf {
                    it == ModeConfig.HALF_FOCUS_DISABLE || it == ModeConfig.HALF_FOCUS_CENTER
                }
                ?: ModeConfig.HALF_FOCUS_NATIVE,
        )
    }
    var showDetail by remember { mutableStateOf(ModeConfig.isShowDetail()) }
    // 手柄连接状态（2s 轮询输入设备 N1G）：驱动「摄影手柄设置」入口的显示/隐藏
    var gripConnected by remember { mutableStateOf(isGripConnected(context)) }
    var showRootDialog by remember { mutableStateOf(!ModeConfig.hasRoot()) }
    var showRebootDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showVersionDialog by remember { mutableStateOf(false) }
    var showChangelogDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showBackupConfirm by remember { mutableStateOf(false) }
    var backupResult by remember { mutableStateOf<String?>(null) }

    // 轮询手柄连接状态：拔出后立即隐藏「摄影手柄设置」入口
    LaunchedEffect(Unit) {
        while (true) {
            gripConnected = isGripConnected(context)
            delay(2000)
        }
    }

    LaunchedEffect(resumeTick) {
        ModeConfig.migrateConfigToNewDir()
        cfg = ModeConfig.read()
        ThemePreference.sync(cfg)
        cameraVersionCode = readInstalledCameraVersionCode(context)
        master = cfg?.optBoolean("enabled", true) ?: true
        mappingScheme = ModeConfig.readMappingScheme(cfg)
        v51 = cfg?.optBoolean("v51", false) ?: false
        dev = cfg?.optBoolean("devMode", false) ?: false
        streetSpecial = cfg?.optBoolean(ModeConfig.OPTION_STREET_SPECIAL, false) ?: false
        streetWithoutGrip = streetSpecial && (cfg?.optBoolean(ModeConfig.OPTION_STREET_SPECIAL_WITHOUT_GRIP, false) ?: false)
        halfFocusPolicy = cfg?.optString(
            ModeConfig.OPTION_HALF_FOCUS_POLICY,
            ModeConfig.HALF_FOCUS_NATIVE,
        )?.takeIf {
            it == ModeConfig.HALF_FOCUS_DISABLE || it == ModeConfig.HALF_FOCUS_CENTER
        } ?: ModeConfig.HALF_FOCUS_NATIVE
        showDetail = ModeConfig.isShowDetail()
        if (needsReboot(context)) {
            showRebootDialog = true
        }
    }

    fun ensureCfg(): JSONObject {
        if (cfg == null) cfg = JSONObject()
        return cfg!!
    }

    fun save() {
        UiUtil.toast(
            context,
            if (ModeConfig.writeViaRoot(ensureCfg())) "已保存"
            else "保存失败：需要 root 权限（KernelSU/Magisk）",
        )
    }

    val subtitle = when (pagerState.currentPage) {
        1 -> "模块设置与调试"
        2 -> "备份与恢复"
        else -> "模块开关与按键映射"
    }

    fun saveStreetOptions(parent: Boolean, withoutGrip: Boolean) {
        // Commit a copy first: failed Root writes cannot leave UI/config dependencies half changed.
        val updated = JSONObject(ensureCfg().toString()).apply {
            put(ModeConfig.OPTION_STREET_SPECIAL, parent)
            put(ModeConfig.OPTION_STREET_SPECIAL_WITHOUT_GRIP, parent && withoutGrip)
        }
        if (ModeConfig.writeViaRoot(updated)) {
            cfg = updated
            streetSpecial = parent
            streetWithoutGrip = parent && withoutGrip
            UiUtil.stopCameraQuiet()
            UiUtil.toast(context, "已保存并停止相机进程，重新打开相机生效")
        } else UiUtil.toast(context, "保存失败：需要 root 权限（KernelSU/Magisk）")
        UiUtil.hapticClick(view)
    }

    // 旧版 Prometheus 开关是精确版本适配项：只在模块版 V5.1 内嵌的 V5.0 相机
    // versionCode=750005100 时显示。隐藏时保留 v51 配置，避免切换相机后丢失用户选择。
    val showV51Adapter = promFix
            && cameraVersionCode == CameraBuilds.PROMETHEUS_MODULE_V51_VERSION_CODE

    val quickLaunchAvailable = cameraVersionCode?.let { version ->
        (promFix && CameraBuilds.isPhoenixOs4Family(version)) ||
                (mappingScheme == ModeConfig.MAPPING_CAMERA_68 && android.os.Build.VERSION.SDK_INT >= 35 &&
                    CameraBuilds.isCamera68Port(version))
    } == true

    // ===== 界面毛玻璃（v10.54 起默认启用，无开关）：顶栏/底栏毛玻璃 =====
    val pageBg = MiuixTheme.colorScheme.surface
    // layerBackdrop 只捕获应用它的内容，不包含 Scaffold 的 Surface 背景；
    // 先画一层面板背景色打底，避免模糊内容出现透明伪影
    val backdrop = rememberLayerBackdrop {
        drawRect(pageBg)
        drawContent()
    }
    // drawBackdrop 的 blur() 以像素为单位，dp * density 换算
    val density = LocalDensity.current.density
    // 官方 uitest 参数：表面色 80% alpha 混色遮盖，压暗模糊内容、消除滚动时文字边缘抽动观感
    val materialColors = remember(pageBg) {
        BlurColors(
            blendColors = listOf(
                BlendColorEntry(pageBg.copy(alpha = 0.8f), BlurBlendMode.SrcOver),
            ),
        )
    }
    // 列表顶部/底部需给悬浮顶栏/底栏留出空间。
    // 顶栏带副标题时实际高度约 62dp（52dp 标题区 + 副标题约 10dp），
    // 额外留 24dp 余量，避免第一个卡片顶部圆角/描边框被顶栏截断
    val listPadding = PaddingValues(
        start = 12.dp,
        end = 12.dp,
        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 52.dp + 24.dp,
        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 64.dp,
    )

    // 底栏三个导航项（毛玻璃/普通两种模式共用，避免重复代码）
    val navContent: @Composable RowScope.() -> Unit = {
        NavigationBarItem(
            selected = pagerState.currentPage == 0,
            onClick = {
                UiUtil.hapticClick(view)
                scope.launch { pagerState.animateScrollToPage(0) }
            },
            icon = MiuixIcons.Home,
            label = "主页",
        )
        NavigationBarItem(
            selected = pagerState.currentPage == 1,
            onClick = {
                UiUtil.hapticClick(view)
                scope.launch { pagerState.animateScrollToPage(1) }
            },
            icon = MiuixIcons.Info,
            label = "模块设置与调试",
        )
        NavigationBarItem(
            selected = pagerState.currentPage == 2,
            onClick = {
                UiUtil.hapticClick(view)
                scope.launch { pagerState.animateScrollToPage(2) }
            },
            icon = MiuixIcons.Backup,
            label = "备份与恢复",
        )
    }

    // 全屏容器：内容延伸至状态栏/导航栏，顶栏/底栏以悬浮覆盖层叠在内容之上
    Box(Modifier.fillMaxSize()) {
        // 不占位 Scaffold：仅提供弹窗 popup 层，内容全屏交给 HorizontalPager
        Scaffold(
            containerColor = pageBg,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { _ ->
            HorizontalPager(
                state = pagerState,
                // 全屏内容：layerBackdrop 捕获整个屏幕画面（含状态栏/导航栏区域），
                // 供悬浮顶栏/底栏毛玻璃按同一屏幕坐标采样
                modifier = Modifier.fillMaxSize().layerBackdrop(backdrop),
            ) { page ->
                // 页面顺序是主页、模块设置与调试、备份与恢复；内容块与导航索引
                // 使用同一页码，保证滑动、点击和状态恢复保持一致。
                when (page) {
                    // ============ 主页 ============
                0 -> {
                        val listState = rememberLazyListState()
                        LazyListEdgeHaptic(listState, view)
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = listPadding,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                        item {
                            Card {
                                SwitchPreference(
                                    title = "启用模块功能",
                                    summary = "总开关：控制模块按键映射是否生效",
                                    checked = master,
                                    onCheckedChange = { c ->
                                        master = c
                                        ensureCfg().put("enabled", c)
                                        save()
                                        UiUtil.hapticClick(view)
                                        UiUtil.forceStopCamera(context, c)
                                    },
                                )
                                OverlayDropdownPreference(
                                    items = listOf("Prometheus Cam", "官方相机", "6.8相机移植"),
                                    selectedIndex = mappingScheme,
                                    title = "手柄按键映射方案",
                                    onSelectedIndexChange = { idx ->
                                        val updated = JSONObject(ensureCfg().toString())
                                        ModeConfig.setMappingScheme(updated, idx)
                                        if (idx == ModeConfig.MAPPING_OFFICIAL) {
                                            updated.put(ModeConfig.OPTION_STREET_SPECIAL, false)
                                            updated.put(ModeConfig.OPTION_STREET_SPECIAL_WITHOUT_GRIP, false)
                                        }
                                        if (idx != ModeConfig.MAPPING_CAMERA_68) {
                                            ModeConfig.sanitizeForCamera(
                                                updated, idx == ModeConfig.MAPPING_PROMETHEUS,
                                            )
                                        }
                                        if (ModeConfig.writeViaRoot(updated)) {
                                            cfg = updated
                                            mappingScheme = idx
                                            streetSpecial = updated.optBoolean(ModeConfig.OPTION_STREET_SPECIAL)
                                            streetWithoutGrip = updated.optBoolean(ModeConfig.OPTION_STREET_SPECIAL_WITHOUT_GRIP)
                                            UiUtil.stopCameraQuiet()
                                            UiUtil.toast(context, "已保存，重新打开相机生效")
                                        } else {
                                            UiUtil.toast(context, "保存失败：需要 root 权限（KernelSU/Magisk）", true)
                                        }
                                        UiUtil.hapticClick(view)
                                    },
                                )
                                // 旧版开关只对模块版 V5.1 内嵌的 V5.0 相机显示；其它版本
                                // 不删配置，便于用户切回已确认版本时恢复原选择。
                                if (showV51Adapter) {
                                    SwitchPreference(
                                        title = "旧版“Prometheus Cam”手柄适配",
                                        summary = if (v51) "已启用（“Prometheus Cam” V5.1版本，未适配摄影手柄基础功能，启用开关后可正常使用手柄）" else "已关闭（自NRV-1.3.2起，“Prometheus Cam” 已适配摄影手柄基础功能；建议关闭该开关，避免因多次挂载发生相机功能异常问题）",
                                        checked = v51,
                                        onCheckedChange = { c ->
                                            v51 = c
                                            ensureCfg().put("v51", c)
                                            save()
                                            UiUtil.hapticClick(view)
                                            UiUtil.forceStopCamera(context, c)
                                        },
                                    )
                                }
                            }
                        }
                        item {
                            SmallTitle(
                                text = "按键配置",
                                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        item {
                            Card {
                                KEYS.forEach { k ->
                                    val counts = UiUtil.customCounts(cfg, k.id)
                                    val countSummary = when (k.id) {
                                        ModeConfig.KEY_SHUTTER ->
                                            "短按 ${counts.short} 条，长按 ${counts.long} 条"
                                        ModeConfig.KEY_RECORD ->
                                            "短按 ${counts.short} 条，短长按 ${counts.long} 条，深长按 ${counts.deepLong} 条"
                                        ModeConfig.KEY_ZOOM -> "拨杆 ${counts.short} 条"
                                        else -> "拨轮 ${counts.short} 条"
                                    }
                                    ArrowPreference(
                                        title = k.name,
                                        // 「显示模式编号」开关打开时显示 keycode/波轮滚动等按键码，关闭只显示配置状态
                                        summary = (if (showDetail) k.desc + "  ·  " else "") + countSummary,
                                        onClick = {
                                            context.startActivity(
                                                Intent(context, KeyConfigActivity::class.java).apply {
                                                    putExtra("key", k.id)
                                                    putExtra("title", k.name)
                                                },
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        item {
                            SmallTitle(
                                text = "其他",
                                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        item {
                            Card {
                                ArrowPreference(title = "使用说明", onClick = { showHelpDialog = true })
                                ArrowPreference(title = "适配版本", onClick = { showVersionDialog = true })
                            }
                        }
                    }
                    }
    
                    // ============ 备份与恢复 ============
                2 -> {
                        val listState = rememberLazyListState()
                        LazyListEdgeHaptic(listState, view)
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = listPadding,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                        item {
                            Card {
                                ArrowPreference(
                                    title = "恢复默认规则",
                                    summary = "清除所有模式规则，全部按键回到“跟随设置页”",
                                    onClick = { showResetDialog = true },
                                )
                                ArrowPreference(
                                    title = "备份配置",
                                    summary = "备份当前设置到 Download/HandleFixConfig/backups",
                                    onClick = { showBackupConfirm = true },
                                )
                                ArrowPreference(
                                    title = "导入配置",
                                    summary = "点击导入 · 长按更多操作",
                                    onClick = { context.startActivity(Intent(context, ImportBackupActivity::class.java)) },
                                )
                            }
                        }

                    }
                    }
    
                    // ============ 模块设置与调试 ============
                1 -> {
                        val listState = rememberLazyListState()
                        LazyListEdgeHaptic(listState, view)
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = listPadding,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                        item {
                            // 摄影手柄常驻状态卡（标题/图片横滑切换/手机+手柄电量实时显示）
                            GripStatusCard()
                        }
                        item {
                            // 充电控制紧跟状态卡；关闭主开关后保护项收起，状态值仍由系统保留。
                            Card {
                                GripChargeSettingsPreferences(resumeTick)
                            }
                        }
                        item {
                            Card {
                                // 仅识别到手柄插入时显示；点击跳转系统摄影手柄功能设置页
                                if (gripConnected) {
                                    ArrowPreference(
                                        title = "摄影手柄设置",
                                        summary = "点击后跳转至摄影手柄功能设置页",
                                        onClick = { openSystemGripSettings(context) },
                                    )
                                }
                                ArrowPreference(
                                    title = "锁屏/息屏禁用手柄按键",
                                    summary = gripInputGuardSummary(cfg),
                                    onClick = {
                                        context.startActivity(
                                            Intent(context, GripInputGuardActivity::class.java),
                                        )
                                    },
                                )
                                ArrowPreference(
                                    title = "相机模式快捷跳转",
                                    summary = "通过摄影手柄按键，快捷跳转至相机对应模式",
                                    enabled = quickLaunchAvailable,
                                    onClick = {
                                        context.startActivity(
                                            Intent(context, GripQuickLaunchActivity::class.java),
                                        )
                                    },
                                )
                                // C6 的中心单次 AF 调用相机自己的 FocusManager 中心区域与
                                // 超时恢复状态机；不注入触摸，也不直写 Camera2 请求。
                                OverlayDropdownPreference(
                                    items = listOf(
                                        "原生（默认）",
                                        "禁用手柄对焦",
                                        "单次中心对焦",
                                    ),
                                    selectedIndex = when (halfFocusPolicy) {
                                        ModeConfig.HALF_FOCUS_DISABLE -> 1
                                        ModeConfig.HALF_FOCUS_CENTER -> 2
                                        else -> 0
                                    },
                                    title = "半按对焦策略",
                                    summary = when (halfFocusPolicy) {
                                        ModeConfig.HALF_FOCUS_DISABLE ->
                                            "由相机进行自动对焦"
                                        ModeConfig.HALF_FOCUS_CENTER ->
                                            "对画面中心进行对焦"
                                        else -> "摄影手柄原生半按对焦"
                                    },
                                    onSelectedIndexChange = { idx ->
                                        halfFocusPolicy = when (idx) {
                                            1 -> ModeConfig.HALF_FOCUS_DISABLE
                                            2 -> ModeConfig.HALF_FOCUS_CENTER
                                            else -> ModeConfig.HALF_FOCUS_NATIVE
                                        }
                                        ensureCfg().put(
                                            ModeConfig.OPTION_HALF_FOCUS_POLICY,
                                            halfFocusPolicy,
                                        )
                                        save()
                                        UiUtil.hapticClick(view)
                                    },
                                )
                                // Phoenix 上游现已原生提供街拍特别款，设置入口暂时隐藏。
                                // streetSpecial / streetSpecialWithoutGrip 配置、Hook 与备份能力均保留，
                                // 旧配置不会在本次 UI 调整中被删除或重写。
                                ArrowPreference(
                                    title = "模块诊断",
                                    summary = "检测模块运行环境和各功能运行情况",
                                    onClick = { context.startActivity(Intent(context, EnvCheckActivity::class.java)) },
                                )
                            }
                        }
                        item {
                            Card {
                                OverlayDropdownPreference(
                                    title = "主题设置",
                                    summary = "控制模块浅色或深色显示",
                                    items = listOf("跟随系统", "浅色样式", "深色样式"),
                                    selectedIndex = ThemePreference.mode,
                                    onSelectedIndexChange = { index ->
                                        ensureCfg().put("themeMode", index)
                                        if (ModeConfig.writeViaRoot(ensureCfg())) ThemePreference.mode = index
                                        else UiUtil.toast(context, "保存失败：需要 root 权限")
                                    },
                                )
                                ArrowPreference(
                                    title = "本模块项目地址",
                                    summary = "点击跳转至本项目GitHub链接",
                                    onClick = {
                                        val repository = Uri.parse("https://github.com/rainryanwong/Xiaomi-14-Ultra-Handle-Function-Expansion")
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, repository))
                                        } catch (_: android.content.ActivityNotFoundException) {
                                            UiUtil.toast(context, "未找到可以打开 GitHub 链接的应用")
                                        }
                                    },
                                )
                                ArrowPreference(
                                    title = "更新日志",
                                    summary = "查看各版本更新内容",
                                    onClick = { showChangelogDialog = true },
                                )
                            }
                        }
                        item {
                            // 开发者信息与 UI 组件说明保持在操作项之后。
                            DeveloperProfileCard()
                        }
                        item {
                            Card {
                                ArrowPreference(
                                    title = "本模块使用 Miuix UI 组件构建",
                                    summary = "点击跳转至Miuix GitHub页面",
                                    onClick = {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/compose-miuix-ui/miuix")),
                                        )
                                    },
                                )
                            }
                        }
                    }
                    }
                }
            }

        // 悬浮毛玻璃覆盖层（纯绘制不拦截触摸，事件穿透到下方内容）。
        // 放在 content 层：Scaffold 的弹窗在 popup 层（z 最高），不会被这里遮挡
        // 悬浮顶栏：毛玻璃采样整个屏幕内容，内容滚到顶栏下方被吸收
        Box(Modifier.align(Alignment.TopCenter)) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RectangleShape },
                        effects = {
                            // 级联三次 7dp：等效约 12.1dp 强度，2x 降采样（低重采样伪影）
                            blur(7f * density)
                            blur(7f * density)
                            blur(7f * density)
                            // 表面色 80% 混色遮盖（官方 uitest 参数）：压暗模糊内容，消除抽动观感
                            blendColors(materialColors)
                        },
                    ),
            )
            SmallTopAppBar(
                title = "摄影手柄功能拓展",
                subtitle = subtitle,
                color = Color.Transparent,
            )
        }
        // 悬浮底栏
        Box(Modifier.align(Alignment.BottomCenter)) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RectangleShape },
                        effects = {
                            blur(7f * density)
                            blur(7f * density)
                            blur(7f * density)
                            blendColors(materialColors)
                        },
                    ),
            )
            NavigationBar(
                color = Color.Transparent,
                showDivider = false,
            ) {
                navContent()
            }
        }

        // ===== 弹窗（左对齐；必须在 Scaffold 内部） =====

        // Root 权限检测弹窗：未授权时提示并退出
        if (showRootDialog) {
            LeftDialog(
                show = true,
                title = "Root 权限",
                text = "识别到模块未获取到Root权限，影响部分功能的使用，请给予本模块Root权限。",
                onDismissRequest = { (context as? Activity)?.finish() },
            ) {
                DialogButtons(
                    confirm = "确定",
                    onConfirm = { (context as? Activity)?.finish() },
                )
            }
        }

        // 重启检测提示（模块安装/更新后未重启且未加载到相机）
        if (showRebootDialog) {
            LeftDialog(
                show = true,
                title = "建议重启",
                text = "检测到模块是在本次开机后安装/更新的，且相机进程尚未加载新模块。\n\n请重启手机，或在 LSPosed 中确认已启用后，结束运行并重启相机再重新打开。",
                onDismissRequest = { showRebootDialog = false },
            ) {
                DialogButtons(confirm = "知道了", onConfirm = { showRebootDialog = false })
            }
        }

        // 恢复默认确认
        if (showResetDialog) {
            LeftDialog(
                show = true,
                title = "恢复默认",
                text = "清除所有模式规则，全部按键回到“跟随设置页”？",
                onDismissRequest = { showResetDialog = false },
            ) {
                DialogButtons(
                    cancel = "取消",
                    onCancel = { showResetDialog = false },
                    confirm = "确定",
                    onConfirm = {
                        showResetDialog = false
                        cfg = JSONObject().apply {
                            put("enabled", true)
                            put("promFix", true)
                            put("v51", false)
                            put("shutterLongPressMs", 800)
                            put(ModeConfig.OPTION_STREET_SPECIAL, false)
                            put(ModeConfig.OPTION_STREET_SPECIAL_WITHOUT_GRIP, false)
                            put(ModeConfig.OPTION_GLOBAL_RECORD_VIDEO,
                                ModeConfig.GLOBAL_RECORD_NATIVE)
                            put(ModeConfig.OPTION_GRIP_INPUT_GUARD, false)
                            put(ModeConfig.OPTION_GLOBAL_RECORD_LONG_PRESS_MS, 800)
                            put(ModeConfig.OPTION_HALF_FOCUS_POLICY, ModeConfig.HALF_FOCUS_NATIVE)
                            put("keys", JSONObject())
                        }
                        master = true
                        mappingScheme = ModeConfig.MAPPING_PROMETHEUS
                        v51 = false
                        dev = false
                        showDetail = false
                        streetSpecial = false
                        streetWithoutGrip = false
                        halfFocusPolicy = ModeConfig.HALF_FOCUS_NATIVE
                        save()
                        ThemePreference.sync(ModeConfig.read())
                    },
                )
            }
        }

        // 使用说明（内容较长，文本区可滚动，避免信息被裁剪无法查看）
        if (showHelpDialog) {
            LeftDialog(
                show = true,
                title = "使用说明",
                onDismissRequest = { showHelpDialog = false },
            ) {
                Column {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = HELP_TEXT,
                            modifier = Modifier.padding(horizontal = 4.dp),
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                    DialogButtons(confirm = "确定", onConfirm = { showHelpDialog = false })
                }
            }
        }

        // 适配版本
        if (showVersionDialog) {
            LeftDialog(
                show = true,
                title = "适配版本",
                text = "Prometheus Cam适配版本：模块版 V5.1（内嵌 V5.0 相机）；NRV-1.3.2；NRV-1.5.0；NRV-1.6.1；Phoenix-0.3.0 及同一版本组（基于 HyperOS 4.0 6.6.000510.0）\n\n官方相机版本（HyperOS 3.0）：6.4.000370.0；6.4.000530.0\nHyperOS 4.0：6.6.000510.0\n\n6.8相机移植（小米18Pro系列相机，HyperOS 4.0）：6.8.001770.0",
                onDismissRequest = { showVersionDialog = false },
            ) {
                DialogButtons(confirm = "确定", onConfirm = { showVersionDialog = false })
            }
        }

        // 更新日志
        if (showChangelogDialog) {
            LeftDialog(
                show = true,
                title = "更新日志",
                onDismissRequest = { showChangelogDialog = false },
            ) {
                Column {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = CHANGELOG_TEXT,
                            modifier = Modifier.padding(horizontal = 4.dp),
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                    DialogButtons(confirm = "确定", onConfirm = { showChangelogDialog = false })
                }
            }
        }

        // 备份确认
        if (showBackupConfirm) {
            LeftDialog(
                show = true,
                title = "备份当前配置",
                text = "是否备份当前配置？",
                onDismissRequest = { showBackupConfirm = false },
            ) {
                DialogButtons(
                    cancel = "取消",
                    onCancel = { showBackupConfirm = false },
                    confirm = "确定",
                    onConfirm = {
                        showBackupConfirm = false
                        UiUtil.runBg {
                            val path = ModeConfig.backup()
                            UiUtil.runUi {
                                if (path != null) backupResult = path
                                else UiUtil.toast(context, "备份失败：需要 root 权限（KernelSU/Magisk）", true)
                            }
                        }
                    },
                )
            }
        }

        // 备份成功
        backupResult?.let { path ->
            LeftDialog(
                show = true,
                title = "备份成功",
                text = "当前设置已备份到：\n$path\n\n（目录：/sdcard/Download/HandleFixConfig/backups/）",
                onDismissRequest = { backupResult = null },
            ) {
                DialogButtons(confirm = "确定", onConfirm = { backupResult = null })
            }
        }
        }
    }
}

/**
 * 弹窗底部按钮行（官方 MIUI 样式）：
 * - 按钮放底部，选项做大
 * - 取消 = 灰色，确定 = 蓝色背景
 * - 点击触发振动反馈
 */
@Composable
internal fun DialogButtons(
    confirm: String? = null,
    onConfirm: (() -> Unit)? = null,
    cancel: String? = null,
    onCancel: (() -> Unit)? = null,
) {
    val view = LocalView.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        cancel?.let {
            Button(
                onClick = {
                    UiUtil.hapticClick(view)
                    onCancel?.invoke()
                },
                modifier = Modifier.weight(1f),
                minHeight = 50.dp,
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(it) }
        }
        confirm?.let {
            Button(
                onClick = {
                    UiUtil.hapticClick(view)
                    onConfirm?.invoke()
                },
                modifier = Modifier.weight(1f),
                minHeight = 50.dp,
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(it) }
        }
    }
}

/** 跳转系统「摄影手柄」功能设置页（com.android.settings 的 CameraGripDetail，与安全中心弹窗「去设置」同一入口） */
private fun openSystemGripSettings(context: Context) {
    try {
        context.startActivity(
            Intent().apply {
                setPackage("com.android.settings")
                setClassName("com.android.settings", "com.android.settings.SubSettings")
                putExtra(":android:show_fragment", "com.android.settings.cameragrip.CameraGripDetail")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    } catch (t: Throwable) {
        UiUtil.toast(context, "无法打开摄影手柄设置页", true)
    }
}

/** 读取当前安装相机的精确 versionCode；读取失败时返回 null，所有能力门均应保持关闭。 */
private fun readInstalledCameraVersionCode(context: Context): Long? {
    return try {
        context.packageManager.getPackageInfo("com.android.camera", 0).longVersionCode
    } catch (t: Throwable) {
        null
    }
}

/** 检测摄影手柄（Xiaomi N1G）是否已作为输入设备连接 */
private fun isGripConnected(context: Context): Boolean {
    return try {
        val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        im.getInputDeviceIds().any { id ->
            val dev: InputDevice? = im.getInputDevice(id)
            dev?.name?.contains("N1G") == true
        }
    } catch (t: Throwable) {
        false
    }
}
