package com.localllm.app.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.localllm.app.R

@Composable
internal fun StartupSection(
    startOnBoot: Boolean,
    onStartOnBootChange: (Boolean) -> Unit,
    autostart: Boolean,
    onAutostartChange: (Boolean) -> Unit,
    urlsText: String,
    onUrlsTextChange: (String) -> Unit,
) {
    SettingRow(
        label = stringResource(R.string.settings_boot),
        subtitle = stringResource(R.string.settings_boot_subtitle),
        checked = startOnBoot,
        onCheckedChange = onStartOnBootChange,
    )
    SettingRow(
        label = stringResource(R.string.settings_autostart),
        subtitle = stringResource(R.string.settings_autostart_subtitle),
        checked = autostart,
        onCheckedChange = onAutostartChange,
    )
    HintText(stringResource(R.string.settings_urls_hint))
    OutlinedTextField(
        value = urlsText,
        onValueChange = onUrlsTextChange,
        label = { Text(stringResource(R.string.settings_urls_label)) },
        leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
        modifier = Modifier.fillMaxWidth().height(140.dp),
        placeholder = { Text(stringResource(R.string.settings_urls_placeholder)) }
    )
}
