package com.loooong.reader.engine

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy

data class PdfSecurityPermissions(
    val allowPrinting: Boolean = true,
    val allowCopying: Boolean = true,
    val allowModifying: Boolean = true,
    val allowAnnotations: Boolean = true,
    val allowFormFilling: Boolean = true,
    val allowAccessibility: Boolean = true,
    val allowPageAssembly: Boolean = true,
    val allowHighQualityPrinting: Boolean = true,
)

data class PdfSecuritySettings(
    val userPassword: String,
    val ownerPassword: String,
    val permissions: PdfSecurityPermissions = PdfSecurityPermissions(),
    val encryptionKeyLength: Int = 256,
)

data class PdfSecurityInfo(
    val isEncrypted: Boolean,
    val permissions: PdfSecurityPermissions,
    val encryptionKeyLength: Int?,
)

/** long: PDF 密码策略集中在引擎边界内，界面只提交用户意图，避免直接操作容易混淆的权限位。 */
object PdfSecurityEditor {

    fun inspect(document: PDDocument): PdfSecurityInfo {
        if (!document.isEncrypted) {
            return PdfSecurityInfo(
                isEncrypted = false,
                permissions = PdfSecurityPermissions(),
                encryptionKeyLength = null,
            )
        }
        val encryption = requireNotNull(document.encryption) { "加密 PDF 缺少安全字典" }
        // long: 所有者密码打开时 currentAccessPermission 会显示完整权限；必须读取安全字典原始权限位才能展示真实用户权限。
        val access = AccessPermission(encryption.permissions)
        return PdfSecurityInfo(
            isEncrypted = true,
            permissions = access.toSecurityPermissions(),
            encryptionKeyLength = encryption.length.takeIf { it > 0 },
        )
    }

    fun protect(document: PDDocument, settings: PdfSecuritySettings) {
        require(settings.ownerPassword.isNotEmpty()) { "所有者密码不能为空" }
        require(settings.userPassword.isEmpty() || settings.userPassword != settings.ownerPassword) {
            "打开密码不能与所有者密码相同"
        }
        require(settings.encryptionKeyLength == 128 || settings.encryptionKeyLength == 256) {
            "仅支持 AES-128 或 AES-256"
        }
        require(settings.permissions.allowPrinting || !settings.permissions.allowHighQualityPrinting) {
            "允许高质量打印前需要先允许打印"
        }

        val access = settings.permissions.toAccessPermission()
        val policy = StandardProtectionPolicy(
            settings.ownerPassword,
            settings.userPassword,
            access,
        ).apply {
            setEncryptionKeyLength(settings.encryptionKeyLength)
            setPreferAES(true)
        }
        document.setAllSecurityToBeRemoved(false)
        document.protect(policy)
    }

    fun removeProtection(document: PDDocument): Boolean {
        if (!document.isEncrypted) return false
        // long: PDFBox 在下一次完整保存时移除安全字典；调用方必须随后用空密码重新加载新的工作文件。
        document.setAllSecurityToBeRemoved(true)
        return true
    }

    private fun PdfSecurityPermissions.toAccessPermission(): AccessPermission = AccessPermission().apply {
        setCanPrint(allowPrinting)
        setCanExtractContent(allowCopying)
        setCanModify(allowModifying)
        setCanModifyAnnotations(allowAnnotations)
        setCanFillInForm(allowFormFilling)
        setCanExtractForAccessibility(allowAccessibility)
        setCanAssembleDocument(allowPageAssembly)
        setCanPrintFaithful(allowHighQualityPrinting)
    }

    private fun AccessPermission.toSecurityPermissions(): PdfSecurityPermissions = PdfSecurityPermissions(
        allowPrinting = canPrint(),
        allowCopying = canExtractContent(),
        allowModifying = canModify(),
        allowAnnotations = canModifyAnnotations(),
        allowFormFilling = canFillInForm(),
        allowAccessibility = canExtractForAccessibility(),
        allowPageAssembly = canAssembleDocument(),
        allowHighQualityPrinting = canPrintFaithful(),
    )
}
