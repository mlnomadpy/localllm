package com.localllm.app.server.routes

import android.content.Context
import com.google.gson.JsonParser
import com.localllm.app.LogManager
import com.localllm.app.inference.aicore.AICoreBenchmark
import com.localllm.app.inference.aicore.AICoreBenchmarkCache
import com.localllm.app.inference.aicore.AICoreEngine
import com.localllm.app.server.auth.authorize
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.concurrent.atomic.AtomicLong

/**
 * `POST /v1/aicore/benchmark` — speed test for Gemini Nano. Body is
 * optional `{"prompts": [...], "warmup": N}`; empty / malformed bodies
 * fall back to the [AICoreBenchmark.DEFAULT_PROMPTS] set so curl can
 * trigger it with no payload.
 *
 * `GET /v1/aicore/benchmark` — serve the most-recent cached result. 404
 * when no benchmark has been run since process start.
 *
 * Refuses when AICore is not AVAILABLE — no fallback, by policy.
 */
fun Route.benchmarkRoute(
    appContext: Context,
    lastActivityAt: AtomicLong,
) {
    post("/v1/aicore/benchmark") {
        if (!authorize(call, appContext)) return@post
        lastActivityAt.set(System.currentTimeMillis())

        val statusCode = try {
            AICoreEngine.checkStatusCode()
        } catch (e: Throwable) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to mapOf(
                    "type" to "aicore_unavailable",
                    "message" to "AICore status probe failed: ${e.message ?: e.javaClass.simpleName}",
                    "status" to "error",
                )),
            )
            return@post
        }
        if (statusCode != AICoreEngine.STATUS_AVAILABLE) {
            val label = AICoreEngine.statusLabel(statusCode)
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to mapOf(
                    "type" to "aicore_unavailable",
                    "message" to "AICore (Gemini Nano) is $label on this device.",
                    "status" to label,
                )),
            )
            return@post
        }

        var prompts: List<String> = AICoreBenchmark.DEFAULT_PROMPTS
        var warmup = 1
        try {
            val raw = call.receive<String>()
            if (raw.isNotBlank()) {
                val obj = JsonParser.parseString(raw).asJsonObject
                obj.get("prompts")?.takeIf { it.isJsonArray }?.asJsonArray?.let { arr ->
                    val parsed = arr.mapNotNull { it?.asString }.filter { it.isNotBlank() }
                    if (parsed.isNotEmpty()) prompts = parsed
                }
                obj.get("warmup")?.takeIf { it.isJsonPrimitive }?.asInt?.let { warmup = it.coerceIn(0, 5) }
            }
        } catch (_: Throwable) {
            // Body optional — use defaults.
        }

        try {
            val result = AICoreBenchmark.runBenchmark(prompts = prompts, warmupRuns = warmup)
            AICoreBenchmarkCache.latest = result
            call.respond(result)
        } catch (e: Throwable) {
            LogManager.e("BenchmarkRoute", "AICore benchmark failed: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to mapOf(
                    "type" to "benchmark_failed",
                    "message" to (e.message ?: e.javaClass.simpleName),
                    "status" to AICoreEngine.statusLabel(statusCode),
                )),
            )
        }
    }

    get("/v1/aicore/benchmark") {
        if (!authorize(call, appContext)) return@get
        val last = AICoreBenchmarkCache.latest
        if (last == null) {
            call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to mapOf(
                    "type" to "no_benchmark",
                    "message" to "No benchmark has been run yet. POST to this endpoint first.",
                )),
            )
            return@get
        }
        call.respond(last)
    }
}
