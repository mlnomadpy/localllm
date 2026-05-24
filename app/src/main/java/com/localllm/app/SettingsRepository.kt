package com.localllm.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Reactive façade in front of a Preferences [DataStore].
 *
 * Each preference is exposed as a [StateFlow] so Compose can observe it without
 * re-reading from disk on every recomposition. Writes go through the
 * repository: it persists the value via [DataStore.edit] and the DataStore
 * flow re-emits which updates the backing [MutableStateFlow] (no manual
 * write-through needed — the flow we collect IS the source of truth).
 *
 * Migration from the legacy `SharedPreferences("settings")` is wired through
 * [SharedPreferencesMigration]; the first time DataStore is read on a device
 * with existing prefs, every key listed in [migrationKeys] is copied over.
 *
 * The synchronous [Settings] object is kept as a thin facade on top of this
 * repository for code paths that read from background threads
 * (e.g. [LLMServerService]).
 *
 * All clamping / sanitization for setters lives here so [Settings] and direct
 * repository users share the same validation.
 */
class SettingsRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val dataStore: DataStore<Preferences> = appContext.settingsDataStore

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Resolves once every preference flow has been seeded from disk (or the
     * migration completed). Tests may await this to guarantee deterministic
     * reads through the synchronous [Settings] facade.
     */
    private val firstFlushDeferred = CompletableDeferred<Unit>()
    val firstFlush: CompletableDeferred<Unit> get() = firstFlushDeferred

    /* ---------- backing flows (seeded to defaults, hydrated async) -------- */

    private val _port = MutableStateFlow(Settings.DEFAULT_PORT)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _maxTokens = MutableStateFlow(Settings.DEFAULT_MAX_TOKENS)
    val maxTokens: StateFlow<Int> = _maxTokens.asStateFlow()

    private val _temperature = MutableStateFlow(Settings.DEFAULT_TEMPERATURE)
    val temperature: StateFlow<Float> = _temperature.asStateFlow()

    private val _topK = MutableStateFlow(Settings.DEFAULT_TOP_K)
    val topK: StateFlow<Int> = _topK.asStateFlow()

    private val _bindLan = MutableStateFlow(false)
    val bindLan: StateFlow<Boolean> = _bindLan.asStateFlow()

    private val _startOnBoot = MutableStateFlow(true)
    val startOnBoot: StateFlow<Boolean> = _startOnBoot.asStateFlow()

    private val _autostart = MutableStateFlow(true)
    val autostart: StateFlow<Boolean> = _autostart.asStateFlow()

    private val _customModelUrls = MutableStateFlow<List<String>>(emptyList())
    val customModelUrls: StateFlow<List<String>> = _customModelUrls.asStateFlow()

    private val _requestTimeoutMs = MutableStateFlow(Settings.DEFAULT_REQUEST_TIMEOUT_MS)
    val requestTimeoutMs: StateFlow<Long> = _requestTimeoutMs.asStateFlow()

    private val _maxQueueDepth = MutableStateFlow(Settings.DEFAULT_MAX_QUEUE_DEPTH)
    val maxQueueDepth: StateFlow<Int> = _maxQueueDepth.asStateFlow()

    private val _maxPromptChars = MutableStateFlow(Settings.DEFAULT_MAX_PROMPT_CHARS)
    val maxPromptChars: StateFlow<Int> = _maxPromptChars.asStateFlow()

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _rateLimitPerSec = MutableStateFlow(Settings.DEFAULT_RATE_LIMIT_PER_SEC)
    val rateLimitPerSec: StateFlow<Double> = _rateLimitPerSec.asStateFlow()

    private val _rateLimitBurst = MutableStateFlow(Settings.DEFAULT_RATE_LIMIT_BURST)
    val rateLimitBurst: StateFlow<Double> = _rateLimitBurst.asStateFlow()

    private val _keepAwake = MutableStateFlow(true)
    val keepAwake: StateFlow<Boolean> = _keepAwake.asStateFlow()

    private val _idleEvictMs = MutableStateFlow(Settings.DEFAULT_IDLE_EVICT_MS)
    val idleEvictMs: StateFlow<Long> = _idleEvictMs.asStateFlow()

    private val _idleStopMs = MutableStateFlow(Settings.DEFAULT_IDLE_STOP_MS)
    val idleStopMs: StateFlow<Long> = _idleStopMs.asStateFlow()

    private val _selectedModelId = MutableStateFlow(Settings.DEFAULT_MODEL_ID)
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    private val _allowCors = MutableStateFlow(false)
    val allowCors: StateFlow<Boolean> = _allowCors.asStateFlow()

    init {
        // Seed all flows synchronously from a single first read of DataStore.
        // This avoids the racy "default for a tick, then real value" gap that
        // the synchronous Settings facade would otherwise expose to callers
        // immediately after process start.
        runBlocking {
            val prefs = dataStore.data.first()
            applySnapshot(prefs)
            firstFlushDeferred.complete(Unit)
        }
        // Continue observing for any external edits (e.g. via the prefs()
        // escape hatch on next process restart — not within the same process).
        scope.launch {
            dataStore.data.collect { applySnapshot(it) }
        }
    }

    private fun applySnapshot(p: Preferences) {
        _port.value = p[KEY_PORT] ?: Settings.DEFAULT_PORT
        _maxTokens.value = p[KEY_MAX_TOKENS] ?: Settings.DEFAULT_MAX_TOKENS
        _temperature.value = p[KEY_TEMPERATURE] ?: Settings.DEFAULT_TEMPERATURE
        _topK.value = p[KEY_TOP_K] ?: Settings.DEFAULT_TOP_K
        _bindLan.value = p[KEY_BIND_LAN] ?: false
        _startOnBoot.value = p[KEY_START_ON_BOOT] ?: true
        _autostart.value = p[KEY_AUTOSTART] ?: true
        _customModelUrls.value = parseUrls(p[KEY_CUSTOM_MODEL_URLS] ?: "")
        _requestTimeoutMs.value = p[KEY_REQUEST_TIMEOUT_MS] ?: Settings.DEFAULT_REQUEST_TIMEOUT_MS
        _maxQueueDepth.value = p[KEY_MAX_QUEUE_DEPTH] ?: Settings.DEFAULT_MAX_QUEUE_DEPTH
        _maxPromptChars.value = p[KEY_MAX_PROMPT_CHARS] ?: Settings.DEFAULT_MAX_PROMPT_CHARS
        _apiKey.value = p[KEY_API_KEY] ?: ""
        _rateLimitPerSec.value = p[KEY_RATE_LIMIT_PER_SEC] ?: Settings.DEFAULT_RATE_LIMIT_PER_SEC
        _rateLimitBurst.value = p[KEY_RATE_LIMIT_BURST] ?: Settings.DEFAULT_RATE_LIMIT_BURST
        _keepAwake.value = p[KEY_KEEP_AWAKE] ?: true
        _idleEvictMs.value = p[KEY_IDLE_EVICT_MS] ?: Settings.DEFAULT_IDLE_EVICT_MS
        _idleStopMs.value = p[KEY_IDLE_STOP_MS] ?: Settings.DEFAULT_IDLE_STOP_MS
        _selectedModelId.value = (p[KEY_SELECTED_MODEL_ID]?.takeIf { it.isNotBlank() }) ?: Settings.DEFAULT_MODEL_ID
        _allowCors.value = p[KEY_ALLOW_CORS] ?: false
    }

    /* ---------- writers (apply same clamping as the legacy Settings API) -- */

    private fun writeBlocking(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        runBlocking { dataStore.edit { block(it) } }
    }

    fun setPort(value: Int) {
        val safe = value.coerceIn(1024, 65535)
        writeBlocking { it[KEY_PORT] = safe }
        _port.value = safe
    }

    fun setMaxTokens(value: Int) {
        val safe = value.coerceIn(64, 8192)
        writeBlocking { it[KEY_MAX_TOKENS] = safe }
        _maxTokens.value = safe
    }

    fun setTemperature(value: Float) {
        val safe = value.coerceIn(0f, 2f)
        writeBlocking { it[KEY_TEMPERATURE] = safe }
        _temperature.value = safe
    }

    fun setTopK(value: Int) {
        val safe = value.coerceIn(1, 200)
        writeBlocking { it[KEY_TOP_K] = safe }
        _topK.value = safe
    }

    fun setBindLan(value: Boolean) {
        writeBlocking { it[KEY_BIND_LAN] = value }
        _bindLan.value = value
    }

    fun setStartOnBoot(value: Boolean) {
        writeBlocking { it[KEY_START_ON_BOOT] = value }
        _startOnBoot.value = value
    }

    fun setAutostart(value: Boolean) {
        writeBlocking { it[KEY_AUTOSTART] = value }
        _autostart.value = value
    }

    fun setCustomModelUrls(urls: List<String>) {
        val joined = urls.joinToString("\n")
        writeBlocking { it[KEY_CUSTOM_MODEL_URLS] = joined }
        _customModelUrls.value = parseUrls(joined)
    }

    fun setRequestTimeoutMs(value: Long) {
        val safe = value.coerceIn(5_000L, 600_000L)
        writeBlocking { it[KEY_REQUEST_TIMEOUT_MS] = safe }
        _requestTimeoutMs.value = safe
    }

    fun setMaxQueueDepth(value: Int) {
        val safe = value.coerceIn(1, 100)
        writeBlocking { it[KEY_MAX_QUEUE_DEPTH] = safe }
        _maxQueueDepth.value = safe
    }

    fun setMaxPromptChars(value: Int) {
        val safe = value.coerceIn(512, 2_000_000)
        writeBlocking { it[KEY_MAX_PROMPT_CHARS] = safe }
        _maxPromptChars.value = safe
    }

    fun setApiKey(value: String) {
        val safe = value.trim()
        writeBlocking { it[KEY_API_KEY] = safe }
        _apiKey.value = safe
    }

    fun setRateLimitPerSec(value: Double) {
        val safe = value.coerceIn(0.0, 1000.0)
        writeBlocking { it[KEY_RATE_LIMIT_PER_SEC] = safe }
        _rateLimitPerSec.value = safe
    }

    fun setRateLimitBurst(value: Double) {
        val safe = value.coerceIn(1.0, 10_000.0)
        writeBlocking { it[KEY_RATE_LIMIT_BURST] = safe }
        _rateLimitBurst.value = safe
    }

    fun setKeepAwake(value: Boolean) {
        writeBlocking { it[KEY_KEEP_AWAKE] = value }
        _keepAwake.value = value
    }

    fun setIdleEvictMs(value: Long) {
        val safe = value.coerceAtLeast(0L)
        writeBlocking { it[KEY_IDLE_EVICT_MS] = safe }
        _idleEvictMs.value = safe
    }

    fun setIdleStopMs(value: Long) {
        val safe = value.coerceAtLeast(0L)
        writeBlocking { it[KEY_IDLE_STOP_MS] = safe }
        _idleStopMs.value = safe
    }

    fun setSelectedModelId(value: String) {
        val safe = value.trim().ifEmpty { Settings.DEFAULT_MODEL_ID }
        writeBlocking { it[KEY_SELECTED_MODEL_ID] = safe }
        _selectedModelId.value = safe
    }

    fun setAllowCors(value: Boolean) {
        writeBlocking { it[KEY_ALLOW_CORS] = value }
        _allowCors.value = value
    }

    /** Convenience for `bindHost` resolution, mirroring the old facade. */
    fun bindHost(): String = if (_bindLan.value) "0.0.0.0" else "127.0.0.1"

    /** Exposed so tests / debug tooling can wipe state. */
    fun clearAll() {
        writeBlocking { it.clear() }
        _port.value = Settings.DEFAULT_PORT
        _maxTokens.value = Settings.DEFAULT_MAX_TOKENS
        _temperature.value = Settings.DEFAULT_TEMPERATURE
        _topK.value = Settings.DEFAULT_TOP_K
        _bindLan.value = false
        _startOnBoot.value = true
        _autostart.value = true
        _customModelUrls.value = emptyList()
        _requestTimeoutMs.value = Settings.DEFAULT_REQUEST_TIMEOUT_MS
        _maxQueueDepth.value = Settings.DEFAULT_MAX_QUEUE_DEPTH
        _maxPromptChars.value = Settings.DEFAULT_MAX_PROMPT_CHARS
        _apiKey.value = ""
        _rateLimitPerSec.value = Settings.DEFAULT_RATE_LIMIT_PER_SEC
        _rateLimitBurst.value = Settings.DEFAULT_RATE_LIMIT_BURST
        _keepAwake.value = true
        _idleEvictMs.value = Settings.DEFAULT_IDLE_EVICT_MS
        _idleStopMs.value = Settings.DEFAULT_IDLE_STOP_MS
        _selectedModelId.value = Settings.DEFAULT_MODEL_ID
        _allowCors.value = false
    }

    private fun parseUrls(raw: String): List<String> =
        raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        private const val DATASTORE_NAME = "settings"

        // Typed Preferences keys. The string names match the legacy SharedPreferences
        // keys verbatim so SharedPreferencesMigration picks them up automatically.
        private val KEY_PORT = intPreferencesKey(Settings.KEY_SERVER_PORT)
        private val KEY_MAX_TOKENS = intPreferencesKey(Settings.KEY_MAX_TOKENS)
        private val KEY_TEMPERATURE = floatPreferencesKey(Settings.KEY_TEMPERATURE)
        private val KEY_TOP_K = intPreferencesKey(Settings.KEY_TOP_K)
        private val KEY_BIND_LAN = booleanPreferencesKey(Settings.KEY_BIND_LAN)
        private val KEY_START_ON_BOOT = booleanPreferencesKey(Settings.KEY_START_ON_BOOT)
        private val KEY_AUTOSTART = booleanPreferencesKey(Settings.KEY_AUTOSTART)
        private val KEY_CUSTOM_MODEL_URLS = stringPreferencesKey(Settings.KEY_CUSTOM_MODEL_URLS)
        private val KEY_REQUEST_TIMEOUT_MS = longPreferencesKey(Settings.KEY_REQUEST_TIMEOUT_MS)
        private val KEY_MAX_QUEUE_DEPTH = intPreferencesKey(Settings.KEY_MAX_QUEUE_DEPTH)
        private val KEY_MAX_PROMPT_CHARS = intPreferencesKey(Settings.KEY_MAX_PROMPT_CHARS)
        private val KEY_API_KEY = stringPreferencesKey(Settings.KEY_API_KEY)
        private val KEY_RATE_LIMIT_PER_SEC = doublePreferencesKey(Settings.KEY_RATE_LIMIT_PER_SEC)
        private val KEY_RATE_LIMIT_BURST = doublePreferencesKey(Settings.KEY_RATE_LIMIT_BURST)
        private val KEY_KEEP_AWAKE = booleanPreferencesKey(Settings.KEY_KEEP_AWAKE)
        private val KEY_IDLE_EVICT_MS = longPreferencesKey(Settings.KEY_IDLE_EVICT_MS)
        private val KEY_IDLE_STOP_MS = longPreferencesKey(Settings.KEY_IDLE_STOP_MS)
        private val KEY_SELECTED_MODEL_ID = stringPreferencesKey(Settings.KEY_SELECTED_MODEL_ID)
        private val KEY_ALLOW_CORS = booleanPreferencesKey(Settings.KEY_ALLOW_CORS)

        /**
         * The DataStore lives at file `settings.preferences_pb`. The
         * [SharedPreferencesMigration] copies values out of the legacy
         * `settings` SharedPreferences on first DataStore read; once the
         * migration succeeds, the old XML file is deleted automatically.
         */
        private val Context.settingsDataStore by preferencesDataStore(
            name = DATASTORE_NAME,
            produceMigrations = { ctx ->
                listOf(SharedPreferencesMigration(ctx, DATASTORE_NAME))
            }
        )

        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun get(context: Context): SettingsRepository {
            // Application context guards against Activity leaks: the singleton
            // outlives any individual Activity.
            val appCtx = context.applicationContext
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(appCtx).also { INSTANCE = it }
            }
        }

        /** Visible for testing — drop the singleton so the next [get] re-reads from disk. */
        internal fun resetForTesting() {
            synchronized(this) {
                INSTANCE?.scope?.coroutineContext?.get(Job)?.cancel()
                INSTANCE = null
            }
        }

        /**
         * Visible for testing — synchronously clears the DataStore-backed
         * settings file. Pair with [resetForTesting] so the next [get] re-reads
         * empty state. (Cannot use the typical [DataStore.edit] { clear() }
         * because we want this callable from `@Before` without test plumbing.)
         */
        internal fun wipeForTesting(context: Context) {
            val ds = context.applicationContext.settingsDataStore
            runBlocking { ds.edit { it.clear() } }
        }
    }
}
