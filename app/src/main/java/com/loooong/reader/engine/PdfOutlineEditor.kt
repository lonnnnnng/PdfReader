package com.loooong.reader.engine

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.util.Collections
import java.util.IdentityHashMap

data class PdfOutlineEntry(
    val id: String,
    val parentId: String?,
    val title: String,
    val pageIndex: Int?,
    val level: Int,
    val hasChildren: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

enum class PdfOutlineMove {
    UP,
    DOWN,
}

/** long: PDF 目录编辑保留现有节点字典，只调整标题、目标页和树链接，避免丢失颜色、样式及未知动作。 */
object PdfOutlineEditor {
    private const val MAX_DEPTH = 64
    private const val MAX_ENTRIES = 10_000

    fun list(document: PDDocument): List<PdfOutlineEntry> {
        val root = document.documentCatalog.documentOutline ?: return emptyList()
        val result = ArrayList<PdfOutlineEntry>()
        val visited = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        appendChildren(document, root, parentId = null, level = 0, result, visited)
        return result
    }

    fun add(document: PDDocument, title: String, pageIndex: Int, parentId: String?): Boolean {
        val normalizedTitle = title.trim()
        require(normalizedTitle.isNotEmpty()) { "目录标题不能为空" }
        require(pageIndex in 0 until document.numberOfPages) { "目录目标页超出范围" }

        val root = document.documentCatalog.documentOutline ?: PDDocumentOutline().also { outline ->
            document.documentCatalog.documentOutline = outline
        }
        val parent: PDOutlineNode = parentId?.let { id ->
            findItem(root, id) ?: throw IllegalArgumentException("目录项已经不存在")
        } ?: root
        val item = PDOutlineItem().apply {
            setTitle(normalizedTitle)
            setDestination(document.getPage(pageIndex))
        }
        parent.addLast(item)
        parent.openNode()
        root.openNode()
        markUpdated(document, root, item)
        return true
    }

    fun update(document: PDDocument, id: String, title: String, pageIndex: Int): Boolean {
        val normalizedTitle = title.trim()
        require(normalizedTitle.isNotEmpty()) { "目录标题不能为空" }
        require(pageIndex in 0 until document.numberOfPages) { "目录目标页超出范围" }
        val root = document.documentCatalog.documentOutline
            ?: throw IllegalArgumentException("当前 PDF 没有目录")
        val item = findItem(root, id) ?: throw IllegalArgumentException("目录项已经不存在")
        val currentPageIndex = runCatching { item.findDestinationPage(document) }
            .getOrNull()
            ?.let(document.pages::indexOf)
            ?.takeIf { it >= 0 }
        if (item.title?.trim() == normalizedTitle && currentPageIndex == pageIndex) return false

        item.title = normalizedTitle
        // long: 用户明确选择页码后以直接页面目标为准，移除旧动作可避免同一目录项同时存在 /Dest 与 /A 的歧义。
        item.cosObject.removeItem(COSName.A)
        item.setDestination(document.getPage(pageIndex))
        markUpdated(document, root, item)
        return true
    }

    fun move(document: PDDocument, id: String, direction: PdfOutlineMove): Boolean {
        val root = document.documentCatalog.documentOutline
            ?: throw IllegalArgumentException("当前 PDF 没有目录")
        val item = findItem(root, id) ?: throw IllegalArgumentException("目录项已经不存在")
        val parentDictionary = item.cosObject.getDictionaryObject(COSName.PARENT) as? COSDictionary
            ?: throw IllegalArgumentException("目录树结构不完整")
        val parent = outlineNode(parentDictionary)
        val siblings = childrenOf(parent).toMutableList()
        val currentIndex = siblings.indexOfFirst { it.cosObject === item.cosObject }
        if (currentIndex < 0) throw IllegalArgumentException("目录项已经不在原层级")
        val targetIndex = when (direction) {
            PdfOutlineMove.UP -> currentIndex - 1
            PdfOutlineMove.DOWN -> currentIndex + 1
        }
        if (targetIndex !in siblings.indices) return false

        siblings[currentIndex] = siblings[targetIndex]
        siblings[targetIndex] = item
        // long: 目录上移和下移只重写同级双向链，节点自身字典及其整个子树都保持原对象不变。
        rewriteChildren(parent, siblings)
        markUpdated(document, root, item)
        return true
    }

    fun delete(document: PDDocument, id: String): Boolean {
        val root = document.documentCatalog.documentOutline
            ?: throw IllegalArgumentException("当前 PDF 没有目录")
        val item = findItem(root, id) ?: throw IllegalArgumentException("目录项已经不存在")
        val parentDictionary = item.cosObject.getDictionaryObject(COSName.PARENT) as? COSDictionary
            ?: throw IllegalArgumentException("目录树结构不完整")
        val parent = outlineNode(parentDictionary)
        val remaining = childrenOf(parent).filterNot { it.cosObject === item.cosObject }
        rewriteChildren(parent, remaining)
        item.cosObject.removeItem(COSName.PARENT)
        item.cosObject.removeItem(COSName.PREV)
        item.cosObject.removeItem(COSName.NEXT)
        item.cosObject.isNeedToBeUpdated = true

        if (!root.hasChildren()) {
            // long: 删除最后一个目录项后移除 Catalog 的 /Outlines，避免保留一个无效空树影响其他阅读器解析。
            document.documentCatalog.cosObject.removeItem(COSName.OUTLINES)
        } else {
            rebuildCounts(root, forceOpen = true)
            root.cosObject.isNeedToBeUpdated = true
        }
        document.documentCatalog.cosObject.isNeedToBeUpdated = true
        return true
    }

    private fun appendChildren(
        document: PDDocument,
        parent: PDOutlineNode,
        parentId: String?,
        level: Int,
        result: MutableList<PdfOutlineEntry>,
        visited: MutableSet<COSDictionary>,
    ) {
        if (level >= MAX_DEPTH || result.size >= MAX_ENTRIES) return
        val children = childrenOf(parent)
        children.forEachIndexed { index, item ->
            if (result.size >= MAX_ENTRIES || !visited.add(item.cosObject)) return@forEachIndexed
            val id = parentId?.let { "$it/$index" } ?: index.toString()
            val pageIndex = runCatching { item.findDestinationPage(document) }
                .getOrNull()
                ?.let(document.pages::indexOf)
                ?.takeIf { it >= 0 }
            result += PdfOutlineEntry(
                id = id,
                parentId = parentId,
                title = item.title?.trim().orEmpty().ifBlank { "未命名目录" },
                pageIndex = pageIndex,
                level = level,
                hasChildren = item.hasChildren(),
                canMoveUp = index > 0,
                canMoveDown = index < children.lastIndex,
            )
            appendChildren(document, item, id, level + 1, result, visited)
        }
    }

    private fun findItem(root: PDDocumentOutline, id: String): PDOutlineItem? {
        val path = id.split('/').map { segment -> segment.toIntOrNull() ?: return null }
        if (path.isEmpty() || path.any { it < 0 }) return null
        var parent: PDOutlineNode = root
        var found: PDOutlineItem? = null
        path.forEach { index ->
            found = childrenOf(parent).getOrNull(index) ?: return null
            parent = found!!
        }
        return found
    }

    private fun childrenOf(parent: PDOutlineNode): List<PDOutlineItem> {
        val result = ArrayList<PDOutlineItem>()
        val visited = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        var item = parent.firstChild
        while (item != null && result.size < MAX_ENTRIES && visited.add(item.cosObject)) {
            result += item
            item = item.nextSibling
        }
        return result
    }

    private fun outlineNode(dictionary: COSDictionary): PDOutlineNode =
        if (dictionary.getCOSName(COSName.TYPE) == COSName.OUTLINES) PDDocumentOutline(dictionary)
        else PDOutlineItem(dictionary)

    private fun rewriteChildren(parent: PDOutlineNode, children: List<PDOutlineItem>) {
        val parentDictionary = parent.cosObject
        parentDictionary.removeItem(COSName.FIRST)
        parentDictionary.removeItem(COSName.LAST)
        children.forEachIndexed { index, child ->
            val dictionary = child.cosObject
            dictionary.setItem(COSName.PARENT, parentDictionary)
            dictionary.removeItem(COSName.PREV)
            dictionary.removeItem(COSName.NEXT)
            children.getOrNull(index - 1)?.let { previous ->
                dictionary.setItem(COSName.PREV, previous.cosObject)
            }
            children.getOrNull(index + 1)?.let { next ->
                dictionary.setItem(COSName.NEXT, next.cosObject)
            }
            dictionary.isNeedToBeUpdated = true
        }
        children.firstOrNull()?.let { parentDictionary.setItem(COSName.FIRST, it.cosObject) }
        children.lastOrNull()?.let { parentDictionary.setItem(COSName.LAST, it.cosObject) }
        parentDictionary.isNeedToBeUpdated = true
    }

    private fun rebuildCounts(node: PDOutlineNode, forceOpen: Boolean = false): CountSummary {
        val wasOpen = forceOpen || node.isNodeOpen
        val children = childrenOf(node)
        if (children.isEmpty()) {
            node.cosObject.removeItem(COSName.COUNT)
            node.cosObject.isNeedToBeUpdated = true
            return CountSummary(totalDescendants = 0, visibleDescendants = 0, open = false)
        }
        val childCounts = children.map { child -> rebuildCounts(child) }
        val total = childCounts.sumOf { 1 + it.totalDescendants }
        val visible = childCounts.sumOf { 1 + if (it.open) it.visibleDescendants else 0 }
        node.cosObject.setInt(COSName.COUNT, if (wasOpen) visible else -total)
        node.cosObject.isNeedToBeUpdated = true
        return CountSummary(totalDescendants = total, visibleDescendants = visible, open = wasOpen)
    }

    private data class CountSummary(
        val totalDescendants: Int,
        val visibleDescendants: Int,
        val open: Boolean,
    )

    private fun markUpdated(document: PDDocument, root: PDDocumentOutline, item: PDOutlineItem) {
        item.cosObject.isNeedToBeUpdated = true
        root.cosObject.isNeedToBeUpdated = true
        document.documentCatalog.cosObject.isNeedToBeUpdated = true
    }
}
