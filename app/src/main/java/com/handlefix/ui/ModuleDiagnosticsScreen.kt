package com.handlefix.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.handlefix.ModeConfig
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** Shared controls belong to the screen, not either tab; changing tabs cannot reset them. */
@Composable
internal fun ModuleDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var dev by rememberSaveable { mutableStateOf(ModeConfig.isDevMode()) }
    var detail by rememberSaveable { mutableStateOf(ModeConfig.isShowDetail()) }
    var confirmDev by rememberSaveable { mutableStateOf(false) }

    fun save(key: String, value: Boolean) {
        // Read the latest configuration so diagnostics cannot overwrite another page's settings.
        val config = ModeConfig.read() ?: JSONObject()
        config.put(key, value)
        if (ModeConfig.writeViaRoot(config)) {
            if (key == "devMode") dev = value else detail = value
        } else UiUtil.toast(context, "保存失败：需要 root 权限")
    }

    Scaffold(topBar = {
        SmallTopAppBar(title = "模块诊断", navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(MiuixIcons.Back, contentDescription = "返回")
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                tabs = listOf("环境检查", "模块诊断"),
                selectedTabIndex = tab,
                onTabSelected = { tab = it },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Card(Modifier.padding(12.dp)) {
                SwitchPreference(
                    title = "开发者选项",
                    summary = "启用后忽略过滤表规则，可配置所有功能模式和按键选项（不建议开启）",
                    checked = dev,
                    onCheckedChange = { if (it) confirmDev = true else save("devMode", false) },
                )
                SwitchPreference(
                    title = "显示模式编号",
                    summary = "启用后显示模式编号、功能编号和首页按键码",
                    checked = detail,
                    onCheckedChange = { save("showDetail", it) },
                )
            }
            Box(Modifier.weight(1f)) {
                if (tab == 0) EnvCheckContent() else HookProbeContent()
            }
        }
        // Keep the confirmation inside Scaffold's popup host, outside the changing tab content.
        if (confirmDev) {
            LeftDialog(
                show = true,
                title = "开发者选项",
                text = "启用后忽略过滤表规则，可配置所有功能模式和按键选项（不建议开启）。\n受限于相机自身模式及功能限制，部分按键映射选项不可用，绝大部分情况并非模块BUG所致，请知悉。",
                onDismissRequest = { confirmDev = false },
            ) {
                DialogButtons(
                    cancel = "取消", onCancel = { confirmDev = false },
                    confirm = "确定", onConfirm = { confirmDev = false; save("devMode", true) },
                )
            }
        }
    }
}
