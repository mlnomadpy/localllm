package com.localllm.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localllm.app.Settings
import com.localllm.app.inference.aicore.AICoreBenchmark
import com.localllm.app.inference.aicore.AICoreBenchmarkCache
import com.localllm.app.inference.aicore.AICoreEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
internal fun AICoreBenchmarkCard(coroutineScope: CoroutineScope) {
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
