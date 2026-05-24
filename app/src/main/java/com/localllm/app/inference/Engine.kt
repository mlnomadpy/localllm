package com.localllm.app.inference

import com.localllm.app.Backend
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over a single, ready-to-use generative engine. Implementations:
 *  - [com.localllm.app.inference.litert.LiteRtEngine] — LiteRT-LM in-process
 *  - [com.localllm.app.inference.aicore.AICoreEngineAdapter] — Gemini Nano via AICore
 *
 * The interface is intentionally narrow — the OpenAI-shaped chat handler only
 * needs a flattened prompt for AICore, and a richer per-message contract for
 * LiteRT (which is wired through the LiteRT-specific helpers, not this
 * interface). What this interface unifies is the **lifecycle and identity**
 * concerns the [EngineRegistry] cares about: which backend, which model id,
 * and how to release native resources.
 */
interface Engine : AutoCloseable {
    /** The model id (catalog id, e.g. `gemma-4-e2b` or `gemini-nano-aicore`). */
    val modelId: String

    /** The backend this engine was built for. Drawn from [com.localllm.app.ModelInfo.backend]. */
    val backend: Backend

    /** Free any native resources. Idempotent — multiple closes are safe. */
    override fun close()
}

/**
 * Streaming adapter for engines whose API is "give me a flat text prompt,
 * I'll stream back text chunks". Used by AICore; LiteRT goes through its
 * own richer per-message conversation path and does not implement this.
 */
interface TextStreamingEngine : Engine {
    /** Stream cumulative text from the engine. The caller computes deltas. */
    fun streamText(
        prompt: String,
        temperature: Float?,
        topK: Int?,
        maxOutputTokens: Int?,
    ): Flow<String>

    /** Blocking variant. Returns the final assembled text. */
    suspend fun completeText(
        prompt: String,
        temperature: Float?,
        topK: Int?,
        maxOutputTokens: Int?,
    ): String
}
