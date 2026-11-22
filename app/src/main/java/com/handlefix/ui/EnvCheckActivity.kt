package com.handlefix.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.handlefix.CameraBuilds
import com.handlefix.ModeConfig
import kotlinx.coroutines.delay
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** v10.18 环境检查页（手柄状态实时刷新 + 刷新按钮 + 详细 OS 版本） */
class EnvCheckActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XiaomiEdgeToEdge()
            HandleFixTheme {
                ModuleDiagnosticsScreen { finish() }
            }
        }
    }
}

internal fun envRows(context: Context): List<Pair<String, String>> = buildList {
    add("系统版本" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    add("设备" to "${Build.MANUFACTURER} ${Build.MODEL}")

    var cameraVersionCode = CameraBuilds.UNKNOWN_VERSION_CODE
    val camVer = try {
        val pi = context.packageManager.getPackageInfo("com.android.camera", 0)
        cameraVersionCode = pi.longVersionCode
        "${pi.versionName} (${pi.versionCode})"
    } catch (t: Throwable) {
        "未安装/无法读取"
    }
    add("相机版本" to camVer)

    val modVer = try {
        val pi = context.packageManager.getPackageInfo("com.handlefix.all", 0)
        "${pi.versionName} (${pi.versionCode})"
    } catch (t: Throwable) {
        "读取失败"
    }
    add("模块版本" to modVer)

    add("Root 权限" to if (ModeConfig.hasRoot()) "已获取 ✓" else "未获取 ✗")
    add("澎湃OS版本" to rootProp())
    add("内核版本" to kernelVer())
    add("Root管理器" to rootManagerInfo())

    val cfg: JSONObject? = ModeConfig.read()
    if (cfg != null) {
        add("模块总开关" to (if (cfg.optBoolean("enabled", true)) "开" else "关"))
        add("手柄按键映射方案" to when (ModeConfig.readMappingScheme(cfg)) {
            ModeConfig.MAPPING_CAMERA_68 -> "6.8相机移植"
            ModeConfig.MAPPING_OFFICIAL -> "官方相机"
            else -> "Prometheus Cam"
        })
        // 与主页相同的精确显示门：其它版本不展示该开关/状态，但不触碰已保存的 v51 值。
        if (cameraVersionCode == CameraBuilds.PROMETHEUS_MODULE_V51_VERSION_CODE) {
            add("旧版 Prometheus Cam 手柄适配" to (if (cfg.optBoolean("v51", false))
                "已启用（模块版 V5.1 内嵌 V5.0 相机的基础功能修复）"
            else "已关闭（该版本组通常已内置基础功能修复）"))
        }
        add("启用街拍特别款" to (if (cfg.optBoolean(ModeConfig.OPTION_STREET_SPECIAL, false)) "已启用（仅 Phoenix-0.3.0 及以上）" else "已关闭（默认）"))
        add("任意界面长按录像键" to when (ModeConfig.readGlobalRecordVideoPolicy(cfg)) {
            ModeConfig.GLOBAL_RECORD_OPEN -> "仅跳转录像界面（${ModeConfig.readGlobalRecordLongPressMs(cfg)}ms）"
            ModeConfig.GLOBAL_RECORD_START -> "跳转并启动录制（${ModeConfig.readGlobalRecordLongPressMs(cfg)}ms）"
            else -> "关闭（默认）"
        })
        add("半按对焦策略" to when (cfg.optString(ModeConfig.OPTION_HALF_FOCUS_POLICY)) {
            ModeConfig.HALF_FOCUS_DISABLE -> "禁用手柄对焦"
            ModeConfig.HALF_FOCUS_CENTER -> "单次中心对焦"
            else -> "摄影手柄原生半按对焦"
        })
        add("开发者选项" to (if (cfg.optBoolean("devMode", false)) "开" else "关"))
    } else {
        add("模块总开关" to "开（默认）")
        add("Prometheus Cam 手柄映射" to "已启用（默认）")
        add("旧版 Prometheus Cam 手柄适配" to "已关闭（默认）")
        add("启用街拍特别款" to "已关闭（默认）")
        add("任意界面长按录像键" to "关闭（默认）")
        add("半按对焦策略" to "摄影手柄原生半按对焦")
        add("开发者选项" to "关（默认）")
    }

    val paths = arrayOf(
        "/data/local/tmp/handlefix-mode.conf",
        "/data/system/handlefix/handlefix-mode.conf",
        "/sdcard/Download/HandleFixConfig/.config/handlefix-mode.conf",
        "/sdcard/Download/HandleFixConfig/handlefix-mode.conf",
        "/sdcard/Download/handlefix-mode.conf",
    )
    add("配置文件" to paths.joinToString("\n") { "$it ${if (UiUtil.suExec("ls $it")) "✓" else "✗"}" })
}

/** 导出用：环境检查纯文本 */
internal fun envRowsText(context: Context): String {
    val sb = StringBuilder()
    for ((k, v) in envRows(context)) {
        sb.append(k).append("：").append(v.replace("\n", " | ")).append("\n")
    }
    sb.append("手柄连接：").append(handleStatus()).append("\n")
    return sb.toString().trim()
}

@Composable
internal fun EnvCheckContent() {
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    var handleInfo by remember { mutableStateOf("检测中…") }

    // 手柄连接状态：每 2 秒自动刷新
    LaunchedEffect(refreshTick) {
        while (true) {
            handleInfo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { handleStatus() }
            delay(2000)
        }
    }

    var rows by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    LaunchedEffect(refreshTick) {
        rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { envRows(context) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card {
                // 参考官方「全部参数与信息」：标题在上、内容在下
                rows.forEach { (label, value) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(
                            text = label,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                        SelectionContainer {
                            Text(
                                text = value.ifEmpty { "-" },
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                // 手柄连接：标签行带刷新按钮，内容独立一行（避免长文本挤掉按钮）
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "手柄连接",
                            modifier = Modifier.weight(1f),
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                        TextButton(text = "刷新", onClick = { refreshTick++ })
                    }
                    SelectionContainer {
                        Text(
                            text = handleInfo.ifEmpty { "-" },
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

// ============ v10.16/10.18 环境信息工具 ============

/** 读取澎湃OS版本号：取多个属性中最详细的值 */
internal fun rootProp(): String {
    val name = UiUtil.suOut("getprop ro.mi.os.version.name 2>/dev/null").trim()
    val inc = UiUtil.suOut("getprop ro.build.version.incremental 2>/dev/null").trim()
        .ifEmpty { UiUtil.suOut("getprop ro.mi.os.version.incremental 2>/dev/null").trim() }
    val base = if (inc.length > name.length) inc else name
    val v = when {
        base.startsWith("OS") -> base
        name.startsWith("OS") && base.isNotEmpty() -> "OS$base"
        else -> base
    }
    return v.ifEmpty { "-" }
}

/** 内核版本 */
internal fun kernelVer(): String {
    val v = UiUtil.suOut("uname -r 2>/dev/null").trim()
    return v.ifEmpty { "-" }
}

/** 检测摄影手柄是否插入，并显示设备名（root dumpsys 优先，应用 API 兜底） */
internal fun handleStatus(): String {
    val out = UiUtil.suOut("dumpsys input 2>/dev/null | grep -a 'N1G'").trim()
    val names = out.lineSequence().mapNotNull { line ->
        val idx = line.lastIndexOf(':')
        if (idx >= 0) line.substring(idx + 1).trim().ifEmpty { null } else null
    }.filter { it.contains("N1G", true) }.distinct().toList()
    if (names.isNotEmpty()) return "已连接 ✓（${names.joinToString("、")}）"
    return try {
        val found = InputDevice.getDeviceIds().map { InputDevice.getDevice(it)?.name }
            .filterNotNull().filter { it.contains("N1G", ignoreCase = true) }
        if (found.isNotEmpty()) "已连接 ✓（${found.joinToString("、")}）" else "未连接 ✗"
    } catch (t: Throwable) {
        "未连接 ✗"
    }
}

/** 检测 Root 管理器类型/版本（Magisk / KernelSU / APatch） */
internal fun rootManagerInfo(): String {
    val branches = listOf(
        "/data/adb/magisk" to "Magisk",
        "/data/adb/ksu" to "KernelSU",
        "/data/adb/apd" to "APatch",
    )
    for ((dir, name) in branches) {
        if (UiUtil.suExec("ls $dir")) {
            val v = UiUtil.suOut("su -v 2>/dev/null").trim()
            return if (v.isNotEmpty()) {
                if (v.contains(name, ignoreCase = true)) v else "$name $v"
            } else name
        }
    }
    val sv = UiUtil.suOut("su -v 2>/dev/null").trim()
    if (sv.isNotEmpty()) {
        return when {
            sv.contains("KernelSU", true) -> sv
            sv.contains("APatch", true) -> sv
            sv.contains("Magisk", true) -> sv
            else -> "su: $sv"
        }
    }
    return "未检测到"
}
