package com.localllm.app.inference.aicore

import android.os.Build
import kotlinx.coroutines.flow.collectIndexed

/**
 * Speed-test harness for the AICore (Gemini Nano) backend.
 *
 * Measures time-to-first-token (TTFT), total wall-clock time, and an
 * *approximate* tokens/sec for a small fixed prompt set. AICore's SDK
 * doesn't expose token counts — we approximate by splitting on whitespace.
 * Biases toward English and undercounts code / punctuation-heavy output,
 * but is consistent enough for relative comparisons across runs on the
 * same device.
 *
 * Streaming is used purely so we can capture TTFT from the first non-empty
 * delta. The final `response` is the cumulative text from the last
 * emission of the stream.
 */
object AICoreBenchmark {

    /** Four prompts of varying shape — short arithmetic, medium technical,
     *  creative-short, structured-JSON. */
    val DEFAULT_PROMPTS: List<String> = listOf(
        "What is 2+2?",
        "Explain the difference between TCP and UDP in one paragraph.",
        "Write a haiku about Android development.",
        "List 5 cities in France as a JSON array.",
    )

    data class BenchmarkRun(
        val prompt: String,
        val response: String,
        val ttftMs: Long,
        val totalMs: Long,
        val tokenCount: Int,
        val tokensPerSec: Double,
    )

    data class BenchmarkResult(
        val runs: List<BenchmarkRun>,
        val avgTtftMs: Double,
        val avgTotalMs: Double,
        val avgTokensPerSec: Double,
        val totalTokensGenerated: Int,
        val deviceModel: String,
        val socModel: String,
        val aicoreStatus: String,
        val timestamp: Long,
    )

    /**
     * Approximate token count. AICore's SDK doesn't surface token usage,
     * so we use whitespace-split as a stand-in. Empty input → 0.
     */
    internal fun approxTokenCount(text: String): Int {
        if (text.isBlank()) return 0
        return text.trim().split(Regex("\\s+")).size
    }

    private fun socModel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL ?: "unknown" else "unknown"

    private suspend fun runOne(prompt: String): BenchmarkRun {
        val start = System.nanoTime()
        var ttftNs: Long = -1L
        var cumulative = ""
        AICoreEngine.stream(prompt).collectIndexed { _, full ->
            val delta = when {
                full.startsWith(cumulative) && full.length > cumulative.length ->
                    full.substring(cumulative.length)
                full == cumulative -> ""
                else -> full
            }
            if (delta.isNotEmpty() && ttftNs < 0L) ttftNs = System.nanoTime() - start
            cumulative = if (full.startsWith(cumulative)) full else cumulative + delta
        }
        val totalNs = System.nanoTime() - start
        if (ttftNs < 0L) ttftNs = totalNs // no chunk ever arrived; degenerate
        val tokens = approxTokenCount(cumulative)
        val totalMs = totalNs / 1_000_000L
        val ttftMs = ttftNs / 1_000_000L
        val tps = if (totalMs > 0) tokens.toDouble() / (totalMs.toDouble() / 1000.0) else 0.0
        return BenchmarkRun(
            prompt = prompt,
            response = cumulative,
            ttftMs = ttftMs,
            totalMs = totalMs,
            tokenCount = tokens,
            tokensPerSec = tps,
        )
    }

    /**
     * Run [warmupRuns] discarded inferences (to amortize cold-start cost on
     * the first AICore invocation per process), then time each of [prompts]
     * in order and return aggregated stats.
     *
     * Caller is responsible for confirming `AICoreEngine.checkStatusCode()
     * == STATUS_AVAILABLE` first — this method will surface any underlying
     * SDK exception verbatim.
     */
    suspend fun runBenchmark(
        prompts: List<String> = DEFAULT_PROMPTS,
        warmupRuns: Int = 1,
    ): BenchmarkResult {
        val warmupPrompt = prompts.firstOrNull() ?: "Hello."
        repeat(warmupRuns) {
            try { AICoreEngine.complete(warmupPrompt) } catch (_: Throwable) { /* warmup losses ignored */ }
        }

        val runs = prompts.map { runOne(it) }
        val n = runs.size.coerceAtLeast(1)
        val avgTtft = runs.sumOf { it.ttftMs }.toDouble() / n
        val avgTotal = runs.sumOf { it.totalMs }.toDouble() / n
        val avgTps = runs.sumOf { it.tokensPerSec } / n
        val totalTok = runs.sumOf { it.tokenCount }

        val status = try {
            AICoreEngine.statusLabel(AICoreEngine.checkStatusCode())
        } catch (e: Throwable) {
            "error(${e.javaClass.simpleName})"
        }

        return BenchmarkResult(
            runs = runs,
            avgTtftMs = avgTtft,
            avgTotalMs = avgTotal,
            avgTokensPerSec = avgTps,
            totalTokensGenerated = totalTok,
            deviceModel = Build.MODEL ?: "unknown",
            socModel = socModel(),
            aicoreStatus = status,
            timestamp = System.currentTimeMillis(),
        )
    }
}

/**
 * Process-wide cache for the most-recent benchmark result. Allows
 * `GET /v1/aicore/benchmark` to serve a snapshot without re-running.
 * Reads are lock-free via @Volatile; writes happen at end-of-POST only.
 */
object AICoreBenchmarkCache {
    @Volatile var latest: AICoreBenchmark.BenchmarkResult? = null
}
