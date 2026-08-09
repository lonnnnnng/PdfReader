package com.loooong.reader.engine

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabelRange
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabels
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.util.Collections
import java.util.IdentityHashMap

/** long: 页面树操作集中在此处，调用方负责文档级快照和缓存刷新。 */
object PageTreeEditor {

    fun delete(document: PDDocument, index: Int): Int {
        require(document.numberOfPages > 1) { "PDF 至少需要保留一页" }
        require(index in 0 until document.numberOfPages) { "页面索引越界" }
        document.removePage(index)
        return index.coerceAtMost(document.numberOfPages - 1)
    }

    fun deleteSelected(document: PDDocument, indices: Set<Int>, currentIndex: Int): Int {
        require(indices.isNotEmpty()) { "请至少选择一页" }
        require(currentIndex in 0 until document.numberOfPages) { "当前页面索引越界" }
        require(indices.all { it in 0 until document.numberOfPages }) { "页面索引越界" }
        require(indices.size < document.numberOfPages) { "PDF 至少需要保留一页" }

        val removedBeforeCurrent = indices.count { it < currentIndex }
        val remainingPageCount = document.numberOfPages - indices.size
        val targetIndex = (currentIndex - removedBeforeCurrent).coerceIn(0, remainingPageCount - 1)
        // long: 从末页向前删除可避免前一次移除改变后续页码，同时让一次文档快照完整覆盖整批操作。
        indices.sortedDescending().forEach(document::removePage)
        return targetIndex
    }

    fun rotateSelected(document: PDDocument, indices: Set<Int>, degrees: Int) {
        require(indices.isNotEmpty()) { "请至少选择一页" }
        require(indices.all { it in 0 until document.numberOfPages }) { "页面索引越界" }
        require(degrees != 0 && degrees % 90 == 0) { "页面旋转角度必须是非零的 90 度倍数" }
        // long: 批量旋转只更新各页的 /Rotate 属性，不重写内容流，避免文字、图片和批注坐标发生漂移。
        indices.sorted().forEach { index ->
            ElementEditor.rotatePage(document, document.getPage(index), degrees)
        }
    }

    fun importAll(document: PDDocument, source: PDDocument, afterIndex: Int): Int {
        require(source !== document) { "源 PDF 不能与目标文档使用同一实例" }
        require(source.numberOfPages > 0) { "源 PDF 没有可导入的页面" }
        require(afterIndex in -1 until document.numberOfPages) { "页面索引越界" }

        val importedPages = mutableListOf<PDPage>()
        val cloner = PDFCloneUtility(document)
        return try {
            repeat(source.numberOfPages) { index ->
                importedPages += importPageWithResources(document, source.getPage(index), cloner)
            }
            importedPages.forEach(document.pages::remove)

            var anchor = afterIndex.takeIf { it >= 0 }?.let(document::getPage)
            // long: 每次以上一张导入页为锚点插入，源 PDF 的阅读顺序不会因目标页树重排而倒置。
            importedPages.forEach { page ->
                if (anchor == null) {
                    if (document.numberOfPages == 0) document.addPage(page)
                    else document.pages.insertBefore(page, document.getPage(0))
                } else {
                    document.pages.insertAfter(page, anchor)
                }
                anchor = page
            }
            afterIndex + 1
        } catch (t: Throwable) {
            // long: 跨文档复制可能因损坏资源中途失败，回收本批页面可让上层快照事务保持原目标文档可用。
            importedPages.asReversed().forEach { page -> runCatching { document.pages.remove(page) } }
            throw t
        }
    }

    fun duplicate(document: PDDocument, index: Int): Int {
        require(index in 0 until document.numberOfPages) { "页面索引越界" }
        val source = document.getPage(index)
        val imported = importPageWithResources(document, source, PDFCloneUtility(document))
        document.pages.remove(imported)
        document.pages.insertAfter(imported, source)
        return index + 1
    }

    fun duplicateSelected(document: PDDocument, indices: Set<Int>, currentIndex: Int): Int {
        require(indices.isNotEmpty()) { "请至少选择一页" }
        require(currentIndex in 0 until document.numberOfPages) { "当前页面索引越界" }
        require(indices.all { it in 0 until document.numberOfPages }) { "页面索引越界" }

        val sourcePages = indices.sorted().map(document::getPage)
        val currentPage = document.getPage(currentIndex)
        val clones = mutableListOf<PDPage>()
        return try {
            val cloner = PDFCloneUtility(document)
            sourcePages.forEach { source ->
                clones += importPageWithResources(document, source, cloner)
            }
            clones.forEach(document.pages::remove)
            var anchor = sourcePages.last()
            clones.forEach { clone ->
                document.pages.insertAfter(clone, anchor)
                anchor = clone
            }
            document.pages.indexOf(currentPage)
        } catch (t: Throwable) {
            // long: 批量复制可能在任一页资源克隆时失败，临时页必须全部移除才能交给上层快照回滚。
            clones.forEach { page -> runCatching { document.pages.remove(page) } }
            throw t
        }
    }

    fun insertBlank(document: PDDocument, afterIndex: Int): Int {
        require(afterIndex in -1 until document.numberOfPages) { "页面索引越界" }
        if (document.numberOfPages == 0) {
            document.addPage(PDPage())
            return 0
        }
        // long: 空白页沿用相邻页的尺寸、裁剪框和旋转方向，避免混合尺寸文档出现突兀的默认纸张。
        val templateIndex = afterIndex.coerceAtLeast(0)
        val template = document.getPage(templateIndex)
        val blank = PDPage(copyRectangle(template.mediaBox)).apply {
            cropBox = copyRectangle(template.cropBox)
            rotation = template.rotation
        }
        if (afterIndex < 0) {
            document.pages.insertBefore(blank, document.getPage(0))
        } else if (afterIndex == document.numberOfPages - 1) {
            document.addPage(blank)
        } else {
            document.pages.insertAfter(blank, document.getPage(afterIndex))
        }
        return afterIndex + 1
    }

    fun move(document: PDDocument, index: Int, delta: Int): Int {
        require(index in 0 until document.numberOfPages) { "页面索引越界" }
        require(delta == -1 || delta == 1) { "页面移动步长必须为一页" }
        val target = index + delta
        require(target in 0 until document.numberOfPages) { "页面已经位于边界" }
        return moveTo(document, index, target)
    }

    fun moveTo(document: PDDocument, sourceIndex: Int, targetIndex: Int): Int {
        require(sourceIndex in 0 until document.numberOfPages) { "页面索引越界" }
        require(targetIndex in 0 until document.numberOfPages) { "页面索引越界" }
        if (sourceIndex == targetIndex) return sourceIndex

        val page = document.getPage(sourceIndex)
        val targetPage = document.getPage(targetIndex)
        document.pages.remove(page)
        // long: 目标页对象在移除源页后仍然稳定，按移动方向插入可保证 targetIndex 表示最终位置。
        if (sourceIndex > targetIndex) {
            document.pages.insertBefore(page, targetPage)
        } else {
            document.pages.insertAfter(page, targetPage)
        }
        return targetIndex
    }

    fun moveSelected(document: PDDocument, indices: Set<Int>, delta: Int, currentIndex: Int): Int {
        require(indices.isNotEmpty()) { "请至少选择一页" }
        require(currentIndex in 0 until document.numberOfPages) { "当前页面索引越界" }
        require(indices.all { it in 0 until document.numberOfPages }) { "页面索引越界" }
        require(delta == -1 || delta == 1) { "批量页面移动步长必须为一页" }

        val pages = document.pages.toList()
        val currentPage = document.getPage(currentIndex)
        val selected = indices.map { pages[it] }.toSet()
        val reordered = pages.toMutableList()
        if (delta < 0) {
            for (index in 1 until reordered.size) {
                if (reordered[index] in selected && reordered[index - 1] !in selected) {
                    val page = reordered[index]
                    reordered[index] = reordered[index - 1]
                    reordered[index - 1] = page
                }
            }
        } else {
            for (index in reordered.size - 2 downTo 0) {
                if (reordered[index] in selected && reordered[index + 1] !in selected) {
                    val page = reordered[index]
                    reordered[index] = reordered[index + 1]
                    reordered[index + 1] = page
                }
            }
        }
        if (reordered != pages) {
            pages.forEach(document.pages::remove)
            reordered.forEach(document.pages::add)
        }
        return document.pages.indexOf(currentPage)
    }

    fun extract(document: PDDocument, indices: Set<Int>): PDDocument {
        require(indices.isNotEmpty()) { "请至少选择一页" }
        require(indices.all { it in 0 until document.numberOfPages }) { "页面索引越界" }
        PdfPageTransferInspector.requireSupported(document, indices)
        val extracted = PDDocument()
        return try {
            val cloner = PDFCloneUtility(extracted)
            val pageIndexMap = LinkedHashMap<Int, Int>()
            val copiedPages = LinkedHashMap<PDPage, PDPage>()
            // long: 选择集合按原文档页码排序后再复制，导出的章节顺序始终与用户看到的 PDF 一致。
            indices.sorted().forEachIndexed { targetIndex, index ->
                val sourcePage = document.getPage(index)
                val importedPage = importPageWithResources(extracted, sourcePage, cloner)
                copiedPages[sourcePage] = importedPage
                pageIndexMap[index] = targetIndex
            }
            copiedPages.forEach { (sourcePage, importedPage) ->
                copyPageAnnotations(
                    source = document,
                    destination = extracted,
                    sourcePage = sourcePage,
                    importedPage = importedPage,
                    pageIndexMap = pageIndexMap,
                    cloner = cloner,
                )
            }
            copyDocumentInformation(document, extracted, cloner)
            copyXmpMetadata(document, extracted)
            copyEmbeddedFiles(document, extracted, cloner)
            copyPageLabels(document, extracted, pageIndexMap)
            copyResolvableOutline(document, extracted, pageIndexMap, cloner)
            extracted
        } catch (t: Throwable) {
            runCatching { extracted.close() }
            throw t
        }
    }

    private fun importPageWithResources(
        destination: PDDocument,
        sourcePage: PDPage,
        cloner: PDFCloneUtility,
    ): PDPage {
        val importedPage = destination.importPage(sourcePage)
        materializeInheritedPageAttributes(sourcePage, importedPage)
        sourcePage.resources?.let { sourceResources ->
            // long: PDFBox 不会携带从 /Pages 继承的资源；深复制后显式挂到目标页，源文档关闭后字体和 XObject 仍可独立保存。
            val cloned = cloner.cloneForNewDocument(sourceResources.cosObject) as? COSDictionary
                ?: error("无法复制页面资源")
            importedPage.resources = PDResources(cloned)
        }
        return importedPage
    }

    private fun copyPageAnnotations(
        source: PDDocument,
        destination: PDDocument,
        sourcePage: PDPage,
        importedPage: PDPage,
        pageIndexMap: Map<Int, Int>,
        cloner: PDFCloneUtility,
    ) {
        val copied = sourcePage.annotations.map { annotation ->
            // long: importPage() 只浅复制 /Annots；克隆前移除源页反向引用，再把副本绑定到新页，
            // 否则源文档继续编辑时会同步篡改导出结果，源文档关闭后也可能留下跨文档引用。
            val detached = COSDictionary(annotation.cosObject).apply {
                removeItem(COSName.P)
                if (annotation is PDAnnotationLink) {
                    removeItem(COSName.A)
                    removeItem(LINK_DESTINATION_KEY)
                }
            }
            val cloned = cloner.cloneForNewDocument(detached) as? COSDictionary
                ?: error("无法复制页面批注")
            val copiedAnnotation = PDAnnotation.createAnnotation(cloned)
            if (annotation is PDAnnotationLink && copiedAnnotation is PDAnnotationLink) {
                copyLinkTarget(
                    sourceDocument = source,
                    destinationDocument = destination,
                    sourceLink = annotation,
                    copiedLink = copiedAnnotation,
                    pageIndexMap = pageIndexMap,
                    cloner = cloner,
                )
            }
            copiedAnnotation.apply {
                page = importedPage
            }
        }
        importedPage.annotations = copied
    }

    private fun copyLinkTarget(
        sourceDocument: PDDocument,
        destinationDocument: PDDocument,
        sourceLink: PDAnnotationLink,
        copiedLink: PDAnnotationLink,
        pageIndexMap: Map<Int, Int>,
        cloner: PDFCloneUtility,
    ) {
        when (val action = sourceLink.action) {
            null -> sourceLink.destination?.let { sourceDestination ->
                copiedLink.destination = copyPageDestination(
                    sourceDocument,
                    destinationDocument,
                    sourceDestination,
                    pageIndexMap,
                    cloner,
                )
            }
            is PDActionURI -> {
                val copiedAction = cloner.cloneForNewDocument(action.cosObject) as? COSDictionary
                    ?: error("无法复制 URI 链接")
                copiedLink.action = PDActionURI(copiedAction)
            }
            is PDActionGoTo -> {
                val actionWithoutDestination = COSDictionary(action.cosObject).apply {
                    removeItem(COSName.D)
                }
                val copiedActionDictionary = cloner.cloneForNewDocument(actionWithoutDestination) as? COSDictionary
                    ?: error("无法复制页内链接动作")
                copiedLink.action = PDActionGoTo(copiedActionDictionary).apply {
                    destination = copyPageDestination(
                        sourceDocument,
                        destinationDocument,
                        requireNotNull(action.destination) { "页内链接缺少目标页" },
                        pageIndexMap,
                        cloner,
                    )
                }
            }
            else -> error("暂不支持导出包含 ${action.javaClass.simpleName} 动作的链接")
        }
    }

    private fun copyPageDestination(
        sourceDocument: PDDocument,
        destinationDocument: PDDocument,
        sourceDestination: PDDestination,
        pageIndexMap: Map<Int, Int>,
        cloner: PDFCloneUtility,
    ): PDDestination {
        val pageDestination = sourceDestination as? PDPageDestination
            ?: error("暂不支持导出命名链接目标")
        val sourcePageIndex = PdfPageTransferInspector.resolvePageIndex(sourceDocument, pageDestination)
            ?: error("无法解析页内链接目标")
        val targetPageIndex = pageIndexMap[sourcePageIndex]
            ?: error("所选页面包含指向未导出页面的链接，请同时导出目标页")
        val sourceArray = pageDestination.cosObject
        val copiedArray = COSArray().apply {
            add(destinationDocument.getPage(targetPageIndex))
            for (index in 1 until sourceArray.size()) {
                add(cloner.cloneForNewDocument(sourceArray.get(index)))
            }
        }
        return PDDestination.create(copiedArray)
    }

    private fun materializeInheritedPageAttributes(sourcePage: PDPage, importedPage: PDPage) {
        // long: 页面尺寸、旋转和资源都可能只写在 /Pages 父节点；跨文档导入后父节点已经变化，
        // 因此必须把源页面实际生效的属性落到副本自己的字典，否则继承属性会静默回退为默认值。
        if (hasInheritedEntry(sourcePage, COSName.MEDIA_BOX)) {
            importedPage.cosObject.setItem(COSName.MEDIA_BOX, copyRectangle(sourcePage.mediaBox).cosObject)
        }
        if (hasInheritedEntry(sourcePage, COSName.CROP_BOX)) {
            importedPage.cosObject.setItem(COSName.CROP_BOX, copyRectangle(sourcePage.cropBox).cosObject)
        }
        if (hasInheritedEntry(sourcePage, COSName.BLEED_BOX)) {
            importedPage.cosObject.setItem(COSName.BLEED_BOX, copyRectangle(sourcePage.bleedBox).cosObject)
        }
        if (hasInheritedEntry(sourcePage, COSName.TRIM_BOX)) {
            importedPage.cosObject.setItem(COSName.TRIM_BOX, copyRectangle(sourcePage.trimBox).cosObject)
        }
        if (hasInheritedEntry(sourcePage, COSName.ART_BOX)) {
            importedPage.cosObject.setItem(COSName.ART_BOX, copyRectangle(sourcePage.artBox).cosObject)
        }
        if (hasInheritedEntry(sourcePage, COSName.ROTATE)) {
            importedPage.rotation = sourcePage.rotation
        }
    }

    private fun hasInheritedEntry(page: PDPage, key: COSName): Boolean {
        var dictionary: COSDictionary? = page.cosObject
        while (dictionary != null) {
            if (dictionary.containsKey(key)) return true
            dictionary = dictionary.getDictionaryObject(COSName.PARENT) as? COSDictionary
        }
        return false
    }

    private fun copyDocumentInformation(
        source: PDDocument,
        destination: PDDocument,
        cloner: PDFCloneUtility,
    ) {
        val cloned = cloner.cloneForNewDocument(source.documentInformation.cosObject) as? COSDictionary
            ?: return
        // long: 克隆整个 Info 字典而不是只复制标题，生产者、时间和业务自定义属性都能随导出文件保留。
        destination.documentInformation = PDDocumentInformation(cloned)
    }

    private fun copyXmpMetadata(source: PDDocument, destination: PDDocument) {
        val metadata = source.documentCatalog.metadata ?: return
        // long: Info 字典和 XMP 是两套独立元数据来源；页面子集导出时必须复制 XMP 流，
        // 否则版权、文档标识及归档系统依赖的扩展属性会在无提示的情况下消失。
        metadata.exportXMPMetadata().use { input ->
            destination.documentCatalog.metadata = PDMetadata(destination, input)
        }
    }

    private fun copyEmbeddedFiles(
        source: PDDocument,
        destination: PDDocument,
        cloner: PDFCloneUtility,
    ) {
        val sourceTree = source.documentCatalog.names?.embeddedFiles ?: return
        val clonedTree = cloner.cloneForNewDocument(sourceTree.cosObject) as? COSDictionary
            ?: error("无法复制 PDF 附件名称树")
        val destinationNames = PDDocumentNameDictionary(destination.documentCatalog).apply {
            embeddedFiles = PDEmbeddedFilesNameTreeNode(clonedTree)
        }
        // long: 附件是文档级资产，与选中页无直接归属；子集导出时完整复制，避免生成能打开但附件静默消失的 PDF。
        destination.documentCatalog.names = destinationNames
    }

    private fun copyPageLabels(
        source: PDDocument,
        destination: PDDocument,
        pageIndexMap: Map<Int, Int>,
    ) {
        val sourceLabels = source.documentCatalog.pageLabels?.labelsByPageIndices ?: return
        val copiedLabels = PDPageLabels(destination)
        pageIndexMap.forEach { (sourceIndex, targetIndex) ->
            val visibleLabel = sourceLabels.getOrNull(sourceIndex) ?: return@forEach
            // long: 非连续选页会打断原标签区间，按目标页写入最终可见标签可精确保留“前言-i”等逻辑页码，
            // 而不是错误地从新文档第 1 页重新开始原区间的计数。
            copiedLabels.setLabelItem(
                targetIndex,
                PDPageLabelRange().apply { prefix = visibleLabel },
            )
        }
        destination.documentCatalog.pageLabels = copiedLabels
    }

    private fun copyResolvableOutline(
        source: PDDocument,
        destination: PDDocument,
        pageIndexMap: Map<Int, Int>,
        cloner: PDFCloneUtility,
    ) {
        val sourceRoot = source.documentCatalog.documentOutline ?: return
        val destinationRoot = PDDocumentOutline()
        val visited = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        childrenOf(sourceRoot).forEach { item ->
            copyOutlineItem(source, destination, item, pageIndexMap, cloner, visited)
                ?.let(destinationRoot::addLast)
        }
        if (destinationRoot.hasChildren()) {
            destinationRoot.openNode()
            destination.documentCatalog.documentOutline = destinationRoot
        }
    }

    private fun copyOutlineItem(
        source: PDDocument,
        destination: PDDocument,
        item: PDOutlineItem,
        pageIndexMap: Map<Int, Int>,
        cloner: PDFCloneUtility,
        visited: MutableSet<COSDictionary>,
    ): PDOutlineItem? {
        if (!visited.add(item.cosObject)) return null
        val targetPageIndex = runCatching { item.findDestinationPage(source) }
            .getOrNull()
            ?.let(source.pages::indexOf)
            ?.let(pageIndexMap::get)
        val children = childrenOf(item).mapNotNull { child ->
            copyOutlineItem(source, destination, child, pageIndexMap, cloner, visited)
        }
        if (targetPageIndex == null && children.isEmpty()) return null

        val copied = PDOutlineItem().apply {
            title = item.title?.trim().orEmpty().ifBlank { "未命名目录" }
            targetPageIndex?.let { setDestination(destination.getPage(it)) }
        }
        // long: 子集导出只保留不依赖源文档对象的颜色和样式标记，旧动作及无效页面目标不会污染新目录树。
        listOf(COSName.C, COSName.F).forEach { key ->
            item.cosObject.getItem(key)?.let { value ->
                copied.cosObject.setItem(key, cloner.cloneForNewDocument(value))
            }
        }
        children.forEach(copied::addLast)
        if (children.isNotEmpty() && item.isNodeOpen) copied.openNode()
        return copied
    }

    private fun childrenOf(parent: PDOutlineNode): List<PDOutlineItem> {
        val result = ArrayList<PDOutlineItem>()
        val visited = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        var item = parent.firstChild
        while (item != null && result.size < MAX_OUTLINE_ENTRIES && visited.add(item.cosObject)) {
            result += item
            item = item.nextSibling
        }
        return result
    }

    private fun copyRectangle(source: PDRectangle): PDRectangle = PDRectangle(
        source.lowerLeftX,
        source.lowerLeftY,
        source.width,
        source.height,
    )

    private const val MAX_OUTLINE_ENTRIES = 10_000
    private val LINK_DESTINATION_KEY = COSName.getPDFName("Dest")
}
