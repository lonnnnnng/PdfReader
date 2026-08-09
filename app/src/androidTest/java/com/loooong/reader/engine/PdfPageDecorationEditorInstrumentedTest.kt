package com.loooong.reader.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.loooong.reader.FontCatalog
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfPageDecorationEditorInstrumentedTest {

    @Test
    fun chineseWatermarkResolvedFromDeviceFontsSurvivesReload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val document = PDDocument().apply { addPage(PDPage(PDRectangle(600f, 800f))) }
        val fontCatalog = FontCatalog(context)

        PdfPageDecorationEditor.apply(
            document = document,
            request = PdfPageDecorationRequest(
                pageIndices = setOf(0),
                watermark = PdfWatermarkRequest(text = "内部资料"),
            ),
            resolveFont = { text -> fontCatalog.resolveForText(document, text) },
        )

        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        document.close()
        PDDocument.load(saved).use { reopened ->
            val text = ContentStreamEngine.parse(reopened.getPage(0)).leaves
                .filter { it.kind == NodeKind.TEXT }
                .joinToString("\n") { it.text.orEmpty() }
            assertTrue(text.contains("内部资料"))
        }
    }
}
