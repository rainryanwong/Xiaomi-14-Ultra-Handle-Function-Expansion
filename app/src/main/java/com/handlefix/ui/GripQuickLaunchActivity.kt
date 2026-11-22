package com.handlefix.ui

import kotlin.math.roundToInt
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.handlefix.ModeConfig
import com.handlefix.Camera68LaunchTargets
import com.handlefix.activation.ActivationProbeProtocol
import com.handlefix.activation.LspActivationMonitor
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 相机外摄影手柄快捷启动；相机前台按键语义仍由四按键配置页管理。 */
class GripQuickLaunchActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XiaomiEdgeToEdge()
            HandleFixTheme {
                GripQuickLaunchScreen(resumeTick) { finish() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}

/**
 * 与四按键配置页默认显示的十个主模式保持一致。街拍额外提供“跟随相机”与
 * 三个精确路由；旧“普通拍照”能力仍使用 capture 配置值，但界面统一显示为“拍照”。
 */
private val quickTargets = listOf(
    ModeConfig.GRIP_QUICK_NATIVE to "保持原生（不响应）",
    ModeConfig.GRIP_QUICK_LEGEND to "徕卡一瞬",
    ModeConfig.GRIP_QUICK_STREET_FOLLOW to "街拍模式（跟随相机）",
    ModeConfig.GRIP_QUICK_STREET_225 to "普通街拍（强制）",
    ModeConfig.GRIP_QUICK_STREET_229 to "街拍特别款（强制）",
    ModeConfig.GRIP_QUICK_STREET_229_LEGEND to "街拍传奇款（强制）",
    ModeConfig.GRIP_QUICK_CAPTURE to "拍照",
    ModeConfig.GRIP_QUICK_VIDEO to "录像",
    ModeConfig.GRIP_QUICK_PRO_PHOTO to "专业拍照",
    ModeConfig.GRIP_QUICK_PRO_VIDEO to "专业录像",
    ModeConfig.GRIP_QUICK_NIGHT to "夜景",
    ModeConfig.GRIP_QUICK_PORTRAIT to "人像",
    ModeConfig.GRIP_QUICK_DOCUMENT to "文档",
    ModeConfig.GRIP_QUICK_ULTRA_PIXEL to "超清",
)

@Composable
private fun GripQuickLaunchScreen(resumeTick: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var cfg by remember { mutableStateOf(ModeConfig.read()) }
    val camera68 = ModeConfig.readMappingScheme(cfg) == ModeConfig.MAPPING_CAMERA_68
    var streetReady by remember(resumeTick, camera68) { mutableStateOf(false) }
    LaunchedEffect(resumeTick, camera68) {
        if (camera68) streetReady = LspActivationMonitor.cameraCapability(
            context, ActivationProbeProtocol.CAMERA68_STREET_READY,
        ) == true
    }
    val availableTargets = if (camera68) {
        quickTargets.filter { Camera68LaunchTargets.supportsTarget(it.first) }.mapNotNull { target ->
            when (target.first) {
                ModeConfig.GRIP_QUICK_STREET_229 ->
                    if (streetReady) target.first to "经典街拍（强制）" else null
                ModeConfig.GRIP_QUICK_STREET_229_LEGEND ->
                    if (streetReady) target.first to "传奇街拍（强制）" else null
                else -> target
            }
        }
    } else {
        quickTargets
    }
    var recordPolicy by remember { mutableStateOf(ModeConfig.readGlobalRecordVideoPolicy(cfg)) }
    var recordThreshold by remember {
        mutableIntStateOf(ModeConfig.readGlobalRecordLongPressMs(cfg))
    }
    var zoomIn by remember { mutableStateOf(ModeConfig.readGripQuickTarget(cfg, true)) }
    var zoomOut by remember { mutableStateOf(ModeConfig.readGripQuickTarget(cfg, false)) }
    var zoomThreshold by remember {
        mutableIntStateOf(ModeConfig.readGripQuickLongPressMs(cfg))
    }
    var haptic by remember {
        mutableStateOf(cfg?.optBoolean(ModeConfig.OPTION_GRIP_QUICK_HAPTIC, true) ?: true)
    }
    fun save(change: JSONObject.() -> Unit) {
        val updated = JSONObject((cfg ?: JSONObject()).toString()).apply(change)
        if (ModeConfig.writeViaRoot(updated)) {
            cfg = updated
            recordPolicy = ModeConfig.readGlobalRecordVideoPolicy(updated)
            recordThreshold = ModeConfig.readGlobalRecordLongPressMs(updated)
            zoomIn = ModeConfig.readGripQuickTarget(updated, true)
            zoomOut = ModeConfig.readGripQuickTarget(updated, false)
            zoomThreshold = ModeConfig.readGripQuickLongPressMs(updated)
            haptic = updated.optBoolean(ModeConfig.OPTION_GRIP_QUICK_HAPTIC, true)
            UiUtil.toast(context, "已保存")
        } else {
            UiUtil.toast(context, "保存失败：需要 root 权限", true)
        }
        UiUtil.hapticClick(view)
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "相机模式快捷跳转",
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
            TabRow(
                tabs = listOf("长按录像按键", "长拨变焦拨杆"),
                selectedTabIndex = tab,
                onTabSelected = { tab = it },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            val listState = rememberLazyListState()
            LazyListEdgeHaptic(listState, view)
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (tab == 0) {
                    item {
                        SmallTitle(
                            text = "该功能仅在相机非前台时响应，当相机在前台时，则优先调用当前模式的按键映射配置",
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    item {
                        Card {
                            OverlayDropdownPreference(
                                items = listOf(
                                    "关闭（默认）",
                                    "仅跳转录像界面",
                                    "跳转并启动录制",
                                ),
                                selectedIndex = when (recordPolicy) {
                                    ModeConfig.GLOBAL_RECORD_OPEN -> 1
                                    ModeConfig.GLOBAL_RECORD_START -> 2
                                    else -> 0
                                },
                                title = "任意界面长按录像键",
                                summary = when (recordPolicy) {
                                    ModeConfig.GLOBAL_RECORD_OPEN -> "长按进入录像界面"
                                    ModeConfig.GLOBAL_RECORD_START -> "长按进入录像界面并录制"
                                    else -> "保持系统原生行为"
                                },
                                onSelectedIndexChange = { index ->
                                    save {
                                        put(
                                            ModeConfig.OPTION_GLOBAL_RECORD_VIDEO,
                                            when (index) {
                                                1 -> ModeConfig.GLOBAL_RECORD_OPEN
                                                2 -> ModeConfig.GLOBAL_RECORD_START
                                                else -> ModeConfig.GLOBAL_RECORD_NATIVE
                                            },
                                        )
                                    }
                                },
                            )
                            // 阈值始终可见：用户可先调整手感，再决定是否启用快捷录像。
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(
                                    text = "录像键长按响应时间 · ${recordThreshold}ms",
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Slider(
                                    value = recordThreshold.toFloat(),
                                    onValueChange = {
                                        recordThreshold = (it / 100f).roundToInt() * 100
                                    },
                                    onValueChangeFinished = {
                                        save {
                                            put(
                                                ModeConfig.OPTION_GLOBAL_RECORD_LONG_PRESS_MS,
                                                recordThreshold,
                                            )
                                        }
                                    },
                                    valueRange = 500f..1000f,
                                    steps = 4,
                                    showKeyPoints = true,
                                    keyPoints = (500..1000 step 100).map { it.toFloat() },
                                    hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                                )
                            }
                        }
                    }
                } else {
                    item {
                        SmallTitle(
                            text = "该功能仅在相机非前台时响应，当相机在前台时，则优先调用当前模式的按键映射配置",
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    item {
                        Card {
                            QuickTargetPreference(
                                title = "向放大方向长拨（右）",
                                target = zoomIn,
                                targets = availableTargets,
                                onChange = { value ->
                                    save { put(ModeConfig.OPTION_GRIP_QUICK_ZOOM_IN, value) }
                                },
                            )
                            QuickTargetPreference(
                                title = "向缩小方向长拨（左）",
                                target = zoomOut,
                                targets = availableTargets,
                                onChange = { value ->
                                    save { put(ModeConfig.OPTION_GRIP_QUICK_ZOOM_OUT, value) }
                                },
                            )
                        }
                    }
                    item {
                        Card {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(
                                    text = "长拨响应时间 · ${zoomThreshold}ms",
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Slider(
                                    value = zoomThreshold.toFloat(),
                                    onValueChange = {
                                        zoomThreshold = (it / 100f).roundToInt() * 100
                                    },
                                    onValueChangeFinished = {
                                    save {
                                        put(
                                            ModeConfig.OPTION_GRIP_QUICK_LONG_PRESS_MS,
                                            zoomThreshold,
                                        )
                                    }
                                    },
                                    valueRange = 700f..1000f,
                                    steps = 2,
                                    showKeyPoints = true,
                                    keyPoints = (700..1000 step 100).map { it.toFloat() },
                                    hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                                )
                            }
                            SwitchPreference(
                                title = "响应振动提示",
                                summary = "仅在相机成功响应操作时提示",
                                checked = haptic,
                                onCheckedChange = { enabled ->
                                    save { put(ModeConfig.OPTION_GRIP_QUICK_HAPTIC, enabled) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickTargetPreference(
    title: String,
    target: String,
    targets: List<Pair<String, String>>,
    onChange: (String) -> Unit,
) {
    val selected = targets.indexOfFirst { it.first == target }.coerceAtLeast(0)
    OverlayDropdownPreference(
        items = targets.map { it.second },
        selectedIndex = selected,
        title = title,
        summary = if (target == targets[selected].first) targets[selected].second
            else "已保存的目标暂不可用",
        onSelectedIndexChange = { onChange(targets[it].first) },
    )
}
