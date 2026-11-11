package com.loooong.pdfreader.engine

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PageGeometryEditorTest {

    @Test
    fun cropAppliesFourMarginsRelativeToMediaBox() {
        val page = PDPage(PDRectangle(10f, 20f, 600f, 800f))

        PageGeometryEditor.crop(
            page,
            PageCropMargins(left = 10f, top = 20f, right = 30f, bottom = 40f),
        )

        assertEquals(20f, page.cropBox.lowerLeftX, 0.001f)
        assertEquals(60f, page.cropBox.lowerLeftY, 0.001f)
        assertEquals(560f, page.cropBox.width, 0.001f)
        assertEquals(740f, page.cropBox.height, 0.001f)
    }

    @Test
    fun cropRejectsInvalidOrDegenerateMarginsBeforeChangingPage() {
        val invalidMargins = listOf(
            PageCropMargins(left = -1f, top = 0f, right = 0f, bottom = 0f),
            PageCropMargins(left = Float.NaN, top = 0f, right = 0f, bottom = 0f),
            PageCropMargins(left = 300f, top = 0f, right = 300f, bottom = 0f),
            PageCropMargins(left = 0f, top = 400f, right = 0f, bottom = 400f),
        )

        invalidMargins.forEach { margins ->
            val page = PDPage(PDRectangle(600f, 800f))
            try {
                PageGeometryEditor.crop(page, margins)
                throw AssertionError("expected invalid crop margins to be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("裁剪"))
                assertEquals(600f, page.cropBox.width, 0.001f)
                assertEquals(800f, page.cropBox.height, 0.001f)
            }
        }
    }

    @Test
    fun inspectReportsMediaSizeAndExistingCropMargins() {
        val page = PDPage(PDRectangle(10f, 20f, 600f, 800f)).apply {
            cropBox = PDRectangle(25f, 45f, 550f, 740f)
        }

        val info = PageGeometryEditor.inspect(page)

        assertEquals(600f, info.mediaWidth, 0.001f)
        assertEquals(800f, info.mediaHeight, 0.001f)
        assertEquals(PageCropMargins(left = 15f, top = 35f, right = 35f, bottom = 25f), info.margins)
        assertTrue(info.isCropped)
    }

    @Test
    fun resetCropRestoresMediaBoxInheritanceAndReloads() {
        val document = PDDocument()
        val page = PDPage(PDRectangle(600f, 800f)).apply {
            cropBox = PDRectangle(20f, 30f, 540f, 720f)
        }
        document.addPage(page)

        PageGeometryEditor.resetCrop(page)

        assertTrue(!page.cosObject.containsKey(COSName.CROP_BOX))
        assertEquals(600f, page.cropBox.width, 0.001f)
        assertEquals(800f, page.cropBox.height, 0.001f)
        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        PDDocument.load(saved).use { reopened ->
            assertTrue(!PageGeometryEditor.inspect(reopened.getPage(0)).isCropped)
        }
        document.close()
    }

    @Test
    fun cropAndInspectUseVisualMarginsOnRotatedPage() {
        val page = PDPage(PDRectangle(600f, 800f)).apply { rotation = 90 }
        val visualMargins = PageCropMargins(left = 10f, top = 20f, right = 30f, bottom = 40f)

        PageGeometryEditor.crop(page, visualMargins)

        assertEquals(20f, page.cropBox.lowerLeftX, 0.001f)
        assertEquals(10f, page.cropBox.lowerLeftY, 0.001f)
        assertEquals(540f, page.cropBox.width, 0.001f)
        assertEquals(760f, page.cropBox.height, 0.001f)
        val info = PageGeometryEditor.inspect(page)
        assertEquals(800f, info.mediaWidth, 0.001f)
        assertEquals(600f, info.mediaHeight, 0.001f)
        assertEquals(visualMargins, info.margins)
    }

    @Test
    fun cropAndInspectPreserveVisualMarginsForAllQuarterTurns() {
        listOf(180, 270, -90).forEach { rotation ->
            val page = PDPage(PDRectangle(600f, 800f)).apply { this.rotation = rotation }
            val visualMargins = PageCropMargins(left = 11f, top = 22f, right = 33f, bottom = 44f)

            PageGeometryEditor.crop(page, visualMargins)

            assertEquals("rotation=$rotation", visualMargins, PageGeometryEditor.inspect(page).margins)
        }
    }

    @Test
    fun resizeCentersNewMediaBoxAndResetsDependentPageBoxes() {
        val page = PDPage(PDRectangle(10f, 20f, 600f, 800f)).apply {
            cropBox = PDRectangle(30f, 40f, 500f, 700f)
            bleedBox = PDRectangle(35f, 45f, 490f, 690f)
            trimBox = PDRectangle(40f, 50f, 480f, 680f)
            artBox = PDRectangle(45f, 55f, 470f, 670f)
        }

        PageGeometryEditor.resize(page, visualWidth = 300f, visualHeight = 400f)

        assertEquals(160f, page.mediaBox.lowerLeftX, 0.001f)
        assertEquals(220f, page.mediaBox.lowerLeftY, 0.001f)
        assertEquals(300f, page.mediaBox.width, 0.001f)
        assertEquals(400f, page.mediaBox.height, 0.001f)
        assertEquals(page.mediaBox, page.cropBox)
        assertEquals(page.mediaBox, page.bleedBox)
        assertEquals(page.mediaBox, page.trimBox)
        assertEquals(page.mediaBox, page.artBox)
    }

    @Test
    fun resizeUsesVisualDimensionsForRotatedPage() {
        val page = PDPage(PDRectangle(600f, 800f)).apply { rotation = 90 }

        PageGeometryEditor.resize(page, visualWidth = 500f, visualHeight = 700f)

        assertEquals(700f, page.mediaBox.width, 0.001f)
        assertEquals(500f, page.mediaBox.height, 0.001f)
        val info = PageGeometryEditor.inspect(page)
        assertEquals(500f, info.mediaWidth, 0.001f)
        assertEquals(700f, info.mediaHeight, 0.001f)
    }

    @Test
    fun resizeRejectsInvalidDimensionsWithoutChangingPage() {
        listOf(0f to 400f, 300f to Float.NaN, 30_000f to 400f).forEach { (width, height) ->
            val page = PDPage(PDRectangle(600f, 800f))

            try {
                PageGeometryEditor.resize(page, width, height)
                throw AssertionError("expected invalid page size to be rejected")
            } catch (expected: IllegalArgumentException) {
                assertEquals(600f, page.mediaBox.width, 0.001f)
                assertEquals(800f, page.mediaBox.height, 0.001f)
            }
        }
    }
}
