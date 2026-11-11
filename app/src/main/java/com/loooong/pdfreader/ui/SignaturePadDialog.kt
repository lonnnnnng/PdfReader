package com.loooong.pdfreader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

@Composable
fun SignaturePadDialog(
    onDismiss: () -> Unit,
    onConfirm: (List<List<Pair<Float, Float>>>) -> Unit,
) {
    var strokes by remember { mutableStateOf<List<List<Offset>>>(emptyList()) }
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    fun allStrokes(): List<List<Offset>> = buildList {
        addAll(strokes)
        if (currentStroke.size >= 2) add(currentStroke)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手写签名") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "请在下方空白区域书写签名",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { point -> currentStroke = listOf(point) },
                                onDrag = { change, _ ->
                                    currentStroke = currentStroke + change.position
                                },
                                onDragEnd = {
                                    if (currentStroke.size >= 2) strokes = strokes + listOf(currentStroke)
                                    currentStroke = emptyList()
                                },
                                onDragCancel = { currentStroke = emptyList() },
                            )
                        },
                ) {
                    Canvas(Modifier.matchParentSize()) {
                        allStrokes().forEach { points ->
                            if (points.size < 2) return@forEach
                            val path = Path().apply {
                                moveTo(points.first().x, points.first().y)
                                points.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                            drawPath(
                                path = path,
                                color = Color(0xFF202124),
                                style = Stroke(width = 3.5f),
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        enabled = strokes.isNotEmpty() || currentStroke.isNotEmpty(),
                        onClick = {
                            strokes = emptyList()
                            currentStroke = emptyList()
                        },
                    ) { Text("清除") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            TextButton(
                enabled = allStrokes().isNotEmpty() && canvasSize.width > 0 && canvasSize.height > 0,
                onClick = {
                    val width = canvasSize.width.toFloat()
                    val height = canvasSize.height.toFloat()
                    val normalized = allStrokes().map { stroke ->
                        stroke.map { point ->
                            (point.x / width).coerceIn(0f, 1f) to
                                (point.y / height).coerceIn(0f, 1f)
                        }
                    }
                    onConfirm(normalized)
                },
            ) { Text("使用签名") }
        },
    )
}
