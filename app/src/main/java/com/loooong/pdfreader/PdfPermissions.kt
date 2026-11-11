package com.loooong.pdfreader

import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission

enum class PdfOperation { CONTENT, ANNOTATE, FILL_FORM, DESIGN_FORM, ASSEMBLE, EXTRACT, PRINT, SECURITY }

/** long: PDF 的填表、批注和组页权限彼此独立，不能用 canModify 一项代替全部编辑授权。 */
data class PdfPermissions(
    val content: Boolean = false,
    val annotate: Boolean = false,
    val fillForm: Boolean = false,
    val assemble: Boolean = false,
    val extract: Boolean = false,
    val print: Boolean = false,
    val owner: Boolean = false,
) {
    val canEdit: Boolean get() = content || annotate || fillForm || assemble
    val designForm: Boolean get() = content && annotate

    fun allows(operation: PdfOperation): Boolean = when (operation) {
        PdfOperation.CONTENT -> content
        PdfOperation.ANNOTATE -> annotate
        PdfOperation.FILL_FORM -> fillForm
        PdfOperation.DESIGN_FORM -> designForm
        PdfOperation.ASSEMBLE -> assemble
        PdfOperation.EXTRACT -> extract
        PdfOperation.PRINT -> print
        PdfOperation.SECURITY -> owner
    }

    companion object {
        fun from(permission: AccessPermission?): PdfPermissions {
            if (permission == null) return PdfPermissions()
            val owner = permission.isOwnerPermission
            return PdfPermissions(
                owner || permission.canModify(), owner || permission.canModifyAnnotations(),
                owner || permission.canFillInForm(), owner || permission.canAssembleDocument(),
                owner || permission.canExtractContent(), owner || permission.canPrint(), owner,
            )
        }
    }
}
