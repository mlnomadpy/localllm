package com.localllm.app.inference

/**
 * Thrown by [EngineRegistry.ensureAiCoreReady] when AICore (Gemini Nano)
 * cannot serve a request. Carries the SDK status code so callers can build
 * a structured error response (HTTP status, machine-readable `code`,
 * actionable next steps) without re-probing.
 *
 * [statusCode] is one of [com.localllm.app.inference.aicore.AICoreEngine]'s
 * STATUS_* constants. [probeError] is non-null only when the SDK throw
 * occurred during `checkStatus()` (typically ErrorCode -101 — AICore service
 * not installed) — in that case we synthesize STATUS_UNAVAILABLE with the
 * underlying throwable attached.
 */
class AiCoreNotReadyException(
    val statusCode: Int,
    val probeError: Throwable? = null,
) : Exception("AICore not ready (status=$statusCode)", probeError)
