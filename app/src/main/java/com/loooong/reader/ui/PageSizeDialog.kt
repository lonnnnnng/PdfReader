package com.loooong.reader.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowsLeftRight
import compose.icons.tablericons.AspectRatio
import compose.icons.tablericons.X
import java.util.Locale
import kotlin.math.abs

private data class PageSizePreset(val label: String, val width: Float, val height: Float)

private val PAGE_SIZE_PRESETS = listOf(
    PageSizePreset("A4", 595f, 842f),
    PageSizePreset("A5", 420f, 595f),
    PageSizePreset("Letter", 612f, 792f),
    PageSizePreset("Legal", 612f, 1_008f),
)

@Composable
fun PageSizeDialog(
    pageCount: Int,
    initialWidth: Float,
    initialHeight: Float,
    busy: Boolean,
    onApply: (width: Float, height: Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var widthText by remember(initialWidth) { mutableStateOf(initialWidth.formatPageSize()) }
    var heightText by remember(initialHeight) { mutableStateOf(initialHeight.formatPageSize()) }
    val width = widthText.toFloatOrNull()
    val height = heightText.toFloatOrNull()
    val valid = width != null && height != null &&
        width.isFinite() && height.isFinite() && width in 36f..20_000f && height in 36f..20_000f
    val selectedPreset = PAGE_SIZE_PRESETS.firstOrNull { preset ->
        width != null && height != null && abs(width - preset.width) < 0.5f && abs(height - preset.height) < 0.5f
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .widthIn(max = 560.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        TablerIcons.AspectRatio,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = if (pageCount == 1) "调整页面尺寸" else "调整 $pageCount 页尺寸",
                        modifier = Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss, enabled = !busy) {
                        Icon(TablerIcons.X, contentDescription = "关闭页面尺寸")
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PAGE_SIZE_PRESETS.forEach { preset ->
                            FilterChip(
                                selected = selectedPreset == preset,
                                onClick = {
                                    widthText = preset.width.formatPageSize()
                                    heightText = preset.height.formatPageSize()
                                },
                                enabled = !busy,
                                label = { Text(preset.label) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = widthText,
                            onValueChange = { widthText = it.filterPageSizeInput() },
                            modifier = Modifier.weight(1f),
                            label = { Text("宽度 (pt)") },
                            singleLine = true,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = width != null && (width !in 36f..20_000f),
                        )
                        IconButton(
                            onClick = {
                                val previous = widthText
                                widthText = heightText
                                heightText = previous
                            },
                            enabled = !busy,
                        ) {
                            Icon(TablerIcons.ArrowsLeftRight, contentDescription = "交换宽高")
                        }
                        OutlinedTextField(
                            value = heightText,
                            onValueChange = { heightText = it.filterPageSizeInput() },
                            modifier = Modifier.weight(1f),
                            label = { Text("高度 (pt)") },
                            singleLine = true,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = height != null && (height !in 36f..20_000f),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onApply(requireNotNull(width), requireNotNull(height)) },
                        enabled = !busy && valid,
                    ) {
                        Text("应用尺寸")
                    }
                }
            }
        }
    }
}

private fun String.filterPageSizeInput(): String {
    var decimalSeen = false
    return filter { char ->
        when {
            char.isDigit() -> true
            char == '.' && !decimalSeen -> {
                decimalSeen = true
                true
            }
            else -> false
        }
    }.take(8)
}

private fun Float.formatPageSize(): String = if (this % 1f == 0f) {
    toInt().toString()
} else {
    String.format(Locale.US, "%.1f", this)
}
