package com.loooong.reader.engine

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PdfFormEditorTest {

    @Test
    fun listFieldsReportsTextAndChoiceMetadata() {
        val document = formDocument()
        val form = requireNotNull(document.documentCatalog.acroForm)
        form.fields = listOf(
            PDTextField(form).apply {
                partialName = "name"
                alternateFieldName = "姓名"
                isRequired = true
                setValue("张三")
            },
            PDComboBox(form).apply {
                partialName = "city"
                setOptions(listOf("sh", "bj"), listOf("上海", "北京"))
                setValue("bj")
            },
        )

        val fields = PdfFormEditor.list(document)

        assertEquals(2, fields.size)
        assertEquals(PdfFormFieldKind.TEXT, fields[0].kind)
        assertEquals("姓名", fields[0].label)
        assertEquals(listOf("张三"), fields[0].values)
        assertTrue(fields[0].required)
        assertEquals(PdfFormFieldKind.COMBO_BOX, fields[1].kind)
        assertEquals(listOf("上海", "北京"), fields[1].options.map(PdfFormOption::label))
        assertEquals(listOf("bj"), fields[1].values)
        document.close()
    }

    @Test
    fun updateFieldsChangesMultipleValuesInOneOperation() {
        val document = formDocument()
        val form = requireNotNull(document.documentCatalog.acroForm)
        val name = PDTextField(form).apply {
            partialName = "name"
            setValue("旧姓名")
        }
        val city = PDComboBox(form).apply {
            partialName = "city"
            setOptions(listOf("sh", "bj"), listOf("上海", "北京"))
            setValue("sh")
        }
        form.fields = listOf(name, city)

        val changed = PdfFormEditor.update(
            document,
            mapOf(0 to listOf("新姓名"), 1 to listOf("bj")),
        )

        assertTrue(changed)
        assertEquals("新姓名", name.value)
        assertEquals(listOf("bj"), city.value)
        assertFalse(PdfFormEditor.update(document, mapOf(0 to listOf("新姓名"))))

        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        PDDocument.load(saved).use { reopened ->
            val fields = PdfFormEditor.list(reopened)
            assertEquals(listOf("新姓名"), fields[0].values)
            assertEquals(listOf("bj"), fields[1].values)
        }
        document.close()
    }

    @Test(expected = IllegalArgumentException::class)
    fun readOnlyFieldCannotBeUpdated() {
        val document = formDocument()
        val form = requireNotNull(document.documentCatalog.acroForm)
        form.fields = listOf(
            PDTextField(form).apply {
                partialName = "locked"
                isReadOnly = true
            },
        )

        try {
            PdfFormEditor.update(document, mapOf(0 to listOf("不可写")))
        } finally {
            document.close()
        }
    }

    @Test
    fun invalidChoiceIsRejectedBeforeOtherFieldsAreChanged() {
        val document = formDocument()
        val form = requireNotNull(document.documentCatalog.acroForm)
        val name = PDTextField(form).apply {
            partialName = "name"
            setValue("原值")
        }
        val city = PDComboBox(form).apply {
            partialName = "city"
            setOptions(listOf("sh", "bj"), listOf("上海", "北京"))
            setValue("sh")
        }
        form.fields = listOf(name, city)

        try {
            PdfFormEditor.update(document, mapOf(0 to listOf("新值"), 1 to listOf("unknown")))
            throw AssertionError("expected invalid choice to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("选项已经失效"))
        }

        assertEquals("原值", name.value)
        assertEquals(listOf("sh"), city.value)
        document.close()
    }

    @Test
    fun createTextFieldAddsAWidgetThatCanBeFilledAfterReload() {
        val document = PDDocument().apply { addPage(PDPage(PDRectangle(300f, 400f))) }

        val index = PdfFormEditor.create(
            document,
            PdfFormFieldCreateRequest(
                kind = PdfFormFieldCreateKind.TEXT,
                name = "customer_name",
                label = "客户姓名",
                pageIndex = 0,
                bounds = Bounds(40f, 300f, 220f, 332f),
                required = true,
            ),
        )

        assertEquals(0, index)
        val created = PdfFormEditor.list(document).single()
        assertEquals("customer_name", created.name)
        assertEquals("客户姓名", created.label)
        assertEquals(PdfFormFieldKind.TEXT, created.kind)
        assertEquals(0, created.pageIndex)
        assertTrue(created.required)
        assertEquals(1, document.getPage(0).annotations.size)

        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        document.close()
        PDDocument.load(saved).use { reopened ->
            assertTrue(PdfFormEditor.update(reopened, mapOf(0 to listOf("李四"))))
            assertEquals(listOf("李四"), PdfFormEditor.list(reopened).single().values)
        }
    }

    @Test
    fun createCheckBoxAddsAnInteractiveFieldThatKeepsItsValue() {
        val document = PDDocument().apply { addPage(PDPage(PDRectangle(300f, 400f))) }

        val index = PdfFormEditor.create(
            document,
            PdfFormFieldCreateRequest(
                kind = PdfFormFieldCreateKind.CHECKBOX,
                name = "accepted",
                label = "同意条款",
                pageIndex = 0,
                bounds = Bounds(40f, 260f, 64f, 284f),
                required = true,
            ),
        )

        assertEquals(PdfFormFieldKind.CHECKBOX, PdfFormEditor.list(document)[index].kind)
        assertTrue(PdfFormEditor.update(document, mapOf(index to listOf("true"))))
        assertEquals(listOf("true"), PdfFormEditor.list(document)[index].values)

        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        document.close()
        PDDocument.load(saved).use { reopened ->
            assertEquals(PdfFormFieldKind.CHECKBOX, PdfFormEditor.list(reopened)[index].kind)
            assertEquals(listOf("true"), PdfFormEditor.list(reopened)[index].values)
            assertEquals(1, reopened.getPage(0).annotations.size)
        }
    }

    @Test
    fun createComboBoxPersistsOptionsAndAcceptsASelectedValue() {
        val document = PDDocument().apply { addPage(PDPage(PDRectangle(300f, 400f))) }

        val index = PdfFormEditor.create(
            document,
            PdfFormFieldCreateRequest(
                kind = PdfFormFieldCreateKind.COMBO_BOX,
                name = "priority",
                label = "优先级",
                pageIndex = 0,
                bounds = Bounds(40f, 220f, 220f, 252f),
                options = listOf("高", "中", "低"),
            ),
        )

        val created = PdfFormEditor.list(document)[index]
        assertEquals(PdfFormFieldKind.COMBO_BOX, created.kind)
        assertEquals(listOf("高", "中", "低"), created.options.map(PdfFormOption::label))
        assertEquals(listOf("高"), created.values)
        assertTrue(PdfFormEditor.update(document, mapOf(index to listOf("低"))))

        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        document.close()
        PDDocument.load(saved).use { reopened ->
            val field = PdfFormEditor.list(reopened)[index]
            assertEquals(listOf("高", "中", "低"), field.options.map(PdfFormOption::label))
            assertEquals(listOf("低"), field.values)
        }
    }

    @Test
    fun deleteFieldRemovesItsWidgetAndPersistsAfterReload() {
        val document = PDDocument().apply { addPage(PDPage(PDRectangle(300f, 400f))) }
        PdfFormEditor.create(
            document,
            PdfFormFieldCreateRequest(
                kind = PdfFormFieldCreateKind.TEXT,
                name = "keep",
                label = "保留字段",
                pageIndex = 0,
                bounds = Bounds(40f, 300f, 220f, 332f),
            ),
        )
        PdfFormEditor.create(
            document,
            PdfFormFieldCreateRequest(
                kind = PdfFormFieldCreateKind.CHECKBOX,
                name = "remove",
                label = "删除字段",
                pageIndex = 0,
                bounds = Bounds(40f, 250f, 64f, 274f),
            ),
        )

        assertTrue(PdfFormEditor.delete(document, 1))
        assertEquals(listOf("keep"), PdfFormEditor.list(document).map(PdfFormFieldInfo::name))
        assertEquals(1, document.getPage(0).annotations.size)

        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        document.close()
        PDDocument.load(saved).use { reopened ->
            assertEquals(listOf("keep"), PdfFormEditor.list(reopened).map(PdfFormFieldInfo::name))
            assertEquals(1, reopened.getPage(0).annotations.size)
        }
    }

    @Test
    fun deleteNestedFieldAlsoPrunesItsEmptyParent() {
        val document = PDDocument().apply { addPage(PDPage(PDRectangle(300f, 400f))) }
        val form = PDAcroForm(document).also { document.documentCatalog.acroForm = it }
        val parent = PDNonTerminalField(form).apply { partialName = "contact" }
        form.fields = listOf(parent)
        PdfFormEditor.create(
            document,
            PdfFormFieldCreateRequest(
                kind = PdfFormFieldCreateKind.TEXT,
                name = "temporary",
                label = "临时字段",
                pageIndex = 0,
                bounds = Bounds(40f, 300f, 220f, 332f),
            ),
        )
        val child = form.fields.last()
        form.fields = listOf(parent)
        parent.children = listOf(child)

        assertEquals(1, document.getPage(0).annotations.size)
        assertTrue(PdfFormEditor.delete(document, 0))
        assertTrue(PdfFormEditor.list(document).isEmpty())
        assertTrue(form.fields.isEmpty())
        assertTrue(document.getPage(0).annotations.isEmpty())
        document.close()
    }

    private fun formDocument(): PDDocument = PDDocument().also { document ->
        document.addPage(PDPage())
        document.documentCatalog.acroForm = PDAcroForm(document).apply {
            setNeedAppearances(true)
        }
    }
}
