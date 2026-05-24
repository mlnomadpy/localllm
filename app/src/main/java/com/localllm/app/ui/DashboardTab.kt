package com.localllm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localllm.app.R
import com.localllm.app.RequestTracker
import com.localllm.app.ui.dashboard.AICoreBenchmarkCard
import com.localllm.app.ui.dashboard.ClientsCard
import com.localllm.app.ui.dashboard.HistoryRow
import com.localllm.app.ui.dashboard.InFlightCard
import com.localllm.app.ui.dashboard.QueuedRequestRow
import com.localllm.app.ui.dashboard.StatCardGrid
import com.localllm.app.ui.dashboard.ThroughputCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
