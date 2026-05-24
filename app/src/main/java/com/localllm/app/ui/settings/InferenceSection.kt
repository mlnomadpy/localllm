package com.localllm.app.ui.settings

import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Filter1
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.localllm.app.R

@Composable
internal fun InferenceSection(
    maxTokens: String,
    onMaxTokensChange: (String) -> Unit,
    temperature: Float,
    onTemperatureChange: (Float) -> Unit,
    onTemperatureChangeFinished: () -> Unit,
    topK: Int,
    onTopKChange: (Int) -> Unit,
    onTopKChangeFinished: () -> Unit,
) {
    HintText(stringResource(R.string.settings_inference_hint))

    // Chipset hint.
    val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL ?: "unknown"
    } else {
        "unknown"
    }
    val socLower = socModel.lowercase()
    val chipsetHint = when {
        socLower.startsWith("gs") -> stringResource(R.string.settings_chipset_tensor, Build.MODEL ?: "device")
        socLower.startsWith("sm") || socLower.contains("sdm") || socLower.contains("qcom") ->
            stringResource(R.string.settings_chipset_snapdragon, Build.MODEL ?: "device")
        else -> stringResource(R.string.settings_chipset_other, Build.MODEL ?: "device")
    }
    Text(
        text = chipsetHint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Backend is now declared per-model in the catalog (see
    // ModelCatalog.kt — Backend.AICORE / LITERT_CPU / LITERT_GPU /
    // LITERT_NPU). The server picks the right engine from the
    // selected model's metadata; there is no longer a global
    // backend pill or AUTO fallback chain.

    SettingRowWithHelp(
        helpText = stringResource(R.string.settings_max_tokens_help),
        control = {
            NumberField(
                value = maxTokens,
                onValueChange = onMaxTokensChange,
                label = stringResource(R.string.settings_max_tokens),
                leadingIcon = Icons.Outlined.TextFields,
            )
        }
    )

    SliderRowWithHelp(
        label = stringResource(R.string.settings_temperature, "%.2f".format(temperature)),
        leadingIcon = Icons.Outlined.Thermostat,
        helpText = stringResource(R.string.settings_temperature_help),
        value = temperature,
        onValueChange = onTemperatureChange,
        onValueChangeFinished = onTemperatureChangeFinished,
        valueRange = 0f..2f,
        steps = 39,
    )

    SliderRowWithHelp(
        label = stringResource(R.string.settings_top_k, topK),
        leadingIcon = Icons.Outlined.Filter1,
        helpText = stringResource(R.string.settings_top_k_help),
        value = topK.toFloat(),
        onValueChange = { onTopKChange(it.toInt()) },
        onValueChangeFinished = onTopKChangeFinished,
        valueRange = 1f..100f,
        steps = 98,
    )
}
