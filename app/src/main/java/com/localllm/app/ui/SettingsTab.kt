package com.localllm.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Battery5Bar
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.localllm.app.R
import com.localllm.app.ServerState
import com.localllm.app.SettingsRepository
import com.localllm.app.ui.settings.BackgroundSection
import com.localllm.app.ui.settings.ExpandableSection
import com.localllm.app.ui.settings.InferenceSection
import com.localllm.app.ui.settings.LimitsSection
import com.localllm.app.ui.settings.SecuritySection
import com.localllm.app.ui.settings.ServerSection
import com.localllm.app.ui.settings.StartupSection
import com.localllm.app.ui.settings.mutableStateMapOfDefault
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
            ServerSection(
                serverStatus = serverStatus,
                port = port,
                onPortChange = {
                    val sanitized = it.filter { c -> c.isDigit() }.take(5)
                    port = sanitized
                    sanitized.toIntOrNull()?.let { v -> repo.setPort(v) }
                },
                portInUse = portInUse,
                parsedPort = parsedPort,
                bindLan = bindLan,
                onBindLanChange = { repo.setBindLan(it) },
                onStartServer = onStartServer,
                onStopServer = onStopServer,
            )
        }

        // ----- Inference -----
        ExpandableSection(
            title = stringResource(R.string.settings_section_inference),
            icon = Icons.Outlined.Memory,
            expanded = expanded["inference"] == true,
            onToggle = { expanded["inference"] = !(expanded["inference"] ?: false) },
        ) {
            InferenceSection(
                maxTokens = maxTokens,
                onMaxTokensChange = {
                    val sanitized = it.filter { c -> c.isDigit() }.take(5)
                    maxTokens = sanitized
                    sanitized.toIntOrNull()?.let { v -> repo.setMaxTokens(v) }
                },
                temperature = temperature,
                onTemperatureChange = { temperature = it },
                onTemperatureChangeFinished = { repo.setTemperature(temperature) },
                topK = topK,
                onTopKChange = { topK = it },
                onTopKChangeFinished = { repo.setTopK(topK) },
            )
        }

        // ----- Security -----
        ExpandableSection(
            title = stringResource(R.string.settings_section_security),
            icon = Icons.Outlined.Lock,
            expanded = expanded["security"] == true,
            onToggle = { expanded["security"] = !(expanded["security"] ?: false) },
        ) {
            SecuritySection(
                apiKey = apiKey,
                onApiKeyChange = { repo.setApiKey(it) },
                allowCors = allowCors,
                onAllowCorsChange = { repo.setAllowCors(it) },
            )
        }

        // ----- Background efficiency -----
        ExpandableSection(
            title = stringResource(R.string.settings_section_background),
            icon = Icons.Outlined.Battery5Bar,
            expanded = expanded["background"] == true,
            onToggle = { expanded["background"] = !(expanded["background"] ?: false) },
        ) {
            BackgroundSection(
                idleEvictMin = idleEvictMin,
                onIdleEvictMinChange = {
                    val sanitized = it.filter { c -> c.isDigit() }.take(4)
                    idleEvictMin = sanitized
                    sanitized.toLongOrNull()?.let { v -> repo.setIdleEvictMs(v * 60_000L) }
                },
                idleStopMin = idleStopMin,
                onIdleStopMinChange = {
                    val sanitized = it.filter { c -> c.isDigit() }.take(4)
                    idleStopMin = sanitized
                    sanitized.toLongOrNull()?.let { v -> repo.setIdleStopMs(v * 60_000L) }
                },
                keepAwake = keepAwake,
                onKeepAwakeChange = { repo.setKeepAwake(it) },
            )
        }

        // ----- Limits -----
        ExpandableSection(
            title = stringResource(R.string.settings_section_limits),
            icon = Icons.Outlined.Speed,
            expanded = expanded["limits"] == true,
            onToggle = { expanded["limits"] = !(expanded["limits"] ?: false) },
        ) {
            LimitsSection(
                requestTimeoutSec = requestTimeoutSec,
                onRequestTimeoutSecChange = {
                    val sanitized = it.filter { c -> c.isDigit() }.take(4)
                    requestTimeoutSec = sanitized
                    sanitized.toLongOrNull()?.let { v -> repo.setRequestTimeoutMs(v * 1000L) }
                },
                maxQueueDepth = maxQueueDepth,
                onMaxQueueDepthChange = {
                    val sanitized = it.filter { c -> c.isDigit() }.take(3)
                    maxQueueDepth = sanitized
                    sanitized.toIntOrNull()?.let { v -> repo.setMaxQueueDepth(v) }
                },
                maxPromptChars = maxPromptChars,
                onMaxPromptCharsChange = {
                    val sanitized = it.filter { c -> c.isDigit() }.take(8)
                    maxPromptChars = sanitized
                    sanitized.toIntOrNull()?.let { v -> repo.setMaxPromptChars(v) }
                },
            )
        }

        // ----- Startup -----
        ExpandableSection(
            title = stringResource(R.string.settings_section_startup),
            icon = Icons.Outlined.PowerSettingsNew,
            expanded = expanded["startup"] == true,
            onToggle = { expanded["startup"] = !(expanded["startup"] ?: false) },
        ) {
            StartupSection(
                startOnBoot = startOnBoot,
                onStartOnBootChange = { repo.setStartOnBoot(it) },
                autostart = autostart,
                onAutostartChange = { repo.setAutostart(it) },
                urlsText = urlsText,
                onUrlsTextChange = {
                    urlsText = it
                    val parsed = it.split("\n").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }
                    onCustomUrlsChange(parsed)
                },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}
