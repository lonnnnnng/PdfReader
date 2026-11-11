package com.loooong.pdfreader.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.loooong.pdfreader.AUTO_FONT_ID
import com.loooong.pdfreader.EditTarget
import com.loooong.pdfreader.FontOption
import com.loooong.pdfreader.FontSource
import com.loooong.pdfreader.engine.EditRequest
import com.loooong.pdfreader.engine.ImageCropRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElementEditSheet(
    target: EditTarget,
    onApply: (EditRequest, Uri?) -> Unit,
    onImportFont: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val caps = target.caps
    val id = target.node.id
    var x by remember(id) { mutableStateOf(fmt(target.x)) }
    var y by remember(id) { mutableStateOf(fmt(target.y)) }
    var w by remember(id) { mutableStateOf(fmt(target.w)) }
    var h by remember(id) { mutableStateOf(fmt(target.h)) }
    var fill by remember(id) { mutableStateOf(target.fillArgb?.let(::argbToHex) ?: "") }
    var stroke by remember(id) { mutableStateOf(target.strokeArgb?.let(::argbToHex) ?: "") }
    var text by remember(id) { mutableStateOf(target.text ?: "") }
    var fontSize by remember(id) { mutableStateOf(fmt(target.fontSize)) }
    var characterSpacing by remember(id) { mutableStateOf(fmt(target.characterSpacing)) }
    var lineLeading by remember(id) {
        mutableStateOf(fmt(target.lineLeading.takeIf { it > 0f } ?: (target.fontSize * 1.2f)))
    }
    var opacityPercent by remember(id) { mutableStateOf(fmt(target.opacity * 100f)) }
    var lineWidth by remember(id) { mutableStateOf(fmt(target.lineWidth)) }
    var lineCap by remember(id) { mutableStateOf(target.lineCap) }
    var lineJoin by remember(id) { mutableStateOf(target.lineJoin) }
    var miterLimit by remember(id) { mutableStateOf(fmt(target.miterLimit)) }
    var replacementImageUri by remember(id) { mutableStateOf<Uri?>(null) }
    var cropLeft by remember(id) { mutableStateOf(fmt(target.cropLeft * 100f)) }
    var cropTop by remember(id) { mutableStateOf(fmt(target.cropTop * 100f)) }
    var cropRight by remember(id) { mutableStateOf(fmt(target.cropRight * 100f)) }
    var cropBottom by remember(id) { mutableStateOf(fmt(target.cropBottom * 100f)) }
    var useFallback by remember(id) { mutableStateOf(true) }
    var fontId by remember(id) { mutableStateOf<String?>(AUTO_FONT_ID) }
    val xInvalid = caps.canGeom && parseFiniteFloat(x) == null
    val yInvalid = caps.canGeom && parseFiniteFloat(y) == null
    val widthInvalid = caps.canGeom && parsePositiveFloat(w) == null
    val heightInvalid = caps.canGeom && parsePositiveFloat(h) == null
    val fillInvalid = caps.canFill && fill.isNotBlank() && parseHex(fill) == null
    val strokeInvalid = caps.canStroke && stroke.isNotBlank() && parseHex(stroke) == null
    val fontSizeInvalid = caps.canText && parsePositiveFloat(fontSize) == null
    val characterSpacingInvalid = caps.canText && parseFiniteFloat(characterSpacing) == null
    val lineLeadingInvalid = caps.canText && parsePositiveFloat(lineLeading) == null
    val opacityInvalid = caps.canOpacity && parsePercent(opacityPercent) == null
    val lineWidthInvalid = caps.canStrokeStyle && parsePositiveFloat(lineWidth) == null
    val miterLimitInvalid = caps.canStrokeStyle && lineJoin == 0 &&
        (parsePositiveFloat(miterLimit)?.takeIf { it >= 1f } == null)
    val cropLeftValue = parsePercent(cropLeft)
    val cropTopValue = parsePercent(cropTop)
    val cropRightValue = parsePercent(cropRight)
    val cropBottomValue = parsePercent(cropBottom)
    val horizontalCropInvalid = caps.canCropImage && (
        cropLeftValue == null || cropRightValue == null || cropLeftValue + cropRightValue >= 100f
    )
    val verticalCropInvalid = caps.canCropImage && (
        cropTopValue == null || cropBottomValue == null || cropTopValue + cropBottomValue >= 100f
    )
    val formValid = !xInvalid && !yInvalid && !widthInvalid && !heightInvalid &&
        !fillInvalid && !strokeInvalid && !fontSizeInvalid && !characterSpacingInvalid &&
        !lineLeadingInvalid && !opacityInvalid && !lineWidthInvalid && !miterLimitInvalid &&
        !horizontalCropInvalid && !verticalCropInvalid

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? -> if (uri != null) onImportFont(uri) }
    val replacementImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> if (uri != null) replacementImageUri = uri }

    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                target.node.label,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (caps.canGeom) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("位置", style = MaterialTheme.typography.labelLarge)
                    AdaptiveNumberPair(
                        firstLabel = "X",
                        firstValue = x,
                        firstInvalid = xInvalid,
                        onFirstChange = { x = it },
                        secondLabel = "Y",
                        secondValue = y,
                        secondInvalid = yInvalid,
                        onSecondChange = { y = it },
                        errorMessage = "请输入有效数字",
                    )
                    Text(
                        "单位为 PDF 点，原点位于左下角",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("尺寸", style = MaterialTheme.typography.labelLarge)
                    AdaptiveNumberPair(
                        firstLabel = "宽",
                        firstValue = w,
                        firstInvalid = widthInvalid,
                        onFirstChange = { w = it },
                        secondLabel = "高",
                        secondValue = h,
                        secondInvalid = heightInvalid,
                        onSecondChange = { h = it },
                        errorMessage = "请输入大于 0 的数字",
                    )
                }
            }

            if (caps.canFill) {
                OutlinedTextField(
                    value = fill,
                    onValueChange = { fill = it },
                    label = { Text("填充颜色  #RRGGBB") },
                    singleLine = true,
                    isError = fillInvalid,
                    supportingText = if (fillInvalid) {
                        { Text("请输入 #RRGGBB 格式的颜色") }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (caps.canStroke) {
                OutlinedTextField(
                    value = stroke,
                    onValueChange = { stroke = it },
                    label = { Text("描边颜色  #RRGGBB") },
                    singleLine = true,
                    isError = strokeInvalid,
                    supportingText = if (strokeInvalid) {
                        { Text("请输入 #RRGGBB 格式的颜色") }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (caps.canStrokeStyle) {
                Text("描边样式", style = MaterialTheme.typography.labelLarge)
                NumberField(
                    label = "线宽",
                    value = lineWidth,
                    onValueChange = { lineWidth = it },
                    isError = lineWidthInvalid,
                    errorMessage = "请输入大于 0 的数字",
                    modifier = Modifier.fillMaxWidth(),
                )
                StrokeOptionPair(
                    lineCap = lineCap,
                    onLineCapChange = { lineCap = it },
                    lineJoin = lineJoin,
                    onLineJoinChange = { lineJoin = it },
                )
                if (lineJoin == 0) {
                    NumberField(
                        label = "斜接限制",
                        value = miterLimit,
                        onValueChange = { miterLimit = it },
                        isError = miterLimitInvalid,
                        errorMessage = "请输入不小于 1 的数字",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (target.node.dashPattern.isNotEmpty()) {
                    Text(
                        "虚线：${target.node.dashPattern.joinToString(", ") { fmt(it) }}，相位 ${fmt(target.node.dashPhase)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val cs = target.colorSpace
            if ((caps.canFill || caps.canStroke) && cs != null && cs != "RGB") {
                Text(
                    "色彩空间：${localizedColorSpace(cs)}。编辑后将以 RGB 保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (caps.canText) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("文本") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("文字排版", style = MaterialTheme.typography.labelLarge)
                AdaptiveNumberPair(
                    firstLabel = "字号",
                    firstValue = fontSize,
                    firstInvalid = fontSizeInvalid,
                    onFirstChange = { fontSize = it },
                    secondLabel = "字距",
                    secondValue = characterSpacing,
                    secondInvalid = characterSpacingInvalid,
                    onSecondChange = { characterSpacing = it },
                    errorMessage = "请输入有效数值",
                )
                NumberField(
                    label = "行距",
                    value = lineLeading,
                    onValueChange = { lineLeading = it },
                    isError = lineLeadingInvalid,
                    errorMessage = "请输入大于 0 的数字",
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = useFallback,
                            role = Role.Checkbox,
                            onValueChange = { on ->
                                useFallback = on
                                if (on && fontId == null) fontId = AUTO_FONT_ID
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = useFallback,
                        onCheckedChange = null,
                    )
                    Text("使用备用字体")
                }
                if (useFallback) {
                    FontPicker(
                        options = target.fontOptions,
                        selectedId = fontId ?: AUTO_FONT_ID,
                        onSelect = { fontId = it },
                        onAddCustom = { importLauncher.launch("*/*") },
                    )
                }
                Text(
                    if (useFallback) {
                        "自动选择与原字体最接近的字体，也可以从列表中选择或添加自定义字体。"
                    } else {
                        "使用元素原字体重新编码，仅在识别到完全匹配的字体时自动替换。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (caps.canOpacity) {
                NumberField(
                    label = "不透明度 (%)",
                    value = opacityPercent,
                    onValueChange = { opacityPercent = it },
                    isError = opacityInvalid,
                    errorMessage = "请输入 0 到 100 之间的数字",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (target.blendMode != "Normal") {
                    Text(
                        "当前混合模式：${target.blendMode}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (caps.canReplaceImage) {
                OutlinedButton(
                    onClick = { replacementImageLauncher.launch(arrayOf("image/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (replacementImageUri == null) "替换图片" else "已选择新图片")
                }
                replacementImageUri?.lastPathSegment?.let { name ->
                    Text(
                        name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (caps.canCropImage) {
                Text("图片裁剪 (%)", style = MaterialTheme.typography.labelLarge)
                AdaptiveNumberPair(
                    firstLabel = "左",
                    firstValue = cropLeft,
                    firstInvalid = horizontalCropInvalid,
                    onFirstChange = { cropLeft = it },
                    secondLabel = "右",
                    secondValue = cropRight,
                    secondInvalid = horizontalCropInvalid,
                    onSecondChange = { cropRight = it },
                    errorMessage = "左右裁剪之和必须小于 100",
                )
                AdaptiveNumberPair(
                    firstLabel = "上",
                    firstValue = cropTop,
                    firstInvalid = verticalCropInvalid,
                    onFirstChange = { cropTop = it },
                    secondLabel = "下",
                    secondValue = cropBottom,
                    secondInvalid = verticalCropInvalid,
                    onSecondChange = { cropBottom = it },
                    errorMessage = "上下裁剪之和必须小于 100",
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(
                    enabled = formValid,
                    onClick = {
                        onApply(
                            buildRequest(
                                target, x, y, w, h, fill, stroke, text,
                                if (useFallback) fontId ?: AUTO_FONT_ID else null,
                                fontSize, characterSpacing, lineLeading, opacityPercent,
                                lineWidth, lineCap, lineJoin, miterLimit,
                                cropLeft, cropTop, cropRight, cropBottom,
                            ),
                            replacementImageUri,
                        )
                    },
                ) { Text("应用") }
            }
        }
    }
}

@Composable
private fun AdaptiveNumberPair(
    firstLabel: String,
    firstValue: String,
    firstInvalid: Boolean,
    onFirstChange: (String) -> Unit,
    secondLabel: String,
    secondValue: String,
    secondInvalid: Boolean,
    onSecondChange: (String) -> Unit,
    errorMessage: String,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // long: 分屏和手机横屏可用宽度不足时改为单列，避免字段标签与错误文案互相挤压。
        if (maxWidth < 300.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(firstLabel, firstValue, onFirstChange, firstInvalid, errorMessage)
                NumberField(secondLabel, secondValue, onSecondChange, secondInvalid, errorMessage)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    firstLabel,
                    firstValue,
                    onFirstChange,
                    firstInvalid,
                    errorMessage,
                    Modifier.weight(1f),
                )
                NumberField(
                    secondLabel,
                    secondValue,
                    onSecondChange,
                    secondInvalid,
                    errorMessage,
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StrokeOptionPair(
    lineCap: Int,
    onLineCapChange: (Int) -> Unit,
    lineJoin: Int,
    onLineJoinChange: (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 300.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StrokeOptionPicker(
                    "端点", lineCap, listOf(0 to "平直", 1 to "圆形", 2 to "方形"),
                    onLineCapChange, Modifier.fillMaxWidth(),
                )
                StrokeOptionPicker(
                    "连接", lineJoin, listOf(0 to "斜接", 1 to "圆角", 2 to "斜角"),
                    onLineJoinChange, Modifier.fillMaxWidth(),
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StrokeOptionPicker(
                    label = "端点",
                    selected = lineCap,
                    options = listOf(0 to "平直", 1 to "圆形", 2 to "方形"),
                    onSelect = onLineCapChange,
                    modifier = Modifier.weight(1f),
                )
                StrokeOptionPicker(
                    label = "连接",
                    selected = lineJoin,
                    options = listOf(0 to "斜接", 1 to "圆角", 2 to "斜角"),
                    onSelect = onLineJoinChange,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StrokeOptionPicker(
    label: String,
    selected: Int,
    options: List<Pair<Int, String>>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: options.first().second
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    errorMessage: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = if (isError) ({ Text(errorMessage) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontPicker(
    options: List<FontOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onAddCustom: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (selectedId == AUTO_FONT_ID) {
        "自动（匹配原字体）"
    } else {
        options.firstOrNull { it.id == selectedId }?.let(::fontLabel) ?: "自动（匹配原字体）"
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("备用字体") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("自动（匹配原字体）") },
                onClick = { onSelect(AUTO_FONT_ID); expanded = false },
            )
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(fontLabel(opt)) },
                    onClick = { onSelect(opt.id); expanded = false },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("添加自定义字体") },
                onClick = { expanded = false; onAddCustom() },
            )
        }
    }
}

private fun fontLabel(o: FontOption): String = when (o.source) {
    FontSource.SYSTEM -> "${o.displayName}（系统）"
    FontSource.CUSTOM -> "${o.displayName}（自定义）"
    FontSource.BUNDLED -> o.displayName
}

private fun localizedColorSpace(value: String): String = when {
    value == "Gray" -> "灰度"
    value == "Pattern" -> "图案"
    value == "Color space" -> "未知色彩空间"
    value.startsWith("Separation") -> value.replaceFirst("Separation", "专色")
    else -> value
}

private fun buildRequest(
    target: EditTarget,
    x: String,
    y: String,
    w: String,
    h: String,
    fill: String,
    stroke: String,
    text: String,
    fontId: String?,
    fontSize: String,
    characterSpacing: String,
    lineLeading: String,
    opacityPercent: String,
    lineWidth: String,
    lineCap: Int,
    lineJoin: Int,
    miterLimit: String,
    cropLeft: String,
    cropTop: String,
    cropRight: String,
    cropBottom: String,
): EditRequest {
    val caps = target.caps
    val nx = x.toFloatOrNull() ?: target.x
    val ny = y.toFloatOrNull() ?: target.y
    val nw = w.toFloatOrNull() ?: target.w
    val nh = h.toFloatOrNull() ?: target.h
    val parsedFill = if (caps.canFill) parseHex(fill) else null
    val parsedStroke = if (caps.canStroke) parseHex(stroke) else null
    val textChanged = caps.canText && text != (target.text ?: "")
    val fontChosen = caps.canText && fontId != null
    val parsedFontSize = parsePositiveFloat(fontSize)
    val parsedCharacterSpacing = parseFiniteFloat(characterSpacing)
    val parsedLineLeading = parsePositiveFloat(lineLeading)
    val parsedOpacity = parsePercent(opacityPercent)?.div(100f)
    val parsedLineWidth = if (caps.canStrokeStyle) parsePositiveFloat(lineWidth) else null
    val parsedMiterLimit = if (caps.canStrokeStyle) parsePositiveFloat(miterLimit) else null
    val parsedCrop = if (caps.canCropImage) {
        ImageCropRequest(
            left = (parsePercent(cropLeft) ?: target.cropLeft * 100f) / 100f,
            top = (parsePercent(cropTop) ?: target.cropTop * 100f) / 100f,
            right = (parsePercent(cropRight) ?: target.cropRight * 100f) / 100f,
            bottom = (parsePercent(cropBottom) ?: target.cropBottom * 100f) / 100f,
        )
    } else {
        null
    }
    val cropChanged = parsedCrop != null && (
        !nearlyEqual(parsedCrop.left, target.cropLeft) ||
            !nearlyEqual(parsedCrop.top, target.cropTop) ||
            !nearlyEqual(parsedCrop.right, target.cropRight) ||
            !nearlyEqual(parsedCrop.bottom, target.cropBottom)
    )
    return EditRequest(
        dx = if (caps.canGeom) nx - target.x else 0f,
        dy = if (caps.canGeom) ny - target.y else 0f,
        scaleX = if (caps.canGeom && target.w != 0f && nw > 0f) nw / target.w else 1f,
        scaleY = if (caps.canGeom && target.h != 0f && nh > 0f) nh / target.h else 1f,
        fillArgb = if (parsedFill != null && parsedFill != target.fillArgb) parsedFill else null,
        strokeArgb = if (parsedStroke != null && parsedStroke != target.strokeArgb) parsedStroke else null,
        newText = if (textChanged || fontChosen) text else null,
        fontEntryId = if (fontChosen) fontId else null,
        fontSize = parsedFontSize?.takeIf { !nearlyEqual(it, target.fontSize) },
        characterSpacing = parsedCharacterSpacing?.takeIf {
            !nearlyEqual(it, target.characterSpacing)
        },
        lineLeading = parsedLineLeading?.takeIf { !nearlyEqual(it, target.lineLeading) },
        opacity = parsedOpacity?.takeIf { !nearlyEqual(it, target.opacity) },
        lineWidth = parsedLineWidth?.takeIf { !nearlyEqual(it, target.lineWidth) },
        lineCap = lineCap.takeIf { caps.canStrokeStyle && it != target.lineCap },
        lineJoin = lineJoin.takeIf { caps.canStrokeStyle && it != target.lineJoin },
        miterLimit = parsedMiterLimit?.takeIf { !nearlyEqual(it, target.miterLimit) },
        imageCrop = parsedCrop?.takeIf { cropChanged },
    )
}

private fun fmt(v: Float): String =
    if (v == v.toLong().toFloat()) {
        v.toLong().toString()
    } else {
        String.format(java.util.Locale.US, "%.2f", v)
    }

private fun argbToHex(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)

private fun parseHex(s: String): Int? {
    val t = s.trim().removePrefix("#")
    if (t.length != 6) return null
    val v = t.toIntOrNull(16) ?: return null
    return (0xFF shl 24) or v
}

private fun parseFiniteFloat(value: String): Float? =
    value.toFloatOrNull()?.takeIf { it.isFinite() }

private fun parsePositiveFloat(value: String): Float? =
    parseFiniteFloat(value)?.takeIf { it > 0f }

private fun parsePercent(value: String): Float? =
    parseFiniteFloat(value)?.takeIf { it in 0f..100f }

private fun nearlyEqual(left: Float, right: Float): Boolean = kotlin.math.abs(left - right) < 1e-4f
