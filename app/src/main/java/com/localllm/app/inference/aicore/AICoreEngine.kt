package com.localllm.app.inference.aicore

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Thin wrapper around ML Kit's GenAI Prompt API (Gemini Nano via AICore).
 *
 * Exposed under the magic model id [MODEL_ID]. When `req.model` matches we
 * bypass the LiteRT-LM engine cache entirely and stream directly from
 * AICore — no `.litertlm` file, no vendor delegate, no engine init.
 *
 * AICore picks the device backend (NPU/GPU/CPU) automatically; the SDK
 * surface deliberately doesn't expose backend selection. Availability is
 * device-gated — see [checkStatusCode]. On a fresh device the model may
 * report DOWNLOADABLE / DOWNLOADING and the user has to wait for AICore
 * to fetch the weights through the system service before the first call
 * will succeed.
 *
 * Beta — `com.google.mlkit:genai-prompt:1.0.0-beta2`. API surface may
 * shift before GA.
 */
object AICoreEngine {

    /** OpenAI-style model id clients pass to `/v1/chat/completions`. */
    const val MODEL_ID = "gemini-nano-aicore"

    /**
     * Matches `com.google.mlkit.genai.common.FeatureStatus` constants
     * (declaration order: UNAVAILABLE=0, DOWNLOADABLE=1, DOWNLOADING=2,
     * AVAILABLE=3). Mirroring them here keeps consumers off the
     * `genai-common` direct dependency.
     */
    const val STATUS_UNAVAILABLE = 0
    const val STATUS_DOWNLOADABLE = 1
    const val STATUS_DOWNLOADING = 2
    const val STATUS_AVAILABLE = 3

    @Volatile private var clientRef: GenerativeModel? = null

    /**
     * On bleeding-edge devices (Pixel 10) the STABLE rollout of Gemini Nano
     * hasn't happened yet, so the default `Generation.getClient()` (which
     * picks STABLE) returns `STATUS_UNAVAILABLE`. PREVIEW is the AICore
     * Developer Preview track and is what's actually shipping there.
     * Choosing FAST over FULL also keeps latency closer to interactive.
     * If a downstream caller needs STABLE, expose a config override later.
     */
    private fun client(): GenerativeModel {
        clientRef?.let { return it }
        synchronized(this) {
            clientRef?.let { return it }
            val cfg = generationConfig {
                modelConfig = modelConfig {
                    releaseStage = ModelReleaseStage.PREVIEW
                    preference = ModelPreference.FAST
                }
            }
            val c = Generation.getClient(cfg)
            clientRef = c
            return c
        }
    }

    /**
     * Make sure the model is actually present before [stream] / [complete].
     *
     * - AVAILABLE → no-op, return [STATUS_AVAILABLE].
     * - DOWNLOADABLE → call `download()` and block until the SDK reports
     *   `DownloadCompleted` (or throws on `DownloadFailed`), then re-check.
     * - DOWNLOADING → wait for the existing download by collecting `download()`
     *   too — the SDK serialises and the same Flow surfaces progress for an
     *   already-running pull.
     * - UNAVAILABLE → return unchanged; caller surfaces the structured error.
     *
     * Returns the post-action status code so the caller can decide what to
     * do (proceed, retry later, fail with a useful message).
     */
    suspend fun ensureReady(): Int {
        val initial = client().checkStatus()
        if (initial != STATUS_DOWNLOADABLE && initial != STATUS_DOWNLOADING) return initial
        client().download().collect { ds ->
            when (ds) {
                is DownloadStatus.DownloadFailed -> throw ds.e
                DownloadStatus.DownloadCompleted -> { /* terminal, Flow will end */ }
                else -> { /* DownloadStarted / DownloadProgress — just wait */ }
            }
        }
        return client().checkStatus()
    }

    /**
     * Synchronous-shape probe — `checkStatus()` is a coroutine on the SDK
     * side so callers in a non-suspend context can wrap this. Returns one
     * of [STATUS_AVAILABLE] / [STATUS_DOWNLOADABLE] / [STATUS_DOWNLOADING]
     * / [STATUS_UNAVAILABLE]. Treat [STATUS_AVAILABLE] as the only state
     * where inference will actually succeed without further setup.
     *
     * NB: this only reports MODEL download state, not whether the AICore
     * system service itself is present. On a device without AICore the
     * call can still succeed and return [STATUS_AVAILABLE], and the
     * actual generate call then fails with `ErrorCode -101` from the SDK.
     * Callers should treat exceptions from [stream] / [complete] as the
     * authoritative "AICore not usable" signal.
     */
    suspend fun checkStatusCode(): Int = client().checkStatus()

    /** Human-readable mapping for error messages and the /health endpoint. */
    fun statusLabel(code: Int): String = when (code) {
        STATUS_AVAILABLE -> "available"
        STATUS_DOWNLOADABLE -> "downloadable"
        STATUS_DOWNLOADING -> "downloading"
        STATUS_UNAVAILABLE -> "unavailable"
        else -> "unknown($code)"
    }

    /**
     * Diagnostic: probe checkStatus() under every (releaseStage × preference)
     * combination so a caller can figure out which variant of Gemini Nano the
     * device actually has provisioned. Returns a map keyed by `"stage/pref"`
     * with status code values. Used by `GET /v1/aicore/status`.
     *
     * Each probe spins up a one-off client and doesn't touch the shared
     * [clientRef] — switching the cached client mid-session would surprise
     * anyone already mid-stream.
     */
    suspend fun probeAllConfigs(): Map<String, Int> {
        val combos = listOf(
            "stable/fast" to (ModelReleaseStage.STABLE to ModelPreference.FAST),
            "stable/full" to (ModelReleaseStage.STABLE to ModelPreference.FULL),
            "preview/fast" to (ModelReleaseStage.PREVIEW to ModelPreference.FAST),
            "preview/full" to (ModelReleaseStage.PREVIEW to ModelPreference.FULL),
        )
        val out = LinkedHashMap<String, Int>()
        for ((label, sp) in combos) {
            val (stage, pref) = sp
            val cfg = generationConfig {
                modelConfig = modelConfig {
                    releaseStage = stage
                    preference = pref
                }
            }
            out[label] = try {
                Generation.getClient(cfg).checkStatus()
            } catch (e: Throwable) {
                -1
            }
        }
        return out
    }

    /**
     * Stream Gemini Nano output for [prompt]. Yields cumulative text from
     * the first candidate on each emission — callers compute the delta vs
     * the prior chunk before forwarding to the SSE writer.
     *
     * The AICore SDK doesn't expose a system-prompt slot the way OpenAI
     * does. Callers flatten chat history into a single prompt string
     * (e.g. `"system: ...\n\nuser: ...\n\nassistant:"`) before invoking.
     */
    fun stream(
        prompt: String,
        temperature: Float? = null,
        topK: Int? = null,
        maxOutputTokens: Int? = null,
    ): Flow<String> = flow {
        val request = buildRequest(prompt, temperature, topK, maxOutputTokens)
        client().generateContentStream(request).collect { response ->
            val text = response.candidates.firstOrNull()?.text ?: return@collect
            emit(text)
        }
    }

    /** Non-streaming companion. Returns the first candidate's full text. */
    suspend fun complete(
        prompt: String,
        temperature: Float? = null,
        topK: Int? = null,
        maxOutputTokens: Int? = null,
    ): String {
        val request = buildRequest(prompt, temperature, topK, maxOutputTokens)
        val response = client().generateContent(request)
        return response.candidates.firstOrNull()?.text.orEmpty()
    }

    private fun buildRequest(
        prompt: String,
        temperature: Float?,
        topK: Int?,
        maxOutputTokens: Int?,
    ): GenerateContentRequest {
        val builder = GenerateContentRequest.Builder(TextPart(prompt))
        temperature?.let { builder.temperature = it }
        topK?.let { builder.topK = it }
        maxOutputTokens?.let { builder.maxOutputTokens = it }
        return builder.build()
    }
}
