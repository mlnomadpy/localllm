package com.localllm.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.localllm.app.R

@Composable
internal fun BackgroundSection(
    idleEvictMin: String,
    onIdleEvictMinChange: (String) -> Unit,
    idleStopMin: String,
    onIdleStopMinChange: (String) -> Unit,
    keepAwake: Boolean,
    onKeepAwakeChange: (Boolean) -> Unit,
) {
    HintText(stringResource(R.string.settings_background_hint))
    SettingRowWithHelp(
        helpText = stringResource(R.string.settings_idle_evict_help),
        control = {
            NumberField(
                value = idleEvictMin,
                onValueChange = onIdleEvictMinChange,
                label = stringResource(R.string.settings_idle_evict),
                leadingIcon = Icons.Outlined.HourglassEmpty,
            )
        }
    )
    SettingRowWithHelp(
        helpText = stringResource(R.string.settings_idle_stop_help),
        control = {
            NumberField(
                value = idleStopMin,
                onValueChange = onIdleStopMinChange,
                label = stringResource(R.string.settings_idle_stop),
                leadingIcon = Icons.Outlined.Timer,
            )
        }
    )
    SettingRow(
        label = stringResource(R.string.settings_keep_awake),
        subtitle = stringResource(R.string.settings_keep_awake_subtitle),
        checked = keepAwake,
        onCheckedChange = onKeepAwakeChange,
    )
}
