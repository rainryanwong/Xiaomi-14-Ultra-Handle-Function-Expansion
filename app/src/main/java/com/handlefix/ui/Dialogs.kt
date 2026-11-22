package com.handlefix.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 左对齐弹窗：miuix 的 OverlayDialog 标题/正文默认居中，
 * 这里把标题和正文放进 content 里手动左对齐（样式与原生弹窗一致）。
 */
@Composable
internal fun LeftDialog(
    show: Boolean,
    title: String? = null,
    text: String? = null,
    onDismissRequest: (() -> Unit)? = null,
    content: @Composable (() -> Unit)? = null,
) {
    OverlayDialog(
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (title != null) {
                Text(
                    text = title,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    fontSize = MiuixTheme.textStyles.title4.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Start,
                )
            }
            if (text != null) {
                Text(
                    text = text,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    fontSize = MiuixTheme.textStyles.body1.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Start,
                )
            }
            content?.invoke()
        }
    }
}
