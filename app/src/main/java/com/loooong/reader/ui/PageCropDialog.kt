package com.loooong.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.loooong.reader.engine.PageCropMargins
import com.loooong.reader.engine.PageGeometryInfo
import compose.icons.TablerIcons
import compose.icons.tablericons.Crop
import compose.icons.tablericons.X
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PageCropDialog(
    pageNumber: Int,
    pageCount: Int = 1,
    geometry: PageGeometryInfo,
    busy: Boolean,
    onApply: (PageCropMargins) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = geometry.margins
    var left by remember(geometry) { mutableFloatStateOf(initial.left) }
    var top by remember(geometry) { mutableFloatStateOf(initial.top) }
    var right by remember(geometry) { mutableFloatStateOf(initial.right) }
    var bottom by remember(geometry) { mutableFloatStateOf(initial.bottom) }
    val margins = PageCropMargins(left, top, right, bottom)
    val changed = margins.differsFrom(initial)
    val visibleWidth = geometry.mediaWidth - left - right
    val visibleHeight = geometry.mediaHeight - top - bottom

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .widthIn(max = 620.dp)
                .heightIn(max = 760.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Column {
                PageCropHeader(
                    pageNumber = pageNumber,
                    pageCount = pageCount,
                    geometry = geometry,
                    onDismiss = onDismiss,
                    enabled = !busy,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PageCropPreview(
                        geometry = geometry,
                        margins = margins,
                        visibleWidth = visibleWidth,
                        visibleHeight = visibleHeight,
                    )
                    CropPresets(
                        enabled = !busy,
                        onPreset = { ratio ->
                            left = (geometry.mediaWidth * ratio).roundedPoint()
                            right = left
                            top = (geometry.mediaHeight * ratio).roundedPoint()
                            bottom = top
                        },
                    )
                    CropMarginSlider(
                        label = "左边距",
                        value = left,
                        max = geometry.mediaWidth - right - MIN_VISIBLE_POINTS,
                        enabled = !busy,
                        onValueChange = { left = it.roundedPoint() },
                    )
                    CropMarginSlider(
                        label = "上边距",
                        value = top,
                        max = geometry.mediaHeight - bottom - MIN_VISIBLE_POINTS,
                        enabled = !busy,
                        onValueChange = { top = it.roundedPoint() },
                    )
                    CropMarginSlider(
                        label = "右边距",
                        value = right,
                        max = geometry.mediaWidth - left - MIN_VISIBLE_POINTS,
                        enabled = !busy,
                        onValueChange = { right = it.roundedPoint() },
                    )
                    CropMarginSlider(
                        label = "下边距",
                        value = bottom,
                        max = geometry.mediaHeight - top - MIN_VISIBLE_POINTS,
                        enabled = !busy,
                        onValueChange = { bottom = it.roundedPoint() },
                    )
                    Spacer(Modifier.size(4.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (geometry.isCropped) {
                        TextButton(onClick = onReset, enabled = !busy) {
                            Text("恢复完整页面")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss, enabled = !busy) {
                        Text("取消")
                    }
                    Button(
                        onClick = { onApply(margins) },
                        enabled = !busy && changed && visibleWidth >= MIN_VISIBLE_POINTS &&
                            visibleHeight >= MIN_VISIBLE_POINTS,
                    ) {
                        Text("应用裁剪")
                    }
                }
            }
        }
    }
}

@Composable
private fun PageCropHeader(
    pageNumber: Int,
    pageCount: Int,
    geometry: PageGeometryInfo,
    onDismiss: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = TablerIcons.Crop,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                if (pageCount == 1) "裁剪第 $pageNumber 页" else "裁剪所选 $pageCount 页",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "原始尺寸 ${geometry.mediaWidth.formatPoint()} x ${geometry.mediaHeight.formatPoint()} pt",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onDismiss, enabled = enabled) {
            Icon(TablerIcons.X, contentDescription = "关闭页面裁剪")
        }
    }
}

@Composable
private fun PageCropPreview(
    geometry: PageGeometryInfo,
    margins: PageCropMargins,
    visibleWidth: Float,
    visibleHeight: Float,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier = Modifier
                .heightIn(max = 230.dp)
                .widthIn(max = 300.dp)
                .aspectRatio((geometry.mediaWidth / geometry.mediaHeight).coerceAtLeast(0.1f))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
        ) {
            // long: 示意框按 MediaBox 比例映射四边距，用户无需换算 PDF 坐标即可判断剩余可见区域。
            val leftPadding = maxWidth * (margins.left / geometry.mediaWidth)
            val rightPadding = maxWidth * (margins.right / geometry.mediaWidth)
            val topPadding = maxHeight * (margins.top / geometry.mediaHeight)
            val bottomPadding = maxHeight * (margins.bottom / geometry.mediaHeight)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = leftPadding,
                        top = topPadding,
                        end = rightPadding,
                        bottom = bottomPadding,
                    )
                    .background(Color.White)
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "可见区域",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            text = "${visibleWidth.formatPoint()} x ${visibleHeight.formatPoint()} pt",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CropPresets(enabled: Boolean, onPreset: (Float) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        listOf("窄边 3%" to 0.03f, "标准 5%" to 0.05f, "宽边 10%" to 0.10f).forEach { preset ->
            FilledTonalButton(
                onClick = { onPreset(preset.second) },
                enabled = enabled,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) {
                Text(preset.first)
            }
        }
    }
}

@Composable
private fun CropMarginSlider(
    label: String,
    value: Float,
    max: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    val validMax = max.coerceAtLeast(0f)
    val sliderMax = validMax.coerceAtLeast(MIN_VISIBLE_POINTS)
    val displayedValue = value.coerceIn(0f, validMax)
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Text(
                text = "${displayedValue.formatPoint()} pt",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = displayedValue,
            onValueChange = onValueChange,
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = "${displayedValue.formatPoint()} pt"
            },
            enabled = enabled && validMax >= MIN_VISIBLE_POINTS,
            valueRange = 0f..sliderMax,
        )
    }
}

private fun PageCropMargins.differsFrom(other: PageCropMargins): Boolean =
    abs(left - other.left) > 0.01f ||
        abs(top - other.top) > 0.01f ||
        abs(right - other.right) > 0.01f ||
        abs(bottom - other.bottom) > 0.01f

private fun Float.roundedPoint(): Float = roundToInt().toFloat()

private fun Float.formatPoint(): String = String.format(Locale.US, "%.1f", this)

private const val MIN_VISIBLE_POINTS = 1f
