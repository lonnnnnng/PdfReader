package com.loooong.reader.engine

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata

data class PdfMergeReport(
    val sourceCount: Int,
    val pageCount: Int,
)

/** long: 合并只复制各源文档的页面和页面资源，所有源文档保持不变；文档级目录/附件等对象不跨源拼接，避免引用串到错误文档。 */
object PdfMergeEditor {

    fun merge(documents: List<PDDocument>): Pair<PDDocument, PdfMergeReport> {
        require(documents.size >= 2) { "至少选择两个 PDF 才能合并" }
        require(documents.all { it.numberOfPages > 0 }) { "不能合并没有页面的 PDF" }

        val merged = PDDocument()
        return try {
            documents.forEach { source ->
                // long: PageTreeEditor 使用深克隆重绑定字体、图片和内容流，源文档关闭后合并结果仍可独立保存。
                PageTreeEditor.importAll(merged, source, merged.numberOfPages - 1)
            }
            copyFirstDocumentMetadata(documents.first(), merged)
            merged to PdfMergeReport(
                sourceCount = documents.size,
                pageCount = merged.numberOfPages,
            )
        } catch (error: Throwable) {
            runCatching { merged.close() }
            throw error
        }
    }

    private fun copyFirstDocumentMetadata(source: PDDocument, destination: PDDocument) {
        val clonedInfo = PDFCloneUtility(destination)
            .cloneForNewDocument(source.documentInformation.cosObject) as? COSDictionary
        if (clonedInfo != null) destination.documentInformation = PDDocumentInformation(clonedInfo)

        source.documentCatalog.metadata?.let { metadata ->
            // long: XMP 只采用第一个源文档作为合并文件的主元数据，避免多个文档标识互相覆盖。
            metadata.exportXMPMetadata().use { input ->
                destination.documentCatalog.metadata = PDMetadata(destination, input)
            }
        }
    }
}
