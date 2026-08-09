package com.loooong.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.loooong.reader.engine.PdfCompressionPreset

@Composable
fun PdfCompressionDialog(
    busy: Boolean,
    onApply: (PdfCompressionPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    var presetName by rememberSaveable { mutableStateOf(PdfCompressionPreset.BALANCED.name) }
    val preset = PdfCompressionPreset.valueOf(presetName)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("压缩 PDF") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "压缩页面中的普通位图，文本、矢量图形、批注和页面结构保持不变。透明蒙版、专色和无法安全重编码的图片会自动跳过。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PdfCompressionPreset.entries.forEach { option ->
                    val selected = option == preset
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                enabled = !busy,
                                role = Role.RadioButton,
                                onClick = { presetName = option.name },
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null, enabled = !busy)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(option.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    "压缩成功后可直接撤销；应用会先生成完整文档快照，取消或失败时恢复压缩前内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = { onApply(preset) },
            ) { Text("开始压缩") }
        },
    )
}

private val PdfCompressionPreset.title: String
    get() = when (this) {
        PdfCompressionPreset.LIGHT -> "轻度压缩"
        PdfCompressionPreset.BALANCED -> "均衡压缩（推荐）"
        PdfCompressionPreset.STRONG -> "强力压缩"
    }

private val PdfCompressionPreset.description: String
    get() = when (this) {
        PdfCompressionPreset.LIGHT -> "最长边 2400 像素，JPEG 质量 86，适合清晰度优先。"
        PdfCompressionPreset.BALANCED -> "最长边 1600 像素，JPEG 质量 76，兼顾体积和阅读效果。"
        PdfCompressionPreset.STRONG -> "最长边 1200 像素，JPEG 质量 64，适合分享和节省空间。"
    }
