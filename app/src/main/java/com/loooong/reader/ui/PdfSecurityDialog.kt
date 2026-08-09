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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.loooong.reader.engine.PdfSecurityInfo
import com.loooong.reader.engine.PdfSecurityPermissions
import com.loooong.reader.engine.PdfSecuritySettings

@Composable
fun PdfSecurityDialog(
    info: PdfSecurityInfo,
    digitalSignatureCount: Int,
    digitalSignatureInspectionFailed: Boolean,
    busy: Boolean,
    onApply: (PdfSecuritySettings) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var userPassword by remember(info) { mutableStateOf("") }
    var userPasswordConfirmation by remember(info) { mutableStateOf("") }
    var ownerPassword by remember(info) { mutableStateOf("") }
    var ownerPasswordConfirmation by remember(info) { mutableStateOf("") }
    var permissions by remember(info) { mutableStateOf(info.permissions) }
    var encryptionKeyLength by remember(info) {
        mutableIntStateOf(info.encryptionKeyLength?.takeIf { it == 128 || it == 256 } ?: 256)
    }
    var confirmingRemoval by remember(info) { mutableStateOf(false) }

    if (confirmingRemoval) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmingRemoval = false },
            title = { Text("移除 PDF 保护？") },
            text = {
                Text("保存后任何人都可以无密码打开此 PDF，原有打印、复制和修改限制也会被移除。此操作可在当前编辑会话中撤销。")
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { confirmingRemoval = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = onRemove,
                ) {
                    Text("移除保护", color = MaterialTheme.colorScheme.error)
                }
            },
        )
        return
    }

    val ownerMissing = ownerPassword.isEmpty()
    val ownerMismatch = ownerPassword != ownerPasswordConfirmation
    val userMismatch = userPassword != userPasswordConfirmation
    val passwordsEqual = userPassword.isNotEmpty() && userPassword == ownerPassword
    val canApply = !busy && !ownerMissing && !ownerMismatch && !userMismatch && !passwordsEqual

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (info.isEncrypted) "密码与权限" else "设置 PDF 保护") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (info.isEncrypted) {
                        "当前 PDF 已加密。安全字典不会保存明文密码，更新保护时需要重新填写新密码。"
                    } else {
                        "打开密码可留空；所有者密码用于以后修改权限或移除保护。"
                    },
                )
                if (digitalSignatureCount > 0 || digitalSignatureInspectionFailed) {
                    Text(
                        if (digitalSignatureInspectionFailed) {
                            "无法确认当前数字签名状态。修改保护设置并保存后，已有签名可能失效。"
                        } else {
                            "当前文档包含 $digitalSignatureCount 个数字签名。修改保护设置并保存后，已有签名将失效。"
                        },
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                SecurityPasswordField(
                    label = "打开密码（可选）",
                    value = userPassword,
                    onValueChange = { userPassword = it.take(MAX_PASSWORD_LENGTH) },
                    busy = busy,
                    error = when {
                        userMismatch -> "两次打开密码不一致"
                        passwordsEqual -> "打开密码不能与所有者密码相同"
                        else -> null
                    },
                )
                SecurityPasswordField(
                    label = "确认打开密码",
                    value = userPasswordConfirmation,
                    onValueChange = { userPasswordConfirmation = it.take(MAX_PASSWORD_LENGTH) },
                    busy = busy,
                    error = if (userMismatch) "两次打开密码不一致" else null,
                )
                SecurityPasswordField(
                    label = "所有者密码",
                    value = ownerPassword,
                    onValueChange = { ownerPassword = it.take(MAX_PASSWORD_LENGTH) },
                    busy = busy,
                    error = when {
                        ownerMissing -> "所有者密码不能为空"
                        ownerMismatch -> "两次所有者密码不一致"
                        passwordsEqual -> "所有者密码必须与打开密码不同"
                        else -> null
                    },
                )
                SecurityPasswordField(
                    label = "确认所有者密码",
                    value = ownerPasswordConfirmation,
                    onValueChange = { ownerPasswordConfirmation = it.take(MAX_PASSWORD_LENGTH) },
                    busy = busy,
                    error = if (ownerMismatch) "两次所有者密码不一致" else null,
                )

                Text("加密强度", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecurityKeyLengthChip(128, encryptionKeyLength, busy) { encryptionKeyLength = it }
                    SecurityKeyLengthChip(256, encryptionKeyLength, busy) { encryptionKeyLength = it }
                }

                Text("用户权限", style = MaterialTheme.typography.titleSmall)
                SecurityPermissionOption("允许打印", permissions.allowPrinting, busy) { allowed ->
                    permissions = permissions.copy(
                        allowPrinting = allowed,
                        allowHighQualityPrinting = permissions.allowHighQualityPrinting && allowed,
                    )
                }
                SecurityPermissionOption("允许高质量打印", permissions.allowHighQualityPrinting, busy || !permissions.allowPrinting) {
                    permissions = permissions.copy(allowHighQualityPrinting = it)
                }
                SecurityPermissionOption("允许复制内容", permissions.allowCopying, busy) {
                    permissions = permissions.copy(allowCopying = it)
                }
                SecurityPermissionOption("允许修改内容", permissions.allowModifying, busy) {
                    permissions = permissions.copy(allowModifying = it)
                }
                SecurityPermissionOption("允许添加和修改批注", permissions.allowAnnotations, busy) {
                    permissions = permissions.copy(allowAnnotations = it)
                }
                SecurityPermissionOption("允许填写表单", permissions.allowFormFilling, busy) {
                    permissions = permissions.copy(allowFormFilling = it)
                }
                SecurityPermissionOption("允许无障碍提取", permissions.allowAccessibility, busy) {
                    permissions = permissions.copy(allowAccessibility = it)
                }
                SecurityPermissionOption("允许页面重组", permissions.allowPageAssembly, busy) {
                    permissions = permissions.copy(allowPageAssembly = it)
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (info.isEncrypted) {
                    TextButton(
                        enabled = !busy,
                        onClick = { confirmingRemoval = true },
                    ) {
                        Text("移除保护", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canApply,
                onClick = {
                    onApply(
                        PdfSecuritySettings(
                            userPassword = userPassword,
                            ownerPassword = ownerPassword,
                            permissions = permissions,
                            encryptionKeyLength = encryptionKeyLength,
                        ),
                    )
                },
            ) { Text(if (busy) "正在应用" else if (info.isEncrypted) "更新保护" else "设置保护") }
        },
    )
}

@Composable
private fun SecurityPasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    error: String?,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = !busy,
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { message -> { Text(message) } },
        visualTransformation = PasswordVisualTransformation(),
    )
}

@Composable
private fun SecurityKeyLengthChip(
    value: Int,
    selected: Int,
    busy: Boolean,
    onSelect: (Int) -> Unit,
) {
    FilterChip(
        selected = value == selected,
        onClick = { onSelect(value) },
        enabled = !busy,
        label = { Text("AES-$value") },
    )
}

@Composable
private fun SecurityPermissionOption(
    label: String,
    checked: Boolean,
    disabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = !disabled,
        )
        Text(
            text = label,
            color = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private const val MAX_PASSWORD_LENGTH = 127
