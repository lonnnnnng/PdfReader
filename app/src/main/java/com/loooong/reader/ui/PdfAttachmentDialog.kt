package com.loooong.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.loooong.reader.engine.PdfAttachmentInfo
import java.util.Locale

@Composable
fun PdfAttachmentDialog(
    attachments: List<PdfAttachmentInfo>,
    busy: Boolean,
    editable: Boolean = true,
    onAdd: () -> Unit,
    onExport: (PdfAttachmentInfo) -> Unit,
    onRename: (key: String, fileName: String, description: String?) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedKey by remember { mutableStateOf<String?>(attachments.firstOrNull()?.key) }
    var editTarget by remember { mutableStateOf<PdfAttachmentInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<PdfAttachmentInfo?>(null) }
    val selected = attachments.firstOrNull { it.key == selectedKey }

    LaunchedEffect(attachments) {
        if (selectedKey == null || attachments.none { it.key == selectedKey }) {
            selectedKey = attachments.firstOrNull()?.key
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("PDF 附件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (attachments.isEmpty()) "当前 PDF 没有文档附件。" else "共 ${attachments.size} 个附件",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (attachments.isNotEmpty()) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(attachments, key = PdfAttachmentInfo::key) { attachment ->
                            val selectedRow = selectedKey == attachment.key
                            ListItem(
                                modifier = Modifier.clickable(
                                    enabled = !busy,
                                    role = Role.RadioButton,
                                ) { selectedKey = attachment.key },
                                colors = ListItemDefaults.colors(
                                    containerColor = if (selectedRow) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                ),
                                headlineContent = {
                                    Text(
                                        attachment.fileName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = {
                                    Column {
                                        Text(attachment.summary())
                                        attachment.description?.let {
                                            Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                        if (!attachment.readable) {
                                            Text(
                                                "附件内容缺失或损坏",
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (editable) {
                        TextButton(onClick = onAdd, enabled = !busy) { Text("添加") }
                    }
                    TextButton(
                        onClick = { selected?.let(onExport) },
                        enabled = !busy && selected?.readable == true,
                    ) { Text("导出") }
                    if (editable) {
                        TextButton(
                            onClick = { editTarget = selected },
                            enabled = !busy && selected != null,
                        ) { Text("重命名") }
                        TextButton(
                            onClick = { deleteTarget = selected },
                            enabled = !busy && selected != null,
                        ) { Text("删除") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("完成") }
        },
    )

    editTarget?.let { attachment ->
        AttachmentEditDialog(
            attachment = attachment,
            busy = busy,
            onApply = { fileName, description ->
                editTarget = null
                onRename(attachment.key, fileName, description)
            },
            onDismiss = { editTarget = null },
        )
    }

    deleteTarget?.let { attachment ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleteTarget = null },
            title = { Text("删除附件？") },
            text = { Text("将从 PDF 中删除“${attachment.fileName}”。此操作可以撤销。") },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }, enabled = !busy) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        onDelete(attachment.key)
                    },
                    enabled = !busy,
                ) { Text("删除") }
            },
        )
    }
}

@Composable
private fun AttachmentEditDialog(
    attachment: PdfAttachmentInfo,
    busy: Boolean,
    onApply: (String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var fileName by remember(attachment.key) { mutableStateOf(attachment.fileName) }
    var description by remember(attachment.key) { mutableStateOf(attachment.description.orEmpty()) }
    val valid = fileName.trim().isNotEmpty() && fileName.length <= 255
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("编辑附件信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("文件名") },
                    singleLine = true,
                    enabled = !busy,
                    isError = !valid,
                    supportingText = {
                        if (!valid) Text("请输入 1-255 个字符的文件名")
                    },
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("说明（可选）") },
                    enabled = !busy,
                    minLines = 2,
                    maxLines = 4,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(fileName.trim(), description.trim().takeIf(String::isNotEmpty)) },
                enabled = !busy && valid,
            ) { Text("保存") }
        },
    )
}

private fun PdfAttachmentInfo.summary(): String = buildString {
    append(mimeType ?: "未知类型")
    sizeBytes?.let { append(" · ${formatAttachmentSize(it)}") }
}

private fun formatAttachmentSize(bytes: Long): String {
    if (bytes < 1_024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1_024.0 && unit < units.lastIndex) {
        value /= 1_024.0
        unit++
    }
    return String.format(Locale.ROOT, if (value >= 10) "%.0f %s" else "%.1f %s", value, units[unit])
}
