package com.localllm.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.app.R
import com.localllm.app.RequestTracker
import com.localllm.app.Settings
import com.localllm.app.inference.aicore.AICoreBenchmark
import com.localllm.app.inference.aicore.AICoreBenchmarkCache
import com.localllm.app.inference.aicore.AICoreEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

@Composable
fun DashboardTab(coroutineScope: CoroutineScope) {
    val queue by RequestTracker.queue.collectAsState()
    val current by RequestTracker.current.collectAsState()
    val history by RequestTracker.history.collectAsState()
    val stats by RequestTracker.stats.collectAsState()

    // Live tick for elapsed-time displays. Only runs while a request is in flight.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(current != null) {
        if (current != null) {
            while (true) {
                now = System.currentTimeMillis()
                delay(250)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // === 2x2 stat card grid ===
        item {
            StatCardGrid(stats = stats)
        }

        // === Throughput sparkline ===
        item {
            ThroughputCard(history = history)
        }

        // === In-flight emphasis ===
        item {
            InFlightCard(current = current, queueDepth = queue.size, now = now)
        }

        // === AICore (Gemini Nano) speed test ===
        item {
            AICoreBenchmarkCard(coroutineScope = coroutineScope)
        }

        // Optional queue list when there are waiters
        if (queue.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.dash_waiting, queue.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        queue.forEachIndexed { idx, e ->
                            QueuedRequestRow(position = idx + 1, entry = e, now = now)
                        }
                    }
                }
            }
        }

        // Per-client summary — recomputed from current/queue/history each
        // frame. Empty until at least one request has hit the server, so it
        // gracefully self-hides on first-run.
        item {
            val clients = remember(current, queue, history) {
                RequestTracker.clientSummaries()
            }
            if (clients.isNotEmpty()) ClientsCard(clients = clients)
        }

        // === Cumulative stats block (legacy reset row) ===
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.dash_stats),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { coroutineScope.launch { RequestTracker.resetStats() } },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) { Text(stringResource(R.string.dash_reset)) }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.dash_history, history.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                if (history.isNotEmpty()) {
                    TextButton(
                        onClick = { coroutineScope.launch { RequestTracker.clearHistory() } },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) { Text(stringResource(R.string.dash_clear)) }
                }
            }
        }

        if (history.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.dash_no_history),
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(8.dp)
                )
            }
        } else {
            items(history, key = { it.id }) { entry ->
                HistoryRow(entry = entry)
            }
        }
    }
}

// =====================================================================
// Stat card 2x2 grid
// =====================================================================

@Composable
private fun StatCardGrid(stats: RequestTracker.Stats) {
    // p50 of last 50 inferences. Stats.avgLatencyMs is mean — keep it as it's
    // the only built-in metric; caption simply notes "last 50 reqs".
    val totalToday = stats.totalRequests.toString()
    val latencyValue = if (stats.totalCompleted > 0) "${stats.avgLatencyMs} ms" else "—"
    val tokRate = if (stats.avgChunksPerSec > 0) "%.1f tok/s".format(stats.avgChunksPerSec) else "— tok/s"
    val errPct: Float = stats.errorRate * 100f
    val errStr = if (stats.totalRequests > 0) "%.1f%%".format(errPct) else "—"

    val errColor = when {
        stats.totalRequests == 0L -> MaterialTheme.colorScheme.onSurface
        errPct < 1f -> Color(0xFF22C55E) // green
        errPct < 5f -> Color(0xFFF59E0B) // amber
        else -> MaterialTheme.colorScheme.error
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(
                icon = Icons.Outlined.QueryStats,
                value = totalToday,
                caption = stringResource(R.string.dash_stat_total_caption),
                label = stringResource(R.string.dash_stat_total),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                icon = Icons.Outlined.Timer,
                value = latencyValue,
                caption = stringResource(R.string.dash_stat_latency_caption),
                label = stringResource(R.string.dash_stat_avg_latency),
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(
                icon = Icons.Outlined.Bolt,
                value = tokRate,
                caption = stringResource(R.string.dash_stat_tokens_caption),
                label = stringResource(R.string.dash_stat_avg_tokens),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                icon = Icons.Outlined.ErrorOutline,
                value = errStr,
                caption = stringResource(R.string.dash_stat_err_rate_caption),
                label = stringResource(R.string.dash_stat_err_rate),
                valueColor = errColor,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    caption: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// =====================================================================
// Throughput sparkline
// =====================================================================

@Composable
private fun ThroughputCard(history: List<RequestTracker.Entry>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.dash_throughput_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // Build series: history is newest-first; reverse so chart reads left → right.
            // Filter out NaN / non-finite tok/s defensively.
            val series: List<Float> = remember(history) {
                history.asReversed()
                    .map { it.chunksPerSec }
                    .filter { it.isFinite() && it >= 0f }
                    .takeLast(50)
            }

            if (series.isEmpty()) {
                Text(
                    text = stringResource(R.string.dash_throughput_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Sparkline(series = series)
            }
        }
    }
}

@Composable
private fun Sparkline(series: List<Float>) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant
    val maxVal = (series.maxOrNull() ?: 0f).coerceAtLeast(0.0001f)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(100.dp)) {
        val density = LocalDensity.current
        val strokePx = with(density) { 2.dp.toPx() }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val baselineY = h - strokePx
            val topPad = strokePx * 2

            if (series.size == 1) {
                // Single point: draw a small dot centered horizontally.
                val cx = w / 2f
                val cy = baselineY - (series[0] / maxVal) * (h - topPad - strokePx)
                drawCircle(color = primary, radius = strokePx * 1.5f, center = Offset(cx, cy))
                // Baseline.
                drawLine(
                    color = onSurfaceVar.copy(alpha = 0.25f),
                    start = Offset(0f, baselineY),
                    end = Offset(w, baselineY),
                    strokeWidth = 1f
                )
                return@Canvas
            }

            // Map series to canvas-space points.
            val pts = ArrayList<Offset>(series.size)
            val stepX = w / (series.size - 1).coerceAtLeast(1)
            for (i in series.indices) {
                val v = series[i]
                val x = i * stepX
                val y = baselineY - (v / maxVal) * (h - topPad - strokePx)
                pts.add(Offset(x, y))
            }

            // Build smoothed path using Catmull-Rom → cubic Bezier conversion.
            val linePath = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 0 until pts.size - 1) {
                    val p0 = if (i == 0) pts[i] else pts[i - 1]
                    val p1 = pts[i]
                    val p2 = pts[i + 1]
                    val p3 = if (i + 2 < pts.size) pts[i + 2] else p2
                    // Catmull-Rom → Bezier control points (uniform, tension=0.5).
                    val c1 = Offset(
                        p1.x + (p2.x - p0.x) / 6f,
                        p1.y + (p2.y - p0.y) / 6f
                    )
                    val c2 = Offset(
                        p2.x - (p3.x - p1.x) / 6f,
                        p2.y - (p3.y - p1.y) / 6f
                    )
                    cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
                }
            }

            // Fill path: clone line + close to baseline.
            val fillPath = Path().apply {
                addPath(linePath)
                lineTo(pts.last().x, baselineY)
                lineTo(pts.first().x, baselineY)
                close()
            }

            // Baseline.
            drawLine(
                color = onSurfaceVar.copy(alpha = 0.25f),
                start = Offset(0f, baselineY),
                end = Offset(w, baselineY),
                strokeWidth = 1f
            )
            drawPath(path = fillPath, brush = SolidColor(primary.copy(alpha = 0.20f)))
            drawPath(
                path = linePath,
                brush = SolidColor(primary),
                style = Stroke(width = strokePx)
            )
        }

        // Max-Y label, top-right.
        Text(
            text = "max %.1f tok/s".format(maxVal),
            style = MaterialTheme.typography.bodySmall,
            color = onSurfaceVar,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp)
        )
    }
}

// =====================================================================
// In-flight emphasis card
// =====================================================================

@Composable
private fun InFlightCard(current: RequestTracker.Entry?, queueDepth: Int, now: Long) {
    if (current == null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier.padding(14.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Pause,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.dash_idle_status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                QueueBadge(depth = queueDepth)
            }
        }
        return
    }

    val infinite = rememberInfiniteTransition(label = "boltSpin")
    val angle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "boltAngle"
    )

    val primary = MaterialTheme.colorScheme.primary
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, primary),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Bolt,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(22.dp).rotate(angle)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "#${current.id} ${current.model}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.dash_running_label, formatMs(current.inferenceMs(now))),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                QueueBadge(depth = queueDepth)
            }

            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            val elapsed = current.inferenceMs(now)
            val rate = if (elapsed > 250 && current.chunkCount > 0) {
                current.chunkCount.toFloat() * 1000f / elapsed
            } else 0f
            Text(
                text = "${current.chunkCount} tok · %.1f tok/s · waited %s".format(
                    rate, formatMs(current.queueWaitMs(now))
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// =====================================================================
// Queue row (small reuse)
// =====================================================================

@Composable
private fun QueueBadge(depth: Int) {
    val color = when {
        depth == 0 -> MaterialTheme.colorScheme.surfaceVariant
        depth <= 2 -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    Box(
        modifier = Modifier
            .background(color, shape = MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.dash_queue_label, depth),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Per-client breakdown. Surfaces who's hammering the server right now,
 * who has the longest history with us, and where the queue/inflight load
 * is concentrated — the "is one sibling app monopolising the LLM?"
 * question the multi-app scenario raises.
 */
@Composable
private fun ClientsCard(clients: List<RequestTracker.ClientSummary>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.dash_clients),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            clients.forEach { c ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = c.client,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(
                                R.string.dash_client_stats,
                                c.completed, c.errored, c.totalChunks, c.avgInferenceMs,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    if (c.inFlight > 0 || c.queued > 0) {
                        Text(
                            text = if (c.inFlight > 0) stringResource(R.string.dash_client_inflight, c.queued)
                                   else stringResource(R.string.dash_client_queued, c.queued),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (c.inFlight > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .background(
                                    if (c.inFlight > 0) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueuedRequestRow(position: Int, entry: RequestTracker.Entry, now: Long) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "#$position",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.width(28.dp)
        )
        Text(
            text = entry.model,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${entry.queueWaitMs(now)} ms",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            fontFamily = FontFamily.Monospace
        )
    }
}

// =====================================================================
// History rows (expandable on tap)
// =====================================================================

@Composable
private fun HistoryRow(entry: RequestTracker.Entry) {
    val (statusIcon, statusColor) = when (entry.state) {
        RequestTracker.State.COMPLETED -> Icons.Outlined.CheckCircle to Color(0xFF22C55E)
        RequestTracker.State.CANCELLED -> Icons.Outlined.Cancel to Color(0xFFC5C6C7)
        RequestTracker.State.ERRORED -> Icons.Outlined.Error to MaterialTheme.colorScheme.error
        else -> Icons.Outlined.Cancel to Color(0xFFC5C6C7)
    }

    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                val infMs = entry.inferenceMs()
                Text(
                    text = "${entry.model} · ${formatMs(infMs)} · ${entry.chunkCount} tok",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                Text(
                    text = "#${entry.id}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
            // Second line: time + remote (Entry has no remote IP — show time only).
            Text(
                text = relativeTime(entry.completedAt ?: entry.enqueuedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "tok/s %.2f · waited %s · prompt %d msgs / %d chars · out %d chars".format(
                            entry.chunksPerSec,
                            formatMs(entry.queueWaitMs()),
                            entry.messageCount,
                            entry.promptChars,
                            entry.outputChars
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                    if (entry.stream) {
                        Text(
                            text = "stream",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (entry.error != null) {
                        Text(
                            text = entry.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

internal fun formatMs(ms: Long): String = when {
    ms < 1000 -> "$ms ms"
    ms < 60_000 -> "%.1fs".format(ms / 1000f)
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
}

internal fun relativeTime(timestamp: Long): String {
    val delta = (System.currentTimeMillis() - timestamp) / 1000
    return when {
        delta < 5 -> "just now"
        delta < 60 -> "${delta}s ago"
        delta < 3600 -> "${delta / 60}m ago"
        delta < 86400 -> "${delta / 3600}h ago"
        else -> "${delta / 86400}d ago"
    }
}

// =====================================================================
// AICore (Gemini Nano) speed-test card
// =====================================================================

/**
 * Posts to the local server's `/v1/aicore/benchmark` endpoint and renders
 * the latest result. Uses the running server rather than calling
 * AICoreBenchmark directly so the timing reflects what an external HTTP
 * client would observe (and so the cached result on the server stays in
 * sync with what the UI shows).
 *
 * Disabled when AICore reports anything other than STATUS_AVAILABLE on
 * this device — surfaces the status label so the user knows whether to
 * wait for a download or give up entirely.
 */
@Composable
private fun AICoreBenchmarkCard(coroutineScope: CoroutineScope) {
    val context = LocalContext.current

    var statusCode by remember { mutableStateOf<Int?>(null) }
    var lastResult by remember { mutableStateOf<AICoreBenchmark.BenchmarkResult?>(null) }
    var running by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        statusCode = runCatching {
            withContext(Dispatchers.IO) { AICoreEngine.checkStatusCode() }
        }.getOrNull()
        lastResult = AICoreBenchmarkCache.latest
    }

    val available = statusCode == AICoreEngine.STATUS_AVAILABLE
    val statusLabel = statusCode?.let { AICoreEngine.statusLabel(it) } ?: "probing…"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "AICore Benchmark",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            val deviceLine = "${android.os.Build.MODEL ?: "unknown"} · ${
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) android.os.Build.SOC_MODEL else "unknown"
            }"
            Text(
                text = deviceLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "AICore: $statusLabel",
                style = MaterialTheme.typography.bodySmall,
                color = if (available) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.error,
                fontFamily = FontFamily.Monospace,
            )

            val r = lastResult
            if (r != null) {
                val tsFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Last run: ${tsFmt.format(Date(r.timestamp))} (${relativeTime(r.timestamp)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BenchmarkStat("avg TTFT", "%.0f ms".format(r.avgTtftMs), Modifier.weight(1f))
                    BenchmarkStat("avg tok/s", "%.1f".format(r.avgTokensPerSec), Modifier.weight(1f))
                    BenchmarkStat("tokens", r.totalTokensGenerated.toString(), Modifier.weight(1f))
                }
            } else {
                Text(
                    text = "No benchmark run yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            errorMsg?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        if (running || !available) return@Button
                        running = true
                        errorMsg = null
                        coroutineScope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                runBenchmarkViaHttp(context)
                            }
                            outcome.fold(
                                onSuccess = {
                                    lastResult = it
                                    statusCode = runCatching {
                                        withContext(Dispatchers.IO) { AICoreEngine.checkStatusCode() }
                                    }.getOrNull()
                                },
                                onFailure = { errorMsg = it.message ?: it.javaClass.simpleName },
                            )
                            running = false
                        }
                    },
                    enabled = available && !running,
                ) {
                    Text(if (running) "Running…" else "Run Benchmark")
                }
                if (!available && statusCode != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AICore not available on this device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BenchmarkStat(label: String, value: String, modifier: Modifier = Modifier) {
    OutlinedCard(modifier = modifier) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** Single shared OkHttp instance — generous read timeout because a cold
 *  AICore run with multiple prompts can take 30+ seconds. */
private val benchmarkHttp by lazy {
    OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

/**
 * Fire the POST and parse the response into a [AICoreBenchmark.BenchmarkResult].
 * Returns a Result so the caller can render errors without try/catch noise.
 * Uses Gson — already a transitive dep of Ktor's gson serializer.
 */
private fun runBenchmarkViaHttp(context: android.content.Context): Result<AICoreBenchmark.BenchmarkResult> = runCatching {
    val port = Settings.port(context)
    val apiKey = Settings.apiKey(context)
    val body = "{}".toRequestBody("application/json".toMediaType())
    val builder = OkRequest.Builder()
        .url("http://127.0.0.1:$port/v1/aicore/benchmark")
        .post(body)
    if (apiKey.isNotEmpty()) builder.header("Authorization", "Bearer $apiKey")

    benchmarkHttp.newCall(builder.build()).execute().use { resp ->
        val raw = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            val pretty = runCatching {
                val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
                obj.getAsJsonObject("error")?.get("message")?.asString
            }.getOrNull() ?: raw.take(200)
            error("HTTP ${resp.code}: $pretty")
        }
        com.google.gson.Gson().fromJson(raw, AICoreBenchmark.BenchmarkResult::class.java)
            ?: error("Empty benchmark response")
    }
}
