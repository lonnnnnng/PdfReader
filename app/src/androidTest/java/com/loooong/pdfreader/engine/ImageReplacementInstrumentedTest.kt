package com.loooong.pdfreader.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class ImageReplacementInstrumentedTest {

    @Test
    fun replacingInlineImageCreatesPersistentXObject() {
        PDFBoxResourceLoader.init(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)
        val document = PDDocument()
        val page = PDPage(PDRectangle(300f, 300f)).also {
            it.resources = PDResources()
            document.addPage(it)
        }
        val stream = PDStream(document)
        stream.createOutputStream().use { output ->
            output.write(
                "q 40 0 0 40 20 30 cm BI /W 1 /H 1 /CS /G /BPC 8 ID \u007f EI Q\n"
                    .toByteArray(Charsets.ISO_8859_1),
            )
        }
        page.setContents(stream)
        val inline = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.IMAGE }
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )

        val result = ElementEditor.editElement(
            document,
            page,
            inline,
            EditRequest(replacementImageBytes = png),
        )

        assertTrue(result is EditResult.Applied)
        var replaced = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.IMAGE }
        assertFalse(replaced.inlineImage)
        assertTrue(page.resources.xObjectNames.any())

        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        document.close()
        PDDocument.load(saved).use { reopened ->
            replaced = ContentStreamEngine.parse(reopened.getPage(0)).leaves
                .single { it.kind == NodeKind.IMAGE }
            assertFalse(replaced.inlineImage)
        }
    }
}
