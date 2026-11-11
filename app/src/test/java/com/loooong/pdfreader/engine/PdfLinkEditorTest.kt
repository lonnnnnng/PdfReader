package com.loooong.pdfreader.engine

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PdfLinkEditorTest {

    @Test
    fun uriLinkRemainsEditableAfterSaveAndReload() {
        val saved = PDDocument().use { document ->
            document.addPage(PDPage(PDRectangle.A4))

            val created = PdfLinkEditor.create(
                document,
                PdfLinkCreateRequest(
                    pageIndex = 0,
                    bounds = Bounds(24f, 48f, 180f, 76f),
                    target = PdfLinkTarget.Uri("https://example.com/manual"),
                ),
            )

            assertEquals(PdfLinkId(pageIndex = 0, annotationIndex = 0), created)
            ByteArrayOutputStream().also(document::save).toByteArray()
        }

        PDDocument.load(saved).use { reopened ->
            val link = PdfLinkEditor.list(reopened).single()
            assertEquals(PdfLinkKind.URI, link.kind)
            assertEquals("https://example.com/manual", link.uri)
            val bounds = requireNotNull(link.bounds)
            assertEquals(24f, bounds.minX, 0.001f)
            assertEquals(48f, bounds.minY, 0.001f)
            assertEquals(180f, bounds.maxX, 0.001f)
            assertEquals(76f, bounds.maxY, 0.001f)
            assertTrue(link.editable)
        }
    }

    @Test
    fun pageLinkKeepsItsDestinationPageAndViewAfterReload() {
        val saved = PDDocument().use { document ->
            repeat(3) { document.addPage(PDPage(PDRectangle.A4)) }
            PdfLinkEditor.create(
                document,
                PdfLinkCreateRequest(
                    pageIndex = 0,
                    bounds = Bounds(32f, 96f, 220f, 124f),
                    target = PdfLinkTarget.Page(
                        pageIndex = 2,
                        left = 18,
                        top = 640,
                        zoom = 1.25f,
                    ),
                ),
            )
            ByteArrayOutputStream().also(document::save).toByteArray()
        }

        PDDocument.load(saved).use { reopened ->
            val link = PdfLinkEditor.list(reopened).single()
            assertEquals(PdfLinkKind.PAGE, link.kind)
            assertEquals(2, link.targetPageIndex)
            assertEquals(18, link.targetLeft)
            assertEquals(640, link.targetTop)
            assertEquals(1.25f, link.targetZoom ?: 0f, 0.001f)
            assertTrue(link.editable)
        }
    }

    @Test
    fun linkTargetCanSwitchKindsAndTheLinkCanBeDeleted() {
        PDDocument().use { document ->
            repeat(2) { document.addPage(PDPage(PDRectangle.A4)) }
            val id = PdfLinkEditor.create(
                document,
                PdfLinkCreateRequest(
                    pageIndex = 0,
                    bounds = Bounds(10f, 10f, 100f, 30f),
                    target = PdfLinkTarget.Uri("https://example.com/old"),
                ),
            )

            assertTrue(
                PdfLinkEditor.update(
                    document,
                    id,
                    PdfLinkUpdateRequest(
                        bounds = Bounds(20f, 20f, 140f, 52f),
                        target = PdfLinkTarget.Page(pageIndex = 1),
                    ),
                ),
            )
            assertEquals(PdfLinkKind.PAGE, PdfLinkEditor.list(document).single().kind)

            assertTrue(
                PdfLinkEditor.update(
                    document,
                    id,
                    PdfLinkUpdateRequest(
                        bounds = Bounds(20f, 20f, 140f, 52f),
                        target = PdfLinkTarget.Uri("https://example.com/new"),
                    ),
                ),
            )
            assertEquals("https://example.com/new", PdfLinkEditor.list(document).single().uri)
            assertTrue(PdfLinkEditor.delete(document, id))
            assertTrue(PdfLinkEditor.list(document).isEmpty())
        }
    }

    @Test
    fun unsupportedLinkActionIsDisplayedReadOnly() {
        PDDocument().use { document ->
            document.addPage(PDPage(PDRectangle.A4))
            document.getPage(0).annotations = listOf(
                PDAnnotationLink().apply {
                    rectangle = PDRectangle(10f, 10f, 100f, 30f)
                    cosObject.setItem(
                        COSName.A,
                        COSDictionary().apply { setName(COSName.S, "Launch") },
                    )
                },
            )

            val link = PdfLinkEditor.list(document).single()
            assertEquals(PdfLinkKind.UNSUPPORTED, link.kind)
            assertTrue(!link.editable)
        }
    }

    @Test
    fun externalUriPolicyAllowsWebAndMailButRejectsDangerousSchemes() {
        assertTrue(PdfLinkUriPolicy.validate("https://example.com/manual").allowed)
        assertTrue(PdfLinkUriPolicy.validate("mailto:reader@example.com").allowed)
        assertFalse(PdfLinkUriPolicy.validate("javascript:alert(1)").allowed)
        assertFalse(PdfLinkUriPolicy.validate("file:///sdcard/secret.pdf").allowed)
        assertFalse(PdfLinkUriPolicy.validate("content://com.example.provider/private").allowed)
        assertFalse(PdfLinkUriPolicy.validate("https:missing-host").allowed)
    }

    @Test
    fun unsafeUriCannotBeWrittenIntoPdf() {
        PDDocument().use { document ->
            document.addPage(PDPage(PDRectangle.A4))
            val failure = runCatching {
                PdfLinkEditor.create(
                    document,
                    PdfLinkCreateRequest(
                        pageIndex = 0,
                        bounds = Bounds(10f, 10f, 100f, 30f),
                        target = PdfLinkTarget.Uri("intent://open-private-component"),
                    ),
                )
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(document.getPage(0).annotations.isEmpty())
        }
    }
}
