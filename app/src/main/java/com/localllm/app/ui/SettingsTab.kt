package com.localllm.app.ui

import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Battery5Bar
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Filter1
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.localllm.app.R
import com.localllm.app.ServerState
import com.localllm.app.Settings
import com.localllm.app.SettingsRepository
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.ServerSocket

@Composable
fun SettingsTab(
    context: Context,
    serverStatus: ServerState.Status,
    customUrls: List<String>,
    onCustomUrlsChange: (List<String>) -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit
) {
    val scroll = rememberScrollState()
    val repo = remember(context) { SettingsRepository.get(context) }

    // Observe each preference as a StateFlow — no more disk reads on recompose.
    val portValue by repo.port.collectAsState()
    val maxTokensValue by repo.maxTokens.collectAsState()
    val temperatureValue by repo.temperature.collectAsState()
    val topKValue by repo.topK.collectAsState()
    val bindLan by repo.bindLan.collectAsState()
    val startOnBoot by repo.startOnBoot.collectAsState()
    val autostart by repo.autostart.collectAsState()

    val requestTimeoutMs by repo.requestTimeoutMs.collectAsState()
    val maxQueueDepthValue by repo.maxQueueDepth.collectAsState()
    val maxPromptCharsValue by repo.maxPromptChars.collectAsState()
    val apiKey by repo.apiKey.collectAsState()
    val keepAwake by repo.keepAwake.collectAsState()
    val idleEvictMs by repo.idleEvictMs.collectAsState()
    val idleStopMs by repo.idleStopMs.collectAsState()
    val allowCors by repo.allowCors.collectAsState()

    // For text fields whose user-facing value is a string buffer separate from
    // the stored numeric value (mid-typing the field may hold something like
    // "" or "12" that doesn't yet parse to a valid clamped int), keep a local
    // editable mirror seeded from the flow.
    var port by remember { mutableStateOf(portValue.toString()) }
    var maxTokens by remember { mutableStateOf(maxTokensValue.toString()) }
    var urlsText by remember { mutableStateOf(customUrls.joinToString("\n")) }
    var requestTimeoutSec by remember { mutableStateOf((requestTimeoutMs / 1000).toString()) }
    var maxQueueDepth by remember { mutableStateOf(maxQueueDepthValue.toString()) }
    var maxPromptChars by remember { mutableStateOf(maxPromptCharsValue.toString()) }
    var idleEvictMin by remember { mutableStateOf((idleEvictMs / 60_000L).toString()) }
    var idleStopMin by remember { mutableStateOf((idleStopMs / 60_000L).toString()) }

    // Slider drags need a local mirror.
    var temperature by remember { mutableStateOf(temperatureValue) }
    var topK by remember { mutableStateOf(topKValue) }

    // Port-in-use validation (debounced).
    var portInUse by remember { mutableStateOf(false) }
    val parsedPort = port.toIntOrNull()
    LaunchedEffect(parsedPort) {
        portInUse = false
        val p = parsedPort ?: return@LaunchedEffect
        if (p < 1024 || p > 65535) return@LaunchedEffect
        delay(500)
        portInUse = try {
            ServerSocket(p).also { it.close() }
            false
        } catch (_: IOException) {
            true
        } catch (_: SecurityException) {
            false
        }
    }

    // Expanded state map per section.
    val expanded = remember {
        mutableStateMapOfDefault(
            "server" to true,
            "inference" to false,
            "security" to false,
            "background" to false,
            "limits" to false,
            "startup" to false,
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ----- Server -----
        ExpandableSection(
            title = stringResource(R.string.settings_section_server),
            icon = Icons.Outlined.Dns,
            expanded = expanded["server"] == true,
            onToggle = { expanded["server"] = !(expanded["server"] ?: false) },
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
                        onValueChange = {
                            val sanitized = it.filter { c -> c.isDigit() }.take(5)
                            port = sanitized
                            sanitized.toIntOrNull()?.let { v -> repo.setPort(v) }
                        },
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
                onCheckedChange = { repo.setBindLan(it) }
            )
        }

        // ----- Inference -----
        ExpandableSection(
            title = stringResource(R.string.settings_section_inference),
            icon = Icons.Outlined.Memory,
            expanded = expanded["inference"] == true,
            onToggle = { expanded["inference"] = !(expanded["inference"] ?: false) },
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
                        onValueChange = {
                            val sanitized = it.filter { c -> c.isDigit() }.take(5)
                            maxTokens = sanitized
                            sanitized.toIntOrNull()?.let { v -> repo.setMaxTokens(v) }
                        },
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
                onValueChange = { temperature = it },
                onValueChangeFinished = { repo.setTemperature(temperature) },
                valueRange = 0f..2f,
                steps = 39,
            )

            SliderRowWithHelp(
                label = stringResource(R.string.settings_top_k, topK),
                leadingIcon = Icons.Outlined.Filter1,
                helpText = stringResource(R.string.settings_top_k_help),
                value = topK.toFloat(),
                onValueChange = { topK = it.toInt() },
                onValueChangeFinished = { repo.setTopK(topK) },
                valueRange = 1f..100f,
                steps = 98,
            )
        }

        // ----- Security -----
        ExpandableSection(
            title = stringResource(R.string.settings_section_security),
            icon = Icons.Outlined.Lock,
            expanded = expanded["security"] == true,
            onToggle = { expanded["security"] = !(expanded["security"] ?: false) },
        ) {
            HintText(stringResource(R.string.settings_security_hint))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { repo.setApiKey(it) },
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
                    repo.setApiKey(key)
                }) { Text(stringResource(R.string.settings_generate)) }
                OutlinedButton(
                    enabled = apiKey.isNotEmpty(),
                    onClick = { repo.setApiKey("") }
                ) { Text(stringResource(R.string.settings_clear)) }
            }
            SettingRow(
                label = stringResource(R.string.settings_cors),
                subtitle = stringResource(R.string.settings_cors_subtitle),
                checked = allowCors,
                onCheckedChange = { repo.setAllowCors(it) }
            )
        }

        // ----- Background efficiency -----
        ExpandableSection(
            title = stringResource(R.string.settings_section_background),
            icon = Icons.Outlined.Battery5Bar,
            expanded = expanded["background"] == true,
            onToggle = { expanded["background"] = !(expanded["background"] ?: false) },
        ) {
            HintText(stringResource(R.string.settings_background_hint))
            SettingRowWithHelp(
                helpText = stringResource(R.string.settings_idle_evict_help),
                control = {
                    NumberField(
                        value = idleEvictMin,
                        onValueChange = {
                            val sanitized = it.filter { c -> c.isDigit() }.take(4)
                            idleEvictMin = sanitized
                            sanitized.toLongOrNull()?.let { v -> repo.setIdleEvictMs(v * 60_000L) }
                        },
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
                        onValueChange = {
                            val sanitized = it.filter { c -> c.isDigit() }.take(4)
                            idleStopMin = sanitized
                            sanitized.toLongOrNull()?.let { v -> repo.setIdleStopMs(v * 60_000L) }
                        },
                        label = stringResource(R.string.settings_idle_stop),
                        leadingIcon = Icons.Outlined.Timer,
                    )
                }
            )
            SettingRow(
                label = stringResource(R.string.settings_keep_awake),
                subtitle = stringResource(R.string.settings_keep_awake_subtitle),
                checked = keepAwake,
                onCheckedChange = { repo.setKeepAwake(it) }
            )
        }

        // ----- Limits -----
        ExpandableSection(
            title = stringResource(R.string.settings_section_limits),
            icon = Icons.Outlined.Speed,
            expanded = expanded["limits"] == true,
            onToggle = { expanded["limits"] = !(expanded["limits"] ?: false) },
        ) {
            HintText(stringResource(R.string.settings_limits_hint))
            SettingRowWithHelp(
                helpText = stringResource(R.string.settings_timeout_help),
                control = {
                    NumberField(
                        value = requestTimeoutSec,
                        onValueChange = {
                            val sanitized = it.filter { c -> c.isDigit() }.take(4)
                            requestTimeoutSec = sanitized
                            sanitized.toLongOrNull()?.let { v -> repo.setRequestTimeoutMs(v * 1000L) }
                        },
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
                        onValueChange = {
                            val sanitized = it.filter { c -> c.isDigit() }.take(3)
                            maxQueueDepth = sanitized
                            sanitized.toIntOrNull()?.let { v -> repo.setMaxQueueDepth(v) }
                        },
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
                        onValueChange = {
                            val sanitized = it.filter { c -> c.isDigit() }.take(8)
                            maxPromptChars = sanitized
                            sanitized.toIntOrNull()?.let { v -> repo.setMaxPromptChars(v) }
                        },
                        label = stringResource(R.string.settings_prompt_chars),
                        leadingIcon = Icons.Outlined.TextFields,
                    )
                }
            )
        }

        // ----- Startup -----
        ExpandableSection(
            title = stringResource(R.string.settings_section_startup),
            icon = Icons.Outlined.PowerSettingsNew,
            expanded = expanded["startup"] == true,
            onToggle = { expanded["startup"] = !(expanded["startup"] ?: false) },
        ) {
            SettingRow(
                label = stringResource(R.string.settings_boot),
                subtitle = stringResource(R.string.settings_boot_subtitle),
                checked = startOnBoot,
                onCheckedChange = { repo.setStartOnBoot(it) }
            )
            SettingRow(
                label = stringResource(R.string.settings_autostart),
                subtitle = stringResource(R.string.settings_autostart_subtitle),
                checked = autostart,
                onCheckedChange = { repo.setAutostart(it) }
            )
            HintText(stringResource(R.string.settings_urls_hint))
            OutlinedTextField(
                value = urlsText,
                onValueChange = {
                    urlsText = it
                    val parsed = it.split("\n").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }
                    onCustomUrlsChange(parsed)
                },
                label = { Text(stringResource(R.string.settings_urls_label)) },
                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                placeholder = { Text(stringResource(R.string.settings_urls_placeholder)) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/* ------------------------------------------------------------------ */
/* Reusable building blocks                                            */
/* ------------------------------------------------------------------ */

@Composable
private fun ExpandableSection(
    title: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.settings_collapse) else stringResource(R.string.settings_expand),
                    modifier = Modifier.rotate(rotation),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    leadingIcon: ImageVector? = null,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        isError = isError,
        leadingIcon = leadingIcon?.let { { Icon(it, contentDescription = null) } },
        supportingText = supportingText?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SettingRow(
    label: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingRowWithHelp(
    helpText: String,
    control: @Composable () -> Unit,
) {
    var helpOpen by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) { control() }
            IconButton(
                onClick = { helpOpen = !helpOpen },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Outlined.HelpOutline,
                    contentDescription = stringResource(R.string.settings_help),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(visible = helpOpen) {
            Text(
                text = helpText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun SliderRowWithHelp(
    label: String,
    leadingIcon: ImageVector,
    helpText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
) {
    var helpOpen by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(leadingIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { helpOpen = !helpOpen },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Outlined.HelpOutline,
                    contentDescription = stringResource(R.string.settings_help),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
        )
        AnimatedVisibility(visible = helpOpen) {
            Text(
                text = helpText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* Tiny helper: SnapshotStateMap with initial entries.                  */
/* ------------------------------------------------------------------ */

private fun <K, V> mutableStateMapOfDefault(vararg pairs: Pair<K, V>): androidx.compose.runtime.snapshots.SnapshotStateMap<K, V> {
    val m = androidx.compose.runtime.mutableStateMapOf<K, V>()
    m.putAll(pairs)
    return m
}
