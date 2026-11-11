package com.loooong.pdfreader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.loooong.pdfreader.engine.PdfAnnotationInfo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import kotlin.math.roundToInt

@Composable
fun AnnotationEditDialog(
    annotation: PdfAnnotationInfo,
    busy: Boolean,
    onApply: (colorArgb: Int, opacity: Float, contents: String, fontSize: Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var colorArgb by remember(annotation.index, annotation.colorArgb) {
        mutableIntStateOf(annotation.colorArgb)
    }
    var opacity by remember(annotation.index, annotation.opacity) {
        mutableFloatStateOf(annotation.opacity)
    }
    var contents by remember(annotation.index, annotation.contents) {
        mutableStateOf(annotation.contents.orEmpty())
    }
    var fontSize by remember(annotation.index, annotation.fontSize) {
        mutableFloatStateOf(annotation.fontSize ?: DEFAULT_ANNOTATION_FONT_SIZE)
    }
    val colors = remember(annotation.colorArgb) {
        (ANNOTATION_COLORS + annotation.colorArgb).distinct()
    }
    val freeText = annotation.subtype == PDAnnotationMarkup.SUB_TYPE_FREETEXT

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("编辑批注") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("颜色", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    colors.forEach { candidate ->
                        val selected = colorArgb == candidate
                        Surface(
                            onClick = { colorArgb = candidate },
                            enabled = !busy,
                            modifier = Modifier
                                .size(36.dp)
                                .semantics {
                                    this.selected = selected
                                    contentDescription = "批注颜色 ${candidate.toHexColor()}"
                                },
                            shape = CircleShape,
                            color = Color(candidate),
                            border = BorderStroke(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                            ),
                        ) {}
                    }
                }
                Text(
                    "透明度 ${(opacity * 100f).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    valueRange = 0.1f..1f,
                    steps = 8,
                    enabled = !busy,
                )
                if (freeText) {
                    Text(
                        "字号 ${fontSize.roundToInt()} pt",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(
                        value = fontSize,
                        onValueChange = { fontSize = it },
                        valueRange = 8f..36f,
                        steps = 27,
                        enabled = !busy,
                    )
                }
                OutlinedTextField(
                    value = contents,
                    onValueChange = { contents = it.take(MAX_ANNOTATION_CONTENT_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (freeText) "自由文本" else "批注内容") },
                    minLines = if (freeText) 3 else 2,
                    maxLines = 8,
                    enabled = !busy,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(colorArgb, opacity, contents, fontSize) },
                enabled = !busy && annotation.editable && (!freeText || contents.isNotBlank()),
            ) {
                Text("应用")
            }
        },
    )
}

private fun Int.toHexColor(): String = String.format("#%06X", this and 0xFFFFFF)

private val ANNOTATION_COLORS = listOf(
    0xFFFFD54F.toInt(),
    0xFFEF5350.toInt(),
    0xFF1565C0.toInt(),
    0xFF2E7D32.toInt(),
    0xFF7B1FA2.toInt(),
    0xFF202124.toInt(),
)

private const val MAX_ANNOTATION_CONTENT_LENGTH = 4_000
private const val DEFAULT_ANNOTATION_FONT_SIZE = 12f
