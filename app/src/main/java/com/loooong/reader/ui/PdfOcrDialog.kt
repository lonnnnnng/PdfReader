package com.loooong.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

data class PdfOcrRequest(
    val pageIndices: Set<Int>,
    val skipExistingText: Boolean,
)

private enum class OcrPageScope(val label: String) {
    CURRENT("当前页"),
    ALL("全部页"),
    RANGE("指定范围"),
}

@Composable
fun PdfOcrDialog(
    pageCount: Int,
    currentPageIndex: Int,
    busy: Boolean,
    onApply: (PdfOcrRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var scope by rememberSaveable { mutableStateOf(OcrPageScope.CURRENT) }
    var startPage by rememberSaveable { mutableStateOf((currentPageIndex + 1).toString()) }
    var endPage by rememberSaveable { mutableStateOf((currentPageIndex + 1).toString()) }
    var skipExistingText by rememberSaveable { mutableStateOf(true) }
    val start = startPage.toIntOrNull()
    val end = endPage.toIntOrNull()
    val rangeValid = start != null && end != null && start in 1..pageCount && end in 1..pageCount && start <= end
    val selection = when (scope) {
        OcrPageScope.CURRENT -> setOf(currentPageIndex)
        OcrPageScope.ALL -> (0 until pageCount).toSet()
        OcrPageScope.RANGE -> if (rangeValid) ((start!! - 1)..(end!! - 1)).toSet() else emptySet()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("OCR 文字识别") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "识别过程完全在设备本地完成，并写入不可见但可搜索、可复制的 PDF 文字层。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("识别页面", style = MaterialTheme.typography.labelLarge)
                Column {
                    OcrPageScope.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = scope == option,
                                    enabled = !busy,
                                    role = Role.RadioButton,
                                    onClick = { scope = option },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = scope == option, onClick = null, enabled = !busy)
                            Text(option.label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }

                if (scope == OcrPageScope.RANGE) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        // long: 窄屏改为纵向输入，页码错误提示不会挤压另一个输入框或导致确认按钮误判。
                        if (maxWidth < 360.dp) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OcrPageField("起始页", startPage, pageCount, busy) { startPage = it }
                                OcrPageField("结束页", endPage, pageCount, busy) { endPage = it }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OcrPageField(
                                    label = "起始页",
                                    value = startPage,
                                    pageCount = pageCount,
                                    busy = busy,
                                    modifier = Modifier.weight(1f),
                                    onValueChange = { startPage = it },
                                )
                                OcrPageField(
                                    label = "结束页",
                                    value = endPage,
                                    pageCount = pageCount,
                                    busy = busy,
                                    modifier = Modifier.weight(1f),
                                    onValueChange = { endPage = it },
                                )
                            }
                        }
                    }
                    if (!rangeValid) {
                        Text(
                            "请输入 1-$pageCount 之间且起始页不大于结束页的范围。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = skipExistingText,
                            enabled = !busy,
                            role = Role.Checkbox,
                            onValueChange = { skipExistingText = it },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = skipExistingText, onCheckedChange = null, enabled = !busy)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text("跳过已有可提取文字的页面")
                        Text(
                            "推荐开启，避免文字型 PDF 产生重复搜索结果。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "整次识别只生成一条撤销记录；重新识别成功时会替换应用之前生成的 OCR 层。",
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
                enabled = !busy && selection.isNotEmpty(),
                onClick = {
                    // long: 对话框关闭由事务完成回调决定，识别或写入失败时保留用户的页码选择便于直接重试。
                    onApply(PdfOcrRequest(selection, skipExistingText))
                },
            ) { Text("开始识别") }
        },
    )
}

@Composable
private fun OcrPageField(
    label: String,
    value: String,
    pageCount: Int,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { candidate ->
            if (candidate.isEmpty() || candidate.all(Char::isDigit)) onValueChange(candidate.take(6))
        },
        modifier = modifier.fillMaxWidth(),
        enabled = !busy,
        singleLine = true,
        label = { Text(label) },
        supportingText = { Text("1-$pageCount") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
