package com.localllm.app.server.routes

import android.content.Context
import com.localllm.app.LogManager
import com.localllm.app.RequestTracker
import com.localllm.app.Settings
import com.localllm.app.inference.AiCoreNotReadyException
import com.localllm.app.inference.EngineRegistry
import com.localllm.app.inference.aicore.AICoreEngine
import com.localllm.app.inference.litert.LlmMessageConverter
import com.google.ai.edge.litertlm.Message as LlmMessage
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * `GET /health` — public diagnostics. Unauthenticated by design: monitoring
 * probes shouldn't have to know the API key. Surfaces the queue depth, the
 * cached LiteRT engines, and the live AICore (Gemini Nano) status so a
 * client can tell at a glance whether the default model is usable.
 */
fun Route.healthRoute(
    engineRegistry: EngineRegistry,
    appContext: Context? = null,
    inferenceMutex: Mutex? = null,
) {
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

    /**
     * `POST /health/warm?model=<id>` — force the named engine to load (and,
     * for LiteRT, run a 1-token generation so the JNI runtime is warm). Lets
     * deploy / test scripts wait for first-token-ready instead of guessing.
     *
     * Returns a small JSON envelope: `{model, status, ms, engine_loaded}`.
     *
     * `model` defaults to [Settings.selectedModelId] when omitted. Wrapped in
     * a fresh inference mutex so a warm-up doesn't collide with a real chat
     * request; bounded by a 60s timeout so a wedged engine init doesn't hang
     * a monitoring poll.
     */
    if (appContext != null && inferenceMutex != null) {
        post("/health/warm") {
            val modelParam = call.request.queryParameters["model"]?.trim()
            val modelId = if (modelParam.isNullOrEmpty()) Settings.selectedModelId(appContext) else modelParam
            val t0 = System.nanoTime()
            try {
                val acquired = withTimeout(60_000L) {
                    engineRegistry.acquire(modelId, /*maxTokens=*/null)
                }
                when (acquired) {
                    is EngineRegistry.AcquiredEngine.AiCore -> {
                        try {
                            engineRegistry.ensureAiCoreReady()
                        } catch (notReady: AiCoreNotReadyException) {
                            val label = AICoreEngine.statusLabel(notReady.statusCode)
                            call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                mapOf(
                                    "model" to modelId,
                                    "status" to "aicore_not_ready",
                                    "aicore_status" to label,
                                    "ms" to (System.nanoTime() - t0) / 1_000_000,
                                ),
                            )
                            return@post
                        }
                        // Drive a 1-token completion through the system service so we
                        // know it'll respond before the next real request lands.
                        withTimeout(60_000L) {
                            AICoreEngine.complete(prompt = "Hi", maxOutputTokens = 1)
                        }
                        call.respond(mapOf(
                            "model" to modelId,
                            "status" to "warm",
                            "engine_loaded" to true,
                            "ms" to (System.nanoTime() - t0) / 1_000_000,
                        ))
                    }
                    is EngineRegistry.AcquiredEngine.LiteRt -> {
                        // Hold the inference mutex briefly so we don't collide
                        // with a concurrent /v1/chat/completions on the same
                        // engine — LiteRT-LM doesn't support two live
                        // conversations against the same engine.
                        withTimeout(60_000L) {
                            inferenceMutex.withLock {
                                val conv = LlmMessageConverter.createConversation(
                                    engine = acquired.engine.native,
                                    temperature = 0f,
                                    topK = 1,
                                    systemText = null,
                                    initial = emptyList(),
                                    tools = null,
                                )
                                try {
                                    withContext(Dispatchers.Default) {
                                        conv.sendMessage(LlmMessage.user("Hi"), emptyMap())
                                    }
                                } finally {
                                    try { conv.close() } catch (_: Exception) {}
                                }
                            }
                        }
                        call.respond(mapOf(
                            "model" to modelId,
                            "status" to "warm",
                            "engine_loaded" to true,
                            "ms" to (System.nanoTime() - t0) / 1_000_000,
                        ))
                    }
                }
            } catch (_: TimeoutCancellationException) {
                call.respond(
                    HttpStatusCode.GatewayTimeout,
                    mapOf(
                        "model" to modelId,
                        "status" to "timeout",
                        "ms" to (System.nanoTime() - t0) / 1_000_000,
                    ),
                )
            } catch (e: Throwable) {
                LogManager.e("HealthRoute", "Warm-up failed for $modelId", e)
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf(
                        "model" to modelId,
                        "status" to "error",
                        "error" to (e.message ?: e.javaClass.simpleName),
                        "ms" to (System.nanoTime() - t0) / 1_000_000,
                    ),
                )
            }
        }
    }
}
