package com.localllm.app.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localllm.app.R
import com.localllm.app.RequestTracker

@Composable
internal fun ThroughputCard(history: List<RequestTracker.Entry>) {
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
