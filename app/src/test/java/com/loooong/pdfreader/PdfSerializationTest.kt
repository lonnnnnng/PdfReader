package com.loooong.pdfreader

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class PdfSerializationTest {
    @Test fun loadedEncryptionSurvivesRepeatedSaveAndBothPasswords() {
        listOf(40, 128, 256).forEach { bits ->
            val original = ByteArrayOutputStream()
            PDDocument().use { doc ->
                doc.addPage(PDPage())
                text(doc, "secret marker")
                val permission = AccessPermission().apply {
                    setCanModify(false); setCanModifyAnnotations(true); setCanExtractContent(false)
                }
                doc.protect(StandardProtectionPolicy("owner", "reader", permission).apply {
                    encryptionKeyLength = bits
                    isPreferAES = bits >= 128
                })
                doc.save(original)
            }
            PDDocument.load(original.toByteArray(), "reader").use { doc ->
                val originalId = doc.document.documentID.getString(0)
                val ownerKey = doc.encryption.ownerKey.clone()
                val shared = COSObject(COSString("shared encrypted metadata"))
                doc.documentInformation.cosObject.setItem(COSName.AUTHOR, shared)
                doc.documentInformation.cosObject.setItem(COSName.SUBJECT, shared)
                repeat(3) { index ->
                    doc.documentInformation.title = "标题 $index"
                    val output = ByteArrayOutputStream()
                    PdfSerialization.write(doc, output)
                    assertTrue(PDFTextStripper().getText(doc).contains("secret marker"))
                    listOf("reader", "owner").forEach { password ->
                        PDDocument.load(output.toByteArray(), password).use { loaded ->
                            assertTrue(loaded.isEncrypted)
                            assertEquals("标题 $index", loaded.documentInformation.title)
                            assertEquals("shared encrypted metadata", loaded.documentInformation.author)
                            assertEquals("shared encrypted metadata", loaded.documentInformation.subject)
                            assertEquals(originalId, loaded.document.documentID.getString(0))
                            assertArrayEquals(ownerKey, loaded.encryption.ownerKey)
                            assertTrue(PDFTextStripper().getText(loaded).contains("secret marker"))
                            if (password == "reader") {
                                assertFalse(loaded.currentAccessPermission.canModify())
                                assertFalse(loaded.currentAccessPermission.canExtractContent())
                                assertTrue(loaded.currentAccessPermission.canModifyAnnotations())
                            }
                        }
                    }
                }
                // long: 完整副本不携带增量历史，删除的旧内容不能继续出现在输出字节中。
                doc.getPage(0).cosObject.removeItem(COSName.CONTENTS)
                val redacted = ByteArrayOutputStream()
                PdfSerialization.write(doc, redacted)
                PDDocument.load(redacted.toByteArray(), "reader").use {
                    assertTrue(PDFTextStripper().getText(it).isBlank())
                }
                assertFalse(redacted.toString("ISO-8859-1").contains("/Prev"))
            }
        }
    }

    private fun text(doc: PDDocument, value: String) {
        PDPageContentStream(doc, doc.getPage(0)).use {
            it.beginText(); it.setFont(PDType1Font.HELVETICA, 12f)
            it.newLineAtOffset(20f, 700f); it.showText(value); it.endText()
        }
    }
}
