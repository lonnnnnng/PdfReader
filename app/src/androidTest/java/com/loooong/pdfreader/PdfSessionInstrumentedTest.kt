package com.loooong.pdfreader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.loooong.pdfreader.engine.NodeKind
import com.loooong.pdfreader.engine.ShapeKind
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** long: 用真实 Android 渲染和文件流验证跨文档事务，JVM 默认返回值无法覆盖这些回归。 */
@RunWith(AndroidJUnit4::class)
class PdfSessionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var vm: PdfDocumentViewModel
    private lateinit var store: ViewModelStore
    private val fixtures = mutableListOf<File>()

    @Before fun setup() {
        PDFBoxResourceLoader.init(context)
        PdfRecoveryStore.clear(context, true)
        main { store = ViewModelStore(); vm = PdfDocumentViewModel(); store.put("test", vm) }
    }

    @After fun cleanup() {
        main { store.clear() }
        await { documentOf(vm) == null }
        PdfRecoveryStore.clear(context, true)
        fixtures.forEach { it.delete() }
    }

    @Test fun mixedContentAndPageHistorySurvivesClosedDocuments() {
        open(fixture(2), AppMode.EDIT)
        main { vm.insertShape(context, ShapeKind.RECTANGLE) }; idle()
        val editedCount = vm.state.elementCount
        main { vm.duplicateCurrentPage(context) }; idle()
        assertEquals(3, vm.state.pageCount)
        main { vm.undo() }; idle()
        assertEquals(2, vm.state.pageCount)
        main { vm.undo() }; idle()
        assertTrue(vm.state.elementCount < editedCount)
        main { vm.redo() }; idle()
        assertEquals(editedCount, vm.state.elementCount)
        main { vm.redo() }; idle()
        assertEquals(3, vm.state.pageCount)
        val exported = temp()
        var saved = false
        main { vm.saveCopy(context, Uri.fromFile(exported)) { saved = it } }; idle()
        assertTrue(saved)
        PDDocument.load(exported).use { doc ->
            assertEquals(3, doc.numberOfPages)
            assertTrue(doc.getPage(0).resources.xObjectNames.any())
            assertTrue(doc.getPage(1).resources.xObjectNames.any())
        }
    }

    @Test fun failedImageEditPreservesEarlierRecoveryAndAllowsNextEdit() {
        open(fixture(), AppMode.EDIT)
        main { vm.insertShape(context, ShapeKind.RECTANGLE) }; idle()
        val before = vm.state.elementCount
        val badImage = temp().apply { writeText("not an image") }
        main { vm.insertImage(context, Uri.fromFile(badImage)) }; idle(allowError = true)
        val recovery = requireNotNull(PdfRecoveryStore.load(context))
        assertTrue(recovery.file.isFile)
        PDDocument.load(recovery.file).use { assertEquals(1, it.numberOfPages) }
        assertEquals(before, vm.state.elementCount)
        main { vm.insertShape(context, ShapeKind.LINE) }; idle()
        assertTrue(vm.state.elementCount > before)
    }

    @Test fun sameSourceCopyAndOverlappingSaveCannotOverwriteOriginal() {
        val source = fixture()
        val original = source.readBytes()
        open(source, AppMode.EDIT)
        var concurrentSave = true
        main {
            vm.insertShape(context, ShapeKind.RECTANGLE)
            vm.saveCopy(context, Uri.fromFile(temp())) { concurrentSave = it }
            vm.duplicateCurrentPage(context)
        }
        idle()
        assertFalse(concurrentSave)
        assertEquals(1, vm.state.pageCount)
        var sameSourceSave = true
        main { vm.saveCopy(context, Uri.fromFile(source)) { sameSourceSave = it } }
        assertFalse(sameSourceSave)
        assertTrue(original.contentEquals(source.readBytes()))
        assertTrue(vm.state.dirty)
        var exported = true
        main { vm.exportPages(context, Uri.fromFile(source), setOf(0)) { exported = it } }
        assertFalse(exported)
        assertTrue(original.contentEquals(source.readBytes()))
    }

    @Test fun restoredDraftRetainsSourceFingerprintForProtectedOverwrite() {
        val source = fixture()
        open(source, AppMode.EDIT)
        main { vm.insertShape(context, ShapeKind.LINE) }; idle()
        val draft = requireNotNull(PdfRecoveryStore.load(context))
        main { store.clear() }
        await { documentOf(vm) == null }
        main { store = ViewModelStore(); vm = PdfDocumentViewModel(); store.put("test", vm) }
        main { vm.resumeRecovery(context) }; idle()
        assertEquals(draft.sourceFingerprint, PdfRecoveryStore.load(context)?.sourceFingerprint)
        var result: PdfSaveOutcome? = null
        main { vm.saveOriginal(context) { result = it } }; idle()
        assertEquals(PdfSaveOutcome.SAVED, result)
        assertFalse(vm.state.dirty)
    }

    @Test fun failedOpenKeepsOldSourceAndEditableContentTogether() {
        val original = fixture()
        open(original, AppMode.EDIT)
        main { vm.insertShape(context, ShapeKind.LINE) }; idle()
        val oldCount = vm.state.elementCount
        val bad = temp().apply { writeText("invalid PDF") }
        main { vm.open(context, Uri.fromFile(bad), AppMode.READ) }; idle(allowError = true)
        assertEquals(Uri.fromFile(original).toString(), vm.state.sourceUri)
        assertEquals(oldCount, vm.state.elementCount)
        assertTrue(vm.state.dirty)
    }

    @Test fun latestReaderWidthWinsAndVisibleLongPageSurvivesPrefetch() {
        open(fixture(3, PDRectangle(100f, 10000f)), AppMode.READ)
        main {
            vm.retainReaderPage(0)
            vm.ensureReaderPage(0, 2560)
            vm.ensureReaderPage(0, 640)
            vm.ensureReaderPage(1, 2560)
            vm.ensureReaderPage(2, 2560)
        }
        await { vm.readerPages[0]?.bitmap != null && vm.readerPages[0]?.widthPx == 640 &&
            vm.readerPages.values.none { it.loading } }
        val page = requireNotNull(vm.readerPages[0]?.bitmap)
        assertTrue(page.height <= 8192)
        assertTrue(page.width.toLong() * page.height <= 4_000_000)
    }

    @Test fun largePageRegionKeepsNativeCoordinateScale() = runBlocking {
        open(fixture(1, PDRectangle(5000f, 5000f), grid = true), AppMode.READ)
        val tile = requireNotNull(vm.renderRegion(0, 4800f, 4800f, 5200f, 5200f, 200, 200))
        try {
            val pixel = tile.getPixel(100, 100)
            assertTrue("中心标记应位于 tile 中心", Color.red(pixel) > 200 && Color.green(pixel) < 80)
        } finally { tile.recycle() }
    }

    @Test fun annotationOnlyPasswordAllowsNotesButRejectsContentAndAssembly() {
        val source = fixture(permission = AccessPermission().apply {
            setCanModify(false); setCanModifyAnnotations(true); setCanFillInForm(false); setCanAssembleDocument(false)
        })
        main { vm.open(context, Uri.fromFile(source), AppMode.EDIT) }
        await { vm.passwordPrompt != null }
        main { vm.submitPdfPassword(context, "reader") }; idle()
        assertEquals(AppMode.EDIT, vm.state.mode)
        main { vm.insertShape(context, ShapeKind.LINE); vm.rotateCurrentPage(context) }
        assertFalse(vm.state.dirty)
        val text = requireNotNull(vm.parsed).leaves.first { it.kind == NodeKind.TEXT }
        main { vm.select(text.id); vm.addNoteToSelection(context, "allowed annotation") }; idle()
        assertTrue(vm.state.dirty)
        assertEquals(1, vm.state.annotations.size)
        main { vm.undo() }; idle()
        assertEquals(0, vm.state.annotations.size)
        main { vm.redo() }; idle()
        assertEquals(1, vm.state.annotations.size)
        val exported = temp()
        main { vm.saveCopy(context, Uri.fromFile(exported)) }; idle()
        listOf("reader", "owner").forEach { password ->
            PDDocument.load(exported, password).use {
                assertTrue(it.isEncrypted)
                assertEquals(1, it.getPage(0).annotations.size)
            }
        }
    }

    @Test fun chineseTextSupportsRepeatedEditsHistoryAndExport() {
        open(fixture(), AppMode.EDIT)
        main { vm.insertTextAtCenter(context, "中文编辑第一轮") }; idle()
        main { vm.insertTextAtCenter(context, "第二次添加文字") }; idle()
        main { vm.undo() }; idle()
        main { vm.redo() }; idle()
        val exported = temp()
        main { vm.saveCopy(context, Uri.fromFile(exported)) }; idle()
        PDDocument.load(exported).use {
            val text = com.tom_roush.pdfbox.text.PDFTextStripper().getText(it)
            assertTrue("导出文字：$text", text.contains("中文编辑第一轮"))
            assertTrue("导出文字：$text", text.contains("第二次添加文字"))
        }
    }

    @Test fun viewModelDisposalWaitsForDocumentUsers() {
        open(fixture(), AppMode.READ)
        val field = PdfDocumentViewModel::class.java.getDeclaredField("readerDataMutex").apply { isAccessible = true }
        val mutex = field.get(vm) as Mutex
        runBlocking { mutex.lock() }
        try {
            main { store.clear() }
            SystemClock.sleep(100)
            assertNotNull(documentOf(vm))
        } finally { mutex.unlock() }
        await { documentOf(vm) == null }
    }

    @Test fun externalOpenRotationKeepsUnsavedEditingSession() {
        val source = fixture(2)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            var activityVm: PdfDocumentViewModel? = null
            scenario.onActivity { activityVm = ViewModelProvider(it)[PdfDocumentViewModel::class.java] }
            val active = requireNotNull(activityVm)
            await { active.state.hasDocument && !active.state.loading }
            main { active.switchMode(AppMode.EDIT) }
            await { active.state.mode == AppMode.EDIT && active.state.busy == null }
            main { active.showPage(1) }; await { active.state.busy == null && active.state.pageIndex == 1 }
            main { active.insertShape(context, ShapeKind.LINE) }; await { active.state.busy == null && active.state.dirty }
            val token = active.state.documentToken
            scenario.recreate()
            scenario.onActivity { assertSame(active, ViewModelProvider(it)[PdfDocumentViewModel::class.java]) }
            assertEquals(token, active.state.documentToken)
            assertEquals(1, active.state.pageIndex)
            assertTrue(active.state.dirty)
            main { active.saveCopy(context, Uri.fromFile(temp())) }; await { active.state.busy == null }
        }
    }

    private fun fixture(pages: Int = 1, size: PDRectangle = PDRectangle.LETTER,
                        grid: Boolean = false, permission: AccessPermission? = null): File = temp().also { file ->
        PDDocument().use { doc ->
            repeat(pages) {
                val page = PDPage(size); doc.addPage(page)
                PDPageContentStream(doc, page).use { stream ->
                    if (grid) {
                        stream.setNonStrokingColor(255, 0, 0)
                        stream.addRect(2300f, 2300f, 400f, 400f); stream.fill()
                    } else {
                        stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12f)
                        stream.newLineAtOffset(10f, size.height - 30f); stream.showText("PDF regression page $it"); stream.endText()
                        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
                        stream.drawImage(LosslessFactory.createFromImage(doc, bitmap), 10f, 20f, 20f, 20f)
                        bitmap.recycle()
                    }
                }
            }
            if (permission != null) doc.protect(StandardProtectionPolicy("owner", "reader", permission).apply { encryptionKeyLength = 128 })
            doc.save(file)
        }
    }

    private fun temp(): File {
        val directory = File(context.cacheDir, "external-pdf").apply { mkdirs() }
        return File.createTempFile("session-test-", ".pdf", directory).also(fixtures::add)
    }
    private fun open(file: File, mode: AppMode) { main { vm.open(context, Uri.fromFile(file), mode) }; idle() }
    private fun idle(allowError: Boolean = false) {
        await { !vm.state.loading && vm.state.busy == null && vm.state.hasDocument }
        if (!allowError) assertNull(vm.state.error, vm.state.error)
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            main { done = condition() }
            if (done) return
            SystemClock.sleep(25)
        }
        throw AssertionError("等待 PDF 操作超时：${vm.state}")
    }
    private fun documentOf(model: PdfDocumentViewModel): Any? =
        PdfDocumentViewModel::class.java.getDeclaredField("document").apply { isAccessible = true }.get(model)
}
