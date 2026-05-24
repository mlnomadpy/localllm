package com.localllm.app.server.routes

import com.localllm.app.RequestTracker
import com.localllm.app.server.ServerDeps
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /health` — public diagnostics. Unauthenticated by design: monitoring
 * probes shouldn't have to know the API key. Surfaces the queue depth and
 * the cached engines (now declarative — each entry carries the backend it
 * was built for, no AUTO-chain decision tree to render).
 */
fun Route.healthRoute(deps: ServerDeps) {
    get("/health") {
        call.respond(mapOf(
            "status" to "ok",
            "service" to "localllm-android",
            "version" to "1.0",
            "queue_depth" to RequestTracker.queue.value.size,
            "engines_loaded" to deps.engineRegistry.engineCount(),
            "engines" to deps.engineRegistry.snapshot().map { e ->
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
        ))
    }
}
