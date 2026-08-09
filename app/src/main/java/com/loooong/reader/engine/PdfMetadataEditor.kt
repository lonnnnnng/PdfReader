package com.loooong.reader.engine

import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.util.Calendar

data class PdfMetadata(
    val title: String = "",
    val author: String = "",
    val subject: String = "",
    val keywords: String = "",
    val creator: String = "",
)

/** long: 文档信息字典只暴露用户可维护字段，生成器、创建时间和自定义属性始终原样保留。 */
object PdfMetadataEditor {

    fun read(document: PDDocument): PdfMetadata = document.documentInformation.let { information ->
        PdfMetadata(
            title = information.title.normalized(),
            author = information.author.normalized(),
            subject = information.subject.normalized(),
            keywords = information.keywords.normalized(),
            creator = information.creator.normalized(),
        )
    }

    fun update(document: PDDocument, metadata: PdfMetadata): Boolean {
        val normalized = metadata.normalized()
        if (read(document) == normalized) return false

        val information = document.documentInformation
        // long: 空内容写为 null 可移除旧字段，但不替换整个 Info 字典，避免生产者、创建时间及业务自定义键丢失。
        information.title = normalized.title.nullIfBlank()
        information.author = normalized.author.nullIfBlank()
        information.subject = normalized.subject.nullIfBlank()
        information.keywords = normalized.keywords.nullIfBlank()
        information.creator = normalized.creator.nullIfBlank()
        information.modificationDate = Calendar.getInstance()
        information.cosObject.isNeedToBeUpdated = true
        return true
    }

    private fun PdfMetadata.normalized(): PdfMetadata = copy(
        title = title.trim(),
        author = author.trim(),
        subject = subject.trim(),
        keywords = keywords.trim(),
        creator = creator.trim(),
    )

    private fun String?.normalized(): String = this?.trim().orEmpty()

    private fun String.nullIfBlank(): String? = takeIf(String::isNotBlank)
}
