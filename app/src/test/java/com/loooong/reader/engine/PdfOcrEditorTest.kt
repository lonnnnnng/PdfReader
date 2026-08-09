package com.loooong.reader.engine

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.loooong.reader.ui.PageTransform
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfOcrEditorTest {

    @Test
    fun hiddenLayerIsSearchableButAbsentFromInspectorAfterReload() {
        val document = PDDocument().apply { addPage(PDPage(PDRectangle(600f, 800f))) }

        val written = PdfOcrEditor.replaceLayer(
            document = document,
            page = document.getPage(0),
            recognition = recognition("Hello OCR"),
            font = PDType1Font.HELVETICA,
        )

        assertEquals(1, written)
        val parsed = ContentStreamEngine.parse(document.getPage(0))
        assertTrue(parsed.leaves.isEmpty())
        assertEquals(1, parsed.ocrLeaves.size)
        assertTrue(parsed.ocrLeaves.single().generatedOcr)
        assertTrue(pageText(document).contains("Hello OCR"))
        assertTrue(PdfOcrEditor.hasExtractableText(document, 0))

        val bytes = ByteArrayOutputStream().also(document::save).toByteArray()
        document.close()
        PDDocument.load(bytes).use { reopened ->
            val reopenedPage = ContentStreamEngine.parse(reopened.getPage(0))
            assertTrue(reopenedPage.leaves.isEmpty())
            assertEquals("Hello OCR", reopenedPage.ocrLeaves.single().text)
            assertTrue(pageText(reopened).contains("Hello OCR"))
        }
    }

    @Test
    fun replacingLayerRemovesPreviousRecognition() {
        val document = PDDocument().apply { addPage(PDPage(PDRectangle(600f, 800f))) }
        val page = document.getPage(0)

        PdfOcrEditor.replaceLayer(document, page, recognition("First result"), PDType1Font.HELVETICA)
        PdfOcrEditor.replaceLayer(document, page, recognition("Second result"), PDType1Font.HELVETICA)

        val text = pageText(document)
        assertFalse(text.contains("First result"))
        assertTrue(text.contains("Second result"))
        assertEquals(1, ContentStreamEngine.parse(page).ocrLeaves.size)
        document.close()
    }

    @Test
    fun rotatedCroppedPageMapsBitmapCoordinatesIntoVisualPage() {
        val page = PDPage(PDRectangle(600f, 800f)).apply {
            cropBox = PDRectangle(50f, 100f, 400f, 600f)
            rotation = 90
        }

        val topLeft = PdfOcrEditor.imageToVisual(page, 600, 400, OcrImagePoint(0f, 0f))
        val bottomRight = PdfOcrEditor.imageToVisual(page, 600, 400, OcrImagePoint(600f, 400f))

        assertEquals(0f, topLeft.first, 0.001f)
        assertEquals(400f, topLeft.second, 0.001f)
        assertEquals(600f, bottomRight.first, 0.001f)
        assertEquals(0f, bottomRight.second, 0.001f)
    }

    @Test
    fun rotatedCroppedPageWritesLayerAtRecognizedVisualBounds() {
        val page = PDPage(PDRectangle(600f, 800f)).apply {
            cropBox = PDRectangle(50f, 100f, 400f, 600f)
            rotation = 90
        }
        val document = PDDocument().apply { addPage(page) }
        val recognition = OcrPageRecognition(
            imageWidth = 600,
            imageHeight = 400,
            lines = listOf(
                OcrLine(
                    text = "ROTATED",
                    topLeft = OcrImagePoint(100f, 60f),
                    topRight = OcrImagePoint(300f, 60f),
                    bottomRight = OcrImagePoint(300f, 100f),
                    bottomLeft = OcrImagePoint(100f, 100f),
                ),
            ),
        )

        PdfOcrEditor.replaceLayer(document, page, recognition, PDType1Font.HELVETICA)

        val bounds = requireNotNull(ContentStreamEngine.parse(page).ocrLeaves.single().bounds)
        val visual = PageTransform(
            cropX = page.cropBox.lowerLeftX,
            cropY = page.cropBox.lowerLeftY,
            cropW = page.cropBox.width,
            cropH = page.cropBox.height,
            rotation = page.rotation,
            scale = 1f,
        ).toRect(bounds)
        assertEquals(100f, visual.left, 1.5f)
        assertEquals(60f, visual.top, 1.5f)
        assertEquals(300f, visual.right, 1.5f)
        assertEquals(100f, visual.bottom, 1.5f)
        document.close()
    }

    @Test
    fun redactionCanDeleteIntersectingGeneratedText() {
        val document = PDDocument().apply { addPage(PDPage(PDRectangle(600f, 800f))) }
        val page = document.getPage(0)
        PDPageContentStream(document, page).use { content ->
            content.setNonStrokingColor(0f, 0f, 0f)
            content.addRect(100f, 660f, 200f, 40f)
            content.fill()
        }
        PdfOcrEditor.replaceLayer(document, page, recognition("SECRET"), PDType1Font.HELVETICA)
        val parsed = ContentStreamEngine.parse(page)
        val visible = parsed.leaves.single { it.kind == NodeKind.PATH }
        val hidden = parsed.ocrLeaves.single()

        assertTrue(ElementEditor.redactNodes(document, page, listOf(visible, hidden)) is EditResult.Applied)
        assertFalse(pageText(document).contains("SECRET"))
        assertTrue(ContentStreamEngine.parse(page).ocrLeaves.isEmpty())
        document.close()
    }

    private fun recognition(text: String) = OcrPageRecognition(
        imageWidth = 600,
        imageHeight = 800,
        lines = listOf(
            OcrLine(
                text = text,
                topLeft = OcrImagePoint(100f, 100f),
                topRight = OcrImagePoint(300f, 100f),
                bottomRight = OcrImagePoint(300f, 140f),
                bottomLeft = OcrImagePoint(100f, 140f),
            ),
        ),
    )

    private fun pageText(document: PDDocument): String = PDFTextStripper().apply {
        startPage = 1
        endPage = 1
    }.getText(document)
}
