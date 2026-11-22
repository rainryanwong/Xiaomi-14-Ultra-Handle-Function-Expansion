package com.handlefix.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.preference.SwitchPreference

private const val HANDLE_CHARGE_SETTING = "sec_setting_handle_charge_state"
private const val HANDLE_PROTECT_SETTING = "sec_setting_handle_charge_protect"
private const val HANDLE_CHARGE_NODE = "/sys/class/qcom-battery/handle_stop_charging"
private const val SMART_CHARGE_NODE = "/sys/class/qcom-battery/smart_chg"

private data class GripChargeSnapshot(
    val chargeEnabled: Boolean,
    val protectEnabled: Boolean,
    val chargeSupported: Boolean,
    val protectSupported: Boolean,
)

private data class GripChargeResult(
    val success: Boolean,
    val snapshot: GripChargeSnapshot,
    val message: String,
)

/**
 * 摄影手柄供电控制。
 *
 * 主开关同步系统 Secure 状态与厂商充电节点；保护开关只通知系统安全中心的
 * CameraHandleProtectManager，由系统负责 MODE_HANDLE 和 smart_chg 仲裁。这里禁止直接
 * 写 smart_chg，避免覆盖夜间充电、智能充电等其它原生保护模式。
 */
private object GripChargeController {

    /**
     * LazyColumn 会在项目离开可见区域后释放其组合。缓存同一 Activity resumeTick 的
     * 状态，避免重新滚回本项或在三个主页页签间切换时反复执行 Root 查询。
     */
    @Volatile private var cachedSnapshot: GripChargeSnapshot? = null
    @Volatile private var cachedResumeTick: Int = Int.MIN_VALUE

    fun cached(): GripChargeSnapshot? = cachedSnapshot

    @Synchronized
    fun readForResume(resumeTick: Int): GripChargeSnapshot {
        val cached = cachedSnapshot
        if (cached != null && cachedResumeTick == resumeTick) return cached
        return read().also {
            cachedSnapshot = it
            cachedResumeTick = resumeTick
        }
    }

    private fun updateCache(snapshot: GripChargeSnapshot) {
        cachedSnapshot = snapshot
    }

    fun read(): GripChargeSnapshot {
        val output = UiUtil.suOut(
            "printf 'charge='; settings get secure $HANDLE_CHARGE_SETTING; " +
                "printf 'protect='; settings get secure $HANDLE_PROTECT_SETTING; " +
                "printf 'charge_node='; if [ -r $HANDLE_CHARGE_NODE ]; then cat $HANDLE_CHARGE_NODE; else echo missing; fi; " +
                "printf 'smart_node='; if [ -r $SMART_CHARGE_NODE ]; then cat $SMART_CHARGE_NODE; else echo missing; fi; " +
                "printf 'smart_cap='; getprop persist.vendor.smartchg",
        )
        val values = output.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null
                else line.substring(0, separator) to line.substring(separator + 1).trim()
            }
            .toMap()
        val chargeNode = values["charge_node"].orEmpty()
        val smartNode = values["smart_node"].orEmpty()
        val smartCapabilityText = values["smart_cap"].orEmpty()
        val smartCapability = if (smartCapabilityText.startsWith("0x", ignoreCase = true)) {
            smartCapabilityText.substring(2).toLongOrNull(16) ?: 0L
        } else {
            smartCapabilityText.toLongOrNull() ?: 0L
        }
        val chargeSupported = chargeNode.isNotEmpty() && chargeNode != "missing"
        val protectSupported = chargeSupported && smartNode.isNotEmpty() && smartNode != "missing" &&
            (smartCapability and 0x10L) != 0L
        val chargeEnabled = values["charge"] == "1" && chargeNode == "1"
        // 保护偏好独立保存：父开关关闭时只隐藏该项，不得抹掉用户原来的保护选择。
        val protectEnabled = values["protect"] == "1"
        return GripChargeSnapshot(
            chargeEnabled = chargeEnabled,
            protectEnabled = protectEnabled,
            chargeSupported = chargeSupported,
            protectSupported = protectSupported,
        )
    }

    fun setChargeEnabled(enabled: Boolean): GripChargeResult {
        val before = read()
        if (!before.chargeSupported) {
            return GripChargeResult(false, before, "当前 ROM 缺少摄影手柄充电驱动节点")
        }
        val command = if (enabled) {
            // 与官方页面一致：先切换硬件供电，再保存设置状态。
            "printf '1' > $HANDLE_CHARGE_NODE && " +
                "settings put secure $HANDLE_CHARGE_SETTING 1"
        } else {
            // 只关闭手柄供电；保护偏好保持原值，重新开启供电后继续沿用。
            "printf '0' > $HANDLE_CHARGE_NODE && " +
                "settings put secure $HANDLE_CHARGE_SETTING 0"
        }
        val executed = UiUtil.suExec(command)
        val after = read()
        updateCache(after)
        val reached = after.chargeEnabled == enabled &&
            after.protectEnabled == before.protectEnabled
        return GripChargeResult(
            success = executed && reached,
            snapshot = after,
            message = if (executed && reached) {
                if (enabled) "已开启使用摄影手柄充电" else "已关闭使用摄影手柄充电"
            } else {
                "切换失败，请检查 root 授权和当前 ROM 的充电节点"
            },
        )
    }

    fun setProtectEnabled(enabled: Boolean): GripChargeResult {
        val before = read()
        if (!before.protectSupported) {
            return GripChargeResult(false, before, "当前 ROM 缺少原生手柄充电保护能力")
        }
        if (enabled && !before.chargeEnabled) {
            return GripChargeResult(false, before, "请先开启使用摄影手柄充电")
        }
        val executed = UiUtil.suExec(
            "settings put secure $HANDLE_PROTECT_SETTING ${if (enabled) 1 else 0}",
        )
        val after = read()
        updateCache(after)
        val reached = after.protectEnabled == enabled
        return GripChargeResult(
            success = executed && reached,
            snapshot = after,
            message = if (executed && reached) {
                if (enabled) "已开启手柄充电保护" else "已关闭手柄充电保护"
            } else {
                "切换失败，系统未接受手柄充电保护状态"
            },
        )
    }
}

/** 模块设置与调试页中、紧跟在手柄电量状态卡下方的摄影手柄充电联动开关。 */
@Composable
fun GripChargeSettingsPreferences(resumeTick: Int) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(GripChargeController.cached()) }
    var busy by remember { mutableStateOf(false) }

    fun applyResult(block: () -> GripChargeResult) {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { block() }
            snapshot = result.snapshot
            busy = false
            UiUtil.toast(context, result.message, !result.success)
        }
    }

    LaunchedEffect(resumeTick) {
        snapshot = withContext(Dispatchers.IO) {
            GripChargeController.readForResume(resumeTick)
        }
    }

    val current = snapshot
    SwitchPreference(
        title = "使用摄影手柄充电",
        summary = when {
            current == null -> "正在读取系统充电状态"
            !current.chargeSupported -> "当前 ROM 缺少摄影手柄充电驱动节点"
            current.chargeEnabled -> "已开启，插入摄影手柄后可为手机供电"
            else -> "插入摄影手柄后，可通过手柄给手机充电"
        },
        checked = current?.chargeEnabled == true,
        enabled = current?.chargeSupported == true && !busy,
        onCheckedChange = { enabled ->
            UiUtil.hapticClick(view)
            applyResult { GripChargeController.setChargeEnabled(enabled) }
        },
    )
    if (current?.chargeEnabled == true) {
        SwitchPreference(
            title = "手柄充电保护",
            summary = when {
                !current.protectSupported -> "当前 ROM 缺少原生手柄充电保护能力"
                current.protectEnabled -> "已开启，手机电量达到约 80% 后停止继续增加"
                else -> "手机电量达到约 80% 后停止继续增加"
            },
            checked = current.protectEnabled,
            enabled = current.protectSupported && !busy,
            onCheckedChange = { enabled ->
                UiUtil.hapticClick(view)
                applyResult { GripChargeController.setProtectEnabled(enabled) }
            },
        )
    }
}
