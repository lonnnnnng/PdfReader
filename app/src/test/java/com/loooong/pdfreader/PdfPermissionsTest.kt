package com.loooong.pdfreader

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class PdfPermissionsTest {
    @Test
    fun userPasswordRetainsIndependentPermissionsAfterSerialization() {
        val permission = AccessPermission().apply {
            setCanModify(false)
            setCanModifyAnnotations(true)
            setCanFillInForm(true)
            setCanAssembleDocument(false)
            setCanExtractContent(false)
            setCanPrint(false)
        }
        val bytes = ByteArrayOutputStream()
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.protect(StandardProtectionPolicy("owner", "reader", permission).apply { encryptionKeyLength = 128 })
            doc.save(bytes)
        }
        PDDocument.load(bytes.toByteArray(), "reader").use { doc ->
            val caps = PdfPermissions.from(doc.currentAccessPermission)
            assertTrue(caps.canEdit)
            assertTrue(caps.annotate)
            assertTrue(caps.fillForm)
            assertFalse(caps.content)
            assertFalse(caps.assemble)
            assertFalse(caps.designForm)
            assertFalse(caps.extract)
            assertFalse(caps.print)
        }
        PDDocument.load(bytes.toByteArray(), "owner").use { doc ->
            val caps = PdfPermissions.from(doc.currentAccessPermission)
            assertTrue(PdfOperation.entries.all(caps::allows))
        }
    }
}
