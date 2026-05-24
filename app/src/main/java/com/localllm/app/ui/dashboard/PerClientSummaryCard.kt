package com.localllm.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localllm.app.R
import com.localllm.app.RequestTracker

/**
 * Per-client breakdown. Surfaces who's hammering the server right now,
 * who has the longest history with us, and where the queue/inflight load
 * is concentrated — the "is one sibling app monopolising the LLM?"
 * question the multi-app scenario raises.
 */
@Composable
internal fun ClientsCard(clients: List<RequestTracker.ClientSummary>) {
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
