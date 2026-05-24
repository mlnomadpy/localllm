package com.localllm.app.inference.aicore

import com.localllm.app.Backend
import com.localllm.app.inference.Engine
import com.localllm.app.inference.TextStreamingEngine
import kotlinx.coroutines.flow.Flow

/**
 * [Engine] adapter around the static [AICoreEngine] singleton. AICore owns
 * the model weights system-side, so there's no per-instance native resource
 * to manage — `close()` is a no-op. The adapter exists purely so the
 * registry can treat AICore uniformly alongside LiteRT engines.
 */
class AICoreEngineAdapter : TextStreamingEngine {
    override val modelId: String = AICoreEngine.MODEL_ID
    override val backend: Backend = Backend.AICORE

    override fun streamText(
        prompt: String,
        temperature: Float?,
        topK: Int?,
        maxOutputTokens: Int?,
    ): Flow<String> = AICoreEngine.stream(prompt, temperature, topK, maxOutputTokens)

    override suspend fun completeText(
        prompt: String,
        temperature: Float?,
        topK: Int?,
        maxOutputTokens: Int?,
    ): String = AICoreEngine.complete(prompt, temperature, topK, maxOutputTokens)

    override fun close() { /* system-owned — nothing to release */ }
}
