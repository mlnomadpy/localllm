package com.localllm.app.server.routes

import com.localllm.app.RequestTracker
import com.localllm.app.inference.EngineRegistry
import com.localllm.app.inference.aicore.AICoreEngine
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /health` — public diagnostics. Unauthenticated by design: monitoring
 * probes shouldn't have to know the API key. Surfaces the queue depth, the
 * cached LiteRT engines, and the live AICore (Gemini Nano) status so a
 * client can tell at a glance whether the default model is usable.
 */
fun Route.healthRoute(engineRegistry: EngineRegistry) {
    get("/health") {
        // Probe AICore inline. `checkStatusCode()` is suspend; we swallow
        // throws because the SDK throwing (typically ErrorCode -101) is the
        // canonical "AICore service not installed" signal — we report that
        // state rather than 500ing the health endpoint.
        val aicoreBlock: Map<String, Any?> = try {
            val code = AICoreEngine.checkStatusCode()
            mapOf(
                "status_code" to code,
                "status" to AICoreEngine.statusLabel(code),
                "model_id" to AICoreEngine.MODEL_ID,
                "is_default" to true,
            )
        } catch (e: Throwable) {
            mapOf(
                "status_code" to null,
                "status" to "unavailable",
                "model_id" to AICoreEngine.MODEL_ID,
                "is_default" to true,
                "error" to (e.message ?: e.javaClass.simpleName),
            )
        }

        call.respond(mapOf(
            "status" to "ok",
            "service" to "localllm-android",
            "version" to "1.0",
            "queue_depth" to RequestTracker.queue.value.size,
            "engines_loaded" to engineRegistry.engineCount(),
            "engines" to engineRegistry.snapshot().map { e ->
                mapOf(
                    "key" to e.cacheKey,
                    "backend" to e.backend,
                    "attempts" to e.attempts.map {
                        mapOf(
                            "backend" to it.backend,
                            "result" to it.result,
                            "duration_ms" to it.durationMs,
                        )
                    },
                )
            },
            "aicore" to aicoreBlock,
        ))
    }
}
