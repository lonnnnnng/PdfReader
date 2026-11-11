package com.loooong.pdfreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.loooong.pdfreader.engine.Bounds
import com.loooong.pdfreader.engine.PdfLinkCreateRequest
import com.loooong.pdfreader.engine.PdfLinkId
import com.loooong.pdfreader.engine.PdfLinkInfo
import com.loooong.pdfreader.engine.PdfLinkKind
import com.loooong.pdfreader.engine.PdfLinkTarget
import com.loooong.pdfreader.engine.PdfLinkUpdateRequest
import java.util.Locale

/** long: 链接管理弹窗把页面动作与区域坐标集中展示，避免用户只能通过底层批注猜测跳转关系。 */
@Composable
fun PdfLinkDialog(
    links: List<PdfLinkInfo>,
    pageCount: Int,
    currentPageIndex: Int,
    defaultBounds: Bounds?,
    busy: Boolean,
    onCreate: (PdfLinkCreateRequest, onComplete: (Boolean) -> Unit) -> Unit,
    onUpdate: (PdfLinkId, PdfLinkUpdateRequest, onComplete: (Boolean) -> Unit) -> Unit,
    onDelete: (PdfLinkId, onComplete: (Boolean) -> Unit) -> Unit,
    onGoToPage: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedId by remember { mutableStateOf<PdfLinkId?>(null) }
    var editor by remember { mutableStateOf<PdfLinkEditorRequest?>(null) }
    var deleteTarget by remember { mutableStateOf<PdfLinkInfo?>(null) }
    val selected = links.firstOrNull { it.id == selectedId }

    LaunchedEffect(links) {
        if (selectedId != null && links.none { it.id == selectedId }) selectedId = null
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("管理 PDF 链接") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (links.isEmpty()) "当前 PDF 没有链接，可从当前页创建 URI 或页内跳转链接。"
                    else "共 ${links.size} 个链接；不支持的动作会以只读方式保留。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp, max = 430.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    items(links, key = { it.id.pageIndex to it.id.annotationIndex }) { link ->
                        PdfLinkRow(
                            link = link,
                            selected = link.id == selectedId,
                            enabled = !busy,
                            onSelect = { selectedId = link.id },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        enabled = !busy && defaultBounds != null,
                        onClick = {
                            defaultBounds?.let { bounds ->
                                editor = PdfLinkEditorRequest.Create(
                                    pageIndex = currentPageIndex,
                                    bounds = bounds,
                                )
                            }
                        },
                    ) { Text("新建") }
                    TextButton(
                        enabled = !busy && selected?.editable == true && selected.bounds != null,
                        onClick = { selected?.let { editor = PdfLinkEditorRequest.Edit(it) } },
                    ) { Text("编辑") }
                    TextButton(
                        enabled = !busy && selected?.editable == true,
                        onClick = { selected?.let { deleteTarget = it } },
                    ) { Text("删除") }
                    TextButton(
                        enabled = !busy && selected?.kind == PdfLinkKind.PAGE && selected.targetPageIndex != null,
                        onClick = { selected?.targetPageIndex?.let(onGoToPage) },
                    ) { Text("跳转") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("完成") }
        },
    )

    editor?.let { request ->
        PdfLinkEditorDialog(
            request = request,
            pageCount = pageCount,
            busy = busy,
            onConfirm = { target, bounds ->
                when (request) {
                    is PdfLinkEditorRequest.Create -> onCreate(
                        PdfLinkCreateRequest(request.pageIndex, bounds, target),
                    ) { success -> if (success) editor = null }
                    is PdfLinkEditorRequest.Edit -> onUpdate(
                        request.link.id,
                        PdfLinkUpdateRequest(bounds, target),
                    ) { success -> if (success) editor = null }
                }
            },
            onDismiss = { editor = null },
        )
    }

    deleteTarget?.let { link ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleteTarget = null },
            title = { Text("删除链接？") },
            text = { Text("将删除第 ${link.id.pageIndex + 1} 页上的“${link.description}”链接。") },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { deleteTarget = null }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        onDelete(link.id) { success ->
                            if (success) {
                                deleteTarget = null
                                selectedId = null
                            }
                        }
                    },
                ) { Text("删除") }
            },
        )
    }
}

@Composable
private fun PdfLinkRow(
    link: PdfLinkInfo,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val kindLabel = when (link.kind) {
        PdfLinkKind.URI -> "网址"
        PdfLinkKind.PAGE -> "页内跳转"
        PdfLinkKind.UNSUPPORTED -> "只读动作"
        PdfLinkKind.BROKEN -> "损坏链接"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("第 ${link.id.pageIndex + 1} 页 · $kindLabel")
                Text(
                    if (link.editable) "可编辑" else "只读",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                link.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            link.bounds?.let { bounds ->
                Text(
                    "区域 ${format(bounds.minX)}, ${format(bounds.minY)} – ${format(bounds.maxX)}, ${format(bounds.maxY)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PdfLinkEditorDialog(
    request: PdfLinkEditorRequest,
    pageCount: Int,
    busy: Boolean,
    onConfirm: (PdfLinkTarget, Bounds) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialLink = (request as? PdfLinkEditorRequest.Edit)?.link
    val initialTarget = initialLink?.let { info ->
        when (info.kind) {
            PdfLinkKind.URI -> info.uri?.let(PdfLinkTarget::Uri)
            PdfLinkKind.PAGE -> info.targetPageIndex?.let { pageIndex ->
                PdfLinkTarget.Page(pageIndex, info.targetLeft, info.targetTop, info.targetZoom)
            }
            else -> null
        }
    }
    var targetType by remember(request) {
        mutableStateOf(if (initialTarget is PdfLinkTarget.Page) LinkTargetType.PAGE else LinkTargetType.URI)
    }
    var uri by remember(request) {
        mutableStateOf((initialTarget as? PdfLinkTarget.Uri)?.value.orEmpty())
    }
    var targetPage by remember(request) {
        mutableStateOf(((initialTarget as? PdfLinkTarget.Page)?.pageIndex?.plus(1) ?: 1).toString())
    }
    var targetLeft by remember(request) {
        mutableStateOf((initialTarget as? PdfLinkTarget.Page)?.left?.toString().orEmpty())
    }
    var targetTop by remember(request) {
        mutableStateOf((initialTarget as? PdfLinkTarget.Page)?.top?.toString().orEmpty())
    }
    var targetZoom by remember(request) {
        mutableStateOf((initialTarget as? PdfLinkTarget.Page)?.zoom?.toString().orEmpty())
    }
    val initialBounds = initialLink?.bounds ?: (request as? PdfLinkEditorRequest.Create)?.bounds
    var minX by remember(request) { mutableStateOf(format(initialBounds?.minX ?: 0f)) }
    var minY by remember(request) { mutableStateOf(format(initialBounds?.minY ?: 0f)) }
    var maxX by remember(request) { mutableStateOf(format(initialBounds?.maxX ?: 160f)) }
    var maxY by remember(request) { mutableStateOf(format(initialBounds?.maxY ?: 28f)) }

    val parsedBounds = parseBounds(minX, minY, maxX, maxY)
    val pageNumber = targetPage.toIntOrNull()
    val target = if (targetType == LinkTargetType.URI) {
        PdfLinkTarget.Uri(uri.trim())
    } else if (pageNumber != null) {
        PdfLinkTarget.Page(
            pageIndex = pageNumber - 1,
            left = targetLeft.toIntOrNull(),
            top = targetTop.toIntOrNull(),
            zoom = targetZoom.toFloatOrNull(),
        )
    } else {
        null
    }
    val validTarget = when (target) {
        is PdfLinkTarget.Uri -> uri.trim().isNotEmpty() && uri.contains(":")
        is PdfLinkTarget.Page -> target.pageIndex in 0 until pageCount &&
            (target.zoom == null || target.zoom.isFinite() && target.zoom in 0f..100f)
        null -> false
    }
    val valid = !busy && parsedBounds != null && validTarget

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (request is PdfLinkEditorRequest.Create) "新建 PDF 链接" else "编辑 PDF 链接") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        enabled = !busy,
                        onClick = { targetType = LinkTargetType.URI },
                    ) { Text(if (targetType == LinkTargetType.URI) "• 网址" else "网址") }
                    TextButton(
                        enabled = !busy,
                        onClick = { targetType = LinkTargetType.PAGE },
                    ) { Text(if (targetType == LinkTargetType.PAGE) "• 页内跳转" else "页内跳转") }
                }
                if (targetType == LinkTargetType.URI) {
                    OutlinedTextField(
                        value = uri,
                        onValueChange = { uri = it.take(2048) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("链接地址") },
                        supportingText = { Text("例如 https://example.com") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                } else {
                    OutlinedTextField(
                        value = targetPage,
                        onValueChange = { targetPage = it.filter(Char::isDigit).take(6) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("目标页码") },
                        supportingText = { Text("可填写 1-$pageCount") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinkNumberField("左坐标", targetLeft, !busy) {
                            targetLeft = it.filter { c -> c == '-' || c.isDigit() }.take(8)
                        }
                        LinkNumberField("上坐标", targetTop, !busy) {
                            targetTop = it.filter { c -> c == '-' || c.isDigit() }.take(8)
                        }
                    }
                    OutlinedTextField(
                        value = targetZoom,
                        onValueChange = { targetZoom = it.filter { c -> c.isDigit() || c == '.' }.take(8) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("缩放比例（可选）") },
                        supportingText = { Text("0-100，留空表示保持当前缩放") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                Text("链接区域（PDF 坐标，左下角为原点）", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinkNumberField("左", minX, !busy) { minX = it }
                    LinkNumberField("下", minY, !busy) { minY = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinkNumberField("右", maxX, !busy) { maxX = it }
                    LinkNumberField("上", maxY, !busy) { maxY = it }
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(requireNotNull(target), requireNotNull(parsedBounds)) },
            ) { Text(if (busy) "正在保存" else "保存") }
        },
    )
}

@Composable
private fun RowScope.LinkNumberField(
    label: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter { c -> c.isDigit() || c == '-' || c == '.' }.take(10)) },
        modifier = Modifier.weight(1f),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

private fun parseBounds(minX: String, minY: String, maxX: String, maxY: String): Bounds? {
    val values = listOf(minX, minY, maxX, maxY).map(String::toFloatOrNull)
    if (values.any { it == null || !it.isFinite() }) return null
    val (left, bottom, right, top) = values.map { requireNotNull(it) }
    if (right <= left || top <= bottom) return null
    return Bounds(left, bottom, right, top)
}

private fun format(value: Float): String =
    if (value.isFinite() && value % 1f == 0f) value.toInt().toString()
    else String.format(Locale.US, "%.2f", value)

private enum class LinkTargetType { URI, PAGE }

private sealed interface PdfLinkEditorRequest {
    data class Create(val pageIndex: Int, val bounds: Bounds) : PdfLinkEditorRequest
    data class Edit(val link: PdfLinkInfo) : PdfLinkEditorRequest
}
