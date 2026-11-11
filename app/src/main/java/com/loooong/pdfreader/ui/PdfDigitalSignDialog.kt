package com.loooong.pdfreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.loooong.pdfreader.engine.PdfCertificateStatus
import com.loooong.pdfreader.engine.PdfDigitalSignRequest
import com.loooong.pdfreader.engine.PdfPkcs12EntryInfo
import com.loooong.pdfreader.engine.PdfPkcs12Info
import com.loooong.pdfreader.engine.PdfSignaturePlacement
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PdfDigitalSignDialog(
    certificateFileName: String?,
    certificateInfo: PdfPkcs12Info?,
    certificateLoading: Boolean,
    certificateError: String?,
    pageCount: Int,
    currentPageIndex: Int,
    busy: Boolean,
    onChooseCertificate: () -> Unit,
    onInspectCertificate: (String) -> Unit,
    onSign: (PdfDigitalSignRequest, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember(certificateFileName) { mutableStateOf("") }
    var inspectedPassword by remember(certificateFileName) { mutableStateOf<String?>(null) }
    var selectedAlias by rememberSaveable(certificateFileName) { mutableStateOf<String?>(null) }
    var pageText by rememberSaveable { mutableStateOf((currentPageIndex + 1).toString()) }
    var reason by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("") }
    var contactInfo by rememberSaveable { mutableStateOf("") }
    var placement by rememberSaveable { mutableStateOf(PdfSignaturePlacement.BOTTOM_RIGHT) }

    LaunchedEffect(certificateInfo) {
        val aliases = certificateInfo?.entries?.map(PdfPkcs12EntryInfo::alias).orEmpty()
        if (selectedAlias !in aliases) selectedAlias = aliases.firstOrNull()
    }
    val pageIndex = pageText.toIntOrNull()?.minus(1)
    val selectedEntry = certificateInfo?.entries?.firstOrNull { it.alias == selectedAlias }
    val canSign = !busy && !certificateLoading && selectedEntry != null &&
        selectedEntry.statusNow == PdfCertificateStatus.VALID && selectedEntry.allowsDigitalSignature &&
        password.isNotEmpty() && inspectedPassword == password &&
        pageIndex != null && pageIndex in 0 until pageCount

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("使用数字证书签名") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "签名将保存为新的 PDF，并使用增量写入保留已有有效签名。证书密码只在本次签署期间使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onChooseCertificate,
                    enabled = !busy && !certificateLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        certificateFileName?.let { "证书文件：$it" } ?: "选择 PKCS#12 / PFX 证书",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (certificateFileName != null) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            inspectedPassword = null
                            selectedAlias = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("证书密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    OutlinedButton(
                        onClick = {
                            inspectedPassword = password
                            onInspectCertificate(password)
                        },
                        enabled = password.isNotEmpty() && !certificateLoading && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (certificateLoading) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        }
                        Text(if (certificateInfo == null) "读取证书" else "重新读取证书")
                    }
                }
                certificateError?.let { message ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            message,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                certificateInfo?.entries?.let { entries ->
                    Text("签名证书", style = MaterialTheme.typography.labelLarge)
                    entries.forEach { entry ->
                        CertificateEntry(
                            entry = entry,
                            selected = entry.alias == selectedAlias,
                            onSelect = { selectedAlias = entry.alias },
                        )
                    }
                }
                OutlinedTextField(
                    value = pageText,
                    onValueChange = { pageText = it.filter(Char::isDigit).take(6) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("可视签名页码（1-$pageCount）") },
                    singleLine = true,
                    isError = pageIndex == null || pageIndex !in 0 until pageCount,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(200) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("签署原因（可选）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it.take(120) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("签署地点（可选）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = contactInfo,
                    onValueChange = { contactInfo = it.take(160) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("联系方式（可选）") },
                    singleLine = true,
                )
                Text("签名外观", style = MaterialTheme.typography.labelLarge)
                PdfSignaturePlacement.entries.chunked(2).forEach { rowEntries ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowEntries.forEach { candidate ->
                            FilterChip(
                                selected = placement == candidate,
                                onClick = { placement = candidate },
                                modifier = Modifier.weight(1f),
                                label = { Text(candidate.displayName()) },
                            )
                        }
                        if (rowEntries.size == 1) {
                            Surface(modifier = Modifier.weight(1f), color = androidx.compose.ui.graphics.Color.Transparent) {}
                        }
                    }
                }
                if (selectedEntry != null && !selectedEntry.allowsDigitalSignature) {
                    Text(
                        "所选证书的 KeyUsage 不允许数字签名。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (selectedEntry != null && selectedEntry.statusNow != PdfCertificateStatus.VALID) {
                    Text(
                        "所选证书当前不在有效期内。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
        confirmButton = {
            TextButton(
                enabled = canSign,
                onClick = {
                    onSign(
                        PdfDigitalSignRequest(
                            alias = requireNotNull(selectedAlias),
                            pageIndex = requireNotNull(pageIndex),
                            reason = reason,
                            location = location,
                            contactInfo = contactInfo,
                            placement = placement,
                        ),
                        password,
                    )
                },
            ) { Text("签名并另存") }
        },
    )
}

@Composable
private fun CertificateEntry(
    entry: PdfPkcs12EntryInfo,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    principalLabel(entry.subject),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${entry.alias} · ${entry.keyAlgorithm} · 至 ${formatDate(entry.notAfter)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun PdfSignaturePlacement.displayName(): String = when (this) {
    PdfSignaturePlacement.INVISIBLE -> "不可见签名"
    PdfSignaturePlacement.TOP_LEFT -> "左上角"
    PdfSignaturePlacement.TOP_RIGHT -> "右上角"
    PdfSignaturePlacement.BOTTOM_LEFT -> "左下角"
    PdfSignaturePlacement.BOTTOM_RIGHT -> "右下角"
}

private fun formatDate(value: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(value))
