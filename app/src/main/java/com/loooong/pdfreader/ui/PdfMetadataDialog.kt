package com.loooong.pdfreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.loooong.pdfreader.engine.PdfMetadata

@Composable
fun PdfMetadataDialog(
    metadata: PdfMetadata,
    busy: Boolean,
    onApply: (PdfMetadata) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(metadata) { mutableStateOf(metadata.title) }
    var author by remember(metadata) { mutableStateOf(metadata.author) }
    var subject by remember(metadata) { mutableStateOf(metadata.subject) }
    var keywords by remember(metadata) { mutableStateOf(metadata.keywords) }
    var creator by remember(metadata) { mutableStateOf(metadata.creator) }
    val updated = PdfMetadata(title, author, subject, keywords, creator)
    val changed = updated.normalized() != metadata.normalized()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("文档属性") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("这些信息会写入 PDF 的文档信息字典，创建时间和生成器信息保持不变。")
                MetadataField("标题", title, { title = it }, busy)
                MetadataField("作者", author, { author = it }, busy)
                MetadataField("主题", subject, { subject = it }, busy)
                MetadataField("关键词", keywords, { keywords = it }, busy)
                MetadataField("创建程序", creator, { creator = it }, busy)
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && changed,
                onClick = { onApply(updated) },
            ) { Text(if (busy) "正在保存" else "保存") }
        },
    )
}

@Composable
private fun MetadataField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = !busy,
        singleLine = true,
    )
}

private fun PdfMetadata.normalized(): PdfMetadata = copy(
    title = title.trim(),
    author = author.trim(),
    subject = subject.trim(),
    keywords = keywords.trim(),
    creator = creator.trim(),
)
