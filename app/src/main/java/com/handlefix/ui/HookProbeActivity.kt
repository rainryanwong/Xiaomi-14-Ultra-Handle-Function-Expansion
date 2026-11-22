package com.handlefix.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.handlefix.ModeConfig
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v11.2 模块诊断（折叠式）：
 * 默认只显示结论，点「展开详情」看完整诊断；找不到环境记录时直接诊断 root/目录/相机包信息。
 */
@Composable
internal fun HookProbeContent() {
    val context = LocalContext.current
    var result by remember { mutableStateOf("正在检测…") }
    var expanded by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    fun probe() {
        result = "正在检测…"
        UiUtil.runBg {
            val r = doProbe(context)
            UiUtil.runUi { result = r }
        }
    }

    LaunchedEffect(Unit) { probe() }

    val found = result.contains("【环境记录】已找到 ✓")

    Column(Modifier.fillMaxSize()) {
        Text(
            text = "开始检测前，请先启动一次相机",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Card(Modifier.padding(12.dp)) {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (result == "正在检测…") result else if (found) "✓ 已找到模块环境记录" else "✗ 未找到环境记录",
                        modifier = Modifier.weight(1f),
                        color = if (found) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                    TextButton(
                        text = if (expanded) "收起" else "展开详情",
                        onClick = { expanded = !expanded },
                    )
                }
                if (expanded) {
                    val busy = result == "正在检测…"
                    if (!busy) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(text = if (exporting) "正在导出…" else "导出日志", onClick = {
                                if (!exporting) {
                                    exporting = true
                                    exportLog(context) { exporting = false }
                                }
                            })
                            TextButton(text = "重新检测", onClick = { probe() })
                        }
                    }
                    Text(
                        text = result,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private fun doProbe(context: Context): String {
    val out = StringBuilder()

    // 1) 找环境记录文件：逐个直接 cat（能读出内容即命中），找不到再 find 兜底
    val envPaths = arrayOf(
        "/sdcard/Android/data/com.android.camera/files/handlefix_env.xml",
        "/data/user_de/0/com.android.camera/shared_prefs/handlefix_env.xml",
        "/data/user/0/com.android.camera/shared_prefs/handlefix_env.xml",
        "/data/data/com.android.camera/shared_prefs/handlefix_env.xml",
    )
    var xml = ""
    for (ep in envPaths) {
        val c = UiUtil.suOut("cat $ep 2>/dev/null").trim()
        if (c.length > 20) {
            xml = c
            break
        }
    }
    if (xml.isEmpty()) {
        val found = UiUtil.suOut("find /data -name handlefix_env.xml 2>/dev/null | head -1").trim()
        if (found.isNotEmpty()) {
            xml = UiUtil.suOut("cat $found")
        }
    }

    if (xml.trim().isNotEmpty()) {
        out.append("【环境记录】已找到 ✓\n\n")
        out.append("【写入时间】").append(tsOf(xml)).append("\n\n")
        val env = strOf(xml, "env")
        if (env != null && env != "-" && env.length > 5) {
            // 新版：env 单字段包含全部信息（含 ok/fail/probe）
            out.append("【加载信息】\n").append(env).append("\n\n")
            val okM = Regex("ok=(\\d+)").find(env)
            val failM = Regex("fail=(\\d+)").find(env)
            out.append("【Hook 汇总】\nok=").append(okM?.groupValues?.get(1) ?: "?")
                .append("  fail=").append(failM?.groupValues?.get(1) ?: "?").append("\n")
        } else {
            // 旧版：分字段
            out.append("【加载信息】\n").append(strOf(xml, "load")).append("\n\n")
            out.append("【Hook 汇总】\nok=").append(intOf(xml, "hook_ok"))
                .append("  fail=").append(intOf(xml, "hook_fail")).append("\n\n")
            val probe = strOf(xml, "probe")
            if (probe != null && probe.isNotEmpty()) {
                out.append("【版本探测】\n").append(probe).append("\n")
            }
        }
    } else {
        out.append("【环境记录】未找到 ✗\n\n")
        out.append("下面是逐步诊断，帮你定位原因：\n\n")

        // 3-1) Root 检查
        val id = UiUtil.suOut("id 2>/dev/null").trim()
        out.append("【Root 检查】")
            .append(if (id.contains("uid=0")) "正常（uid=0）" else "未获取到 root（id 输出：${id.ifEmpty { "空" }}）")
            .append("\n\n")

        // 3-2) 三个候选目录逐一列目录
        val dirs = arrayOf(
            "/data/user/0/com.android.camera/shared_prefs",
            "/data/user_de/0/com.android.camera/shared_prefs",
            "/data/data/com.android.camera/shared_prefs",
        )
        for (d in dirs) {
            val ls = UiUtil.suOut("ls -la $d 2>/dev/null").trim()
            if (ls.isNotEmpty()) {
                out.append("【目录】$d\n").append(ls.take(600)).append("\n\n")
            } else {
                out.append("【目录】$d （不存在或无权限）\n\n")
            }
        }

        // 3-3) find 兜底
        val foundFiles = UiUtil.suOut("find /data -name '*handlefix*' 2>/dev/null | head -10").trim()
        out.append("【handlefix 相关文件】")
            .append(if (foundFiles.isEmpty()) "未找到" else foundFiles.replace("\n", " | "))
            .append("\n\n")

        // 3-4) 相机包信息（版本/安装时间/路径，判断新旧构建）
        val pkg = UiUtil.suOut(
            "dumpsys package com.android.camera 2>/dev/null | grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime|codePath=' | head -10"
        ).trim()
        if (pkg.isNotEmpty()) {
            out.append("【相机包信息】\n").append(pkg).append("\n\n")
        }
        out.append("提示：模块更新后，需要先打开一次相机再回来重新检测。\n")
        out.append("如果上面 Root 正常、目录里也有 handlefix_env.xml，说明是读取问题，把这份详情发给我。\n\n")
    }

    // 附加信息（与模块整体排查相关）
    out.append("\n【模块版本】").append(moduleVersion(context)).append("\n")
    out.append("【Root 管理器】").append(rootManagerInfo()).append("\n")
    out.append("【手柄连接】").append(handleStatus()).append("\n")
    return out.toString().trim()
}

private fun exportLog(context: Context, onFinished: () -> Unit) {
    val app = context.applicationContext
    UiUtil.runBg {
        val message = try {
            val report = "===== 环境检查 =====\n" + envRowsText(app) +
                "\n\n===== 模块诊断 =====\n" + doProbe(app)
            val name = "handlefix-report-" +
                SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".txt"
            saveDiagnosticReport(app, name, report)
            "已导出：Download/HandleFixConfig/Reports/$name"
        } catch (failure: Exception) {
            android.util.Log.e("HandleFix", "Diagnostic report export failed", failure)
            "导出失败：" + (failure.localizedMessage ?: failure.javaClass.simpleName)
        }
        UiUtil.runUi {
            onFinished()
            UiUtil.toast(app, message, true)
        }
    }
}

private fun moduleVersion(context: Context): String {
    return try {
        val pi = context.packageManager.getPackageInfo("com.handlefix.all", 0)
        "${pi.versionName} (${pi.versionCode})"
    } catch (t: Throwable) {
        "-"
    }
}

private fun strOf(xml: String, key: String): String {
    val p = Regex("<string name=\"$key\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
    return p.find(xml)?.groupValues?.get(1) ?: "-"
}

private fun intOf(xml: String, key: String): String {
    val p = Regex("<int name=\"$key\" value=\"(.*?)\"")
    return p.find(xml)?.groupValues?.get(1) ?: "-"
}

private fun tsOf(xml: String): String {
    val p = Regex("<long name=\"ts\" value=\"(.*?)\"")
    val m = p.find(xml)?.groupValues?.get(1) ?: return "-"
    return try {
        val v = m.toLong()
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(v))
    } catch (t: Throwable) {
        m
    }
}
