package com.loooong.reader.engine

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfDocumentFeatureInspectorTest {

    @Test
    fun reportsDigitalSignatureDictionariesBeforeEditingStarts() {
        val document = PDDocument().apply { addPage(PDPage()) }
        PdfDocumentFeatureInspector.inspect(document).also { features ->
            assertEquals(0, features.digitalSignatureCount)
            assertFalse(features.digitalSignatureInspectionFailed)
            assertFalse(features.requiresSafeCopy)
        }

        document.addSignature(
            PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                setName("测试签署人")
            },
        )

        PdfDocumentFeatureInspector.inspect(document).also { features ->
            assertEquals(1, features.digitalSignatureCount)
            assertFalse(features.digitalSignatureInspectionFailed)
            assertTrue(features.requiresSafeCopy)
        }
        document.close()
    }

    @Test
    fun malformedSignatureTreeFailsClosedBeforeOverwriteIsAllowed() {
        val document = PDDocument().apply { addPage(PDPage()) }
        val form = PDAcroForm(document)
        val brokenField = PDSignatureField(form).apply {
            partialName = "broken-signature"
            // long: 损坏文件可能把签名值写成非字典对象；PDFBox 枚举时会抛错，安全策略必须按未知签名处理。
            cosObject.setItem(COSName.V, COSString("not-a-signature-dictionary"))
        }
        form.fields = listOf(brokenField)
        document.documentCatalog.acroForm = form

        PdfDocumentFeatureInspector.inspect(document).also { features ->
            assertEquals(0, features.digitalSignatureCount)
            assertTrue(features.digitalSignatureInspectionFailed)
            assertTrue(features.requiresSafeCopy)
        }
        document.close()
    }
}
