package com.loooong.pdfreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.loooong.pdfreader.engine.PageDecorationPosition
import com.loooong.pdfreader.engine.PdfPageDecorationRequest
import com.loooong.pdfreader.engine.PdfPageNumberRequest
import com.loooong.pdfreader.engine.PdfWatermarkRequest
import com.loooong.pdfreader.parsePdfPageRanges

private enum class PageDecorationScope {
    CURRENT,
    ALL,
    RANGE,
}

@Composable
fun PdfPageDecorationDialog(
    pageCount: Int,
    currentPageIndex: Int,
    busy: Boolean,
    onApply: (PdfPageDecorationRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var scope by remember { mutableStateOf(PageDecorationScope.ALL) }
    var rangeText by remember(currentPageIndex) { mutableStateOf((currentPageIndex + 1).toString()) }

    var watermarkEnabled by remember { mutableStateOf(false) }
    var watermarkText by remember { mutableStateOf("") }
    var watermarkSize by remember { mutableStateOf("48") }
    var watermarkColor by remember { mutableStateOf("#64748B") }
    var watermarkOpacity by remember { mutableStateOf("22") }
    var watermarkRotation by remember { mutableStateOf("-35") }
    var watermarkMargin by remember { mutableStateOf("36") }
    var watermarkPosition by remember { mutableStateOf(PageDecorationPosition.CENTER) }

    var pageNumberEnabled by remember { mutableStateOf(true) }
    var pageNumberTemplate by remember { mutableStateOf("{page}") }
    var pageNumberStart by remember { mutableStateOf("1") }
    var pageNumberSize by remember { mutableStateOf("11") }
    var pageNumberColor by remember { mutableStateOf("#475569") }
    var pageNumberOpacity by remember { mutableStateOf("100") }
    var pageNumberMargin by remember { mutableStateOf("24") }
    var pageNumberPosition by remember { mutableStateOf(PageDecorationPosition.BOTTOM_CENTER) }

    val rangeResult = remember(scope, rangeText, pageCount, currentPageIndex) {
        runCatching {
            when (scope) {
                PageDecorationScope.CURRENT -> setOf(currentPageIndex)
                PageDecorationScope.ALL -> (0 until pageCount).toSet()
                PageDecorationScope.RANGE -> parsePdfPageRanges(rangeText, pageCount)
                    .flatMap(IntRange::toList)
                    .toSet()
            }
        }
    }
    val watermarkRequest = if (watermarkEnabled) {
        buildWatermarkRequest(
            text = watermarkText,
            fontSize = watermarkSize,
            color = watermarkColor,
            opacity = watermarkOpacity,
            rotation = watermarkRotation,
            position = watermarkPosition,
            margin = watermarkMargin,
        )
    } else {
        null
    }
    val pageNumberRequest = if (pageNumberEnabled) {
        buildPageNumberRequest(
            template = pageNumberTemplate,
            startNumber = pageNumberStart,
            fontSize = pageNumberSize,
            color = pageNumberColor,
            opacity = pageNumberOpacity,
            position = pageNumberPosition,
            margin = pageNumberMargin,
        )
    } else {
        null
    }
    val request = rangeResult.getOrNull()?.takeIf(Set<Int>::isNotEmpty)?.let { pages ->
        if (
            (watermarkEnabled && watermarkRequest == null) ||
            (pageNumberEnabled && pageNumberRequest == null) ||
            (!watermarkEnabled && !pageNumberEnabled)
        ) {
            null
        } else {
            PdfPageDecorationRequest(pages, watermarkRequest, pageNumberRequest)
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .widthIn(max = 680.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Column {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("水印与页码", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "一次应用到所选页面，并作为一条文档级操作支持撤销。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text("应用页面", style = MaterialTheme.typography.titleMedium)
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        // long: 小屏和分屏模式下改为纵向排列，三个范围入口不会因中文标签被压缩成难以点击的小块。
                        if (maxWidth < 360.dp) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ScopeChip(
                                    "当前页", PageDecorationScope.CURRENT, scope, busy,
                                    Modifier.fillMaxWidth(),
                                ) { scope = it }
                                ScopeChip(
                                    "全部页", PageDecorationScope.ALL, scope, busy,
                                    Modifier.fillMaxWidth(),
                                ) { scope = it }
                                ScopeChip(
                                    "指定页", PageDecorationScope.RANGE, scope, busy,
                                    Modifier.fillMaxWidth(),
                                ) { scope = it }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ScopeChip("当前页", PageDecorationScope.CURRENT, scope, busy) { scope = it }
                                ScopeChip("全部页", PageDecorationScope.ALL, scope, busy) { scope = it }
                                ScopeChip("指定页", PageDecorationScope.RANGE, scope, busy) { scope = it }
                            }
                        }
                    }
                    if (scope == PageDecorationScope.RANGE) {
                        OutlinedTextField(
                            value = rangeText,
                            onValueChange = { rangeText = it.take(200) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("页码范围，例如 1-3,5,8-") },
                            enabled = !busy,
                            singleLine = true,
                            isError = rangeResult.isFailure,
                            supportingText = rangeResult.exceptionOrNull()?.message?.let { message ->
                                { Text(message) }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        )
                    }

                    HorizontalDivider()
                    OptionSwitch(
                        title = "文字水印",
                        description = "支持中文、透明度、旋转和九宫格位置",
                        checked = watermarkEnabled,
                        enabled = !busy,
                        onCheckedChange = { watermarkEnabled = it },
                    )
                    if (watermarkEnabled) {
                        OutlinedTextField(
                            value = watermarkText,
                            onValueChange = { watermarkText = it.take(500) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("水印文字") },
                            enabled = !busy,
                            singleLine = true,
                            isError = watermarkText.isBlank(),
                        )
                        NumberPair(
                            firstLabel = "字号 (pt)",
                            firstValue = watermarkSize,
                            onFirstChange = { watermarkSize = it },
                            secondLabel = "不透明度 (%)",
                            secondValue = watermarkOpacity,
                            onSecondChange = { watermarkOpacity = it },
                            busy = busy,
                            firstError = decimalRangeError(watermarkSize, 4f, 400f, "请输入 4-400 之间的数字"),
                            secondError = decimalRangeError(watermarkOpacity, 0f, 100f, "请输入 0-100 之间的数字"),
                        )
                        NumberPair(
                            firstLabel = "旋转角度",
                            firstValue = watermarkRotation,
                            onFirstChange = { watermarkRotation = it },
                            secondLabel = "边距 (pt)",
                            secondValue = watermarkMargin,
                            onSecondChange = { watermarkMargin = it },
                            busy = busy,
                            allowSignedFirst = true,
                            firstError = finiteNumberError(watermarkRotation, "请输入有效旋转角度"),
                            secondError = nonNegativeNumberError(watermarkMargin, "边距不能小于 0"),
                        )
                        ColorField(watermarkColor, { watermarkColor = it }, busy)
                        PositionPicker(watermarkPosition, busy) { watermarkPosition = it }
                    }

                    HorizontalDivider()
                    OptionSwitch(
                        title = "页码",
                        description = "{page} 为顺序编号，{total} 为所选页面总数",
                        checked = pageNumberEnabled,
                        enabled = !busy,
                        onCheckedChange = { pageNumberEnabled = it },
                    )
                    if (pageNumberEnabled) {
                        OutlinedTextField(
                            value = pageNumberTemplate,
                            onValueChange = { pageNumberTemplate = it.take(200) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("页码模板") },
                            enabled = !busy,
                            singleLine = true,
                            isError = !validPageNumberTemplate(pageNumberTemplate),
                            supportingText = { Text("示例：第 {page} 页 / 共 {total} 页") },
                        )
                        NumberPair(
                            firstLabel = "所选第一页编号",
                            firstValue = pageNumberStart,
                            onFirstChange = { pageNumberStart = it },
                            secondLabel = "字号 (pt)",
                            secondValue = pageNumberSize,
                            onSecondChange = { pageNumberSize = it },
                            busy = busy,
                            allowSignedFirst = true,
                            integerOnlyFirst = true,
                            firstError = integerError(pageNumberStart, "请输入整数"),
                            secondError = decimalRangeError(pageNumberSize, 4f, 400f, "请输入 4-400 之间的数字"),
                        )
                        NumberPair(
                            firstLabel = "不透明度 (%)",
                            firstValue = pageNumberOpacity,
                            onFirstChange = { pageNumberOpacity = it },
                            secondLabel = "边距 (pt)",
                            secondValue = pageNumberMargin,
                            onSecondChange = { pageNumberMargin = it },
                            busy = busy,
                            firstError = decimalRangeError(pageNumberOpacity, 0f, 100f, "请输入 0-100 之间的数字"),
                            secondError = nonNegativeNumberError(pageNumberMargin, "边距不能小于 0"),
                        )
                        ColorField(pageNumberColor, { pageNumberColor = it }, busy)
                        PositionPicker(pageNumberPosition, busy) { pageNumberPosition = it }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = !busy && request != null,
                        onClick = { onApply(requireNotNull(request)) },
                    ) {
                        Text(if (busy) "正在应用" else "应用")
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeChip(
    label: String,
    value: PageDecorationScope,
    selected: PageDecorationScope,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (PageDecorationScope) -> Unit,
) {
    FilterChip(
        selected = value == selected,
        onClick = { onSelect(value) },
        enabled = !busy,
        modifier = modifier,
        label = { Text(label) },
    )
}

@Composable
private fun OptionSwitch(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun NumberPair(
    firstLabel: String,
    firstValue: String,
    onFirstChange: (String) -> Unit,
    secondLabel: String,
    secondValue: String,
    onSecondChange: (String) -> Unit,
    busy: Boolean,
    allowSignedFirst: Boolean = false,
    integerOnlyFirst: Boolean = false,
    firstError: String? = null,
    secondError: String? = null,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // long: 数值项包含单位和错误说明，窄屏下堆叠可避免两个输入框同时截断提示信息。
        if (maxWidth < 360.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DecorationNumberField(
                    label = firstLabel,
                    value = firstValue,
                    onValueChange = onFirstChange,
                    modifier = Modifier.fillMaxWidth(),
                    busy = busy,
                    allowSigned = allowSignedFirst,
                    integerOnly = integerOnlyFirst,
                    error = firstError,
                )
                DecorationNumberField(
                    label = secondLabel,
                    value = secondValue,
                    onValueChange = onSecondChange,
                    modifier = Modifier.fillMaxWidth(),
                    busy = busy,
                    error = secondError,
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DecorationNumberField(
                    label = firstLabel,
                    value = firstValue,
                    onValueChange = onFirstChange,
                    modifier = Modifier.weight(1f),
                    busy = busy,
                    allowSigned = allowSignedFirst,
                    integerOnly = integerOnlyFirst,
                    error = firstError,
                )
                DecorationNumberField(
                    label = secondLabel,
                    value = secondValue,
                    onValueChange = onSecondChange,
                    modifier = Modifier.weight(1f),
                    busy = busy,
                    error = secondError,
                )
            }
        }
    }
}

@Composable
private fun DecorationNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean,
    allowSigned: Boolean = false,
    integerOnly: Boolean = false,
    error: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            onValueChange(input.filterNumberInput(allowSigned, allowDecimal = !integerOnly).take(12))
        },
        modifier = modifier,
        label = { Text(label) },
        enabled = !busy,
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { message -> { Text(message) } },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (allowSigned) KeyboardType.Ascii else KeyboardType.Decimal,
        ),
    )
}

@Composable
private fun ColorField(value: String, onValueChange: (String) -> Unit, busy: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(7)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("颜色 (#RRGGBB)") },
        enabled = !busy,
        singleLine = true,
        isError = parseColor(value) == null,
        supportingText = if (parseColor(value) == null) {
            { Text("请输入 #RRGGBB 格式的颜色") }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
    )
}

@Composable
private fun PositionPicker(
    selected: PageDecorationPosition,
    busy: Boolean,
    onSelect: (PageDecorationPosition) -> Unit,
) {
    Text("位置", style = MaterialTheme.typography.labelLarge)
    POSITION_ROWS.forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.forEach { position ->
                FilterChip(
                    selected = selected == position,
                    onClick = { onSelect(position) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    label = {
                        Text(POSITION_LABELS.getValue(position), maxLines = 1)
                    },
                )
            }
        }
    }
}

private fun buildWatermarkRequest(
    text: String,
    fontSize: String,
    color: String,
    opacity: String,
    rotation: String,
    position: PageDecorationPosition,
    margin: String,
): PdfWatermarkRequest? {
    val parsedSize = fontSize.toFloatOrNull()?.takeIf { it.isFinite() && it in 4f..400f } ?: return null
    val parsedOpacity = opacity.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..100f } ?: return null
    val parsedRotation = rotation.toFloatOrNull()?.takeIf(Float::isFinite) ?: return null
    val parsedMargin = margin.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f } ?: return null
    val parsedColor = parseColor(color) ?: return null
    val normalizedText = text.trim().takeIf(String::isNotEmpty) ?: return null
    return PdfWatermarkRequest(
        text = normalizedText,
        fontSize = parsedSize,
        colorArgb = parsedColor,
        opacity = parsedOpacity / 100f,
        rotationDegrees = parsedRotation,
        position = position,
        margin = parsedMargin,
    )
}

private fun buildPageNumberRequest(
    template: String,
    startNumber: String,
    fontSize: String,
    color: String,
    opacity: String,
    position: PageDecorationPosition,
    margin: String,
): PdfPageNumberRequest? {
    val normalizedTemplate = template.trim().takeIf(::validPageNumberTemplate) ?: return null
    val parsedStart = startNumber.toIntOrNull() ?: return null
    val parsedSize = fontSize.toFloatOrNull()?.takeIf { it.isFinite() && it in 4f..400f } ?: return null
    val parsedOpacity = opacity.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..100f } ?: return null
    val parsedMargin = margin.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f } ?: return null
    val parsedColor = parseColor(color) ?: return null
    return PdfPageNumberRequest(
        template = normalizedTemplate,
        startNumber = parsedStart,
        fontSize = parsedSize,
        colorArgb = parsedColor,
        opacity = parsedOpacity / 100f,
        position = position,
        margin = parsedMargin,
    )
}

private fun validPageNumberTemplate(value: String): Boolean {
    val template = value.trim()
    if (template.isEmpty() || (!template.contains("{page}") && !template.contains("{total}"))) return false
    val remaining = template.replace("{page}", "").replace("{total}", "")
    return '{' !in remaining && '}' !in remaining
}

private fun parseColor(value: String): Int? {
    val digits = value.trim().removePrefix("#")
    if (digits.length != 6) return null
    return digits.toIntOrNull(16)?.let { rgb -> (0xFF shl 24) or rgb }
}

private fun String.filterNumberInput(allowSigned: Boolean, allowDecimal: Boolean): String {
    var decimalSeen = false
    return filterIndexed { index, char ->
        when {
            char.isDigit() -> true
            allowDecimal && char == '.' && !decimalSeen -> {
                decimalSeen = true
                true
            }
            allowSigned && char == '-' && index == 0 -> true
            else -> false
        }
    }
}

private fun decimalRangeError(value: String, minimum: Float, maximum: Float, message: String): String? =
    value.toFloatOrNull()?.takeIf { it.isFinite() && it in minimum..maximum }?.let { null } ?: message

private fun nonNegativeNumberError(value: String, message: String): String? =
    value.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }?.let { null } ?: message

private fun finiteNumberError(value: String, message: String): String? =
    value.toFloatOrNull()?.takeIf(Float::isFinite)?.let { null } ?: message

private fun integerError(value: String, message: String): String? =
    value.toIntOrNull()?.let { null } ?: message

private val POSITION_ROWS = listOf(
    listOf(PageDecorationPosition.TOP_LEFT, PageDecorationPosition.TOP_CENTER, PageDecorationPosition.TOP_RIGHT),
    listOf(PageDecorationPosition.CENTER_LEFT, PageDecorationPosition.CENTER, PageDecorationPosition.CENTER_RIGHT),
    listOf(PageDecorationPosition.BOTTOM_LEFT, PageDecorationPosition.BOTTOM_CENTER, PageDecorationPosition.BOTTOM_RIGHT),
)

private val POSITION_LABELS = mapOf(
    PageDecorationPosition.TOP_LEFT to "左上",
    PageDecorationPosition.TOP_CENTER to "上中",
    PageDecorationPosition.TOP_RIGHT to "右上",
    PageDecorationPosition.CENTER_LEFT to "左中",
    PageDecorationPosition.CENTER to "居中",
    PageDecorationPosition.CENTER_RIGHT to "右中",
    PageDecorationPosition.BOTTOM_LEFT to "左下",
    PageDecorationPosition.BOTTOM_CENTER to "下中",
    PageDecorationPosition.BOTTOM_RIGHT to "右下",
)
