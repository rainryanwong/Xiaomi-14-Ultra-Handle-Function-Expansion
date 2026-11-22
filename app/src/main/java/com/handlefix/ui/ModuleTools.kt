package com.handlefix.ui

import android.content.Context

// ============ v10.23 重启检测 ============

/** 模块安装/更新后是否已重启生效：安装时间>开机时间 且 相机尚未加载新模块 -> true */
internal fun needsReboot(context: Context): Boolean {
    return try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        val updateTime = maxOf(pi.lastUpdateTime, pi.firstInstallTime)
        val bootTime = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
        if (updateTime <= bootTime) return false
        val envTs = readEnvTs()
        if (envTs != null && envTs >= updateTime) return false
        true
    } catch (t: Throwable) {
        false
    }
}

/** 读取相机环境记录写入时间戳（外部目录，root） */
internal fun readEnvTs(): Long? {
    val xml = UiUtil.suOut("cat /sdcard/Android/data/com.android.camera/files/handlefix_env.xml 2>/dev/null").trim()
    if (xml.isEmpty()) return null
    val m = Regex("<long name=\"ts\" value=\"(\\d+)\"").find(xml)
    return m?.groupValues?.get(1)?.toLongOrNull()
}
