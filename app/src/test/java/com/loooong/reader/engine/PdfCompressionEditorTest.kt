package com.loooong.reader.engine

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PdfCompressionEditorTest {

    @Test
    fun documentWithoutRasterImagesRemainsUnchanged() {
        PDDocument().use { document ->
            document.addPage(PDPage())

            val report = PdfCompressionEditor.compress(
                document,
                PdfCompressionPreset.BALANCED.options,
            )

            assertFalse(report.changed)
            assertEquals(0, report.scannedImages)
            assertEquals(0, report.compressedImages)
            assertEquals(0L, report.estimatedSavedBytes)
            assertEquals(1, document.numberOfPages)
        }
    }
}
