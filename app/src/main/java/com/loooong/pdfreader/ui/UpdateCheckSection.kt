package com.loooong.pdfreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.loooong.pdfreader.BuildConfig
import com.loooong.pdfreader.ReleaseInfo
import com.loooong.pdfreader.UpdateCheckResult
import com.loooong.pdfreader.UpdateChecker
import compose.icons.TablerIcons
import compose.icons.tablericons.ExternalLink
import compose.icons.tablericons.Refresh
import kotlinx.coroutines.launch

@Composable
internal fun UpdateCheckSection() {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateCheckUiState>(UpdateCheckUiState.Idle) }
    val checkAllowed = state !is UpdateCheckUiState.Checking

    val checkUpdates: () -> Unit = checkUpdates@{
        if (!checkAllowed) return@checkUpdates
        // long: 网络检查只读取 GitHub Release 元数据，更新安装交给用户从发布页完成。
        state = UpdateCheckUiState.Checking
        scope.launch {
            state = when (val result = UpdateChecker.check(BuildConfig.VERSION_NAME)) {
                is UpdateCheckResult.Available -> UpdateCheckUiState.Available(result.release)
                is UpdateCheckResult.UpToDate -> UpdateCheckUiState.UpToDate(result.release.tagName)
                is UpdateCheckResult.Failed -> UpdateCheckUiState.Failed(result.message)
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier.clickable(
                enabled = checkAllowed,
                role = Role.Button,
                onClick = checkUpdates,
            ),
            headlineContent = { Text("检查更新") },
            supportingContent = { UpdateCheckStatus(state) },
            leadingContent = { Icon(TablerIcons.Refresh, contentDescription = null) },
            trailingContent = {
                if (state is UpdateCheckUiState.Checking) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else if (checkAllowed) {
                    Icon(TablerIcons.Refresh, contentDescription = null)
                }
            },
        )

        when (val current = state) {
            is UpdateCheckUiState.Available -> UpdateAvailableDetails(
                release = current.release,
                onOpenRelease = { uriHandler.openUri(current.release.releaseUrl) },
            )
            is UpdateCheckUiState.Failed -> TextButton(
                onClick = checkUpdates,
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                Text("重新检查")
            }
            else -> Unit
        }
    }
}

@Composable
private fun UpdateAvailableDetails(
    release: ReleaseInfo,
    onOpenRelease: () -> Unit,
) {
    var notesExpanded by remember(release.tagName) { mutableStateOf(false) }
    var notesCanExpand by remember(release.tagName) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("发现新版本 ${release.tagName}", style = MaterialTheme.typography.titleMedium)
        Text(
            "请前往 GitHub Releases 获取更新。应用不会在后台下载或安装 APK。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (release.notes.isNotBlank()) {
            Text("版本说明", style = MaterialTheme.typography.labelLarge)
            Text(
                release.notes,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (notesExpanded) Int.MAX_VALUE else 8,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result ->
                    if (!notesExpanded && result.hasVisualOverflow) notesCanExpand = true
                },
            )
            if (notesCanExpand) {
                TextButton(onClick = { notesExpanded = !notesExpanded }) {
                    Text(if (notesExpanded) "收起版本说明" else "展开版本说明")
                }
            }
        }
        FilledTonalButton(onClick = onOpenRelease, modifier = Modifier.fillMaxWidth()) {
            Icon(TablerIcons.ExternalLink, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("前往 GitHub 更新")
        }
    }
}

@Composable
private fun UpdateCheckStatus(state: UpdateCheckUiState) {
    val color = if (state is UpdateCheckUiState.Failed) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = when (state) {
        UpdateCheckUiState.Idle -> "当前版本 ${BuildConfig.VERSION_NAME}，点击检查 GitHub"
        UpdateCheckUiState.Checking -> "正在检查 GitHub 最新版本"
        is UpdateCheckUiState.UpToDate -> "已是最新版本 · ${state.latestVersion}"
        is UpdateCheckUiState.Available -> "发现新版本 ${state.release.tagName}，请前往 GitHub 更新"
        is UpdateCheckUiState.Failed -> state.message
    }
    Text(text, color = color)
}

private sealed interface UpdateCheckUiState {
    data object Idle : UpdateCheckUiState
    data object Checking : UpdateCheckUiState
    data class UpToDate(val latestVersion: String) : UpdateCheckUiState
    data class Available(val release: ReleaseInfo) : UpdateCheckUiState
    data class Failed(val message: String) : UpdateCheckUiState
}
