package com.loooong.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.loooong.reader.engine.PdfFormFieldCreateKind

@Composable
fun PdfFormFieldCreateDialog(
    busy: Boolean,
    usesSelectionBounds: Boolean,
    onCreate: (
        kind: PdfFormFieldCreateKind,
        name: String,
        label: String,
        required: Boolean,
        multiline: Boolean,
        options: List<String>,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf(PdfFormFieldCreateKind.TEXT) }
    var name by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var required by remember { mutableStateOf(false) }
    var multiline by remember { mutableStateOf(false) }
    var optionsText by remember { mutableStateOf("") }
    val options = optionsText.split('\n', ',', '，')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
    val valid = name.trim().isNotEmpty() && name.trim().length <= 128 &&
        (kind != PdfFormFieldCreateKind.COMBO_BOX || options.size >= 2)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("创建表单字段") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (usesSelectionBounds) "字段将覆盖当前选中元素的区域。"
                    else "字段将放在当前页中央，可先选择页面元素作为定位区域。",
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FormKindChip("文本框", PdfFormFieldCreateKind.TEXT, kind, busy) { kind = it }
                    FormKindChip("复选框", PdfFormFieldCreateKind.CHECKBOX, kind, busy) { kind = it }
                    FormKindChip("下拉框", PdfFormFieldCreateKind.COMBO_BOX, kind, busy) { kind = it }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(128) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("字段名称") },
                    supportingText = { Text("名称在当前 PDF 中必须唯一") },
                    singleLine = true,
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(128) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("显示名称") },
                    supportingText = { Text("留空时使用字段名称") },
                    singleLine = true,
                    enabled = !busy,
                )
                if (kind == PdfFormFieldCreateKind.COMBO_BOX) {
                    OutlinedTextField(
                        value = optionsText,
                        onValueChange = { optionsText = it.take(2_000) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("下拉选项") },
                        supportingText = { Text("每行一个选项，至少填写两个") },
                        minLines = 3,
                        maxLines = 6,
                        enabled = !busy,
                    )
                }
                if (kind == PdfFormFieldCreateKind.TEXT) {
                    FormBooleanOption("允许多行输入", multiline, busy) { multiline = it }
                }
                FormBooleanOption("必填字段", required, busy) { required = it }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && valid,
                onClick = {
                    onCreate(kind, name.trim(), label.trim(), required, multiline, options)
                },
            ) { Text(if (busy) "正在创建" else "创建") }
        },
    )
}

@Composable
private fun FormKindChip(
    label: String,
    value: PdfFormFieldCreateKind,
    selected: PdfFormFieldCreateKind,
    busy: Boolean,
    onSelect: (PdfFormFieldCreateKind) -> Unit,
) {
    FilterChip(
        selected = value == selected,
        onClick = { onSelect(value) },
        enabled = !busy,
        label = { Text(label) },
    )
}

@Composable
private fun FormBooleanOption(
    label: String,
    checked: Boolean,
    busy: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = !busy)
        Text(label)
    }
}
