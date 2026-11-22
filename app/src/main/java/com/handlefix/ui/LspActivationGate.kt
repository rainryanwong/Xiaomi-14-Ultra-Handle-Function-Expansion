package com.handlefix.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.handlefix.activation.LspActivationMonitor
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text

/** Startup-only gate. Once admitted, never unmount MainScreen on secondary-page returns:
 * doing so destroys pager/scroll state and shows a white CHECKING frame on every onResume.
 * The waiting branch owns its Scaffold: OverlayDialog cannot render outside a popup host.
 * UNKNOWN is not "disabled"; allow settings access without inventing successful activation.
 */
@Composable
internal fun LspActivationGate(activity: ComponentActivity, content: @Composable () -> Unit) {
    val status by LspActivationMonitor.state.collectAsState()
    var retry by remember { mutableStateOf(0) }
    var admitted by remember { mutableStateOf(status == LspActivationMonitor.Status.ACTIVE) }
    var foreground by remember {
        mutableStateOf(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(retry, foreground) {
        if (foreground && !admitted) {
            LspActivationMonitor.check(activity.applicationContext)
            if (LspActivationMonitor.state.value == LspActivationMonitor.Status.ACTIVE) admitted = true
        }
    }
    if (admitted) {
        content()
    } else {
        BackHandler(enabled = foreground) { activity.finishAffinity() }
        Scaffold { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(if (status == LspActivationMonitor.Status.CHECKING)
                    "正在检测 LSPosed 启用状态…" else "未确认当前版本已加载")
            }
            LeftDialog(
                show = foreground && status == LspActivationMonitor.Status.UNKNOWN,
                title = "未确认模块启用",
                text = "未收到当前版本的实时加载响应。请检查 LSPosed 是否启用本模块及相机/系统作用域；更新模块后可能需要重启手机。\n\n也可能是检测通道不可用，这不等于模块一定未启用。可以继续进入设置，但仍需通过模块诊断核验实际功能。",
                onDismissRequest = {},
            ) {
                DialogButtons(
                    cancel = "继续进入设置", onCancel = { admitted = true },
                    confirm = "重新检测", onConfirm = { retry++ },
                )
            }
        }
    }
}
