package com.loooong.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.loooong.reader.engine.PdfCertificateChainStatus
import com.loooong.reader.engine.PdfCertificateInfo
import com.loooong.reader.engine.PdfCertificateStatus
import com.loooong.reader.engine.PdfDigitalSignatureInfo
import com.loooong.reader.engine.PdfDigitalSignatureReport
import com.loooong.reader.engine.PdfDigitalSignatureStatus
import com.loooong.reader.engine.PdfSignatureCoverage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PdfDigitalSignatureDialog(
    report: PdfDigitalSignatureReport?,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("数字签名") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    loading -> Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.width(12.dp))
                        Text("正在验证签名和证书…")
                    }
                    error != null -> Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            error,
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    report == null -> Text("尚未读取数字签名信息")
                    report.signatures.isEmpty() -> Text("当前 PDF 没有数字签名")
                    else -> {
                        Text(
                            "共检测到 ${report.signatures.size} 个数字签名",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        report.signatures.forEachIndexed { index, signature ->
                            if (index > 0) HorizontalDivider()
                            SignatureDetails(signature)
                        }
                    }
                }
                Text(
                    "密码学验证只确认签名值与签署字节是否匹配。当前版本不会联网检查 OCSP/CRL 吊销状态，" +
                        "嵌入证书链也不等同于受系统信任或具有法律效力。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onRefresh, enabled = !loading) { Text("重新验证") }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

@Composable
private fun SignatureDetails(signature: PdfDigitalSignatureInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SignatureStatusBadge(signature.status)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    signature.name?.takeIf { it.isNotBlank() }
                        ?: signature.fieldName?.takeIf { it.isNotBlank() }
                        ?: "签名 ${signature.index + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    signature.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = signatureStatusColor(signature.status),
                )
            }
        }
        SignatureValue("签署时间", formatDate(signature.signDate))
        signature.reason?.takeIf(String::isNotBlank)?.let { SignatureValue("签署原因", it) }
        signature.location?.takeIf(String::isNotBlank)?.let { SignatureValue("签署地点", it) }
        SignatureValue("签名格式", signature.subFilter ?: "未知")
        SignatureValue(
            "算法",
            listOfNotNull(signature.digestAlgorithm, signature.encryptionAlgorithm)
                .joinToString(" / ")
                .ifBlank { "未知" },
        )
        SignatureValue("覆盖范围", coverageLabel(signature.coverage))
        if (signature.coverage == PdfSignatureCoverage.SIGNED_REVISION) {
            SignatureValue(
                "签署版本",
                "${formatBytes(signature.signedRevisionLength)} / 当前 ${formatBytes(signature.fileLength)}",
            )
        }
        val certificate = signature.certificate
        if (certificate != null) CertificateDetails(certificate)
    }
}

@Composable
private fun CertificateDetails(certificate: PdfCertificateInfo) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("签署证书", style = MaterialTheme.typography.labelLarge)
            SignatureValue("主体", principalLabel(certificate.subject))
            SignatureValue("颁发者", principalLabel(certificate.issuer))
            SignatureValue("序列号", certificate.serialNumber)
            SignatureValue(
                "有效期",
                "${formatDate(certificate.notBefore)} 至 ${formatDate(certificate.notAfter)}",
            )
            SignatureValue("签署时证书", certificateStatusLabel(certificate.statusAtSigningTime))
            SignatureValue("当前证书", certificateStatusLabel(certificate.statusNow))
            SignatureValue(
                "嵌入证书链",
                "${certificate.chainLength} 张 · ${certificateChainLabel(certificate.chainStatus)}",
            )
        }
    }
}

@Composable
private fun SignatureStatusBadge(status: PdfDigitalSignatureStatus) {
    Surface(
        shape = CircleShape,
        color = signatureStatusColor(status).copy(alpha = 0.14f),
    ) {
        Text(
            signatureStatusLabel(status),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = signatureStatusColor(status),
        )
    }
}

@Composable
private fun SignatureValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            label,
            modifier = Modifier.width(88.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun signatureStatusColor(status: PdfDigitalSignatureStatus): Color = when (status) {
    PdfDigitalSignatureStatus.VALID -> MaterialTheme.colorScheme.primary
    PdfDigitalSignatureStatus.VALID_WITH_LATER_CHANGES -> MaterialTheme.colorScheme.tertiary
    PdfDigitalSignatureStatus.INVALID,
    PdfDigitalSignatureStatus.ERROR,
    -> MaterialTheme.colorScheme.error
    PdfDigitalSignatureStatus.UNSUPPORTED -> MaterialTheme.colorScheme.secondary
}

private fun signatureStatusLabel(status: PdfDigitalSignatureStatus): String = when (status) {
    PdfDigitalSignatureStatus.VALID -> "有效"
    PdfDigitalSignatureStatus.VALID_WITH_LATER_CHANGES -> "后续有修改"
    PdfDigitalSignatureStatus.INVALID -> "无效"
    PdfDigitalSignatureStatus.UNSUPPORTED -> "暂不支持"
    PdfDigitalSignatureStatus.ERROR -> "检查失败"
}

private fun coverageLabel(coverage: PdfSignatureCoverage): String = when (coverage) {
    PdfSignatureCoverage.WHOLE_DOCUMENT -> "覆盖当前完整文档"
    PdfSignatureCoverage.SIGNED_REVISION -> "仅覆盖较早的签署版本"
    PdfSignatureCoverage.INVALID -> "ByteRange 无效"
}

private fun certificateStatusLabel(status: PdfCertificateStatus): String = when (status) {
    PdfCertificateStatus.VALID -> "有效期内"
    PdfCertificateStatus.EXPIRED -> "已过期"
    PdfCertificateStatus.NOT_YET_VALID -> "尚未生效"
    PdfCertificateStatus.INVALID -> "证书异常"
    PdfCertificateStatus.UNKNOWN -> "无法判断"
}

private fun certificateChainLabel(status: PdfCertificateChainStatus): String = when (status) {
    PdfCertificateChainStatus.EMBEDDED_CHAIN_VALID -> "链内签名关系有效"
    PdfCertificateChainStatus.SELF_SIGNED -> "自签名证书"
    PdfCertificateChainStatus.INCOMPLETE -> "证书链不完整"
    PdfCertificateChainStatus.INVALID -> "证书链校验失败"
    PdfCertificateChainStatus.NOT_AVAILABLE -> "没有证书链"
}

private fun formatDate(value: Long?): String = value?.let {
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(it))
} ?: "未知"

private fun formatBytes(value: Long?): String {
    value ?: return "未知"
    if (value < 1024L) return "$value B"
    val kb = value / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(Locale.CHINA, kb)
    return "%.1f MB".format(Locale.CHINA, kb / 1024.0)
}

internal fun principalLabel(value: String): String = value
    .split(',')
    .firstOrNull { it.trim().startsWith("CN=", ignoreCase = true) }
    ?.substringAfter('=')
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: value
