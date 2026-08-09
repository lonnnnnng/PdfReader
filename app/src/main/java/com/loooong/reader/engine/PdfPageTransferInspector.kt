package com.loooong.reader.engine

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination

enum class PdfPageTransferIssueKind {
    ACRO_FORM,
    NAME_TREE,
    NAMED_DESTINATIONS,
    TAGGED_STRUCTURE,
    OPTIONAL_CONTENT,
    OPEN_ACTION,
    ADDITIONAL_ACTIONS,
    THREADS,
    LINK_TARGET_OUTSIDE_SELECTION,
    NAMED_LINK_TARGET,
    UNSUPPORTED_LINK_ACTION,
    BROKEN_LINK,
}

data class PdfPageTransferIssue(
    val kind: PdfPageTransferIssueKind,
    val message: String,
)

class PdfPageTransferException(
    val issues: List<PdfPageTransferIssue>,
) : IllegalStateException(issues.joinToString("；", transform = PdfPageTransferIssue::message))

/** long: 页面跨文档复制前统一识别尚不能完整重建的文档结构，禁止导出流程静默生成残缺 PDF。 */
object PdfPageTransferInspector {

    fun requireSupported(document: PDDocument, pageIndices: Set<Int>) {
        val issues = inspect(document, pageIndices)
        if (issues.isNotEmpty()) throw PdfPageTransferException(issues)
    }

    fun inspect(document: PDDocument, pageIndices: Set<Int>): List<PdfPageTransferIssue> {
        require(pageIndices.all { it in 0 until document.numberOfPages }) { "页面索引越界" }
        val issues = mutableListOf<PdfPageTransferIssue>()
        val form = document.documentCatalog.acroForm
        if (form != null && runCatching { form.fieldTree.any() }.getOrDefault(true)) {
            // long: Widget 只是页面上的外观和交互区域，字段值、名称与计算规则位于 AcroForm 字段树；
            // 只复制页面会留下看得见但无法正确填写或提交的孤儿控件。
            issues += PdfPageTransferIssue(
                PdfPageTransferIssueKind.ACRO_FORM,
                "当前 PDF 包含表单字段，页面导出暂不能保证字段结构完整，已取消操作",
            )
        }
        val catalog = document.documentCatalog.cosObject
        (catalog.getDictionaryObject(COSName.NAMES) as? com.tom_roush.pdfbox.cos.COSDictionary)?.let { names ->
            val unsupportedKeys = names.keySet().filterNot { it == EMBEDDED_FILES_KEY }
            if (unsupportedKeys.isNotEmpty()) {
                val namedDestinations = unsupportedKeys.any { it == DESTINATIONS_KEY }
                issues += PdfPageTransferIssue(
                    if (namedDestinations) {
                        PdfPageTransferIssueKind.NAMED_DESTINATIONS
                    } else {
                        PdfPageTransferIssueKind.NAME_TREE
                    },
                    if (namedDestinations) {
                        "当前 PDF 包含命名目标，页面导出暂不能保证命名目标完整，已取消操作"
                    } else {
                        "当前 PDF 包含附件之外的名称树，页面导出暂不能保证其结构完整，已取消操作"
                    },
                )
            }
        }
        CATALOG_RULES.forEach { rule ->
            if (catalog.containsKey(rule.key)) {
                // long: 这些对象从 Catalog 根节点维护跨页引用，单独复制页面无法证明目标、层级或脚本仍完整；
                // 在专用重建器完成前统一拒绝，比生成可打开但语义残缺的 PDF 更安全。
                issues += PdfPageTransferIssue(rule.kind, rule.message)
            }
        }
        pageIndices.sorted().forEach { pageIndex ->
            document.getPage(pageIndex).annotations.filterIsInstance<PDAnnotationLink>().forEach { link ->
                inspectLink(document, pageIndices, link)?.let(issues::add)
            }
        }
        return issues
    }

    fun inspectAll(
        document: PDDocument,
        pageSelections: List<Set<Int>>,
    ): List<PdfPageTransferIssue> {
        require(pageSelections.all { it.isNotEmpty() }) { "每个页面导出范围都必须至少包含一页" }
        // long: 拆分 PDF 会对同一文档执行多次页面提取；在用户选择输出文件夹前一次性检查全部范围，
        // 并按问题类型去重，避免同一份名称树或表单风险重复占满提示框。
        return pageSelections
            .flatMap { selection -> inspect(document, selection) }
            .distinctBy(PdfPageTransferIssue::kind)
    }

    private fun inspectLink(
        document: PDDocument,
        pageIndices: Set<Int>,
        link: PDAnnotationLink,
    ): PdfPageTransferIssue? = runCatching {
        when (val action = link.action) {
            null -> link.destination?.let { inspectDestination(document, pageIndices, it) }
            is PDActionURI -> null
            is PDActionGoTo -> {
                val destination = action.destination
                if (destination == null) PdfPageTransferIssue(
                    PdfPageTransferIssueKind.BROKEN_LINK,
                    "所选页面包含无法解析目标的页内链接，已取消导出",
                ) else inspectDestination(document, pageIndices, destination)
            }
            else -> PdfPageTransferIssue(
                PdfPageTransferIssueKind.UNSUPPORTED_LINK_ACTION,
                "所选页面包含暂不支持的 ${action.javaClass.simpleName} 链接动作，已取消导出",
            )
        }
    }.getOrElse {
        PdfPageTransferIssue(
            PdfPageTransferIssueKind.BROKEN_LINK,
            "所选页面包含结构异常的链接，已取消导出",
        )
    }

    private fun inspectDestination(
        document: PDDocument,
        pageIndices: Set<Int>,
        destination: PDDestination,
    ): PdfPageTransferIssue? {
        val pageDestination = destination as? PDPageDestination
            ?: return PdfPageTransferIssue(
                PdfPageTransferIssueKind.NAMED_LINK_TARGET,
                "所选页面包含命名链接目标，页面导出暂不能保证目标完整，已取消操作",
            )
        val targetPageIndex = resolvePageIndex(document, pageDestination)
            ?: return PdfPageTransferIssue(
                PdfPageTransferIssueKind.BROKEN_LINK,
                "所选页面包含无法解析目标的页内链接，已取消导出",
            )
        return if (targetPageIndex !in pageIndices) {
            PdfPageTransferIssue(
                PdfPageTransferIssueKind.LINK_TARGET_OUTSIDE_SELECTION,
                "所选页面包含指向未导出页面的链接，请同时导出目标页",
            )
        } else {
            null
        }
    }

    internal fun resolvePageIndex(document: PDDocument, destination: PDPageDestination): Int? {
        val directPage = destination.page
        if (directPage != null) {
            val indexed = document.pages.indexOf(directPage)
            if (indexed >= 0) return indexed

            // long: PDFBox 可能为同一个页面字典创建不同的 PDPage 包装对象；按 COS 身份匹配才能识别直接页引用，
            // 避免把实际已经包含在导出集合中的页内链接误判成损坏链接。
            val dictionaryIndex = (0 until document.numberOfPages).firstOrNull { pageIndex ->
                document.getPage(pageIndex).cosObject === directPage.cosObject
            }
            if (dictionaryIndex != null) return dictionaryIndex
        }
        return destination.pageNumber.takeIf { it in 0 until document.numberOfPages }
            ?: runCatching { destination.retrievePageNumber() }.getOrNull()
                ?.takeIf { it in 0 until document.numberOfPages }
    }

    private data class CatalogRule(
        val key: COSName,
        val kind: PdfPageTransferIssueKind,
        val message: String,
    )

    private val CATALOG_RULES = listOf(
        CatalogRule(
            COSName.getPDFName("Dests"),
            PdfPageTransferIssueKind.NAMED_DESTINATIONS,
            "当前 PDF 包含命名目标，页面导出暂不能保证命名目标完整，已取消操作",
        ),
        CatalogRule(
            COSName.getPDFName("StructTreeRoot"),
            PdfPageTransferIssueKind.TAGGED_STRUCTURE,
            "当前 PDF 包含标签结构，页面导出暂不能保证 Tagged PDF 语义完整，已取消操作",
        ),
        CatalogRule(
            COSName.getPDFName("OCProperties"),
            PdfPageTransferIssueKind.OPTIONAL_CONTENT,
            "当前 PDF 包含可选图层，页面导出暂不能保证图层配置完整，已取消操作",
        ),
        CatalogRule(
            COSName.getPDFName("OpenAction"),
            PdfPageTransferIssueKind.OPEN_ACTION,
            "当前 PDF 包含打开动作，页面导出暂不能保证动作目标安全，已取消操作",
        ),
        CatalogRule(
            COSName.getPDFName("AA"),
            PdfPageTransferIssueKind.ADDITIONAL_ACTIONS,
            "当前 PDF 包含文档附加动作，页面导出暂不能保证动作引用安全，已取消操作",
        ),
        CatalogRule(
            COSName.getPDFName("Threads"),
            PdfPageTransferIssueKind.THREADS,
            "当前 PDF 包含文章线程，页面导出暂不能保证文章线程完整，已取消操作",
        ),
    )

    private val EMBEDDED_FILES_KEY = COSName.getPDFName("EmbeddedFiles")
    private val DESTINATIONS_KEY = COSName.getPDFName("Dests")
}
