package com.handlefix.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.handlefix.ModeConfig
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 锁屏/息屏输入保护的独立设置页；仅调整配置，不改变已验证的系统层拦截链。 */
class GripInputGuardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XiaomiEdgeToEdge()
            HandleFixTheme {
                GripInputGuardScreen { finish() }
            }
        }
    }
}

@Composable
private fun GripInputGuardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    var cfg by remember { mutableStateOf(ModeConfig.read()) }
    var enabled by remember {
        mutableStateOf(cfg?.optBoolean(ModeConfig.OPTION_GRIP_INPUT_GUARD, false) ?: false)
    }
    var scope by remember { mutableStateOf(ModeConfig.readGripInputGuardScope(cfg)) }
    var mask by remember { mutableStateOf(ModeConfig.readGripInputGuardMask(cfg)) }

    fun save(nextEnabled: Boolean, nextScope: String, nextMask: Int) {
        val updated = JSONObject((cfg ?: JSONObject()).toString()).apply {
            put(ModeConfig.OPTION_GRIP_INPUT_GUARD, nextEnabled)
            put(ModeConfig.OPTION_GRIP_INPUT_GUARD_SCOPE, nextScope)
            put(ModeConfig.OPTION_GRIP_INPUT_GUARD_MASK, nextMask and ModeConfig.GRIP_INPUT_ALL)
        }
        if (ModeConfig.writeViaRoot(updated)) {
            cfg = updated
            enabled = nextEnabled
            scope = nextScope
            mask = nextMask and ModeConfig.GRIP_INPUT_ALL
            UiUtil.toast(context, "已保存")
        } else {
            UiUtil.toast(context, "保存失败：需要 root 权限", true)
        }
        UiUtil.hapticClick(view)
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "锁屏/息屏禁用手柄按键",
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
        val listState = rememberLazyListState()
        LazyListEdgeHaptic(listState, view)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card {
                    SwitchPreference(
                        title = "启用锁屏/息屏输入保护",
                        summary = "按所选场景和按键类型忽略手柄输入；不影响连接与充电",
                        checked = enabled,
                        onCheckedChange = { save(it, scope, mask) },
                    )
                }
            }
            if (enabled) {
                item {
                    SmallTitle(
                        text = "生效场景",
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                item {
                    Card {
                        OverlayDropdownPreference(
                            items = listOf(
                                "锁屏和息屏均禁用",
                                "仅锁屏禁用",
                                "仅息屏禁用",
                            ),
                            selectedIndex = when (scope) {
                                ModeConfig.GRIP_GUARD_SCOPE_KEYGUARD -> 1
                                ModeConfig.GRIP_GUARD_SCOPE_SCREEN_OFF -> 2
                                else -> 0
                            },
                            title = "禁用场景",
                            summary = when (scope) {
                                ModeConfig.GRIP_GUARD_SCOPE_KEYGUARD ->
                                    "仅亮屏但尚未解锁时拦截"
                                ModeConfig.GRIP_GUARD_SCOPE_SCREEN_OFF ->
                                    "仅屏幕处于非交互状态时拦截"
                                else -> "锁屏界面和息屏状态都拦截"
                            },
                            onSelectedIndexChange = { index ->
                                save(
                                    true,
                                    when (index) {
                                        1 -> ModeConfig.GRIP_GUARD_SCOPE_KEYGUARD
                                        2 -> ModeConfig.GRIP_GUARD_SCOPE_SCREEN_OFF
                                        else -> ModeConfig.GRIP_GUARD_SCOPE_BOTH
                                    },
                                    mask,
                                )
                            },
                        )
                    }
                }
                item {
                    SmallTitle(
                        text = "屏蔽按键",
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                item {
                    Card {
                        val allInputs = mask == ModeConfig.GRIP_INPUT_ALL
                        SwitchPreference(
                            title = "全按键不响应",
                            summary = "包含快门、变焦拨杆、拨轮调节和录像按键",
                            checked = allInputs,
                            onCheckedChange = { checked ->
                                save(
                                    true,
                                    scope,
                                    if (checked) ModeConfig.GRIP_INPUT_ALL else 0,
                                )
                            },
                        )
                        listOf(
                            Triple("快门按键不响应", ModeConfig.GRIP_INPUT_SHUTTER,
                                "包含半按和完整按下"),
                            Triple("变焦拨杆不响应", ModeConfig.GRIP_INPUT_ZOOM,
                                "屏蔽两个方向的拨杆输入"),
                            Triple("拨轮调节不响应", ModeConfig.GRIP_INPUT_WHEEL,
                                "屏蔽拨轮刻度及其唤醒运动"),
                            Triple("录像按键不响应", ModeConfig.GRIP_INPUT_RECORD,
                                "屏蔽录像键短按和长按"),
                        ).forEach { (title, bit, detail) ->
                            SwitchPreference(
                                title = title,
                                summary = detail,
                                checked = mask and bit != 0,
                                enabled = !allInputs,
                                onCheckedChange = { checked ->
                                    save(
                                        true,
                                        scope,
                                        if (checked) mask or bit else mask and bit.inv(),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
