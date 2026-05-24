package com.localllm.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralized typed access to user preferences for the LocalLLM server.
 *
 * Single source of truth — both the UI (Settings tab) and the LLMServerService
 * read from here so they can never drift apart.
 *
 * As of the StateFlow refactor, this object is a **thin synchronous facade**
 * over [SettingsRepository]. The repository owns the in-memory
 * `MutableStateFlow`s and [SharedPreferences] for persistence; the legacy
 * `Settings.xxx(context)` / `Settings.setXxx(context, value)` API still works
 * verbatim for callers like [LLMServerService] and [BootReceiver] that run on
 * background threads and don't care about reactivity.
 */
object Settings {
    private const val PREFS = "settings"

    const val KEY_SERVER_PORT = "server_port"
    const val KEY_MAX_TOKENS = "max_tokens"
    const val KEY_TEMPERATURE = "temperature"
    const val KEY_TOP_K = "top_k"
    const val KEY_BIND_LAN = "bind_lan"
    const val KEY_START_ON_BOOT = "start_on_boot"
    const val KEY_AUTOSTART = "autostart_on_launch"
    const val KEY_CUSTOM_MODEL_URLS = "custom_model_urls"

    // Limits / safety
    const val KEY_REQUEST_TIMEOUT_MS = "request_timeout_ms"
    const val KEY_MAX_QUEUE_DEPTH = "max_queue_depth"
    const val KEY_MAX_PROMPT_CHARS = "max_prompt_chars"
    const val KEY_API_KEY = "api_key"
    const val KEY_KEEP_AWAKE = "keep_awake"
    // Per-client rate limit (token bucket keyed by User-Agent). 0 disables.
    const val KEY_RATE_LIMIT_PER_SEC = "rate_limit_per_sec"
    const val KEY_RATE_LIMIT_BURST = "rate_limit_burst"

    // Background efficiency
    const val KEY_IDLE_EVICT_MS = "idle_evict_ms"
    const val KEY_IDLE_STOP_MS = "idle_stop_ms"

    // The currently-selected model id (persisted across launches). The chat
    // handler also uses this as the fallback when a `/v1/chat/completions`
    // request omits the `model` field. Defaults to AICore (Gemini Nano).
    const val KEY_SELECTED_MODEL_ID = "selected_model_id"
    const val DEFAULT_MODEL_ID = "gemini-nano-aicore"

    // CORS: when off (default) the server responds without CORS headers — safe
    // because only non-browser clients (native apps, curl) can use it. When on,
    // `anyHost()` is installed so browser-based clients can call the API.
    const val KEY_ALLOW_CORS = "allow_cors"

    const val DEFAULT_PORT = 8080
    const val DEFAULT_MAX_TOKENS = 1024
    const val DEFAULT_TEMPERATURE = 0.8f
    const val DEFAULT_TOP_K = 40
    const val DEFAULT_REQUEST_TIMEOUT_MS = 120_000L
    const val DEFAULT_MAX_QUEUE_DEPTH = 8
    const val DEFAULT_MAX_PROMPT_CHARS = 100_000
    /** Per-client requests per second (0 disables the limiter entirely). */
    const val DEFAULT_RATE_LIMIT_PER_SEC = 0.0
    /** Burst capacity per client when the limiter is enabled. */
    const val DEFAULT_RATE_LIMIT_BURST = 10.0
    const val DEFAULT_IDLE_EVICT_MS = 5L * 60_000L   // 5 minutes; 0 disables
    const val DEFAULT_IDLE_STOP_MS = 0L               // disabled by default

    /**
     * Returns the raw [SharedPreferences]. This is the only documented escape
     * hatch for callers that need direct prefs access (e.g. tests that wipe
     * state in `@Before`). Because direct edits via this path bypass the
     * repository's in-memory flows AND the new DataStore-backed persistence,
     * we reset the repository singleton AND wipe the DataStore here — the
     * next read will re-seed the flows. Any direct SharedPreferences writes
     * made via the returned handle within the same process will NOT be
     * observed (DataStore's [androidx.datastore.preferences.SharedPreferencesMigration]
     * only runs once); for test fixtures, write through the repo instead.
     */
    fun prefs(context: Context): SharedPreferences {
        // Wipe DataStore first so the freshly-built repo sees empty state.
        SettingsRepository.wipeForTesting(context)
        SettingsRepository.resetForTesting()
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun repo(context: Context): SettingsRepository = SettingsRepository.get(context)

    fun port(context: Context): Int = repo(context).port.value
    fun setPort(context: Context, value: Int) = repo(context).setPort(value)

    fun maxTokens(context: Context): Int = repo(context).maxTokens.value
    fun setMaxTokens(context: Context, value: Int) = repo(context).setMaxTokens(value)

    fun temperature(context: Context): Float = repo(context).temperature.value
    fun setTemperature(context: Context, value: Float) = repo(context).setTemperature(value)

    fun topK(context: Context): Int = repo(context).topK.value
    fun setTopK(context: Context, value: Int) = repo(context).setTopK(value)

    fun bindLan(context: Context): Boolean = repo(context).bindLan.value
    fun setBindLan(context: Context, value: Boolean) = repo(context).setBindLan(value)

    fun startOnBoot(context: Context): Boolean = repo(context).startOnBoot.value
    fun setStartOnBoot(context: Context, value: Boolean) = repo(context).setStartOnBoot(value)

    fun autostart(context: Context): Boolean = repo(context).autostart.value
    fun setAutostart(context: Context, value: Boolean) = repo(context).setAutostart(value)

    fun customModelUrls(context: Context): List<String> = repo(context).customModelUrls.value

    fun setCustomModelUrls(context: Context, urls: List<String>) =
        repo(context).setCustomModelUrls(urls)

    fun bindHost(context: Context): String = if (bindLan(context)) "0.0.0.0" else "127.0.0.1"

    fun requestTimeoutMs(context: Context): Long = repo(context).requestTimeoutMs.value
    fun setRequestTimeoutMs(context: Context, value: Long) =
        repo(context).setRequestTimeoutMs(value)

    fun maxQueueDepth(context: Context): Int = repo(context).maxQueueDepth.value
    fun setMaxQueueDepth(context: Context, value: Int) = repo(context).setMaxQueueDepth(value)

    fun maxPromptChars(context: Context): Int = repo(context).maxPromptChars.value
    fun setMaxPromptChars(context: Context, value: Int) = repo(context).setMaxPromptChars(value)

    fun apiKey(context: Context): String = repo(context).apiKey.value
    fun setApiKey(context: Context, value: String) = repo(context).setApiKey(value)

    fun rateLimitPerSec(context: Context): Double = repo(context).rateLimitPerSec.value
    fun setRateLimitPerSec(context: Context, value: Double) =
        repo(context).setRateLimitPerSec(value)

    fun rateLimitBurst(context: Context): Double = repo(context).rateLimitBurst.value
    fun setRateLimitBurst(context: Context, value: Double) =
        repo(context).setRateLimitBurst(value)

    fun keepAwake(context: Context): Boolean = repo(context).keepAwake.value
    fun setKeepAwake(context: Context, value: Boolean) = repo(context).setKeepAwake(value)

    fun idleEvictMs(context: Context): Long = repo(context).idleEvictMs.value
    fun setIdleEvictMs(context: Context, value: Long) = repo(context).setIdleEvictMs(value)

    fun idleStopMs(context: Context): Long = repo(context).idleStopMs.value
    fun setIdleStopMs(context: Context, value: Long) = repo(context).setIdleStopMs(value)

    fun selectedModelId(context: Context): String = repo(context).selectedModelId.value
    fun setSelectedModelId(context: Context, value: String) =
        repo(context).setSelectedModelId(value)

    fun allowCors(context: Context): Boolean = repo(context).allowCors.value
    fun setAllowCors(context: Context, value: Boolean) = repo(context).setAllowCors(value)
}
