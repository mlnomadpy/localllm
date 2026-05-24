package com.localllm.app.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localllm.app.R
import com.localllm.app.ServerState
import com.localllm.app.ui.AppTab
import com.localllm.app.ui.StatusDot

/**
 * Compact single-line top app bar. Leading status dot, middle-truncated URL
 * title (italic placeholder when server is stopped/starting), and contextual
 * trailing actions:
 *
 *  - Chat tab: Tune (system prompt) + Refresh (clear chat).
 *  - All other tabs: Copy URL.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactAppBar(
    serverStatus: ServerState.Status,
    serverUrl: String?,
    onCopyUrl: () -> Unit,
    activeTab: AppTab,
    onOpenSystemPrompt: () -> Unit,
    onClearChat: () -> Unit,
) {
    CenterAlignedTopAppBar(
        title = {
            val (label, italic) = when {
                serverStatus == ServerState.Status.RUNNING && serverUrl != null ->
                    serverUrl to false
                serverStatus == ServerState.Status.STARTING ->
                    stringResource(R.string.status_starting) to true
                serverStatus == ServerState.Status.ERROR ->
                    stringResource(R.string.status_error) to true
                else ->
                    stringResource(R.string.status_stopped) to true
            }
            val canCopy = serverStatus == ServerState.Status.RUNNING && serverUrl != null
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = middleEllipsize(label, maxChars = 32),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (italic) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (canCopy && activeTab != AppTab.CHAT) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        },
        navigationIcon = {
            Box(
                modifier = Modifier
                    .size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                StatusDot(status = serverStatus)
            }
        },
        actions = {
            if (activeTab == AppTab.CHAT) {
                IconButton(onClick = onOpenSystemPrompt) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = "Edit system prompt",
                    )
                }
                IconButton(onClick = onClearChat) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Clear chat",
                    )
                }
            } else {
                IconButton(
                    onClick = onCopyUrl,
                    enabled = serverStatus == ServerState.Status.RUNNING && serverUrl != null,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy_url),
                    )
                }
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
fun AppNavigationBar(
    activeTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        AppTab.values().forEach { tab ->
            NavigationBarItem(
                selected = activeTab == tab,
                onClick = { onTabSelected(tab) },
                icon = { Icon(iconForTab(tab), contentDescription = null) },
                label = {
                    Text(
                        text = stringResource(tab.labelRes),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

private fun iconForTab(tab: AppTab): ImageVector = when (tab) {
    AppTab.MODELS -> Icons.Outlined.FolderOpen
    AppTab.DASHBOARD -> Icons.Outlined.BarChart
    AppTab.CONSOLE -> Icons.Outlined.Terminal
    AppTab.CHAT -> Icons.AutoMirrored.Outlined.Chat
    AppTab.DOCUMENTS -> Icons.Outlined.Description
    AppTab.SETTINGS -> Icons.Outlined.Settings
}

/**
 * Cheap middle-ellipsis for the app-bar URL. Compose doesn't offer
 * `TextOverflow.MiddleEllipsis` natively (it's experimental as of Compose
 * 1.7), so we do it manually on the string. Truncates only when the input
 * exceeds [maxChars], preserving the prefix (scheme://host) and the suffix
 * (port + path).
 */
private fun middleEllipsize(s: String, maxChars: Int): String {
    if (s.length <= maxChars) return s
    val keep = maxChars - 1 // one char for the ellipsis
    val head = keep / 2
    val tail = keep - head
    return s.substring(0, head) + "…" + s.substring(s.length - tail)
}
