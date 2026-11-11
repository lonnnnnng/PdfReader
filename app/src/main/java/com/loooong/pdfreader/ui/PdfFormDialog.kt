package com.loooong.pdfreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.Trash
import com.loooong.pdfreader.engine.PdfFormFieldInfo
import com.loooong.pdfreader.engine.PdfFormFieldKind
import com.loooong.pdfreader.engine.PdfFormOption

@Composable
fun PdfFormDialog(
    fields: List<PdfFormFieldInfo>,
    busy: Boolean,
    onApply: (Map<Int, List<String>>) -> Unit,
    onDelete: (PdfFormFieldInfo) -> Unit,
    onGoToPage: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var drafts by remember(fields) { mutableStateOf(fields.associate { it.index to it.values }) }
    val changed = remember(fields, drafts) {
        fields.mapNotNull { field ->
            drafts[field.index]
                ?.takeIf { it != field.values }
                ?.let { field.index to it }
        }.toMap()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("填写 PDF 表单") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
            ) {
                items(fields, key = { it.index }) { field ->
                    PdfFormFieldEditor(
                        field = field,
                        values = drafts[field.index].orEmpty(),
                        busy = busy,
                        onValuesChange = { values -> drafts = drafts + (field.index to values) },
                        onGoToPage = onGoToPage,
                        onDelete = { onDelete(field) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && changed.isNotEmpty(),
                onClick = { onApply(changed) },
            ) { Text("应用") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun PdfFormFieldEditor(
    field: PdfFormFieldInfo,
    values: List<String>,
    busy: Boolean,
    onValuesChange: (List<String>) -> Unit,
    onGoToPage: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = field.label + if (field.required) " *" else "",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            field.pageIndex?.let { pageIndex ->
                TextButton(enabled = !busy, onClick = { onGoToPage(pageIndex) }) {
                    Text("第 ${pageIndex + 1} 页")
                }
            }
            IconButton(
                enabled = !busy,
                onClick = onDelete,
            ) {
                Icon(TablerIcons.Trash, contentDescription = "删除${field.label}")
            }
        }
        when (field.kind) {
            PdfFormFieldKind.TEXT -> OutlinedTextField(
                value = values.firstOrNull().orEmpty(),
                onValueChange = { onValuesChange(listOf(it)) },
                enabled = field.editable && !busy,
                modifier = Modifier.fillMaxWidth(),
                minLines = if (field.multiline) 3 else 1,
                maxLines = if (field.multiline) 6 else 1,
                visualTransformation = if (field.password) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                supportingText = if (field.readOnly) {
                    { Text("只读字段") }
                } else {
                    null
                },
            )
            PdfFormFieldKind.CHECKBOX -> {
                val checked = values.firstOrNull().toBoolean()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = field.editable && !busy,
                            role = Role.Checkbox,
                            onClick = { onValuesChange(listOf((!checked).toString())) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        enabled = field.editable && !busy,
                        onCheckedChange = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (checked) "已勾选" else "未勾选")
                }
            }
            PdfFormFieldKind.RADIO -> OptionList(
                options = field.options,
                selected = values.toSet(),
                enabled = field.editable && !busy,
                multiSelect = false,
                onSelectedChange = { onValuesChange(it.toList()) },
            )
            PdfFormFieldKind.COMBO_BOX -> ChoiceMenu(
                options = field.options,
                selected = values.firstOrNull(),
                enabled = field.editable && !busy,
                onSelected = { onValuesChange(listOf(it)) },
            )
            PdfFormFieldKind.LIST_BOX -> OptionList(
                options = field.options,
                selected = values.toSet(),
                enabled = field.editable && !busy,
                multiSelect = field.multiSelect,
                onSelectedChange = { onValuesChange(it.toList()) },
            )
            PdfFormFieldKind.UNSUPPORTED -> Text(
                "暂不支持此字段类型",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OptionList(
    options: List<PdfFormOption>,
    selected: Set<String>,
    enabled: Boolean,
    multiSelect: Boolean,
    onSelectedChange: (Set<String>) -> Unit,
) {
    if (options.isEmpty()) {
        Text("没有可选项", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column {
        options.forEach { option ->
            val checked = option.value in selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = enabled,
                        role = if (multiSelect) Role.Checkbox else Role.RadioButton,
                        onClick = {
                            val next = if (multiSelect) {
                                if (checked) selected - option.value else selected + option.value
                            } else {
                                setOf(option.value)
                            }
                            onSelectedChange(next)
                        },
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (multiSelect) {
                    Checkbox(checked = checked, enabled = enabled, onCheckedChange = null)
                } else {
                    RadioButton(selected = checked, enabled = enabled, onClick = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(option.label, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ChoiceMenu(
    options: List<PdfFormOption>,
    selected: String?,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.value == selected }?.label ?: selected.orEmpty()
    Box {
        TextButton(
            enabled = enabled && options.isNotEmpty(),
            onClick = { expanded = true },
        ) {
            Text(label.ifBlank { "请选择" })
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onSelected(option.value)
                    },
                )
            }
        }
    }
}
