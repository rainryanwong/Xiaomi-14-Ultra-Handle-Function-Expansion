package com.handlefix.ui

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * 小米/HyperOS 沉浸式适配（参考 miuix 官方示例 + 小米文档）：
 * - 状态栏/导航栏（手势提示线）透明，内容延伸到底部，手势线随页面背景色
 * - isNavigationBarContrastEnforced=false 是 MIUI 上必须的一步
 * - 刘海/挖孔由 Scaffold 默认 WindowInsets.displayCutout 自动避让
 */
@Composable
internal fun XiaomiEdgeToEdge() {
    val context = LocalContext.current
    val dark = useDarkTheme()
    DisposableEffect(dark) {
        val activity = context as? ComponentActivity
        if (activity != null) {
            activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activity.window.isNavigationBarContrastEnforced = false
            }
        }
        onDispose { }
    }
}
