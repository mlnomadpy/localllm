package com.localllm.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.localllm.app.R
import com.localllm.app.ServerState

@Composable
internal fun ServerSection(
    serverStatus: ServerState.Status,
    port: String,
    onPortChange: (String) -> Unit,
    portInUse: Boolean,
    parsedPort: Int?,
    bindLan: Boolean,
    onBindLanChange: (Boolean) -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onStartServer,
            enabled = serverStatus != ServerState.Status.RUNNING && serverStatus != ServerState.Status.STARTING
        ) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(
                if (serverStatus == ServerState.Status.ERROR) R.string.settings_retry
                else R.string.settings_start
            ))
        }
        Button(
            onClick = onStopServer,
            enabled = serverStatus == ServerState.Status.RUNNING,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.settings_stop))
        }
    }
    HintText(stringResource(R.string.settings_restart_hint))

    SettingRowWithHelp(
        helpText = stringResource(R.string.settings_port_help),
        control = {
            NumberField(
                value = port,
                onValueChange = onPortChange,
                label = stringResource(R.string.settings_port),
                leadingIcon = Icons.Outlined.Lan,
                isError = portInUse,
                supportingText = if (portInUse && parsedPort != null) {
                    stringResource(R.string.settings_port_in_use, parsedPort)
                } else null,
            )
        }
    )
    SettingRow(
        label = stringResource(R.string.settings_bind_lan),
        subtitle = stringResource(R.string.settings_bind_lan_subtitle),
        checked = bindLan,
        onCheckedChange = onBindLanChange,
    )
}

@Composable
internal fun SecuritySection(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    allowCors: Boolean,
    onAllowCorsChange: (Boolean) -> Unit,
) {
    HintText(stringResource(R.string.settings_security_hint))
    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        label = { Text(stringResource(R.string.settings_api_key)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
        placeholder = { Text(stringResource(R.string.settings_api_key_placeholder)) }
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val random = java.security.SecureRandom()
            val bytes = ByteArray(16).also { random.nextBytes(it) }
            val key = bytes.joinToString("") { "%02x".format(it) }
            onApiKeyChange(key)
        }) { Text(stringResource(R.string.settings_generate)) }
        OutlinedButton(
            enabled = apiKey.isNotEmpty(),
            onClick = { onApiKeyChange("") }
        ) { Text(stringResource(R.string.settings_clear)) }
    }
    SettingRow(
        label = stringResource(R.string.settings_cors),
        subtitle = stringResource(R.string.settings_cors_subtitle),
        checked = allowCors,
        onCheckedChange = onAllowCorsChange,
    )
}
