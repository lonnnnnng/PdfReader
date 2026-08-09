package com.loooong.reader.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.loooong.reader.buildPdfSplitRanges
import com.loooong.reader.parsePdfPageRanges
import compose.icons.TablerIcons
import compose.icons.tablericons.FileExport
import compose.icons.tablericons.X

@Composable
fun PageSplitDialog(
    pageCount: Int,
    busy: Boolean,
    onApply: (List<IntRange>) -> Unit,
    onDismiss: () -> Unit,
) {
    var rangeText by remember(pageCount) { mutableStateOf("1-$pageCount") }
    val parsed = runCatching { parsePdfPageRanges(rangeText, pageCount) }
    val ranges = parsed.getOrNull()
    val error = parsed.exceptionOrNull()?.message

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .widthIn(max = 560.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        TablerIcons.FileExport,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "拆分 PDF",
                        modifier = Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss, enabled = !busy) {
                        Icon(TablerIcons.X, contentDescription = "关闭拆分 PDF")
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(1, 2, 5).forEach { size ->
                            FilterChip(
                                selected = ranges == buildPdfSplitRanges(pageCount, size),
                                onClick = {
                                    rangeText = buildPdfSplitRanges(pageCount, size).toRangeText()
                                },
                                enabled = !busy,
                                label = { Text(if (size == 1) "每页" else "每 $size 页") },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = rangeText,
                        onValueChange = { value ->
                            rangeText = value.filter { it.isDigit() || it == '-' || it == ',' || it == '，' || it.isWhitespace() }
                                .take(120)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("页码范围") },
                        placeholder = { Text("1-3,4-6,7-") },
                        supportingText = {
                            Text(error ?: "将生成 ${ranges?.size ?: 0} 个 PDF")
                        },
                        isError = error != null,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        singleLine = true,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onApply(requireNotNull(ranges)) },
                        enabled = !busy && !ranges.isNullOrEmpty(),
                    ) {
                        Text("选择输出文件夹")
                    }
                }
            }
        }
    }
}

private fun List<IntRange>.toRangeText(): String = joinToString(",") { range ->
    if (range.first == range.last) "${range.first + 1}" else "${range.first + 1}-${range.last + 1}"
}
