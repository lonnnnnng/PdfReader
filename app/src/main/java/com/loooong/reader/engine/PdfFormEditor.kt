package com.loooong.reader.engine

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDAppearanceContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceCharacteristicsDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField

enum class PdfFormFieldKind {
    TEXT,
    CHECKBOX,
    RADIO,
    COMBO_BOX,
    LIST_BOX,
    UNSUPPORTED,
}

enum class PdfFormFieldCreateKind {
    TEXT,
    CHECKBOX,
    COMBO_BOX,
}

data class PdfFormFieldCreateRequest(
    val kind: PdfFormFieldCreateKind,
    val name: String,
    val label: String,
    val pageIndex: Int,
    val bounds: Bounds,
    val required: Boolean = false,
    val multiline: Boolean = false,
    val options: List<String> = emptyList(),
)

data class PdfFormOption(
    val value: String,
    val label: String,
)

data class PdfFormFieldInfo(
    val index: Int,
    val name: String,
    val label: String,
    val kind: PdfFormFieldKind,
    val values: List<String>,
    val options: List<PdfFormOption> = emptyList(),
    val readOnly: Boolean = false,
    val required: Boolean = false,
    val multiline: Boolean = false,
    val password: Boolean = false,
    val multiSelect: Boolean = false,
    val pageIndex: Int? = null,
) {
    val editable: Boolean
        get() = !readOnly && kind != PdfFormFieldKind.UNSUPPORTED
}

/** long: AcroForm 的读取和写入集中在此处，UI 只处理稳定的字段模型，不直接持有 PDFBox 对象。 */
object PdfFormEditor {

    fun create(document: PDDocument, request: PdfFormFieldCreateRequest): Int {
        val name = request.name.trim()
        val label = request.label.trim().ifBlank { name }
        require(name.isNotEmpty()) { "字段名称不能为空" }
        require(name.length <= 128) { "字段名称不能超过 128 个字符" }
        require(request.pageIndex in 0 until document.numberOfPages) { "字段目标页超出范围" }
        require(request.bounds.isValid && request.bounds.width >= 12f && request.bounds.height >= 12f) {
            "字段区域过小"
        }
        val options = request.options.map(String::trim).filter(String::isNotEmpty).distinct()
        if (request.kind == PdfFormFieldCreateKind.COMBO_BOX) {
            require(options.size >= 2) { "下拉框至少需要两个不同选项" }
        }

        val form = ensureAcroForm(document)
        require(form.fieldTree.none { it.fullyQualifiedName == name }) { "字段名称已存在" }
        val field = when (request.kind) {
            PdfFormFieldCreateKind.TEXT -> PDTextField(form).apply {
                partialName = name
                alternateFieldName = label
                isRequired = request.required
                isMultiline = request.multiline
                defaultAppearance = DEFAULT_APPEARANCE
            }
            PdfFormFieldCreateKind.CHECKBOX -> PDCheckBox(form).apply {
                partialName = name
                alternateFieldName = label
                isRequired = request.required
                exportValues = listOf(CHECKBOX_ON_VALUE)
            }
            PdfFormFieldCreateKind.COMBO_BOX -> PDComboBox(form).apply {
                partialName = name
                alternateFieldName = label
                isRequired = request.required
                defaultAppearance = DEFAULT_APPEARANCE
                setOptions(options)
            }
        }
        val page = document.getPage(request.pageIndex)
        val widget = createWidget(field, page, request.bounds)
        if (field is PDCheckBox) configureCheckBoxAppearance(document, widget, request.bounds)
        form.fields = form.fields.toMutableList().apply { add(field) }
        page.annotations = page.annotations.toMutableList().apply { add(widget) }
        applyWithAppearanceFallback(form.getNeedAppearances(), form::setNeedAppearances) {
            when (field) {
                is PDTextField -> field.setValue("")
                is PDCheckBox -> field.unCheck()
                is PDComboBox -> field.setValue(options.first())
            }
        }
        markUpdated(document, form, field.cosObject, widget.cosObject, page.cosObject)
        return form.fieldTree.toList().indexOfFirst { it.cosObject === field.cosObject }
            .takeIf { it >= 0 } ?: error("新建字段未加入表单树")
    }

    fun list(document: PDDocument): List<PdfFormFieldInfo> {
        val form = document.documentCatalog.acroForm ?: return emptyList()
        return form.fieldTree.toList().mapIndexed { index, field ->
            val kind = field.kind()
            PdfFormFieldInfo(
                index = index,
                name = field.fullyQualifiedName ?: field.partialName ?: "field-$index",
                label = field.alternateFieldName
                    ?: field.partialName
                    ?: field.fullyQualifiedName
                    ?: "未命名字段 ${index + 1}",
                kind = kind,
                values = field.currentValues(kind),
                options = field.options(kind),
                readOnly = field.isReadOnly,
                required = field.isRequired,
                multiline = (field as? PDTextField)?.isMultiline == true,
                password = (field as? PDTextField)?.isPassword == true,
                multiSelect = (field as? PDChoice)?.isMultiSelect == true,
                pageIndex = findPageIndex(document, field),
            )
        }
    }

    fun update(document: PDDocument, updates: Map<Int, List<String>>): Boolean {
        if (updates.isEmpty()) return false
        val form = document.documentCatalog.acroForm ?: error("当前 PDF 没有可填写表单")
        val fields = form.fieldTree.toList()
        val changed = updates.map { (index, values) ->
            val field = fields.getOrNull(index) ?: throw IllegalArgumentException("表单字段已经不存在")
            require(!field.isReadOnly) { "“${field.alternateFieldName ?: field.partialName}”为只读字段" }
            val kind = field.kind()
            require(kind != PdfFormFieldKind.UNSUPPORTED) { "当前字段类型暂不支持编辑" }
            val normalized = normalizeValues(kind, field, values)
            validateValues(kind, field, normalized)
            Triple(field, kind, normalized)
        }.filter { (field, kind, values) ->
            field.currentValues(kind) != values
        }
        if (changed.isEmpty()) return false

        changed.forEach { (field, kind, values) ->
            val normalized = normalizeValues(kind, field, values)
            applyWithAppearanceFallback(form.getNeedAppearances(), { form.setNeedAppearances(it) }) {
                when (kind) {
                    PdfFormFieldKind.TEXT -> field.setValue(normalized.firstOrNull().orEmpty())
                    PdfFormFieldKind.CHECKBOX -> {
                        if (normalized.firstOrNull().toBoolean()) (field as PDCheckBox).check()
                        else (field as PDCheckBox).unCheck()
                    }
                    PdfFormFieldKind.RADIO -> field.setValue(normalized.firstOrNull().orEmpty())
                    PdfFormFieldKind.COMBO_BOX -> field.setValue(normalized.firstOrNull().orEmpty())
                    PdfFormFieldKind.LIST_BOX -> {
                        val choice = field as PDChoice
                        if (choice.isMultiSelect) choice.setValue(normalized)
                        else choice.setValue(normalized.firstOrNull().orEmpty())
                    }
                    PdfFormFieldKind.UNSUPPORTED -> error("当前字段类型暂不支持编辑")
                }
            }
        }
        return true
    }

    fun delete(document: PDDocument, index: Int): Boolean {
        val form = document.documentCatalog.acroForm ?: error("当前 PDF 没有可管理表单")
        val field = form.fieldTree.toList().getOrNull(index)
            ?: throw IllegalArgumentException("表单字段已经不存在")
        // long: 字段字典从表单树移除后，页面上的 Widget 不会自动消失；必须同步清理所有页面引用，
        // 否则阅读器仍可能显示并响应一个已经没有字段定义的孤立控件。
        val widgetObjects = field.subtreeWidgets().map { it.cosObject }.toSet()
        val updatedFieldObjects = mutableListOf<COSDictionary>()
        if (!detachField(form, field, updatedFieldObjects)) return false
        for (pageIndex in 0 until document.numberOfPages) {
            val page = document.getPage(pageIndex)
            val annotations = page.annotations
            val retained = annotations.filterNot { annotation -> annotation.cosObject in widgetObjects }
            if (retained.size != annotations.size) {
                page.annotations = retained
                page.cosObject.isNeedToBeUpdated = true
            }
        }
        markUpdated(
            document,
            form,
            *((updatedFieldObjects + widgetObjects).distinct().toTypedArray()),
        )
        return true
    }

    private fun detachField(
        form: PDAcroForm,
        field: PDField,
        updatedObjects: MutableList<COSDictionary>,
    ): Boolean {
        val parent = field.parent
        val siblings = parent?.children ?: form.fields
        val retained = siblings.filterNot { it.cosObject === field.cosObject }
        if (retained.size == siblings.size) return false
        updatedObjects += field.cosObject
        if (parent == null) {
            form.fields = retained
        } else {
            parent.children = retained
            updatedObjects += parent.cosObject
            // long: 删除层级表单的最后一个子字段后同步剪除空父节点，避免保存出没有控件、
            // 也无法在填写界面访问的空字段路径。
            if (retained.isEmpty()) detachField(form, parent, updatedObjects)
        }
        return true
    }

    private fun PDField.subtreeWidgets(): List<PDAnnotationWidget> = when (this) {
        is PDNonTerminalField -> children.flatMap { child -> child.subtreeWidgets() }
        else -> widgets
    }

    private fun PDField.kind(): PdfFormFieldKind = when (this) {
        is PDTextField -> PdfFormFieldKind.TEXT
        is PDCheckBox -> PdfFormFieldKind.CHECKBOX
        is PDRadioButton -> PdfFormFieldKind.RADIO
        is PDComboBox -> PdfFormFieldKind.COMBO_BOX
        is PDListBox -> PdfFormFieldKind.LIST_BOX
        is PDPushButton -> PdfFormFieldKind.UNSUPPORTED
        else -> PdfFormFieldKind.UNSUPPORTED
    }

    private fun PDField.currentValues(kind: PdfFormFieldKind): List<String> = when (kind) {
        PdfFormFieldKind.CHECKBOX -> listOf((this as PDCheckBox).isChecked.toString())
        PdfFormFieldKind.COMBO_BOX,
        PdfFormFieldKind.LIST_BOX,
        -> (this as PDChoice).value
        PdfFormFieldKind.RADIO -> valueAsString.takeIf { it.isNotBlank() && it != "Off" }?.let(::listOf).orEmpty()
        else -> listOf(valueAsString)
    }

    private fun PDField.options(kind: PdfFormFieldKind): List<PdfFormOption> = when (kind) {
        PdfFormFieldKind.COMBO_BOX,
        PdfFormFieldKind.LIST_BOX,
        -> {
            val choice = this as PDChoice
            val exports = choice.optionsExportValues
            val displays = choice.optionsDisplayValues
            exports.mapIndexed { index, value ->
                PdfFormOption(value, displays.getOrNull(index) ?: value)
            }
        }
        PdfFormFieldKind.RADIO -> {
            val button = this as PDRadioButton
            button.exportValues.ifEmpty { button.onValues.toList() }
                .distinct()
                .map { PdfFormOption(it, it) }
        }
        else -> emptyList()
    }

    private fun normalizeValues(
        kind: PdfFormFieldKind,
        field: PDField,
        values: List<String>,
    ): List<String> = when (kind) {
        PdfFormFieldKind.CHECKBOX -> listOf(values.firstOrNull().toBoolean().toString())
        PdfFormFieldKind.LIST_BOX -> if ((field as PDChoice).isMultiSelect) values.distinct() else values.take(1)
        else -> values.take(1)
    }

    private fun validateValues(kind: PdfFormFieldKind, field: PDField, values: List<String>) {
        val allowed = field.options(kind).map(PdfFormOption::value).toSet()
        val acceptsCustomValue = field is PDComboBox && field.isEdit
        if (allowed.isNotEmpty() && !acceptsCustomValue) {
            require(values.all(allowed::contains)) { "表单选项已经失效，请重新选择" }
        }
    }

    private fun findPageIndex(document: PDDocument, field: PDField): Int? {
        field.widgets.forEach { widget ->
            widget.page?.let { page ->
                val index = document.pages.indexOf(page)
                if (index >= 0) return index
            }
            for (pageIndex in 0 until document.numberOfPages) {
                val found = document.getPage(pageIndex).annotations.any { annotation ->
                    annotation.cosObject === widget.cosObject
                }
                if (found) return pageIndex
            }
        }
        return null
    }

    private fun applyWithAppearanceFallback(
        needAppearances: Boolean,
        setNeedAppearances: (Boolean) -> Unit,
        update: () -> Unit,
    ) {
        try {
            update()
        } catch (first: Throwable) {
            if (needAppearances) throw first
            // long: 缺少默认字体或外观流的旧表单仍应可填写，交由兼容阅读器在打开时生成字段外观。
            setNeedAppearances(true)
            try {
                update()
            } catch (second: Throwable) {
                setNeedAppearances(false)
                second.addSuppressed(first)
                throw second
            }
        }
    }

    private fun ensureAcroForm(document: PDDocument): PDAcroForm {
        var createdForm = false
        val form = document.documentCatalog.acroForm ?: PDAcroForm(document).also { created ->
            document.documentCatalog.acroForm = created
            createdForm = true
        }
        val resources = form.defaultResources ?: PDResources().also(form::setDefaultResources)
        resources.put(DEFAULT_FONT_NAME, PDType1Font.HELVETICA)
        if (form.defaultAppearance.isNullOrBlank()) form.defaultAppearance = DEFAULT_APPEARANCE
        if (createdForm) form.setNeedAppearances(false)
        return form
    }

    private fun createWidget(
        field: com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField,
        page: com.tom_roush.pdfbox.pdmodel.PDPage,
        bounds: Bounds,
    ): PDAnnotationWidget {
        val widget = PDAnnotationWidget().apply {
            rectangle = PDRectangle(bounds.minX, bounds.minY, bounds.width, bounds.height)
            setPage(page)
            isPrinted = true
            borderStyle = PDBorderStyleDictionary().apply {
                width = 1f
                style = PDBorderStyleDictionary.STYLE_SOLID
            }
            appearanceCharacteristics = PDAppearanceCharacteristicsDictionary(COSDictionary()).apply {
                borderColour = PDColor(floatArrayOf(0.45f, 0.48f, 0.52f), PDDeviceRGB.INSTANCE)
                background = PDColor(floatArrayOf(1f, 1f, 1f), PDDeviceRGB.INSTANCE)
            }
            setParent(field)
        }
        field.widgets = listOf(widget)
        return widget
    }

    private fun configureCheckBoxAppearance(
        document: PDDocument,
        widget: PDAnnotationWidget,
        bounds: Bounds,
    ) {
        val normal = COSDictionary().apply {
            setItem(COSName.Off, checkBoxAppearance(document, bounds.width, bounds.height, checked = false))
            setItem(COSName.getPDFName(CHECKBOX_ON_VALUE), checkBoxAppearance(document, bounds.width, bounds.height, checked = true))
        }
        widget.appearance = PDAppearanceDictionary().apply {
            normalAppearance = PDAppearanceEntry(normal)
        }
        widget.setAppearanceState(COSName.Off.name)
    }

    private fun checkBoxAppearance(
        document: PDDocument,
        width: Float,
        height: Float,
        checked: Boolean,
    ): PDAppearanceStream = PDAppearanceStream(document).apply {
        setBBox(PDRectangle(width, height))
        resources = PDResources()
        PDAppearanceContentStream(this).use { content ->
            content.setNonStrokingColor(1f, 1f, 1f)
            content.setStrokingColor(92f / 255f, 98f / 255f, 108f / 255f)
            content.setLineWidth(1f)
            content.addRect(0.5f, 0.5f, (width - 1f).coerceAtLeast(1f), (height - 1f).coerceAtLeast(1f))
            content.fillAndStroke()
            if (checked) {
                content.setStrokingColor(25f / 255f, 103f / 255f, 210f / 255f)
                content.setLineWidth((minOf(width, height) * 0.11f).coerceAtLeast(1.5f))
                content.moveTo(width * 0.20f, height * 0.52f)
                content.lineTo(width * 0.43f, height * 0.28f)
                content.lineTo(width * 0.82f, height * 0.76f)
                content.stroke()
            }
        }
    }

    private fun markUpdated(
        document: PDDocument,
        form: PDAcroForm,
        vararg dictionaries: COSDictionary,
    ) {
        dictionaries.forEach { it.isNeedToBeUpdated = true }
        form.cosObject.isNeedToBeUpdated = true
        document.documentCatalog.cosObject.isNeedToBeUpdated = true
    }

    private val DEFAULT_FONT_NAME: COSName = COSName.getPDFName("ReaderHelv")
    private const val DEFAULT_APPEARANCE = "/ReaderHelv 12 Tf 0 g"
    private const val CHECKBOX_ON_VALUE = "Yes"
}
