package com.handlefix.ui

/**
 * 素材来源（重要，维护必读）：
 * - USB 手柄（14U 黑/白、15U 黑/灰、17U 黑/金）：系统安全中心 SecurityCenter 的 drawable
 *   （pc_handle / o1g / p1g，设备分支见其 CameraHandleReceiver）
 * - 无线/蓝牙手柄（13U 灰/绿、15U 时尚 x5、17U 轻薄黑/白/紫）：
 *   系统蓝牙扩展 BluetoothExtension 的云端资源包（FDS 签名下载）：
 *   https://cdn.cnbj1.fds.api.mi-img.com/bluetoothencrypt/fast_connect/{deviceId}.zip
 *   AccessKey=AKGHWII73YKE5DXZB7  Secret=TAWKBFc4SgD3+G1AueOwiC01HimHaQWzXgCYeNu9
 *   签名: HMAC-SHA1("GET\n\n\n{expires_ms}\n/bluetoothencrypt/fast_connect/{id}.zip") -> Base64
 *   包内 fc_{id}_hs_ok 为主图、resource_record.xml 为官方设备名
 *   详见 outputs/HANDOFF_小米蓝牙设备云端素材下载通道_v1.0.md
 * 页序（共 16 页，改页数务必同步所有 % 16 取模！）：
 *   0-5 USB（14U 黑/白、15U 黑/灰、17U 黑/金）
 *   6-7 13U 无线（灰/绿）  8-12 15U 时尚 x5  13-15 17U 轻薄（黑/白/紫）
 */

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.input.InputManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import android.view.InputDevice
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.handlefix.all.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 手柄电量广播（MIUI 系统发送：extra batteryLevel=0~100 / batteryStats=充电状态） */
private const val ACTION_HANDLE_BATTERY_STATE_CHANGED = "miui.intent.action.ACTION_HANDLE_BATTERY_STATE_CHANGED"

/** 手柄连接状态广播（MIUI 系统发送：extra EXTRA_HANDLE_CONNECT_STATE=0/1、ColorNumber=颜色码） */
private const val ACTION_HANDLE_STATE_CHANGED = "miui.intent.action.ACTION_HANDLE_STATE_CHANGED"

/** 日志 TAG */
private const val TAG = "HandleFixGrip"

/**
 * 摄影手柄常驻状态卡（模块设置与调试页顶部，v12.0.4）：
 * 参考系统弹窗布局——标题 + 手柄图片（左右横滑切换黑/白）+ 手机/手柄电量。
 * - 手机电量：标准 BATTERY_CHANGED 广播
 * - 手柄电量：MIUI ACTION_HANDLE_BATTERY_STATE_CHANGED 广播（全局可收，已在系统日志验证）
 * - 手柄连接状态：轮询 InputManager 输入设备（Xiaomi N1G），插入/拔出实时刷新，未插入电量显示 --
 */
@Composable
fun GripStatusCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phoneLevel by remember { mutableIntStateOf(100) }
    var gripLevel by remember { mutableStateOf<Int?>(null) }
    var gripConnected by remember { mutableStateOf(isGripConnected(context)) }
    // 无限循环轮播：初始页取大数中间，pageCount 给足空间，
    // 实际内容用 page % 16 映射，左右划动永远不会到尽头（不会划出到外层备份与恢复页）
    val pagerState = rememberPagerState(initialPage = 8000, pageCount = { 16000 })

    // 动态注册广播：手机电量 + 手柄电量（页面组合期间有效，退出自动注销）
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        phoneLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                    }
                    ACTION_HANDLE_BATTERY_STATE_CHANGED -> {
                        gripLevel = intent.getIntExtra("batteryLevel", -1).takeIf { it >= 0 }
                    }
                    // 手柄插入：按设备型号 + 颜色码自动跳到对应手柄图片页（逻辑同系统安全中心 CameraHandleReceiver）
                    ACTION_HANDLE_STATE_CHANGED -> {
                        val connected = intent.getIntExtra("miui.intent.extra.EXTRA_HANDLE_CONNECT_STATE", 0) == 1
                        val color = intent.getStringExtra("ColorNumber")
                        Log.d(TAG, "handleState connected=$connected color=$color cur=${pagerState.currentPage} mod16=${pagerState.currentPage % 16}")
                        if (connected && color != null) {
                            val page = gripPageForColor(color)
                            Log.d(TAG, "handleState mapPage=$page")
                            if (page != null) {
                                val target = pagerState.currentPage - (pagerState.currentPage % 16) + page
                                Log.d(TAG, "handleState scrollTo=$target")
                                scope.launch {
                                    pagerState.scrollToPage(target)
                                    Log.d(TAG, "handleState scrollDone cur=${pagerState.currentPage}")
                                }
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(ACTION_HANDLE_BATTERY_STATE_CHANGED)
            addAction(ACTION_HANDLE_STATE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    // 轮询手柄连接状态（2s）：拔出后清空电量显示 --，插入后由广播刷新电量
    LaunchedEffect(Unit) {
        while (true) {
            val connected = isGripConnected(context)
            if (!connected) gripLevel = null
            gripConnected = connected
            // 用系统最新电量广播（sticky）同步手机电量：广播实时更新 + 这里兜底，避免显示滞后
            val stickyBattery = context.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            stickyBattery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                ?.takeIf { it >= 0 }
                ?.let { phoneLevel = it }
            delay(2000)
        }
    }

    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 手柄图片：左右横向划动切换
            // 页序：USB 6 页（安全中心素材）+ 无线/蓝牙 10 页（蓝牙扩展云端素材）：13U 灰/绿、15U 时尚 x5、17U 轻薄黑/白/紫
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().height(160.dp),
            ) { page ->
                val pageIndex = page % 16
                val gripRes = when (pageIndex) {
                    0 -> R.drawable.grip_black
                    1 -> R.drawable.grip_white
                    2 -> R.drawable.grip_15u_black
                    3 -> R.drawable.grip_15u_grey
                    4 -> R.drawable.grip_17u_black
                    5 -> R.drawable.grip_17u_gold
                    // 无线/蓝牙手柄（素材取自系统蓝牙扩展云端资源包 fc_{deviceId}_hs_ok）
                    6 -> R.drawable.grip_13u_grey    // 13U 无线摄影手柄（0101210001 浅灰）
                    7 -> R.drawable.grip_13u_green   // 13U 无线摄影手柄（0101210000 绿）
                    8 -> R.drawable.grip_15u_bt_1    // 15U 时尚摄影手柄（0101210009）
                    9 -> R.drawable.grip_15u_bt_2    // 15U 时尚摄影手柄（010121000A）
                    10 -> R.drawable.grip_15u_bt_3   // 15U 时尚摄影手柄（010121000B）
                    11 -> R.drawable.grip_15u_bt_4   // 15U 时尚摄影手柄（010121000C）
                    12 -> R.drawable.grip_15u_bt_5   // 15U 时尚摄影手柄（010121000D）
                    13 -> R.drawable.grip_17u_bt_black // 17U 轻薄影像手柄（0101210017 黑）
                    14 -> R.drawable.grip_17u_bt_white // 17U 轻薄影像手柄（0101210018 白）
                    else -> R.drawable.grip_17u_bt_purple // 17U 轻薄影像手柄（010121001A 紫）
                }
                Image(
                    painter = painterResource(gripRes),
                    contentDescription = "摄影手柄图片",
                    modifier = Modifier.fillMaxWidth().height(160.dp).clipToBounds(),
                    // 竖图（13U/15U 无线，页 6-12）宽度铺满 + 垂直居中裁剪，手柄主体大小与 USB 横幅图一致；其余横幅图完整 Fit
                    contentScale = if (pageIndex in 6..12) ContentScale.FillWidth else ContentScale.Fit,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "手机电量：$phoneLevel%",
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = "手柄电量：" + (if (gripConnected && gripLevel != null) "${gripLevel}%" else "--"),
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * 根据设备型号 + 手柄颜色码映射到图片页（与系统安全中心 CameraHandleReceiver 的映射一致）：
 * - 14 Ultra（Build.DEVICE=aurora）：ZL/ZT=黑，ZM/ZU=白
 * - 15 Ultra（Build.DEVICE=xuanyuan）：1B=黑，2W=灰
 * - 17 Ultra（Build.DEVICE=nezha）：1B=黑，2G=金
 * 页序：0=14U黑 1=14U白 2=15U黑 3=15U灰 4=17U黑 5=17U金
 */
private fun gripPageForColor(color: String): Int? {
    return when (Build.DEVICE) {
        "aurora", "aurorapro" -> when (color) {
            "ZL", "ZT" -> 0
            "ZM", "ZU" -> 1
            else -> null
        }
        "xuanyuan" -> when (color) {
            "1B" -> 2
            "2W" -> 3
            else -> null
        }
        "nezha" -> when (color) {
            "1B" -> 4
            "2G" -> 5
            else -> null
        }
        else -> null
    }
}

/** 检测摄影手柄（Xiaomi N1G）是否已作为输入设备连接 */
private fun isGripConnected(context: Context): Boolean {
    return try {
        val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        im.getInputDeviceIds().any { id ->
            val dev: InputDevice? = im.getInputDevice(id)
            dev?.name?.contains("N1G") == true
        }
    } catch (t: Throwable) {
        false
    }
}
