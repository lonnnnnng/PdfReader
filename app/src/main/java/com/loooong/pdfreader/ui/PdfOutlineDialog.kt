package com.loooong.pdfreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.loooong.pdfreader.engine.PdfOutlineEntry
import com.loooong.pdfreader.engine.PdfOutlineMove

@Composable
fun PdfOutlineDialog(
    entries: List<PdfOutlineEntry>,
    pageCount: Int,
    currentPageIndex: Int,
    busy: Boolean,
    onAdd: (title: String, pageIndex: Int, parentId: String?) -> Unit,
    onUpdate: (id: String, title: String, pageIndex: Int) -> Unit,
    onMove: (id: String, direction: PdfOutlineMove) -> Unit,
    onDelete: (id: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<OutlineEditorRequest?>(null) }
    var deleteTarget by remember { mutableStateOf<PdfOutlineEntry?>(null) }
    val selected = entries.firstOrNull { it.id == selectedId }

    LaunchedEffect(entries) {
        if (selectedId != null && entries.none { it.id == selectedId }) selectedId = null
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("编辑 PDF 目录") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (entries.isEmpty()) "当前 PDF 还没有目录，可从当前页开始创建。"
                    else "共 ${entries.size} 个目录项，子目录会随父目录一起移动或删除。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(entries, key = PdfOutlineEntry::id) { entry ->
                        OutlineEntryRow(
                            entry = entry,
                            selected = entry.id == selectedId,
                            enabled = !busy,
                            onSelect = { selectedId = entry.id },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            editor = OutlineEditorRequest.Add(parentId = null, pageIndex = currentPageIndex)
                        },
                    ) { Text("新增章节") }
                    TextButton(
                        enabled = !busy && selected != null,
                        onClick = {
                            selected?.let { entry ->
                                editor = OutlineEditorRequest.Add(
                                    parentId = entry.id,
                                    pageIndex = entry.pageIndex ?: currentPageIndex,
                                )
                            }
                        },
                    ) { Text("新增子目录") }
                    TextButton(
                        enabled = !busy && selected != null,
                        onClick = {
                            selected?.let { entry -> editor = OutlineEditorRequest.Edit(entry) }
                        },
                    ) { Text("编辑") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        enabled = !busy && selected?.canMoveUp == true,
                        onClick = {
                            selected?.let {
                                // long: 路径 ID 会随同级顺序变化，移动前清空选择可避免旧 ID 指向交换后的另一个目录项。
                                selectedId = null
                                onMove(it.id, PdfOutlineMove.UP)
                            }
                        },
                    ) { Text("上移") }
                    TextButton(
                        enabled = !busy && selected?.canMoveDown == true,
                        onClick = {
                            selected?.let {
                                selectedId = null
                                onMove(it.id, PdfOutlineMove.DOWN)
                            }
                        },
                    ) { Text("下移") }
                    TextButton(
                        enabled = !busy && selected != null,
                        onClick = { deleteTarget = selected },
                    ) { Text("删除") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("完成") }
        },
    )

    editor?.let { request ->
        OutlineEntryEditorDialog(
            request = request,
            pageCount = pageCount,
            busy = busy,
            onConfirm = { title, pageIndex ->
                when (request) {
                    is OutlineEditorRequest.Add -> onAdd(title, pageIndex, request.parentId)
                    is OutlineEditorRequest.Edit -> onUpdate(request.entry.id, title, pageIndex)
                }
                editor = null
            },
            onDismiss = { editor = null },
        )
    }

    deleteTarget?.let { entry ->
        val descendantCount = entries.count { candidate -> candidate.id.startsWith("${entry.id}/") }
        AlertDialog(
            onDismissRequest = { if (!busy) deleteTarget = null },
            title = { Text("删除目录项？") },
            text = {
                Text(
                    if (descendantCount == 0) "将删除“${entry.title}”。"
                    else "将删除“${entry.title}”及其 $descendantCount 个子目录。",
                )
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { deleteTarget = null }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        onDelete(entry.id)
                        deleteTarget = null
                        selectedId = null
                    },
                ) { Text("删除") }
            },
        )
    }
}

@Composable
private fun OutlineEntryRow(
    entry: PdfOutlineEntry,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(
                start = 12.dp + (entry.level.coerceAtMost(8) * 18).dp,
                end = 12.dp,
                top = 10.dp,
                bottom = 10.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.title, fontWeight = if (entry.level == 0) FontWeight.SemiBold else FontWeight.Normal)
                Text(
                    entry.pageIndex?.let { "第 ${it + 1} 页" } ?: "目标页无法解析",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.hasChildren) Text("含子目录", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun OutlineEntryEditorDialog(
    request: OutlineEditorRequest,
    pageCount: Int,
    busy: Boolean,
    onConfirm: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialTitle = (request as? OutlineEditorRequest.Edit)?.entry?.title.orEmpty()
    val initialPage = when (request) {
        is OutlineEditorRequest.Add -> request.pageIndex
        is OutlineEditorRequest.Edit -> request.entry.pageIndex ?: 0
    }
    var title by remember(request) { mutableStateOf(initialTitle) }
    var pageText by remember(request) { mutableStateOf((initialPage + 1).toString()) }
    val pageNumber = pageText.toIntOrNull()
    val valid = title.trim().isNotEmpty() && pageNumber != null && pageNumber in 1..pageCount

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (request is OutlineEditorRequest.Add) "新增目录项" else "编辑目录项") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("标题") },
                    singleLine = true,
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = pageText,
                    onValueChange = { pageText = it.filter(Char::isDigit).take(6) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("目标页码") },
                    supportingText = { Text("可填写 1-$pageCount") },
                    isError = pageText.isNotEmpty() && pageNumber !in 1..pageCount,
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && valid,
                onClick = { onConfirm(title.trim(), requireNotNull(pageNumber) - 1) },
            ) { Text("确定") }
        },
    )
}

private sealed interface OutlineEditorRequest {
    data class Add(val parentId: String?, val pageIndex: Int) : OutlineEditorRequest
    data class Edit(val entry: PdfOutlineEntry) : OutlineEditorRequest
}
