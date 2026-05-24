package com.localllm.app.ui

import android.text.format.DateUtils
import android.text.format.Formatter
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.Memory
import com.localllm.app.AVAILABLE_MODELS
import com.localllm.app.Backend
import com.localllm.app.ModelInfo
import com.localllm.app.Settings
import com.localllm.app.SettingsRepository
import com.localllm.app.inference.aicore.AICoreEngine
import com.localllm.app.matchesCurrentSoc
import com.localllm.app.npuSocLabel
import com.localllm.app.R
import com.localllm.app.ui.models.DefaultModelRadio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ModelsTab(
    builtIn: List<ModelInfo>,
    customUrls: List<String>,
    existingModels: Set<String>,
    modelSizes: Map<String, Long>,
    modelMtimes: Map<String, Long>,
    activeDownloads: Map<String, Long>,
    downloadProgress: Map<String, Float>,
    downloadTotalBytes: Map<String, Long>,
    onDownload: (ModelInfo) -> Unit,
    onCancel: (ModelInfo) -> Unit,
    onDelete: (ModelInfo) -> Unit,
    onImport: () -> Unit
) {
    val context = LocalContext.current
    // Reactive read so a tap on any row's "Set as default" updates the
    // highlighted row + the AICore card simultaneously, without recompose
    // ping-pong through MainActivity.
    val selectedModelId by SettingsRepository.get(context).selectedModelId.collectAsState()
    val customDesc = stringResource(R.string.catalog_custom_description)
    val custom = customUrls.mapNotNull { url ->
        val fname = url.substringAfterLast('/').takeIf { it.endsWith(".litertlm") }
            ?: return@mapNotNull null
        val bare = fname.removeSuffix(".litertlm")
        ModelInfo(
            id = bare,
            name = bare,
            description = customDesc,
            url = url,
            filename = fname,
            backend = Backend.LITERT_CPU,
        )
    }
    // AICore gets its own dedicated card above; filter it out of the generic
    // items() loop so it isn't rendered twice.
    val all = (builtIn + custom).filter { it.backend != Backend.AICORE }
    val nothingInstalled = existingModels.isEmpty() && activeDownloads.isEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (nothingInstalled) {
            item { EmptyHeroCard() }
        }

        // AICore (Gemini Nano) — the default engine post-pivot. Shown at the
        // top so the live status badge (Ready / Tap to download / Downloading
        // / Not available) is the first thing a user sees on the Models tab.
        item {
            AICoreModelCard(
                isDefault = selectedModelId == Settings.DEFAULT_MODEL_ID,
                onSetDefault = {
                    Settings.setSelectedModelId(context, Settings.DEFAULT_MODEL_ID)
                },
            )
        }

        item {
            OutlinedButton(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Outlined.UploadFile,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.models_import))
            }
        }

        items(all) { model ->
            val isDownloaded = existingModels.contains(model.filename)
            val isDownloading = activeDownloads.containsKey(model.filename)
            val progress = downloadProgress[model.filename] ?: 0f
            val total = downloadTotalBytes[model.filename] ?: -1L
            val sizeOnDisk = modelSizes[model.filename] ?: 0L
            val mtime = modelMtimes[model.filename] ?: 0L

            ModelCard(
                model = model,
                isDownloaded = isDownloaded,
                isDownloading = isDownloading,
                progress = progress,
                totalBytes = total,
                sizeOnDisk = sizeOnDisk,
                mtime = mtime,
                isDefault = selectedModelId == model.id,
                // .litertlm-backed rows can only become the default once the
                // weights are on disk; otherwise the server would fail to
                // load the fallback model. AICore is rendered separately.
                canSetDefault = isDownloaded,
                onSetDefault = { Settings.setSelectedModelId(context, model.id) },
                onDownload = { onDownload(model) },
                onCancel = { onCancel(model) },
                onDelete = { onDelete(model) },
                formatBytes = { Formatter.formatShortFileSize(context, it) },
                relativeTime = {
                    DateUtils.getRelativeTimeSpanString(
                        it,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS
                    ).toString()
                }
            )
        }
    }
}

@Composable
private fun EmptyHeroCard() {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.DownloadForOffline,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.models_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.models_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelInfo,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    progress: Float,
    totalBytes: Long,
    sizeOnDisk: Long,
    mtime: Long,
    isDefault: Boolean,
    canSetDefault: Boolean,
    onSetDefault: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    formatBytes: (Long) -> String,
    relativeTime: (Long) -> String
) {
    val isBuiltIn = AVAILABLE_MODELS.any { it.filename == model.filename }
    val hasKnownHash = isBuiltIn && AVAILABLE_MODELS.firstOrNull { it.filename == model.filename }?.sha256 != null

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Title row — name + a filled "Default" pill when this row is the
            // current fallback model. The pill mirrors the AICore card so the
            // visual story across the tab stays consistent.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (isDefault) {
                    DefaultModelRadio(
                        isDefault = true,
                        enabled = true,
                        onSelect = {},
                    )
                }
            }

            // SHA-256 verification badge (only for installed models)
            if (isDownloaded) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasKnownHash) {
                        Icon(
                            imageVector = Icons.Outlined.Verified,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.models_sha_verified),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.models_sha_unverified),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // NPU SoC chip — only shown for NPU-gated catalog entries.
            // Tinted differently when the chip matches the current device's
            // SoC, so a Qualcomm user sees their compatible variant at a glance.
            model.npuSocLabel()?.let { label ->
                Spacer(modifier = Modifier.height(6.dp))
                val matches = model.matchesCurrentSoc()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(
                                if (matches) MaterialTheme.colorScheme.tertiaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Memory,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (matches) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (matches) stringResource(R.string.models_npu_match, label) else label,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (matches) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Description
            Text(
                text = model.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )

            // Metadata line for installed models: size · last used
            if (isDownloaded && sizeOnDisk > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                val sizeStr = formatBytes(sizeOnDisk)
                val timeStr = if (mtime > 0) relativeTime(mtime) else ""
                Text(
                    text = if (timeStr.isNotEmpty())
                        stringResource(R.string.models_metadata_line, sizeStr, timeStr)
                    else sizeStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isDownloading) {
                // Progress row: bar + cancel button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.models_cancel)
                        )
                    }
                }
                // Subtitle: "X.X MB / Y.Y GB" or just percent if total unknown.
                val subtitle = if (totalBytes > 0) {
                    val current = (progress.coerceIn(0f, 1f) * totalBytes).toLong()
                    stringResource(
                        R.string.models_progress_sizes,
                        formatBytes(current),
                        formatBytes(totalBytes)
                    )
                } else {
                    stringResource(R.string.models_progress_percent, (progress * 100).toInt())
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Left: "Set as default" radio (only when not already the
                    // default — the title-row pill handles that case).
                    if (!isDefault) {
                        DefaultModelRadio(
                            isDefault = false,
                            enabled = canSetDefault,
                            onSelect = onSetDefault,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (isDownloaded) {
                        OutlinedButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.models_delete))
                        }
                    } else {
                        Button(onClick = onDownload) {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.models_download))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Sentinel + state constants for the AICore live-status probe. Mirrors
 * [AICoreEngine] but kept separate so we can model probe failures (the SDK
 * throws when AICore isn't installed at all — typically ErrorCode -101)
 * distinctly from the SDK-reported UNAVAILABLE state.
 */
private const val AICORE_PROBE_PENDING = -2
private const val AICORE_PROBE_NOT_INSTALLED = -101

/**
 * AICore (Gemini Nano) catalog card. Renders a live status badge driven by
 * [AICoreEngine.checkStatusCode] and surfaces:
 *  - a "Default" pill (AICore is the post-pivot default engine);
 *  - a status pill: Ready / Tap to download / Downloading / Not available /
 *    Not installed;
 *  - a foreground-foreground-required tip (AICore returns ErrorCode 30 when
 *    the host activity is backgrounded);
 *  - a Provision button for the DOWNLOADABLE state that triggers the
 *    download via a no-op `complete("warmup")` call — the AICore SDK
 *    implicitly fetches weights on the first generate call from a
 *    foreground activity.
 *
 * The status is re-probed every 5s while the card is composed, plus once
 * eagerly on first composition.
 */
@Composable
private fun AICoreModelCard(
    isDefault: Boolean,
    onSetDefault: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableIntStateOf(0) }
    var provisioning by remember { mutableStateOf(false) }

    // produceState re-runs the producer whenever any key changes; bumping
    // refreshTick after a provision attempt forces an immediate re-probe.
    val statusCode by produceState<Int>(initialValue = AICORE_PROBE_PENDING, key1 = refreshTick) {
        // Eager probe.
        value = withContext(Dispatchers.Default) {
            try { AICoreEngine.checkStatusCode() }
            catch (_: Throwable) { AICORE_PROBE_NOT_INSTALLED }
        }
        // Poll while card is composed. 5s is a reasonable cadence — DOWNLOADING
        // can take many minutes, and there's no callback API exposed by the SDK.
        while (true) {
            delay(5_000)
            value = withContext(Dispatchers.Default) {
                try { AICoreEngine.checkStatusCode() }
                catch (_: Throwable) { AICORE_PROBE_NOT_INSTALLED }
            }
        }
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Title row: name + "Default" badge.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Gemini Nano · AICore",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                // AICore has no .litertlm file so it's always selectable as
                // the default; availability is enforced at request time by
                // the server engine, not here.
                DefaultModelRadio(
                    isDefault = isDefault,
                    enabled = true,
                    onSelect = onSetDefault,
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "model id: ${AICoreEngine.MODEL_ID}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            AICoreStatusBadge(statusCode = statusCode, provisioning = provisioning)

            Spacer(Modifier.height(8.dp))
            // Sub-text that explains the current state in plain words.
            Text(
                text = aicoreSubtext(statusCode),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Foreground-required tip. AICore returns ErrorCode 30 when the
            // host activity isn't visible; an external HTTP client (curl,
            // PWA) cannot satisfy this on its own.
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Tip: AICore requires this app to be in the foreground when an HTTP client queries Gemini Nano.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Provision button — only meaningful while AICore is DOWNLOADABLE.
            if (statusCode == AICoreEngine.STATUS_DOWNLOADABLE) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        enabled = !provisioning,
                        onClick = {
                            provisioning = true
                            scope.launch {
                                // The SDK doesn't expose a download() helper
                                // via our wrapper; the canonical trigger is
                                // a generate-from-foreground call. We use a
                                // throwaway warmup prompt; output is ignored.
                                try {
                                    withContext(Dispatchers.Default) {
                                        AICoreEngine.complete(prompt = "ok")
                                    }
                                } catch (_: Throwable) {
                                    // Expected while download is still in
                                    // progress — the next status probe will
                                    // reflect the new DOWNLOADING state.
                                }
                                provisioning = false
                                refreshTick++
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Download Gemini Nano")
                    }
                }
            }
        }
    }
}

/**
 * Coloured status pill for AICore. Mapping:
 *  - AVAILABLE         → green "Ready" + check
 *  - DOWNLOADABLE      → amber "Tap to download" + cloud
 *  - DOWNLOADING       → amber "Downloading…" + spinner (or "Provisioning…"
 *                        while the user-triggered warmup is in flight)
 *  - UNAVAILABLE       → red "Not available on this device" + error
 *  - PROBE_NOT_INSTALLED → red "AICore not installed" + error (caught -101)
 *  - PROBE_PENDING     → muted "Checking…" + spinner
 */
@Composable
private fun AICoreStatusBadge(statusCode: Int, provisioning: Boolean) {
    val (label, bg, fg, leading) = when (statusCode) {
        AICoreEngine.STATUS_AVAILABLE -> Quad(
            "Ready",
            Color(0xFF1B5E20),                // dark green container
            Color(0xFFB9F6CA),                // light green text
            BadgeIcon.Check,
        )
        AICoreEngine.STATUS_DOWNLOADABLE -> Quad(
            if (provisioning) "Provisioning…" else "Tap to download",
            Color(0xFF8D6E00),                // amber container
            Color(0xFFFFECB3),                // amber text
            if (provisioning) BadgeIcon.Spinner else BadgeIcon.Cloud,
        )
        AICoreEngine.STATUS_DOWNLOADING -> Quad(
            "Downloading…",
            Color(0xFF8D6E00),
            Color(0xFFFFECB3),
            BadgeIcon.Spinner,
        )
        AICoreEngine.STATUS_UNAVAILABLE -> Quad(
            "Not available on this device",
            Color(0xFF7F1D1D),
            Color(0xFFFFCDD2),
            BadgeIcon.Error,
        )
        AICORE_PROBE_NOT_INSTALLED -> Quad(
            "AICore not installed",
            Color(0xFF7F1D1D),
            Color(0xFFFFCDD2),
            BadgeIcon.Error,
        )
        else /* AICORE_PROBE_PENDING or unknown */ -> Quad(
            "Checking…",
            Color(0xFF424242),                // muted neutral container
            Color(0xFFE0E0E0),
            BadgeIcon.Spinner,
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(bg, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        when (leading) {
            BadgeIcon.Check -> Icon(Icons.Outlined.CheckCircle, null, Modifier.size(14.dp), tint = fg)
            BadgeIcon.Cloud -> Icon(Icons.Outlined.CloudDownload, null, Modifier.size(14.dp), tint = fg)
            BadgeIcon.Error -> Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(14.dp), tint = fg)
            BadgeIcon.Warn -> Icon(Icons.Outlined.WarningAmber, null, Modifier.size(14.dp), tint = fg)
            BadgeIcon.Spinner -> CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = fg,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private enum class BadgeIcon { Check, Cloud, Error, Warn, Spinner }
/** Tuple for AICore badge styling — destructured at the call site. */
private data class Quad(
    val label: String,
    val background: Color,
    val foreground: Color,
    val icon: BadgeIcon,
)

private fun aicoreSubtext(statusCode: Int): String = when (statusCode) {
    AICoreEngine.STATUS_AVAILABLE ->
        "Provided by the Android system service. Inference runs on AICore's chosen backend (NPU / GPU / CPU) — no .litertlm file on disk."
    AICoreEngine.STATUS_DOWNLOADABLE ->
        "AICore is installed but Gemini Nano weights aren't on this device yet. Tap below to provision — the download is handled by the system and can take several minutes on the first run."
    AICoreEngine.STATUS_DOWNLOADING ->
        "AICore is currently downloading Gemini Nano in the background. This screen will update when it reports Available."
    AICoreEngine.STATUS_UNAVAILABLE ->
        "Requires Pixel 8+ with the AICore Developer Preview enrolled, or an OEM build that ships the AICore system service."
    AICORE_PROBE_NOT_INSTALLED ->
        "The AICore system service was not detected. Install it from Play Store / OEM channels, or use a built-in LiteRT-LM model from the list below."
    else /* PROBE_PENDING */ ->
        "Probing AICore status — this typically takes under a second."
}
