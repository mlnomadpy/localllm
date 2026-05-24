package com.localllm.app.inference.litert

import com.google.ai.edge.litertlm.Engine as LiteRtNativeEngine
import com.localllm.app.Backend
import com.localllm.app.LogManager
import com.localllm.app.inference.Engine
import com.localllm.app.inference.EngineKey

/**
 * [Engine] wrapper around a LiteRT-LM native engine. Holds the cache key
 * separately so the registry can correlate eviction with conversation
 * cleanup, without exposing the LiteRT-LM SDK type on the [Engine] surface.
 *
 * The chat route still talks to the native engine directly for conversation
 * lifecycle (LiteRT-LM's Conversation API is too rich to wedge through the
 * narrow [Engine] interface) — see `ChatRoute.kt`. This wrapper exists so the
 * registry can hold both LiteRT and AICore engines uniformly.
 */
class LiteRtEngine(
    override val modelId: String,
    override val backend: Backend,
    val native: LiteRtNativeEngine,
    val cacheKey: EngineKey,
) : Engine {

    @Volatile private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        try { native.close() } catch (e: Exception) {
            LogManager.e("LiteRtEngine", "Error closing native engine ${cacheKey.asString()}", e)
        }
    }
}
