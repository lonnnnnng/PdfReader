package com.loooong.reader.engine

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PdfAnnotationEditorTest {

    @Test
    fun highlightAndNoteAreStoredAsStandardPageAnnotations() {
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        PdfAnnotationEditor.addHighlight(document, page, Bounds(10f, 20f, 110f, 40f))
        PdfAnnotationEditor.addNote(document, page, Bounds(10f, 20f, 110f, 40f), "检查这里")

        val annotations = page.annotations
        assertEquals(2, annotations.size)
        val highlight = annotations[0] as PDAnnotationTextMarkup
        assertEquals(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT, highlight.subtype)
        assertEquals(8, highlight.quadPoints.size)
        assertTrue(annotations[1] is PDAnnotationText)
        assertEquals("检查这里", annotations[1].contents)

        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        PDDocument.load(saved).use { reopened ->
            assertEquals(2, reopened.getPage(0).annotations.size)
        }
        document.close()
    }

    @Test
    fun annotationSnapshotRestoresPreviousPageState() {
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)
        val before = ElementEditor.snapshot(page)

        PdfAnnotationEditor.addHighlight(document, page, Bounds(10f, 20f, 110f, 40f))
        assertEquals(1, page.annotations.size)

        ElementEditor.restore(document, page, before)
        assertEquals(0, page.annotations.size)
        document.close()
    }

    @Test
    fun signatureIsStoredAsInkAnnotationWithPdfCoordinates() {
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        val added = PdfAnnotationEditor.addSignature(
            document,
            page,
            listOf(listOf(0f to 1f, 0.5f to 0.5f, 1f to 0f)),
        )

        assertTrue(added)
        val annotation = page.annotations.single() as PDAnnotationMarkup
        assertEquals(PDAnnotationMarkup.SUB_TYPE_INK, annotation.subtype)
        assertEquals(1, annotation.inkList.size)
        assertEquals(6, annotation.inkList[0].size)
        document.close()
    }

    @Test
    fun listAndDeleteUseTheVisibleAnnotationIndex() {
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)
        PdfAnnotationEditor.addHighlight(document, page, Bounds(10f, 20f, 110f, 40f))
        PdfAnnotationEditor.addNote(document, page, Bounds(20f, 30f, 80f, 50f), "保留")

        val annotations = PdfAnnotationEditor.list(page)
        assertEquals(listOf(0, 1), annotations.map { it.index })
        assertEquals("Highlight", annotations[0].subtype)
        assertEquals("Text", annotations[1].subtype)
        assertTrue(PdfAnnotationEditor.delete(document, page, annotations[0].index))

        val remaining = PdfAnnotationEditor.list(page)
        assertEquals(1, remaining.size)
        assertEquals("保留", remaining.single().contents)
        document.close()
    }

    @Test
    fun formWidgetCannotBeChangedThroughTheGenericAnnotationEditor() {
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)
        PdfFormEditor.create(
            document,
            PdfFormFieldCreateRequest(
                kind = PdfFormFieldCreateKind.TEXT,
                name = "customer_name",
                label = "客户姓名",
                pageIndex = 0,
                bounds = Bounds(20f, 30f, 180f, 64f),
            ),
        )

        val widget = PdfAnnotationEditor.list(page).single()

        assertEquals("Widget", widget.subtype)
        assertFalse(widget.editable)
        assertFalse(PdfAnnotationEditor.move(document, page, widget.index, 8f, 4f))
        assertFalse(PdfAnnotationEditor.delete(document, page, widget.index))
        assertEquals(1, page.annotations.size)
        assertEquals(1, requireNotNull(document.documentCatalog.acroForm).fieldTree.count())
        document.close()
    }

    @Test
    fun movingHighlightUpdatesRectangleAndQuadPointsTogether() {
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)
        PdfAnnotationEditor.addHighlight(document, page, Bounds(10f, 20f, 110f, 40f))
        val before = (page.annotations.single() as PDAnnotationTextMarkup).quadPoints.copyOf()

        assertTrue(PdfAnnotationEditor.move(document, page, 0, 7f, -3f))

        val info = PdfAnnotationEditor.list(page).single()
        assertEquals(17f, info.bounds.minX, 0.001f)
        assertEquals(17f, info.bounds.minY, 0.001f)
        val after = (page.annotations.single() as PDAnnotationTextMarkup).quadPoints
        assertEquals(before[0] + 7f, after[0], 0.001f)
        assertEquals(before[1] - 3f, after[1], 0.001f)
        document.close()
    }

    @Test
    fun underlineAndStrikeoutAreStoredAsStandardTextMarkupAnnotations() {
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        PdfAnnotationEditor.addTextMarkup(
            document,
            page,
            Bounds(10f, 20f, 110f, 40f),
            TextMarkupKind.UNDERLINE,
        )
        PdfAnnotationEditor.addTextMarkup(
            document,
            page,
            Bounds(20f, 50f, 120f, 70f),
            TextMarkupKind.STRIKEOUT,
        )

        assertEquals(
            listOf(PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE, PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT),
            page.annotations.map { it.subtype },
        )
        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        PDDocument.load(saved).use { reopened ->
            assertEquals(
                listOf(PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE, PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT),
                reopened.getPage(0).annotations.map { it.subtype },
            )
        }
        document.close()
    }

    @Test
    fun annotationPropertiesUpdateColorOpacityAndContents() {
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)
        PdfAnnotationEditor.addHighlight(document, page, Bounds(10f, 20f, 110f, 40f))

        assertTrue(
            PdfAnnotationEditor.update(
                document,
                page,
                index = 0,
                request = AnnotationEditRequest(
                    colorArgb = 0xFF1565C0.toInt(),
                    opacity = 0.7f,
                    contents = "重点复核",
                ),
            ),
        )

        val info = PdfAnnotationEditor.list(page).single()
        assertEquals(0xFF1565C0.toInt(), info.colorArgb)
        assertEquals(0.7f, info.opacity, 0.001f)
        assertEquals("重点复核", info.contents)
        document.close()
    }

    @Test
    fun freeTextStoresContentsAndReusableAppearance() {
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        PdfAnnotationEditor.addFreeText(
            document = document,
            page = page,
            bounds = Bounds(20f, 30f, 180f, 90f),
            contents = "Review this paragraph",
            font = PDType1Font.HELVETICA,
            colorArgb = 0xFF202124.toInt(),
        )

        val annotation = page.annotations.single() as PDAnnotationMarkup
        assertEquals(PDAnnotationMarkup.SUB_TYPE_FREETEXT, annotation.subtype)
        assertEquals("Review this paragraph", annotation.contents)
        assertTrue(annotation.normalAppearanceStream != null)
        assertEquals(12f, PdfAnnotationEditor.list(page).single().fontSize!!, 0.001f)
        assertTrue(
            PdfAnnotationEditor.update(
                document,
                page,
                0,
                AnnotationEditRequest(
                    colorArgb = 0xFF202124.toInt(),
                    opacity = 1f,
                    contents = "Review this paragraph",
                    font = PDType1Font.HELVETICA,
                    fontSize = 18f,
                ),
            ),
        )
        assertEquals(18f, PdfAnnotationEditor.list(page).single().fontSize!!, 0.001f)
        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        PDDocument.load(saved).use { reopened ->
            val reopenedAnnotation = reopened.getPage(0).annotations.single()
            assertEquals(PDAnnotationMarkup.SUB_TYPE_FREETEXT, reopenedAnnotation.subtype)
            assertTrue(reopenedAnnotation.normalAppearanceStream != null)
        }
        document.close()
    }

    @Test
    fun stampStoresStandardNameAndReusableAppearance() {
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        PdfAnnotationEditor.addStamp(
            document = document,
            page = page,
            bounds = Bounds(20f, 30f, 180f, 90f),
            kind = StampKind.APPROVED,
            font = PDType1Font.HELVETICA_BOLD,
        )

        val stamp = page.annotations.single() as PDAnnotationRubberStamp
        assertEquals(PDAnnotationRubberStamp.NAME_APPROVED, stamp.name)
        assertEquals(PDAnnotationRubberStamp.SUB_TYPE, stamp.subtype)
        assertTrue(stamp.normalAppearanceStream != null)
        document.close()
    }

    @Test
    fun lockedAnnotationRejectsMoveDeleteAndPropertyUpdates() {
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)
        PdfAnnotationEditor.addHighlight(document, page, Bounds(10f, 20f, 110f, 40f))
        page.annotations.single().isLockedContents = true

        assertTrue(!PdfAnnotationEditor.list(page).single().editable)
        assertTrue(!PdfAnnotationEditor.move(document, page, 0, 5f, 5f))
        assertTrue(!PdfAnnotationEditor.delete(document, page, 0))
        try {
            PdfAnnotationEditor.update(
                document,
                page,
                0,
                AnnotationEditRequest(0xFF202124.toInt(), 1f, "不能修改"),
            )
            throw AssertionError("expected locked annotation update to be rejected")
        } catch (_: IllegalArgumentException) {
            // long: 锁定批注的颜色、内容和透明度都必须保持原值，避免绕过 UI 直接改写。
        }
        document.close()
    }
}
