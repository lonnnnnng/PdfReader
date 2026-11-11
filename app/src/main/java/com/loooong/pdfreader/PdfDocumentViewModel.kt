package com.loooong.pdfreader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.LoadParams
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.loooong.pdfreader.engine.ContentStreamEngine
import com.loooong.pdfreader.engine.Bounds
import com.loooong.pdfreader.engine.AlignmentAction
import com.loooong.pdfreader.engine.DrawNode
import com.loooong.pdfreader.engine.EditCaps
import com.loooong.pdfreader.engine.EditRequest
import com.loooong.pdfreader.engine.EditResult
import com.loooong.pdfreader.engine.ElementEditor
import com.loooong.pdfreader.engine.ElementAlignment
import com.loooong.pdfreader.engine.ImageInsertRequest
import com.loooong.pdfreader.engine.NodeKind
import com.loooong.pdfreader.engine.LayerAction
import com.loooong.pdfreader.engine.PdfAnnotationEditor
import com.loooong.pdfreader.engine.PdfAnnotationInfo
import com.loooong.pdfreader.engine.AnnotationEditRequest
import com.loooong.pdfreader.engine.TextMarkupKind
import com.loooong.pdfreader.engine.StampKind
import com.loooong.pdfreader.engine.PageTreeEditor
import com.loooong.pdfreader.engine.PageCropMargins
import com.loooong.pdfreader.engine.PageGeometryEditor
import com.loooong.pdfreader.engine.PageGeometryInfo
import com.loooong.pdfreader.engine.PdfFormEditor
import com.loooong.pdfreader.engine.PdfFormFieldCreateKind
import com.loooong.pdfreader.engine.PdfFormFieldCreateRequest
import com.loooong.pdfreader.engine.PdfFormFieldInfo
import com.loooong.pdfreader.engine.PdfAttachmentEditor
import com.loooong.pdfreader.engine.PdfAttachmentInfo
import com.loooong.pdfreader.engine.PdfDocumentFeatureInspector
import com.loooong.pdfreader.engine.PdfDigitalSignatureInspector
import com.loooong.pdfreader.engine.PdfDigitalSignatureReport
import com.loooong.pdfreader.engine.PdfDigitalSigner
import com.loooong.pdfreader.engine.PdfDigitalSignRequest
import com.loooong.pdfreader.engine.PdfDigitalSignResult
import com.loooong.pdfreader.engine.PdfDigitalSignatureStatus
import com.loooong.pdfreader.engine.PdfPkcs12Info
import com.loooong.pdfreader.engine.PdfCompressionEditor
import com.loooong.pdfreader.engine.PdfCompressionPreset
import com.loooong.pdfreader.engine.PdfCompressionReport
import com.loooong.pdfreader.engine.PdfMetadata
import com.loooong.pdfreader.engine.PdfMetadataEditor
import com.loooong.pdfreader.engine.PdfOutlineEditor
import com.loooong.pdfreader.engine.PdfOutlineEntry
import com.loooong.pdfreader.engine.PdfOutlineMove
import com.loooong.pdfreader.engine.OcrPageRecognition
import com.loooong.pdfreader.engine.PdfOcrEditor
import com.loooong.pdfreader.engine.PdfPageDecorationEditor
import com.loooong.pdfreader.engine.PdfPageDecorationRequest
import com.loooong.pdfreader.engine.PdfPageTransferInspector
import com.loooong.pdfreader.engine.PdfPageTransferIssue
import com.loooong.pdfreader.engine.PdfFlattenedCopyExporter
import com.loooong.pdfreader.engine.PdfPageExportMode
import com.loooong.pdfreader.engine.PdfPageExportStrategy
import com.loooong.pdfreader.engine.planPdfPageExport
import com.loooong.pdfreader.engine.PdfLinkEditor
import com.loooong.pdfreader.engine.PdfLinkId
import com.loooong.pdfreader.engine.PdfLinkInfo
import com.loooong.pdfreader.engine.PdfLinkCreateRequest
import com.loooong.pdfreader.engine.PdfLinkUpdateRequest
import com.loooong.pdfreader.engine.PdfSecurityEditor
import com.loooong.pdfreader.engine.PdfSecurityInfo
import com.loooong.pdfreader.engine.PdfSecuritySettings
import com.loooong.pdfreader.engine.PdfMergeEditor
import com.loooong.pdfreader.engine.RawPasteNode
import com.loooong.pdfreader.engine.SignatureStroke
import com.loooong.pdfreader.engine.ShapeInsertRequest
import com.loooong.pdfreader.engine.ShapeKind
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.loooong.pdfreader.engine.ParsedPage
import com.loooong.pdfreader.engine.StreamOwner
import com.loooong.pdfreader.engine.TextInsertRequest
import com.loooong.pdfreader.engine.collectGroupIds
import com.loooong.pdfreader.engine.findNode
import com.loooong.pdfreader.ui.PageTransform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.roundToInt

class PdfDocumentViewModel : ViewModel() {

    var state by mutableStateOf(PdfUiState())
        private set

    private var document: PDDocument? = null
    val permissions: PdfPermissions get() = PdfPermissions.from(document?.currentAccessPermission)
    var parsed: ParsedPage? = null
        private set

    private var pdfRenderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var documentSourceFile: File? = null
    private var cacheFile: File? = null
    private var applicationContext: Context? = null
    private var documentPassword: String? = null
    // long: 记录打开时的来源字节指纹，覆盖保存前用它识别其他应用的外部修改。
    private var sourceFingerprint: PdfFileFingerprint? = null
    private var pendingPasswordAction: PendingPdfPasswordAction? = null
    private var passwordPromptId = 0
    private val renderMutex = Mutex()
    private val readerDataMutex = Mutex()
    private var documentGeneration = 0
    private var readerRenderGeneration = 0
    private val readerRenderJobs = mutableMapOf<Pair<Boolean, Int>, Job>()
    private val visibleReaderPages = mutableSetOf<Int>()
    private val readerRequestedWidths = mutableMapOf<Pair<Boolean, Int>, Int>()
    private var initialOpenConsumed = false
    private var searchJob: Job? = null
    private var readerIndexJob: Job? = null
    private var ocrJob: Job? = null
    private var compressionJob: Job? = null
    private var digitalSignatureInspectionJob: Job? = null
    private var digitalSignatureInspectionGeneration = 0
    private var signingCertificateInspectionJob: Job? = null
    private var signingCertificateInspectionGeneration = 0

    val readerPages = mutableStateMapOf<Int, ReaderPageState>()
    val readerThumbnails = mutableStateMapOf<Int, ReaderPageState>()
    var readerState by mutableStateOf(ReaderUiState())
        private set
    var readerHistory by mutableStateOf<List<ReaderHistoryEntry>>(emptyList())
        private set
    var originalBackups by mutableStateOf<List<File>>(emptyList())
        private set
    var recoveryInfo by mutableStateOf<PdfRecoveryInfo?>(null)
        private set
    var passwordPrompt by mutableStateOf<PdfPasswordPrompt?>(null)
        private set
    var formFields by mutableStateOf<List<PdfFormFieldInfo>>(emptyList())
        private set
    var digitalSignatureReport by mutableStateOf<PdfDigitalSignatureReport?>(null)
        private set
    var digitalSignatureReportLoading by mutableStateOf(false)
        private set
    var digitalSignatureReportError by mutableStateOf<String?>(null)
        private set
    var signingCertificateInfo by mutableStateOf<PdfPkcs12Info?>(null)
        private set
    var signingCertificateLoading by mutableStateOf(false)
        private set
    var signingCertificateError by mutableStateOf<String?>(null)
        private set
    private val readerPageLru = LinkedHashSet<Int>()
    private val readerThumbnailLru = LinkedHashSet<Int>()
    private val readerTextIndex = java.util.concurrent.ConcurrentHashMap<Int, ReaderPageTextIndex>()

    private var fontCatalog: FontCatalog? = null
    private var embeddedFonts = false

    private val undoStack = ArrayDeque<EditSnapshot.Document>()
    private val redoStack = ArrayDeque<EditSnapshot.Document>()
    private var historyBytes = 0L
    private var pendingPageSnapshot: EditSnapshot.Document? = null
    private var elementClipboard: ElementClipboard? = null

    /** long: Activity 旋转会重新组合，但保留的会话不能重新打开来源并清掉未保存修改。 */
    fun openInitialDocument(context: Context, uri: Uri?) {
        if (initialOpenConsumed) return
        initialOpenConsumed = true
        loadReaderLibrary(context)
        // long: 进程重建时先保留草稿恢复入口，原始 Intent 不能覆盖尚未恢复的编辑。
        if (uri == null || state.hasDocument || recoveryInfo != null) return
        val page = readerHistory.firstOrNull { it.uri == uri.toString() }?.pageIndex ?: 0
        open(context, uri, AppMode.READ, page)
    }

    fun open(
        context: Context,
        uri: Uri,
        mode: AppMode = AppMode.EDIT,
        startPage: Int = 0,
    ) {
        if (state.busy != null || state.loading) return
        pendingPasswordAction = null
        passwordPrompt = null
        openWithPassword(context, uri, mode, startPage, password = null)
    }

    private fun openWithPassword(
        context: Context,
        uri: Uri,
        mode: AppMode,
        startPage: Int,
        password: String?,
    ) {
        val appContext = context.applicationContext
        applicationContext = appContext
        val generation = ++documentGeneration
        val replacingDocument = state.hasDocument
        passwordPrompt = null
        state = state.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    persistUriPermissions(appContext, uri)
                    prepareUriDocument(appContext, uri, generation, password)
                }
                if (generation != documentGeneration) {
                    loaded.dispose()
                    return@launch
                }
                val previousSourceFile = documentSourceFile
                val previousCacheFile = cacheFile
                adoptLoadedDocument(loaded)
                if (replacingDocument) {
                    // long: 新文档确认打开后才丢弃旧草稿，文件选择器取消时仍可回到原编辑现场。
                    clearRecoveryForFile(appContext, previousCacheFile, deleteFile = false)
                }
                runCatching { previousSourceFile?.delete() }
                runCatching { previousCacheFile?.delete() }
                clearHistory()
                elementClipboard = null
                clearReaderCaches()
                fontCatalog = FontCatalog(appContext)
                embeddedFonts = false
                documentPassword = loaded.password
                sourceFingerprint = loaded.sourceFingerprint
                val documentFeatures = PdfDocumentFeatureInspector.inspect(loaded.document)
                val initialPage = startPage.coerceIn(0, (loaded.document.numberOfPages - 1).coerceAtLeast(0))
                val effectiveMode = if (
                    mode == AppMode.EDIT && !PdfPermissions.from(loaded.document.currentAccessPermission).canEdit
                ) AppMode.READ else mode
                if (effectiveMode == AppMode.EDIT) renderPage(initialPage)
                val sourceUri = requireNotNull(loaded.sourceUri)
                val preferences = ReaderPreferences(appContext)
                val bookmarks = preferences.loadBookmarks(sourceUri)
                readerHistory = preferences.loadHistory()
                readerState = ReaderUiState(
                    pageInfos = loaded.pageInfos,
                    outline = loaded.outline,
                    links = loaded.links,
                    bookmarks = bookmarks,
                )
                state = state.copy(
                    loading = false,
                    hasDocument = true,
                    mode = effectiveMode,
                    pageCount = loaded.document.numberOfPages,
                    pageIndex = initialPage,
                    fileName = loaded.title,
                    sourceUri = sourceUri,
                    // long: 完整保存会破坏现有数字签名；签名存在或状态无法确认时都必须失败关闭，只允许另存副本。
                    canOverwrite = loaded.canOverwrite && !documentFeatures.requiresSafeCopy,
                    digitalSignatureCount = documentFeatures.digitalSignatureCount,
                    digitalSignatureInspectionFailed = documentFeatures.digitalSignatureInspectionFailed,
                    dirty = false,
                    documentSessionId = generation,
                    documentToken = state.documentToken + 1,
                    canUndo = false,
                    canRedo = false,
                )
                formFields = PdfFormEditor.list(loaded.document)
                pendingPasswordAction = null
                passwordPrompt = null
                if (effectiveMode == AppMode.READ) {
                    restoreReaderSearchIndex(appContext)
                    preferences.record(
                        ReaderHistoryEntry(sourceUri, loaded.title, initialPage, System.currentTimeMillis()),
                    )
                    readerHistory = preferences.loadHistory()
                    if ((false to initialPage) !in readerRequestedWidths) ensureReaderPage(initialPage, DEFAULT_READER_WIDTH_PX)
                    startReaderIndexing()
                }
                if (effectiveMode != mode) {
                    Toast.makeText(context, "当前密码仅允许阅读，已进入阅读模式", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "open failed", t)
                if (generation == documentGeneration) {
                    if (t is InvalidPasswordException) {
                        requestPdfPassword(
                            PendingPdfPasswordAction.Open(
                                uri = uri,
                                mode = mode,
                                startPage = startPage,
                                title = displayName(appContext, uri),
                            ),
                            wrongPassword = password != null,
                        )
                    } else {
                        state = state.copy(loading = false, error = "无法打开 PDF 文件")
                    }
                }
            }
        }
    }

    fun submitPdfPassword(context: Context, password: String) {
        val action = pendingPasswordAction ?: return
        if (password.isEmpty()) {
            passwordPrompt = passwordPrompt?.copy(error = "请输入 PDF 密码")
            return
        }
        passwordPrompt = null
        when (action) {
            is PendingPdfPasswordAction.Open -> openWithPassword(
                context,
                action.uri,
                action.mode,
                action.startPage,
                password,
            )
            is PendingPdfPasswordAction.Recovery -> resumeRecoveryWithPassword(
                context,
                action.recovery,
                password,
            )
        }
    }

    fun dismissPdfPassword() {
        pendingPasswordAction = null
        passwordPrompt = null
        state = state.copy(loading = false, error = null)
    }

    private fun requestPdfPassword(action: PendingPdfPasswordAction, wrongPassword: Boolean) {
        pendingPasswordAction = action
        passwordPrompt = PdfPasswordPrompt(
            requestId = ++passwordPromptId,
            title = action.title,
            error = if (wrongPassword) "密码错误，请重新输入" else null,
        )
        state = state.copy(loading = false, error = null)
    }

    fun showPage(index: Int) {
        if (state.busy != null || state.loading) return
        val doc = document ?: return
        if (index < 0 || index >= doc.numberOfPages || index == state.pageIndex) return
        state = state.copy(busy = "正在加载页面")
        viewModelScope.launch {
            renderPage(index)
            state = state.copy(busy = null, pageIndex = index)
            if (state.dirty) persistRecovery()
        }
    }

    fun deleteCurrentPage(context: Context) {
        deletePages(context, setOf(state.pageIndex))
    }

    fun deletePages(context: Context, pageIndices: Set<Int>) {
        if (pageIndices.isEmpty()) return
        val stablePageIndices = pageIndices.toSet()
        applyDocumentPageEdit(context, "正在删除页面") { doc, index ->
            // long: 多页删除作为单次文档事务写入历史，用户一次撤销即可恢复整批页面及其原始顺序。
            PageTreeEditor.deleteSelected(doc, stablePageIndices, currentIndex = index)
        }
    }

    fun duplicateCurrentPage(context: Context) {
        applyDocumentPageEdit(context, "正在复制页面") { doc, index ->
            PageTreeEditor.duplicate(doc, index)
        }
    }

    fun duplicatePages(context: Context, pageIndices: Set<Int>) {
        if (pageIndices.isEmpty()) return
        val stablePageIndices = pageIndices.toSet()
        applyDocumentPageEdit(context, "正在复制 ${stablePageIndices.size} 页") { doc, index ->
            PageTreeEditor.duplicateSelected(doc, stablePageIndices, currentIndex = index)
        }
    }

    fun moveCurrentPage(context: Context, delta: Int) {
        applyDocumentPageEdit(context, "正在调整页面顺序") { doc, index ->
            PageTreeEditor.move(doc, index, delta)
        }
    }

    // long: 页面结构和可见框会影响文档级对象，统一走整文档快照才能让撤销、崩溃恢复和安全保存保持一致。
    fun insertBlankPage(context: Context, afterIndex: Int = state.pageIndex) {
        applyDocumentPageEdit(context, "正在插入空白页") { doc, _ ->
            PageTreeEditor.insertBlank(doc, afterIndex)
        }
    }

    fun movePage(context: Context, sourceIndex: Int, targetIndex: Int) {
        if (sourceIndex == targetIndex) return
        applyDocumentPageEdit(
            context = context,
            busyLabel = "正在调整页面顺序",
            sourceIndex = sourceIndex,
        ) { doc, index ->
            PageTreeEditor.moveTo(doc, index, targetIndex)
        }
    }

    fun movePages(context: Context, pageIndices: Set<Int>, delta: Int) {
        if (pageIndices.isEmpty()) return
        val stablePageIndices = pageIndices.toSet()
        applyDocumentPageEdit(context, "正在移动 ${stablePageIndices.size} 页") { doc, index ->
            PageTreeEditor.moveSelected(doc, stablePageIndices, delta, currentIndex = index)
        }
    }

    fun importPages(context: Context, sourceUri: Uri, afterIndex: Int = state.pageIndex) {
        applyDocumentPageEdit(context, "正在导入页面") { doc, _ ->
            val input = requireNotNull(context.contentResolver.openInputStream(sourceUri)) {
                "无法读取所选 PDF"
            }
            input.buffered().use { sourceStream ->
                PDDocument.load(sourceStream).use { sourceDocument ->
                    require(PdfPermissions.from(sourceDocument.currentAccessPermission).extract) { "来源 PDF 不允许提取页面" }
                    // long: 源 PDF 只在文档事务内部保持打开，页面资源复制完成后立即释放文件句柄和解析缓存。
                    PageTreeEditor.importAll(doc, sourceDocument, afterIndex)
                }
            }
        }
    }

    fun pageGeometry(index: Int): PageGeometryInfo? {
        val doc = document ?: return null
        return index
            .takeIf { it in 0 until doc.numberOfPages }
            ?.let(doc::getPage)
            ?.let(PageGeometryEditor::inspect)
    }

    fun currentPageGeometry(): PageGeometryInfo? = pageGeometry(state.pageIndex)

    fun cropCurrentPage(context: Context, margins: PageCropMargins) {
        applyDocumentPageEdit(context, "正在裁剪页面") { doc, index ->
            PageGeometryEditor.crop(doc.getPage(index), margins)
            index
        }
    }

    fun resetCurrentPageCrop(context: Context) {
        applyDocumentPageEdit(context, "正在恢复完整页面") { doc, index ->
            PageGeometryEditor.resetCrop(doc.getPage(index))
            index
        }
    }

    fun cropPages(context: Context, pageIndices: Set<Int>, margins: PageCropMargins) {
        if (pageIndices.isEmpty()) return
        val stablePageIndices = pageIndices.toSet()
        applyDocumentPageEdit(context, "正在裁剪 ${stablePageIndices.size} 页") { doc, currentIndex ->
            require(stablePageIndices.all { it in 0 until doc.numberOfPages }) { "页面索引越界" }
            stablePageIndices.sorted().forEach { index ->
                PageGeometryEditor.crop(doc.getPage(index), margins)
            }
            currentIndex
        }
    }

    fun resetCropPages(context: Context, pageIndices: Set<Int>) {
        if (pageIndices.isEmpty()) return
        val stablePageIndices = pageIndices.toSet()
        applyDocumentPageEdit(context, "正在恢复 ${stablePageIndices.size} 页") { doc, currentIndex ->
            require(stablePageIndices.all { it in 0 until doc.numberOfPages }) { "页面索引越界" }
            stablePageIndices.sorted().forEach { index ->
                PageGeometryEditor.resetCrop(doc.getPage(index))
            }
            currentIndex
        }
    }

    fun resizePages(context: Context, pageIndices: Set<Int>, width: Float, height: Float) {
        if (pageIndices.isEmpty()) return
        val stablePageIndices = pageIndices.toSet()
        applyDocumentPageEdit(context, "正在调整页面尺寸") { doc, currentIndex ->
            require(stablePageIndices.all { it in 0 until doc.numberOfPages }) { "页面索引越界" }
            // long: 多页尺寸调整共享一次文档快照，任一页面失败时上层会整体回滚，避免混合新旧纸张尺寸。
            stablePageIndices.sorted().forEach { index ->
                PageGeometryEditor.resize(doc.getPage(index), width, height)
            }
            currentIndex
        }
    }

    fun documentMetadata(): PdfMetadata? = document?.let(PdfMetadataEditor::read)

    fun documentOutlineEntries(): List<PdfOutlineEntry> = document?.let(PdfOutlineEditor::list).orEmpty()

    fun documentLinks(): List<PdfLinkInfo> = document?.let(PdfLinkEditor::list).orEmpty()

    fun documentAttachments(): List<PdfAttachmentInfo> = document?.let(PdfAttachmentEditor::list).orEmpty()

    fun inspectDigitalSignatures() {
        val file = cacheFile ?: return
        val token = state.documentToken
        val password = documentPassword
        digitalSignatureInspectionJob?.cancel()
        val requestGeneration = ++digitalSignatureInspectionGeneration
        digitalSignatureReport = null
        digitalSignatureReportError = null
        digitalSignatureReportLoading = true
        digitalSignatureInspectionJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { PdfDigitalSignatureInspector.inspect(file, password) }
            }
            // long: 签名验证可能持续数秒，期间若文档发生编辑或切换，旧文件结果不能覆盖新会话的状态。
            if (requestGeneration != digitalSignatureInspectionGeneration) return@launch
            if (token != state.documentToken || file != cacheFile) {
                digitalSignatureReportLoading = false
                return@launch
            }
            result.onSuccess { report ->
                digitalSignatureReport = report
            }.onFailure { error ->
                Log.e(TAG, "digital signature inspection failed", error)
                digitalSignatureReportError = error.message
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "无法验证数字签名：$it" }
                    ?: "无法验证数字签名"
            }
            digitalSignatureReportLoading = false
        }
    }

    fun clearDigitalSignatureInspection() {
        digitalSignatureInspectionJob?.cancel()
        digitalSignatureInspectionJob = null
        digitalSignatureInspectionGeneration++
        digitalSignatureReport = null
        digitalSignatureReportError = null
        digitalSignatureReportLoading = false
    }

    fun inspectSigningCertificate(context: Context, uri: Uri, password: String) {
        signingCertificateInspectionJob?.cancel()
        val requestGeneration = ++signingCertificateInspectionGeneration
        signingCertificateInfo = null
        signingCertificateError = null
        signingCertificateLoading = true
        persistUriPermissions(context.applicationContext, uri)
        signingCertificateInspectionJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val bytes = readUriBytesWithLimit(context, uri, MAX_PKCS12_BYTES)
                    try {
                        Result.success(PdfDigitalSigner.inspectPkcs12(bytes, password))
                    } finally {
                        bytes.fill(0)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Result.failure(error)
                }
            }
            if (requestGeneration != signingCertificateInspectionGeneration) return@launch
            result.onSuccess { signingCertificateInfo = it }
                .onFailure { error ->
                    Log.e(TAG, "PKCS#12 inspection failed", error)
                    signingCertificateError = error.message
                        ?.takeIf { it.isNotBlank() }
                        ?: "无法读取数字证书"
                }
            signingCertificateLoading = false
        }
    }

    fun clearSigningCertificateInspection() {
        signingCertificateInspectionJob?.cancel()
        signingCertificateInspectionJob = null
        signingCertificateInspectionGeneration++
        signingCertificateInfo = null
        signingCertificateError = null
        signingCertificateLoading = false
    }

    fun signDocumentWithCertificate(
        context: Context,
        targetUri: Uri,
        certificateUri: Uri,
        certificatePassword: CharArray,
        request: PdfDigitalSignRequest,
        onComplete: (PdfDigitalSignResult?) -> Unit = {},
    ) {
        if (!requirePermission(PdfOperation.FILL_FORM)) { certificatePassword.fill('\u0000'); onComplete(null); return }
        // long: 数字签名会把新的 ByteRange 写入目标；目标若仍指向来源 URI，就会破坏用户保留的未签名原件。
        if (isSourceTarget(context, targetUri)) {
            certificatePassword.fill('\u0000')
            Toast.makeText(context, "数字签名必须另存为新文件，不能覆盖当前 PDF", Toast.LENGTH_LONG).show()
            return onComplete(null)
        }
        val source = cacheFile ?: run {
            certificatePassword.fill('\u0000')
            return onComplete(null)
        }
        if (state.busy != null) {
            certificatePassword.fill('\u0000')
            return onComplete(null)
        }
        state = state.copy(busy = "正在生成数字签名", error = null)
        viewModelScope.launch {
            var certificateBytes: ByteArray? = null
            var result: PdfDigitalSignResult? = null
            try {
                val appContext = context.applicationContext
                persistUriPermissions(appContext, targetUri)
                val signedResult = withContext(Dispatchers.IO) {
                    certificateBytes = readUriBytesWithLimit(appContext, certificateUri, MAX_PKCS12_BYTES)
                    val staging = File.createTempFile("pdf-digital-signature-", ".pdf", appContext.cacheDir)
                    val signResult = PdfDigitalSigner.sign(
                        sourceFile = source,
                        outputFile = staging,
                        documentPassword = documentPassword,
                        pkcs12Bytes = requireNotNull(certificateBytes),
                        pkcs12Password = certificatePassword,
                        request = request,
                    )
                    val writeResult = PdfDocumentWriter.writePreparedNewTarget(
                        stagingFile = staging,
                        openOutput = { appContext.contentResolver.openOutputStream(targetUri, "wt") },
                        openReadback = { appContext.contentResolver.openInputStream(targetUri) },
                        validate = { file ->
                            val report = PdfDigitalSignatureInspector.inspect(file, documentPassword)
                            val newest = report.signatures.maxByOrNull { it.signedRevisionLength ?: -1L }
                                ?: error("签名输出没有签名字典")
                            check(newest.status == PdfDigitalSignatureStatus.VALID) {
                                "签名输出验证失败：${newest.statusMessage}"
                            }
                        },
                    )
                    signResult to writeResult.targetVerified
                }
                result = signedResult.first
                val verificationText = if (signedResult.second) "，目标已回读验证" else "，目标提供方不支持回读"
                Toast.makeText(
                    context,
                    "数字签名已保存$verificationText",
                    Toast.LENGTH_LONG,
                ).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "digital signing failed", error)
                val message = error.message?.takeIf { it.isNotBlank() } ?: "数字签名失败"
                state = state.copy(error = message)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            } finally {
                certificateBytes?.fill(0)
                certificatePassword.fill('\u0000')
                state = state.copy(busy = null)
                onComplete(result)
            }
        }
    }

    fun addDocumentAttachment(
        context: Context,
        uri: Uri,
        onComplete: (Boolean) -> Unit = {},
    ) {
        val fileName = displayName(context, uri)
        val mimeType = context.contentResolver.getType(uri)
        val sourceSize = contentSize(context, uri)
        applyDocumentEdit(
            context = context,
            busyLabel = "正在添加附件",
            failureLabel = "附件添加",
            onComplete = onComplete,
        ) { doc, pageIndex ->
            val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取所选附件" }
            input.buffered().use { source ->
                PdfAttachmentEditor.add(
                    document = doc,
                    fileName = fileName,
                    description = null,
                    mimeType = mimeType,
                    source = source,
                    sourceSize = sourceSize,
                )
            }
            pageIndex
        }
    }

    fun renameDocumentAttachment(
        context: Context,
        key: String,
        fileName: String,
        description: String?,
        onComplete: (Boolean) -> Unit = {},
    ) {
        applyDocumentEdit(
            context = context,
            busyLabel = "正在更新附件",
            failureLabel = "附件更新",
            onComplete = onComplete,
        ) { doc, pageIndex ->
            pageIndex.takeIf { PdfAttachmentEditor.rename(doc, key, fileName, description) != null }
        }
    }

    fun deleteDocumentAttachment(
        context: Context,
        key: String,
        onComplete: (Boolean) -> Unit = {},
    ) {
        applyDocumentEdit(
            context = context,
            busyLabel = "正在删除附件",
            failureLabel = "附件删除",
            onComplete = onComplete,
        ) { doc, pageIndex ->
            pageIndex.takeIf { PdfAttachmentEditor.delete(doc, key) }
        }
    }

    fun exportDocumentAttachment(
        context: Context,
        key: String,
        uri: Uri,
        onComplete: (Boolean) -> Unit = {},
    ) {
        if (document == null || state.busy != null) {
            runCatching { onComplete(false) }
            return
        }
        state = state.copy(busy = "正在导出附件", error = null)
        viewModelScope.launch {
            var stagingFile: File? = null
            try {
                withContext(Dispatchers.IO) {
                    val target = File.createTempFile("pdf-attachment-", ".bin", context.cacheDir)
                    stagingFile = target
                    readerDataMutex.withLock {
                        target.outputStream().buffered().use { output ->
                            PdfAttachmentEditor.writeTo(requireNotNull(document), key, output)
                        }
                    }
                    val resolver = context.contentResolver
                    requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法创建附件文件" }.buffered().use { output ->
                        target.inputStream().buffered().use { input -> input.copyTo(output) }
                    }
                    // long: SAF 写入成功不等于数据完整，重新读取并核对字节数后才能向用户报告导出完成。
                    val writtenSize = requireNotNull(resolver.openInputStream(uri)) { "无法校验导出的附件" }.buffered().use {
                        countBytes(it)
                    }
                    check(writtenSize == target.length()) { "附件写入不完整" }
                }
                Toast.makeText(context, "附件已导出", Toast.LENGTH_SHORT).show()
                runCatching { onComplete(true) }
            } catch (t: Throwable) {
                Log.e(TAG, "attachment export failed", t)
                state = state.copy(error = "附件导出失败")
                Toast.makeText(context, t.message ?: "附件导出失败", Toast.LENGTH_LONG).show()
                runCatching { onComplete(false) }
            } finally {
                runCatching { stagingFile?.delete() }
                state = state.copy(busy = null)
            }
        }
    }

    fun defaultLinkBounds(): Bounds? {
        val page = document?.getPage(state.pageIndex) ?: return null
        val selected = state.selectedIds.singleOrNull()?.let { id ->
            state.page?.let { pageState -> findNode(pageState.root, id)?.bounds?.duplicate() }
        }
        if (selected != null && selected.isValid && selected.width >= 1f && selected.height >= 1f) return selected
        val crop = page.cropBox
        val width = (crop.width * 0.42f).coerceIn(120f, 260f).coerceAtMost(crop.width)
        val height = 28f.coerceAtMost(crop.height)
        val left = crop.lowerLeftX + (crop.width - width) / 2f
        val bottom = crop.lowerLeftY + (crop.height - height) / 2f
        return Bounds(left, bottom, left + width, bottom + height)
    }

    fun createLink(
        context: Context,
        request: PdfLinkCreateRequest,
        onComplete: (Boolean) -> Unit = {},
    ) {
        applyDocumentEdit(
            context = context,
            busyLabel = "正在创建链接",
            failureLabel = "链接创建",
            permission = PdfOperation.ANNOTATE,
            sourceIndex = request.pageIndex,
            onComplete = onComplete,
        ) { doc, _ ->
            PdfLinkEditor.create(doc, request)
            request.pageIndex
        }
    }

    fun updateLink(
        context: Context,
        id: PdfLinkId,
        request: PdfLinkUpdateRequest,
        onComplete: (Boolean) -> Unit = {},
    ) {
        applyDocumentEdit(
            context = context,
            busyLabel = "正在更新链接",
            failureLabel = "链接更新",
            permission = PdfOperation.ANNOTATE,
            sourceIndex = id.pageIndex,
            onComplete = onComplete,
        ) { doc, _ ->
            id.pageIndex.takeIf { PdfLinkEditor.update(doc, id, request) }
        }
    }

    fun deleteLink(
        context: Context,
        id: PdfLinkId,
        onComplete: (Boolean) -> Unit = {},
    ) {
        applyDocumentEdit(
            context = context,
            busyLabel = "正在删除链接",
            failureLabel = "链接删除",
            permission = PdfOperation.ANNOTATE,
            sourceIndex = id.pageIndex,
            onComplete = onComplete,
        ) { doc, _ ->
            id.pageIndex.takeIf { PdfLinkEditor.delete(doc, id) }
        }
    }

    fun documentSecurityInfo(): PdfSecurityInfo? = document?.let(PdfSecurityEditor::inspect)

    fun protectDocument(
        context: Context,
        settings: PdfSecuritySettings,
        onComplete: (Boolean) -> Unit = {},
    ) {
        applySecurityEdit(
            context = context,
            busyLabel = if (document?.isEncrypted == true) "正在更新 PDF 保护" else "正在保护 PDF",
            failureLabel = "PDF 保护设置",
            reloadPassword = settings.ownerPassword,
            onComplete = onComplete,
        ) { doc ->
            PdfSecurityEditor.protect(doc, settings)
            true
        }
    }

    fun removeDocumentProtection(
        context: Context,
        onComplete: (Boolean) -> Unit = {},
    ) {
        applySecurityEdit(
            context = context,
            busyLabel = "正在移除 PDF 保护",
            failureLabel = "PDF 保护移除",
            reloadPassword = null,
            onComplete = onComplete,
            operation = PdfSecurityEditor::removeProtection,
        )
    }

    fun updateDocumentMetadata(
        context: Context,
        metadata: PdfMetadata,
        onComplete: (Boolean) -> Unit = {},
    ) {
        applyDocumentEdit(
            context = context,
            busyLabel = "正在更新文档属性",
            failureLabel = "文档属性更新",
            onComplete = onComplete,
        ) { doc, index ->
            index.takeIf { PdfMetadataEditor.update(doc, metadata) }
        }
    }

    fun applyPageDecorations(
        context: Context,
        request: PdfPageDecorationRequest,
        onComplete: (Boolean) -> Unit = {},
    ) {
        applyDocumentEdit(
            context = context,
            busyLabel = "正在添加水印与页码",
            failureLabel = "水印与页码添加",
            onComplete = { success ->
                // long: 页面装饰可能嵌入新的中文字体，后续元素编辑继续使用完整保存，避免增量写入遗漏字体子集。
                if (success) embeddedFonts = true
                onComplete(success)
            },
        ) { doc, index ->
            val catalog = fontCatalog ?: error("字体目录未就绪")
            PdfPageDecorationEditor.apply(doc, request) { text ->
                catalog.resolveForText(doc, text)
            }
            index
        }
    }

    fun applyOcr(
        context: Context,
        pageIndices: Set<Int>,
        skipExistingText: Boolean,
        onComplete: (Boolean) -> Unit = {},
    ) {
        if (!requirePermission(PdfOperation.CONTENT)) { onComplete(false); return }
        val activeDocument = document
        if (activeDocument == null || state.mode != AppMode.EDIT || state.busy != null || pageIndices.isEmpty()) {
            runCatching { onComplete(false) }
            return
        }
        val stablePages = pageIndices.toSortedSet()
        if (stablePages.any { it !in 0 until activeDocument.numberOfPages }) {
            Toast.makeText(context, "OCR 页码超出文档范围", Toast.LENGTH_LONG).show()
            runCatching { onComplete(false) }
            return
        }

        val stateBeforeEdit = state
        state = state.copy(busy = "正在准备 OCR", error = null)
        ocrJob = viewModelScope.launch {
            var snapshot: EditSnapshot.Document? = null
            var skippedPages = 0
            var emptyPages = 0
            val prepared = ArrayList<PreparedOcrPage>()
            try {
                AndroidOcrRecognizer().use { recognizer ->
                    stablePages.forEachIndexed { order, pageIndex ->
                        state = state.copy(
                            busy = "正在识别第 ${order + 1}/${stablePages.size} 页（PDF 第 ${pageIndex + 1} 页）",
                        )
                        val shouldSkip = skipExistingText && withContext(Dispatchers.IO) {
                            readerDataMutex.withLock {
                                PdfOcrEditor.hasExtractableText(requireNotNull(document), pageIndex)
                            }
                        }
                        if (shouldSkip) {
                            skippedPages++
                            return@forEachIndexed
                        }

                        val bitmap = withContext(Dispatchers.IO) {
                            readerDataMutex.withLock {
                                renderOcrPage(requireNotNull(document), pageIndex)
                            }
                        }
                        // long: AndroidOcrRecognizer 持有 Bitmap 直到 ML Kit Task 完成，取消协程时不能提前回收。
                        val recognition = recognizer.recognize(bitmap)
                        if (recognition.lines.isEmpty()) emptyPages++
                        else prepared += PreparedOcrPage(pageIndex, recognition)
                    }
                }

                if (prepared.isEmpty()) {
                    val message = when {
                        skippedPages > 0 && emptyPages > 0 -> "没有写入文字层：跳过 $skippedPages 页，另有 $emptyPages 页未识别到文字"
                        skippedPages > 0 -> "所选页面已有可提取文字，已跳过 $skippedPages 页"
                        else -> "所选页面未识别到文字"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    runCatching { onComplete(true) }
                    return@launch
                }

                state = state.copy(busy = "正在写入 OCR 文字层")
                var writtenLines = 0
                withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val doc = requireNotNull(document)
                        val catalog = fontCatalog ?: error("字体目录未就绪")
                        snapshot = createDocumentSnapshot(doc, stateBeforeEdit.pageIndex)
                        prepared.forEach { item ->
                            val text = item.recognition.lines.joinToString("\n") { it.text }
                            val font = catalog.resolveForText(doc, text)
                                ?: error("没有可写入 OCR 中文和英文的字体")
                            writtenLines += PdfOcrEditor.replaceLayer(
                                document = doc,
                                page = doc.getPage(item.pageIndex),
                                recognition = item.recognition,
                                font = font,
                            )
                        }
                    }
                }
                check(writtenLines > 0) { "识别结果无法写入 PDF 文字层" }

                fullResyncCacheAndReopen()
                refreshPageStructureState()
                renderPage(state.pageIndex)
                pushDocumentUndo(requireNotNull(snapshot))
                snapshot = null
                embeddedFonts = true
                state = state.copy(dirty = true, canUndo = true, canRedo = false)
                runCatching { persistRecovery() }.onFailure { recoveryFailure ->
                    Log.w(TAG, "OCR recovery persistence failed", recoveryFailure)
                    state = state.copy(error = "OCR 已完成，但恢复草稿保存失败")
                }
                val detail = buildString {
                    append("已为 ${prepared.size} 页写入 $writtenLines 行可搜索文字")
                    if (skippedPages > 0) append("，跳过 $skippedPages 页")
                    if (emptyPages > 0) append("，$emptyPages 页未识别到文字")
                }
                Toast.makeText(context, detail, Toast.LENGTH_LONG).show()
                runCatching { onComplete(true) }
            } catch (cancelled: CancellationException) {
                val rollback = snapshot
                if (rollback != null) {
                    // long: 取消发生在写入或重载中间时仍必须恢复完整文档；取消上下文不可执行挂起回滚，
                    // 因此使用 NonCancellable 保证源文件、渲染器和恢复记录最终回到编辑前状态。
                    val rollbackFailure = withContext(NonCancellable) {
                        runCatching {
                            restoreDocumentSnapshot(rollback.file, rollback.reloadPassword)
                            state = state.copy(
                                pageIndex = stateBeforeEdit.pageIndex,
                                dirty = stateBeforeEdit.dirty,
                                canUndo = stateBeforeEdit.canUndo,
                                canRedo = stateBeforeEdit.canRedo,
                            )
                            refreshPageStructureState()
                            renderPage(state.pageIndex)
                            if (stateBeforeEdit.dirty) persistRecovery()
                        }.exceptionOrNull()
                    }
                    rollback.dispose()
                    snapshot = null
                    if (rollbackFailure == null) {
                        state = state.copy(error = "OCR 已取消，文档已恢复")
                        Toast.makeText(context, "OCR 已取消，文档已恢复", Toast.LENGTH_SHORT).show()
                    } else {
                        cancelled.addSuppressed(rollbackFailure)
                        state = state.copy(error = "OCR 已取消，但文档恢复失败")
                        Log.e(TAG, "OCR cancellation rollback failed", rollbackFailure)
                        Toast.makeText(context, "OCR 已取消，但文档恢复失败，请立即另存为", Toast.LENGTH_LONG).show()
                    }
                }
                throw cancelled
            } catch (t: Throwable) {
                val rollback = snapshot
                val rollbackFailure = if (rollback == null) null else runCatching {
                    restoreDocumentSnapshot(rollback.file, rollback.reloadPassword)
                    state = state.copy(
                        pageIndex = stateBeforeEdit.pageIndex,
                        dirty = stateBeforeEdit.dirty,
                        canUndo = stateBeforeEdit.canUndo,
                        canRedo = stateBeforeEdit.canRedo,
                    )
                    refreshPageStructureState()
                    renderPage(state.pageIndex)
                    if (stateBeforeEdit.dirty) persistRecovery()
                }.exceptionOrNull()
                rollback?.dispose()
                snapshot = null
                if (rollbackFailure != null) t.addSuppressed(rollbackFailure)
                Log.e(TAG, "OCR failed", t)
                val message = if (rollbackFailure == null) {
                    "OCR 失败，文档已恢复"
                } else {
                    "OCR 失败，且无法恢复文档"
                }
                state = state.copy(error = message)
                Toast.makeText(context, t.message ?: message, Toast.LENGTH_LONG).show()
                runCatching { onComplete(false) }
            } finally {
                prepared.clear()
                ocrJob = null
                state = state.copy(busy = null)
            }
        }
    }

    fun cancelOcr() {
        ocrJob?.cancel()
    }

    fun compressDocument(
        context: Context,
        preset: PdfCompressionPreset,
        onComplete: (PdfCompressionReport?) -> Unit = {},
    ) {
        if (!requirePermission(PdfOperation.CONTENT)) { onComplete(null); return }
        val activeDocument = document
        if (activeDocument == null || state.mode != AppMode.EDIT || state.busy != null) {
            runCatching { onComplete(null) }
            return
        }

        val stateBeforeEdit = state
        state = state.copy(busy = "正在扫描 PDF 图片", error = null)
        compressionJob = viewModelScope.launch {
            var snapshot: EditSnapshot.Document? = null
            try {
                val report = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val doc = requireNotNull(document)
                        snapshot = createDocumentSnapshot(doc, stateBeforeEdit.pageIndex)
                        val activeJob = coroutineContext[Job]
                        PdfCompressionEditor.compress(
                            document = doc,
                            options = preset.options,
                            onProgress = { progress ->
                                viewModelScope.launch {
                                    if (compressionJob?.isActive == true) {
                                        state = state.copy(
                                            busy = "正在压缩图片 ${progress.processedImages}/${progress.totalImages}",
                                        )
                                    }
                                }
                            },
                            isCancelled = { activeJob?.isActive == false },
                        )
                    }
                }

                if (!report.changed) {
                    snapshot?.dispose()
                    snapshot = null
                    val detail = buildString {
                        append("未发现可进一步压缩的图片")
                        if (report.skippedProtectedImages > 0) append("，${report.skippedProtectedImages} 张为透明、蒙版或特殊色彩图片")
                        if (report.skippedUnreadableImages > 0) append("，${report.skippedUnreadableImages} 张无法安全读取")
                    }
                    Toast.makeText(context, detail, Toast.LENGTH_LONG).show()
                    runCatching { onComplete(report) }
                    return@launch
                }

                state = state.copy(busy = "正在校验压缩后的 PDF")
                fullResyncCacheAndReopen()
                refreshPageStructureState()
                renderPage(state.pageIndex)
                pushDocumentUndo(requireNotNull(snapshot))
                snapshot = null
                state = state.copy(dirty = true, canUndo = true, canRedo = false)
                runCatching { persistRecovery() }.onFailure { recoveryFailure ->
                    Log.w(TAG, "compression recovery persistence failed", recoveryFailure)
                    state = state.copy(error = "PDF 已压缩，但恢复草稿保存失败")
                }
                val saved = android.text.format.Formatter.formatFileSize(context, report.estimatedSavedBytes)
                Toast.makeText(
                    context,
                    "已压缩 ${report.compressedImages}/${report.scannedImages} 张图片，预计减少 $saved",
                    Toast.LENGTH_LONG,
                ).show()
                runCatching { onComplete(report) }
            } catch (cancelled: CancellationException) {
                val rollback = snapshot
                if (rollback != null) {
                    // long: 图片资源可能已经逐个替换；用户取消时必须从整文档快照恢复，不能留下半压缩资源字典。
                    val rollbackFailure = withContext(NonCancellable) {
                        runCatching {
                            restoreDocumentSnapshot(rollback.file, rollback.reloadPassword)
                            state = state.copy(
                                pageIndex = stateBeforeEdit.pageIndex,
                                dirty = stateBeforeEdit.dirty,
                                canUndo = stateBeforeEdit.canUndo,
                                canRedo = stateBeforeEdit.canRedo,
                            )
                            refreshPageStructureState()
                            renderPage(state.pageIndex)
                            if (stateBeforeEdit.dirty) persistRecovery()
                        }.exceptionOrNull()
                    }
                    rollback.dispose()
                    snapshot = null
                    if (rollbackFailure == null) {
                        state = state.copy(error = "PDF 压缩已取消，文档已恢复")
                        Toast.makeText(context, "PDF 压缩已取消", Toast.LENGTH_SHORT).show()
                    } else {
                        cancelled.addSuppressed(rollbackFailure)
                        state = state.copy(error = "压缩已取消，但文档恢复失败")
                        Log.e(TAG, "compression cancellation rollback failed", rollbackFailure)
                        Toast.makeText(context, "文档恢复失败，请立即另存为", Toast.LENGTH_LONG).show()
                    }
                }
                throw cancelled
            } catch (t: Throwable) {
                val rollback = snapshot
                val rollbackFailure = if (rollback == null) null else runCatching {
                    restoreDocumentSnapshot(rollback.file, rollback.reloadPassword)
                    state = state.copy(
                        pageIndex = stateBeforeEdit.pageIndex,
                        dirty = stateBeforeEdit.dirty,
                        canUndo = stateBeforeEdit.canUndo,
                        canRedo = stateBeforeEdit.canRedo,
                    )
                    refreshPageStructureState()
                    renderPage(state.pageIndex)
                    if (stateBeforeEdit.dirty) persistRecovery()
                }.exceptionOrNull()
                rollback?.dispose()
                snapshot = null
                if (rollbackFailure != null) t.addSuppressed(rollbackFailure)
                Log.e(TAG, "compression failed", t)
                val message = if (rollbackFailure == null) "PDF 压缩失败，文档已恢复" else "PDF 压缩失败，且无法恢复文档"
                state = state.copy(error = message)
                Toast.makeText(context, t.message ?: message, Toast.LENGTH_LONG).show()
                runCatching { onComplete(null) }
            } finally {
                compressionJob = null
                state = state.copy(busy = null)
            }
        }
    }

    fun cancelCompression() {
        compressionJob?.cancel()
    }

    private fun renderOcrPage(document: PDDocument, pageIndex: Int): Bitmap {
        val page = document.getPage(pageIndex)
        val crop = page.cropBox
        val size = fitPdfRenderSize(
            widthPoints = crop.width,
            heightPoints = crop.height,
            rotation = page.rotation,
            requestedScale = OCR_DPI / 72f,
            maxPixels = MAX_OCR_RENDER_PIXELS,
            maxDimension = MAX_OCR_RENDER_DIMENSION,
        )
        // long: OCR 使用受像素预算约束的整页渲染，既保证常规印刷文字有足够像素，也避免异常大页面耗尽堆内存。
        return PDFRenderer(document).renderImageWithDPI(pageIndex, size.scale * 72f)
    }

    fun addDocumentOutline(
        context: Context,
        title: String,
        pageIndex: Int,
        parentId: String?,
        onComplete: (Boolean) -> Unit = {},
    ) {
        applyDocumentEdit(
            context = context,
            busyLabel = "正在添加目录",
            failureLabel = "目录添加",
            onComplete = onComplete,
        ) { doc, index ->
            index.takeIf { PdfOutlineEditor.add(doc, title, pageIndex, parentId) }
        }
    }

    fun updateDocumentOutline(
        context: Context,
        id: String,
        title: String,
        pageIndex: Int,
        onComplete: (Boolean) -> Unit = {},
    ) {
        applyDocumentEdit(
            context = context,
            busyLabel = "正在更新目录",
            failureLabel = "目录更新",
            onComplete = onComplete,
        ) { doc, index ->
            index.takeIf { PdfOutlineEditor.update(doc, id, title, pageIndex) }
        }
    }

    fun moveDocumentOutline(
        context: Context,
        id: String,
        direction: PdfOutlineMove,
        onComplete: (Boolean) -> Unit = {},
    ) {
        applyDocumentEdit(
            context = context,
            busyLabel = "正在调整目录顺序",
            failureLabel = "目录移动",
            onComplete = onComplete,
        ) { doc, index ->
            index.takeIf { PdfOutlineEditor.move(doc, id, direction) }
        }
    }

    fun deleteDocumentOutline(
        context: Context,
        id: String,
        onComplete: (Boolean) -> Unit = {},
    ) {
        applyDocumentEdit(
            context = context,
            busyLabel = "正在删除目录",
            failureLabel = "目录删除",
            onComplete = onComplete,
        ) { doc, index ->
            index.takeIf { PdfOutlineEditor.delete(doc, id) }
        }
    }

    fun defaultFormFieldBounds(kind: PdfFormFieldCreateKind): Bounds? {
        val doc = document ?: return null
        val page = doc.getPage(state.pageIndex)
        val crop = page.cropBox
        val selected = state.selectedIds.singleOrNull()
            ?.let { id -> parsed?.let { pageState -> findNode(pageState.root, id) } }
            ?.bounds
            ?.takeIf(Bounds::isValid)
        val desiredWidth = when (kind) {
            PdfFormFieldCreateKind.CHECKBOX -> 24f
            PdfFormFieldCreateKind.TEXT,
            PdfFormFieldCreateKind.COMBO_BOX,
            -> selected?.width?.coerceAtLeast(140f) ?: (crop.width * 0.58f).coerceIn(140f, 300f)
        }.coerceAtMost(crop.width)
        val desiredHeight = when (kind) {
            PdfFormFieldCreateKind.CHECKBOX -> 24f
            PdfFormFieldCreateKind.TEXT,
            PdfFormFieldCreateKind.COMBO_BOX,
            -> selected?.height?.coerceAtLeast(34f) ?: 38f
        }.coerceAtMost(crop.height)
        val preferredLeft = selected?.minX ?: (crop.lowerLeftX + (crop.width - desiredWidth) / 2f)
        val preferredBottom = selected?.minY ?: (crop.lowerLeftY + (crop.height - desiredHeight) / 2f)
        val left = preferredLeft.coerceIn(crop.lowerLeftX, crop.upperRightX - desiredWidth)
        val bottom = preferredBottom.coerceIn(crop.lowerLeftY, crop.upperRightY - desiredHeight)
        return Bounds(left, bottom, left + desiredWidth, bottom + desiredHeight)
    }

    fun createFormField(
        context: Context,
        request: PdfFormFieldCreateRequest,
        onComplete: (Boolean) -> Unit = {},
    ) {
        applyDocumentEdit(
            context = context,
            busyLabel = "正在创建表单字段",
            failureLabel = "表单字段创建",
            permission = PdfOperation.DESIGN_FORM,
            onComplete = onComplete,
        ) { doc, index ->
            PdfFormEditor.create(doc, request)
            index
        }
    }

    fun deleteFormField(
        context: Context,
        index: Int,
        onComplete: (Boolean) -> Unit = {},
    ) {
        applyDocumentEdit(
            context = context,
            busyLabel = "正在删除表单字段",
            failureLabel = "表单字段删除",
            permission = PdfOperation.DESIGN_FORM,
            onComplete = onComplete,
        ) { doc, pageIndex ->
            pageIndex.takeIf { PdfFormEditor.delete(doc, index) }
        }
    }

    private fun applyDocumentPageEdit(
        context: Context,
        busyLabel: String,
        sourceIndex: Int = state.pageIndex,
        operation: (PDDocument, Int) -> Int,
    ) {
        applyDocumentEdit(
            context = context,
            busyLabel = busyLabel,
            failureLabel = "页面操作",
            permission = PdfOperation.ASSEMBLE,
            sourceIndex = sourceIndex,
        ) { doc, index -> operation(doc, index) }
    }

    private fun applyDocumentEdit(
        context: Context,
        busyLabel: String,
        failureLabel: String,
        permission: PdfOperation = PdfOperation.CONTENT,
        sourceIndex: Int = state.pageIndex,
        onComplete: (Boolean) -> Unit = {},
        operation: (PDDocument, Int) -> Int?,
    ) {
        if (document == null || state.mode != AppMode.EDIT || state.busy != null || !requirePermission(permission)) {
            runCatching { onComplete(false) }
            return
        }
        // long: 在启动异步任务前立即占用操作状态，避免连续点击让页面结构编辑与撤销同时进入历史事务。
        val stateBeforeEdit = state
        state = state.copy(busy = busyLabel, error = null)
        viewModelScope.launch {
            var snapshot: EditSnapshot.Document? = null
            try {
                val targetIndex = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val activeDocument = requireNotNull(document)
                        snapshot = createDocumentSnapshot(activeDocument, sourceIndex)
                        operation(activeDocument, sourceIndex)
                    }
                }
                if (targetIndex == null) {
                    snapshot?.dispose()
                    snapshot = null
                    runCatching { onComplete(true) }
                    return@launch
                }
                fullResyncCacheAndReopen()
                state = state.copy(pageIndex = targetIndex)
                refreshPageStructureState()
                val activeDocument = requireNotNull(document)
                state = state.copy(pageIndex = targetIndex.coerceIn(0, activeDocument.numberOfPages - 1))
                renderPage(state.pageIndex)
                pushDocumentUndo(requireNotNull(snapshot))
                snapshot = null
                state = state.copy(
                    dirty = true,
                    canUndo = true,
                    canRedo = false,
                )
                runCatching { persistRecovery() }.onFailure { recoveryFailure ->
                    Log.w(TAG, "document edit recovery persistence failed", recoveryFailure)
                    state = state.copy(error = "内容已更新，但恢复草稿保存失败")
                }
                runCatching { onComplete(true) }
            } catch (t: Throwable) {
                val rollback = snapshot
                var rollbackFailure: Throwable? = null
                if (rollback != null) {
                    rollbackFailure = runCatching {
                        // long: 页面树可能已经部分修改；从操作前完整快照换回文档实例，保证失败不会留下半完成页面结构。
                        restoreDocumentSnapshot(rollback.file, rollback.reloadPassword)
                        state = state.copy(
                            pageIndex = stateBeforeEdit.pageIndex,
                            dirty = stateBeforeEdit.dirty,
                            canUndo = stateBeforeEdit.canUndo,
                            canRedo = stateBeforeEdit.canRedo,
                        )
                        refreshPageStructureState()
                        renderPage(state.pageIndex)
                        if (stateBeforeEdit.dirty) persistRecovery()
                    }.exceptionOrNull()
                    rollback.dispose()
                    snapshot = null
                }
                if (rollbackFailure != null) t.addSuppressed(rollbackFailure)
                Log.e(TAG, "document edit failed", t)
                val message = if (rollbackFailure == null) "$failureLabel 失败，文档已恢复" else "$failureLabel 失败，且无法恢复文档"
                state = state.copy(error = message)
                Toast.makeText(context, t.message ?: message, Toast.LENGTH_LONG).show()
                runCatching { onComplete(false) }
            } finally {
                state = state.copy(busy = null)
            }
        }
    }

    private fun applySecurityEdit(
        context: Context,
        busyLabel: String,
        failureLabel: String,
        reloadPassword: String?,
        onComplete: (Boolean) -> Unit,
        operation: (PDDocument) -> Boolean,
    ) {
        val activeDocument = document
        if (activeDocument == null || state.mode != AppMode.EDIT || state.busy != null) {
            runCatching { onComplete(false) }
            return
        }
        if (activeDocument.isEncrypted && !activeDocument.currentAccessPermission.isOwnerPermission) {
            state = state.copy(error = "请使用所有者密码重新打开 PDF 后再修改保护设置")
            Toast.makeText(context, "需要所有者权限", Toast.LENGTH_LONG).show()
            runCatching { onComplete(false) }
            return
        }

        val stateBeforeEdit = state
        state = state.copy(busy = busyLabel, error = null)
        viewModelScope.launch {
            var snapshot: EditSnapshot.Document? = null
            try {
                val changed = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val doc = requireNotNull(document)
                        snapshot = createDocumentSnapshot(doc, stateBeforeEdit.pageIndex)
                        operation(doc)
                    }
                }
                if (!changed) {
                    snapshot?.dispose()
                    snapshot = null
                    runCatching { onComplete(true) }
                    return@launch
                }

                // long: 安全字典只有完整保存并按新密码重载后才真正生效，不能沿用普通编辑只刷新 PdfRenderer 的路径。
                reloadDocumentAfterSecurityChange(reloadPassword)
                refreshPageStructureState()
                renderPage(state.pageIndex)
                // long: 新密码重载、结构刷新和页面渲染全部成功后才提交历史；中途失败仍能用旧密码快照完整回滚。
                pushDocumentUndo(requireNotNull(snapshot))
                snapshot = null
                state = state.copy(
                    dirty = true,
                    canUndo = true,
                    canRedo = false,
                )
                runCatching { persistRecovery() }.onFailure { recoveryFailure ->
                    Log.w(TAG, "security recovery persistence failed", recoveryFailure)
                    state = state.copy(error = "保护设置已更新，但恢复草稿保存失败")
                }
                Toast.makeText(context, "PDF 保护设置已更新", Toast.LENGTH_SHORT).show()
                runCatching { onComplete(true) }
            } catch (t: Throwable) {
                val rollback = snapshot
                val rollbackFailure = if (rollback == null) null else runCatching {
                    restoreDocumentSnapshot(rollback.file, rollback.reloadPassword)
                    state = state.copy(
                        pageIndex = stateBeforeEdit.pageIndex,
                        dirty = stateBeforeEdit.dirty,
                        canUndo = stateBeforeEdit.canUndo,
                        canRedo = stateBeforeEdit.canRedo,
                    )
                    refreshPageStructureState()
                    renderPage(state.pageIndex)
                    if (stateBeforeEdit.dirty) persistRecovery()
                }.exceptionOrNull()
                rollback?.dispose()
                snapshot = null
                if (rollbackFailure != null) t.addSuppressed(rollbackFailure)
                Log.e(TAG, "security edit failed", t)
                val message = if (rollbackFailure == null) {
                    "$failureLabel 失败，文档已恢复"
                } else {
                    "$failureLabel 失败，且无法恢复文档"
                }
                state = state.copy(error = message)
                Toast.makeText(context, t.message ?: message, Toast.LENGTH_LONG).show()
                runCatching { onComplete(false) }
            } finally {
                state = state.copy(busy = null)
            }
        }
    }

    fun rotateCurrentPage(context: Context) {
        val doc = document ?: return
        if (state.mode != AppMode.EDIT || state.busy != null) return
        val pageIndex = state.pageIndex
        if (!beginPageEdit(PdfOperation.ASSEMBLE)) return
        viewModelScope.launch {
            state = state.copy(busy = "正在旋转页面", error = null)
            try {
                val before = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val page = doc.getPage(pageIndex)
                        val snapshot = createPageSnapshot(doc, pageIndex)
                        ElementEditor.rotatePage(doc, page, 90)
                        snapshot
                    }
                }
                pushUndo(pageIndex, before)
                state = state.copy(
                    dirty = true,
                    canUndo = true,
                    canRedo = false,
                )
                resyncCacheAndReopen()
                invalidateReaderDocumentContent()
                renderPage(pageIndex)
            } catch (t: Throwable) {
                Log.e(TAG, "page rotation failed", t)
                state = state.copy(error = "旋转页面失败")
                Toast.makeText(context, "旋转页面失败", Toast.LENGTH_LONG).show()
            } finally {
                restorePendingPageEdit()
                state = state.copy(busy = null)
            }
        }
    }

    fun rotatePages(context: Context, pageIndices: Set<Int>) {
        if (pageIndices.isEmpty()) return
        val stablePageIndices = pageIndices.toSet()
        applyDocumentPageEdit(context, "正在旋转 ${stablePageIndices.size} 页") { doc, index ->
            // long: 批量旋转共用一份文档快照，确保整批页面只占用一次撤销并可整体恢复。
            PageTreeEditor.rotateSelected(doc, stablePageIndices, degrees = 90)
            index
        }
    }

    fun addHighlightToSelection(context: Context) {
        addTextMarkupToSelection(context, TextMarkupKind.HIGHLIGHT)
    }

    fun addTextMarkupToSelection(context: Context, kind: TextMarkupKind) {
        val label = when (kind) {
            TextMarkupKind.HIGHLIGHT -> "高亮"
            TextMarkupKind.UNDERLINE -> "下划线"
            TextMarkupKind.STRIKEOUT -> "删除线"
        }
        applyAnnotation(context, "正在添加$label") { doc, page, bounds ->
            PdfAnnotationEditor.addTextMarkup(doc, page, bounds, kind)
        }
    }

    fun addNoteToSelection(context: Context, contents: String) {
        if (contents.isBlank()) return
        applyAnnotation(context, "正在添加便签") { doc, page, bounds ->
            PdfAnnotationEditor.addNote(doc, page, bounds, contents)
        }
    }

    fun addFreeTextToSelection(context: Context, contents: String) {
        if (contents.isBlank()) return
        applyAnnotation(context, "正在添加自由文本") { doc, page, bounds ->
            val font = fontCatalog?.resolveForText(doc, contents)
                ?: error("没有可编码此文本的字体")
            PdfAnnotationEditor.addFreeText(doc, page, bounds, contents, font)
            // long: FreeText 外观引用新嵌入字体，下一次缓存同步必须完整保存才能完成字体子集。
            embeddedFonts = true
        }
    }

    fun addStampToSelection(context: Context, kind: StampKind) {
        applyAnnotation(context, "正在添加${kind.displayName}图章") { doc, page, bounds ->
            val font = fontCatalog?.resolveForText(doc, kind.appearanceText)
                ?: error("没有可绘制图章的字体")
            PdfAnnotationEditor.addStamp(doc, page, bounds, kind, font)
            // long: 图章外观流使用嵌入字体，完整保存可保证目标 PDF 在其他阅读器中独立显示。
            embeddedFonts = true
        }
    }

    fun updateSelectedAnnotation(
        context: Context,
        colorArgb: Int,
        opacity: Float,
        contents: String,
        fontSize: Float,
    ) {
        val doc = document ?: return
        val annotationIndex = state.selectedAnnotationIndex ?: return
        val annotationInfo = state.annotations.firstOrNull { it.index == annotationIndex } ?: return
        if (state.mode != AppMode.EDIT || state.busy != null || !annotationInfo.editable) return
        if (
            annotationInfo.colorArgb == colorArgb &&
            annotationInfo.opacity == opacity &&
            annotationInfo.contents.orEmpty() == contents.trim() &&
            (annotationInfo.fontSize == null || annotationInfo.fontSize == fontSize)
        ) return
        val pageIndex = state.pageIndex
        if (!beginPageEdit(PdfOperation.ANNOTATE)) return
        viewModelScope.launch {
            state = state.copy(busy = "正在更新批注", error = null)
            try {
                val before = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val page = doc.getPage(pageIndex)
                        val snapshot = createPageSnapshot(doc, pageIndex)
                        val needsAppearanceFont = annotationInfo.subtype == PDAnnotationMarkup.SUB_TYPE_FREETEXT ||
                            annotationInfo.subtype == PDAnnotationRubberStamp.SUB_TYPE
                        val font = if (needsAppearanceFont) {
                            val sampleText = if (annotationInfo.subtype == PDAnnotationRubberStamp.SUB_TYPE) {
                                "STAMP"
                            } else {
                                contents
                            }
                            fontCatalog?.resolveForText(doc, sampleText)
                                ?: error("没有可编码此文本的字体")
                        } else {
                            null
                        }
                        check(
                            PdfAnnotationEditor.update(
                                document = doc,
                                page = page,
                                index = annotationIndex,
                                request = AnnotationEditRequest(
                                    colorArgb = colorArgb,
                                    opacity = opacity,
                                    contents = contents,
                                    font = font,
                                    fontSize = fontSize,
                                ),
                            ),
                        ) { "批注属性没有变化" }
                        if (font != null) embeddedFonts = true
                        snapshot
                    }
                }
                pushUndo(pageIndex, before)
                state = state.copy(dirty = true, canUndo = true, canRedo = false)
                resyncCacheAndReopen()
                invalidateReaderDocumentContent()
                renderPage(pageIndex)
            } catch (t: Throwable) {
                Log.e(TAG, "annotation update failed", t)
                state = state.copy(error = t.message ?: "更新批注失败")
                Toast.makeText(context, t.message ?: "更新批注失败", Toast.LENGTH_LONG).show()
            } finally {
                restorePendingPageEdit()
                state = state.copy(busy = null)
            }
        }
    }

    fun addSignature(context: Context, strokes: List<SignatureStroke>) {
        if (strokes.isEmpty()) return
        val doc = document ?: return
        if (state.mode != AppMode.EDIT || state.busy != null) return
        val pageIndex = state.pageIndex
        if (!beginPageEdit(PdfOperation.ANNOTATE)) return
        viewModelScope.launch {
            state = state.copy(busy = "正在添加签名", error = null)
            try {
                val before = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val page = doc.getPage(pageIndex)
                        val snapshot = createPageSnapshot(doc, pageIndex)
                        check(PdfAnnotationEditor.addSignature(doc, page, strokes)) {
                            "签名笔画为空"
                        }
                        snapshot
                    }
                }
                pushUndo(pageIndex, before)
                state = state.copy(dirty = true, canUndo = true, canRedo = false)
                resyncCacheAndReopen()
                invalidateReaderDocumentContent()
                renderPage(pageIndex)
            } catch (t: Throwable) {
                Log.e(TAG, "signature failed", t)
                state = state.copy(error = "添加签名失败")
                Toast.makeText(context, "添加签名失败", Toast.LENGTH_LONG).show()
            } finally {
                restorePendingPageEdit()
                state = state.copy(busy = null)
            }
        }
    }

    private fun applyAnnotation(
        context: Context,
        busyLabel: String,
        action: (PDDocument, com.tom_roush.pdfbox.pdmodel.PDPage, Bounds) -> Unit,
    ) {
        val doc = document ?: return
        if (state.mode != AppMode.EDIT || state.busy != null || state.selectedIds.size > 1) return
        val id = state.selectedIds.singleOrNull() ?: state.selectedId ?: return
        val node = findNode(parsed?.root ?: return, id) ?: return
        val bounds = node.bounds ?: run {
            Toast.makeText(context, "当前元素没有可用的页面范围", Toast.LENGTH_SHORT).show()
            return
        }
        val pageIndex = state.pageIndex
        if (!beginPageEdit(PdfOperation.ANNOTATE)) return
        viewModelScope.launch {
            state = state.copy(busy = busyLabel, error = null)
            try {
                val before = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val page = doc.getPage(pageIndex)
                        val snapshot = createPageSnapshot(doc, pageIndex)
                        action(doc, page, bounds)
                        snapshot
                    }
                }
                pushUndo(pageIndex, before)
                state = state.copy(dirty = true, canUndo = true, canRedo = false)
                resyncCacheAndReopen()
                invalidateReaderDocumentContent()
                renderPage(pageIndex)
            } catch (t: Throwable) {
                Log.e(TAG, "annotation failed", t)
                state = state.copy(error = "添加批注失败")
                Toast.makeText(context, "添加批注失败", Toast.LENGTH_LONG).show()
            } finally {
                restorePendingPageEdit()
                state = state.copy(busy = null)
            }
        }
    }

    fun switchMode(mode: AppMode) {
        if (!state.hasDocument || state.mode == mode || state.busy != null || state.loading) return
        if (mode == AppMode.EDIT && !permissions.canEdit) {
            state = state.copy(error = "当前 PDF 权限仅允许阅读")
            return
        }
        if (mode == AppMode.READ) {
            state = state.copy(mode = AppMode.READ, selectedId = null, selectedIds = emptySet(), editingId = null)
            ensureReaderPage(state.pageIndex, DEFAULT_READER_WIDTH_PX)
            startReaderIndexing()
            return
        }
        cancelReaderRendering()
        readerPages.entries.removeAll { it.value.loading }
        state = state.copy(busy = "正在进入编辑模式")
        viewModelScope.launch {
            searchJob?.cancelAndJoin()
            readerIndexJob?.cancelAndJoin()
            readerState = readerState.copy(indexing = false)
            state = state.copy(busy = "正在进入编辑模式")
            renderPage(state.pageIndex)
            state = state.copy(mode = AppMode.EDIT, busy = null)
        }
    }

    fun loadReaderLibrary(context: Context) {
        val appContext = context.applicationContext
        applicationContext = appContext
        readerHistory = ReaderPreferences(appContext).loadHistory()
        recoveryInfo = PdfRecoveryStore.load(appContext)
        refreshOriginalBackups(appContext)
    }

    fun resumeRecovery(context: Context) {
        val appContext = context.applicationContext
        applicationContext = appContext
        val recovery = PdfRecoveryStore.load(appContext) ?: run {
            recoveryInfo = null
            return
        }
        resumeRecoveryWithPassword(context, recovery, password = null)
    }

    private fun resumeRecoveryWithPassword(
        context: Context,
        recovery: PdfRecoveryInfo,
        password: String?,
    ) {
        val appContext = context.applicationContext
        applicationContext = appContext
        val generation = ++documentGeneration
        passwordPrompt = null
        state = state.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    prepareRecoveryDocument(appContext, recovery, generation, password)
                }
                if (generation != documentGeneration) {
                    loaded.dispose()
                    return@launch
                }
                adoptLoadedDocument(loaded)
                clearHistory()
                elementClipboard = null
                clearReaderCaches()
                fontCatalog = FontCatalog(appContext)
                embeddedFonts = false
                documentPassword = loaded.password
                val documentFeatures = PdfDocumentFeatureInspector.inspect(loaded.document)
                val initialPage = recovery.pageIndex.coerceIn(
                    0,
                    (loaded.document.numberOfPages - 1).coerceAtLeast(0),
                )
                state = state.copy(
                    loading = false,
                    hasDocument = true,
                    mode = AppMode.EDIT,
                    pageCount = loaded.document.numberOfPages,
                    pageIndex = initialPage,
                    fileName = loaded.title,
                    sourceUri = loaded.sourceUri,
                    canOverwrite = loaded.canOverwrite && !documentFeatures.requiresSafeCopy,
                    digitalSignatureCount = documentFeatures.digitalSignatureCount,
                    digitalSignatureInspectionFailed = documentFeatures.digitalSignatureInspectionFailed,
                    dirty = true,
                    documentSessionId = generation,
                    documentToken = state.documentToken + 1,
                    canUndo = false,
                    canRedo = false,
                )
                readerState = ReaderUiState(
                    pageInfos = loaded.pageInfos,
                    outline = loaded.outline,
                    links = loaded.links,
                )
                formFields = PdfFormEditor.list(loaded.document)
                pendingPasswordAction = null
                passwordPrompt = null
                renderPage(initialPage)
                // long: 恢复成功后立即把记录切到新的工作文件，旧进程留下的缓存随后才能安全删除。
                persistRecovery()
            } catch (t: Throwable) {
                Log.e(TAG, "recovery failed", t)
                if (generation == documentGeneration) {
                    if (t is InvalidPasswordException) {
                        requestPdfPassword(
                            PendingPdfPasswordAction.Recovery(recovery, recovery.title),
                            wrongPassword = password != null,
                        )
                    } else {
                        state = state.copy(loading = false, error = "无法恢复未保存的 PDF")
                    }
                }
            }
        }
    }

    fun discardRecovery(context: Context) {
        PdfRecoveryStore.clear(context.applicationContext, deleteFile = true)
        recoveryInfo = null
    }

    fun updateFormFields(
        context: Context,
        updates: Map<Int, List<String>>,
        onComplete: (Boolean) -> Unit = {},
    ) {
        if (!requirePermission(PdfOperation.FILL_FORM)) { onComplete(false); return }
        val doc = document ?: run {
            onComplete(false)
            return
        }
        if (state.mode != AppMode.EDIT || state.busy != null) {
            onComplete(false)
            return
        }
        if (updates.isEmpty()) {
            onComplete(true)
            return
        }
        state = state.copy(busy = "正在填写表单", error = null)
        viewModelScope.launch {
            var snapshot: EditSnapshot.Document? = null
            var updated = false
            try {
                updated = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        snapshot = createDocumentSnapshot(doc, state.pageIndex)
                        PdfFormEditor.update(doc, updates)
                    }
                }
                if (!updated) {
                    snapshot?.dispose()
                    snapshot = null
                    onComplete(true)
                    return@launch
                }
                pushDocumentUndo(requireNotNull(snapshot))
                snapshot = null
                state = state.copy(dirty = true, canUndo = true, canRedo = false)
                fullResyncCacheAndReopen()
                formFields = document?.let(PdfFormEditor::list).orEmpty()
                renderPage(state.pageIndex)
                Toast.makeText(context, "表单内容已更新", Toast.LENGTH_SHORT).show()
                onComplete(true)
            } catch (t: Throwable) {
                val failedSnapshot = snapshot
                if (failedSnapshot != null) {
                    val restoreFailure = runCatching {
                        restoreDocumentSnapshot(failedSnapshot.file, failedSnapshot.reloadPassword)
                        document?.let { formFields = PdfFormEditor.list(it) }
                        renderPage(state.pageIndex)
                    }.exceptionOrNull()
                    failedSnapshot.dispose()
                    if (restoreFailure != null) t.addSuppressed(restoreFailure)
                }
                Log.e(TAG, "form update failed", t)
                state = state.copy(error = t.message ?: "填写 PDF 表单失败")
                Toast.makeText(context, t.message ?: "填写表单失败", Toast.LENGTH_LONG).show()
                onComplete(false)
            } finally {
                state = state.copy(busy = null)
            }
        }
    }

    fun openHistory(context: Context, entry: ReaderHistoryEntry) {
        open(context, entry.uri.toUri(), AppMode.READ, entry.pageIndex)
    }

    fun removeReaderHistory(context: Context, entry: ReaderHistoryEntry) {
        val preferences = ReaderPreferences(context)
        preferences.removeHistory(entry.uri)
        readerHistory = preferences.loadHistory()
    }

    fun updateReaderPosition(context: Context, pageIndex: Int) {
        if (state.mode != AppMode.READ || pageIndex !in 0 until state.pageCount) return
        if (state.pageIndex == pageIndex) return
        state = state.copy(pageIndex = pageIndex)
        val sourceUri = state.sourceUri ?: return
        val preferences = ReaderPreferences(context)
        preferences.record(
            ReaderHistoryEntry(sourceUri, state.fileName, pageIndex, System.currentTimeMillis()),
        )
        readerHistory = preferences.loadHistory()
    }

    fun toggleReaderBookmark(context: Context, pageIndex: Int) {
        val sourceUri = state.sourceUri ?: return
        val updated = togglePageBookmark(readerState.bookmarks, pageIndex)
        ReaderPreferences(context).saveBookmarks(sourceUri, updated)
        readerState = readerState.copy(bookmarks = updated)
    }

    fun retainReaderPage(pageIndex: Int) { visibleReaderPages.add(pageIndex) }

    fun releaseReaderPage(pageIndex: Int, thumbnail: Boolean = false) {
        if (!thumbnail) visibleReaderPages.remove(pageIndex)
        val keep = (visibleReaderPages + state.pageIndex).flatMap { readerPrefetchPages(it, state.pageCount) }.toSet()
        readerRenderJobs.keys.toList().filter { key ->
            if (thumbnail) key == (true to pageIndex) else !key.first && key.second !in keep
        }.forEach { key ->
            readerRenderJobs.remove(key)?.cancel()
            readerRequestedWidths.remove(key)
            val target = if (key.first) readerThumbnails else readerPages
            if (target[key.second]?.loading == true) target.remove(key.second)
        }
    }

    private fun cancelReaderRendering() {
        readerRenderGeneration++
        readerRenderJobs.values.forEach { it.cancel() }
        readerRenderJobs.clear()
        readerRequestedWidths.clear()
        visibleReaderPages.clear()
    }

    fun ensureReaderPage(pageIndex: Int, targetWidthPx: Int) {
        ensureReaderImage(pageIndex, targetWidthPx, thumbnail = false)
    }

    fun prefetchReaderPages(pageIndex: Int, targetWidthPx: Int) {
        (listOf(pageIndex) + readerPrefetchPages(pageIndex, state.pageCount).filter { it != pageIndex }).forEach { targetPage ->
            ensureReaderImage(targetPage, targetWidthPx, thumbnail = false)
        }
    }

    fun ensureReaderThumbnail(pageIndex: Int) {
        ensureReaderImage(pageIndex, THUMBNAIL_WIDTH_PX, thumbnail = true)
    }

    private fun ensureReaderImage(pageIndex: Int, rawWidthPx: Int, thumbnail: Boolean) {
        val info = readerState.pageInfos.getOrNull(pageIndex) ?: return
        val widthPx = rawWidthPx.coerceIn(
            if (thumbnail) THUMBNAIL_WIDTH_PX else MIN_READER_WIDTH_PX,
            if (thumbnail) THUMBNAIL_WIDTH_PX else MAX_READER_WIDTH_PX,
        )
        val key = thumbnail to pageIndex
        readerRequestedWidths[key] = widthPx
        val target = if (thumbnail) readerThumbnails else readerPages
        val current = target[pageIndex]
        if (current?.loading == true) return
        if (current?.bitmap != null && current.widthPx == widthPx) {
            touchReaderCache(pageIndex, thumbnail)
            return
        }
        val generation = documentGeneration
        val renderGeneration = readerRenderGeneration
        target[pageIndex] = ReaderPageState(info = info, widthPx = widthPx, loading = true)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                do {
                    val requested = readerRequestedWidths[key] ?: return@launch
                    var rendered: Bitmap? = null
                    val bitmap = try { withContext(Dispatchers.IO) {
                        readerDataMutex.withLock {
                            check(generation == documentGeneration && renderGeneration == readerRenderGeneration) {
                                "文档已经切换"
                            }
                            renderReaderBitmap(pageIndex, info, requested, generation, thumbnail).also { rendered = it }
                        }
                    }
                    } catch (failure: Throwable) {
                        rendered?.recycle()
                        throw failure
                    }
                    if (generation != documentGeneration || renderGeneration != readerRenderGeneration) {
                        bitmap.recycle()
                        return@launch
                    }
                    // long: 渲染期间窗口宽度可能变化；旧分辨率不发布，接着处理最新请求。
                    if (readerRequestedWidths[key] != requested) {
                        bitmap.recycle()
                        continue
                    }
                    target[pageIndex] = ReaderPageState(info, requested, bitmap.asImageBitmap())
                    touchReaderCache(pageIndex, thumbnail)
                    return@launch
                } while (true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.e(TAG, "reader render failed page=$pageIndex", t)
                if (generation == documentGeneration && renderGeneration == readerRenderGeneration) {
                    target[pageIndex] = ReaderPageState(info, widthPx, error = "页面渲染失败，请重试")
                }
            } finally {
                if (readerRenderJobs[key] === coroutineContext[Job]) readerRenderJobs.remove(key)
            }
        }
        readerRenderJobs[key] = job
        job.start()
    }

    private suspend fun renderReaderBitmap(
        pageIndex: Int,
        info: ReaderPageInfo,
        widthPx: Int,
        generation: Int,
        thumbnail: Boolean,
    ): Bitmap {
        // long: 超长页面和缩略图同样受预算约束；仅限制宽度无法防止数百 MB 的单页分配。
        val size = fitPdfRenderSize(
            info.widthPoints, info.heightPoints, 0, widthPx / info.widthPoints,
            if (thumbnail) 64_000L else minOf(4_000_000L, readerCacheBudget() * 7L / 8L / 3L / 4L), 8192,
        )
        return try {
            renderWithPdfium(pageIndex, size.widthPx, size.heightPx, generation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (outOfMemory: OutOfMemoryError) {
            throw outOfMemory
        } catch (t: Throwable) {
            Log.w(TAG, "reader pdfium render failed page=$pageIndex", t)
            check(generation == documentGeneration) { "文档已经切换" }
            PDFRenderer(requireNotNull(document)).renderImage(pageIndex, size.scale)
        }
    }

    private fun readerCacheBudget(): Long = minOf(Runtime.getRuntime().maxMemory() / 8L, 64L * 1024 * 1024)

    private fun touchReaderCache(pageIndex: Int, thumbnail: Boolean) {
        val lru = if (thumbnail) readerThumbnailLru else readerPageLru
        val target = if (thumbnail) readerThumbnails else readerPages
        val limit = if (thumbnail) MAX_THUMBNAIL_CACHE else MAX_READER_PAGE_CACHE
        val totalBudget = readerCacheBudget()
        val budget = if (thumbnail) totalBudget / 8L else totalBudget * 7L / 8L
        lru.remove(pageIndex)
        lru.add(pageIndex)
        fun bytes(): Long = target.values.sumOf { it.bitmap?.let { b -> b.width.toLong() * b.height * 4L } ?: 0L }
        while (lru.size > 1 && (lru.size > limit || bytes() > budget)) {
            val oldest = lru.firstOrNull { it != state.pageIndex && (thumbnail || it !in visibleReaderPages) } ?: break
            lru.remove(oldest)
            target.remove(oldest)
            readerRequestedWidths.remove(thumbnail to oldest)
        }
    }

    private suspend fun readerPageIndex(
        pageIndex: Int,
        requireCoordinates: Boolean = false,
    ): ReaderPageTextIndex {
        if (pageIndex !in 0 until state.pageCount) return ReaderPageTextIndex(pageIndex, "")
        readerTextIndex[pageIndex]?.let { cached ->
            if (!requireCoordinates || cached.coordinatesAvailable) return cached
        }
        val generation = documentGeneration
        val indexed = withContext(Dispatchers.IO) {
            readerDataMutex.withLock {
                readerTextIndex[pageIndex]?.let { cached ->
                    if (!requireCoordinates || cached.coordinatesAvailable) return@withLock cached
                }
                val doc = document ?: return@withLock ReaderPageTextIndex(pageIndex, "")
                ReaderTextIndexer.indexPage(doc, pageIndex)
            }
        }
        if (generation == documentGeneration) readerTextIndex[pageIndex] = indexed
        return indexed
    }

    suspend fun readerPageText(pageIndex: Int): String {
        check(permissions.extract) { "当前 PDF 不允许复制文本" }
        return readerPageIndex(pageIndex).text.trim()
    }

    private fun startReaderIndexing() {
        val pageCount = state.pageCount
        if (pageCount <= 0 || readerIndexJob?.isActive == true) return
        val generation = documentGeneration
        val remaining = (0 until pageCount).filterNot(readerTextIndex::containsKey)
        if (remaining.isEmpty()) {
            readerState = readerState.copy(
                indexing = false,
                indexedPageCount = pageCount,
                searchProgress = pageCount,
            )
            return
        }
        readerState = readerState.copy(
            indexing = true,
            indexedPageCount = readerTextIndex.size.coerceAtMost(pageCount),
            searchProgress = readerTextIndex.size.coerceAtMost(pageCount),
        )
        readerIndexJob = viewModelScope.launch {
            var completedWithoutFailures = true
            remaining.forEach { pageIndex ->
                if (generation != documentGeneration) return@launch
                try {
                    readerPageIndex(pageIndex)
                } catch (cancelled: CancellationException) {
                    // long: 文档切换或进入编辑模式会取消后台索引，未处理页面必须留待下次继续，不能误记为无文本。
                    throw cancelled
                } catch (failure: Throwable) {
                    Log.w(TAG, "reader text index failed page=$pageIndex", failure)
                    completedWithoutFailures = false
                    readerTextIndex[pageIndex] = ReaderPageTextIndex(pageIndex, "")
                }
                val indexedCount = readerTextIndex.size.coerceAtMost(pageCount)
                readerState = readerState.copy(
                    indexedPageCount = indexedCount,
                    searchProgress = indexedCount,
                )
            }
            if (generation == documentGeneration) {
                readerState = readerState.copy(indexing = false, indexedPageCount = pageCount)
                // long: 临时解析失败可用空页结束本次搜索，但不能写入磁盘，否则漏搜会跨重启长期存在。
                if (completedWithoutFailures) persistReaderSearchIndex()
            }
        }
    }

    private suspend fun restoreReaderSearchIndex(context: Context) {
        val fingerprint = sourceFingerprint ?: return
        if (document?.isEncrypted == true || state.dirty || state.pageCount <= 0) return
        val restored = withContext(Dispatchers.IO) {
            ReaderSearchIndexStore(readerSearchIndexDirectory(context)).load(fingerprint, state.pageCount)
        } ?: return
        if (restored.size != state.pageCount) return
        restored.forEach { page -> readerTextIndex[page.pageIndex] = page }
        readerState = readerState.copy(
            indexing = false,
            indexedPageCount = restored.size,
            searchProgress = restored.size,
        )
    }

    private suspend fun persistReaderSearchIndex() {
        val context = applicationContext ?: return
        val fingerprint = sourceFingerprint ?: return
        if (document?.isEncrypted == true || state.dirty || readerTextIndex.size != state.pageCount) return
        val pages = (0 until state.pageCount).mapNotNull(readerTextIndex::get)
        if (pages.size != state.pageCount) return
        withContext(Dispatchers.IO) {
            ReaderSearchIndexStore(readerSearchIndexDirectory(context)).save(fingerprint, pages)
        }
    }

    private fun readerSearchIndexDirectory(context: Context): File =
        File(context.noBackupFilesDir, "reader-search-index")

    fun searchReader(rawQuery: String) {
        val query = rawQuery.trim()
        searchJob?.cancel()
        if (query.isEmpty()) {
            readerState = readerState.copy(
                searchQuery = "",
                searchResults = emptyList(),
            searchLimitReached = false,
                searching = false,
                searchProgress = 0,
                searchError = null,
                activeSearchMatchIndex = null,
            )
            return
        }
        val generation = documentGeneration
        startReaderIndexing()
        readerState = readerState.copy(
            searchQuery = query,
            searchResults = emptyList(),
            searchLimitReached = false,
            searching = true,
            searchProgress = 0,
            searchError = null,
        )
        searchJob = viewModelScope.launch {
            try {
                readerIndexJob?.join()
                if (generation == documentGeneration) {
                    val pages = (0 until state.pageCount).mapNotNull(readerTextIndex::get)
                    val results = withContext(Dispatchers.Default) {
                        val searchContext = coroutineContext
                        searchReaderIndex(pages, query) { searchContext.ensureActive() }
                    }
                    if (generation != documentGeneration || query != readerState.searchQuery) return@launch
                    readerState = readerState.copy(
                        searchResults = results,
                        searchLimitReached = results.size >= MAX_READER_SEARCH_RESULTS,
                        searching = false,
                        activeSearchMatchIndex = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                // long: 用户改用新关键词或关闭搜索面板时会主动取消旧任务，取消属于正常控制流，不能显示成搜索失败。
                throw cancelled
            } catch (t: Throwable) {
                Log.e(TAG, "reader search failed", t)
                if (generation == documentGeneration) {
                    readerState = readerState.copy(searching = false, searchError = "全文搜索失败")
                }
            }
        }
    }

    fun clearReaderSearch() {
        searchJob?.cancel()
        readerState = readerState.copy(
            searchQuery = "",
            searchResults = emptyList(),
            searchLimitReached = false,
            searching = false,
            searchProgress = 0,
            searchError = null,
            activeSearchMatchIndex = null,
        )
    }

    fun activateReaderSearchResult(matchIndex: Int) {
        val selected = readerState.searchResults.firstOrNull { it.matchIndex == matchIndex } ?: return
        readerState = readerState.copy(activeSearchMatchIndex = matchIndex)
        val page = readerTextIndex[selected.pageIndex]
        if (page?.coordinatesAvailable == true) return
        val generation = documentGeneration
        val query = readerState.searchQuery
        viewModelScope.launch {
            try {
                readerPageIndex(selected.pageIndex, requireCoordinates = true)
                if (
                    generation != documentGeneration ||
                    query != readerState.searchQuery ||
                    readerState.activeSearchMatchIndex != matchIndex
                ) return@launch
                val pages = (0 until state.pageCount).mapNotNull(readerTextIndex::get)
                val results = withContext(Dispatchers.Default) {
                    val searchContext = coroutineContext
                    searchReaderIndex(pages, query) { searchContext.ensureActive() }
                }
                if (generation != documentGeneration || query != readerState.searchQuery || readerState.activeSearchMatchIndex != matchIndex) return@launch
                readerState = readerState.copy(
                    searchResults = results,
                    activeSearchMatchIndex = matchIndex,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.w(TAG, "reader search highlight load failed page=${selected.pageIndex}", failure)
            }
        }
    }

    fun moveReaderSearchResult(delta: Int): ReaderSearchResult? {
        val target = moveReaderSearchSelection(
            results = readerState.searchResults,
            currentMatchIndex = readerState.activeSearchMatchIndex,
            delta = delta,
        ) ?: return null
        activateReaderSearchResult(target.matchIndex)
        return target
    }

    fun select(id: Int?, reveal: Boolean = false) {
        state = state.copy(
            selectedId = id,
            selectedIds = id?.let(::setOf) ?: emptySet(),
            selectedAnnotationIndex = null,
            editingId = null,
            revealTick = if (reveal) state.revealTick + 1 else state.revealTick,
        )
    }

    fun selectAnnotation(index: Int?) {
        if (index != null && state.annotations.none { it.index == index }) return
        state = state.copy(
            selectedAnnotationIndex = index,
            selectedId = null,
            selectedIds = emptySet(),
            editingId = null,
        )
    }

    fun deleteSelectedAnnotation(context: Context) {
        val annotationIndex = state.selectedAnnotationIndex ?: return
        val doc = document ?: return
        if (state.mode != AppMode.EDIT || state.busy != null) return
        val pageIndex = state.pageIndex
        if (!beginPageEdit(PdfOperation.ANNOTATE)) return
        viewModelScope.launch {
            state = state.copy(busy = "正在删除批注", error = null)
            try {
                val before = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val page = doc.getPage(pageIndex)
                        val snapshot = createPageSnapshot(doc, pageIndex)
                        check(PdfAnnotationEditor.delete(doc, page, annotationIndex)) {
                            "批注已经不存在"
                        }
                        snapshot
                    }
                }
                pushUndo(pageIndex, before)
                state = state.copy(
                    dirty = true,
                    canUndo = true,
                    canRedo = false,
                    selectedAnnotationIndex = null,
                )
                resyncCacheAndReopen()
                invalidateReaderDocumentContent()
                renderPage(pageIndex)
            } catch (t: Throwable) {
                Log.e(TAG, "annotation delete failed", t)
                state = state.copy(error = "删除批注失败")
                Toast.makeText(context, "删除批注失败", Toast.LENGTH_LONG).show()
            } finally {
                restorePendingPageEdit()
                state = state.copy(busy = null)
            }
        }
    }

    fun moveSelectedAnnotation(context: Context, dx: Float, dy: Float) {
        val annotationIndex = state.selectedAnnotationIndex ?: return
        val doc = document ?: return
        if (state.mode != AppMode.EDIT || state.busy != null || !dx.isFinite() || !dy.isFinite()) return
        val pageIndex = state.pageIndex
        if (!beginPageEdit(PdfOperation.ANNOTATE)) return
        viewModelScope.launch {
            state = state.copy(busy = "正在移动批注", error = null)
            try {
                val before = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val page = doc.getPage(pageIndex)
                        val snapshot = createPageSnapshot(doc, pageIndex)
                        check(PdfAnnotationEditor.move(doc, page, annotationIndex, dx, dy)) {
                            "批注无法移动"
                        }
                        snapshot
                    }
                }
                pushUndo(pageIndex, before)
                state = state.copy(dirty = true, canUndo = true, canRedo = false)
                resyncCacheAndReopen()
                invalidateReaderDocumentContent()
                renderPage(pageIndex)
            } catch (t: Throwable) {
                Log.e(TAG, "annotation move failed", t)
                state = state.copy(error = "移动批注失败")
                Toast.makeText(context, "移动批注失败", Toast.LENGTH_LONG).show()
            } finally {
                restorePendingPageEdit()
                state = state.copy(busy = null)
            }
        }
    }

    /** long: 长按只在页面级叶子对象之间切换多选，文本 run 和分组继续保留原有编辑语义。 */
    fun toggleSelection(id: Int, reveal: Boolean = false) {
        val page = parsed ?: return
        if (page.leaves.none { it.id == id }) return
        val next = state.selectedIds.toMutableSet().apply {
            if (!add(id)) remove(id)
        }
        state = state.copy(
            selectedIds = next,
            selectedId = when {
                id in next -> id
                next.isEmpty() -> null
                else -> next.last()
            },
            selectedAnnotationIndex = null,
            editingId = null,
            revealTick = if (reveal) state.revealTick + 1 else state.revealTick,
        )
    }

    /** long: 单选文字兼容系统剪贴板；多选对象保留同流 token 和联合边界，粘贴后仍维持原相对布局。 */
    fun copySelectedElement(context: Context) {
        if (!requirePermission(PdfOperation.EXTRACT)) return
        val page = parsed ?: return
        if (state.selectedIds.size > 1) {
            val nodes = state.selectedIds.mapNotNull { findNode(page.root, it) }
                .sortedBy(DrawNode::startIndex)
            val stream = nodes.firstOrNull()?.stream
            val supported = nodes.size == state.selectedIds.size &&
                stream != null && stream.owner is StreamOwner.Page &&
                nodes.all { node ->
                    val isWholeText = node.kind == NodeKind.TEXT &&
                        (page.tokens.getOrNull(node.startIndex) as? Operator)?.name == "BT"
                    !node.locked && node.bounds != null &&
                        node.stream === stream &&
                        (node.kind == NodeKind.PATH || node.kind == NodeKind.IMAGE || isWholeText)
                } && nodes.zipWithNext().none { (left, right) -> left.endIndex >= right.startIndex }
            if (!supported) {
                Toast.makeText(context, "多选复制仅支持同一页面内容流中的路径、图片和完整文本对象", Toast.LENGTH_SHORT).show()
                return
            }
            val bounds = Bounds(
                nodes.minOf { it.bounds!!.minX },
                nodes.minOf { it.bounds!!.minY },
                nodes.maxOf { it.bounds!!.maxX },
                nodes.maxOf { it.bounds!!.maxY },
            )
            elementClipboard = ElementClipboard.Group(
                nodes = nodes.map { node ->
                    RawPasteNode(
                        tokens = stream!!.tokens.subList(node.startIndex, node.endIndex + 1).toList(),
                        sourceResources = stream.resources,
                    )
                },
                bounds = bounds.copyBounds(),
                sourcePageIndex = state.pageIndex,
            )
            state = state.copy(hasElementClipboard = true)
            Toast.makeText(context, "已复制 ${nodes.size} 个元素，可粘贴到当前 PDF", Toast.LENGTH_SHORT).show()
            return
        }
        val node = findNode(page.root, state.selectedId) ?: return
        val bounds = node.bounds ?: run {
            Toast.makeText(context, "当前元素没有可复制的范围", Toast.LENGTH_SHORT).show()
            return
        }
        when (node.kind) {
            NodeKind.TEXT -> {
                val text = node.text.orEmpty()
                if (text.isBlank()) return
                elementClipboard = ElementClipboard.Text(
                    text = text,
                    fontSize = node.fontSize.coerceAtLeast(12f),
                    fillArgb = node.colorArgb ?: 0xFF202124.toInt(),
                    bounds = bounds.copyBounds(),
                    sourcePageIndex = state.pageIndex,
                )
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("PDF 文本", text))
            }
            NodeKind.PATH, NodeKind.IMAGE -> {
                val stream = node.stream ?: return
                val copied = stream.tokens.subList(node.startIndex, node.endIndex + 1).toList()
                elementClipboard = ElementClipboard.Raw(
                    kind = node.kind,
                    tokens = copied,
                    sourceResources = stream.resources,
                    bounds = bounds.copyBounds(),
                    sourcePageIndex = state.pageIndex,
                )
            }
            NodeKind.GROUP -> {
                Toast.makeText(context, "请选择具体元素后再复制", Toast.LENGTH_SHORT).show()
                return
            }
        }
        state = state.copy(hasElementClipboard = true)
        Toast.makeText(context, "已复制元素，可粘贴到当前 PDF", Toast.LENGTH_SHORT).show()
    }

    /** long: 粘贴内部元素剪贴板；没有内部对象时仍允许通过已有文本入口粘贴。 */
    fun pasteElement(context: Context) {
        val clip = elementClipboard
        if (clip == null) {
            pasteText(context)
            return
        }
        val doc = document ?: return
        val parsedPage = parsed ?: return
        val pageIndex = state.pageIndex
        if (!beginPageEdit()) return
        viewModelScope.launch {
            var mutationApplied = false
            try {
                state = state.copy(busy = "正在粘贴元素", error = null)
                val execution = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val page = doc.getPage(pageIndex)
                        val before = createPageSnapshot(doc, pageIndex)
                        val crop = page.cropBox
                        val result = when (clip) {
                            is ElementClipboard.Text -> {
                                val catalog = fontCatalog ?: error("字体目录未就绪")
                                val font = catalog.resolveForText(doc, clip.text)
                                    ?: error("没有可编码此文本的字体")
                                val x = if (clip.sourcePageIndex == pageIndex) {
                                    clip.bounds.minX + 24f
                                } else {
                                    crop.lowerLeftX + (crop.width - clip.bounds.width) / 2f
                                }
                                val y = if (clip.sourcePageIndex == pageIndex) {
                                    clip.bounds.minY + 24f
                                } else {
                                    crop.lowerLeftY + crop.height / 2f
                                }
                                ElementEditor.insertText(
                                    doc,
                                    page,
                                    parsedPage.tokens,
                                    TextInsertRequest(
                                        text = clip.text,
                                        x = x,
                                        y = y,
                                        fontSize = clip.fontSize,
                                        font = font,
                                        fillArgb = clip.fillArgb,
                                    ),
                                )
                            }
                            is ElementClipboard.Raw -> {
                                val dx = if (clip.sourcePageIndex == pageIndex) 24f
                                else crop.lowerLeftX + crop.width / 2f - (clip.bounds.minX + clip.bounds.maxX) / 2f
                                val dy = if (clip.sourcePageIndex == pageIndex) 24f
                                else crop.lowerLeftY + crop.height / 2f - (clip.bounds.minY + clip.bounds.maxY) / 2f
                                ElementEditor.pasteNode(
                                    doc,
                                    page,
                                    parsedPage.tokens,
                                    clip.tokens,
                                    clip.sourceResources,
                                    dx,
                                    dy,
                                )
                            }
                            is ElementClipboard.Group -> {
                                val dx = if (clip.sourcePageIndex == pageIndex) 24f
                                else crop.lowerLeftX + crop.width / 2f - (clip.bounds.minX + clip.bounds.maxX) / 2f
                                val dy = if (clip.sourcePageIndex == pageIndex) 24f
                                else crop.lowerLeftY + crop.height / 2f - (clip.bounds.minY + clip.bounds.maxY) / 2f
                                ElementEditor.pasteNodes(
                                    doc,
                                    page,
                                    parsedPage.tokens,
                                    clip.nodes,
                                    dx,
                                    dy,
                                )
                            }
                        }
                        PasteExecution(result, before, clip is ElementClipboard.Text)
                    }
                }
                when (execution.result) {
                    is EditResult.Applied -> {
                        pushUndo(pageIndex, execution.before)
                        if (execution.embeddedFont) embeddedFonts = true
                        mutationApplied = true
                        state = state.copy(dirty = true, canUndo = true, canRedo = false)
                        resyncCacheAndReopen()
                        renderPage(pageIndex)
                    }
                    else -> Toast.makeText(context, "无法粘贴此元素", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "paste failed", t)
                state = state.copy(
                    error = "粘贴元素失败",
                    dirty = state.dirty || mutationApplied,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
                Toast.makeText(context, "粘贴元素失败", Toast.LENGTH_LONG).show()
            } finally {
                restorePendingPageEdit()
                state = state.copy(busy = null)
            }
        }
    }

    fun toggleExpand(id: Int) {
        val e = state.expanded
        state = state.copy(expanded = if (id in e) e - id else e + id)
    }

    fun toggleRaw() {
        state = state.copy(showRaw = !state.showRaw)
    }

    fun toggleSnap() {
        state = state.copy(snapEnabled = !state.snapEnabled)
    }

    fun canRedactSelection(selection: Set<Int> = state.selectedIds): Boolean {
        if (!permissions.content) return false
        val doc = document ?: return false
        val page = parsed ?: return false
        if (selection.isEmpty()) return false
        val nodes = redactionNodes(page, selection) ?: return false
        val owner = page.pageStream.owner as? StreamOwner.Page ?: return false
        return ElementEditor.canRedactNodes(doc, owner.page, nodes)
    }

    fun redactSelected(context: Context, selection: Set<Int> = state.selectedIds) {
        if (!canRedactSelection(selection)) {
            Toast.makeText(context, "当前选择无法安全永久遮盖", Toast.LENGTH_SHORT).show()
            return
        }
        val pageState = parsed ?: return
        val nodes = redactionNodes(pageState, selection) ?: return
        applyDocumentEdit(
            context = context,
            busyLabel = "正在永久遮盖",
            failureLabel = "永久遮盖",
            onComplete = { success ->
                if (success) Toast.makeText(context, "所选内容已永久遮盖", Toast.LENGTH_SHORT).show()
            },
        ) { doc, pageIndex ->
            val page = doc.getPage(pageIndex)
            check(ElementEditor.redactNodes(doc, page, nodes) is EditResult.Applied) {
                "所选内容无法永久遮盖"
            }
            pageIndex
        }
    }

    private fun redactionNodes(page: ParsedPage, selection: Set<Int>): List<DrawNode>? {
        val visibleNodes = selection.mapNotNull { id -> findNode(page.root, id) }
        if (visibleNodes.size != selection.size) return null
        val selectedBounds = visibleNodes.mapNotNull(DrawNode::bounds)
        if (selectedBounds.size != visibleNodes.size) return null
        // long: 永久遮盖可见对象时同步删除相交的 OCR 隐藏文字，否则扫描图片虽被移除，搜索仍可能泄露原文。
        val hiddenOcr = page.ocrLeaves.filter { ocr ->
            val ocrBounds = ocr.bounds ?: return@filter false
            selectedBounds.any { selected -> selected.overlaps(ocrBounds) }
        }
        return visibleNodes + hiddenOcr
    }

    fun deleteSelected(context: Context) {
        val doc = document ?: return
        val parsedPage = parsed ?: return
        val nodes = state.selectedIds.mapNotNull { findNode(parsedPage.root, it) }
            .ifEmpty { listOfNotNull(findNode(parsedPage.root, state.selectedId)) }
        if (nodes.isEmpty()) return
        if (nodes.any { it.locked }) {
            Toast.makeText(context, "请先解锁所选元素", Toast.LENGTH_SHORT).show()
            return
        }
        val pageIndex = state.pageIndex
        val batch = nodes.size > 1
        if (batch && nodes.any { it.stream?.owner !is StreamOwner.Page || it.kind == NodeKind.GROUP }) {
            Toast.makeText(context, "多选删除仅支持页面级文本、路径和图片", Toast.LENGTH_SHORT).show()
            return
        }
        if (!beginPageEdit()) return
        viewModelScope.launch {
            state = state.copy(busy = "正在删除")
            var mutationApplied = false
            try {
                val before = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        check(document === doc) { "文档已经切换" }
                    val page = doc.getPage(pageIndex)
                    val stream = requireNotNull(nodes.first().stream) { "Element has no content-stream owner" }
                    // long: Form 编辑会重绑定资源，独立文档快照同时保存内容和调用关系。
                    val snapshot = createPageSnapshot(doc, pageIndex)
                    if (batch) {
                        check(ElementEditor.deleteNodes(doc, page, nodes) is EditResult.Applied) {
                            "无法批量删除所选元素"
                        }
                    } else {
                        ElementEditor.deleteNode(doc, page, nodes.single())
                    }
                    snapshot
                    }
                }
                // long: 一批元素共用一份独立文档快照，所以整次删除只登记一条撤销记录。
                pushUndo(pageIndex, before)
                mutationApplied = true
                state = state.copy(dirty = true, canUndo = true, canRedo = false)
                resyncCacheAndReopen()
                renderPage(pageIndex)
            } catch (t: Throwable) {
                Log.e(TAG, "delete failed", t)
                state = state.copy(
                    error = "删除元素失败",
                    dirty = state.dirty || mutationApplied,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
            } finally {
                restorePendingPageEdit()
                state = state.copy(busy = null)
            }
        }
    }

    fun beginEdit(id: Int) {
        if (state.busy != null || !requirePermission(PdfOperation.CONTENT)) return
        val node = findNode(parsed?.root ?: return, id) ?: return
        if (node.locked) return
        state = state.copy(selectedId = id, selectedIds = setOf(id), editingId = id)
    }

    fun toggleElementLock(context: Context, id: Int) {
        val doc = document ?: return
        val node = findNode(parsed?.root ?: return, id) ?: return
        if (state.mode != AppMode.EDIT || state.busy != null) return
        val pageIndex = state.pageIndex
        val willLock = !node.locked
        if (!beginPageEdit()) return
        viewModelScope.launch {
            var mutationApplied = false
            try {
                state = state.copy(
                    busy = if (willLock) "正在锁定元素" else "正在解锁元素",
                    error = null,
                    editingId = null,
                )
                val before = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val page = doc.getPage(pageIndex)
                        val targetStream = if (willLock) node.stream else node.locks.lastOrNull()?.stream
                        requireNotNull(targetStream) { "元素没有可写入的内容流" }
                        // long: Form 锁定会改写资源，完整快照保证跨页面树操作后仍可撤销。
                        val snapshot = createPageSnapshot(doc, pageIndex)
                        check(ElementEditor.setLocked(doc, page, node, willLock) is EditResult.Applied) {
                            "元素锁定状态没有变化"
                        }
                        snapshot
                    }
                }
                pushUndo(pageIndex, before)
                mutationApplied = true
                state = state.copy(dirty = true, canUndo = true, canRedo = false)
                resyncCacheAndReopen()
                renderPage(pageIndex)
                Toast.makeText(context, if (willLock) "已锁定元素" else "已解锁元素", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.e(TAG, "element lock failed", t)
                state = state.copy(
                    error = if (willLock) "锁定元素失败" else "解锁元素失败",
                    dirty = state.dirty || mutationApplied,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
                Toast.makeText(context, state.error, Toast.LENGTH_LONG).show()
            } finally {
                restorePendingPageEdit()
                state = state.copy(busy = null)
            }
        }
    }

    fun cancelEdit() {
        state = state.copy(editingId = null)
    }

    fun editTarget(): EditTarget? {
        val parsedPage = parsed ?: return null
        val node = findNode(parsedPage.root, state.editingId ?: return null) ?: return null
        val tokens = node.stream?.tokens ?: parsedPage.tokens
        val caps = ElementEditor.capabilities(tokens, node)
        val b = node.bounds
        return EditTarget(
            node = node,
            caps = caps,
            x = b?.minX ?: 0f,
            y = b?.minY ?: 0f,
            w = b?.width ?: 0f,
            h = b?.height ?: 0f,
            fillArgb = if (caps.canFill) {
                state.swatchColors[node.id] ?: node.fillArgb ?: node.colorArgb
            } else {
                null
            },
            strokeArgb = if (caps.canStroke) node.strokeArgb ?: node.colorArgb else null,
            text = if (caps.canText) node.text else null,
            colorSpace = node.colorSpace,
            fontSize = node.fontSize,
            characterSpacing = node.characterSpacing,
            lineLeading = node.lineLeading,
            opacity = if (node.kind == NodeKind.PATH && caps.canStroke && !caps.canFill) {
                node.strokingAlpha
            } else {
                node.nonStrokingAlpha
            },
            blendMode = node.blendMode,
            lineWidth = node.lineWidth,
            lineCap = node.lineCap,
            lineJoin = node.lineJoin,
            miterLimit = node.miterLimit,
            cropLeft = node.imageCrop?.left ?: 0f,
            cropTop = node.imageCrop?.top ?: 0f,
            cropRight = node.imageCrop?.right ?: 0f,
            cropBottom = node.imageCrop?.bottom ?: 0f,
            fontOptions = if (caps.canText) fontCatalog?.options() ?: emptyList() else emptyList(),
        )
    }

    fun applyEdit(context: Context, request: EditRequest, replacementImageUri: Uri? = null) {
        val node = findNode(parsed?.root ?: return, state.editingId) ?: return
        applyEditInternal(context, node, request, replacementImageUri)
    }

    fun insertTextAtCenter(context: Context, text: String) {
        val doc = document ?: return
        val parsedPage = parsed ?: return
        if (text.isBlank()) return
        val pageIndex = state.pageIndex
        if (!beginPageEdit()) return
        viewModelScope.launch {
            var mutationApplied = false
            try {
                state = state.copy(busy = "正在插入文本", error = null)
                val execution = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val page = doc.getPage(pageIndex)
                        val box = page.cropBox
                        // long: 操作前快照必须早于加载本次字体，不能把尚未使用的字体提前完成为空子集。
                        val before = createPageSnapshot(doc, pageIndex)
                        val catalog = fontCatalog ?: error("字体目录未就绪")
                        val font = catalog.resolveForText(doc, text) ?: error("没有可编码此文本的字体")
                        val request = TextInsertRequest(
                            text = text,
                            x = box.lowerLeftX + box.width / 2f,
                            y = box.lowerLeftY + box.height / 2f,
                            fontSize = 18f,
                            font = font,
                            fillArgb = 0xFF202124.toInt(),
                        )
                        ElementEditor.insertText(doc, page, parsedPage.tokens, request) to before
                    }
                }
                when (val result = execution.first) {
                    is EditResult.Applied -> {
                        pushUndo(pageIndex, execution.second)
                        embeddedFonts = true
                        mutationApplied = true
                        state = state.copy(dirty = true, canUndo = true, canRedo = false)
                        resyncCacheAndReopen()
                        renderPage(pageIndex)
                    }
                    is EditResult.TextEncodeFailed ->
                        Toast.makeText(context, "默认字体无法编码此文本，请先导入字体", Toast.LENGTH_LONG).show()
                    EditResult.NoChange -> Unit
                    EditResult.Degenerate ->
                        Toast.makeText(context, "无法插入文本", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "insert text failed", t)
                state = state.copy(
                    error = "插入文本失败",
                    dirty = state.dirty || mutationApplied,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
                Toast.makeText(context, "插入文本失败", Toast.LENGTH_LONG).show()
            } finally {
                restorePendingPageEdit()
                state = state.copy(busy = null)
            }
        }
    }

    fun pasteText(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(context, "剪贴板中没有文本", Toast.LENGTH_SHORT).show()
        } else {
            insertTextAtCenter(context, text)
        }
    }

    fun insertImage(context: Context, uri: Uri) {
        val doc = document ?: return
        val parsedPage = parsed ?: return
        val pageIndex = state.pageIndex
        if (!beginPageEdit()) return
        viewModelScope.launch {
            var mutationApplied = false
            try {
                state = state.copy(busy = "正在插入图片", error = null)
                val execution = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val bytes = context.contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "无法读取图片" }.readBytes()
                        }
                        val page = doc.getPage(pageIndex)
                        val box = page.cropBox
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        val sourceW = bounds.outWidth.takeIf { it > 0 } ?: 1
                        val sourceH = bounds.outHeight.takeIf { it > 0 } ?: 1
                        val maxW = (box.width * 0.55f).coerceAtLeast(72f)
                        val maxH = (box.height * 0.45f).coerceAtLeast(72f)
                        val factor = minOf(maxW / sourceW, maxH / sourceH)
                        val width = (sourceW * factor).coerceAtLeast(24f)
                        val height = (sourceH * factor).coerceAtLeast(24f)
                        val before = createPageSnapshot(doc, pageIndex)
                        val request = ImageInsertRequest(
                            bytes = bytes,
                            x = box.lowerLeftX + (box.width - width) / 2f,
                            y = box.lowerLeftY + (box.height - height) / 2f,
                            width = width,
                            height = height,
                        )
                        ElementEditor.insertImage(doc, page, parsedPage.tokens, request) to before
                    }
                }
                when (execution.first) {
                    is EditResult.Applied -> {
                        pushUndo(pageIndex, execution.second)
                        mutationApplied = true
                        state = state.copy(dirty = true, canUndo = true, canRedo = false)
                        resyncCacheAndReopen()
                        renderPage(pageIndex)
                    }
                    else -> Toast.makeText(context, "无法插入此图片", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "insert image failed", t)
                state = state.copy(
                    error = "插入图片失败",
                    dirty = state.dirty || mutationApplied,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
                Toast.makeText(context, "插入图片失败", Toast.LENGTH_LONG).show()
            } finally {
                restorePendingPageEdit()
                state = state.copy(busy = null)
            }
        }
    }

    fun insertShape(context: Context, kind: ShapeKind) {
        val doc = document ?: return
        val parsedPage = parsed ?: return
        val pageIndex = state.pageIndex
        val shapeName = if (kind == ShapeKind.RECTANGLE) "矩形" else "直线"
        if (!beginPageEdit()) return
        viewModelScope.launch {
            var mutationApplied = false
            try {
                state = state.copy(busy = "正在插入$shapeName", error = null)
                val execution = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val page = doc.getPage(pageIndex)
                        val box = page.cropBox
                        val width = minOf(180f, box.width * 0.36f).coerceAtLeast(24f)
                        val height = minOf(96f, box.height * 0.16f).coerceAtLeast(24f)
                        val before = createPageSnapshot(doc, pageIndex)
                        val request = when (kind) {
                            ShapeKind.RECTANGLE -> ShapeInsertRequest(
                                kind = kind,
                                x = box.lowerLeftX + (box.width - width) / 2f,
                                y = box.lowerLeftY + (box.height - height) / 2f,
                                width = width,
                                height = height,
                                fillArgb = 0xFFE8F0FE.toInt(),
                                strokeArgb = 0xFF2563EB.toInt(),
                                lineWidth = 2f,
                                lineJoin = 1,
                            )
                            ShapeKind.LINE -> ShapeInsertRequest(
                                kind = kind,
                                x = box.lowerLeftX + (box.width - width) / 2f,
                                y = box.lowerLeftY + box.height / 2f,
                                width = width,
                                height = 0f,
                                strokeArgb = 0xFF202124.toInt(),
                                lineWidth = 2f,
                                lineCap = 1,
                            )
                        }
                        ElementEditor.insertShape(doc, page, parsedPage.tokens, request) to before
                    }
                }
                when (execution.first) {
                    is EditResult.Applied -> {
                        pushUndo(pageIndex, execution.second)
                        mutationApplied = true
                        state = state.copy(dirty = true, canUndo = true, canRedo = false)
                        resyncCacheAndReopen()
                        renderPage(pageIndex)
                    }
                    else -> Toast.makeText(context, "无法插入$shapeName", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "insert shape failed", t)
                state = state.copy(
                    error = "插入${shapeName}失败",
                    dirty = state.dirty || mutationApplied,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
                Toast.makeText(context, "插入${shapeName}失败", Toast.LENGTH_LONG).show()
            } finally {
                restorePendingPageEdit()
                state = state.copy(busy = null)
            }
        }
    }

    fun canTransform(id: Int): Boolean {
        if (!permissions.content || state.busy != null) return false
        val parsedPage = parsed ?: return false
        val node = findNode(parsedPage.root, id) ?: return false
        if (node.locked) return false
        val tokens = node.stream?.tokens ?: parsedPage.tokens
        return ElementEditor.capabilities(tokens, node).canGeom
    }

    fun canTransformSelection(): Boolean {
        if (!permissions.content || state.busy != null) return false
        val page = parsed ?: return false
        val nodes = state.selectedIds.mapNotNull { findNode(page.root, it) }
        if (nodes.isEmpty() || nodes.size != state.selectedIds.size) return false
        val stream = nodes.first().stream ?: return false
        if (stream.owner !is StreamOwner.Page || nodes.any { it.stream !== stream }) return false
        return nodes.all { node ->
            !node.locked && ElementEditor.capabilities(stream.tokens, node).canGeom
        }
    }

    /** long: 多选变换围绕联合外接框中心执行，整次手势只写流并登记一条撤销记录。 */
    fun applyCanvasTransformSelection(
        context: Context,
        dx: Float,
        dy: Float,
        scale: Float,
        rotationDegrees: Float,
    ) {
        if (!canTransformSelection()) return
        val doc = document ?: return
        val pageState = parsed ?: return
        val nodes = state.selectedIds.mapNotNull { findNode(pageState.root, it) }
        val union = Bounds.empty()
        nodes.forEach { it.bounds?.let(union::includeBounds) }
        if (!union.isValid || scale <= 0f) return
        if (!dx.isFinite() || !dy.isFinite() || !scale.isFinite() || !rotationDegrees.isFinite()) return
        if (dx == 0f && dy == 0f && scale == 1f && rotationDegrees == 0f) return
        val edits = nodes.map { node ->
            node to EditRequest(
                dx = dx,
                dy = dy,
                scaleX = scale,
                scaleY = scale,
                rotationDegrees = rotationDegrees,
                pivotX = (union.minX + union.maxX) / 2f,
                pivotY = (union.minY + union.maxY) / 2f,
            )
        }
        val pageIndex = state.pageIndex
        if (!beginPageEdit()) return
        viewModelScope.launch {
            var mutationApplied = false
            try {
                state = state.copy(busy = "正在变换元素", error = null)
                val before = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val page = doc.getPage(pageIndex)
                        val snapshot = createPageSnapshot(doc, pageIndex)
                        check(ElementEditor.editElements(doc, page, edits) is EditResult.Applied) {
                            "无法批量变换所选元素"
                        }
                        snapshot
                    }
                }
                pushUndo(pageIndex, before)
                mutationApplied = true
                state = state.copy(dirty = true, canUndo = true, canRedo = false)
                resyncCacheAndReopen()
                renderPage(pageIndex)
            } catch (t: Throwable) {
                Log.e(TAG, "multi transform failed", t)
                state = state.copy(
                    error = "变换元素失败",
                    dirty = state.dirty || mutationApplied,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
            } finally {
                restorePendingPageEdit()
                state = state.copy(busy = null)
            }
        }
    }

    fun alignSelected(context: Context, action: AlignmentAction) {
        val doc = document ?: return
        val parsedPage = parsed ?: return
        val nodes = state.selectedIds.mapNotNull { findNode(parsedPage.root, it) }
        val minimum = if (
            action == AlignmentAction.DISTRIBUTE_HORIZONTAL ||
            action == AlignmentAction.DISTRIBUTE_VERTICAL
        ) 3 else 2
        if (nodes.size < minimum) {
            Toast.makeText(context, if (minimum == 3) "至少选择 3 个元素" else "至少选择 2 个元素", Toast.LENGTH_SHORT).show()
            return
        }
        if (nodes.any { node ->
                node.locked || node.stream?.owner !is StreamOwner.Page ||
                    !ElementEditor.capabilities(node.stream?.tokens.orEmpty(), node).canGeom
            }
        ) {
            Toast.makeText(context, "所选内容包含无法对齐的元素", Toast.LENGTH_SHORT).show()
            return
        }
        val translations = ElementAlignment.compute(nodes, action)
        if (translations.isEmpty()) {
            Toast.makeText(context, "所选元素已经满足此布局", Toast.LENGTH_SHORT).show()
            return
        }
        val byId = nodes.associateBy { it.id }
        val edits = translations.mapNotNull { move ->
            byId[move.id]?.let { node -> node to EditRequest(dx = move.dx, dy = move.dy) }
        }
        val pageIndex = state.pageIndex
        if (!beginPageEdit()) return
        viewModelScope.launch {
            var mutationApplied = false
            try {
                state = state.copy(busy = "正在对齐元素", error = null)
                val before = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val page = doc.getPage(pageIndex)
                        val snapshot = createPageSnapshot(doc, pageIndex)
                        check(ElementEditor.editElements(doc, page, edits) is EditResult.Applied) {
                            "无法批量对齐所选元素"
                        }
                        snapshot
                    }
                }
                pushUndo(pageIndex, before)
                mutationApplied = true
                state = state.copy(dirty = true, canUndo = true, canRedo = false)
                resyncCacheAndReopen()
                renderPage(pageIndex)
            } catch (t: Throwable) {
                Log.e(TAG, "align failed", t)
                state = state.copy(
                    error = "对齐元素失败",
                    dirty = state.dirty || mutationApplied,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
            } finally {
                restorePendingPageEdit()
                state = state.copy(busy = null)
            }
        }
    }

    fun reorderSelected(context: Context, action: LayerAction) {
        val doc = document ?: return
        val parsedPage = parsed ?: return
        if (state.selectedIds.size != 1) return
        val id = state.selectedIds.single()
        if (findNode(parsedPage.root, id)?.locked == true) {
            Toast.makeText(context, "请先解锁元素", Toast.LENGTH_SHORT).show()
            return
        }
        val target = findSafeLayerMove(parsedPage.root, id, action)
        if (target == null) {
            Toast.makeText(context, "此元素依赖外部绘制状态，暂不能调整图层", Toast.LENGTH_SHORT).show()
            return
        }
        val pageIndex = state.pageIndex
        if (!beginPageEdit()) return
        viewModelScope.launch {
            var mutationApplied = false
            try {
                state = state.copy(busy = "正在调整图层", error = null)
                val before = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val page = doc.getPage(pageIndex)
                        val snapshot = createPageSnapshot(doc, pageIndex)
                        check(
                            ElementEditor.reorderRange(
                                doc,
                                page,
                                target.stream,
                                target.start,
                                target.end,
                                target.insertAt,
                            ) is EditResult.Applied,
                        ) { "图层位置没有变化" }
                        snapshot
                    }
                }
                pushUndo(pageIndex, before)
                mutationApplied = true
                state = state.copy(dirty = true, canUndo = true, canRedo = false)
                resyncCacheAndReopen()
                renderPage(pageIndex)
            } catch (t: Throwable) {
                Log.e(TAG, "reorder failed", t)
                state = state.copy(
                    error = "调整图层失败",
                    dirty = state.dirty || mutationApplied,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
            } finally {
                restorePendingPageEdit()
                state = state.copy(busy = null)
            }
        }
    }

    fun applyCanvasTransform(
        context: Context,
        id: Int,
        dx: Float,
        dy: Float,
        scale: Float,
        rotationDegrees: Float,
    ) {
        val node = findNode(parsed?.root ?: return, id) ?: return
        val bounds = node.bounds ?: return
        if (!dx.isFinite() || !dy.isFinite() || !scale.isFinite() || !rotationDegrees.isFinite()) return
        if (scale <= 0f) return
        if (!canTransform(id)) return
        if (dx == 0f && dy == 0f && scale == 1f && rotationDegrees == 0f) return
        // long: 每次画布手势只在抬手时提交一次，确保一次移动、缩放或旋转只产生一条撤销记录。
        applyEditInternal(
            context,
            node,
            EditRequest(
                dx = dx,
                dy = dy,
                scaleX = scale,
                scaleY = scale,
                rotationDegrees = rotationDegrees,
                pivotX = (bounds.minX + bounds.maxX) / 2f,
                pivotY = (bounds.minY + bounds.maxY) / 2f,
            ),
        )
    }

    // Inline canvas editing: retype one text run in place. Always routes through
    // the auto fallback so the new characters are guaranteed to encode, matching
    // the edit sheet's default.
    fun applyInlineText(context: Context, id: Int, newText: String) {
        val node = findNode(parsed?.root ?: return, id) ?: return
        if (node.kind != NodeKind.TEXT || node.locked) return
        if (newText == (node.text ?: "")) return
        applyEditInternal(
            context, node, EditRequest(newText = newText, fontEntryId = AUTO_FONT_ID),
        )
    }

    // The fallback face the inline editor renders while typing, matching what
    // applyInlineText embeds (the auto match for the run's own font).
    fun inlineFontFace(id: Int): FontCatalog.FaceSource? {
        val node = findNode(parsed?.root ?: return null, id) ?: return null
        return fontCatalog?.autoMatchFace(node.font)
    }

    // For the debug overlay: which matcher step picked the run's substitute.
    fun fontDecisionFor(id: Int): FontCatalog.MatchExplain? {
        val node = findNode(parsed?.root ?: return null, id) ?: return null
        if (node.kind != NodeKind.TEXT) return null
        return fontCatalog?.explainMatch(node.font)
    }

    // Width scale the inline editor applies so its preview size tracks what the
    // commit embeds (both use the auto match for the run's own font).
    fun inlineFontScale(id: Int): Float {
        val node = findNode(parsed?.root ?: return 1f, id) ?: return 1f
        val cat = fontCatalog ?: return 1f
        val matchId = cat.autoMatchId(node.font) ?: return 1f
        return cat.widthScale(node.font, matchId)
    }

    private fun applyEditInternal(
        context: Context,
        node: DrawNode,
        request: EditRequest,
        replacementImageUri: Uri? = null,
    ) {
        val doc = document ?: return
        parsed ?: return
        if (node.locked) {
            Toast.makeText(context, "请先解锁元素", Toast.LENGTH_SHORT).show()
            return
        }
        val pageIndex = state.pageIndex
        if (!beginPageEdit()) return
        viewModelScope.launch {
            var mutationApplied = false
            try {
                // long: 编辑与保存共享事务门禁；失败时保留输入并从独立快照恢复文档。
                val execution = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        check(document === doc) { "文档已经切换" }
                    val page = doc.getPage(pageIndex)
                    val stream = requireNotNull(node.stream) { "Element has no content-stream owner" }
                    // long: Form 的 copy-on-write 会改写调用点，独立文档快照不能保留旧对象引用。
                    val before = createPageSnapshot(doc, pageIndex)
                    val effectiveRequest = if (replacementImageUri == null) {
                        request
                    } else {
                        val bytes = context.contentResolver.openInputStream(replacementImageUri).use { input ->
                            requireNotNull(input) { "无法读取替换图片" }.readBytes()
                        }
                        request.copy(replacementImageBytes = bytes)
                    }
                    val sub = resolveSubstitute(doc, node, effectiveRequest)
                    val r = ElementEditor.editElement(
                        doc, page, node, effectiveRequest, sub?.font, sub?.scale ?: 1f,
                    )
                    EditExecution(r, before, sub != null)
                    }
                }
                when (val result = execution.result) {
                    is EditResult.Applied -> {
                        pushUndo(pageIndex, execution.before)
                        if (execution.embeddedFont) embeddedFonts = true
                        mutationApplied = true
                        state = state.copy(editingId = null, busy = "正在应用编辑")
                        state = state.copy(dirty = true, canUndo = true, canRedo = false)
                        resyncCacheAndReopen()
                        renderPage(pageIndex)
                    }
                    is EditResult.TextEncodeFailed -> {
                        val chars = result.chars
                        val msg = if (chars.isNullOrBlank()) {
                            "此字体无法编码输入的文本"
                        } else {
                            "此字体缺少以下字符：$chars"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                    EditResult.Degenerate ->
                        Toast.makeText(context, "无法变换此元素", Toast.LENGTH_SHORT).show()
                    EditResult.NoChange -> state = state.copy(editingId = null)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "edit failed", t)
                state = state.copy(
                    error = "应用编辑失败",
                    dirty = state.dirty || mutationApplied,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
                Toast.makeText(context, "应用编辑失败", Toast.LENGTH_LONG).show()
            } finally {
                restorePendingPageEdit()
                state = state.copy(busy = null)
            }
        }
    }

    private class Substitute(val font: PDFont, val scale: Float)

    private class EditExecution(
        val result: EditResult,
        val before: EditSnapshot.Document,
        val embeddedFont: Boolean,
    )

    // Prefer-confident policy: on a text edit with no explicit pick, swap to a
    // precisely identified metric-compatible font even when the original could
    // encode, so missing glyphs and unreliable embedded widths stop biting.
    private fun resolveSubstitute(doc: PDDocument, node: DrawNode, request: EditRequest): Substitute? {
        val cat = fontCatalog ?: return null
        val id = request.fontEntryId
        val realId = when {
            id == AUTO_FONT_ID -> cat.autoMatchId(node.font)
            id != null -> id
            request.newText != null -> cat.confidentMatchId(node.font)
            else -> null
        } ?: return null
        val font = cat.resolve(doc, realId) ?: return null
        return Substitute(font, cat.widthScale(node.font, realId))
    }

    fun importFont(context: Context, uri: Uri) {
        val cat = fontCatalog
        if (cat == null) {
            Toast.makeText(context, "请先打开文档", Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { cat.importFont(uri) }
            if (ok) {
                state = state.copy(fontCatalogTick = state.fontCatalogTick + 1)
                Toast.makeText(context, "字体已添加", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "无法导入此字体", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun undo() = stepHistory(undoStack, redoStack)

    fun redo() = stepHistory(redoStack, undoStack)

    private fun stepHistory(from: ArrayDeque<EditSnapshot.Document>, to: ArrayDeque<EditSnapshot.Document>) {
        document ?: return
        if (state.busy != null || from.isEmpty()) return
        // long: 撤销与文档编辑共享 busy 门禁，先同步占位可阻止双击或弹窗回调把两个完整文档快照交叉恢复。
        state = state.copy(busy = "正在处理", error = null)
        viewModelScope.launch {
            val entry = from.last()
            try {
                val current = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        createDocumentSnapshot(requireNotNull(document), state.pageIndex)
                    }
                }
                try {
                    restoreDocumentSnapshot(entry.file, entry.reloadPassword)
                } catch (t: Throwable) {
                    current.dispose()
                    throw t
                }

                // 页面恢复成功后再移动历史记录，失败时仍可重试同一次撤销或重做。
                from.removeLast()
                historyBytes -= entry.sizeBytes
                entry.dispose()
                to.addLast(current)
                historyBytes += current.sizeBytes

                val doc = requireNotNull(document)
                val targetPage = entry.pageIndex.coerceIn(0, doc.numberOfPages - 1)
                state = state.copy(
                    pageIndex = targetPage,
                    pageCount = doc.numberOfPages,
                    dirty = true,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
                persistRecovery()
                refreshPageStructureState()
                renderPage(targetPage)
            } catch (t: Throwable) {
                Log.e(TAG, "history step failed", t)
                state = state.copy(
                    error = "更新编辑历史失败",
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
            } finally {
                state = state.copy(busy = null)
            }
        }
    }

    private fun requirePermission(operation: PdfOperation): Boolean {
        if (permissions.allows(operation)) return true
        state = state.copy(error = "当前 PDF 权限不允许此操作，请使用所有者密码重新打开")
        return false
    }

    /** long: 在发起异步编辑前占位，连续点击和保存回调不能进入同一文档事务。 */
    private fun beginPageEdit(operation: PdfOperation = PdfOperation.CONTENT): Boolean {
        if (!requirePermission(operation)) return false
        if (state.busy != null || state.loading || state.mode != AppMode.EDIT) return false
        state = state.copy(busy = "正在处理", error = null)
        return true
    }

    private fun createPageSnapshot(doc: PDDocument, pageIndex: Int): EditSnapshot.Document {
        check(pendingPageSnapshot == null) { "前一次编辑尚未结束" }
        return createDocumentSnapshot(doc, pageIndex).also { pendingPageSnapshot = it }
    }

    private suspend fun restorePendingPageEdit() {
        val snapshot = pendingPageSnapshot ?: return
        pendingPageSnapshot = null
        try {
            withContext(NonCancellable) {
                restoreDocumentSnapshot(snapshot.file, snapshot.reloadPassword)
                refreshPageStructureState()
                renderPage(snapshot.pageIndex)
                if (state.dirty) persistRecovery()
            }
        } catch (t: Throwable) {
            state = state.copy(error = "编辑未完成，恢复失败，请保留恢复文件", dirty = true)
            Log.e(TAG, "page edit rollback failed", t)
            // long: 恢复失败时不能删除唯一的操作前快照，保留到会话结束供下一次撤销使用。
            pushHistory(snapshot)
            state = state.copy(canUndo = true, canRedo = false)
            return
        }
        snapshot.dispose()
    }

    // Capped both ways so history never grows without bound.
    private fun pushUndo(pageIndex: Int, content: EditSnapshot.Document) {
        check(content.pageIndex == pageIndex)
        pendingPageSnapshot = null
        pushHistory(content)
    }

    private fun pushDocumentUndo(snapshot: EditSnapshot.Document) {
        pushHistory(snapshot)
    }

    private fun pushHistory(snapshot: EditSnapshot.Document) {
        undoStack.addLast(snapshot)
        historyBytes += snapshot.sizeBytes
        for (entry in redoStack) {
            historyBytes -= entry.sizeBytes
            entry.dispose()
        }
        redoStack.clear()
        while (undoStack.size > MAX_HISTORY || historyBytes > MAX_HISTORY_BYTES) {
            if (undoStack.size <= 1) break
            val removed = undoStack.removeFirst()
            historyBytes -= removed.sizeBytes
            removed.dispose()
        }
    }

    private fun clearHistory() {
        undoStack.forEach(EditSnapshot::dispose)
        redoStack.forEach(EditSnapshot::dispose)
        undoStack.clear()
        redoStack.clear()
        historyBytes = 0
    }

    private fun createDocumentSnapshot(
        doc: PDDocument,
        pageIndex: Int,
    ): EditSnapshot.Document {
        val parent = cacheFile?.parentFile
            ?: File(requireNotNull(System.getProperty("java.io.tmpdir")))
        val file = File.createTempFile("pdf-page-history-", ".pdf", parent)
        return try {
            file.outputStream().buffered().use { PdfSerialization.write(doc, it) }
            EditSnapshot.Document(pageIndex, file, documentPassword)
        } catch (t: Throwable) {
            runCatching { file.delete() }
            throw t
        }
    }

    private suspend fun restoreDocumentSnapshot(snapshotFile: File, reloadPassword: String?) {
        val parent = snapshotFile.parentFile ?: error("快照目录不存在")
        var source: File? = null
        var working: File? = null
        var restored: PDDocument? = null
        var adopted = false
        try {
            withContext(Dispatchers.IO) {
                source = File.createTempFile("source-restore-", ".pdf", parent)
                snapshotFile.copyTo(requireNotNull(source), overwrite = true)
                working = File.createTempFile("working-restore-", ".pdf", parent)
                requireNotNull(source).copyTo(requireNotNull(working), overwrite = true)
                restored = PDDocument.load(requireNotNull(source), reloadPassword.orEmpty())
            }
            readerDataMutex.withLock {
                renderMutex.withLock {
                    val previousDocument = document
                    val previousSource = documentSourceFile
                    val previousCache = cacheFile
                    val previousPassword = documentPassword
                    closeRenderer()
                    try {
                        document = requireNotNull(restored)
                        documentSourceFile = source
                        cacheFile = working
                        openRenderer(requireNotNull(working), reloadPassword)
                        documentPassword = reloadPassword.takeIf { requireNotNull(restored).isEncrypted }
                        // long: 先提交新恢复路径再删旧工作文件；进程被回收时仍能找到此前未保存的修改。
                        if (state.dirty) persistRecovery()
                        adopted = true
                    } catch (t: Throwable) {
                        closeRenderer()
                        document = previousDocument
                        documentSourceFile = previousSource
                        cacheFile = previousCache
                        documentPassword = previousPassword
                        previousCache?.let { openRenderer(it, previousPassword) }
                        throw t
                    }
                    runCatching { previousDocument?.close() }
                    runCatching { previousSource?.delete() }
                    runCatching { previousCache?.delete() }
                }
            }
            // long: 重新解析成功前不能继续使用旧页节点，即使随后的渲染失败也不会访问已关闭资源。
            parsed = null
            elementClipboard = null
            state = state.copy(page = null, bitmap = null, selectedId = null, selectedIds = emptySet(),
                editingId = null, selectedAnnotationIndex = null, hasElementClipboard = false)
            embeddedFonts = true
            invalidateReaderDocumentContent()
        } finally {
            if (!adopted) {
                runCatching { restored?.close() }
                runCatching { source?.delete() }
                runCatching { working?.delete() }
            }
        }
    }

    private suspend fun reloadDocumentAfterSecurityChange(reloadPassword: String?) {
        val activeDocument = document ?: return
        val activeSource = documentSourceFile
        val activeCache = cacheFile ?: return
        val parent = activeCache.parentFile
            ?: File(requireNotNull(System.getProperty("java.io.tmpdir")))
        var freshSource: File? = null
        var freshCache: File? = null
        var freshDocument: PDDocument? = null
        try {
            withContext(Dispatchers.IO) {
                freshSource = File.createTempFile("source-security-", ".pdf", parent).also { target ->
                    target.outputStream().buffered().use { PdfSerialization.write(activeDocument, it) }
                }
                freshDocument = PDDocument.load(requireNotNull(freshSource), reloadPassword.orEmpty()).also { reopened ->
                    check(reopened.numberOfPages == activeDocument.numberOfPages) { "保护设置保存后的页数校验失败" }
                    if (reloadPassword == null) {
                        check(!reopened.isEncrypted) { "PDF 保护未被移除" }
                    } else {
                        check(reopened.isEncrypted) { "PDF 保护未生效" }
                        check(reopened.currentAccessPermission.isOwnerPermission) { "所有者密码无法取得完整权限" }
                    }
                }
                freshCache = File.createTempFile("working-security-", ".pdf", parent).also { target ->
                    requireNotNull(freshSource).inputStream().buffered().use { source ->
                        target.outputStream().buffered().use(source::copyTo)
                    }
                }
            }

            readerDataMutex.withLock {
                renderMutex.withLock {
                    val previousPassword = documentPassword
                    closeRenderer()
                    try {
                        document = requireNotNull(freshDocument)
                        documentSourceFile = requireNotNull(freshSource)
                        cacheFile = requireNotNull(freshCache)
                        openRenderer(requireNotNull(freshCache), reloadPassword)
                        documentPassword = reloadPassword.takeIf { requireNotNull(freshDocument).isEncrypted }
                        runCatching { activeDocument.close() }
                        runCatching { activeSource?.delete() }
                        runCatching { activeCache.delete() }
                        freshDocument = null
                        freshSource = null
                        freshCache = null
                    } catch (t: Throwable) {
                        document = activeDocument
                        documentSourceFile = activeSource
                        cacheFile = activeCache
                        documentPassword = previousPassword
                        runCatching { openRenderer(activeCache, previousPassword) }
                        throw t
                    }
                }
            }
            embeddedFonts = true
            invalidateReaderDocumentContent()
        } finally {
            runCatching { freshDocument?.close() }
            runCatching { freshSource?.delete() }
            runCatching { freshCache?.delete() }
        }
    }

    private suspend fun fullResyncCacheAndReopen() {
        val doc = document ?: return
        val oldFile = cacheFile ?: return
        val freshFile = withContext(Dispatchers.IO) {
            File.createTempFile("working-pages-", ".pdf", oldFile.parentFile).also { target ->
                try {
                    target.outputStream().buffered().use { PdfSerialization.write(doc, it) }
                } catch (t: Throwable) {
                    runCatching { target.delete() }
                    throw t
                }
            }
        }
        renderMutex.withLock {
            closeRenderer()
            try {
                cacheFile = freshFile
                openRenderer(freshFile)
            } catch (t: Throwable) {
                cacheFile = oldFile
                runCatching { freshFile.delete() }
                openRenderer(oldFile)
                throw t
            }
        }
        persistRecovery()
        runCatching { oldFile.delete() }
        invalidateReaderDocumentContent()
    }

    private fun refreshPageStructureState() {
        val doc = document ?: return
        val documentFeatures = PdfDocumentFeatureInspector.inspect(doc)
        readerState = readerState.copy(
            pageInfos = collectReaderPageInfos(doc),
            outline = collectReaderOutline(doc),
            links = PdfLinkEditor.list(doc),
            bookmarks = emptySet(),
        )
        formFields = PdfFormEditor.list(doc)
        state = state.copy(
            pageCount = doc.numberOfPages,
            pageIndex = state.pageIndex.coerceIn(0, doc.numberOfPages - 1),
            digitalSignatureCount = documentFeatures.digitalSignatureCount,
            digitalSignatureInspectionFailed = documentFeatures.digitalSignatureInspectionFailed,
            canOverwrite = state.canOverwrite && !documentFeatures.requiresSafeCopy,
            documentToken = state.documentToken + 1,
        )
    }

    private fun originalBackupDirectory(context: Context): File =
        File(context.filesDir, "pdf-original-backups").apply { mkdirs() }

    private fun refreshOriginalBackups(context: Context) {
        originalBackups = originalBackupDirectory(context).listFiles()
            .orEmpty().filter { it.isFile && it.extension == "pdf" && it.length() > 0L }
            .sortedByDescending { it.lastModified() }
    }

    /** long: 同一 SAF 文档可能同时使用 tree URI 与普通 URI，不能只比较字符串。 */
    private fun isSourceTarget(context: Context, target: Uri): Boolean {
        val source = state.sourceUri?.toUri() ?: return false
        return sameDocumentTarget(context, source, target)
    }

    private fun sameDocumentTarget(context: Context, source: Uri, target: Uri): Boolean {
        if (source.normalizeScheme() == target.normalizeScheme()) return true
        if (source.scheme == "file" && target.scheme == "file") {
            return runCatching { File(requireNotNull(source.path)).canonicalFile == File(requireNotNull(target.path)).canonicalFile }.getOrDefault(false)
        }
        return runCatching {
            source.authority == target.authority &&
                DocumentsContract.isDocumentUri(context, source) && DocumentsContract.isDocumentUri(context, target) &&
                DocumentsContract.getDocumentId(source) == DocumentsContract.getDocumentId(target)
        }.getOrDefault(false)
    }

    fun exportOriginalBackup(context: Context, backup: File, target: Uri) {
        if (state.busy != null || backup !in originalBackups || isSourceTarget(context, target)) return
        state = state.copy(busy = "正在导出原始备份", error = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val staged = File.createTempFile("backup-export-", ".pdf", context.cacheDir)
                    backup.copyTo(staged, overwrite = true)
                    val expected = PdfFileFingerprint.fromFile(backup)
                    PdfDocumentWriter.writePreparedNewTarget(
                        staged,
                        openOutput = { context.contentResolver.openOutputStream(target, "wt") },
                        openReadback = { context.contentResolver.openInputStream(target) },
                        validate = { check(PdfFileFingerprint.fromFile(it) == expected) { "备份校验失败" } },
                    )
                }
                Toast.makeText(context, "原始备份已导出，应用内备份继续保留", Toast.LENGTH_LONG).show()
            } catch (failure: Throwable) {
                state = state.copy(error = "导出失败，原始备份仍然保留")
            } finally {
                state = state.copy(busy = null)
            }
        }
    }

    fun saveCopy(context: Context, uri: Uri, onComplete: (Boolean) -> Unit = {}) {
        if (state.busy != null) {
            onComplete(false)
            return
        }
        if (isSourceTarget(context, uri)) {
            state = state.copy(error = "另存为必须选择新文件；覆盖原文件请使用保存功能")
            onComplete(false)
            return
        }
        val doc = document ?: run {
            onComplete(false)
            return
        }
        // long: 保存与页面编辑共用 busy 门禁，先同步占位才能保证导出的文档和随后清除的脏状态属于同一次事务。
        state = state.copy(busy = "正在保存", error = null)
        viewModelScope.launch {
            var saved = false
            try {
                val writeResult = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val stagingFile = File.createTempFile("pdf-export-", ".pdf", context.cacheDir)
                        PdfDocumentWriter.writeNewTarget(
                            document = doc,
                            openOutput = { context.contentResolver.openOutputStream(uri, "wt") },
                            stagingFile = stagingFile,
                            validationPassword = documentPassword,
                            openReadback = { context.contentResolver.openInputStream(uri) },
                        )
                    }
                }
                val appContext = context.applicationContext
                persistUriPermissions(appContext, uri)
                val canOverwrite = withContext(Dispatchers.IO) { canWriteUri(appContext, uri) }
                sourceFingerprint = writeResult.fingerprint.takeIf { writeResult.targetVerified }
                clearRecoveryForFile(appContext, cacheFile, deleteFile = false)
                state = state.copy(
                    dirty = false,
                    sourceUri = uri.toString(),
                    fileName = displayName(appContext, uri),
                    // long: 目标无法回读时仍允许用户保留副本，但后续只能再次另存，避免未经验证的覆盖写入。
                    canOverwrite = canOverwrite && writeResult.targetVerified &&
                        sourceFingerprint != null && state.digitalSignatureCount == 0 &&
                        !state.digitalSignatureInspectionFailed,
                )
                saved = true
                Toast.makeText(
                    context,
                    if (writeResult.targetVerified) "副本已保存" else "副本已保存，但无法回读验证；后续请使用另存为",
                    if (writeResult.targetVerified) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                ).show()
            } catch (t: Throwable) {
                Log.e(TAG, "save failed", t)
                state = state.copy(error = "保存 PDF 副本失败，请重试")
                Toast.makeText(context, "保存失败", Toast.LENGTH_LONG).show()
            } finally {
                state = state.copy(busy = null)
                runCatching { onComplete(saved) }
            }
        }
    }

    fun pageTransferIssues(
        pageIndices: Set<Int>,
        mode: PdfPageExportMode = PdfPageExportMode.PRESERVE_STRUCTURE,
    ): List<PdfPageTransferIssue> {
        val doc = document ?: return emptyList()
        val strategy = planPdfPageExport(mode, doc.numberOfPages, pageIndices)
        return if (strategy == PdfPageExportStrategy.REBUILD_SELECTION) {
            PdfPageTransferInspector.inspect(doc, pageIndices)
        } else {
            emptyList()
        }
    }

    fun pageTransferIssues(ranges: List<IntRange>): List<PdfPageTransferIssue> {
        val doc = document ?: return emptyList()
        // long: 每个拆分范围最终都会生成独立 PDF，因此页内链接和文档级结构必须按每个输出文件分别检查。
        return PdfPageTransferInspector.inspectAll(doc, ranges.map(IntRange::toSet))
    }

    fun exportPages(
        context: Context,
        uri: Uri,
        pageIndices: Set<Int>,
        mode: PdfPageExportMode = PdfPageExportMode.PRESERVE_STRUCTURE,
        onComplete: (Boolean) -> Unit = {},
    ) {
        if (!requirePermission(PdfOperation.EXTRACT)) { onComplete(false); return }
        if (state.busy != null || pageIndices.isEmpty()) {
            onComplete(false)
            return
        }
        if (isSourceTarget(context, uri)) {
            state = state.copy(error = "页面导出必须选择新文件，不能覆盖当前 PDF")
            onComplete(false)
            return
        }
        val doc = document ?: run {
            onComplete(false)
            return
        }
        val strategy = runCatching { planPdfPageExport(mode, doc.numberOfPages, pageIndices) }
            .getOrElse {
                onComplete(false)
                return
            }
        // long: 三种策略都只生成新文档，不改当前来源、脏状态或撤销栈，用户导出后可继续编辑原 PDF。
        val busyLabel = if (mode == PdfPageExportMode.FLATTENED_COPY) {
            "正在展平并导出 ${pageIndices.size} 页"
        } else {
            "正在导出 ${pageIndices.size} 页"
        }
        state = state.copy(busy = busyLabel, error = null)
        viewModelScope.launch {
            var exported = false
            try {
                withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        when (strategy) {
                            PdfPageExportStrategy.DIRECT_DOCUMENT -> PdfDocumentWriter.writeNewTarget(
                                document = doc,
                                openOutput = { context.contentResolver.openOutputStream(uri, "wt") },
                                stagingFile = File.createTempFile("pdf-pages-export-", ".pdf", context.cacheDir),
                                validationPassword = documentPassword,
                            )
                            PdfPageExportStrategy.REBUILD_SELECTION -> PageTreeEditor.extract(doc, pageIndices)
                                .use { extracted ->
                                    PdfDocumentWriter.writeNewTarget(
                                        document = extracted,
                                        openOutput = { context.contentResolver.openOutputStream(uri, "wt") },
                                        stagingFile = File.createTempFile(
                                            "pdf-pages-export-",
                                            ".pdf",
                                            context.cacheDir,
                                        ),
                                    )
                                }
                            PdfPageExportStrategy.FLATTEN_SELECTION -> PdfFlattenedCopyExporter
                                .create(doc, pageIndices)
                                .use { flattened ->
                                    PdfDocumentWriter.writeNewTarget(
                                        document = flattened,
                                        openOutput = { context.contentResolver.openOutputStream(uri, "wt") },
                                        stagingFile = File.createTempFile(
                                            "pdf-flattened-export-",
                                            ".pdf",
                                            context.cacheDir,
                                        ),
                                    )
                                }
                        }
                    }
                }
                exported = true
                val message = if (mode == PdfPageExportMode.FLATTENED_COPY) {
                    "已导出展平副本，共 ${pageIndices.size} 页"
                } else {
                    "已保留结构导出 ${pageIndices.size} 页"
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.e(TAG, "page export failed", t)
                state = state.copy(error = "导出所选页面失败，请重试")
                Toast.makeText(context, t.message ?: "导出失败", Toast.LENGTH_LONG).show()
            } finally {
                state = state.copy(busy = null)
                runCatching { onComplete(exported) }
            }
        }
    }

    fun splitDocument(
        context: Context,
        treeUri: Uri,
        ranges: List<IntRange>,
        onComplete: (Boolean) -> Unit = {},
    ) {
        if (!requirePermission(PdfOperation.EXTRACT)) { onComplete(false); return }
        if (state.busy != null || ranges.isEmpty()) {
            onComplete(false)
            return
        }
        val doc = document ?: run {
            onComplete(false)
            return
        }
        val stableRanges = ranges.toList()
        val createdUris = mutableListOf<Uri>()
        state = state.copy(busy = "正在拆分 PDF", error = null)
        viewModelScope.launch {
            var completed = false
            try {
                withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        require(stableRanges.all { range ->
                            range.first >= 0 && range.last < doc.numberOfPages && !range.isEmpty()
                        }) { "拆分页码超出文档范围" }
                        val resolver = context.contentResolver
                        runCatching {
                            resolver.takePersistableUriPermission(
                                treeUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            )
                        }
                        val parentUri = DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            DocumentsContract.getTreeDocumentId(treeUri),
                        )
                        val baseName = state.fileName.substringBeforeLast('.')
                            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                            .ifBlank { "PDF" }
                        stableRanges.forEach { range ->
                            PageTreeEditor.extract(doc, range.toSet()).use { extracted ->
                                val suffix = if (range.first == range.last) {
                                    String.format(Locale.US, "第%03d页", range.first + 1)
                                } else {
                                    String.format(Locale.US, "%03d-%03d", range.first + 1, range.last + 1)
                                }
                                val outputUri = requireNotNull(
                                    DocumentsContract.createDocument(
                                        resolver,
                                        parentUri,
                                        "application/pdf",
                                        "$baseName-$suffix.pdf",
                                    ),
                                ) { "无法在所选文件夹创建 PDF" }
                                createdUris += outputUri
                                PdfDocumentWriter.writeNewTarget(
                                    document = extracted,
                                    openOutput = { resolver.openOutputStream(outputUri, "wt") },
                                    stagingFile = File.createTempFile("pdf-split-", ".pdf", context.cacheDir),
                                )
                            }
                        }
                    }
                }
                completed = true
                Toast.makeText(context, "已生成 ${stableRanges.size} 个 PDF", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                withContext(Dispatchers.IO) {
                    createdUris.asReversed().forEach { uri ->
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    }
                }
                Log.e(TAG, "split PDF failed", t)
                state = state.copy(error = "拆分 PDF 失败，请重试")
                Toast.makeText(context, t.message ?: "拆分 PDF 失败", Toast.LENGTH_LONG).show()
            } finally {
                state = state.copy(busy = null)
                runCatching { onComplete(completed) }
            }
        }
    }

    fun mergeDocuments(
        context: Context,
        sourceUris: List<Uri>,
        targetUri: Uri,
        onComplete: (Boolean) -> Unit = {},
    ) {
        if (state.busy != null || sourceUris.size < 2) {
            onComplete(false)
            return
        }
        val uniqueSources = sourceUris.distinct()
        if (uniqueSources.size < 2 || uniqueSources.any { sameDocumentTarget(context, it, targetUri) } || isSourceTarget(context, targetUri)) {
            onComplete(false)
            return
        }
        state = state.copy(busy = "正在合并 ${uniqueSources.size} 个 PDF", error = null)
        viewModelScope.launch {
            var completed = false
            val openedDocuments = mutableListOf<PDDocument>()
            try {
                withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    uniqueSources.forEach { sourceUri ->
                        val input = requireNotNull(resolver.openInputStream(sourceUri)) {
                            "无法读取待合并的 PDF"
                        }
                        input.buffered().use { stream ->
                            // long: 空用户密码也可能带复制限制，成功打开不代表可以把页面复制到无保护的新文档。
                            val inputDocument = PDDocument.load(stream)
                            openedDocuments += inputDocument
                            require(PdfPermissions.from(inputDocument.currentAccessPermission).extract) { "来源 PDF 不允许提取页面" }
                        }
                    }
                    val merged = PdfMergeEditor.merge(openedDocuments).first
                    merged.use {
                        PdfDocumentWriter.writeNewTarget(
                            document = it,
                            openOutput = { resolver.openOutputStream(targetUri, "wt") },
                            stagingFile = File.createTempFile("pdf-merge-", ".pdf", context.cacheDir),
                        )
                    }
                }
                completed = true
                Toast.makeText(context, "已合并 ${uniqueSources.size} 个 PDF", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.e(TAG, "merge PDF failed", t)
                state = state.copy(error = t.message ?: "合并 PDF 失败")
                Toast.makeText(context, t.message ?: "合并 PDF 失败", Toast.LENGTH_LONG).show()
            } finally {
                openedDocuments.asReversed().forEach { runCatching { it.close() } }
                state = state.copy(busy = null)
                runCatching { onComplete(completed) }
            }
        }
    }

    fun prepareExternalPdf(
        context: Context,
        onComplete: (File?, Int) -> Unit,
    ) {
        if (state.busy != null) {
            onComplete(null, 0)
            return
        }
        val doc = document ?: run {
            onComplete(null, 0)
            return
        }
        state = state.copy(busy = "正在准备 PDF", error = null)
        viewModelScope.launch {
            var output: File? = null
            try {
                output = withContext(Dispatchers.IO) {
                    val target = PdfExternalFileStore.create(context, "reader-")
                    try {
                        readerDataMutex.withLock {
                            PdfDocumentWriter.writeNewTarget(
                                document = doc,
                                openOutput = { target.outputStream().buffered() },
                                stagingFile = File.createTempFile("pdf-external-", ".pdf", context.cacheDir),
                                validationPassword = documentPassword,
                            )
                        }
                        target
                    } catch (error: Throwable) {
                        runCatching { target.delete() }
                        throw error
                    }
                }
                onComplete(output, doc.numberOfPages)
            } catch (t: Throwable) {
                Log.e(TAG, "prepare external PDF failed", t)
                state = state.copy(error = t.message ?: "准备 PDF 失败")
                Toast.makeText(context, t.message ?: "准备 PDF 失败", Toast.LENGTH_LONG).show()
                onComplete(null, 0)
            } finally {
                state = state.copy(busy = null)
            }
        }
    }

    fun saveOriginal(
        context: Context,
        forceOverwrite: Boolean = false,
        onComplete: (PdfSaveOutcome) -> Unit = {},
    ) {
        if (state.busy != null) {
            onComplete(PdfSaveOutcome.FAILED)
            return
        }
        val doc = document
        val sourceUri = state.sourceUri?.toUri()
        if (doc == null || sourceUri == null || !state.canOverwrite) {
            onComplete(PdfSaveOutcome.FAILED)
            return
        }
        // long: 覆盖原文件前同步锁住交互状态，防止整文档撤销刚替换实例时仍把旧 PDDocument 写回来源 URI。
        state = state.copy(busy = "正在保存", error = null)
        viewModelScope.launch {
            var outcome = PdfSaveOutcome.FAILED
            try {
                val expected = sourceFingerprint
                if (!forceOverwrite) {
                    val current = withContext(Dispatchers.IO) {
                        fingerprintUri(context.applicationContext, sourceUri)
                    }
                    if (expected == null || current == null || current != expected) {
                        // long: 无法确认来源仍是打开时的版本时，宁可停在冲突决策，也不能静默覆盖。
                        state = state.copy(error = null)
                        outcome = PdfSaveOutcome.CONFLICT
                        return@launch
                    }
                }
                val writeResult = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        PdfDocumentWriter.overwrite(
                            document = doc,
                            openCurrent = { context.contentResolver.openInputStream(sourceUri) },
                            openOutput = { context.contentResolver.openOutputStream(sourceUri, "wt") },
                            stagingFile = File.createTempFile("pdf-overwrite-", ".pdf", context.cacheDir),
                            backupFile = File.createTempFile("原始备份-", ".pdf", originalBackupDirectory(context)),
                            validationPassword = documentPassword,
                            openReadback = { context.contentResolver.openInputStream(sourceUri) },
                            expectedCurrent = expected.takeUnless { forceOverwrite },
                        )
                    }
                }
                sourceFingerprint = writeResult.fingerprint
                clearRecoveryForFile(context.applicationContext, cacheFile, deleteFile = false)
                state = state.copy(dirty = false)
                outcome = PdfSaveOutcome.SAVED
                Toast.makeText(context, "修改已保存", Toast.LENGTH_SHORT).show()
            } catch (failure: PdfBackupRecoveryException) {
                refreshOriginalBackups(context)
                state = state.copy(error = "原文件恢复失败，原始备份已保留，请导出备份", canOverwrite = false)
            } catch (_: PdfSourceConflictException) {
                state = state.copy(error = null)
                outcome = PdfSaveOutcome.CONFLICT
            } catch (t: Throwable) {
                Log.e(TAG, "overwrite failed", t)
                state = state.copy(
                    error = t.message ?: "覆盖保存失败，请另存为新文件",
                    canOverwrite = false,
                )
                Toast.makeText(context, "覆盖保存失败，请另存为", Toast.LENGTH_LONG).show()
            } finally {
                state = state.copy(busy = null)
                runCatching { onComplete(outcome) }
            }
        }
    }

    fun closeDocument() {
        if (state.busy != null || state.loading) return
        documentGeneration++
        searchJob?.cancel()
        state = state.copy(busy = "正在关闭文档")
        viewModelScope.launch {
            applicationContext?.let {
                clearRecoveryForFile(it, cacheFile, deleteFile = false)
            }
            readerDataMutex.withLock {
                renderMutex.withLock {
                    closeRenderer()
                    document?.close()
                    document = null
                }
            }
            parsed = null
            clearHistory()
            elementClipboard = null
            clearReaderCaches()
            formFields = emptyList()
            runCatching { cacheFile?.delete() }
            runCatching { documentSourceFile?.delete() }
            cacheFile = null
            documentSourceFile = null
            fontCatalog = null
            embeddedFonts = false
            documentPassword = null
            sourceFingerprint = null
            pendingPasswordAction = null
            passwordPrompt = null
            state = PdfUiState()
        }
    }

    private suspend fun renderPage(index: Int) {
        val generation = documentGeneration
        try {
            val result = withContext(Dispatchers.IO) {
                // long: 页面解析和 PDFBox 回退共享同一 PDDocument，串行读取可避免切换文档时旧任务访问已关闭对象。
                readerDataMutex.withLock {
                    check(generation == documentGeneration) { "文档已经切换" }
                    val doc = requireNotNull(document) { "文档已经关闭" }
                    val page = doc.getPage(index)
                    val crop = page.cropBox
                    val renderSize = fitPdfRenderSize(
                        widthPoints = crop.width,
                        heightPoints = crop.height,
                        rotation = page.rotation,
                        requestedScale = RENDER_DPI / 72f,
                        maxPixels = MAX_EDIT_RENDER_PIXELS,
                        maxDimension = MAX_EDIT_RENDER_DIMENSION,
                    )
                    val bmp = renderPageBitmap(
                        document = doc,
                        index = index,
                        pxW = renderSize.widthPx,
                        pxH = renderSize.heightPx,
                        generation = generation,
                        renderDpi = renderSize.scale * 72f,
                    )
                    val parsedPage = ContentStreamEngine.parse(page)
                    val transform = PageTransform(
                        crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height,
                        page.rotation, renderSize.scale,
                    )
                    PageRenderResult(bmp, parsedPage, transform, PdfAnnotationEditor.list(page))
                }
            }
            if (generation != documentGeneration) return
            parsed = result.parsed
            state = state.copy(
                bitmap = result.bitmap.asImageBitmap(),
                elementCount = result.parsed.leaves.size,
                page = result.parsed,
                pageTransform = result.transform,
                annotations = result.annotations,
                swatchColors = sampleLeafColors(result.bitmap, result.parsed.leaves, result.transform),
                selectedId = null,
                selectedIds = emptySet(),
                selectedAnnotationIndex = null,
                expanded = collectGroupIds(result.parsed.root),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Log.e(TAG, "render failed page=$index", t)
            if (generation == documentGeneration) {
                state = state.copy(error = "渲染页面失败")
            }
        }
    }

    // pdfbox-android can't resolve some color spaces (Separation, etc.), so the
    // true paint color is read from the pixel pdfium actually drew at the
    // element's center. Text keeps its parsed color (glyph centers are usually
    // background).
    private fun sampleLeafColors(
        bmp: Bitmap,
        leaves: List<DrawNode>,
        t: PageTransform,
    ): Map<Int, Int> {
        val map = HashMap<Int, Int>()
        val w = bmp.width
        val h = bmp.height
        for (leaf in leaves) {
            if (leaf.kind != NodeKind.PATH && leaf.kind != NodeKind.IMAGE) continue
            val b = leaf.bounds ?: continue
            val r = t.toRect(b)
            val cx = ((r.left + r.right) * 0.5f).toInt().coerceIn(0, w - 1)
            val cy = ((r.top + r.bottom) * 0.5f).toInt().coerceIn(0, h - 1)
            val px = bmp.getPixel(cx, cy)
            if (px ushr 24 != 0) map[leaf.id] = px or (0xFF shl 24)
        }
        return map
    }

    private suspend fun renderPageBitmap(
        document: PDDocument,
        index: Int,
        pxW: Int,
        pxH: Int,
        generation: Int,
        renderDpi: Float,
    ): Bitmap =
        try {
            renderWithPdfium(index, pxW, pxH, generation)
        } catch (outOfMemory: OutOfMemoryError) {
            // long: 首次位图分配已耗尽堆时不能再让 PDFBox 申请同尺寸位图，否则会连续触发 OOM。
            throw outOfMemory
        } catch (t: Throwable) {
            Log.e(TAG, "pdfium render failed page=$index, falling back to pdfbox", t)
            PDFRenderer(document).renderImageWithDPI(index, renderDpi)
        }

    // pdfium leaves empty pixels transparent and PDFs assume white paper, so
    // prefill white. It is single-page and not thread-safe, hence the mutex.
    private suspend fun renderWithPdfium(
        index: Int,
        pxW: Int,
        pxH: Int,
        generation: Int,
    ): Bitmap =
        renderMutex.withLock {
            check(generation == documentGeneration) { "文档已经切换" }
            val renderer = requireNotNull(pdfRenderer) { "renderer not open" }
            val bmp = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val page = renderer.openPage(index)
            try {
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } finally {
                page.close()
            }
            bmp
        }

    // long: 普通页面直接用 PdfRenderer 重绘可见区域；加密或旋转页由 PDFBox 整页回退后裁剪，二者共享同一 tile 接口。
    suspend fun renderRegion(
        pageIndex: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        outW: Int,
        outH: Int,
    ): Bitmap? {
        val generation = documentGeneration
        val bw = right - left
        val bh = bottom - top
        if (bw < 1f || bh < 1f) return null
        val w = outW.coerceIn(1, MAX_TILE_PX)
        val h = outH.coerceIn(1, MAX_TILE_PX)
        return withContext(Dispatchers.IO) {
            readerDataMutex.withLock {
                if (generation != documentGeneration) return@withLock null
                val doc = document ?: return@withLock null
                if (pageIndex !in 0 until doc.numberOfPages) return@withLock null
                val page = doc.getPage(pageIndex)
                val crop = page.cropBox
                val s = fitPdfRenderSize(
                    widthPoints = crop.width,
                    heightPoints = crop.height,
                    rotation = page.rotation,
                    requestedScale = RENDER_DPI / 72f,
                    maxPixels = MAX_EDIT_RENDER_PIXELS,
                    maxDimension = MAX_EDIT_RENDER_DIMENSION,
                ).scale
                renderMutex.withLock {
                    if (generation != documentGeneration) return@withLock null
                    val rotation = ((page.rotation % 360) + 360) % 360
                    val renderer = pdfRenderer
                    if (renderer == null || rotation != 0) {
                        return@withLock renderRegionWithPdfBox(
                            document = doc,
                            pageIndex = pageIndex,
                            cropWidth = crop.width,
                            cropHeight = crop.height,
                            rotation = rotation,
                            left = left,
                            top = top,
                            right = right,
                            bottom = bottom,
                            outW = w,
                            outH = h,
                            renderScale = s,
                        )
                    }
                    val matrix = Matrix().apply {
                        // long: tile 输入始终是 144 DPI 坐标，整页预算降采样不能改变区域映射。
                        setScale((w / bw) * (RENDER_DPI / 72f), (h / bh) * (RENDER_DPI / 72f))
                        postTranslate(-left * (w / bw), -top * (h / bh))
                    }
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    val pdfPage = renderer.openPage(pageIndex)
                    try {
                        pdfPage.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    } finally {
                        pdfPage.close()
                    }
                    bmp
                }
            }
        }
    }

    /** long: PDFBox 回退先按统一预算生成整页，再按阅读器传入的 144-DPI 归一化坐标裁剪，覆盖加密和旋转页的高清区域。 */
    private fun renderRegionWithPdfBox(
        document: PDDocument,
        pageIndex: Int,
        cropWidth: Float,
        cropHeight: Float,
        rotation: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        outW: Int,
        outH: Int,
        renderScale: Float,
    ): Bitmap {
        val full = PDFRenderer(document).renderImageWithDPI(pageIndex, renderScale * 72f)
        return try {
            val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            output.eraseColor(Color.WHITE)
            val source = mapPdfRenderRegionToBitmap(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                cropWidth = cropWidth,
                cropHeight = cropHeight,
                rotation = rotation,
                baseScale = RENDER_DPI / 72f,
                bitmapWidth = full.width,
                bitmapHeight = full.height,
            )
            if (source != null) {
                Canvas(output).drawBitmap(
                    full,
                    Rect(source.left, source.top, source.right, source.bottom),
                    Rect(0, 0, outW, outH),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
            }
            output
        } finally {
            full.recycle()
        }
    }

    /** long: 新解析器准备失败时保留整个旧会话，来源 URI、指纹和内存文档不能跨文件混用。 */
    private suspend fun adoptLoadedDocument(loaded: LoadedDocument) {
        var prepared: Pair<ParcelFileDescriptor?, PdfRenderer?>? = null
        var adopted = false
        try {
            withContext(Dispatchers.IO) { prepared = createRenderer(loaded.workingFile, loaded.password) }
            readerDataMutex.withLock {
                renderMutex.withLock {
                    closeRenderer()
                    runCatching { document?.close() }
                    document = loaded.document
                    documentSourceFile = loaded.sourceFile
                    cacheFile = loaded.workingFile
                    pfd = prepared?.first
                    pdfRenderer = prepared?.second
                    sourceFingerprint = loaded.sourceFingerprint
                    documentPassword = loaded.password
                    parsed = null
                    state = state.copy(sourceUri = loaded.sourceUri, fileName = loaded.title,
                        page = null, bitmap = null, canOverwrite = false)
                    adopted = true
                }
            }
        } finally {
            if (!adopted) {
                runCatching { prepared?.second?.close() }
                runCatching { prepared?.first?.close() }
                loaded.dispose()
            }
        }
    }

    private fun createRenderer(file: File, password: String?): Pair<ParcelFileDescriptor?, PdfRenderer?> {
        if (password != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null to null
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val renderer = if (password != null) {
                PdfRenderer(fd, LoadParams.Builder().setPassword(password).build())
            } else PdfRenderer(fd)
            return fd to renderer
        } catch (failure: Throwable) {
            runCatching { fd.close() }
            throw failure
        }
    }

    private fun openRenderer(file: File, password: String? = documentPassword) {
        val prepared = createRenderer(file, password)
        pfd = prepared.first
        pdfRenderer = prepared.second
    }

    private fun closeRenderer() {
        runCatching { pdfRenderer?.close() }
        runCatching { pfd?.close() }
        pdfRenderer = null
        pfd = null
    }

    /**
     * long: 内存编辑后始终写入新的工作文件，再切换 PdfRenderer。PDFBox 继续读取独立源文件，
     * 避免截断自身加载源；写入失败时旧工作文件仍可继续渲染和恢复。
     */
    private suspend fun resyncCacheAndReopen() {
        val doc = document ?: return
        val oldFile = cacheFile ?: return
        val freshFile = withContext(Dispatchers.IO) {
            File.createTempFile("working-edit-", ".pdf", oldFile.parentFile).also { target ->
                try {
                    target.outputStream().buffered().use { PdfSerialization.write(doc, it) }
                    PDDocument.load(target, documentPassword.orEmpty()).use { saved ->
                        check(saved.numberOfPages == doc.numberOfPages) { "编辑工作文件页数校验失败" }
                    }
                } catch (failure: Throwable) {
                    runCatching { target.delete() }
                    throw failure
                }
            }
        }
        renderMutex.withLock {
            closeRenderer()
            try {
                cacheFile = freshFile
                openRenderer(freshFile)
            } catch (t: Throwable) {
                cacheFile = oldFile
                runCatching { freshFile.delete() }
                openRenderer(oldFile)
                throw t
            }
        }
        persistRecovery()
        runCatching { oldFile.delete() }
        invalidateReaderDocumentContent()
    }

    private fun prepareUriDocument(
        context: Context,
        uri: Uri,
        generation: Int,
        password: String?,
    ): LoadedDocument {
        val sourceFile = File.createTempFile("pdf-source-$generation-", ".pdf", context.cacheDir)
        var workingFile: File? = null
        var loadedDocument: PDDocument? = null
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法打开所选文件" }.buffered().use { source ->
                    sourceFile.outputStream().buffered().use(source::copyTo)
                }
            }
            workingFile = File.createTempFile("pdf-working-$generation-", ".pdf", context.cacheDir)
            sourceFile.inputStream().buffered().use { source ->
                workingFile.outputStream().buffered().use(source::copyTo)
            }
            // long: PDFBox 始终读取 sourceFile，PdfRenderer 只读取 workingFile，后续编辑可原子替换渲染文件。
            loadedDocument = PDDocument.load(sourceFile, password.orEmpty())
            return LoadedDocument(
                document = loadedDocument,
                sourceFile = sourceFile,
                workingFile = workingFile,
                title = displayName(context, uri),
                sourceUri = uri.toString(),
                sourceFingerprint = PdfFileFingerprint.fromFile(sourceFile),
                canOverwrite = canWriteUri(context, uri),
                pageInfos = collectReaderPageInfos(loadedDocument),
                outline = collectReaderOutline(loadedDocument),
                links = PdfLinkEditor.list(loadedDocument),
                password = password.takeIf { loadedDocument.isEncrypted },
            )
        } catch (t: Throwable) {
            runCatching { loadedDocument?.close() }
            runCatching { sourceFile.delete() }
            runCatching { workingFile?.delete() }
            throw t
        }
    }

    private fun prepareRecoveryDocument(
        context: Context,
        recovery: PdfRecoveryInfo,
        generation: Int,
        password: String?,
    ): LoadedDocument {
        val sourceFile = File.createTempFile("pdf-recovery-source-$generation-", ".pdf", context.cacheDir)
        var workingFile: File? = null
        var loadedDocument: PDDocument? = null
        try {
            recovery.file.inputStream().buffered().use { source ->
                sourceFile.outputStream().buffered().use(source::copyTo)
            }
            workingFile = File.createTempFile("pdf-recovery-working-$generation-", ".pdf", context.cacheDir)
            sourceFile.inputStream().buffered().use { source ->
                workingFile.outputStream().buffered().use(source::copyTo)
            }
            loadedDocument = PDDocument.load(sourceFile, password.orEmpty())
            val sourceUri = recovery.sourceUri
            val canOverwrite = recovery.canOverwrite && sourceUri != null &&
                canWriteUri(context, sourceUri.toUri())
            return LoadedDocument(
                document = loadedDocument,
                sourceFile = sourceFile,
                workingFile = workingFile,
                title = recovery.title,
                sourceUri = sourceUri,
                sourceFingerprint = recovery.sourceFingerprint,
                canOverwrite = canOverwrite && recovery.sourceFingerprint != null,
                pageInfos = collectReaderPageInfos(loadedDocument),
                outline = collectReaderOutline(loadedDocument),
                links = PdfLinkEditor.list(loadedDocument),
                password = password.takeIf { loadedDocument.isEncrypted },
            )
        } catch (t: Throwable) {
            runCatching { loadedDocument?.close() }
            runCatching { sourceFile.delete() }
            runCatching { workingFile?.delete() }
            throw t
        }
    }

    private fun canWriteUri(context: Context, uri: Uri): Boolean {
        if (uri.scheme == "file") return uri.path?.let(::File)?.canWrite() == true
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { true } ?: false
        }.getOrDefault(false)
    }

    private fun fingerprintUri(context: Context, uri: Uri): PdfFileFingerprint? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            PdfFileFingerprint.fromInputStream(input)
        }
    }.getOrNull()

    private fun persistUriPermissions(context: Context, uri: Uri) {
        // long: 读写授权分开申请，提供方拒绝写权限时仍要保住后续重新打开文档所需的持久读权限。
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    private suspend fun persistRecovery() {
        if (!state.dirty || state.mode != AppMode.EDIT) return
        val context = applicationContext ?: return
        val file = cacheFile ?: return
        withContext(Dispatchers.IO) {
            PdfRecoveryStore.remember(
                context = context,
                file = file,
                title = state.fileName,
                sourceUri = state.sourceUri,
                pageIndex = state.pageIndex,
                canOverwrite = state.canOverwrite,
                sourceFingerprint = sourceFingerprint,
            )
        }
        recoveryInfo = PdfRecoveryStore.load(context)
    }

    private fun clearRecoveryForFile(context: Context, file: File?, deleteFile: Boolean) {
        val stored = PdfRecoveryStore.load(context)
        if (file != null && stored?.file?.absolutePath == file.absolutePath) {
            PdfRecoveryStore.clear(context, deleteFile)
            recoveryInfo = null
        } else {
            recoveryInfo = stored
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        val fromResolver = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        return fromResolver ?: uri.lastPathSegment?.substringAfterLast('/') ?: "文档.pdf"
    }

    private fun contentSize(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getLong(0).takeIf { it >= 0L }
        }
    }.getOrNull()

    private fun readUriBytesWithLimit(context: Context, uri: Uri, limit: Long): ByteArray {
        contentSize(context, uri)?.let { size ->
            require(size <= limit) { "证书文件不能超过 ${limit / 1024 / 1024} MB" }
        }
        val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取证书文件" }
        return input.buffered().use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "证书文件不能超过 ${limit / 1024 / 1024} MB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun countBytes(input: InputStream): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
        }
    }

    private fun collectReaderPageInfos(doc: PDDocument): List<ReaderPageInfo> =
        (0 until doc.numberOfPages).map { pageIndex ->
            val page = doc.getPage(pageIndex)
            val crop = page.cropBox
            val rotation = ((page.rotation % 360) + 360) % 360
            val width = if (rotation == 90 || rotation == 270) crop.height else crop.width
            val height = if (rotation == 90 || rotation == 270) crop.width else crop.height
            ReaderPageInfo(
                pageIndex = pageIndex,
                widthPoints = width.coerceAtLeast(1f),
                heightPoints = height.coerceAtLeast(1f),
                cropX = crop.lowerLeftX,
                cropY = crop.lowerLeftY,
                cropWidthPoints = crop.width.coerceAtLeast(1f),
                cropHeightPoints = crop.height.coerceAtLeast(1f),
                rotation = rotation,
            )
        }

    private fun collectReaderOutline(doc: PDDocument): List<ReaderOutlineEntry> {
        val root = doc.documentCatalog.documentOutline ?: return emptyList()
        val entries = ArrayList<ReaderOutlineEntry>()
        appendReaderOutline(doc, root, level = 0, entries)
        return entries
    }

    private fun appendReaderOutline(
        doc: PDDocument,
        node: PDOutlineNode,
        level: Int,
        entries: MutableList<ReaderOutlineEntry>,
    ) {
        node.children().forEach { item ->
            val pageIndex = runCatching {
                item.findDestinationPage(doc)?.let(doc.pages::indexOf)
            }.getOrNull() ?: -1
            val title = item.title?.trim().orEmpty()
            if (title.isNotEmpty() && pageIndex >= 0) {
                entries += ReaderOutlineEntry(title, pageIndex, level)
            }
            if (item.hasChildren()) appendReaderOutline(doc, item, level + 1, entries)
        }
    }

    private fun clearReaderCaches() {
        cancelReaderRendering()
        searchJob?.cancel()
        readerIndexJob?.cancel()
        readerIndexJob = null
        readerPages.clear()
        readerThumbnails.clear()
        readerPageLru.clear()
        readerThumbnailLru.clear()
        readerTextIndex.clear()
        readerState = ReaderUiState()
    }

    private fun invalidateReaderDocumentContent() {
        cancelReaderRendering()
        searchJob?.cancel()
        readerIndexJob?.cancel()
        readerIndexJob = null
        readerPages.clear()
        readerThumbnails.clear()
        readerPageLru.clear()
        readerThumbnailLru.clear()
        readerTextIndex.clear()
        val pageInfos = document?.let(::collectReaderPageInfos) ?: readerState.pageInfos
        readerState = readerState.copy(
            pageInfos = pageInfos,
            searchQuery = "",
            searchResults = emptyList(),
            searchLimitReached = false,
            searching = false,
            searchProgress = 0,
            searchError = null,
            indexing = false,
            indexedPageCount = 0,
            activeSearchMatchIndex = null,
        )
    }

    override fun onCleared() {
        documentGeneration++
        searchJob?.cancel()
        readerIndexJob?.cancel()
        if (state.dirty && state.mode == AppMode.EDIT) {
            val context = applicationContext
            val file = cacheFile
            if (context != null && file != null) {
                runCatching {
                    PdfRecoveryStore.remember(
                        context,
                        file,
                        state.fileName,
                        state.sourceUri,
                        state.pageIndex,
                        state.canOverwrite,
                        sourceFingerprint,
                    )
                }
            }
        }
        cancelReaderRendering()
        // long: 原生渲染不能被协程取消中断，退出必须等待相同的锁，不能关闭正在绘制的页面。
        CoroutineScope(Dispatchers.IO).launch {
            readerDataMutex.withLock {
                renderMutex.withLock {
                    closeRenderer()
                    runCatching { document?.close() }
                    document = null
                    runCatching { documentSourceFile?.delete() }
                    documentSourceFile = null
                    if (!state.dirty) runCatching { cacheFile?.delete() }
                    cacheFile = null
                }
            }
        }
    }

    companion object {
        const val RENDER_DPI = 144f
        private const val OCR_DPI = 200f
        private const val MAX_TILE_PX = 4096
        private const val MAX_EDIT_RENDER_PIXELS = 12_000_000L
        private const val MAX_EDIT_RENDER_DIMENSION = 8192
        private const val MAX_OCR_RENDER_PIXELS = 16_000_000L
        private const val MAX_OCR_RENDER_DIMENSION = 8192
        private const val DEFAULT_READER_WIDTH_PX = 1080
        private const val MIN_READER_WIDTH_PX = 320
        private const val MAX_READER_WIDTH_PX = 2560
        private const val THUMBNAIL_WIDTH_PX = 180
        private const val MAX_READER_PAGE_CACHE = 8
        private const val MAX_THUMBNAIL_CACHE = 40
        private const val TAG = "PdfReader"
        private const val MAX_HISTORY = 50
        private const val MAX_HISTORY_BYTES = 16L * 1024 * 1024
        private const val MAX_PKCS12_BYTES = 16L * 1024 * 1024
    }
}

private data class LoadedDocument(
    val document: PDDocument,
    val sourceFile: File,
    val workingFile: File,
    val title: String,
    val sourceUri: String?,
    val sourceFingerprint: PdfFileFingerprint?,
    val canOverwrite: Boolean,
    val pageInfos: List<ReaderPageInfo>,
    val outline: List<ReaderOutlineEntry>,
    val links: List<PdfLinkInfo>,
    val password: String?,
) {
    fun dispose() {
        runCatching { document.close() }
        runCatching { sourceFile.delete() }
        runCatching { workingFile.delete() }
    }
}

data class PdfPasswordPrompt(
    val requestId: Int,
    val title: String,
    val error: String? = null,
)

private sealed interface PendingPdfPasswordAction {
    val title: String

    data class Open(
        val uri: Uri,
        val mode: AppMode,
        val startPage: Int,
        override val title: String,
    ) : PendingPdfPasswordAction

    data class Recovery(
        val recovery: PdfRecoveryInfo,
        override val title: String,
    ) : PendingPdfPasswordAction
}

private data class PageRenderResult(
    val bitmap: Bitmap,
    val parsed: ParsedPage,
    val transform: PageTransform,
    val annotations: List<PdfAnnotationInfo>,
)

private data class PreparedOcrPage(
    val pageIndex: Int,
    val recognition: OcrPageRecognition,
)

private sealed class EditSnapshot {
    abstract val pageIndex: Int
    abstract val sizeBytes: Long

    open fun dispose() = Unit

    class Document(
        override val pageIndex: Int,
        val file: File,
        val reloadPassword: String?,
    ) : EditSnapshot() {
        override val sizeBytes: Long get() = file.length()
        override fun dispose() {
            runCatching { file.delete() }
        }
    }
}

private sealed class ElementClipboard {
    class Text(
        val text: String,
        val fontSize: Float,
        val fillArgb: Int,
        val bounds: Bounds,
        val sourcePageIndex: Int,
    ) : ElementClipboard()

    class Raw(
        val kind: NodeKind,
        val tokens: List<Any>,
        val sourceResources: PDResources?,
        val bounds: Bounds,
        val sourcePageIndex: Int,
    ) : ElementClipboard()

    class Group(
        val nodes: List<RawPasteNode>,
        val bounds: Bounds,
        val sourcePageIndex: Int,
    ) : ElementClipboard()
}

private class PasteExecution(
    val result: EditResult,
    val before: EditSnapshot.Document,
    val embeddedFont: Boolean,
)

private fun Bounds.copyBounds() = Bounds(minX, minY, maxX, maxY)

private fun Bounds.overlaps(other: Bounds): Boolean =
    maxOf(minX, other.minX) <= minOf(maxX, other.maxX) &&
        maxOf(minY, other.minY) <= minOf(maxY, other.maxY)

private class LayerMove(
    val stream: com.loooong.pdfreader.engine.ParsedStream,
    val start: Int,
    val end: Int,
    val insertAt: Int,
)

/** long: 仅把单子节点 q/Q 分组视为自包含图层，避免重排后继承到错误的颜色或 CTM。 */
private fun findSafeLayerMove(root: DrawNode, id: Int, action: LayerAction): LayerMove? {
    val path = ArrayList<DrawNode>()
    fun walk(node: DrawNode): Boolean {
        path.add(node)
        if (node.id == id) return true
        for (child in node.children) if (walk(child)) return true
        path.removeAt(path.lastIndex)
        return false
    }
    if (!walk(root)) return null
    val selectedIndex = path.lastIndex
    val selected = path[selectedIndex]
    val blockIndex = when {
        selected.kind == NodeKind.GROUP && isSafeLayerBlock(selected) -> selectedIndex
        selectedIndex > 0 -> {
            val parent = path[selectedIndex - 1]
            if (parent.children.size == 1 && isSafeLayerBlock(parent)) selectedIndex - 1 else return null
        }
        else -> return null
    }
    if (blockIndex <= 0) return null
    val block = path[blockIndex]
    val container = path[blockIndex - 1]
    val siblings = container.children
    val index = siblings.indexOfFirst { it.id == block.id }
    if (index < 0) return null
    val insertAt = when (action) {
        LayerAction.FORWARD -> siblings.getOrNull(index + 1)?.endIndex?.plus(1)
        LayerAction.BACKWARD -> siblings.getOrNull(index - 1)?.startIndex
        LayerAction.TO_FRONT -> siblings.lastOrNull()?.takeIf { it.id != block.id }?.endIndex?.plus(1)
        LayerAction.TO_BACK -> siblings.firstOrNull()?.takeIf { it.id != block.id }?.startIndex
    } ?: return null
    return LayerMove(requireNotNull(block.stream), block.startIndex, block.endIndex, insertAt)
}

private fun isSafeLayerBlock(node: DrawNode): Boolean {
    val stream = node.stream ?: return false
    if (node.kind != NodeKind.GROUP || stream.owner !is StreamOwner.Page) return false
    val open = stream.tokens.getOrNull(node.startIndex) as? Operator
    val close = stream.tokens.getOrNull(node.endIndex) as? Operator
    return open?.name == "q" && close?.name == "Q"
}

class EditTarget(
    val node: DrawNode,
    val caps: EditCaps,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val fillArgb: Int?,
    val strokeArgb: Int?,
    val text: String?,
    val colorSpace: String?,
    val fontSize: Float,
    val characterSpacing: Float,
    val lineLeading: Float,
    val opacity: Float,
    val blendMode: String,
    val lineWidth: Float,
    val lineCap: Int,
    val lineJoin: Int,
    val miterLimit: Float,
    val cropLeft: Float,
    val cropTop: Float,
    val cropRight: Float,
    val cropBottom: Float,
    val fontOptions: List<FontOption> = emptyList(),
)

data class PdfUiState(
    val loading: Boolean = false,
    val busy: String? = null,
    val hasDocument: Boolean = false,
    val mode: AppMode? = null,
    val bitmap: ImageBitmap? = null,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val elementCount: Int = 0,
    val fileName: String = "",
    val sourceUri: String? = null,
    val canOverwrite: Boolean = false,
    val digitalSignatureCount: Int = 0,
    val digitalSignatureInspectionFailed: Boolean = false,
    val page: ParsedPage? = null,
    val pageTransform: PageTransform? = null,
    val selectedId: Int? = null,
    val selectedIds: Set<Int> = emptySet(),
    val annotations: List<PdfAnnotationInfo> = emptyList(),
    val selectedAnnotationIndex: Int? = null,
    val editingId: Int? = null,
    val swatchColors: Map<Int, Int> = emptyMap(),
    val revealTick: Int = 0,
    val expanded: Set<Int> = emptySet(),
    val showRaw: Boolean = false,
    val snapEnabled: Boolean = true,
    val dirty: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val hasElementClipboard: Boolean = false,
    val documentSessionId: Int = 0,
    val documentToken: Int = 0,
    val fontCatalogTick: Int = 0,
    val error: String? = null,
)

data class ReaderPageState(
    val info: ReaderPageInfo,
    val widthPx: Int,
    val bitmap: ImageBitmap? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

data class ReaderUiState(
    val pageInfos: List<ReaderPageInfo> = emptyList(),
    val outline: List<ReaderOutlineEntry> = emptyList(),
    val links: List<PdfLinkInfo> = emptyList(),
    val bookmarks: Set<Int> = emptySet(),
    val searchQuery: String = "",
    val searchResults: List<ReaderSearchResult> = emptyList(),
    val searchLimitReached: Boolean = false,
    val searching: Boolean = false,
    val searchProgress: Int = 0,
    val searchError: String? = null,
    val indexing: Boolean = false,
    val indexedPageCount: Int = 0,
    val activeSearchMatchIndex: Int? = null,
)
