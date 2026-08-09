package com.loooong.reader.engine

import com.tom_roush.pdfbox.pdmodel.PDDocument

data class PdfDocumentFeatureInfo(
    val digitalSignatureCount: Int,
    val digitalSignatureInspectionFailed: Boolean,
) {
    val requiresSafeCopy: Boolean
        get() = digitalSignatureCount > 0 || digitalSignatureInspectionFailed
}

/** long: 在任何编辑写回前集中识别会被完整保存破坏的文档特征，UI 不直接遍历 PDFBox 底层对象。 */
object PdfDocumentFeatureInspector {

    fun inspect(document: PDDocument): PdfDocumentFeatureInfo {
        val signatureCount = runCatching { document.signatureDictionaries.size }
        return PdfDocumentFeatureInfo(
            digitalSignatureCount = signatureCount.getOrDefault(0),
            // long: 签名树损坏或 PDFBox 无法枚举时必须失败关闭，未知状态不能被当作“无签名”开放覆盖保存。
            digitalSignatureInspectionFailed = signatureCount.isFailure,
        )
    }
}
