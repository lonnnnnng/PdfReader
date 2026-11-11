package com.loooong.pdfreader.engine

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.loooong.pdfreader.ui.PageTransform
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageDecorationEditorTest {

    @Test
    fun selectedPagesReceiveWatermarkAndSequentialPageNumbersAfterReload() {
        val document = PDDocument().apply {
            repeat(3) { addPage(PDPage(PDRectangle(600f, 800f))) }
        }

        val result = PdfPageDecorationEditor.apply(
            document = document,
            request = PdfPageDecorationRequest(
                pageIndices = setOf(0, 2),
                watermark = PdfWatermarkRequest(text = "DRAFT"),
                pageNumber = PdfPageNumberRequest(
                    template = "{page}/{total}",
                    startNumber = 4,
                ),
            ),
            resolveFont = { PDType1Font.HELVETICA },
        )

        assertEquals(2, result.decoratedPageCount)
        assertEquals(2, result.watermarkCount)
        assertEquals(2, result.pageNumberCount)
        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        document.close()

        PDDocument.load(saved).use { reopened ->
            val firstPageText = pageText(reopened, 0)
            val secondPageText = pageText(reopened, 1)
            val thirdPageText = pageText(reopened, 2)
            assertTrue(firstPageText.contains("DRAFT"))
            assertTrue(firstPageText.contains("4/2"))
            assertFalse(secondPageText.contains("DRAFT"))
            assertFalse(secondPageText.contains("/2"))
            assertTrue(thirdPageText.contains("DRAFT"))
            assertTrue(thirdPageText.contains("5/2"))
        }
    }

    @Test
    fun unknownPageNumberPlaceholderIsRejectedBeforePageMutation() {
        val document = PDDocument().apply { addPage(PDPage(PDRectangle(600f, 800f))) }
        var rejected = false

        try {
            PdfPageDecorationEditor.apply(
                document = document,
                request = PdfPageDecorationRequest(
                    pageIndices = setOf(0),
                    pageNumber = PdfPageNumberRequest(template = "{page}/{section}"),
                ),
                resolveFont = { PDType1Font.HELVETICA },
            )
        } catch (expected: IllegalArgumentException) {
            rejected = true
            assertTrue(expected.message.orEmpty().contains("占位符"))
        }

        assertTrue(rejected)
        assertTrue(ContentStreamEngine.parse(document.getPage(0)).leaves.isEmpty())
        document.close()
    }

    @Test
    fun rotatedCroppedPageUsesVisualTopRightAndKeepsOpacity() {
        val page = PDPage(PDRectangle(600f, 800f)).apply {
            cropBox = PDRectangle(50f, 100f, 400f, 600f)
            rotation = 90
        }
        val document = PDDocument().apply { addPage(page) }

        PdfPageDecorationEditor.apply(
            document = document,
            request = PdfPageDecorationRequest(
                pageIndices = setOf(0),
                watermark = PdfWatermarkRequest(
                    text = "TOP",
                    fontSize = 20f,
                    opacity = 0.35f,
                    rotationDegrees = 0f,
                    position = PageDecorationPosition.TOP_RIGHT,
                    margin = 18f,
                ),
            ),
            resolveFont = { PDType1Font.HELVETICA },
        )

        val textNode = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.TEXT }
        val visualRect = PageTransform(
            cropX = page.cropBox.lowerLeftX,
            cropY = page.cropBox.lowerLeftY,
            cropW = page.cropBox.width,
            cropH = page.cropBox.height,
            rotation = page.rotation,
            scale = 1f,
        ).toRect(requireNotNull(textNode.bounds))
        assertEquals(600f - 18f, visualRect.right, 2f)
        assertEquals(18f, visualRect.top, 2f)
        assertEquals(0.35f, textNode.nonStrokingAlpha, 0.01f)
        document.close()
    }

    private fun pageText(document: PDDocument, pageIndex: Int): String =
        ContentStreamEngine.parse(document.getPage(pageIndex)).leaves
            .filter { it.kind == NodeKind.TEXT }
            .joinToString("\n") { it.text.orEmpty() }
}
