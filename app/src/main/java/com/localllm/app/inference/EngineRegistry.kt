package com.localllm.app.inference

import android.content.Context
import android.util.LruCache
import com.google.ai.edge.litertlm.Conversation
import com.localllm.app.AVAILABLE_MODELS
import com.localllm.app.Backend
import com.localllm.app.LogManager
import com.localllm.app.ModelInfo
import com.localllm.app.findModelInfo
import com.localllm.app.inference.aicore.AICoreEngine
import com.localllm.app.inference.aicore.AICoreEngineAdapter
import com.localllm.app.inference.litert.LiteRtEngine
import com.localllm.app.inference.litert.LiteRtEngineBuilder
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Engine cache + factory. Replaces the AUTO chain that used to live in
 * `LLMServerService.getOrCreateEngine`. The catalog declares the backend
 * per-model (see [Backend]); this registry honors that declaration and
 * either returns a cached engine, builds a fresh one, or throws a
 * structured error if the requested combination is unsupported on the
 * current device.
 *
 * No fallback. If `Backend.LITERT_NPU` is requested but the vendor delegate
 * isn't reachable, the underlying [com.google.ai.edge.litertlm.Engine] init
 * throws and the error propagates. If `Backend.AICORE` is requested but the
 * status isn't AVAILABLE, [acquire] throws an [IllegalStateException]
 * before ever calling the AICore SDK.
 *
 * LiteRT engine cache is bounded at 2 — each model takes 1–2 GB resident
 * and Android LMK will start punishing us beyond that. AICore is a single
 * shared adapter (no resident weight cost on our side).
 */
class EngineRegistry(
    private val appContext: Context,
    private val maxResidentLiteRt: Int = 2,
) {

    /**
     * One step in the engine-init chain. Kept on the registry side so
     * `/health` can surface per-engine init metadata even now that there is
     * no AUTO fallback — each cached engine records the single attempt that
     * built it (label = backend name; result = "ok").
     */
    data class BackendAttempt(
        val backend: String,
        val result: String,
        val durationMs: Long,
    )

    /** Public view of a cached engine entry — surfaced via /health. */
    data class CachedEngineInfo(
        val cacheKey: String,
        val backend: String,
        val attempts: List<BackendAttempt>,
    )

    private data class LiteRtCacheEntry(
        val engine: LiteRtEngine,
        val attempts: List<BackendAttempt>,
    )

    /**
     * LiteRT-LM engine LRU. Keyed by `model_maxTokens_backend` because
     * LiteRT-LM's `EngineConfig.maxNumTokens` is the *total* KV-cache budget
     * (input + output); reusing an engine built with a larger budget for a
     * request that asked for less would let the model overgenerate.
     *
     * Conversations attached to an evicted engine MUST be torn down first —
     * a conversation outliving its parent engine is undefined behavior on
     * the native side. The [onLiteRtEvicted] callback handles that
     * coordination; the chat route registers it before serving traffic.
     */
    private val liteRtEngines = object : LruCache<String, LiteRtCacheEntry>(maxResidentLiteRt) {
        override fun entryRemoved(
            evicted: Boolean,
            key: String?,
            oldValue: LiteRtCacheEntry?,
            newValue: LiteRtCacheEntry?,
        ) {
            if (oldValue == null || key == null) return
            // Notify listeners FIRST so they can close conversations
            // referencing this engine before we tear the engine down.
            if (oldValue !== newValue) {
                try { onLiteRtEvicted?.invoke(key) } catch (_: Throwable) {}
                try {
                    oldValue.engine.close()
                    if (evicted) LogManager.i("EngineRegistry", "Evicted engine: $key")
                } catch (e: Exception) {
                    LogManager.e("EngineRegistry", "Error closing evicted engine $key", e)
                }
            }
        }
    }

    /** Lazy singleton AICore adapter — created on first request. */
    @Volatile private var aiCoreRef: AICoreEngineAdapter? = null

    /**
     * Per-engine conversation lookup. Filled by the chat route; consumed
     * by [purgeConversationsForEngine] so the chat route's session cleanup
     * stays in one place.
     */
    val activeConversations = ConcurrentHashMap<String, Conversation>()

    /**
     * Listener invoked when a LiteRT engine entry is removed (eviction or
     * explicit drop). Registered by the chat route so it can flush its
     * conversation cache before the engine is closed.
     */
    @Volatile var onLiteRtEvicted: ((cacheKey: String) -> Unit)? = null

    /** Resolve a model id to its catalog entry, or treat as side-loaded LiteRT-CPU. */
    fun resolveModelInfo(modelId: String): ModelInfo {
        findModelInfo(modelId)?.let { return it }
        // Side-loaded `.litertlm` files (imported via the Models tab file
        // picker) aren't in the catalog. Treat them as portable CPU
        // models — that's the safe default since we don't know whether the
        // bundle was compiled for GPU / NPU.
        return ModelInfo(
            id = modelId,
            name = modelId,
            description = "Side-loaded LiteRT-LM model",
            url = "",
            filename = "$modelId.litertlm",
            backend = Backend.LITERT_CPU,
        )
    }

    /**
     * Get-or-build an engine for [modelId] with the requested KV budget. The
     * backend is whatever the catalog declares for this model — see
     * [Backend]. No fallback; init failures propagate verbatim.
     *
     * For AICore models, [maxTokens] is irrelevant (AICore manages its own
     * KV budget); the returned [AcquiredEngine] is just the singleton adapter.
     */
    fun acquire(modelId: String, maxTokens: Int?): AcquiredEngine {
        val info = resolveModelInfo(modelId)
        return when (info.backend) {
            Backend.AICORE -> acquireAiCore()
            Backend.LITERT_CPU,
            Backend.LITERT_GPU,
            Backend.LITERT_NPU -> acquireLiteRt(info, maxTokens)
        }
    }

    private fun acquireAiCore(): AcquiredEngine {
        // Authoritative liveness check — surfaces a clean error to the user
        // before we hand a useless engine to the chat route.
        // The actual checkStatusCode() is suspend and must run from the
        // call site; this method is non-suspend so we defer the check to
        // [ensureAiCoreReady] called by the route.
        val adapter = aiCoreRef ?: synchronized(this) {
            aiCoreRef ?: AICoreEngineAdapter().also { aiCoreRef = it }
        }
        return AcquiredEngine.AiCore(adapter)
    }

    /**
     * Pre-flight check for AICore availability. Throws [AiCoreNotReadyException]
     * carrying the SDK status code so the chat route can build a structured
     * [com.localllm.app.RichErrorResponse] envelope without re-probing.
     *
     * Also catches probe failures (e.g. ErrorCode -101 when AICore isn't
     * installed at all) and surfaces them as UNAVAILABLE with the underlying
     * throwable attached.
     */
    suspend fun ensureAiCoreReady() {
        val status = try {
            AICoreEngine.checkStatusCode()
        } catch (probeErr: Throwable) {
            throw AiCoreNotReadyException(AICoreEngine.STATUS_UNAVAILABLE, probeErr)
        }
        if (status != AICoreEngine.STATUS_AVAILABLE) {
            throw AiCoreNotReadyException(status)
        }
    }

    private fun acquireLiteRt(info: ModelInfo, maxTokens: Int?): AcquiredEngine.LiteRt {
        val cacheKey = "${info.id}_${maxTokens ?: "model"}_${info.backend.name}"
        liteRtEngines.get(cacheKey)?.let {
            return AcquiredEngine.LiteRt(it.engine, cacheKey, it.attempts)
        }

        val modelFile = File(appContext.getExternalFilesDir(null), info.filename.ifBlank { "${info.id}.litertlm" })
        if (!modelFile.exists()) {
            throw IllegalStateException("Model file not found: ${modelFile.name}. Download or import it first.")
        }

        // Catalog-declared SoC check for NPU models: don't even try to init
        // if the device clearly doesn't match the compiled-for SoC. Saves a
        // cryptic native error in /health.
        if (info.backend == Backend.LITERT_NPU) {
            val marker = info.requiredSocMarker
            if (marker != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val soc = android.os.Build.SOC_MODEL?.lowercase().orEmpty()
                if (!soc.contains(marker.lowercase())) {
                    throw IllegalStateException(
                        "Model '${info.id}' is compiled for SoC marker '$marker' but this device reports '$soc'. " +
                        "Pick a model that matches your device's chipset."
                    )
                }
            }
        }

        val nativeLibDir = appContext.applicationInfo.nativeLibraryDir.orEmpty()
        val t0 = System.nanoTime()
        val native = try {
            LiteRtEngineBuilder.build(modelFile, maxTokens, info.backend, nativeLibDir)
        } catch (e: Exception) {
            val attempt = BackendAttempt(
                info.backend.name,
                "failed: ${e.message ?: e.javaClass.simpleName}",
                (System.nanoTime() - t0) / 1_000_000,
            )
            throw IllegalStateException(
                "Failed to initialize ${info.backend.name} engine for ${info.id}: ${e.message ?: e.javaClass.simpleName} (${attempt.durationMs}ms)",
                e,
            )
        }
        val attempt = BackendAttempt(info.backend.name, "ok", (System.nanoTime() - t0) / 1_000_000)
        val wrapped = LiteRtEngine(info.id, info.backend, native, cacheKey)
        val entry = LiteRtCacheEntry(wrapped, listOf(attempt))
        try {
            liteRtEngines.put(cacheKey, entry)
            LogManager.i("EngineRegistry", "Engine $cacheKey ready (${info.backend.name}, ${attempt.durationMs}ms)")
        } catch (e: Exception) {
            try { wrapped.close() } catch (_: Exception) {}
            throw e
        }
        return AcquiredEngine.LiteRt(wrapped, cacheKey, listOf(attempt))
    }

    /** Snapshot for /health. */
    fun snapshot(): List<CachedEngineInfo> =
        liteRtEngines.snapshot().map { (key, v) ->
            CachedEngineInfo(key, v.engine.backend.name, v.attempts)
        }

    fun engineCount(): Int = liteRtEngines.size()

    /** Idle eviction path — drops all cached LiteRT engines. */
    fun evictAllLiteRt(): Int {
        val n = liteRtEngines.size()
        liteRtEngines.evictAll()
        return n
    }

    /** Memory-pressure path — keep at most [keep] cached. */
    fun trimLiteRtTo(keep: Int): Int {
        val before = liteRtEngines.size()
        liteRtEngines.trimToSize(keep)
        return before - liteRtEngines.size()
    }

    /** Drop a specific entry (used when an engine is found to be wedged). */
    fun dropLiteRt(cacheKey: String) {
        liteRtEngines.remove(cacheKey)
    }

    /** Acquired-engine union. Hides the LiteRT-vs-AICore split from callers. */
    sealed class AcquiredEngine {
        data class LiteRt(
            val engine: LiteRtEngine,
            val cacheKey: String,
            val attempts: List<BackendAttempt>,
        ) : AcquiredEngine()

        data class AiCore(val engine: AICoreEngineAdapter) : AcquiredEngine()
    }
}
