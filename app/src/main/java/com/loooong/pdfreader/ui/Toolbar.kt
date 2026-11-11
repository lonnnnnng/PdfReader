package com.loooong.pdfreader.ui

import com.loooong.pdfreader.PdfPermissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowBackUp
import compose.icons.tablericons.ArrowForwardUp
import compose.icons.tablericons.AspectRatio
import compose.icons.tablericons.ChevronLeft
import compose.icons.tablericons.ChevronRight
import compose.icons.tablericons.Copy
import compose.icons.tablericons.DeviceFloppy
import compose.icons.tablericons.Dots
import compose.icons.tablericons.Edit
import compose.icons.tablericons.FileExport
import compose.icons.tablericons.FileInfo
import compose.icons.tablericons.Folder
import compose.icons.tablericons.FileText
import compose.icons.tablericons.Maximize
import compose.icons.tablericons.Minimize
import compose.icons.tablericons.Plus
import compose.icons.tablericons.RotateClockwise2
import compose.icons.tablericons.Settings

class AnnotationToolbarActions(
    val canAnnotate: Boolean,
    val canSign: Boolean,
    val canRedact: Boolean,
    val canDelete: Boolean,
    val canEdit: Boolean,
    val addHighlight: () -> Unit,
    val addUnderline: () -> Unit,
    val addStrikeout: () -> Unit,
    val addNote: () -> Unit,
    val addFreeText: () -> Unit,
    val addStamp: () -> Unit,
    val addSignature: () -> Unit,
    val redact: () -> Unit,
    val edit: () -> Unit,
    val delete: () -> Unit,
)

class DocumentToolbarActions(
    val permissions: PdfPermissions,
    val hasFormFields: Boolean,
    val hasDigitalSignatures: Boolean,
    val openMetadata: () -> Unit,
    val openOutline: () -> Unit,
    val openLinks: () -> Unit,
    val openAttachments: () -> Unit,
    val openDigitalSignatures: () -> Unit,
    val signWithCertificate: () -> Unit,
    val openSecurity: () -> Unit,
    val openOcr: () -> Unit,
    val openCompression: () -> Unit,
    val openPageDecorations: () -> Unit,
    val shareDocument: () -> Unit,
    val printDocument: () -> Unit,
    val createFormField: () -> Unit,
    val openForms: () -> Unit,
)

@Composable
fun InspectorToolbar(
    fileName: String,
    fullscreen: Boolean,
    pageIndex: Int,
    pageCount: Int,
    canDeletePage: Boolean,
    canMovePageBackward: Boolean,
    canMovePageForward: Boolean,
    dirty: Boolean,
    canOverwrite: Boolean,
    documentActions: DocumentToolbarActions,
    canUndo: Boolean,
    canRedo: Boolean,
    canCopy: Boolean,
    onCopyElement: () -> Unit,
    canPasteElement: Boolean,
    onPasteElement: () -> Unit,
    onPasteText: () -> Unit,
    onInsertText: () -> Unit,
    onInsertImage: () -> Unit,
    onInsertRectangle: () -> Unit,
    onInsertLine: () -> Unit,
    onFitWidth: () -> Unit,
    onFitHeight: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onDuplicatePage: () -> Unit,
    onMovePageBackward: () -> Unit,
    onMovePageForward: () -> Unit,
    onDeletePage: () -> Unit,
    onRotatePage: () -> Unit,
    onManagePages: () -> Unit,
    annotationActions: AnnotationToolbarActions,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
    ) {
        BoxWithConstraints {
            val compact = maxWidth < 720.dp
            Row(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp, end = 4.dp),
                )
                ToolbarActions(
                    compact = compact,
                    fullscreen = fullscreen,
                    pageIndex = pageIndex,
                    pageCount = pageCount,
                    canDeletePage = canDeletePage,
                    canMovePageBackward = canMovePageBackward,
                    canMovePageForward = canMovePageForward,
                    dirty = dirty,
                    canOverwrite = canOverwrite,
                    documentActions = documentActions,
                    canUndo = canUndo,
                    canRedo = canRedo,
                    canCopy = canCopy,
                    onCopyElement = onCopyElement,
                    canPasteElement = canPasteElement,
                    onPasteElement = onPasteElement,
                    onPasteText = onPasteText,
                    onInsertText = onInsertText,
                    onInsertImage = onInsertImage,
                    onInsertRectangle = onInsertRectangle,
                    onInsertLine = onInsertLine,
                    onFitWidth = onFitWidth,
                    onFitHeight = onFitHeight,
                    onToggleFullscreen = onToggleFullscreen,
                    onPrev = onPrev,
                    onNext = onNext,
                    onDuplicatePage = onDuplicatePage,
                    onMovePageBackward = onMovePageBackward,
                    onMovePageForward = onMovePageForward,
                    onDeletePage = onDeletePage,
                    onRotatePage = onRotatePage,
                    onManagePages = onManagePages,
                    annotationActions = annotationActions,
                    onUndo = onUndo,
                    onRedo = onRedo,
                    onOpen = onOpen,
                    onSave = onSave,
                    onSaveAs = onSaveAs,
                    onSettings = onSettings,
                )
            }
        }
    }
}

@Composable
private fun ToolbarActions(
    compact: Boolean,
    fullscreen: Boolean,
    pageIndex: Int,
    pageCount: Int,
    canDeletePage: Boolean,
    canMovePageBackward: Boolean,
    canMovePageForward: Boolean,
    dirty: Boolean,
    canOverwrite: Boolean,
    documentActions: DocumentToolbarActions,
    canUndo: Boolean,
    canRedo: Boolean,
    canCopy: Boolean,
    onCopyElement: () -> Unit,
    canPasteElement: Boolean,
    onPasteElement: () -> Unit,
    onPasteText: () -> Unit,
    onInsertText: () -> Unit,
    onInsertImage: () -> Unit,
    onInsertRectangle: () -> Unit,
    onInsertLine: () -> Unit,
    onFitWidth: () -> Unit,
    onFitHeight: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onDuplicatePage: () -> Unit,
    onMovePageBackward: () -> Unit,
    onMovePageForward: () -> Unit,
    onDeletePage: () -> Unit,
    onRotatePage: () -> Unit,
    onManagePages: () -> Unit,
    annotationActions: AnnotationToolbarActions,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onSettings: () -> Unit,
) {
    if (compact) {
        CompactToolbarActions(
            fullscreen = fullscreen,
            pageIndex = pageIndex,
            pageCount = pageCount,
            canDeletePage = canDeletePage,
            canMovePageBackward = canMovePageBackward,
            canMovePageForward = canMovePageForward,
            dirty = dirty,
            canOverwrite = canOverwrite,
            documentActions = documentActions,
            canUndo = canUndo,
            canRedo = canRedo,
            canCopy = canCopy,
            onCopyElement = onCopyElement,
            canPasteElement = canPasteElement,
            onPasteElement = onPasteElement,
            onPasteText = onPasteText,
            onInsertText = onInsertText,
            onInsertImage = onInsertImage,
            onInsertRectangle = onInsertRectangle,
            onInsertLine = onInsertLine,
            onFitWidth = onFitWidth,
            onFitHeight = onFitHeight,
            onToggleFullscreen = onToggleFullscreen,
            onPrev = onPrev,
            onNext = onNext,
            onDuplicatePage = onDuplicatePage,
            onMovePageBackward = onMovePageBackward,
            onMovePageForward = onMovePageForward,
            onDeletePage = onDeletePage,
            onRotatePage = onRotatePage,
            onManagePages = onManagePages,
            annotationActions = annotationActions,
            onUndo = onUndo,
            onRedo = onRedo,
            onOpen = onOpen,
            onSave = onSave,
            onSaveAs = onSaveAs,
            onSettings = onSettings,
        )
        return
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (canCopy) {
            IconButton(onClick = onCopyElement) {
                Icon(TablerIcons.Copy, "复制元素", Modifier.size(20.dp))
            }
        }
        InsertMenuButton(
            enabled = documentActions.permissions.content,
            canPasteElement = canPasteElement,
            onPasteElement = onPasteElement,
            onPasteText = onPasteText,
            onInsertText = onInsertText,
            onInsertImage = onInsertImage,
            onInsertRectangle = onInsertRectangle,
            onInsertLine = onInsertLine,
        )
        IconButton(
            onClick = onSave,
            enabled = dirty,
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(
                TablerIcons.DeviceFloppy,
                if (canOverwrite) "保存修改" else "保存副本",
                Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onSaveAs) {
            Icon(TablerIcons.FileExport, "另存为", Modifier.size(20.dp))
        }
        IconButton(onClick = onOpen) {
            Icon(TablerIcons.Folder, "打开 PDF", Modifier.size(20.dp))
        }
        DocumentMenuButton(documentActions)
        ToolDivider()
        IconButton(onClick = onPrev, enabled = pageIndex > 0) {
            Icon(TablerIcons.ChevronLeft, "上一页", Modifier.size(20.dp))
        }
        PageIndicator(pageIndex, pageCount)
        IconButton(onClick = onNext, enabled = pageIndex < pageCount - 1) {
            Icon(TablerIcons.ChevronRight, "下一页", Modifier.size(20.dp))
        }
        PageMenuButton(
            enabled = documentActions.permissions.assemble,
            canDeletePage = canDeletePage,
            canMovePageBackward = canMovePageBackward,
            canMovePageForward = canMovePageForward,
            onDuplicatePage = onDuplicatePage,
            onMovePageBackward = onMovePageBackward,
            onMovePageForward = onMovePageForward,
            onDeletePage = onDeletePage,
            onManagePages = onManagePages,
        )
        IconButton(onClick = onRotatePage, enabled = documentActions.permissions.assemble) {
            Icon(TablerIcons.RotateClockwise2, "顺时针旋转页面", Modifier.size(20.dp))
        }
        AnnotationMenuButton(
            actions = annotationActions,
        )
        ToolDivider()
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(TablerIcons.ArrowBackUp, "撤销", Modifier.size(20.dp))
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(TablerIcons.ArrowForwardUp, "重做", Modifier.size(20.dp))
        }
        ToolDivider()
        IconButton(onClick = onToggleFullscreen) {
            Icon(
                imageVector = if (fullscreen) TablerIcons.Minimize else TablerIcons.Maximize,
                contentDescription = if (fullscreen) "退出全屏" else "进入全屏",
                modifier = Modifier.size(20.dp),
                tint = if (fullscreen) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FitMenuButton(onFitWidth = onFitWidth, onFitHeight = onFitHeight)
        IconButton(onClick = onSettings) {
            Icon(TablerIcons.Settings, "设置", Modifier.size(20.dp))
        }
    }
}

@Composable
private fun CompactToolbarActions(
    fullscreen: Boolean,
    pageIndex: Int,
    pageCount: Int,
    canDeletePage: Boolean,
    canMovePageBackward: Boolean,
    canMovePageForward: Boolean,
    dirty: Boolean,
    canOverwrite: Boolean,
    documentActions: DocumentToolbarActions,
    canUndo: Boolean,
    canRedo: Boolean,
    canCopy: Boolean,
    onCopyElement: () -> Unit,
    canPasteElement: Boolean,
    onPasteElement: () -> Unit,
    onPasteText: () -> Unit,
    onInsertText: () -> Unit,
    onInsertImage: () -> Unit,
    onInsertRectangle: () -> Unit,
    onInsertLine: () -> Unit,
    onFitWidth: () -> Unit,
    onFitHeight: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onDuplicatePage: () -> Unit,
    onMovePageBackward: () -> Unit,
    onMovePageForward: () -> Unit,
    onDeletePage: () -> Unit,
    onRotatePage: () -> Unit,
    onManagePages: () -> Unit,
    annotationActions: AnnotationToolbarActions,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onSave,
            enabled = dirty,
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(
                TablerIcons.DeviceFloppy,
                if (canOverwrite) "保存修改" else "保存副本",
                Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onPrev, enabled = pageIndex > 0) {
            Icon(TablerIcons.ChevronLeft, "上一页", Modifier.size(20.dp))
        }
        PageIndicator(pageIndex, pageCount)
        IconButton(onClick = onNext, enabled = pageIndex < pageCount - 1) {
            Icon(TablerIcons.ChevronRight, "下一页", Modifier.size(20.dp))
        }
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(TablerIcons.Dots, "更多操作", Modifier.size(20.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                fun run(action: () -> Unit) {
                    expanded = false
                    action()
                }
                DropdownMenuItem(text = { Text("打开 PDF") }, onClick = { run(onOpen) })
                DropdownMenuItem(text = { Text("另存为") }, onClick = { run(onSaveAs) })
                DropdownMenuItem(text = { Text("文档属性") }, enabled = documentActions.permissions.content, onClick = { run(documentActions.openMetadata) })
                DropdownMenuItem(text = { Text("编辑 PDF 目录") }, enabled = documentActions.permissions.content, onClick = { run(documentActions.openOutline) })
                DropdownMenuItem(text = { Text("管理 PDF 链接") }, enabled = documentActions.permissions.annotate, onClick = { run(documentActions.openLinks) })
                DropdownMenuItem(text = { Text("管理 PDF 附件") }, enabled = documentActions.permissions.content, onClick = { run(documentActions.openAttachments) })
                if (documentActions.hasDigitalSignatures) {
                    DropdownMenuItem(
                        text = { Text("查看数字签名") },
                        onClick = { run(documentActions.openDigitalSignatures) },
                    )
                }
                DropdownMenuItem(
                    text = { Text("使用数字证书签名") },
                    enabled = documentActions.permissions.fillForm,
                    onClick = { run(documentActions.signWithCertificate) },
                )
                DropdownMenuItem(text = { Text("密码与权限") }, enabled = documentActions.permissions.owner, onClick = { run(documentActions.openSecurity) })
                DropdownMenuItem(text = { Text("OCR 文字识别") }, enabled = documentActions.permissions.content, onClick = { run(documentActions.openOcr) })
                DropdownMenuItem(text = { Text("压缩 PDF") }, enabled = documentActions.permissions.content, onClick = { run(documentActions.openCompression) })
                DropdownMenuItem(text = { Text("水印与页码") }, enabled = documentActions.permissions.content, onClick = { run(documentActions.openPageDecorations) })
                DropdownMenuItem(text = { Text("创建表单字段") }, enabled = documentActions.permissions.designForm, onClick = { run(documentActions.createFormField) })
                if (documentActions.hasFormFields) {
                    DropdownMenuItem(text = { Text("填写 PDF 表单") }, enabled = documentActions.permissions.fillForm, onClick = { run(documentActions.openForms) })
                }
                if (canCopy) {
                    DropdownMenuItem(text = { Text("复制所选元素") }, onClick = { run(onCopyElement) })
                }
                DropdownMenuItem(text = { Text("插入文本") }, enabled = documentActions.permissions.content, onClick = { run(onInsertText) })
                DropdownMenuItem(text = { Text("插入图片") }, enabled = documentActions.permissions.content, onClick = { run(onInsertImage) })
                DropdownMenuItem(text = { Text("插入矩形") }, enabled = documentActions.permissions.content, onClick = { run(onInsertRectangle) })
                DropdownMenuItem(text = { Text("插入直线") }, enabled = documentActions.permissions.content, onClick = { run(onInsertLine) })
                if (canPasteElement) {
                    DropdownMenuItem(text = { Text("粘贴元素") }, onClick = { run(onPasteElement) })
                }
                DropdownMenuItem(text = { Text("粘贴剪贴板文本") }, enabled = documentActions.permissions.content, onClick = { run(onPasteText) })
                DropdownMenuItem(text = { Text("撤销") }, enabled = canUndo, onClick = { run(onUndo) })
                DropdownMenuItem(text = { Text("重做") }, enabled = canRedo, onClick = { run(onRedo) })
                DropdownMenuItem(text = { Text("适合宽度") }, onClick = { run(onFitWidth) })
                DropdownMenuItem(text = { Text("适合高度") }, onClick = { run(onFitHeight) })
                DropdownMenuItem(text = { Text("顺时针旋转页面") }, enabled = documentActions.permissions.assemble, onClick = { run(onRotatePage) })
                DropdownMenuItem(text = { Text("管理页面") }, enabled = documentActions.permissions.assemble, onClick = { run(onManagePages) })
                DropdownMenuItem(text = { Text("复制当前页面") }, enabled = documentActions.permissions.assemble, onClick = { run(onDuplicatePage) })
                DropdownMenuItem(
                    text = { Text("页面上移") },
                    enabled = canMovePageBackward,
                    onClick = { run(onMovePageBackward) },
                )
                DropdownMenuItem(
                    text = { Text("页面下移") },
                    enabled = canMovePageForward,
                    onClick = { run(onMovePageForward) },
                )
                DropdownMenuItem(
                    text = { Text("删除当前页面") },
                    enabled = canDeletePage,
                    onClick = { run(onDeletePage) },
                )
                DropdownMenuItem(
                    text = { Text("添加高亮批注") },
                    enabled = annotationActions.canAnnotate,
                    onClick = { run(annotationActions.addHighlight) },
                )
                DropdownMenuItem(
                    text = { Text("添加下划线") },
                    enabled = annotationActions.canAnnotate,
                    onClick = { run(annotationActions.addUnderline) },
                )
                DropdownMenuItem(
                    text = { Text("添加删除线") },
                    enabled = annotationActions.canAnnotate,
                    onClick = { run(annotationActions.addStrikeout) },
                )
                DropdownMenuItem(
                    text = { Text("添加便签") },
                    enabled = annotationActions.canAnnotate,
                    onClick = { run(annotationActions.addNote) },
                )
                DropdownMenuItem(
                    text = { Text("添加自由文本") },
                    enabled = annotationActions.canAnnotate,
                    onClick = { run(annotationActions.addFreeText) },
                )
                DropdownMenuItem(
                    text = { Text("添加图章") },
                    enabled = annotationActions.canAnnotate,
                    onClick = { run(annotationActions.addStamp) },
                )
                DropdownMenuItem(
                    text = { Text("添加手写签名") },
                    enabled = annotationActions.canSign,
                    onClick = { run(annotationActions.addSignature) },
                )
                DropdownMenuItem(
                    text = { Text("永久遮盖所选内容") },
                    enabled = annotationActions.canRedact,
                    onClick = { run(annotationActions.redact) },
                )
                DropdownMenuItem(
                    text = { Text("编辑所选批注") },
                    enabled = annotationActions.canEdit,
                    onClick = { run(annotationActions.edit) },
                )
                DropdownMenuItem(
                    text = { Text("删除所选批注") },
                    enabled = annotationActions.canDelete,
                    onClick = { run(annotationActions.delete) },
                )
                DropdownMenuItem(
                    text = { Text(if (fullscreen) "退出全屏" else "进入全屏") },
                    onClick = { run(onToggleFullscreen) },
                )
                DropdownMenuItem(text = { Text("设置") }, onClick = { run(onSettings) })
            }
        }
    }
}

@Composable
private fun DocumentMenuButton(actions: DocumentToolbarActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(TablerIcons.FileInfo, "文档工具", Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            fun run(action: () -> Unit) {
                expanded = false
                action()
            }
            DropdownMenuItem(text = { Text("文档属性") }, enabled = actions.permissions.content, onClick = { run(actions.openMetadata) })
            DropdownMenuItem(text = { Text("编辑 PDF 目录") }, enabled = actions.permissions.content, onClick = { run(actions.openOutline) })
            DropdownMenuItem(text = { Text("管理 PDF 链接") }, enabled = actions.permissions.annotate, onClick = { run(actions.openLinks) })
            DropdownMenuItem(text = { Text("管理 PDF 附件") }, enabled = actions.permissions.content, onClick = { run(actions.openAttachments) })
            if (actions.hasDigitalSignatures) {
                DropdownMenuItem(
                    text = { Text("查看数字签名") },
                    onClick = { run(actions.openDigitalSignatures) },
                )
            }
            DropdownMenuItem(
                text = { Text("使用数字证书签名") },
                enabled = actions.permissions.fillForm,
                onClick = { run(actions.signWithCertificate) },
            )
            DropdownMenuItem(text = { Text("密码与权限") }, enabled = actions.permissions.owner, onClick = { run(actions.openSecurity) })
            DropdownMenuItem(text = { Text("OCR 文字识别") }, enabled = actions.permissions.content, onClick = { run(actions.openOcr) })
            DropdownMenuItem(text = { Text("压缩 PDF") }, enabled = actions.permissions.content, onClick = { run(actions.openCompression) })
            DropdownMenuItem(text = { Text("水印与页码") }, enabled = actions.permissions.content, onClick = { run(actions.openPageDecorations) })
            DropdownMenuItem(text = { Text("分享 PDF") }, onClick = { run(actions.shareDocument) })
            DropdownMenuItem(text = { Text("打印 PDF") }, enabled = actions.permissions.print, onClick = { run(actions.printDocument) })
            DropdownMenuItem(text = { Text("创建表单字段") }, enabled = actions.permissions.designForm, onClick = { run(actions.createFormField) })
            if (actions.hasFormFields) {
                DropdownMenuItem(text = { Text("填写 PDF 表单") }, enabled = actions.permissions.fillForm, onClick = { run(actions.openForms) })
            }
        }
    }
}

@Composable
private fun PageMenuButton(
    enabled: Boolean,
    canDeletePage: Boolean,
    canMovePageBackward: Boolean,
    canMovePageForward: Boolean,
    onDuplicatePage: () -> Unit,
    onMovePageBackward: () -> Unit,
    onMovePageForward: () -> Unit,
    onDeletePage: () -> Unit,
    onManagePages: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(TablerIcons.FileText, "页面操作", Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            fun run(action: () -> Unit) {
                expanded = false
                action()
            }
            DropdownMenuItem(text = { Text("管理页面") }, onClick = { run(onManagePages) })
            DropdownMenuItem(text = { Text("复制当前页面") }, onClick = { run(onDuplicatePage) })
            DropdownMenuItem(
                text = { Text("页面上移") },
                enabled = canMovePageBackward,
                onClick = { run(onMovePageBackward) },
            )
            DropdownMenuItem(
                text = { Text("页面下移") },
                enabled = canMovePageForward,
                onClick = { run(onMovePageForward) },
            )
            DropdownMenuItem(
                text = { Text("删除当前页面") },
                enabled = canDeletePage,
                onClick = { run(onDeletePage) },
            )
        }
    }
}

@Composable
private fun AnnotationMenuButton(
    actions: AnnotationToolbarActions,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = actions.canAnnotate || actions.canSign || actions.canRedact ||
                actions.canDelete || actions.canEdit,
        ) {
            Icon(TablerIcons.Edit, "批注与遮盖", Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("添加高亮批注") },
                enabled = actions.canAnnotate,
                onClick = { expanded = false; actions.addHighlight() },
            )
            DropdownMenuItem(
                text = { Text("添加下划线") },
                enabled = actions.canAnnotate,
                onClick = { expanded = false; actions.addUnderline() },
            )
            DropdownMenuItem(
                text = { Text("添加删除线") },
                enabled = actions.canAnnotate,
                onClick = { expanded = false; actions.addStrikeout() },
            )
            DropdownMenuItem(
                text = { Text("添加便签") },
                enabled = actions.canAnnotate,
                onClick = { expanded = false; actions.addNote() },
            )
            DropdownMenuItem(
                text = { Text("添加自由文本") },
                enabled = actions.canAnnotate,
                onClick = { expanded = false; actions.addFreeText() },
            )
            DropdownMenuItem(
                text = { Text("添加图章") },
                enabled = actions.canAnnotate,
                onClick = { expanded = false; actions.addStamp() },
            )
            DropdownMenuItem(
                text = { Text("添加手写签名") },
                enabled = actions.canSign,
                onClick = { expanded = false; actions.addSignature() },
            )
            DropdownMenuItem(
                text = { Text("永久遮盖所选内容") },
                enabled = actions.canRedact,
                onClick = { expanded = false; actions.redact() },
            )
            DropdownMenuItem(
                text = { Text("编辑所选批注") },
                enabled = actions.canEdit,
                onClick = { expanded = false; actions.edit() },
            )
            DropdownMenuItem(
                text = { Text("删除所选批注") },
                enabled = actions.canDelete,
                onClick = { expanded = false; actions.delete() },
            )
        }
    }
}

@Composable
private fun InsertMenuButton(
    enabled: Boolean,
    canPasteElement: Boolean,
    onPasteElement: () -> Unit,
    onPasteText: () -> Unit,
    onInsertText: () -> Unit,
    onInsertImage: () -> Unit,
    onInsertRectangle: () -> Unit,
    onInsertLine: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(TablerIcons.Plus, "插入内容", Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            fun run(action: () -> Unit) {
                expanded = false
                action()
            }
            DropdownMenuItem(text = { Text("插入文本") }, onClick = { run(onInsertText) })
            DropdownMenuItem(text = { Text("插入图片") }, onClick = { run(onInsertImage) })
            DropdownMenuItem(text = { Text("插入矩形") }, onClick = { run(onInsertRectangle) })
            DropdownMenuItem(text = { Text("插入直线") }, onClick = { run(onInsertLine) })
            if (canPasteElement) {
                DropdownMenuItem(text = { Text("粘贴元素") }, onClick = { run(onPasteElement) })
            }
            DropdownMenuItem(text = { Text("粘贴剪贴板文本") }, onClick = { run(onPasteText) })
        }
    }
}

@Composable
private fun PageIndicator(pageIndex: Int, pageCount: Int) {
    Text(
        text = "${pageIndex + 1} / $pageCount",
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .semantics { contentDescription = "第 ${pageIndex + 1} 页，共 $pageCount 页" },
    )
}

@Composable
private fun FitMenuButton(onFitWidth: () -> Unit, onFitHeight: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(TablerIcons.AspectRatio, "页面适配", Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("适合宽度") },
                onClick = { expanded = false; onFitWidth() },
            )
            DropdownMenuItem(
                text = { Text("适合高度") },
                onClick = { expanded = false; onFitHeight() },
            )
        }
    }
}

@Composable
private fun ToolDivider() {
    VerticalDivider(
        modifier = Modifier
            .height(28.dp)
            .padding(horizontal = 4.dp),
    )
}
