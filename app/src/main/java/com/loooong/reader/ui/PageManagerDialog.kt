package com.loooong.reader.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.loooong.reader.ReaderPageInfo
import com.loooong.reader.ReaderPageState
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowsLeftRight
import compose.icons.tablericons.AspectRatio
import compose.icons.tablericons.ChevronLeft
import compose.icons.tablericons.ChevronRight
import compose.icons.tablericons.Check
import compose.icons.tablericons.Copy
import compose.icons.tablericons.Crop
import compose.icons.tablericons.FileExport
import compose.icons.tablericons.FileImport
import compose.icons.tablericons.FilePlus
import compose.icons.tablericons.Plus
import compose.icons.tablericons.RotateClockwise2
import compose.icons.tablericons.Trash
import compose.icons.tablericons.X

@Composable
fun PageManagerDialog(
    pages: List<ReaderPageInfo>,
    thumbnails: Map<Int, ReaderPageState>,
    selectedPageIndex: Int,
    busy: Boolean,
    onLoadThumbnail: (Int) -> Unit,
    onSelectPage: (Int) -> Unit,
    onInsertBefore: () -> Unit,
    onInsertAfter: () -> Unit,
    onDuplicate: () -> Unit,
    onDuplicatePages: (Set<Int>) -> Unit,
    onMoveBackward: () -> Unit,
    onMoveForward: () -> Unit,
    onMovePagesBackward: (Set<Int>) -> Unit,
    onMovePagesForward: (Set<Int>) -> Unit,
    onMoveTo: (sourceIndex: Int, targetIndex: Int) -> Unit,
    onImportPages: () -> Unit,
    onRotate: () -> Unit,
    onCrop: () -> Unit,
    onCropPages: (Set<Int>) -> Unit,
    onResize: () -> Unit,
    onSplit: () -> Unit,
    onExportPages: (Set<Int>) -> Unit,
    onRotatePages: (Set<Int>) -> Unit,
    onResizePages: (Set<Int>) -> Unit,
    onDeletePages: (Set<Int>) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    // long: 页面缩略图同时承载跳页、移动目标和批量选择；模式状态跨旋转保留，但页面结构变化时由上层重建清空。
    var moveSourceIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedPageIndices by rememberSaveable(
        stateSaver = Saver(
            save = { ArrayList(it) },
            restore = { it.toSet() },
        ),
    ) { mutableStateOf<Set<Int>>(emptySet()) }
    val actionsEnabled = !busy && pages.isNotEmpty()
    val gridState = rememberLazyGridState()

    LaunchedEffect(pages.size) {
        if (moveSourceIndex !in pages.indices) moveSourceIndex = null
        selectedPageIndices = selectedPageIndices.filterTo(linkedSetOf()) { it in pages.indices }
    }
    LaunchedEffect(selectedPageIndex, pages.size) {
        if (selectedPageIndex in pages.indices) gridState.scrollToItem(selectedPageIndex)
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .widthIn(max = 960.dp)
                    .heightIn(min = 360.dp, max = 760.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
            ) {
                Column {
                    PageManagerHeader(
                        pageCount = pages.size,
                        moving = moveSourceIndex != null,
                        selecting = selectionMode,
                        selectedCount = selectedPageIndices.size,
                        allSelected = pages.isNotEmpty() && selectedPageIndices.size == pages.size,
                        onCancelMove = { moveSourceIndex = null },
                        onBeginSelection = {
                            selectionMode = true
                            selectedPageIndices = setOf(selectedPageIndex)
                        },
                        onToggleAll = {
                            selectedPageIndices = if (selectedPageIndices.size == pages.size) {
                                emptySet()
                            } else {
                                pages.mapTo(linkedSetOf(), ReaderPageInfo::pageIndex)
                            }
                        },
                        onCancelSelection = {
                            selectionMode = false
                            selectedPageIndices = emptySet()
                        },
                        onDismiss = onDismiss,
                        dismissEnabled = !busy,
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 132.dp),
                        state = gridState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(pages, key = ReaderPageInfo::pageIndex) { page ->
                            val thumbnail = thumbnails[page.pageIndex]
                            // long: 只为可见且尚未缓存的页面请求缩略图，大文档打开工作台时不会一次渲染全部页面。
                            LaunchedEffect(page.pageIndex, thumbnail?.bitmap, thumbnail?.loading) {
                                if (thumbnail?.bitmap == null && thumbnail?.loading != true) {
                                    onLoadThumbnail(page.pageIndex)
                                }
                            }
                            PageThumbnail(
                                page = page,
                                state = thumbnail,
                                current = page.pageIndex == selectedPageIndex,
                                checked = page.pageIndex in selectedPageIndices,
                                selectionMode = selectionMode,
                                moveTargetMode = moveSourceIndex != null,
                                enabled = !busy,
                                onClick = {
                                    val source = moveSourceIndex
                                    if (selectionMode) {
                                        selectedPageIndices = if (page.pageIndex in selectedPageIndices) {
                                            selectedPageIndices - page.pageIndex
                                        } else {
                                            selectedPageIndices + page.pageIndex
                                        }
                                    } else if (source == null) {
                                        onSelectPage(page.pageIndex)
                                    } else {
                                        moveSourceIndex = null
                                        if (source != page.pageIndex) onMoveTo(source, page.pageIndex)
                                    }
                                },
                                onLongClick = {
                                    if (moveSourceIndex == null) {
                                        selectionMode = true
                                        selectedPageIndices = selectedPageIndices + page.pageIndex
                                    }
                                },
                            )
                        }
                    }
                    if (selectionMode) {
                        PageSelectionActions(
                            selectedCount = selectedPageIndices.size,
                            pageCount = pages.size,
                            enabled = !busy && selectedPageIndices.isNotEmpty(),
                            onRotate = { onRotatePages(selectedPageIndices) },
                            onDuplicate = { onDuplicatePages(selectedPageIndices) },
                            onMoveBackward = { onMovePagesBackward(selectedPageIndices) },
                            onMoveForward = { onMovePagesForward(selectedPageIndices) },
                            onCrop = { onCropPages(selectedPageIndices) },
                            onResize = { onResizePages(selectedPageIndices) },
                            onDelete = { onDeletePages(selectedPageIndices) },
                            onExport = { onExportPages(selectedPageIndices) },
                        )
                    } else {
                        PageManagerActions(
                            enabled = actionsEnabled && moveSourceIndex == null,
                            canMoveBackward = actionsEnabled && selectedPageIndex > 0,
                            canMoveForward = actionsEnabled && selectedPageIndex < pages.lastIndex,
                            canDelete = actionsEnabled && pages.size > 1,
                            onInsertBefore = onInsertBefore,
                            onInsertAfter = onInsertAfter,
                            onDuplicate = onDuplicate,
                            onMoveBackward = onMoveBackward,
                            onMoveForward = onMoveForward,
                            onMoveTo = { moveSourceIndex = selectedPageIndex },
                            onImportPages = onImportPages,
                            onRotate = onRotate,
                            onCrop = onCrop,
                            onResize = onResize,
                            onSplit = onSplit,
                            onDelete = onDelete,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageManagerHeader(
    pageCount: Int,
    moving: Boolean,
    selecting: Boolean,
    selectedCount: Int,
    allSelected: Boolean,
    onCancelMove: () -> Unit,
    onBeginSelection: () -> Unit,
    onToggleAll: () -> Unit,
    onCancelSelection: () -> Unit,
    onDismiss: () -> Unit,
    dismissEnabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = when {
                    moving -> "选择目标位置"
                    selecting -> "选择页面"
                    else -> "管理页面"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = when {
                    moving -> "点击目标页，所选页将移动为该页码"
                    selecting -> "已选 $selectedCount / $pageCount 页"
                    else -> "共 $pageCount 页"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        if (moving) {
            FilledTonalButton(onClick = onCancelMove) {
                Text("取消移动")
            }
        } else if (selecting) {
            TextButton(onClick = onToggleAll) {
                Text(if (allSelected) "清空" else "全选")
            }
            IconButton(onClick = onCancelSelection, enabled = dismissEnabled) {
                Icon(TablerIcons.X, contentDescription = "退出页面选择")
            }
        } else {
            IconButton(onClick = onBeginSelection, enabled = dismissEnabled && pageCount > 0) {
                Icon(TablerIcons.Check, contentDescription = "选择多个页面")
            }
            IconButton(onClick = onDismiss, enabled = dismissEnabled) {
                Icon(TablerIcons.X, contentDescription = "关闭页面管理")
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PageThumbnail(
    page: ReaderPageInfo,
    state: ReaderPageState?,
    current: Boolean,
    checked: Boolean,
    selectionMode: Boolean,
    moveTargetMode: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val highlighted = if (selectionMode) checked else current
    val borderColor = if (highlighted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val interactionModifier = if (selectionMode) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = { onClick() },
        )
    } else {
        Modifier.combinedClickable(
            enabled = enabled,
            role = Role.Button,
            onLongClick = if (moveTargetMode) null else onLongClick,
            onClick = onClick,
        )
    }
    Column(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(shape)
            .background(
                if (highlighted) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow,
            )
            .border(if (highlighted) 2.dp else 1.dp, borderColor, shape)
            .then(interactionModifier)
            .semantics {
                contentDescription = when {
                    moveTargetMode -> "将所选页移动为第 ${page.pageIndex + 1} 页"
                    selectionMode && checked -> "第 ${page.pageIndex + 1} 页，已选择"
                    selectionMode -> "第 ${page.pageIndex + 1} 页，未选择"
                    else -> "第 ${page.pageIndex + 1} 页缩略图"
                }
                if (!selectionMode && current) stateDescription = "当前页"
            }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio((page.widthPoints / page.heightPoints).coerceAtLeast(0.1f))
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            state?.bitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            if (state?.loading == true) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else if (state?.error != null) {
                Text(
                    text = "加载失败",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (selectionMode && checked) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(
                        TablerIcons.Check,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp).size(16.dp),
                    )
                }
            }
        }
        Text(
            text = "第 ${page.pageIndex + 1} 页",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PageSelectionActions(
    selectedCount: Int,
    pageCount: Int,
    enabled: Boolean,
    onRotate: () -> Unit,
    onDuplicate: () -> Unit,
    onMoveBackward: () -> Unit,
    onMoveForward: () -> Unit,
    onCrop: () -> Unit,
    onResize: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = "已选 $selectedCount 页",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PageActionButton(TablerIcons.Copy, "复制", enabled, onDuplicate)
            PageActionButton(TablerIcons.ChevronLeft, "前移", enabled, onMoveBackward)
            PageActionButton(TablerIcons.ChevronRight, "后移", enabled, onMoveForward)
            PageActionButton(TablerIcons.Crop, "裁剪", enabled, onCrop)
            PageActionButton(TablerIcons.RotateClockwise2, "旋转", enabled, onRotate)
            PageActionButton(TablerIcons.AspectRatio, "尺寸", enabled, onResize)
            PageActionButton(
                icon = TablerIcons.Trash,
                label = "删除",
                enabled = enabled && selectedCount < pageCount,
                onClick = onDelete,
                destructive = true,
            )
            PageActionButton(TablerIcons.FileExport, "导出", enabled, onExport)
        }
    }
}

@Composable
private fun PageManagerActions(
    enabled: Boolean,
    canMoveBackward: Boolean,
    canMoveForward: Boolean,
    canDelete: Boolean,
    onInsertBefore: () -> Unit,
    onInsertAfter: () -> Unit,
    onDuplicate: () -> Unit,
    onMoveBackward: () -> Unit,
    onMoveForward: () -> Unit,
    onMoveTo: () -> Unit,
    onImportPages: () -> Unit,
    onRotate: () -> Unit,
    onCrop: () -> Unit,
    onResize: () -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PageActionButton(TablerIcons.FilePlus, "前插", enabled, onInsertBefore)
        PageActionButton(TablerIcons.Plus, "后插", enabled, onInsertAfter)
        PageActionButton(TablerIcons.Copy, "复制", enabled, onDuplicate)
        PageActionButton(TablerIcons.ChevronLeft, "前移", canMoveBackward, onMoveBackward)
        PageActionButton(TablerIcons.ChevronRight, "后移", canMoveForward, onMoveForward)
        PageActionButton(TablerIcons.ArrowsLeftRight, "移动到", enabled, onMoveTo)
        PageActionButton(TablerIcons.FileImport, "导入", enabled, onImportPages)
        PageActionButton(TablerIcons.RotateClockwise2, "旋转", enabled, onRotate)
        PageActionButton(TablerIcons.Crop, "裁剪", enabled, onCrop)
        PageActionButton(TablerIcons.AspectRatio, "尺寸", enabled, onResize)
        PageActionButton(TablerIcons.FileExport, "拆分", enabled, onSplit)
        PageActionButton(
            icon = TablerIcons.Trash,
            label = "删除",
            enabled = canDelete,
            onClick = onDelete,
            destructive = true,
        )
    }
}

@Composable
private fun PageActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        colors = if (destructive) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            ButtonDefaults.filledTonalButtonColors()
        },
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, modifier = Modifier.padding(start = 6.dp))
    }
}
