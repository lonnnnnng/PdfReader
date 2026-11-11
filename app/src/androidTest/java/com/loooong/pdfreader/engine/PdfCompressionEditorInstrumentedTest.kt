package com.loooong.pdfreader.engine

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class PdfCompressionEditorInstrumentedTest {

    @Test
    fun losslessRasterIsDownscaledAndReplacedWithSmallerJpeg() {
        PDFBoxResourceLoader.init(InstrumentationRegistry.getInstrumentation().targetContext)
        val document = PDDocument()
        val page = PDPage(PDRectangle(300f, 300f)).also(document::addPage)
        val bitmap = noisyBitmap(800, 800)
        val image = LosslessFactory.createFromImage(document, bitmap)
        bitmap.recycle()
        PDPageContentStream(document, page).use { content ->
            content.drawImage(image, 20f, 20f, 260f, 260f)
        }
        val before = ByteArrayOutputStream().also(document::save).size()

        val report = PdfCompressionEditor.compress(
            document,
            PdfCompressionOptions(jpegQuality = 64, maxImageDimension = 640, minimumSavingsPercent = 5),
        )

        assertTrue(report.changed)
        assertEquals(1, report.scannedImages)
        assertEquals(1, report.compressedImages)
        assertTrue(report.estimatedSavedBytes > 0L)
        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        assertTrue(saved.size < before)
        document.close()

        PDDocument.load(saved).use { reopened ->
            val xObjects = reopened.getPage(0).resources.xObjectNames.map {
                reopened.getPage(0).resources.getXObject(it)
            }
            assertEquals(1, xObjects.size)
            assertTrue(xObjects.single().cosObject.filters.toString().contains("DCTDecode"))
        }
    }

    @Test
    fun imageWithSoftMaskIsSkippedInsteadOfFlatteningTransparency() {
        PDFBoxResourceLoader.init(InstrumentationRegistry.getInstrumentation().targetContext)
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(200f, 200f)).also(document::addPage)
            val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.argb(120, 30, 120, 220))
            }
            val image = LosslessFactory.createFromImage(document, bitmap)
            bitmap.recycle()
            PDPageContentStream(document, page).use { content ->
                content.drawImage(image, 20f, 20f, 160f, 160f)
            }

            val report = PdfCompressionEditor.compress(document, PdfCompressionPreset.STRONG.options)

            assertEquals(1, report.scannedImages)
            assertEquals(0, report.compressedImages)
            assertEquals(1, report.skippedProtectedImages)
        }
    }

    private fun noisyBitmap(width: Int, height: Int): Bitmap {
        var seed = 0x13579BDF
        val pixels = IntArray(width * height) {
            seed = seed xor (seed shl 13)
            seed = seed xor (seed ushr 17)
            seed = seed xor (seed shl 5)
            Color.rgb(seed and 0xFF, (seed ushr 8) and 0xFF, (seed ushr 16) and 0xFF)
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(false)
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}
