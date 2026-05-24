package com.localllm.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localllm.app.R
import com.localllm.app.RequestTracker

@Composable
internal fun StatCardGrid(stats: RequestTracker.Stats) {
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
