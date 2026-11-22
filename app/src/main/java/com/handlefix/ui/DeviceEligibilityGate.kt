package com.handlefix.ui

import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.Scaffold

internal object DeviceEligibility {
    // Match aliases independently: ported ROMs can replace one or two product properties.
    // Normalize spaces/case, but never infer eligibility just from Xiaomi as manufacturer.
    fun matches(vararg identifiers: String): Boolean = identifiers.any { raw ->
        val value = raw.lowercase(java.util.Locale.ROOT).replace(Regex("[\\s_-]"), "")
        value == "aurora" || value == "24031pn0dc" ||
            value == "xiaomi14ultra" || value == "小米14ultra"
    }
}

@Composable
internal fun DeviceEligibilityGate(activity: Activity, content: @Composable () -> Unit) {
    val supported = remember {
        DeviceEligibility.matches(Build.MODEL, Build.DEVICE, Build.PRODUCT)
    }
    if (supported) content() else {
        BackHandler { activity.finishAffinity() }
        // OverlayDialog registers with Scaffold's popup host; a sibling dialog is invisible.
        Scaffold {
            Box(Modifier.fillMaxSize())
            LeftDialog(
                show = true,
                title = "机型不支持",
                text = "检测到当前机型可能非：Xiaomi 14 Ultra，无法使用本模块。",
                onDismissRequest = {},
            ) {
                DialogButtons(confirm = "确认", onConfirm = { activity.finishAffinity() })
            }
        }
    }
}
