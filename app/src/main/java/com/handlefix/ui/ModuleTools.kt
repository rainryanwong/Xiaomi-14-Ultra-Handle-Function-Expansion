package com.handlefix.ui

import android.content.Context
import java.io.File

/** 导出唯一内置源码快照到 Download/HandleFixConfig（需 root 写入公共目录）。 */
internal fun exportModuleSource(context: Context, onSuccess: (String) -> Unit) {
    UiUtil.runBg {
        try {
            // 版本号和源码快照可以在维护窗口解耦：发布小版本时若明确不更新源码，
            // 仍应导出仓库中唯一、经过校验的快照，而不是动态拼出不存在的文件名。
            val sourceArchives = context.assets.list("")?.filter {
                it.startsWith("handlefix-src-") && it.endsWith(".zip")
            }.orEmpty()
            require(sourceArchives.size == 1) {
                "内置源码快照数量异常：${sourceArchives.size}"
            }
            val srcName = sourceArchives.single()
            val bytes = context.assets.open(srcName).readBytes()
            val dir = "/sdcard/Download/HandleFixConfig"
            UiUtil.suExec("mkdir -p $dir")
            val tmp = File(context.cacheDir, srcName)
            tmp.writeBytes(bytes)
            val ok = UiUtil.suExec("cp ${tmp.absolutePath} $dir/$srcName")
            if (ok) {
                UiUtil.runUi { onSuccess("$dir/$srcName") }
            } else {
                UiUtil.runUi { UiUtil.toast(context, "导出失败：需要 root 权限", true) }
            }
        } catch (t: Throwable) {
            UiUtil.runUi { UiUtil.toast(context, "导出失败：${t.message}", true) }
        }
    }
}

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
