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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.Memory
import com.localllm.app.AVAILABLE_MODELS
import com.localllm.app.Backend
import com.localllm.app.ModelInfo
import com.localllm.app.isVirtual
import com.localllm.app.matchesCurrentSoc
import com.localllm.app.npuSocLabel
import com.localllm.app.R

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
            // Custom URLs are assumed to be portable CPU LiteRT-LM bundles —
            // we have no way to know otherwise. Catalog-declared NPU/GPU
            // models live in AVAILABLE_MODELS, not here.
            backend = Backend.LITERT_CPU,
        )
    }
    val all = builtIn + custom
    val nothingInstalled = existingModels.isEmpty() && activeDownloads.isEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (nothingInstalled) {
            item { EmptyHeroCard() }
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
            // Title
            Text(
                text = model.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

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
            } else if (model.isVirtual) {
                // Virtual entries (AICore etc.) have no file on disk and no
                // download URL. Surface the "managed by the system" hint and
                // hide the Download/Delete buttons that would otherwise fire
                // a DownloadManager request against an empty URL and stall
                // the active-downloads poll loop.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Memory,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Provided by the system — no download needed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
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
