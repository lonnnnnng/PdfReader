package com.loooong.pdfreader.engine

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

class PdfSecurityEditorTest {

    @Test
    fun protectedDocumentRequiresPasswordAndAppliesUserPermissions() {
        val saved = PDDocument().use { document ->
            document.addPage(PDPage())
            PdfSecurityEditor.protect(
                document,
                PdfSecuritySettings(
                    userPassword = "reader-password",
                    ownerPassword = "owner-password",
                    permissions = PdfSecurityPermissions(
                        allowPrinting = true,
                        allowCopying = false,
                        allowModifying = false,
                        allowAnnotations = true,
                        allowFormFilling = true,
                        allowAccessibility = true,
                        allowPageAssembly = false,
                        allowHighQualityPrinting = false,
                    ),
                    encryptionKeyLength = 256,
                ),
            )
            ByteArrayOutputStream().also(document::save).toByteArray()
        }

        assertPasswordRejected(saved, "")
        assertPasswordRejected(saved, "wrong-password")

        PDDocument.load(saved, "reader-password").use { reopened ->
            val info = PdfSecurityEditor.inspect(reopened)
            assertTrue(info.isEncrypted)
            assertEquals(256, info.encryptionKeyLength)
            assertEquals(
                PdfSecurityPermissions(
                    allowPrinting = true,
                    allowCopying = false,
                    allowModifying = false,
                    allowAnnotations = true,
                    allowFormFilling = true,
                    allowAccessibility = true,
                    allowPageAssembly = false,
                    allowHighQualityPrinting = false,
                ),
                info.permissions,
            )
            assertFalse(reopened.currentAccessPermission.isOwnerPermission)
            assertFalse(reopened.currentAccessPermission.canModify())
        }

        PDDocument.load(saved, "owner-password").use { reopened ->
            assertTrue(reopened.currentAccessPermission.isOwnerPermission)
            assertTrue(reopened.currentAccessPermission.canModify())
            // long: 所有者会话拥有完整能力，但设置页仍应展示安全字典里真实的用户权限，而不是会话提升后的权限。
            assertFalse(PdfSecurityEditor.inspect(reopened).permissions.allowModifying)
            assertFalse(PdfSecurityEditor.inspect(reopened).permissions.allowCopying)
        }
    }

    @Test
    fun ownerCanRemoveProtectionAndReopenWithoutPassword() {
        val protectedBytes = PDDocument().use { document ->
            document.addPage(PDPage())
            PdfSecurityEditor.protect(
                document,
                PdfSecuritySettings(
                    userPassword = "reader-password",
                    ownerPassword = "owner-password",
                ),
            )
            ByteArrayOutputStream().also(document::save).toByteArray()
        }

        val unlockedBytes = PDDocument.load(protectedBytes, "owner-password").use { document ->
            assertTrue(PdfSecurityEditor.removeProtection(document))
            ByteArrayOutputStream().also(document::save).toByteArray()
        }

        PDDocument.load(unlockedBytes).use { reopened ->
            assertFalse(reopened.isEncrypted)
            assertFalse(PdfSecurityEditor.inspect(reopened).isEncrypted)
            assertEquals(1, reopened.numberOfPages)
        }
    }

    @Test
    fun protectionRejectsAmbiguousOrUnsupportedPasswordSettings() {
        PDDocument().use { document ->
            document.addPage(PDPage())
            assertRejected("打开密码不能与所有者密码相同") {
                PdfSecurityEditor.protect(
                    document,
                    PdfSecuritySettings(
                        userPassword = "same-password",
                        ownerPassword = "same-password",
                    ),
                )
            }
            assertRejected("仅支持 AES-128 或 AES-256") {
                PdfSecurityEditor.protect(
                    document,
                    PdfSecuritySettings(
                        userPassword = "",
                        ownerPassword = "owner-password",
                        encryptionKeyLength = 40,
                    ),
                )
            }
            assertFalse(document.isEncrypted)
        }
    }

    private fun assertPasswordRejected(bytes: ByteArray, password: String) {
        try {
            PDDocument.load(bytes, password).close()
            fail("错误密码不应打开受保护 PDF")
        } catch (_: InvalidPasswordException) {
            // long: 只接受明确的密码异常，其他解析错误不能被误判为安全策略生效。
        }
    }

    private fun assertRejected(expectedMessage: String, action: () -> Unit) {
        try {
            action()
            fail("无效安全设置应被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertEquals(expectedMessage, expected.message)
        }
    }
}
