package com.localllm.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.localllm.app.R

@Composable
internal fun LimitsSection(
    requestTimeoutSec: String,
    onRequestTimeoutSecChange: (String) -> Unit,
    maxQueueDepth: String,
    onMaxQueueDepthChange: (String) -> Unit,
    maxPromptChars: String,
    onMaxPromptCharsChange: (String) -> Unit,
) {
    HintText(stringResource(R.string.settings_limits_hint))
    SettingRowWithHelp(
        helpText = stringResource(R.string.settings_timeout_help),
        control = {
            NumberField(
                value = requestTimeoutSec,
                onValueChange = onRequestTimeoutSecChange,
                label = stringResource(R.string.settings_timeout),
                leadingIcon = Icons.Outlined.Timer,
            )
        }
    )
    SettingRowWithHelp(
        helpText = stringResource(R.string.settings_queue_depth_help),
        control = {
            NumberField(
                value = maxQueueDepth,
                onValueChange = onMaxQueueDepthChange,
                label = stringResource(R.string.settings_queue_depth),
                leadingIcon = Icons.Outlined.Layers,
            )
        }
    )
    SettingRowWithHelp(
        helpText = stringResource(R.string.settings_prompt_chars_help),
        control = {
            NumberField(
                value = maxPromptChars,
                onValueChange = onMaxPromptCharsChange,
                label = stringResource(R.string.settings_prompt_chars),
                leadingIcon = Icons.Outlined.TextFields,
            )
        }
    )
}
