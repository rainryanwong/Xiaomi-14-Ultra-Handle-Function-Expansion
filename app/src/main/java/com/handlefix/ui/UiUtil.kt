package com.handlefix.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.handlefix.ModeConfig
import org.json.JSONObject

/** UI 通用工具：Toast / 后台线程 / root 命令 / 配置统计 */
object UiUtil {

    data class CustomCounts(
        val short: Int = 0,
        val long: Int = 0,
        val deepLong: Int = 0,
    )

    fun toast(context: Context, msg: String, long: Boolean = false) {
        Toast.makeText(context, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    fun runBg(block: () -> Unit) {
        Thread(block).start()
    }

    fun runUi(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }

    /** 执行 root 命令，返回是否成功（exit 0） */
    fun suExec(cmd: String): Boolean = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        p.inputStream.read()
        p.waitFor()
        p.exitValue() == 0
    } catch (t: Throwable) {
        false
    }

    /** 执行 root 命令并完整读取输出 */
    fun suOut(cmd: String): String = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val sb = StringBuilder()
        p.inputStream.bufferedReader().use { r ->
            var line = r.readLine()
            while (line != null) {
                sb.append(line).append("\n")
                line = r.readLine()
            }
        }
        p.waitFor()
        sb.toString()
    } catch (t: Throwable) {
        ""
    }

    /** 统计某个按键下已自定义的模式数量 */
    fun customCount(cfg: JSONObject?, key: String): Int {
        if (cfg == null) return 0
        return try {
            val keys = cfg.optJSONObject("keys") ?: return 0
            val k = keys.optJSONObject(key) ?: return 0
            var n = 0
            val it = k.keys()
            while (it.hasNext()) {
                val mode = it.next()
                if (mode != ModeConfig.F_DEFAULT && mode != "enabled"
                    && ModeConfig.F_DEFAULT != k.optString(mode, ModeConfig.F_DEFAULT)) {
                    n++
                }
            }
            n
        } catch (t: Throwable) {
            0
        }
    }

    /**
     * 分别统计短按、短长按/长按和深长按配置。全局键 0、enabled 与结构节点不计入，
     * 只有明确覆盖某个相机模式且功能非 0 才算一条。
     */
    fun customCounts(cfg: JSONObject?, key: String): CustomCounts {
        if (cfg == null) return CustomCounts()
        return try {
            val keyNode = cfg.optJSONObject("keys")?.optJSONObject(key)
                ?: return CustomCounts()
            fun countNode(node: JSONObject?): Int {
                if (node == null) return 0
                var count = 0
                val names = node.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    if (name != ModeConfig.F_DEFAULT &&
                        node.optString(name, ModeConfig.F_DEFAULT) != ModeConfig.F_DEFAULT) {
                        count++
                    }
                }
                return count
            }
            var shortCount = 0
            val names = keyNode.keys()
            while (names.hasNext()) {
                val name = names.next()
                if (name != ModeConfig.F_DEFAULT && name != "enabled" &&
                    name != ModeConfig.LONG_KEY && name != ModeConfig.DEEP_LONG_KEY &&
                    keyNode.optString(name, ModeConfig.F_DEFAULT) != ModeConfig.F_DEFAULT) {
                    shortCount++
                }
            }
            CustomCounts(
                short = shortCount,
                long = countNode(keyNode.optJSONObject(ModeConfig.LONG_KEY)),
                deepLong = countNode(keyNode.optJSONObject(ModeConfig.DEEP_LONG_KEY)),
            )
        } catch (_: Throwable) {
            CustomCounts()
        }
    }

    /** 静默停止相机进程（不弹 toast） */
    fun stopCameraQuiet() {
        runBg { suExec("am force-stop com.android.camera") }
    }

    /** 切换 promFix 后 force-stop 相机，让修复方案立即按新开关生效 */
    fun forceStopCamera(context: Context, enabled: Boolean) {
        runBg {
            val ok = suExec("am force-stop com.android.camera")
            runUi {
                if (!ok) {
                    toast(context, "已保存，但停止相机进程失败，请手动重启相机", true)
                } else if (enabled) {
                    toast(context, "已启用修复方案并停止相机进程，重新打开相机生效")
                } else {
                    toast(context, "已关闭修复方案并停止相机进程")
                }
            }
        }
    }


    /** 点击/切换时的振动反馈（小米线性马达；CONFIRM 需 API 30+） */
    fun hapticClick(view: android.view.View?) {
        if (view == null) return
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            } else {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (t: Throwable) {
            // ignore
        }
    }
}

/**
 * 滚动边界振动（v11.7）：列表滚动到顶部/底部时各触发一次振动。
 * - 用 canScrollBackward/Forward 判断边界（不受 contentPadding 影响，底部也能正确检测）
 * - 状态翻转 + 400ms 时间防抖：到达边界瞬间只振一次，避免滚动/切页时的重复振动
 */
@Composable
fun LazyListEdgeHaptic(listState: LazyListState, view: View?) {
    var wasTop by remember { mutableStateOf(!listState.canScrollBackward) }
    var wasBottom by remember { mutableStateOf(!listState.canScrollForward) }
    // 顶部/底部独立防抖（300ms）：快速来回切换（顶→底→顶）时两侧各能振到，
    // 同一侧因抖动/回弹在 300ms 内的重复触发才被抑制
    var lastTopMs by remember { mutableStateOf(0L) }
    var lastBottomMs by remember { mutableStateOf(0L) }
    LaunchedEffect(listState) {
        snapshotFlow { !listState.canScrollBackward to !listState.canScrollForward }
            .collect { (atTop, atBottom) ->
                val now = System.currentTimeMillis()
                if (atTop && !wasTop && now - lastTopMs > 300L) {
                    view?.let { UiUtil.hapticClick(it) }
                    lastTopMs = now
                }
                if (atBottom && !wasBottom && now - lastBottomMs > 300L) {
                    view?.let { UiUtil.hapticClick(it) }
                    lastBottomMs = now
                }
                wasTop = atTop
                wasBottom = atBottom
            }
    }
}
