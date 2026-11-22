package com.handlefix.ui

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException

/** User-requested reports use scoped storage, not the root-only configuration writer.
 * Publish only a completely written report; remove our own pending row on failure.
 */
internal fun saveDiagnosticReport(context: Context, name: String, text: String) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
        put(MediaStore.MediaColumns.RELATIVE_PATH,
            Environment.DIRECTORY_DOWNLOADS + "/HandleFixConfig/Reports")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("无法创建报告文件")
    try {
        val stream = resolver.openOutputStream(uri, "w")
            ?: throw IOException("无法打开报告文件")
        stream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        val published = resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
        if (published != 1) throw IOException("无法完成报告保存")
    } catch (failure: Exception) {
        try {
            resolver.delete(uri, null, null)
        } catch (cleanup: Exception) {
            failure.addSuppressed(cleanup)
        }
        throw failure
    }
}
