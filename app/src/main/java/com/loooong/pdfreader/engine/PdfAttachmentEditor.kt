package com.loooong.pdfreader.engine

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import java.io.InputStream
import java.io.OutputStream
import java.util.Calendar
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

data class PdfAttachmentInfo(
    val key: String,
    val fileName: String,
    val description: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
    val createdAt: Long?,
    val modifiedAt: Long?,
    val readable: Boolean,
)

/** long: 文档级附件统一通过 EmbeddedFiles 名称树维护，避免 UI 直接改 Catalog 后遗漏 Unicode 文件名或流参数。 */
object PdfAttachmentEditor {

    fun list(document: PDDocument): List<PdfAttachmentInfo> {
        val tree = document.documentCatalog.names?.embeddedFiles ?: return emptyList()
        return collectEntries(tree).map { (key, specification) ->
            val embedded = specification.preferredEmbeddedFile()
            PdfAttachmentInfo(
                key = key,
                fileName = specification.preferredFileName().ifBlank { key },
                description = specification.fileDescription?.trim()?.takeIf(String::isNotEmpty),
                mimeType = runCatching { embedded?.subtype }.getOrNull()?.takeIf(String::isNotBlank),
                sizeBytes = embedded?.let { file ->
                    runCatching { file.size.toLong() }.getOrNull()?.takeIf { it >= 0L }
                        ?: runCatching { file.length.toLong() }.getOrNull()?.takeIf { it >= 0L }
                },
                createdAt = embedded?.let { runCatching { it.creationDate?.timeInMillis }.getOrNull() },
                modifiedAt = embedded?.let { runCatching { it.modDate?.timeInMillis }.getOrNull() },
                readable = embedded != null,
            )
        }
    }

    fun add(
        document: PDDocument,
        fileName: String,
        description: String?,
        mimeType: String?,
        source: InputStream,
        sourceSize: Long? = null,
    ): String {
        val normalizedName = normalizeFileName(fileName)
        val normalizedMime = normalizeMimeType(mimeType)
        val names = ensureNames(document)
        val tree = names.embeddedFiles ?: PDEmbeddedFilesNameTreeNode().also(names::setEmbeddedFiles)
        val entries = collectEntries(tree).toMutableMap()
        val key = uniqueKey(entries.keys, normalizedName)
        val now = Calendar.getInstance()
        val embedded = PDEmbeddedFile(document, source).apply {
            normalizedMime?.let(::setSubtype)
            sourceSize?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.let { setSize(it.toInt()) }
            creationDate = now
            modDate = now
        }
        val specification = PDComplexFileSpecification().apply {
            file = normalizedName
            fileUnicode = normalizedName
            fileDescription = description?.trim()?.takeIf(String::isNotEmpty)
            embeddedFile = embedded
            embeddedFileUnicode = embedded
        }
        entries[key] = specification
        rewriteTree(document, names, tree, entries)
        markUpdated(document, names, tree, specification, embedded)
        return key
    }

    fun rename(
        document: PDDocument,
        key: String,
        fileName: String,
        description: String?,
    ): String? {
        val names = document.documentCatalog.names ?: return null
        val tree = names.embeddedFiles ?: return null
        val entries = collectEntries(tree).toMutableMap()
        val specification = entries.remove(key) ?: return null
        val normalizedName = normalizeFileName(fileName)
        val nextKey = uniqueKey(entries.keys, normalizedName)
        specification.file = normalizedName
        specification.fileUnicode = normalizedName
        specification.fileDescription = description?.trim()?.takeIf(String::isNotEmpty)
        specification.preferredEmbeddedFile()?.apply {
            modDate = Calendar.getInstance()
            cosObject.isNeedToBeUpdated = true
        }
        specification.cosObject.isNeedToBeUpdated = true
        entries[nextKey] = specification
        rewriteTree(document, names, tree, entries)
        markUpdated(document, names, tree, specification, specification.preferredEmbeddedFile())
        return nextKey
    }

    fun delete(document: PDDocument, key: String): Boolean {
        val names = document.documentCatalog.names ?: return false
        val tree = names.embeddedFiles ?: return false
        val entries = collectEntries(tree).toMutableMap()
        if (entries.remove(key) == null) return false
        if (entries.isEmpty()) {
            // long: 删除最后一个附件时只移除 EmbeddedFiles，保留同一 Names 字典中的命名目标和脚本名称树。
            names.cosObject.removeItem(EMBEDDED_FILES_KEY)
            names.cosObject.isNeedToBeUpdated = true
            document.documentCatalog.cosObject.isNeedToBeUpdated = true
        } else {
            rewriteTree(document, names, tree, entries)
            markUpdated(document, names, tree, null, null)
        }
        return true
    }

    fun writeTo(document: PDDocument, key: String, output: OutputStream): Long {
        val tree = document.documentCatalog.names?.embeddedFiles
            ?: throw IllegalArgumentException("当前 PDF 没有附件")
        val specification = collectEntries(tree)[key]
            ?: throw IllegalArgumentException("附件已经不存在")
        val embedded = specification.preferredEmbeddedFile()
            ?: throw IllegalArgumentException("附件内容损坏或缺失")
        var written = 0L
        embedded.createInputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                written += count
            }
        }
        return written
    }

    private fun ensureNames(document: PDDocument): PDDocumentNameDictionary {
        val catalog = document.documentCatalog
        return catalog.names ?: PDDocumentNameDictionary(catalog).also(catalog::setNames)
    }

    private fun collectEntries(
        root: PDEmbeddedFilesNameTreeNode,
    ): LinkedHashMap<String, PDComplexFileSpecification> {
        val result = LinkedHashMap<String, PDComplexFileSpecification>()
        val visited = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())

        fun visit(node: PDEmbeddedFilesNameTreeNode) {
            if (!visited.add(node.cosObject)) return
            runCatching { node.names }.getOrNull().orEmpty().forEach { (key, value) ->
                result.putIfAbsent(key, value)
            }
            node.kids.orEmpty().forEach { child ->
                val embeddedChild = child as? PDEmbeddedFilesNameTreeNode
                    ?: PDEmbeddedFilesNameTreeNode(child.cosObject)
                visit(embeddedChild)
            }
        }

        visit(root)
        return result
    }

    private fun rewriteTree(
        document: PDDocument,
        names: PDDocumentNameDictionary,
        tree: PDEmbeddedFilesNameTreeNode,
        entries: Map<String, PDComplexFileSpecification>,
    ) {
        // long: 写回前将多层名称树拍平成有序根节点，避免重命名后旧 Limits 与 Kids 继续指向已经删除的键。
        tree.cosObject.removeItem(COSName.KIDS)
        tree.cosObject.removeItem(COSName.LIMITS)
        tree.names = entries.toSortedMap()
        names.embeddedFiles = tree
        document.documentCatalog.names = names
    }

    private fun markUpdated(
        document: PDDocument,
        names: PDDocumentNameDictionary,
        tree: PDEmbeddedFilesNameTreeNode,
        specification: PDComplexFileSpecification?,
        embedded: PDEmbeddedFile?,
    ) {
        embedded?.cosObject?.isNeedToBeUpdated = true
        specification?.cosObject?.isNeedToBeUpdated = true
        tree.cosObject.isNeedToBeUpdated = true
        names.cosObject.isNeedToBeUpdated = true
        document.documentCatalog.cosObject.isNeedToBeUpdated = true
    }

    private fun PDComplexFileSpecification.preferredFileName(): String =
        fileUnicode?.trim().orEmpty().ifBlank { filename?.trim().orEmpty() }

    private fun PDComplexFileSpecification.preferredEmbeddedFile(): PDEmbeddedFile? =
        embeddedFileUnicode ?: embeddedFile ?: embeddedFileUnix ?: embeddedFileMac ?: embeddedFileDos

    private fun normalizeFileName(raw: String): String {
        val normalized = raw.trim().substringAfterLast('/').substringAfterLast('\\')
        require(normalized.isNotEmpty() && normalized != "." && normalized != "..") { "附件名称不能为空" }
        require(normalized.length <= MAX_FILE_NAME_LENGTH) { "附件名称不能超过 $MAX_FILE_NAME_LENGTH 个字符" }
        require(normalized.none { it.code < 0x20 || it.code == 0x7F }) { "附件名称包含不允许的控制字符" }
        return normalized
    }

    private fun normalizeMimeType(raw: String?): String? {
        val normalized = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty) ?: return null
        return normalized.takeIf(MIME_TYPE_REGEX::matches)
    }

    private fun uniqueKey(existing: Set<String>, fileName: String): String {
        if (fileName !in existing) return fileName
        val dot = fileName.lastIndexOf('.').takeIf { it > 0 } ?: fileName.length
        val base = fileName.substring(0, dot)
        val extension = fileName.substring(dot)
        var suffix = 2
        while (true) {
            val candidate = "$base ($suffix)$extension"
            if (candidate !in existing) return candidate
            suffix++
        }
    }

    private val EMBEDDED_FILES_KEY = COSName.getPDFName("EmbeddedFiles")
    private val MIME_TYPE_REGEX = Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
    private const val MAX_FILE_NAME_LENGTH = 255
}
