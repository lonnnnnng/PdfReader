package com.loooong.reader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.print.PrintManager
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import compose.icons.TablerIcons
import compose.icons.tablericons.FileText
import compose.icons.tablericons.Book
import compose.icons.tablericons.ChevronRight
import compose.icons.tablericons.Edit
import compose.icons.tablericons.FileExport
import compose.icons.tablericons.History
import compose.icons.tablericons.Settings
import compose.icons.tablericons.Trash
import com.loooong.reader.engine.NodeKind
import com.loooong.reader.engine.ShapeKind
import com.loooong.reader.engine.Bounds
import com.loooong.reader.engine.ElementSnapper
import com.loooong.reader.engine.SnapGuide
import com.loooong.reader.engine.SnapResult
import com.loooong.reader.engine.TextMarkupKind
import com.loooong.reader.engine.StampKind
import com.loooong.reader.engine.PdfFormFieldCreateRequest
import com.loooong.reader.engine.PdfDigitalSignRequest
import com.loooong.reader.engine.findNode
import com.loooong.reader.ui.AnnotationEditDialog
import com.loooong.reader.ui.AnnotationToolbarActions
import com.loooong.reader.ui.Dock
import com.loooong.reader.ui.DocumentToolbarActions
import com.loooong.reader.ui.ElementEditSheet
import com.loooong.reader.ui.FitMode
import com.loooong.reader.ui.InspectorDock
import com.loooong.reader.ui.InspectorPane
import com.loooong.reader.ui.InspectorToolbar
import com.loooong.reader.ui.LeafRect
import com.loooong.reader.ui.ObjectTransformOverlay
import com.loooong.reader.ui.PdfCanvas
import com.loooong.reader.ui.PdfFormDialog
import com.loooong.reader.ui.PdfFormFieldCreateDialog
import com.loooong.reader.ui.PdfCompressionDialog
import com.loooong.reader.ui.PdfAttachmentDialog
import com.loooong.reader.ui.PdfMetadataDialog
import com.loooong.reader.ui.PdfOutlineDialog
import com.loooong.reader.ui.PdfOcrDialog
import com.loooong.reader.ui.PdfLinkDialog
import com.loooong.reader.ui.PdfPageDecorationDialog
import com.loooong.reader.ui.PdfSecurityDialog
import com.loooong.reader.ui.PdfDigitalSignatureDialog
import com.loooong.reader.ui.PdfDigitalSignDialog
import com.loooong.reader.ui.PageManagerDialog
import com.loooong.reader.ui.PageCropDialog
import com.loooong.reader.ui.PageSizeDialog
import com.loooong.reader.ui.PageSplitDialog
import com.loooong.reader.ui.ReaderScreen
import com.loooong.reader.ui.SettingsScreen
import com.loooong.reader.ui.SignaturePadDialog
import com.loooong.reader.ui.theme.InspectorTheme
import com.loooong.reader.ui.theme.ThemeState
import com.loooong.reader.ui.theme.rememberThemeState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialUri: Uri? = if (intent?.action == Intent.ACTION_VIEW) intent?.data else null
        setContent {
            val themeState = rememberThemeState()
            InspectorTheme(
                mode = themeState.mode,
                dynamicColor = themeState.dynamic,
                accent = themeState.accent,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    InspectorScreen(initialUri = initialUri, themeState = themeState)
                }
            }
        }
    }
}

@Composable
fun InspectorScreen(
    initialUri: Uri? = null,
    themeState: ThemeState,
    viewModel: PdfDocumentViewModel = viewModel(),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val state = viewModel.state
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showExitConfirmation by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    var showDeletePageConfirmation by rememberSaveable { mutableStateOf(false) }
    var showDeleteAnnotationConfirmation by rememberSaveable { mutableStateOf(false) }
    var pendingRedactionIds by remember { mutableStateOf(emptySet<Int>()) }
    var pendingMode by rememberSaveable { mutableStateOf(AppMode.EDIT) }
    var pendingLeaveTarget by rememberSaveable { mutableStateOf<PdfLeaveTarget?>(null) }
    var saveLeaveTarget by rememberSaveable { mutableStateOf<PdfLeaveTarget?>(null) }
    var showOverwriteConflict by rememberSaveable { mutableStateOf(false) }
    var overwriteConflictTarget by rememberSaveable { mutableStateOf<PdfLeaveTarget?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var showInsertTextDialog by rememberSaveable { mutableStateOf(false) }
    var insertTextValue by rememberSaveable { mutableStateOf("") }
    var showAnnotationNoteDialog by rememberSaveable { mutableStateOf(false) }
    var annotationNoteValue by rememberSaveable { mutableStateOf("") }
    var showAnnotationFreeTextDialog by rememberSaveable { mutableStateOf(false) }
    var annotationFreeTextValue by rememberSaveable { mutableStateOf("") }
    var showAnnotationEditDialog by rememberSaveable { mutableStateOf(false) }
    var showStampDialog by rememberSaveable { mutableStateOf(false) }
    var showSignatureDialog by rememberSaveable { mutableStateOf(false) }
    var showFormDialog by rememberSaveable { mutableStateOf(false) }
    var showCreateFormFieldDialog by rememberSaveable { mutableStateOf(false) }
    var pendingFormFieldDeletion by remember { mutableStateOf<com.loooong.reader.engine.PdfFormFieldInfo?>(null) }
    var showMetadataDialog by rememberSaveable { mutableStateOf(false) }
    var showOutlineDialog by rememberSaveable { mutableStateOf(false) }
    var showLinkDialog by rememberSaveable { mutableStateOf(false) }
    var showAttachmentDialog by rememberSaveable { mutableStateOf(false) }
    var showSecurityDialog by rememberSaveable { mutableStateOf(false) }
    var showDigitalSignatureDetails by rememberSaveable { mutableStateOf(false) }
    var showDigitalSignDialog by rememberSaveable { mutableStateOf(false) }
    var signingCertificateUri by remember { mutableStateOf<Uri?>(null) }
    var signingCertificateFileName by remember { mutableStateOf<String?>(null) }
    var pendingDigitalSignRequest by remember { mutableStateOf<PdfDigitalSignRequest?>(null) }
    var pendingDigitalSignPassword by remember { mutableStateOf<CharArray?>(null) }
    var showOcrDialog by rememberSaveable { mutableStateOf(false) }
    var showCompressionDialog by rememberSaveable { mutableStateOf(false) }
    var showPageDecorationDialog by rememberSaveable { mutableStateOf(false) }
    var showPageManager by rememberSaveable { mutableStateOf(false) }
    var showPageCrop by rememberSaveable { mutableStateOf(false) }
    var pageCropTargetIndices by rememberSaveable { mutableStateOf(arrayListOf<Int>()) }
    var pageCropDocumentToken by rememberSaveable { mutableStateOf<Int?>(null) }
    var showPageSplit by rememberSaveable { mutableStateOf(false) }
    var pageSizeTargetIndices by rememberSaveable { mutableStateOf(arrayListOf<Int>()) }
    var pageSizeDocumentToken by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingSplitRangeBounds by rememberSaveable { mutableStateOf(arrayListOf<Int>()) }
    var pendingSplitDocumentToken by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingExportPageIndices by rememberSaveable { mutableStateOf(arrayListOf<Int>()) }
    var pendingExportDocumentToken by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingDeletePageIndices by rememberSaveable { mutableStateOf(arrayListOf<Int>()) }
    var pendingDeleteDocumentToken by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingPageImportAfterIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingPageImportDocumentToken by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingMergeUris by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var pendingAttachmentExportKey by rememberSaveable { mutableStateOf<String?>(null) }
    var warnedSignatureSessionId by rememberSaveable { mutableStateOf<Int?>(null) }
    var showDigitalSignatureWarning by rememberSaveable { mutableStateOf(false) }
    var pageTransferIssueTitle by remember { mutableStateOf<String?>(null) }
    var pageTransferIssueMessages by remember { mutableStateOf(emptyList<String>()) }
    var pdfPassword by remember { mutableStateOf("") }

    fun clearDigitalSignFlow() {
        pendingDigitalSignPassword?.fill('\u0000')
        pendingDigitalSignPassword = null
        pendingDigitalSignRequest = null
        signingCertificateUri = null
        signingCertificateFileName = null
        showDigitalSignDialog = false
        viewModel.clearSigningCertificateInspection()
    }

    LaunchedEffect(viewModel.passwordPrompt?.requestId) {
        if (viewModel.passwordPrompt != null) pdfPassword = ""
    }

    LaunchedEffect(showDigitalSignatureDetails, state.documentToken) {
        if (showDigitalSignatureDetails && state.hasDocument) {
            viewModel.inspectDigitalSignatures()
        }
    }

    LaunchedEffect(state.hasDocument) {
        if (!state.hasDocument && showDigitalSignatureDetails) {
            showDigitalSignatureDetails = false
            viewModel.clearDigitalSignatureInspection()
        }
    }

    LaunchedEffect(state.documentSessionId) {
        // long: 文件切换后旧证书选择和待签署参数不能继续作用到新文档，避免把签名落到错误文件。
        clearDigitalSignFlow()
    }

    LaunchedEffect(
        state.documentSessionId,
        state.digitalSignatureCount,
        state.digitalSignatureInspectionFailed,
        state.mode,
    ) {
        if (
            state.hasDocument && state.mode == AppMode.EDIT &&
            (state.digitalSignatureCount > 0 || state.digitalSignatureInspectionFailed) &&
            warnedSignatureSessionId != state.documentSessionId
        ) {
            // long: 每次打开签名文档只提示一次；后续页面重排会更新 documentToken，但不应反复打断当前编辑。
            warnedSignatureSessionId = state.documentSessionId
            showDigitalSignatureWarning = true
        }
    }

    // long: 删除确认绑定发起时的页码与文档版本，避免弹窗停留期间把旧选择应用到已经变化的页面结构。
    fun requestPageDeletion(pageIndices: Set<Int>) {
        if (pageIndices.isEmpty()) return
        pendingDeletePageIndices = ArrayList(pageIndices.sorted())
        pendingDeleteDocumentToken = state.documentToken
        showDeletePageConfirmation = true
    }

    fun dismissPageDeletion() {
        showDeletePageConfirmation = false
        pendingDeletePageIndices = arrayListOf()
        pendingDeleteDocumentToken = null
    }

    fun openEbookReader() {
        context.startActivity(Intent(context, EbookActivity::class.java))
    }

    LaunchedEffect(initialUri) {
        viewModel.loadReaderLibrary(context)
        if (initialUri != null) viewModel.open(context, initialUri, AppMode.READ)
    }

    val openLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.open(context, it, pendingMode) } }

    fun executeLeave(target: PdfLeaveTarget) {
        when (target) {
            PdfLeaveTarget.CLOSE_DOCUMENT -> {
                fullscreen = false
                viewModel.closeDocument()
            }
            PdfLeaveTarget.OPEN_EDIT_DOCUMENT -> {
                pendingMode = AppMode.EDIT
                openLauncher.launch(arrayOf("application/pdf"))
            }
            PdfLeaveTarget.OPEN_READ_DOCUMENT -> {
                pendingMode = AppMode.READ
                openLauncher.launch(arrayOf("application/pdf"))
            }
        }
    }

    fun requestLeave(target: PdfLeaveTarget) {
        if (state.busy != null) return
        when (val decision = decidePdfLeave(state.dirty, target)) {
            is PdfLeaveDecision.Proceed -> executeLeave(decision.target)
            is PdfLeaveDecision.Confirm -> pendingLeaveTarget = decision.target
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val target = saveLeaveTarget
        saveLeaveTarget = null
        if (uri != null) {
            viewModel.saveCopy(context, uri) { saved ->
                if (target != null) {
                    when (val decision = resolvePdfLeaveSave(target, saved)) {
                        is PdfLeaveDecision.Proceed -> {
                            pendingLeaveTarget = null
                            executeLeave(decision.target)
                        }
                        is PdfLeaveDecision.Confirm -> pendingLeaveTarget = decision.target
                    }
                }
            }
        }
    }
    val pageExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val pageIndices = pendingExportPageIndices.toSet()
        val documentToken = pendingExportDocumentToken
        pendingExportPageIndices = arrayListOf()
        pendingExportDocumentToken = null
        if (uri != null && pageIndices.isNotEmpty() && documentToken == state.documentToken) {
            viewModel.exportPages(context, uri, pageIndices)
        } else if (uri != null && documentToken != state.documentToken) {
            Toast.makeText(context, "文档内容已变化，请重新选择页面", Toast.LENGTH_LONG).show()
        }
    }
    val pageImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val afterIndex = pendingPageImportAfterIndex
        val documentToken = pendingPageImportDocumentToken
        pendingPageImportAfterIndex = null
        pendingPageImportDocumentToken = null
        if (uri != null && afterIndex != null && documentToken == state.documentToken) {
            viewModel.importPages(context, uri, afterIndex)
        } else if (uri != null && documentToken != state.documentToken) {
            Toast.makeText(context, "文档内容已变化，请重新选择导入位置", Toast.LENGTH_LONG).show()
        }
    }
    val mergeOutputLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
    ) { targetUri ->
        val sourceUris = pendingMergeUris.map(Uri::parse)
        pendingMergeUris = arrayListOf()
        if (targetUri != null && sourceUris.size >= 2) {
            viewModel.mergeDocuments(context, sourceUris, targetUri)
        }
    }
    val mergeInputLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.size >= 2) {
            pendingMergeUris = ArrayList(uris.map(Uri::toString))
            mergeOutputLauncher.launch("合并文档.pdf")
        } else if (uris.isNotEmpty()) {
            Toast.makeText(context, "至少选择两个 PDF 才能合并", Toast.LENGTH_SHORT).show()
        }
    }
    val splitFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val ranges = pendingSplitRangeBounds.chunked(2).mapNotNull { pair ->
            pair.takeIf { it.size == 2 }?.let { it[0]..it[1] }
        }
        val documentToken = pendingSplitDocumentToken
        pendingSplitRangeBounds = arrayListOf()
        pendingSplitDocumentToken = null
        if (uri != null && ranges.isNotEmpty() && documentToken == state.documentToken) {
            viewModel.splitDocument(context, uri, ranges)
        } else if (uri != null && documentToken != state.documentToken) {
            Toast.makeText(context, "文档内容已变化，请重新设置拆分范围", Toast.LENGTH_LONG).show()
        }
    }
    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.insertImage(context, it) } }

    val attachmentAddLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.addDocumentAttachment(context, it) }
    }
    val attachmentExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val key = pendingAttachmentExportKey
        pendingAttachmentExportKey = null
        if (uri != null && key != null) {
            viewModel.exportDocumentAttachment(context, key, uri)
        }
    }
    val signingCertificateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            signingCertificateUri = uri
            signingCertificateFileName = queryDocumentDisplayName(context, uri)
            viewModel.clearSigningCertificateInspection()
        }
    }
    val digitalSignOutputLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
    ) { targetUri ->
        val request = pendingDigitalSignRequest
        val password = pendingDigitalSignPassword
        val certificateUri = signingCertificateUri
        pendingDigitalSignRequest = null
        pendingDigitalSignPassword = null
        if (targetUri == null || request == null || password == null || certificateUri == null) {
            password?.fill('\u0000')
        } else {
            viewModel.signDocumentWithCertificate(
                context = context,
                targetUri = targetUri,
                certificateUri = certificateUri,
                certificatePassword = password,
                request = request,
            ) { result ->
                if (result != null) clearDigitalSignFlow()
            }
        }
    }

    fun pickPdf(mode: AppMode) {
        requestLeave(
            if (mode == AppMode.EDIT) {
                PdfLeaveTarget.OPEN_EDIT_DOCUMENT
            } else {
                PdfLeaveTarget.OPEN_READ_DOCUMENT
            },
        )
    }

    fun shareCurrentPdf() {
        viewModel.prepareExternalPdf(context) { file, _ ->
            if (file == null) return@prepareExternalPdf
            runCatching {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = android.content.ClipData.newRawUri("PDF", uri)
                }
                context.startActivity(Intent.createChooser(intent, "分享 PDF"))
            }.onFailure {
                runCatching { file.delete() }
                Toast.makeText(context, "没有可用的分享应用", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun printCurrentPdf() {
        viewModel.prepareExternalPdf(context) { file, pageCount ->
            if (file == null) return@prepareExternalPdf
            runCatching {
                val printManager = context.getSystemService(PrintManager::class.java)
                    ?: error("系统没有可用的打印服务")
                printManager.print(
                    state.fileName.ifBlank { "文档.pdf" },
                    PdfFilePrintAdapter(file, state.fileName.ifBlank { "文档.pdf" }, pageCount),
                    null,
                )
            }.onFailure {
                runCatching { file.delete() }
                Toast.makeText(context, it.message ?: "无法打开系统打印服务", Toast.LENGTH_LONG).show()
            }
        }
    }

    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val sideDockSupported = configuration.screenWidthDp >= 600
    var dock by remember(landscape, sideDockSupported) {
        mutableStateOf(if (landscape && sideDockSupported) Dock.SIDE else Dock.BOTTOM)
    }
    var transparent by rememberSaveable { mutableStateOf(false) }
    // long: 手机横屏改用更紧凑的底部面板范围，为画布和工具栏保留基本可操作高度。
    val minBottomHeight = if (landscape) 96.dp else 180.dp
    val maxBottomHeight = (
        configuration.screenHeightDp * if (landscape) 0.45f else 0.55f
    ).dp.coerceAtLeast(minBottomHeight)
    val minSideWidth = 220.dp
    val maxSideWidth = minOf(
        (configuration.screenWidthDp * 0.50f).dp,
        configuration.screenWidthDp.dp - 280.dp - 40.dp,
    ).coerceAtLeast(minSideWidth)
    var bottomHeight by remember(configuration.screenHeightDp, landscape) {
        mutableStateOf((configuration.screenHeightDp * 0.34f).dp.coerceIn(minBottomHeight, maxBottomHeight))
    }
    var sideWidth by remember(configuration.screenWidthDp) {
        mutableStateOf((configuration.screenWidthDp * 0.36f).dp.coerceIn(minSideWidth, maxSideWidth))
    }
    val density = LocalDensity.current
    val sizeDp = if (dock == Dock.BOTTOM) bottomHeight else sideWidth
    val onResize: (Float) -> Unit = { delta ->
        if (dock == Dock.BOTTOM) {
            bottomHeight = with(density) { (bottomHeight.toPx() - delta).toDp() }
                .coerceIn(minBottomHeight, maxBottomHeight)
        } else {
            sideWidth = with(density) { (sideWidth.toPx() - delta).toDp() }
                .coerceIn(minSideWidth, maxSideWidth)
        }
    }

    var fitMode by remember { mutableStateOf(FitMode.WIDTH) }
    LaunchedEffect(state.documentToken) { fitMode = FitMode.WIDTH }

    LaunchedEffect(fullscreen) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (fullscreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // long: 宿主组合销毁时主动恢复系统栏，避免下次进入页面继承残留的沉浸状态。
            (context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler(enabled = showSettings) {
        showSettings = false
    }

    BackHandler(enabled = !showSettings && state.hasDocument) {
        if (state.busy != null) return@BackHandler
        if (fullscreen) {
            fullscreen = false
        } else {
            requestLeave(PdfLeaveTarget.CLOSE_DOCUMENT)
        }
    }

    // 首页已经没有更上一层页面，系统返回手势在真正结束 Activity 前必须再次确认。
    BackHandler(enabled = !showSettings && !state.hasDocument) {
        showExitConfirmation = true
    }

    if (showSettings) {
        SettingsScreen(
            theme = themeState,
            onBack = { showSettings = false },
        )
    } else if (state.hasDocument && state.mode == AppMode.READ) {
        val hasPdfAttachments = remember(state.documentToken) {
            viewModel.documentAttachments().isNotEmpty()
        }
        key(state.documentToken) {
            ReaderScreen(
                viewModel = viewModel,
                state = state,
                fullscreen = fullscreen,
                onToggleFullscreen = { fullscreen = !fullscreen },
                onClose = {
                    fullscreen = false
                    requestLeave(PdfLeaveTarget.CLOSE_DOCUMENT)
                },
                onOpen = { pickPdf(AppMode.READ) },
                hasAttachments = hasPdfAttachments,
                onAttachments = { showAttachmentDialog = true },
                hasDigitalSignatures = state.digitalSignatureCount > 0 ||
                    state.digitalSignatureInspectionFailed,
                onDigitalSignatures = { showDigitalSignatureDetails = true },
                onShare = ::shareCurrentPdf,
                onPrint = ::printCurrentPdf,
                onSettings = {
                    fullscreen = false
                    showSettings = true
                },
            )
        }
        state.busy?.let { BusyOverlay(it) }
    } else if (state.hasDocument) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    InspectorToolbar(
                        fileName = state.fileName,
                        fullscreen = fullscreen,
                        pageIndex = state.pageIndex,
                        pageCount = state.pageCount,
                        canDeletePage = state.pageCount > 1,
                        canMovePageBackward = state.pageIndex > 0,
                        canMovePageForward = state.pageIndex < state.pageCount - 1,
                        dirty = state.dirty,
                        canOverwrite = state.canOverwrite,
                        documentActions = DocumentToolbarActions(
                            hasFormFields = viewModel.formFields.isNotEmpty(),
                            hasDigitalSignatures = state.digitalSignatureCount > 0 ||
                                state.digitalSignatureInspectionFailed,
                            openMetadata = { showMetadataDialog = true },
                            openOutline = { showOutlineDialog = true },
                            openLinks = { showLinkDialog = true },
                            openAttachments = { showAttachmentDialog = true },
                            openDigitalSignatures = { showDigitalSignatureDetails = true },
                            signWithCertificate = { showDigitalSignDialog = true },
                            openSecurity = { showSecurityDialog = true },
                            openOcr = { showOcrDialog = true },
                            openCompression = { showCompressionDialog = true },
                            openPageDecorations = { showPageDecorationDialog = true },
                            shareDocument = ::shareCurrentPdf,
                            printDocument = ::printCurrentPdf,
                            createFormField = { showCreateFormFieldDialog = true },
                            openForms = { showFormDialog = true },
                        ),
                        canUndo = state.canUndo,
                        canRedo = state.canRedo,
                        canCopy = state.selectedIds.isNotEmpty(),
                        onCopyElement = { viewModel.copySelectedElement(context) },
                        canPasteElement = state.hasElementClipboard,
                        onPasteElement = { viewModel.pasteElement(context) },
                        onPasteText = { viewModel.pasteText(context) },
                        onInsertText = {
                            insertTextValue = ""
                            showInsertTextDialog = true
                        },
                        onInsertImage = { imageLauncher.launch(arrayOf("image/*")) },
                        onInsertRectangle = { viewModel.insertShape(context, ShapeKind.RECTANGLE) },
                        onInsertLine = { viewModel.insertShape(context, ShapeKind.LINE) },
                        onFitWidth = { fitMode = FitMode.WIDTH },
                        onFitHeight = { fitMode = FitMode.HEIGHT },
                        onToggleFullscreen = { fullscreen = !fullscreen },
                        onPrev = { viewModel.showPage(state.pageIndex - 1) },
                        onNext = { viewModel.showPage(state.pageIndex + 1) },
                        onDuplicatePage = { viewModel.duplicateCurrentPage(context) },
                        onMovePageBackward = { viewModel.moveCurrentPage(context, -1) },
                        onMovePageForward = { viewModel.moveCurrentPage(context, 1) },
                        onDeletePage = { requestPageDeletion(setOf(state.pageIndex)) },
                        onRotatePage = { viewModel.rotateCurrentPage(context) },
                        onManagePages = { showPageManager = true },
                        annotationActions = AnnotationToolbarActions(
                            canAnnotate = state.selectedIds.size == 1,
                            canSign = true,
                            canRedact = viewModel.canRedactSelection(),
                            canDelete = state.annotations.firstOrNull {
                                it.index == state.selectedAnnotationIndex
                            }?.editable == true,
                            canEdit = state.annotations.firstOrNull {
                                it.index == state.selectedAnnotationIndex
                            }?.editable == true,
                            addHighlight = { viewModel.addHighlightToSelection(context) },
                            addUnderline = {
                                viewModel.addTextMarkupToSelection(context, TextMarkupKind.UNDERLINE)
                            },
                            addStrikeout = {
                                viewModel.addTextMarkupToSelection(context, TextMarkupKind.STRIKEOUT)
                            },
                            addNote = {
                                annotationNoteValue = ""
                                showAnnotationNoteDialog = true
                            },
                            addFreeText = {
                                annotationFreeTextValue = ""
                                showAnnotationFreeTextDialog = true
                            },
                            addStamp = { showStampDialog = true },
                            addSignature = { showSignatureDialog = true },
                            redact = { pendingRedactionIds = state.selectedIds.toSet() },
                            edit = { showAnnotationEditDialog = true },
                            delete = { showDeleteAnnotationConfirmation = true },
                        ),
                        onUndo = { viewModel.undo() },
                        onRedo = { viewModel.redo() },
                        onOpen = { pickPdf(AppMode.EDIT) },
                        onSave = {
                            if (state.canOverwrite) {
                                viewModel.saveOriginal(context) { outcome ->
                                    when (outcome) {
                                        PdfSaveOutcome.SAVED -> Unit
                                        PdfSaveOutcome.FAILED -> saveLauncher.launch("已编辑.pdf")
                                        PdfSaveOutcome.CONFLICT -> {
                                            overwriteConflictTarget = null
                                            showOverwriteConflict = true
                                        }
                                    }
                                }
                            } else {
                                saveLauncher.launch("已编辑.pdf")
                            }
                        },
                        onSaveAs = { saveLauncher.launch("已编辑.pdf") },
                        onSettings = {
                            fullscreen = false
                            showSettings = true
                        },
                    )
                },
            ) { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(inner),
                ) {
                    Workspace(
                        viewModel = viewModel,
                        state = state,
                        dock = dock,
                        transparent = transparent,
                        sizeDp = sizeDp,
                        canDockSide = sideDockSupported,
                        onResize = onResize,
                        fitMode = fitMode,
                        onUserTransform = { fitMode = FitMode.NONE },
                        onToggleDock = {
                            dock = if (dock == Dock.BOTTOM && sideDockSupported) Dock.SIDE else Dock.BOTTOM
                        },
                        onToggleTransparent = {
                            transparent = !transparent
                            fitMode = FitMode.NONE
                        },
                        onDelete = { showDeleteConfirmation = true },
                    )
                }
            }
            state.busy?.let { message ->
                BusyOverlay(
                    message = message,
                    onCancel = when {
                        message.contains("OCR") -> viewModel::cancelOcr
                        message.contains("压缩") -> viewModel::cancelCompression
                        else -> null
                    },
                )
            }
        }
    } else {
        HomeScreen(
            loading = state.loading,
            error = state.error,
            recovery = viewModel.recoveryInfo,
            history = viewModel.readerHistory,
            onOpenEdit = { pickPdf(AppMode.EDIT) },
            onOpenRead = { pickPdf(AppMode.READ) },
            onOpenEbook = ::openEbookReader,
            onMergePdf = { mergeInputLauncher.launch(arrayOf("application/pdf")) },
            onOpenHistory = { viewModel.openHistory(context, it) },
            onRemoveHistory = { viewModel.removeReaderHistory(context, it) },
            onResumeRecovery = { viewModel.resumeRecovery(context) },
            onDiscardRecovery = { viewModel.discardRecovery(context) },
            onSettings = { showSettings = true },
        )
    }

    viewModel.passwordPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = {
                pdfPassword = ""
                viewModel.dismissPdfPassword()
            },
            title = { Text("输入 PDF 密码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("“${prompt.title}”受密码保护，输入密码后继续打开。")
                    OutlinedTextField(
                        value = pdfPassword,
                        onValueChange = { pdfPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("密码") },
                        singleLine = true,
                        isError = prompt.error != null,
                        supportingText = prompt.error?.let { error -> { Text(error) } },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (pdfPassword.isNotEmpty()) {
                                    viewModel.submitPdfPassword(context, pdfPassword)
                                }
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pdfPassword = ""
                        viewModel.dismissPdfPassword()
                    },
                ) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    enabled = pdfPassword.isNotEmpty(),
                    onClick = { viewModel.submitPdfPassword(context, pdfPassword) },
                ) { Text("打开") }
            },
        )
    }

    if (
        showDigitalSignatureWarning && state.hasDocument &&
        (state.digitalSignatureCount > 0 || state.digitalSignatureInspectionFailed)
    ) {
        AlertDialog(
            onDismissRequest = { showDigitalSignatureWarning = false },
            title = {
                Text(if (state.digitalSignatureInspectionFailed) "无法确认数字签名状态" else "数字签名将失效")
            },
            text = {
                Text(
                    if (state.digitalSignatureInspectionFailed) {
                        "当前 PDF 的签名字段结构异常，应用无法安全确认签名状态。为保护原文件，" +
                            "已禁用覆盖保存；继续编辑时请使用另存为。"
                    } else {
                        "检测到 ${state.digitalSignatureCount} 个数字签名。继续编辑并保存会使原有签名验证失效，" +
                            "应用已禁用覆盖原文件，请使用另存为保留原始签名文档。"
                    },
                )
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDigitalSignatureWarning = false
                        viewModel.switchMode(AppMode.READ)
                    },
                ) { Text("返回阅读模式") }
            },
            confirmButton = {
                TextButton(onClick = { showDigitalSignatureWarning = false }) {
                    Text("仍然编辑")
                }
            },
        )
    }

    if (!showSettings && state.mode == AppMode.EDIT && state.editingId != null) {
        val target = remember(state.editingId, state.page, state.fontCatalogTick) {
            viewModel.editTarget()
        }
        if (target != null) {
            ElementEditSheet(
                target = target,
                onApply = { req, replacementUri ->
                    viewModel.applyEdit(context, req, replacementUri)
                },
                onImportFont = { uri -> viewModel.importFont(context, uri) },
                onDismiss = { viewModel.cancelEdit() },
            )
        }
    }

    if (showFormDialog && state.mode == AppMode.EDIT && viewModel.formFields.isNotEmpty()) {
        PdfFormDialog(
            fields = viewModel.formFields,
            busy = state.busy != null,
            onApply = { updates ->
                viewModel.updateFormFields(context, updates) { success ->
                    if (success) showFormDialog = false
                }
            },
            onDelete = { pendingFormFieldDeletion = it },
            onGoToPage = { pageIndex ->
                showFormDialog = false
                viewModel.showPage(pageIndex)
            },
            onDismiss = { showFormDialog = false },
        )
    }

    pendingFormFieldDeletion?.let { field ->
        AlertDialog(
            onDismissRequest = { if (state.busy == null) pendingFormFieldDeletion = null },
            title = { Text("删除表单字段？") },
            text = { Text("将删除“${field.label}”及其页面控件，此操作可以使用撤销恢复。") },
            dismissButton = {
                TextButton(
                    enabled = state.busy == null,
                    onClick = { pendingFormFieldDeletion = null },
                ) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    enabled = state.busy == null,
                    onClick = {
                        viewModel.deleteFormField(context, field.index) { success ->
                            if (success) {
                                pendingFormFieldDeletion = null
                                Toast.makeText(context, "表单字段已删除", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
        )
    }

    if (showCreateFormFieldDialog && state.mode == AppMode.EDIT && state.hasDocument) {
        PdfFormFieldCreateDialog(
            busy = state.busy != null,
            usesSelectionBounds = state.selectedIds.size == 1,
            onCreate = { kind, name, label, required, multiline, options ->
                val bounds = viewModel.defaultFormFieldBounds(kind)
                if (bounds == null) {
                    Toast.makeText(context, "无法确定字段位置", Toast.LENGTH_LONG).show()
                } else {
                    viewModel.createFormField(
                        context = context,
                        request = PdfFormFieldCreateRequest(
                            kind = kind,
                            name = name,
                            label = label,
                            pageIndex = state.pageIndex,
                            bounds = bounds,
                            required = required,
                            multiline = multiline,
                            options = options,
                        ),
                    ) { success ->
                        if (success) showCreateFormFieldDialog = false
                    }
                }
            },
            onDismiss = { showCreateFormFieldDialog = false },
        )
    }

    if (showMetadataDialog && state.mode == AppMode.EDIT && state.hasDocument) {
        val metadata = remember(state.documentToken) { viewModel.documentMetadata() }
        if (metadata != null) {
            PdfMetadataDialog(
                metadata = metadata,
                busy = state.busy != null,
                onApply = { updated ->
                    viewModel.updateDocumentMetadata(context, updated) { success ->
                        if (success) showMetadataDialog = false
                    }
                },
                onDismiss = { showMetadataDialog = false },
            )
        }
    }

    if (showOutlineDialog && state.mode == AppMode.EDIT && state.hasDocument) {
        val entries = remember(state.documentToken) { viewModel.documentOutlineEntries() }
        PdfOutlineDialog(
            entries = entries,
            pageCount = state.pageCount,
            currentPageIndex = state.pageIndex,
            busy = state.busy != null,
            onAdd = { title, pageIndex, parentId ->
                viewModel.addDocumentOutline(context, title, pageIndex, parentId)
            },
            onUpdate = { id, title, pageIndex ->
                viewModel.updateDocumentOutline(context, id, title, pageIndex)
            },
            onMove = { id, direction ->
                viewModel.moveDocumentOutline(context, id, direction)
            },
            onDelete = { id -> viewModel.deleteDocumentOutline(context, id) },
            onDismiss = { showOutlineDialog = false },
        )
    }

    if (showLinkDialog && state.mode == AppMode.EDIT && state.hasDocument) {
        val links = remember(state.documentToken) { viewModel.documentLinks() }
        key(state.documentToken) {
            PdfLinkDialog(
                links = links,
                pageCount = state.pageCount,
                currentPageIndex = state.pageIndex,
                defaultBounds = viewModel.defaultLinkBounds(),
                busy = state.busy != null,
                onCreate = { request, onComplete ->
                    viewModel.createLink(context, request, onComplete)
                },
                onUpdate = { id, request, onComplete ->
                    viewModel.updateLink(context, id, request, onComplete)
                },
                onDelete = { id, onComplete ->
                    viewModel.deleteLink(context, id, onComplete)
                },
                onGoToPage = { pageIndex ->
                    viewModel.showPage(pageIndex)
                },
                onDismiss = { showLinkDialog = false },
            )
        }
    }

    if (showAttachmentDialog && state.hasDocument) {
        val attachments = remember(state.documentToken) { viewModel.documentAttachments() }
        key(state.documentToken) {
            PdfAttachmentDialog(
                attachments = attachments,
                busy = state.busy != null,
                editable = state.mode == AppMode.EDIT,
                onAdd = { attachmentAddLauncher.launch(arrayOf("*/*")) },
                onExport = { attachment ->
                    pendingAttachmentExportKey = attachment.key
                    attachmentExportLauncher.launch(attachment.fileName)
                },
                onRename = { key, fileName, description ->
                    viewModel.renameDocumentAttachment(context, key, fileName, description)
                },
                onDelete = { key -> viewModel.deleteDocumentAttachment(context, key) },
                onDismiss = { showAttachmentDialog = false },
            )
        }
    }

    if (showDigitalSignatureDetails && state.hasDocument) {
        PdfDigitalSignatureDialog(
            report = viewModel.digitalSignatureReport,
            loading = viewModel.digitalSignatureReportLoading,
            error = viewModel.digitalSignatureReportError,
            onRefresh = viewModel::inspectDigitalSignatures,
            onDismiss = {
                showDigitalSignatureDetails = false
                viewModel.clearDigitalSignatureInspection()
            },
        )
    }

    if (showDigitalSignDialog && state.mode == AppMode.EDIT && state.hasDocument) {
        PdfDigitalSignDialog(
            certificateFileName = signingCertificateFileName,
            certificateInfo = viewModel.signingCertificateInfo,
            certificateLoading = viewModel.signingCertificateLoading,
            certificateError = viewModel.signingCertificateError,
            pageCount = state.pageCount,
            currentPageIndex = state.pageIndex,
            busy = state.busy != null,
            onChooseCertificate = {
                signingCertificateLauncher.launch(arrayOf("*/*"))
            },
            onInspectCertificate = { password ->
                val certificateUri = signingCertificateUri
                if (certificateUri == null) {
                    Toast.makeText(context, "请先选择 PKCS#12 / PFX 证书", Toast.LENGTH_LONG).show()
                } else {
                    viewModel.inspectSigningCertificate(context, certificateUri, password)
                }
            },
            onSign = { request, password ->
                pendingDigitalSignPassword?.fill('\u0000')
                pendingDigitalSignRequest = request
                pendingDigitalSignPassword = password.toCharArray()
                val baseName = state.fileName.substringBeforeLast('.').ifBlank { "PDF" }
                runCatching {
                    digitalSignOutputLauncher.launch("$baseName-已签名.pdf")
                }.onFailure {
                    pendingDigitalSignPassword?.fill('\u0000')
                    pendingDigitalSignPassword = null
                    pendingDigitalSignRequest = null
                    Toast.makeText(context, "无法打开签名文件保存位置，请重试", Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = ::clearDigitalSignFlow,
        )
    }

    if (showSecurityDialog && state.mode == AppMode.EDIT && state.hasDocument) {
        val securityInfo = remember(state.documentToken) { viewModel.documentSecurityInfo() }
        if (securityInfo != null) {
            PdfSecurityDialog(
                info = securityInfo,
                digitalSignatureCount = state.digitalSignatureCount,
                digitalSignatureInspectionFailed = state.digitalSignatureInspectionFailed,
                busy = state.busy != null,
                onApply = { settings ->
                    viewModel.protectDocument(context, settings) { success ->
                        if (success) showSecurityDialog = false
                    }
                },
                onRemove = {
                    viewModel.removeDocumentProtection(context) { success ->
                        if (success) showSecurityDialog = false
                    }
                },
                onDismiss = { showSecurityDialog = false },
            )
        }
    }

    if (showOcrDialog && state.mode == AppMode.EDIT && state.hasDocument) {
        PdfOcrDialog(
            pageCount = state.pageCount,
            currentPageIndex = state.pageIndex,
            busy = state.busy != null,
            onApply = { request ->
                viewModel.applyOcr(
                    context = context,
                    pageIndices = request.pageIndices,
                    skipExistingText = request.skipExistingText,
                ) { success ->
                    if (success) showOcrDialog = false
                }
            },
            onDismiss = { showOcrDialog = false },
        )
    }

    if (showCompressionDialog && state.mode == AppMode.EDIT && state.hasDocument) {
        PdfCompressionDialog(
            busy = state.busy != null,
            onApply = { preset ->
                viewModel.compressDocument(context, preset) { report ->
                    if (report != null) showCompressionDialog = false
                }
            },
            onDismiss = { showCompressionDialog = false },
        )
    }

    if (showPageDecorationDialog && state.mode == AppMode.EDIT && state.hasDocument) {
        PdfPageDecorationDialog(
            pageCount = state.pageCount,
            currentPageIndex = state.pageIndex,
            busy = state.busy != null,
            onApply = { request ->
                viewModel.applyPageDecorations(context, request) { success ->
                    if (success) {
                        showPageDecorationDialog = false
                        Toast.makeText(context, "水印与页码已应用", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { showPageDecorationDialog = false },
        )
    }

    // long: documentToken 变化代表文档或页面结构已经改变，重建工作台可防止旧页码选择误用于新页面。
    if (
        showPageManager && !showPageCrop && !showPageSplit && pageSizeTargetIndices.isEmpty() &&
        state.mode == AppMode.EDIT && state.hasDocument
    ) {
        key(state.documentToken) {
            PageManagerDialog(
                pages = viewModel.readerState.pageInfos,
                thumbnails = viewModel.readerThumbnails,
                selectedPageIndex = state.pageIndex,
                busy = state.busy != null ||
                    pendingExportPageIndices.isNotEmpty() ||
                    pendingPageImportDocumentToken != null ||
                    pendingSplitDocumentToken != null,
                onLoadThumbnail = viewModel::ensureReaderThumbnail,
                onSelectPage = viewModel::showPage,
                onInsertBefore = { viewModel.insertBlankPage(context, state.pageIndex - 1) },
                onInsertAfter = { viewModel.insertBlankPage(context, state.pageIndex) },
                onDuplicate = { viewModel.duplicateCurrentPage(context) },
                onDuplicatePages = { pageIndices -> viewModel.duplicatePages(context, pageIndices) },
                onMoveBackward = { viewModel.moveCurrentPage(context, -1) },
                onMoveForward = { viewModel.moveCurrentPage(context, 1) },
                onMovePagesBackward = { pageIndices -> viewModel.movePages(context, pageIndices, -1) },
                onMovePagesForward = { pageIndices -> viewModel.movePages(context, pageIndices, 1) },
                onMoveTo = { sourceIndex, targetIndex ->
                    viewModel.movePage(context, sourceIndex, targetIndex)
                },
                onImportPages = {
                    // long: 文件选择器返回前冻结插入位置和文档版本，避免页面重排后把外部 PDF 插入错误位置。
                    if (pendingPageImportDocumentToken == null) {
                        pendingPageImportAfterIndex = state.pageIndex
                        pendingPageImportDocumentToken = state.documentToken
                        runCatching {
                            pageImportLauncher.launch(arrayOf("application/pdf"))
                        }.onFailure {
                            pendingPageImportAfterIndex = null
                            pendingPageImportDocumentToken = null
                            Toast.makeText(context, "无法打开 PDF 选择器，请重试", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onRotate = { viewModel.rotateCurrentPage(context) },
                onCrop = {
                    pageCropTargetIndices = arrayListOf(state.pageIndex)
                    pageCropDocumentToken = state.documentToken
                    showPageCrop = true
                },
                onResize = {
                    pageSizeTargetIndices = arrayListOf(state.pageIndex)
                    pageSizeDocumentToken = state.documentToken
                },
                onSplit = { showPageSplit = true },
                onExportPages = { pageIndices ->
                    val issues = viewModel.pageTransferIssues(pageIndices)
                    if (issues.isNotEmpty()) {
                        pageTransferIssueTitle = "无法安全导出所选页面"
                        pageTransferIssueMessages = issues.map { it.message }
                    } else {
                        // long: 预检通过后再打开 SAF；选择器返回前保留页码与文档版本，避免输出到已经变化的文档。
                        if (pendingExportPageIndices.isEmpty()) {
                            pendingExportPageIndices = ArrayList(pageIndices.sorted())
                            pendingExportDocumentToken = state.documentToken
                            val baseName = state.fileName.substringBeforeLast('.').ifBlank { "PDF" }
                            runCatching {
                                pageExportLauncher.launch("$baseName-选中页面.pdf")
                            }.onFailure {
                                pendingExportPageIndices = arrayListOf()
                                pendingExportDocumentToken = null
                                Toast.makeText(context, "无法打开保存位置，请重试", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                onRotatePages = { pageIndices -> viewModel.rotatePages(context, pageIndices) },
                onCropPages = { pageIndices ->
                    pageCropTargetIndices = ArrayList(pageIndices.sorted())
                    pageCropDocumentToken = state.documentToken
                    showPageCrop = true
                },
                onResizePages = { pageIndices ->
                    pageSizeTargetIndices = ArrayList(pageIndices.sorted())
                    pageSizeDocumentToken = state.documentToken
                },
                onDeletePages = ::requestPageDeletion,
                onDelete = { requestPageDeletion(setOf(state.pageIndex)) },
                onDismiss = { showPageManager = false },
            )
        }
    }

    if (showPageCrop && state.mode == AppMode.EDIT && state.hasDocument) {
        val targets = pageCropTargetIndices.toSet()
        val geometry = remember(state.documentToken, targets) {
            targets.firstOrNull()?.let(viewModel::pageGeometry)
        }
        if (geometry != null && targets.isNotEmpty()) {
            PageCropDialog(
                pageNumber = targets.singleOrNull()?.plus(1) ?: 0,
                pageCount = targets.size,
                geometry = geometry,
                busy = state.busy != null,
                onApply = { margins ->
                    val documentToken = pageCropDocumentToken
                    showPageCrop = false
                    pageCropTargetIndices = arrayListOf()
                    pageCropDocumentToken = null
                    if (documentToken == state.documentToken) {
                        viewModel.cropPages(context, targets, margins)
                    } else {
                        Toast.makeText(context, "文档内容已变化，请重新选择页面", Toast.LENGTH_LONG).show()
                    }
                },
                onReset = {
                    val documentToken = pageCropDocumentToken
                    showPageCrop = false
                    pageCropTargetIndices = arrayListOf()
                    pageCropDocumentToken = null
                    if (documentToken == state.documentToken) {
                        viewModel.resetCropPages(context, targets)
                    } else {
                        Toast.makeText(context, "文档内容已变化，请重新选择页面", Toast.LENGTH_LONG).show()
                    }
                },
                onDismiss = {
                    showPageCrop = false
                    pageCropTargetIndices = arrayListOf()
                    pageCropDocumentToken = null
                },
            )
        }
    }

    if (pageSizeTargetIndices.isNotEmpty() && state.mode == AppMode.EDIT && state.hasDocument) {
        val geometry = remember(state.documentToken, pageSizeTargetIndices) {
            pageSizeTargetIndices.firstOrNull()?.let(viewModel::pageGeometry)
        }
        if (geometry != null) {
            PageSizeDialog(
                pageCount = pageSizeTargetIndices.size,
                initialWidth = geometry.mediaWidth,
                initialHeight = geometry.mediaHeight,
                busy = state.busy != null,
                onApply = { width, height ->
                    val targets = pageSizeTargetIndices.toSet()
                    val documentToken = pageSizeDocumentToken
                    pageSizeTargetIndices = arrayListOf()
                    pageSizeDocumentToken = null
                    if (documentToken == state.documentToken) {
                        viewModel.resizePages(context, targets, width, height)
                    } else {
                        Toast.makeText(context, "文档内容已变化，请重新选择页面", Toast.LENGTH_LONG).show()
                    }
                },
                onDismiss = {
                    pageSizeTargetIndices = arrayListOf()
                    pageSizeDocumentToken = null
                },
            )
        }
    }

    if (showPageSplit && state.mode == AppMode.EDIT && state.hasDocument) {
        PageSplitDialog(
            pageCount = state.pageCount,
            busy = state.busy != null,
            onApply = { ranges ->
                showPageSplit = false
                val issues = viewModel.pageTransferIssues(ranges)
                if (issues.isNotEmpty()) {
                    pageTransferIssueTitle = "无法安全拆分 PDF"
                    pageTransferIssueMessages = issues.map { it.message }
                } else {
                    pendingSplitRangeBounds = ArrayList(ranges.flatMap { listOf(it.first, it.last) })
                    pendingSplitDocumentToken = state.documentToken
                    runCatching { splitFolderLauncher.launch(null) }.onFailure {
                        pendingSplitRangeBounds = arrayListOf()
                        pendingSplitDocumentToken = null
                        Toast.makeText(context, "无法打开文件夹选择器，请重试", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { showPageSplit = false },
        )
    }

    pageTransferIssueTitle?.let { title ->
        AlertDialog(
            onDismissRequest = {
                pageTransferIssueTitle = null
                pageTransferIssueMessages = emptyList()
            },
            title = { Text(title) },
            text = {
                Text(
                    pageTransferIssueMessages.joinToString("\n\n") { message -> "• $message" },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pageTransferIssueTitle = null
                        pageTransferIssueMessages = emptyList()
                    },
                ) { Text("我知道了") }
            },
        )
    }

    if (showExitConfirmation) {
        AlertDialog(
            onDismissRequest = { showExitConfirmation = false },
            title = { Text("退出阅读？") },
            text = { Text("确定要退出应用吗？") },
            dismissButton = {
                TextButton(onClick = { showExitConfirmation = false }) {
                    Text("取消")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirmation = false
                        (context as? Activity)?.finish()
                    },
                ) {
                    Text("退出")
                }
            },
        )
    }

    // long: 覆盖冲突弹窗优先级更高，避免离开确认和冲突确认同时渲染导致双 AlertDialog。
    pendingLeaveTarget?.takeIf { !showOverwriteConflict }?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingLeaveTarget = null },
            title = { Text("保存当前修改？") },
            text = {
                Text(
                    if (target == PdfLeaveTarget.CLOSE_DOCUMENT) {
                        if (state.canOverwrite) {
                            "当前 PDF 有尚未保存的修改，关闭前可以保存到原文件。"
                        } else {
                            "当前 PDF 有尚未保存的修改，关闭前可以先保存副本。"
                        }
                    } else {
                        if (state.canOverwrite) {
                            "当前 PDF 有尚未保存的修改，打开其他文件前可以保存到原文件。"
                        } else {
                            "当前 PDF 有尚未保存的修改，打开其他文件前可以先保存副本。"
                        }
                    },
                )
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { pendingLeaveTarget = null }) {
                        Text("取消")
                    }
                    TextButton(
                        onClick = {
                            pendingLeaveTarget = null
                            executeLeave(target)
                        },
                    ) {
                        Text("不保存")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (state.canOverwrite) {
                            viewModel.saveOriginal(context) { outcome ->
                                when (outcome) {
                                    PdfSaveOutcome.SAVED -> {
                                        pendingLeaveTarget = null
                                        executeLeave(target)
                                    }
                                    PdfSaveOutcome.FAILED -> {
                                        saveLeaveTarget = target
                                        saveLauncher.launch("已编辑.pdf")
                                    }
                                    PdfSaveOutcome.CONFLICT -> {
                                        overwriteConflictTarget = target
                                        showOverwriteConflict = true
                                    }
                                }
                            }
                        } else {
                            saveLeaveTarget = target
                            saveLauncher.launch("已编辑.pdf")
                        }
                    },
                ) {
                    Text(if (state.canOverwrite) "保存" else "保存副本")
                }
            },
        )
    }

    if (showOverwriteConflict) {
        AlertDialog(
            onDismissRequest = {
                showOverwriteConflict = false
                overwriteConflictTarget = null
            },
            title = { Text("原文件已发生变化") },
            text = {
                Text(
                    "打开 PDF 后，原文件的内容已经被其他应用修改，继续覆盖可能丢失对方的改动。" +
                        "建议另存为新文件；如果确认要舍弃外部改动，也可以强制覆盖。",
                )
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showOverwriteConflict = false
                        overwriteConflictTarget = null
                    },
                ) { Text("取消") }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            val target = overwriteConflictTarget
                            showOverwriteConflict = false
                            overwriteConflictTarget = null
                            if (target != null) saveLeaveTarget = target
                            saveLauncher.launch("已编辑.pdf")
                        },
                    ) { Text("另存为") }
                    TextButton(
                        onClick = {
                            val target = overwriteConflictTarget
                            showOverwriteConflict = false
                            overwriteConflictTarget = null
                            viewModel.saveOriginal(context, forceOverwrite = true) { outcome ->
                                when (outcome) {
                                    PdfSaveOutcome.SAVED -> {
                                        if (target != null) {
                                            pendingLeaveTarget = null
                                            executeLeave(target)
                                        }
                                    }
                                    PdfSaveOutcome.FAILED -> {
                                        if (target == null) saveLauncher.launch("已编辑.pdf")
                                    }
                                    PdfSaveOutcome.CONFLICT -> {
                                        overwriteConflictTarget = target
                                        showOverwriteConflict = true
                                    }
                                }
                            }
                        },
                    ) { Text("强制覆盖") }
                }
            },
        )
    }

    if (showDeleteConfirmation) {
        val selectedLabel = if (state.selectedIds.size > 1) {
            "${state.selectedIds.size} 个元素"
        } else {
            state.page
                ?.let { findNode(it.root, state.selectedId) }
                ?.label
                ?.takeIf { it.isNotBlank() }
                ?: "当前元素"
        }
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("删除元素？") },
            text = { Text("将删除“$selectedLabel”，此操作可以使用撤销恢复。") },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.deleteSelected(context)
                    },
                ) { Text("删除") }
            },
        )
    }

    if (showDeletePageConfirmation) {
        val pageIndices = pendingDeletePageIndices.toSet()
        val pageCount = pageIndices.size
        val singlePageNumber = pageIndices.singleOrNull()?.plus(1)
        // long: 最终确认前再次校验文档版本和页码范围，并始终保留至少一页，防止陈旧弹窗破坏 PDF 页面树。
        val deletionStillValid = pendingDeleteDocumentToken == state.documentToken &&
            pageIndices.isNotEmpty() &&
            pageIndices.all { it in 0 until state.pageCount } &&
            pageCount < state.pageCount
        AlertDialog(
            onDismissRequest = ::dismissPageDeletion,
            title = {
                Text(if (singlePageNumber != null) "删除第 $singlePageNumber 页？" else "删除所选 $pageCount 页？")
            },
            text = {
                Text("此操作会移除${if (pageCount == 1) "当前页面" else "所选页面"}，但可以使用一次撤销完整恢复。")
            },
            dismissButton = {
                TextButton(onClick = ::dismissPageDeletion) {
                    Text("取消")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = deletionStillValid && state.busy == null,
                    onClick = {
                        val confirmedPageIndices = pageIndices
                        dismissPageDeletion()
                        viewModel.deletePages(context, confirmedPageIndices)
                    },
                ) {
                    Text("删除")
                }
            },
        )
    }

    if (showDeleteAnnotationConfirmation) {
        val annotation = state.annotations.firstOrNull { it.index == state.selectedAnnotationIndex }
        val annotationLabel = when (annotation?.subtype) {
            "Highlight" -> "高亮批注"
            "Text" -> "便签"
            "Ink" -> "手写签名"
            else -> "批注"
        }
        AlertDialog(
            onDismissRequest = { showDeleteAnnotationConfirmation = false },
            title = { Text("删除批注？") },
            text = { Text("将删除“$annotationLabel”，此操作可以使用撤销恢复。") },
            dismissButton = {
                TextButton(onClick = { showDeleteAnnotationConfirmation = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteAnnotationConfirmation = false
                        viewModel.deleteSelectedAnnotation(context)
                    },
                ) { Text("删除") }
            },
        )
    }

    if (pendingRedactionIds.isNotEmpty()) {
        val redactionCount = pendingRedactionIds.size
        AlertDialog(
            onDismissRequest = { pendingRedactionIds = emptySet() },
            title = { Text("永久遮盖所选内容？") },
            text = {
                Text(
                    "将从 PDF 内容流中删除所选 $redactionCount 个元素，并用不透明黑色区域覆盖原位置。" +
                        "导出的 PDF 无法恢复这些内容；当前编辑会话内仍可使用撤销。",
                )
            },
            dismissButton = {
                TextButton(onClick = { pendingRedactionIds = emptySet() }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val confirmedSelection = pendingRedactionIds
                        pendingRedactionIds = emptySet()
                        viewModel.redactSelected(context, confirmedSelection)
                    },
                ) { Text("永久遮盖", color = MaterialTheme.colorScheme.error) }
            },
        )
    }

    if (showInsertTextDialog) {
        AlertDialog(
            onDismissRequest = { showInsertTextDialog = false },
            title = { Text("插入文本") },
            text = {
                OutlinedTextField(
                    value = insertTextValue,
                    onValueChange = { insertTextValue = it },
                    label = { Text("文本内容") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            dismissButton = {
                TextButton(onClick = { showInsertTextDialog = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    enabled = insertTextValue.isNotBlank(),
                    onClick = {
                        val text = insertTextValue
                        showInsertTextDialog = false
                        viewModel.insertTextAtCenter(context, text)
                    },
                ) { Text("插入") }
            },
        )
    }

    if (showAnnotationNoteDialog) {
        AlertDialog(
            onDismissRequest = { showAnnotationNoteDialog = false },
            title = { Text("添加便签") },
            text = {
                OutlinedTextField(
                    value = annotationNoteValue,
                    onValueChange = { annotationNoteValue = it },
                    label = { Text("批注内容") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            dismissButton = {
                TextButton(onClick = { showAnnotationNoteDialog = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    enabled = annotationNoteValue.isNotBlank(),
                    onClick = {
                        val contents = annotationNoteValue
                        showAnnotationNoteDialog = false
                        viewModel.addNoteToSelection(context, contents)
                    },
                ) { Text("添加") }
            },
        )
    }

    if (showAnnotationFreeTextDialog) {
        AlertDialog(
            onDismissRequest = { showAnnotationFreeTextDialog = false },
            title = { Text("添加自由文本") },
            text = {
                OutlinedTextField(
                    value = annotationFreeTextValue,
                    onValueChange = { annotationFreeTextValue = it.take(4_000) },
                    label = { Text("文本内容") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            dismissButton = {
                TextButton(onClick = { showAnnotationFreeTextDialog = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    enabled = annotationFreeTextValue.isNotBlank(),
                    onClick = {
                        val contents = annotationFreeTextValue
                        showAnnotationFreeTextDialog = false
                        viewModel.addFreeTextToSelection(context, contents)
                    },
                ) { Text("添加") }
            },
        )
    }

    val selectedAnnotation = state.annotations.firstOrNull { it.index == state.selectedAnnotationIndex }
    if (showAnnotationEditDialog && selectedAnnotation != null) {
        AnnotationEditDialog(
            annotation = selectedAnnotation,
            busy = state.busy != null,
            onApply = { colorArgb, opacity, contents, fontSize ->
                showAnnotationEditDialog = false
                viewModel.updateSelectedAnnotation(context, colorArgb, opacity, contents, fontSize)
            },
            onDismiss = { showAnnotationEditDialog = false },
        )
    }

    if (showStampDialog) {
        AlertDialog(
            onDismissRequest = { showStampDialog = false },
            title = { Text("添加图章") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StampKind.entries.forEach { kind ->
                        TextButton(
                            onClick = {
                                showStampDialog = false
                                viewModel.addStampToSelection(context, kind)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(kind.displayName)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showStampDialog = false }) { Text("取消") }
            },
        )
    }

    if (showSignatureDialog) {
        SignaturePadDialog(
            onDismiss = { showSignatureDialog = false },
            onConfirm = { strokes ->
                showSignatureDialog = false
                viewModel.addSignature(context, strokes)
            },
        )
    }
}

@Composable
private fun Workspace(
    viewModel: PdfDocumentViewModel,
    state: PdfUiState,
    dock: Dock,
    transparent: Boolean,
    sizeDp: Dp,
    canDockSide: Boolean,
    onResize: (Float) -> Unit,
    fitMode: FitMode,
    onUserTransform: () -> Unit,
    onToggleDock: () -> Unit,
    onToggleTransparent: () -> Unit,
    onDelete: () -> Unit,
) {
    val page = state.page ?: return
    val transform = state.pageTransform

    val context = LocalContext.current
    val density = LocalDensity.current
    val leafRects = remember(page, transform) {
        if (transform != null) {
            page.leaves.mapNotNull { n -> n.bounds?.let { LeafRect(n.id, transform.toRect(it)) } }
        } else {
            emptyList()
        }
    }
    // Every Tj/TJ run inside a BT..ET, boxed so it can be tapped and retyped.
    val runBoxes = remember(page, transform) {
        if (transform == null) {
            emptyList()
        } else {
            page.leaves
                .asSequence()
                .filter { it.kind == NodeKind.TEXT }
                .flatMap { it.children.asSequence() }
                .filter {
                    it.kind == NodeKind.TEXT && !it.locked && !it.text.isNullOrBlank() && it.bounds != null
                }
                .map { LeafRect(it.id, transform.toRect(it.bounds!!)) }
                .toList()
        }
    }
    val selectedRects = remember(page, transform, state.selectedIds) {
        if (transform == null) emptyList() else state.selectedIds.mapNotNull { id ->
            findNode(page.root, id)?.bounds?.let(transform::toRect)
        }
    }
    val annotationRects = remember(state.annotations, transform) {
        if (transform == null) emptyList() else state.annotations.map { annotation ->
            LeafRect(annotation.index, transform.toRect(annotation.bounds))
        }
    }
    val selectedAnnotationRect = remember(state.selectedAnnotationIndex, annotationRects) {
        annotationRects.firstOrNull { it.id == state.selectedAnnotationIndex }?.rect
    }
    val selectedAnnotationEditable = remember(state.annotations, state.selectedAnnotationIndex) {
        state.annotations.firstOrNull { it.index == state.selectedAnnotationIndex }?.editable == true
    }
    val selectedRect = selectedRects.singleOrNull()
    val selectionOverlayRect = selectedRects.takeIf { it.isNotEmpty() }?.let { rects ->
        Rect(
            left = rects.minOf { it.left },
            top = rects.minOf { it.top },
            right = rects.maxOf { it.right },
            bottom = rects.maxOf { it.bottom },
        )
    }
    val selectedCanTransform = remember(page, state.selectedIds) {
        if (state.selectedIds.size == 1) {
            state.selectedId?.let(viewModel::canTransform) == true
        } else {
            viewModel.canTransformSelection()
        }
    }
    val selectedContainsLocked = remember(page, state.selectedIds) {
        state.selectedIds.any { id -> findNode(page.root, id)?.locked == true }
    }
    val snapCandidateBounds = remember(page, transform, state.selectedIds) {
        if (transform == null) {
            emptyList()
        } else {
            page.leaves.asSequence()
                .filterNot { it.id in state.selectedIds }
                .mapNotNull { node -> node.bounds?.let(transform::toRect) }
                .map { rect -> Bounds(rect.left, rect.top, rect.right, rect.bottom) }
                .toList()
        }
    }

    val highlight = MaterialTheme.colorScheme.primary
    val backdrop = MaterialTheme.colorScheme.surfaceVariant
    val runBoxColor = MaterialTheme.colorScheme.outline
    var editingRunId by remember(page) { mutableStateOf<Int?>(null) }
    val bmp = state.bitmap
    var snapGuides by remember(page, state.selectedIds) { mutableStateOf(emptyList<SnapGuide>()) }
    LaunchedEffect(state.snapEnabled) {
        if (!state.snapEnabled) snapGuides = emptyList()
    }

    // Held here, not inside PdfCanvas, so zoom/pan persist when the canvas moves
    // between the docked and transparent layouts.
    val scaleState = remember { mutableStateOf(1f) }
    val offsetState = remember { mutableStateOf(Offset.Zero) }

    // Last font-match decision, kept for the debug overlay; refreshed on each tap.
    var fontDebug by remember(page) { mutableStateOf<FontCatalog.MatchExplain?>(null) }
    LaunchedEffect(editingRunId, state.fontCatalogTick) {
        editingRunId?.let { fontDebug = viewModel.fontDecisionFor(it) }
    }
    val fontDebugRows: List<Pair<String, String>> = fontDebug?.let { e ->
        buildList {
            val matchStep = when (e.step) {
                "tex" -> "TeX 字体"
                "alias" -> "字体别名"
                "system" -> "系统字体"
                "width" -> "字宽特征"
                "panose" -> "PANOSE 特征"
                "none" -> "未匹配"
                else -> e.step
            }
            add("字体" to if (e.original == "(none)") "无" else e.original)
            add("匹配" to matchStep + if (e.confident) "" else "（低置信度）")
            add("选择" to (e.match ?: "-"))
            e.detail?.let { add("宽度" to it) }
        }
    } ?: emptyList()

    val canvas: @Composable (Modifier) -> Unit = { mod ->
        if (bmp != null) {
            Box(mod) {
                PdfCanvas(
                    bitmap = bmp,
                    pageIndex = state.pageIndex,
                    scaleState = scaleState,
                    offsetState = offsetState,
                    leaves = leafRects,
                    selectedRects = if (selectedCanTransform && editingRunId == null) emptyList() else selectedRects,
                    annotationRects = annotationRects,
                    selectedAnnotationRect = selectedAnnotationRect,
                    highlightColor = highlight,
                    backdropColor = backdrop,
                    runBoxes = runBoxes,
                    editingRunId = editingRunId,
                    textBoxColor = runBoxColor,
                    snapGuides = snapGuides,
                    onEditRun = { id -> editingRunId = id },
                    debugRows = fontDebugRows,
                    fitMode = fitMode,
                    onUserTransform = onUserTransform,
                    onSelect = { id -> viewModel.select(id, reveal = true) },
                    onSelectAnnotation = viewModel::selectAnnotation,
                    onToggleSelect = { id -> viewModel.toggleSelection(id, reveal = true) },
                    renderTile = { idx, src, outW, outH ->
                        viewModel.renderRegion(idx, src.left, src.top, src.right, src.bottom, outW, outH)
                            ?.asImageBitmap()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                val selectedId = state.selectedId
                if (
                    selectedId != null && selectionOverlayRect != null && transform != null &&
                    selectedCanTransform && !selectedContainsLocked && editingRunId == null
                ) {
                    ObjectTransformOverlay(
                        objectId = state.selectedIds.hashCode(),
                        rect = selectionOverlayRect,
                        canvasScale = scaleState.value,
                        canvasOffset = offsetState.value,
                        color = highlight,
                        enabled = state.busy == null && !selectedContainsLocked,
                        onCommit = { translationBitmap, scale, screenRotation ->
                            val userDelta = transform.toUserDelta(translationBitmap)
                            if (state.selectedIds.size == 1) {
                                viewModel.applyCanvasTransform(
                                    context = context,
                                    id = selectedId,
                                    dx = userDelta.x,
                                    dy = userDelta.y,
                                    scale = scale,
                                    rotationDegrees = transform.toUserRotation(screenRotation),
                                )
                            } else {
                                viewModel.applyCanvasTransformSelection(
                                    context = context,
                                    dx = userDelta.x,
                                    dy = userDelta.y,
                                    scale = scale,
                                    rotationDegrees = transform.toUserRotation(screenRotation),
                                )
                            }
                        },
                        snapTranslation = { raw ->
                            if (!state.snapEnabled) {
                                SnapResult(raw.x, raw.y)
                            } else {
                                // long: moving、候选框和页面边界都来自同一张渲染位图，阈值按当前缩放反算后保持拖动手感稳定。
                                ElementSnapper.snap(
                                    moving = Bounds(
                                        selectionOverlayRect.left,
                                        selectionOverlayRect.top,
                                        selectionOverlayRect.right,
                                        selectionOverlayRect.bottom,
                                    ),
                                    candidates = snapCandidateBounds,
                                    page = Bounds(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()),
                                    dx = raw.x,
                                    dy = raw.y,
                                    threshold = with(density) { 8.dp.toPx() } /
                                        scaleState.value.coerceAtLeast(0.1f),
                                )
                            }
                        },
                        onSnapGuidesChange = { snapGuides = it },
                    )
                }
                if (selectedAnnotationRect != null && transform != null && selectedAnnotationEditable) {
                    ObjectTransformOverlay(
                        objectId = -(state.selectedAnnotationIndex ?: 0) - 1,
                        rect = selectedAnnotationRect,
                        canvasScale = scaleState.value,
                        canvasOffset = offsetState.value,
                        color = highlight,
                        enabled = state.busy == null,
                        showScaleHandle = false,
                        showRotationHandle = false,
                        selectionDescription = "已选批注，拖动可移动",
                        onCommit = { translationBitmap, _, _ ->
                            val userDelta = transform.toUserDelta(translationBitmap)
                            viewModel.moveSelectedAnnotation(context, userDelta.x, userDelta.y)
                        },
                    )
                }
                val er = editingRunId
                val run = if (er != null && transform != null) findNode(page.root, er) else null
                val rect = run?.bounds?.let { transform?.toRect(it) }
                if (er != null && run != null && rect != null) {
                    val fontFamily = remember(er) {
                        when (val face = viewModel.inlineFontFace(er)) {
                            is FontCatalog.FaceSource.Asset ->
                                FontFamily(Font(path = face.path, assetManager = context.assets))
                            is FontCatalog.FaceSource.FileFace -> FontFamily(Font(file = face.file))
                            null -> null
                        }
                    }
                    val fontScale = remember(er, state.fontCatalogTick) {
                        viewModel.inlineFontScale(er)
                    }
                    InlineTextEditor(
                        runId = er,
                        rect = rect,
                        scale = scaleState.value,
                        offset = offsetState.value,
                        initial = run.text ?: "",
                        textColor = run.colorArgb?.let { Color(it) } ?: Color.Black,
                        fontFamily = fontFamily,
                        fontScale = fontScale,
                        onCommit = { newText ->
                            editingRunId = null
                            viewModel.applyInlineText(context, er, newText)
                        },
                    )
                }
            }
        } else {
            Box(mod, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
    val pane: @Composable () -> Unit = {
        InspectorPane(
            page = page,
            expanded = state.expanded,
            selectedId = state.selectedId,
            selectedIds = state.selectedIds,
            swatchColors = state.swatchColors,
            revealTick = state.revealTick,
            showRaw = state.showRaw,
            snapEnabled = state.snapEnabled,
            canDelete = state.selectedIds.isNotEmpty() && !selectedContainsLocked,
            dock = dock,
            transparent = transparent,
            canDockSide = canDockSide,
            onSelect = { id -> viewModel.select(id) },
            onToggleSelect = { id -> viewModel.toggleSelection(id, reveal = true) },
            onToggleExpand = { id -> viewModel.toggleExpand(id) },
            onToggleRaw = { viewModel.toggleRaw() },
            onToggleSnap = { viewModel.toggleSnap() },
            onToggleDock = onToggleDock,
            onToggleTransparent = onToggleTransparent,
            onDelete = onDelete,
            onEdit = { id -> viewModel.beginEdit(id) },
            onToggleLock = { id -> viewModel.toggleElementLock(context, id) },
            onAlign = { action -> viewModel.alignSelected(context, action) },
            onReorder = { action -> viewModel.reorderSelected(context, action) },
        )
    }

    if (transparent) {
        Box(Modifier.fillMaxSize()) {
            canvas(Modifier.fillMaxSize())
            InspectorDock(
                dock = dock,
                transparent = true,
                sizeDp = sizeDp,
                onResizePx = onResize,
                modifier = Modifier
                    .align(if (dock == Dock.SIDE) Alignment.CenterEnd else Alignment.BottomCenter)
                    .then(if (dock == Dock.SIDE) Modifier.fillMaxHeight() else Modifier.fillMaxWidth()),
            ) { pane() }
        }
    } else if (dock == Dock.SIDE) {
        Row(Modifier.fillMaxSize()) {
            canvas(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            InspectorDock(dock, false, sizeDp, onResize, Modifier.fillMaxHeight()) { pane() }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            canvas(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            InspectorDock(dock, false, sizeDp, onResize, Modifier.fillMaxWidth()) { pane() }
        }
    }
}

// A text field pinned over the tapped run. Position and size follow the live
// zoom/pan so it sits exactly on the glyphs; committing hands the new string to
// the same run rewrite the edit sheet uses. The scrim commits on a tap away.
@Composable
private fun InlineTextEditor(
    runId: Int,
    rect: Rect,
    scale: Float,
    offset: Offset,
    initial: String,
    textColor: Color,
    fontFamily: FontFamily?,
    fontScale: Float,
    onCommit: (String) -> Unit,
) {
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    var value by remember(runId) {
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    val left = rect.left * scale + offset.x
    val top = rect.top * scale + offset.y
    val widthPx = (rect.width * scale).coerceAtLeast(32f)
    val heightPx = (rect.height * scale).coerceAtLeast(22f)
    // The run box is the text's em (ascent 0.75 to descent -0.25), so the font
    // fills it and the baseline rests 75% down. Bottom aligning a naturally
    // sized field lands the glyphs on that line with no top padding or clipping.
    val fontPx = (rect.height * scale * fontScale).coerceAtLeast(14f)
    val fontSp = with(density) { fontPx.toSp() }
    val surfaceIsLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val textIsDark = textColor.luminance() < 0.5f
    // long: 编辑浮层必须同时适配 PDF 原文字色和应用主题，避免暗色模式下黑字落在深底或白字落在浅底。
    val editorBackground = if (surfaceIsLight == textIsDark) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.inverseSurface
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(runId) { detectTapGestures { onCommit(value.text) } },
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
                .background(editorBackground)
                .border(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = textColor,
                    fontSize = fontSp,
                    fontFamily = fontFamily,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                cursorBrush = SolidColor(textColor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCommit(value.text) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Bottom, unbounded = true)
                    .focusRequester(focusRequester),
            )
        }
    }
    LaunchedEffect(runId) { focusRequester.requestFocus() }
}

@Composable
private fun HomeScreen(
    loading: Boolean,
    error: String?,
    recovery: PdfRecoveryInfo?,
    history: List<ReaderHistoryEntry>,
    onOpenEdit: () -> Unit,
    onOpenRead: () -> Unit,
    onOpenEbook: () -> Unit,
    onMergePdf: () -> Unit,
    onOpenHistory: (ReaderHistoryEntry) -> Unit,
    onRemoveHistory: (ReaderHistoryEntry) -> Unit,
    onResumeRecovery: () -> Unit,
    onDiscardRecovery: () -> Unit,
    onSettings: () -> Unit,
) {
    var pendingHistoryRemoval by remember { mutableStateOf<ReaderHistoryEntry?>(null) }
    var showDiscardRecovery by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(64.dp)
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onSettings) {
                    Icon(TablerIcons.Settings, contentDescription = "设置", modifier = Modifier.size(22.dp))
                }
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "正在准备文档…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 720.dp)
                        .fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 20.dp,
                        vertical = 20.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (recovery != null) {
                        item {
                            RecoveryCard(
                                recovery = recovery,
                                onResume = onResumeRecovery,
                                onDiscard = { showDiscardRecovery = true },
                            )
                        }
                    }
                    item {
                        FilledTonalButton(
                            onClick = onMergePdf,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(TablerIcons.FileExport, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("合并多个 PDF", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    item {
                        Column {
                            Text("打开文档", style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "继续阅读，或从设备中选择文件",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (error != null) {
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.errorContainer,
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        error,
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = onOpenRead) {
                                        Text("选择文件")
                                    }
                                }
                            }
                        }
                    }
                    item {
                        PrimaryModeCard(
                            icon = { Icon(TablerIcons.Book, contentDescription = null) },
                            title = "阅读 PDF",
                            description = "连续浏览、搜索、目录与书签",
                            onClick = onOpenRead,
                        )
                    }
                    item {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            // long: 窄屏和大字号环境下双列入口容易挤压标题，改为单列后仍保留完整触控区域和信息层级。
                            if (maxWidth < 320.dp) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CompactModeCard(
                                        icon = { Icon(TablerIcons.Edit, contentDescription = null) },
                                        title = "编辑 PDF",
                                        detail = "编辑文档内容",
                                        onClick = onOpenEdit,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    CompactModeCard(
                                        icon = { Icon(TablerIcons.FileText, contentDescription = null) },
                                        title = "电子书",
                                        detail = "EPUB · TXT",
                                        onClick = onOpenEbook,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    CompactModeCard(
                                        icon = { Icon(TablerIcons.Edit, contentDescription = null) },
                                        title = "编辑 PDF",
                                        detail = "编辑文档内容",
                                        onClick = onOpenEdit,
                                        modifier = Modifier.weight(1f),
                                    )
                                    CompactModeCard(
                                        icon = { Icon(TablerIcons.FileText, contentDescription = null) },
                                        title = "电子书",
                                        detail = "EPUB · TXT",
                                        onClick = onOpenEbook,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Icon(
                                TablerIcons.History,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("阅读历史", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    if (history.isEmpty()) {
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Text(
                                    "暂无 PDF 阅读记录",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        items(history, key = { it.uri }) { entry ->
                            HistoryItem(
                                entry = entry,
                                onClick = { onOpenHistory(entry) },
                                onDelete = { pendingHistoryRemoval = entry },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingHistoryRemoval?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingHistoryRemoval = null },
            title = { Text("移除阅读历史") },
            text = { Text("将移除该 PDF 的阅读记录和书签，不会删除原始文件。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingHistoryRemoval = null
                        onRemoveHistory(entry)
                    },
                ) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingHistoryRemoval = null }) { Text("取消") }
            },
        )
    }

    if (showDiscardRecovery) {
        AlertDialog(
            onDismissRequest = { showDiscardRecovery = false },
            title = { Text("放弃未保存的编辑？") },
            text = { Text("恢复文件将被删除，此操作无法撤销，原始 PDF 不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardRecovery = false
                        onDiscardRecovery()
                    },
                ) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardRecovery = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun RecoveryCard(
    recovery: PdfRecoveryInfo,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(TablerIcons.Edit, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("恢复未保存的编辑", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${recovery.title} · 第 ${recovery.pageIndex + 1} 页",
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDiscard) { Text("放弃") }
                TextButton(onClick = onResume) { Text("恢复编辑") }
            }
        }
    }
}

@Composable
private fun PrimaryModeCard(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .clickable(role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f),
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Box(contentAlignment = Alignment.Center) { icon() }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                )
            }
            Icon(TablerIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun CompactModeCard(
    icon: @Composable () -> Unit,
    title: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 120.dp)
            .clickable(role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) { icon() }
            }
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryItem(
    entry: ReaderHistoryEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(role = Role.Button, onClick = onClick)
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    TablerIcons.FileText,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(
                        "上次阅读到第 ${entry.pageIndex + 1} 页",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    TablerIcons.Trash,
                    contentDescription = "移除${entry.title}",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun BusyOverlay(message: String, onCancel: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = message
                    liveRegion = LiveRegionMode.Polite
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(14.dp))
                Text(
                    message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (onCancel != null) {
                    TextButton(onClick = onCancel) { Text("取消") }
                }
            }
        }
    }
}

private fun queryDocumentDisplayName(context: Context, uri: Uri): String {
    // long: SAF URI 通常不包含真实文件名，优先读取提供方元数据，便于用户核对当前选择的证书。
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
    return fromProvider?.takeIf(String::isNotBlank)
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "数字证书.p12"
}
