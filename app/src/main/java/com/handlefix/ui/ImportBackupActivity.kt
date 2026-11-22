package com.handlefix.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.handlefix.ModeConfig
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** v10.8 导入备份页：点击导入、长按更多操作（备注/删除）；导入成功后重启相机进程 */
class ImportBackupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XiaomiEdgeToEdge()
            HandleFixTheme {
                ImportBackupScreen { finish() }
            }
        }
    }
}

@Composable
private fun ImportBackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    var backups by remember { mutableStateOf(ModeConfig.listBackups()) }
    var refreshTick by remember { mutableStateOf(0) }
    var actionPath by remember { mutableStateOf<String?>(null) }
    var notePath by remember { mutableStateOf<String?>(null) }
    var deletePath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshTick) {
        backups = ModeConfig.listBackups()
    }

    fun import(path: String) {
        val ok = ModeConfig.importBackup(path)
        if (ok) {
            ThemePreference.sync(ModeConfig.read())
            UiUtil.toast(context, "导入成功，设置已恢复（已重启相机进程）", true)
            UiUtil.stopCameraQuiet()
        } else {
            UiUtil.toast(context, "导入失败：需要 root 权限或备份文件损坏", true)
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "导入备份",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 顶部固定提示
            Text(
                text = "点击导入，长按进行更多操作",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (backups.isEmpty()) {
                    item {
                        Card {
                            Text(
                                text = "备份目录为空：\n${ModeConfig.BACKUP_DIR}\n\n请先在“备份配置”里创建备份。",
                                modifier = Modifier.padding(12.dp),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                } else {
                    item {
                        Card {
                            backups.forEach { path ->
                                val r = refreshTick // 订阅刷新：备注保存后立即重绘
                                val note = ModeConfig.getBackupNote(path)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { import(path) },
                                            onLongClick = {
                                                UiUtil.hapticClick(view)
                                                actionPath = path
                                            },
                                        )
                                        .padding(horizontal = 4.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = java.io.File(path).name,
                                            color = MiuixTheme.colorScheme.onSurface,
                                        )
                                        if (note.isNotEmpty()) {
                                            Text(
                                                text = note,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 长按「更多操作」菜单
        actionPath?.let { path ->
            LeftDialog(
                show = true,
                title = "备份操作",
                onDismissRequest = { actionPath = null },
            ) {
                Column {
                    ActionRow("添加/修改备注") {
                        actionPath = null
                        notePath = path
                    }
                    ActionRow("删除备份") {
                        actionPath = null
                        deletePath = path
                    }
                    DialogButtons(cancel = "取消", onCancel = { actionPath = null })
                }
            }
        }

        // 备注输入弹窗
        notePath?.let { path ->
            LeftDialog(
                show = true,
                title = "备注",
                text = "为备份文件添加自定义备注（留空清除）：",
                onDismissRequest = { notePath = null },
            ) {
                key(path) {
                    var noteInput by remember(path) { mutableStateOf(ModeConfig.getBackupNote(path)) }
                    Column {
                        BasicTextField(
                            value = noteInput,
                            onValueChange = { noteInput = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MiuixTheme.colorScheme.surfaceContainer,
                                    RoundedCornerShape(10.dp),
                                )
                                .padding(12.dp),
                        )
                        DialogButtons(
                            cancel = "取消",
                            onCancel = { notePath = null },
                            confirm = "确定",
                            onConfirm = {
                                val text = noteInput.trim()
                                val ok = ModeConfig.setBackupNote(path, text)
                                notePath = null
                                UiUtil.toast(context, if (ok) "备注已保存" else "备注保存失败：需要 root 权限", true)
                                refreshTick++
                            },
                        )
                    }
                }
            }
        }

        // 删除确认
        deletePath?.let { path ->
            LeftDialog(
                show = true,
                title = "删除备份",
                text = "确定删除备份文件「${java.io.File(path).name}」？此操作不可恢复。",
                onDismissRequest = { deletePath = null },
            ) {
                DialogButtons(
                    cancel = "取消",
                    onCancel = { deletePath = null },
                    confirm = "确定",
                    onConfirm = {
                        val ok = ModeConfig.deleteBackup(path)
                        deletePath = null
                        UiUtil.toast(context, if (ok) "已删除备份" else "删除失败：需要 root 权限", true)
                        refreshTick++
                    },
                )
            }
        }
    }
}

@Composable
private fun ActionRow(text: String, onClick: () -> Unit) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                UiUtil.hapticClick(view)
                onClick()
            }
            .padding(vertical = 14.dp),
    ) {
        Text(text = text, color = MiuixTheme.colorScheme.onSurface)
    }
}
